package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.GameEvent;
import com.prattlemob.marionette.bridge.protocol.ProtocolSession;
import com.prattlemob.marionette.bridge.protocol.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * Localhost-only WebSocket transport for protocol v2. All protocol logic
 * lives in {@link ProtocolSession}; this class only moves frames and
 * executes the session's instructions. One controller connection, plus up
 * to {@code maxObservers} observers, at a time. Admission happens at
 * hello-processing time, not at socket-accept time: two sockets may coexist
 * pre-hello, and the first to complete a controller hello wins the slot; a
 * later one is refused with a controller_attached or observer_attached
 * error and close code 1013; while the local panic latch is engaged every
 * controller hello is refused with panic_latched (1008) instead. A
 * connection that never sends hello within {@code helloTimeoutMillis} is
 * closed 1002. The single Netty event-loop
 * thread parses and enqueues commands but never touches game state; the
 * tick thread drains them. Deliberately free of Minecraft imports so it is
 * testable headless.
 */
public final class BridgeServer {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeServer.class);

    /** Max WebSocket message size; larger closes with 1009 (see protocol/v1.md). */
    private static final int MAX_FRAME_BYTES = 65536;

    /** Maximum watchdog/ping interval; shorter leases use a quarter-timeout interval. */
    private static final long PING_INTERVAL_MILLIS = 1_000;

    /** One inbound command plus the connection it came from (configure and
     *  apply-time error replies are per-connection). */
    public record Received(AgentCommand command, AgentConnection from, long generation) {
        public boolean valid() { return from.valid(generation); }
    }

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final String modVersion;
    private final int maxObservers;
    private final long pingIntervalMillis;
    private final long helloTimeoutMillis;
    private final long pongTimeoutMillis;
    private final Set<Channel> pending = new HashSet<>(); // event-loop only
    private final Set<Channel> sockets = new HashSet<>(); // includes incomplete HTTP
    private int nextConnection;
    public static final int COMMANDS_PER_TICK = 32;
    public static final long WORK_BUDGET_NANOS = 2_000_000;
    private final AtomicReference<AgentConnection> controller = new AtomicReference<>();
    private final List<AgentConnection> observers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    /** Serializes controller admission against the panic latch (no hello can race a panic). */
    private final Object admissionLock = new Object();
    private boolean panicLatched; // guarded by admissionLock
    private long latchedRefusals; // guarded by admissionLock

    private NioEventLoopGroup group;
    private Channel listener;

    /**
     * @param bindAddress address to bind; callers are responsible for
     *        loopback clamping (MarionetteConfig.resolveBindAddress)
     * @param port TCP port; 0 binds an ephemeral port (tests).
     * @param maxObservers cap on concurrently attached observers.
     * @param helloTimeoutMillis close a connection that has not completed hello within this window
     */
    public BridgeServer(String bindAddress, int port, String modVersion,
                        int maxObservers, long helloTimeoutMillis) {
        this(bindAddress, port, modVersion, maxObservers, PING_INTERVAL_MILLIS, helloTimeoutMillis);
    }

    BridgeServer(String bindAddress, int port, String modVersion, int maxObservers,
                 long pingIntervalMillis, long helloTimeoutMillis) {
        this(resolveLoopback(bindAddress), port, modVersion, maxObservers,
                pingIntervalMillis, helloTimeoutMillis, 5_000);
    }

    public BridgeServer(InetAddress bindAddress, int port, String modVersion, int maxObservers,
                        long helloTimeoutMillis, long pongTimeoutMillis) {
        this(bindAddress, port, modVersion, maxObservers,
                Math.min(PING_INTERVAL_MILLIS, Math.max(1, pongTimeoutMillis / 4)),
                helloTimeoutMillis, pongTimeoutMillis);
    }

    private BridgeServer(InetAddress bindAddress, int port, String modVersion, int maxObservers,
                         long pingIntervalMillis, long helloTimeoutMillis, long pongTimeoutMillis) {
        if (!bindAddress.isLoopbackAddress()) throw new IllegalArgumentException("loopback required");
        this.bindAddress = bindAddress;
        this.pongTimeoutMillis = pongTimeoutMillis;
        this.requestedPort = port;
        this.modVersion = modVersion;
        this.maxObservers = maxObservers;
        this.pingIntervalMillis = pingIntervalMillis;
        this.helloTimeoutMillis = helloTimeoutMillis;
    }

    private static InetAddress resolveLoopback(String address) {
        try {
            InetAddress resolved = InetAddress.getByName(address);
            if (!resolved.isLoopbackAddress()) throw new IllegalArgumentException("loopback required");
            return resolved;
        } catch (UnknownHostException e) { throw new IllegalArgumentException(e); }
    }

    /** Sever only the controller; invalidate immediately on the calling thread. */
    public void disconnectController(String reason) {
        AgentConnection current = controller.get();
        if (current != null) current.close(1008, reason);
    }

    /**
     * Local panic: latch controller admission off, then sever the attached
     * controller (close 1008 {@code reason}). Latch and slot are read under
     * the admission lock, so a concurrent hello is either severed here or
     * refused with panic_latched. Repeated panics only keep the latch.
     * Observers are untouched. Safe from any thread.
     *
     * @return true if this call engaged the latch, false if already latched
     */
    public boolean panic(String reason) {
        boolean engaged;
        AgentConnection current;
        synchronized (admissionLock) {
            engaged = !panicLatched;
            if (engaged) latchedRefusals = 0;
            panicLatched = true;
            current = controller.get();
        }
        if (current != null) current.close(1008, reason);
        return engaged;
    }

    /**
     * Clear the panic latch so a future controller hello may be admitted.
     * Grants, restores and replays nothing.
     *
     * @return true if the latch was engaged
     */
    public boolean rearm() {
        long refused;
        synchronized (admissionLock) {
            if (!panicLatched) return false;
            panicLatched = false;
            refused = latchedRefusals;
        }
        LOG.info("Panic latch cleared; {} controller hello(s) were refused while latched", refused);
        return true;
    }

    /** True while local panic latches controller admission off. */
    public boolean panicLatched() {
        synchronized (admissionLock) {
            return panicLatched;
        }
    }

    /** nanoTime of the newest pong from the controller; 0 before the first. Used for liveness diagnostics. */
    public long lastPongNanos() {
        AgentConnection current = controller.get();
        return current != null ? current.lastPongNanos() : 0;
    }

    /**
     * Every intentional close writes its own coded frame first, so Netty must
     * not add one: its default 1000 "normal closure" would otherwise reach an
     * agent whose connection was dropped for being saturated.
     */
    static WebSocketServerProtocolConfig protocolConfig() {
        return WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/")
                .allowExtensions(true)
                .maxFramePayloadLength(MAX_FRAME_BYTES)
                .dropPongFrames(false)
                .sendCloseFrame(null)
                .build();
    }

    /** Bind to the configured address. Blocks briefly; call once. Throws on bind failure. */
    public void start() {
        group = new NioEventLoopGroup(1, new DefaultThreadFactory("marionette-bridge", true));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                // Hard bound for a slow-reading agent: beyond the high-water mark the
                // channel reports unwritable and sendObservation coalesces instead.
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        AgentConnectionHandler handler = new AgentConnectionHandler();
                        channel.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_FRAME_BYTES),
                                new ControlFrameBackpressure(ctx -> {
                                    if (handler.connection != null) handler.connection.close(1013, "overloaded");
                                    else ctx.close();
                                }),
                                new io.netty.channel.SimpleChannelInboundHandler<io.netty.handler.codec.http.FullHttpRequest>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx,
                                            io.netty.handler.codec.http.FullHttpRequest request) {
                                        if (request.headers().contains(io.netty.handler.codec.http.HttpHeaderNames.ORIGIN)) {
                                            ctx.writeAndFlush(new io.netty.handler.codec.http.DefaultFullHttpResponse(
                                                    io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                                                    io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN))
                                                    .addListener(ChannelFutureListener.CLOSE);
                                        } else ctx.fireChannelRead(request.retain());
                                    }
                                },
                                new io.netty.channel.ChannelInboundHandlerAdapter() {
                                    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                                        if (message instanceof CloseWebSocketFrame && handler.connection != null) {
                                            handler.connection.session().close();
                                            handler.connection.invalidate();
                                        }
                                        ctx.fireChannelRead(message);
                                    }
                                },
                                new WebSocketServerProtocolHandler(protocolConfig()),
                                new WebSocketFrameAggregator(MAX_FRAME_BYTES),
                                handler);
                    }
                });
        listener = bootstrap.bind(bindAddress, requestedPort).syncUninterruptibly().channel();
    }

    /** The actually bound port (differs from requested when that was 0). */
    public int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    /** True while a controller that completed the hello handshake is attached and panic is not latched. */
    public boolean hasController() {
        AgentConnection current = controller.get();
        return current != null && current.ready() && !panicLatched();
    }

    /**
     * True exactly once after a hello-completed controller is lost; the tick
     * loop turns this into release-all. The one-tick safety guarantee rests
     * on the tick loop calling this every tick. Connections that never
     * completed hello (controller or observer) do not signal here.
     */
    public boolean pollDisconnected() {
        return disconnected.getAndSet(false);
    }

    /** Priority releases first; at most one ordinary command per round-robin turn. */
    public Received pollCommand() {
        Received safety = pollRelease();
        if (safety != null) return safety;
        List<AgentConnection> active = connections().toList();
        for (int i = 0; i < active.size(); i++) {
            AgentConnection connection = active.get(Math.floorMod(nextConnection++, active.size()));
            Received received = connection.pollCommand();
            if (received != null && received.valid()) return received;
        }
        return null;
    }

    public Received pollRelease() {
        AgentConnection current = controller.get();
        if (current == null) return null;
        Received received = current.pollRelease();
        return received != null && received.valid() ? received : null;
    }

    /** Bounded convenience drain for headless consumers. The game also imposes a time budget. */
    public List<Received> drainCommands() {
        List<Received> result = new ArrayList<>();
        for (int i = 0; i < COMMANDS_PER_TICK; i++) {
            Received received = pollCommand();
            if (received == null) break;
            result.add(received);
        }
        return result;
    }

    /**
     * Broadcast one observation frame to every ready connection (controller
     * and observers), each with its own writability gate and latest-wins
     * stash: a slow reader only ever drops its own frames.
     */
    public void sendObservation(String json) {
        connections().forEach(connection -> connection.sendObservation(json));
    }

    /**
     * Tick-cadence observation send: each ready connection receives the
     * frame only on ticks its effective divisor divides. The frame is
     * serialized at most once, and not at all when nobody is due.
     * For composite player frames use sendPlayerObservation instead.
     */
    public void sendObservation(long tick, int defaultDivisor, Supplier<String> frame) {
        String json = null;
        for (AgentConnection connection : (Iterable<AgentConnection>) connections()::iterator) {
            if (!connection.ready() || tick % connection.effectiveDivisor(defaultDivisor) != 0) {
                continue;
            }
            if (json == null) {
                json = frame.get();
            }
            connection.sendObservation(json);
        }
    }

    /** Serialize once per due mask; sample player data at most once per tick. */
    public void sendPlayerObservation(long tick, int defaultDivisor,
                                     Supplier<JsonObject> player) {
        sendSectionObservation(tick, defaultDivisor, player, () -> null);
    }

    /** Serialize once per due mask; sample each section at most once per tick, only when selected. */
    public void sendSectionObservation(long tick, int defaultDivisor,
                                       Supplier<JsonObject> player, Supplier<JsonObject> inventory) {
        sendSectionObservation(tick, defaultDivisor, Map.of("player", player, "inventory", inventory));
    }

    /**
     * Serialize once per due mask; sample each section at most once per tick,
     * only when some due session selected it. Sections without a sampler are omitted.
     */
    public void sendSectionObservation(long tick, int defaultDivisor, Map<String, Supplier<JsonObject>> samplers) {
        Map<Set<String>, String> frames = new HashMap<>();
        Map<String, JsonObject> snapshots = new HashMap<>();
        for (AgentConnection connection : (Iterable<AgentConnection>) connections()::iterator) {
            if (!connection.ready() || tick % connection.effectiveDivisor(defaultDivisor) != 0) continue;
            var mask = connection.sections();
            String json = frames.get(mask);
            if (json == null) {
                Map<String, JsonObject> selected = new HashMap<>();
                for (String name : mask) {
                    Supplier<JsonObject> sampler = samplers.get(name);
                    if (sampler == null) continue;
                    if (!snapshots.containsKey(name)) snapshots.put(name, sampler.get());
                    selected.put(name, snapshots.get(name));
                }
                json = Messages.sectionObservation(tick, selected);
                frames.put(mask, json);
            }
            connection.sendObservation(json);
        }
    }

    /**
     * Deliver one event to every subscribed, ready connection, each with its
     * own sequence and bound (protocol/v1.md, Events). Client thread only, so
     * events, observations and tick-side replies share one write order.
     */
    public void sendEvent(GameEvent event) {
        connections().forEach(connection -> connection.sendEvent(event));
    }

    /** Ready observer sessions; for headless tests. */
    int observerCount() {
        return (int) observers.stream().filter(AgentConnection::ready).count();
    }

    /** Observation frames deferred/dropped across all connections since they attached. */
    public long coalescedObservations() {
        return connections().mapToLong(AgentConnection::coalescedObservations).sum();
    }

    private Stream<AgentConnection> connections() {
        AgentConnection current = controller.get();
        return current == null ? observers.stream()
                : Stream.concat(Stream.of(current), observers.stream());
    }

    /** Claim a slot for a hello-processing connection; null admits. Event-loop only. */
    private ErrorCode tryAdmit(AgentConnection connection, Role role) {
        if (role == Role.CONTROLLER) {
            synchronized (admissionLock) {
                if (panicLatched) {
                    if (++latchedRefusals == 1) LOG.info("Controller hello refused: panic latched");
                    else LOG.debug("Controller hello refused: panic latched ({} since panic)", latchedRefusals);
                    return ErrorCode.PANIC_LATCHED;
                }
                return controller.compareAndSet(null, connection) ? null : ErrorCode.CONTROLLER_ATTACHED;
            }
        }
        if (observers.size() >= maxObservers) {
            return ErrorCode.OBSERVER_ATTACHED;
        }
        observers.add(connection);
        return null;
    }

    /**
     * Close listener, every connection (1001 going away), and event loop.
     * The group shutdown is bounded (2s shutdown timeout, 3s await); daemon
     * threads are the final backstop if that window is somehow exceeded.
     * Ordering rule (docs/decisions.md): the caller releases controls
     * before stopping the bridge. Safe to call repeatedly.
     */
    public void stop() {
        if (listener != null) {
            listener.close().syncUninterruptibly();
            listener = null;
        }
        AgentConnection current = controller.getAndSet(null);
        List<AgentConnection> watching = new ArrayList<>(observers);
        observers.clear();
        List<AgentConnection> all = new ArrayList<>();
        if (current != null) {
            all.add(current);
        }
        all.addAll(watching);
        for (AgentConnection connection : all) {
            if (connection.channel().isActive()) {
                connection.channel().writeAndFlush(new CloseWebSocketFrame(1001, "server shutting down"))
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }
        if (group != null) {
            group.submit(() -> new ArrayList<>(sockets).forEach(Channel::close)).syncUninterruptibly();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
                    .awaitUninterruptibly(3, TimeUnit.SECONDS);
            group = null;
        }
    }

    /**
     * Netty's automatic pong path must obey backpressure too: a ping or pong
     * written while the channel is unwritable is dropped and the peer is
     * disconnected instead of growing the outbound buffer.
     */
    static final class ControlFrameBackpressure extends io.netty.channel.ChannelDuplexHandler {
        private final java.util.function.Consumer<ChannelHandlerContext> onOverload;

        ControlFrameBackpressure(java.util.function.Consumer<ChannelHandlerContext> onOverload) {
            this.onOverload = onOverload;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, io.netty.channel.ChannelPromise promise) {
            if ((message instanceof PingWebSocketFrame || message instanceof PongWebSocketFrame)
                    && !ctx.channel().isWritable()) {
                io.netty.util.ReferenceCountUtil.release(message);
                promise.tryFailure(new IllegalStateException("control-frame overload"));
                onOverload.accept(ctx);
            } else ctx.write(message, promise);
        }
    }

    private final class AgentConnectionHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private AgentConnection connection;
        private ScheduledFuture<?> pingTask;
        private ScheduledFuture<?> helloTimeoutTask;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            if (pending.size() >= 8) { ctx.close(); return; }
            sockets.add(ctx.channel());
            pending.add(ctx.channel());
            helloTimeoutTask = ctx.executor().schedule(() -> {
                if (connection == null) ctx.close();
                else if (!connection.session().isActive()) connection.close(1002, "hello timeout");
            }, helloTimeoutMillis, TimeUnit.MILLISECONDS);
            super.channelActive(ctx);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ProtocolSession session = new ProtocolSession(modVersion, this::admit);
                connection = new AgentConnection(ctx.channel(), session, () -> {
                    if (connection != null && connection.role() == Role.CONTROLLER) disconnected.set(true);
                });
                pingTask = ctx.executor().scheduleAtFixedRate(
                        () -> {
                            if (connection.pongExpired(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(pongTimeoutMillis))) {
                                LOG.warn("Bridge {} pong timeout", connection.role());
                                connection.close(1008, "pong timeout");
                            } else if (connection.ready() && ctx.channel().isWritable()) {
                                ctx.writeAndFlush(new PingWebSocketFrame());
                            }
                        },
                        pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
            }
            super.userEventTriggered(ctx, event);
        }

        /** RoleAdmission callback: claim a slot and stamp the role on success. */
        private ErrorCode admit(Role role) {
            ErrorCode refusal = tryAdmit(connection, role);
            if (refusal == null) {
                connection.setRole(role);
                pending.remove(connection.channel());
                if (helloTimeoutTask != null) {
                    helloTimeoutTask.cancel(false);
                }
            }
            return refusal;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (connection == null) {
                return; // frame raced the handshake-complete event; nothing to do
            }
            if (frame instanceof PongWebSocketFrame) {
                connection.recordPong();
                Role pongRole = connection.role();
                LOG.debug("Pong from {}", pongRole != null ? pongRole : "pre-hello");
                return;
            }
            if (!(frame instanceof TextWebSocketFrame text)) {
                connection.session().close();
                connection.close(1003, "text frames only");
                return;
            }
            List<ProtocolSession.Action> actions = connection.session().onFrame(text.text());
            // Become ready before the hello reply is written: an agent that has
            // read the reply must receive every later broadcast. This inline
            // write still precedes any client-thread write, which Netty queues.
            if (!connection.ready() && connection.session().isActive()) {
                connection.setSections(connection.session().sections());
                connection.setEvents(connection.session().events());
            }
            connection.setReady(connection.session().isActive());
            for (ProtocolSession.Action action : actions) {
                switch (action) {
                    case ProtocolSession.Action.Send send ->
                            connection.sendReliable(send.json());
                    case ProtocolSession.Action.Enqueue enqueue -> {
                        if (!connection.enqueue(enqueue.command())) {
                            connection.sendReliable(Messages.error(ErrorCode.OVERLOADED,
                                    "command queue full", null, null));
                            connection.close(1013, "overloaded");
                        }
                    }
                    case ProtocolSession.Action.Close close ->
                            connection.close(close.code(), close.reason());
                }
            }
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (connection != null && ctx.channel().isWritable() && connection.ready()) {
                connection.flushPending();
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            if (helloTimeoutTask != null) {
                helloTimeoutTask.cancel(false);
            }
            pending.remove(ctx.channel());
            sockets.remove(ctx.channel());
            if (connection != null) {
                connection.invalidate();
                if (controller.compareAndSet(connection, null)) {
                    // invalidate() already cleared only this connection's work
                    // and signaled loss once, before any courtesy close flush.
                } else if (observers.remove(connection)) {

                    LOG.info("Observer disconnected; {} still watching", observers.size());
                }
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            int code = cause instanceof TooLongFrameException ? 1009 : 1011;
            String reason = code == 1009 ? "message too big" : "transport error";
            LOG.warn("Bridge connection error ({}): {}; closing connection",
                    cause.getClass().getSimpleName(), cause.getMessage());
            if (connection != null) connection.close(code, reason);
            else ctx.close();
        }
    }
}
