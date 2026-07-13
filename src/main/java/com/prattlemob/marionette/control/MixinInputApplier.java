package com.prattlemob.marionette.control;

/**
 * D4 variant 2: publish the active {@link ControlState} for the
 * KeyboardInput mixin to merge into the player's semantic input each input
 * tick. Manipulates meaning rather than faking key presses, so vanilla key
 * state stays visible underneath (human keys OR-merge with agent controls).
 */
public final class MixinInputApplier implements ControlStateApplier {
    private static volatile ControlState active;

    /** Read by the mixin on the client tick thread; null = agent inactive. */
    public static ControlState activeState() {
        return active;
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
