package com.prattlemob.marionette.control;

/**
 * Owns the camera state while a smoothed pan is active (M3.2, D5). Targets
 * are set, replaced, re-aimed, and canceled tick-side only; advanceFrame()
 * runs once per render frame. Client tick and render share one thread, so
 * there is no locking. Pure math and plain state — no Minecraft imports —
 * MarionetteClient feeds in rotations/eye positions and writes results to
 * the player.
 */
public final class CameraSmoother {
    /** Squared eye-to-point distance below which a look-at direction is degenerate (1 mm). */
    static final double DEGENERATE_DISTANCE_SQ = 1.0e-6;

    private SmoothingModel model;
    private Rotation target;
    private boolean trackingPoint;
    private double pointX;
    private double pointY;
    private double pointZ;

    /** True while a pan is in flight. */
    public boolean active() {
        return model != null;
    }

    /** The current unwrapped target, or {@code null} when inactive. */
    public Rotation target() {
        return target;
    }

    /** Start (or replace) a pan toward absolute angles. */
    public void startAngles(float yaw, float pitch, SmoothingModel model, Rotation current) {
        this.model = model;
        model.reset();
        trackingPoint = false;
        target = new Rotation(Rotation.unwrapNear(current.yaw(), yaw), Rotation.clampPitch(pitch));
    }

    /**
     * Start (or replace) a pan toward a world point, re-aimed every tick.
     * Returns {@code false} — pan not started, previous pan untouched —
     * when the eye-to-point direction is degenerate.
     */
    public boolean startPoint(double x, double y, double z, SmoothingModel model,
            Rotation current, double eyeX, double eyeY, double eyeZ) {
        Rotation aimed = aim(x, y, z, current, eyeX, eyeY, eyeZ);
        if (aimed == null) {
            return false;
        }
        this.model = model;
        model.reset();
        trackingPoint = true;
        pointX = x;
        pointY = y;
        pointZ = z;
        target = aimed;
        return true;
    }

    /**
     * Tick-side: re-resolve a point target from the current eye position so
     * accuracy holds while the player moves. Keeps the previous aim when
     * the direction has become degenerate. No-op for angle targets.
     */
    public void onTick(Rotation current, double eyeX, double eyeY, double eyeZ) {
        if (model == null || !trackingPoint) {
            return;
        }
        Rotation aimed = aim(pointX, pointY, pointZ, current, eyeX, eyeY, eyeZ);
        if (aimed != null) {
            target = aimed;
        }
    }

    /**
     * Render-side: advance the pan by {@code dt} seconds. Returns the
     * rotation to write to the player, or {@code null} when no pan is
     * active. On convergence returns the exact target and deactivates.
     */
    public Rotation advanceFrame(Rotation current, float dt) {
        if (model == null) {
            return null;
        }
        if (model.converged(current, target)) {
            Rotation finalTarget = target;
            cancel();
            return finalTarget;
        }
        return model.advance(current, target, dt);
    }

    /** Drop any active pan; the camera stays where it is (no snap-back). */
    public void cancel() {
        model = null;
        target = null;
        trackingPoint = false;
    }

    /** Eye→point angles, yaw unwrapped near current; {@code null} when degenerate. */
    private static Rotation aim(double x, double y, double z,
            Rotation current, double eyeX, double eyeY, double eyeZ) {
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        if (dx * dx + dy * dy + dz * dz < DEGENERATE_DISTANCE_SQ) {
            return null;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new Rotation(Rotation.unwrapNear(current.yaw(), yaw), Rotation.clampPitch(pitch));
    }
}
