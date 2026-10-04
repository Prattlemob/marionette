package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.prattlemob.marionette.control.HumanPrecedence.Cause;
import com.prattlemob.marionette.control.HumanPrecedence.Change;
import com.prattlemob.marionette.control.HumanPrecedence.HumanInput;
import com.prattlemob.marionette.control.HumanPrecedence.LockoutResult;
import com.prattlemob.marionette.control.HumanPrecedence.Mode;

class HumanPrecedenceTest {
    private static final long MS = 1_000_000L;
    private long delay = 2000 * MS;
    private final HumanPrecedence precedence = new HumanPrecedence(() -> delay);
    private final Object agent = new Object();

    private HumanPrecedence attached() {
        Change change = precedence.controller(agent);
        assertEquals(new Change(Mode.HUMAN_PRIORITY, false, Cause.CONTROLLER_ATTACHED, Set.of()), change);
        return precedence;
    }

    @Test
    void startsInHumanPriorityWithNothingSuppressed() {
        assertEquals(Mode.HUMAN_PRIORITY, precedence.mode());
        assertFalse(precedence.agentPaused());
        assertFalse(precedence.suppressLocal(null));
        assertNull(precedence.controller(null), "no controller before and after: nothing to report");
    }

    @Test
    void humanInputWithoutAControllerIsIgnored() {
        assertNull(precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0));
        assertFalse(precedence.agentPaused());
    }

    @Test
    void humanInputPausesOnceAndReleasesTheAgent() {
        attached();
        Change change = precedence.human(EnumSet.of(HumanInput.LOOK, HumanInput.MOVEMENT), 0);
        assertEquals(Mode.HUMAN_PRIORITY, change.mode());
        assertTrue(change.paused());
        assertEquals(Cause.HUMAN_INPUT, change.cause());
        assertTrue(change.releasesAgent());
        assertEquals(List.of(HumanInput.MOVEMENT, HumanInput.LOOK), List.copyOf(change.inputs()),
                "inputs are reported in wire order");
        assertTrue(precedence.agentPaused());
        assertNull(precedence.human(EnumSet.of(HumanInput.ATTACK), 10 * MS), "an extension is not reported");
    }

    @Test
    void resumesOnlyAfterTheQuietDelaySinceTheLastInput() {
        attached();
        precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0);
        precedence.human(EnumSet.of(HumanInput.HOTBAR), 1500 * MS);
        assertNull(precedence.tick(EnumSet.noneOf(HumanInput.class), 3000 * MS), "only 1.5 s since the last press");
        Change resumed = precedence.tick(EnumSet.noneOf(HumanInput.class), 3500 * MS);
        assertEquals(new Change(Mode.HUMAN_PRIORITY, false, Cause.HUMAN_IDLE, Set.of()), resumed);
        assertFalse(resumed.releasesAgent());
        assertFalse(precedence.agentPaused());
        assertNull(precedence.tick(EnumSet.noneOf(HumanInput.class), 9000 * MS));
    }

    @Test
    void heldInputKeepsThePauseAndStartsItWhenFirstSeen() {
        attached();
        Change change = precedence.tick(EnumSet.of(HumanInput.PAUSE_MENU), 0);
        assertEquals(Cause.HUMAN_INPUT, change.cause());
        assertEquals(Set.of(HumanInput.PAUSE_MENU), change.inputs());
        assertNull(precedence.tick(EnumSet.of(HumanInput.PAUSE_MENU), 60_000 * MS), "held for a minute");
        assertNull(precedence.tick(EnumSet.noneOf(HumanInput.class), 61_000 * MS));
        assertEquals(Cause.HUMAN_IDLE, precedence.tick(EnumSet.noneOf(HumanInput.class), 62_000 * MS).cause());
    }

    @Test
    void theResumeDelayIsReadLive() {
        attached();
        precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0);
        delay = 250 * MS;
        assertEquals(Cause.HUMAN_IDLE, precedence.tick(EnumSet.noneOf(HumanInput.class), 300 * MS).cause());
    }

    @Test
    void lockoutNeedsAControllerAndGameplay() {
        assertEquals(LockoutResult.NO_CONTROLLER, precedence.toggleLockout(false).result());
        attached();
        var screen = precedence.toggleLockout(true);
        assertEquals(LockoutResult.SCREEN_OPEN, screen.result());
        assertNull(screen.change());
        assertFalse(precedence.lockout());
        var engaged = precedence.toggleLockout(false);
        assertEquals(LockoutResult.ENGAGED, engaged.result());
        assertEquals(new Change(Mode.AGENT_EXCLUSIVE, false, Cause.LOCKOUT_ENGAGED, Set.of()), engaged.change());
        assertTrue(precedence.suppressLocal(agent));
    }

    @Test
    void lockoutReleasesFromAnywhereIncludingScreens() {
        attached();
        precedence.toggleLockout(false);
        var released = precedence.toggleLockout(true);
        assertEquals(LockoutResult.RELEASED, released.result());
        assertEquals(new Change(Mode.HUMAN_PRIORITY, false, Cause.LOCKOUT_RELEASED, Set.of()), released.change());
        assertFalse(precedence.suppressLocal(agent));
    }

    @Test
    void lockoutIgnoresHumanInputAndClearsAPause() {
        attached();
        precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0);
        precedence.toggleLockout(false);
        assertFalse(precedence.agentPaused(), "agent-exclusive is never paused");
        assertNull(precedence.human(EnumSet.of(HumanInput.MOVEMENT, HumanInput.LOOK), 10 * MS));
        assertNull(precedence.tick(EnumSet.of(HumanInput.ATTACK), 20 * MS));
        assertFalse(precedence.agentPaused());
    }

    @Test
    void lockoutDropsTheMomentNoControllerIsAttached() {
        attached();
        precedence.toggleLockout(false);
        assertFalse(precedence.suppressLocal(null), "suppression re-checks the live controller");
        assertFalse(precedence.suppressLocal(new Object()), "and its identity");
        Change lost = precedence.controller(null);
        assertEquals(new Change(Mode.HUMAN_PRIORITY, false, Cause.CONTROLLER_LOST, Set.of()), lost);
        assertFalse(precedence.lockout());
    }

    @Test
    void aDifferentControllerNeverInheritsTheLockoutOrPause() {
        attached();
        precedence.toggleLockout(false);
        Object next = new Object();
        Change change = precedence.controller(next);
        assertEquals(Cause.CONTROLLER_ATTACHED, change.cause());
        assertEquals(Mode.HUMAN_PRIORITY, change.mode());
        assertFalse(precedence.suppressLocal(next));
        precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0);
        precedence.controller(new Object());
        assertFalse(precedence.agentPaused());
    }

    @Test
    void panicLatchesDropsLockoutAndRepeatsSilently() {
        attached();
        precedence.toggleLockout(false);
        Change panic = precedence.panic();
        assertEquals(new Change(Mode.PANIC, false, Cause.PANIC, Set.of()), panic);
        assertFalse(precedence.suppressLocal(agent));
        assertNull(precedence.panic());
        assertEquals(LockoutResult.PANIC_LATCHED, precedence.toggleLockout(false).result());
        assertNull(precedence.human(EnumSet.of(HumanInput.MOVEMENT), 0));
        assertNull(precedence.controller(null), "the severed controller had no lockout left");
        assertEquals(new Change(Mode.HUMAN_PRIORITY, false, Cause.REARMED, Set.of()), precedence.rearm());
        assertNull(precedence.rearm());
    }

    @Test
    void panicWithoutAControllerStillReportsTheMode() {
        assertEquals(Mode.PANIC, precedence.panic().mode());
        assertEquals(Mode.PANIC, precedence.mode());
    }

    @Test
    void forgettingTheControllerReportsItAgainOnTheNextSync() {
        attached();
        precedence.toggleLockout(false);
        precedence.forgetController();
        assertFalse(precedence.lockout());
        assertEquals(Cause.CONTROLLER_ATTACHED, precedence.controller(agent).cause());
    }

    @Test
    void emptyInputIsNotHumanInput() {
        attached();
        assertNull(precedence.human(EnumSet.noneOf(HumanInput.class), 0));
        assertFalse(precedence.agentPaused());
    }

    @Test
    void wireNamesMatchTheProtocol() {
        assertEquals(List.of("movement", "jump", "sneak", "sprint", "look", "attack", "use", "hotbar", "drop",
                "swap_hands", "pick_block", "pause_menu"),
                EnumSet.allOf(HumanInput.class).stream().map(HumanInput::wire).toList());
        assertEquals(List.of("human_priority", "agent_exclusive", "panic"),
                EnumSet.allOf(Mode.class).stream().map(Mode::wire).toList());
        assertEquals(List.of("controller_attached", "human_input", "human_idle", "lockout_engaged",
                "lockout_released", "controller_lost", "panic", "rearmed"),
                EnumSet.allOf(Cause.class).stream().map(Cause::wire).toList());
    }
}
