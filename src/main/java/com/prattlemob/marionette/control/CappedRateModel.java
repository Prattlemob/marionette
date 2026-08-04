package com.prattlemob.marionette.control;

/**
 * The settled D5 camera smoothing model (see docs/decisions.md): constant
 * max angular velocity with ease-in/out — a trapezoidal velocity profile
 * along the straight yaw/pitch line to the target. Max velocity = speed;
 * acceleration = 4·speed. The ease-out is brake-limited: velocity never
 * exceeds what can still decelerate to zero exactly at the target.
 */
public final class CappedRateModel implements SmoothingModel {
    private final float maxVelocity;
    private final float acceleration;
    private float velocity;

    public CappedRateModel(float speedDegPerSec) {
        this.maxVelocity = speedDegPerSec;
        this.acceleration = 4.0f * speedDegPerSec;
    }

    @Override
    public Rotation advance(Rotation current, Rotation target, float dt) {
        float yawError = target.yaw() - current.yaw();
        float pitchError = target.pitch() - current.pitch();
        float remaining = (float) Math.hypot(yawError, pitchError);
        if (remaining == 0.0f) {
            velocity = 0.0f;
            return target;
        }
        float brakeLimit = (float) Math.sqrt(2.0f * acceleration * remaining);
        velocity = Math.min(Math.min(velocity + acceleration * dt, maxVelocity), brakeLimit);
        float step = velocity * dt;
        if (step >= remaining) {
            velocity = 0.0f;
            return target;
        }
        float scale = step / remaining;
        return new Rotation(current.yaw() + yawError * scale, current.pitch() + pitchError * scale);
    }

    @Override
    public boolean converged(Rotation current, Rotation target) {
        return Math.abs(current.yaw() - target.yaw()) <= CONVERGENCE_EPSILON_DEG
                && Math.abs(current.pitch() - target.pitch()) <= CONVERGENCE_EPSILON_DEG;
    }

    @Override
    public void reset() {
        velocity = 0.0f;
    }
}
