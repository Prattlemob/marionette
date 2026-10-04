package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

class MessagesTest {
    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void helloReplyCarriesVersionCapabilitiesAndModVersion() {
        JsonObject reply = parse(Messages.helloReply(1, "0.1.0", null));
        assertEquals("hello", reply.get("type").getAsString());
        assertEquals(1, reply.get("version").getAsInt());
        assertTrue(reply.get("capabilities").getAsJsonObject().get("configure").getAsBoolean());
        assertTrue(reply.get("capabilities").getAsJsonObject().get("tap").getAsBoolean());
        assertEquals("0.1.0", reply.get("mod").getAsString());
        assertFalse(reply.has("id"));
    }

    @Test
    void helloReplyEchoesId() {
        JsonObject reply = parse(Messages.helloReply(1, "0.1.0", new JsonPrimitive(7)));
        assertEquals(7, reply.get("id").getAsInt());
    }

    @Test
    void errorCarriesCodeMessageIdAndInput() {
        JsonObject error = parse(Messages.error(ErrorCode.INVALID_FIELD,
                "field \"pitch\" must be a number", new JsonPrimitive("a1"), "{\"bad\":1}"));
        assertEquals("error", error.get("type").getAsString());
        assertEquals("invalid_field", error.get("code").getAsString());
        assertEquals("field \"pitch\" must be a number", error.get("message").getAsString());
        assertEquals("a1", error.get("id").getAsString());
        assertEquals("{\"bad\":1}", error.get("input").getAsString());
    }

    @Test
    void errorOmitsAbsentIdAndInput() {
        JsonObject error = parse(Messages.error(ErrorCode.INVALID_JSON, "malformed JSON", null, null));
        assertFalse(error.has("id"));
        assertFalse(error.has("input"));
    }

    @Test
    void errorInputEchoTruncatesTo256Chars() {
        String input = "x".repeat(300);
        JsonObject error = parse(Messages.error(ErrorCode.INVALID_JSON, "malformed JSON", null, input));
        assertEquals(256, error.get("input").getAsString().length());
    }

    @Test
    void unsupportedVersionErrorListsSupportedVersions() {
        JsonObject error = parse(Messages.unsupportedVersionError(List.of(1), null, "{}"));
        assertEquals("unsupported_version", error.get("code").getAsString());
        assertEquals(1, error.get("supported").getAsJsonArray().size());
        assertEquals(1, error.get("supported").getAsJsonArray().get(0).getAsInt());
    }

    @Test
    void observationUsesSelectedCompositeSections() {
        JsonObject player = new JsonObject();
        player.addProperty("x", 12.5);
        JsonObject frame = parse(Messages.observation(1234, player));
        assertEquals("observation", frame.get("type").getAsString());
        assertEquals(1234, frame.get("tick").getAsLong());
        assertEquals(player, frame.getAsJsonObject("player"));
        assertFalse(frame.has("x"));
        assertEquals(2, parse(Messages.observation(1234, null)).size());
        JsonObject inventory = new JsonObject();
        inventory.addProperty("selected", 3);
        JsonObject both = parse(Messages.observation(7, player, inventory));
        assertEquals(List.of("type", "tick", "player", "inventory"), List.copyOf(both.keySet()));
        assertEquals(inventory, both.getAsJsonObject("inventory"));
        assertFalse(parse(Messages.observation(7, player, null)).has("inventory"));
    }

    @Test
    void observationOrdersEverySectionAndOmitsUnselected() {
        java.util.Map<String, JsonObject> sections = new java.util.HashMap<>();
        for (String name : List.of("entities", "world", "target", "inventory", "player")) {
            JsonObject section = new JsonObject();
            section.addProperty("name", name);
            sections.put(name, section);
        }
        assertEquals(List.of("type", "tick", "player", "inventory", "target", "world", "entities"),
                List.copyOf(parse(Messages.sectionObservation(9, sections)).keySet()));
        sections.remove("inventory");
        sections.put("player", null);
        assertEquals(List.of("type", "tick", "target", "world", "entities"),
                List.copyOf(parse(Messages.sectionObservation(9, sections)).keySet()));
    }

    @Test
    void helloReplyAdvertisesTargetAndWorldStateCapabilities() {
        JsonObject capabilities = parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities");
        assertTrue(capabilities.get("targetState").getAsBoolean());
        assertTrue(capabilities.get("worldState").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesEntityStateCapability() {
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("entityState").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesBlockScanCapability() {
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("blockScan").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesCraftingCapability() {
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("crafting").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesInventoryStateCapability() {
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("inventoryState").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesInventoryStorageCapability() {
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("inventoryStorage").getAsBoolean());
    }

    @Test
    void inventoryErrorsCarryAnAdditiveReason() {
        JsonObject error = parse(Messages.error(ErrorCode.INVENTORY_IMPOSSIBLE, "destination_rejects",
                "destination forbids this item", new JsonPrimitive("m"), "{}"));
        assertEquals("inventory_impossible", error.get("code").getAsString());
        assertEquals("destination_rejects", error.get("reason").getAsString());
        assertEquals("m", error.get("id").getAsString());
        assertFalse(parse(Messages.error(ErrorCode.INVENTORY_BUSY, null, "busy", null, null)).has("reason"));
        assertFalse(parse(Messages.error(ErrorCode.INVALID_JSON, "malformed JSON", null, null)).has("reason"));
    }

    @Test
    void helloReplyAdvertisesCameraCapability() {
        JsonObject reply = JsonParser.parseString(
                Messages.helloReply(1, "0.1.0", null)).getAsJsonObject();
        assertTrue(reply.getAsJsonObject("capabilities").get("camera").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesObserverCapability() {
        JsonObject reply = JsonParser.parseString(
                Messages.helloReply(1, "1.0", null)).getAsJsonObject();
        assertTrue(reply.get("capabilities").getAsJsonObject().get("observer").getAsBoolean());
    }

    @Test
    void helloReplyAdvertisesInteract() {
        JsonObject reply = parse(Messages.helloReply(1, "0.1.0", null));
        assertTrue(reply.get("capabilities").getAsJsonObject().get("interact").getAsBoolean());
    }

    @Test
    void eventEnvelopeComesFirstAndNeverCarriesAnId() {
        JsonObject fields = new JsonObject();
        fields.addProperty("id", "spoofed");          // a kind's fields cannot answer a request
        fields.addProperty("seq", 99);                 // nor override the envelope
        fields.addProperty("item", "minecraft:diamond");
        fields.add("count", JsonParser.parseString("3"));
        String json = Messages.event(new GameEvent("item_pickup", "w1", 42, GameEvent.Basis.SERVER, fields), 7);
        assertTrue(json.startsWith("{\"type\":\"event\",\"event\":\"item_pickup\",\"seq\":7,"
                + "\"worldSession\":\"w1\",\"tick\":42,\"basis\":\"server\""), json);
        JsonObject event = parse(json);
        assertFalse(event.has("id"));
        assertEquals(7, event.get("seq").getAsLong());
        assertEquals("minecraft:diamond", event.get("item").getAsString());
        assertTrue(parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities")
                .get("events").getAsBoolean());
    }

    @Test
    void unknownFieldValuesStayExplicitNulls() {
        JsonObject fields = new JsonObject();
        fields.addProperty("item", (String) null);
        JsonObject event = parse(Messages.event(new GameEvent("item_pickup", "w", 1, GameEvent.Basis.CLIENT, fields), 1));
        assertTrue(event.has("item") && event.get("item").isJsonNull());
        assertEquals("client", event.get("basis").getAsString());
    }

    @Test
    void helloReplyAdvertisesGameplayCapabilities() {
        JsonObject capabilities = parse(Messages.helloReply(2, "0.1.0", null)).getAsJsonObject("capabilities");
        for (String name : List.of("swapHands", "respawn", "chat", "playerIdentity", "playerActivity",
                "humanPrecedence")) {
            assertTrue(capabilities.get(name).getAsBoolean(), name);
        }
    }

    @Test
    void actionResultEchoesIdAndNamesTheAction() {
        assertEquals(parse("{\"type\":\"action_result\",\"id\":\"c1\",\"action\":\"command\"}"),
                parse(Messages.actionResult("command", new com.google.gson.JsonPrimitive("c1"))));
        assertEquals(parse("{\"type\":\"action_result\",\"action\":\"respawn\"}"),
                parse(Messages.actionResult("respawn", null)));
    }

    @Test
    void chatErrorCarriesReasonLimitsAndOnlyRateRefusalsRetry() {
        JsonObject limited = parse(Messages.chatError("rate_limited", "slow down", new com.google.gson.JsonPrimitive(3),
                "{}", 5, 10, 256, 1234L));
        assertEquals("chat_refused", limited.get("code").getAsString());
        assertEquals("rate_limited", limited.get("reason").getAsString());
        assertEquals(parse("{\"maxMessages\":5,\"windowSeconds\":10,\"maxLength\":256}"), limited.getAsJsonObject("limits"));
        assertEquals(1234L, limited.get("retryAfterMs").getAsLong());
        assertEquals(3, limited.get("id").getAsInt());
        JsonObject disabled = parse(Messages.chatError("commands_disabled", "off", null, "{}", 5, 10, 256, null));
        assertFalse(disabled.has("retryAfterMs"));
        assertFalse(disabled.has("id"));
    }
}
