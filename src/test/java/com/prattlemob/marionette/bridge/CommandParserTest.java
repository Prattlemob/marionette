package com.prattlemob.marionette.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandParserTest {
    @Test
    void parsesHello() {
        AgentCommand command = CommandParser.parse("{\"type\": \"hello\", \"version\": 0}");
        assertEquals(new AgentCommand.Hello(0), command);
    }

    @Test
    void parsesPartialInputWithOmittedFieldsNull() {
        AgentCommand command = CommandParser.parse("{\"type\": \"input\", \"forward\": true, \"sprint\": false}");
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class, command);
        assertEquals(Boolean.TRUE, update.forward());
        assertEquals(Boolean.FALSE, update.sprint());
        assertNull(update.back());
        assertNull(update.left());
        assertNull(update.right());
        assertNull(update.jump());
        assertNull(update.sneak());
    }

    @Test
    void parsesLookAndRelease() {
        assertEquals(new AgentCommand.Look(90.0F, -12.5F),
                CommandParser.parse("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": -12.5}"));
        assertEquals(new AgentCommand.Release(),
                CommandParser.parse("{\"type\": \"release\"}"));
    }

    @Test
    void ignoresUnknownFields() {
        AgentCommand command = CommandParser.parse("{\"type\": \"release\", \"extra\": 42}");
        assertEquals(new AgentCommand.Release(), command);
    }

    @Test
    void rejectsGarbage() {
        assertThrows(ProtocolException.class, () -> CommandParser.parse("garbage"));
        assertThrows(ProtocolException.class, () -> CommandParser.parse("{\"type\": \"input\", \"forward\":"));
        assertThrows(ProtocolException.class, () -> CommandParser.parse("[1, 2, 3]"));
    }

    @Test
    void rejectsMissingOrUnknownType() {
        ProtocolException missing = assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"forward\": true}"));
        assertTrue(missing.getMessage().contains("type"));
        ProtocolException unknown = assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"fly\"}"));
        assertTrue(unknown.getMessage().contains("fly"));
    }

    @Test
    void rejectsWrongFieldTypes() {
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"input\", \"forward\": \"yes\"}"));
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": \"north\", \"pitch\": 0}"));
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": 0}")); // pitch missing
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"hello\"}")); // version missing
    }

    @Test
    void rejectsNonFiniteLook() {
        // 1e400 is valid JSON and parses as a number, but overflows float to
        // Infinity — this must trip the finiteness check, not the type check.
        assertThrows(ProtocolException.class,
                () -> CommandParser.parse("{\"type\": \"look\", \"yaw\": 1e400, \"pitch\": 0}"));
    }
}
