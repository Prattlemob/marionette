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
    private static final String HELLO = "{\"type\": \"hello\", \"versions\": [1]}";

    private BridgeServer server;

    @BeforeEach
    void startServer() {
        server = new BridgeServer(0, "test-version");
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
        WebSocket ws;

        static TestClient connect(int port) throws Exception {
            TestClient client = new TestClient();
            client.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/"), client)
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
    void helloHandshakeRepliesWithVersionCapabilitiesAndModVersion() throws Exception {
        TestClient client = TestClient.connect(server.port());
        assertFalse(server.hasController(), "not a controller before hello");
        client.send(HELLO);
        JsonObject reply = JsonParser.parseString(client.awaitMessage()).getAsJsonObject();
        assertEquals("hello", reply.get("type").getAsString());
        assertEquals(1, reply.get("version").getAsInt());
        assertTrue(reply.get("capabilities").getAsJsonObject().isEmpty());
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
        assertEquals(1, error.get("supported").getAsJsonArray().get(0).getAsInt());
        assertEquals(1002, (int) client.closeCode.get(5, TimeUnit.SECONDS));
    }

    @Test
    void secondConnectionGetsControllerAttachedThenCloses1013() throws Exception {
        TestClient first = connectAndHello();
        TestClient second = TestClient.connect(server.port());
        JsonObject error = JsonParser.parseString(second.awaitMessage()).getAsJsonObject();
        assertEquals("controller_attached", error.get("code").getAsString());
        assertEquals(1013, (int) second.closeCode.get(5, TimeUnit.SECONDS));
        // the original controller is unaffected:
        first.send("{\"type\": \"input\", \"forward\": true}");
        await(() -> server.drainCommands().stream()
                .anyMatch(c -> c instanceof AgentCommand.InputUpdate));
    }

    @Test
    void commandsAreQueuedInOrderForTheTickThread() throws Exception {
        TestClient client = connectAndHello();
        client.send("{\"type\": \"input\", \"forward\": true}");
        client.send("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": 0.0}");
        List<AgentCommand> drained = new java.util.ArrayList<>();
        await(() -> {
            drained.addAll(server.drainCommands());
            return drained.size() >= 2;
        });
        assertInstanceOf(AgentCommand.InputUpdate.class, drained.get(0));
        assertInstanceOf(AgentCommand.Look.class, drained.get(1));
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
        await(() -> server.drainCommands().stream().anyMatch(c -> c instanceof AgentCommand.Release));
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
        TestClient[] next = new TestClient[1];
        await(() -> {
            try {
                next[0] = connectAndHello();
                return true;
            } catch (Exception stillAttached) {
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
        await(() -> server.drainCommands().stream().anyMatch(c -> c instanceof AgentCommand.Release));
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
}
