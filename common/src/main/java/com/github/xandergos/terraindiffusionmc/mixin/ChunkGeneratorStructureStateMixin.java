package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.ScaleAwareStructureState;
import com.github.xandergos.terraindiffusionmc.world.ScaledStructureSets;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import java.util.Map;

@Mixin(ChunkGeneratorStructureState.class)
public abstract class ChunkGeneratorStructureStateMixin implements ScaleAwareStructureState {
    @Shadow @Final @Mutable private List<Holder<StructureSet>> possibleStructureSets;
    @Shadow @Final private Map<Structure, List<StructurePlacement>> placementsForStructure;
    @Unique private ScaledStructureSets terrainDiffusion$scaledSets;

    @Override
    public void terrainDiffusion$configureStructureScale(int scale, RegistryAccess registries) {
        if (terrainDiffusion$scaledSets != null) return;
        // Called on the loading thread after the saved scale is read, before chunk generation.
        // A different mod may already have requested the locate index: update it as well.
        // Exclusion targets need the same grid even if their own biomes are absent here.
        var definitions = registries.registryOrThrow(Registries.STRUCTURE_SET).holders()
                .map(holder -> (Holder<StructureSet>) holder).toList();
        terrainDiffusion$scaledSets = new ScaledStructureSets(possibleStructureSets, definitions, scale,
                RegistryOps.create(JsonOps.INSTANCE, registries));
        possibleStructureSets = terrainDiffusion$scaledSets.sets();
        placementsForStructure.replaceAll((structure, placements) -> placements.stream()
                .map(terrainDiffusion$scaledSets::resolve).toList());
    }

    @ModifyVariable(method = "hasStructureChunkInRange", at = @At("HEAD"), argsOnly = true)
    private Holder<StructureSet> terrainDiffusion$resolveExclusion(Holder<StructureSet> original) {
        return terrainDiffusion$scaledSets == null ? original : terrainDiffusion$scaledSets.resolve(original);
    }
}
