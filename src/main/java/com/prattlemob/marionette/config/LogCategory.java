package com.prattlemob.marionette.config;

/**
 * Marionette log categories. Each logs under its own logger name
 * ({@code marionette.<name>}) and has its own configurable verbosity
 * (config {@code logging.<name>}), defaulting to {@code logging.verbosity}.
 */
public enum LogCategory {
    /** Bridge start/stop, connections, admission, liveness. */
    BRIDGE("bridge"),
    /** Agent actuation: releases, camera pans, respawn, chat, puppet evidence. */
    CONTROL("control"),
    /** Human precedence, panic, re-arm and the input lockout. */
    PRECEDENCE("precedence"),
    /** Observation cadence, coalescing and block scans. */
    OBSERVATION("observation"),
    /** One-shot events as recorded. */
    EVENTS("events"),
    /** World entry/exit, tick heartbeat and the status HUD. */
    CLIENT("client");

    private final String wire;

    LogCategory(String wire) {
        this.wire = wire;
    }

    /** Config key and logger-name suffix. */
    public String key() {
        return wire;
    }
}
