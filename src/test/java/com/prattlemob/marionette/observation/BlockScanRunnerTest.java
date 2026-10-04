package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.MessageParser;

class BlockScanRunnerTest {
    private final BlockScanRunner runner = new BlockScanRunner();
    private final List<JsonObject> replies = new ArrayList<>();
    private final Object level = new Object();
    private static final int[] FEET = {0, 64, 0};

    private static AgentCommand.Scan scan(String json) {
        return (AgentCommand.Scan) MessageParser.parse(json);
    }

    private void start(String json, int[] feet, int radius) {
        runner.start(scan(json), raw -> replies.add(JsonParser.parseString(raw).getAsJsonObject()),
                feet, radius, level, "minecraft:overworld");
    }

    private JsonObject only() {
        assertEquals(1, replies.size(), replies.toString());
        return replies.getFirst();
    }

    @Test
    void sixteenByEightBySixteenCompletesOnItsSecondTick() {
        start("{\"type\":\"scan\",\"id\":\"s\",\"size\":{\"x\":16,\"y\":8,\"z\":16}}", FEET, 16);
        assertTrue(runner.active());
        assertEquals(1024, runner.tick(5, 1024, level, (x, y, z) -> "minecraft:stone"));
        assertTrue(replies.isEmpty(), "no partial result");
        assertEquals(1024, runner.tick(6, 1024, level, (x, y, z) -> "minecraft:stone"));
        JsonObject result = only();
        assertEquals("scan_result", result.get("type").getAsString());
        assertEquals("s", result.get("id").getAsString());
        assertEquals("{\"x\":-8,\"y\":60,\"z\":-8}", result.get("min").toString());
        assertEquals(5, result.get("startTick").getAsLong());
        assertEquals(6, result.get("tick").getAsLong());
        assertFalse(runner.active());
        assertEquals(0, runner.tick(7, 1024, level, (x, y, z) -> "minecraft:stone"));
    }

    @Test
    void overCapRequestsAreRefusedWithLimitsAndScanNothing() {
        start("{\"type\":\"scan\",\"id\":\"far\",\"min\":{\"x\":100,\"y\":60,\"z\":0},\"size\":{\"x\":16,\"y\":8,\"z\":16}}",
                FEET, 16);
        JsonObject error = only();
        assertEquals("scan_refused", error.get("code").getAsString());
        assertEquals("over_radius", error.get("reason").getAsString());
        assertEquals("far", error.get("id").getAsString());
        assertEquals("{\"radius\":16,\"maxVolume\":8192}", error.get("limits").toString());
        assertTrue(error.get("input").getAsString().contains("\"far\""));
        assertFalse(runner.active());
        replies.clear();
        start("{\"type\":\"scan\",\"size\":{\"x\":33,\"y\":33,\"z\":33}}", FEET, 32);
        assertEquals("over_volume", only().get("reason").getAsString());
        assertEquals(32, only().getAsJsonObject("limits").get("radius").getAsInt());
        assertFalse(runner.active());
    }

    @Test
    void secondScanIsBusyAndNoWorldIsRefused() {
        start("{\"type\":\"scan\",\"id\":1,\"size\":{\"x\":4,\"y\":4,\"z\":4}}", FEET, 16);
        start("{\"type\":\"scan\",\"id\":2,\"size\":{\"x\":4,\"y\":4,\"z\":4}}", FEET, 16);
        JsonObject busy = only();
        assertEquals("busy", busy.get("reason").getAsString());
        assertEquals(2, busy.get("id").getAsInt());
        assertFalse(busy.has("limits"));
        assertTrue(runner.active(), "the first scan continues");
        replies.clear();
        BlockScanRunner idle = new BlockScanRunner();
        idle.start(scan("{\"type\":\"scan\",\"size\":{\"x\":1,\"y\":1,\"z\":1}}"),
                raw -> replies.add(JsonParser.parseString(raw).getAsJsonObject()), null, 16, null, null);
        assertEquals("no_world", only().get("reason").getAsString());
    }

    @Test
    void cancellationSendsOneErrorAndNoResult() {
        start("{\"type\":\"scan\",\"id\":\"c\",\"size\":{\"x\":16,\"y\":8,\"z\":16}}", FEET, 16);
        runner.tick(1, 1024, level, (x, y, z) -> "a");
        runner.cancel("released", "controls released");
        runner.cancel("released", "again");
        JsonObject error = only();
        assertEquals("scan_cancelled", error.get("code").getAsString());
        assertEquals("released", error.get("reason").getAsString());
        assertEquals("c", error.get("id").getAsString());
        assertEquals(0, runner.tick(2, 1024, level, (x, y, z) -> "a"));
        assertEquals(1, replies.size());
    }

    @Test
    void aReplacedLevelCancelsTheScan() {
        start("{\"type\":\"scan\",\"size\":{\"x\":16,\"y\":8,\"z\":16}}", FEET, 16);
        runner.tick(1, 1024, level, (x, y, z) -> "a");
        assertEquals(0, runner.tick(2, 1024, new Object(), (x, y, z) -> "a"));
        assertEquals("level_changed", only().get("reason").getAsString());
        assertFalse(runner.active());
    }

    @Test
    void anOversizedResultIsCancelledInsteadOfClosingTheSession() {
        start("{\"type\":\"scan\",\"size\":{\"x\":32,\"y\":8,\"z\":32}}", FEET, 16);
        runner.tick(1, 8192, level, (x, y, z) -> "modded:" + "x".repeat(20) + x + "_" + y + "_" + z);
        JsonObject error = only();
        assertEquals("scan_cancelled", error.get("code").getAsString());
        assertEquals("too_large", error.get("reason").getAsString());
    }

    @Test
    void maximumVanillaLikeScanFitsTheReplyLimit() {
        start("{\"type\":\"scan\",\"size\":{\"x\":32,\"y\":8,\"z\":32}}", FEET, 16);
        // Worst plausible palette: a distinct long vanilla-length id for each of 256 kinds.
        runner.tick(1, 8192, level, (x, y, z) -> "minecraft:waxed_weathered_cut_copper_stairs_" + ((x * 31 + z * 7 + y) & 255));
        assertEquals("scan_result", only().get("type").getAsString());
        assertEquals(8192, only().getAsJsonArray("indices").size());
    }

    @Test
    void refusalMatchesTheProtocolExample() throws Exception {
        JsonObject expected = null;
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("protocol/v1.md"))) {
            if (line.startsWith("{\"type\":\"error\",\"code\":\"scan_refused\"")) expected = JsonParser.parseString(line).getAsJsonObject();
        }
        start(expected.get("input").getAsString(), new int[] {0, -60, 0}, 16);
        assertEquals(expected, only());
    }
}
