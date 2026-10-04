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

    @Test
    void observationCountersSeparateDeliveredFromDroppedFrames() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        connection.setReady(true);
        try {
            connection.sendObservation("a");
            assertEquals(1, connection.observationsSent());
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            connection.sendObservation("b"); // stashed: delayed, not dropped
            assertEquals(0, connection.observationsDropped());
            connection.sendObservation("c"); // replaces b, which is never delivered
            assertEquals(1, connection.observationsDropped());
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            channel.runPendingTasks();
            assertEquals(2, connection.observationsSent(), "the stash flush is a delivered frame");
            assertEquals(1, connection.observationsDropped());
            connection.sendObservation("x".repeat(AgentConnection.FRAME_BYTES + 1));
            assertEquals(2, connection.observationsDropped(), "an oversized frame is dropped");
            assertEquals(2, connection.status(1, System.nanoTime()).observationsSent());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void anEventDropsAnOlderStashedObservation() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        connection.setReady(true);
        connection.setEvents(true);
        try {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            connection.sendObservation("old");
            connection.sendEvent(new com.prattlemob.marionette.bridge.protocol.GameEvent("death", "w", 1,
                    com.prattlemob.marionette.bridge.protocol.GameEvent.Basis.SERVER, new com.google.gson.JsonObject()));
            assertEquals(1, connection.observationsDropped());
            assertEquals(1, connection.status(1, System.nanoTime()).eventsSent());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void latencyCountersMeasurePingRoundTripAndCommandApplication() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        try {
            var initial = connection.status(1, System.nanoTime());
            assertNull(initial.rttMillis());
            assertNull(initial.commandLatencyMillis());
            connection.recordPong(); // unsolicited: no ping outstanding, nothing measured
            assertNull(connection.status(1, System.nanoTime()).rttMillis());
            connection.recordPing(System.nanoTime() - 5_000_000);
            connection.recordPing(System.nanoTime()); // still awaiting the first ping's pong
            connection.recordPong();
            double rtt = connection.status(1, System.nanoTime()).rttMillis();
            assertTrue(rtt >= 5.0 && rtt < 1000.0, "rtt " + rtt);
            connection.recordApplied(1_000_000_000L, 1_012_500_000L);
            assertEquals(12.5, connection.status(1, System.nanoTime()).commandLatencyMillis(), 1e-9);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void observationRateIsMeasuredOverCompletedOneSecondWindows() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        connection.setReady(true);
        try {
            connection.sampleRate(1_000_000_000L);
            for (int i = 0; i < 10; i++) connection.sendObservation("f" + i);
            connection.sampleRate(1_500_000_000L);
            assertEquals(0.0, connection.status(1, 0).observationRate(), "no completed window yet");
            connection.sampleRate(3_000_000_000L);
            assertEquals(5.0, connection.status(1, 0).observationRate(), 1e-9);
            connection.sampleRate(4_000_000_000L);
            assertEquals(0.0, connection.status(1, 0).observationRate(), 1e-9);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void statusReportsSessionSettings() {
        EmbeddedChannel channel = new EmbeddedChannel();
        AgentConnection connection = new AgentConnection(channel, new ProtocolSession("test", role -> null), () -> {});
        try {
            connection.setAgent("walker");
            connection.setRole(com.prattlemob.marionette.bridge.protocol.Role.OBSERVER);
            connection.setSections(java.util.Set.of("world"));
            connection.setRateDivisor(4);
            var status = connection.status(1, System.nanoTime());
            assertEquals("observer", status.role());
            assertEquals("walker", status.agent());
            assertEquals(4, status.rateDivisor());
            assertEquals(java.util.List.of("world"), status.sections());
            assertEquals(4, connection.status(2, System.nanoTime()).rateDivisor(), "the override wins over the default");
        } finally { channel.finishAndReleaseAll(); }
    }
}
