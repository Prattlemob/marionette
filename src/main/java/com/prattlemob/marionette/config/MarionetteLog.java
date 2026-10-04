package com.prattlemob.marionette.config;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-category gated logging (M5.2). Minecraft-free so the bridge can use it
 * headless. Each category logs through its own logger,
 * {@code marionette.<category>}, so log lines name their subsystem; messages
 * that carry values use {@code key=value} pairs. Warnings and errors are
 * never gated: QUIET silences only informational lines.
 */
public final class MarionetteLog {
    private static final Map<LogCategory, Logger> LOGGERS = new EnumMap<>(LogCategory.class);
    /** Per-category override; null means "use the global verbosity". */
    private static final Verbosity[] OVERRIDES = new Verbosity[LogCategory.values().length];
    private static volatile Verbosity global = Verbosity.NORMAL;

    static {
        for (LogCategory category : LogCategory.values()) {
            LOGGERS.put(category, LoggerFactory.getLogger("marionette." + category.key()));
        }
    }

    private MarionetteLog() {
    }

    /** The configured global verbosity and per-category overrides (null = inherit). */
    public static synchronized void configure(Verbosity verbosity, Map<LogCategory, Verbosity> overrides) {
        global = verbosity;
        for (LogCategory category : LogCategory.values()) {
            OVERRIDES[category.ordinal()] = overrides.get(category);
        }
    }

    /** The effective verbosity of {@code category}. */
    public static Verbosity level(LogCategory category) {
        Verbosity override = OVERRIDES[category.ordinal()];
        return override != null ? override : global;
    }

    /** True when a line gated at {@code level} in {@code category} is emitted. */
    public static boolean enabled(LogCategory category, Verbosity level) {
        return level(category).atLeast(level);
    }

    /** The category's logger, for ungated warnings and errors. */
    public static Logger logger(LogCategory category) {
        return LOGGERS.get(category);
    }

    /** Lifecycle and connection lines (NORMAL and above). */
    public static void normal(LogCategory category, String message, Object... args) {
        if (enabled(category, Verbosity.NORMAL)) LOGGERS.get(category).info(message, args);
    }

    /** Per-tick and evidence lines (VERBOSE only). */
    public static void verbose(LogCategory category, String message, Object... args) {
        if (enabled(category, Verbosity.VERBOSE)) LOGGERS.get(category).info(message, args);
    }
}
