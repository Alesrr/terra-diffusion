package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

// Runs a wrapped feature everywhere except where the column's biome is the skipped one
public final class SnowPatchGate extends Feature<NoneFeatureConfiguration> {
    private final ResourceKey<Biome> skip;
    private volatile ConfiguredFeature<?, ?> wrapped;

    public SnowPatchGate(String namespace, String biome) {
        super(NoneFeatureConfiguration.CODEC);
        this.skip = ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, biome));
    }

    void wrap(ConfiguredFeature<?, ?> feature) {
        this.wrapped = feature;
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        ConfiguredFeature<?, ?> feature = wrapped;
        if (feature == null || context.level().getBiome(context.origin()).is(skip)) {
            return false;
        }
        return feature.place(context.level(), context.chunkGenerator(), context.random(), context.origin());
    }
}
