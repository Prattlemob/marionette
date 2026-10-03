package com.prattlemob.marionette.mixin;

import com.prattlemob.marionette.MarionetteClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Substitute only the animated screen's render pointer; real input remains vanilla. */
@Mixin(Screen.class)
public abstract class InventoryCursorMixin {
    @ModifyVariable(method = "renderWithTooltip", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int marionette$cursorX(int mouseX) {
        MarionetteClient client = MarionetteClient.instance();
        return client == null ? mouseX : client.inventoryActions().cursorX((Screen) (Object) this, mouseX);
    }

    @ModifyVariable(method = "renderWithTooltip", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int marionette$cursorY(int mouseY) {
        MarionetteClient client = MarionetteClient.instance();
        return client == null ? mouseY : client.inventoryActions().cursorY((Screen) (Object) this, mouseY);
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void marionette$drawCursor(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        MarionetteClient client = MarionetteClient.instance();
        if (client != null) client.inventoryActions().drawCursor((Screen) (Object) this, graphics);
    }
}
