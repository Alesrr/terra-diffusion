package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.world.StructureSpacing;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGeneratorStructureState.class)
public class ChunkGeneratorStructureStateMixin {
    @Inject(method = "createForNormal", at = @At("HEAD"))
    private static void terrainDiffusion$respace(RandomState randomState, long seed, BiomeSource biomeSource,
                                                 HolderLookup<StructureSet> structureSets,
                                                 CallbackInfoReturnable<ChunkGeneratorStructureState> cir) {
        if (biomeSource instanceof TerrainDiffusionBiomeSource) {
            StructureSpacing.apply(structureSets);
        }
    }
}
