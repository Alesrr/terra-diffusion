package com.github.xandergos.terraindiffusionmc.checks;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.ScaleAwareStructureState;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Native candidates, including frequency/exclusion checks. No biome or terrain viability claim. */
final class DensityMapData {
    static void export(ServerLevel level) throws Exception {
        JsonObject output = new JsonObject();
        output.addProperty("seed", Long.toString(level.getSeed()));
        output.addProperty("markerMeaning", "Native placement candidates before biome and terrain rejection");
        output.addProperty("nativeBlocksPerCoarseCell", 32 * WorldPipelineModelConfig.latentCompression());
        var gson = new GsonBuilder().setPrettyPrinting().create();
        output.add("frequencyMultipliers", gson.toJsonTree(WorldPipelineModelConfig.frequencyMultipliers()));
        JsonObject coarse = new JsonObject();
        int start = -16, end = Integer.getInteger("terradiff.structureTest.coarseEnd", 16), size = end - start, pixels = size * size;
        coarse.addProperty("ci0", start); coarse.addProperty("ci1", end);
        coarse.addProperty("cj0", start); coarse.addProperty("cj1", end);
        var tensor = LocalTerrainProvider.explorerCoarse(start, start, end, end);
        JsonObject channels = new JsonObject();
        String[] names = {"Elev", "P5", "Temp", "Precip", "PET", "Snow"};
        for (int channel = 0; channel < names.length; channel++) {
            JsonArray rows = new JsonArray();
            for (int z = 0; z < size; z++) {
                JsonArray row = new JsonArray();
                for (int x = 0; x < size; x++) {
                    int index = z * size + x;
                    float weight = tensor.data[6 * pixels + index];
                    float value = weight > 1e-8f ? tensor.data[channel * pixels + index] / weight : 0;
                    if (channel < 2) value = Math.signum(value) * value * value;
                    row.add(value);
                }
                rows.add(row);
            }
            channels.add(names[channel], rows);
        }
        coarse.add("channels", channels); output.add("coarse", coarse);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        List<Holder<StructureSet>> definitions = List.of(
                registry.getHolder(ResourceLocation.parse("minecraft:villages")).orElseThrow(),
                registry.getHolder(ResourceLocation.parse("terralith:rare_village")).orElseThrow());
        JsonArray comparisons = new JsonArray();
        for (int scale = 1; scale <= 6; scale++) {
            for (String framing : List.of("regional", "fixed-area")) {
                JsonObject comparison = new JsonObject();
                comparison.addProperty("scale", scale);
                comparison.addProperty("framing", framing);
                int extent = framing.equals("regional") ? 4096 * scale : 8192;
                comparison.addProperty("extentBlocks", extent);
                for (boolean after : new boolean[]{false, true}) {
                    var state = ChunkGeneratorStructureState.createForFlat(level.getChunkSource().randomState(),
                            level.getSeed(), level.getChunkSource().getGenerator().getBiomeSource(), definitions.stream());
                    if (after) ((ScaleAwareStructureState) state).terrainDiffusion$configureStructureScale(scale, level.registryAccess());
                    JsonArray points = new JsonArray();
                    for (int setIndex = 0; setIndex < definitions.size(); setIndex++) {
                        Holder<StructureSet> definition = definitions.get(setIndex);
                        var set = state.possibleStructureSets().stream()
                                .filter(h -> h.value().structures().equals(definition.value().structures())).findFirst().orElseThrow().value();
                        var placement = (RandomSpreadStructurePlacement) set.placement();
                        int minChunk = -extent / 32, maxChunk = extent / 32;
                        int minCell = Math.floorDiv(minChunk, placement.spacing());
                        int maxCell = Math.floorDiv(maxChunk - 1, placement.spacing());
                        for (int cz = minCell; cz <= maxCell; cz++) for (int cx = minCell; cx <= maxCell; cx++) {
                            var candidate = placement.getPotentialStructureChunk(level.getSeed(), cx * placement.spacing(), cz * placement.spacing());
                            if (candidate.x < minChunk || candidate.x >= maxChunk || candidate.z < minChunk || candidate.z >= maxChunk
                                    || !placement.isStructureChunk(state, candidate.x, candidate.z)) continue;
                            JsonArray point = new JsonArray();
                            point.add(candidate.x * 16 + 8); point.add(candidate.z * 16 + 8); point.add(setIndex);
                            points.add(point);
                        }
                    }
                    comparison.add(after ? "after" : "before", points);
                }
                comparisons.add(comparison);
            }
        }
        output.add("comparisons", comparisons);
        Files.writeString(Path.of("density-data.json"), gson.toJson(output));
        System.out.println("DENSITY MAP DATA EXPORTED");
    }
}
