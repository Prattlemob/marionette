package com.prattlemob.marionette.control;

import java.util.Locale;

/**
 * Controls that support one-shot taps: pressed for exactly one client
 * tick, then auto-released. M3.3 adds ATTACK and USE. Wire names in the
 * protocol's {@code input.tap} array are the lowercase enum names.
 */
public enum TapControl {
    JUMP;

    /** The name used on the wire in the {@code input.tap} array. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The control for a wire name, or {@code null} if unknown. */
    public static TapControl fromWire(String name) {
        for (TapControl control : values()) {
            if (control.wire().equals(name)) {
                return control;
            }
        }
        return null;
    }
}
