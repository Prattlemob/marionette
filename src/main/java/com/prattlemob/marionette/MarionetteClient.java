package com.prattlemob.marionette;

import java.util.concurrent.TimeUnit;

import com.prattlemob.marionette.bridge.BridgeServer;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.config.MarionetteConfig;
import com.prattlemob.marionette.config.Verbosity;
import com.prattlemob.marionette.control.CameraSmoother;
import com.prattlemob.marionette.control.InventoryActionApplier;
import com.prattlemob.marionette.control.CappedRateModel;
import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.ControlStateApplier;
import com.prattlemob.marionette.control.DemoScript;
import com.prattlemob.marionette.control.MixinInputApplier;
import com.prattlemob.marionette.control.Rotation;
import com.prattlemob.marionette.control.SmoothingModel;

import com.prattlemob.marionette.observation.PlayerObservation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
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
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
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
    /** Longest dt one render frame may advance a pan, seconds (hitch/pause clamp). */
    private static final float MAX_FRAME_DT = 0.1f;

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
    private final KeyMapping panicKey = new KeyMapping("key.marionette.panic", GLFW.GLFW_KEY_F8,
            "key.categories.marionette");
    /** Separate re-arm key: only clears the panic latch (D16b). */
    private final KeyMapping rearmKey = new KeyMapping("key.marionette.rearm", GLFW.GLFW_KEY_F9,
            "key.categories.marionette");
    /** One replaceable toast slot for agent-control notices. */
    private static final SystemToast.SystemToastId CONTROL_TOAST = new SystemToast.SystemToastId();

    private final InventoryActionApplier inventoryApplier = new InventoryActionApplier();
    private final ControlState controlState = new ControlState();
    private final ControlStateApplier applier = new MixinInputApplier();
    private final CameraSmoother cameraSmoother = new CameraSmoother();
    /** nanoTime of the previous render frame; 0 = no previous frame. */
    private long lastFrameNanos;
    /** nanoTime when the active pan started, for the t= log field. */
    private long panStartNanos;

    private boolean inWorld;
    private long ticksInWorld;
    private DemoScript demo;
    private boolean controlsEngaged;
    private BridgeServer bridge;
    private long reportedCoalesced;

    private final String modVersion;

    public MarionetteClient(ModContainer container, IEventBus modBus) {
        instance = this;
        modVersion = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, MarionetteConfig.SPEC);
        modBus.addListener(this::onClientSetup);
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(panicKey);
            event.register(rearmKey);
        });
        // Panic is checked first and wins if both mappings share a key. Re-arm
        // is deliberate in-game input only: never from a screen or text field.
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            if (panicKey.matches(event.getKey(), event.getScanCode())) panic();
            else if (rearmKey.matches(event.getKey(), event.getScanCode())) rearm();
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            if (panicKey.matchesMouse(event.getButton())) panic();
            else if (rearmKey.matchesMouse(event.getButton())) rearm();
        });
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onInventoryMousePress);
        NeoForge.EVENT_BUS.addListener(this::onInventoryKeyPress);
        NeoForge.EVENT_BUS.addListener(this::onInventoryScroll);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

    public InventoryActionApplier inventoryActions() {
        return inventoryApplier;
    }

    private void onInventoryMousePress(ScreenEvent.MouseButtonPressed.Pre event) {
        inventoryApplier.cancel("human mouse input");
    }

    private void onInventoryKeyPress(ScreenEvent.KeyPressed.Pre event) {
        if (panicKey.matches(event.getKeyCode(), event.getScanCode())) {
            panic();
            event.setCanceled(true);
        }
        inventoryApplier.cancel("human keyboard input");
    }

    private void onInventoryScroll(ScreenEvent.MouseScrolled.Pre event) {
        inventoryApplier.cancel("human scroll input");
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
        java.net.InetAddress bind = MarionetteConfig.resolveBindAddress(configured);
        try {
            BridgeServer server = new BridgeServer(bind, MarionetteConfig.port, modVersion,
                    MarionetteConfig.maxObservers,
                    TimeUnit.SECONDS.toMillis(MarionetteConfig.helloTimeoutSeconds),
                    TimeUnit.SECONDS.toMillis(MarionetteConfig.pongTimeoutSeconds));
            server.start();
            bridge = server;
            logNormal("Bridge listening on {}:{}", bind, server.port());
        } catch (Exception e) {
            bridge = null;
            Marionette.LOGGER.error("Bridge failed to start; running without external control", e);
        }
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        inventoryApplier.cancel("game shutting down", false);
        // Ordering rule (docs/decisions.md, M2.3): controls release before the
        // bridge stops. Inert in practice (ticks have stopped) but explicit.
        if (controlsEngaged) {
            releaseControls();
        }
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
        processSafety();
        if (!inWorld || player == null) {
            processCommands(null);
            if (controlsEngaged) { demo = null; releaseControls(); }
            return;
        }
        processCommands(player);
        processSafety();
        inventoryApplier.tick();
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
            ControlState.LookDelta delta = controlState.consumeLookDelta();
            if (delta != null) {
                player.setYRot(player.getYRot() + delta.yaw());
                player.setXRot(Rotation.clampPitch(player.getXRot() + delta.pitch()));
            }
            cameraSmoother.onTick(new Rotation(player.getYRot(), player.getXRot()),
                    player.getX(), player.getEyeY(), player.getZ());
            if (minecraft.screen != null) {
                // Screen-open rule (M3.3 spec): a screen releases agent
                // attack/use — vanilla releases real keys on setScreen, and
                // a hold that resumed on close would stall against the
                // screen's missTime. Movement continues through GUIs (D4).
                controlState.releaseInteractions();
            } else {
                Integer hotbar = controlState.consumeHotbar();
                if (hotbar != null) {
                    player.getInventory().setSelectedSlot(hotbar);
                }
            }
            applier.apply(controlState);
            controlsEngaged = true;
        } else {
            cameraSmoother.cancel(); // nothing may keep driving the camera
            if (controlsEngaged) {
                releaseControls();
            }
        }
    }

    /** Disengage only: release, sever and latch. Repeating it never re-enables anything. */
    private void panic() {
        demo = null;
        boolean engaged = bridge != null && bridge.panic("local panic");
        releaseControls();
        if (bridge == null) {
            logNormal("Local panic: controls released (bridge not running)");
        } else if (engaged) {
            logNormal("Local panic: controller severed; agent control latched off until re-armed");
            notifyControl("marionette.control.disabled", "marionette.control.disabled.detail");
        } else {
            logNormal("Local panic: agent control already latched off");
            notifyControl("marionette.control.still_disabled", "marionette.control.disabled.detail");
        }
    }

    /** Clear the panic latch; grants, restores and replays nothing. */
    private void rearm() {
        if (Minecraft.getInstance().screen != null || bridge == null || !bridge.rearm()) return;
        logNormal("Panic latch re-armed: a controller may connect again");
        notifyControl("marionette.control.enabled", "marionette.control.enabled.detail");
    }

    private void notifyControl(String title, String detail) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.addOrUpdate(minecraft.getToastManager(), CONTROL_TOAST, Component.translatable(title),
                Component.translatable(detail, rearmKey.getTranslatedKeyMessage()));
    }

    private void processSafety() {
        if (bridge != null && bridge.pollRelease() != null) releaseControls();
        if (bridge != null && bridge.pollDisconnected()) {
            demo = null;
            releaseControls();
        }
    }

    private void processCommands(LocalPlayer player) {
        if (bridge == null) return;
        long deadline = System.nanoTime() + BridgeServer.WORK_BUDGET_NANOS;
        for (int i = 0; i < BridgeServer.COMMANDS_PER_TICK; i++) {
            processSafety();
            if (System.nanoTime() >= deadline) break;
            BridgeServer.Received received = bridge.pollCommand();
            if (received == null) break;
            if (!received.valid()) continue;
            if (player != null || received.command() instanceof AgentCommand.Configure
                    || received.command() instanceof AgentCommand.InventoryAction
                    || received.command() instanceof AgentCommand.Release) applyCommand(received, player);
        }
    }

    private void applyCommand(BridgeServer.Received received, LocalPlayer player) {
        switch (received.command()) {
            case AgentCommand.InventoryAction inventory -> {
                if (player == null) {
                    received.from().sendReliable(Messages.error(ErrorCode.INVENTORY_UNAVAILABLE,
                            "no world is loaded", inventory.id(), inventory.raw()));
                } else {
                    inventoryApplier.apply(inventory, received.from()::sendReliable);
                }
            }
            case AgentCommand.InputUpdate update -> {
                if (update.forward() != null) controlState.setForward(update.forward());
                if (update.back() != null) controlState.setBack(update.back());
                if (update.left() != null) controlState.setLeft(update.left());
                if (update.right() != null) controlState.setRight(update.right());
                if (update.jump() != null) controlState.setJump(update.jump());
                if (update.sneak() != null) controlState.setSneak(update.sneak());
                if (update.sprint() != null) controlState.setSprint(update.sprint());
                if (update.attack() != null) controlState.setAttack(update.attack());
                if (update.use() != null) controlState.setUse(update.use());
                if (update.hotbar() != null) controlState.selectHotbar(update.hotbar());
                update.taps().forEach(controlState::tap);
            }
            case AgentCommand.Look look -> {
                cameraSmoother.cancel();
                controlState.setLook(look.yaw(), look.pitch());
            }
            case AgentCommand.LookDelta delta -> {
                cameraSmoother.cancel();
                controlState.addLookDelta(delta.yaw(), delta.pitch());
            }
            case AgentCommand.LookSmoothAngles smooth -> {
                cameraSmoother.startAngles(smooth.yaw(), smooth.pitch(), newModel(smooth.speed()),
                        new Rotation(player.getYRot(), player.getXRot()));
                logPanStart(smooth.speed());
            }
            case AgentCommand.LookSmoothPoint smooth -> {
                boolean started = cameraSmoother.startPoint(smooth.x(), smooth.y(), smooth.z(),
                        newModel(smooth.speed()),
                        new Rotation(player.getYRot(), player.getXRot()),
                        player.getX(), player.getEyeY(), player.getZ());
                if (started) {
                    logPanStart(smooth.speed());
                } else {
                    received.from().sendReliable(Messages.error(ErrorCode.INVALID_FIELD,
                            "smooth look target is the player's eye position",
                            smooth.id(), smooth.raw()));
                }
            }
            case AgentCommand.Release release -> {
                releaseControls();
            }
            case AgentCommand.Configure configure -> {
                if (configure.sections() != null) received.from().setSections(configure.sections());
                if (configure.rateDivisor() != null) {
                    received.from().setRateDivisor(configure.rateDivisor());
                    logNormal("Observation rate divisor set to {} for a {} session",
                            configure.rateDivisor(), received.from().role());
                }
            }
        }
    }

    /** A fresh per-pan model at config speed × the message's multiplier. */
    private SmoothingModel newModel(Float speedMultiplier) {
        float speed = (float) (MarionetteConfig.cameraSmoothingSpeed
                * (speedMultiplier != null ? speedMultiplier : 1.0f));
        return new CappedRateModel(speed);
    }

    /** Pan-start log marker; scripts/analyze_pan.py parses this format. */
    private void logPanStart(Float speedMultiplier) {
        panStartNanos = System.nanoTime();
        Rotation target = cameraSmoother.target();
        float speed = (float) (MarionetteConfig.cameraSmoothingSpeed
                * (speedMultiplier != null ? speedMultiplier : 1.0f));
        logVerbose(String.format("Pan start target yaw=%.3f pitch=%.3f speed=%.1f model=%s",
                target.yaw(), target.pitch(), speed, CappedRateModel.class.getSimpleName()));
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
        inventoryApplier.cancel("controls released");
        cameraSmoother.cancel();
        controlState.releaseAll();
        applier.release();
        controlsEngaged = false;
        logNormal("Controls released; vanilla input restored");
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        // ClientTickEvent.Post fires after handleKeybinds in Minecraft.tick:
        // an attack/use tap nothing consumed this tick (screen open) dies
        // here rather than firing when the menu closes later.
        controlState.dropInteractionTaps();
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            logVerbose("Client tick {} in world", ticksInWorld);
        }
        if (bridge != null && ticksInWorld % TICK_LOG_INTERVAL == 0) {
            long total = bridge.coalescedObservations();
            if (total > reportedCoalesced) {
                logNormal("{} observation frames coalesced for a slow-reading agent ({} total this session)",
                        total - reportedCoalesced, total);
                reportedCoalesced = total;
            } else if (total < reportedCoalesced) {
                // coalescedObservations() sums only LIVE connections, so the total
                // drops whenever any connection (controller or observer) detaches,
                // not just on a reconnect — its already-reported drops leave the
                // sum with it. Rebase silently instead of reporting a negative
                // delta; the next increase is measured from this lower baseline,
                // so nothing already reported gets counted twice.
                reportedCoalesced = total;
            }
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (controlsEngaged && player != null && ticksInWorld % PUPPET_LOG_INTERVAL == 0) {
            logVerbose(String.format("Puppet pos %.2f %.2f %.2f yaw %.1f",
                    player.getX(), player.getY(), player.getZ(), player.getYRot()));
        }
        if (bridge != null && inWorld && player != null) {
            bridge.sendPlayerObservation(ticksInWorld, MarionetteConfig.observationRateDivisor,
                    () -> PlayerObservation.capture(player));
        }
    }

    /**
     * Advance an active smoothed pan by the real frame delta and write the
     * player's rotation — the same per-frame path vanilla mouse input uses,
     * so footage is smooth at any fps (M3.2, D5). State changes happen
     * tick-side only; tick and render share the client thread.
     */
    private void onRenderFramePre(RenderFrameEvent.Pre event) {
        processSafety();
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.0f
                : Math.min((now - lastFrameNanos) / 1_000_000_000.0f, MAX_FRAME_DT);
        lastFrameNanos = now;
        if (!cameraSmoother.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !inWorld) {
            return; // cancellation is tick-side; just don't advance
        }
        if (minecraft.isPaused()) {
            return; // frames render while paused; a frozen world's camera must not move
        }
        Rotation next = cameraSmoother.advanceFrame(
                new Rotation(player.getYRot(), player.getXRot()), dt);
        if (next == null) {
            return;
        }
        player.setYRot(next.yaw());
        player.setXRot(next.pitch());
        float t = (now - panStartNanos) / 1_000_000.0f;
        if (cameraSmoother.active()) {
            // Frame log marker; scripts/analyze_pan.py parses this format.
            logVerbose(String.format("Pan yaw=%.3f pitch=%.3f t=%.1f", next.yaw(), next.pitch(), t));
        } else {
            logVerbose(String.format("Pan converged t=%.1f", t));
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
        if (bridge != null && bridge.panicLatched()) {
            logNormal("Agent control remains latched off by panic");
            notifyControl("marionette.control.still_disabled", "marionette.control.disabled.detail");
        }
    }

    private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // LoggingOut also fires with a null player during the defensive
        // disconnect that precedes creating an integrated server or joining a
        // multiplayer server; only a non-null player marks a real session end.
        if (event.getPlayer() == null) {
            return;
        }
        if (bridge != null) bridge.disconnectController("left world");
        inventoryApplier.cancel("left world", false);
        inWorld = false;
        demo = null;
        if (controlsEngaged) {
            releaseControls();
        }
        logNormal("Left world");
    }
}
