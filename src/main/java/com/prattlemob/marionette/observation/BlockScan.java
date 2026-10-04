package com.prattlemob.marionette.observation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonPrimitive;
import com.prattlemob.marionette.bridge.protocol.Messages;

/**
 * One bounded block scan (D3): a box read in y→z→x index order into a palette
 * of block ids and a flat index array, a budgeted portion per call.
 * Minecraft-free; the client thread supplies blocks through {@link BlockSource}.
 * See protocol/v1.md, scan and scan_result.
 */
public final class BlockScan {
    /** Fixed protocol limit on positions per scan; keeps results far inside the reply limit. */
    public static final int MAX_VOLUME = 8192;

    /** Block id at a position, or null when the client has no data there (unloaded chunk). */
    @FunctionalInterface
    public interface BlockSource {
        String blockAt(int x, int y, int z);
    }

    private final int[] min;
    private final int[] size;
    private final int[] indices;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> lookup = new HashMap<>();
    private int unloadedIndex = -1;
    private int next;
    private long startTick = -1;
    private long tick = -1;

    public BlockScan(int[] min, int[] size) {
        long volume = volume(size);
        if (volume < 1 || volume > MAX_VOLUME) throw new IllegalArgumentException("scan volume " + volume);
        this.min = min.clone();
        this.size = size.clone();
        this.indices = new int[(int) volume];
    }

    public static long volume(int[] size) {
        return (long) size[0] * size[1] * size[2];
    }

    /** The box centred on the feet block: {@code min = feet − floor(size / 2)} per axis. */
    public static int[] centred(int[] size, int[] feet) {
        return new int[] {feet[0] - size[0] / 2, feet[1] - size[1] / 2, feet[2] - size[2] / 2};
    }

    /**
     * The wire refusal reason for a box, or null when it is within the caps:
     * {@code over_volume} above {@link #MAX_VOLUME} positions, {@code over_radius}
     * when any position lies farther than {@code radius} from {@code feet} on an axis.
     */
    public static String refusal(int[] min, int[] size, int[] feet, int radius) {
        if (volume(size) > MAX_VOLUME) return "over_volume";
        for (int axis = 0; axis < 3; axis++) {
            long low = min[axis];
            long high = low + size[axis] - 1;
            if (low < (long) feet[axis] - radius || high > (long) feet[axis] + radius) return "over_radius";
        }
        return null;
    }

    /**
     * Read up to {@code budget} more positions, stamping them with {@code tick}.
     * @return the number of positions read
     */
    public int advance(BlockSource source, int budget, long tick) {
        if (startTick < 0) startTick = tick;
        this.tick = tick;
        int end = (int) Math.min(indices.length, (long) next + Math.max(budget, 1));
        int sizeX = size[0];
        int layer = size[0] * size[2];
        for (int i = next; i < end; i++) {
            int dx = i % sizeX;
            int dz = (i % layer) / sizeX;
            int dy = i / layer;
            indices[i] = paletteIndex(source.blockAt(min[0] + dx, min[1] + dy, min[2] + dz));
        }
        int read = end - next;
        next = end;
        return read;
    }

    private int paletteIndex(String block) {
        if (block == null) {
            if (unloadedIndex < 0) {
                unloadedIndex = palette.size();
                palette.add(null);
            }
            return unloadedIndex;
        }
        Integer index = lookup.get(block);
        if (index == null) {
            index = palette.size();
            palette.add(block);
            lookup.put(block, index);
        }
        return index;
    }

    public boolean done() {
        return next == indices.length;
    }

    public int[] min() {
        return min.clone();
    }

    public int[] size() {
        return size.clone();
    }

    /** The {@code scan_result} reply; call only when {@link #done()}. */
    public String result(JsonPrimitive id, String dimension) {
        if (!done()) throw new IllegalStateException("scan incomplete");
        return Messages.scanResult(id, dimension, min, size, startTick, tick, palette, indices);
    }
}
