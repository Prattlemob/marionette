package com.prattlemob.marionette.bridge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.prattlemob.marionette.bridge.protocol.ProtocolSession;
import com.prattlemob.marionette.bridge.protocol.Role;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * One agent connection: channel, protocol session, and every piece of
 * per-connection stream state (latest-wins observation stash, coalesced
 * counter, pong timestamp, rate-divisor override). Created by the
 * transport at WebSocket handshake completion; it joins the controller
 * slot or observer list only when its session admits a role (M2.4).
 * Tick-thread-facing methods are public; event-loop internals are
 * package-private. Free of Minecraft imports.
 */
public final class AgentConnection {
    private final Channel channel;
    private final ProtocolSession session;
    private final AtomicReference<String> pendingObservation = new AtomicReference<>();
    private final AtomicLong coalesced = new AtomicLong();
    private volatile Role role;
    private volatile boolean ready;
    private volatile long lastPongNanos;
    private volatile Integer rateDivisorOverride;

    AgentConnection(Channel channel, ProtocolSession session) {
        this.channel = channel;
        this.session = session;
    }

    /** The admitted role; null until hello completes. Safe from any thread. */
    public Role role() {
        return role;
    }

    void setRole(Role role) {
        this.role = role;
    }

    /** Per-session cadence override from configure; null restores the config default. */
    public void setRateDivisor(Integer divisor) {
        this.rateDivisorOverride = divisor;
    }

    /** This connection's observation divisor: the override if set, else the config default. */
    public int effectiveDivisor(int defaultDivisor) {
        Integer override = rateDivisorOverride;
        return override != null ? override : defaultDivisor;
    }

    /**
     * Unconditional write for reliable messages (apply-time errors): never
     * coalesced or dropped for a slow reader. Tick-thread safe.
     */
    public void sendReliable(String json) {
        if (ready && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /** Observation frames deferred/dropped for this slow reader since it attached. */
    public long coalescedObservations() {
        return coalesced.get();
    }

    /**
     * Send one observation frame (tick thread). Writability gate +
     * one-slot latest-wins stash, exactly the M2.3 policy, now per
     * connection: a slow reader only ever drops its own frames.
     */
    void sendObservation(String json) {
        if (!ready || !channel.isActive()) {
            return;
        }
        if (channel.isWritable()) {
            pendingObservation.set(null);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        } else {
            // Edge case accepted: a frame stashed here just after a writability
            // flush (channelWritabilityChanged already ran) waits for the next
            // observation to supersede it rather than flushing immediately.
            // Benign — the stream is continuous while in a world.
            pendingObservation.set(json);
            coalesced.incrementAndGet();
        }
    }

    /** Flush the stashed frame when the channel drains (event loop). */
    void flushPending() {
        String pending = pendingObservation.getAndSet(null);
        if (pending != null) {
            channel.writeAndFlush(new TextWebSocketFrame(pending));
        }
    }

    void recordPong() {
        lastPongNanos = System.nanoTime();
    }

    long lastPongNanos() {
        return lastPongNanos;
    }

    boolean ready() {
        return ready;
    }

    void setReady(boolean ready) {
        this.ready = ready;
    }

    Channel channel() {
        return channel;
    }

    ProtocolSession session() {
        return session;
    }
}
