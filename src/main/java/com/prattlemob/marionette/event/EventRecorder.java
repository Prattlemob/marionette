package com.prattlemob.marionette.event;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.prattlemob.marionette.bridge.protocol.GameEvent;
import com.prattlemob.marionette.bridge.protocol.GameEvent.Basis;

/**
 * Turns facts reported by the game into protocol events (protocol/v1.md,
 * Events). Free of Minecraft types so the pairing and lifecycle rules are
 * testable headless; client-thread only.
 *
 * <p>Damage arrives in two parts: a server damage report (source) and,
 * separately, the server-synced health (amount). Health can arrive through
 * the health packet or entity data, so losses are measured against a
 * per-player baseline that starts at the first server health update and is
 * sampled every tick. Reports and losses pair within
 * {@link #DAMAGE_WINDOW_TICKS}; unknown halves are reported as null.
 */
public final class EventRecorder {
    /** Client ticks a damage report waits for its health update. */
    public static final int DAMAGE_WINDOW_TICKS = 20;
    /** Longest text field, in UTF-16 characters. */
    public static final int TEXT_LIMIT = 2048;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final LongSupplier tick;
    private final Consumer<GameEvent> sink;
    private String worldSession;
    private JsonObject pendingSource;
    private long pendingTick;
    private boolean dead;
    /** Last server-synced health of the current player; null until synced. */
    private Float baseline;

    /**
     * @param tick the in-progress client tick: the tick of the next observation
     * @param sink receives every recorded event, in order
     */
    public EventRecorder(LongSupplier tick, Consumer<GameEvent> sink) {
        this.tick = tick;
        this.sink = sink;
    }

    /** A fresh opaque id for each world join. */
    public static String newWorldSessionId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public void startWorldSession(String id) {
        worldSession = id;
        pendingSource = null;
        dead = false;
        baseline = null;
    }

    /** Nothing is recorded outside a world; an unpaired report is discarded with it. */
    public void endWorldSession() {
        worldSession = null;
        pendingSource = null;
        dead = false;
        baseline = null;
    }

    public String worldSession() {
        return worldSession;
    }

    /** True after a death until the next player instance (respawn). */
    public boolean dead() {
        return dead;
    }

    /** Close the damage window; call once per tick before the tick's observation. */
    public void tick() {
        if (pendingSource != null && tick.getAsLong() - pendingTick >= DAMAGE_WINDOW_TICKS) {
            JsonObject source = pendingSource;
            pendingSource = null;
            emitDamage(source, null, null);
        }
    }

    /** The server reported damage to the local player; amount follows separately. */
    public void damageReported(JsonObject source) {
        if (worldSession == null || dead) return;
        if (pendingSource != null) emitDamage(pendingSource, null, null);
        pendingSource = source.deepCopy(); // replaces the one just reported
        pendingTick = tick.getAsLong();
    }

    /**
     * The server's health update packet for the current player instance.
     *
     * @param initial the first update of a new player instance: it establishes
     *        the baseline and is never damage
     */
    public void healthUpdated(float health, boolean initial) {
        if (worldSession == null || dead) return;
        if (initial) baseline = health;
        else healthObserved(health);
    }

    /**
     * The client's current view of server-synced health (per tick, and on
     * every health packet). A drop below the baseline is damage.
     */
    public void healthObserved(float health) {
        if (worldSession == null || dead || baseline == null) return;
        float previous = baseline;
        baseline = health;
        if (health < previous) {
            JsonObject source = pendingSource;
            pendingSource = null;
            emitDamage(source, previous - health, health);
        }
    }

    /**
     * Death ends the current player: any waiting report, and any health not
     * yet seen lost, is reported as damage first (source null if unreported).
     *
     * @param message the death message as plain text; null or empty when none was sent
     */
    public void death(String message) {
        if (worldSession == null || dead) return;
        Float lost = baseline != null && baseline > 0 ? baseline : null;
        if (pendingSource != null || lost != null) {
            JsonObject source = pendingSource;
            pendingSource = null;
            emitDamage(source, lost, lost != null ? 0.0f : null);
        }
        dead = true;
        baseline = null;
        JsonObject fields = new JsonObject();
        fields.addProperty("truncated", addText(fields, "message",
                message == null || message.isEmpty() ? null : message));
        emit("death", Basis.SERVER, fields);
    }

    /**
     * The client replaced its player (respawn packet).
     *
     * @param wasDead the old player was dead, by death event or its own health
     */
    public void playerReplaced(boolean wasDead, String fromDimension, String toDimension) {
        if (worldSession == null) return;
        boolean respawned = wasDead || dead;
        dead = false;
        pendingSource = null;
        baseline = null; // the new player is unsynced until its first health update
        if (respawned) {
            JsonObject fields = new JsonObject();
            fields.addProperty("dimension", toDimension);
            emit("respawn", Basis.SERVER, fields);
        }
        if (fromDimension != null && toDimension != null && !fromDimension.equals(toDimension)) {
            JsonObject fields = new JsonObject();
            fields.addProperty("from", fromDimension);
            fields.addProperty("to", toDimension);
            emit("dimension_change", Basis.SERVER, fields);
        }
    }

    /** @param item item id, or null when the client did not know the collected entity */
    public void itemPickup(String item, int count) {
        if (worldSession == null) return;
        JsonObject fields = new JsonObject();
        fields.addProperty("item", item);
        fields.addProperty("count", count);
        emit("item_pickup", Basis.SERVER, fields);
    }

    /**
     * @param kind "chat", "system" or "action_bar"
     * @param senderName the sender's profile name (playerIdentity), or null
     */
    public void chat(String kind, String text, String sender, String senderName, String chatType) {
        if (worldSession == null) return;
        JsonObject fields = new JsonObject();
        fields.addProperty("kind", kind);
        boolean truncated = addText(fields, "text", text);
        fields.addProperty("sender", sender);
        fields.addProperty("senderName", senderName);
        fields.addProperty("chatType", chatType);
        fields.addProperty("truncated", truncated);
        emit("chat", Basis.SERVER, fields);
    }

    /** A client-predicted block break by the local player. */
    public void blockBroken(String block, int x, int y, int z) {
        if (worldSession == null) return;
        JsonObject fields = new JsonObject();
        fields.addProperty("block", block);
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        fields.add("pos", pos);
        emit("block_broken", Basis.CLIENT, fields);
    }

    /**
     * The local human precedence state changed (humanPrecedence).
     *
     * @param mode wire mode name
     * @param cause wire cause name
     * @param inputs wire input categories, in wire order; empty unless the cause is human input
     */
    public void control(String mode, boolean paused, String cause, List<String> inputs) {
        if (worldSession == null) return;
        JsonObject fields = new JsonObject();
        fields.addProperty("mode", mode);
        fields.addProperty("paused", paused);
        fields.addProperty("cause", cause);
        JsonArray array = new JsonArray();
        inputs.forEach(array::add);
        fields.add("inputs", array);
        emit("control", Basis.CLIENT, fields);
    }

    /** A damage source object; null ids are unknown or absent. */
    public static JsonObject source(String type, String attacker, String direct) {
        return source(type, attacker, direct, null, null);
    }

    /**
     * A damage source with the responsible player's identity (playerIdentity):
     * {@code attackerPlayer} is {uuid, name} when both are known, else null.
     */
    public static JsonObject source(String type, String attacker, String direct,
                                    String attackerUuid, String attackerName) {
        JsonObject source = new JsonObject();
        source.addProperty("type", type);
        source.addProperty("attacker", attacker);
        source.addProperty("direct", direct);
        if (attackerUuid != null && attackerName != null) {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", attackerUuid);
            player.addProperty("name", attackerName);
            source.add("attackerPlayer", player);
        } else {
            source.add("attackerPlayer", JsonNull.INSTANCE);
        }
        return source;
    }

    private void emitDamage(JsonObject source, Float amount, Float health) {
        JsonObject fields = new JsonObject();
        fields.add("source", source == null ? JsonNull.INSTANCE : source);
        fields.addProperty("amount", amount);
        fields.addProperty("health", health);
        emit("damage", Basis.SERVER, fields);
    }

    /** @return true if the text was cut to {@link #TEXT_LIMIT} */
    private static boolean addText(JsonObject fields, String name, String text) {
        boolean truncated = text != null && text.length() > TEXT_LIMIT;
        fields.addProperty(name, truncated ? text.substring(0, TEXT_LIMIT) : text);
        return truncated;
    }

    private void emit(String kind, Basis basis, JsonObject fields) {
        sink.accept(new GameEvent(kind, worldSession, tick.getAsLong(), basis, fields));
    }
}
