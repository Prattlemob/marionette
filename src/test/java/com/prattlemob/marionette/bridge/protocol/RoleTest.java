package com.prattlemob.marionette.bridge.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RoleTest {
    @Test
    void wireStringsRoundTrip() {
        assertEquals(Role.CONTROLLER, Role.fromWire("controller"));
        assertEquals(Role.OBSERVER, Role.fromWire("observer"));
        assertEquals("controller", Role.CONTROLLER.wire());
        assertEquals("observer", Role.OBSERVER.wire());
    }

    @Test
    void unknownAndReservedRolesMapToNull() {
        assertNull(Role.fromWire("director"), "director is reserved, not implemented");
        assertNull(Role.fromWire("puppeteer"));
        assertNull(Role.fromWire(""));
    }
}
