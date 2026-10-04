package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonPrimitive;
import com.prattlemob.marionette.control.TapControl;

class MessageParserTest {
    @Test
    void parsesHelloWithVersionsRoleAndId() {
        ParsedMessage.Hello hello = assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\": \"hello\", \"versions\": [0, 1], \"role\": \"observer\", \"id\": 7}"));
        assertEquals(List.of(0, 1), hello.versions());
        assertEquals("observer", hello.role());
        assertEquals(new JsonPrimitive(7), hello.id());
    }

    @Test
    void helloRoleDefaultsToControllerAndIdDefaultsToNull() {
        ParsedMessage.Hello hello = assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\": \"hello\", \"versions\": [1]}"));
        assertEquals("controller", hello.role());
        assertNull(hello.id());
    }

    @Test
    void rejectsMalformedHello() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\"}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": []}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": [\"one\"]}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": 1}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": [1], \"role\": 5}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": [1.5]}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"hello\", \"versions\": [4000000000]}")).code());
    }

    @Test
    void parsesPartialInputWithOmittedFieldsNull() {
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\": \"input\", \"forward\": true, \"sprint\": false}"));
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
                MessageParser.parse("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": -12.5}"));
        assertEquals(new AgentCommand.Release(),
                MessageParser.parse("{\"type\": \"release\"}"));
    }

    @Test
    void ignoresUnknownFields() {
        assertEquals(new AgentCommand.Release(),
                MessageParser.parse("{\"type\": \"release\", \"extra\": 42}"));
    }

    @Test
    void acceptsStringAndNumberIdsAndRejectsOtherIdTypes() {
        // A valid id on a command parses fine (ids matter only for replies).
        MessageParser.parse("{\"type\": \"release\", \"id\": \"abc\"}");
        MessageParser.parse("{\"type\": \"release\", \"id\": 3.5}");
        ProtocolError boolId = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"release\", \"id\": true}"));
        assertEquals(ErrorCode.INVALID_FIELD, boolId.code());
        assertNull(boolId.id(), "an unreadable id must not be echoed");
        ProtocolError objId = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"release\", \"id\": {}}"));
        assertEquals(ErrorCode.INVALID_FIELD, objId.code());
    }

    @Test
    void fieldErrorsCarryTheEnvelopeId() {
        ProtocolError error = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"look\", \"id\": 12, \"yaw\": \"north\", \"pitch\": 0}"));
        assertEquals(ErrorCode.INVALID_FIELD, error.code());
        assertEquals(new JsonPrimitive(12), error.id());
    }

    @Test
    void rejectsGarbageAsInvalidJson() {
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("garbage")).code());
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"forward\":")).code());
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("[1, 2, 3]")).code());
    }

    @Test
    void rejectsMissingOrUnknownType() {
        ProtocolError missing = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"forward\": true}"));
        assertEquals(ErrorCode.INVALID_FIELD, missing.code());
        assertTrue(missing.getMessage().contains("type"));
        ProtocolError unknown = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"fly\"}"));
        assertEquals(ErrorCode.UNKNOWN_TYPE, unknown.code());
        assertTrue(unknown.getMessage().contains("fly"));
    }

    @Test
    void rejectsWrongFieldTypes() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"forward\": \"yes\"}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"look\", \"yaw\": \"north\", \"pitch\": 0}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"look\", \"yaw\": 0}")).code()); // pitch missing
    }

    @Test
    void rejectsNonFiniteLook() {
        // 1e400 is valid JSON and parses as a number, but overflows float to
        // Infinity — this must trip the finiteness check, not the type check.
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"look\", \"yaw\": 1e400, \"pitch\": 0}")).code());
    }

    @Test
    void rejectsNonStrictJson() {
        // Gson's default JsonParser.parseString is lenient; the wire contract is RFC 8259.
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{type: \"release\"}")).code());              // unquoted key
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{'type': 'release'}")).code());              // single quotes
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"release\"} trailing")).code()); // trailing data
        assertEquals(ErrorCode.INVALID_JSON, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"look\", \"yaw\": NaN, \"pitch\": 0}")).code());
    }

    @Test
    void strictParsingStillAcceptsConformingJson() {
        assertInstanceOf(AgentCommand.Release.class,
                MessageParser.parse("{\"type\": \"release\"}"));
    }

    @Test
    void parsesConfigureWithRateDivisor() {
        AgentCommand.Configure configure = assertInstanceOf(AgentCommand.Configure.class,
                MessageParser.parse("{\"type\": \"configure\", \"rateDivisor\": 5}"));
        assertEquals(5, configure.rateDivisor());
    }

    @Test
    void parsesConfigureWithNoFieldsAsNoOp() {
        AgentCommand.Configure configure = assertInstanceOf(AgentCommand.Configure.class,
                MessageParser.parse("{\"type\": \"configure\"}"));
        assertNull(configure.rateDivisor());
    }

    @Test
    void rejectsBadRateDivisor() {
        for (String bad : List.of("0", "101", "-3", "2.5", "true", "\"5\"")) {
            assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                    () -> MessageParser.parse("{\"type\": \"configure\", \"rateDivisor\": " + bad + "}")).code(),
                    "rateDivisor " + bad + " must be rejected");
        }
    }

    @Test
    void inputTapArrayParses() {
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\": \"input\", \"sprint\": true, \"tap\": [\"jump\"]}"));
        assertEquals(Set.of(TapControl.JUMP), update.taps());
        assertEquals(Boolean.TRUE, update.sprint());
    }

    @Test
    void inputWithoutTapHasEmptyTaps() {
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\": \"input\", \"forward\": true}"));
        assertEquals(Set.of(), update.taps());
    }

    @Test
    void duplicateTapEntriesCollapse() {
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\": \"input\", \"tap\": [\"jump\", \"jump\"]}"));
        assertEquals(Set.of(TapControl.JUMP), update.taps());
    }

    @Test
    void nonArrayTapIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"tap\": \"jump\"}")).code());
    }

    @Test
    void nonStringTapEntryIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"tap\": [true]}")).code());
    }

    @Test
    void unknownTapControlIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"tap\": [\"fly\"]}")).code());
    }

    @Test
    void parsedTapSetIsImmutable() {
        AgentCommand.InputUpdate update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\": \"input\", \"tap\": [\"jump\"]}"));
        assertThrows(UnsupportedOperationException.class, () -> update.taps().add(TapControl.JUMP));
    }

    @Test
    void lookWithoutModeStaysInstant() {
        var look = assertInstanceOf(AgentCommand.Look.class,
                MessageParser.parse("{\"type\": \"look\", \"yaw\": 90.0, \"pitch\": 0.0}"));
        assertEquals(90.0f, look.yaw());
    }

    @Test
    void lookModeInstantParsesExplicitly() {
        assertInstanceOf(AgentCommand.Look.class, MessageParser.parse(
                "{\"type\": \"look\", \"mode\": \"instant\", \"yaw\": 1.0, \"pitch\": 2.0}"));
    }

    @Test
    void lookModeDeltaParses() {
        var delta = assertInstanceOf(AgentCommand.LookDelta.class, MessageParser.parse(
                "{\"type\": \"look\", \"mode\": \"delta\", \"yaw\": 15.0, \"pitch\": -5.0}"));
        assertEquals(15.0f, delta.yaw());
        assertEquals(-5.0f, delta.pitch());
    }

    @Test
    void lookModeSmoothWithAnglesParses() {
        var smooth = assertInstanceOf(AgentCommand.LookSmoothAngles.class, MessageParser.parse(
                "{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 90.0, \"pitch\": 10.0, \"speed\": 1.5}"));
        assertEquals(90.0f, smooth.yaw());
        assertEquals(1.5f, smooth.speed());
    }

    @Test
    void lookModeSmoothSpeedDefaultsToNull() {
        var smooth = assertInstanceOf(AgentCommand.LookSmoothAngles.class, MessageParser.parse(
                "{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 0.0, \"pitch\": 0.0}"));
        assertNull(smooth.speed());
    }

    @Test
    void lookModeSmoothWithPointCarriesIdAndRawFrame() {
        String raw = "{\"type\": \"look\", \"mode\": \"smooth\", \"id\": 7, \"x\": 1.0, \"y\": 2.0, \"z\": 3.0}";
        var smooth = assertInstanceOf(AgentCommand.LookSmoothPoint.class, MessageParser.parse(raw));
        assertEquals(1.0, smooth.x());
        assertEquals(2.0, smooth.y());
        assertEquals(3.0, smooth.z());
        assertEquals(7, smooth.id().getAsInt());
        assertEquals(raw, smooth.raw());
        assertNull(smooth.speed());
    }

    @Test
    void lookModeSmoothRejectsBothAnglesAndPoint() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 0.0, \"pitch\": 0.0, \"x\": 1.0, \"y\": 2.0, \"z\": 3.0}");
    }

    @Test
    void lookModeSmoothRejectsNeitherShape() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\"}");
    }

    @Test
    void lookModeSmoothRejectsPartialPoint() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\", \"x\": 1.0, \"y\": 2.0}");
    }

    @Test
    void lookModeSmoothRejectsBadSpeed() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 0.0, \"pitch\": 0.0, \"speed\": 0}");
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 0.0, \"pitch\": 0.0, \"speed\": -1.5}");
        assertInvalidField("{\"type\": \"look\", \"mode\": \"smooth\", \"yaw\": 0.0, \"pitch\": 0.0, \"speed\": \"fast\"}");
    }

    @Test
    void lookRejectsUnknownMode() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"teleport\", \"yaw\": 0.0, \"pitch\": 0.0}");
    }

    @Test
    void lookModeDeltaRequiresBothFields() {
        assertInvalidField("{\"type\": \"look\", \"mode\": \"delta\", \"yaw\": 15.0}");
    }

    private void assertInvalidField(String json) {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse(json)).code());
    }

    @Test
    void idOfExtractsTheEnvelopeId() {
        assertEquals(12, MessageParser.idOf("{\"type\": \"release\", \"id\": 12}").getAsInt());
        assertEquals("a", MessageParser.idOf("{\"type\": \"release\", \"id\": \"a\"}").getAsString());
    }

    @Test
    void idOfIsNullForAbsentInvalidOrUnparseableIds() {
        assertNull(MessageParser.idOf("{\"type\": \"release\"}"));
        assertNull(MessageParser.idOf("{\"type\": \"release\", \"id\": true}"));
        assertNull(MessageParser.idOf("not json"));
    }

    @Test
    void inputAttackAndUseHoldsParse() {
        AgentCommand.InputUpdate update = (AgentCommand.InputUpdate)
                MessageParser.parse("{\"type\": \"input\", \"attack\": true, \"use\": false}");
        assertEquals(true, update.attack());
        assertEquals(false, update.use());
        assertNull(update.hotbar());
        assertNull(update.forward()); // omitted = unchanged
    }

    @Test
    void attackAndUseTapNamesParse() {
        AgentCommand.InputUpdate update = (AgentCommand.InputUpdate)
                MessageParser.parse("{\"type\": \"input\", \"tap\": [\"attack\", \"use\"]}");
        assertEquals(Set.of(TapControl.ATTACK, TapControl.USE), update.taps());
    }

    @Test
    void hotbarParses() {
        AgentCommand.InputUpdate update = (AgentCommand.InputUpdate)
                MessageParser.parse("{\"type\": \"input\", \"hotbar\": 8}");
        assertEquals(Integer.valueOf(8), update.hotbar());
    }

    @Test
    void hotbarOutOfRangeIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"hotbar\": 9}")).code());
    }

    @Test
    void hotbarNonIntegerIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"hotbar\": 2.5}")).code());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"hotbar\": \"3\"}")).code());
    }

    @Test
    void nonBooleanAttackIsInvalidField() {
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\": \"input\", \"attack\": 1}")).code());
    }

    @Test
    void helloAndConfigureCarryTheOptionalEventsSubscription() {
        var hello = assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\":\"hello\",\"versions\":[2],\"events\":true}"));
        assertEquals(Boolean.TRUE, hello.events());
        assertNull(assertInstanceOf(ParsedMessage.Hello.class,
                MessageParser.parse("{\"type\":\"hello\",\"versions\":[2]}")).events());
        var configure = assertInstanceOf(AgentCommand.Configure.class,
                MessageParser.parse("{\"type\":\"configure\",\"events\":false}"));
        assertEquals(Boolean.FALSE, configure.events());
        assertNull(configure.rateDivisor());
        assertNull(assertInstanceOf(AgentCommand.Configure.class,
                MessageParser.parse("{\"type\":\"configure\"}")).events());
        for (String bad : new String[] {"1", "\"true\"", "null", "[]"}) {
            assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                    () -> MessageParser.parse("{\"type\":\"configure\",\"events\":" + bad + "}")).code());
            assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                    () -> MessageParser.parse("{\"type\":\"hello\",\"versions\":[2],\"events\":" + bad + "}")).code());
        }
    }

    @Test
    void sessionSubscribesOnlyWhenHelloAsks() {
        var plain = new ProtocolSession("test");
        plain.onFrame("{\"type\":\"hello\",\"versions\":[2]}");
        assertFalse(plain.events());
        var subscribed = new ProtocolSession("test");
        subscribed.onFrame("{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\",\"events\":true}");
        assertTrue(subscribed.events());
    }

    @Test
    void parsesScanWithAndWithoutMin() {
        var centred = assertInstanceOf(AgentCommand.Scan.class,
                MessageParser.parse("{\"type\":\"scan\",\"id\":\"t\",\"size\":{\"x\":16,\"y\":8,\"z\":16}}"));
        assertNull(centred.min());
        assertEquals(new AgentCommand.BlockCoord(16, 8, 16), centred.size());
        assertEquals(new JsonPrimitive("t"), centred.id());
        var boxed = assertInstanceOf(AgentCommand.Scan.class, MessageParser.parse(
                "{\"type\":\"scan\",\"min\":{\"x\":-8,\"y\":-62,\"z\":-8},\"size\":{\"x\":1,\"y\":8192,\"z\":1}}"));
        assertEquals(new AgentCommand.BlockCoord(-8, -62, -8), boxed.min());
    }

    @Test
    void rejectsMalformedScans() {
        for (String frame : List.of(
                "{\"type\":\"scan\"}",
                "{\"type\":\"scan\",\"size\":[16,8,16]}",
                "{\"type\":\"scan\",\"size\":{\"x\":16,\"y\":8}}",
                "{\"type\":\"scan\",\"size\":{\"x\":0,\"y\":8,\"z\":16}}",
                "{\"type\":\"scan\",\"size\":{\"x\":8193,\"y\":1,\"z\":1}}",
                "{\"type\":\"scan\",\"size\":{\"x\":1.5,\"y\":1,\"z\":1}}",
                "{\"type\":\"scan\",\"min\":5,\"size\":{\"x\":1,\"y\":1,\"z\":1}}",
                "{\"type\":\"scan\",\"min\":{\"x\":0,\"y\":\"64\",\"z\":0},\"size\":{\"x\":1,\"y\":1,\"z\":1}}")) {
            assertEquals(ErrorCode.INVALID_FIELD,
                    assertThrows(ProtocolError.class, () -> MessageParser.parse(frame)).code(), frame);
        }
    }

    @Test
    void parsesRespawnWithItsIdAndFrame() {
        String frame = "{\"type\":\"respawn\",\"id\":\"r1\"}";
        var respawn = assertInstanceOf(AgentCommand.Respawn.class, MessageParser.parse(frame));
        assertEquals(new JsonPrimitive("r1"), respawn.id());
        assertEquals(frame, respawn.raw());
    }

    @Test
    void parsesChatTextOrCommandVerbatim() {
        var text = assertInstanceOf(AgentCommand.Chat.class,
                MessageParser.parse("{\"type\":\"chat\",\"id\":4,\"text\":\"  hi  \"}"));
        assertEquals("  hi  ", text.text(), "normalization happens on the client tick");
        assertNull(text.command());
        assertEquals(new JsonPrimitive(4), text.id());
        var command = assertInstanceOf(AgentCommand.Chat.class,
                MessageParser.parse("{\"type\":\"chat\",\"command\":\"/time set day\"}"));
        assertNull(command.text());
        assertEquals("/time set day", command.command());
    }

    @Test
    void rejectsChatWithoutExactlyOneStringField() {
        for (String frame : List.of(
                "{\"type\":\"chat\"}",
                "{\"type\":\"chat\",\"text\":\"a\",\"command\":\"b\"}",
                "{\"type\":\"chat\",\"text\":5}",
                "{\"type\":\"chat\",\"command\":null}",
                "{\"type\":\"chat\",\"text\":[\"a\"]}")) {
            assertEquals(ErrorCode.INVALID_FIELD,
                    assertThrows(ProtocolError.class, () -> MessageParser.parse(frame)).code(), frame);
        }
    }

    @Test
    void parsesSwapHandsTap() {
        var update = assertInstanceOf(AgentCommand.InputUpdate.class,
                MessageParser.parse("{\"type\":\"input\",\"tap\":[\"swap_hands\",\"use\"]}"));
        assertEquals(Set.of(TapControl.SWAP_HANDS, TapControl.USE), update.taps());
        assertEquals(ErrorCode.INVALID_FIELD, assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\":\"input\",\"tap\":[\"swapHands\"]}")).code());
    }
}
