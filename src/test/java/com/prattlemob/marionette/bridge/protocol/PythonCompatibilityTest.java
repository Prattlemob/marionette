package com.prattlemob.marionette.bridge.protocol;

import com.google.gson.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Shared fixture ships in the Python sdist; validates actual Java parser/builders. */
class PythonCompatibilityTest {
    @Test void sharedProtocolTwoFixtures() throws Exception {
        var fixture = JsonParser.parseString(Files.readString(
                Path.of("python/tests/fixtures/protocol2.json"))).getAsJsonObject();
        for (var entry : fixture.getAsJsonArray("commands")) {
            var item = entry.getAsJsonObject();
            var command = MessageParser.parse(item.get("wire").toString());
            assertEquals(item.get("javaType").getAsString(), command.getClass().getSimpleName());
            if (command instanceof AgentCommand.InputUpdate input) {
                assertEquals(Boolean.TRUE, input.forward());
                assertEquals(Boolean.FALSE, input.back());
                assertEquals(8, input.hotbar());
                assertEquals(2, input.taps().size());
            }
            if (command instanceof AgentCommand.Scan scan) {
                assertEquals(item.getAsJsonObject("wire").get("id"), scan.id());
                assertEquals(16, scan.size().x());
            }
            if (command instanceof AgentCommand.InventoryAction inventory) {
                assertEquals(item.getAsJsonObject("wire").get("op").getAsString(), inventory.op());
                assertEquals(item.getAsJsonObject("wire").get("id"), inventory.id());
            }
        }
        for (var entry : fixture.getAsJsonArray("invalidCommands")) {
            var item = entry.getAsJsonObject();
            var error = assertThrows(ProtocolError.class, () -> MessageParser.parse(item.get("wire").toString()));
            assertEquals(item.get("code").getAsString(), error.code().wire());
        }
        for (var entry : fixture.getAsJsonArray("messages")) {
            var expected = entry.getAsJsonObject();
            var id = expected.has("id") ? expected.getAsJsonPrimitive("id") : null;
            String actual = switch (expected.get("type").getAsString()) {
                case "hello" -> Messages.helloReply(2, "0.1.0", id);
                case "observation" -> {
                    java.util.Map<String, JsonObject> sections = new java.util.HashMap<>();
                    for (String name : Messages.SECTION_ORDER) {
                        if (expected.has(name)) sections.put(name, expected.getAsJsonObject(name));
                    }
                    yield Messages.sectionObservation(expected.get("tick").getAsLong(), sections);
                }
                case "inventory_result" -> Messages.inventoryResult(expected.get("op").getAsString(), id,
                        expected.get("menu").isJsonNull() ? null : expected.getAsJsonObject("menu"));
                case "error" -> expected.get("code").getAsString().startsWith("scan_")
                        ? Messages.scanError(ErrorCode.valueOf(expected.get("code").getAsString().toUpperCase(java.util.Locale.ROOT)),
                                expected.get("reason").getAsString(), expected.get("message").getAsString(), id,
                                expected.get("input").getAsString(), expected.has("limits") ? new int[] {
                                        expected.getAsJsonObject("limits").get("radius").getAsInt(),
                                        expected.getAsJsonObject("limits").get("maxVolume").getAsInt()} : null)
                        : expected.get("code").getAsString().equals("unsupported_version")
                        ? Messages.unsupportedVersionError(List.of(2), id, expected.get("input").getAsString())
                        : Messages.error(java.util.Arrays.stream(ErrorCode.values())
                                        .filter(c -> c.wire().equals(expected.get("code").getAsString())).findFirst().orElseThrow(),
                                expected.has("reason") ? expected.get("reason").getAsString() : null,
                                expected.get("message").getAsString(), id, expected.get("input").getAsString());
                default -> throw new AssertionError("unknown fixture");
            };
            assertEquals(expected, JsonParser.parseString(actual));
        }
        // Scan results go only to a client that sent scan (blockScan), so they are kept apart too.
        for (var entry : fixture.getAsJsonArray("scanResults")) {
            var expected = entry.getAsJsonObject();
            var palette = new java.util.ArrayList<String>();
            expected.getAsJsonArray("palette").forEach(name -> palette.add(name.isJsonNull() ? null : name.getAsString()));
            var indices = new int[expected.getAsJsonArray("indices").size()];
            for (int i = 0; i < indices.length; i++) indices[i] = expected.getAsJsonArray("indices").get(i).getAsInt();
            String actual = Messages.scanResult(expected.getAsJsonPrimitive("id"), expected.get("dimension").getAsString(),
                    xyz(expected.getAsJsonObject("min")), xyz(expected.getAsJsonObject("size")),
                    expected.get("startTick").getAsLong(), expected.get("tick").getAsLong(), palette, indices);
            assertEquals(expected, JsonParser.parseString(actual));
        }
        // Event frames are kept apart from "messages": clients that predate
        // events (and never subscribe) are only ever sent the messages above.
        for (var entry : fixture.getAsJsonArray("events")) {
            var expected = entry.getAsJsonObject();
            var fields = expected.deepCopy();
            for (String envelope : List.of("type", "event", "seq", "worldSession", "tick", "basis")) fields.remove(envelope);
            var event = new GameEvent(expected.get("event").getAsString(), expected.get("worldSession").getAsString(),
                    expected.get("tick").getAsLong(),
                    GameEvent.Basis.valueOf(expected.get("basis").getAsString().toUpperCase(java.util.Locale.ROOT)), fields);
            assertEquals(expected, JsonParser.parseString(Messages.event(event, expected.get("seq").getAsLong())));
        }
    }

    private static int[] xyz(JsonObject coord) {
        return new int[] {coord.get("x").getAsInt(), coord.get("y").getAsInt(), coord.get("z").getAsInt()};
    }
}
