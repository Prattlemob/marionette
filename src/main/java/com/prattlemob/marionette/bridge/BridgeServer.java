package com.prattlemob.marionette.bridge;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Localhost-only WebSocket bridge (protocol v0). One controller connection
 * at a time; a later connect while one is attached is refused with close
 * code 1013. The single Netty event-loop thread parses and enqueues
 * commands but never touches game state; the tick thread drains them.
 * Deliberately free of Minecraft imports so it is testable headless.
 */
public final class BridgeServer {
    public static final int PROTOCOL_VERSION = 0;
    public static final int DEFAULT_PORT = 24680;

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
                                new HttpObjectAggregator(65536),
                                new WebSocketServerProtocolHandler("/", null, true),
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
     * True exactly once after a connection is lost; the tick loop turns this
     * into release-all. The one-tick safety guarantee rests on the tick loop
     * calling this every tick.
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

    private static String errorJson(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("message", message);
        return error.toString();
    }

    private final class AgentConnectionHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
        private boolean helloDone;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
            if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete
                    && !controller.compareAndSet(null, ctx.channel())) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1013, "controller already connected"))
                        .addListener(ChannelFutureListener.CLOSE);
            }
            super.userEventTriggered(ctx, event);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
            if (controller.get() != ctx.channel()) {
                return; // a refused extra connection; it is already closing
            }
            AgentCommand command;
            try {
                command = CommandParser.parse(frame.text());
            } catch (ProtocolException e) {
                ctx.writeAndFlush(new TextWebSocketFrame(errorJson(e.getMessage())));
                return;
            }
            if (!helloDone) {
                handleHello(ctx, command);
                return;
            }
            if (command instanceof AgentCommand.Hello) {
                ctx.writeAndFlush(new TextWebSocketFrame(errorJson("duplicate hello")));
                return;
            }
            inbound.add(command);
        }

        private void handleHello(ChannelHandlerContext ctx, AgentCommand command) {
            if (!(command instanceof AgentCommand.Hello hello)) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "hello required first"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            if (hello.version() != PROTOCOL_VERSION) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1002, "unsupported protocol version"))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }
            helloDone = true;
            JsonObject reply = new JsonObject();
            reply.addProperty("type", "hello");
            reply.addProperty("version", PROTOCOL_VERSION);
            reply.addProperty("mod", modVersion);
            ctx.writeAndFlush(new TextWebSocketFrame(reply.toString()));
            controllerReady.set(true);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (controller.compareAndSet(ctx.channel(), null)) {
                controllerReady.set(false);
                inbound.clear(); // commands from a dead controller must not act
                disconnected.set(true);
            }
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close(); // channelInactive handles the release signal
        }
    }
}
