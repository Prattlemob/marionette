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
    void observationMatchesTheV1Shape() {
        JsonObject frame = parse(Messages.observation(1234, 12.5, 64.0, -8.25, 90.0F, 0.0F));
        assertEquals("observation", frame.get("type").getAsString());
        assertEquals(1234, frame.get("tick").getAsLong());
        assertEquals(12.5, frame.get("x").getAsDouble());
        assertEquals(64.0, frame.get("y").getAsDouble());
        assertEquals(-8.25, frame.get("z").getAsDouble());
        assertEquals(90.0F, frame.get("yaw").getAsFloat());
        assertEquals(0.0F, frame.get("pitch").getAsFloat());
    }

    @Test
    void helloReplyAdvertisesCameraCapability() {
        JsonObject reply = JsonParser.parseString(
                Messages.helloReply(1, "0.1.0", null)).getAsJsonObject();
        assertTrue(reply.getAsJsonObject("capabilities").get("camera").getAsBoolean());
    }
}
