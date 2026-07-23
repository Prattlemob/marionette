package com.prattlemob.marionette.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VerbosityTest {
    @Test
    void higherOrEqualLevelsInclude() {
        assertTrue(Verbosity.VERBOSE.atLeast(Verbosity.NORMAL));
        assertTrue(Verbosity.VERBOSE.atLeast(Verbosity.VERBOSE));
        assertTrue(Verbosity.NORMAL.atLeast(Verbosity.NORMAL));
        assertTrue(Verbosity.QUIET.atLeast(Verbosity.QUIET));
    }

    @Test
    void lowerLevelsExclude() {
        assertFalse(Verbosity.QUIET.atLeast(Verbosity.NORMAL));
        assertFalse(Verbosity.QUIET.atLeast(Verbosity.VERBOSE));
        assertFalse(Verbosity.NORMAL.atLeast(Verbosity.VERBOSE));
    }
}
