package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.List;

public final class NoiseFillCeiling {

    private static final ThreadLocal<long[]> SIGN_ONLY = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});

    private NoiseFillCeiling() {
    }

    public static int cellCount(NoiseGeneratorSettings settings, Blender blender, StructureManager structures,
                                ChunkAccess chunk, int minCellY, int cellCountY) {
        long[] signOnly = SIGN_ONLY.get();
        signOnly[0] = Long.MIN_VALUE;
        if (blender != Blender.empty() || !terrainDiffusion(settings)) {
            return cellCountY;
        }
        List<StructureStart> starts = structures.startsForStructure(chunk.getPos(),
                structure -> structure.terrainAdaptation() != TerrainAdjustment.NONE);
        if (starts.isEmpty()) {
            signOnly[0] = chunk.getPos().toLong();
        }
        int top = TerrainWater.highestFilled(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ(), settings.seaLevel());
        if (top == Integer.MAX_VALUE) {
            return cellCountY;
        }
        for (StructureStart start : starts) {
            top = Math.max(top, start.getBoundingBox().maxY() + Beardifier.BEARD_KERNEL_RADIUS);
        }
        int cells = Math.floorDiv(top, settings.noiseSettings().getCellHeight()) - minCellY + 1;
        return Math.max(0, Math.min(cellCountY, cells));
    }

    // True while this thread fills a chunk where only the density sign decides blocks
    static boolean signOnly(int x, int z) {
        return SIGN_ONLY.get()[0] == ChunkPos.asLong(x >> 4, z >> 4);
    }

    private static boolean terrainDiffusion(NoiseGeneratorSettings settings) {
        DensityFunction density = settings.noiseRouter().finalDensity();
        while (density instanceof DensityFunctions.HolderHolder holder) {
            density = holder.function().value();
        }
        return density instanceof TerrainDiffusionDensityFunction;
    }
}
