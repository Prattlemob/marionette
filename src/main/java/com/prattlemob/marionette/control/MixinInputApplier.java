package com.prattlemob.marionette.control;

import java.util.function.BooleanSupplier;

/**
 * D4 variant 2: publish the active {@link ControlState} for the
 * KeyboardInput mixin to merge into the player's semantic input each input
 * tick. Manipulates meaning rather than faking key presses, so vanilla key
 * state stays visible underneath (human keys OR-merge with agent controls).
 */
public final class MixinInputApplier implements ControlStateApplier {
    private static volatile ControlState active;
    private static volatile BooleanSupplier localInputLocked = () -> false;

    /** Read by the mixin on the client tick thread; null = agent inactive. */
    public static ControlState activeState() {
        return active;
    }

    /**
     * True while agent-exclusive mode suppresses local gameplay input
     * (protocol/v1.md, Human precedence): the input mixins then replace,
     * rather than merge, the human's keys, buttons and mouse look. The
     * supplier re-checks the attached controller on every read, so the
     * lockout ends the moment no controller is attached.
     */
    public static boolean localInputLocked() {
        return localInputLocked.getAsBoolean();
    }

    /** Installed once by the client entry point. */
    public static void setLocalInputGate(BooleanSupplier gate) {
        localInputLocked = gate;
    }

    @Override
    public void apply(ControlState state) {
        active = state;
    }

    @Override
    public void release() {
        active = null;
    }
}
