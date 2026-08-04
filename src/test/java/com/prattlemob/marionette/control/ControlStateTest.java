package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ControlStateTest {
    @Test
    void controlsAreNeutralInitially() {
        ControlState state = new ControlState();
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }

    @Test
    void heldControlsPersistUntilChanged() {
        ControlState state = new ControlState();
        state.setForward(true);
        state.setSprint(true);
        // unrelated updates must not disturb held controls (set-and-hold)
        state.setJump(true);
        state.setJump(false);
        assertTrue(state.forward());
        assertTrue(state.sprint());
        assertFalse(state.jump());
        assertTrue(state.anyHeld());
    }

    @Test
    void releaseAllReturnsEverythingToNeutral() {
        ControlState state = new ControlState();
        state.setForward(true);
        state.setBack(true);
        state.setLeft(true);
        state.setRight(true);
        state.setJump(true);
        state.setSneak(true);
        state.setSprint(true);
        state.setLook(90.0F, -10.0F);
        state.releaseAll();
        assertFalse(state.forward());
        assertFalse(state.back());
        assertFalse(state.left());
        assertFalse(state.right());
        assertFalse(state.jump());
        assertFalse(state.sneak());
        assertFalse(state.sprint());
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }

    @Test
    void lookIsConsumedExactlyOnce() {
        ControlState state = new ControlState();
        state.setLook(45.0F, 10.0F);
        ControlState.Look look = state.consumeLook();
        assertEquals(45.0F, look.yaw());
        assertEquals(10.0F, look.pitch());
        assertNull(state.consumeLook());
    }

    @Test
    void newestLookWins() {
        ControlState state = new ControlState();
        state.setLook(10.0F, 0.0F);
        state.setLook(20.0F, 5.0F);
        assertEquals(new ControlState.Look(20.0F, 5.0F), state.consumeLook());
    }

    @Test
    void tapsAreConsumedExactlyOnce() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        assertFalse(state.anyHeld()); // a pending tap is not a held control
        assertEquals(Set.of(TapControl.JUMP), state.consumeTaps());
        assertEquals(Set.of(), state.consumeTaps());
    }

    @Test
    void duplicateTapsCollapse() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        state.tap(TapControl.JUMP);
        assertEquals(Set.of(TapControl.JUMP), state.consumeTaps());
    }

    @Test
    void tapDoesNotDisturbHeldControls() {
        ControlState state = new ControlState();
        state.setJump(true);
        state.tap(TapControl.JUMP);
        assertEquals(Set.of(TapControl.JUMP), state.consumeTaps());
        assertTrue(state.jump()); // tap never releases a held control
    }

    @Test
    void releaseAllClearsPendingTaps() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        state.releaseAll();
        assertEquals(Set.of(), state.consumeTaps());
    }

    @Test
    void lookDeltasAccumulateUntilConsumed() {
        ControlState state = new ControlState();
        assertNull(state.consumeLookDelta());
        state.addLookDelta(10.0f, -5.0f);
        state.addLookDelta(2.5f, 1.0f);
        ControlState.LookDelta delta = state.consumeLookDelta();
        assertEquals(12.5f, delta.yaw());
        assertEquals(-4.0f, delta.pitch());
        assertNull(state.consumeLookDelta()); // consumed
    }

    @Test
    void zeroLookDeltaIsStillQueued() {
        ControlState state = new ControlState();
        state.addLookDelta(0.0f, 0.0f);
        assertNotNull(state.consumeLookDelta());
    }

    @Test
    void releaseAllClearsPendingLookDelta() {
        ControlState state = new ControlState();
        state.addLookDelta(10.0f, 0.0f);
        state.releaseAll();
        assertNull(state.consumeLookDelta());
    }
}
