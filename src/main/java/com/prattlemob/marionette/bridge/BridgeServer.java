package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
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
 * Localhost-only WebSocket transport for protocol v1. All protocol logic
 * lives in {@link ProtocolSession}; this class only moves frames and
 * executes the session's instructions. One controller connection, plus up
 * to {@code maxObservers} observers, at a time. Admission happens at
 * hello-processing time, not at socket-accept time: two sockets may coexist
 * pre-hello, and the first to complete a controller hello wins the slot; a
 * later one is refused with a controller_attached or observer_attached
 * error and close code 1013. A connection that never sends hello within
 * {@code helloTimeoutMillis} is closed 1002. The single Netty event-loop
 * thread parses and enqueues commands but never touches game state; the
 * tick thread drains them. Deliberately free of Minecraft imports so it is
 * testable headless.
 */
public final class BridgeServer {
    private static final Logger LOG = LoggerFactory.getLogger(BridgeServer.class);

    /** Max WebSocket message size; larger closes with 1009 (see protocol/v1.md). */
    private static final int MAX_FRAME_BYTES = 65536;

    /** Liveness ping cadence; constant in M2.3, the watchdog timeout knob is M5.1's. */
    private static final long PING_INTERVAL_MILLIS = 10_000;

    /** One inbound command plus the connection it came from (configure and
     *  apply-time error replies are per-connection). */
    public record Received(AgentCommand command, AgentConnection from) {}

    private final String bindAddress;
    private final int requestedPort;
    private final String modVersion;
    private final int maxObservers;
    private final long pingIntervalMillis;
    private final long helloTimeoutMillis;
    private final Queue<Received> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicReference<AgentConnection> controller = new AtomicReference<>();
    private final List<AgentConnection> observers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean disconnected = new AtomicBoolean();

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
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.modVersion = modVersion;
        this.maxObservers = maxObservers;
        this.pingIntervalMillis = pingIntervalMillis;
        this.helloTimeoutMillis = helloTimeoutMillis;
    }

    /** nanoTime of the newest pong from the controller; 0 before the first. For the M5.1 watchdog. */
    public long lastPongNanos() {
        AgentConnection current = controller.get();
        return current != null ? current.lastPongNanos() : 0;
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
                        channel.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_FRAME_BYTES),
                                new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                                        .websocketPath("/")
                                        .allowExtensions(true)
                                        .maxFramePayloadLength(MAX_FRAME_BYTES)
                                        .dropPongFrames(false)
                                        .build()),
                                new WebSocketFrameAggregator(MAX_FRAME_BYTES),
                                new AgentConnectionHandler());
                    }
                });
        listener = bootstrap.bind(bindAddress, requestedPort).syncUninterruptibly().channel();
    }

    /** The actually bound port (differs from requested when that was 0). */
    public int port() {
        return ((InetSocketAddress) listener.localAddress()).getPort();
    }

    /** True while a controller that completed the hello handshake is attached. */
    public boolean hasController() {
        AgentConnection current = controller.get();
        return current != null && current.ready();
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

    /** All commands received since the last drain, in arrival order, tagged with origin. */
    public List<Received> drainCommands() {
        List<Received> commands = new ArrayList<>();
        Received received;
        while ((received = inbound.poll()) != null) {
            commands.add(received);
        }
        return commands;
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
     * serialized at most once, and not at all when nobody is due. (When
     * D2 section masks arrive in M4.1, recipients with different masks
     * will need per-mask serialization — group by mask then.)
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
            return controller.compareAndSet(null, connection) ? null : ErrorCode.CONTROLLER_ATTACHED;
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
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS)
                    .awaitUninterruptibly(3, TimeUnit.SECONDS);
            group = null;
        }
    }

    private final class AgentConnectionHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private AgentConnection connection;
        private ScheduledFuture<?> pingTask;
        private ScheduledFuture<?> helloTimeoutTask;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                ProtocolSession session = new ProtocolSession(modVersion, this::admit);
                connection = new AgentConnection(ctx.channel(), session);
                pingTask = ctx.executor().scheduleAtFixedRate(
                        () -> {
                            if (ctx.channel().isWritable()) {
                                ctx.writeAndFlush(new PingWebSocketFrame());
                            }
                        },
                        pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
                helloTimeoutTask = ctx.executor().schedule(
                        () -> {
                            if (!connection.session().isActive()) {
                                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "hello timeout"))
                                        .addListener(ChannelFutureListener.CLOSE);
                            }
                        },
                        helloTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            super.userEventTriggered(ctx, event);
        }

        /** RoleAdmission callback: claim a slot and stamp the role on success. */
        private ErrorCode admit(Role role) {
            ErrorCode refusal = tryAdmit(connection, role);
            if (refusal == null) {
                connection.setRole(role);
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
                ctx.writeAndFlush(new CloseWebSocketFrame(1003, "text frames only"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            for (ProtocolSession.Action action : connection.session().onFrame(text.text())) {
                switch (action) {
                    case ProtocolSession.Action.Send send ->
                            ctx.writeAndFlush(new TextWebSocketFrame(send.json()));
                    case ProtocolSession.Action.Enqueue enqueue ->
                            inbound.add(new Received(enqueue.command(), connection));
                    case ProtocolSession.Action.Close close ->
                            ctx.writeAndFlush(new CloseWebSocketFrame(close.code(), close.reason()))
                                    .addListener(ChannelFutureListener.CLOSE);
                }
            }
            connection.setReady(connection.session().isActive());
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
            if (connection != null) {
                connection.setReady(false);
                if (controller.compareAndSet(connection, null)) {
                    // Commands from a dead controller must not act; an
                    // observer's queued configure must survive it.
                    inbound.removeIf(received -> received.from() == connection);
                    if (connection.session().helloCompleted()) {
                        disconnected.set(true); // only a controller loss is an agent loss
                    }
                } else if (observers.remove(connection)) {
                    inbound.removeIf(received -> received.from() == connection);
                    LOG.info("Observer disconnected; {} still watching", observers.size());
                }
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof TooLongFrameException) {
                // The frame decoder/aggregator rejected an oversized message
                // before it ever reached channelRead0. Send the 1009 close
                // frame explicitly: a bare ctx.close() here would let
                // WebSocketServerProtocolHandler's default close path inject
                // its own courtesy 1000 "Bye" frame instead, masking the
                // real cause (see protocol/v1.md's transport section).
                // Best-effort: pre-handshake (HttpObjectAggregator) no WS
                // encoder is wired, so the write fails harmlessly — the
                // connection dies via the close listener either way.
                ctx.writeAndFlush(new CloseWebSocketFrame(1009, "message too big"))
                        .addListener(ChannelFutureListener.CLOSE);
            } else {
                LOG.warn("Bridge connection error ({}): {}; closing connection",
                        cause.getClass().getSimpleName(), cause.getMessage());
                ctx.close(); // channelInactive handles the release signal
            }
        }
    }
}
