package com.prattlemob.marionette;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.BridgeServer;
import com.prattlemob.marionette.bridge.protocol.AgentCommand;
import com.prattlemob.marionette.bridge.protocol.ConnectionStatus;
import com.prattlemob.marionette.bridge.protocol.StatusReport;
import com.prattlemob.marionette.bridge.protocol.ErrorCode;
import com.prattlemob.marionette.bridge.protocol.Messages;
import com.prattlemob.marionette.config.MarionetteConfig;
import com.prattlemob.marionette.config.LogCategory;
import com.prattlemob.marionette.config.MarionetteLog;
import com.prattlemob.marionette.control.CameraSmoother;
import com.prattlemob.marionette.control.InventoryActionApplier;
import com.prattlemob.marionette.control.CappedRateModel;
import com.prattlemob.marionette.control.ChatPolicy;
import com.prattlemob.marionette.control.ControlState;
import com.prattlemob.marionette.control.ControlStateApplier;
import com.prattlemob.marionette.control.DemoScript;
import com.prattlemob.marionette.control.HumanPrecedence;
import com.prattlemob.marionette.control.HumanPrecedence.HumanInput;
import com.prattlemob.marionette.control.MixinInputApplier;
import com.prattlemob.marionette.control.Rotation;
import com.prattlemob.marionette.control.SmoothingModel;
import com.prattlemob.marionette.diagnostics.StatusHud;
import com.prattlemob.marionette.event.EventRecorder;
import com.prattlemob.marionette.event.MinecraftEvents;

import com.prattlemob.marionette.observation.BlockScanRunner;
import com.prattlemob.marionette.observation.EntityObservation;
import com.prattlemob.marionette.observation.InventoryObservation;
import com.prattlemob.marionette.observation.LevelBlockSource;
import com.prattlemob.marionette.observation.PlayerObservation;
import com.prattlemob.marionette.observation.TargetObservation;
import com.prattlemob.marionette.observation.WorldObservation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
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

    private static void logNormal(LogCategory category, String message, Object... args) {
        MarionetteLog.normal(category, message, args);
    }

    private static void logVerbose(LogCategory category, String message, Object... args) {
        MarionetteLog.verbose(category, message, args);
    }

    private static MarionetteClient instance;
    private final KeyMapping panicKey = new KeyMapping("key.marionette.panic", GLFW.GLFW_KEY_F8,
            "key.categories.marionette");
    /** Separate re-arm key: only clears the panic latch (D16b). */
    private final KeyMapping rearmKey = new KeyMapping("key.marionette.rearm", GLFW.GLFW_KEY_F9,
            "key.categories.marionette");
    /** Agent-exclusive input lockout toggle (M5.1); never suppressed. */
    private final KeyMapping lockoutKey = new KeyMapping("key.marionette.lockout", GLFW.GLFW_KEY_F7,
            "key.categories.marionette");
    /** Status HUD toggle (M5.2); an interface key, never suppressed or human input. */
    private final KeyMapping hudKey = new KeyMapping("key.marionette.hud", GLFW.GLFW_KEY_F6,
            "key.categories.marionette");
    /** One replaceable toast slot for agent-control notices. */
    private static final SystemToast.SystemToastId CONTROL_TOAST = new SystemToast.SystemToastId();

    private final InventoryActionApplier inventoryApplier = new InventoryActionApplier();
    /** Bounded block scans (M4.5, D3): one at a time, a budgeted portion per tick. */
    private final BlockScanRunner scans = new BlockScanRunner();
    private LevelBlockSource scanSource;
    private final ControlState controlState = new ControlState();
    /** Human precedence modes, pause and lockout (M5.1); client thread only. */
    private final HumanPrecedence precedence = new HumanPrecedence(
            () -> TimeUnit.MILLISECONDS.toNanos(MarionetteConfig.resumeAfterMillis));
    /** The local player was dead at the last check: death releases agent holds once. */
    private boolean playerDead;
    /** Chat limits (M3.7): client-wide, so reconnecting never resets the rate window. */
    private final ChatPolicy chatPolicy = new ChatPolicy(System::nanoTime);
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
    /** One-shot events (M4.6); stamped with the in-progress tick, sent immediately. */
    private final EventRecorder eventRecorder = new EventRecorder(() -> ticksInWorld + 1, event -> {
        logVerbose(LogCategory.EVENTS, "Event {} tick {} ({})", event.kind(), event.tick(), event.fields());
        BridgeServer server = bridge;
        if (server != null) server.sendEvent(event);
    });

    private final String modVersion;

    public MarionetteClient(ModContainer container, IEventBus modBus) {
        instance = this;
        modVersion = container.getModInfo().getVersion().toString();
        container.registerConfig(ModConfig.Type.CLIENT, MarionetteConfig.SPEC);
        modBus.addListener(this::onClientSetup);
        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(panicKey);
            event.register(rearmKey);
            event.register(lockoutKey);
            event.register(hudKey);
        });
        // Below chat, so chat and the tab list draw over it; above everything else in game.
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerBelow(VanillaGuiLayers.CHAT,
                ResourceLocation.fromNamespaceAndPath(Marionette.MODID, "status"),
                new StatusHud(this::statusReport, () -> lockoutKey.getTranslatedKeyMessage().getString(),
                        () -> rearmKey.getTranslatedKeyMessage().getString())));
        MixinInputApplier.setLocalInputGate(this::localInputLocked);
        // Panic is checked first and wins if mappings share a key. Re-arm and
        // engaging the lockout are deliberate in-game input only: never from a
        // screen or text field. None of these keys is ever suppressed.
        NeoForge.EVENT_BUS.addListener((InputEvent.Key event) -> {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                if (panicKey.matches(event.getKey(), event.getScanCode())) panic();
                else if (rearmKey.matches(event.getKey(), event.getScanCode())) rearm();
                else if (lockoutKey.matches(event.getKey(), event.getScanCode())) toggleLockout();
                else if (hudKey.matches(event.getKey(), event.getScanCode())) toggleHud();
            }
            if (event.getAction() != GLFW.GLFW_RELEASE) {
                humanInput(mapping -> mapping.matches(event.getKey(), event.getScanCode()));
            }
        });
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseButton.Pre event) -> {
            if (event.getAction() != GLFW.GLFW_PRESS) return;
            if (panicKey.matchesMouse(event.getButton())) panic();
            else if (rearmKey.matchesMouse(event.getButton())) rearm();
            else if (lockoutKey.matchesMouse(event.getButton())) toggleLockout();
            else if (hudKey.matchesMouse(event.getButton())) toggleHud();
            humanInput(mapping -> mapping.matchesMouse(event.getButton()));
        });
        // In game only (vanilla fires it with no screen open): hotbar scrolling.
        NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent event) -> {
            if (localInputLocked()) event.setCanceled(true);
            else humanInputs(EnumSet.of(HumanInput.HOTBAR));
        });
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onRenderFramePre);
        NeoForge.EVENT_BUS.addListener(this::onLoggingIn);
        NeoForge.EVENT_BUS.addListener(this::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(this::onPlayerClone);
        // Last, and only if shown: what the player actually saw.
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onChatReceived);
        NeoForge.EVENT_BUS.addListener(this::onInventoryMousePress);
        NeoForge.EVENT_BUS.addListener(this::onInventoryKeyPress);
        NeoForge.EVENT_BUS.addListener(this::onInventoryScroll);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
    }

    public InventoryActionApplier inventoryActions() {
        return inventoryApplier;
    }

    /** Client-thread recorder for one-shot events; mixins report through it. */
    public EventRecorder eventRecorder() {
        return eventRecorder;
    }

    /** Respawn packet: a new LocalPlayer replaces the old one (death or dimension). */
    private void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        eventRecorder.playerReplaced(event.getOldPlayer().isDeadOrDying(),
                MinecraftEvents.dimension(event.getOldPlayer().level()),
                MinecraftEvents.dimension(event.getNewPlayer().level()));
        // Lifecycle rule (M5.1): holds never carry over a respawn or dimension change.
        if (controlsEngaged) releaseControls("released", "player replaced (respawn or dimension change)", true);
        playerDead = false;
    }

    private void onChatReceived(ClientChatReceivedEvent event) {
        String kind = "chat";
        String sender = null;
        String senderName = null;
        if (event instanceof ClientChatReceivedEvent.System system) {
            kind = system.isOverlay() ? "action_bar" : "system";
        } else if (event instanceof ClientChatReceivedEvent.Player player) {
            sender = player.getSender().toString();
            senderName = MinecraftEvents.playerName(player.getSender());
        }
        var bound = event.getBoundChatType();
        String chatType = bound == null ? null
                : bound.chatType().unwrapKey().map(key -> key.location().toString()).orElse(null);
        eventRecorder.chat(kind, event.getMessage().getString(), sender, senderName, chatType);
    }

    private void onInventoryMousePress(ScreenEvent.MouseButtonPressed.Pre event) {
        inventoryApplier.cancel("human_input", "human mouse input");
    }

    private void onInventoryKeyPress(ScreenEvent.KeyPressed.Pre event) {
        if (panicKey.matches(event.getKeyCode(), event.getScanCode())) {
            panic();
            event.setCanceled(true);
        } else if (lockoutKey.matches(event.getKeyCode(), event.getScanCode()) && precedence.lockout()) {
            // Releasing the lockout works everywhere; engaging needs gameplay.
            toggleLockout();
            event.setCanceled(true);
        }
        inventoryApplier.cancel("human_input", "human keyboard input");
    }

    private void onInventoryScroll(ScreenEvent.MouseScrolled.Pre event) {
        inventoryApplier.cancel("human_input", "human scroll input");
    }

    /** Configs are loaded by client setup; start the bridge on the main thread. */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(this::startBridge);
    }

    private void startBridge() {
        if (!MarionetteConfig.bridgeEnabled) {
            logNormal(LogCategory.BRIDGE, "Bridge disabled by config");
            return;
        }
        String configured = MarionetteConfig.bindAddress;
        boolean optOut = MarionetteConfig.nonLoopbackOptOut;
        // Resolved exactly once; this object is what Netty binds (D16, M5.1).
        java.net.InetAddress bind = MarionetteConfig.resolveBindAddress(configured, optOut);
        try {
            BridgeServer server = new BridgeServer(bind, MarionetteConfig.port, modVersion,
                    MarionetteConfig.maxObservers,
                    TimeUnit.SECONDS.toMillis(MarionetteConfig.helloTimeoutSeconds),
                    TimeUnit.SECONDS.toMillis(MarionetteConfig.pongTimeoutSeconds), optOut);
            server.start();
            bridge = server;
            if (bind.isLoopbackAddress()) {
                logNormal(LogCategory.BRIDGE, "Bridge listening on {}:{}", bind, server.port());
            } else {
                Marionette.LOGGER.warn("Bridge listening on NON-LOOPBACK {}:{} without authentication"
                        + " (bridge.iUnderstandNonLoopbackIsUnauthenticated)", bind, server.port());
            }
        } catch (Exception e) {
            bridge = null;
            Marionette.LOGGER.error("Bridge failed to start; running without external control", e);
        }
    }

    private void onGameShuttingDown(GameShuttingDownEvent event) {
        inventoryApplier.cancel("world_exit", "game shutting down", false);
        scans.cancel("world_exit", "game shutting down");
        // Ordering rule (docs/decisions.md, M2.3): controls release before the
        // bridge stops. Inert in practice (ticks have stopped) but explicit.
        if (controlsEngaged) {
            releaseControls();
        }
        if (bridge != null) {
            bridge.stop();
            logNormal(LogCategory.BRIDGE, "Bridge stopped");
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

    /**
     * True while agent-exclusive mode suppresses local gameplay input: the
     * lockout is engaged for the controller attached right now. Read by the
     * input mixins on the client thread; no controller means no lockout.
     */
    public boolean localInputLocked() {
        BridgeServer server = bridge;
        return server != null && precedence.suppressLocal(server.currentController());
    }

    /** Mouse look turned the camera (MouseLookMixin); human input in human-priority mode. */
    public void humanLook() {
        humanInputs(EnumSet.of(HumanInput.LOOK));
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
        if (bridge != null) bridge.sampleRates(System.nanoTime());
        processSafety();
        if (!inWorld || player == null) {
            processCommands(null);
            if (controlsEngaged) { demo = null; releaseControls(); }
            return;
        }
        boolean dead = player.isDeadOrDying();
        if (dead && !playerDead && controlsEngaged) {
            // Lifecycle rule (M5.1): death releases every agent actuator once.
            releaseControls("released", "player died", true);
        }
        playerDead = dead;
        processCommands(player);
        processSafety();
        inventoryApplier.tick();
        if (demo != null) {
            DemoScript.Stunt stunt = demo.tick(player.getYRot(), controlState);
            executeStunt(minecraft, player, stunt);
            if (demo.isDone()) {
                demo = null;
                logNormal(LogCategory.CONTROL, "Demo complete");
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
        report(precedence.panic());
        if (bridge == null) {
            logNormal(LogCategory.PRECEDENCE, "Local panic: controls released (bridge not running)");
        } else if (engaged) {
            logNormal(LogCategory.PRECEDENCE, "Local panic: controller severed; agent control latched off until re-armed");
            notifyControl("marionette.control.disabled", "marionette.control.disabled.detail");
        } else {
            logNormal(LogCategory.PRECEDENCE, "Local panic: agent control already latched off");
            notifyControl("marionette.control.still_disabled", "marionette.control.disabled.detail");
        }
    }

    /** Clear the panic latch; grants, restores and replays nothing. */
    private void rearm() {
        if (Minecraft.getInstance().screen != null || bridge == null || !bridge.rearm()) return;
        report(precedence.rearm());
        logNormal(LogCategory.PRECEDENCE, "Panic latch re-armed: a controller may connect again");
        notifyControl("marionette.control.enabled", "marionette.control.enabled.detail");
    }

    private void notifyControl(String title, String detail) {
        notifyControl(title, detail, rearmKey);
    }

    private void notifyControl(String title, String detail, KeyMapping key) {
        Minecraft minecraft = Minecraft.getInstance();
        SystemToast.addOrUpdate(minecraft.getToastManager(), CONTROL_TOAST, Component.translatable(title),
                Component.translatable(detail, key.getTranslatedKeyMessage()));
    }

    /** The lockout key (M5.1): release anywhere; engage only in game with an attached controller. */
    private void toggleLockout() {
        syncPrecedence();
        HumanPrecedence.Toggle toggle = precedence.toggleLockout(Minecraft.getInstance().screen != null);
        switch (toggle.result()) {
            case ENGAGED -> {
                logNormal(LogCategory.PRECEDENCE, "Input lockout engaged: agent-exclusive, local gameplay input suppressed");
                notifyControl("marionette.lockout.on", "marionette.lockout.on.detail", lockoutKey);
            }
            case RELEASED -> {
                logNormal(LogCategory.PRECEDENCE, "Input lockout released: human-priority");
                notifyControl("marionette.lockout.off", "marionette.lockout.off.detail", lockoutKey);
            }
            case NO_CONTROLLER -> notifyControl("marionette.lockout.unavailable",
                    "marionette.lockout.unavailable.detail", lockoutKey);
            case PANIC_LATCHED -> notifyControl("marionette.control.still_disabled",
                    "marionette.control.disabled.detail");
            case SCREEN_OPEN -> { }
        }
        report(toggle.change());
    }

    /** The HUD key (M5.2): in game with no screen open, like re-arm; the choice is saved. */
    private void toggleHud() {
        if (Minecraft.getInstance().screen != null) return;
        logNormal(LogCategory.CLIENT, "Status HUD {}", MarionetteConfig.toggleHud() ? "shown" : "hidden");
    }

    /**
     * What the HUD shows and a status query reports, sampled now on the client
     * thread (protocol/v1.md, status_result). The session field is left for
     * the requester.
     */
    public StatusReport statusReport() {
        BridgeServer server = bridge;
        long now = System.nanoTime();
        boolean latched = server != null && server.panicLatched();
        ConnectionStatus controller = server == null ? null
                : server.controllerStatus(MarionetteConfig.observationRateDivisor, now);
        String state = latched ? StatusReport.LATCHED : controller != null ? StatusReport.CONNECTED : StatusReport.IDLE;
        List<String> held = new ArrayList<>();
        if (controlState.forward()) held.add("forward");
        if (controlState.back()) held.add("back");
        if (controlState.left()) held.add("left");
        if (controlState.right()) held.add("right");
        if (controlState.jump()) held.add("jump");
        if (controlState.sneak()) held.add("sneak");
        if (controlState.sprint()) held.add("sprint");
        if (controlState.attack()) held.add("attack");
        if (controlState.use()) held.add("use");
        return new StatusReport(state, precedence.mode().wire(), precedence.agentPaused(), inWorld,
                inWorld ? ticksInWorld : null, List.copyOf(held), cameraSmoother.active(),
                server == null ? 0 : server.observerCount(), controller, null);
    }

    /** Human gameplay input matching {@code matches}, if it happened in game with no screen open. */
    private void humanInput(Predicate<KeyMapping> matches) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) return;
        EnumSet<HumanInput> inputs = EnumSet.noneOf(HumanInput.class);
        Options options = minecraft.options;
        if (matches.test(options.keyUp) || matches.test(options.keyDown) || matches.test(options.keyLeft)
                || matches.test(options.keyRight)) inputs.add(HumanInput.MOVEMENT);
        if (matches.test(options.keyJump)) inputs.add(HumanInput.JUMP);
        if (matches.test(options.keyShift)) inputs.add(HumanInput.SNEAK);
        if (matches.test(options.keySprint)) inputs.add(HumanInput.SPRINT);
        if (matches.test(options.keyAttack)) inputs.add(HumanInput.ATTACK);
        if (matches.test(options.keyUse)) inputs.add(HumanInput.USE);
        if (matches.test(options.keyPickItem)) inputs.add(HumanInput.PICK_BLOCK);
        if (matches.test(options.keyDrop)) inputs.add(HumanInput.DROP);
        if (matches.test(options.keySwapOffhand)) inputs.add(HumanInput.SWAP_HANDS);
        for (KeyMapping slot : options.keyHotbarSlots) {
            if (matches.test(slot)) inputs.add(HumanInput.HOTBAR);
        }
        humanInputs(inputs);
    }

    private void humanInputs(EnumSet<HumanInput> inputs) {
        if (inputs.isEmpty() || !inWorld) return;
        syncPrecedence();
        report(precedence.human(inputs, System.nanoTime()));
    }

    /**
     * Human gameplay input held right now: non-toggle gameplay keys and
     * buttons down in game, or the pause menu open. Key state is released by
     * vanilla whenever a screen opens.
     */
    private EnumSet<HumanInput> heldHumanInputs() {
        EnumSet<HumanInput> held = EnumSet.noneOf(HumanInput.class);
        Minecraft minecraft = Minecraft.getInstance();
        if (!inWorld || minecraft.player == null) return held;
        if (minecraft.screen instanceof PauseScreen) held.add(HumanInput.PAUSE_MENU);
        if (minecraft.screen != null) return held;
        Options options = minecraft.options;
        if (options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown()
                || options.keyRight.isDown()) held.add(HumanInput.MOVEMENT);
        if (options.keyJump.isDown()) held.add(HumanInput.JUMP);
        // Toggle mode reports the toggled state, not a held key: presses count instead.
        if (!options.toggleCrouch().get() && options.keyShift.isDown()) held.add(HumanInput.SNEAK);
        if (!options.toggleSprint().get() && options.keySprint.isDown()) held.add(HumanInput.SPRINT);
        if (options.keyAttack.isDown()) held.add(HumanInput.ATTACK);
        if (options.keyUse.isDown()) held.add(HumanInput.USE);
        if (options.keyPickItem.isDown()) held.add(HumanInput.PICK_BLOCK);
        return held;
    }

    /** Follow the bridge's attached controller: identity changes drop lockout and pause. */
    private void syncPrecedence() {
        BridgeServer server = bridge;
        report(precedence.controller(server == null ? null : server.currentController()));
    }

    /** Act on and announce one precedence change (control event, log, notices). */
    private void report(HumanPrecedence.Change change) {
        if (change == null) return;
        if (change.releasesAgent()) {
            releaseControls("human_input", "human input paused agent control", false);
        }
        List<String> inputs = change.inputs().stream().map(HumanInput::wire).toList();
        logNormal(LogCategory.PRECEDENCE, "Human precedence: mode {} paused {} cause {}{}", change.mode().wire(), change.paused(),
                change.cause().wire(), inputs.isEmpty() ? "" : " inputs " + inputs);
        if (change.cause() == HumanPrecedence.Cause.CONTROLLER_LOST) {
            notifyControl("marionette.lockout.dropped", "marionette.lockout.dropped.detail", lockoutKey);
        }
        eventRecorder.control(change.mode().wire(), change.paused(), change.cause().wire(), inputs);
    }

    private void processSafety() {
        if (bridge != null && bridge.pollRelease() != null) releaseControls();
        if (bridge != null && bridge.pollDisconnected()) {
            demo = null;
            releaseControls();
        }
        syncPrecedence();
        report(precedence.tick(heldHumanInputs(), System.nanoTime()));
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
            received.from().recordApplied(received.receivedNanos(), System.nanoTime());
            if (player != null || received.command() instanceof AgentCommand.Configure
                    || received.command() instanceof AgentCommand.Status
                    || received.command() instanceof AgentCommand.InventoryAction
                    || received.command() instanceof AgentCommand.Scan
                    || received.command() instanceof AgentCommand.Respawn
                    || received.command() instanceof AgentCommand.Chat
                    || received.command() instanceof AgentCommand.Release) applyCommand(received, player);
        }
    }

    private void applyCommand(BridgeServer.Received received, LocalPlayer player) {
        // Human-priority pause (M5.1): actuation is discarded or refused; reads,
        // release and configure keep working.
        boolean paused = precedence.agentPaused();
        switch (received.command()) {
            case AgentCommand.InventoryAction inventory -> {
                if (player == null) {
                    received.from().sendReliable(Messages.error(ErrorCode.INVENTORY_UNAVAILABLE, "no_world",
                            "no world is loaded", inventory.id(), inventory.raw()));
                } else if (paused && !inventory.op().equals("inspect")) {
                    received.from().sendReliable(Messages.error(ErrorCode.INVENTORY_UNAVAILABLE, "human_paused",
                            "human input has paused agent control", inventory.id(), inventory.raw()));
                } else {
                    inventoryApplier.apply(inventory, received.from()::sendReliable);
                }
            }
            case AgentCommand.Scan scan -> {
                Minecraft minecraft = Minecraft.getInstance();
                var level = minecraft.level;
                var feet = player == null || level == null ? null : player.blockPosition();
                scans.start(scan, received.from()::sendReliable,
                        feet == null ? null : new int[] {feet.getX(), feet.getY(), feet.getZ()},
                        MarionetteConfig.blockScanRadius, level,
                        level == null ? null : LevelBlockSource.dimension(level));
            }
            case AgentCommand.Respawn respawn -> applyRespawn(respawn, player, received.from()::sendReliable);
            case AgentCommand.Chat chat -> applyChat(chat, player, received.from()::sendReliable);
            case AgentCommand.InputUpdate update when paused -> { }
            case AgentCommand.Look look when paused -> { }
            case AgentCommand.LookDelta delta when paused -> { }
            case AgentCommand.LookSmoothAngles smooth when paused -> { }
            case AgentCommand.LookSmoothPoint smooth when paused -> { }
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
            case AgentCommand.Status status -> received.from().sendReliable(Messages.statusResult(
                    statusReport().forSession(received.from().status(MarionetteConfig.observationRateDivisor,
                            System.nanoTime())), status.id()));
            case AgentCommand.Configure configure -> {
                if (configure.sections() != null) received.from().setSections(configure.sections());
                if (configure.events() != null) received.from().setEvents(configure.events());
                if (configure.rateDivisor() != null) {
                    received.from().setRateDivisor(configure.rateDivisor());
                    logNormal(LogCategory.OBSERVATION, "Observation rate divisor set to {} for a {} session",
                            configure.rateDivisor(), received.from().role());
                }
            }
        }
    }

    /** The death screen's Respawn button (protocol/v1.md, respawn); never automatic. */
    private void applyRespawn(AgentCommand.Respawn respawn, LocalPlayer player, Consumer<String> reply) {
        Minecraft minecraft = Minecraft.getInstance();
        String reason = player == null || minecraft.level == null ? "no_world"
                : precedence.agentPaused() ? "human_paused"
                : !player.isDeadOrDying() ? "not_dead"
                : minecraft.level.getLevelData().isHardcore() ? "hardcore" : null;
        if (reason != null) {
            reply.accept(Messages.error(ErrorCode.RESPAWN_REFUSED, reason, switch (reason) {
                case "no_world" -> "no world is loaded";
                case "not_dead" -> "the player is alive";
                case "human_paused" -> "human input has paused agent control";
                default -> "hardcore worlds offer only spectating";
            }, respawn.id(), respawn.raw()));
            return;
        }
        player.respawn();
        logNormal(LogCategory.CONTROL, "Agent respawn requested");
        reply.accept(Messages.actionResult("respawn", respawn.id()));
    }

    /** Ordinary chat or a command, through vanilla's chat path, within the chat limits. */
    private void applyChat(AgentCommand.Chat chat, LocalPlayer player, Consumer<String> reply) {
        Minecraft minecraft = Minecraft.getInstance();
        int maxMessages = MarionetteConfig.chatMaxMessages;
        ChatPolicy.Decision decision;
        if (player == null || minecraft.getConnection() == null) {
            decision = new ChatPolicy.Decision("no_world", "no world is loaded", null, null, null);
        } else if (precedence.agentPaused()) {
            decision = new ChatPolicy.Decision("human_paused", "human input has paused agent control",
                    null, null, null);
        } else if (!minecraft.getChatStatus().isChatAllowed(minecraft.isLocalServer())) {
            decision = new ChatPolicy.Decision("client_restricted", "the game does not allow chat for this client",
                    null, null, null);
        } else {
            decision = chatPolicy.check(chat.text(), chat.command(), MarionetteConfig.allowChat,
                    MarionetteConfig.allowCommands, maxMessages);
        }
        if (!decision.accepted()) {
            reply.accept(Messages.chatError(decision.reason(), decision.message(), chat.id(), chat.raw(),
                    maxMessages, ChatPolicy.WINDOW_SECONDS, ChatPolicy.MAX_LENGTH, decision.retryAfterMs()));
            return;
        }
        if (decision.action().equals("command")) {
            player.connection.sendCommand(decision.content());
        } else {
            player.connection.sendChat(decision.content());
        }
        logNormal(LogCategory.CONTROL, "Agent {} sent ({} characters)", decision.action(), decision.content().length());
        reply.accept(Messages.actionResult(decision.action(), chat.id()));
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
        logVerbose(LogCategory.CONTROL, String.format("Pan start target yaw=%.3f pitch=%.3f speed=%.1f model=%s",
                target.yaw(), target.pitch(), speed, CappedRateModel.class.getSimpleName()));
    }

    private void executeStunt(Minecraft minecraft, LocalPlayer player, DemoScript.Stunt stunt) {
        switch (stunt) {
            case OPEN_INVENTORY -> {
                minecraft.setScreen(new InventoryScreen(player));
                logNormal(LogCategory.CONTROL, "Demo stunt: opened inventory");
            }
            case CLOSE_SCREEN -> {
                minecraft.setScreen(null);
                logNormal(LogCategory.CONTROL, "Demo stunt: closed screen");
            }
            case NONE -> { }
        }
    }

    /** The safety rule: neutral ControlState, applier released, evidence logged. */
    private void releaseControls() {
        releaseControls("released", "controls released", true);
    }

    /**
     * Release every agent actuator. A human-priority pause keeps a running
     * block scan (it reads, it does not act) and cancels inventory work with
     * reason human_input.
     */
    private void releaseControls(String inventoryReason, String message, boolean cancelScan) {
        inventoryApplier.cancel(inventoryReason, message);
        if (cancelScan) scans.cancel("released", message);
        cameraSmoother.cancel();
        controlState.releaseAll();
        applier.release();
        controlsEngaged = false;
        logNormal(LogCategory.CONTROL, "Controls released ({}); vanilla input restored", message);
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        if (!inWorld) {
            return;
        }
        // ClientTickEvent.Post fires after handleKeybinds in Minecraft.tick:
        // an attack/use tap nothing consumed this tick (screen open) dies
        // here rather than firing when the menu closes later.
        controlState.dropInteractionTaps();
        LocalPlayer tickPlayer = Minecraft.getInstance().player;
        if (tickPlayer != null) eventRecorder.healthObserved(tickPlayer.getHealth()); // entity-data health too
        eventRecorder.tick(); // close damage windows within this tick, before its observation
        ticksInWorld++;
        if (ticksInWorld % TICK_LOG_INTERVAL == 0) {
            logVerbose(LogCategory.CLIENT, "Client tick {} in world", ticksInWorld);
        }
        if (bridge != null && ticksInWorld % TICK_LOG_INTERVAL == 0) {
            long total = bridge.coalescedObservations();
            if (total > reportedCoalesced) {
                logNormal(LogCategory.OBSERVATION, "{} observation frames coalesced for a slow-reading agent ({} total this session)",
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
        tickScan();
        LocalPlayer player = Minecraft.getInstance().player;
        if (controlsEngaged && player != null && ticksInWorld % PUPPET_LOG_INTERVAL == 0) {
            logVerbose(LogCategory.CONTROL, String.format("Puppet pos %.2f %.2f %.2f yaw %.1f",
                    player.getX(), player.getY(), player.getZ(), player.getYRot()));
        }
        if (bridge != null && inWorld && player != null) {
            Minecraft minecraft = Minecraft.getInstance();
            bridge.sendSectionObservation(ticksInWorld, MarionetteConfig.observationRateDivisor, Map.<String, Supplier<JsonObject>>of(
                    "player", () -> PlayerObservation.capture(player),
                    "inventory", () -> InventoryObservation.capture(player, inventoryApplier),
                    "target", () -> TargetObservation.capture(minecraft, player),
                    "world", () -> minecraft.level == null ? null : WorldObservation.capture(minecraft.level, player),
                    "entities", () -> minecraft.level == null ? null : EntityObservation.capture(minecraft.level, player,
                            MarionetteConfig.entityRadius, MarionetteConfig.entityMaxCount)));
        }
    }

    /** Read this tick's portion of an active block scan, before this tick's observation. */
    private void tickScan() {
        if (!scans.active()) return;
        var level = Minecraft.getInstance().level;
        if (level != null && (scanSource == null || scanSource.level() != level)) scanSource = new LevelBlockSource(level);
        long start = System.nanoTime();
        int read = scans.tick(ticksInWorld, MarionetteConfig.blockScanBlocksPerTick, level, scanSource);
        if (read > 0) {
            // Evidence marker for the per-tick work budget (chunking across ticks).
            logVerbose(LogCategory.OBSERVATION, "Block scan read {} blocks at tick {} in {} us{}", read, ticksInWorld,
                    (System.nanoTime() - start) / 1000, scans.active() ? "" : " (complete)");
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
            logVerbose(LogCategory.CONTROL, String.format("Pan yaw=%.3f pitch=%.3f t=%.1f", next.yaw(), next.pitch(), t));
        } else {
            logVerbose(LogCategory.CONTROL, String.format("Pan converged t=%.1f", t));
        }
    }

    private void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        inWorld = true;
        ticksInWorld = 0;
        playerDead = false;
        // A controller attached before joining reports its start state on the first sync.
        precedence.forgetController();
        eventRecorder.startWorldSession(EventRecorder.newWorldSessionId());
        if (Boolean.getBoolean("marionette.demo")) {
            demo = new DemoScript(Boolean.getBoolean("marionette.demo.gui"));
            logNormal(LogCategory.CONTROL, "Demo armed (gui stunts: {})", Boolean.getBoolean("marionette.demo.gui"));
        }
        logNormal(LogCategory.CLIENT, "Entered world");
        if (bridge != null && bridge.panicLatched()) {
            logNormal(LogCategory.PRECEDENCE, "Agent control remains latched off by panic");
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
        syncPrecedence(); // drops an engaged lockout now (local notice; no world left for events)
        inventoryApplier.cancel("world_exit", "left world", false);
        scans.cancel("world_exit", "left world");
        scanSource = null;
        eventRecorder.endWorldSession();
        inWorld = false;
        demo = null;
        if (controlsEngaged) {
            releaseControls();
        }
        logNormal(LogCategory.CLIENT, "Left world");
    }
}
