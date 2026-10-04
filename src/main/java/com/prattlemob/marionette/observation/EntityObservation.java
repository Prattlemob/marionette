package com.prattlemob.marionette.observation;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * Client-thread snapshot of the {@code entities} section: the client's loaded
 * entities within {@code radius} of the player's feet, nearest first, at most
 * {@code maxCount}. Only client-visible state is read.
 */
public final class EntityObservation {
    private EntityObservation() {}

    private record InRange(Entity entity, double distance) {}

    public static JsonObject capture(ClientLevel level, LocalPlayer player, int radius, int maxCount) {
        double radiusSquared = (double) radius * radius;
        List<InRange> inRange = new ArrayList<>();
        for (Entity entity : level.getEntities(player, new AABB(player.blockPosition()).inflate(radius + 1),
                entity -> !(entity instanceof PartEntity<?>))) {
            double distanceSquared = entity.distanceToSqr(player.getX(), player.getY(), player.getZ());
            if (distanceSquared <= radiusSquared) inRange.add(new InRange(entity, Math.sqrt(distanceSquared)));
        }
        List<JsonObject> nearby = EntityJson.nearest(inRange, maxCount, InRange::distance, e -> e.entity().getId())
                .stream().map(e -> entry(e.entity(), e.distance(), player)).toList();
        return EntityJson.section(radius, maxCount, inRange.size(), nearby);
    }

    private static JsonObject entry(Entity entity, double distance, LocalPlayer player) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        JsonObject entry = EntityJson.entry(entity.getId(), type, hostility(type, entity),
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo, distance);
        if (entity instanceof LivingEntity living) {
            EntityJson.addLiving(entry, living.getHealth(), living.getMaxHealth(), targetingMe(living, player));
        }
        if (entity instanceof Player other) {
            entry.addProperty("name", other.getGameProfile().getName());
            entry.addProperty("uuid", other.getUUID().toString()); // playerIdentity
        }
        if (entity instanceof ItemEntity item) {
            var stack = item.getItem();
            EntityJson.addItem(entry, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
        }
        return entry;
    }

    static String hostility(String type, Entity entity) {
        return EntityJson.hostility(type, new EntityJson.Traits(entity instanceof Player, entity instanceof ItemEntity,
                entity instanceof Mob, entity instanceof NeutralMob, entity instanceof Enemy,
                entity.getType().getCategory() == MobCategory.MONSTER));
    }

    /** Only entities that synchronize their attack target can answer; see protocol/v1.md. */
    private static String targetingMe(LivingEntity entity, LocalPlayer player) {
        if (entity instanceof Guardian guardian) {
            if (!guardian.hasActiveAttackTarget()) return EntityJson.UNKNOWN;
            // A synchronized target that is not loaded here cannot be the local player.
            return guardian.getActiveAttackTarget() == player ? EntityJson.YES : EntityJson.NO;
        }
        if (entity instanceof WitherBoss wither) return EntityJson.targeting(wither.getAlternativeTarget(0), player.getId());
        return EntityJson.UNKNOWN;
    }
}
