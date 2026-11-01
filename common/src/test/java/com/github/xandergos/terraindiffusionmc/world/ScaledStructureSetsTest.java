package com.github.xandergos.terraindiffusionmc.world;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScaledStructureSetsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void scaleOneIsExactDatapackParityAndAllScalesRetainPlacementFields() {
        JsonObject input = new JsonObject();
        input.addProperty("type", "minecraft:random_spread");
        input.addProperty("spacing", 34);
        input.addProperty("separation", 8);
        input.addProperty("salt", 10387312);
        input.addProperty("spread_type", "triangular");
        input.addProperty("frequency", 0.625);
        input.addProperty("frequency_reduction_method", "legacy_type_3");
        input.add("locate_offset", JsonOps.INSTANCE.createIntList(java.util.stream.IntStream.of(3, 7, -2)));
        var original = (RandomSpreadStructurePlacement) StructurePlacement.CODEC.parse(JsonOps.INSTANCE, input).getOrThrow();
        assertSame(original, ScaledStructureSets.scale(original, 1, JsonOps.INSTANCE));
        for (int scale = 1; scale <= 6; scale++) {
            var scaled = ScaledStructureSets.scale(original, scale, JsonOps.INSTANCE);
            JsonObject actual = StructurePlacement.CODEC.encodeStart(JsonOps.INSTANCE, scaled).getOrThrow().getAsJsonObject();
            JsonObject expected = StructurePlacement.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow().getAsJsonObject();
            expected.addProperty("spacing", 34 * scale);
            expected.addProperty("separation", 8 * scale);
            assertEquals(expected, actual);
        }
    }

    @Test
    void nativeCandidateGridsKeepRegionalCountsAndAreOrderIndependentInEveryQuadrant() {
        var original = new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312);
        for (long seed : new long[]{0, 1, -431794351L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            for (int scale = 1; scale <= 6; scale++) {
                var scaled = ScaledStructureSets.scale(original, scale, JsonOps.INSTANCE);
                Set<ChunkPos> candidates = new HashSet<>();
                // Same 20 x 20 terrain-normalized regions at every scale, straddling zero.
                for (int x = -10; x < 10; x++) for (int z = -10; z < 10; z++) {
                    ChunkPos pos = scaled.getPotentialStructureChunk(seed, x * scaled.spacing(), z * scaled.spacing());
                    assertEquals(x, Math.floorDiv(pos.x, scaled.spacing()));
                    assertEquals(z, Math.floorDiv(pos.z, scaled.spacing()));
                    assertTrue(Math.floorMod(pos.x, scaled.spacing()) < scaled.spacing() - scaled.separation());
                    assertTrue(Math.floorMod(pos.z, scaled.spacing()) < scaled.spacing() - scaled.separation());
                    assertTrue(candidates.add(pos));
                }
                var reloaded = ScaledStructureSets.scale(original, scale, JsonOps.INSTANCE);
                for (int x = 9; x >= -10; x--) for (int z = 9; z >= -10; z--) {
                    assertTrue(candidates.remove(reloaded.getPotentialStructureChunk(seed,
                            x * scaled.spacing(), z * scaled.spacing())));
                }
                assertTrue(candidates.isEmpty());
            }
        }
    }

    @Test
    void copiesArePerGeneratorAndExclusionsResolveTheScaledVillageGrid() {
        var registry = new MappedRegistry<StructureSet>(Registries.STRUCTURE_SET,
                com.mojang.serialization.Lifecycle.stable());
        var villages = Registry.registerForHolder(registry, id("minecraft:villages"),
                new StructureSet(List.of(), new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 10387312)));
        // The actual registry is used for the exclusion holder's encode/decode round trip.
        var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry));
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        JsonObject rareJson = new JsonObject();
        rareJson.addProperty("type", "minecraft:random_spread");
        rareJson.addProperty("spacing", 46);
        rareJson.addProperty("separation", 18);
        rareJson.addProperty("salt", 1798451114);
        JsonObject exclusion = new JsonObject();
        exclusion.addProperty("other_set", "minecraft:villages");
        exclusion.addProperty("chunk_count", 8);
        rareJson.add("exclusion_zone", exclusion);
        var rarePlacement = StructurePlacement.CODEC.parse(ops, rareJson).getOrThrow();
        var rare = Registry.registerForHolder(registry, id("terralith:rare_village"), new StructureSet(List.of(), rarePlacement));
        var unrelated = Registry.registerForHolder(registry, id("test:unrelated"),
                new StructureSet(List.of(), new RandomSpreadStructurePlacement(17, 5, RandomSpreadType.LINEAR, 17)));
        registry.freeze();
        registry.bindTags(Map.of(ScaledStructureSets.SCALE_WITH_TERRAIN, List.of(villages, rare)));
        var originals = List.<Holder<StructureSet>>of(villages, rare, unrelated);
        var scale4 = new ScaledStructureSets(originals, 4, ops);
        var scale6 = new ScaledStructureSets(originals, 6, ops);
        assertSame(villages, new ScaledStructureSets(originals, 1, ops).sets().getFirst());
        assertSame(unrelated, scale4.sets().get(2));
        assertEquals(34, ((RandomSpreadStructurePlacement) villages.value().placement()).spacing());
        assertEquals(136, ((RandomSpreadStructurePlacement) scale4.resolve(villages).value().placement()).spacing());
        assertEquals(204, ((RandomSpreadStructurePlacement) scale6.resolve(villages).value().placement()).spacing());
        assertSame(scale4.sets().getFirst(), scale4.resolve(villages));
        assertSame(villages.value().structures(), scale4.sets().getFirst().value().structures());
        JsonObject encoded = StructurePlacement.CODEC.encodeStart(ops, scale4.sets().get(1).value().placement()).getOrThrow().getAsJsonObject();
        assertEquals(exclusion, encoded.getAsJsonObject("exclusion_zone"));
        assertEquals(184, encoded.get("spacing").getAsInt());
        assertSame(scale4.sets().getFirst().value().placement(), scale4.resolve(villages.value().placement()));
        var absentTarget = new ScaledStructureSets(List.of(rare), originals, 4, ops);
        assertEquals(1, absentTarget.sets().size());
        assertEquals(136, ((RandomSpreadStructurePlacement) absentTarget.resolve(villages).value().placement()).spacing());
    }

    @Test
    void tagsCannotReplaceCustomAlgorithmsOrStrongholdRings() {
        var registry = new MappedRegistry<StructureSet>(Registries.STRUCTURE_SET,
                com.mojang.serialization.Lifecycle.stable());
        var custom = Registry.registerForHolder(registry, id("test:custom"), new StructureSet(List.of(),
                new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 1) {}));
        var rings = Registry.registerForHolder(registry, id("test:rings"), new StructureSet(List.of(),
                new ConcentricRingsStructurePlacement(32, 3, 128, HolderSet.direct())));
        var untagged = Registry.registerForHolder(registry, id("test:untagged"), new StructureSet(List.of(),
                new RandomSpreadStructurePlacement(34, 8, RandomSpreadType.LINEAR, 1)));
        registry.freeze();
        registry.bindTags(Map.of(ScaledStructureSets.SCALE_WITH_TERRAIN, List.of(custom, rings)));
        var originals = List.<Holder<StructureSet>>of(custom, rings, untagged);
        var scaled = new ScaledStructureSets(originals, 6, JsonOps.INSTANCE);
        for (int i = 0; i < originals.size(); i++) assertSame(originals.get(i), scaled.sets().get(i));
    }

    @Test
    void unusuallyLargeDatapackGridsStayCodecValid() {
        var original = new RandomSpreadStructurePlacement(4096, 4095, RandomSpreadType.LINEAR, 13);
        var scaled = ScaledStructureSets.scale(original, 6, JsonOps.INSTANCE);
        assertEquals(4096, scaled.spacing());
        assertEquals(4095, scaled.separation());
        assertTrue(StructurePlacement.CODEC.encodeStart(JsonOps.INSTANCE, scaled).isSuccess());
    }

    private static ResourceLocation id(String id) {
        return ResourceLocation.parse(id);
    }
}
