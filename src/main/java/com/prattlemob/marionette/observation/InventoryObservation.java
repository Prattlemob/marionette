package com.prattlemob.marionette.observation;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prattlemob.marionette.control.InventoryActionApplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;

/** Client-thread snapshot of the {@code inventory} observation section. */
public final class InventoryObservation {
    private InventoryObservation() {}

    public static JsonObject capture(LocalPlayer player, InventoryActionApplier actions) {
        Inventory inventory = player.getInventory();
        JsonObject result = new JsonObject();
        result.addProperty("selected", inventory.getSelectedSlot());
        result.add("mainHand", ItemObservation.stack(inventory.getSelectedItem()));
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) hotbar.add(ItemObservation.stack(inventory.getItem(i)));
        result.add("hotbar", hotbar);
        JsonArray main = new JsonArray();
        for (int i = Inventory.SELECTION_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
            main.add(ItemObservation.stack(inventory.getItem(i)));
        }
        result.add("main", main);
        JsonObject armor = new JsonObject();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            armor.add(slot.getName(), ItemObservation.stack(player.getItemBySlot(slot)));
        }
        result.add("armor", armor);
        result.add("offhand", ItemObservation.stack(player.getItemBySlot(EquipmentSlot.OFFHAND)));
        JsonObject menu = actions.describeVisibleUnbounded(player);
        result.add("menu", menu == null ? JsonNull.INSTANCE : menu);
        return InventoryJson.boundSection(result);
    }
}
