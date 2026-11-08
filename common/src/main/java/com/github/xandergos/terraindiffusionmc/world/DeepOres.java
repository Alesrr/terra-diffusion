package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.ales.HolderReferenceAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

// Rewrites placed features at world load for a world floor of -192
public final class DeepOres {

    private static final Logger LOG = LoggerFactory.getLogger(DeepOres.class);

    // Lowest Y with no deepslate mixed into the stone
    private static final int PATCH_FLOOR = 8;

    private static final double PATCH_SCALE = 0.5;

    // Blob-shaped rock features only
    private static final List<String> PATCH_FEATURES = List.of(
            "minecraft:ore",
            "minecraft:scattered_ore",
            "minecraft:netherrack_replace_blobs",
            "minecraft:geode");

    private static final List<String> PATCH_BLOCKS = List.of(
            "\"minecraft:granite\"",
            "\"minecraft:diorite\"",
            "\"minecraft:andesite\"",
            "\"minecraft:dirt\"");

    private DeepOres() {
    }

    @SuppressWarnings("unchecked")
    public static void apply(ServerLevel world) {
        RegistryAccess access = world.registryAccess();
        Registry<PlacedFeature> registry = access.registryOrThrow(Registries.PLACED_FEATURE);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);

        int minY = world.getMinBuildHeight();
        int height = world.getMaxBuildHeight() - minY;

        int oresExtended = 0, alreadyDeep = 0, patches = 0, failed = 0;
        for (Holder.Reference<PlacedFeature> ref : registry.holders().toList()) {
            PlacedFeature placed = ref.value();

            JsonElement encoded = PlacedFeature.DIRECT_CODEC.encodeStart(ops, placed)
                    .result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) continue;
            JsonObject root = encoded.getAsJsonObject();
            if (!hasHeightRange(root)) continue;

            boolean patch = placesPatchBlock(ops, placed.feature().value());
            boolean ore = placed.feature().value().config() instanceof OreConfiguration;
            if (!patch && !ore) continue;

            int outcome = patch ? clampPatch(root, minY, height) : extendOre(root, minY, height);
            if (outcome == 0) {
                if (!patch) alreadyDeep++;
                continue;
            }
            if (outcome < 0) {
                failed++;
                continue;
            }

            PlacedFeature rebuilt = PlacedFeature.DIRECT_CODEC.parse(ops, encoded)
                    .resultOrPartial(err -> LOG.warn("rewrite rejected for {}: {}",
                            ref.key().location(), err))
                    .orElse(null);
            if (rebuilt == null) {
                failed++;
                continue;
            }

            ((HolderReferenceAccessor<PlacedFeature>) ref).terrainDiffusion$bindValue(rebuilt);
            if (patch) patches++;
            else oresExtended++;
        }

        LOG.info("Ore bands extended to Y {}: {} rewritten, {} already reached the bottom. "
                        + "Stone and dirt patches floored at Y {} and scaled by {}: {}. Left alone: {}",
                minY, oresExtended, alreadyDeep, PATCH_FLOOR, PATCH_SCALE, patches, failed);
    }

    private static boolean hasHeightRange(JsonObject root) {
        if (!root.has("placement") || !root.get("placement").isJsonArray()) return false;
        for (JsonElement element : root.getAsJsonArray("placement")) {
            if (element.isJsonObject() && element.getAsJsonObject().has("type")
                    && element.getAsJsonObject().get("type").getAsString()
                            .equals("minecraft:height_range")) {
                return true;
            }
        }
        return false;
    }

    // Inspects the configured feature as encoded JSON
    private static boolean placesPatchBlock(RegistryOps<JsonElement> ops, ConfiguredFeature<?, ?> cf) {
        JsonElement encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, cf).result().orElse(null);
        if (encoded == null || !encoded.isJsonObject()) return false;
        JsonObject root = encoded.getAsJsonObject();
        if (!root.has("type") || !PATCH_FEATURES.contains(root.get("type").getAsString())) return false;

        String text = encoded.toString();
        for (String block : PATCH_BLOCKS) {
            if (text.contains(block)) return true;
        }
        return false;
    }

    private static JsonObject find(JsonArray placement, String type) {
        for (JsonElement element : placement) {
            if (!element.isJsonObject()) continue;
            JsonObject modifier = element.getAsJsonObject();
            if (modifier.has("type") && modifier.get("type").getAsString().equals(type)) {
                return modifier;
            }
        }
        return null;
    }

    private static int clampPatch(JsonObject root, int minY, int height) {
        JsonArray placement = root.getAsJsonArray("placement");
        JsonObject range = find(placement, "minecraft:height_range");
        if (range == null || !range.has("height") || !range.get("height").isJsonObject()) return -1;

        JsonObject provider = range.getAsJsonObject("height");
        if (!provider.has("min_inclusive") || !provider.has("max_inclusive")) return -1;
        if (!provider.get("min_inclusive").isJsonObject()
                || !provider.get("max_inclusive").isJsonObject()) return -1;

        Integer low = anchor(provider.getAsJsonObject("min_inclusive"), minY, height);
        Integer high = anchor(provider.getAsJsonObject("max_inclusive"), minY, height);
        if (low == null || high == null) return -1;
        if (high <= PATCH_FLOOR) return -1;

        if (low < PATCH_FLOOR) provider.add("min_inclusive", absolute(PATCH_FLOOR));

        scale(root, placement, find(placement, "minecraft:count"),
                find(placement, "minecraft:rarity_filter"), PATCH_SCALE);
        return 1;
    }

    private static int extendOre(JsonObject root, int minY, int height) {
        JsonArray placement = root.getAsJsonArray("placement");
        JsonObject range = find(placement, "minecraft:height_range");
        if (range == null || !range.has("height") || !range.get("height").isJsonObject()) return -1;

        JsonObject provider = range.getAsJsonObject("height");
        if (!provider.has("min_inclusive") || !provider.has("max_inclusive")) return -1;
        if (!provider.get("min_inclusive").isJsonObject()
                || !provider.get("max_inclusive").isJsonObject()) return -1;

        Integer low = anchor(provider.getAsJsonObject("min_inclusive"), minY, height);
        Integer high = anchor(provider.getAsJsonObject("max_inclusive"), minY, height);
        if (low == null || high == null || high < low) return -1;
        if (low <= minY) return 0;

        int oldSpan = high - low + 1;
        int tailSpan = low - minY;

        JsonObject tail = new JsonObject();
        tail.addProperty("type", "minecraft:uniform");
        tail.add("min_inclusive", absolute(minY));
        tail.add("max_inclusive", absolute(low - 1));

        JsonArray distribution = new JsonArray();
        distribution.add(weighted(provider, oldSpan));
        distribution.add(weighted(tail, tailSpan));

        JsonObject mixed = new JsonObject();
        mixed.addProperty("type", "minecraft:weighted_list");
        mixed.add("distribution", distribution);
        range.add("height", mixed);

        scale(root, placement, find(placement, "minecraft:count"), null,
                (double) (oldSpan + tailSpan) / oldSpan);
        return 1;
    }

    private static JsonObject weighted(JsonObject data, int weight) {
        JsonObject entry = new JsonObject();
        entry.add("data", data);
        entry.addProperty("weight", Math.max(1, weight));
        return entry;
    }

    private static JsonObject absolute(int y) {
        JsonObject anchor = new JsonObject();
        anchor.addProperty("absolute", y);
        return anchor;
    }

    private static Integer anchor(JsonObject anchor, int minY, int height) {
        if (anchor.has("absolute")) return anchor.get("absolute").getAsInt();
        if (anchor.has("above_bottom")) return minY + anchor.get("above_bottom").getAsInt();
        if (anchor.has("below_top")) return minY + height - 1 - anchor.get("below_top").getAsInt();
        return null;
    }

    // Puts a newly added count first among the modifiers
    private static void scale(JsonObject root, JsonArray placement, JsonObject count,
                              JsonObject rarity, double factor) {
        if (count != null) {
            scaleCount(count, factor);
            return;
        }
        if (rarity != null && rarity.has("chance") && rarity.get("chance").isJsonPrimitive()) {
            int chance = rarity.get("chance").getAsInt();
            rarity.addProperty("chance", Math.max(1, (int) Math.round(chance / factor)));
            return;
        }
        JsonObject added = new JsonObject();
        added.addProperty("type", "minecraft:count");
        added.addProperty("count", Math.max(1, (int) Math.round(factor)));
        JsonArray reordered = new JsonArray();
        reordered.add(added);
        for (JsonElement element : placement) reordered.add(element);
        root.add("placement", reordered);
    }

    private static void scaleCount(JsonObject count, double factor) {
        JsonElement value = count.get("count");
        if (value == null) return;
        if (value.isJsonPrimitive()) {
            count.addProperty("count", Math.max(1, (int) Math.round(value.getAsInt() * factor)));
            return;
        }
        if (!value.isJsonObject()) return;

        JsonObject inner = value.getAsJsonObject();
        if (inner.has("distribution") && inner.get("distribution").isJsonArray()) {
            for (JsonElement entry : inner.getAsJsonArray("distribution")) {
                if (!entry.isJsonObject()) continue;
                JsonObject wrapper = entry.getAsJsonObject();
                JsonElement data = wrapper.get("data");
                if (data != null && data.isJsonPrimitive()) {
                    wrapper.addProperty("data",
                            Math.max(1, (int) Math.round(data.getAsInt() * factor)));
                }
            }
            return;
        }

        JsonObject bounds = inner.has("value") && inner.get("value").isJsonObject()
                ? inner.getAsJsonObject("value")
                : inner;
        for (String key : List.of("min_inclusive", "max_inclusive", "value")) {
            if (bounds.has(key) && bounds.get(key).isJsonPrimitive()) {
                bounds.addProperty(key,
                        Math.max(1, (int) Math.round(bounds.get(key).getAsInt() * factor)));
            }
        }
    }
}
