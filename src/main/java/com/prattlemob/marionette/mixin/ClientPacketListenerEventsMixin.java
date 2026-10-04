package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.prattlemob.marionette.event.MinecraftEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;

/**
 * M4.6: server-reported one-shot events. Each handler first reschedules
 * itself from the network thread (ensureRunningOnSameThread throws there),
 * so TAIL injections only run on the client thread; HEAD injections check.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerEventsMixin {
    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void marionette$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        MinecraftEvents.damage(packet);
    }

    @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
    private void marionette$death(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        MinecraftEvents.death(packet);
    }

    /** HEAD: vanilla shrinks/removes the item entity before TAIL. */
    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void marionette$pickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) MinecraftEvents.pickup(packet);
    }
}
