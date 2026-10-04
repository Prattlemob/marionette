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

    @Test void countedMovesPlaceExactlyThatManyItems() {
        assertDoesNotThrow(() -> InventoryRules.moveCount(stack(16), stack(0), 1, false));
        assertDoesNotThrow(() -> InventoryRules.moveCount(stack(16), stack(60), 4, false));
        assertDoesNotThrow(() -> InventoryRules.moveCount(stack(16), stack(0), 16, true));   // whole stack
        assertEquals("count_exceeds_source", reason(() -> InventoryRules.moveCount(stack(3), stack(0), 4, false)));
        assertEquals("destination_full", reason(() -> InventoryRules.moveCount(stack(16), stack(61), 4, false)));
        assertEquals("destination_mismatch", reason(() -> InventoryRules.moveCount(stack(16), new Slot(1, "stone", true, true, 64), 1, false)));
        assertEquals("destination_rejects", reason(() -> InventoryRules.moveCount(stack(16), new Slot(0, "dirt", true, false, 64), 1, false)));
        assertEquals("source_empty", reason(() -> InventoryRules.moveCount(stack(0), stack(0), 1, false)));
    }

    @Test void partialTakesNeedASourceThatGivesUpAndTakesBackPartStacks() {
        assertEquals("whole_stack_only", reason(() -> InventoryRules.moveCount(stack(5), stack(0), 2, true)));
        assertEquals("source_rejects", reason(() -> InventoryRules.moveCount(new Slot(5, "dirt", true, false, 64), stack(0), 2, false)));
    }

    @Test void craftNeedsAResultAndEnoughOfEveryIngredient() {
        assertDoesNotThrow(() -> InventoryRules.craft(4, new int[] {1}, new boolean[] {false}, 1));
        assertDoesNotThrow(() -> InventoryRules.craft(1, new int[] {3, 2, 5}, new boolean[3], 2));
        assertEquals("no_result", reason(() -> InventoryRules.craft(0, new int[] {1}, new boolean[1], 1)));
        assertEquals("missing_ingredients", reason(() -> InventoryRules.craft(1, new int[] {3, 1}, new boolean[2], 2)));
        assertDoesNotThrow(() -> InventoryRules.craft(1, new int[] {1, 4}, new boolean[] {true, false}, 1));
        assertEquals("remainder_unsupported", reason(() -> InventoryRules.craft(1, new int[] {2}, new boolean[] {true}, 1)));
    }

    @Test void receiveChecksTheWholeCraftedAmount() {
        assertDoesNotThrow(() -> InventoryRules.receive(stack(56), stack(4), 8));
        assertEquals("destination_full", reason(() -> InventoryRules.receive(stack(57), stack(4), 8)));
    }

    @Test void swapChecksBothDirectionsToAvoidVanillaOverflowDrops() {
        assertDoesNotThrow(() -> InventoryRules.swap(stack(16), new Slot(1, "stone", true, true, 64)));
        assertDoesNotThrow(() -> InventoryRules.swap(new Slot(1, "helmet", true, false, 1), stack(0)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(new Slot(1, "helmet", true, true, 1), stack(16)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(new Slot(1, "helmet", true, false, 1), stack(1)));
        assertThrows(IllegalArgumentException.class, () -> InventoryRules.swap(stack(1), new Slot(1, "stone", false, true, 64)));
    }
}
