package com.prattlemob.marionette.control;

import java.util.EnumSet;

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
    private boolean attack;
    private boolean use;
    private Look pendingLook;
    private float pendingDeltaYaw;
    private float pendingDeltaPitch;
    private boolean hasPendingDelta;
    private final EnumSet<TapControl> pendingTaps = EnumSet.noneOf(TapControl.class);
    private Integer pendingHotbar;

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

    public boolean attack() { return attack; }
    public boolean use() { return use; }

    /** Hold/release attack; a rising edge queues the click a vanilla press carries. */
    public void setAttack(boolean held) {
        if (held && !attack) {
            pendingTaps.add(TapControl.ATTACK);
        }
        attack = held;
    }

    /** Hold/release use; a rising edge queues the click a vanilla press carries. */
    public void setUse(boolean held) {
        if (held && !use) {
            pendingTaps.add(TapControl.USE);
        }
        use = held;
    }

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

    /**
     * Queue a one-shot press of {@code control} for the next input tick.
     * A tap of a currently held attack/use is ignored: the hold's own
     * edge click already happened, and an extra consumed click would
     * fire a real extra swing (for jump the OR-merge makes this case
     * invisible, so no guard is needed).
     */
    public void tap(TapControl control) {
        if (control == TapControl.ATTACK && attack) {
            return;
        }
        if (control == TapControl.USE && use) {
            return;
        }
        pendingTaps.add(control);
    }

    /** True exactly once per queued tap of {@code control}, clearing only it. */
    public boolean consumeTap(TapControl control) {
        return pendingTaps.remove(control);
    }

    /** Queue a one-shot hotbar slot select (0–8); replaces any unconsumed intent. */
    public void selectHotbar(int slot) {
        pendingHotbar = slot;
    }

    /** The pending hotbar slot, clearing it; {@code null} when none queued. */
    public Integer consumeHotbar() {
        Integer slot = pendingHotbar;
        pendingHotbar = null;
        return slot;
    }

    /**
     * Drop attack/use taps nothing consumed this tick (the one-tick tap
     * lifetime: a screen kept handleKeybinds from running, and a stale
     * click must not fire when the menu closes later). Called at tick
     * post; jump taps are consumed unconditionally by the input mixin
     * and need no lifetime rule.
     */
    public void dropInteractionTaps() {
        pendingTaps.remove(TapControl.ATTACK);
        pendingTaps.remove(TapControl.USE);
    }

    /**
     * The screen-open rule (M3.3 spec): opening a screen releases
     * attack/use and drops interaction intents, mirroring vanilla's
     * key release on setScreen — a hold that "resumed" on close would
     * stall against the screen's missTime=10000. Movement holds are
     * deliberately untouched (D4: movement continues through GUIs).
     */
    public void releaseInteractions() {
        attack = false;
        use = false;
        pendingHotbar = null;
        dropInteractionTaps();
    }

    /** True when any control is held. */
    public boolean anyHeld() {
        return forward || back || left || right || jump || sneak || sprint || attack || use;
    }

    /**
     * Return every control to neutral and drop every pending intent:
     * look, look delta, taps (including attack/use edge clicks), and the
     * hotbar select. The selected hotbar slot itself is world state and
     * is not restored (M3.3 spec).
     */
    public void releaseAll() {
        forward = back = left = right = jump = sneak = sprint = false;
        attack = use = false;
        pendingLook = null;
        pendingDeltaYaw = 0.0f;
        pendingDeltaPitch = 0.0f;
        hasPendingDelta = false;
        pendingTaps.clear();
        pendingHotbar = null;
    }
}
