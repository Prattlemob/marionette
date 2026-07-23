package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.bridge.protocol.ProtocolSession;

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

/**
 * Localhost-only WebSocket transport for protocol v1. All protocol logic
 * lives in {@link ProtocolSession}; this class only moves frames and
 * executes the session's instructions. One controller connection at a
 * time; a later connect while one is attached is refused with a
 * controller_attached error and close code 1013. The single Netty
 * event-loop thread parses and enqueues commands but never touches game
 * state; the tick thread drains them. Deliberately free of Minecraft
 * imports so it is testable headless.
 */
public final class BridgeServer {
    /** Max WebSocket message size; larger closes with 1009 (see protocol/v1.md). */
    private static final int MAX_FRAME_BYTES = 65536;

    /** Liveness ping cadence; constant in M2.3, the watchdog timeout knob is M5.1's. */
    private static final long PING_INTERVAL_MILLIS = 10_000;

    private final String bindAddress;
    private final int requestedPort;
    private final String modVersion;
    private final long pingIntervalMillis;
    private final Queue<AgentCommand> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Channel> controller = new AtomicReference<>();
    private final AtomicBoolean controllerReady = new AtomicBoolean();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicReference<String> pendingObservation = new AtomicReference<>();
    private final AtomicLong coalesced = new AtomicLong();
    private volatile long lastPongNanos;

    private NioEventLoopGroup group;
    private Channel listener;

    /**
     * @param bindAddress address to bind; callers are responsible for
     *        loopback clamping (MarionetteConfig.resolveBindAddress)
     * @param port TCP port; 0 binds an ephemeral port (tests).
     */
    public BridgeServer(String bindAddress, int port, String modVersion) {
        this(bindAddress, port, modVersion, PING_INTERVAL_MILLIS);
    }

    BridgeServer(String bindAddress, int port, String modVersion, long pingIntervalMillis) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.modVersion = modVersion;
        this.pingIntervalMillis = pingIntervalMillis;
    }

    /** nanoTime of the newest pong from the controller; 0 before the first. For the M5.1 watchdog. */
    public long lastPongNanos() {
        return lastPongNanos;
    }

    /** Bind to the configured address. Blocks briefly; call once. Throws on bind failure. */
    public void start() {
        group = new NioEventLoopGroup(1);
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
        return controllerReady.get();
    }

    /**
     * True exactly once after a hello-completed controller is lost; the tick
     * loop turns this into release-all. The one-tick safety guarantee rests
     * on the tick loop calling this every tick. Connections that never
     * completed hello do not signal here.
     */
    public boolean pollDisconnected() {
        return disconnected.getAndSet(false);
    }

    /** All commands received since the last drain, in arrival order. */
    public List<AgentCommand> drainCommands() {
        List<AgentCommand> commands = new ArrayList<>();
        AgentCommand command;
        while ((command = inbound.poll()) != null) {
            commands.add(command);
        }
        return commands;
    }

    /**
     * Send one observation frame; silently dropped when no controller is
     * ready. A slow-reading agent (channel unwritable) gets frames coalesced
     * to latest: the newest frame waits in a one-slot stash, flushed when the
     * channel drains; intermediate frames are dropped and counted. Setting
     * the stash to null before a direct write is what keeps latest-wins
     * ordering: a newer frame always supersedes a stashed older one.
     */
    public void sendObservation(String json) {
        Channel channel = controller.get();
        if (!controllerReady.get() || channel == null || !channel.isActive()) {
            return;
        }
        if (channel.isWritable()) {
            pendingObservation.set(null);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        } else {
            pendingObservation.set(json);
            coalesced.incrementAndGet();
        }
    }

    /** Observation frames deferred/dropped for a slow reader since this controller attached. */
    public long coalescedObservations() {
        return coalesced.get();
    }

    /** Close listener, connection, and event loop. Safe to call once at shutdown. */
    public void stop() {
        if (listener != null) {
            listener.close().syncUninterruptibly();
        }
        Channel channel = controller.get();
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private final class AgentConnectionHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private ProtocolSession session;
        private ScheduledFuture<?> pingTask;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                if (controller.compareAndSet(null, ctx.channel())) {
                    session = new ProtocolSession(modVersion);
                    pingTask = ctx.executor().scheduleAtFixedRate(
                            () -> ctx.writeAndFlush(new PingWebSocketFrame()),
                            pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
                } else {
                    ctx.write(new TextWebSocketFrame(Messages.error(
                            ErrorCode.CONTROLLER_ATTACHED, "controller already connected",
                            null, null)));
                    ctx.writeAndFlush(new CloseWebSocketFrame(
                                    ErrorCode.CONTROLLER_ATTACHED.closeCode(),
                                    "controller already connected"))
                            .addListener(ChannelFutureListener.CLOSE);
                }
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (controller.get() != ctx.channel() || session == null) {
                return; // a refused extra connection; it is already closing
            }
            if (frame instanceof PongWebSocketFrame) {
                lastPongNanos = System.nanoTime();
                return;
            }
            if (!(frame instanceof TextWebSocketFrame text)) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1003, "text frames only"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            for (ProtocolSession.Action action : session.onFrame(text.text())) {
                switch (action) {
                    case ProtocolSession.Action.Send send ->
                            ctx.writeAndFlush(new TextWebSocketFrame(send.json()));
                    case ProtocolSession.Action.Enqueue enqueue -> inbound.add(enqueue.command());
                    case ProtocolSession.Action.Close close ->
                            ctx.writeAndFlush(new CloseWebSocketFrame(close.code(), close.reason()))
                                    .addListener(ChannelFutureListener.CLOSE);
                }
            }
            controllerReady.set(session.isActive());
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (ctx.channel() == controller.get() && ctx.channel().isWritable()) {
                String pending = pendingObservation.getAndSet(null);
                if (pending != null) {
                    ctx.writeAndFlush(new TextWebSocketFrame(pending));
                }
            }
            super.channelWritabilityChanged(ctx);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (controller.compareAndSet(ctx.channel(), null)) {
                controllerReady.set(false);
                inbound.clear(); // commands from a dead controller must not act
                pendingObservation.set(null);
                coalesced.set(0);
                if (pingTask != null) {
                    pingTask.cancel(false);
                }
                lastPongNanos = 0;
                if (session != null && session.isActive()) {
                    // Only a hello-completed controller counts as an agent loss.
                    disconnected.set(true);
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
                ctx.close(); // channelInactive handles the release signal
            }
        }
    }
}
