package com.prattlemob.marionette;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-side lifecycle anchor. Receives every client tick and tracks whether
 * the player is currently in a world; every later subsystem (input injection,
 * the bridge, observations) hangs off this class.
 *
 * This class never loads on dedicated servers ({@code dist = Dist.CLIENT}),
 * so client-only code is safe to reference from here.
 */
@Mod(value = Marionette.MODID, dist = Dist.CLIENT)
public class MarionetteClient {
    /** How often (in ticks) to emit the heartbeat log line: 100 ticks = 5 s. */
    private static final long TICK_LOG_INTERVAL = 100;

    private static MarionetteClient instance;

    private boolean inWorld;
    private long ticksInWorld;

    public MarionetteClient(ModContainer container) {
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
    }

    public static MarionetteClient instance() {
        return instance;
    }

    public boolean isInWorld() {
        return inWorld;
    }

    public long ticksInWorld() {
        return ticksInWorld;
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info("Client tick {} in world", ticksInWorld);
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        Marionette.LOGGER.info("Entered world");
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        inWorld = false;
        Marionette.LOGGER.info("Left world");
    }
}
