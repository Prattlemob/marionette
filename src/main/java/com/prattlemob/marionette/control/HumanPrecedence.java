package com.prattlemob.marionette.control;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Human precedence (protocol/v1.md, Human precedence; docs/safety-state-machine.md):
 * the local mode, the human-priority pause and the agent-exclusive input
 * lockout. Pure state — no Minecraft imports — owned by the client thread.
 * The caller releases agent actuators when a change says so; this class only
 * decides.
 *
 * <p>The controller is tracked by identity (whatever object the bridge uses
 * for the attached, admitted controller), so a lockout can never outlive the
 * controller it was engaged for, even when another one attaches between two
 * synchronizations.
 */
public final class HumanPrecedence {
    /** The three modes, with their wire names. */
    public enum Mode {
        HUMAN_PRIORITY("human_priority"),
        AGENT_EXCLUSIVE("agent_exclusive"),
        PANIC("panic");

        private final String wire;

        Mode(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** Categories of local human gameplay input, in wire order. */
    public enum HumanInput {
        MOVEMENT("movement"),
        JUMP("jump"),
        SNEAK("sneak"),
        SPRINT("sprint"),
        LOOK("look"),
        ATTACK("attack"),
        USE("use"),
        HOTBAR("hotbar"),
        DROP("drop"),
        SWAP_HANDS("swap_hands"),
        PICK_BLOCK("pick_block"),
        PAUSE_MENU("pause_menu");

        private final String wire;

        HumanInput(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** Why the mode or pause changed, with wire names. */
    public enum Cause {
        CONTROLLER_ATTACHED("controller_attached"),
        HUMAN_INPUT("human_input"),
        HUMAN_IDLE("human_idle"),
        LOCKOUT_ENGAGED("lockout_engaged"),
        LOCKOUT_RELEASED("lockout_released"),
        CONTROLLER_LOST("controller_lost"),
        PANIC("panic"),
        REARMED("rearmed");

        private final String wire;

        Cause(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /** One reportable change; {@code inputs} is nonempty only for HUMAN_INPUT. */
    public record Change(Mode mode, boolean paused, Cause cause, Set<HumanInput> inputs) {
        public Change {
            inputs = Collections.unmodifiableSet(inputs.isEmpty()
                    ? EnumSet.noneOf(HumanInput.class) : EnumSet.copyOf(inputs));
        }

        /** True when the caller must release every agent actuator now. */
        public boolean releasesAgent() {
            return cause == Cause.HUMAN_INPUT;
        }
    }

    /** Outcome of a lockout key press. */
    public enum LockoutResult {
        ENGAGED,
        RELEASED,
        /** Engaging needs gameplay with no screen open; nothing changed. */
        SCREEN_OPEN,
        NO_CONTROLLER,
        PANIC_LATCHED
    }

    /** A lockout key outcome and the change to report, if any. */
    public record Toggle(LockoutResult result, Change change) {}

    private final LongSupplier resumeDelayNanos;
    private Object controller;
    private boolean latched;
    private boolean lockout;
    private boolean paused;
    private long lastHumanNanos;

    /** @param resumeDelayNanos quiet time after the last human input before the agent resumes (read live) */
    public HumanPrecedence(LongSupplier resumeDelayNanos) {
        this.resumeDelayNanos = resumeDelayNanos;
    }

    public Mode mode() {
        return latched ? Mode.PANIC : lockout ? Mode.AGENT_EXCLUSIVE : Mode.HUMAN_PRIORITY;
    }

    /** True while human input has paused agent control (human-priority only). */
    public boolean agentPaused() {
        return paused;
    }

    public boolean lockout() {
        return lockout;
    }

    /**
     * True when local gameplay input must be suppressed: the lockout is
     * engaged for exactly the controller that is attached right now.
     *
     * @param current the bridge's attached, admitted controller, or null
     */
    public boolean suppressLocal(Object current) {
        return lockout && !latched && current != null && current == controller;
    }

    /**
     * Synchronize with the bridge's attached controller. Any change of
     * identity drops the lockout and the pause; a lost lockout is reported as
     * CONTROLLER_LOST, a new controller as CONTROLLER_ATTACHED.
     *
     * @return the change to report, or null
     */
    public Change controller(Object current) {
        if (current == controller) return null;
        boolean hadLockout = lockout;
        controller = current;
        lockout = false;
        paused = false;
        if (current != null) return change(Cause.CONTROLLER_ATTACHED, Set.of());
        return hadLockout ? change(Cause.CONTROLLER_LOST, Set.of()) : null;
    }

    /** Forget the controller and every mode except the panic latch, silently (world join). */
    public void forgetController() {
        controller = null;
        lockout = false;
        paused = false;
    }

    /** Local panic; repeated panics report nothing new. */
    public Change panic() {
        boolean changed = !latched || lockout || paused;
        latched = true;
        lockout = false;
        paused = false;
        return changed ? change(Cause.PANIC, Set.of()) : null;
    }

    /** The re-arm key cleared the latch. */
    public Change rearm() {
        if (!latched) return null;
        latched = false;
        return change(Cause.REARMED, Set.of());
    }

    /**
     * The lockout key was pressed. It releases an engaged lockout anywhere;
     * it engages only in gameplay with no screen open, while a controller is
     * attached and panic is not latched.
     */
    public Toggle toggleLockout(boolean screenOpen) {
        if (lockout) {
            lockout = false;
            return new Toggle(LockoutResult.RELEASED, change(Cause.LOCKOUT_RELEASED, Set.of()));
        }
        if (screenOpen) return new Toggle(LockoutResult.SCREEN_OPEN, null);
        if (latched) return new Toggle(LockoutResult.PANIC_LATCHED, null);
        if (controller == null) return new Toggle(LockoutResult.NO_CONTROLLER, null);
        lockout = true;
        paused = false;
        return new Toggle(LockoutResult.ENGAGED, change(Cause.LOCKOUT_ENGAGED, Set.of()));
    }

    /**
     * Human gameplay input happened now. In human-priority mode with a
     * controller attached it starts (or extends) the pause; otherwise it is
     * ignored. Only the start of a pause is reported.
     */
    public Change human(Set<HumanInput> inputs, long nowNanos) {
        if (inputs.isEmpty() || controller == null || latched || lockout) return null;
        lastHumanNanos = nowNanos;
        if (paused) return null;
        paused = true;
        return change(Cause.HUMAN_INPUT, inputs);
    }

    /**
     * Periodic check with the human inputs held right now (keys and buttons
     * down, the pause menu open). Held input keeps the pause; the agent
     * resumes once nothing has been held or pressed for the resume delay.
     */
    public Change tick(Set<HumanInput> held, long nowNanos) {
        if (!held.isEmpty()) return human(held, nowNanos);
        if (paused && nowNanos - lastHumanNanos >= resumeDelayNanos.getAsLong()) {
            paused = false;
            return change(Cause.HUMAN_IDLE, Set.of());
        }
        return null;
    }

    private Change change(Cause cause, Set<HumanInput> inputs) {
        return new Change(mode(), paused, cause, inputs);
    }
}
