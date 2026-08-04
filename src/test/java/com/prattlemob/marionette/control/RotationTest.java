package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RotationTest {
    @Test
    void wrapDegreesMapsToHalfOpenRange() {
        assertEquals(0.0f, Rotation.wrapDegrees(0.0f));
        assertEquals(0.0f, Rotation.wrapDegrees(360.0f));
        assertEquals(-180.0f, Rotation.wrapDegrees(180.0f));
        assertEquals(-170.0f, Rotation.wrapDegrees(190.0f));
        assertEquals(170.0f, Rotation.wrapDegrees(-190.0f));
        assertEquals(10.0f, Rotation.wrapDegrees(730.0f));
    }

    @Test
    void unwrapNearPicksTheCongruentValueClosestToReference() {
        // 350 -> 10 must pan through 360, not back through 180.
        assertEquals(370.0f, Rotation.unwrapNear(350.0f, 10.0f));
        assertEquals(-10.0f, Rotation.unwrapNear(0.0f, 350.0f));
        assertEquals(90.0f, Rotation.unwrapNear(80.0f, 90.0f));
        // Player yaw is unbounded; unwrap must live in the reference's turn count.
        assertEquals(730.0f, Rotation.unwrapNear(720.0f, 10.0f));
    }

    @Test
    void clampPitchLimitsToVerticals() {
        assertEquals(90.0f, Rotation.clampPitch(120.0f));
        assertEquals(-90.0f, Rotation.clampPitch(-91.0f));
        assertEquals(45.0f, Rotation.clampPitch(45.0f));
    }
}
