package com.prattlemob.marionette.bridge.protocol;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.prattlemob.marionette.control.TapControl;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Parses protocol v2 text frames into {@link ParsedMessage}s. Network-thread code. */
public final class MessageParser {
    private MessageParser() {}

    public static ParsedMessage parse(String text) {
        JsonObject json = strictJsonObject(text);
        JsonPrimitive id = envelopeId(json);
        try {
            return parseTyped(json, id, text);
        } catch (ProtocolError e) {
            // Attach the envelope id so error replies can echo it.
            throw e.id() != null ? e : new ProtocolError(e.code(), e.getMessage(), id);
        }
    }

    /**
     * The envelope id of a frame, or null when absent, invalid, or
     * unparseable. For error echoes on refusals decided after parsing
     * (role_forbidden), where the parsed command does not carry the id.
     */
    public static JsonPrimitive idOf(String text) {
        try {
            return envelopeId(strictJsonObject(text));
        } catch (ProtocolError e) {
            return null;
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

    private static ParsedMessage parseTyped(JsonObject json, JsonPrimitive id, String raw) {
        String type = optionalString(json, "type");
        if (type == null) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "missing \"type\"");
        }
        return switch (type) {
            case "hello" -> new ParsedMessage.Hello(versions(json), role(json), id, sections(json),
                    optionalBoolean(json, "events"));
            case "input" -> new AgentCommand.InputUpdate(
                    optionalBoolean(json, "forward"),
                    optionalBoolean(json, "back"),
                    optionalBoolean(json, "left"),
                    optionalBoolean(json, "right"),
                    optionalBoolean(json, "jump"),
                    optionalBoolean(json, "sneak"),
                    optionalBoolean(json, "sprint"),
                    optionalBoolean(json, "attack"),
                    optionalBoolean(json, "use"),
                    optionalRangedInt(json, "hotbar", 0, 8),
                    tapArray(json));
            case "inventory" -> parseInventory(json, id, raw);
            case "look" -> parseLook(json, id, raw);
            case "release" -> new AgentCommand.Release();
            case "configure" -> new AgentCommand.Configure(
                    optionalRangedInt(json, "rateDivisor", 1, 100), sections(json),
                    optionalBoolean(json, "events"));
            default -> throw new ProtocolError(ErrorCode.UNKNOWN_TYPE, "unknown type: " + type);
        };
    }

    /** Implemented observation sections; reserved future names are rejected until they ship. */
    static final Set<String> SECTIONS = Set.of("player", "inventory", "target", "world");

    private static Set<String> sections(JsonObject json) {
        if (!json.has("sections")) return null;
        if (!(json.get("sections") instanceof JsonArray array)) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "sections must be an array");
        }
        Set<String> result = new HashSet<>();
        for (JsonElement entry : array) {
            if (!(entry instanceof JsonPrimitive value) || !value.isString()
                    || !SECTIONS.contains(value.getAsString())) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD, "unsupported observation section");
            }
            result.add(value.getAsString());
        }
        return Set.copyOf(result);
    }

    private static AgentCommand.InventoryAction parseInventory(JsonObject json, JsonPrimitive id, String raw) {
        String op = optionalString(json, "op");
        if (op == null || !Set.of("open", "inspect", "close", "move", "swap", "drop", "equip").contains(op)) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "unknown or missing inventory op");
        }
        AgentCommand.MenuRef menu = null;
        if (!op.equals("open") && !op.equals("inspect")) {
            if (!(json.get("menu") instanceof JsonObject ref)) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD, "menu must be an object");
            }
            String type = optionalString(ref, "type");
            if (type == null || type.isBlank()) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD, "menu.type must be a nonempty string");
            }
            menu = new AgentCommand.MenuRef(type,
                    requiredInt(ref, "containerId", Integer.MAX_VALUE),
                    requiredInt(ref, "stateId", Integer.MAX_VALUE));
        }
        boolean needsSource = Set.of("move", "swap", "drop", "equip").contains(op);
        return new AgentCommand.InventoryAction(op, menu,
                needsSource ? slotRef(json, "from") : null,
                op.equals("move") ? slotRef(json, "to") : null,
                op.equals("swap") ? requiredInt(json, "hotbar", 8) : null,
                op.equals("drop") && Boolean.TRUE.equals(optionalBoolean(json, "all")),
                Boolean.TRUE.equals(optionalBoolean(json, "animated")), id, raw);
    }

    private static int requiredInt(JsonObject json, String field, int max) {
        Integer value = optionalRangedInt(json, field, 0, max);
        if (value == null) throw new ProtocolError(ErrorCode.INVALID_FIELD, "missing " + field);
        return value;
    }

    private static AgentCommand.SlotRef slotRef(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value instanceof JsonPrimitive primitive && primitive.isString()) {
            String alias = primitive.getAsString();
            if (alias.matches("hotbar\\.[0-8]|main\\.([0-9]|1[0-9]|2[0-6])|armor\\.(head|chest|legs|feet)|offhand")) {
                return new AgentCommand.SlotRef(null, alias);
            }
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "unknown player slot alias: " + alias);
        }
        return new AgentCommand.SlotRef(requiredInt(json, field, Integer.MAX_VALUE), null);
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

    private static Integer optionalRangedInt(JsonObject json, String name, int min, int max) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        String requirement = "field \"" + name + "\" must be an integer between " + min + " and " + max;
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
        }
        double value = primitive.getAsDouble();
        if (value != Math.rint(value) || value < min || value > max) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
        }
        return primitive.getAsInt();
    }

    /** The "tap" array as TapControls; empty when absent. See protocol/v1.md. */
    private static Set<TapControl> tapArray(JsonObject json) {
        JsonElement element = json.get("tap");
        if (element == null) {
            return Set.of();
        }
        String requirement = "field \"tap\" must be an array of control names";
        if (!(element instanceof JsonArray array)) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
        }
        EnumSet<TapControl> taps = EnumSet.noneOf(TapControl.class);
        for (JsonElement entry : array) {
            if (!(entry instanceof JsonPrimitive primitive) || !primitive.isString()) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
            }
            TapControl control = TapControl.fromWire(primitive.getAsString());
            if (control == null) {
                throw new ProtocolError(ErrorCode.INVALID_FIELD,
                        "field \"tap\" has unknown control \"" + primitive.getAsString() + "\"");
            }
            taps.add(control);
        }
        return Set.copyOf(taps);
    }

    private static ParsedMessage parseLook(JsonObject json, JsonPrimitive id, String raw) {
        String mode = optionalString(json, "mode");
        return switch (mode == null ? "instant" : mode) {
            case "instant" -> new AgentCommand.Look(
                    requiredFiniteFloat(json, "yaw"),
                    requiredFiniteFloat(json, "pitch"));
            case "delta" -> new AgentCommand.LookDelta(
                    requiredFiniteFloat(json, "yaw"),
                    requiredFiniteFloat(json, "pitch"));
            case "smooth" -> parseLookSmooth(json, id, raw);
            default -> throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"mode\" must be \"instant\", \"delta\", or \"smooth\"");
        };
    }

    private static ParsedMessage parseLookSmooth(JsonObject json, JsonPrimitive id, String raw) {
        boolean hasAngles = json.has("yaw") || json.has("pitch");
        boolean hasPoint = json.has("x") || json.has("y") || json.has("z");
        if (hasAngles && hasPoint) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "smooth look takes either yaw/pitch or x/y/z, not both");
        }
        if (!hasAngles && !hasPoint) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "smooth look requires yaw/pitch or x/y/z");
        }
        Float speed = optionalPositiveFiniteFloat(json, "speed");
        if (hasAngles) {
            return new AgentCommand.LookSmoothAngles(
                    requiredFiniteFloat(json, "yaw"),
                    requiredFiniteFloat(json, "pitch"),
                    speed);
        }
        return new AgentCommand.LookSmoothPoint(
                requiredFiniteDouble(json, "x"),
                requiredFiniteDouble(json, "y"),
                requiredFiniteDouble(json, "z"),
                speed, id, raw);
    }

    private static double requiredFiniteDouble(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, "missing \"" + name + "\"");
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be a number");
        }
        double value = primitive.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD,
                    "field \"" + name + "\" must be finite");
        }
        return value;
    }

    private static Float optionalPositiveFiniteFloat(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return null;
        }
        String requirement = "field \"" + name + "\" must be a positive finite number";
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
        }
        float value = primitive.getAsFloat();
        if (!Float.isFinite(value) || value <= 0.0f) {
            throw new ProtocolError(ErrorCode.INVALID_FIELD, requirement);
        }
        return value;
    }
}
