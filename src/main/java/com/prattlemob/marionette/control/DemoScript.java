package com.prattlemob.marionette.control;

/**
 * Temporary hardcoded M1.1 demo: after a settle delay, sprint-walk forward,
 * turn 90° right, sneak-walk, then release and coast (any drift while
 * coasting means stuck controls). Pure logic — no Minecraft imports — so the
 * phase machine is unit-testable; the wiring executes returned {@link Stunt}s.
 * Armed only when {@code -Dmarionette.demo=true}; slated for removal or
 * permanent disablement after M1.3.
 */
public final class DemoScript {
    /** Side effects the wiring must perform (GUI stunts for the D4 criteria). */
    public enum Stunt { NONE, OPEN_INVENTORY, CLOSE_SCREEN }

    private static final long WAIT_TICKS = 100;
    private static final long WALK_TICKS = 60;
    private static final long COAST_TICKS = 40;
    private static final long OPEN_INVENTORY_AT = 20;
    private static final long CLOSE_SCREEN_AT = 40;

    private enum Phase { WAIT, WALK_1, TURN, WALK_2, COAST, DONE }

    private final boolean guiStunts;
    private Phase phase = Phase.WAIT;
    private long phaseTicks;

    public DemoScript(boolean guiStunts) {
        this.guiStunts = guiStunts;
    }

    public boolean isDone() {
        return phase == Phase.DONE;
    }

    /**
     * Advance one client tick, mutating {@code state} in place.
     * {@code currentYaw} is the player's yaw this tick.
     */
    public Stunt tick(float currentYaw, ControlState state) {
        phaseTicks++;
        switch (phase) {
            case WAIT:
                if (phaseTicks >= WAIT_TICKS) {
                    state.setForward(true);
                    state.setSprint(true);
                    enter(Phase.WALK_1);
                }
                return Stunt.NONE;
            case WALK_1:
                if (guiStunts && phaseTicks == OPEN_INVENTORY_AT) {
                    return Stunt.OPEN_INVENTORY;
                }
                if (guiStunts && phaseTicks == CLOSE_SCREEN_AT) {
                    return Stunt.CLOSE_SCREEN;
                }
                if (phaseTicks >= WALK_TICKS) {
                    state.setForward(false);
                    state.setSprint(false);
                    state.setLook(currentYaw + 90.0F, 0.0F);
                    enter(Phase.TURN);
                }
                return Stunt.NONE;
            case TURN:
                state.setForward(true);
                state.setSneak(true);
                enter(Phase.WALK_2);
                return Stunt.NONE;
            case WALK_2:
                if (phaseTicks >= WALK_TICKS) {
                    state.releaseAll();
                    enter(Phase.COAST);
                }
                return Stunt.NONE;
            case COAST:
                if (phaseTicks >= COAST_TICKS) {
                    enter(Phase.DONE);
                }
                return Stunt.NONE;
            default:
                return Stunt.NONE;
        }
    }

    private void enter(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}
