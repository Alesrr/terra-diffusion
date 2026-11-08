package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerralithSurfaceRules;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$noHighlandSand(FeaturePlaceContext<DiskConfiguration> context,
                                                 CallbackInfoReturnable<Boolean> cir) {
        if (context.origin().getY() < TerralithSurfaceRules.ERODED_FROM_Y) {
            return;
        }
        if (!(context.chunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return;
        }
        BlockState disk = context.config().stateProvider().fallback().getState(RandomSource.create(0L), context.origin());
        if (disk.is(Blocks.SAND) || disk.is(Blocks.RED_SAND) || disk.is(Blocks.CLAY)) {
            cir.setReturnValue(false);
        }
    }
}
