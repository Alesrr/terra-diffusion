package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RiverHydrology {

    private static final int REGION_PX = 1024;

    private static final int MARGIN_PX =
            Integer.parseInt(System.getProperty("terradiff.margin", "512"));
    private static final int WINDOW_PX = REGION_PX + 2 * MARGIN_PX;

    private static final int DOWNSAMPLE = 1;
    private static final int GRID = WINDOW_PX / DOWNSAMPLE;

    // Judge a channel stranded across the whole window
    private static final boolean OCEAN_GATE =
            !"false".equals(System.getProperty("terradiff.oceanGate"));

    private static final int CROP_RIM = 28;
    private static final int CROP_OFF = MARGIN_PX / DOWNSAMPLE - CROP_RIM;
    private static final int CROP_N = REGION_PX / DOWNSAMPLE + 2 * CROP_RIM;

    private static float[] crop(float[] full) {
        float[] out = new float[CROP_N * CROP_N];
        for (int r = 0; r < CROP_N; r++) {
            System.arraycopy(full, (CROP_OFF + r) * GRID + CROP_OFF, out, r * CROP_N, CROP_N);
        }
        return out;
    }

    private static final float RIVER_THRESHOLD = 600f;

    private static final float WIDTH_GROWTH_DECADES = 2.0f;

    private static final float DISCHARGE_AREA_EXPONENT = 0.8f;
    private static final float WIDTH_Q_EXPONENT = 0.5f;
    private static final float DEPTH_Q_EXPONENT = 0.4f;

    private static final float BANKFULL_WIDTH_M =
            Float.parseFloat(System.getProperty("terradiff.bankfullWidth", "58"));
    private static final float BANKFULL_DEPTH_M = 9f;

    private static final float MAX_WIDTH_M = 340f;
    private static final float MAX_DEPTH_M = 45f;

    private static final float CONFINEMENT_FRACTION =
            Float.parseFloat(System.getProperty("terradiff.confinement", "0.72"));

    private static final int CONFINEMENT_SCAN_CELLS = 16;
    private static final float CONFINEMENT_RISE_M = 18f;

    private static final float ORDER_WIDTH_GAIN = 0.06f;

    private static final float FREEBOARD_BLOCKS = 0.5f;

    private static final float GORGE_LIMIT_BLOCKS = 3f;

    private static final float FALL_MIN_DROP_BLOCKS = 2f;

    private static final boolean FALLS_ENABLED =
            Boolean.getBoolean("terradiff.falls");

    private static final int FIELD_SMOOTH_PASSES =
            Integer.parseInt(System.getProperty("terradiff.fieldSmooth", "12"));

    private static final boolean EXACT_DISTANCE =
            !"false".equals(System.getProperty("terradiff.exactDistance"));

    private static final boolean SECTION_STAMP =
            !"false".equals(System.getProperty("terradiff.sectionStamp"));

    private static final float STAMP_MIN_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.stampMinBlocks", "5"));

    private static final float STAMP_REACH =
            Float.parseFloat(System.getProperty("terradiff.stampReach", "3.0"));

    private static final int FIELD_BLUR_PASSES =
            Integer.parseInt(System.getProperty("terradiff.fieldBlur", "16"));

    private static final int DEQUANT_PASSES =
            Integer.parseInt(System.getProperty("terradiff.dequantPasses", "8"));

    private static final float OVERLAP_REACH =
            Float.parseFloat(System.getProperty("terradiff.overlapReach", "2.0"));

    private static final float MIN_SOURCE_ELEV_M = 200f;

    private static final float HOLD_QUANTILE =
            Float.parseFloat(System.getProperty("terradiff.holdQuantile", "0.15"));

    private static final float OVERLAP_AGREE_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.overlapAgree", "0.5"));

    private static final int ARC_BLUR_PASSES =
            Integer.parseInt(System.getProperty("terradiff.arcBlur", "6"));

    private static final int SCAN_CAP =
            Integer.parseInt(System.getProperty("terradiff.scanCap", "20"));

    private static final boolean MIN_CORNER_WATER = Boolean.getBoolean("terradiff.minCornerWater");

    private static final boolean REJECT_DRAINED_LAKES =
            !"false".equals(System.getProperty("terradiff.rejectDrainedLakes"));

    private static final boolean TIMING = Boolean.getBoolean("terradiff.timing");

    private static long PHASE_T0;

    private static void phase(String name) {
        InfiniteTensor.throwIfCancelled();
        if (!TIMING) {
            return;
        }
        long now = System.nanoTime();
        System.err.printf("diag phase %-16s %7.0f ms%n", name, (now - PHASE_T0) / 1.0e6);
        PHASE_T0 = now;
    }

    private static final float MAX_INFLUENCE_CELLS = 12f;

    private static final float FILL_EPSILON_M = 1.0e-2f;

    private static final float FLAT_DROP_M = 0.5f;

    private static final float FLAT_TILT_MAX_M = 0.4f;

    private static final float FLAT_GRADIENT_M = 1.0e-3f;

    private static final int[] FACET_CR = { 0, -1, -1, 0, 0, 1, 1, 0};
    private static final int[] FACET_CC = { 1, 0, 0, -1, -1, 0, 0, 1};
    private static final int[] FACET_DR = {-1, -1, -1, -1, 1, 1, 1, 1};
    private static final int[] FACET_DC = { 1, 1, -1, -1, -1, -1, 1, 1};

    private static final float CELL_SIZE_M = 30f;

    private static final double CHANNEL_SLOPE_EXPONENT = 2.0;

    private static final double CHANNEL_INITIATION = 900.0;

    // Ceiling on the slope term at channel initiation
    private static final double CHANNEL_SLOPE_CAP =
            Double.parseDouble(System.getProperty("terradiff.slopeCap", "0.5"));

    private static final float CONVERGENCE_MIN_M = 0.35f;

    private static final int CONVERGENCE_RADIUS = 3;

    private static final double MFD_EXPONENT = 1.1;

    private static final FastNoiseLite ROUTING_DETAIL = makeRoutingNoise();

    private static final float ROUTING_DETAIL_M = 1.0f;

    private static FastNoiseLite makeRoutingNoise() {
        FastNoiseLite fnl = new FastNoiseLite(0x5EA1);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);

        fnl.SetFrequency(1f / 22f);
        fnl.SetFractalOctaves(1);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private static final int MEANDER_TANGENT_CELLS = 8;

    private static final float MEANDER_CLIMB_M = 3f;

    private static final float[] MEANDER_BAND_CELLS = {8f, 24f, 72f};
    private static final FastNoiseLite[] MEANDER_BAND_I = {
            makeMeanderNoise(0x51DE, MEANDER_BAND_CELLS[0]),
            makeMeanderNoise(0x51DF, MEANDER_BAND_CELLS[1]),
            makeMeanderNoise(0x51E0, MEANDER_BAND_CELLS[2])};
    private static final FastNoiseLite[] MEANDER_BAND_J = {
            makeMeanderNoise(0x7A2C, MEANDER_BAND_CELLS[0]),
            makeMeanderNoise(0x7A2D, MEANDER_BAND_CELLS[1]),
            makeMeanderNoise(0x7A2E, MEANDER_BAND_CELLS[2])};

    private static final float MEANDER_AMPLITUDE_WIDTHS = 2.4f;
    private static final float MEANDER_ASPECT = 4.4f;

    private static final float MEANDER_MIN_AMPLITUDE_M = 78f;

    private static final float MEANDER_SPACE_FRACTION = 0.42f;

    private static FastNoiseLite makeMeanderNoise(int seed, float wavelengthCells) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(1f / wavelengthCells);
        fnl.SetFractalOctaves(2);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.45f);
        return fnl;
    }

    private static final float MOUTH_AREA = 140000f;

    private static final float MIN_TRIBUTARY_AREA = 40000f;

    private static final float LAKE_MIN_DEPTH_M = 15f;

    private static final int LAKE_MIN_CELLS = 150;

    private static final int LAKE_MAX_SPAN = MARGIN_PX;

    private static final float LAKE_FLOOR_M = 0.5f;

    private static final int LAKE_RIM_CELLS = 3;

    private static final float LAKE_MIN_SEA_CELLS = 8f;

    private static final int LAKE_MIN_LANDMASS_CELLS = 20000;

    public static final class Region {
        final int regionI, regionJ;
        final float[] distance;
        final float[] magnitude;
        final float[] water;
        final float[] lake;
        final float[] lakeDeep;
        final float[] delta;
        final float[] width;
        final float[] depth;
        final float[] fall;
        final KarstNetwork karst;
        private int[] lakeBody;
        private byte[] bodyVerdict;

        Region(int regionI, int regionJ, float[] distance, float[] magnitude, float[] water,
               float[] lake, float[] lakeDeep, float[] delta,
               float[] width, float[] depth, float[] fall, KarstNetwork karst) {
            this.regionI = regionI;
            this.regionJ = regionJ;
            this.distance = distance;
            this.magnitude = magnitude;
            this.water = water;
            this.lake = lake;
            this.lakeDeep = lakeDeep;
            this.delta = delta;
            this.width = width;
            this.depth = depth;
            this.fall = fall;
            this.karst = karst;
        }
    }

    private static final float SEAM_BLEND_CELLS =
            Float.parseFloat(System.getProperty("terradiff.seamBlend", "24"));

    private static final int MAX_CACHED_REGIONS = 6;

    private static float[] ARC_POS;

    // Hydrology regions built for one pipeline, least recently used evicted first
    public static final class RegionCache {
        private final Map<Long, Region> regions = new ConcurrentHashMap<>();
        private final Map<Long, AtomicLong> used = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();
        private boolean seamNeighbours = true;

        public void clear() {
            regions.clear();
            used.clear();
        }

        // When off, a seam blends only with a neighbouring region that is already built
        public void buildSeamNeighbours(boolean on) {
            seamNeighbours = on;
        }
    }

    // Builds every region overlapping the native rectangle [i0, i1) x [j0, j1)
    public static void prepare(WorldPipeline pipeline, int i0, int j0, int i1, int j1, float blockM) {
        int ri0 = Math.floorDiv(i0, REGION_PX), ri1 = Math.floorDiv(i1 - 1, REGION_PX);
        int rj0 = Math.floorDiv(j0, REGION_PX), rj1 = Math.floorDiv(j1 - 1, REGION_PX);
        for (int ri = ri0; ri <= ri1; ri++) {
            for (int rj = rj0; rj <= rj1; rj++) {
                regionFor(pipeline, ri, rj, blockM);
            }
        }
    }

    private RiverHydrology() {
    }

    private static long key(int regionI, int regionJ) {
        return (((long) regionI) << 32) ^ (regionJ & 0xFFFFFFFFL);
    }

    public static KarstNetwork karstAt(WorldPipeline pipeline, float nativeI, float nativeJ,
                                      float blockM) {
        if (!KarstHydrology.ENABLED) {
            return KarstNetwork.EMPTY;
        }
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, ri, rj, blockM);
        return region == null || region.karst == null ? KarstNetwork.EMPTY : region.karst;
    }

    public static float[] sampleChannel(WorldPipeline pipeline, float nativeI, float nativeJ,
                                       float blockM) {
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        float[] own = sampleFrom(pipeline, ri, rj, nativeI, nativeJ, blockM);
        if (own == null || SEAM_BLEND_CELLS <= 0) {
            return own;
        }

        float fi = nativeI - ri * (float) REGION_PX;
        float fj = nativeJ - rj * (float) REGION_PX;
        float di = Math.min(fi, REGION_PX - fi);
        float dj = Math.min(fj, REGION_PX - fj);
        int ni = ri, nj = rj;
        float dist;
        if (di <= dj) {
            dist = di;
            ni = fi < REGION_PX - fi ? ri - 1 : ri + 1;
        } else {
            dist = dj;
            nj = fj < REGION_PX - fj ? rj - 1 : rj + 1;
        }
        if (dist >= SEAM_BLEND_CELLS) {
            return own;
        }
        RegionCache cache = pipeline.hydrology;
        if (!cache.seamNeighbours && !cache.regions.containsKey(key(ni, nj))) {
            return own;
        }
        float[] other = sampleFrom(pipeline, ni, nj, nativeI, nativeJ, blockM);
        if (other == null) {
            return own;
        }

        float wOther = 0.5f * (1f - dist / SEAM_BLEND_CELLS);
        for (int k = 0; k < own.length; k++) {
            own[k] = own[k] + (other[k] - own[k]) * wOther;
        }
        return own;
    }

    private static float[] sampleFrom(WorldPipeline pipeline, int regionI, int regionJ,
                                      float nativeI, float nativeJ, float blockM) {
        Region region = regionFor(pipeline, regionI, regionJ, blockM);
        if (region == null) {
            return null;
        }

        int windowI = regionI * REGION_PX - MARGIN_PX;
        int windowJ = regionJ * REGION_PX - MARGIN_PX;

        float gi = (nativeI - windowI) / (float) DOWNSAMPLE - CROP_OFF;
        float gj = (nativeJ - windowJ) / (float) DOWNSAMPLE - CROP_OFF;
        int i0 = (int) Math.floor(gi), j0 = (int) Math.floor(gj);
        if (i0 < 0 || j0 < 0 || i0 >= CROP_N - 1 || j0 >= CROP_N - 1) {
            return null;
        }
        float ti = gi - i0, tj = gj - j0;

        int a = i0 * CROP_N + j0;
        int b = a + 1;
        int c = a + CROP_N;
        int d2 = c + 1;

        float d = lerp(lerp(region.distance[a], region.distance[b], tj),
                       lerp(region.distance[c], region.distance[d2], tj), ti);
        if (d >= MAX_INFLUENCE_CELLS) {
            return null;
        }
        float m = lerp(lerp(region.magnitude[a], region.magnitude[b], tj),
                       lerp(region.magnitude[c], region.magnitude[d2], tj), ti);

        float w = Float.NaN;
        if (MIN_CORNER_WATER) {
            float[] corners = {region.water[a], region.water[b], region.water[c], region.water[d2]};
            for (float cw : corners) {
                if (!Float.isNaN(cw) && (Float.isNaN(w) || cw < w)) {
                    w = cw;
                }
            }
        } else {
            w = region.water[nearest(region, gi, gj)];
        }
        if (Float.isNaN(w)) {
            w = region.water[nearest(region, gi, gj)];
        }
        if (Float.isNaN(w)) {
            w = 0f;
        }

        float wa = region.water[a], wb = region.water[b];
        float wc = region.water[c], wd = region.water[d2];
        float ws = (Float.isNaN(wa) || Float.isNaN(wb) || Float.isNaN(wc) || Float.isNaN(wd))
                ? w
                : lerp(lerp(wa, wb, tj), lerp(wc, wd, tj), ti);
        float dw = region.delta == null ? 0f
                : lerp(lerp(region.delta[a], region.delta[b], tj),
                       lerp(region.delta[c], region.delta[d2], tj), ti);

        float wid = lerp(lerp(region.width[a], region.width[b], tj),
                         lerp(region.width[c], region.width[d2], tj), ti);
        float dep = lerp(lerp(region.depth[a], region.depth[b], tj),
                         lerp(region.depth[c], region.depth[d2], tj), ti);

        float fl = Math.max(Math.max(region.fall[a], region.fall[b]),
                            Math.max(region.fall[c], region.fall[d2]));
        return new float[]{d * DOWNSAMPLE, m, w, dw, ws, wid, dep, fl};
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // A lake body crossing into a neighbour must be mostly lake there too, or it is dropped
    private static final float LAKE_AGREE =
            Float.parseFloat(System.getProperty("terradiff.lakeAgree", "0.25"));

    private static final byte BODY_KEEP = 1, BODY_DROP = 2;

    private static final int[][] NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    // Labels each 8-connected lake body in the region's crop; -1 where there is no lake
    private static synchronized int[] lakeBodies(Region region) {
        if (region.lakeBody != null) {
            return region.lakeBody;
        }
        int n = CROP_N * CROP_N;
        int[] label = new int[n];
        Arrays.fill(label, -1);
        int[] stack = new int[n];
        int bodies = 0;
        for (int seed = 0; seed < n; seed++) {
            if (label[seed] != -1 || Float.isNaN(region.lake[seed])) continue;
            int top = 0;
            stack[top++] = seed;
            label[seed] = bodies;
            while (top > 0) {
                int k = stack[--top];
                int r = k / CROP_N, c = k % CROP_N;
                for (int dr = -1; dr <= 1; dr++) {
                    int rr = r + dr;
                    if (rr < 0 || rr >= CROP_N) continue;
                    for (int dc = -1; dc <= 1; dc++) {
                        int cc = c + dc;
                        if ((dr == 0 && dc == 0) || cc < 0 || cc >= CROP_N) continue;
                        top = claim(region, label, stack, top, rr * CROP_N + cc, bodies);
                    }
                }
            }
            bodies++;
        }
        region.bodyVerdict = new byte[bodies];
        region.lakeBody = label;
        return label;
    }

    private static int claim(Region region, int[] label, int[] stack, int top, int k, int body) {
        if (label[k] == -1 && !Float.isNaN(region.lake[k])) {
            label[k] = body;
            stack[top++] = k;
        }
        return top;
    }

    // Whether the lake body holding this cell survives comparison with every neighbour it reaches
    private static boolean lakeConfirmed(WorldPipeline pipeline, Region region, int at, float blockM) {
        if (LAKE_AGREE <= 0f) {
            return true;
        }
        int body = lakeBodies(region)[at];
        byte verdict = region.bodyVerdict[body];
        if (verdict == 0) {
            verdict = judgeBody(pipeline, region, body, blockM);
            region.bodyVerdict[body] = verdict;
        }
        return verdict == BODY_KEEP;
    }

    private static byte judgeBody(WorldPipeline pipeline, Region region, int body, float blockM) {
        int[] label = region.lakeBody;
        int band = 2 * CROP_RIM;
        for (int[] d : NEIGHBOURS) {
            int r0 = d[0] > 0 ? CROP_N - band : 0, r1 = d[0] < 0 ? band : CROP_N;
            int c0 = d[1] > 0 ? CROP_N - band : 0, c1 = d[1] < 0 ? band : CROP_N;

            boolean reaches = false;
            for (int r = r0; r < r1 && !reaches; r++) {
                for (int c = c0; c < c1; c++) {
                    if (label[r * CROP_N + c] == body) { reaches = true; break; }
                }
            }
            if (!reaches) continue;

            Region other = regionFor(pipeline, region.regionI + d[0], region.regionJ + d[1], blockM);
            if (other == null || other.lake == null) continue;
            int seen = 0, agree = 0;
            for (int r = r0; r < r1; r++) {
                for (int c = c0; c < c1; c++) {
                    if (label[r * CROP_N + c] != body) continue;
                    int ni = (r + CROP_OFF) * DOWNSAMPLE + region.regionI * REGION_PX - MARGIN_PX;
                    int nj = (c + CROP_OFF) * DOWNSAMPLE + region.regionJ * REGION_PX - MARGIN_PX;
                    int oi = (ni - (other.regionI * REGION_PX - MARGIN_PX)) / DOWNSAMPLE - CROP_OFF;
                    int oj = (nj - (other.regionJ * REGION_PX - MARGIN_PX)) / DOWNSAMPLE - CROP_OFF;
                    if (oi < 0 || oj < 0 || oi >= CROP_N || oj >= CROP_N) continue;
                    seen++;
                    if (!Float.isNaN(other.lake[oi * CROP_N + oj])) agree++;
                }
            }
            if (seen > 0 && agree < LAKE_AGREE * seen) return BODY_DROP;
        }
        return BODY_KEEP;
    }

    public static float[] sampleLake(WorldPipeline pipeline, float nativeI, float nativeJ,
                                    float blockM) {
        int regionI = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int regionJ = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, regionI, regionJ, blockM);
        if (region == null || region.lake == null) {
            return null;
        }
        float gi = (nativeI - (regionI * REGION_PX - MARGIN_PX)) / (float) DOWNSAMPLE - CROP_OFF;
        float gj = (nativeJ - (regionJ * REGION_PX - MARGIN_PX)) / (float) DOWNSAMPLE - CROP_OFF;
        int i0 = (int) Math.floor(gi), j0 = (int) Math.floor(gj);
        if (i0 < 0 || j0 < 0 || i0 >= CROP_N - 1 || j0 >= CROP_N - 1) {
            return null;
        }

        int at = i0 * CROP_N + j0;
        float near = region.lake[at];
        if (Float.isNaN(near)) {
            return null;
        }
        if (!lakeConfirmed(pipeline, region, at, blockM)) {
            return null;
        }
        float deep = region.lakeDeep == null || Float.isNaN(region.lakeDeep[at])
                ? 0f : region.lakeDeep[at];
        return new float[]{(float) Math.ceil(Math.floor(near / blockM) * blockM), deep};
    }

    private static int[] landmassArea(float[] elev) {
        int n = GRID * GRID;
        int[] area = new int[n];
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int[] nr4 = {-1, 1, 0, 0}, nc4 = {0, 0, -1, 1};

        for (int start = 0; start < n; start++) {
            if (seen[start] || elev[start] < 0f) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int i = queue[head++];
                int r = i / GRID, c = i % GRID;
                for (int k = 0; k < 4; k++) {
                    int rr = r + nr4[k], cc = c + nc4[k];
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int j = rr * GRID + cc;
                    if (seen[j] || elev[j] < 0f) {
                        continue;
                    }
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }
            for (int m = 0; m < tail; m++) {
                area[queue[m]] = tail;
            }
        }
        return area;
    }

    private static float[][] lakes(float[] elev, float[] fillDepth, float[] seaDist, int[] landArea,
                                 boolean[] channel) {
        int n = GRID * GRID;
        float[] lake = new float[n];
        Arrays.fill(lake, Float.NaN);

        float[] deep = new float[n];
        Arrays.fill(deep, Float.NaN);

        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int[] nr4 = {-1, 1, 0, 0}, nc4 = {0, 0, -1, 1};

        for (int start = 0; start < n; start++) {
            if (seen[start] || elev[start] < 0f || fillDepth[start] <= LAKE_FLOOR_M) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;

            float deepest = 0f;
            float nearestSea = Float.MAX_VALUE;
            boolean drained = false;
            int minR = GRID, maxR = -1, minC = GRID, maxC = -1;

            while (head < tail) {
                int i = queue[head++];
                if (fillDepth[i] > deepest) {
                    deepest = fillDepth[i];
                }
                if (channel[i]) {
                    drained = true;
                }
                if (seaDist[i] < nearestSea) {
                    nearestSea = seaDist[i];
                }
                int r = i / GRID, c = i % GRID;
                if (r < minR) minR = r;
                if (r > maxR) maxR = r;
                if (c < minC) minC = c;
                if (c > maxC) maxC = c;

                for (int k = 0; k < 4; k++) {
                    int rr = r + nr4[k], cc = c + nc4[k];
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int j = rr * GRID + cc;
                    if (seen[j] || elev[j] < 0f || fillDepth[j] <= LAKE_FLOOR_M) {
                        continue;
                    }
                    seen[j] = true;
                    queue[tail++] = j;
                }
            }

            boolean atBorder = minR == 0 || minC == 0 || maxR == GRID - 1 || maxC == GRID - 1;

            if ((REJECT_DRAINED_LAKES && drained) || atBorder
                    || deepest < LAKE_MIN_DEPTH_M || tail < LAKE_MIN_CELLS
                    || nearestSea < LAKE_MIN_SEA_CELLS
                    || landArea[start] < LAKE_MIN_LANDMASS_CELLS
                    || maxR - minR + 1 > LAKE_MAX_SPAN || maxC - minC + 1 > LAKE_MAX_SPAN) {
                continue;
            }
            int accepted = 0;
            for (int m = 0; m < tail; m++) {
                int i = queue[m];
                lake[i] = elev[i] + fillDepth[i];
                deep[i] = deepest;
                accepted++;
            }
            if (Boolean.getBoolean("terradiff.diag")) {
                System.err.printf(
                        "diag lake cells=%d span=%dx%d deepest=%.1fm spill=%.1fm centreLocal=%d,%d%n",
                        accepted, maxR - minR + 1, maxC - minC + 1, deepest,
                        elev[start] + fillDepth[start], (minR + maxR) / 2, (minC + maxC) / 2);
            }
        }

        for (int pass = 0; pass < LAKE_RIM_CELLS; pass++) {
            float[] rim = lake.clone();
            float[] rimDeep = deep.clone();
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) {
                    int i = r * GRID + c;
                    if (!Float.isNaN(rim[i])) {
                        continue;
                    }
                    float best = Float.NaN, bestDeep = Float.NaN;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int rr = r + dr, cc = c + dc;
                            if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                                continue;
                            }
                            float v = rim[rr * GRID + cc];
                            if (!Float.isNaN(v) && (Float.isNaN(best) || v > best)) {
                                best = v;
                                bestDeep = rimDeep[rr * GRID + cc];
                            }
                        }
                    }
                    if (!Float.isNaN(best) && elev[i] >= best) {
                        lake[i] = best;
                        deep[i] = bestDeep;
                    }
                }
            }
        }
        return new float[][]{lake, deep};
    }

    private static long pack(float key, int index) {
        int bits = Float.floatToIntBits(key);
        bits ^= (bits >> 31) & 0x7FFFFFFF;
        return (((long) bits) << 32) | (index & 0xFFFFFFFFL);
    }

    private static int[] orderBy(float[] key, int n) {
        long[] packed = new long[n];
        for (int i = 0; i < n; i++) {
            packed[i] = pack(key[i], i);
        }
        Arrays.sort(packed);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = (int) packed[i];
        }
        return order;
    }

    private static final class LongHeap {
        private long[] heap;
        private int size;

        LongHeap(int capacity) {
            this.heap = new long[Math.max(16, capacity / 4)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(long value) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, heap.length * 2);
            }
            int i = size++;
            heap[i] = value;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heap[parent] <= heap[i]) break;
                long t = heap[parent]; heap[parent] = heap[i]; heap[i] = t;
                i = parent;
            }
        }

        long pop() {
            long top = heap[0];
            heap[0] = heap[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, small = i;
                if (l < size && heap[l] < heap[small]) small = l;
                if (r < size && heap[r] < heap[small]) small = r;
                if (small == i) break;
                long t = heap[small]; heap[small] = heap[i]; heap[i] = t;
                i = small;
            }
            return top;
        }
    }

    private static int nearest(Region region, float gi, float gj) {
        int r = Math.max(0, Math.min(CROP_N - 1, Math.round(gi)));
        int c = Math.max(0, Math.min(CROP_N - 1, Math.round(gj)));
        return r * CROP_N + c;
    }

    private static Region regionFor(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        RegionCache cache = pipeline.hydrology;
        long k = key(regionI, regionJ);
        Region cached = cache.regions.get(k);
        if (cached != null) {
            cache.used.computeIfAbsent(k, x -> new AtomicLong()).set(cache.clock.incrementAndGet());
            return cached;
        }

        Region built = build(pipeline, regionI, regionJ, blockM);
        cache.regions.put(k, built);
        cache.used.put(k, new AtomicLong(cache.clock.incrementAndGet()));
        evict(cache);
        return built;
    }

    private static void evict(RegionCache cache) {
        if (cache.regions.size() <= MAX_CACHED_REGIONS) {
            return;
        }
        cache.used.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().get()))
                .limit(Math.max(1, cache.regions.size() - MAX_CACHED_REGIONS))
                .map(Map.Entry::getKey)
                .forEach(k -> { cache.regions.remove(k); cache.used.remove(k); });
    }

    private static Region build(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        PHASE_T0 = System.nanoTime();
        int windowI = regionI * REGION_PX - MARGIN_PX;
        int windowJ = regionJ * REGION_PX - MARGIN_PX;

        float[] fine = pipeline.get(windowI, windowJ,
                windowI + WINDOW_PX, windowJ + WINDOW_PX, false)[0];
        phase("modelDecode");

        float[] elev = new float[GRID * GRID];
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++) {
                float sum = 0;
                for (int dr = 0; dr < DOWNSAMPLE; dr++)
                    for (int dc = 0; dc < DOWNSAMPLE; dc++)
                        sum += fine[(r * DOWNSAMPLE + dr) * WINDOW_PX + (c * DOWNSAMPLE + dc)];
                elev[r * GRID + c] = sum / (DOWNSAMPLE * DOWNSAMPLE);
            }

        float[] routing = new float[GRID * GRID];
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                float nx = (windowJ + c) * (float) DOWNSAMPLE;
                float ny = (windowI + r) * (float) DOWNSAMPLE;

                float offSea = Math.min(1f, Math.abs(elev[i]) / (2f * ROUTING_DETAIL_M));
                routing[i] = elev[i]
                        + ROUTING_DETAIL.GetNoise(nx, ny) * ROUTING_DETAIL_M * offSea;
            }
        }

        phase("elev+routing");
        float[] seaDist = seaDistance(elev);
        float[] fillDepth = new float[GRID * GRID];
        int[] down = new int[GRID * GRID];
        float[] slope = new float[GRID * GRID];

        phase("seaDistance");
        float[] acc = accumulate(pipeline, routing, elev, fillDepth, down, slope, windowI, windowJ,
                blockM, seaDist, null);

        float[] distance = new float[GRID * GRID];
        float[] magnitude = new float[GRID * GRID];
        Arrays.fill(distance, Float.MAX_VALUE);

        boolean[] channel = new boolean[GRID * GRID];
        float[] surface = new float[GRID * GRID];
        phase("accumulate");
        for (int i = 0; i < GRID * GRID; i++) surface[i] = routing[i] + fillDepth[i];
        int[] bySurface = orderBy(surface, GRID * GRID);

        for (int i = 0; i < GRID * GRID; i++) {
            if (elev[i] < 0f) {
                continue;
            }
            double s = Math.max(slope[i], 1.0e-6);

            int rr0 = i / GRID, cc0 = i % GRID;
            if (rr0 == 0 || cc0 == 0 || rr0 == GRID - 1 || cc0 == GRID - 1) {
                continue;
            }

            double sGate = Math.min(s, CHANNEL_SLOPE_CAP);
            // MIN_SOURCE_ELEV_M asks where a river may be BORN, so it must not be asked of one
            boolean continuation = acc[i] >= SEED_MIN_CELLS;
            if ((elev[i] >= MIN_SOURCE_ELEV_M || continuation)
                    && acc[i] * Math.pow(sGate, CHANNEL_SLOPE_EXPONENT) >= CHANNEL_INITIATION) {
                channel[i] = true;
            }
        }

        if (Boolean.getBoolean("terradiff.diag")) {
            int chan = 0, negDown = 0, nonAdj = 0, mouths = 0, cont = 0, zeroDown = 0, broken = 0;
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || elev[i] < 0f) {
                    continue;
                }
                chan++;
                int d = successor(down, i);
                if (d >= 0) {
                    if (elev[d] < 0f) {
                        mouths++;
                    } else if (channel[d]) {
                        cont++;
                    } else {
                        broken++;
                    }
                } else if (down[i] < 0) {
                    negDown++;
                } else {
                    nonAdj++;
                    if (down[i] == 0) zeroDown++;
                }
            }
            System.err.printf(
                    "diag region %d,%d: channel=%d continues=%d mouths=%d stops(down<0)=%d stops(nonAdjacent)=%d broken(successor not channel)=%d%n",
                    regionI, regionJ, chan, cont, mouths, negDown, nonAdj, broken);
        }

        for (int start = 0; start < GRID * GRID; start++) {
            if (!channel[start]) {
                continue;
            }
            int at = start;
            for (int step = 0; step < GRID * 4; step++) {
                int d = successor(down, at);
                if (d < 0 || elev[d] < 0f || channel[d]) {
                    break;
                }
                channel[d] = true;
                at = d;
            }
        }

        final byte UNKNOWN = 0, REACHES = 1, STRANDED = 2;
        byte[] fate = new byte[GRID * GRID];
        int[] path = new int[GRID * 4 + 2];
        for (int startCell = 0; startCell < GRID * GRID; startCell++) {
            if (!channel[startCell] || fate[startCell] != UNKNOWN) {
                continue;
            }
            int len = 0;
            int at = startCell;
            byte verdict = STRANDED;
            while (len < path.length) {
                path[len++] = at;
                fate[at] = STRANDED;
                int r = at / GRID, c = at % GRID;

                int inRegionLow = OCEAN_GATE ? 1 : MARGIN_PX / DOWNSAMPLE;
                int inRegionHigh = OCEAN_GATE
                        ? GRID - 1
                        : (MARGIN_PX + REGION_PX) / DOWNSAMPLE;
                if (r < inRegionLow || c < inRegionLow || r >= inRegionHigh || c >= inRegionHigh) {
                    verdict = REACHES;
                    break;
                }
                int d = successor(down, at);
                if (d < 0) {
                    break;
                }
                if (elev[d] < 0f) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == REACHES) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == STRANDED && d != at) {

                    break;
                }
                at = d;
            }
            for (int k = 0; k < len; k++) {
                fate[path[k]] = verdict;
            }
        }
        int pruned = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            if (channel[i] && fate[i] == STRANDED) {
                channel[i] = false;
                pruned++;
            }
        }
        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag pruned %d stranded channel cells%n", pruned);
        }
        int[] chanIdx = channelCells(channel, null);
        int[] chanUp = channelCells(channel, bySurface);

        if (Boolean.getBoolean("terradiff.diag")) {
            int chan2 = 0, brk2 = 0, mouth2 = 0, dead2 = 0;
            int deadBorder = 0, deadInterior = 0, deadNearSea = 0;
            float deadElevSum = 0f;
            int deadMinSeaDist = Integer.MAX_VALUE;
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || elev[i] < 0f) continue;
                chan2++;
                int d = successor(down, i);
                if (d < 0) {
                    dead2++;
                    int r = i / GRID, c = i % GRID;
                    boolean border = r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1;
                    if (border) deadBorder++; else deadInterior++;
                    deadElevSum += elev[i];
                    int sd = (int) seaDist[i];
                    deadMinSeaDist = Math.min(deadMinSeaDist, sd);
                    if (sd <= 8) deadNearSea++;
                }
                else if (elev[d] < 0f) mouth2++;
                else if (!channel[d]) brk2++;
            }
            System.err.printf("diag AFTER walk: channel=%d mouths=%d deadEnds=%d broken=%d%n",
                    chan2, mouth2, dead2, brk2);

            int repeats = 0, turns = 0, longestRun = 0;
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || elev[i] < 0f) continue;
                int d = successor(down, i);
                if (d < 0 || !channel[d]) continue;
                int e = successor(down, d);
                if (e < 0 || !channel[e]) continue;
                int d1r = (d / GRID) - (i / GRID), d1c = (d % GRID) - (i % GRID);
                int d2r = (e / GRID) - (d / GRID), d2c = (e % GRID) - (d % GRID);
                if (d1r == d2r && d1c == d2c) repeats++; else turns++;
            }
            for (int start = 0; start < GRID * GRID; start++) {
                if (!channel[start] || elev[start] < 0f) continue;
                int at = start, run = 1, pr = Integer.MIN_VALUE, pc = Integer.MIN_VALUE;
                for (int step = 0; step < 512; step++) {
                    int d = successor(down, at);
                    if (d < 0 || !channel[d]) break;
                    int dr = (d / GRID) - (at / GRID), dc = (d % GRID) - (at % GRID);
                    if (dr == pr && dc == pc) {
                        run++;
                        if (run > longestRun) longestRun = run;
                    } else {
                        run = 1;
                    }
                    pr = dr; pc = dc;
                    at = d;
                }
            }
            int dirTotal = repeats + turns;
            System.err.printf("diag straightness: sameStep=%.1f%% (%d of %d)  longestRun=%d cells%n",
                    dirTotal == 0 ? 0.0 : 100.0 * repeats / dirTotal, repeats, dirTotal, longestRun);
            System.err.printf("diag deadEnds: border=%d interior=%d nearSea(<=8cells)=%d meanElev=%.1fm minSeaDist=%d%n",
                    deadBorder, deadInterior, deadNearSea,
                    dead2 == 0 ? 0f : deadElevSum / dead2,
                    deadMinSeaDist == Integer.MAX_VALUE ? -1 : deadMinSeaDist);

            int trunk = -1;
            float bestAcc = 0f;
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || elev[i] < 0f || seaDist[i] < 10f) continue;
                if (acc[i] > bestAcc) { bestAcc = acc[i]; trunk = i; }
            }
            if (trunk >= 0) {
                System.err.printf("diag trunk from r=%d c=%d elev=%.0fm seaDist=%.0f acc=%.0f%n",
                        trunk / GRID, trunk % GRID, elev[trunk], seaDist[trunk], acc[trunk]);
                int at = trunk;
                for (int step = 0; step < 400; step++) {
                    int d = successor(down, at);
                    if (d < 0) {
                        System.err.printf("  step %d: STOP no successor at r=%d c=%d elev=%.0fm seaDist=%.0f%n",
                                step, at / GRID, at % GRID, elev[at], seaDist[at]);
                        break;
                    }
                    if (elev[d] < 0f) {
                        System.err.printf("  step %d: REACHED SEA from r=%d c=%d elev=%.0fm%n",
                                step, at / GRID, at % GRID, elev[at]);
                        break;
                    }
                    if (step % 40 == 0 || !channel[d]) {
                        System.err.printf("  step %d: r=%d c=%d elev=%.0fm seaDist=%.0f channel=%b%n",
                                step, d / GRID, d % GRID, elev[d], seaDist[d], channel[d]);
                    }
                    at = d;
                }
            }
        }

        phase("channelWalk");
        float[][] lakeFields = lakes(elev, fillDepth, seaDist, landmassArea(elev), channel);
        float[] lake = lakeFields[0];
        float[] lakeDeep = lakeFields[1];

        float[] perR = new float[GRID * GRID];
        float[] perC = new float[GRID * GRID];
        phase("lakes");
        flowTangents(elev, chanIdx, down, perR, perC);

        float[] widthM = new float[GRID * GRID];
        float[] depthM = new float[GRID * GRID];
        phase("flowTangents");
        hydraulicGeometry(elev, channel, chanIdx, chanUp, acc, down, perR, perC, widthM, depthM);

        float[] offR = new float[GRID * GRID];
        float[] offC = new float[GRID * GRID];
        phase("hydraulicGeom");
        meanderOffsets(elev, chanIdx, widthM, perR, perC, windowI, windowJ, offR, offC);

        String probe = System.getProperty("terradiff.probe");
        if (probe != null) {
            String[] p = probe.split(",");
            int pi = Integer.parseInt(p[0].trim()), pj = Integer.parseInt(p[1].trim());
            int pr = pi - windowI, pc = pj - windowJ;
            if (pr >= 0 && pc >= 0 && pr < GRID && pc < GRID) {
                int pIdx = pr * GRID + pc;
                double ps = Math.max(slope[pIdx], 1.0e-6);
                System.err.printf(
                        "probe region %d,%d sees (%d,%d) at local (%d,%d): channel=%b elev=%.1f "
                                + "acc=%.0f slope=%.5f accSlope2=%.0f (needs %.0f) fillDepth=%.2f%n",
                        regionI, regionJ, pi, pj, pr, pc, channel[pIdx], elev[pIdx], acc[pIdx],
                        slope[pIdx], acc[pIdx] * Math.pow(ps, CHANNEL_SLOPE_EXPONENT),
                        CHANNEL_INITIATION, fillDepth[pIdx]);
            }
        }

        String box = System.getProperty("terradiff.probeBox");
        if (box != null) {
            String[] b = box.split(",");
            int bi0 = Integer.parseInt(b[0].trim()), bj0 = Integer.parseInt(b[1].trim());
            int bi1 = Integer.parseInt(b[2].trim()), bj1 = Integer.parseInt(b[3].trim());
            int bstep = Integer.parseInt(b[4].trim());
            System.err.printf("probeBox region %d,%d  digit=log10(acc) clamped 0-9, '#'=channel%n",
                    regionI, regionJ);
            for (int pi = bi0; pi <= bi1; pi += bstep) {
                StringBuilder sb = new StringBuilder(String.format("  i=%6d ", pi));
                for (int pj = bj0; pj <= bj1; pj += bstep) {
                    int pr = pi - windowI, pc = pj - windowJ;
                    if (pr < 0 || pc < 0 || pr >= GRID || pc >= GRID) {
                        sb.append('?');
                        continue;
                    }
                    int k = pr * GRID + pc;
                    if (channel[k]) {
                        sb.append('#');
                    } else {
                        int mag10 = (int) Math.max(0, Math.min(9,
                                Math.log10(Math.max(1.0, acc[k]))));
                        sb.append((char) ('0' + mag10));
                    }
                }
                System.err.println(sb);
            }
        }

        float[] filled = new float[GRID * GRID];
        for (int i = 0; i < GRID * GRID; i++) {
            filled[i] = elev[i] + fillDepth[i];
        }

        phase("meanderOffsets");
        float[] holdM = containGround(elev, elev, chanIdx, offR, offC, perR, perC, widthM);

        float[] water = new float[GRID * GRID];
        float[] fallDrop = new float[GRID * GRID];
        solveLevels(elev, channel, chanIdx, chanUp, lake, holdM, depthM, widthM, down, blockM,
                water, fallDrop);

        if (prunePerched(channel, water, elev, lake, down, blockM)) {
            chanIdx = channelCells(channel, null);
            chanUp = channelCells(channel, bySurface);
        }

        if (Boolean.getBoolean("terradiff.diag")) {
            int chan = 0, rises = 0, steps2 = 0, falls = 0, spill = 0, junc = 0, juncStep = 0, juncFall = 0;
            float worstRise = 0f, worstFall = 0f;
            final int[] cnr = {-1, -1, -1, 0, 0, 1, 1, 1}, cnc = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] upstreamCount = new int[GRID * GRID];
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || Float.isNaN(water[i])) continue;
                int d = successor(down, i);
                if (d >= 0 && channel[d]) upstreamCount[d]++;
            }
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || Float.isNaN(water[i]) || elev[i] < 0f) continue;
                chan++;
                if (fallDrop[i] >= FALL_MIN_DROP_BLOCKS) {
                    falls++;
                    worstFall = Math.max(worstFall, fallDrop[i]);
                }

                if (!Float.isNaN(holdM[i]) && water[i] > holdM[i] + 1.0e-3f) spill++;
                int d = successor(down, i);
                if (d < 0 || !channel[d] || Float.isNaN(water[d])) continue;
                if (water[d] > water[i] + 1.0e-3f) {
                    rises++;
                    worstRise = Math.max(worstRise, water[d] - water[i]);
                }
                int drop = (int) Math.round((water[i] - water[d]) / blockM);
                if (drop > 1 && fallDrop[d] < FALL_MIN_DROP_BLOCKS) steps2++;
            }
            for (int j = 0; j < GRID * GRID; j++) {
                if (!channel[j] || upstreamCount[j] < 2 || Float.isNaN(water[j])) continue;
                int r = j / GRID, c = j % GRID;
                if (r <= 0 || c <= 0 || r >= GRID - 1 || c >= GRID - 1) continue;
                junc++;
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (int k = 0; k < 8; k++) {
                    int up = (r + cnr[k]) * GRID + (c + cnc[k]);
                    if (!channel[up] || Float.isNaN(water[up])) continue;
                    if (successor(down, up) != j) continue;
                    lo = Math.min(lo, water[up]);
                    hi = Math.max(hi, water[up]);
                }

                if (Math.round((hi - lo) / blockM) > 1) {
                    if (fallDrop[j] >= FALL_MIN_DROP_BLOCKS || !Float.isNaN(lake[j])) {
                        juncFall++;
                    } else {
                        juncStep++;
                    }
                }
            }

            float wLo = Float.MAX_VALUE, wHi = 0f, wSum = 0f;
            float dLo = Float.MAX_VALUE, dHi = 0f, dSum = 0f;
            int wN = 0;
            for (int i = 0; i < GRID * GRID; i++) {
                if (!channel[i] || elev[i] < 0f || widthM[i] <= 0f) continue;
                wLo = Math.min(wLo, widthM[i]);
                wHi = Math.max(wHi, widthM[i]);
                wSum += widthM[i];
                dLo = Math.min(dLo, depthM[i]);
                dHi = Math.max(dHi, depthM[i]);
                dSum += depthM[i];
                wN++;
            }
            if (wN > 0) {
                System.err.printf(
                        "diag geometry: width %.0f/%.0f/%.0f m  depth %.1f/%.1f/%.1f m  (min/mean/max over %d cells)%n",
                        wLo, wSum / wN, wHi, dLo, dSum / wN, dHi, wN);
            }
            System.err.printf(
                    "diag solve: channel=%d spill=%d rise=%d (worst %.1fm) "
                            + "step2+=%d falls=%d (worst %.0f blocks) junctions=%d over1block=%d atFallOrLake=%d%n",
                    chan, spill, rises, worstRise, steps2, falls, worstFall,
                    junc, juncStep, juncFall);
        }
        float[] waterOut = new float[GRID * GRID];
        Arrays.fill(waterOut, Float.NaN);
        float[] deltaW = new float[GRID * GRID];
        float[] widthOut = new float[GRID * GRID];
        float[] depthOut = new float[GRID * GRID];
        float[] fallOut = new float[GRID * GRID];
        Paint paint = new Paint(distance, magnitude, waterOut, deltaW,
                widthOut, depthOut, fallOut);

        float[] arcPos;
        arcPos = new float[GRID * GRID];
        Arrays.fill(arcPos, Float.NaN);
        for (int i : chanUp) {
            if (elev[i] < 0f) {
                continue;
            }
            int d = successor(down, i);
            float base = 0f;
            float len = 0f;
            if (d >= 0 && channel[d] && !Float.isNaN(arcPos[d])) {
                base = arcPos[d];
                float dr = (d / GRID) + offR[d] - ((i / GRID) + offR[i]);
                float dc = (d % GRID) + offC[d] - ((i % GRID) + offC[i]);
                len = (float) Math.sqrt(dr * dr + dc * dc);
            }
            arcPos[i] = base + len;
        }
        ARC_POS = arcPos;

        phase("solveLevels");
        meander(elev, channel, chanIdx, down, acc, water,
                offR, offC, widthM, depthM, fallDrop, paint);
        phase("meanderPaint");
        chamfer(paint);

        int active = 0;
        int[] activeIdx;
        {
            int cap = 0;
            for (int r = 1; r < GRID - 1; r++) {
                for (int c = 1; c < GRID - 1; c++) {
                    if (distance[r * GRID + c] < MAX_INFLUENCE_CELLS) {
                        cap++;
                    }
                }
            }
            activeIdx = new int[cap];
            for (int r = 1; r < GRID - 1; r++) {
                for (int c = 1; c < GRID - 1; c++) {
                    int i = r * GRID + c;
                    if (distance[i] < MAX_INFLUENCE_CELLS) {
                        activeIdx[active++] = i;
                    }
                }
            }
            if (Boolean.getBoolean("terradiff.diag")) {
                System.err.printf("diag active cells: %d of %d (%.1f%%)%n",
                        active, GRID * GRID, 100.0 * active / (GRID * GRID));
            }
        }

        if (SECTION_STAMP) {
            stampSections(chanUp, water, elev, offR, offC, widthM,
                    perR, perC, distance, waterOut, blockM);
        }

        phase("chamfer");
        if (FIELD_BLUR_PASSES > 0) {
            float[] src = waterOut;
            float[] dst = new float[GRID * GRID];
            System.arraycopy(src, 0, dst, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_BLUR_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    if (Float.isNaN(src[i])) {
                        continue;
                    }
                    float sum = 0f;
                    float wsum = 0f;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = src[base + dc];
                            if (Float.isNaN(v)) {
                                continue;
                            }
                            sum += v;
                            wsum += 1f;
                        }
                    }
                    if (wsum > 0f) {
                        dst[i] = sum / wsum;
                    }
                }
                float[] swap = src;
                src = dst;
                dst = swap;
            }
            if (src != waterOut) {
                System.arraycopy(src, 0, waterOut, 0, GRID * GRID);
            }
        }

        phase("fieldBlur");
        if (ARC_BLUR_PASSES > 0) {
            float[] ps = paint.pos;
            float[] pd = new float[GRID * GRID];
            System.arraycopy(ps, 0, pd, 0, GRID * GRID);
            for (int pass = 0; pass < ARC_BLUR_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    if (Float.isNaN(ps[i])) {
                        continue;
                    }
                    float sum = 0f;
                    int cnt = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = ps[base + dc];
                            if (!Float.isNaN(v)) {
                                sum += v;
                                cnt++;
                            }
                        }
                    }
                    if (cnt > 0) {
                        pd[i] = sum / cnt;
                    }
                }
                float[] sw = ps; ps = pd; pd = sw;
            }
            if (ps != paint.pos) {
                System.arraycopy(ps, 0, paint.pos, 0, GRID * GRID);
            }
        }

        int[] up = new int[GRID * GRID];
        Arrays.fill(up, -1);
        float[] bestAcc = new float[GRID * GRID];
        phase("arcBlur");
        for (int i : chanIdx) {
            if (Float.isNaN(water[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && acc[i] > bestAcc[d]) {
                bestAcc[d] = acc[i];
                up[d] = i;
            }
        }

        for (int ai = 0; ai < active; ai++) {
            int k = activeIdx[ai];
            int i = paint.src[k];
            float target = paint.pos[k];
            if (i >= 0 && !Float.isNaN(arcPos[i])) {

                float kr = k / GRID, kc = k % GRID;
                float ir = (i / GRID) + offR[i], ic = (i % GRID) + offC[i];
                float best = Float.MAX_VALUE;
                float bestArc = arcPos[i];
                int dn = successor(down, i);
                int upn = up[i];
                for (int side = 0; side < 2; side++) {
                    int j = side == 0 ? dn : upn;
                    if (j < 0 || !channel[j] || Float.isNaN(arcPos[j])) {
                        continue;
                    }
                    float jr = (j / GRID) + offR[j], jc = (j % GRID) + offC[j];
                    float sr = jr - ir, sc = jc - ic;
                    float len2 = sr * sr + sc * sc;
                    if (len2 < 1.0e-6f) {
                        continue;
                    }
                    float t = ((kr - ir) * sr + (kc - ic) * sc) / len2;
                    t = Math.max(0f, Math.min(1f, t));
                    float pr = ir + sr * t, pc = ic + sc * t;
                    float d2 = (kr - pr) * (kr - pr) + (kc - pc) * (kc - pc);
                    if (d2 < best) {
                        best = d2;
                        bestArc = arcPos[i] + (arcPos[j] - arcPos[i]) * t;
                    }
                }
                if (best != Float.MAX_VALUE) {
                    target = bestArc;
                }
            }
            if (i < 0 || Float.isNaN(target) || Float.isNaN(waterOut[k])
                    || Float.isNaN(arcPos[i]) || distance[k] >= MAX_INFLUENCE_CELLS) {
                continue;
            }

            int a = i;
            int guard = 0;
            if (target < arcPos[a]) {
                while (guard++ < 96) {
                    int d = successor(down, a);
                    if (d < 0 || !channel[d] || Float.isNaN(arcPos[d]) || arcPos[d] <= target) {
                        break;
                    }
                    a = d;
                }
                int d = successor(down, a);
                if (d >= 0 && channel[d] && !Float.isNaN(arcPos[d]) && !Float.isNaN(water[d])) {
                    float span = arcPos[a] - arcPos[d];
                    float t = span > 1.0e-4f ? (arcPos[a] - target) / span : 0f;
                    t = Math.max(0f, Math.min(1f, t));
                    waterOut[k] = water[a] + (water[d] - water[a]) * t;
                    continue;
                }
                waterOut[k] = water[a];
            } else {
                while (guard++ < 96) {
                    int u = up[a];
                    if (u < 0 || Float.isNaN(arcPos[u]) || arcPos[u] >= target) {
                        break;
                    }
                    a = u;
                }
                int u = up[a];
                if (u >= 0 && !Float.isNaN(arcPos[u]) && !Float.isNaN(water[u])) {
                    float span = arcPos[u] - arcPos[a];
                    float t = span > 1.0e-4f ? (target - arcPos[a]) / span : 0f;
                    t = Math.max(0f, Math.min(1f, t));
                    waterOut[k] = water[a] + (water[u] - water[a]) * t;
                    continue;
                }
                waterOut[k] = water[a];
            }
        }

        phase("activeLoop");
        if (FIELD_SMOOTH_PASSES > 0) {
            float[] wSrc = widthOut, dSrc = depthOut;
            float[] wDst = new float[GRID * GRID], dDst = new float[GRID * GRID];
            System.arraycopy(wSrc, 0, wDst, 0, GRID * GRID);
            System.arraycopy(dSrc, 0, dDst, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_SMOOTH_PASSES; pass++) {
                for (int a = 0; a < active; a++) {
                    int i = activeIdx[a];
                    float ws = 0f, ds = 0f;
                    int n2 = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            int j = base + dc;
                            if (distance[j] >= MAX_INFLUENCE_CELLS) {
                                continue;
                            }
                            ws += wSrc[j];
                            ds += dSrc[j];
                            n2++;
                        }
                    }
                    if (n2 > 0) {
                        wDst[i] = ws / n2;
                        dDst[i] = ds / n2;
                    }
                }
                float[] sw = wSrc; wSrc = wDst; wDst = sw;
                float[] sd = dSrc; dSrc = dDst; dDst = sd;
            }
            if (wSrc != widthOut) {
                System.arraycopy(wSrc, 0, widthOut, 0, GRID * GRID);
                System.arraycopy(dSrc, 0, depthOut, 0, GRID * GRID);
            }
        }

        if (EXACT_DISTANCE) {
            float[] sq = new float[GRID * GRID];
            for (int i = 0; i < GRID * GRID; i++) {
                sq[i] = distance[i] == 0f ? 0f : 1.0e20f;
            }
        phase("fieldSmooth");
            edt2d(sq, GRID, GRID);
            for (int i = 0; i < GRID * GRID; i++) {
                distance[i] = (float) Math.sqrt(sq[i]);
            }
        }

        int capBound = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            if (Float.isNaN(waterOut[i])) {
                continue;
            }
            float cap = Math.max(0f, elev[i]);
            if (waterOut[i] > cap) {
                capBound++;

                waterOut[i] = cap;
            }
        }

        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag groundCap: bound on %d cells%n", capBound);
        }

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (Float.isNaN(waterOut[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && !Float.isNaN(waterOut[d]) && waterOut[d] > waterOut[i]) {
                waterOut[d] = waterOut[i];
            }
        }

        phase("edt+width");
        if (FIELD_BLUR_PASSES > 0) {
            float[] src2 = waterOut;
            float[] dst2 = new float[GRID * GRID];
            System.arraycopy(src2, 0, dst2, 0, GRID * GRID);
            for (int pass = 0; pass < FIELD_BLUR_PASSES; pass++) {
                for (int a2 = 0; a2 < active; a2++) {
                    int i = activeIdx[a2];
                    if (Float.isNaN(src2[i])) {
                        continue;
                    }
                    float sum = 0f;
                    int cnt = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int base = i + dr * GRID;
                        for (int dc = -1; dc <= 1; dc++) {
                            float v = src2[base + dc];
                            if (!Float.isNaN(v)) {
                                sum += v;
                                cnt++;
                            }
                        }
                    }
                    if (cnt > 0) {
                        dst2[i] = sum / cnt;
                    }
                }
                float[] sw2 = src2;
                src2 = dst2;
                dst2 = sw2;
            }
            if (src2 != waterOut) {
                System.arraycopy(src2, 0, waterOut, 0, GRID * GRID);
            }
        }

        phase("fieldBlur2");
        KarstNetwork karst = KarstHydrology.solve(regionI, regionJ, windowI, windowJ, GRID,
                elev, acc, channel, fillDepth, waterOut, lake, widthM, down, REGION_PX, MARGIN_PX, blockM);
        phase("karst");
        return new Region(regionI, regionJ, crop(distance), crop(magnitude), crop(waterOut),
                crop(lake), crop(lakeDeep), crop(deltaW),
                crop(widthOut), crop(depthOut), crop(fallOut), karst);
    }

    private static void stampSections(int[] chanUp, float[] water,
                                      float[] elev, float[] offR, float[] offC, float[] widthM,
                                      float[] perR, float[] perC, float[] distance,
                                      float[] waterOut, float blockM) {

            for (int oi = chanUp.length - 1; oi >= 0; oi--) {
                int i = chanUp[oi];
                if (Float.isNaN(water[i]) || elev[i] < 0f) {
                    continue;
                }
                float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];

                float drawnM = Math.max(widthM[i], STAMP_MIN_BLOCKS * blockM);
                float halfCells = STAMP_REACH * 0.5f * drawnM / CELL_SIZE_M;

                int steps = Math.max(1, (int) Math.ceil(halfCells * 4f));
                float tanR = -perC[i], tanC = perR[i];
                for (int s = -steps; s <= steps; s++) {
                    float t = halfCells * s / steps;
                    for (int u = -1; u <= 1; u++) {
                    float ut = u * 0.5f;
                    int rr = Math.round(r0 + perR[i] * t + tanR * ut);
                    int cc = Math.round(c0 + perC[i] * t + tanC * ut);
                    if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) {
                        continue;
                    }
                    int k = rr * GRID + cc;

                    if (distance[k] < MAX_INFLUENCE_CELLS
                            && (Float.isNaN(waterOut[k]) || water[i] >= waterOut[k] - blockM)) {
                        waterOut[k] = water[i];
                    }
                    }
                }
            }
    }

    private static int[] strahlerOrder(float[] elev, boolean[] channel, int[] down,
                                       int[] chanUp) {
        int n = GRID * GRID;
        int[] order = new int[n];
        int[] best = new int[n];
        int[] ties = new int[n];

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (elev[i] < 0f) {
                continue;
            }
            int here = best[i] == 0 ? 1 : (ties[i] >= 2 ? best[i] + 1 : best[i]);
            order[i] = here;
            int d = successor(down, i);
            if (d < 0 || !channel[d] || elev[d] < 0f) {
                continue;
            }
            if (here > best[d]) {
                best[d] = here;
                ties[d] = 1;
            } else if (here == best[d]) {
                ties[d]++;
            }
        }
        return order;
    }

    private static float sampleGrid(float[] field, float rf, float cf) {
        int r0 = (int) Math.floor(rf), c0 = (int) Math.floor(cf);
        if (r0 < 0 || c0 < 0 || r0 >= GRID - 1 || c0 >= GRID - 1) {
            return Float.NaN;
        }
        float tr = rf - r0, tc = cf - c0;
        int a = r0 * GRID + c0;
        return lerp(lerp(field[a], field[a + 1], tc),
                    lerp(field[a + GRID], field[a + GRID + 1], tc), tr);
    }

    private static float valleyFloorM(float[] elev, float r0, float c0, float perpR, float perpC) {
        float base = sampleGrid(elev, r0, c0);
        if (Float.isNaN(base)) {
            return CELL_SIZE_M;
        }
        int cells = 0;
        for (int side = -1; side <= 1; side += 2) {
            for (int s = 1; s <= CONFINEMENT_SCAN_CELLS; s++) {
                float g = sampleGrid(elev, r0 + perpR * s * side, c0 + perpC * s * side);
                if (Float.isNaN(g) || g > base + CONFINEMENT_RISE_M) {
                    break;
                }
                cells++;
            }
        }
        return Math.max(1, cells) * CELL_SIZE_M;
    }

    private static void flowTangents(float[] elev, int[] chanIdx, int[] down,
                                     float[] perROut, float[] perCOut) {
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            int ahead = i;
            for (int s = 0; s < MEANDER_TANGENT_CELLS; s++) {
                int step = successor(down, ahead);
                if (step < 0) {
                    break;
                }
                ahead = step;
            }
            float dr = (ahead / GRID) - (i / GRID), dc = (ahead % GRID) - (i % GRID);
            float len = (float) Math.sqrt(dr * dr + dc * dc);
            if (len <= 0f) {
                continue;
            }
            perROut[i] = -dc / len;
            perCOut[i] = dr / len;
        }
    }

    private static void hydraulicGeometry(float[] elev, boolean[] channel, int[] chanIdx, int[] chanUp,
                                          float[] acc, int[] down,
                                          float[] perR, float[] perC,
                                          float[] widthOut, float[] depthOut) {
        int[] order = strahlerOrder(elev, channel, down, chanUp);
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }

            double area = Math.max(1.0, acc[i] / RIVER_THRESHOLD);
            double q = Math.pow(area, DISCHARGE_AREA_EXPONENT);
            float w = BANKFULL_WIDTH_M * (float) Math.pow(q, WIDTH_Q_EXPONENT);
            float dep = BANKFULL_DEPTH_M * (float) Math.pow(q, DEPTH_Q_EXPONENT);

            w *= 1f + ORDER_WIDTH_GAIN * (order[i] - 1);

            w = MAX_WIDTH_M * w / (MAX_WIDTH_M + w);
            dep = MAX_DEPTH_M * dep / (MAX_DEPTH_M + dep);

            float floorM = valleyFloorM(elev, i / GRID, i % GRID, perR[i], perC[i]);
            w = Math.min(w, CONFINEMENT_FRACTION * floorM);

            float squeeze = Math.min(2f, Math.max(1f,
                    (BANKFULL_WIDTH_M * (float) Math.pow(q, WIDTH_Q_EXPONENT)) / Math.max(1f, w)));
            dep *= (float) Math.sqrt(squeeze);

            widthOut[i] = Math.max(CELL_SIZE_M * 0.25f, w);
            depthOut[i] = Math.max(0.5f, dep);
        }
    }

    private static void meanderOffsets(float[] elev, int[] chanIdx, float[] widthM,
                                       float[] perR, float[] perC, int windowI, int windowJ,
                                       float[] offROut, float[] offCOut) {
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            float perpR = perR[i], perpC = perC[i];
            if (perpR == 0f && perpC == 0f) {
                continue;
            }
            int r = i / GRID, c = i % GRID;
            float floorM = valleyFloorM(elev, r, c, perpR, perpC);

            float ampM = MEANDER_AMPLITUDE_WIDTHS * widthM[i];
            ampM = Math.max(ampM, MEANDER_MIN_AMPLITUDE_M);
            ampM = Math.min(ampM, MEANDER_SPACE_FRACTION * floorM);
            if (ampM <= 0f) {
                continue;
            }
            float lamCells = MEANDER_ASPECT * ampM / CELL_SIZE_M;

            float nx = (windowJ + c) * (float) DOWNSAMPLE;
            float ny = (windowI + r) * (float) DOWNSAMPLE;
            float lateral = 0f, weight = 0f;
            float t = (float) (Math.log(Math.max(1f, lamCells)) / Math.log(2));
            for (int k = 0; k < MEANDER_BAND_CELLS.length; k++) {
                float tk = (float) (Math.log(MEANDER_BAND_CELLS[k]) / Math.log(2));
                float wk = 1f - Math.abs(t - tk) / 1.585f;
                if (wk <= 0f) {
                    continue;
                }
                lateral += wk * (MEANDER_BAND_I[k].GetNoise(nx, ny) * perpR
                        + MEANDER_BAND_J[k].GetNoise(nx, ny) * perpC);
                weight += wk;
            }
            if (weight <= 0f) {
                continue;
            }
            lateral /= weight;

            float want = lateral * (ampM / CELL_SIZE_M);
            float shift = valleyLimit(elev, r, c, perpR, perpC, want, MEANDER_CLIMB_M);
            offROut[i] = perpR * shift;
            offCOut[i] = perpC * shift;
        }
    }

    private static float freeboardM(float depthM, float blockM) {
        return Math.max(FREEBOARD_BLOCKS * blockM, 0.5f * depthM);
    }

    private static float[] containGround(float[] filled, float[] elev, int[] chanIdx,
                                         float[] offR, float[] offC, float[] perR, float[] perC,
                                         float[] widthM) {
        int n = GRID * GRID;
        float[] hold = new float[n];
        Arrays.fill(hold, Float.NaN);
        float[] buf = new float[64];
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];
            float halfCells = 0.5f * widthM[i] / CELL_SIZE_M;
            int span = Math.max(1, (int) Math.ceil(halfCells * 1.25f));
            int got = 0;
            for (int s = -span; s <= span && got < buf.length; s++) {
                float g = sampleGrid(filled, r0 + perR[i] * s, c0 + perC[i] * s);
                if (!Float.isNaN(g)) {
                    buf[got++] = g;
                }
            }
            if (got == 0) {
                hold[i] = filled[i];
                continue;
            }

            int k = Math.min(got - 1, (int) Math.floor(HOLD_QUANTILE * got));
            for (int a = 0; a <= k; a++) {
                int m = a;
                for (int b = a + 1; b < got; b++) {
                    if (buf[b] < buf[m]) {
                        m = b;
                    }
                }
                float t = buf[a];
                buf[a] = buf[m];
                buf[m] = t;
            }
            hold[i] = buf[k];
        }
        return hold;
    }

    private static void solveLevels(float[] elev, boolean[] channel, int[] chanIdx, int[] chanUp,
                                    float[] lake, float[] holdM,
                                    float[] depthM, float[] widthM, int[] down,
                                    float blockM, float[] waterOut, float[] fallOut) {
        int n = GRID * GRID;
        float[] lvl = new float[n];
        Arrays.fill(lvl, Float.NaN);
        boolean[] pinned = new boolean[n];
        float gorge = Math.max(1f, GORGE_LIMIT_BLOCKS) * blockM;

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            float here;
            if (elev[i] < 0f) {
                here = 0f;
                pinned[i] = true;
            } else if (!Float.isNaN(lake[i])) {

                here = lake[i];
                pinned[i] = true;
            } else {

                float freeboard = freeboardM(depthM[i], blockM);
                float own = holdM[i] - freeboard;
                if (own < 0f) {
                    own = 0f;
                }
                if (Float.isNaN(lvl[i])) {
                    here = own;
                } else {
                    here = Math.min(lvl[i], own);
                    float drop = lvl[i] - here;
                    if (drop > gorge) {
                        fallOut[i] = drop / blockM;
                    }
                }
            }
            lvl[i] = here;
            int d = successor(down, i);
            if (d >= 0 && channel[d] && (Float.isNaN(lvl[d]) || here < lvl[d])) {
                lvl[d] = here;
            }
        }

        int scanCap = SCAN_CAP;
        for (int sweep = 0; sweep < 12; sweep++) {
            boolean moved = false;
            for (int i : chanIdx) {
                if (Float.isNaN(lvl[i]) || pinned[i]) {
                    continue;
                }
                int r = i / GRID, c = i % GRID;
                float halfI = 0.5f * widthM[i] / CELL_SIZE_M;
                int rad = Math.min(scanCap, (int) Math.ceil(halfI) + 3);
                if (r < rad || c < rad || r >= GRID - rad || c >= GRID - rad) {
                    continue;
                }
                float lowest = Float.MAX_VALUE;
                for (int dr = -rad; dr <= rad; dr++) {
                    for (int dc = -rad; dc <= rad; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        int j = i + dr * GRID + dc;
                        if (!channel[j] || Float.isNaN(lvl[j]) || lvl[j] >= lowest) {
                            continue;
                        }
                        float sep = (float) Math.sqrt(dr * dr + dc * dc);

                        float reach = OVERLAP_REACH * (halfI + 0.5f * widthM[j] / CELL_SIZE_M);
                        if (sep <= reach) {
                            lowest = lvl[j];
                        }
                    }
                }
                float tol = OVERLAP_AGREE_BLOCKS * blockM;
                if (lowest != Float.MAX_VALUE && lvl[i] > lowest + tol) {
                    lvl[i] = lowest + tol;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }

        for (int sweep = 0; sweep < 24; sweep++) {
            boolean moved = false;
            for (int i : chanIdx) {
                if (Float.isNaN(lvl[i]) || pinned[i]) {
                    continue;
                }
                int r = i / GRID, c = i % GRID;
                if (r < 1 || c < 1 || r >= GRID - 1 || c >= GRID - 1) {
                    continue;
                }
                float lowest = Float.MAX_VALUE;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) {
                            continue;
                        }
                        int j = i + dr * GRID + dc;
                        if (channel[j] && !Float.isNaN(lvl[j]) && lvl[j] < lowest) {
                            lowest = lvl[j];
                        }
                    }
                }
                if (lowest != Float.MAX_VALUE && lvl[i] > lowest + blockM) {
                    lvl[i] = lowest + blockM;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }

        for (int oi = chanUp.length - 1; oi >= 0; oi--) {
            int i = chanUp[oi];
            if (Float.isNaN(lvl[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d >= 0 && channel[d] && !Float.isNaN(lvl[d]) && !pinned[d] && lvl[d] > lvl[i]) {
                lvl[d] = lvl[i];
            }
        }

        Arrays.fill(fallOut, 0f);
        if (FALLS_ENABLED) {
        for (int i : chanIdx) {
            if (Float.isNaN(lvl[i])) {
                continue;
            }
            int d = successor(down, i);
            if (d < 0 || !channel[d] || Float.isNaN(lvl[d])) {
                continue;
            }
            float drop = lvl[i] - lvl[d];
            if (drop > gorge && drop / blockM > fallOut[d]) {
                fallOut[d] = drop / blockM;
            }
        }
        }

        for (int i : chanUp) {
            if (Float.isNaN(lvl[i]) || pinned[i]) {
                continue;
            }
            int d = successor(down, i);
            if (d < 0 || !channel[d] || Float.isNaN(lvl[d])) {
                continue;
            }
            if (fallOut[d] >= FALL_MIN_DROP_BLOCKS) {
                continue;
            }
            if (lvl[i] > lvl[d] + blockM) {
                lvl[i] = lvl[d] + blockM;
            }
        }

        float[] cont = new float[n];
        for (int i = 0; i < n; i++) {
            cont[i] = (channel[i] && !Float.isNaN(lvl[i])) ? lvl[i] : Float.NaN;
        }
        float[] copy = new float[n];
        float[] predSum = new float[n];
        int[] predCnt = new int[n];
        for (int pass = 0; pass < DEQUANT_PASSES; pass++) {
            System.arraycopy(cont, 0, copy, 0, n);
            for (int i : chanIdx) {
                predSum[i] = 0f;
                predCnt[i] = 0;
            }
            for (int i : chanIdx) {
                if (Float.isNaN(copy[i])) {
                    continue;
                }
                int d = successor(down, i);
                if (d >= 0 && !Float.isNaN(copy[d])) {
                    predSum[d] += copy[i];
                    predCnt[d]++;
                }
            }
            for (int i : chanIdx) {
                if (Float.isNaN(copy[i]) || pinned[i]) {
                    continue;
                }
                int d = successor(down, i);
                float dn = (d >= 0 && !Float.isNaN(copy[d])) ? copy[d] : copy[i];
                float up = predCnt[i] > 0 ? predSum[i] / predCnt[i] : copy[i];
                cont[i] = 0.5f * copy[i] + 0.25f * dn + 0.25f * up;
            }

            for (int i : chanIdx) {
                if (Float.isNaN(cont[i]) || pinned[i]) {
                    continue;
                }
                float freeboard = freeboardM(depthM[i], blockM);
                float capM = holdM[i] - freeboard;
                if (cont[i] > capM) {
                    cont[i] = capM;
                }
                if (cont[i] < 0f) {
                    cont[i] = 0f;
                }
            }

            for (int oi = chanUp.length - 1; oi >= 0; oi--) {
                int i = chanUp[oi];
                if (Float.isNaN(cont[i])) {
                    continue;
                }
                int d = successor(down, i);
                if (d >= 0 && !Float.isNaN(cont[d]) && !pinned[d] && cont[d] > cont[i]) {
                    cont[d] = cont[i];
                }
            }
        }

        for (int i = 0; i < n; i++) {
            waterOut[i] = cont[i];
        }
    }

    private static void meander(float[] elev, boolean[] channel, int[] chanIdx, int[] down, float[] acc,
                                float[] water, float[] offR, float[] offC,
                                float[] widthM, float[] depthM, float[] fallDrop, Paint p) {
        int n = GRID * GRID;
        float[] mag = new float[n];
        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }
            mag[i] = (float) (Math.log10(Math.max(1.0, acc[i] / RIVER_THRESHOLD))
                    / WIDTH_GROWTH_DECADES);
        }

        for (int i : chanIdx) {
            if (elev[i] < 0f) {
                continue;
            }

            float r0 = (i / GRID) + offR[i], c0 = (i % GRID) + offC[i];
            int d = successor(down, i);
            if (d < 0) {
                plot(p, r0, c0, mag[i], water[i], widthM[i], depthM[i], fallDrop[i], 0f, i);
                continue;
            }

            boolean live = channel[d] && elev[d] >= 0f;
            trace(p, r0, c0, (d / GRID) + offR[d], (d % GRID) + offC[d],
                    mag[i], live ? mag[d] : mag[i],
                    water[i], live ? water[d] : water[i],
                    widthM[i], live ? widthM[d] : widthM[i],
                    depthM[i], live ? depthM[d] : depthM[i],
                    fallDrop[i], live ? fallDrop[d] : fallDrop[i], 0f,
                    i, live ? d : i);
        }

    }

    private static float[] seaDistance(float[] elev) {
        int n = GRID * GRID;
        float[] d = new float[n];
        float far = GRID * 2f;
        for (int i = 0; i < n; i++) {
            d[i] = elev[i] < 0f ? 0f : far;
        }
        final float ORTH = 1f, DIAG = 1.41421f;
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                if (r > 0) d[i] = Math.min(d[i], d[i - GRID] + ORTH);
                if (c > 0) d[i] = Math.min(d[i], d[i - 1] + ORTH);
                if (r > 0 && c > 0) d[i] = Math.min(d[i], d[i - GRID - 1] + DIAG);
                if (r > 0 && c < GRID - 1) d[i] = Math.min(d[i], d[i - GRID + 1] + DIAG);
            }
        for (int r = GRID - 1; r >= 0; r--)
            for (int c = GRID - 1; c >= 0; c--) {
                int i = r * GRID + c;
                if (r < GRID - 1) d[i] = Math.min(d[i], d[i + GRID] + ORTH);
                if (c < GRID - 1) d[i] = Math.min(d[i], d[i + 1] + ORTH);
                if (r < GRID - 1 && c < GRID - 1) d[i] = Math.min(d[i], d[i + GRID + 1] + DIAG);
                if (r < GRID - 1 && c > 0) d[i] = Math.min(d[i], d[i + GRID - 1] + DIAG);
            }
        return d;
    }

    private static final float PERCH_FULL_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.perchFull", "20"));

    private static final float PERCH_FADE_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.perchFade", "13"));

    private static final float PERCH_RESIDUAL_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.perchResidual", "12"));

    private static final boolean PERCH_PRUNE =
            !"false".equals(System.getProperty("terradiff.perchPrune"));

    // Returns whether any channel cell was removed
    private static boolean prunePerched(boolean[] channel, float[] water, float[] elev, float[] lake,
                                        int[] down, float blockM) {
        if (!PERCH_PRUNE) {
            return false;
        }
        int n = GRID * GRID;
        float full = PERCH_FULL_BLOCKS * blockM;
        float fade = Math.max(1.0e-3f, PERCH_FADE_BLOCKS * blockM);
        float allow = PERCH_RESIDUAL_BLOCKS * blockM;
        boolean[] blocked = new boolean[n];
        int blockedCount = 0;
        for (int i = 0; i < n; i++) {
            if (!channel[i] || Float.isNaN(water[i])) {
                continue;
            }
            if (!Float.isNaN(lake[i])) {
                continue;
            }
            float perch = elev[i] - water[i];
            if (perch <= full) {
                continue;
            }
            float t = (perch - full) / fade;
            t = t < 0f ? 0f : (t > 1f ? 1f : t);
            float keep = 1f - t * t * (3f - 2f * t);
            if (perch * (1f - keep) >= allow) {
                blocked[i] = true;
                blockedCount++;
            }
        }
        if (blockedCount == 0) {
            return false;
        }

        final byte UNKNOWN = 0, REACHES = 1, STRANDED = 2;
        byte[] fate = new byte[n];
        int[] path = new int[GRID * 4 + 2];
        for (int start = 0; start < n; start++) {
            if (!channel[start] || fate[start] != UNKNOWN) {
                continue;
            }
            int len = 0;
            int at = start;
            byte verdict = STRANDED;
            while (len < path.length) {
                path[len++] = at;
                fate[at] = STRANDED;
                if (blocked[at]) {
                    break;
                }
                int r = at / GRID, c = at % GRID;
                if (r < 1 || c < 1 || r >= GRID - 1 || c >= GRID - 1) {
                    verdict = REACHES;
                    break;
                }
                int d = successor(down, at);
                if (d < 0) {
                    break;
                }
                if (elev[d] < 0f || fate[d] == REACHES) {
                    verdict = REACHES;
                    break;
                }
                if (fate[d] == STRANDED && d != at) {
                    break;
                }
                at = d;
            }
            for (int k = 0; k < len; k++) {
                fate[path[k]] = verdict;
            }
        }
        int pruned = 0;
        for (int i = 0; i < n; i++) {
            if (channel[i] && fate[i] == STRANDED) {
                channel[i] = false;
                water[i] = Float.NaN;
                pruned++;
            }
        }
        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag perch-pruned %d channel cells behind %d perched barriers%n",
                    pruned, blockedCount);
        }
        return pruned > 0;
    }

    // Channel cells in index order, or in the given order when there is one
    private static int[] channelCells(boolean[] channel, int[] order) {
        int count = 0;
        for (boolean b : channel) {
            if (b) count++;
        }
        int[] out = new int[count];
        int k = 0;
        if (order == null) {
            for (int i = 0; i < channel.length; i++) {
                if (channel[i]) out[k++] = i;
            }
        } else {
            for (int i : order) {
                if (channel[i]) out[k++] = i;
            }
        }
        return out;
    }

    private static int successor(int[] down, int i) {
        int d = down[i];
        if (d < 0 || d == i) {
            return -1;
        }
        int dr = (d / GRID) - (i / GRID), dc = (d % GRID) - (i % GRID);
        return (Math.abs(dr) <= 1 && Math.abs(dc) <= 1) ? d : -1;
    }

    private static float valleyLimit(float[] elev, int r, int c,
                                     float perpR, float perpC, float want, float climbM) {
        float limit = Math.abs(want);
        if (limit <= 0f) {
            return 0f;
        }
        float sign = want < 0f ? -1f : 1f;
        float ceiling = elev[r * GRID + c] + climbM;
        float reached = 0f;
        int steps = (int) Math.ceil(limit);
        for (int s = 1; s <= steps; s++) {
            float t = Math.min(s, limit);
            int rr = Math.round(r + perpR * t * sign);
            int cc = Math.round(c + perpC * t * sign);
            if (rr < 1 || cc < 1 || rr >= GRID - 1 || cc >= GRID - 1) {
                break;
            }
            if (elev[rr * GRID + cc] > ceiling) {
                break;
            }
            reached = t;
        }
        return reached * sign;
    }

    private static final class Paint {
        final float[] distance, magnitude, water, deltaW, width, depth, fall;

        final float[] pos;

        final int[] src;

        Paint(float[] distance, float[] magnitude, float[] water, float[] deltaW,
              float[] width, float[] depth, float[] fall) {
            this.src = new int[GRID * GRID];
            Arrays.fill(this.src, -1);
            this.pos = new float[GRID * GRID];
            Arrays.fill(this.pos, Float.NaN);
            this.distance = distance;
            this.magnitude = magnitude;
            this.water = water;
            this.deltaW = deltaW;
            this.width = width;
            this.depth = depth;
            this.fall = fall;
        }
    }

    private static void trace(Paint p, float r0, float c0, float r1, float c1,
                              float m0, float m1, float w0, float w1,
                              float wid0, float wid1, float dep0, float dep1,
                              float fall0, float fall1, float dw,
                              int src0, int src1) {
        float dr = r1 - r0, dc = c1 - c0;
        int steps = Math.max(1, (int) Math.ceil(2f * Math.max(Math.abs(dr), Math.abs(dc))));
        for (int s = 0; s <= steps; s++) {
            float t = s / (float) steps;
            boolean near = t < 0.5f;
            plot(p, r0 + dr * t, c0 + dc * t,
                    near ? m0 : m1, near ? w0 : w1,
                    near ? wid0 : wid1, near ? dep0 : dep1, near ? fall0 : fall1, dw,
                    near ? src0 : src1);
        }
    }

    private static void plot(Paint p, float rf, float cf, float m, float w,
                             float wid, float dep, float fl, float dw, int srcIdx) {
        int r = Math.round(rf), c = Math.round(cf);
        if (r < 0 || c < 0 || r >= GRID || c >= GRID) {
            return;
        }
        int i = r * GRID + c;
        if (p.distance[i] == 0f) {

            if (m > p.magnitude[i]) {
                p.magnitude[i] = m;
            }
            if (!Float.isNaN(w) && (Float.isNaN(p.water[i]) || w > p.water[i])) {
                p.water[i] = w;
                p.src[i] = srcIdx;
                p.pos[i] = (srcIdx >= 0 && ARC_POS != null) ? ARC_POS[srcIdx] : Float.NaN;
            }
            if (wid > p.width[i]) {
                p.width[i] = wid;
            }
            if (dep > p.depth[i]) {
                p.depth[i] = dep;
            }
            if (fl > p.fall[i]) {
                p.fall[i] = fl;
            }
            if (dw > p.deltaW[i]) {
                p.deltaW[i] = dw;
            }
            return;
        }
        p.distance[i] = 0f;
        p.magnitude[i] = m;
        p.water[i] = w;
        p.src[i] = srcIdx;
        p.pos[i] = (srcIdx >= 0 && ARC_POS != null) ? ARC_POS[srcIdx] : Float.NaN;
        p.width[i] = wid;
        p.depth[i] = dep;
        p.fall[i] = fl;
        p.deltaW[i] = dw;
    }

    // Smallest catchment worth carrying in, in native cells
    private static final float SEED_MIN_CELLS =
            Float.parseFloat(System.getProperty("terradiff.seedMinCells", "200000"));

    private static float[] accumulate(WorldPipeline pipeline, float[] elev, float[] slopeSurface,
                                      float[] fillDepthOut, int[] downOut, float[] slopeOut,
                                      int windowI, int windowJ, float blockM, float[] seaDist,
                                      boolean[] extraOutlets) {
        int n = GRID * GRID;
        float[] filled = elev.clone();
        boolean[] closed = new boolean[n];

        Arrays.fill(slopeOut, Float.MAX_VALUE);

        Arrays.fill(downOut, -1);

        LongHeap open = new LongHeap(n);

        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            if (elev[i] < 0f || (extraOutlets != null && extraOutlets[i])
                    || r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                closed[i] = true;
                open.push(pack(filled[i], i));
            }
        }

        int[] nr4 = {-1, -1, -1, 0, 0, 1, 1, 1}, nc4 = {-1, 0, 1, -1, 1, -1, 0, 1};
        while (!open.isEmpty()) {
            int i = (int) open.pop();
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr4[k], cc = c + nc4[k];
                if (rr < 0 || cc < 0 || rr >= GRID || cc >= GRID) continue;
                int j = rr * GRID + cc;
                if (closed[j]) continue;
                closed[j] = true;
                if (filled[j] <= filled[i]) {
                    filled[j] = filled[i] + FILL_EPSILON_M;
                }
                open.push(pack(filled[j], j));
            }
        }

        phase("  flood");
        int[] nr8 = {-1, -1, -1, 0, 0, 1, 1, 1}, nc8 = {-1, 0, 1, -1, 1, -1, 0, 1};

        float[] toOutlet = new float[n];
        Arrays.fill(toOutlet, -1f);
        int[] flatQueue = new int[n];
        int flatHead = 0, flatTail = 0;

        for (int i = 0; i < n; i++) {
            int r = i / GRID, c = i % GRID;
            if (r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                continue;
            }
            for (int k = 0; k < 8; k++) {
                if (filled[i] - filled[(r + nr8[k]) * GRID + (c + nc8[k])] > FLAT_DROP_M) {
                    toOutlet[i] = 0f;
                    flatQueue[flatTail++] = i;
                    break;
                }
            }
        }

        while (flatHead < flatTail) {
            int i = flatQueue[flatHead++];
            int r = i / GRID, c = i % GRID;
            for (int k = 0; k < 8; k++) {
                int rr = r + nr8[k], cc = c + nc8[k];
                if (rr < 1 || cc < 1 || rr >= GRID - 1 || cc >= GRID - 1) {
                    continue;
                }
                int j = rr * GRID + cc;
                if (toOutlet[j] >= 0f) {
                    continue;
                }
                toOutlet[j] = toOutlet[i] + 1f;
                flatQueue[flatTail++] = j;
            }
        }

        for (int i = 0; i < n; i++) {
            if (toOutlet[i] > 0f) {

                float n01 = 0.5f + 0.5f * ROUTING_DETAIL.GetNoise(
                        (windowJ + i % GRID) * (float) DOWNSAMPLE,
                        (windowI + i / GRID) * (float) DOWNSAMPLE);
                filled[i] += Math.min(FLAT_GRADIENT_M * (toOutlet[i] + n01), FLAT_TILT_MAX_M);
            }
        }

        int noDescentReported = 0;
        phase("  flatBFS");
        int[] ascending = orderBy(filled, n);
        phase("  orderBy");

        float[] acc = new float[n];
        Arrays.fill(acc, 1f);

        final float QUADRANT = (float) (Math.PI / 4.0);
        for (int oi = n - 1; oi >= 0; oi--) {
            int i = ascending[oi];
            if (elev[i] < 0f || (extraOutlets != null && extraOutlets[i])) {
                continue;
            }
            int r = i / GRID, c = i % GRID;
            if (r == 0 || c == 0 || r == GRID - 1 || c == GRID - 1) {
                continue;
            }

            float steepest = 0f;
            for (int k = 0; k < 8; k++) {
                int j = (r + nr8[k]) * GRID + (c + nc8[k]);
                float step = (nr8[k] != 0 && nc8[k] != 0) ? 1.41421f : 1f;

                float trueSlope = (slopeSurface[i] - slopeSurface[j]) / (step * CELL_SIZE_M);
                if (trueSlope > steepest) {
                    steepest = trueSlope;
                }
            }
            slopeOut[i] = steepest;

            float bestSlope = 0f, bestAngle = 0f;
            int bestCardinal = -1, bestDiagonal = -1;
            float bestS1 = 0f, bestS2 = 0f;
            int bestClamp = 0;
            for (int f = 0; f < 8; f++) {
                int cardinal = (r + FACET_CR[f]) * GRID + (c + FACET_CC[f]);
                int diagonal = (r + FACET_DR[f]) * GRID + (c + FACET_DC[f]);
                float s1 = filled[i] - filled[cardinal];
                float s2 = filled[cardinal] - filled[diagonal];
                float mag;
                int clamp;
                if (s2 < 0f) {
                    mag = s1;
                    clamp = -1;
                } else if ((s1 <= 0f && !(s1 == 0f && s2 == 0f)) || s2 > s1) {
                    mag = (filled[i] - filled[diagonal]) / 1.41421f;
                    clamp = 1;
                } else {
                    mag = (float) Math.sqrt(s1 * s1 + s2 * s2);
                    clamp = 0;
                }
                if (mag > bestSlope) {
                    bestSlope = mag;
                    bestCardinal = cardinal;
                    bestDiagonal = diagonal;
                    bestS1 = s1;
                    bestS2 = s2;
                    bestClamp = clamp;
                }
            }
            bestAngle = bestClamp < 0 ? 0f
                    : bestClamp > 0 ? QUADRANT
                    : (float) Math.atan2(bestS2, bestS1);

            if (bestCardinal < 0 || bestSlope <= 0f) {

                if (noDescentReported < 4 && Boolean.getBoolean("terradiff.diag")) {
                    noDescentReported++;
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format(
                            "diag noDescent r=%d c=%d elev=%.2f filled=%.6f toOutlet=%.0f acc=%.0f nbrs:",
                            r, c, elev[i], filled[i], toOutlet[i], acc[i]));
                    for (int k = 0; k < 8; k++) {
                        int j = (r + nr8[k]) * GRID + (c + nc8[k]);
                        sb.append(String.format(" [%+d%+d]%.6f(d%+.2e)",
                                nr8[k], nc8[k], filled[j], filled[i] - filled[j]));
                    }
                    System.err.println(sb);
                }
                downOut[i] = -1;
                continue;
            }

            float toDiagonal = bestAngle / QUADRANT;
            float toCardinal = 1f - toDiagonal;
            acc[bestCardinal] += acc[i] * toCardinal;
            acc[bestDiagonal] += acc[i] * toDiagonal;

            downOut[i] = toCardinal >= toDiagonal ? bestCardinal : bestDiagonal;
        }
        phase("  dinfRouting");
        for (int i = 0; i < n; i++) fillDepthOut[i] = filled[i] - elev[i];
        return acc;
    }

    private static void edt2d(float[] f, int w, int h) {
        int longest = Math.max(w, h);
        float[] col = new float[longest];
        float[] out = new float[longest];
        int[] v = new int[longest];
        float[] z = new float[longest + 1];

        for (int c = 0; c < w; c++) {
            for (int r = 0; r < h; r++) col[r] = f[r * w + c];
            edt1d(col, out, v, z, h);
            for (int r = 0; r < h; r++) f[r * w + c] = out[r];
        }
        for (int r = 0; r < h; r++) {
            System.arraycopy(f, r * w, col, 0, w);
            edt1d(col, out, v, z, w);
            System.arraycopy(out, 0, f, r * w, w);
        }
    }

    private static void edt1d(float[] f, float[] d, int[] v, float[] z, int n) {
        final float INF = Float.MAX_VALUE / 4f;
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            float s = ((f[q] + q * (float) q) - (f[v[k]] + v[k] * (float) v[k]))
                    / (2f * q - 2f * v[k]);
            while (k > 0 && s <= z[k]) {
                k--;
                s = ((f[q] + q * (float) q) - (f[v[k]] + v[k] * (float) v[k]))
                        / (2f * q - 2f * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            float dx = q - v[k];
            d[q] = dx * dx + f[v[k]];
        }
    }

    private static void chamfer(Paint p) {
        final float ORTH = 1f, DIAG = 1.41421f;
        for (int r = 0; r < GRID; r++)
            for (int c = 0; c < GRID; c++) {
                int i = r * GRID + c;
                if (r > 0) relax(p, i, i - GRID, ORTH);
                if (c > 0) relax(p, i, i - 1, ORTH);
                if (r > 0 && c > 0) relax(p, i, i - GRID - 1, DIAG);
                if (r > 0 && c < GRID - 1) relax(p, i, i - GRID + 1, DIAG);
            }
        for (int r = GRID - 1; r >= 0; r--)
            for (int c = GRID - 1; c >= 0; c--) {
                int i = r * GRID + c;
                if (r < GRID - 1) relax(p, i, i + GRID, ORTH);
                if (c < GRID - 1) relax(p, i, i + 1, ORTH);
                if (r < GRID - 1 && c < GRID - 1) relax(p, i, i + GRID + 1, DIAG);
                if (r < GRID - 1 && c > 0) relax(p, i, i + GRID - 1, DIAG);
            }
    }

    private static void relax(Paint p, int i, int from, float step) {
        if (p.distance[from] == Float.MAX_VALUE) {
            return;
        }
        float candidate = p.distance[from] + step;
        if (candidate < p.distance[i]) {
            p.distance[i] = candidate;
            p.src[i] = p.src[from];
            p.pos[i] = p.pos[from];
            p.magnitude[i] = p.magnitude[from];
            p.water[i] = p.water[from];
            p.deltaW[i] = p.deltaW[from];
            p.width[i] = p.width[from];
            p.depth[i] = p.depth[from];
            p.fall[i] = p.fall[from];
        }
    }
}
