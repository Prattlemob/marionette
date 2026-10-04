package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** The status query, its reply and the hello agent name (protocol/v1.md, status). */
class StatusProtocolTest {
    private static JsonObject json(ProtocolSession.Action action) {
        return JsonParser.parseString(
                assertInstanceOf(ProtocolSession.Action.Send.class, action).json()).getAsJsonObject();
    }

    @Test
    void statusParsesWithItsIdAndIgnoresUnknownFields() {
        var status = assertInstanceOf(AgentCommand.Status.class,
                MessageParser.parse("{\"type\":\"status\",\"id\":\"s1\",\"extra\":1}"));
        assertEquals(new JsonPrimitive("s1"), status.id());
        assertNull(assertInstanceOf(AgentCommand.Status.class, MessageParser.parse("{\"type\":\"status\"}")).id());
    }

    @Test
    void helloCarriesAnOptionalAgentName() {
        var hello = assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\":\"hello\",\"versions\":[2],\"agent\":\"walker\"}"));
        assertEquals("walker", hello.agent());
        assertNull(assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\":\"hello\",\"versions\":[2]}")).agent());
        assertEquals("x".repeat(64), assertInstanceOf(ParsedMessage.Hello.class, MessageParser.parse(
                "{\"type\":\"hello\",\"versions\":[2],\"agent\":\"" + "x".repeat(64) + "\"}")).agent());
    }

    @Test
    void invalidAgentNamesAreInvalidField() {
        for (String agent : List.of("\"\"", "\"" + "x".repeat(65) + "\"", "\"a\\nb\"", "\"\\u00a7cred\"", "7", "null",
                "[\"a\"]")) {
            var error = assertThrows(ProtocolError.class,
                    () -> MessageParser.parse("{\"type\":\"hello\",\"versions\":[2],\"agent\":" + agent + "}"), agent);
            assertEquals(ErrorCode.INVALID_FIELD, error.code(), agent);
        }
    }

    @Test
    void invalidAgentNameInHelloIsFatal() {
        ProtocolSession session = new ProtocolSession("test");
        var actions = session.onFrame("{\"type\":\"hello\",\"versions\":[2],\"agent\":\"\"}");
        assertEquals("invalid_field", json(actions.get(0)).get("code").getAsString());
        assertEquals(new ProtocolSession.Action.Close(1002, "invalid_field"), actions.get(1));
        assertFalse(session.isActive());
    }

    @Test
    void sessionRemembersTheAgentAndBothRolesMayQueryStatus() {
        for (String role : List.of("controller", "observer")) {
            ProtocolSession session = new ProtocolSession("test");
            session.onFrame("{\"type\":\"hello\",\"versions\":[2],\"role\":\"" + role + "\",\"agent\":\"bot\"}");
            assertEquals("bot", session.agent());
            var actions = session.onFrame("{\"type\":\"status\",\"id\":3}");
            assertEquals(1, actions.size(), role);
            var enqueue = assertInstanceOf(ProtocolSession.Action.Enqueue.class, actions.get(0));
            assertInstanceOf(AgentCommand.Status.class, enqueue.command());
        }
    }

    @Test
    void helloAdvertisesTheStatusCapability() {
        assertTrue(JsonParser.parseString(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject()
                .getAsJsonObject("capabilities").get("status").getAsBoolean());
    }

    @Test
    void idleStatusResultHasANullControllerAndTheRequestersCounters() {
        var session = new ConnectionStatus("observer", null, 1500, 4.0, 30, 2, 5, 0, 0.84, null, 5,
                List.of("world", "player"), true);
        var report = new StatusReport(StatusReport.IDLE, "human_priority", false, false, null, List.of(), false, 1,
                null, null).forSession(session);
        JsonObject reply = JsonParser.parseString(Messages.statusResult(report, new JsonPrimitive("s9")))
                .getAsJsonObject();
        assertEquals("status_result", reply.get("type").getAsString());
        assertEquals("s9", reply.get("id").getAsString());
        assertEquals("idle", reply.get("state").getAsString());
        assertTrue(reply.get("controller").isJsonNull());
        assertTrue(reply.get("tick").isJsonNull());
        assertFalse(reply.get("inWorld").getAsBoolean());
        JsonObject own = reply.getAsJsonObject("session");
        assertEquals("observer", own.get("role").getAsString());
        assertEquals(5, own.get("rateDivisor").getAsInt());
        assertEquals("[\"player\",\"world\"]", own.get("sections").toString(), "sections are sorted");
        assertTrue(own.get("events").getAsBoolean());
        assertTrue(own.get("agent").isJsonNull());
        assertEquals(0.8, own.get("rttMillis").getAsDouble(), "one decimal place");
        assertTrue(own.get("commandLatencyMillis").isJsonNull());
        assertEquals(30, own.get("observationsSent").getAsLong());
        assertEquals(2, own.get("observationsDropped").getAsLong());
        assertEquals(5, own.get("eventsSent").getAsLong());
        assertEquals(1500, own.get("connectedMillis").getAsLong());
    }

    @Test
    void connectedStatusResultReportsTheControllerAndHeldControls() {
        var controller = new ConnectionStatus("controller", "walker", 5321, 19.96, 106, 0, 0, 1, 0.8, 12.44, 1,
                List.of("player"), false);
        var report = new StatusReport(StatusReport.CONNECTED, "agent_exclusive", false, true, 812L,
                List.of("forward", "sprint"), true, 0, controller, null).forSession(controller);
        JsonObject reply = JsonParser.parseString(Messages.statusResult(report, null)).getAsJsonObject();
        assertFalse(reply.has("id"));
        assertEquals(812, reply.get("tick").getAsLong());
        assertEquals("[\"forward\",\"sprint\"]", reply.get("held").toString());
        assertTrue(reply.get("panning").getAsBoolean());
        assertEquals("agent_exclusive", reply.get("mode").getAsString());
        JsonObject attached = reply.getAsJsonObject("controller");
        assertEquals("walker", attached.get("agent").getAsString());
        assertEquals(20.0, attached.get("observationRate").getAsDouble());
        assertEquals(12.4, attached.get("commandLatencyMillis").getAsDouble());
        assertFalse(attached.has("role"), "role and settings belong to session only");
        JsonObject own = reply.getAsJsonObject("session");
        for (String key : attached.keySet()) assertEquals(attached.get(key), own.get(key), key);
    }
}
