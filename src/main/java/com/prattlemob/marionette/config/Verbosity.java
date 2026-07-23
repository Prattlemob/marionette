package com.prattlemob.marionette.config;

/**
 * Marionette log chattiness, ordered quietest to loudest. QUIET emits
 * warnings/errors only; NORMAL adds lifecycle and connection events;
 * VERBOSE adds per-tick heartbeat and puppet-position evidence logs.
 */
public enum Verbosity {
    QUIET,
    NORMAL,
    VERBOSE;

    /** True when this configured level includes messages gated at {@code level}. */
    public boolean atLeast(Verbosity level) {
        return ordinal() >= level.ordinal();
    }
}
