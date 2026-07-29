package com.prattlemob.marionette.mixin;

import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
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
 * OR-merge the agent's held controls in and recompute the move vector.
 * Extends ClientInput (the mixin target's superclass) for access to the
 * protected moveVector field.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void marionette$mergeAgentInput(CallbackInfo ci) {
        ControlState state = MixinInputApplier.activeState();
        if (state == null) {
            return;
        }
        Set<TapControl> taps = state.consumeTaps();
        this.keyPresses = new Input(
                this.keyPresses.forward() || state.forward(),
                this.keyPresses.backward() || state.back(),
                this.keyPresses.left() || state.left(),
                this.keyPresses.right() || state.right(),
                this.keyPresses.jump() || state.jump() || taps.contains(TapControl.JUMP),
                this.keyPresses.shift() || state.sneak(),
                this.keyPresses.sprint() || state.sprint());
        // Mirrors vanilla KeyboardInput.tick()'s impulse derivation —
        // re-verify against it on any Minecraft version bump.
        float forwardImpulse = this.keyPresses.forward() == this.keyPresses.backward()
                ? 0.0F : (this.keyPresses.forward() ? 1.0F : -1.0F);
        float leftImpulse = this.keyPresses.left() == this.keyPresses.right()
                ? 0.0F : (this.keyPresses.left() ? 1.0F : -1.0F);
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }
}
