package com.prattlemob.marionette.observation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

class BlockScanTest {
    /** The protocol's worked example world: a 3×2×2 box at (10, 64, −3). */
    static BlockScan.BlockSource workedExample() {
        Map<String, String> blocks = new HashMap<>();
        String[][] rows = {
                {"stone", "stone", "dirt"}, {"stone", "water", "dirt"},
                {"air", "air", "oak_log"}, {"air", "air", "air"}};
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 3; dx++) {
                    blocks.put((10 + dx) + "," + (64 + dy) + "," + (-3 + dz), "minecraft:" + rows[dy * 2 + dz][dx]);
                }
            }
        }
        return (x, y, z) -> blocks.get(x + "," + y + "," + z);
    }

    static JsonObject protocolExample(String type) throws Exception {
        for (String line : Files.readAllLines(Path.of("protocol/v1.md"))) {
            if (line.startsWith("{\"type\":\"" + type + "\"")) return JsonParser.parseString(line).getAsJsonObject();
        }
        throw new AssertionError("no " + type + " example");
    }

    @Test
    void workedExampleMatchesTheProtocolDocument() throws Exception {
        BlockScan scan = new BlockScan(new int[] {10, 64, -3}, new int[] {3, 2, 2});
        assertEquals(12, scan.advance(workedExample(), 1024, 812));
        assertTrue(scan.done());
        JsonObject result = JsonParser.parseString(scan.result(new JsonPrimitive("pit"), "minecraft:overworld"))
                .getAsJsonObject();
        assertEquals(protocolExample("scan_result"), result);
    }

    @Test
    void indicesAreYMajorThenZThenX() {
        int[] min = {-5, 60, 7};
        int[] size = {4, 3, 5};
        BlockScan scan = new BlockScan(min, size);
        scan.advance((x, y, z) -> "m:" + x + "_" + y + "_" + z, 8192, 1);
        JsonObject result = JsonParser.parseString(scan.result(null, "d")).getAsJsonObject();
        JsonArray palette = result.getAsJsonArray("palette");
        JsonArray indices = result.getAsJsonArray("indices");
        assertEquals(60, indices.size());
        for (int dy = 0; dy < 3; dy++) {
            for (int dz = 0; dz < 5; dz++) {
                for (int dx = 0; dx < 4; dx++) {
                    int i = (dy * size[2] + dz) * size[0] + dx;
                    String expected = "m:" + (min[0] + dx) + "_" + (min[1] + dy) + "_" + (min[2] + dz);
                    assertEquals(expected, palette.get(indices.get(i).getAsInt()).getAsString());
                }
            }
        }
        assertEquals("yzx", result.get("order").getAsString());
    }

    @Test
    void readsAtMostTheBudgetPerTickInIndexOrder() {
        List<String> order = new ArrayList<>();
        BlockScan scan = new BlockScan(new int[] {0, 0, 0}, new int[] {16, 8, 16});
        BlockScan.BlockSource source = (x, y, z) -> {
            order.add(x + "," + y + "," + z);
            return "minecraft:stone";
        };
        assertEquals(1024, scan.advance(source, 1024, 40));
        assertFalse(scan.done());
        assertEquals("0,0,0", order.get(0));
        assertEquals("15,0,0", order.get(15));
        assertEquals("0,0,1", order.get(16));
        assertEquals("15,3,15", order.get(1023), "first tick ends after four full layers");
        assertEquals(1024, scan.advance(source, 1024, 41));
        assertTrue(scan.done());
        JsonObject result = JsonParser.parseString(scan.result(null, "d")).getAsJsonObject();
        assertEquals(40, result.get("startTick").getAsLong());
        assertEquals(41, result.get("tick").getAsLong());
        assertEquals(2048, result.getAsJsonArray("indices").size());
    }

    @Test
    void ticksNeededFollowTheBudget() {
        for (int budget : new int[] {64, 1000, 1024, 2048, 8192}) {
            BlockScan scan = new BlockScan(new int[] {0, 0, 0}, new int[] {16, 8, 16});
            int ticks = 0;
            while (!scan.done()) {
                scan.advance((x, y, z) -> "a", budget, ticks++);
            }
            assertEquals((2048 + budget - 1) / budget, ticks, "budget " + budget);
        }
    }

    @Test
    void unloadedPositionsShareOneNullPaletteEntry() {
        BlockScan scan = new BlockScan(new int[] {0, 0, 0}, new int[] {4, 1, 1});
        scan.advance((x, y, z) -> x % 2 == 0 ? null : "minecraft:air", 64, 0);
        JsonObject result = JsonParser.parseString(scan.result(null, "d")).getAsJsonObject();
        JsonArray palette = result.getAsJsonArray("palette");
        assertEquals(2, palette.size());
        assertTrue(palette.get(0).isJsonNull());
        assertEquals("[0,1,0,1]", result.getAsJsonArray("indices").toString());
    }

    @Test
    void capsAreInclusiveAndPerAxis() {
        int[] feet = {100, 64, -20};
        assertNull(BlockScan.refusal(new int[] {84, 48, -36}, new int[] {33, 33, 1}, feet, 16));
        assertEquals("over_radius", BlockScan.refusal(new int[] {83, 64, -20}, new int[] {1, 1, 1}, feet, 16));
        assertEquals("over_radius", BlockScan.refusal(new int[] {100, 64, -20}, new int[] {1, 1, 18}, feet, 16));
        assertEquals("over_radius", BlockScan.refusal(new int[] {100, 81, -20}, new int[] {1, 1, 1}, feet, 16));
        assertEquals("over_volume", BlockScan.refusal(new int[] {84, 48, -36}, new int[] {33, 33, 33}, feet, 32));
        assertNull(BlockScan.refusal(new int[] {84, 64, -36}, new int[] {32, 8, 32}, feet, 16), "8192 is allowed");
        assertEquals("over_volume", BlockScan.refusal(new int[] {0, 0, 0}, new int[] {8192, 8192, 8192},
                new int[] {0, 0, 0}, 32), "no int overflow");
        assertEquals("over_radius", BlockScan.refusal(new int[] {Integer.MAX_VALUE, 0, 0}, new int[] {8192, 1, 1},
                new int[] {0, 0, 0}, 32), "no int overflow");
    }

    @Test
    void centredBoxFitsTheRadiusItNeeds() {
        int[] feet = {10, -60, 3};
        int[] min = BlockScan.centred(new int[] {16, 8, 16}, feet);
        assertArrayEquals(new int[] {2, -64, -5}, min);
        assertNull(BlockScan.refusal(min, new int[] {16, 8, 16}, feet, 8));
        assertArrayEquals(new int[] {10, -60, 3}, BlockScan.centred(new int[] {1, 1, 1}, feet));
    }

    @Test
    void volumeOutsideTheLimitCannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new BlockScan(new int[3], new int[] {32, 9, 32}));
        assertThrows(IllegalStateException.class, () -> new BlockScan(new int[3], new int[] {2, 1, 1}).result(null, "d"));
    }
}
