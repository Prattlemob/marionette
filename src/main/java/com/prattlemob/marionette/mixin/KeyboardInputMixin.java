package com.prattlemob.marionette.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.MixinInputApplier;
import com.prattlemob.marionette.control.TapControl;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * D4 variant 2: after vanilla populates the semantic input from key state,
 * OR-merge the agent's held controls in and recompute the move vector. In
 * agent-exclusive mode (M5.1) the human's keys are replaced, not merged.
 * Extends ClientInput (the mixin target's superclass) for access to the
 * protected moveVector field.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Unique
    private static final ControlState NEUTRAL = new ControlState();

    @Inject(method = "tick", at = @At("TAIL"))
    private void marionette$mergeAgentInput(CallbackInfo ci) {
        ControlState state = MixinInputApplier.activeState();
        // Agent-exclusive (M5.1): replace instead of merge, so the human's
        // movement, jump, sneak and sprint keys have no effect.
        boolean locked = MixinInputApplier.localInputLocked();
        if (state == null && !locked) {
            return;
        }
        if (state == null) state = NEUTRAL;
        Input human = locked ? Input.EMPTY : this.keyPresses;
        // Consume even when jump is already held, so a tap cannot survive
        // short-circuit evaluation and fire after the held key is released.
        boolean jumpTap = state.consumeTap(TapControl.JUMP);
        this.keyPresses = new Input(
                human.forward() || state.forward(),
                human.backward() || state.back(),
                human.left() || state.left(),
                human.right() || state.right(),
                human.jump() || state.jump() || jumpTap,
                human.shift() || state.sneak(),
                human.sprint() || state.sprint());
        // Mirrors vanilla KeyboardInput.tick()'s impulse derivation —
        // re-verify against it on any Minecraft version bump.
        float forwardImpulse = this.keyPresses.forward() == this.keyPresses.backward()
                ? 0.0F : (this.keyPresses.forward() ? 1.0F : -1.0F);
        float leftImpulse = this.keyPresses.left() == this.keyPresses.right()
                ? 0.0F : (this.keyPresses.left() ? 1.0F : -1.0F);
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }
}
