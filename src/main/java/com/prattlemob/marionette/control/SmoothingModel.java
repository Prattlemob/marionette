package com.prattlemob.marionette.control;

/**
 * A camera smoothing model — one of the D5 experiment candidates (see
 * docs/decisions.md). One instance is created per pan and holds that pan's
 * internal state (e.g. angular velocity). Yaw targets arrive pre-unwrapped
 * by CameraSmoother (shortest path, unbounded yaw space), so models treat
 * yaw and pitch as plain scalars. Pure math — no Minecraft imports.
 */
public interface SmoothingModel {
    /** Angular error below which a pan counts as converged, in degrees. */
    float CONVERGENCE_EPSILON_DEG = 0.05f;

    /** Advance from {@code current} toward {@code target} by {@code dt} seconds. */
    Rotation advance(Rotation current, Rotation target, float dt);

    /** True when current is within tolerance of target. */
    boolean converged(Rotation current, Rotation target);

    /** Clear per-pan internal state; called when a (re)start begins a new pan. */
    void reset();
}
