package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CameraSmootherTest {
    private static final float DT = 1.0f / 60.0f;

    private CameraSmoother smoother;

    @BeforeEach
    void setUp() {
        smoother = new CameraSmoother();
    }

    /** Advance until converged; returns the final rotation. Fails at 20 simulated seconds. */
    private Rotation runToConvergence(Rotation start) {
        Rotation current = start;
        for (int frame = 0; frame < 20 * 60 && smoother.active(); frame++) {
            Rotation next = smoother.advanceFrame(current, DT);
            if (next != null) {
                current = next;
            }
        }
        assertFalse(smoother.active(), "did not converge: " + current);
        return current;
    }

    @Test
    void inactiveByDefault() {
        assertFalse(smoother.active());
        assertNull(smoother.advanceFrame(new Rotation(0, 0), DT));
        assertNull(smoother.target());
    }

    @Test
    void anglePanConvergesToExactTarget() {
        smoother.startAngles(90.0f, 10.0f, new CappedRateModel(180.0f), new Rotation(0, 0));
        assertTrue(smoother.active());
        Rotation end = runToConvergence(new Rotation(0, 0));
        assertEquals(90.0f, end.yaw());   // exact write on convergence, not epsilon-close
        assertEquals(10.0f, end.pitch());
    }

    @Test
    void anglePanUnwrapsYawShortestPath() {
        smoother.startAngles(10.0f, 0.0f, new CappedRateModel(180.0f), new Rotation(350.0f, 0));
        assertEquals(370.0f, smoother.target().yaw());
    }

    @Test
    void anglePanClampsTargetPitch() {
        smoother.startAngles(0.0f, 135.0f, new CappedRateModel(180.0f), new Rotation(0, 0));
        assertEquals(90.0f, smoother.target().pitch());
    }

    @Test
    void pointPanAimsFromEye() {
        // Eye at origin, point due north (-Z): Minecraft yaw for -Z is ±180.
        boolean started = smoother.startPoint(0.0, 0.0, -10.0, new CappedRateModel(180.0f),
                new Rotation(0, 0), 0.0, 0.0, 0.0);
        assertTrue(started);
        assertEquals(180.0f, Math.abs(smoother.target().yaw()), 1e-3f);
        assertEquals(0.0f, smoother.target().pitch(), 1e-3f);
    }

    @Test
    void pointAboveGivesNegativePitch() {
        smoother.startPoint(0.0, 10.0, 10.0, new CappedRateModel(180.0f),
                new Rotation(0, 0), 0.0, 0.0, 0.0);
        assertEquals(-45.0f, smoother.target().pitch(), 1e-3f);
        assertEquals(0.0f, smoother.target().yaw(), 1e-3f);
    }

    @Test
    void degeneratePointRefusesToStart() {
        boolean started = smoother.startPoint(1.0, 2.0, 3.0, new CappedRateModel(180.0f),
                new Rotation(0, 0), 1.0, 2.0, 3.0);
        assertFalse(started);
        assertFalse(smoother.active());
    }

    @Test
    void onTickReaimsPointTarget() {
        smoother.startPoint(10.0, 0.0, 0.0, new CappedRateModel(180.0f),
                new Rotation(0, 0), 0.0, 0.0, 0.0);
        float before = smoother.target().yaw();
        // Eye moves; the same point is now in a different direction.
        smoother.onTick(new Rotation(0, 0), 0.0, 0.0, -10.0);
        assertTrue(Math.abs(smoother.target().yaw() - before) > 1.0f);
    }

    @Test
    void onTickKeepsPreviousAimWhenDegenerate() {
        smoother.startPoint(10.0, 0.0, 0.0, new CappedRateModel(180.0f),
                new Rotation(0, 0), 0.0, 0.0, 0.0);
        Rotation before = smoother.target();
        smoother.onTick(new Rotation(0, 0), 10.0, 0.0, 0.0); // eye reached the point
        assertEquals(before, smoother.target());
    }

    @Test
    void onTickIgnoresAngleTargets() {
        smoother.startAngles(90.0f, 0.0f, new CappedRateModel(180.0f), new Rotation(0, 0));
        smoother.onTick(new Rotation(0, 0), 5.0, 5.0, 5.0);
        assertEquals(90.0f, smoother.target().yaw());
    }

    @Test
    void cancelDeactivatesWithoutSnap() {
        smoother.startAngles(90.0f, 0.0f, new CappedRateModel(180.0f), new Rotation(0, 0));
        Rotation mid = smoother.advanceFrame(new Rotation(0, 0), DT);
        smoother.cancel();
        assertFalse(smoother.active());
        assertNull(smoother.advanceFrame(mid, DT)); // no further writes
    }

    @Test
    void restartReplacesActivePan() {
        smoother.startAngles(90.0f, 0.0f, new CappedRateModel(180.0f), new Rotation(0, 0));
        smoother.startAngles(-45.0f, 5.0f, new CappedRateModel(180.0f), new Rotation(10.0f, 0));
        assertEquals(-45.0f, smoother.target().yaw());
        assertEquals(5.0f, smoother.target().pitch());
    }
}
