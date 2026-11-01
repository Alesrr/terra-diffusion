package com.github.xandergos.terraindiffusionmc.checks;

import com.github.xandergos.terraindiffusionmc.world.ScaleAwareStructureState;
import com.github.xandergos.terraindiffusionmc.world.ScaledStructureSets;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exits before normal spawn generation; optional start checks use detached chunks. */
@EventBusSubscriber(modid = "terra_diffusion_compat")
public final class StructureChecks {
    @SubscribeEvent
    public static void configure(ServerAboutToStartEvent event) {
        if (Boolean.getBoolean("terradiff.structureTest")) {
            WorldScaleSelectionState.setPendingScale(Integer.getInteger("terradiff.structureTest.scale", 4));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void check(LevelEvent.Load event) {
        if (!Boolean.getBoolean("terradiff.structureTest") || !(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) return;
        try {
            run(level);
            if (Boolean.getBoolean("terradiff.structureTest.maps")) DensityMapData.export(level);
            if (Boolean.getBoolean("terradiff.structureTest.validVillages")) ValidVillageMapData.export(level);
            Files.writeString(Path.of("structure-checks.txt"), "PASS: lifecycle, tags, native locate index, exclusion grid, registry isolation\n");
            System.out.println("STRUCTURE CHECKS PASSED");
            // No normal shutdown/save: the fixture deliberately stops inside world construction.
            Runtime.getRuntime().halt(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            Runtime.getRuntime().halt(1);
        }
    }

    private static void run(ServerLevel level) {
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        Holder<StructureSet> villages = registry.getHolder(ResourceLocation.parse("minecraft:villages")).orElseThrow();
        Holder<StructureSet> rare = registry.getHolder(ResourceLocation.parse("terralith:rare_village")).orElseThrow();
        int scale = Integer.getInteger("terradiff.structureTest.scale", 4);
        int expectedFactor = Boolean.getBoolean("terradiff.structureTest.enabled") ? scale : 1;
        var originalPlacement = (RandomSpreadStructurePlacement) villages.value().placement();
        var runtimeSets = level.getChunkSource().getGeneratorState().possibleStructureSets();
        var runtimeVillage = runtimeSets.stream().filter(h -> h.value().structures().equals(villages.value().structures()))
                .findFirst().orElseThrow().value();
        require(((RandomSpreadStructurePlacement) runtimeVillage.placement()).spacing()
                == originalPlacement.spacing() * expectedFactor, "real lifecycle scale");
        require(villages.is(ScaledStructureSets.SCALE_WITH_TERRAIN)
                && rare.is(ScaledStructureSets.SCALE_WITH_TERRAIN), "both village grids tagged");
        require(originalPlacement.spacing() == 34, "registry unchanged");

        var plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        List<Holder<StructureSet>> originals = List.of(villages, rare);
        // A bounded set avoids scheduling stronghold biome searches/inference in this placement check.
        var normal = ChunkGeneratorStructureState.createForFlat(null, 42, new FixedBiomeSource(plains), originals.stream());
        var state = ChunkGeneratorStructureState.createForFlat(null, 42, new FixedBiomeSource(plains), originals.stream());
        var structure = villages.value().structures().stream().filter(e -> e.structure().value().biomes().contains(plains))
                .findFirst().orElseThrow().structure();
        require(state.getPlacementsForStructure(structure).contains(originalPlacement), "early locate index");
        ((ScaleAwareStructureState) state).terrainDiffusion$configureStructureScale(scale,
                level.registryAccess());
        var scaled = (RandomSpreadStructurePlacement) state.getPlacementsForStructure(structure).getFirst();
        require(scaled.spacing() == 34 * scale, "locate index updated");
        require(normal.getPlacementsForStructure(structure).contains(originalPlacement), "other generator unchanged");
        ((ScaleAwareStructureState) state).terrainDiffusion$configureStructureScale(scale,
                level.registryAccess());
        require(state.getPlacementsForStructure(structure).getFirst() == scaled, "idempotence");
        boolean distinguishable = false;
        for (int x = -8; x <= 8; x++) {
            var candidate = scaled.getPotentialStructureChunk(42, x * scaled.spacing(), -scaled.spacing());
            require(state.hasStructureChunkInRange(villages, candidate.x, candidate.z, 0), "scaled exclusion target");
            if (!normal.hasStructureChunkInRange(villages, candidate.x, candidate.z, 0)) distinguishable = true;
        }
        require(scale == 1 || distinguishable, "exclusion does not use old grid");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
