package com.prattlemob.marionette;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Marionette.MODID)
public class Marionette {
    public static final String MODID = "marionette";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Intentionally minimal: Marionette registers no gameplay content.
    // Client-side lifecycle lives in MarionetteClient; this common entrypoint
    // stays a no-op so the mod is inert on dedicated servers.
    public Marionette(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Marionette {} initialising", modContainer.getModInfo().getVersion());
    }
}
