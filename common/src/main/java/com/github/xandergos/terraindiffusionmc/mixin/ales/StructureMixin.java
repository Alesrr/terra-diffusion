package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(Structure.class)
public class StructureMixin {

    private static final int GROUND_TOLERANCE = 4;

    private static final int MONUMENT_FLOOR_RANGE = 12;

    private static final int CHAMBER_COVER = 12;

    private static final ResourceLocation TRIAL_CHAMBERS = ResourceLocation.withDefaultNamespace("trial_chambers");

    @Inject(method = "generate", at = @At("RETURN"), cancellable = true)
    private void terrainDiffusion$vetoUnsupportedStart(
            RegistryAccess registryAccess, ChunkGenerator generator, BiomeSource biomeSource,
            RandomState randomState, StructureTemplateManager templateManager, long seed,
            ChunkPos chunkPos, int references, LevelHeightAccessor level,
            Predicate<Holder<Biome>> validBiome, CallbackInfoReturnable<StructureStart> cir) {

        if (!(biomeSource instanceof TerrainDiffusionBiomeSource)) {
            return;
        }

        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid()) {
            return;
        }

        Structure self = (Structure) (Object) this;
        if (self instanceof OceanMonumentStructure) {
            cir.setReturnValue(seatMonument(self, start, chunkPos, references, generator, level, randomState));
            return;
        }

        boolean wreck = self instanceof ShipwreckStructure shipwreck && !shipwreck.isBeached;
        if (wreck || (!(self instanceof ShipwreckStructure) && (self instanceof OceanRuinStructure || oceanOnly(self)))) {
            int limit = wreck ? TerrainDiffusionConfig.shipwreckFloorY() : TerrainDiffusionConfig.oceanRuinFloorY();
            if (floorAbove(start, limit, generator, level, randomState)) {
                cir.setReturnValue(StructureStart.INVALID_START);
            }
            return;
        }

        BoundingBox box = start.getBoundingBox();
        int startY = box.minY();
        if (startY >= HeightConverter.SEA_LEVEL) {
            return;
        }

        if (TRIAL_CHAMBERS.equals(registryAccess.registryOrThrow(Registries.STRUCTURE).getKey(self))
                && nearSeaFloor(box, generator, level, randomState)) {
            cir.setReturnValue(StructureStart.INVALID_START);
            return;
        }

        BlockPos center = box.getCenter();
        int ground = floorAt(generator, center.getX(), center.getZ(), level, randomState);
        if (startY > ground + GROUND_TOLERANCE) {
            cir.setReturnValue(StructureStart.INVALID_START);
        }
    }

    private static int floorAt(ChunkGenerator generator, int x, int z, LevelHeightAccessor level, RandomState randomState) {
        return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
    }

    private static boolean floorAbove(StructureStart start, int limit, ChunkGenerator generator,
                                      LevelHeightAccessor level, RandomState randomState) {
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox b = piece.getBoundingBox();
            for (int i = 0; i <= 2; i++) {
                for (int j = 0; j <= 2; j++) {
                    int x = b.minX() + (b.maxX() - b.minX()) * i / 2;
                    int z = b.minZ() + (b.maxZ() - b.minZ()) * j / 2;
                    if (floorAt(generator, x, z, level, randomState) > limit) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean oceanOnly(Structure structure) {
        HolderSet<Biome> biomes = structure.biomes();
        if (biomes.size() == 0) {
            return false;
        }
        for (Holder<Biome> biome : biomes) {
            if (!biome.is(BiomeTags.IS_OCEAN)) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearSeaFloor(BoundingBox box, ChunkGenerator generator, LevelHeightAccessor level, RandomState randomState) {
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                int x = box.minX() + (box.maxX() - box.minX()) * i / 4;
                int z = box.minZ() + (box.maxZ() - box.minZ()) * j / 4;
                int floor = floorAt(generator, x, z, level, randomState);
                if (floor < HeightConverter.SEA_LEVEL && floor <= box.maxY() + CHAMBER_COVER) {
                    return true;
                }
            }
        }
        return false;
    }

    private static StructureStart seatMonument(Structure self, StructureStart start, ChunkPos chunkPos, int references,
                                               ChunkGenerator generator, LevelHeightAccessor level, RandomState randomState) {
        BoundingBox box = start.getBoundingBox();
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        long sum = 0;
        for (int i = 0; i <= 4; i++) {
            for (int j = 0; j <= 4; j++) {
                int x = box.minX() + (box.maxX() - box.minX()) * i / 4;
                int z = box.minZ() + (box.maxZ() - box.minZ()) * j / 4;
                int floor = floorAt(generator, x, z, level, randomState);
                lo = Math.min(lo, floor);
                hi = Math.max(hi, floor);
                sum += floor;
            }
        }
        if (hi - lo > MONUMENT_FLOOR_RANGE) {
            return StructureStart.INVALID_START;
        }
        int drop = Math.min(0, Math.round(sum / 25f) - box.minY());
        if (drop == 0) {
            return start;
        }
        for (StructurePiece piece : start.getPieces()) {
            piece.move(0, drop, 0);
        }
        return new StructureStart(self, chunkPos, references, new PiecesContainer(start.getPieces()));
    }
}
