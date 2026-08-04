package com.prattlemob.marionette.control;

import java.util.EnumSet;
import java.util.Set;

/**
 * Set-and-hold control intent: values persist until changed by a later
 * command, mirroring keys a player holds down. One instance is owned by the
 * client tick loop and only ever touched from the client tick thread. Pure
 * data — no Minecraft imports — so it stays unit-testable.
 */
public final class ControlState {
    /** A one-shot raw camera rotation intent, consumed when applied. */
    public record Look(float yaw, float pitch) {}

    /** An accumulated relative camera offset, consumed when applied. */
    public record LookDelta(float yaw, float pitch) {}

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneak;
    private boolean sprint;
    private Look pendingLook;
    private float pendingDeltaYaw;
    private float pendingDeltaPitch;
    private boolean hasPendingDelta;
    private final EnumSet<TapControl> pendingTaps = EnumSet.noneOf(TapControl.class);

    public boolean forward() { return forward; }
    public boolean back() { return back; }
    public boolean left() { return left; }
    public boolean right() { return right; }
    public boolean jump() { return jump; }
    public boolean sneak() { return sneak; }
    public boolean sprint() { return sprint; }

    public void setForward(boolean held) { forward = held; }
    public void setBack(boolean held) { back = held; }
    public void setLeft(boolean held) { left = held; }
    public void setRight(boolean held) { right = held; }
    public void setJump(boolean held) { jump = held; }
    public void setSneak(boolean held) { sneak = held; }
    public void setSprint(boolean held) { sprint = held; }

    /** Queue a raw camera set; replaces any unconsumed intent. */
    public void setLook(float yaw, float pitch) {
        pendingLook = new Look(yaw, pitch);
    }

    /** The pending look intent, clearing it; {@code null} when none queued. */
    public Look consumeLook() {
        Look look = pendingLook;
        pendingLook = null;
        return look;
    }

    /** Accumulate a relative camera offset for the next tick (mode "delta"). */
    public void addLookDelta(float yaw, float pitch) {
        pendingDeltaYaw += yaw;
        pendingDeltaPitch += pitch;
        hasPendingDelta = true;
    }

    /** The accumulated delta, clearing it; {@code null} when none queued. */
    public LookDelta consumeLookDelta() {
        if (!hasPendingDelta) {
            return null;
        }
        LookDelta delta = new LookDelta(pendingDeltaYaw, pendingDeltaPitch);
        pendingDeltaYaw = 0.0f;
        pendingDeltaPitch = 0.0f;
        hasPendingDelta = false;
        return delta;
    }

    /** Queue a one-shot press of {@code control} for the next input tick. */
    public void tap(TapControl control) {
        pendingTaps.add(control);
    }

    /** The pending taps, clearing them; empty when none queued. */
    public Set<TapControl> consumeTaps() {
        if (pendingTaps.isEmpty()) {
            return Set.of();
        }
        Set<TapControl> taps = EnumSet.copyOf(pendingTaps);
        pendingTaps.clear();
        return taps;
    }

    /** True when any control is held. */
    public boolean anyHeld() {
        return forward || back || left || right || jump || sneak || sprint;
    }

    /** Return every control to neutral and drop any pending look intent, look delta, and pending taps. */
    public void releaseAll() {
        forward = back = left = right = jump = sneak = sprint = false;
        pendingLook = null;
        pendingDeltaYaw = 0.0f;
        pendingDeltaPitch = 0.0f;
        hasPendingDelta = false;
        pendingTaps.clear();
    }
}
