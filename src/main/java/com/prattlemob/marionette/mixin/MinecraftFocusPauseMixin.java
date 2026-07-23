package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.prattlemob.marionette.MarionetteClient;
import com.prattlemob.marionette.config.MarionetteConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Suppresses the vanilla pause-on-focus-loss while an agent is connected,
 * so a puppeted session keeps running (and streaming) when the operator
 * alt-tabs. Active only when the suppressPauseOnLostFocus config toggle is
 * on AND a hello-completed controller is attached; otherwise vanilla
 * behavior is untouched. Never mutates Options.pauseOnLostFocus, so
 * options.txt cannot be persisted wrong.
 *
 * <p>Retargeted during M2.2 verification: in NeoForge 21.8.53's actual
 * decompiled sources there is no {@code Minecraft#pauseIfInactive()} method
 * (contrary to the plan, which was checked against a mismatched decompile).
 * The equivalent logic lives in {@code GameRenderer#render(DeltaTracker,
 * boolean)}, which calls {@code Minecraft#pauseGame(false)} once its own
 * focus/idle-time check passes. Redirecting only that call site (scoped to
 * this one method) leaves the unrelated {@code pauseGame} call in
 * {@code KeyboardHandler} — the manual F3+P debug toggle — untouched, and
 * preserves the original consequence: when suppression ends while the
 * window is still unfocused, {@code lastActiveTime} in GameRenderer is
 * already stale, so vanilla pauses on the next frame. (M2.2;
 * per-precedence-mode focus behavior is M5.1 — see docs/decisions.md.)
 */
@Mixin(GameRenderer.class)
public abstract class MinecraftFocusPauseMixin {
    @Redirect(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void marionette$suppressFocusPause(Minecraft instance, boolean normal) {
        MarionetteClient client = MarionetteClient.instance();
        if (MarionetteConfig.suppressPauseOnLostFocus && client != null && client.hasAgentController()) {
            return;
        }
        instance.pauseGame(normal);
    }
}
