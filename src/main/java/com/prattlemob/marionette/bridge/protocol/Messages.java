package com.prattlemob.marionette.bridge.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        capabilities.addProperty("targetState", true);
        capabilities.addProperty("worldState", true);
        capabilities.addProperty("entityState", true);
        capabilities.addProperty("blockScan", true);
        capabilities.addProperty("crafting", true);
        capabilities.addProperty("swapHands", true);
        capabilities.addProperty("respawn", true);
        capabilities.addProperty("chat", true);
        capabilities.addProperty("playerIdentity", true);
        capabilities.addProperty("playerActivity", true);
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

    /** Reply to an accepted {@code respawn} or {@code chat} request: "respawn", "chat" or "command". */
    public static String actionResult(String action, JsonPrimitive id) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "action_result");
        if (id != null) reply.add("id", id);
        reply.addProperty("action", action);
        return reply.toString();
    }

    /**
     * A chat refusal ({@code chat}): always the limits in force; {@code retryAfterMs}
     * only for {@code rate_limited} (null otherwise).
     */
    public static String chatError(String reason, String message, JsonPrimitive id, String offendingInput,
                                   int maxMessages, int windowSeconds, int maxLength, Long retryAfterMs) {
        JsonObject error = errorObject(ErrorCode.CHAT_REFUSED, message, id, offendingInput);
        error.addProperty("reason", reason);
        JsonObject limits = new JsonObject();
        limits.addProperty("maxMessages", maxMessages);
        limits.addProperty("windowSeconds", windowSeconds);
        limits.addProperty("maxLength", maxLength);
        error.add("limits", limits);
        if (retryAfterMs != null) error.addProperty("retryAfterMs", retryAfterMs);
        return error.toString();
    }

    /**
     * A block scan result ({@code blockScan}); {@code palette} entries may be
     * null (no block data). See protocol/v1.md, scan_result.
     */
    public static String scanResult(JsonPrimitive id, String dimension, int[] min, int[] size,
                                    long startTick, long tick, List<String> palette, int[] indices) {
        JsonObject reply = new JsonObject();
        reply.addProperty("type", "scan_result");
        if (id != null) reply.add("id", id);
        reply.addProperty("dimension", dimension);
        reply.add("min", coord(min));
        reply.add("size", coord(size));
        reply.addProperty("order", "yzx");
        reply.addProperty("startTick", startTick);
        reply.addProperty("tick", tick);
        JsonArray names = new JsonArray(palette.size());
        palette.forEach(names::add);
        reply.add("palette", names);
        // Gson's tree is heavy for thousands of ints; append the array text directly.
        String head = reply.toString();
        StringBuilder out = new StringBuilder(head.length() + indices.length * 3 + 16);
        out.append(head, 0, head.length() - 1).append(",\"indices\":[");
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) out.append(',');
            out.append(indices[i]);
        }
        return out.append("]}").toString();
    }

    /** A scan refusal or cancellation; {@code limits} (radius, maxVolume) only on cap refusals. */
    public static String scanError(ErrorCode code, String reason, String message, JsonPrimitive id,
                                   String offendingInput, int[] limits) {
        JsonObject error = errorObject(code, message, id, offendingInput);
        error.addProperty("reason", reason);
        if (limits != null) {
            JsonObject caps = new JsonObject();
            caps.addProperty("radius", limits[0]);
            caps.addProperty("maxVolume", limits[1]);
            error.add("limits", caps);
        }
        return error.toString();
    }

    private static JsonObject coord(int[] xyz) {
        JsonObject coord = new JsonObject();
        coord.addProperty("x", xyz[0]);
        coord.addProperty("y", xyz[1]);
        coord.addProperty("z", xyz[2]);
        return coord;
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
        Map<String, JsonObject> sections = new HashMap<>();
        sections.put("player", player);
        sections.put("inventory", inventory);
        return sectionObservation(tick, sections);
    }

    /** Observation sections in their protocol (frame) order. */
    public static final List<String> SECTION_ORDER = List.of("player", "inventory", "target", "world", "entities");

    /** Absent or null sections are omitted (unselected); present ones appear in protocol order. */
    public static String sectionObservation(long tick, Map<String, JsonObject> sections) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "observation");
        frame.addProperty("tick", tick);
        for (String name : SECTION_ORDER) {
            JsonObject section = sections.get(name);
            if (section != null) frame.add(name, section);
        }
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
