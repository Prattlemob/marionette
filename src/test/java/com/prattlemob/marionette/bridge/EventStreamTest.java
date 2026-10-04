package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.GameEvent;
import com.prattlemob.marionette.bridge.protocol.Messages;

/** M4.6 event delivery over real sockets: opt-in, sequence, ordering, overflow. */
class EventStreamTest {
    private BridgeServer server;

    @BeforeEach
    void startServer() {
        server = new BridgeServer("127.0.0.1", 0, "test-version", 2, 10_000);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for condition");
            Thread.sleep(5);
        }
    }

    private static final class Client implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        volatile boolean stalled;
        WebSocket ws;

        static Client connect(int port, String hello) throws Exception {
            Client client = new Client();
            client.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/"), client).get(5, TimeUnit.SECONDS);
            client.ws.sendText(hello, true).join();
            assertEquals("hello", client.next().get("type").getAsString());
            return client;
        }

        JsonObject next() throws InterruptedException {
            String message = messages.poll(10, TimeUnit.SECONDS);
            assertNotNull(message, "timed out waiting for a message");
            return JsonParser.parseString(message).getAsJsonObject();
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.add(partial.toString()); partial.setLength(0); }
            if (!stalled) webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }

    private static GameEvent chat(long tick, String text) {
        JsonObject fields = new JsonObject();
        fields.addProperty("kind", "system");
        fields.addProperty("text", text);
        fields.add("sender", com.google.gson.JsonNull.INSTANCE);
        fields.add("chatType", com.google.gson.JsonNull.INSTANCE);
        fields.addProperty("truncated", false);
        return new GameEvent("chat", "w1", tick, GameEvent.Basis.SERVER, fields);
    }

    /** Ready the session and return its connection by draining one configure. */
    private AgentConnection configure(Client client, String configure) throws Exception {
        client.ws.sendText(configure, true).join();
        List<BridgeServer.Received> received = new ArrayList<>();
        await(() -> { received.addAll(server.drainCommands()); return !received.isEmpty(); });
        var connection = received.getFirst().from();
        var settings = (AgentCommand.Configure) received.getFirst().command();
        if (settings.events() != null) connection.setEvents(settings.events());
        return connection;
    }

    @Test
    void eventsAreOptInAndUnsubscribedSessionsNeverSeeTheType() throws Exception {
        Client legacy = Client.connect(server.port(), "{\"type\":\"hello\",\"versions\":[2]}");
        Client subscribed = Client.connect(server.port(),
                "{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\",\"events\":true}");
        Client later = Client.connect(server.port(), "{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\"}");
        server.sendEvent(chat(1, "first"));
        server.sendObservation("{\"type\":\"observation\",\"tick\":1}");
        assertEquals("observation", legacy.next().get("type").getAsString(), "legacy sessions get no event");
        assertEquals("observation", later.next().get("type").getAsString());
        JsonObject event = subscribed.next();
        assertEquals("event", event.get("type").getAsString());
        assertEquals(1, event.get("seq").getAsLong());
        assertFalse(event.has("id"));
        assertEquals("observation", subscribed.next().get("type").getAsString());
        configure(later, "{\"type\":\"configure\",\"events\":true}");
        server.sendEvent(chat(2, "second"));
        assertEquals(1, later.next().get("seq").getAsLong(), "numbering is per connection");
        assertEquals(2, subscribed.next().get("seq").getAsLong());
        configure(later, "{\"type\":\"configure\",\"events\":false}");
        server.sendEvent(chat(3, "third"));
        server.sendObservation("{\"type\":\"observation\",\"tick\":3}");
        assertEquals("observation", later.next().get("type").getAsString(), "unsubscribed again");
        assertTrue(legacy.messages.stream().noneMatch(m -> m.contains("\"event\"")));
    }

    @Test
    void oneWriteOrderForEventsObservationsAndClientThreadReplies() throws Exception {
        Client client = Client.connect(server.port(), "{\"type\":\"hello\",\"versions\":[2],\"events\":true}");
        AgentConnection connection = configure(client, "{\"type\":\"configure\",\"id\":\"cfg\"}");
        List<String> expected = new ArrayList<>();
        for (long tick = 1; tick <= 200; tick++) {
            server.sendEvent(chat(tick, "e" + tick));
            expected.add("event:" + tick);
            if (tick % 7 == 0) {
                connection.sendReliable(Messages.inventoryResult("inspect", new com.google.gson.JsonPrimitive("r" + tick), null));
                expected.add("inventory_result:" + tick);
            }
            server.sendObservation("{\"type\":\"observation\",\"tick\":" + tick + "}");
            expected.add("observation:" + tick);
        }
        List<String> actual = new ArrayList<>();
        long seq = 0;
        for (int i = 0; i < expected.size(); i++) {
            JsonObject message = client.next();
            String type = message.get("type").getAsString();
            long tick = switch (type) {
                case "inventory_result" -> Long.parseLong(message.get("id").getAsString().substring(1));
                default -> message.get("tick").getAsLong();
            };
            if (type.equals("event")) {
                assertEquals(++seq, message.get("seq").getAsLong(), "contiguous sequence");
                assertFalse(message.has("id"), "events never carry an id");
            }
            actual.add(type + ":" + tick);
        }
        assertEquals(expected, actual);
    }

    @Test
    void anEventDiscardsAnOlderStashedObservationInsteadOfFollowingIt() throws Exception {
        var channel = new io.netty.channel.embedded.EmbeddedChannel();
        var connection = new AgentConnection(channel,
                new com.prattlemob.marionette.bridge.protocol.ProtocolSession("test", role -> null), () -> {});
        connection.setReady(true);
        connection.setEvents(true);
        try {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            connection.sendObservation("{\"type\":\"observation\",\"tick\":4}");
            assertEquals(1, connection.coalescedObservations());
            assertEquals(1, connection.sendEvent(chat(5, "after the stash")));
            assertEquals(2, connection.coalescedObservations(), "the stale stash is counted as coalesced");
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            connection.flushPending();
            channel.runPendingTasks();
            var frame = (io.netty.handler.codec.http.websocketx.TextWebSocketFrame) channel.readOutbound();
            assertTrue(frame.text().contains("\"event\""));
            frame.release();
            assertNull(channel.readOutbound(), "tick 4 must not arrive after the tick 5 event");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void inFlightBoundClosesWithoutSkippingASequenceNumber() {
        var promises = new ArrayList<io.netty.channel.ChannelPromise>();
        var retained = new ArrayList<Object>();
        var channel = new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg,
                                        io.netty.channel.ChannelPromise promise) {
                retained.add(msg);
                promises.add(promise); // a stalled socket never accepts the write
            }
        });
        var losses = new java.util.concurrent.atomic.AtomicInteger();
        var connection = new AgentConnection(channel,
                new com.prattlemob.marionette.bridge.protocol.ProtocolSession("test", role -> null), losses::incrementAndGet);
        connection.setReady(true);
        connection.setEvents(true);
        try {
            for (int i = 1; i <= AgentConnection.EVENT_LIMIT; i++) {
                assertEquals(i, connection.sendEvent(chat(i, "e")));
            }
            assertEquals(AgentConnection.EVENT_LIMIT, connection.eventsInFlight());
            assertEquals(0, connection.sendEvent(chat(9999, "overflow")));
            assertFalse(connection.ready(), "overflow closes the connection");
            assertEquals(AgentConnection.EVENT_LIMIT, connection.eventSeq(), "no number was skipped or reused");
            assertEquals(0, connection.sendEvent(chat(10_000, "late")), "nothing after overflow");
            assertEquals(1, losses.get());
            promises.forEach(p -> p.trySuccess());
            assertEquals(0, connection.eventsInFlight(), "completed writes release the bound");
        } finally {
            retained.forEach(io.netty.util.ReferenceCountUtil::release);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void stalledSubscriberOverflowsAloneAndItsStreamEndsWithoutAGap() throws Exception {
        Client controller = Client.connect(server.port(), "{\"type\":\"hello\",\"versions\":[2],\"events\":true}");
        Client stalled = Client.connect(server.port(),
                "{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\",\"events\":true}");
        AgentConnection controllerConnection = configure(controller, "{\"type\":\"configure\"}");
        AgentConnection stalledConnection = configure(stalled, "{\"type\":\"configure\"}");
        stalled.stalled = true;
        String padding = "p".repeat(400);
        long sent = 0;
        while (stalledConnection.ready() && sent < 200_000) {
            // Pace on the controller's own backlog, not wall time: it must keep
            // up on a loaded machine while only the stalled reader overflows.
            await(() -> controllerConnection.outboundBytes() < AgentConnection.OUTBOUND_BYTES / 4
                    && controllerConnection.eventsInFlight() < AgentConnection.EVENT_LIMIT / 4);
            server.sendEvent(chat(sent + 1, padding));
            sent++;
        }
        assertFalse(stalledConnection.ready(), "the stalled subscriber must overflow");
        assertFalse(server.pollDisconnected(), "an observer overflow is not controller loss");
        assertTrue(server.hasController());
        long delivered = stalledConnection.eventSeq();
        assertTrue(delivered < sent, "the overflowing event and later ones are not delivered");
        // The controller receives every event, in order.
        for (long seq = 1; seq <= sent; seq++) {
            JsonObject message = controller.next();
            assertEquals(seq, message.get("seq").getAsLong());
        }
        stalled.stalled = false;
        stalled.ws.request(Long.MAX_VALUE);
        // The server drops the saturated socket, possibly mid-frame. The JDK
        // client does not always report such an end, so wait for the server
        // side to close and the client to report it or stop receiving.
        await(() -> !stalledConnection.channel().isOpen());
        int[] seen = {-1};
        long[] quietSince = {System.nanoTime()};
        await(() -> {
            int size = stalled.messages.size();
            if (size != seen[0]) { seen[0] = size; quietSince[0] = System.nanoTime(); }
            return stalled.closeCode.isDone() || System.nanoTime() - quietSince[0] > TimeUnit.SECONDS.toNanos(1);
        });
        long last = 0;
        for (String raw : stalled.messages) {
            JsonObject message = JsonParser.parseString(raw).getAsJsonObject();
            if (message.get("type").getAsString().equals("event")) {
                assertEquals(++last, message.get("seq").getAsLong(), "no gap before the end");
            } else {
                assertEquals(ErrorCode.OVERLOADED.wire(), message.get("code").getAsString());
            }
        }
        // Writes still buffered in the process when it closes are discarded
        // with the connection: the agent sees a gapless prefix of the stream.
        assertTrue(last >= 1 && last <= delivered, "received a prefix, saw " + last + " of " + delivered);
        if (stalled.closeCode.isDone()) {
            try {
                int code = stalled.closeCode.get();
                assertTrue(code == 1013 || code == 1006, "event overflow or abnormal close, saw " + code);
            } catch (java.util.concurrent.ExecutionException lostCourtesyClose) { /* documented */ }
        }
    }

    @Test
    void eventOverflowOfTheControllerIsControllerLoss() throws Exception {
        Client controller = Client.connect(server.port(), "{\"type\":\"hello\",\"versions\":[2],\"events\":true}");
        AgentConnection connection = configure(controller, "{\"type\":\"configure\"}");
        controller.stalled = true;
        String padding = "p".repeat(400);
        for (long sent = 1; connection.ready() && sent < 200_000; sent++) server.sendEvent(chat(sent, padding));
        assertFalse(connection.ready());
        await(server::pollDisconnected);
        assertFalse(server.hasController());
    }
}
