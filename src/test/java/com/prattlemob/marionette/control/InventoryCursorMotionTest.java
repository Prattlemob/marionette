package com.prattlemob.marionette.control;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryCursorMotionTest {
    @Test void holdsStartThroughClickPauseAndArrivesBeforeNextClick() {
        var leg = new InventoryCursorMotion(10, 20, 90, 60, 120_000_000L);
        assertEquals(10, leg.x(0));
        assertEquals(20, leg.y(120_000_000L));
        assertFalse(leg.ready(120_000_000L));
        assertEquals(90, leg.x(500_000_000L));
        assertEquals(60, leg.y(500_000_000L));
        assertTrue(leg.ready(500_000_000L));
    }

    @Test void progressesMonotonicallyWithoutOvershootAtAnyFrameRate() {
        var leg = new InventoryCursorMotion(200, 100, 0, 300, 0);
        double previousX = 200, previousY = 100;
        for (long now = 0; now <= 1_000_000_000L; now += 7_000_000L) {
            double x = leg.x(now), y = leg.y(now);
            assertTrue(x <= previousX && x >= 0);
            assertTrue(y >= previousY && y <= 300);
            previousX = x;
            previousY = y;
        }
    }

    @Test void capsTravelAndDwellsBeforeClickEvenAtZeroDistance() {
        var far = new InventoryCursorMotion(0, 0, 10000, 0, 0);
        assertEquals(10000, far.x(450_000_000L));
        assertFalse(far.ready(549_000_000L));
        assertTrue(far.ready(550_000_000L));
        var near = new InventoryCursorMotion(0, 0, 0, 0, 0);
        assertFalse(near.ready(299_000_000L));
        assertTrue(near.ready(300_000_000L));
    }
}
