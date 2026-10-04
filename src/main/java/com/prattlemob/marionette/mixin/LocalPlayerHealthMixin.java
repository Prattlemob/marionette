package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.prattlemob.marionette.event.MinecraftEvents;

import net.minecraft.client.player.LocalPlayer;

/**
 * M4.6: server health updates (handleSetHealth's only call). Vanilla treats
 * a new player's first update as a sync, not a hurt; so does the recorder,
 * which uses it as the damage baseline.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerHealthMixin {
    @Shadow private boolean flashOnSetHealth;

    @Inject(method = "hurtTo", at = @At("HEAD"))
    private void marionette$health(float health, CallbackInfo ci) {
        MinecraftEvents.health(health, !flashOnSetHealth);
    }
}
