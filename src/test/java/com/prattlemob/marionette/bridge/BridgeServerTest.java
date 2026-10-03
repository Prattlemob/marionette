package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
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

class BridgeServerTest {
    private static final String HELLO = "{\"type\": \"hello\", \"versions\": [2]}";

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

    /** Poll a condition until it holds or 5 s pass. */
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting for condition");
            }
            Thread.sleep(10);
        }
    }

    /** Minimal JDK WebSocket client collecting text messages and the close code. */
    private static final class TestClient implements WebSocket.Listener {
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        volatile boolean stalled;
        WebSocket ws;

        void stallReads() {
            stalled = true;
        }

        void resumeReads() {
            stalled = false;
            ws.request(1);
        }

        static TestClient connect(int port) throws Exception {
            return connect("127.0.0.1", port);
        }

        static TestClient connect(String host, int port) throws Exception {
            TestClient client = new TestClient();
            client.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://" + host + ":" + port + "/"), client)
                    .get(5, TimeUnit.SECONDS);
            return client;
        }

        void send(String text) {
            ws.sendText(text, true).join();
        }

        String awaitMessage() throws InterruptedException {
            String message = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(message, "timed out waiting for a message");
            return message;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                messages.add(partial.toString());
                partial.setLength(0);
            }
            if (!stalled) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }

    private TestClient connectAndHello() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send(HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        return client;
    }

    @Test
    void masksAreIndependentAndSnapshotIsLazyAndShared() throws Exception {
        var samples = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<JsonObject> snapshot = () -> {
            samples.incrementAndGet();
            JsonObject player = new JsonObject();
            player.addProperty("health", 17);
            return player;
        };
        server.sendPlayerObservation(1, 1, snapshot);
        assertEquals(0, samples.get());
        TestClient controller = connectAndHello();
        TestClient observer = TestClient.connect(server.port());
        observer.send("""
                {"type":"hello","versions":[2],"role":"observer","sections":[]}
                """);
        observer.awaitMessage();
        // Drain a command to establish readiness after hello and obtain its connection.
        observer.send("""
                {"type":"configure","sections":["player"],"rateDivisor":2}
                """);
        List<BridgeServer.Received> commands = new java.util.ArrayList<>();
        await(() -> { commands.addAll(server.drainCommands()); return !commands.isEmpty(); });
        var connection = commands.getFirst().from();
        server.sendPlayerObservation(1, 1, snapshot);
        assertTrue(JsonParser.parseString(controller.awaitMessage()).getAsJsonObject().has("player"));
        assertFalse(JsonParser.parseString(observer.awaitMessage()).getAsJsonObject().has("player"));
        assertEquals(1, samples.get());
        var settings = (AgentCommand.Configure) commands.getFirst().command();
        connection.setSections(settings.sections());
        connection.setRateDivisor(settings.rateDivisor());
        server.sendPlayerObservation(2, 1, snapshot);
        assertEquals(controller.awaitMessage(), observer.awaitMessage());
        assertEquals(2, samples.get(), "both connections must share one snapshot");
        server.sendPlayerObservation(3, 1, snapshot);
        controller.awaitMessage();
        assertTrue(observer.messages.isEmpty(), "observer cadence must stay independent");
        connection.setSections(java.util.Set.of());
        controller.ws.abort();
        await(server::pollDisconnected);
        server.sendPlayerObservation(4, 1, snapshot);
        assertEquals(2, JsonParser.parseString(observer.awaitMessage()).getAsJsonObject().size());
        assertEquals(3, samples.get(), "empty-only recipients must not sample game state");
    }

    private static final String OBSERVER_HELLO =
            "{\"type\": \"hello\", \"versions\": [2], \"role\": \"observer\"}";

    private TestClient connectObserver() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send(OBSERVER_HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        return client;
    }

    @Test
    void helloHandshakeRepliesWithVersionCapabilitiesAndModVersion() throws Exception {
        TestClient client = TestClient.connect(server.port());
        assertFalse(server.hasController(), "not a controller before hello");
        client.send(HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        assertEquals(2, reply.get("version").getAsInt());
        assertTrue(reply.get("capabilities").getAsJsonObject().get("configure").getAsBoolean());
        assertEquals("test-version", reply.get("mod").getAsString());
        await(server::hasController);
    }

    @Test
    void nonHelloFirstMessageGetsErrorThenCloses1002() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send("{\"type\": \"input\", \"forward\": true}");
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello_required", error.get("code").getAsString());
        assertEquals(1002, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        assertFalse(server.hasController());
    }

    @Test
    void unsupportedVersionGetsErrorWithSupportedThenCloses1002() throws Exception {
        TestClient client = TestClient.connect(server.port());
        client.send("{\"type\": \"hello\", \"versions\": [99]}");
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("unsupported_version", error.get("code").getAsString());
        assertEquals(2, error.get("supported").getAsJsonArray().get(0).getAsInt());
        assertEquals(1002, (int) client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void secondControllerHelloGetsControllerAttachedThenCloses1013() throws Exception {
        TestClient first = connectAndHello();
        TestClient second = TestClient.connect(server.port());
        second.send(HELLO);
        JsonObject error = JsonParser.parseString(second.awaitMessage()).getAsJsonObject();
        assertEquals("controller_attached", error.get("code").getAsString());
        assertEquals(HELLO, error.get("input").getAsString());
        assertEquals(1013, (int) second.closeCode.get(5, TimeUnit.SECONDS));
        // the original controller is unaffected:
        first.send("{\"type\": \"input\", \"forward\": true}");
        await(() -> server.drainCommands().stream()
                .anyMatch(r -> r.command() instanceof AgentCommand.InputUpdate));
    }

    @Test
    void commandsAreQueuedInOrderForTheTickThread() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\": \"input\", \"forward\": true}");
        client.send("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": 0.0}");
        List<BridgeServer.Received> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return drained.size() >= 2;
        });
        assertInstanceOf(AgentCommand.InputUpdate.class, drained.get(0).command());
        assertInstanceOf(AgentCommand.Look.class, drained.get(1).command());
    }

    @Test
    void malformedMessageGetsErrorReplyAndConnectionSurvives() throws Exception {
        TestClient client = connectAndHello();
        client.send("garbage");
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("error", error.get("type").getAsString());
        assertNotNull(error.get("message"));
        assertNotNull(error.get("code"));
        // still alive and functional:
        client.send("{\"type\": \"release\"}");
        await(() -> server.drainCommands().stream().anyMatch(r -> r.command() instanceof AgentCommand.Release));
    }

    @Test
    void invalidHotbarRejectsTheWholeInteractionWithoutClosingTheSession() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\":\"input\",\"id\":\"bad-slot\",\"hotbar\":9,\"attack\":true,\"tap\":[\"use\"]}");
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("invalid_field", error.get("code").getAsString());
        assertEquals("bad-slot", error.get("id").getAsString());
        client.send("{\"type\":\"release\"}");
        List<BridgeServer.Received> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return !drained.isEmpty();
        });
        assertEquals(1, drained.size(), "invalid input must not queue attack or use");
        assertInstanceOf(AgentCommand.Release.class, drained.getFirst().command());
        assertTrue(server.hasController());
    }

    @Test
    void abruptDisconnectSignalsOnceAndAllowsReconnect() throws Exception {
        TestClient client = connectAndHello();
        client.ws.abort(); // no close frame — the kill -9 analogue
        await(server::pollDisconnected);
        assertFalse(server.pollDisconnected(), "signal is one-shot");
        await(() -> !server.hasController());
        // reconnect works without restarting the server:
        TestClient again = connectAndHello();
        await(server::hasController);
        again.ws.abort();
        await(server::pollDisconnected);
    }

    @Test
    void observationsReachTheAgentAndAreDroppedWhenNoController() throws Exception {
        server.sendObservation("{\"type\": \"observation\", \"tick\": 0}"); // no controller: silently dropped
        TestClient client = connectAndHello();
        server.sendObservation("{\"type\": \"observation\", \"tick\": 1}");
        JsonObject observation = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals(1, observation.get("tick").getAsInt());
    }

    @Test
    void neverHelloConnectionDoesNotSignalAgentLossOnClose() throws Exception {
        TestClient probe = TestClient.connect(server.port());
        probe.ws.abort(); // never sent hello
        // Deterministic ordering: the controller slot frees only in
        // channelInactive, so once a new connection completes hello, the
        // probe's close has been fully processed.
        TestClient[] next = new TestClient[2];
        await(() -> {
            try {
                next[0] = connectAndHello();
                return true;
            } catch (Exception | AssertionError stillAttached) {
                return false;
            }
        });
        assertFalse(server.pollDisconnected(),
                "a connection that never completed hello must not latch a disconnect");
    }

    @Test
    void duplicateHelloIsNonFatalError() throws Exception {
        TestClient client = connectAndHello();
        client.send(HELLO);
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("unexpected_hello", error.get("code").getAsString());
        // still alive and functional:
        client.send("{\"type\": \"release\"}");
        await(() -> server.drainCommands().stream().anyMatch(r -> r.command() instanceof AgentCommand.Release));
    }

    @Test
    void errorEchoesIdAndOffendingInput() throws Exception {
        TestClient client = connectAndHello();
        String frame = "{\"type\": \"look\", \"id\": 12, \"yaw\": \"north\", \"pitch\": 0}";
        client.send(frame);
        JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("invalid_field", error.get("code").getAsString());
        assertEquals(12, error.get("id").getAsInt());
        assertEquals(frame, error.get("input").getAsString());
    }

    @Test
    void binaryFrameCloses1003() throws Exception {
        TestClient client = connectAndHello();
        client.ws.sendBinary(ByteBuffer.wrap(new byte[] {1, 2, 3}), true).join();
        assertEquals(1003, (int) client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void stopSendsGoingAwayCloseToController() throws Exception {
        TestClient client = connectAndHello();
        server.stop();
        assertEquals(1001, (int) client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void eventLoopThreadsAreNamedDaemons() {
        var bridgeThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("marionette-bridge")).toList();
        assertFalse(bridgeThreads.isEmpty(), "expected a marionette-bridge event-loop thread");
        assertTrue(bridgeThreads.stream().allMatch(Thread::isDaemon));
    }

    @Test
    void binaryFrameViolationStillSignalsAgentLoss() throws Exception {
        TestClient client = connectAndHello();
        client.ws.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).join();
        assertEquals(1003, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        await(server::pollDisconnected); // safety: release-all must still fire
    }

    @Test
    void framesPipelinedBehindABinaryViolationAreIgnored() throws Exception {
        TestClient client = connectAndHello();
        client.ws.sendBinary(ByteBuffer.wrap(new byte[] {1}), true).join();
        try {
            client.ws.sendText("{\"type\": \"release\"}", true).join();
        } catch (Exception alreadyClosing) {
            // the server may have torn the connection down first — fine
        }
        assertEquals(1003, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        Thread.sleep(100); // grace for any (incorrectly) processed pipelined frame
        assertTrue(server.drainCommands().isEmpty(),
                "text behind a binary violation must never be enqueued");
    }

    @Test
    void bindsTheConfiguredNonDefaultLoopbackAddress() throws Exception {
        // 127.0.0.53 is a valid loopback address on Linux without configuration.
        BridgeServer other = new BridgeServer("127.0.0.53", 0, "test-version", 2, 10_000);
        other.start();
        try {
            TestClient client = TestClient.connect("127.0.0.53", other.port());
            client.send(HELLO);
            JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
            assertEquals("hello", reply.get("type").getAsString());
        } finally {
            other.stop();
        }
    }

    @Test
    void oversizedMessageClosesTheConnection() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\": \"release\", \"pad\": \"" + "x".repeat(70_000) + "\"}");
        // Netty's frame decoder rejects messages over MAX_FRAME_BYTES with a
        // 1009 close frame. If the JDK client fragments the message instead,
        // the frame aggregator trips and the server closes without a close
        // frame — accept either terminal signal, but the connection must die.
        try {
            assertEquals(1009, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException abnormalClose) {
            // closed without a close frame — acceptable; see note above
        }
        await(() -> !server.hasController());
    }

    @Test
    void serverPingsPeriodicallyAndRecordsThePong() throws Exception {
        BridgeServer fast = new BridgeServer("127.0.0.1", 0, "test-version", 2, 100, 10_000);
        fast.start();
        try {
            TestClient client = TestClient.connect(fast.port());
            client.send(HELLO);
            client.awaitMessage(); // hello reply
            assertEquals(0, fast.lastPongNanos(), "no pong before the first ping interval");
            await(() -> fast.lastPongNanos() != 0);
        } finally {
            fast.stop();
        }
    }

    @Test
    void slowConsumerCoalescesToLatestWithBoundedBuffer() throws Exception {
        TestClient client = connectAndHello();
        client.stallReads();
        // Hammer frames from this (tick-analogue) thread until the writability
        // gate engages. TCP + Netty buffers absorb the first ~hundreds of KB.
        String lastSent = null;
        long tick = 0;
        while (server.coalescedObservations() < 101 && tick < 500_000) {
            lastSent = "{\"type\": \"observation\", \"tick\": " + tick++ + "}";
            server.sendObservation(lastSent);
        }
        assertTrue(server.coalescedObservations() > 0, "writability gate never engaged");
        long coalescedAtStall = server.coalescedObservations();
        // Latest-wins: after the agent resumes reading, the last frame it
        // eventually receives is the newest one sent, not a stale backlog tail.
        client.resumeReads();
        String expected = lastSent;
        await(() -> {
            String message;
            while ((message = client.messages.poll()) != null) {
                if (message.equals(expected)) {
                    return true;
                }
            }
            return false;
        });
        // Bounded: the coalesced counter kept growing instead of the queue.
        assertTrue(coalescedAtStall > 100,
                "expected sustained coalescing while stalled, saw " + coalescedAtStall);
    }

    @Test
    void stalledObserverDropsItsOwnFramesWhileControllerStreamsUnaffected() throws Exception {
        TestClient controller = connectAndHello();
        TestClient observer = connectObserver();
        observer.stallReads();
        // Hammer frames from this (tick-analogue) thread until the observer's
        // writability gate engages. The controller keeps reading normally, so
        // every coalesced frame counted here can only be the observer's own.
        String lastSent = null;
        long tick = 0;
        while (server.coalescedObservations() < 101 && tick < 500_000) {
            lastSent = "{\"type\": \"observation\", \"tick\": " + tick++ + "}";
            server.sendObservation(lastSent);
        }
        assertTrue(server.coalescedObservations() > 0, "writability gate never engaged");
        long coalescedAtStall = server.coalescedObservations();
        // The controller's stream is unaffected: it keeps receiving fresh
        // frames the whole time the observer is stalled.
        String finalTick = lastSent;
        await(() -> {
            String message;
            while ((message = controller.messages.poll()) != null) {
                if (message.equals(finalTick)) {
                    return true;
                }
            }
            return false;
        });
        // Latest-wins for the observer too, once it resumes.
        observer.resumeReads();
        String expected = lastSent;
        await(() -> {
            String message;
            while ((message = observer.messages.poll()) != null) {
                if (message.equals(expected)) {
                    return true;
                }
            }
            return false;
        });
        assertTrue(coalescedAtStall > 100,
                "expected sustained coalescing on the observer while stalled, saw " + coalescedAtStall);
    }

    @Test
    void controllerLossPrunesOnlyControllerOriginatedInboundCommands() throws Exception {
        TestClient controller = connectAndHello();
        TestClient observer = connectObserver();
        observer.send("{\"type\": \"configure\", \"rateDivisor\": 7}");
        controller.send("{\"type\": \"release\"}"); // deliberately not drained
        controller.ws.abort();
        await(server::pollDisconnected);
        // Collect across drains: the observer's Configure may enqueue after
        // the abort races the drain loop, so keep polling until it shows up
        // rather than asserting on a single drain snapshot.
        List<BridgeServer.Received> collected = new java.util.ArrayList<>();
        await(() -> {
            collected.addAll(server.drainCommands());
            return collected.stream().anyMatch(r -> r.command() instanceof AgentCommand.Configure
                    && r.from().role() == com.prattlemob.marionette.bridge.protocol.Role.OBSERVER);
        });
        assertTrue(collected.stream().noneMatch(
                r -> r.from().role() == com.prattlemob.marionette.bridge.protocol.Role.CONTROLLER),
                "no controller-originated command should have survived the controller's loss");
    }

    @Test
    void sendReliableRoutesAnErrorToTheOriginConnection() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\": \"release\"}");
        List<BridgeServer.Received> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return !drained.isEmpty();
        });
        drained.get(0).from().sendReliable(
                "{\"type\": \"error\", \"code\": \"invalid_field\", \"message\": \"test\"}");
        JsonObject json = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("error", json.get("type").getAsString());
    }

    @Test
    void connectionThatNeverSendsHelloIsClosedAtTheTimeout() throws Exception {
        BridgeServer fast = new BridgeServer("127.0.0.1", 0, "test-version", 2, 10_000, 200);
        fast.start();
        try {
            TestClient idle = TestClient.connect(fast.port());
            assertEquals(1002, (int) idle.closeCode.get(5, TimeUnit.SECONDS));
            assertFalse(fast.pollDisconnected(), "a never-hello close is not an agent loss");
        } finally {
            fast.stop();
        }
    }

    @Test
    void slowHelloWithinTheTimeoutStillWorks() throws Exception {
        BridgeServer fast = new BridgeServer("127.0.0.1", 0, "test-version", 2, 10_000, 2_000);
        fast.start();
        try {
            TestClient client = TestClient.connect(fast.port());
            Thread.sleep(300); // well inside the window
            client.send(HELLO);
            JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
            assertEquals("hello", reply.get("type").getAsString());
            await(fast::hasController);
        } finally {
            fast.stop();
        }
    }

    @Test
    void twoPreHelloConnectionsMayCoexistUntilRolesAreKnown() throws Exception {
        // Admission is post-hello now: a second socket is not refused at
        // upgrade time. The first to send a controller hello wins the slot.
        TestClient a = TestClient.connect(server.port());
        TestClient b = TestClient.connect(server.port());
        b.send(HELLO);
        JsonObject reply = JsonParser.parseString(b.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        a.send(HELLO);
        JsonObject error = JsonParser.parseString(a.awaitMessage()).getAsJsonObject();
        assertEquals("controller_attached", error.get("code").getAsString());
        assertEquals(1013, (int) a.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void helloTimeoutDoesNotFireOnACompletedSession() throws Exception {
        BridgeServer fast = new BridgeServer("127.0.0.1", 0, "test-version", 2, 10_000, 200);
        fast.start();
        try {
            TestClient client = TestClient.connect(fast.port());
            client.send(HELLO);
            JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
            assertEquals("hello", reply.get("type").getAsString());
            await(fast::hasController);
            Thread.sleep(500); // well past the 200ms hello-timeout window
            assertTrue(fast.hasController(), "an established session must survive the hello timeout");
            assertFalse(client.closeCode.isDone(), "no close should have been received");
        } finally {
            fast.stop();
        }
    }

    @Test
    void observerConnectsAlongsideTheControllerInEitherOrder() throws Exception {
        TestClient observer = connectObserver();
        TestClient controller = connectAndHello();
        await(server::hasController);
        server.sendObservation("{\"type\": \"observation\", \"tick\": 5}");
        assertEquals(5, JsonParser.parseString(observer.awaitMessage())
                .getAsJsonObject().get("tick").getAsInt());
        assertEquals(5, JsonParser.parseString(controller.awaitMessage())
                .getAsJsonObject().get("tick").getAsInt());
    }

    @Test
    void observerBeyondTheCapIsRefused1013() throws Exception {
        connectObserver();
        connectObserver(); // cap is 2
        TestClient third = TestClient.connect(server.port());
        third.send(OBSERVER_HELLO);
        JsonObject error = JsonParser.parseString(third.awaitMessage()).getAsJsonObject();
        assertEquals("observer_attached", error.get("code").getAsString());
        assertEquals(1013, (int) third.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void zeroMaxObserversDisablesTheRole() throws Exception {
        BridgeServer locked = new BridgeServer("127.0.0.1", 0, "test-version", 0, 10_000);
        locked.start();
        try {
            TestClient client = TestClient.connect(locked.port());
            client.send(OBSERVER_HELLO);
            JsonObject error = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
            assertEquals("observer_attached", error.get("code").getAsString());
            assertEquals(1013, (int) client.closeCode.get(5, TimeUnit.SECONDS));
        } finally {
            locked.stop();
        }
    }

    @Test
    void observerLossIsNotAnAgentLoss() throws Exception {
        connectAndHello();
        TestClient doomed = connectObserver();
        connectObserver(); // fill the cap (2) so both slots are genuinely full
        doomed.ws.abort(); // kill -9 analogue
        // Deterministic settle: with the cap full, a replacement observer's
        // hello can only be admitted once the dead one's channelInactive
        // frees its slot — the retry below is a real synchronization point
        // (it fails on every attempt until that happens), not a vacuously
        // first-try success.
        await(() -> {
            try {
                connectObserver();
                return true;
            } catch (Exception | AssertionError slotStillHeld) {
                return false;
            }
        });
        assertFalse(server.pollDisconnected(), "observer loss must never latch release-all");
        assertTrue(server.hasController(), "controller undisturbed");
    }

    @Test
    void controllerLossReleasesWhileTheObserverKeepsWatching() throws Exception {
        TestClient controller = connectAndHello();
        TestClient observer = connectObserver();
        controller.ws.abort();
        await(server::pollDisconnected); // release-all latch fires
        await(() -> !server.hasController());
        server.sendObservation("{\"type\": \"observation\", \"tick\": 9}");
        assertEquals(9, JsonParser.parseString(observer.awaitMessage())
                .getAsJsonObject().get("tick").getAsInt());
    }

    @Test
    void observerActuationIsRefusedButConfigureLands() throws Exception {
        TestClient observer = connectObserver();
        observer.send("{\"type\": \"input\", \"forward\": true}");
        JsonObject error = JsonParser.parseString(observer.awaitMessage()).getAsJsonObject();
        assertEquals("role_forbidden", error.get("code").getAsString());
        observer.send("{\"type\": \"configure\", \"rateDivisor\": 40}");
        await(() -> server.drainCommands().stream()
                .anyMatch(r -> r.command() instanceof AgentCommand.Configure
                        && r.from().role() == com.prattlemob.marionette.bridge.protocol.Role.OBSERVER));
    }

    @Test
    void dueAwareSendHonorsPerConnectionDivisorsAndSerializesOnce() throws Exception {
        TestClient controller = connectAndHello();
        TestClient observer = connectObserver();
        // Give the observer a divisor of 4 via its connection object.
        observer.send("{\"type\": \"configure\", \"rateDivisor\": 4}");
        List<BridgeServer.Received> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return drained.stream().anyMatch(r -> r.command() instanceof AgentCommand.Configure);
        });
        drained.stream().filter(r -> r.command() instanceof AgentCommand.Configure)
                .forEach(r -> r.from().setRateDivisor(
                        ((AgentCommand.Configure) r.command()).rateDivisor()));

        java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        for (long tick = 1; tick <= 4; tick++) {
            final long t = tick;
            server.sendObservation(t, 1, () -> {
                builds.incrementAndGet();
                return "{\"type\": \"observation\", \"tick\": " + t + "}";
            });
        }
        // Controller (divisor 1) got ticks 1..4; observer (divisor 4) got only tick 4.
        for (int expected = 1; expected <= 4; expected++) {
            assertEquals(expected, JsonParser.parseString(controller.awaitMessage())
                    .getAsJsonObject().get("tick").getAsInt());
        }
        assertEquals(4, JsonParser.parseString(observer.awaitMessage())
                .getAsJsonObject().get("tick").getAsInt());
        assertEquals(4, builds.get(), "frame built once per due tick, not per recipient");
        assertTrue(observer.messages.isEmpty(), "observer saw only its due tick");
    }

    @Test
    void nobodyDueMeansNoFrameBuild() throws Exception {
        connectAndHello();
        java.util.concurrent.atomic.AtomicInteger builds = new java.util.concurrent.atomic.AtomicInteger();
        server.sendObservation(3, 2, () -> { // tick 3, divisor 2: not due
            builds.incrementAndGet();
            return "{}";
        });
        assertEquals(0, builds.get());
    }
    /** Raw client deliberately never responds to ping (JDK clients auto-pong internally). */
    private static java.net.Socket silentClient(int port, String hello) throws Exception {
        var socket = new java.net.Socket("127.0.0.1", port);
        socket.setSoTimeout(2000);
        socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        int matched = 0;
        while (matched < 4) {
            int value = socket.getInputStream().read();
            if (value == "\r\n\r\n".charAt(matched)) matched++; else matched = 0;
            if (value < 0) throw new AssertionError("upgrade closed");
        }
        rawText(socket, hello);
        return socket;
    }
    private static void rawText(java.net.Socket socket, String text) throws Exception {
        byte[] payload = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payload.length < 126);
        var out = socket.getOutputStream();
        out.write(0x81); out.write(0x80 | payload.length);
        out.write(new byte[4]); out.write(payload); out.flush();
    }

    @Test
    void watchdogIgnoresOrdinaryTrafficAndIsolatesObserverLoss() throws Exception {
        server.stop();
        server = new BridgeServer(java.net.InetAddress.getLoopbackAddress(), 0, "test", 1, 2000, 300);
        server.start();
        TestClient controller = connectAndHello();
        try (var observer = silentClient(server.port(), OBSERVER_HELLO)) {
            // An application error/hello frame proves admission before the watchdog window.
            assertEquals(0x81, observer.getInputStream().read());
            Thread.sleep(600);
            assertTrue(server.hasController());
            assertFalse(server.pollDisconnected());
            connectObserver(); // slot was reclaimed by watchdog
        }
        controller.ws.abort();
        await(server::pollDisconnected);
        try (var frozen = silentClient(server.port(), HELLO)) {
            await(server::hasController);
            long start = System.nanoTime();
            while (server.hasController() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2)) {
                try { rawText(frozen, "{\"type\":\"configure\"}"); } catch (Exception closed) { break; }
                server.drainCommands();
                Thread.sleep(20);
            }
            await(server::pollDisconnected);
            assertFalse(server.hasController());
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        }
    }

    /** Reads one server frame, answering a ping with a masked pong; returns the opcode. */
    private static int answerFrame(java.net.Socket socket) throws Exception {
        var in = new java.io.DataInputStream(socket.getInputStream());
        int opcode = in.readUnsignedByte() & 0x0F;
        long length = in.readUnsignedByte() & 0x7F;
        if (length == 126) length = in.readUnsignedShort();
        else if (length == 127) length = in.readLong();
        byte[] payload = in.readNBytes((int) length);
        if (opcode == 0x9) {
            var out = socket.getOutputStream();
            out.write(0x8A); out.write(0x80 | payload.length);
            out.write(new byte[4]); out.write(payload); out.flush();
        }
        return opcode;
    }

    @Test
    void defaultWatchdogToleratesShortStallsAndDropsSilence() throws Exception {
        server.stop();
        long timeout = TimeUnit.SECONDS.toMillis(com.prattlemob.marionette.config.MarionetteConfig.pongTimeoutSeconds);
        long interval = Math.min(1000, timeout / 4);
        server = new BridgeServer(java.net.InetAddress.getLoopbackAddress(), 0, "test", 1, 2000, timeout);
        server.start();
        try (var agent = silentClient(server.port(), HELLO)) {
            await(server::hasController);
            // Stalls of half the lease stay below the timeout minus one ping interval.
            for (int stall = 0; stall < 2; stall++) {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
                while (System.nanoTime() < until) answerFrame(agent);
                Thread.sleep(timeout / 2);
            }
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (System.nanoTime() < until) answerFrame(agent);
            assertTrue(server.hasController());
            assertFalse(server.pollDisconnected());

            long silent = System.nanoTime();
            await(server::pollDisconnected);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - silent);
            assertTrue(elapsed >= timeout - interval && elapsed <= timeout + interval + 500,
                    "silent agent detected after " + elapsed + " ms");
        }
    }

    @Test
    void commandFloodClosesOnlyOffenderAndBoundsTickDrain() throws Exception {
        TestClient controller = connectAndHello();
        TestClient observer = connectObserver();
        controller.send("{\"type\":\"configure\"}");
        List<BridgeServer.Received> first = new java.util.ArrayList<>();
        await(() -> { first.addAll(server.drainCommands()); return !first.isEmpty(); });
        AgentConnection connection = first.getFirst().from();
        for (int i = 0; i < 80; i++) controller.send("{\"type\":\"input\",\"forward\":true}");
        await(() -> connection.queuedCommands() == 80);
        assertEquals(32, server.drainCommands().size());
        assertEquals(48, connection.queuedCommands());
        for (int i = 0; i < 129; i++) {
            try { controller.send("{\"type\":\"input\",\"forward\":true}"); }
            catch (Exception closed) { break; }
        }
        assertEquals("overloaded", JsonParser.parseString(controller.awaitMessage()).getAsJsonObject().get("code").getAsString());
        assertEquals(1013, controller.closeCode.get(5, TimeUnit.SECONDS));
        await(server::pollDisconnected);
        assertEquals(0, connection.queuedCommands());
        assertFalse(first.getFirst().valid());
        server.sendObservation("{\"type\":\"observation\",\"tick\":7}");
        assertTrue(observer.awaitMessage().contains("7"));
    }

    @Test
    void releaseInvalidatesAlreadyPolledWorkAndBypassesFullQueue() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\":\"input\",\"forward\":true}");
        List<BridgeServer.Received> first = new java.util.ArrayList<>();
        await(() -> { first.addAll(server.drainCommands()); return !first.isEmpty(); });
        var stale = first.getFirst();
        for (int i = 0; i < 128; i++) client.send("{\"type\":\"input\",\"forward\":true}");
        await(() -> stale.from().queuedCommands() == 128);
        client.send("{\"type\":\"release\"}");
        await(() -> !stale.valid());
        assertInstanceOf(AgentCommand.Release.class, server.pollRelease().command());
        assertTrue(server.drainCommands().isEmpty());
        assertTrue(server.hasController());
        server.disconnectController("local panic");
        assertFalse(server.hasController(), "panic invalidates synchronously");
        assertTrue(server.pollDisconnected());
        assertEquals(1008, client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void originIsRejectedIncludingNullButNativeClientsWork() throws Exception {
        for (String origin : List.of("https://example.com", "null", "http://localhost")) {
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"))
                    .header("Origin", origin).GET().build();
            assertEquals(403, HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }
        connectAndHello();
    }

    @Test
    void rawTcpPendingConnectionsAreCappedAndTimeoutBeforeUpgrade() throws Exception {
        server.stop();
        server = new BridgeServer("127.0.0.1", 0, "test", 2, 100, 1000);
        server.start();
        List<java.net.Socket> sockets = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 9; i++) {
                var socket = new java.net.Socket("127.0.0.1", server.port());
                socket.setSoTimeout(2500);
                sockets.add(socket);
            }
            assertEquals(-1, sockets.getLast().getInputStream().read());
            assertEquals(-1, sockets.getFirst().getInputStream().read());
            assertFalse(server.pollDisconnected());
        } finally { for (var socket : sockets) socket.close(); }
        connectAndHello();
    }

    @Test
    void reliableRepliesToAStalledReaderCloseInsteadOfGrowingWithoutBound() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\":\"configure\"}");
        List<BridgeServer.Received> first = new java.util.ArrayList<>();
        await(() -> { first.addAll(server.drainCommands()); return !first.isEmpty(); });
        var connection = first.getFirst().from();
        TestClient observer = connectObserver();
        client.stallReads();
        String invalid = "{\"type\":\"unknown\",\"id\":\"" + "x".repeat(60000) + "\"}";
        for (int i = 0; i < 200 && connection.ready(); i++) {
            try { client.send(invalid); } catch (Exception closed) { break; }
            assertTrue(connection.outboundBytes() <= AgentConnection.OUTBOUND_BYTES);
        }
        await(() -> !connection.ready());
        await(server::pollDisconnected);
        assertTrue(connection.outboundBytes() <= AgentConnection.OUTBOUND_BYTES);
        client.resumeReads();
        // A saturated TCP stream may end before its courtesy close is delivered.
        try {
            int code = client.closeCode.get(5, TimeUnit.SECONDS);
            assertTrue(code == 1013 || code == 1006,
                    "overload close or abnormal close when its courtesy frame cannot drain");
        } catch (java.util.concurrent.ExecutionException lostCourtesyClose) { /* documented */ }
        server.sendObservation("{\"type\":\"observation\",\"tick\":99}");
        assertTrue(observer.awaitMessage().contains("99"));
    }

    @Test
    void pingFloodFromANonReaderCannotBypassOutputBackpressure() throws Exception {
        try (var socket = silentClient(server.port(), HELLO)) {
            socket.setReceiveBufferSize(1024);
            await(server::hasController);
            byte[] flood = new byte[131 * 50000]; // masked 125-byte control frames
            for (int offset = 0; offset < flood.length; offset += 131) {
                flood[offset] = (byte) 0x89;
                flood[offset + 1] = (byte) (0x80 | 125);
            }
            long start = System.nanoTime();
            try { socket.getOutputStream().write(flood); }
            catch (java.net.SocketException closedDuringFlood) { /* expected */ }
            await(server::pollDisconnected);
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(4),
                    "backpressure must close before the five-second watchdog");
            assertFalse(server.hasController());
        }
    }

}
