package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.prattlemob.marionette.MarionetteClient;
import com.prattlemob.marionette.control.MixinInputApplier;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;

/**
 * M5.1 human precedence at vanilla's only mouse-look call: the per-frame
 * {@code player.turn} in {@code MouseHandler.turnPlayer}, which runs only
 * while the mouse is grabbed (in game, no screen). In agent-exclusive mode
 * the human's turn is dropped; vanilla still clears its accumulated movement,
 * so nothing is applied later. Otherwise a non-zero turn is reported as human
 * look input (human-priority pause) and applied as usual. Agent camera
 * control writes the rotation directly and never passes through here.
 */
@Mixin(MouseHandler.class)
public abstract class MouseLookMixin {
    @Redirect(method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void marionette$humanLook(LocalPlayer player, double yaw, double pitch) {
        if (MixinInputApplier.localInputLocked()) {
            return;
        }
        if (yaw != 0.0 || pitch != 0.0) {
            MarionetteClient client = MarionetteClient.instance();
            if (client != null) client.humanLook();
        }
        player.turn(yaw, pitch);
    }
}
