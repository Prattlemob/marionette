package com.prattlemob.marionette.control;

import java.util.Locale;

/**
 * Controls that support one-shot taps: pressed for exactly one client
 * tick, then auto-released. Wire names in the protocol's
 * {@code input.tap} array are the lowercase enum names. JUMP is
 * consumed by KeyboardInputMixin; ATTACK and USE by
 * MinecraftInteractionMixin (M3.3), as is SWAP_HANDS (M3.7, the
 * vanilla swap-offhand key; capability {@code swapHands}).
 */
public enum TapControl {
    JUMP,
    ATTACK,
    USE,
    SWAP_HANDS;

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
