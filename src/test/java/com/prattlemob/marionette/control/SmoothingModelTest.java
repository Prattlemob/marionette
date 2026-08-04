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
    void dampedSpringConvergesWithoutOvershoot() {
        assertConvergesWithoutOvershoot(new DampedSpringModel(SPEED));
    }

    @Test
    void exponentialConvergesWithoutOvershoot() {
        assertConvergesWithoutOvershoot(new ExponentialModel(SPEED));
    }

    @Test
    void cappedRateConvergesWithoutOvershoot() {
        assertConvergesWithoutOvershoot(new CappedRateModel(SPEED));
    }

    @Test
    void doubledSpeedConvergesFaster() {
        for (SmoothingModelType type : SmoothingModelType.values()) {
            int slow = simulate(type.create(SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
            int fast = simulate(type.create(2 * SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
            assertTrue(fast < slow, type + ": " + fast + " !< " + slow);
        }
    }

    @Test
    void ninetyDegreePanAtDefaultSpeedSettlesWithinBudget() {
        for (SmoothingModelType type : SmoothingModelType.values()) {
            int frames = simulate(type.create(SPEED), new Rotation(0, 0), new Rotation(90, 0)).size();
            assertTrue(frames <= 90, type + " took " + frames + " frames (> 1.5 s)");
        }
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

    @Test
    void resetClearsSpringVelocity() {
        DampedSpringModel model = new DampedSpringModel(SPEED);
        simulate(model, new Rotation(0, 0), new Rotation(90, 0)); // builds internal velocity
        // A fresh pan from rest must not inherit the old velocity.
        model.reset();
        Rotation first = model.advance(new Rotation(0, 0), new Rotation(-90, 0), DT);
        assertTrue(first.yaw() <= 0.0f, "spring carried stale velocity: " + first.yaw());
    }

    @Test
    void typeCreatesMatchingModel() {
        assertTrue(SmoothingModelType.DAMPED_SPRING.create(SPEED) instanceof DampedSpringModel);
        assertTrue(SmoothingModelType.EXPONENTIAL.create(SPEED) instanceof ExponentialModel);
        assertTrue(SmoothingModelType.CAPPED_RATE.create(SPEED) instanceof CappedRateModel);
    }
}
