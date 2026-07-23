package com.prattlemob.marionette;

import com.prattlemob.marionette.bridge.BridgeServer;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.config.MarionetteConfig;
import com.prattlemob.marionette.config.Verbosity;
import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.ControlStateApplier;
import com.prattlemob.marionette.control.DemoScript;
import com.prattlemob.marionette.control.MixinInputApplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * Client-side lifecycle anchor and tick-loop orchestrator. Owns the single
 * {@link ControlState}, applies it through the {@link ControlStateApplier}
 * each tick (pre), and enforces the safety rule: whenever nothing should be
 * controlling the player, all controls release immediately.
 *
 * This class never loads on dedicated servers ({@code dist = Dist.CLIENT}),
 * so client-only code is safe to reference from here.
 */
@Mod(value = Marionette.MODID, dist = Dist.CLIENT)
public class MarionetteClient {
    /** How often (in ticks) to emit the heartbeat log line: 100 ticks = 5 s. */
    private static final long TICK_LOG_INTERVAL = 100;
    /** How often (in ticks) to log puppet position evidence while controlled. */
    private static final long PUPPET_LOG_INTERVAL = 20;

    private static void logNormal(String message, Object... args) {
        if (MarionetteConfig.logAt(Verbosity.NORMAL)) {
            Marionette.LOGGER.info(message, args);
        }
    }

    private static void logVerbose(String message, Object... args) {
        if (MarionetteConfig.logAt(Verbosity.VERBOSE)) {
            Marionette.LOGGER.info(message, args);
        }
    }

    private static MarionetteClient instance;

    private final ControlState controlState = new ControlState();
    private final ControlStateApplier applier = new MixinInputApplier();

    private boolean inWorld;
    private long ticksInWorld;
    private DemoScript demo;
    private boolean controlsEngaged;
    private BridgeServer bridge;

    private String modVersion;

    public MarionetteClient(ModContainer container, IEventBus modBus) {
        instance = this;
        modVersion = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, MarionetteConfig.SPEC);
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

    /** Configs are loaded by client setup; start the bridge on the main thread. */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(this::startBridge);
    }

    private void startBridge() {
        if (!MarionetteConfig.bridgeEnabled) {
            logNormal("Bridge disabled by config");
            return;
        }
        String configured = MarionetteConfig.bindAddress;
        String bind = MarionetteConfig.resolveBindAddress(configured);
        if (!bind.equals(configured)) {
            Marionette.LOGGER.warn(
                    "Config bindAddress '{}' is not loopback; clamped to 127.0.0.1. "
                    + "Non-loopback binding requires the explicit opt-out gate planned for M5.1.",
                    configured);
        }
        try {
            BridgeServer server = new BridgeServer(bind, MarionetteConfig.port, modVersion);
            server.start();
            bridge = server;
            logNormal("Bridge listening on {}:{}", bind, server.port());
        } catch (Exception e) {
            bridge = null;
            Marionette.LOGGER.error("Bridge failed to start; running without external control", e);
        }
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        if (bridge != null) {
            bridge.stop();
            logNormal("Bridge stopped");
        }
    }

    /** The singleton, or {@code null} until FML constructs the mod during client startup. */
    public static MarionetteClient instance() {
        return instance;
    }

    /** True while a hello-completed controller is attached to the bridge. */
    public boolean hasAgentController() {
        BridgeServer server = bridge;
        return server != null && server.hasController();
    }

    public boolean isInWorld() {
        return inWorld;
    }

    /** Client ticks since login; runs continuously across dimension changes and respawns. */
    public long ticksInWorld() {
        return ticksInWorld;
    }

    private void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        boolean agentLost = bridge != null && bridge.pollDisconnected();

        if (!inWorld || player == null) {
            if (bridge != null) {
                bridge.drainCommands(); // no world to act in: discard
            }
            if (agentLost) {
                logNormal("Agent disconnected");
            }
            // Safety rule: no player entity to control -> nothing may stay held.
            if (controlsEngaged) {
                demo = null;
                releaseControls();
            }
            return;
        }
        if (agentLost) {
            controlState.releaseAll(); // drop un-applied residue from the dead agent
            if (controlsEngaged) {
                logNormal("Agent disconnected — releasing all controls");
                releaseControls();
            } else {
                logNormal("Agent disconnected");
            }
        }
        if (bridge != null) {
            for (AgentCommand command : bridge.drainCommands()) {
                applyCommand(command);
            }
        }
        if (demo != null) {
            DemoScript.Stunt stunt = demo.tick(player.getYRot(), controlState);
            executeStunt(minecraft, player, stunt);
            if (demo.isDone()) {
                demo = null;
                logNormal("Demo complete");
            }
        }
        boolean shouldControl = demo != null || (bridge != null && bridge.hasController());
        if (shouldControl) {
            ControlState.Look look = controlState.consumeLook();
            if (look != null) {
                player.setYRot(look.yaw());
                player.setXRot(look.pitch());
            }
            applier.apply(controlState);
            controlsEngaged = true;
        } else if (controlsEngaged) {
            releaseControls();
        }
    }

    private void applyCommand(AgentCommand command) {
        switch (command) {
            case AgentCommand.InputUpdate update -> {
                if (update.forward() != null) controlState.setForward(update.forward());
                if (update.back() != null) controlState.setBack(update.back());
                if (update.left() != null) controlState.setLeft(update.left());
                if (update.right() != null) controlState.setRight(update.right());
                if (update.jump() != null) controlState.setJump(update.jump());
                if (update.sneak() != null) controlState.setSneak(update.sneak());
                if (update.sprint() != null) controlState.setSprint(update.sprint());
            }
            case AgentCommand.Look look -> controlState.setLook(look.yaw(), look.pitch());
            case AgentCommand.Release release -> controlState.releaseAll();
        }
    }

    private void executeStunt(Minecraft minecraft, LocalPlayer player, DemoScript.Stunt stunt) {
        switch (stunt) {
            case OPEN_INVENTORY -> {
                minecraft.setScreen(new InventoryScreen(player));
                logNormal("Demo stunt: opened inventory");
            }
            case CLOSE_SCREEN -> {
                minecraft.setScreen(null);
                logNormal("Demo stunt: closed screen");
            }
            case NONE -> { }
        }
    }

    /** The safety rule: neutral ControlState, applier released, evidence logged. */
    private void releaseControls() {
        controlState.releaseAll();
        applier.release();
        controlsEngaged = false;
        logNormal("Controls released; vanilla input restored");
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            logVerbose("Client tick {} in world", ticksInWorld);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (controlsEngaged && player != null && ticksInWorld % PUPPET_LOG_INTERVAL == 0) {
            logVerbose(String.format("Puppet pos %.2f %.2f %.2f yaw %.1f",
                    player.getX(), player.getY(), player.getZ(), player.getYRot()));
        }
        if (bridge != null && inWorld && player != null
                && ticksInWorld % MarionetteConfig.observationRateDivisor == 0) {
            bridge.sendObservation(Messages.observation(ticksInWorld,
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot()));
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        if (Boolean.getBoolean("marionette.demo")) {
            demo = new DemoScript(Boolean.getBoolean("marionette.demo.gui"));
            logNormal("Demo armed (gui stunts: {})", Boolean.getBoolean("marionette.demo.gui"));
        }
        logNormal("Entered world");
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // LoggingOut also fires with a null player during the defensive
        // disconnect that precedes creating an integrated server or joining a
        // multiplayer server; only a non-null player marks a real session end.
        if (event.getPlayer() == null) {
            return;
        }
        inWorld = false;
        demo = null;
        if (controlsEngaged) {
            releaseControls();
        }
        logNormal("Left world");
    }
}
