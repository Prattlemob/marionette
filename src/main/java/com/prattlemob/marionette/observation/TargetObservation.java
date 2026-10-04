package com.prattlemob.marionette.observation;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-thread snapshot of the {@code target} section: vanilla's own crosshair
 * pick ({@link Minecraft#hitResult}, recomputed every rendered frame). No ray is cast here.
 */
public final class TargetObservation {
    private TargetObservation() {}

    public static JsonObject capture(Minecraft minecraft, LocalPlayer player) {
        var reach = new TargetJson.Reach(player.blockInteractionRange(), player.entityInteractionRange());
        HitResult hit = minecraft.hitResult;
        Entity camera = minecraft.getCameraEntity() != null ? minecraft.getCameraEntity() : player;
        Vec3 eye = camera.getEyePosition(1.0F);
        var eyePoint = new TargetJson.Point(eye.x, eye.y, eye.z);
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
                && minecraft.level != null) {
            var pos = block.getBlockPos();
            String id = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(pos).getBlock()).toString();
            return TargetJson.block(pos.getX(), pos.getY(), pos.getZ(), id,
                    block.getDirection().getSerializedName(), point(block.getLocation()), eyePoint, reach);
        }
        if (hit instanceof EntityHitResult entity && entity.getType() == HitResult.Type.ENTITY) {
            Entity target = entity.getEntity();
            return TargetJson.entity(target.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                    point(entity.getLocation()), eyePoint, reach);
        }
        return TargetJson.none(reach);
    }

    private static TargetJson.Point point(Vec3 vec) {
        return new TargetJson.Point(vec.x, vec.y, vec.z);
    }
}
