package com.prattlemob.marionette.config;

/** A per-category config value: INHERIT uses {@code logging.verbosity}. */
public enum CategoryVerbosity {
    INHERIT(null),
    QUIET(Verbosity.QUIET),
    NORMAL(Verbosity.NORMAL),
    VERBOSE(Verbosity.VERBOSE);

    private final Verbosity verbosity;

    CategoryVerbosity(Verbosity verbosity) {
        this.verbosity = verbosity;
    }

    /** The override, or null to inherit. */
    public Verbosity verbosity() {
        return verbosity;
    }
}
