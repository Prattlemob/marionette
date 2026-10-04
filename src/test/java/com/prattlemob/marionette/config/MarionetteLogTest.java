package com.prattlemob.marionette.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MarionetteLogTest {
    @AfterEach
    void reset() {
        MarionetteLog.configure(Verbosity.NORMAL, Map.of());
    }

    @Test
    void categoriesInheritTheGlobalVerbosity() {
        MarionetteLog.configure(Verbosity.QUIET, Map.of());
        for (LogCategory category : LogCategory.values()) {
            assertEquals(Verbosity.QUIET, MarionetteLog.level(category));
            assertFalse(MarionetteLog.enabled(category, Verbosity.NORMAL));
        }
    }

    @Test
    void anOverrideAppliesToItsCategoryOnly() {
        MarionetteLog.configure(Verbosity.NORMAL, Map.of(LogCategory.BRIDGE, Verbosity.VERBOSE,
                LogCategory.CONTROL, Verbosity.QUIET));
        assertTrue(MarionetteLog.enabled(LogCategory.BRIDGE, Verbosity.VERBOSE));
        assertFalse(MarionetteLog.enabled(LogCategory.CONTROL, Verbosity.NORMAL));
        assertTrue(MarionetteLog.enabled(LogCategory.EVENTS, Verbosity.NORMAL));
        assertFalse(MarionetteLog.enabled(LogCategory.EVENTS, Verbosity.VERBOSE));
    }

    @Test
    void reconfiguringClearsOldOverrides() {
        MarionetteLog.configure(Verbosity.NORMAL, Map.of(LogCategory.CLIENT, Verbosity.QUIET));
        MarionetteLog.configure(Verbosity.VERBOSE, Map.of());
        assertEquals(Verbosity.VERBOSE, MarionetteLog.level(LogCategory.CLIENT));
    }

    @Test
    void eachCategoryHasItsOwnLoggerAndInheritMeansNoOverride() {
        assertEquals("marionette.precedence", MarionetteLog.logger(LogCategory.PRECEDENCE).getName());
        assertEquals(null, CategoryVerbosity.INHERIT.verbosity());
        assertEquals(Verbosity.VERBOSE, CategoryVerbosity.VERBOSE.verbosity());
    }
}
