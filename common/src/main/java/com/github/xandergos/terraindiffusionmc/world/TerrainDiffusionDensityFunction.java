package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.DeepCaverns;
import com.github.xandergos.terraindiffusionmc.pipeline.KarstNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class TerrainDiffusionDensityFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.BOOL.optionalFieldOf("caves", Boolean.TRUE).forGetter(f -> f.caves)
            ).apply(instance, TerrainDiffusionDensityFunction::new));

    private static final int CAVE_ROOF =
            Integer.parseInt(System.getProperty("terradiff.caveRoof", "5"));

    private static final int DEEP_CLEARANCE =
            Integer.parseInt(System.getProperty("terradiff.deepClearance", "8"));

    private static final int SEA_FLOOR_ROOF =
            Integer.parseInt(System.getProperty("terradiff.seaFloorRoof", "8"));

    private static final int CAVE_SIDE =
            Integer.parseInt(System.getProperty("terradiff.caveSide", "6"));

    private static final int RIVER_WALL =
            Integer.parseInt(System.getProperty("terradiff.riverWall", "3"));

    private static boolean nearRiverWater(HeightmapData data, int localX, int localZ, int y) {
        if (RIVER_WALL <= 0 || data.waterLevel == null) {
            return false;
        }
        for (int dz = -RIVER_WALL; dz <= RIVER_WALL; dz++) {
            int lz = localZ + dz;
            if (lz < 0 || lz >= data.height) continue;
            for (int dx = -RIVER_WALL; dx <= RIVER_WALL; dx++) {
                int lx = localX + dx;
                if (lx < 0 || lx >= data.width) continue;
                short w = data.waterLevel[lz][lx];
                if (w == HeightmapData.NO_WATER) continue;
                int surf = HeightConverter.convertToMinecraftHeight(w);
                if (y > surf + 1) continue;
                int bed = HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx]);
                if (y >= bed - RIVER_WALL) return true;
            }
        }
        return false;
    }

    private static int nearestSurface(HeightmapData data, int localX, int localZ, int here) {
        if (CAVE_SIDE <= 0) {
            return here;
        }
        int lowest = here;
        for (int k = 0; k < 8; k++) {
            int dx = (k == 0 || k == 4 || k == 5) ? CAVE_SIDE
                    : (k == 1 || k == 6 || k == 7) ? -CAVE_SIDE : 0;
            int dz = (k == 2 || k == 4 || k == 6) ? CAVE_SIDE
                    : (k == 3 || k == 5 || k == 7) ? -CAVE_SIDE : 0;
            int lx = localX + dx;
            int lz = localZ + dz;
            if (lx < 0) lx = 0; else if (lx >= data.width) lx = data.width - 1;
            if (lz < 0) lz = 0; else if (lz >= data.height) lz = data.height - 1;
            int h = HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx]);
            if (h < lowest) lowest = h;
        }
        return lowest;
    }

    // nearestSurface for every column of a tile, computed once per tile
    private static short[] sideSurface(HeightmapData data) {
        short[] side = data.sideSurface;
        if (side != null) {
            return side;
        }
        int w = data.width, h = data.height;
        side = new short[w * h];
        for (int lz = 0; lz < h; lz++) {
            for (int lx = 0; lx < w; lx++) {
                int here = HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx]);
                side[lz * w + lx] = (short) nearestSurface(data, lx, lz, here);
            }
        }
        data.sideSurface = side;
        return side;
    }

    public static final TerrainDiffusionDensityFunction INSTANCE =
            new TerrainDiffusionDensityFunction(Boolean.TRUE);

    private final boolean caves;

    public TerrainDiffusionDensityFunction(Boolean caves) {
        this.caves = caves == null || caves;
    }

    @Override
    public double compute(DensityFunction.FunctionContext pos) {
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int tileX = x >> tileShift;
        int tileZ = z >> tileShift;

        int blockStartX = tileX << tileShift;
        int blockStartZ = tileZ << tileShift;

        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        HeightmapData data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);

        if (data == null || data.heightmap == null) {
            return 0.0;
        }

        int localX = Math.max(0, Math.min(data.width - 1, x - blockStartX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - blockStartZ));

        int targetHeight = HeightConverter.convertToMinecraftHeight(
                data.heightmap[localZ][localX]
        );

        double terrain = targetHeight - y;
        if (terrain <= 0.0) {
            return terrain;
        }
        return caves ? carve(data, x, y, z, terrain, targetHeight,
                sideSurface(data)[localZ * data.width + localX] - y, localX, localZ) : terrain;
    }

    private static final class FillContext {
        int blockStartX, blockStartZ, blockEndX, blockEndZ;
        HeightmapData data;

        void update(int x, int z) {
            if (x < blockStartX || x >= blockEndX) this.init(x, z);
            if (z < blockStartZ || z >= blockEndZ) this.init(x, z);
        }

        void init(int x, int z) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);

            int tileX = x >> tileShift;
            int tileZ = z >> tileShift;

            this.blockStartX = tileX << tileShift;
            this.blockStartZ = tileZ << tileShift;
            this.blockEndX = blockStartX + tileSize;
            this.blockEndZ = blockStartZ + tileSize;

            this.data = LocalTerrainProvider.getInstance()
                .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        }
    }

    @Override
    public void fillArray(double[] densities, DensityFunction.ContextProvider applier) {
        if (densities.length == 0) return;

        FillContext ctx = new FillContext();
        DensityFunction.FunctionContext pos = applier.forIndex(0);
        int x = pos.blockX();
        int z = pos.blockZ();
        int y = pos.blockY();
        ctx.init(x, z);

        for (int i = 0; i < densities.length; i++) {
            pos = applier.forIndex(i);
            x = pos.blockX();
            z = pos.blockZ();
            y = pos.blockY();
            ctx.update(x, z);

            HeightmapData data = ctx.data;
            if (data == null || data.heightmap == null) {
                densities[i] = -y;
                continue;
            }

            int localX = Math.max(0, Math.min(data.width  - 1, x - ctx.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, z - ctx.blockStartZ));

            int targetHeight = HeightConverter
                .convertToMinecraftHeight(data.heightmap[localZ][localX]);
            double terrain = targetHeight - y;
            densities[i] = (!caves || terrain <= 0.0)
                    ? terrain
                    : carve(data, x, y, z, terrain, targetHeight,
                            sideSurface(data)[localZ * data.width + localX] - y, localX, localZ);
        }
    }

    private static double carve(HeightmapData data, int x, int y, int z,
                                double terrain, int targetHeight, double sideDepth,
                                int localX, int localZ) {
        double best = terrain;
        boolean underSea = targetHeight <= HeightConverter.SEA_LEVEL;
        int roof = underSea ? Math.max(CAVE_ROOF, SEA_FLOOR_ROOF) : CAVE_ROOF;
        boolean shallow = terrain < roof || sideDepth < roof;
        KarstNetwork karst = data.karst;
        if (karst != null && !karst.isEmpty() && !(shallow && underSea)) {
            float cave = shallow ? karst.dolineDensity(x, y, z, (float) best) : karst.density(x, y, z, (float) best);
            if (cave < best) best = cave;
        }
        int deepClear = underSea ? Math.max(DEEP_CLEARANCE, SEA_FLOOR_ROOF) : DEEP_CLEARANCE;
        if (!shallow && y <= DeepCaverns.TOP && y < targetHeight - deepClear) {
            float deep = DeepCaverns.density(x, y, z);
            if (deep < best) best = deep;
        }
        if (best < 0.0 && (TerrainWater.rimSolid(x, y, z)
                || nearRiverWater(data, localX, localZ, y))) return -best;
        return best;
    }

    @Override
    public DensityFunction mapAll(DensityFunction.Visitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return -4096;
    }

    @Override
    public double maxValue() {
        return 4096;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }
}
