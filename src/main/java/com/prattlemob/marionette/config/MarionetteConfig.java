package com.prattlemob.marionette.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.prattlemob.marionette.Marionette;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client config schema and cached values (generated as
 * config/marionette-client.toml). Values are copied into plain static
 * volatile fields on config load/reload — NeoForge's file watcher fires
 * Reloading on disk edits, so per-use values behave live — and readers
 * never touch ConfigValue.get(), so headless tests and mixins can read at
 * any time. Port, bind address, and the enable toggle are consumed once at
 * bridge startup and therefore need a restart to take effect.
 */
@EventBusSubscriber(modid = Marionette.MODID, value = Dist.CLIENT)
public final class MarionetteConfig {
    private static final ModConfigSpec.BooleanValue BRIDGE_ENABLED;
    private static final ModConfigSpec.IntValue PORT;
    private static final ModConfigSpec.ConfigValue<String> BIND_ADDRESS;
    private static final ModConfigSpec.IntValue RATE_DIVISOR;
    private static final ModConfigSpec.IntValue ENTITY_RADIUS;
    private static final ModConfigSpec.IntValue ENTITY_MAX_COUNT;
    private static final ModConfigSpec.IntValue BLOCK_SCAN_RADIUS;
    private static final ModConfigSpec.BooleanValue SUPPRESS_PAUSE;
    private static final ModConfigSpec.DoubleValue SMOOTHING_SPEED;
    private static final ModConfigSpec.EnumValue<Verbosity> VERBOSITY;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("bridge");
        BRIDGE_ENABLED = builder
                .comment("Master switch: when false, the WebSocket bridge never starts. (restart required)")
                .define("enabled", true);
        PORT = builder
                .comment("TCP port for the bridge listener. (restart required)")
                .defineInRange("port", 24680, 1, 65535);
        BIND_ADDRESS = builder
                .comment("Bind address. Non-loopback values are ignored and clamped to 127.0.0.1",
                        "with a warning until the explicit opt-out gate ships (M5.1). (restart required)")
                .define("bindAddress", "127.0.0.1");
        builder.pop();
        builder.push("observation");
        RATE_DIVISOR = builder
                .comment("Send one observation frame every N client ticks. (live)",
                        "Agents can override this per session with the `configure` protocol",
                        "message; this config value is the default and is restored on disconnect.")
                .defineInRange("rateDivisor", 1, 1, 100);
        ENTITY_RADIUS = builder
                .comment("Caps for future observation sections (M4.4 entities, M4.5 block scan).",
                        "Defined now so operators see the ceiling; enforced when those ship. (live)")
                .defineInRange("entityRadius", 32, 4, 64);
        ENTITY_MAX_COUNT = builder.defineInRange("entityMaxCount", 64, 1, 256);
        BLOCK_SCAN_RADIUS = builder.defineInRange("blockScanRadius", 16, 4, 32);
        builder.pop();
        builder.push("client");
        SUPPRESS_PAUSE = builder
                .comment("While an agent is connected, suppress the vanilla pause-on-focus-loss so",
                        "the session keeps running and streaming when unfocused. (live)")
                .define("suppressPauseOnLostFocus", true);
        builder.pop();
        builder.push("camera");
        SMOOTHING_SPEED = builder
                .comment("Characteristic speed of smoothed camera pans, in degrees/second. (live)",
                        "Agents scale it per pan with the `speed` multiplier on look mode \"smooth\".")
                .defineInRange("smoothingSpeed", 180.0, 10.0, 1080.0);
        builder.pop();
        builder.push("logging");
        VERBOSITY = builder
                .comment("QUIET: warnings/errors only. NORMAL: lifecycle + connection events.",
                        "VERBOSE: adds per-tick heartbeat and puppet-position evidence logs. (live)")
                .defineEnum("verbosity", Verbosity.NORMAL);
        builder.pop();
        SPEC = builder.build();
    }

    // Cached snapshots; defaults mirror the spec so reads are safe pre-load.
    public static volatile boolean bridgeEnabled = true;
    public static volatile int port = 24680;
    public static volatile String bindAddress = "127.0.0.1";
    public static volatile int observationRateDivisor = 1;
    public static volatile int entityRadius = 32;
    public static volatile int entityMaxCount = 64;
    public static volatile int blockScanRadius = 16;
    public static volatile boolean suppressPauseOnLostFocus = true;
    public static volatile double cameraSmoothingSpeed = 180.0;
    public static volatile Verbosity verbosity = Verbosity.NORMAL;

    private MarionetteConfig() {
    }

    /** True when Marionette should emit logs gated at {@code level}. */
    public static boolean logAt(Verbosity level) {
        return verbosity.atLeast(level);
    }

    /** True when a tick-synced observation should be sent on this tick. */
    public static boolean observationDueAt(long tick) {
        return observationDueAt(tick, observationRateDivisor);
    }

    /** Same, with an explicit divisor (per-session override via configure). */
    public static boolean observationDueAt(long tick, int divisor) {
        return tick % divisor == 0;
    }

    /**
     * Clamp non-loopback (or unresolvable) bind addresses to 127.0.0.1.
     * The explicit "I understand" opt-out for real non-loopback binding is
     * M5.1's loopback-enforcement item (see docs/decisions.md).
     */
    public static String resolveBindAddress(String configured) {
        try {
            if (InetAddress.getByName(configured).isLoopbackAddress()) {
                return configured;
            }
        } catch (UnknownHostException e) {
            // unresolvable — fall through to clamp
        }
        return "127.0.0.1";
    }

    @SubscribeEvent
    static void onLoading(ModConfigEvent.Loading event) {
        copyValues(event);
    }

    @SubscribeEvent
    static void onReloading(ModConfigEvent.Reloading event) {
        copyValues(event);
    }

    private static void copyValues(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        bridgeEnabled = BRIDGE_ENABLED.get();
        port = PORT.get();
        bindAddress = BIND_ADDRESS.get();
        observationRateDivisor = RATE_DIVISOR.get();
        entityRadius = ENTITY_RADIUS.get();
        entityMaxCount = ENTITY_MAX_COUNT.get();
        blockScanRadius = BLOCK_SCAN_RADIUS.get();
        suppressPauseOnLostFocus = SUPPRESS_PAUSE.get();
        cameraSmoothingSpeed = SMOOTHING_SPEED.get();
        verbosity = VERBOSITY.get();
    }
}
