package com.prattlemob.marionette.observation;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Minecraft-free builders for the {@code entities} observation section
 * (protocol/v1.md, Entities section): hostility classification, nearest-first
 * truncation and entry JSON.
 */
public final class EntityJson {
    private EntityJson() {}

    public static final String HOSTILE = "hostile";
    public static final String NEUTRAL = "neutral";
    public static final String PASSIVE = "passive";
    public static final String PLAYER = "player";
    public static final String ITEM = "item";
    public static final String OTHER = "other";

    public static final String YES = "yes";
    public static final String NO = "no";
    public static final String UNKNOWN = "unknown";

    /**
     * Vanilla types classified by table rather than by class: these attack only
     * when provoked or under conditions, although several are {@code Enemy}
     * subclasses (spiders, piglins) or plain animals (goats, llamas, pandas, dolphins).
     */
    public static final Set<String> VANILLA_NEUTRAL = Set.of(
            "minecraft:bee", "minecraft:cave_spider", "minecraft:dolphin", "minecraft:enderman",
            "minecraft:goat", "minecraft:iron_golem", "minecraft:llama", "minecraft:panda",
            "minecraft:piglin", "minecraft:polar_bear", "minecraft:spider", "minecraft:trader_llama",
            "minecraft:wolf", "minecraft:zombified_piglin");

    /** The class markers the classification falls back to. */
    public record Traits(boolean player, boolean item, boolean mob, boolean neutralMob,
                         boolean enemy, boolean monsterCategory) {}

    /** The protocol's precedence: player, item, vanilla table, then class markers. */
    public static String hostility(String type, Traits traits) {
        if (traits.player()) return PLAYER;
        if (traits.item()) return ITEM;
        if (VANILLA_NEUTRAL.contains(type)) return NEUTRAL;
        if (traits.neutralMob()) return NEUTRAL;
        if (traits.enemy() || traits.mob() && traits.monsterCategory()) return HOSTILE;
        return traits.mob() ? PASSIVE : OTHER;
    }

    /**
     * {@code targetingMe} from an entity's synchronized attack target: {@code 0}
     * means none is synchronized now, which says nothing about its real target.
     */
    public static String targeting(int syncedTargetId, int myId) {
        if (syncedTargetId == 0) return UNKNOWN;
        return syncedTargetId == myId ? YES : NO;
    }

    /** The nearest {@code maxCount} of {@code inRange}, ascending by distance then id. */
    public static <T> List<T> nearest(List<T> inRange, int maxCount, ToDoubleFunction<T> distance, ToIntFunction<T> id) {
        return inRange.stream()
                .sorted(Comparator.comparingDouble(distance).thenComparingInt(id))
                .limit(maxCount)
                .toList();
    }

    public static JsonObject entry(int id, String type, String hostility, double x, double y, double z,
                                   double vx, double vy, double vz, double distance) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("type", type);
        entry.addProperty("hostility", hostility);
        entry.addProperty("x", x);
        entry.addProperty("y", y);
        entry.addProperty("z", z);
        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", vx);
        velocity.addProperty("y", vy);
        velocity.addProperty("z", vz);
        entry.add("velocity", velocity);
        entry.addProperty("distance", distance);
        return entry;
    }

    public static void addLiving(JsonObject entry, float health, float maxHealth, String targetingMe) {
        entry.addProperty("health", health);
        entry.addProperty("maxHealth", maxHealth);
        entry.addProperty("targetingMe", targetingMe);
    }

    public static void addItem(JsonObject entry, String item, int count) {
        JsonObject stack = new JsonObject();
        stack.addProperty("item", item);
        stack.addProperty("count", count);
        entry.add("item", stack);
    }

    /** {@code nearby} must already be the nearest-first selection of {@code total} in-range entities. */
    public static JsonObject section(int radius, int maxCount, int total, List<JsonObject> nearby) {
        JsonObject result = new JsonObject();
        result.addProperty("radius", radius);
        result.addProperty("maxCount", maxCount);
        result.addProperty("total", total);
        result.addProperty("truncated", total > maxCount);
        JsonArray entries = new JsonArray();
        nearby.forEach(entries::add);
        result.add("nearby", entries);
        return result;
    }
}
