package com.prattlemob.marionette.observation;

import com.google.gson.JsonObject;

/**
 * Minecraft-free builders for the {@code target} observation section
 * (protocol/v1.md, Target section). Every kind carries {@code reach}.
 */
public final class TargetJson {
    private TargetJson() {}

    /** Interaction ranges in blocks, from the player's attributes. */
    public record Reach(double block, double entity) {}

    /** A world-space point. */
    public record Point(double x, double y, double z) {
        double distanceTo(Point other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public static JsonObject none(Reach reach) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "none");
        result.add("reach", reach(reach));
        return result;
    }

    /** {@code face} is the vanilla direction name: down, up, north, south, west or east. */
    public static JsonObject block(int x, int y, int z, String block, String face, Point hit, Point eye, Reach reach) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "block");
        JsonObject pos = new JsonObject();
        pos.addProperty("x", x);
        pos.addProperty("y", y);
        pos.addProperty("z", z);
        result.add("pos", pos);
        result.addProperty("block", block);
        result.addProperty("face", face);
        addHit(result, hit, eye);
        result.add("reach", reach(reach));
        return result;
    }

    public static JsonObject entity(int id, String entity, Point hit, Point eye, Reach reach) {
        JsonObject result = new JsonObject();
        result.addProperty("kind", "entity");
        result.addProperty("id", id);
        result.addProperty("entity", entity);
        addHit(result, hit, eye);
        result.add("reach", reach(reach));
        return result;
    }

    private static void addHit(JsonObject result, Point hit, Point eye) {
        JsonObject point = new JsonObject();
        point.addProperty("x", hit.x());
        point.addProperty("y", hit.y());
        point.addProperty("z", hit.z());
        result.add("hit", point);
        result.addProperty("distance", hit.distanceTo(eye));
    }

    private static JsonObject reach(Reach reach) {
        JsonObject result = new JsonObject();
        result.addProperty("block", reach.block());
        result.addProperty("entity", reach.entity());
        return result;
    }
}
