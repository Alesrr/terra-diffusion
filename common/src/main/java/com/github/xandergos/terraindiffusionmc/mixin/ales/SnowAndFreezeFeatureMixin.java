package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SnowAndFreezeFeature.class)
public abstract class SnowAndFreezeFeatureMixin {
    @Redirect(
            method = "place",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/WorldGenLevel;setBlock("
                            + "Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean terrainDiffusion$deepenSnow(WorldGenLevel level, BlockPos pos,
                                                 BlockState state, int flags) {
        if (!state.is(Blocks.SNOW)) {
            return level.setBlock(pos, state, flags);
        }

        int depth = terrainDiffusion$snowDepth(pos);

        if (terrainDiffusion$isCanopy(level, pos)) {
            terrainDiffusion$snowGroundBelow(level, pos, state, depth, flags);
            return level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, 1), flags);
        }

        if (depth <= SnowLayerBlock.MAX_HEIGHT) {
            return level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, depth), flags);
        }

        BlockPos above = pos.above();
        if (level.isOutsideBuildHeight(above)) {
            return level.setBlock(pos,
                    state.setValue(SnowLayerBlock.LAYERS, SnowLayerBlock.MAX_HEIGHT), flags);
        }
        level.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), flags);
        return level.setBlock(above,
                state.setValue(SnowLayerBlock.LAYERS, depth - SnowLayerBlock.MAX_HEIGHT), flags);
    }

    private static final int MAX_CANOPY_SCAN = 40;

    @Inject(method = "place", at = @At("RETURN"))
    private void terrainDiffusion$deepenCoveredSnow(
            FeaturePlaceContext<NoneFeatureConfiguration> context,
            CallbackInfoReturnable<Boolean> cir) {
        if (!TerrainDiffusionConfig.snowDepthScaling()) {
            return;
        }

        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

                for (int dy = 0; dy >= -1; dy--) {
                    BlockPos pos = new BlockPos(x, top + dy, z);
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof SnowLayerBlock)) {
                        continue;
                    }
                    terrainDiffusion$deepenAt(level, pos, state);
                    break;
                }
            }
        }
    }

    private void terrainDiffusion$deepenAt(WorldGenLevel level, BlockPos pos, BlockState state) {
        int current = state.getValue(SnowLayerBlock.LAYERS);
        int target = terrainDiffusion$snowDepth(pos);

        boolean covering = level.getBlockEntity(pos) != null;
        if (!covering) {
            int layers = Math.min(target, SnowLayerBlock.MAX_HEIGHT);
            if (layers > current) {
                level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers), 2);
            }
            return;
        }

        int cap = TerrainDiffusionConfig.snowMaxLayersOverVegetation();
        if (target <= cap) {
            int layers = Math.min(target, cap);
            if (layers > current) {
                level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers), 2);
            }
            return;
        }

        BlockState plain = Blocks.SNOW.defaultBlockState();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        terrainDiffusion$layDepth(level, pos, plain, target, 2);
    }

    private void terrainDiffusion$layDepth(WorldGenLevel level, BlockPos pos, BlockState snow,
                                            int depth, int flags) {
        if (depth <= SnowLayerBlock.MAX_HEIGHT) {
            level.setBlock(pos, snow.setValue(SnowLayerBlock.LAYERS, depth), flags);
            return;
        }
        BlockPos above = pos.above();
        if (level.isOutsideBuildHeight(above) || !level.getBlockState(above).isAir()) {
            level.setBlock(pos, snow.setValue(SnowLayerBlock.LAYERS, SnowLayerBlock.MAX_HEIGHT), flags);
            return;
        }
        level.setBlock(pos, Blocks.SNOW_BLOCK.defaultBlockState(), flags);
        level.setBlock(above,
                snow.setValue(SnowLayerBlock.LAYERS, depth - SnowLayerBlock.MAX_HEIGHT), flags);
    }

    private boolean terrainDiffusion$isCanopy(WorldGenLevel level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        return below.is(BlockTags.LEAVES) || below.is(BlockTags.LOGS);
    }

    private void terrainDiffusion$snowGroundBelow(WorldGenLevel level, BlockPos pos,
                                                   BlockState snow, int depth, int flags) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int i = 0; i < MAX_CANOPY_SCAN; i++) {
            cursor.move(Direction.DOWN);
            if (level.isOutsideBuildHeight(cursor)) {
                return;
            }
            BlockState here = level.getBlockState(cursor);
            if (here.isAir() || here.is(BlockTags.LEAVES) || here.is(BlockTags.LOGS)) {
                continue;
            }
            if (!here.getFluidState().isEmpty()) {
                return;
            }

            BlockPos target = cursor.above();
            if (!level.getBlockState(target).isAir()) {
                return;
            }
            if (depth <= SnowLayerBlock.MAX_HEIGHT) {
                level.setBlock(target, snow.setValue(SnowLayerBlock.LAYERS, depth), flags);
                return;
            }
            BlockPos stacked = target.above();
            if (level.isOutsideBuildHeight(stacked) || !level.getBlockState(stacked).isAir()) {
                level.setBlock(target,
                        snow.setValue(SnowLayerBlock.LAYERS, SnowLayerBlock.MAX_HEIGHT), flags);
                return;
            }
            level.setBlock(target, Blocks.SNOW_BLOCK.defaultBlockState(), flags);
            level.setBlock(stacked,
                    snow.setValue(SnowLayerBlock.LAYERS, depth - SnowLayerBlock.MAX_HEIGHT), flags);
            return;
        }
    }

    private int terrainDiffusion$snowDepth(BlockPos pos) {
        if (!TerrainDiffusionConfig.snowDepthScaling()) {
            return 1;
        }

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        int blockStartX = (pos.getX() >> tileShift) << tileShift;
        int blockStartZ = (pos.getZ() >> tileShift) << tileShift;

        LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.peekHeightmap(
                blockStartZ, blockStartX, blockStartZ + tileSize, blockStartX + tileSize);
        if (data == null || data.snowLayers == null) {
            return 1;
        }

        int localX = Math.max(0, Math.min(data.width - 1, pos.getX() - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, pos.getZ() - blockStartZ));
        int depth = data.snowLayers[localZ][localX];
        return Math.max(1, Math.min(2 * SnowLayerBlock.MAX_HEIGHT - 1, depth));
    }
}
