package com.prattlemob.marionette.bridge.protocol;

import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Builders for every mod → agent message. See protocol/v1.md. */
public final class Messages {
    /** Longest offending-input echo in an error frame, in characters. */
    static final int INPUT_ECHO_LIMIT = 256;

    private Messages() {}

    public static String helloReply(int version, String modVersion, JsonPrimitive id) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "hello");
        if (id != null) {
            reply.add("id", id);
        }
        reply.addProperty("version", version);
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("configure", true);
        capabilities.addProperty("bridgeSafety", true);
        capabilities.addProperty("panicLatch", true);
        capabilities.addProperty("playerState", true);
        capabilities.addProperty("tap", true);
        capabilities.addProperty("camera", true);
        capabilities.addProperty("observer", true);
        capabilities.addProperty("interact", true);
        capabilities.addProperty("inventory", true);
        capabilities.addProperty("inventoryAnimation", true);
        capabilities.addProperty("events", true);
        capabilities.addProperty("inventoryState", true);
        capabilities.addProperty("inventoryStorage", true);
        reply.add("capabilities", capabilities);
        reply.addProperty("mod", modVersion);
        return reply.toString();
    }

    public static String inventoryResult(String op, JsonPrimitive id, JsonObject menu) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "inventory_result");
        if (id != null) reply.add("id", id);
        reply.addProperty("op", op);
        reply.add("menu", menu);
        return reply.toString();
    }

    public static String error(ErrorCode code, String message, JsonPrimitive id, String offendingInput) {
        return errorObject(code, message, id, offendingInput).toString();
    }

    /** An inventory error carrying a machine-readable {@code reason} ({@code inventoryStorage}). */
    public static String error(ErrorCode code, String reason, String message, JsonPrimitive id, String offendingInput) {
        JsonObject error = errorObject(code, message, id, offendingInput);
        if (reason != null) error.addProperty("reason", reason);
        return error.toString();
    }

    public static String unsupportedVersionError(List<Integer> supported, JsonPrimitive id, String offendingInput) {
        JsonObject error = errorObject(ErrorCode.UNSUPPORTED_VERSION,
                "mod speaks protocol " + supported, id, offendingInput);
        JsonArray array = new JsonArray();
        supported.forEach(array::add);
        error.add("supported", array);
        return error.toString();
    }

    public static String observation(long tick, JsonObject player) {
        return observation(tick, player, null);
    }

    /** Null sections are omitted (unselected); sections appear in protocol order. */
    public static String observation(long tick, JsonObject player, JsonObject inventory) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "observation");
        frame.addProperty("tick", tick);
        if (player != null) frame.add("player", player);
        if (inventory != null) frame.add("inventory", inventory);
        return frame.toString();
    }

    /**
     * One event frame: envelope fields first, then the kind's own fields.
     * Never carries an id; events never answer requests.
     */
    public static String event(GameEvent event, long seq) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "event");
        frame.addProperty("event", event.kind());
        frame.addProperty("seq", seq);
        frame.addProperty("worldSession", event.worldSession());
        frame.addProperty("tick", event.tick());
        frame.addProperty("basis", event.basis().wire());
        for (var field : event.fields().entrySet()) {
            if (!ENVELOPE_FIELDS.contains(field.getKey())) frame.add(field.getKey(), field.getValue());
        }
        return frame.toString();
    }

    private static final Set<String> ENVELOPE_FIELDS =
            Set.of("type", "id", "event", "seq", "worldSession", "tick", "basis");

    private static JsonObject errorObject(ErrorCode code, String message, JsonPrimitive id, String offendingInput) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "error");
        error.addProperty("code", code.wire());
        error.addProperty("message", message);
        if (id != null) {
            error.add("id", id);
        }
        if (offendingInput != null) {
            error.addProperty("input", offendingInput.length() <= INPUT_ECHO_LIMIT
                    ? offendingInput
                    : offendingInput.substring(0, INPUT_ECHO_LIMIT));
        }
        return error;
    }
}
