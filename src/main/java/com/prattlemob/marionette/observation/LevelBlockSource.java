package com.prattlemob.marionette.observation;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Block;

/**
 * Client-thread block reads for {@link BlockScan}: block registry ids as the
 * client knows them, null in chunks the client has not loaded. Positions
 * outside the build height read as vanilla reports them ({@code void_air}).
 */
public final class LevelBlockSource implements BlockScan.BlockSource {
    private final ClientLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Map<Block, String> names = new IdentityHashMap<>();

    public LevelBlockSource(ClientLevel level) {
        this.level = level;
    }

    @Override
    public String blockAt(int x, int y, int z) {
        if (!level.isOutsideBuildHeight(y) && !level.getChunkSource().hasChunk(
                SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
            return null;
        }
        Block block = level.getBlockState(pos.set(x, y, z)).getBlock();
        return names.computeIfAbsent(block, b -> BuiltInRegistries.BLOCK.getKey(b).toString());
    }

    public ClientLevel level() {
        return level;
    }

    public static String dimension(ClientLevel level) {
        return level.dimension().location().toString();
    }
}
