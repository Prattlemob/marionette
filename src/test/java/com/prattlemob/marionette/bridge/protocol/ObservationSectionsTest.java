package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservationSectionsTest {
    @Test
    void masksDistinguishOmittedEmptyAndDuplicateSections() {
        assertNull(((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure"}
                """)).sections());
        assertEquals(Set.of(), ((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure","sections":[]}
                """)).sections());
        assertEquals(Set.of("player"), ((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure","sections":["player","player"]}
                """)).sections());
        assertEquals(Set.of("player", "inventory"), ((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure","sections":["inventory","player"]}
                """)).sections());
        assertEquals(Set.of("inventory"), ((ParsedMessage.Hello) MessageParser.parse("""
                {"type":"hello","versions":[2],"sections":["inventory"]}
                """)).sections());
        assertEquals(Set.of("target", "world"), ((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure","sections":["world","target"]}
                """)).sections());
        assertEquals(Set.of("player", "inventory", "target", "world"), ((ParsedMessage.Hello) MessageParser.parse("""
                {"type":"hello","versions":[2],"sections":["player","inventory","target","world"]}
                """)).sections());
        assertEquals(Set.of("entities"), ((AgentCommand.Configure) MessageParser.parse("""
                {"type":"configure","sections":["entities"]}
                """)).sections());
        assertEquals(Set.of("player", "inventory", "target", "world", "entities"), ((ParsedMessage.Hello) MessageParser.parse("""
                {"type":"hello","versions":[2],"sections":["entities","player","inventory","target","world"]}
                """)).sections());
    }

    @Test
    void invalidMasksRejectWholeMessageAndEchoId() {
        for (String value : new String[]{"null", "true", "{}", "\"player\"", "[1]",
                "[\"entity\"]", "[\"player\",\"Entities\"]", "[\"Inventory\"]", "[\"Target\"]",
                "[\"player\",\"unknown\"]"}) {
            for (String prefix : new String[]{"\"type\":\"configure\",\"rateDivisor\":4",
                    "\"type\":\"hello\",\"versions\":[2]"}) {
                ProtocolError error = assertThrows(ProtocolError.class, () -> MessageParser.parse(
                        "{" + prefix + ",\"id\":17,\"sections\":" + value + "}"));
                assertEquals(ErrorCode.INVALID_FIELD, error.code());
                assertEquals(17, error.id().getAsInt());
            }
        }
    }

    @Test
    void helloMaskDefaultsAndExplicitEmptySurviveAdmission() {
        ProtocolSession defaults = new ProtocolSession("test");
        defaults.onFrame("{\"type\":\"hello\",\"versions\":[2]}");
        assertEquals(Set.of("player"), defaults.sections());
        ProtocolSession empty = new ProtocolSession("test");
        empty.onFrame("{\"type\":\"hello\",\"versions\":[2],\"sections\":[]}");
        assertTrue(empty.isActive());
        assertEquals(Set.of(), empty.sections());
    }

    @Test
    void oldProtocolIsRejectedBeforeAdmission() {
        ProtocolSession session = new ProtocolSession("test", role -> {
            fail("v1 must not claim a role");
            return null;
        });
        var actions = session.onFrame("{\"type\":\"hello\",\"versions\":[1]}");
        assertTrue(((ProtocolSession.Action.Send) actions.getFirst()).json().contains("unsupported_version"));
        assertInstanceOf(ProtocolSession.Action.Close.class, actions.getLast());
        assertFalse(session.isActive());
    }
}
