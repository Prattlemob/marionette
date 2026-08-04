package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SmoothingModelTest {
    private static final float DT = 1.0f / 60.0f;
    private static final float SPEED = 180.0f;

    /** Run a pan to convergence; returns the trajectory including start. Fails at 20 simulated seconds. */
    private static List<Rotation> simulate(SmoothingModel model, Rotation start, Rotation target) {
        var trajectory = new java.util.ArrayList<Rotation>();
        Rotation current = start;
        trajectory.add(current);
        model.reset();
        for (int frame = 0; frame < 20 * 60; frame++) {
            if (model.converged(current, target)) {
                return trajectory;
            }
            current = model.advance(current, target, DT);
            trajectory.add(current);
        }
        throw new AssertionError("did not converge within 20 s: last=" + current);
    }

    private static void assertConvergesWithoutOvershoot(SmoothingModel model) {
        Rotation start = new Rotation(0.0f, 0.0f);
        Rotation target = new Rotation(90.0f, 20.0f);
        List<Rotation> trajectory = simulate(model, start, target);
        Rotation last = trajectory.getLast();
        assertEquals(target.yaw(), last.yaw(), SmoothingModel.CONVERGENCE_EPSILON_DEG);
        assertEquals(target.pitch(), last.pitch(), SmoothingModel.CONVERGENCE_EPSILON_DEG);
        for (Rotation r : trajectory) {
            assertTrue(r.yaw() <= target.yaw() + 0.5f, "yaw overshoot: " + r.yaw());
            assertTrue(r.pitch() <= target.pitch() + 0.5f, "pitch overshoot: " + r.pitch());
        }
        // Monotone progress toward the target.
        for (int i = 1; i < trajectory.size(); i++) {
            assertTrue(trajectory.get(i).yaw() >= trajectory.get(i - 1).yaw() - 1e-3f,
                    "yaw moved away from target at frame " + i);
        }
    }

    @Test
    void cappedRateConvergesWithoutOvershoot() {
        assertConvergesWithoutOvershoot(new CappedRateModel(SPEED));
    }

    @Test
    void doubledSpeedConvergesFaster() {
        int slow = simulate(new CappedRateModel(SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
        int fast = simulate(new CappedRateModel(2 * SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
        assertTrue(fast < slow, "fast=" + fast + " !< slow=" + slow);
    }

    @Test
    void ninetyDegreePanAtDefaultSpeedSettlesWithinBudget() {
        int frames = simulate(new CappedRateModel(SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
        assertTrue(frames <= 90, "took " + frames + " frames (> 1.5 s)");
    }

    @Test
    void cappedRateNeverExceedsMaxVelocity() {
        List<Rotation> trajectory = simulate(new CappedRateModel(SPEED),
                new Rotation(0, 0), new Rotation(90, 0));
        for (int i = 1; i < trajectory.size(); i++) {
            float step = trajectory.get(i).yaw() - trajectory.get(i - 1).yaw();
            assertTrue(step <= SPEED * DT + 1e-3f, "step " + step + " exceeds max velocity");
        }
    }
}
