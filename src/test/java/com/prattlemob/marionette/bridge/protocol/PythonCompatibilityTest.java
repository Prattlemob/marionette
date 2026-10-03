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
                case "observation" -> Messages.observation(expected.get("tick").getAsLong(),
                        expected.has("player") ? expected.getAsJsonObject("player") : null);
                case "inventory_result" -> Messages.inventoryResult(expected.get("op").getAsString(), id,
                        expected.get("menu").isJsonNull() ? null : expected.getAsJsonObject("menu"));
                case "error" -> expected.get("code").getAsString().equals("unsupported_version")
                        ? Messages.unsupportedVersionError(List.of(2), id, expected.get("input").getAsString())
                        : Messages.error(ErrorCode.INVENTORY_CANCELLED, "cancelled", id, expected.get("input").getAsString());
                default -> throw new AssertionError("unknown fixture");
            };
            assertEquals(expected, JsonParser.parseString(actual));
        }
    }
}
