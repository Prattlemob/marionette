package com.prattlemob.marionette.event;

import com.prattlemob.marionette.MarionetteClient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-thread adapters from vanilla packets and hooks to the
 * {@link EventRecorder}. Called by the event mixins after the packet has
 * been rescheduled onto the client thread; client-only.
 */
public final class MinecraftEvents {
    private MinecraftEvents() {}

    private static EventRecorder recorder() {
        MarionetteClient client = MarionetteClient.instance();
        return client == null ? null : client.eventRecorder();
    }

    private static boolean isLocalPlayer(int entityId) {
        var player = Minecraft.getInstance().player;
        return player != null && player.getId() == entityId;
    }

    public static void damage(ClientboundDamageEventPacket packet) {
        EventRecorder recorder = recorder();
        var level = Minecraft.getInstance().level;
        if (recorder == null || level == null || !isLocalPlayer(packet.entityId())) return;
        DamageSource source = packet.getSource(level);
        Player attacker = source.getEntity() instanceof Player player ? player : null;
        recorder.damageReported(EventRecorder.source(key(source.typeHolder()),
                entityType(source.getEntity()), entityType(source.getDirectEntity()),
                attacker == null ? null : attacker.getUUID().toString(),
                attacker == null ? null : attacker.getGameProfile().getName()));
    }

    public static void health(float health, boolean initial) {
        EventRecorder recorder = recorder();
        if (recorder != null) recorder.healthUpdated(health, initial);
    }

    public static void death(ClientboundPlayerCombatKillPacket packet) {
        EventRecorder recorder = recorder();
        var player = Minecraft.getInstance().player;
        if (recorder == null || player == null || player.getId() != packet.playerId()) return;
        recorder.death(packet.message().getString());
    }

    /** Before vanilla shrinks or removes the collected entity. */
    public static void pickup(ClientboundTakeItemEntityPacket packet) {
        EventRecorder recorder = recorder();
        var level = Minecraft.getInstance().level;
        if (recorder == null || level == null || !isLocalPlayer(packet.getPlayerId())) return;
        Entity entity = level.getEntity(packet.getItemId());
        if (entity instanceof ExperienceOrb) return;
        ItemStack stack = switch (entity) {
            case ItemEntity item -> item.getItem();
            case AbstractArrow arrow -> arrow.getPickupItemStackOrigin();
            case null, default -> ItemStack.EMPTY;
        };
        recorder.itemPickup(stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                packet.getAmount());
    }

    public static void blockBroken(BlockState state, BlockPos pos) {
        EventRecorder recorder = recorder();
        if (recorder != null) {
            recorder.blockBroken(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /** The profile name the client's player list shows for {@code sender}, or null. */
    public static String playerName(java.util.UUID sender) {
        var connection = Minecraft.getInstance().getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(sender);
        return info == null ? null : info.getProfile().getName();
    }

    public static String dimension(Level level) {
        return level == null ? null : level.dimension().location().toString();
    }

    private static String key(Holder<?> holder) {
        return holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
    }

    private static String entityType(Entity entity) {
        return entity == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }
}
