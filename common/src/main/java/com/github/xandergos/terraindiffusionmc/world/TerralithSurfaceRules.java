package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.ales.NoiseBasedChunkGeneratorAccessor;
import com.github.xandergos.terraindiffusionmc.pipeline.TerralithCompat;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerralithSurfaceRules {
    private static final Logger LOG = LoggerFactory.getLogger(TerralithSurfaceRules.class);

    private static NoiseGeneratorSettings splicedInto;

    private TerralithSurfaceRules() {
    }

    public static void apply(ServerLevel overworld) {
        ChunkGenerator generator = overworld.getChunkSource().getGenerator();

        generator.getBiomeSource().possibleBiomes();

        if (!TerralithCompat.isActive() || !TerrainDiffusionConfig.terralithInjectSurfaceRules()) {
            return;
        }

        if (!(generator instanceof NoiseBasedChunkGenerator noiseGenerator)) {
            return;
        }

        NoiseGeneratorSettings current = noiseGenerator.generatorSettings().value();
        if (current == splicedInto) {
            return;
        }

        SurfaceRules.RuleSource terralithRule = overworld.registryAccess()
                .registryOrThrow(Registries.NOISE_SETTINGS)
                .getOptional(NoiseGeneratorSettings.OVERWORLD)
                .map(NoiseGeneratorSettings::surfaceRule)
                .orElse(null);

        if (terralithRule == null) {
            LOG.warn("Could not read the overworld surface rule; Terralith biomes will use default surfaces");
            return;
        }

        NoiseGeneratorSettings merged = new NoiseGeneratorSettings(
                current.noiseSettings(),
                current.defaultBlock(),
                current.defaultFluid(),
                current.noiseRouter(),
                SurfaceRules.sequence(waterBeds(), aridSurfaces(), terralithRule, current.surfaceRule()),
                current.spawnTarget(),
                current.seaLevel(),
                current.disableMobGeneration(),
                current.isAquifersEnabled(),
                current.oreVeinsEnabled(),
                current.useLegacyRandomSource()
        );

        ((NoiseBasedChunkGeneratorAccessor) (Object) noiseGenerator)
                .terrainDiffusion$setSettings(Holder.direct(merged));
        splicedInto = merged;
        LOG.info("Applied Terralith surface rules to the terrain-diffusion overworld");
    }

    private static final short BED_TOP_M = 2000;

    private static final double CLAY_PATCH = 0.2;

    private static final String[] NATURAL_BEDS = {
            "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean", "terralith:deep_warm_ocean",
            "minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore", "terralith:gravel_beach",
            "minecraft:swamp", "minecraft:mangrove_swamp", "terralith:orchid_swamp",
            "minecraft:stony_peaks", "minecraft:jagged_peaks", "minecraft:frozen_peaks", "minecraft:snowy_slopes",
            "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
            "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands",
            "terralith:windswept_spires", "terralith:stony_spires", "terralith:rocky_mountains",
            "terralith:granite_cliffs", "terralith:yosemite_cliffs", "terralith:white_cliffs",
            "terralith:frozen_cliffs", "terralith:glacial_chasm", "terralith:emerald_peaks",
            "terralith:painted_mountains", "terralith:scarlet_mountains", "terralith:volcanic_peaks",
            "terralith:basalt_cliffs", "terralith:desert_spires", "terralith:bryce_canyon",
            "terralith:white_mesa", "terralith:warped_mesa", "terralith:haze_mountain"
    };

    public static final int ERODED_FROM_Y = 150;

    private static final String[] WATER_BEDS = {
            "minecraft:ocean", "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean", "minecraft:warm_ocean", "terralith:deep_warm_ocean",
            "minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore", "terralith:gravel_beach",
            "minecraft:swamp", "minecraft:mangrove_swamp", "terralith:orchid_swamp"
    };

    @SuppressWarnings("unchecked")
    private static ResourceKey<Biome>[] biomes(String[] names) {
        ResourceKey<Biome>[] keys = new ResourceKey[names.length];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(names[i]));
        }
        return keys;
    }

    private static SurfaceRules.RuleSource block(net.minecraft.world.level.block.Block block) {
        return SurfaceRules.state(block.defaultBlockState());
    }

    // Badlands is the red sand belt between desert and terracotta, and wooded badlands keeps its grass, at any height
    private static SurfaceRules.RuleSource aridSurfaces() {
        SurfaceRules.RuleSource redSand = SurfaceRules.ifTrue(SurfaceRules.isBiome(Biomes.BADLANDS), SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, block(Blocks.RED_SANDSTONE)),
                        block(Blocks.RED_SAND))),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, block(Blocks.RED_SAND)),
                SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR, block(Blocks.RED_SANDSTONE))));
        SurfaceRules.RuleSource woodedTop = SurfaceRules.ifTrue(SurfaceRules.isBiome(Biomes.WOODED_BADLANDS),
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.909, -0.5454), block(Blocks.COARSE_DIRT)),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -0.1818, 0.1818), block(Blocks.COARSE_DIRT)),
                        SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.5454, 0.909), block(Blocks.COARSE_DIRT)),
                        SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0), block(Blocks.GRASS_BLOCK)),
                        block(Blocks.DIRT))));
        return SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), SurfaceRules.sequence(redSand, woodedTop));
    }

    private static SurfaceRules.RuleSource waterBeds() {
        int top = HeightConverter.convertToMinecraftHeight(BED_TOP_M);
        SurfaceRules.RuleSource sand = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, block(Blocks.SANDSTONE)),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.PATCH, CLAY_PATCH), block(Blocks.CLAY)),
                block(Blocks.SAND));
        SurfaceRules.RuleSource eroded = SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, block(Blocks.COBBLESTONE)),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SWAMP, 0.35), block(Blocks.COBBLESTONE)),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, 0.12),
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.ICE, 0.0), block(Blocks.ANDESITE)),
                                block(Blocks.TUFF))),
                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.SURFACE, -Double.MAX_VALUE, -0.12),
                        SurfaceRules.sequence(
                                SurfaceRules.ifTrue(SurfaceRules.noiseCondition(Noises.PATCH, 0.0), block(Blocks.MUD)),
                                block(Blocks.DIRT))),
                block(Blocks.GRAVEL));
        SurfaceRules.RuleSource highland = SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.isBiome(biomes(WATER_BEDS))),
                SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(ERODED_FROM_Y), 0),
                        SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(0, 0)),
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, eroded))));
        SurfaceRules.RuleSource lowland = SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.isBiome(biomes(NATURAL_BEDS))),
                SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(top), 0)),
                        SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.waterBlockCheck(-2, 0)),
                                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, sand))));
        return SurfaceRules.sequence(highland, lowland);
    }
}
