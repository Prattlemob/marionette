package com.prattlemob.marionette.control;

/**
 * An absolute camera orientation in degrees (Minecraft convention: yaw 0 =
 * south, clockwise; pitch −90 = up, +90 = down). Pure data — no Minecraft
 * imports — so camera math stays unit-testable.
 */
public record Rotation(float yaw, float pitch) {
    /** Wrap an angle to [-180, 180). */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    /**
     * The value congruent to {@code angle} (mod 360) nearest
     * {@code reference}. Player yaw is unbounded (turns accumulate), so pan
     * targets are expressed in the player's current turn count and a pan
     * always takes the shortest way around.
     */
    public static float unwrapNear(float reference, float angle) {
        return reference + wrapDegrees(angle - reference);
    }

    /** Clamp pitch to Minecraft's [-90, 90]. */
    public static float clampPitch(float pitch) {
        return Math.clamp(pitch, -90.0f, 90.0f);
    }
}
