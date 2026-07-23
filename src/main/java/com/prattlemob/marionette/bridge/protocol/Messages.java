package com.prattlemob.marionette.bridge.protocol;

import java.util.List;

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
        reply.add("capabilities", capabilities);
        reply.addProperty("mod", modVersion);
        return reply.toString();
    }

    public static String error(ErrorCode code, String message, JsonPrimitive id, String offendingInput) {
        return errorObject(code, message, id, offendingInput).toString();
    }

    public static String unsupportedVersionError(List<Integer> supported, JsonPrimitive id, String offendingInput) {
        JsonObject error = errorObject(ErrorCode.UNSUPPORTED_VERSION,
                "mod speaks protocol " + supported, id, offendingInput);
        JsonArray array = new JsonArray();
        supported.forEach(array::add);
        error.add("supported", array);
        return error.toString();
    }

    public static String observation(long tick, double x, double y, double z, float yaw, float pitch) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "observation");
        frame.addProperty("tick", tick);
        frame.addProperty("x", x);
        frame.addProperty("y", y);
        frame.addProperty("z", z);
        frame.addProperty("yaw", yaw);
        frame.addProperty("pitch", pitch);
        return frame.toString();
    }

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
