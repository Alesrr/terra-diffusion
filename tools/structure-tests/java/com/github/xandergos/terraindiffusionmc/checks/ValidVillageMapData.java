package com.github.xandergos.terraindiffusionmc.checks;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel;
import com.github.xandergos.terraindiffusionmc.world.ScaleAwareStructureState;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runs the real STRUCTURE_STARTS pipeline in detached ProtoChunks, without populating a world. */
final class ValidVillageMapData {
    private record Site(int set, ChunkPos chunk) {}

    static void export(ServerLevel level) throws Exception {
        var gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        var source = JsonParser.parseString(Files.readString(Path.of("density-data.json"))).getAsJsonObject();
        var coarse = source.getAsJsonObject("coarse");
        var elevation = coarse.getAsJsonObject("channels").getAsJsonArray("Elev");
        var temperature = coarse.getAsJsonObject("channels").getAsJsonArray("Temp");
        // Select the most land-dominated, mild, low-elevation 8x8 coarse-cell window.
        // This selection never consults village positions or either before/after result.
        int bestX = 0, bestZ = 0;
        double bestScore = -1;
        for (int z = 2; z <= elevation.size()-10; z++) for (int x = 2; x <= elevation.size()-10; x++) {
            double score = 0;
            for (int dz = 0; dz < 8; dz++) for (int dx = 0; dx < 8; dx++) {
                double e = elevation.get(z+dz).getAsJsonArray().get(x+dx).getAsDouble();
                double t = temperature.get(z+dz).getAsJsonArray().get(x+dx).getAsDouble();
                if (e > 0) score += 100;
                if (e > 0 && e < 1800) score += 20;
                if (t > 3 && t < 25) score += 1;
            }
            if (score > bestScore) { bestScore = score; bestX = x; bestZ = z; }
        }
        int cell = source.get("nativeBlocksPerCoarseCell").getAsInt();
        int centerX = (coarse.get("cj0").getAsInt()+bestX+4)*cell;
        int centerZ = (coarse.get("ci0").getAsInt()+bestZ+4)*cell;
        int nativeExtent = 8*cell;
        String region = System.getProperty("terradiff.structureTest.villageRegion", "");
        if (!region.isBlank()) {
            String[] values = region.split(",");
            if (values.length != 3) throw new IllegalArgumentException("Region must be native centerX,centerZ,extent");
            centerX = Integer.parseInt(values[0]); centerZ = Integer.parseInt(values[1]);
            nativeExtent = Integer.parseInt(values[2]);
            if (nativeExtent <= 0 || nativeExtent % 32 != 0 || centerX % 16 != 0 || centerZ % 16 != 0)
                throw new IllegalArgumentException("Use a positive multiple-of-32 extent and chunk-aligned center");
        }
        if (centerX-nativeExtent/2 < coarse.get("cj0").getAsInt()*cell
                || centerX+nativeExtent/2 > coarse.get("cj1").getAsInt()*cell
                || centerZ-nativeExtent/2 < coarse.get("ci0").getAsInt()*cell
                || centerZ+nativeExtent/2 > coarse.get("ci1").getAsInt()*cell)
            throw new IllegalArgumentException("The terrain backdrop must cover the complete chosen region");
        var output = source.deepCopy();
        output.addProperty("markerMeaning", "Valid native village structure starts, including biome, terrain, weighted selection, jigsaw layout and fork veto checks");
        output.addProperty("validStarts", true);
        output.addProperty("regionSelection", region.isBlank()
                ? "Highest land/lowland/mild-climate score; village results not consulted"
                : "Explicit village-bearing land region; same native bounds before/after at every scale");
        output.addProperty("centerNativeX", centerX);
        output.addProperty("centerNativeZ", centerZ);
        output.addProperty("nativeExtentBlocks", nativeExtent);
        int land = 0, samples = 0;
        for (int z = 0; z < elevation.size(); z++) for (int x = 0; x < elevation.get(z).getAsJsonArray().size(); x++) {
            int nx = (coarse.get("cj0").getAsInt()+x)*cell+cell/2;
            int nz = (coarse.get("ci0").getAsInt()+z)*cell+cell/2;
            if (nx >= centerX-nativeExtent/2 && nx < centerX+nativeExtent/2
                    && nz >= centerZ-nativeExtent/2 && nz < centerZ+nativeExtent/2) {
                samples++;
                if (elevation.get(z).getAsJsonArray().get(x).getAsDouble() > 0) land++;
            }
        }
        output.addProperty("coarseLandShare", land/(double)samples);
        if (land/(double)samples < .8) throw new IllegalArgumentException("Choose a region with at least 80% coarse land");
        output.addProperty("inferenceBackend", OnnxModel.getResolvedInferenceProvider());
        JsonArray comparisons = new JsonArray();
        output.add("comparisons", comparisons);
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        List<Holder<StructureSet>> definitions = List.of(
                registry.getHolder(ResourceLocation.parse("minecraft:villages")).orElseThrow(),
                registry.getHolder(ResourceLocation.parse("terralith:rare_village")).orElseThrow());
        var generator = level.getChunkSource().getGenerator();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int previousScale = WorldScaleManager.getCurrentScale();
        var checkpoint = Path.of("valid-village-progress.json");
        var completed = Files.exists(checkpoint) ? JsonParser.parseString(Files.readString(checkpoint)).getAsJsonObject() : new JsonObject();
        try {
            for (int scale = 1; scale <= 6; scale++) {
                WorldScaleManager.setCurrentScale(level, scale);
                LocalTerrainProvider.clearCache();
                var type = level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                        .get(ResourceLocation.parse("terrain-diffusion-mc:terrain_diffusion_scale_"+scale));
                var height = LevelHeightAccessor.create(type.minY(), type.height());
                int extent = nativeExtent * scale;
                int minX = (centerX*scale-extent/2)/16, maxX = (centerX*scale+extent/2)/16;
                int minZ = (centerZ*scale-extent/2)/16, maxZ = (centerZ*scale+extent/2)/16;
                var states = new ChunkGeneratorStructureState[2][2];
                Map<Site, Integer> sites = new HashMap<>();
                for (int mode = 0; mode < 2; mode++) for (int setIndex = 0; setIndex < 2; setIndex++) {
                    var state = ChunkGeneratorStructureState.createForFlat(level.getChunkSource().randomState(),
                            level.getSeed(), generator.getBiomeSource(), java.util.stream.Stream.of(definitions.get(setIndex)));
                    if (mode == 1) ((ScaleAwareStructureState) state).terrainDiffusion$configureStructureScale(scale, level.registryAccess());
                    states[mode][setIndex] = state;
                    if (state.possibleStructureSets().isEmpty()) continue;
                    var placement = (RandomSpreadStructurePlacement) state.possibleStructureSets().getFirst().value().placement();
                    for (int z = Math.floorDiv(minZ, placement.spacing()); z <= Math.floorDiv(maxZ-1, placement.spacing()); z++)
                        for (int x = Math.floorDiv(minX, placement.spacing()); x <= Math.floorDiv(maxX-1, placement.spacing()); x++) {
                            var candidate = placement.getPotentialStructureChunk(level.getSeed(), x*placement.spacing(), z*placement.spacing());
                            if (candidate.x < minX || candidate.x >= maxX || candidate.z < minZ || candidate.z >= maxZ
                                    || !placement.isStructureChunk(state, candidate.x, candidate.z)) continue;
                            sites.merge(new Site(setIndex, candidate), 1 << mode, (a,b) -> a|b);
                        }
                }
                var ordered = new ArrayList<>(sites.keySet());
                ordered.sort(Comparator.comparingInt((Site s) -> Math.floorDiv(s.chunk.z, 16))
                        .thenComparingInt(s -> Math.floorDiv(s.chunk.x,16)).thenComparingInt(s -> s.chunk.z).thenComparingInt(s -> s.chunk.x));
                var comparison = new JsonObject();
                comparison.addProperty("scale", scale); comparison.addProperty("framing", "regional");
                comparison.addProperty("extentBlocks", extent);
                comparison.addProperty("centerX", centerX*scale); comparison.addProperty("centerZ", centerZ*scale);
                var before = new JsonArray(); var after = new JsonArray();
                comparison.add("before", before); comparison.add("after", after);
                comparisons.add(comparison);
                int evaluated = 0;
                System.out.println("VALID VILLAGE SCALE " + scale + ": " + ordered.size() + " unique native starts to evaluate");
                for (Site site : ordered) {
                    String key = scale+":"+site.set+":"+site.chunk.x+":"+site.chunk.z;
                    JsonElement result = completed.get(key);
                    if (result == null) {
                        var chunk = new ProtoChunk(site.chunk, UpgradeData.EMPTY, height, biomeRegistry, null);
                        int flags = sites.get(site);
                        var state = states[(flags & 2) != 0 ? 1 : 0][site.set];
                        generator.createStructures(level.registryAccess(), state, level.structureManager(), chunk, level.getStructureManager());
                        result = JsonNull.INSTANCE;
                        for (var entry : chunk.getAllStarts().entrySet()) {
                            var start = entry.getValue();
                            if (!start.isValid()) continue;
                            var center = start.getBoundingBox().getCenter();
                            JsonArray point = new JsonArray();
                            point.add(center.getX()); point.add(center.getZ()); point.add(site.set);
                            point.add(level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(entry.getKey()).toString());
                            point.add(start.getPieces().size());
                            result = point;
                        }
                        completed.add(key, result);
                        Files.writeString(checkpoint, gson.toJson(completed));
                    }
                    if (!result.isJsonNull()) {
                        int flags = sites.get(site);
                        if ((flags & 1) != 0) before.add(result);
                        if ((flags & 2) != 0) after.add(result);
                    }
                    if (++evaluated % 10 == 0 || evaluated == ordered.size()) {
                        Files.writeString(Path.of("valid-village-data.partial.json"), gson.toJson(output));
                        System.out.println("VALID VILLAGE SCALE "+scale+": "+evaluated+"/"+ordered.size()+" evaluated; before="+before.size()+", after="+after.size());
                    }
                }
                if (scale == 1 && !before.equals(after)) throw new AssertionError("Valid scale-1 starts differ");
            }
        } finally {
            WorldScaleManager.setCurrentScale(level, previousScale);
            LocalTerrainProvider.clearCache();
        }
        output.addProperty("inferenceBackend", OnnxModel.getResolvedInferenceProvider());
        Files.writeString(Path.of("valid-village-data.json"), gson.toJson(output));
        System.out.println("VALID VILLAGE MAP DATA EXPORTED");
    }
}
