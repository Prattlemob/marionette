package com.prattlemob.marionette.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarionetteConfigTest {
    @Test
    void loopbackAddressesPassThrough() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("127.0.0.1").getHostAddress());
        assertTrue(MarionetteConfig.resolveBindAddress("localhost").isLoopbackAddress());
        assertEquals("0:0:0:0:0:0:0:1", MarionetteConfig.resolveBindAddress("::1").getHostAddress());
        assertEquals("127.0.0.53", MarionetteConfig.resolveBindAddress("127.0.0.53").getHostAddress());
    }

    @Test
    void nonLoopbackClampsToLoopback() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("0.0.0.0").getHostAddress());
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("192.168.1.10").getHostAddress());
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("8.8.8.8").getHostAddress());
    }

    @Test
    void unresolvableClampsToLoopback() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("not a hostname!").getHostAddress());
    }

    @Test
    void defaultsAreSafeBeforeConfigLoads() {
        assertEquals(24680, MarionetteConfig.port);
        assertEquals("127.0.0.1", MarionetteConfig.bindAddress);
        assertEquals(1, MarionetteConfig.observationRateDivisor);
        assertEquals(Verbosity.NORMAL, MarionetteConfig.verbosity);
        assertEquals(true, MarionetteConfig.bridgeEnabled);
        assertEquals(32, MarionetteConfig.entityRadius);
        assertEquals(64, MarionetteConfig.entityMaxCount);
        assertEquals(16, MarionetteConfig.blockScanRadius);
        assertEquals(1024, MarionetteConfig.blockScanBlocksPerTick);
        assertEquals(true, MarionetteConfig.suppressPauseOnLostFocus);
        assertEquals(180.0, MarionetteConfig.cameraSmoothingSpeed);
    }

    @Test
    void observerDefaultsMatchTheSpec() {
        assertEquals(2, MarionetteConfig.maxObservers);
        assertEquals(10, MarionetteConfig.helloTimeoutSeconds);
    }

    @Test
    void pongWatchdogDefaultsToTwoSeconds() {
        assertEquals(2, MarionetteConfig.pongTimeoutSeconds);
    }

    @Test
    void observationDueAtRespectsDivisor() {
        int original = MarionetteConfig.observationRateDivisor;
        try {
            MarionetteConfig.observationRateDivisor = 20;
            assertEquals(true, MarionetteConfig.observationDueAt(0));
            assertEquals(true, MarionetteConfig.observationDueAt(20));
            assertEquals(true, MarionetteConfig.observationDueAt(40));
            assertEquals(false, MarionetteConfig.observationDueAt(1));
            assertEquals(false, MarionetteConfig.observationDueAt(19));
            assertEquals(false, MarionetteConfig.observationDueAt(21));

            MarionetteConfig.observationRateDivisor = 1;
            assertEquals(true, MarionetteConfig.observationDueAt(0));
            assertEquals(true, MarionetteConfig.observationDueAt(1));
            assertEquals(true, MarionetteConfig.observationDueAt(2));
        } finally {
            MarionetteConfig.observationRateDivisor = original;
        }
    }

    @Test
    void observationDueAtHonorsExplicitDivisor() {
        assertTrue(MarionetteConfig.observationDueAt(0, 4));
        assertFalse(MarionetteConfig.observationDueAt(1, 4));
        assertFalse(MarionetteConfig.observationDueAt(3, 4));
        assertTrue(MarionetteConfig.observationDueAt(4, 4));
        assertTrue(MarionetteConfig.observationDueAt(7, 1));
    }
}
