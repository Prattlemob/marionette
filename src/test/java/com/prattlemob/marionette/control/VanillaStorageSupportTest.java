package com.prattlemob.marionette.control;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The storage analysis against the real 1.21.8 classes (loaded, never initialized). */
class VanillaStorageSupportTest {
    private static final String PACKAGE = "net.minecraft.world.inventory.";
    private final StorageSupport support = new StorageSupport(load("AbstractContainerMenu"), load("Slot"));

    private static Class<?> load(String name) {
        try {
            return Class.forName(PACKAGE + name, false, VanillaStorageSupportTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private boolean menuQualifies(String name) {
        return StorageSupport.MENU_OVERRIDES.containsAll(support.overrides(load(name), load("AbstractContainerMenu")));
    }

    private boolean slotQualifies(String name, boolean player) {
        return (player ? StorageSupport.PLAYER_SLOT_OVERRIDES : StorageSupport.SLOT_OVERRIDES)
                .containsAll(support.overrides(load(name), load("Slot")));
    }

    @Test void previouslyListedVanillaStorageMenusStillQualify() {
        for (String menu : new String[] {"ChestMenu", "HopperMenu", "DispenserMenu", "ShulkerBoxMenu"}) {
            assertTrue(menuQualifies(menu), menu);
        }
        assertTrue(slotQualifies("Slot", false));
        assertTrue(slotQualifies("ShulkerBoxSlot", false));
    }

    @Test void workstationMenusAndResultSlotsDoNot() {
        for (String menu : new String[] {"CraftingMenu", "AnvilMenu", "EnchantmentMenu", "LoomMenu",
                "StonecutterMenu", "MerchantMenu", "LecternMenu", "BeaconMenu"}) {
            assertFalse(menuQualifies(menu), menu);
        }
        // Furnaces, brewing stands and crafters keep vanilla click handling; their slots
        // (and synchronized data, checked on the live menu) exclude them.
        for (String slot : new String[] {"ResultSlot", "FurnaceResultSlot", "MerchantResultSlot",
                "BrewingStandMenu$PotionSlot", "CrafterSlot", "NonInteractiveResultSlot"}) {
            assertFalse(slotQualifies(slot, true), slot);
        }
    }

    @Test void vanillaWorkstationsQualifyAgainstTheirBase() {
        Class<?> menu = load("AbstractContainerMenu");
        assertTrue(support.overrides(load("CraftingMenu"), load("CraftingMenu"), menu).isEmpty());
        for (String furnace : new String[] {"FurnaceMenu", "BlastFurnaceMenu", "SmokerMenu"}) {
            assertEquals(java.util.Set.of(), support.overrides(load(furnace), load("AbstractFurnaceMenu"), menu), furnace);
        }
        // Other workstations are not crafting or furnace bases at all.
        assertFalse(load("AbstractFurnaceMenu").isAssignableFrom(load("BrewingStandMenu")));
        assertFalse(load("CraftingMenu").isAssignableFrom(load("CrafterMenu")));
        assertFalse(load("CraftingMenu").isAssignableFrom(load("InventoryMenu")));
        Class<?> slot = load("Slot");
        assertTrue(StorageSupport.SLOT_OVERRIDES.containsAll(support.overrides(load("FurnaceFuelSlot"), load("FurnaceFuelSlot"), slot)));
        assertFalse(StorageSupport.SLOT_OVERRIDES.containsAll(support.overrides(load("FurnaceResultSlot"), slot)));
    }

    @Test void brewingStandFailsTheStorageAnalysis() {
        assertFalse(slotQualifies("BrewingStandMenu$PotionSlot", false));
        assertTrue(menuQualifies("BrewingStandMenu"));   // menu_data and slot_behavior exclude it on the live menu
    }

    @Test void armorSlotsQualifyOnlyAsPlayerSlots() {
        assertTrue(slotQualifies("ArmorSlot", true));
        assertFalse(slotQualifies("ArmorSlot", false));
    }
}
