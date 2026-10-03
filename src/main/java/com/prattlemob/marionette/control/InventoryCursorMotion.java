package com.prattlemob.marionette.control;

/** A time-based cursor leg. Read-only interpolation can be sampled at render frequency. */
public final class InventoryCursorMotion {
    private final double fromX, fromY, toX, toY;
    private final long starts, travel;
    private static final long DWELL = 100_000_000L;

    public InventoryCursorMotion(double fromX, double fromY, double toX, double toY, long starts) {
        this.fromX = fromX;
        this.fromY = fromY;
        this.toX = toX;
        this.toY = toY;
        this.starts = starts;
        travel = (long) (Math.clamp(Math.hypot(toX - fromX, toY - fromY) / 400.0, 0.2, 0.45) * 1_000_000_000L);
    }

    private double fraction(long now) {
        double t = Math.clamp((double) (now - starts) / travel, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public double x(long now) { return fromX + (toX - fromX) * fraction(now); }
    public double y(long now) { return fromY + (toY - fromY) * fraction(now); }
    public boolean ready(long now) { return now - starts >= travel + DWELL; }
}
