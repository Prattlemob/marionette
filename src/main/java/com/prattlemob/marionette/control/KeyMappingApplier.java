package com.prattlemob.marionette.control;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * D4 variant 1: force the pressed state of the vanilla movement key
 * mappings. Known risk (probed by the experiment, not worked around here):
 * keyShift/keySprint are ToggleKeyMappings — with toggle-crouch/-sprint
 * enabled, setDown(true) toggles instead of holding and setDown(false)
 * no-ops, so this variant may flicker or leak stuck toggle state.
 */
public final class KeyMappingApplier implements ControlStateApplier {
    @Override
    public void apply(ControlState state) {
        Options options = Minecraft.getInstance().options;
        options.keyUp.setDown(state.forward());
        options.keyDown.setDown(state.back());
        options.keyLeft.setDown(state.left());
        options.keyRight.setDown(state.right());
        options.keyJump.setDown(state.jump());
        options.keyShift.setDown(state.sneak());
        options.keySprint.setDown(state.sprint());
    }

    @Override
    public void release() {
        Options options = Minecraft.getInstance().options;
        for (KeyMapping key : new KeyMapping[] {
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint }) {
            key.setDown(false);
        }
    }
}
