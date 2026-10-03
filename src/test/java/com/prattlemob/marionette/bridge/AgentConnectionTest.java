package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import com.prattlemob.marionette.bridge.protocol.ProtocolSession;

class AgentConnectionTest {
    @Test
    void synchronousWriteCompletionCannotFlushAnOlderStashAfterNewFrame() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        connection.setReady(true);
        try {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            connection.sendObservation("old");
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            connection.sendObservation("new");
            channel.runPendingTasks();
            var frame = (io.netty.handler.codec.http.websocketx.TextWebSocketFrame) channel.readOutbound();
            assertEquals("new", frame.text());
            frame.release();
            assertNull(channel.readOutbound(), "the superseded stash must not follow the new frame");
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void reliableBytesIncludePendingWritesAndOverflowInvalidatesOnce() {
        var retained = new ArrayList<Object>();
        var promises = new ArrayList<ChannelPromise>();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                retained.add(msg);
                promises.add(promise); // model a stalled downstream socket
            }
        });
        AtomicInteger losses = new AtomicInteger();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), losses::incrementAndGet);
        connection.setReady(true);
        try {
            String frame = "x".repeat(64 * 1024);
            for (int i = 0; i < 3; i++) connection.sendReliable(frame);
            assertEquals(3L * (frame.length() + 64), connection.outboundBytes());
            connection.sendReliable(frame);
            assertFalse(connection.ready());
            assertTrue(connection.outboundBytes() <= AgentConnection.OUTBOUND_BYTES);
            connection.invalidate();
            assertEquals(1, losses.get(), "close and inactive cannot signal duplicate controller loss");
        } finally {
            retained.forEach(ReferenceCountUtil::release);
            promises.forEach(p -> p.trySuccess());
            channel.finishAndReleaseAll();
        }
    }
}
