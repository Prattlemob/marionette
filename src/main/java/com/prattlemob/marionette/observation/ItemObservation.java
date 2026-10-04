package com.prattlemob.marionette.observation;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/** Client-thread adapter from vanilla stacks to the enumerated wire extras. */
public final class ItemObservation {
    private ItemObservation() {}

    public static JsonObject stack(ItemStack stack) {
        return InventoryJson.stack(detail(stack));
    }

    static InventoryJson.Stack detail(ItemStack stack) {
        if (stack.isEmpty()) return InventoryJson.Stack.empty();
        var name = stack.get(DataComponents.CUSTOM_NAME);
        var potion = stack.get(DataComponents.POTION_CONTENTS);
        return new InventoryJson.Stack(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.isDamageableItem() ? stack.getDamageValue() : 0,
                stack.isDamageableItem() ? stack.getMaxDamage() : 0,
                name == null ? null : name.getString(),
                enchantments(stack.get(DataComponents.ENCHANTMENTS)),
                enchantments(stack.get(DataComponents.STORED_ENCHANTMENTS)),
                potion == null ? null : potion.potion().map(holder -> holder.getRegisteredName()).orElse(null));
    }

    private static List<InventoryJson.Enchantment> enchantments(ItemEnchantments enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return List.of();
        List<InventoryJson.Enchantment> result = new ArrayList<>();
        for (var entry : enchantments.entrySet()) {
            result.add(new InventoryJson.Enchantment(entry.getKey().getRegisteredName(), entry.getIntValue()));
        }
        return result;
    }
}
