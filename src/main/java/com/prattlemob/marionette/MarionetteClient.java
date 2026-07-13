package com.prattlemob.marionette;

import java.util.List;

import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.AgentCommand;
import com.prattlemob.marionette.bridge.BridgeServer;
import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.ControlStateApplier;
import com.prattlemob.marionette.control.DemoScript;
import com.prattlemob.marionette.control.MixinInputApplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
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

    private static MarionetteClient instance;

    private final ControlState controlState = new ControlState();
    private final ControlStateApplier applier = new MixinInputApplier();

    private boolean inWorld;
    private long ticksInWorld;
    private DemoScript demo;
    private boolean controlsEngaged;
    private BridgeServer bridge;

    public MarionetteClient(ModContainer container) {
        instance = this;
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
        try {
            BridgeServer server = new BridgeServer(BridgeServer.DEFAULT_PORT,
                    container.getModInfo().getVersion().toString());
            server.start();
            bridge = server;
            Marionette.LOGGER.info("Bridge listening on 127.0.0.1:{}", server.port());
        } catch (Exception e) {
            bridge = null;
            Marionette.LOGGER.error("Bridge failed to start; running without external control", e);
        }
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        if (bridge != null) {
            bridge.stop();
            Marionette.LOGGER.info("Bridge stopped");
        }
    }

    /** The singleton, or {@code null} until FML constructs the mod during client startup. */
    public static MarionetteClient instance() {
        return instance;
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
                Marionette.LOGGER.info("Agent disconnected");
            }
            // Safety rule: no player entity to control -> nothing may stay held.
            if (controlsEngaged) {
                demo = null;
                releaseControls();
            }
            return;
        }
        if (agentLost) {
            Marionette.LOGGER.info("Agent disconnected — releasing all controls");
            releaseControls();
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
                Marionette.LOGGER.info("Demo complete");
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
            case AgentCommand.Hello hello -> { } // handshake handled by the bridge
        }
    }

    private void executeStunt(Minecraft minecraft, LocalPlayer player, DemoScript.Stunt stunt) {
        switch (stunt) {
            case OPEN_INVENTORY -> {
                minecraft.setScreen(new InventoryScreen(player));
                Marionette.LOGGER.info("Demo stunt: opened inventory");
            }
            case CLOSE_SCREEN -> {
                minecraft.setScreen(null);
                Marionette.LOGGER.info("Demo stunt: closed screen");
            }
            case NONE -> { }
        }
    }

    /** The safety rule: neutral ControlState, applier released, evidence logged. */
    private void releaseControls() {
        controlState.releaseAll();
        applier.release();
        controlsEngaged = false;
        Marionette.LOGGER.info("Controls released; vanilla input restored");
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info("Client tick {} in world", ticksInWorld);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (controlsEngaged && player != null && ticksInWorld % PUPPET_LOG_INTERVAL == 0) {
            Marionette.LOGGER.info(String.format("Puppet pos %.2f %.2f %.2f yaw %.1f",
                    player.getX(), player.getY(), player.getZ(), player.getYRot()));
        }
        if (bridge != null && inWorld && player != null) {
            JsonObject frame = new JsonObject();
            frame.addProperty("type", "observation");
            frame.addProperty("tick", ticksInWorld);
            frame.addProperty("x", player.getX());
            frame.addProperty("y", player.getY());
            frame.addProperty("z", player.getZ());
            frame.addProperty("yaw", player.getYRot());
            frame.addProperty("pitch", player.getXRot());
            bridge.sendObservation(frame.toString());
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        if (Boolean.getBoolean("marionette.demo")) {
            demo = new DemoScript(Boolean.getBoolean("marionette.demo.gui"));
            Marionette.LOGGER.info("Demo armed (gui stunts: {})", Boolean.getBoolean("marionette.demo.gui"));
        }
        Marionette.LOGGER.info("Entered world");
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
        Marionette.LOGGER.info("Left world");
    }
}
