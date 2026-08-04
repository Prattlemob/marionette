package com.prattlemob.marionette.control;

/**
 * D5 candidate 2: exponential smoothing — simplest; ease-out only (fastest
 * at pan start, expected to read slightly robotic). Framerate-independent
 * via the exact decay form. τ = 30/speed seconds.
 */
public final class ExponentialModel implements SmoothingModel {
    private final float tau;

    public ExponentialModel(float speedDegPerSec) {
        this.tau = 30.0f / speedDegPerSec;
    }

    @Override
    public Rotation advance(Rotation current, Rotation target, float dt) {
        float blend = 1.0f - (float) Math.exp(-dt / tau);
        return new Rotation(
                current.yaw() + (target.yaw() - current.yaw()) * blend,
                current.pitch() + (target.pitch() - current.pitch()) * blend);
    }

    @Override
    public boolean converged(Rotation current, Rotation target) {
        return Math.abs(current.yaw() - target.yaw()) <= CONVERGENCE_EPSILON_DEG
                && Math.abs(current.pitch() - target.pitch()) <= CONVERGENCE_EPSILON_DEG;
    }

    @Override
    public void reset() {
    }
}
