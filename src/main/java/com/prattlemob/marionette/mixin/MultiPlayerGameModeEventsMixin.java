package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.prattlemob.marionette.event.MinecraftEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * M4.6: the client's own block-break prediction. destroyBlock returns true
 * when the local player broke the block client-side; the server may still
 * refuse it. The state is captured before vanilla removes the block.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeEventsMixin {
    @Unique private BlockState marionette$breaking;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void marionette$captureBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        var level = Minecraft.getInstance().level;
        marionette$breaking = level == null ? null : level.getBlockState(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void marionette$blockBroken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = marionette$breaking;
        marionette$breaking = null;
        if (cir.getReturnValueZ() && state != null) MinecraftEvents.blockBroken(state, pos);
    }
}
