package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.MixinInputApplier;
import com.prattlemob.marionette.control.TapControl;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;

/**
 * M3.3: OR-merges agent attack/use (and, M3.7, swap-hands) intent into the values
 * {@code Minecraft.handleKeybinds()} reads — the same
 * manipulate-meaning-not-key-state philosophy as the D4 movement mixin,
 * at the method vanilla routes clicks and holds through. Vanilla itself
 * then runs startAttack/startUseItem/continueAttack, so attack
 * cooldown, missTime, rightClickDelay, and use ticks stay vanilla, and
 * NeoForge's onClickInput hooks observe agent clicks like human ones.
 *
 * <p>Agent-exclusive mode (M5.1) replaces instead of merging: the human's
 * attack, use, pick-block, drop, swap-hands and hotbar presses are dropped.
 *
 * <p>All three redirects are scoped to handleKeybinds and identity-check
 * the mapping: every other KeyMapping call in the method passes through
 * untouched. Null activeState() = agent inactive = pure vanilla.
 *
 * <p>The isMouseGrabbed redirect exists because vanilla gates held-mining
 * on a grabbed mouse, which is false while the window is unfocused —
 * and this mod deliberately keeps unfocused clients running
 * (suppressPauseOnLostFocus, M2.2). An agent has no mouse to grab.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInteractionMixin {
    @Inject(method = "setScreen", at = @At("RETURN"))
    private void marionette$releaseInteractionsOnScreenOpen(Screen screen, CallbackInfo ci) {
        ControlState state = MixinInputApplier.activeState();
        if (((Minecraft) (Object) this).screen != null && state != null) {
            // A keybind or use action can open a screen after tick-pre. Release
            // immediately, before handleKeybinds can process more interactions.
            state.releaseInteractions();
        }
    }

    @Redirect(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean marionette$mergeAgentHolds(KeyMapping mapping) {
        boolean vanilla = mapping.isDown();
        ControlState state = MixinInputApplier.activeState();
        Minecraft minecraft = (Minecraft) (Object) this;
        if (MixinInputApplier.localInputLocked() && (mapping == minecraft.options.keyAttack
                || mapping == minecraft.options.keyUse)) {
            // Agent-exclusive (M5.1): the human's attack/use buttons do nothing.
            vanilla = false;
        }
        if (state == null) {
            return vanilla;
        }
        if (mapping == minecraft.options.keyAttack) {
            return vanilla || state.attack();
        }
        if (mapping == minecraft.options.keyUse) {
            return vanilla || state.use();
        }
        return vanilla;
    }

    @Redirect(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
    private boolean marionette$mergeAgentClicks(KeyMapping mapping) {
        // Vanilla's while (consumeClick()) loops stay bounded: consumeTap
        // is consume-once, and short-circuiting only defers it to the
        // loop's next iteration.
        boolean vanilla = mapping.consumeClick();
        ControlState state = MixinInputApplier.activeState();
        Minecraft minecraft = (Minecraft) (Object) this;
        if (vanilla && MixinInputApplier.localInputLocked() && marionette$gameplay(minecraft, mapping)) {
            // Agent-exclusive (M5.1): drain and drop the human's gameplay clicks.
            while (mapping.consumeClick()) {
                // discard queued presses too, so none fires after the lockout ends
            }
            vanilla = false;
        }
        if (state == null) {
            return vanilla;
        }
        if (mapping == minecraft.options.keyAttack) {
            return vanilla || state.consumeTap(TapControl.ATTACK);
        }
        if (mapping == minecraft.options.keyUse) {
            return vanilla || state.consumeTap(TapControl.USE);
        }
        if (mapping == minecraft.options.keySwapOffhand) {
            // M3.7 (swapHands): one press of vanilla's swap-offhand key; vanilla
            // sends its own server request and keeps the spectator rule.
            return vanilla || state.consumeTap(TapControl.SWAP_HANDS);
        }
        return vanilla;
    }

    /** Gameplay clicks suppressed by agent-exclusive mode; UI and system keys are not listed. */
    @Unique
    private static boolean marionette$gameplay(Minecraft minecraft, KeyMapping mapping) {
        var options = minecraft.options;
        if (mapping == options.keyAttack || mapping == options.keyUse || mapping == options.keyPickItem
                || mapping == options.keyDrop || mapping == options.keySwapOffhand) {
            return true;
        }
        for (KeyMapping slot : options.keyHotbarSlots) {
            if (mapping == slot) return true;
        }
        return false;
    }

    @Redirect(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean marionette$mergeMouseGrab(MouseHandler handler) {
        ControlState state = MixinInputApplier.activeState();
        return handler.isMouseGrabbed() || (state != null && state.attack());
    }
}
