package com.prattlemob.marionette.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Parses protocol v0 text frames into {@link AgentCommand}s. Network-thread code. */
public final class CommandParser {
    private CommandParser() {}

    public static AgentCommand parse(String text) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                throw new ProtocolException("message must be a JSON object");
            }
            json = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ProtocolException("malformed JSON");
        }
        String type = optionalString(json, "type");
        if (type == null) {
            throw new ProtocolException("missing \"type\"");
        }
        return switch (type) {
            case "hello" -> new AgentCommand.Hello(requiredInt(json, "version"));
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
            default -> throw new ProtocolException("unknown type: " + type);
        };
    }

    private static String optionalString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new ProtocolException("field \"" + name + "\" must be a string");
        }
        return primitive.getAsString();
    }

    private static Boolean optionalBoolean(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isBoolean()) {
            throw new ProtocolException("field \"" + name + "\" must be a boolean");
        }
        return primitive.getAsBoolean();
    }

    private static int requiredInt(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolException("missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolException("field \"" + name + "\" must be a number");
        }
        return primitive.getAsInt();
    }

    private static float requiredFiniteFloat(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolException("missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolException("field \"" + name + "\" must be a number");
        }
        float value = primitive.getAsFloat();
        if (!Float.isFinite(value)) {
            throw new ProtocolException("field \"" + name + "\" must be finite");
        }
        return value;
    }
}
