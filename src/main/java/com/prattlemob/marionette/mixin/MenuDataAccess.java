package com.prattlemob.marionette.mixin;

import java.util.List;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of a menu's synchronized data values, for the storage analysis. */
@Mixin(AbstractContainerMenu.class)
public interface MenuDataAccess {
    @Accessor("dataSlots")
    List<DataSlot> marionette$dataSlots();
}
