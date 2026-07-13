package com.prattlemob.marionette.control;

/**
 * Injects a {@link ControlState} into the running client, once per client
 * tick (pre). The D4 experiment lives behind this seam: two implementations
 * are prototyped, the winner stays. Implementations may touch game state and
 * are therefore client-tick-thread-only.
 */
public interface ControlStateApplier {
    /** Impose {@code state} on the player's input for this tick. */
    void apply(ControlState state);

    /** Return input to vanilla immediately; must never leave stuck state. */
    void release();
}
