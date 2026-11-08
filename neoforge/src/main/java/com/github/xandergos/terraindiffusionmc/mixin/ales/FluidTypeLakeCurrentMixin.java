package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.StreamsCompat;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = FluidType.class, remap = false)
public abstract class FluidTypeLakeCurrentMixin {

    @ModifyReturnValue(method = "motionScale(Lnet/minecraft/world/entity/Entity;)D", at = @At("RETURN"))
    private double terrainDiffusion$slowLakeMobs(double scale, Entity entity) {
        return scale * StreamsCompat.lakeMobScale((FluidType) (Object) this, entity);
    }
}
