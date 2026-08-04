package com.prattlemob.marionette.control;

/**
 * D5 candidate 1: critically damped spring — natural accel/decel, no
 * overshoot by construction (from rest). Closed-form step, so it is
 * unconditionally stable for any frame dt. ω = speed/18 makes a 90° pan at
 * the default 180 deg/s settle in about a second.
 */
public final class DampedSpringModel implements SmoothingModel {
    /** Angular velocity below which the spring counts as settled, deg/s. */
    static final float VELOCITY_EPSILON = 0.5f;

    private final float omega;
    private float yawVelocity;
    private float pitchVelocity;

    public DampedSpringModel(float speedDegPerSec) {
        this.omega = speedDegPerSec / 18.0f;
    }

    @Override
    public Rotation advance(Rotation current, Rotation target, float dt) {
        float decay = (float) Math.exp(-omega * dt);
        float yawOffset = current.yaw() - target.yaw();
        float newYaw = target.yaw() + (yawOffset * (1 + omega * dt) + yawVelocity * dt) * decay;
        yawVelocity = (yawVelocity * (1 - omega * dt) - yawOffset * omega * omega * dt) * decay;
        float pitchOffset = current.pitch() - target.pitch();
        float newPitch = target.pitch() + (pitchOffset * (1 + omega * dt) + pitchVelocity * dt) * decay;
        pitchVelocity = (pitchVelocity * (1 - omega * dt) - pitchOffset * omega * omega * dt) * decay;
        return new Rotation(newYaw, newPitch);
    }

    @Override
    public boolean converged(Rotation current, Rotation target) {
        return Math.abs(current.yaw() - target.yaw()) <= CONVERGENCE_EPSILON_DEG
                && Math.abs(current.pitch() - target.pitch()) <= CONVERGENCE_EPSILON_DEG
                && Math.abs(yawVelocity) <= VELOCITY_EPSILON
                && Math.abs(pitchVelocity) <= VELOCITY_EPSILON;
    }

    @Override
    public void reset() {
        yawVelocity = 0.0f;
        pitchVelocity = 0.0f;
    }
}
