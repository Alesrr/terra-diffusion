package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.ales.HolderReferenceAccessor;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Fits TFMG's worldgen to this dimension at world load
public final class TfmgCompat {

    private static final Logger LOG = LoggerFactory.getLogger(TfmgCompat.class);

    public static final String NAMESPACE = "tfmg";

    public static final TfmgOilFeature OIL_DEPOSIT = new TfmgOilFeature(TfmgOilFeature.Kind.DEPOSIT);
    public static final TfmgOilFeature OIL_WELL = new TfmgOilFeature(TfmgOilFeature.Kind.WELL);

    private static final String[] OIL_FEATURES = {"oil_deposit", "oil_well"};
    private static final String[] STRIATED_FEATURES = {
            "tfmg_striated_ores_overworld",
            "tfmg_striated_ores_nether"};

    private static volatile BlockState depositMarker;
    private static volatile BlockState fossilstone;
    private static volatile BlockState crudeOil;

    private TfmgCompat() {
    }

    public static boolean blocksResolved() {
        return depositMarker != null && fossilstone != null && crudeOil != null;
    }

    public static BlockState depositMarker() {
        return depositMarker;
    }

    public static BlockState fossilstone() {
        return fossilstone;
    }

    public static BlockState crudeOil() {
        return crudeOil;
    }

    public static void apply(ServerLevel world) {
        if (!TerrainDiffusionConfig.tfmgEnabled()) return;
        if (!resolveBlocks()) return;

        RegistryAccess access = world.registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Registry<ConfiguredFeature<?, ?>> configured = access.registryOrThrow(Registries.CONFIGURED_FEATURE);
        Registry<PlacedFeature> placed = access.registryOrThrow(Registries.PLACED_FEATURE);

        int oilY = Math.max(world.getMinBuildHeight(), Math.min(world.getMaxBuildHeight() - 1,
                TerrainDiffusionConfig.tfmgOilY()));

        int replaced = 0;
        for (String path : OIL_FEATURES) {
            if (bindOilFeature(configured, path)) replaced++;
        }

        int moved = 0;
        for (String path : OIL_FEATURES) {
            if (setHeight(ops, placed, path, oilY)) moved++;
        }

        int enlarged = 0;
        int size = Math.max(0, Math.min(64, TerrainDiffusionConfig.tfmgStriatedOreSize()));
        for (String path : STRIATED_FEATURES) {
            if (setOreSize(ops, configured, path, size)) enlarged++;
        }

        if (replaced == 0 && moved == 0 && enlarged == 0) return;
        LOG.info("TFMG worldgen adapted: {} oil features replaced, {} moved to Y {}, "
                        + "{} striated ore bodies resized to {}",
                replaced, moved, oilY, enlarged, size);
    }

    private static boolean resolveBlocks() {
        Block marker = block("oil_deposit");
        Block fossil = block("fossilstone");
        Fluid oil = BuiltInRegistries.FLUID.get(id("crude_oil"));
        if (marker == null || fossil == null || oil == null || oil == Fluids.EMPTY) {
            return false;
        }

        Fluid source = oil instanceof FlowingFluid flowing ? flowing.getSource() : oil;
        depositMarker = marker.defaultBlockState();
        fossilstone = fossil.defaultBlockState();
        crudeOil = source.defaultFluidState().createLegacyBlock();
        return true;
    }

    private static Block block(String path) {
        Block found = BuiltInRegistries.BLOCK.get(id(path));
        return found == null || found == Blocks.AIR ? null : found;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, path);
    }

    @SuppressWarnings("unchecked")
    private static boolean bindOilFeature(Registry<ConfiguredFeature<?, ?>> registry, String path) {
        ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE, id(path));
        Holder.Reference<ConfiguredFeature<?, ?>> ref = registry.getHolder(key).orElse(null);
        if (ref == null) return false;

        TfmgOilFeature feature = path.equals("oil_well") ? OIL_WELL : OIL_DEPOSIT;
        if (ref.value().feature() == feature) return false;

        ConfiguredFeature<?, ?> rebuilt =
                new ConfiguredFeature<>(feature, NoneFeatureConfiguration.INSTANCE);
        ((HolderReferenceAccessor<ConfiguredFeature<?, ?>>) ref).terrainDiffusion$bindValue(rebuilt);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean setHeight(RegistryOps<JsonElement> ops, Registry<PlacedFeature> registry,
                                     String path, int y) {
        ResourceKey<PlacedFeature> key = ResourceKey.create(Registries.PLACED_FEATURE, id(path));
        Holder.Reference<PlacedFeature> ref = registry.getHolder(key).orElse(null);
        if (ref == null) return false;

        JsonElement encoded = PlacedFeature.DIRECT_CODEC.encodeStart(ops, ref.value())
                .result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) return false;

        JsonObject root = encoded.getAsJsonObject();
        if (!root.has("placement") || !root.get("placement").isJsonArray()) return false;

        JsonObject range = null;
        for (JsonElement element : root.getAsJsonArray("placement")) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            if (modifier.has("type")
                    && modifier.get("type").getAsString().equals("minecraft:height_range")) {
                range = modifier;
                break;
            }
        }
        if (range == null) return false;

        JsonObject uniform = new JsonObject();
        uniform.addProperty("type", "minecraft:uniform");
        uniform.add("min_inclusive", absolute(y));
        uniform.add("max_inclusive", absolute(y));
        range.add("height", uniform);

        PlacedFeature rebuilt = PlacedFeature.DIRECT_CODEC.parse(ops, encoded)
                .resultOrPartial(err -> LOG.warn("height rewrite rejected for {}: {}", id(path), err))
                .orElse(null);
        if (rebuilt == null) return false;

        ((HolderReferenceAccessor<PlacedFeature>) ref).terrainDiffusion$bindValue(rebuilt);
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean setOreSize(RegistryOps<JsonElement> ops,
                                      Registry<ConfiguredFeature<?, ?>> registry,
                                      String path, int size) {
        ResourceKey<ConfiguredFeature<?, ?>> key = ResourceKey.create(Registries.CONFIGURED_FEATURE, id(path));
        Holder.Reference<ConfiguredFeature<?, ?>> ref = registry.getHolder(key).orElse(null);
        if (ref == null) return false;

        JsonElement encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, ref.value())
                .result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) return false;

        JsonObject root = encoded.getAsJsonObject();
        if (!root.has("config") || !root.get("config").isJsonObject()) return false;

        JsonObject config = root.getAsJsonObject("config");
        if (!config.has("size") || !config.get("size").isJsonPrimitive()) return false;
        if (config.get("size").getAsInt() == size) return false;
        config.addProperty("size", size);

        ConfiguredFeature<?, ?> rebuilt = ConfiguredFeature.DIRECT_CODEC.parse(ops, encoded)
                .resultOrPartial(err -> LOG.warn("size rewrite rejected for {}: {}", id(path), err))
                .orElse(null);
        if (rebuilt == null) return false;

        ((HolderReferenceAccessor<ConfiguredFeature<?, ?>>) ref).terrainDiffusion$bindValue(rebuilt);
        return true;
    }

    private static JsonObject absolute(int y) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("absolute", y);
        return anchor;
    }
}
