package com.prattlemob.marionette;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Marionette.MODID, dist = Dist.CLIENT)
public class MarionetteClient {
    // Intentionally empty: client-side hooks will live here once the design is settled.
    public MarionetteClient(ModContainer container) {
    }
}
