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

    // Intentionally empty: Marionette registers no gameplay content.
    // TODO: wire up the observation/action bridge once its design is settled (see protocol/).
    public Marionette(IEventBus modEventBus, ModContainer modContainer) {
    }
}
