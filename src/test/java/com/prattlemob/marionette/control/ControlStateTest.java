package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
