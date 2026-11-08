package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.world.NoiseFillCeiling;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorFillMixin {

    @ModifyVariable(method = "doFill", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int terrainDiffusion$fillCeiling(int cellCountY,
                                             @Local(argsOnly = true) Blender blender,
                                             @Local(argsOnly = true) StructureManager structureManager,
                                             @Local(argsOnly = true) ChunkAccess chunk,
                                             @Local(argsOnly = true, ordinal = 0) int minCellY) {
        return NoiseFillCeiling.cellCount(((NoiseBasedChunkGenerator) (Object) this).generatorSettings().value(),
                blender, structureManager, chunk, minCellY, cellCountY);
    }
}
