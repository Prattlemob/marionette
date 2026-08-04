package com.prattlemob.marionette.control;

/**
 * D5 experiment selector (config `camera.smoothingModel`). EXPERIMENTAL:
 * this enum, the config entry, and the two losing models are deleted once
 * the D5 winner is recorded in docs/decisions.md.
 */
public enum SmoothingModelType {
    DAMPED_SPRING,
    EXPONENTIAL,
    CAPPED_RATE;

    /** A fresh per-pan model; {@code speedDegPerSec} is the characteristic pan speed. */
    public SmoothingModel create(float speedDegPerSec) {
        return switch (this) {
            case DAMPED_SPRING -> new DampedSpringModel(speedDegPerSec);
            case EXPONENTIAL -> new ExponentialModel(speedDegPerSec);
            case CAPPED_RATE -> new CappedRateModel(speedDegPerSec);
        };
    }
}
