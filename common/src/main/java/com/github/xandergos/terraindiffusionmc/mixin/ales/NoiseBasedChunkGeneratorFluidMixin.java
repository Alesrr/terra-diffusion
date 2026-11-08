package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.TerrainWater;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorFluidMixin {

    private static final Logger LOG =
            LoggerFactory.getLogger(NoiseBasedChunkGeneratorFluidMixin.class);

    @Inject(method = "createFluidPicker", at = @At("RETURN"), cancellable = true)
    private static void terrainDiffusion$inlandWater(
            NoiseGeneratorSettings settings,
            CallbackInfoReturnable<Aquifer.FluidPicker> cir) {

        DensityFunction density = settings.noiseRouter().finalDensity();
        while (density instanceof DensityFunctions.HolderHolder holder) {
            density = holder.function().value();
        }
        if (!(density instanceof TerrainDiffusionDensityFunction)) {
            return;
        }

        LOG.info("Terrain diffusion fluid picker installed (pipeline water, no aquifer lava)");
        cir.setReturnValue(TerrainWater.fluidPicker(settings.seaLevel(), settings.defaultFluid()));
    }
}
