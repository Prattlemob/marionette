package com.prattlemob.marionette.observation;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.LightLayer;

/** Client-thread snapshot of the {@code world} section: client-known level state at the feet. */
public final class WorldObservation {
    private WorldObservation() {}

    public static JsonObject capture(ClientLevel level, LocalPlayer player) {
        var feet = player.blockPosition();
        var light = new WorldJson.Light(
                level.getBrightness(LightLayer.BLOCK, feet),
                level.getBrightness(LightLayer.SKY, feet),
                level.getChunkSource().getLightEngine().getRawBrightness(feet, 0),
                level.getMaxLocalRawBrightness(feet));
        return WorldJson.build(level.dimension().location().toString(), level.getDayTime(),
                level.isRaining(), level.isThundering(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F),
                feet.getX(), feet.getY(), feet.getZ(), light);
    }
}
