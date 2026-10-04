package com.prattlemob.marionette.observation;

import java.util.Comparator;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

/** Client-thread-only snapshot of the player's client-visible state. */
public final class PlayerObservation {
    private PlayerObservation() {}

    public static JsonObject capture(LocalPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        var delta = player.getDeltaMovement();
        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", delta.x);
        velocity.addProperty("y", delta.y);
        velocity.addProperty("z", delta.z);
        result.add("velocity", velocity);
        result.addProperty("health", player.getHealth());
        result.addProperty("maxHealth", player.getMaxHealth());
        result.addProperty("hunger", player.getFoodData().getFoodLevel());
        result.addProperty("saturation", player.getFoodData().getSaturationLevel());
        result.addProperty("air", player.getAirSupply());
        result.addProperty("maxAir", player.getMaxAirSupply());
        JsonObject xp = new JsonObject();
        xp.addProperty("level", player.experienceLevel);
        xp.addProperty("progress", player.experienceProgress);
        xp.addProperty("total", player.totalExperience);
        result.add("xp", xp);
        result.addProperty("onGround", player.onGround());
        result.addProperty("inWater", player.isInWater());
        result.addProperty("sneaking", player.isCrouching());
        result.addProperty("sprinting", player.isSprinting());
        result.addProperty("sleeping", player.isSleeping());
        result.addProperty("onFire", player.isOnFire());
        JsonArray effects = new JsonArray();
        player.getActiveEffects().stream().sorted(Comparator.comparing(
                effect -> BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString()))
                .forEach(effect -> {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString());
                    entry.addProperty("duration", effect.getDuration());
                    entry.addProperty("amplifier", effect.getAmplifier());
                    effects.add(entry);
                });
        result.add("effects", effects);
        // playerIdentity: the local player's profile as this world knows it.
        result.addProperty("uuid", player.getUUID().toString());
        result.addProperty("name", player.getGameProfile().getName());
        // playerActivity: what the gameplay controls (M3.7) visibly do.
        result.addProperty("swimming", player.isSwimming());
        result.addProperty("fallFlying", player.isFallFlying());
        result.addProperty("blocking", player.isBlocking());
        if (player.isUsingItem()) {
            JsonObject using = new JsonObject();
            using.addProperty("hand", player.getUsedItemHand() == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand");
            using.addProperty("item", BuiltInRegistries.ITEM.getKey(player.getUseItem().getItem()).toString());
            using.addProperty("ticks", player.getTicksUsingItem());
            result.add("usingItem", using);
        } else {
            result.add("usingItem", JsonNull.INSTANCE);
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            JsonObject ridden = new JsonObject();
            ridden.addProperty("id", vehicle.getId());
            ridden.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString());
            result.add("vehicle", ridden);
        } else {
            result.add("vehicle", JsonNull.INSTANCE);
        }
        return result;
    }
}
