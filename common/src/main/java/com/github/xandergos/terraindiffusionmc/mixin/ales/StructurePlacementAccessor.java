package com.github.xandergos.terraindiffusionmc.mixin.ales;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Invoker("locateOffset")
    Vec3i terrainDiffusion$locateOffset();

    @Invoker("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod terrainDiffusion$frequencyReductionMethod();

    @Invoker("salt")
    int terrainDiffusion$salt();

    @Invoker("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> terrainDiffusion$exclusionZone();
}
