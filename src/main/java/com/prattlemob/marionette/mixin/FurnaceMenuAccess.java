package com.prattlemob.marionette.mixin;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read-only: whether the client's recipe data lets this furnace type cook a stack (processing descriptor). */
@Mixin(AbstractFurnaceMenu.class)
public interface FurnaceMenuAccess {
    @Invoker("canSmelt")
    boolean marionette$canSmelt(ItemStack stack);
}
