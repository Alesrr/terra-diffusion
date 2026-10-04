package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.SnowFreeBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Shadow
    public abstract float getBaseTemperature();

    @Shadow
    public abstract boolean hasPrecipitation();

    @Inject(method = "warmEnoughToRain", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$ignoreAltitudeForSnow(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (this.getBaseTemperature() >= 0.15F || SnowFreeBiomes.contains((Biome) (Object) this)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void preventHighAltitudeSnow(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        if (!this.hasPrecipitation()) {
            cir.setReturnValue(Biome.Precipitation.NONE);
            return;
        }

        // Base temperature >= 0.15 means this is NOT a snowy biome
        if (this.getBaseTemperature() >= 0.15F || SnowFreeBiomes.contains((Biome) (Object) this)) {
            cir.setReturnValue(Biome.Precipitation.RAIN);
        }
        // For snowy biomes (base temp < 0.15), let vanilla handle it
    }
}
