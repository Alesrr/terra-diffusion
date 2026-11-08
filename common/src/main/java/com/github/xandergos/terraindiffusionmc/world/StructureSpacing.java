package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.ales.HolderReferenceAccessor;
import com.github.xandergos.terraindiffusionmc.mixin.ales.StructurePlacementAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

// Rebinds structure set placements to Terra Diffusion's spacing before a world's structure state is built
public final class StructureSpacing {
    private static final Logger LOG = LoggerFactory.getLogger(StructureSpacing.class);

    private static final String[] SETS = {"ocean_monuments", "ancient_cities", "shipwrecks", "villages", "pillager_outposts"};

    private static final Map<Holder.Reference<StructureSet>, StructureSet> ORIGINAL =
            Collections.synchronizedMap(new WeakHashMap<>());

    private StructureSpacing() {
    }

    @SuppressWarnings("unchecked")
    public static void apply(HolderLookup<StructureSet> lookup) {
        StringBuilder done = new StringBuilder();
        for (String set : SETS) {
            float[] spacing = TerrainDiffusionConfig.structureSpacing(set);
            if (spacing == null) continue;
            ResourceKey<StructureSet> key = ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.withDefaultNamespace(set));
            Holder.Reference<StructureSet> ref = lookup.get(key).orElse(null);
            if (ref == null) continue;
            StructureSet original = ORIGINAL.computeIfAbsent(ref, Holder.Reference::value);
            if (!(original.placement() instanceof RandomSpreadStructurePlacement spread)) continue;
            int every = Math.max(2, Math.round(spacing[0]));
            int apart = Math.max(0, Math.min(every - 1, Math.round(spacing[1])));
            float frequency = Math.max(0f, Math.min(1f, spacing[2]));
            StructurePlacementAccessor from = (StructurePlacementAccessor) spread;
            RandomSpreadStructurePlacement placement = new RandomSpreadStructurePlacement(from.terrainDiffusion$locateOffset(),
                    from.terrainDiffusion$frequencyReductionMethod(), frequency, from.terrainDiffusion$salt(),
                    from.terrainDiffusion$exclusionZone(), every, apart, spread.spreadType());
            ((HolderReferenceAccessor<StructureSet>) ref).terrainDiffusion$bindValue(new StructureSet(original.structures(), placement));
            done.append(String.format(java.util.Locale.ROOT, " %s %d/%d/%.2f", set, every, apart, frequency));
        }
        if (done.length() > 0) {
            LOG.info("Structure spacing for this world (spacing/separation/frequency):{}", done);
        }
    }
}
