package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ProtocolSessionTest {
    private static final String HELLO = "{\"type\": \"hello\", \"versions\": [1]}";

    private final ProtocolSession session = new ProtocolSession("test-version");

    private static JsonObject json(ProtocolSession.Action action) {
        return JsonParser.parseString(
                assertInstanceOf(ProtocolSession.Action.Send.class, action).json()).getAsJsonObject();
    }

    private void activate() {
        session.onFrame(HELLO);
        assertTrue(session.isActive());
    }

    @Test
    void helloActivatesAndReplies() {
        assertFalse(session.isActive());
        List<ProtocolSession.Action> actions = session.onFrame(HELLO);
        assertEquals(1, actions.size());
        JsonObject reply = json(actions.get(0));
        assertEquals("hello", reply.get("type").getAsString());
        assertEquals(1, reply.get("version").getAsInt());
        assertEquals("test-version", reply.get("mod").getAsString());
        assertTrue(reply.get("capabilities").getAsJsonObject().isEmpty());
        assertTrue(session.isActive());
    }

    @Test
    void helloSelectsTheSharedVersionFromMany() {
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"hello\", \"versions\": [0, 1, 99]}");
        assertEquals(1, json(actions.get(0)).get("version").getAsInt());
        assertTrue(session.isActive());
    }

    @Test
    void helloEchoesId() {
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"hello\", \"versions\": [1], \"id\": 7}");
        assertEquals(7, json(actions.get(0)).get("id").getAsInt());
    }

    @Test
    void unsupportedVersionSendsErrorWithSupportedThenCloses1002() {
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"hello\", \"versions\": [99]}");
        assertEquals(2, actions.size());
        JsonObject error = json(actions.get(0));
        assertEquals("unsupported_version", error.get("code").getAsString());
        assertEquals(1, error.get("supported").getAsJsonArray().get(0).getAsInt());
        ProtocolSession.Action.Close close =
                assertInstanceOf(ProtocolSession.Action.Close.class, actions.get(1));
        assertEquals(1002, close.code());
        assertFalse(session.isActive());
    }

    @Test
    void unsupportedRoleSendsErrorThenCloses1002() {
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"hello\", \"versions\": [1], \"role\": \"observer\"}");
        assertEquals(2, actions.size());
        assertEquals("unsupported_role", json(actions.get(0)).get("code").getAsString());
        assertEquals(1002, assertInstanceOf(ProtocolSession.Action.Close.class, actions.get(1)).code());
        assertFalse(session.isActive());
    }

    @Test
    void commandBeforeHelloSendsHelloRequiredThenCloses1002() {
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"input\", \"forward\": true}");
        assertEquals(2, actions.size());
        assertEquals("hello_required", json(actions.get(0)).get("code").getAsString());
        assertEquals(1002, assertInstanceOf(ProtocolSession.Action.Close.class, actions.get(1)).code());
        assertFalse(session.isActive());
    }

    @Test
    void garbageBeforeHelloIsFatal() {
        List<ProtocolSession.Action> actions = session.onFrame("garbage");
        assertEquals(2, actions.size());
        assertEquals("invalid_json", json(actions.get(0)).get("code").getAsString());
        assertEquals(1002, assertInstanceOf(ProtocolSession.Action.Close.class, actions.get(1)).code());
    }

    @Test
    void malformedHelloIsFatal() {
        List<ProtocolSession.Action> actions = session.onFrame("{\"type\": \"hello\"}");
        assertEquals(2, actions.size());
        assertEquals("invalid_field", json(actions.get(0)).get("code").getAsString());
        assertEquals(1002, assertInstanceOf(ProtocolSession.Action.Close.class, actions.get(1)).code());
    }

    @Test
    void duplicateHelloIsNonFatal() {
        activate();
        List<ProtocolSession.Action> actions = session.onFrame(HELLO);
        assertEquals(1, actions.size());
        assertEquals("unexpected_hello", json(actions.get(0)).get("code").getAsString());
        assertTrue(session.isActive());
    }

    @Test
    void commandsEnqueueOnceActive() {
        activate();
        List<ProtocolSession.Action> actions =
                session.onFrame("{\"type\": \"input\", \"forward\": true}");
        assertEquals(1, actions.size());
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                assertInstanceOf(ProtocolSession.Action.Enqueue.class, actions.get(0)).command());
        assertEquals(Boolean.TRUE, update.forward());
        assertInstanceOf(ProtocolSession.Action.Enqueue.class,
                session.onFrame("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": 0.0}").get(0));
        assertInstanceOf(ProtocolSession.Action.Enqueue.class,
                session.onFrame("{\"type\": \"release\"}").get(0));
    }

    @Test
    void invalidFieldWhenActiveIsNonFatalAndEchoesIdAndInput() {
        activate();
        String frame = "{\"type\": \"look\", \"id\": 12, \"yaw\": \"north\", \"pitch\": 0}";
        List<ProtocolSession.Action> actions = session.onFrame(frame);
        assertEquals(1, actions.size());
        JsonObject error = json(actions.get(0));
        assertEquals("invalid_field", error.get("code").getAsString());
        assertEquals(12, error.get("id").getAsInt());
        assertEquals(frame, error.get("input").getAsString());
        assertTrue(session.isActive());
    }

    @Test
    void garbageWhenActiveIsNonFatalAndInputEchoTruncates() {
        activate();
        List<ProtocolSession.Action> actions = session.onFrame("x".repeat(300));
        assertEquals(1, actions.size());
        JsonObject error = json(actions.get(0));
        assertEquals("invalid_json", error.get("code").getAsString());
        assertEquals(256, error.get("input").getAsString().length());
        assertTrue(session.isActive());
    }

    @Test
    void closedSessionIgnoresFrames() {
        session.onFrame("garbage"); // fatal pre-hello
        assertTrue(session.onFrame(HELLO).isEmpty());
        assertFalse(session.isActive());
    }
}
