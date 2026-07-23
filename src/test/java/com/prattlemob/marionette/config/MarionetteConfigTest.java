package com.prattlemob.marionette.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarionetteConfigTest {
    @Test
    void loopbackAddressesPassThrough() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("127.0.0.1"));
        assertEquals("localhost", MarionetteConfig.resolveBindAddress("localhost"));
        assertEquals("::1", MarionetteConfig.resolveBindAddress("::1"));
        assertEquals("127.0.0.53", MarionetteConfig.resolveBindAddress("127.0.0.53"));
    }

    @Test
    void nonLoopbackClampsToLoopback() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("0.0.0.0"));
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("192.168.1.10"));
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("8.8.8.8"));
    }

    @Test
    void unresolvableClampsToLoopback() {
        assertEquals("127.0.0.1", MarionetteConfig.resolveBindAddress("not a hostname!"));
    }

    @Test
    void defaultsAreSafeBeforeConfigLoads() {
        assertEquals(24680, MarionetteConfig.port);
        assertEquals("127.0.0.1", MarionetteConfig.bindAddress);
        assertEquals(1, MarionetteConfig.observationRateDivisor);
        assertEquals(Verbosity.NORMAL, MarionetteConfig.verbosity);
    }
}
