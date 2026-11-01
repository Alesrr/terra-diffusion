package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Per-generator copies: never rebind shared registry holders or mutate datapack definitions. */
public final class ScaledStructureSets {
    public static final TagKey<StructureSet> SCALE_WITH_TERRAIN = TagKey.create(
            Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(
                    "terrain-diffusion-mc", "scale_with_terrain"));

    private final Map<StructureSet, Holder<StructureSet>> replacements = new IdentityHashMap<>();
    private final Map<StructurePlacement, StructurePlacement> placements = new IdentityHashMap<>();
    private final List<Holder<StructureSet>> sets;

    public ScaledStructureSets(List<Holder<StructureSet>> originals, int scale, DynamicOps<JsonElement> ops) {
        this(originals, originals, scale, ops);
    }

    public ScaledStructureSets(List<Holder<StructureSet>> originals, List<Holder<StructureSet>> definitions,
                               int scale, DynamicOps<JsonElement> ops) {
        int factor = WorldScaleManager.clampScale(scale);
        for (Holder<StructureSet> holder : definitions) {
            StructureSet original = holder.value();
            if (factor == 1 || !holder.is(SCALE_WITH_TERRAIN)
                    || original.placement().getClass() != RandomSpreadStructurePlacement.class) {
                continue;
            }
            StructurePlacement placement = placements.computeIfAbsent(original.placement(),
                    value -> scale((RandomSpreadStructurePlacement) value, factor, ops));
            Holder<StructureSet> replacement = Holder.direct(new StructureSet(original.structures(), placement));
            replacements.put(original, replacement);
        }
        sets = originals.stream().map(this::resolve).toList();
    }

    public List<Holder<StructureSet>> sets() {
        return sets;
    }

    /** Exclusion zones retain registry holders; resolve those through this generator's copies. */
    public Holder<StructureSet> resolve(Holder<StructureSet> original) {
        return replacements.getOrDefault(original.value(), original);
    }

    public StructurePlacement resolve(StructurePlacement original) {
        return placements.getOrDefault(original, original);
    }

    static RandomSpreadStructurePlacement scale(RandomSpreadStructurePlacement original, int factor,
                                                       DynamicOps<JsonElement> ops) {
        if (factor == 1) return original;
        // Codec copying retains salt, spread type, frequency/reducer, locate offset and exclusion zone.
        // Use registry-aware ops so other_set continues to reference its original registry holder.
        JsonObject encoded = StructurePlacement.CODEC.encodeStart(ops, original).getOrThrow().getAsJsonObject();
        int spacing = Math.multiplyExact(original.spacing(), factor);
        int separation = Math.multiplyExact(original.separation(), factor);
        // Vanilla's random-spread codec accepts at most 4096 chunks. Preserve the ratio when capped.
        if (spacing > 4096) {
            separation = (int) ((long) separation * 4096 / spacing);
            spacing = 4096;
        }
        encoded.addProperty("spacing", spacing);
        encoded.addProperty("separation", separation);
        return (RandomSpreadStructurePlacement) StructurePlacement.CODEC.parse(ops, encoded).getOrThrow();
    }
}
