package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void tapIsConsumedExactlyOnce() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        assertFalse(state.anyHeld()); // a pending tap is not a held control
        assertTrue(state.consumeTap(TapControl.JUMP));
        assertFalse(state.consumeTap(TapControl.JUMP));
    }

    @Test
    void duplicateTapsCollapse() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        state.tap(TapControl.JUMP);
        assertTrue(state.consumeTap(TapControl.JUMP));
        assertFalse(state.consumeTap(TapControl.JUMP));
    }

    @Test
    void tapDoesNotDisturbHeldControls() {
        ControlState state = new ControlState();
        state.setJump(true);
        state.tap(TapControl.JUMP);
        assertTrue(state.consumeTap(TapControl.JUMP));
        assertTrue(state.jump()); // tap never releases a held control
    }

    @Test
    void consumingOneTapLeavesOthersPending() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        assertFalse(state.consumeTap(TapControl.ATTACK));
        assertTrue(state.consumeTap(TapControl.JUMP));
    }

    @Test
    void releaseAllClearsPendingTaps() {
        ControlState state = new ControlState();
        state.tap(TapControl.JUMP);
        state.releaseAll();
        assertFalse(state.consumeTap(TapControl.JUMP));
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

    @Test
    void risingEdgeOfHoldQueuesOneClick() {
        ControlState state = new ControlState();
        state.setAttack(true);
        assertTrue(state.attack());
        assertTrue(state.consumeTap(TapControl.ATTACK)); // the press's click
        assertFalse(state.consumeTap(TapControl.ATTACK)); // exactly one
    }

    @Test
    void reassertingAHeldControlClicksNothing() {
        ControlState state = new ControlState();
        state.setAttack(true);
        state.consumeTap(TapControl.ATTACK);
        state.setAttack(true); // no edge
        assertFalse(state.consumeTap(TapControl.ATTACK));
    }

    @Test
    void reholdingAfterReleaseClicksAgain() {
        ControlState state = new ControlState();
        state.setUse(true);
        state.consumeTap(TapControl.USE);
        state.setUse(false);
        state.setUse(true);
        assertTrue(state.consumeTap(TapControl.USE));
    }

    @Test
    void tapOfAHeldInteractionControlIsIgnored() {
        // For jump the held-tap no-op is invisible (OR-merge); for
        // attack/use an extra consumed click would fire a real swing,
        // so the no-op rule is enforced here (see the M3.3 spec).
        ControlState state = new ControlState();
        state.setAttack(true);
        state.consumeTap(TapControl.ATTACK); // drain the edge click
        state.tap(TapControl.ATTACK);
        assertFalse(state.consumeTap(TapControl.ATTACK));
        assertTrue(state.attack()); // and it releases nothing
    }

    @Test
    void quickPressReleaseBetweenTicksStillLandsItsClick() {
        ControlState state = new ControlState();
        state.setUse(true);
        state.setUse(false);
        assertFalse(state.use());
        assertTrue(state.consumeTap(TapControl.USE));
    }

    @Test
    void anyHeldIncludesAttackAndUse() {
        ControlState state = new ControlState();
        state.setAttack(true);
        assertTrue(state.anyHeld());
        state.releaseAll();
        state.setUse(true);
        assertTrue(state.anyHeld());
    }

    @Test
    void releaseAllClearsAttackAndUse() {
        ControlState state = new ControlState();
        state.setAttack(true);
        state.setUse(true);
        state.releaseAll();
        assertFalse(state.attack());
        assertFalse(state.use());
        assertFalse(state.consumeTap(TapControl.ATTACK)); // edge clicks dropped too
        assertFalse(state.consumeTap(TapControl.USE));
    }

    @Test
    void hotbarIntentIsConsumedExactlyOnce() {
        ControlState state = new ControlState();
        assertNull(state.consumeHotbar());
        state.selectHotbar(3);
        assertEquals(Integer.valueOf(3), state.consumeHotbar());
        assertNull(state.consumeHotbar());
    }

    @Test
    void newestHotbarSelectWins() {
        ControlState state = new ControlState();
        state.selectHotbar(1);
        state.selectHotbar(7);
        assertEquals(Integer.valueOf(7), state.consumeHotbar());
    }

    @Test
    void releaseAllClearsHotbarIntent() {
        ControlState state = new ControlState();
        state.selectHotbar(2);
        state.releaseAll();
        assertNull(state.consumeHotbar());
    }

    @Test
    void dropInteractionTapsKeepsJumpTaps() {
        ControlState state = new ControlState();
        state.tap(TapControl.ATTACK);
        state.tap(TapControl.USE);
        state.tap(TapControl.JUMP);
        state.dropInteractionTaps();
        assertFalse(state.consumeTap(TapControl.ATTACK));
        assertFalse(state.consumeTap(TapControl.USE));
        assertTrue(state.consumeTap(TapControl.JUMP));
    }

    @Test
    void releaseInteractionsClearsOnlyInteractionState() {
        ControlState state = new ControlState();
        state.setForward(true);
        state.setJump(true);
        state.tap(TapControl.JUMP);
        state.setAttack(true);
        state.setUse(true);
        state.selectHotbar(4);
        state.releaseInteractions();
        assertFalse(state.attack());
        assertFalse(state.use());
        assertNull(state.consumeHotbar());
        assertFalse(state.consumeTap(TapControl.ATTACK)); // the edge click too
        assertFalse(state.consumeTap(TapControl.USE));
        assertTrue(state.forward()); // movement continues through GUIs (D4)
        assertTrue(state.jump());
        assertTrue(state.consumeTap(TapControl.JUMP));
    }
}
