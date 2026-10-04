package com.prattlemob.marionette.observation;

import com.google.gson.JsonObject;

/**
 * Minecraft-free builder for the {@code world} observation section
 * (protocol/v1.md, World section).
 */
public final class WorldJson {
    static final long DAY_TICKS = 24000L;

    private WorldJson() {}

    /** Light levels (0–15) at the feet block. */
    public record Light(int block, int sky, int combined, int effective) {}

    /** {@code raining}/{@code thundering} are vanilla's thresholded level-wide states. */
    public static String weather(boolean raining, boolean thundering) {
        if (thundering) return "thunder";
        return raining ? "rain" : "clear";
    }

    public static JsonObject build(String dimension, long dayTime, boolean raining, boolean thundering,
                                   float rainLevel, float thunderLevel, int feetX, int feetY, int feetZ, Light light) {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", dimension);
        result.addProperty("dayTime", dayTime);
        result.addProperty("timeOfDay", Math.floorMod(dayTime, DAY_TICKS));
        result.addProperty("day", Math.floorDiv(dayTime, DAY_TICKS));
        result.addProperty("weather", weather(raining, thundering));
        result.addProperty("rainLevel", rainLevel);
        result.addProperty("thunderLevel", thunderLevel);
        JsonObject feet = new JsonObject();
        feet.addProperty("x", feetX);
        feet.addProperty("y", feetY);
        feet.addProperty("z", feetZ);
        result.add("feet", feet);
        JsonObject levels = new JsonObject();
        levels.addProperty("block", light.block());
        levels.addProperty("sky", light.sky());
        levels.addProperty("combined", light.combined());
        levels.addProperty("effective", light.effective());
        result.add("light", levels);
        return result;
    }
}
