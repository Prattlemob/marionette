package com.prattlemob.marionette.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Restore vanilla's cached hover before a human key/scroll action can use it. */
@Mixin(AbstractContainerScreen.class)
public interface InventoryHoverAccess {
    @Accessor("hoveredSlot")
    void marionette$setHoveredSlot(Slot slot);

    @Invoker("getHoveredSlot")
    Slot marionette$findHoveredSlot(double x, double y);
}
