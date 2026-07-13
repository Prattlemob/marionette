package com.prattlemob.marionette.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemoScriptTest {
    private static final float YAW = 30.0F;

    /** Run n ticks, collecting any non-NONE stunts. */
    private static java.util.List<DemoScript.Stunt> run(DemoScript demo, ControlState state, int ticks) {
        java.util.List<DemoScript.Stunt> stunts = new java.util.ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            DemoScript.Stunt stunt = demo.tick(YAW, state);
            if (stunt != DemoScript.Stunt.NONE) {
                stunts.add(stunt);
            }
        }
        return stunts;
    }

    @Test
    void waitsBeforeMoving() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 99);
        assertFalse(state.anyHeld());
        demo.tick(YAW, state);
        assertTrue(state.forward());
        assertTrue(state.sprint());
    }

    @Test
    void turnsNinetyDegreesBetweenLegs() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 100 + 60); // WAIT + WALK_1 complete
        assertFalse(state.forward());
        assertFalse(state.sprint());
        assertEquals(new ControlState.Look(YAW + 90.0F, 0.0F), state.consumeLook());
        demo.tick(YAW, state); // TURN tick starts leg two
        assertTrue(state.forward());
        assertTrue(state.sneak());
    }

    @Test
    void releasesThenCoastsThenFinishes() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 100 + 60 + 1 + 60); // through WALK_2
        assertFalse(state.anyHeld());
        assertFalse(demo.isDone());
        run(demo, state, 40); // COAST
        assertTrue(demo.isDone());
        assertFalse(state.anyHeld());
    }

    @Test
    void guiStuntsFireAtWalkTicks20And40OnlyWhenEnabled() {
        DemoScript demo = new DemoScript(true);
        ControlState state = new ControlState();
        run(demo, state, 100); // WAIT
        var stunts = run(demo, state, 60); // WALK_1
        assertEquals(java.util.List.of(DemoScript.Stunt.OPEN_INVENTORY, DemoScript.Stunt.CLOSE_SCREEN), stunts);

        DemoScript quiet = new DemoScript(false);
        ControlState quietState = new ControlState();
        assertEquals(0, run(quiet, quietState, 300).size());
    }

    @Test
    void staysDoneAndInertAfterFinishing() {
        DemoScript demo = new DemoScript(false);
        ControlState state = new ControlState();
        run(demo, state, 400);
        assertTrue(demo.isDone());
        assertEquals(DemoScript.Stunt.NONE, demo.tick(YAW, state));
        assertFalse(state.anyHeld());
        assertNull(state.consumeLook());
    }
}
