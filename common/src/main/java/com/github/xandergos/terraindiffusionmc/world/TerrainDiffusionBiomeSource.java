package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeIds;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithCompat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionBiomeSource.class);

    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "snowy_taiga_sparse"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));

    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;
    private Holder<Biome>[] caveBiomes = null;
    private boolean caveTerralith = false;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap != null) {
            return;
        }

        Map<Short, Holder<Biome>> biomes = new LinkedHashMap<>(Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 7, this.biomeLookup.getOrThrow(Biomes.RIVER)),
                    entry((short) 11, this.biomeLookup.getOrThrow(Biomes.FROZEN_RIVER)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
        ));

        addTerralithBiomes(biomes);
        biomeIdMap = Map.copyOf(biomes);
        resolveCaveBiomes();
    }

    private void addTerralithBiomes(Map<Short, Holder<Biome>> biomes) {
        Map<Short, String> paths = TerralithBiomeIds.paths();
        Map<Short, Holder<Biome>> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (Map.Entry<Short, String> path : paths.entrySet()) {
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME,
                    ResourceLocation.fromNamespaceAndPath(TerralithCompat.NAMESPACE, path.getValue()));
            Optional<Holder.Reference<Biome>> holder = this.biomeLookup.get(key);
            if (holder.isPresent()) {
                resolved.put(path.getKey(), holder.get());
            } else {
                missing.add(path.getValue());
            }
        }

        SnowFreeBiomes.set(List.of());
        if (resolved.isEmpty()) {
            TerralithCompat.setActive(false);
            return;
        }

        if (!missing.isEmpty()) {
            LOG.warn("Terralith is installed but {} of {} expected biomes are missing (first: {}); "
                            + "keeping the vanilla biome palette",
                    missing.size(), paths.size(), missing.get(0));
            TerralithCompat.setActive(false);
            return;
        }

        if (!TerrainDiffusionConfig.terralithEnabled()) {
            LOG.info("Terralith detected but terralith.enabled=false; keeping the vanilla biome palette");
            TerralithCompat.setActive(false);
            return;
        }

        biomes.putAll(resolved);
        SnowFreeBiomes.set(List.of(resolved.get(TerralithBiomeIds.SCARLET_MOUNTAINS).value()));
        TerralithCompat.setActive(true);
        LOG.info("Terralith detected: added {} biomes to the terrain-diffusion palette", resolved.size());
    }

    @SuppressWarnings("unchecked")
    private void resolveCaveBiomes() {
        boolean terralith = TerralithCompat.isActive();
        Holder<Biome>[] out = new Holder[CaveBiomes.IDS.length];
        List<String> missing = new ArrayList<>();

        for (int i = 0; i < CaveBiomes.IDS.length; i++) {
            if (CaveBiomes.isTerralith(i) && !terralith) continue;
            ResourceLocation id = ResourceLocation.parse(CaveBiomes.IDS[i]);
            Optional<Holder.Reference<Biome>> holder =
                    this.biomeLookup.get(ResourceKey.create(Registries.BIOME, id));
            if (holder.isPresent()) out[i] = holder.get();
            else if (CaveBiomes.isTerralith(i)) missing.add(CaveBiomes.IDS[i]);
            else throw new IllegalStateException("missing vanilla cave biome " + id);
        }

        if (!missing.isEmpty()) {
            LOG.warn("{} Terralith cave biomes are missing (first: {}); those slots fall back to the "
                    + "vanilla cave palette", missing.size(), missing.get(0));
            terralith = false;
            for (int i = 3; i < out.length; i++) out[i] = null;
        }

        this.caveBiomes = out;
        this.caveTerralith = terralith;
        LOG.info("Cave biomes active: {} entries (terralith={})",
                java.util.Arrays.stream(out).filter(java.util.Objects::nonNull).count(), terralith);
    }

    private Holder<Biome> caveBiomeAt(HeightmapData data, int localX, int localZ,
                                      int worldX, int worldZ, int surfaceY, int blockY) {
        if (caveBiomes == null || data.climateT == null) return null;
        float depth = CaveBiomes.depthAt(surfaceY, blockY);
        if (depth < CaveBiomes.MIN_DEPTH) return null;
        float t = CaveBiomes.jitterTemp(data.climateT[localZ][localX] / 127f, worldX, worldZ);
        float h = CaveBiomes.jitterHumidity(data.climateH[localZ][localX] / 127f, worldX, worldZ);
        float e = CaveBiomes.jitterErosion(data.climateE[localZ][localX] / 127f, worldX, worldZ);
        float c = CaveBiomes.continentalnessAt(data.heightmap[localZ][localX]);
        float w = CaveBiomes.weirdnessAt(worldX, worldZ);
        int idx = CaveBiomes.select(t, h, c, e, w, depth, caveTerralith);
        return idx == CaveBiomes.NONE ? null : caveBiomes[idx];
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return Stream.concat(biomeIdMap.values().stream(),
                java.util.Arrays.stream(caveBiomes).filter(java.util.Objects::nonNull)).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        requireBiomeIdMap();
        Holder<Biome> found = biomeAt(x, y, z, true);
        return found != null ? found : biomeIdMap.get((short) 1);
    }

    // hundreds of scattered positions would run the model once per position
    private Holder<Biome> biomeAt(int x, int y, int z, boolean generate) {
        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int blockStartX = (blockX >> tileShift) << tileShift;
        int blockStartZ = (blockZ >> tileShift) << tileShift;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = generate
                ? LocalTerrainProvider.getInstance()
                        .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX)
                : LocalTerrainProvider.peekHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        if (data == null || data.biomeIds == null) return null;

        int localX = Math.max(0, Math.min(data.width  - 1, blockX - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, blockZ - blockStartZ));

        int surfaceY = HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
        Holder<Biome> cave = caveBiomeAt(data, localX, localZ, blockX, blockZ,
                surfaceY, QuartPos.toBlock(y));
        if (cave != null) return cave;

        return biomeIdMap.get(data.biomeIds[localZ][localX]);
    }

    private static final int MAX_SAMPLES =
            Integer.parseInt(System.getProperty("terradiff.biomeSearchSamples", "40000"));

    // Rings outward in quart space, sweeping vertically
    private Pair<BlockPos, Holder<Biome>> search(int x, int minY, int maxY, int z, int radius,
                                                 int horizontalInterval, int verticalInterval,
                                                 Predicate<Holder<Biome>> predicate) {
        requireBiomeIdMap();
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        int qRadius = Math.max(0, QuartPos.fromBlock(radius));
        int step = Math.max(1, QuartPos.fromBlock(horizontalInterval));
        int qMinY = QuartPos.fromBlock(minY);
        int qMaxY = QuartPos.fromBlock(maxY);
        int vStep = Math.max(1, QuartPos.fromBlock(verticalInterval));

        int budget = MAX_SAMPLES;
        for (int ring = 0; ring <= qRadius; ring += step) {
            for (int dz = -ring; dz <= ring; dz += step) {
                boolean onZEdge = Math.abs(Math.abs(dz) - ring) < step;
                for (int dx = -ring; dx <= ring; dx += step) {
                    if (!onZEdge && Math.abs(Math.abs(dx) - ring) >= step) continue;
                    for (int qy = qMaxY; qy >= qMinY; qy -= vStep) {
                        if (--budget < 0) return null;
                        Holder<Biome> found = biomeAt(qx + dx, qy, qz + dz, false);
                        if (found != null && predicate.test(found)) {
                            return Pair.of(new BlockPos(QuartPos.toBlock(qx + dx),
                                    QuartPos.toBlock(qy), QuartPos.toBlock(qz + dz)), found);
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval, Predicate<Holder<Biome>> predicate, Climate.Sampler noiseSampler, LevelReader world) {
        return search(origin.getX(), world.getMinBuildHeight(), world.getMaxBuildHeight() - 1,
                origin.getZ(), radius, horizontalBlockCheckInterval, verticalBlockCheckInterval,
                predicate);
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius, int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random, boolean bl, Climate.Sampler noiseSampler) {
        return search(x, y, y, z, radius, blockCheckInterval, 16, predicate);
    }
}
