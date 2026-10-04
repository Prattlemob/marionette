package com.prattlemob.marionette.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ConnectionStatus;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.GameEvent;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ProtocolSession;
import com.prattlemob.marionette.bridge.protocol.Role;
import com.prattlemob.marionette.config.LogCategory;
import com.prattlemob.marionette.config.MarionetteLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;
import io.netty.channel.socket.DuplexChannel;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.concurrent.TimeUnit;

/** Per-connection bounded queues. No Minecraft access; safe from the tick thread. */
public final class AgentConnection {
    private static final Logger LOG = LoggerFactory.getLogger(AgentConnection.class);
    static final int COMMAND_LIMIT = 128;
    static final int OUTBOUND_BYTES = 256 * 1024;
    static final int FRAME_BYTES = 128 * 1024;
    /** Events written but not yet accepted by the socket; never coalesced. */
    static final int EVENT_LIMIT = 1024;
    private final Channel channel;
    private final ProtocolSession session;
    private final Runnable onLoss;
    private final ArrayBlockingQueue<BridgeServer.Received> commands = new ArrayBlockingQueue<>(COMMAND_LIMIT);
    private final AtomicReference<BridgeServer.Received> release = new AtomicReference<>();
    private final AtomicReference<String> pendingObservation = new AtomicReference<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicBoolean lossNotified = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong outboundBytes = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    // Diagnostics (status, HUD): frames written and frames that will never be delivered.
    private final AtomicLong observationsSent = new AtomicLong();
    private final AtomicLong observationsDropped = new AtomicLong();
    private volatile long pingSentNanos; // 0 = no ping awaiting its pong
    private volatile long rttNanos = -1;
    private volatile long commandLatencyNanos = -1;
    private long rateWindowStart; // client thread
    private long rateWindowBase; // client thread
    private volatile double observationRate;
    private final AtomicInteger eventsInFlight = new AtomicInteger();
    private long eventSeq; // guarded by this
    private volatile boolean events;
    private volatile Role role;
    private volatile boolean ready;
    private volatile Set<String> sections = Set.of("player");
    private volatile long lastPongNanos;
    private volatile long admittedNanos;
    private volatile Integer rateDivisorOverride;
    private volatile String agent;

    AgentConnection(Channel channel, ProtocolSession session, Runnable onLoss) {
        this.channel = channel;
        this.session = session;
        this.onLoss = onLoss;
    }

    public Set<String> sections() { return sections; }
    public void setSections(Set<String> sections) { this.sections = Set.copyOf(sections); }
    public boolean events() { return events; }
    public void setEvents(boolean events) { this.events = events; }
    public Role role() { return role; }
    /** The hello display name, or null. */
    public String agent() { return agent; }
    void setAgent(String agent) { this.agent = agent; }
    void setRole(Role role) { this.role = role; admittedNanos = System.nanoTime(); }
    public void setRateDivisor(Integer divisor) { rateDivisorOverride = divisor; }
    public int effectiveDivisor(int defaultDivisor) {
        Integer override = rateDivisorOverride;
        return override != null ? override : defaultDivisor;
    }

    synchronized boolean enqueue(AgentCommand command) {
        if (closing.get()) return false;
        if (command instanceof AgentCommand.Release) {
            generation.incrementAndGet();
            commands.clear();
            release.set(new BridgeServer.Received(command, this, generation.get(), System.nanoTime()));
            return true;
        }
        return commands.offer(new BridgeServer.Received(command, this, generation.get(), System.nanoTime()));
    }
    synchronized BridgeServer.Received pollRelease() { return release.getAndSet(null); }
    synchronized BridgeServer.Received pollCommand() { return commands.poll(); }
    int queuedCommands() { return commands.size(); }
    synchronized boolean valid(long epoch) { return ready() && epoch == generation.get(); }

    /** Reserve bytes before scheduling a Netty task, not merely after it reaches the socket. */
    private boolean writeText(String json) {
        return writeText(json, null);
    }

    private boolean writeText(String json, Runnable written) {
        int bytes = json.getBytes(StandardCharsets.UTF_8).length + 64;
        if (bytes > FRAME_BYTES || closing.get() || !channel.isActive()) return false;
        long reserved = outboundBytes.addAndGet(bytes);
        if (reserved > OUTBOUND_BYTES) {
            outboundBytes.addAndGet(-bytes);
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(json)).addListener(future -> {
            outboundBytes.addAndGet(-bytes);
            if (written != null) written.run();
            if (!future.isSuccess()) close(1011, "write failed");
            else flushPending();
        });
        return true;
    }

    /** Whether one reply fits the per-frame reply limit (it would otherwise close the connection). */
    public static boolean fitsFrame(String json) {
        return json.length() <= FRAME_BYTES && json.getBytes(StandardCharsets.UTF_8).length + 64 <= FRAME_BYTES;
    }

    /** Includes handshake/protocol errors, so pre-hello output is bounded too. */
    public void sendReliable(String json) {
        if (!closing.get() && !writeText(json)) close(1013, "overloaded");
    }

    /**
     * Invalidate immediately; flushing a courtesy close must never delay safety.
     * After the close frame, only the output is shut down and later input is
     * discarded until the peer closes or the 250 ms flush bound expires. Closing
     * a socket that still receives input (a flooding peer) resets it, and a
     * client whose next write fails may drop the error and close frames it holds.
     */
    void close(int code, String reason) {
        if (!closing.compareAndSet(false, true)) return;
        if (role != null) {
            MarionetteLog.normal(LogCategory.BRIDGE, "Closing role={} agent={} code={} reason={}",
                    role.wire(), agent, code, reason);
        }
        invalidate();
        channel.eventLoop().execute(() -> {
            session.close();
            if (channel.isWritable()) {
                channel.writeAndFlush(new CloseWebSocketFrame(code, reason)).addListener(future -> {
                    if (future.isSuccess() && channel instanceof DuplexChannel duplex) duplex.shutdownOutput();
                    else channel.close();
                });
                channel.eventLoop().schedule(() -> { channel.close(); }, 250, TimeUnit.MILLISECONDS);
            } else channel.close();
        });
    }

    /** Whether this connection is closing; its remaining input is discarded. */
    boolean closing() { return closing.get(); }

    synchronized void invalidate() {
        ready = false;
        generation.incrementAndGet();
        commands.clear();
        release.set(null);
        pendingObservation.set(null);
        if (lossNotified.compareAndSet(false, true)) onLoss.run();
    }

    /**
     * Deliver one event to a subscribed, ready session (client thread). Events
     * never coalesce: they bypass the writability gate and use their own
     * in-flight bound plus the shared reliable byte budget. Exceeding either
     * closes this connection (1013 "event overflow") instead of skipping a
     * sequence number. A stashed observation older than the event is dropped
     * so it can never be delivered after it.
     *
     * @return the sequence number written, or 0 if not delivered
     */
    public synchronized long sendEvent(GameEvent event) {
        if (!ready() || !events) return 0;
        if (pendingObservation.getAndSet(null) != null) {
            coalesced.incrementAndGet();
            observationsDropped.incrementAndGet();
        }
        String json = Messages.event(event, eventSeq + 1);
        // Count before writing: the completion listener may run first.
        if (eventsInFlight.incrementAndGet() > EVENT_LIMIT
                || !writeText(json, eventsInFlight::decrementAndGet)) {
            eventsInFlight.decrementAndGet();
            overflowEvents();
            return 0;
        }
        return ++eventSeq;
    }

    private void overflowEvents() {
        if (closing.get()) return;
        LOG.warn("Bridge {} event overflow after seq {} ({} in flight); closing 1013",
                role, eventSeq, eventsInFlight.get());
        writeText(Messages.error(ErrorCode.OVERLOADED, "event queue overflow", null, null));
        close(1013, "event overflow");
    }

    long eventSeq() { synchronized (this) { return eventSeq; } }
    int eventsInFlight() { return eventsInFlight.get(); }

    public long coalescedObservations() { return coalesced.get(); }
    long outboundBytes() { return outboundBytes.get(); }
    synchronized void sendObservation(String json) {
        if (!ready()) return;
        if (json.length() > FRAME_BYTES || json.getBytes(StandardCharsets.UTF_8).length + 64 > FRAME_BYTES) {
            coalesced.incrementAndGet();
            observationsDropped.incrementAndGet();
            return;
        }
        // Serialize stash consumption and fast writes: an older stash must never
        // be flushed after a newer observation, even on synchronous completion.
        if (pendingObservation.getAndSet(null) != null) observationsDropped.incrementAndGet();
        if (channel.isWritable() && writeText(json)) {
            observationsSent.incrementAndGet();
        } else {
            pendingObservation.set(json);
            coalesced.incrementAndGet();
            flushPending();
        }
    }
    void flushPending() {
        if (!ready() || !channel.isWritable() || !flushScheduled.compareAndSet(false, true)) return;
        channel.eventLoop().execute(() -> {
            synchronized (AgentConnection.this) {
                boolean wrote = false;
                try {
                    if (!ready() || !channel.isWritable()) return;
                    String json = pendingObservation.getAndSet(null);
                    if (json != null && !(wrote = writeText(json))) {
                        if (!pendingObservation.compareAndSet(null, json)) observationsDropped.incrementAndGet();
                        coalesced.incrementAndGet();
                    } else if (wrote) {
                        observationsSent.incrementAndGet();
                    }
                } finally {
                    flushScheduled.set(false);
                }
                if (wrote && pendingObservation.get() != null && channel.isWritable()) flushPending();
            }
        });
    }
    void recordPong() {
        long now = System.nanoTime();
        lastPongNanos = now;
        long sent = pingSentNanos;
        if (sent != 0) {
            rttNanos = now - sent;
            pingSentNanos = 0;
        }
    }

    /** A liveness ping is being written; the next pong measures its round trip. */
    void recordPing(long nowNanos) {
        if (pingSentNanos == 0) pingSentNanos = nowNanos;
    }

    /** One command from this connection was applied on the client tick. */
    public void recordApplied(long receivedNanos, long nowNanos) {
        commandLatencyNanos = Math.max(0, nowNanos - receivedNanos);
    }

    /**
     * Advance the one-second observation-rate window (client thread, each tick).
     * The rate is frames written per second over the latest completed window.
     */
    void sampleRate(long nowNanos) {
        long sent = observationsSent.get();
        if (rateWindowStart == 0) {
            rateWindowStart = nowNanos;
            rateWindowBase = sent;
        } else if (nowNanos - rateWindowStart >= 1_000_000_000L) {
            observationRate = (sent - rateWindowBase) * 1e9 / (nowNanos - rateWindowStart);
            rateWindowStart = nowNanos;
            rateWindowBase = sent;
        }
    }

    public long observationsSent() { return observationsSent.get(); }
    public long observationsDropped() { return observationsDropped.get(); }

    /** This connection's diagnostics counters now (protocol/v1.md, status_result). */
    public ConnectionStatus status(int defaultDivisor, long nowNanos) {
        long rtt = rttNanos;
        long latency = commandLatencyNanos;
        Role current = role;
        return new ConnectionStatus(current == null ? null : current.wire(), agent,
                admittedNanos == 0 ? 0 : (nowNanos - admittedNanos) / 1_000_000, observationRate,
                observationsSent.get(), observationsDropped.get(), eventSeq(), queuedCommands(),
                rtt < 0 ? null : rtt / 1e6, latency < 0 ? null : latency / 1e6,
                effectiveDivisor(defaultDivisor), List.copyOf(sections), events);
    }
    long lastPongNanos() { return lastPongNanos; }
    boolean pongExpired(long now, long timeoutNanos) {
        return ready() && now - Math.max(admittedNanos, lastPongNanos) >= timeoutNanos;
    }
    public boolean ready() { return ready && !closing.get(); }
    void setReady(boolean ready) { this.ready = ready && !closing.get(); }
    Channel channel() { return channel; }
    ProtocolSession session() { return session; }
}
