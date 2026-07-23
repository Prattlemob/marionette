package com.prattlemob.marionette.bridge.protocol;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Parses protocol v1 text frames into {@link ParsedMessage}s. Network-thread code. */
public final class MessageParser {
    private MessageParser() {}

    public static ParsedMessage parse(String text) {
        JsonObject json = strictJsonObject(text);
        JsonPrimitive id = envelopeId(json);
        try {
            return parseTyped(json, id);
        } catch (ProtocolError e) {
            // Attach the envelope id so error replies can echo it.
            throw e.id() != null ? e : new ProtocolError(e.code(), e.getMessage(), id);
        }
    }

    /**
     * RFC 8259 parsing. Gson's JsonParser.parseString is lenient (unquoted
     * keys, single quotes, NaN); the wire contract is strict JSON, and
     * protocol/README.md makes later tightening a breaking change — so be
     * strict from the start (docs/decisions.md, M2.3).
     */
    private static JsonObject strictJsonObject(String text) {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement element;
        try {
            element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ProtocolError(ErrorCode.INVALID_JSON, "trailing content after JSON value");
            }
        } catch (JsonParseException | IOException e) {
            throw new ProtocolError(ErrorCode.INVALID_JSON, "malformed JSON");
        }
        if (!element.isJsonObject()) {
            throw new ProtocolError(ErrorCode.INVALID_JSON, "message must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static JsonPrimitive envelopeId(JsonObject json) {
        JsonElement element = json.get("id");
        if (element == null) {
            return null;
        }
        if (element instanceof JsonPrimitive primitive
                && (primitive.isString() || primitive.isNumber())) {
            return primitive;
        }
        throw new ProtocolError(ErrorCode.INVALID_FIELD, "field \"id\" must be a string or number");
    }

    private static ParsedMessage parseTyped(JsonObject json, JsonPrimitive id) {
        String type = optionalString(json, "type");
        if (type == null) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "missing \"type\"");
        }
        return switch (type) {
            case "hello" -> new ParsedMessage.Hello(versions(json), role(json), id);
            case "input" -> new AgentCommand.InputUpdate(
                    optionalBoolean(json, "forward"),
                    optionalBoolean(json, "back"),
                    optionalBoolean(json, "left"),
                    optionalBoolean(json, "right"),
                    optionalBoolean(json, "jump"),
                    optionalBoolean(json, "sneak"),
                    optionalBoolean(json, "sprint"));
            case "look" -> new AgentCommand.Look(
                    requiredFiniteFloat(json, "yaw"),
                    requiredFiniteFloat(json, "pitch"));
            case "release" -> new AgentCommand.Release();
            default -> throw new ProtocolError(ErrorCode.UNKNOWN_TYPE, "unknown type: " + type);
        };
    }

    private static List<Integer> versions(JsonObject json) {
        JsonElement element = json.get("versions");
        if (!(element instanceof JsonArray array) || array.isEmpty()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"versions\" must be a non-empty array of integers");
        }
        List<Integer> versions = new ArrayList<>();
        for (JsonElement entry : array) {
            if (!(entry instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD,
                        "field \"versions\" must be a non-empty array of integers");
            }
            double value = primitive.getAsDouble();
            if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD,
                        "field \"versions\" must be a non-empty array of integers");
            }
            versions.add(primitive.getAsInt());
        }
        return versions;
    }

    private static String role(JsonObject json) {
        String role = optionalString(json, "role");
        return role != null ? role : "controller";
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be a string");
        }
        return primitive.getAsString();
    }

    private static Boolean optionalBoolean(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static float requiredFiniteFloat(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be a number");
        }
        float value = primitive.getAsFloat();
        if (!Float.isFinite(value)) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be finite");
        }
        return value;
    }
}
