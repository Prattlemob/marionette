package com.prattlemob.marionette.control;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRulesTest {
    private record Slot(int count, String item, boolean mayPickup, boolean place, int max) implements InventoryRules.Slot {
        public boolean mayPlace(InventoryRules.Slot source) { return place; }
        public int capacity(InventoryRules.Slot source) { return max; }
        public boolean sameItem(InventoryRules.Slot other) { return item.equals(((Slot) other).item); }
    }
    private static Slot stack(int count) { return new Slot(count, "dirt", true, true, 64); }

    @Test void wholeStackMovesToEmptyOrCompatibleSlot() {
        assertDoesNotThrow(() -> InventoryRules.move(stack(16), stack(0)));
        assertDoesNotThrow(() -> InventoryRules.move(stack(16), stack(48)));
    }
    @Test void refusesPartialMovesAndDifferentComponents() {
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(16), stack(49)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(1), new Slot(1, "other-components", true, true, 64)));
    }
    @Test void refusesEmptyOrLockedSource() {
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(0), stack(0)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(new Slot(1, "dirt", false, true, 64), stack(0)));
    }
    @Test void honorsPlacementAndItemSpecificCapacity() {
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(1), new Slot(0, "dirt", true, false, 64)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(2), new Slot(0, "dirt", true, true, 1)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.move(stack(1), new Slot(1, "dirt", false, true, 64)));
    }
    private static String reason(Runnable rule) {
        return assertThrows(InventoryRules.Rejection.class, rule::run).reason();
    }

    @Test void rejectionsCarryTheirWireReason() {
        assertEquals("source_empty", reason(() -> InventoryRules.move(stack(0), stack(0))));
        assertEquals("source_locked", reason(() -> InventoryRules.move(new Slot(1, "dirt", false, true, 64), stack(0))));
        assertEquals("destination_mismatch", reason(() -> InventoryRules.move(stack(1), new Slot(1, "stone", true, true, 64))));
        assertEquals("destination_locked", reason(() -> InventoryRules.move(stack(1), new Slot(1, "dirt", false, true, 64))));
        assertEquals("destination_rejects", reason(() -> InventoryRules.move(stack(1), new Slot(0, "dirt", true, false, 64))));
        assertEquals("destination_full", reason(() -> InventoryRules.move(stack(2), stack(63))));
        assertEquals("destination_full", reason(() -> InventoryRules.swap(stack(2), new Slot(0, "dirt", true, true, 1))));
        assertEquals("destination_rejects", reason(() -> InventoryRules.swap(stack(1), new Slot(1, "stone", true, false, 64))));
        assertEquals("source_rejects", reason(() -> InventoryRules.swap(new Slot(1, "helmet", true, false, 1), stack(1))));
        assertEquals("source_full", reason(() -> InventoryRules.swap(new Slot(1, "helmet", true, true, 1), stack(16))));
        assertEquals("destination_locked", reason(() -> InventoryRules.swap(stack(1), new Slot(1, "stone", false, true, 64))));
    }

    @Test void swapChecksBothDirectionsToAvoidVanillaOverflowDrops() {
        assertDoesNotThrow(() -> InventoryRules.swap(stack(16), new Slot(1, "stone", true, true, 64)));
        assertDoesNotThrow(() -> InventoryRules.swap(new Slot(1, "helmet", true, false, 1), stack(0)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(new Slot(1, "helmet", true, true, 1), stack(16)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(new Slot(1, "helmet", true, false, 1), stack(1)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(stack(1), new Slot(1, "stone", false, true, 64)));
    }
}
