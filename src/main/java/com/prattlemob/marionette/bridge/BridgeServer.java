package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
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
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
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
    public static final int DEFAULT_PORT = 24680;
    /** Max WebSocket message size; larger closes with 1009 (see protocol/v1.md). */
    private static final int MAX_FRAME_BYTES = 65536;

    private final int requestedPort;
    private final String modVersion;
    private final Queue<AgentCommand> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Channel> controller = new AtomicReference<>();
    private final AtomicBoolean controllerReady = new AtomicBoolean();
    private final AtomicBoolean disconnected = new AtomicBoolean();

    private NioEventLoopGroup group;
    private Channel listener;

    /** @param port TCP port on loopback; 0 binds an ephemeral port (tests). */
    public BridgeServer(int port, String modVersion) {
        this.requestedPort = port;
        this.modVersion = modVersion;
    }

    /** Bind to loopback. Blocks briefly; call once. Throws on bind failure. */
    public void start() {
        group = new NioEventLoopGroup(1);
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(MAX_FRAME_BYTES),
                                new WebSocketServerProtocolHandler("/", null, true, MAX_FRAME_BYTES),
                                new WebSocketFrameAggregator(MAX_FRAME_BYTES),
                                new AgentConnectionHandler());
                    }
                });
        listener = bootstrap.bind("127.0.0.1", requestedPort).syncUninterruptibly().channel();
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

    /** Send one observation frame; silently dropped when no controller is ready. */
    public void sendObservation(String json) {
        Channel channel = controller.get();
        if (controllerReady.get() && channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
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

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                if (controller.compareAndSet(null, ctx.channel())) {
                    session = new ProtocolSession(modVersion);
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
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (controller.compareAndSet(ctx.channel(), null)) {
                controllerReady.set(false);
                inbound.clear(); // commands from a dead controller must not act
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
