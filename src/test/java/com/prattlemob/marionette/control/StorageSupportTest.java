package com.prattlemob.marionette.control;

import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageSupportTest {
    // Stand-ins for AbstractContainerMenu and Slot with the same method names and arities.
    static class Menu {
        public void clicked(int slot, int button, Object type, Object player) {}
        public Object quickMoveStack(Object player, int slot) { return null; }
        public boolean stillValid(Object player) { return true; }
        public void removed(Object player) {}
        public void slotsChanged(Object container) {}
        public boolean clickMenuButton(Object player, int id) { return false; }
        private void doClick() {}
        public static void helper() {}
    }
    static class Slot {
        public boolean mayPlace(Object stack) { return true; }
        public boolean mayPickup(Object player) { return true; }
        public int getMaxStackSize() { return 99; }
        public int getMaxStackSize(Object stack) { return 99; }
        public boolean isActive() { return true; }
        public void onTake(Object player, Object stack) {}
        public Object remove(int amount) { return null; }
        public void setByPlayer(Object stack, Object old) {}
        public void set(Object stack) {}
    }

    static class StorageMenu extends Menu {
        @Override public Object quickMoveStack(Object player, int slot) { return null; }
        @Override public boolean stillValid(Object player) { return false; }
        @Override public void removed(Object player) {}
        public int getRowCount() { return 3; }          // the menu's own API is not an override
        private void doClick() {}                        // cannot override a private method
    }
    static class StorageMenuChild extends StorageMenu {}
    static class ClickingMenu extends StorageMenu {
        @Override public void clicked(int slot, int button, Object type, Object player) {}
    }
    static class ButtonMenu extends Menu {
        @Override public boolean clickMenuButton(Object player, int id) { return true; }
        @Override public void slotsChanged(Object container) {}
    }
    static class RestrictedSlot extends Slot {
        @Override public boolean mayPlace(Object stack) { return false; }
        @Override public int getMaxStackSize() { return 1; }
        public boolean mayPlace(String overloadNotOverride) { return false; }
    }
    static class ResultSlot extends Slot {
        @Override public void onTake(Object player, Object stack) {}
        @Override public Object remove(int amount) { return null; }
    }
    static class EquipmentSlot extends Slot {
        @Override public void setByPlayer(Object stack, Object old) {}
    }
    static class SettingSlot extends Slot {
        @Override public void set(Object stack) {}
    }

    private static final StorageSupport SUPPORT = new StorageSupport(Menu.class, Slot.class);

    private record View(Class<?> menuClass, int dataSlots, List<Class<?>> slots, IntFunction<Boolean> player,
                        IntFunction<Object> containers, IntFunction<Integer> positions) implements StorageSupport.MenuView {
        View(Class<?> menuClass, int dataSlots, List<Class<?>> slots) {
            this(menuClass, dataSlots, slots, i -> false, i -> "chest", i -> i);
        }
        public int slotCount() { return slots.size(); }
        public Class<?> slotClass(int slot) { return slots.get(slot); }
        public boolean playerSlot(int slot) { return player.apply(slot); }
        public Object container(int slot) { return containers.apply(slot); }
        public int containerSlot(int slot) { return positions.apply(slot); }
    }

    @Test void plainAndRestrictedStorageQualifies() {
        assertEquals(List.of(), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(Slot.class, RestrictedSlot.class))));
        assertEquals(List.of(), SUPPORT.reasons(new View(StorageMenuChild.class, 0, List.of(Slot.class))));
        assertEquals(List.of(), SUPPORT.reasons(new View(Menu.class, 0, List.of())));
    }

    @Test void clickOrButtonBehaviorFailsAnywhereInTheHierarchy() {
        assertEquals(List.of("click_behavior"), SUPPORT.reasons(new View(ClickingMenu.class, 0, List.of(Slot.class))));
        assertEquals(List.of("click_behavior"), SUPPORT.reasons(new View(ButtonMenu.class, 0, List.of(Slot.class))));
        assertEquals(java.util.Set.of("clickMenuButton/2", "slotsChanged/1"), SUPPORT.overrides(ButtonMenu.class, Menu.class));
    }

    @Test void processingMenusAreReportedWithEveryFailedRule() {
        assertEquals(List.of("menu_data", "slot_behavior"),
                SUPPORT.reasons(new View(StorageMenu.class, 4, List.of(Slot.class, ResultSlot.class))));
        assertEquals(List.of("slot_behavior"), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(SettingSlot.class))));
    }

    @Test void equipmentHookIsAllowedOnlyForPlayerSlots() {
        assertEquals(List.of(), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(EquipmentSlot.class),
                i -> true, i -> "player", i -> 39)));
        assertEquals(List.of("slot_behavior"), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(EquipmentSlot.class))));
    }

    @Test void twoSlotsAtOneContainerPositionAreShared() {
        Object chest = new Object(), player = new Object();
        assertEquals(List.of("shared_slots"), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(Slot.class, Slot.class),
                i -> false, i -> chest, i -> 0)));
        assertEquals(List.of(), SUPPORT.reasons(new View(StorageMenu.class, 0, List.of(Slot.class, Slot.class),
                i -> i == 1, i -> i == 0 ? chest : player, i -> 0)));
    }

    // Stand-ins for a vanilla workstation base, its subclasses and its slot kinds.
    static class Workbench extends Menu {
        @Override public void slotsChanged(Object container) {}
        @Override public void removed(Object player) {}
        public Object getResultSlot() { return null; }
    }
    static class TableBench extends Workbench {}
    static class ResortingBench extends Workbench {
        @Override public void slotsChanged(Object container) {}
    }
    static class RelocatingBench extends Workbench {
        @Override public Object getResultSlot() { return null; }
    }
    static class ValidatingBench extends Workbench {
        @Override public boolean stillValid(Object player) { return true; }
    }
    static class FuelSlot extends Slot {
        @Override public boolean mayPlace(Object stack) { return false; }
    }
    static class StricterFuelSlot extends FuelSlot {
        @Override public boolean mayPlace(Object stack) { return true; }
    }
    static class TakingFuelSlot extends FuelSlot {
        @Override public void onTake(Object player, Object stack) {}
    }

    private static final StorageSupport.Workstation BENCH = new StorageSupport.Workstation(Workbench.class, 4,
            java.util.Map.of(0, Slot.class, 1, FuelSlot.class, 2, ResultSlot.class));

    private static View bench(Class<?> menu, int data, Class<?> fuel, Class<?> result) {
        return new View(menu, data, List.of(Slot.class, fuel, result, Slot.class), i -> i == 3,
                i -> i == 3 ? "player" : "bench", i -> i);
    }

    @Test void workstationBaseAndPlainSubclassesQualify() {
        assertEquals(List.of(), SUPPORT.workstationReasons(bench(Workbench.class, 4, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of(), SUPPORT.workstationReasons(bench(TableBench.class, 4, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of(), SUPPORT.workstationReasons(bench(ValidatingBench.class, 4, StricterFuelSlot.class, ResultSlot.class), BENCH));
    }

    @Test void workstationSubclassesOverridingBaseOrMenuBehaviorFail() {
        assertEquals(List.of("click_behavior"), SUPPORT.workstationReasons(bench(ResortingBench.class, 4, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of("click_behavior"), SUPPORT.workstationReasons(bench(RelocatingBench.class, 4, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of("click_behavior"), SUPPORT.workstationReasons(bench(StorageMenu.class, 4, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(java.util.Set.of("slotsChanged/1"), SUPPORT.overrides(ResortingBench.class, Workbench.class, Menu.class));
        // Methods inherited from above the base count only when the lookup reaches the menu root.
        assertEquals(java.util.Set.of(), SUPPORT.overrides(ValidatingBench.class, Workbench.class));
        assertEquals(java.util.Set.of("stillValid/1"), SUPPORT.overrides(ValidatingBench.class, Workbench.class, Menu.class));
    }

    @Test void workstationRolesNeedTheirVanillaSlotKindAndData() {
        assertEquals(List.of("menu_data"), SUPPORT.workstationReasons(bench(Workbench.class, 5, FuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of("slot_behavior"), SUPPORT.workstationReasons(bench(Workbench.class, 4, Slot.class, ResultSlot.class), BENCH));
        assertEquals(List.of("slot_behavior"), SUPPORT.workstationReasons(bench(Workbench.class, 4, TakingFuelSlot.class, ResultSlot.class), BENCH));
        assertEquals(List.of("slot_behavior"), SUPPORT.workstationReasons(bench(Workbench.class, 4, FuelSlot.class, Slot.class), BENCH));
        assertEquals(List.of("slot_behavior"), SUPPORT.workstationReasons(new View(Workbench.class, 4, List.of(Slot.class)), BENCH));
    }

    @Test void unrelatedClassesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SUPPORT.overrides(String.class, Menu.class));
    }
}
