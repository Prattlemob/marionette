package com.prattlemob.marionette.bridge.protocol;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class InventoryProtocolTest {
    private static final String MENU = "\"menu\":{\"type\":\"minecraft:inventory\",\"containerId\":0,\"stateId\":7}";

    @Test void parsesMoveAndRetainsErrorContext() {
        String raw = "{\"type\":\"inventory\",\"id\":42,\"op\":\"move\"," + MENU + ",\"from\":\"main.26\",\"to\":3}";
        var command = (AgentCommand.InventoryAction) MessageParser.parse(raw);
        assertEquals("main.26", command.from().alias());
        assertEquals(3, command.to().index());
        assertEquals(7, command.menu().stateId());
        assertEquals(new JsonPrimitive(42), command.id());
        assertEquals(raw, command.raw());
    }

    @ParameterizedTest @ValueSource(strings = {"open", "inspect"})
    void readsWithoutMenu(String op) {
        var command = (AgentCommand.InventoryAction) MessageParser.parse("{\"type\":\"inventory\",\"op\":\"" + op + "\"}");
        assertNull(command.menu());
        assertNull(command.from());
    }

    @ParameterizedTest @ValueSource(strings = {
        "\"op\":\"unknown\"", "\"op\":null", "\"op\":\"move\"", "\"op\":\"close\",\"menu\":null",
        "\"op\":\"close\",\"menu\":{\"type\":\"x\",\"containerId\":0,\"stateId\":-1}",
        "\"op\":\"close\",\"menu\":{\"type\":\"x\",\"containerId\":0.5,\"stateId\":1}",
        "\"op\":\"close\",\"menu\":{\"type\":\"x\",\"containerId\":0,\"stateId\":2147483648}"
    })
    void rejectsInvalidEnvelopeFields(String fields) {
        ProtocolError error = assertThrows(ProtocolError.class,
                () -> MessageParser.parse("{\"type\":\"inventory\",\"id\":\"bad\"," + fields + "}"));
        assertEquals(ErrorCode.INVALID_FIELD, error.code());
        assertEquals(new JsonPrimitive("bad"), error.id());
    }

    @ParameterizedTest @ValueSource(strings = {"-1", "1.5", "true", "null", "{}", "\"main.27\"", "\"hotbar.9\"", "\"armor.body\"", "\"main.01\""})
    void rejectsInvalidSlots(String from) {
        assertThrows(ProtocolError.class, () -> MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"drop\"," + MENU + ",\"from\":" + from + "}"));
    }

    @Test void parsesSwapEquipDropAndClose() {
        var swap = (AgentCommand.InventoryAction) MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"swap\"," + MENU + ",\"from\":\"offhand\",\"hotbar\":8}");
        assertEquals(8, swap.hotbar());
        var drop = (AgentCommand.InventoryAction) MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"drop\"," + MENU + ",\"from\":0,\"all\":true}");
        assertTrue(drop.all());
        var equip = (AgentCommand.InventoryAction) MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"equip\"," + MENU + ",\"from\":\"armor.feet\"}");
        assertEquals("armor.feet", equip.from().alias());
        var close = (AgentCommand.InventoryAction) MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"close\"," + MENU + "}");
        assertNull(close.from());
        assertThrows(ProtocolError.class, () -> MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"swap\"," + MENU + ",\"from\":0,\"hotbar\":9}"));
        assertThrows(ProtocolError.class, () -> MessageParser.parse(
                "{\"type\":\"inventory\",\"op\":\"drop\"," + MENU + ",\"from\":0,\"all\":1}"));
    }

    @Test void observerCannotInspectOrMutateAndControllerEnqueues() {
        ProtocolSession observer = new ProtocolSession("test");
        observer.onFrame("{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\"}");
        for (String op : new String[]{"inspect", "open"}) {
            var actions = observer.onFrame("{\"type\":\"inventory\",\"id\":5,\"op\":\"" + op + "\"}");
            assertEquals(1, actions.size());
            var error = JsonParser.parseString(((ProtocolSession.Action.Send) actions.getFirst()).json()).getAsJsonObject();
            assertEquals("role_forbidden", error.get("code").getAsString());
            assertEquals(5, error.get("id").getAsInt());
            assertTrue(observer.isActive());
        }
        ProtocolSession controller = new ProtocolSession("test");
        controller.onFrame("{\"type\":\"hello\",\"versions\":[2]}");
        assertInstanceOf(ProtocolSession.Action.Enqueue.class,
                controller.onFrame("{\"type\":\"inventory\",\"op\":\"open\"}").getFirst());
    }

    @Test void animationIsOptionalStrictBooleanAndStillControllerOnly() {
        var plain = (AgentCommand.InventoryAction) MessageParser.parse("{\"type\":\"inventory\",\"op\":\"open\"}");
        assertFalse(plain.animated());
        var animated = (AgentCommand.InventoryAction) MessageParser.parse("{\"type\":\"inventory\",\"op\":\"open\",\"animated\":true}");
        assertTrue(animated.animated());
        for (String bad : new String[]{"null", "1", "\"true\""}) {
            assertThrows(ProtocolError.class, () -> MessageParser.parse("{\"type\":\"inventory\",\"op\":\"open\",\"animated\":" + bad + "}"));
        }
        ProtocolSession observer = new ProtocolSession("test");
        observer.onFrame("{\"type\":\"hello\",\"versions\":[2],\"role\":\"observer\"}");
        var actions = observer.onFrame("{\"type\":\"inventory\",\"op\":\"drop\",\"animated\":true," + MENU + ",\"from\":0}");
        assertEquals(1, actions.size());
        assertTrue(((ProtocolSession.Action.Send) actions.getFirst()).json().contains("role_forbidden"));
        var hello = JsonParser.parseString(Messages.helloReply(1, "test", null)).getAsJsonObject();
        assertTrue(hello.getAsJsonObject("capabilities").get("inventoryAnimation").getAsBoolean());
        assertFalse(ErrorCode.INVENTORY_BUSY.fatal());
        assertFalse(ErrorCode.INVENTORY_CANCELLED.fatal());
    }

    @Test void resultAndCapabilityAreAdditive() {
        var hello = JsonParser.parseString(Messages.helloReply(1, "test", null)).getAsJsonObject();
        assertTrue(hello.getAsJsonObject("capabilities").get("inventory").getAsBoolean());
        var result = JsonParser.parseString(Messages.inventoryResult("close", new JsonPrimitive("done"), null)).getAsJsonObject();
        assertEquals("inventory_result", result.get("type").getAsString());
        assertEquals("done", result.get("id").getAsString());
        assertTrue(result.get("menu").isJsonNull());
        for (var code : new ErrorCode[]{ErrorCode.STALE_MENU, ErrorCode.INVENTORY_UNAVAILABLE, ErrorCode.INVENTORY_IMPOSSIBLE}) {
            assertFalse(code.fatal());
        }
    }
}
