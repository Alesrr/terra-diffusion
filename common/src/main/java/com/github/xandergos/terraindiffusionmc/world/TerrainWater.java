package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.BlockWater;
import com.github.xandergos.terraindiffusionmc.pipeline.DeepCaverns;
import com.github.xandergos.terraindiffusionmc.pipeline.KarstNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;

public final class TerrainWater {

    public static final int NO_WATER = Integer.MIN_VALUE;
    public static final int NO_GROUND = Integer.MIN_VALUE;

    private static final int POOL_DEPTH = 6;

    private static final long NO_BAND =
            ((long) Integer.MIN_VALUE << 32) | (Integer.MAX_VALUE & 0xffffffffL);

    private static final int PERCHED_AIR_SIDES = 3;

    private static final class TileRef {
        int startX = Integer.MIN_VALUE;
        int startZ = Integer.MIN_VALUE;
        int gen = -1;
        HeightmapData data;
    }

    private static final ThreadLocal<TileRef> LAST = ThreadLocal.withInitial(TileRef::new);

    private static final long BAND_UNKNOWN = Long.MAX_VALUE;

    // Column facts are cached per thread over a 32x32 block neighbourhood, slotted by the low bits of x and z
    private static final int SLOT_BITS = 5;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int SLOTS = 1 << (2 * SLOT_BITS);

    private static int slotOf(int x, int z) {
        return ((z & SLOT_MASK) << SLOT_BITS) | (x & SLOT_MASK);
    }

    private static final class Columns {
        final int[] x = new int[SLOTS];
        final int[] z = new int[SLOTS];
        final int[] gen = new int[SLOTS];
        final int[] ground = new int[SLOTS];
        final long[] band = new long[SLOTS];
        final byte[] bank = new byte[SLOTS];

        Columns() {
            java.util.Arrays.fill(gen, -1);
        }
    }

    private static final ThreadLocal<Columns> COLUMNS = ThreadLocal.withInitial(Columns::new);

    private static int slot(Columns c, int x, int z) {
        int s = slotOf(x, z);
        int g = LocalTerrainProvider.generation();
        if (c.x[s] != x || c.z[s] != z || c.gen[s] != g) {
            int ground = groundY(x, z);
            c.ground[s] = ground;
            c.band[s] = BAND_UNKNOWN;
            c.bank[s] = 0;
            c.x[s] = x;
            c.z[s] = z;
            c.gen[s] = g;
        }
        return s;
    }

    private static int cachedGround(int x, int z) {
        Columns c = COLUMNS.get();
        return c.ground[slot(c, x, z)];
    }

    private static long cachedBand(int x, int z) {
        Columns c = COLUMNS.get();
        int s = slot(c, x, z);
        long band = c.band[s];
        if (band == BAND_UNKNOWN) {
            band = bandAt(x, z, c.ground[s]);
            c.band[slot(c, x, z)] = band;
        }
        return band;
    }

    private TerrainWater() {
    }

    private static HeightmapData tileFor(int x, int z) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);

        int startX = (x >> tileShift) << tileShift;
        int startZ = (z >> tileShift) << tileShift;

        TileRef ref = LAST.get();
        int gen = LocalTerrainProvider.generation();
        if (ref.startX != startX || ref.startZ != startZ || ref.gen != gen) {
            ref.data = LocalTerrainProvider.getInstance().fetchHeightmap(
                    startZ, startX, startZ + tileSize, startX + tileSize);
            ref.startX = startX;
            ref.startZ = startZ;
            ref.gen = gen;
        }
        return ref.data;
    }

    // Highest y in the 16x16 column block at (minX, minZ) that the noise fill can make anything but air, or MAX_VALUE if unknown
    public static int highestFilled(int minX, int minZ, int seaLevel) {
        int top = Integer.MIN_VALUE;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = minX + dx, z = minZ + dz;
                int ground = groundY(x, z);
                if (ground == NO_GROUND) return Integer.MAX_VALUE;
                top = Math.max(top, ground - 1);
                int level = waterLevelY(x, z);
                int surface = level != NO_WATER ? level : ground < seaLevel ? seaLevel : NO_WATER;
                if (surface == NO_WATER) continue;
                top = Math.max(top, surface - 1);
                int flow = waterFlow(x, z);
                if (flow == 0) continue;
                if (flow <= BlockWater.MAX_RAMP) {
                    top = Math.max(top, surface);
                } else if (flow >= BlockWater.CURTAIN) {
                    top = Math.max(top, surface + flow - BlockWater.CURTAIN - 1);
                }
            }
        }
        return top;
    }

    private static int tileStart(int v) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        return (v >> tileShift) << tileShift;
    }

    public static int waterLevelY(int x, int z) {
        HeightmapData data = tileFor(x, z);
        if (data == null || data.waterLevel == null) {
            return NO_WATER;
        }
        int localX = x - tileStart(x);
        int localZ = z - tileStart(z);
        if (localX < 0 || localZ < 0 || localX >= data.width || localZ >= data.height) {
            return NO_WATER;
        }
        short meters = data.waterLevel[localZ][localX];
        if (meters == HeightmapData.NO_WATER) {
            return NO_WATER;
        }
        return HeightConverter.convertToMinecraftHeight(meters);
    }

    public static int waterFlow(int x, int z) {
        HeightmapData data = tileFor(x, z);
        if (data == null || data.waterFlow == null) {
            return 0;
        }
        int localX = x - tileStart(x);
        int localZ = z - tileStart(z);
        if (localX < 0 || localZ < 0 || localX >= data.width || localZ >= data.height) {
            return 0;
        }
        return data.waterFlow[localZ][localX] & 0xff;
    }

    public static int groundY(int x, int z) {
        HeightmapData data = tileFor(x, z);
        if (data == null || data.heightmap == null) {
            return NO_GROUND;
        }
        int localX = Math.max(0, Math.min(data.width - 1, x - tileStart(x)));
        int localZ = Math.max(0, Math.min(data.height - 1, z - tileStart(z)));
        return HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]);
    }

    public static KarstNetwork karstNetwork(int x, int z) {
        HeightmapData data = tileFor(x, z);
        return data == null || data.karst == null ? KarstNetwork.EMPTY : data.karst;
    }

    public static float karstWaterTableY(int x, int z) {
        KarstNetwork net = karstNetwork(x, z);
        return net.isEmpty() ? Float.NEGATIVE_INFINITY : net.waterTableY(x, z);
    }

    private static boolean openBlock(int x, int y, int z, int ground) {
        if (ground == NO_GROUND || y >= ground) return false;
        KarstNetwork net = karstNetwork(x, z);
        if (!net.isEmpty() && net.density(x, y, z, 0f) < 0f) return true;
        return y <= DeepCaverns.TOP && DeepCaverns.density(x, y, z) < 0f;
    }

    private static boolean wetNear(int x, int y, int z) {
        if (y <= DeepCaverns.TOP && DeepCaverns.fluid(x, y, z) == DeepCaverns.WATER) return true;
        float table = karstWaterTableY(x, z);
        if (Float.isInfinite(table)) return false;
        int levelY = Math.round(table);
        return y < levelY && y >= levelY - POOL_DEPTH;
    }

    // Pool extent for a column, packed as (top << 32) | bottom, or NO_BAND
    private static long bandAt(int x, int z, int ground) {
        if (ground == NO_GROUND) return NO_BAND;
        float table = karstWaterTableY(x, z);
        if (Float.isInfinite(table)) return NO_BAND;
        int levelY = Math.round(table);
        if (levelY >= ground) levelY = ground - 1;

        KarstNetwork net = karstNetwork(x, z);
        if (!net.isEmpty() && net.dolineDensity(x, levelY - 1, z, 0f) < 0f) return NO_BAND;

        int k = 1;
        while (k <= POOL_DEPTH + 1 && openBlock(x, levelY - k, z, ground)) k++;
        if (k == 1 || k > POOL_DEPTH + 1) return NO_BAND;
        return ((long) levelY << 32) | ((levelY - k + 1) & 0xffffffffL);
    }

    private static boolean covers(long band, int y) {
        return band != NO_BAND && y >= (int) band && y < (int) (band >> 32);
    }

    // True where a pool column faces open air sideways and needs a stone rim
    public static boolean rimSolid(int x, int y, int z) {
        long band = cachedBand(x, z);
        if (!covers(band, y)) return false;
        Columns c = COLUMNS.get();
        byte bank = c.bank[slot(c, x, z)];
        if (bank == 0) {
            bank = bankColumn(x, z, band) ? (byte) 2 : (byte) 1;
            c.bank[slot(c, x, z)] = bank;
        }
        return bank == 2;
    }

    private static final int RIM_RADIUS = 2;

    private static final int[] RIM_DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] RIM_DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private static boolean bankColumn(int x, int z, long band) {
        int bottom = (int) band, top = (int) (band >> 32);
        for (int r = 1; r <= RIM_RADIUS; r++) {
            for (int d = 0; d < 8; d++) {
                int nx = x + RIM_DX[d] * r;
                int nz = z + RIM_DZ[d] * r;
                int ng = cachedGround(nx, nz);
                long nb = cachedBand(nx, nz);
                for (int y = bottom; y < top; y++) {
                    if (!openBlock(nx, y, nz, ng)) continue;
                    if (!covers(nb, y)) return true;
                }
            }
        }
        return false;
    }

    // True inside an underground river reach, at or below its water line
    public static boolean undergroundRiver(int x, int y, int z) {
        KarstNetwork net = karstNetwork(x, z);
        if (net.isEmpty()) return false;
        float surface = net.riverWaterY(x, y, z);
        if (Float.isInfinite(surface) || y > Math.round(surface)) return false;
        return net.riverDensity(x, y, z, 0f) < 0f;
    }

    // A block with air on nearly every side is a perch
    private static boolean perched(int x, int y, int z) {
        int air = 0;
        for (int d = 0; d < 4; d++) {
            int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
            int nz = z + (d == 2 ? 1 : d == 3 ? -1 : 0);
            int ground = cachedGround(nx, nz);
            if (!openBlock(nx, y, nz, ground)) continue;
            if (wetNear(nx, y, nz)) continue;
            air++;
        }
        return air >= PERCHED_AIR_SIDES;
    }

    public static Aquifer.FluidPicker fluidPicker(int seaLevel, BlockState fluid) {
        Aquifer.FluidStatus ocean = new Aquifer.FluidStatus(seaLevel, fluid);
        Aquifer.FluidStatus dry =
                new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        Aquifer.FluidStatus stream = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2, fluid);
        Aquifer.FluidStatus lava =
                new Aquifer.FluidStatus(Integer.MAX_VALUE / 2, Blocks.LAVA.defaultBlockState());
        boolean levelled = fluid.hasProperty(LiquidBlock.LEVEL);
        Aquifer.FluidStatus[] flowing = new Aquifer.FluidStatus[BlockWater.MAX_RAMP + 1];
        for (int k = 1; k <= BlockWater.MAX_RAMP; k++) {
            flowing[k] = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2,
                    levelled ? fluid.setValue(LiquidBlock.LEVEL, k) : fluid);
        }
        Aquifer.FluidStatus falling = new Aquifer.FluidStatus(Integer.MAX_VALUE / 2,
                levelled ? fluid.setValue(LiquidBlock.LEVEL, 8) : fluid);
        ThreadLocal<PickerColumns> columns = ThreadLocal.withInitial(PickerColumns::new);

        return (x, y, z) -> {
            PickerColumns p = columns.get();
            int s = slotOf(x, z);
            int g = LocalTerrainProvider.generation();
            if (p.x[s] != x || p.z[s] != z || p.gen[s] != g) {
                int ground = cachedGround(x, z);
                int level = waterLevelY(x, z);
                long band = ground != NO_GROUND ? cachedBand(x, z) : NO_BAND;
                p.ground[s] = ground;
                p.status[s] = level == NO_WATER
                        ? ocean
                        : new Aquifer.FluidStatus(level, fluid);
                p.flow[s] = waterFlow(x, z);
                p.top[s] = level != NO_WATER ? level : ground != NO_GROUND && ground < seaLevel ? seaLevel : NO_WATER;
                p.streamTop[s] = (int) (band >> 32);
                p.streamBottom[s] = (int) band;
                p.x[s] = x;
                p.z[s] = z;
                p.gen[s] = g;
            }

            int ground = p.ground[s];
            if (ground == NO_GROUND || y >= ground) {
                int flow = p.flow[s];
                int top = p.top[s];
                if (flow != 0 && top != NO_WATER && y >= top) {
                    if (flow <= BlockWater.MAX_RAMP) {
                        if (y == top) return flowing[flow];
                    } else if (flow >= BlockWater.CURTAIN) {
                        int height = flow - BlockWater.CURTAIN;
                        if (y < top + height) return y == top + height - 1 ? flowing[1] : falling;
                    }
                }
                return p.status[s];
            }
            if (y <= DeepCaverns.LAVA_Y) {
                return lava;
            }
            if (undergroundRiver(x, y, z)) {
                return stream;
            }
            boolean wet = (y >= p.streamBottom[s] && y < p.streamTop[s])
                    || (y <= DeepCaverns.TOP && DeepCaverns.fluid(x, y, z) == DeepCaverns.WATER);
            if (!wet || perched(x, y, z)) {
                return dry;
            }
            return stream;
        };
    }

    private static final class PickerColumns {
        final int[] x = new int[SLOTS];
        final int[] z = new int[SLOTS];
        final int[] gen = new int[SLOTS];
        final int[] ground = new int[SLOTS];
        final int[] streamTop = new int[SLOTS];
        final int[] streamBottom = new int[SLOTS];
        final int[] flow = new int[SLOTS];
        final int[] top = new int[SLOTS];
        final Aquifer.FluidStatus[] status = new Aquifer.FluidStatus[SLOTS];

        PickerColumns() {
            java.util.Arrays.fill(gen, -1);
        }
    }
}
