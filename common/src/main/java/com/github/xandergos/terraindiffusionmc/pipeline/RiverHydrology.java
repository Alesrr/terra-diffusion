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

    private static final int REGION_PX = 128;

    private static final int MARGIN_PX = 512;
    private static final int WINDOW_PX = REGION_PX + 2 * MARGIN_PX;

    private static final int DOWNSAMPLE = 1;
    private static final int GRID = WINDOW_PX / DOWNSAMPLE;

    private static final int CROP_RIM = 28;
    private static final int CROP_OFF = MARGIN_PX / DOWNSAMPLE - CROP_RIM;
    private static final int CROP_N = REGION_PX / DOWNSAMPLE + 2 * CROP_RIM;

    private static final float RIVER_THRESHOLD = 600f;

    private static final float DISCHARGE_AREA_EXPONENT = 0.8f;
    private static final float WIDTH_Q_EXPONENT = 0.5f;
    private static final float DEPTH_Q_EXPONENT = 0.4f;

    private static final float BANKFULL_WIDTH_M = 58f;
    private static final float BANKFULL_DEPTH_M = 9f;

    private static final float MAX_WIDTH_M = 340f;
    private static final float MAX_DEPTH_M = 45f;

    private static final float CONFINEMENT_FRACTION = 0.72f;

    private static final int CONFINEMENT_SCAN_CELLS = 16;
    private static final float CONFINEMENT_RISE_M = 18f;

    private static final float ORDER_WIDTH_GAIN = 0.06f;

    private static final float FREEBOARD_BLOCKS = 0.5f;

    private static final float V2_INFLUENCE_CELLS = 24f;

    // Streams speed range of river water
    public static final float RIVER_MIN_SPEED = 0.35f, RIVER_MAX_SPEED = 1.4f;

    // Streams speed of open lake water, slowest and fastest, below the river range
    private static final float LAKE_MIN_SPEED = 0.05f;
    private static final float LAKE_BODY_SPEED = 0.22f;

    // Cells over which the current speeds up into the outflow, plus two outflow widths
    private static final float LAKE_FUNNEL_CELLS = 6f;

    // Cells of lake an inflowing river keeps its direction for, plus one and a half river widths
    private static final float LAKE_PLUME_CELLS = 3f;

    // Share of the inflowing river's speed the plume starts with
    private static final float LAKE_PLUME_KEEP = 0.6f;

    // Cells along the path to the outflow that set a lake cell's direction
    private static final int LAKE_TANGENT_CELLS = 8;

    private static final int V2_WIDTH_SMOOTH = 6;

    private static final int V2_SMOOTH_CELLS = 4;

    private static final float V2_LAKE_MIN_DEPTH_M = 4f;

    private static final int V2_LAKE_MIN_CELLS = 40;

    static final float V2_HEAD_MIN_M = 2500f;

    private static final int V2_FLOW_VIEWS = 12;

    private static final int V2_LAKE_CORES = 128;

    private static final int V2_LAKE_FINALS = 8192;

    private static final long V2_LAKE_MAX_CELLS = 3000000L;

    private static final int V2_LAKE_WALK = 12;

    private static final long V2_LAKE_OUTLET_CELLS = 200L;


    private static final int V2_LAKE_MAX_REGIONS = 96;

    private static final int V2_LAKE_PIT_CELLS = 20000;

    private static final float V2_LAKE_CLIMB_M = 600f;


    private static final int V2_LINKS = 4096;

    private static final int V2_LINK_DEPTH = 24;

    private static final float V2_LINK_MIN_ACC = 400f;

    private static final float V2_MOUTH_CELLS = 16f;

    private static final float V2_MOUTH_WIDTHS = 6f;

    private static final float V2_MOUTH_FLARE = 2.2f;

    private static final float V2_MOUTH_DEPTH_BLOCKS = 1f;

    private static final int V2_MAX_MARGIN = 128;

    private static final int V2_BASE_MARGIN = 128;

    private static final int V2_LOOP_HOPS = 6;


    private static final float V2_CHANNEL_AREA = 4000f;

    private static final float V2_WATCH_AREA = Math.min(V2_CHANNEL_AREA,
            4000f);

    private static final float V2_SEAM_ENTRY = 0.5f;

    private static final float V2_WIDTH_RAMP = 4f;

    private static final float V2_ESCALATE_DROP_M = 20f;

    private static final float V2_ESCALATE_BASIN_M = 20f;

    private static final int V2_EDGE_CELLS = 32;

    private static final float V2_MISSING_RIVER_AREA = 16000f;

    private static final int V2_MISSING_RIVER_MAX_MARGIN = 256;

    private static final float V2_MISSING_RIVER_FRACTION = 0.25f;

    private static final float V2_BASIN_DROP_FACTOR = 2f;

    private static final float FILL_EPSILON_M = 1.0e-2f;

    private static final float CELL_SIZE_M = 30f;

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
        private float[] segments;
        private float[] lakeFlow;
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

    public interface InflowSource {
        float[] inflow(int windowI, int windowJ, int size, float[] elev);
    }

    public static volatile InflowSource INFLOW;

    private static final int MAX_CACHED_REGIONS =
            Math.max(6, Math.min(96, (int) (6L * 1080 * 1080 / ((long) CROP_N * CROP_N))));

    private static final class FlowView {
        final int regionI, regionJ, margin, g, windowI, windowJ, popped;
        final float[] elev, filled, acc, lake, lakeDeep;
        final int[] down, order;
        final boolean[] channel, high, firm;
        final boolean riversHere;

        FlowView(int regionI, int regionJ, int margin, int g, int windowI, int windowJ, int popped, float[] elev, float[] filled,
                 float[] acc, float[] lake, float[] lakeDeep, int[] down, int[] order, boolean[] channel, boolean[] high,
                 boolean[] firm, boolean riversHere) {
            this.regionI = regionI;
            this.regionJ = regionJ;
            this.margin = margin;
            this.g = g;
            this.windowI = windowI;
            this.windowJ = windowJ;
            this.popped = popped;
            this.elev = elev;
            this.filled = filled;
            this.acc = acc;
            this.lake = lake;
            this.lakeDeep = lakeDeep;
            this.down = down;
            this.order = order;
            this.channel = channel;
            this.high = high;
            this.firm = firm;
            this.riversHere = riversHere;
        }
    }

    private static final class LakeCore {
        final float[] elev = new float[REGION_PX * REGION_PX];
        final float[] lake = new float[REGION_PX * REGION_PX];
        final float[] fill = new float[REGION_PX * REGION_PX];
        final boolean[] fed = new boolean[REGION_PX * REGION_PX];
        final float[] level = new float[REGION_PX * REGION_PX];
        final float[] deep = new float[REGION_PX * REGION_PX];
        final java.util.List<Integer> outlets = new java.util.ArrayList<>();
        final java.util.Set<Integer> barrier = new java.util.HashSet<>();
        final boolean[] firm = new boolean[REGION_PX * REGION_PX];
        final short[] next = new short[REGION_PX * REGION_PX];
        short[] exitOf;
        boolean seeded;

        LakeCore(FlowView v) {
            for (int r = 0; r < REGION_PX; r++) {
                int from = (v.margin + r) * v.g + v.margin;
                System.arraycopy(v.elev, from, elev, r * REGION_PX, REGION_PX);
                System.arraycopy(v.lake, from, lake, r * REGION_PX, REGION_PX);
                System.arraycopy(v.filled, from, fill, r * REGION_PX, REGION_PX);
                System.arraycopy(v.firm, from, firm, r * REGION_PX, REGION_PX);
                if (v.high != null) System.arraycopy(v.high, from, fed, r * REGION_PX, REGION_PX);
                for (int c = 0; c < REGION_PX; c++) {
                    int d = v.down[from + c];
                    int nr = d < 0 ? -1 : d / v.g - v.margin, nc = d < 0 ? -1 : d % v.g - v.margin;
                    next[r * REGION_PX + c] = nr >= 0 && nc >= 0 && nr < REGION_PX && nc < REGION_PX ? (short) (nr * REGION_PX + nc) : -1;
                }
            }
            Arrays.fill(level, Float.NaN);
        }

        void feedFrom(int ix) {
            for (int at = ix; at >= 0 && !fed[at]; at = next[at]) fed[at] = true;
        }
    }

    private static final class LakeFinal {
        final java.util.List<long[]> masks = new java.util.ArrayList<>();
        final java.util.List<float[]> levels = new java.util.ArrayList<>();
        final java.util.List<Integer> outlets = new java.util.ArrayList<>();
        final java.util.Set<Integer> barrier = new java.util.HashSet<>();

        void add(boolean[] mask, float level, float deep) {
            long[] bits = new long[mask.length / 64 + 1];
            for (int ix = 0; ix < mask.length; ix++) {
                if (mask[ix]) bits[ix >> 6] |= 1L << (ix & 63);
            }
            masks.add(bits);
            levels.add(new float[]{level, deep});
        }

        void applyTo(LakeCore core) {
            for (int m = 0; m < masks.size(); m++) {
                long[] bits = masks.get(m);
                float[] v = levels.get(m);
                for (int ix = 0; ix < core.level.length; ix++) {
                    if ((bits[ix >> 6] >> (ix & 63) & 1L) != 0L) {
                        core.level[ix] = v[0];
                        core.deep[ix] = v[1];
                    }
                }
            }
            core.outlets.addAll(outlets);
            core.barrier.addAll(barrier);
        }
    }

    private static final class RiverLink {
        final int regionI, regionJ, exits;
        final boolean riversHere;
        final int[] landI, landJ, exitAsk;
        final float[] exitAcc, exitLevel;
        final boolean[] exitHigh;
        final short[] ringExit;
        final float[] ringLake;
        final int[] falseFrom, trueUpTo;
        int nearLakes, epoch;
        boolean lakesSeen;
        final boolean[] trueExact;

        RiverLink(int regionI, int regionJ, boolean riversHere, int exits, int[] landI, int[] landJ, float[] exitAcc,
                  boolean[] exitHigh, float[] exitLevel, int[] exitAsk, short[] ringExit, float[] ringLake, int nearLakes) {
            this.regionI = regionI;
            this.regionJ = regionJ;
            this.riversHere = riversHere;
            this.exits = exits;
            this.landI = landI;
            this.landJ = landJ;
            this.exitAcc = exitAcc;
            this.exitHigh = exitHigh;
            this.exitLevel = exitLevel;
            this.nearLakes = nearLakes;
            this.exitAsk = exitAsk;
            this.ringExit = ringExit;
            this.ringLake = ringLake;
            this.falseFrom = new int[exits];
            Arrays.fill(this.falseFrom, Integer.MAX_VALUE);
            this.trueUpTo = new int[exits];
            Arrays.fill(this.trueUpTo, -1);
            this.trueExact = new boolean[exits];
        }
    }

    private static <V> Map<Long, V> recentOnly(int size) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, V> eldest) {
                return size() > size;
            }
        });
    }

    // Hydrology regions built for one pipeline, least recently used evicted first
    public static final class RegionCache {
        private final Map<Long, Region> regions = new ConcurrentHashMap<>();
        private final Map<Long, FlowView> views = recentOnly(V2_FLOW_VIEWS);
        private final Map<Long, RiverLink> links = recentOnly(V2_LINKS);
        private final Map<Long, LakeCore> lakes = recentOnly(V2_LAKE_CORES);
        private final Map<Long, LakeFinal> finals = recentOnly(V2_LAKE_FINALS);
        private int outletEpoch;
        private final Map<Long, AtomicLong> used = new ConcurrentHashMap<>();
        private final AtomicLong clock = new AtomicLong();

        public void clear() {
            regions.clear();
            used.clear();
            views.clear();
            links.clear();
            lakes.clear();
            finals.clear();
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
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, ri, rj, blockM);
        return region == null || region.karst == null ? KarstNetwork.EMPTY : region.karst;
    }

    public static float[] sampleChannel(WorldPipeline pipeline, float nativeI, float nativeJ,
                                       float blockM) {
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        return sampleFrom(pipeline, ri, rj, nativeI, nativeJ, blockM);
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

        float d;
        float segLevel = Float.NaN;
        float dirR = 0f, dirC = 0f, slope = 0f;
        if (region.segments != null) {
            d = Float.MAX_VALUE;
            for (int k : new int[]{a, b, c, d2}) {
                int o = SEGMENT_FIELDS * k;
                float[] sg = region.segments;
                if (Float.isNaN(sg[o])) continue;
                for (int part = 0; part < 2; part++) {
                    int p0 = o + 2 * part;
                    float vr = sg[p0 + 2] - sg[p0], vc = sg[p0 + 3] - sg[p0 + 1];
                    float len2 = vr * vr + vc * vc;
                    float t = len2 > 1.0e-6f ? Math.max(0f, Math.min(1f, ((gi - sg[p0]) * vr + (gj - sg[p0 + 1]) * vc) / len2)) : 0f;
                    float er = gi - (sg[p0] + t * vr), ec = gj - (sg[p0 + 1] + t * vc);
                    float dd = (float) Math.sqrt(er * er + ec * ec);
                    if (dd < d) {
                        d = dd;
                        float tl = t;
                        if (len2 > 1.0e-6f) {
                            tl = Math.abs(vr) >= Math.abs(vc) ? (gi - sg[p0]) / vr : (gj - sg[p0 + 1]) / vc;
                            tl = Math.max(0f, Math.min(1f, tl));
                        }
                        if (len2 > 1.0e-6f) {
                            dirR = vr;
                            dirC = vc;
                            slope = (sg[o + 6 + part] - sg[o + 7 + part]) / ((float) Math.sqrt(len2) * CELL_SIZE_M);
                            if (slope >= V2_FALL_SLOPE) tl = 1f;
                        }
                        segLevel = sg[o + 6 + part] + (sg[o + 7 + part] - sg[o + 6 + part]) * tl;
                    }
                }
            }
        } else {
            d = lerp(lerp(region.distance[a], region.distance[b], tj),
                     lerp(region.distance[c], region.distance[d2], tj), ti);
        }
        if (d >= V2_INFLUENCE_CELLS) {
            return null;
        }
        float m = lerp(lerp(region.magnitude[a], region.magnitude[b], tj),
                       lerp(region.magnitude[c], region.magnitude[d2], tj), ti);

        float w = region.water[nearest(region, gi, gj)];
        if (!Float.isNaN(segLevel)) {
            w = segLevel;
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
        return new float[]{d * DOWNSAMPLE, m, w, dw, ws, wid, dep, fl, dirR, dirC, slope};
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
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
        float deep = region.lakeDeep == null || Float.isNaN(region.lakeDeep[at])
                ? 0f : region.lakeDeep[at];
        return new float[]{(float) Math.ceil(Math.floor(near / blockM) * blockM), deep};
    }

    // Lake levels over a native window, each cell read through the region of the nearest cell in [ti0, ti1] x [tj0, tj1]
    public static float[] lakeWindow(WorldPipeline pipeline, int ci0, int cj0, int rows, int cols,
                                     int ti0, int tj0, int ti1, int tj1, float blockM) {
        float[] out = new float[rows * cols];
        Arrays.fill(out, Float.NaN);
        Region region = null;
        long held = 0L;
        boolean holding = false;
        for (int r = 0; r < rows; r++) {
            int ci = ci0 + r, oi = Math.max(ti0, Math.min(ti1, ci));
            for (int c = 0; c < cols; c++) {
                int cj = cj0 + c, oj = Math.max(tj0, Math.min(tj1, cj));
                int ri = Math.floorDiv(oi, REGION_PX), rj = Math.floorDiv(oj, REGION_PX);
                long k = key(ri, rj);
                if (!holding || k != held) {
                    region = regionFor(pipeline, ri, rj, blockM);
                    held = k;
                    holding = true;
                }
                if (region == null || region.lake == null) continue;
                int gi = Math.floorDiv(ci - (ri * REGION_PX - MARGIN_PX), DOWNSAMPLE) - CROP_OFF;
                int gj = Math.floorDiv(cj - (rj * REGION_PX - MARGIN_PX), DOWNSAMPLE) - CROP_OFF;
                if (gi < 0 || gj < 0 || gi >= CROP_N || gj >= CROP_N) continue;
                int at = gi * CROP_N + gj;
                float near = region.lake[at];
                if (Float.isNaN(near)) continue;
                out[r * cols + c] = (float) Math.ceil(Math.floor(near / blockM) * blockM);
            }
        }
        return out;
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

            if (drained || atBorder
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

        Region built = buildV2(pipeline, regionI, regionJ, blockM);
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

    private static Region buildV2(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        RiverLink self = linkFor(pipeline, regionI, regionJ, blockM);
        FlowView view = flowFor(pipeline, regionI, regionJ, blockM);
        pipeline.hydrology.views.remove(key(regionI, regionJ));
        return drawV2(pipeline, view, self, blockM);
    }

    private static FlowView flowFor(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        Map<Long, FlowView> views = pipeline.hydrology.views;
        FlowView view = views.get(key(regionI, regionJ));
        if (view != null) {
            return view;
        }
        int margin = V2_BASE_MARGIN;
        while (true) {
            boolean last = margin >= V2_MAX_MARGIN;
            view = flowV2(pipeline, regionI, regionJ, blockM, margin, last);
            if (view != null) {
                views.put(key(regionI, regionJ), view);
                if (!pipeline.hydrology.lakes.containsKey(key(regionI, regionJ))) {
                    pipeline.hydrology.lakes.put(key(regionI, regionJ), newLakeCore(pipeline, view));
                }
                return view;
            }
            margin = Math.min(V2_MAX_MARGIN, margin * 2);
        }
    }

    private static LakeCore lakeCore(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        LakeCore core = pipeline.hydrology.lakes.get(key(regionI, regionJ));
        if (core == null) {
            core = newLakeCore(pipeline, flowFor(pipeline, regionI, regionJ, blockM));
            pipeline.hydrology.lakes.put(key(regionI, regionJ), core);
        }
        return core;
    }

    private static LakeCore newLakeCore(WorldPipeline pipeline, FlowView v) {
        LakeCore core = new LakeCore(v);
        core.exitOf = exitOf(v);
        LakeFinal done = pipeline.hydrology.finals.get(key(v.regionI, v.regionJ));
        if (done != null) {
            done.applyTo(core);
            for (int ix : done.outlets) core.feedFrom(ix);
        }
        return core;
    }

    private static void floodLake(WorldPipeline pipeline, int startI, int startJ, float startLevel, float blockM) {
        floodLake(pipeline, startI, startJ, startLevel, blockM, false);
    }

    private static void floodLake(WorldPipeline pipeline, int startI, int startJ, float startLevel, float blockM, boolean river) {
        Map<Long, LakeCore> cores = new java.util.HashMap<>();
        Map<Long, boolean[]> seen = new java.util.HashMap<>();
        PriorityQueue<float[]> shore = new PriorityQueue<>(Comparator.comparingDouble(c -> c[0]));
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        float level = startLevel, bottom = Float.MAX_VALUE;
        long cells = 0;
        boolean fed = river, joined = false;
        int[] below = null;
        seen.computeIfAbsent(key(Math.floorDiv(startI, REGION_PX), Math.floorDiv(startJ, REGION_PX)), k -> new boolean[REGION_PX * REGION_PX])[Math.floorMod(startI, REGION_PX) * REGION_PX + Math.floorMod(startJ, REGION_PX)] = true;
        open.push(new int[]{startI, startJ});
        while (true) {
            while (!open.isEmpty()) {
                int[] at = open.pop();
                LakeCore core = cachedCore(pipeline, cores, Math.floorDiv(at[0], REGION_PX), Math.floorDiv(at[1], REGION_PX), blockM);
                int ix = Math.floorMod(at[0], REGION_PX) * REGION_PX + Math.floorMod(at[1], REGION_PX);
                bottom = Math.min(bottom, core.elev[ix]);
                fed |= core.fed[ix];
                cells++;
                for (int k = 0; k < 8; k++) {
                    int ni = at[0] + V2_DR[k], nj = at[1] + V2_DC[k];
                    int qi = Math.floorDiv(ni, REGION_PX), qj = Math.floorDiv(nj, REGION_PX);
                    boolean[] mask = seen.computeIfAbsent(key(qi, qj), x -> new boolean[REGION_PX * REGION_PX]);
                    int nx = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
                    if (mask[nx]) continue;
                    LakeCore other = cachedCore(pipeline, cores, qi, qj, blockM);
                    float e = other.elev[nx];
                    if (e < 0f || other.barrier.contains(nx)) continue;
                    if (!Float.isNaN(other.level[nx])) {
                        if (Math.abs(other.level[nx] - level) <= 0.01f) joined = true;
                        continue;
                    }
                    if (!Float.isNaN(other.lake[nx])) {
                        if (other.lake[nx] > level) level = other.lake[nx];
                    } else if (e >= level) {
                        shore.add(new float[]{e, ni, nj});
                        continue;
                    }
                    if (cells < V2_LAKE_MAX_CELLS && seen.size() < V2_LAKE_MAX_REGIONS) {
                        mask[nx] = true;
                        open.push(new int[]{ni, nj});
                    }
                }
            }
            float[] next = shore.poll();
            if (next == null) break;
            int si = (int) next[1], sj = (int) next[2];
            int qi = Math.floorDiv(si, REGION_PX), qj = Math.floorDiv(sj, REGION_PX);
            boolean[] mask = seen.get(key(qi, qj));
            int sx = Math.floorMod(si, REGION_PX) * REGION_PX + Math.floorMod(sj, REGION_PX);
            if (mask[sx]) continue;
            if (next[0] < level) {
                mask[sx] = true;
                open.push(new int[]{si, sj});
                continue;
            }
            if (joined || !river && cells < V2_LAKE_OUTLET_CELLS || seen.size() >= V2_LAKE_MAX_REGIONS
                    || next[0] > startLevel + V2_LAKE_CLIMB_M) break;
            fed = fed || fedAcrossSeam(pipeline, seen, blockM);
            if (!fed) break;
            mask[sx] = true;
            for (int k = 0; k < 8 && below == null; k++) {
                int ni = si + V2_DR[k], nj = sj + V2_DC[k];
                int ri = Math.floorDiv(ni, REGION_PX), rj = Math.floorDiv(nj, REGION_PX);
                boolean[] other = seen.computeIfAbsent(key(ri, rj), x -> new boolean[REGION_PX * REGION_PX]);
                int nx = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
                if (other[nx]) continue;
                float e = cachedCore(pipeline, cores, ri, rj, blockM).elev[nx];
                if (e >= level) continue;
                java.util.List<int[]> lobe = e < 0f ? null : lobeBelow(pipeline, cores, seen, ni, nj, level, blockM);
                if (lobe == null) {
                    below = new int[]{ni, nj};
                } else {
                    for (int[] c : lobe) {
                        seen.get(key(Math.floorDiv(c[0], REGION_PX), Math.floorDiv(c[1], REGION_PX)))[Math.floorMod(c[0], REGION_PX) * REGION_PX + Math.floorMod(c[1], REGION_PX)] = true;
                        open.push(c);
                    }
                }
            }
            level = Math.max(level, next[0] - FREEBOARD_BLOCKS * blockM);
            cells++;
            if (below != null) {
                for (int k = 0; k < 8; k++) {
                    int ni = si + V2_DR[k], nj = sj + V2_DC[k];
                    int ri = Math.floorDiv(ni, REGION_PX), rj = Math.floorDiv(nj, REGION_PX);
                    boolean[] other = seen.computeIfAbsent(key(ri, rj), x -> new boolean[REGION_PX * REGION_PX]);
                    int nx = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
                    LakeCore core = cachedCore(pipeline, cores, ri, rj, blockM);
                    if (other[nx] || core.elev[nx] >= next[0]) continue;
                    core.barrier.add(nx);
                    pipeline.hydrology.finals.computeIfAbsent(key(ri, rj), x -> new LakeFinal()).barrier.add(nx);
                }
                break;
            }
            open.push(new int[]{si, sj});
        }
        float deep = level + FREEBOARD_BLOCKS * blockM - bottom;
        for (Map.Entry<Long, boolean[]> e : seen.entrySet()) {
            LakeCore core = cores.get(e.getKey());
            if (core == null) continue;
            boolean[] mask = e.getValue();
            boolean any = false;
            for (int ix = 0; ix < mask.length; ix++) {
                if (mask[ix]) {
                    core.level[ix] = level;
                    core.deep[ix] = deep;
                    any = true;
                }
            }
            if (any) pipeline.hydrology.finals.computeIfAbsent(e.getKey(), k -> new LakeFinal()).add(mask, level, deep);
        }
        if (below != null) pushOutlet(pipeline, below[0], below[1], blockM);
    }

    private static void pushOutlet(WorldPipeline pipeline, int startI, int startJ, float blockM) {
        int ni = startI, nj = startJ;
        for (int hop = 0; hop < V2_LAKE_WALK * 4; hop++) {
            int oi = Math.floorDiv(ni, REGION_PX), oj = Math.floorDiv(nj, REGION_PX);
            int ox = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
            LakeCore out = lakeCore(pipeline, oi, oj, blockM);
            if (out.elev[ox] < 0f || out.outlets.contains(ox)) return;
            out.outlets.add(ox);
            out.feedFrom(ox);
            pipeline.hydrology.finals.computeIfAbsent(key(oi, oj), k -> new LakeFinal()).outlets.add(ox);
            RiverLink link = linkFor(pipeline, oi, oj, blockM);
            int id = out.exitOf[ox];
            if (id < 0) return;
            if (!link.exitHigh[id]) pipeline.hydrology.outletEpoch++;
            link.exitHigh[id] = true;
            if (link.exitAcc[id] >= V2_LINK_MIN_ACC) return;
            ni = link.landI[id];
            nj = link.landJ[id];
        }
    }

    private static float[] seamLoop(WorldPipeline pipeline, RiverLink from, int x, float blockM) {
        Map<Long, Integer> seen = new java.util.HashMap<>();
        float[] levels = new float[V2_LOOP_HOPS];
        RiverLink at = from;
        int here = x;
        for (int hop = 0; hop < V2_LOOP_HOPS && here >= 0; hop++) {
            long visit = (key(at.regionI, at.regionJ) * 1021L) ^ here;
            Integer first = seen.putIfAbsent(visit, hop);
            if (first != null) {
                float top = -Float.MAX_VALUE;
                for (int k = first; k < hop; k++) top = Math.max(top, levels[k]);
                return new float[]{at.landI[here], at.landJ[here], top};
            }
            levels[hop] = at.exitLevel[here];
            int li = at.landI[here], lj = at.landJ[here];
            at = linkFor(pipeline, Math.floorDiv(li, REGION_PX), Math.floorDiv(lj, REGION_PX), blockM);
            here = at.ringExit[ringIndex(Math.floorMod(li, REGION_PX), Math.floorMod(lj, REGION_PX))];
        }
        return null;
    }

    private static boolean fedAcrossSeam(WorldPipeline pipeline, Map<Long, boolean[]> seen, float blockM) {
        for (Map.Entry<Long, boolean[]> e : seen.entrySet()) {
            boolean[] mask = e.getValue();
            boolean any = false;
            for (int ix = 0; ix < mask.length && !any; ix++) any = mask[ix];
            if (!any) continue;
            int regionI = (int) (e.getKey() >> 32), regionJ = e.getKey().intValue();
            RiverLink self = linkFor(pipeline, regionI, regionJ, blockM);
            if (!self.riversHere) continue;
            LakeCore core = lakeCore(pipeline, regionI, regionJ, blockM);
            for (int nb = 0; nb < 9; nb++) {
                if (nb == 4) continue;
                RiverLink q = linkFor(pipeline, regionI + nb / 3 - 1, regionJ + nb % 3 - 1, blockM);
                for (int y = 0; y < q.exits; y++) {
                    int r = q.landI[y] - regionI * REGION_PX, c = q.landJ[y] - regionJ * REGION_PX;
                    if (r < 0 || c < 0 || r >= REGION_PX || c >= REGION_PX) continue;
                    if (q.exitAcc[y] < V2_LINK_MIN_ACC && !q.exitHigh[y]) continue;
                    int at = r * REGION_PX + c;
                    while (at >= 0 && !mask[at]) at = core.next[at];
                    if (at >= 0 && exitKept(pipeline, q, y, V2_LINK_DEPTH - 1, blockM, new Walk(true)) != 0) return true;
                }
            }
        }
        return false;
    }

    private static java.util.List<int[]> lobeBelow(WorldPipeline pipeline, Map<Long, LakeCore> cores, Map<Long, boolean[]> seen,
                                                   int startI, int startJ, float level, float blockM) {
        java.util.List<int[]> lobe = new java.util.ArrayList<>();
        java.util.Set<Long> local = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> open = new java.util.ArrayDeque<>();
        local.add(key(startI, startJ));
        open.add(new int[]{startI, startJ});
        while (!open.isEmpty()) {
            int[] at = open.poll();
            lobe.add(at);
            if (lobe.size() > V2_LAKE_PIT_CELLS) return null;
            for (int k = 0; k < 8; k++) {
                int ni = at[0] + V2_DR[k], nj = at[1] + V2_DC[k];
                int ri = Math.floorDiv(ni, REGION_PX), rj = Math.floorDiv(nj, REGION_PX);
                boolean[] mask = seen.computeIfAbsent(key(ri, rj), x -> new boolean[REGION_PX * REGION_PX]);
                int nx = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
                if (mask[nx]) continue;
                float e = cachedCore(pipeline, cores, ri, rj, blockM).elev[nx];
                if (e < 0f) return null;
                if (e < level && local.add(key(ni, nj))) open.add(new int[]{ni, nj});
            }
        }
        return lobe;
    }

    private static LakeCore cachedCore(WorldPipeline pipeline, Map<Long, LakeCore> cores, int regionI, int regionJ, float blockM) {
        long rk = key(regionI, regionJ);
        LakeCore core = cores.get(rk);
        if (core == null) {
            core = lakeCore(pipeline, regionI, regionJ, blockM);
            cores.put(rk, core);
        }
        return core;
    }

    private static void followToLake(WorldPipeline pipeline, RiverLink link, float blockM) {
        for (int x = 0; x < link.exits; x++) {
            if (link.exitAcc[x] < V2_LINK_MIN_ACC) continue;
            RiverLink at = link;
            int here = x;
            for (int hop = 0; hop < V2_LAKE_WALK && here >= 0; hop++) {
                int li = at.landI[here], lj = at.landJ[here];
                int qi = Math.floorDiv(li, REGION_PX), qj = Math.floorDiv(lj, REGION_PX);
                RiverLink q = linkFor(pipeline, qi, qj, blockM);
                int lr = Math.floorMod(li, REGION_PX), lc = Math.floorMod(lj, REGION_PX);
                int ring = ringIndex(lr, lc);
                float known = q.ringLake[ring];
                if (!Float.isNaN(known)) {
                    seedCore(pipeline, qi, qj, blockM);
                    LakeCore core = lakeCore(pipeline, qi, qj, blockM);
                    int ix = lr * REGION_PX + lc;
                    if (Float.isNaN(core.level[ix]) && core.elev[ix] < known) {
                        floodLake(pipeline, li, lj, known, blockM);
                    }
                    break;
                }
                at = q;
                here = q.ringExit[ring];
            }
        }
    }

    private static void markOutlets(LakeCore own, RiverLink link) {
        for (int ix : own.outlets) {
            if (own.exitOf[ix] >= 0) link.exitHigh[own.exitOf[ix]] = true;
        }
    }

    private static void seedCore(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        LakeCore core = lakeCore(pipeline, regionI, regionJ, blockM);
        if (core.seeded) return;
        core.seeded = true;
        for (int ix = 0; ix < core.lake.length; ix++) {
            if (!Float.isNaN(core.lake[ix]) && Float.isNaN(core.level[ix]) && !core.firm[ix]) {
                floodLake(pipeline, regionI * REGION_PX + ix / REGION_PX, regionJ * REGION_PX + ix % REGION_PX, core.lake[ix], blockM);
            }
        }
    }

    private static void agreeLakes(WorldPipeline pipeline, FlowView v, float blockM) {
        int g = v.g, lo = v.margin - 1, hi = v.margin + REGION_PX + 1;
        for (int r = lo; r < hi; r++) {
            for (int c = lo; c < hi; c++) {
                int i = r * g + c;
                if (Float.isNaN(v.lake[i]) || v.firm[i]) continue;
                int ni = v.windowI + r, nj = v.windowJ + c;
                LakeCore core = lakeCore(pipeline, Math.floorDiv(ni, REGION_PX), Math.floorDiv(nj, REGION_PX), blockM);
                if (Float.isNaN(core.level[Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX)])) {
                    floodLake(pipeline, ni, nj, v.lake[i], blockM);
                }
            }
        }
        int nearLo = Math.max(0, v.margin - REGION_PX), nearHi = Math.min(g, v.margin + 2 * REGION_PX);
        int near = 0;
        for (int r = nearLo; r < nearHi; r++) {
            for (int c = nearLo; c < nearHi; c++) {
                if (r >= v.margin && c >= v.margin && r < v.margin + REGION_PX && c < v.margin + REGION_PX) continue;
                if (Float.isNaN(v.lake[r * g + c]) || v.firm[r * g + c]) continue;
                int dI = r < v.margin ? 0 : r < v.margin + REGION_PX ? 1 : 2, dJ = c < v.margin ? 0 : c < v.margin + REGION_PX ? 1 : 2;
                near |= 1 << (dI * 3 + dJ);
            }
        }
        for (int nb = 0; nb < 9; nb++) {
            if ((near >> nb & 1) != 0) seedCore(pipeline, v.regionI + nb / 3 - 1, v.regionJ + nb % 3 - 1, blockM);
        }
        applyLakes(pipeline, v);
    }

    private static void applyLakes(WorldPipeline pipeline, FlowView v) {
        Map<Long, LakeCore> lakes = pipeline.hydrology.lakes;
        int g = v.g, cropLo = v.margin - CROP_RIM, cropHi = v.margin + REGION_PX + CROP_RIM;
        for (int r = cropLo; r < cropHi; r++) {
            for (int c = cropLo; c < cropHi; c++) {
                int ni = v.windowI + r, nj = v.windowJ + c;
                LakeCore core = lakes.get(key(Math.floorDiv(ni, REGION_PX), Math.floorDiv(nj, REGION_PX)));
                if (core == null) continue;
                int ix = Math.floorMod(ni, REGION_PX) * REGION_PX + Math.floorMod(nj, REGION_PX);
                if (!Float.isNaN(core.level[ix])) {
                    v.lake[r * g + c] = core.level[ix];
                    v.lakeDeep[r * g + c] = core.deep[ix];
                }
            }
        }
    }

    private static RiverLink linkFor(WorldPipeline pipeline, int regionI, int regionJ, float blockM) {
        Map<Long, RiverLink> links = pipeline.hydrology.links;
        RiverLink link = links.get(key(regionI, regionJ));
        if (link == null) {
            FlowView v = flowFor(pipeline, regionI, regionJ, blockM);
            link = linkFrom(v, blockM);
            links.put(key(regionI, regionJ), link);
            if (v.high != null) {
                markOutlets(lakeCore(pipeline, regionI, regionJ, blockM), link);
            }
        }
        return link;
    }

    private static int ringIndex(int r, int c) {
        if (r == 0) return c;
        if (r == REGION_PX - 1) return REGION_PX + c;
        if (c == 0) return 2 * REGION_PX + r;
        return 3 * REGION_PX + r;
    }

    private static short[] exitOf(FlowView v) {
        int g = v.g, lo = v.margin, hi = lo + REGION_PX;
        short[] out = new short[REGION_PX * REGION_PX];
        short exits = 0;
        for (int k = 0; k < v.popped; k++) {
            int i = v.order[k];
            int r = i / g, c = i % g;
            if (r < lo || c < lo || r >= hi || c >= hi) continue;
            short id = -1;
            int d = v.down[i];
            if (d >= 0 && v.elev[d] >= 0f) {
                int rd = d / g, cd = d % g;
                id = rd >= lo && cd >= lo && rd < hi && cd < hi ? out[(rd - lo) * REGION_PX + cd - lo] : exits++;
            }
            out[(r - lo) * REGION_PX + c - lo] = id;
        }
        return out;
    }

    private static RiverLink linkFrom(FlowView v, float blockM) {
        int g = v.g, lo = v.margin, hi = lo + REGION_PX;
        float[] exitLevel = new float[4 * REGION_PX];
        short[] exitOf = new short[REGION_PX * REGION_PX];
        float[] pathLake = new float[REGION_PX * REGION_PX];
        Arrays.fill(pathLake, Float.NaN);
        int[] landI = new int[4 * REGION_PX], landJ = new int[4 * REGION_PX];
        float[] exitAcc = new float[4 * REGION_PX];
        boolean[] exitHigh = new boolean[4 * REGION_PX];
        int exits = 0;
        for (int k = 0; k < v.popped; k++) {
            int i = v.order[k];
            int r = i / g, c = i % g;
            if (r < lo || c < lo || r >= hi || c >= hi) continue;
            int id = -1;
            int d = v.down[i];
            if (d >= 0 && v.elev[d] >= 0f) {
                int rd = d / g, cd = d % g;
                if (rd >= lo && cd >= lo && rd < hi && cd < hi) {
                    id = exitOf[(rd - lo) * REGION_PX + cd - lo];
                    pathLake[(r - lo) * REGION_PX + c - lo] = pathLake[(rd - lo) * REGION_PX + cd - lo];
                } else {
                    id = exits++;
                    landI[id] = v.windowI + rd;
                    landJ[id] = v.windowJ + cd;
                    exitAcc[id] = v.acc[i];
                    exitHigh[id] = v.high != null && v.high[i];
                    exitLevel[id] = !Float.isNaN(v.lake[i]) ? v.lake[i] : Math.max(0f, v.filled[i] - FREEBOARD_BLOCKS * blockM);
                }
            }
            exitOf[(r - lo) * REGION_PX + c - lo] = (short) id;
            if (!Float.isNaN(v.lake[i]) && !v.firm[i]) pathLake[(r - lo) * REGION_PX + c - lo] = v.lake[i];
        }
        short[] ringExit = new short[4 * REGION_PX];
        float[] ringLake = new float[4 * REGION_PX];
        int[] exitAsk = new int[exits];
        for (int r = 0; r < REGION_PX; r++) {
            for (int c = 0; c < REGION_PX; c++) {
                if (r != 0 && c != 0 && r != REGION_PX - 1 && c != REGION_PX - 1) continue;
                short id = exitOf[r * REGION_PX + c];
                ringExit[ringIndex(r, c)] = id;
                ringLake[ringIndex(r, c)] = pathLake[r * REGION_PX + c];
                for (int k = 0; k < 8; k++) {
                    int ur = lo + r + V2_DR[k], uc = lo + c + V2_DC[k];
                    if (ur >= lo && uc >= lo && ur < hi && uc < hi) continue;
                    int dI = ur < lo ? -1 : ur >= hi ? 1 : 0, dJ = uc < lo ? -1 : uc >= hi ? 1 : 0;
                    if (id >= 0) exitAsk[id] |= 1 << ((dI + 1) * 3 + dJ + 1);
                }
            }
        }
        int near = 0;
        int nearLo = Math.max(0, lo - REGION_PX), nearHi = Math.min(g, hi + REGION_PX);
        for (int r = nearLo; r < nearHi; r++) {
            for (int c = nearLo; c < nearHi; c++) {
                int i = r * g + c;
                if (Float.isNaN(v.lake[i]) || v.firm[i]) continue;
                near |= 1 << ((r < lo ? 0 : r < hi ? 1 : 2) * 3 + (c < lo ? 0 : c < hi ? 1 : 2));
            }
        }
        return new RiverLink(v.regionI, v.regionJ, v.riversHere, exits, Arrays.copyOf(landI, exits), Arrays.copyOf(landJ, exits),
                Arrays.copyOf(exitAcc, exits), Arrays.copyOf(exitHigh, exits), Arrays.copyOf(exitLevel, exits), exitAsk, ringExit, ringLake, near);
    }

    private static final class Walk {
        final java.util.HashSet<Long> visiting = new java.util.HashSet<>();
        final boolean duringFlood;
        boolean cycle;

        Walk(boolean duringFlood) {
            this.duringFlood = duringFlood;
        }
    }

    private static int exitKept(WorldPipeline pipeline, RiverLink l, int x, int depth, float blockM, Walk walk) {
        if (!walk.duringFlood && l.riversHere && !l.lakesSeen) {
            l.lakesSeen = true;
            for (int nb = 0; nb < 9; nb++) {
                if ((l.nearLakes >> nb & 1) != 0) seedCore(pipeline, l.regionI + nb / 3 - 1, l.regionJ + nb % 3 - 1, blockM);
            }
            for (int y = 0; y < l.exits; y++) {
                int li = l.landI[y], lj = l.landJ[y];
                RiverLink q = linkFor(pipeline, Math.floorDiv(li, REGION_PX), Math.floorDiv(lj, REGION_PX), blockM);
                if (!Float.isNaN(q.ringLake[ringIndex(Math.floorMod(li, REGION_PX), Math.floorMod(lj, REGION_PX))])) {
                    seedCore(pipeline, q.regionI, q.regionJ, blockM);
                }
            }
        }
        if (l.epoch != pipeline.hydrology.outletEpoch) {
            Arrays.fill(l.falseFrom, Integer.MAX_VALUE);
            l.epoch = pipeline.hydrology.outletEpoch;
        }
        if (l.exitHigh[x]) return 2;
        if (!l.riversHere || l.exitAsk[x] == 0) return 0;
        if (depth >= l.falseFrom[x]) return 0;
        if (l.trueExact[x]) return 2;
        if (depth <= l.trueUpTo[x] || depth == 0) return 1;
        long visit = (key(l.regionI, l.regionJ) * 1021L) ^ x;
        if (!walk.visiting.add(visit)) {
            walk.cycle = true;
            return 0;
        }
        boolean cycleBefore = walk.cycle;
        walk.cycle = false;
        java.util.List<long[]> feeders = new java.util.ArrayList<>();
        int i0 = l.regionI * REGION_PX, j0 = l.regionJ * REGION_PX;
        for (int nb = 0; nb < 9; nb++) {
            if ((l.exitAsk[x] >> nb & 1) == 0) continue;
            int qi = l.regionI + nb / 3 - 1, qj = l.regionJ + nb % 3 - 1;
            RiverLink q = linkFor(pipeline, qi, qj, blockM);
            for (int y = 0; y < q.exits; y++) {
                int r = q.landI[y] - i0, c = q.landJ[y] - j0;
                if (r < 0 || c < 0 || r >= REGION_PX || c >= REGION_PX) continue;
                if (q.exitAcc[y] < V2_LINK_MIN_ACC && !q.exitHigh[y]) continue;
                if (l.ringExit[ringIndex(r, c)] == x) {
                    feeders.add(new long[]{(long) -q.exitAcc[y], nb, y});
                }
            }
        }
        feeders.sort(Comparator.comparingLong(f -> f[0]));
        int result = 0;
        for (long[] f : feeders) {
            int nb = (int) f[1];
            RiverLink q = linkFor(pipeline, l.regionI + nb / 3 - 1, l.regionJ + nb % 3 - 1, blockM);
            result = exitKept(pipeline, q, (int) f[2], depth - 1, blockM, walk);
            if (result > 0) break;
        }
        walk.visiting.remove(visit);
        if (result == 2) {
            l.trueExact[x] = true;
        } else if (result == 1) {
            l.trueUpTo[x] = Math.max(l.trueUpTo[x], depth);
        } else if (!walk.cycle && !walk.duringFlood) {
            l.falseFrom[x] = Math.min(l.falseFrom[x], depth);
        }
        walk.cycle |= cycleBefore;
        return result;
    }

    private static FlowView flowV2(WorldPipeline pipeline, int regionI, int regionJ, float blockM,
                                   int margin, boolean force) {
        int g = REGION_PX + 2 * margin;
        int windowI = regionI * REGION_PX - margin;
        int windowJ = regionJ * REGION_PX - margin;
        int n = g * g;
        float[] elev = pipeline.get(windowI, windowJ, windowI + g, windowJ + g, false)[0];
        InfiniteTensor.throwIfCancelled();

        float[] filled = elev.clone();
        int[] down = new int[n];
        Arrays.fill(down, -1);
        int[] exit = new int[n];
        int[] order = new int[n];
        int popped = 0;
        boolean[] closed = new boolean[n];
        boolean[] border = new boolean[n];
        LongHeap open = new LongHeap(n);
        for (int i = 0; i < n; i++) {
            int r = i / g, c = i % g;
            border[i] = r == 0 || c == 0 || r == g - 1 || c == g - 1;
            if (elev[i] < 0f || border[i]) {
                closed[i] = true;
                exit[i] = i;
                open.push(pack(filled[i], i));
            }
        }
        while (!open.isEmpty()) {
            int i = (int) open.pop();
            order[popped++] = i;
            int r = i / g, c = i % g;
            for (int k = 0; k < 8; k++) {
                int rr = r + V2_DR[k], cc = c + V2_DC[k];
                if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                int j = rr * g + cc;
                if (closed[j]) continue;
                closed[j] = true;
                down[j] = i;
                exit[j] = exit[i];
                if (filled[j] <= filled[i]) {
                    filled[j] = filled[i] + FILL_EPSILON_M;
                }
                open.push(pack(filled[j], j));
            }
        }
        InfiniteTensor.throwIfCancelled();

        float[] acc = new float[n];
        Arrays.fill(acc, 1f);
        LatentRivers.Window latent = pipeline.latentRivers.forWindow(windowI, windowJ, g);
        InflowSource source = INFLOW;
        float[] inflow = source != null ? source.inflow(windowI, windowJ, g, elev)
                : placeInflow(latent.crossings, elev, g, windowI, windowJ);
        if (inflow != null) {
            for (int i = 0; i < n; i++) acc[i] += inflow[i];
        }
        InfiniteTensor.throwIfCancelled();
        for (int k = popped - 1; k >= 0; k--) {
            int i = order[k];
            if (down[i] >= 0) acc[down[i]] += acc[i];
        }

        boolean[] channel = new boolean[n];
        for (int i = 0; i < n; i++) {
            channel[i] = elev[i] >= 0f && !border[i] && acc[i] >= V2_CHANNEL_AREA;
        }
        int ringLo = margin, ringHi = margin + REGION_PX - 1;
        boolean entered = false;
        for (int r = ringLo; r <= ringHi; r++) {
            for (int c = ringLo; c <= ringHi; c++) {
                if (r != ringLo && r != ringHi && c != ringLo && c != ringHi) continue;
                int i = r * g + c;
                if (channel[i] || elev[i] < 0f || acc[i] < V2_SEAM_ENTRY * V2_CHANNEL_AREA) continue;
                int main = -1;
                for (int k = 0; k < 8; k++) {
                    int u = (r + V2_DR[k]) * g + c + V2_DC[k];
                    if (down[u] == i && (main < 0 || acc[u] > acc[main])) main = u;
                }
                if (main < 0) continue;
                int ur = main / g, uc = main % g;
                if (ur < ringLo || uc < ringLo || ur > ringHi || uc > ringHi) {
                    channel[i] = true;
                    entered = true;
                }
            }
        }
        if (entered) {
            for (int k = popped - 1; k >= 0; k--) {
                int i = order[k];
                if (channel[i] && down[i] >= 0 && elev[down[i]] >= 0f) channel[down[i]] = true;
            }
        }
        InfiniteTensor.throwIfCancelled();

        float[] lake = new float[n];
        float[] lakeDeep = new float[n];
        boolean[] firm = new boolean[n];
        Arrays.fill(lake, Float.NaN);
        Arrays.fill(lakeDeep, Float.NaN);
        int[] body = new int[n];
        Arrays.fill(body, -1);
        int[] stack = new int[n];
        int[] members = new int[n];
        int bodies = 0;
        for (int seed = 0; seed < n; seed++) {
            if (body[seed] != -1 || elev[seed] < 0f || filled[seed] - elev[seed] < V2_LAKE_MIN_DEPTH_M) continue;
            int top = 0, size = 0;
            stack[top++] = seed;
            body[seed] = bodies;
            float surface = Float.MAX_VALUE, bottom = Float.MAX_VALUE;
            while (top > 0) {
                int i = stack[--top];
                members[size++] = i;
                surface = Math.min(surface, filled[i]);
                bottom = Math.min(bottom, elev[i]);
                int r = i / g, c = i % g;
                for (int k = 0; k < 8; k++) {
                    int rr = r + V2_DR[k], cc = c + V2_DC[k];
                    if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                    int j = rr * g + cc;
                    if (body[j] != -1 || elev[j] < 0f || filled[j] - elev[j] <= 0.05f) continue;
                    body[j] = bodies;
                    stack[top++] = j;
                }
            }
            if (size >= V2_LAKE_MIN_CELLS) {
                boolean spillsAway = true;
                for (int m = 0; m < size && spillsAway; m++) {
                    int i = members[m];
                    spillsAway = !border[i] && (elev[exit[i]] < 0f || surface - elev[exit[i]] >= V2_ESCALATE_DROP_M);
                }
                for (int m = 0; m < size; m++) {
                    lake[members[m]] = surface - FREEBOARD_BLOCKS * blockM;
                    lakeDeep[members[m]] = surface - bottom;
                    firm[members[m]] = spillsAway;
                }
            }
            bodies++;
        }
        boolean outflows = false;
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(lake[i])) continue;
            int d = down[i];
            if (d >= 0 && elev[d] >= 0f && Float.isNaN(lake[d]) && !channel[d]) {
                channel[d] = true;
                outflows = true;
            }
        }
        if (outflows) {
            for (int k = popped - 1; k >= 0; k--) {
                int i = order[k];
                if (channel[i] && down[i] >= 0 && elev[down[i]] >= 0f) channel[down[i]] = true;
            }
        }
        boolean gated = V2_HEAD_MIN_M > Float.NEGATIVE_INFINITY;
        boolean riversHere = !gated || pipeline.latentRivers.highZone(windowI, windowJ, g);
        boolean[] high = null;
        if (gated) {
            high = new boolean[n];
            for (int k = popped - 1; k >= 0; k--) {
                int i = order[k];
                if (acc[i] >= V2_CHANNEL_AREA && (channel[i] && elev[i] >= V2_HEAD_MIN_M || !Float.isNaN(lake[i]) && lake[i] >= V2_HEAD_MIN_M)) high[i] = true;
                int d = down[i];
                if (d >= 0 && high[i]) high[d] = true;
            }
            if (!riversHere) {
                for (int i = 0; i < n; i++) {
                    if (channel[i] && !high[i]) channel[i] = false;
                }
            }
        }
        if (!force) {
            int lo = margin - CROP_RIM, hi = margin + REGION_PX + CROP_RIM;
            int lg = g / RiverNetwork.LC;
            float[] basin = null;
            basin = new float[n];
            for (int k = 0; k < popped; k++) {
                int i = order[k];
                int r = i / g, c = i % g;
                boolean outer = r < V2_EDGE_CELLS || c < V2_EDGE_CELLS || r >= g - V2_EDGE_CELLS || c >= g - V2_EDGE_CELLS;
                float here = outer ? latent.depth[(r / RiverNetwork.LC) * lg + c / RiverNetwork.LC] : 0f;
                basin[i] = down[i] >= 0 ? Math.max(here, basin[down[i]]) : here;
            }
            boolean[] latentRiver = new boolean[n];
            for (int lr = lo / RiverNetwork.LC; lr < (hi + RiverNetwork.LC - 1) / RiverNetwork.LC; lr++) {
                for (int lc = lo / RiverNetwork.LC; lc < (hi + RiverNetwork.LC - 1) / RiverNetwork.LC; lc++) {
                    if (latent.acc[lr * lg + lc] < V2_WATCH_AREA || !riversHere) continue;
                    int lowest = -1;
                    for (int dr = 0; dr < RiverNetwork.LC; dr++) {
                        for (int dc = 0; dc < RiverNetwork.LC; dc++) {
                            int k = (lr * RiverNetwork.LC + dr) * g + lc * RiverNetwork.LC + dc;
                            if (elev[k] < 0f) continue;
                            if (lowest < 0 || elev[k] < elev[lowest]) lowest = k;
                        }
                    }
                    if (lowest >= 0) latentRiver[lowest] = true;
                    float want = latent.own[lr * lg + lc];
                    if (margin >= V2_MISSING_RIVER_MAX_MARGIN || want < V2_MISSING_RIVER_AREA
                            || latent.outside[lr * lg + lc] < 0.5f * want) continue;
                    float have = 0f;
                    for (int r = Math.max(0, (lr - 1) * RiverNetwork.LC); r < Math.min(g, (lr + 2) * RiverNetwork.LC); r++) {
                        for (int c = Math.max(0, (lc - 1) * RiverNetwork.LC); c < Math.min(g, (lc + 2) * RiverNetwork.LC); c++) {
                            have = Math.max(have, acc[r * g + c]);
                        }
                    }
                    if (have < V2_MISSING_RIVER_FRACTION * want) {
                        return null;
                    }
                }
            }
            for (int r = lo; r < hi; r++) {
                for (int c = lo; c < hi; c++) {
                    int i = r * g + c;
                    if (elev[i] < 0f || !channel[i] && (acc[i] < V2_WATCH_AREA || !riversHere) && !latentRiver[i]
                            && filled[i] - elev[i] < V2_LAKE_MIN_DEPTH_M) continue;
                    int x = exit[i];
                    if (elev[x] < 0f) continue;
                    float drop = filled[i] - elev[x];
                    if (drop < V2_ESCALATE_DROP_M || basin != null && basin[i] > V2_ESCALATE_BASIN_M
                            && drop < V2_BASIN_DROP_FACTOR * basin[i]) {
                        return null;
                    }
                }
            }
        }

        InfiniteTensor.throwIfCancelled();
        return new FlowView(regionI, regionJ, margin, g, windowI, windowJ, popped, elev, filled, acc, lake, lakeDeep, down, order,
                channel, high, firm, riversHere);
    }

    private static Region drawV2(WorldPipeline pipeline, FlowView v, RiverLink self, float blockM) {
        agreeLakes(pipeline, v, blockM);
        if (v.high != null && pipeline.latentRivers.bigBasin(v.windowI, v.windowJ, v.g)) {
            followToLake(pipeline, self, blockM);
            applyLakes(pipeline, v);
        }
        int regionI = v.regionI, regionJ = v.regionJ, margin = v.margin, g = v.g, windowI = v.windowI, windowJ = v.windowJ;
        int popped = v.popped, n = g * g;
        float[] elev = v.elev, filled = v.filled, acc = v.acc, lake = v.lake, lakeDeep = v.lakeDeep;
        int[] down = v.down, order = v.order;
        boolean[] channel = v.channel;
        float[] cap = null;
        if (v.high != null) {
            boolean[] high = v.high;
            if (v.riversHere) {
                float[] push = null;
                for (int nb = 0; nb < 9; nb++) {
                    if (nb == 4) continue;
                    RiverLink q = linkFor(pipeline, regionI + nb / 3 - 1, regionJ + nb % 3 - 1, blockM);
                    for (int y = 0; y < q.exits; y++) {
                        int r = q.landI[y] - windowI, c = q.landJ[y] - windowJ;
                        if (r < margin || c < margin || r >= margin + REGION_PX || c >= margin + REGION_PX) continue;
                        if (q.exitAcc[y] < V2_LINK_MIN_ACC && !q.exitHigh[y]) continue;
                        if (exitKept(pipeline, q, y, V2_LINK_DEPTH - 1, blockM, new Walk(false)) == 0) continue;
                        int at = r * g + c;
                        if (elev[at] < 0f) continue;
                        if (push == null) {
                            push = new float[n];
                            cap = new float[n];
                            Arrays.fill(cap, Float.MAX_VALUE);
                        }
                        push[at] = Math.max(push[at], q.exitAcc[y]);
                        cap[at] = Math.min(cap[at], q.exitLevel[y]);
                        short leaves = self.ringExit[ringIndex(r - margin, c - margin)];
                        if (leaves >= 0 && !self.exitHigh[leaves]) {
                            self.exitHigh[leaves] = true;
                            pipeline.hydrology.outletEpoch++;
                        }
                        for (int step = 0; step <= CROP_RIM && at >= 0; step++) {
                            high[at] = true;
                            channel[at] = true;
                            int best = -1, ar = at / g, ac = at % g;
                            for (int k = 0; k < 8; k++) {
                                int rr = ar + V2_DR[k], cc = ac + V2_DC[k];
                                if (rr < 1 || cc < 1 || rr >= g - 1 || cc >= g - 1) continue;
                                if (rr >= margin && cc >= margin && rr < margin + REGION_PX && cc < margin + REGION_PX) continue;
                                int u = rr * g + cc;
                                if (down[u] == at && (best < 0 || acc[u] > acc[best])) best = u;
                            }
                            at = best;
                        }
                    }
                }
                if (push != null) {
                    float[] extra = new float[n];
                    for (int k = popped - 1; k >= 0; k--) {
                        int i = order[k];
                        if (push[i] > acc[i] + extra[i]) extra[i] = push[i] - acc[i];
                        int d = down[i];
                        if (d < 0) continue;
                        extra[d] += extra[i];
                        if (high[i]) {
                            high[d] = true;
                            if (channel[i] && elev[d] >= 0f) channel[d] = true;
                        }
                    }
                    for (int i = 0; i < n; i++) acc[i] += extra[i];
                }
                LakeCore own = lakeCore(pipeline, regionI, regionJ, blockM);
                if (!own.outlets.isEmpty()) {
                    for (int ix : own.outlets) {
                        int at = (margin + ix / REGION_PX) * g + margin + ix % REGION_PX;
                        high[at] = true;
                        channel[at] = true;
                    }
                    for (int k = popped - 1; k >= 0; k--) {
                        int i = order[k];
                        int d = down[i];
                        if (d >= 0 && high[i]) {
                            high[d] = true;
                            if (channel[i] && elev[d] >= 0f) channel[d] = true;
                        }
                    }
                }
                for (int x = 0; x < self.exits; x++) {
                    if (self.exitAcc[x] < V2_LINK_MIN_ACC && !self.exitHigh[x]) continue;
                    if (exitKept(pipeline, self, x, V2_LINK_DEPTH, blockM, new Walk(false)) == 0) continue;
                    int qi = Math.floorDiv(self.landI[x], REGION_PX), qj = Math.floorDiv(self.landJ[x], REGION_PX);
                    LakeCore core = lakeCore(pipeline, qi, qj, blockM);
                    int ix = Math.floorMod(self.landI[x], REGION_PX) * REGION_PX + Math.floorMod(self.landJ[x], REGION_PX);
                    if (Float.isNaN(core.level[ix]) && !Float.isNaN(core.lake[ix]) && !core.firm[ix]) {
                        floodLake(pipeline, self.landI[x], self.landJ[x], core.lake[ix], blockM);
                    }
                    float[] loop = seamLoop(pipeline, self, x, blockM);
                    if (loop == null) continue;
                    int li = (int) loop[0], lj = (int) loop[1];
                    LakeCore at = lakeCore(pipeline, Math.floorDiv(li, REGION_PX), Math.floorDiv(lj, REGION_PX), blockM);
                    int lx = Math.floorMod(li, REGION_PX) * REGION_PX + Math.floorMod(lj, REGION_PX);
                    if (!Float.isNaN(at.level[lx]) || loop[2] > at.elev[lx] + V2_LAKE_CLIMB_M) continue;
                    floodLake(pipeline, li, lj, loop[2], blockM, true);
                }
                applyLakes(pipeline, v);
            }
            for (int i = 0; i < n; i++) {
                if (channel[i] && !high[i]) channel[i] = false;
            }
        }
        InfiniteTensor.throwIfCancelled();

        float[] freeboard = riverFreeboard(channel, lake, down, order, popped);
        float[] level = new float[n];
        Arrays.fill(level, Float.NaN);
        byte[] ord = new byte[n];
        int[] mainUp = new int[n];
        Arrays.fill(mainUp, -1);
        for (int k = popped - 1; k >= 0; k--) {
            int i = order[k];
            if (!channel[i]) continue;
            level[i] = !Float.isNaN(lake[i]) ? lake[i] : Math.max(0f, filled[i] - freeboard[i] * blockM);
            if (ord[i] == 0) ord[i] = 1;
            int d = down[i];
            if (cap != null && cap[i] < Float.MAX_VALUE && Float.isNaN(lake[i])) {
                level[i] = Math.min(level[i], Math.max(0f, cap[i]));
                if (d >= 0 && channel[d]) cap[d] = Math.min(cap[d], level[i]);
            }
            if (d < 0 || !channel[d]) continue;
            if (mainUp[d] < 0 || acc[i] > acc[mainUp[d]]) mainUp[d] = i;
            if (ord[i] > ord[d]) {
                ord[d] = ord[i];
            } else if (ord[i] == ord[d] && ord[d] < 127) {
                ord[d] = (byte) (ord[i] + 1);
            }
        }

        float[] widthM = new float[n];
        float[] depthM = new float[n];
        float[] posR = new float[n];
        float[] posC = new float[n];
        float[] toSea = new float[n];
        float[] mouth = new float[n];
        Arrays.fill(toSea, Float.MAX_VALUE);
        for (int k = 0; k < popped; k++) {
            int i = order[k];
            int d = down[i];
            if (!channel[i] || d < 0) continue;
            float step = d / g != i / g && d % g != i % g ? 1.4142135f : 1f;
            if (elev[d] < 0f) {
                toSea[i] = step;
            } else if (channel[d] && toSea[d] < Float.MAX_VALUE) {
                toSea[i] = toSea[d] + step;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!channel[i]) continue;
            double area = Math.max(1.0, acc[i] / RIVER_THRESHOLD);
            double q = Math.pow(area, DISCHARGE_AREA_EXPONENT);
            float w = BANKFULL_WIDTH_M * (float) Math.pow(q, WIDTH_Q_EXPONENT);
            float dep = BANKFULL_DEPTH_M * (float) Math.pow(q, DEPTH_Q_EXPONENT);
            w *= 1f + ORDER_WIDTH_GAIN * (ord[i] - 1);
            w = MAX_WIDTH_M * w / (MAX_WIDTH_M + w);
            float ramp = Math.max(0f, Math.min(1f, (float) (acc[i] / V2_WATCH_AREA - 1.0) / V2_WIDTH_RAMP));
            w *= ramp * ramp * (3f - 2f * ramp);
            dep = MAX_DEPTH_M * dep / (MAX_DEPTH_M + dep);
            int r = i / g, c = i % g;
            int d = down[i];
            if (d >= 0) {
                float fr = d / g - r, fc = d % g - c;
                float len = (float) Math.sqrt(fr * fr + fc * fc);
                float floorM = valleyFloorV2(elev, g, r, c, -fc / len, fr / len);
                w = Math.min(w, CONFINEMENT_FRACTION * floorM);
            }
            widthM[i] = Math.max(CELL_SIZE_M * 0.25f, w);
            depthM[i] = Math.max(0.5f, dep);
            if (toSea[i] < Float.MAX_VALUE) {
                float reach = Math.max(V2_MOUTH_CELLS, V2_MOUTH_WIDTHS * widthM[i] / CELL_SIZE_M);
                float t = Math.max(0f, 1f - toSea[i] / reach);
                t = t * t * (3f - 2f * t);
                mouth[i] = t;
                widthM[i] *= 1f + (V2_MOUTH_FLARE - 1f) * t;
                depthM[i] += (V2_MOUTH_DEPTH_BLOCKS * blockM - depthM[i]) * t;
            }

            if (mainUp[i] < 0) {
                posR[i] = r;
                posC[i] = c;
                for (int k = 0; k < 8; k++) {
                    int rr = r + V2_DR[k], cc = c + V2_DC[k];
                    if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                    int u = rr * g + cc;
                    if (down[u] == i && !Float.isNaN(lake[u])) {
                        posR[i] = rr;
                        posC[i] = cc;
                        break;
                    }
                }
                continue;
            }
            float sr = r, sc = c;
            int cnt = 1;
            int at = i;
            for (int s2 = 0; s2 < V2_SMOOTH_CELLS; s2++) {
                at = down[at];
                if (at < 0 || !channel[at]) break;
                sr += at / g;
                sc += at % g;
                cnt++;
            }
            at = i;
            for (int s2 = 0; s2 < V2_SMOOTH_CELLS; s2++) {
                at = mainUp[at];
                if (at < 0) break;
                sr += at / g;
                sc += at % g;
                cnt++;
            }
            posR[i] = sr / cnt;
            posC[i] = sc / cnt;
        }
        for (int pass = 0; pass < V2_WIDTH_SMOOTH; pass++) {
            float[] next = widthM.clone();
            for (int i = 0; i < n; i++) {
                if (!channel[i]) continue;
                float sum = widthM[i];
                int cnt = 1;
                int d = down[i];
                if (d >= 0 && channel[d]) {
                    sum += widthM[d];
                    cnt++;
                }
                if (mainUp[i] >= 0) {
                    sum += widthM[mainUp[i]];
                    cnt++;
                }
                next[i] = sum / cnt;
            }
            widthM = next;
        }
        InfiniteTensor.throwIfCancelled();

        int[] src = new int[n];
        float[] dist = new float[n];
        Arrays.fill(src, -1);
        Arrays.fill(dist, Float.MAX_VALUE);
        float[] endR = new float[n];
        float[] endC = new float[n];
        for (int i = 0; i < n; i++) {
            if (!channel[i]) continue;
            int d = down[i];
            if (d >= 0 && channel[d]) {
                endR[i] = posR[d];
                endC[i] = posC[d];
            } else if (d >= 0 && elev[d] < 0f) {
                endR[i] = d / g;
                endC[i] = d % g;
            } else {
                endR[i] = posR[i];
                endC[i] = posC[i];
            }
        }
        for (int i = 0; i < n; i++) {
            if (!channel[i]) continue;
            src[i] = i;
            dist[i] = segmentDistance(i / g, i % g, posR[i], posC[i], endR[i], endC[i]);
        }
        for (int pass = 0; pass < 2; pass++) {
            boolean fwd = pass == 0;
            for (int t = 0; t < n; t++) {
                int i = fwd ? t : n - 1 - t;
                if (channel[i]) continue;
                int r = i / g, c = i % g;
                for (int k = 0; k < 4; k++) {
                    int rr = r + (fwd ? V2_FR[k] : -V2_FR[k]), cc = c + (fwd ? V2_FC[k] : -V2_FC[k]);
                    if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                    int s2 = src[rr * g + cc];
                    if (s2 < 0 || s2 == src[i]) continue;
                    float dd = segmentDistance(r, c, posR[s2], posC[s2], endR[s2], endC[s2]);
                    if (dd < dist[i]) {
                        dist[i] = dd;
                        src[i] = s2;
                    }
                }
            }
        }

        float[] distance = new float[n];
        float[] magnitude = new float[n];
        float[] water = new float[n];
        float[] width = new float[n];
        float[] depth = new float[n];
        float[] delta = new float[n];
        float[] zero = new float[n];
        Arrays.fill(water, Float.NaN);
        int off = margin - CROP_RIM;
        float[] segments = new float[SEGMENT_FIELDS * CROP_N * CROP_N];
        Arrays.fill(segments, Float.NaN);
        for (int i = 0; i < n; i++) {
            int s2 = src[i];
            if (s2 < 0) {
                distance[i] = Float.MAX_VALUE;
                continue;
            }
            int cr = i / g - off, cc = i % g - off;
            if (cr >= 0 && cc >= 0 && cr < CROP_N && cc < CROP_N) {
                int o = SEGMENT_FIELDS * (cr * CROP_N + cc);
                int ds = down[s2];
                boolean next = ds >= 0 && channel[ds];
                segments[o] = posR[s2] - off;
                segments[o + 1] = posC[s2] - off;
                segments[o + 2] = endR[s2] - off;
                segments[o + 3] = endC[s2] - off;
                segments[o + 4] = next ? endR[ds] - off : endR[s2] - off;
                segments[o + 5] = next ? endC[ds] - off : endC[s2] - off;
                segments[o + 6] = level[s2];
                segments[o + 7] = segmentEndLevel(s2, down, channel, elev, level);
                segments[o + 8] = next ? segmentEndLevel(ds, down, channel, elev, level) : segments[o + 7];
            }
            distance[i] = dist[i];
            magnitude[i] = 1f;
            water[i] = level[s2];
            width[i] = widthM[s2];
            depth[i] = depthM[s2];
            delta[i] = mouth[s2];
        }
        for (int s2 = 0; s2 < n; s2++) {
            if (!channel[s2]) continue;
            float rad = 0.5f * widthM[s2] / CELL_SIZE_M + 1f;
            int r0 = (int) Math.floor(Math.min(posR[s2], endR[s2]) - rad), r1 = (int) Math.ceil(Math.max(posR[s2], endR[s2]) + rad);
            int c0 = (int) Math.floor(Math.min(posC[s2], endC[s2]) - rad), c1 = (int) Math.ceil(Math.max(posC[s2], endC[s2]) + rad);
            for (int r = Math.max(0, r0); r <= Math.min(g - 1, r1); r++) {
                for (int c = Math.max(0, c0); c <= Math.min(g - 1, c1); c++) {
                    if (segmentDistance(r, c, posR[s2], posC[s2], endR[s2], endC[s2]) > rad) continue;
                    int i = r * g + c;
                    if (Float.isNaN(water[i]) || level[s2] < water[i]) water[i] = level[s2];
                }
            }
        }
        InfiniteTensor.throwIfCancelled();
        float[] lakeFlow = lakeFlowV2(elev, lake, down, channel, widthM, level, posR, posC, endR, endC, g, margin);
        InfiniteTensor.throwIfCancelled();

        float[] fillDepth = new float[n];
        for (int i = 0; i < n; i++) fillDepth[i] = filled[i] - elev[i];
        KarstNetwork karst = KarstHydrology.solve(regionI, regionJ, windowI, windowJ, g,
                elev, acc, channel, fillDepth, water, lake, widthM, down, REGION_PX, margin, blockM);
        InfiniteTensor.throwIfCancelled();
        Region region = new Region(regionI, regionJ, cropV2(distance, g, margin), cropV2(magnitude, g, margin),
                cropV2(water, g, margin), cropV2(lake, g, margin), cropV2(lakeDeep, g, margin),
                cropV2(delta, g, margin), cropV2(width, g, margin), cropV2(depth, g, margin),
                cropV2(zero, g, margin), karst);
        region.segments = segments;
        region.lakeFlow = lakeFlow;
        return region;
    }

    // Streams speed of a river leaving cell d, from the fall of its water level
    private static float riverSpeedV2(int d, int[] down, boolean[] channel, float[] level, int g) {
        if (d < 0 || !channel[d] || Float.isNaN(level[d])) return RIVER_MIN_SPEED;
        int dd = down[d];
        if (dd < 0 || !channel[dd] || Float.isNaN(level[dd])) return RIVER_MIN_SPEED;
        float step = dd / g != d / g && dd % g != d % g ? 1.4142135f : 1f;
        return riverSpeed(Math.max(0f, level[d] - level[dd]) / (step * CELL_SIZE_M));
    }

    // Streams speed of river water on a channel of this slope
    public static float riverSpeed(float slope) {
        return Math.max(RIVER_MIN_SPEED, Math.min(RIVER_MAX_SPEED, 0.45f + 2.2f * (float) Math.sqrt(Math.max(0f, slope))));
    }

    // Current through each lake that drains, toward its outflow, as direction and Streams speed per crop cell
    private static float[] lakeFlowV2(float[] elev, float[] lake, int[] down, boolean[] channel, float[] widthM,
                                      float[] level, float[] posR, float[] posC, float[] endR, float[] endC, int g, int margin) {
        int n = g * g;
        float[] dist = new float[n];
        Arrays.fill(dist, Float.MAX_VALUE);
        int[] parent = new int[n], root = new int[n];
        Arrays.fill(parent, -1);
        Arrays.fill(root, -1);
        float[] exitSpeed = new float[n], exitWidth = new float[n];
        LongHeap heap = new LongHeap(4096);
        boolean any = false;
        for (int u = 0; u < n; u++) {
            if (Float.isNaN(lake[u])) continue;
            int d = down[u];
            if (d < 0) continue;
            boolean leaves = Float.isNaN(lake[d]) ? channel[d] || elev[d] < 0f : lake[d] < lake[u];
            if (!leaves) continue;
            dist[u] = 0f;
            root[u] = u;
            parent[u] = d;
            exitSpeed[u] = riverSpeedV2(d, down, channel, level, g);
            exitWidth[u] = channel[d] ? Math.max(1f, widthM[d] / CELL_SIZE_M) : 2f;
            heap.push(pack(0f, u));
            any = true;
        }
        if (!any) return null;
        while (!heap.isEmpty()) {
            long top = heap.pop();
            int i = (int) top;
            if (Float.intBitsToFloat((int) (top >>> 32)) > dist[i]) continue;
            int r = i / g, c = i % g;
            for (int k = 0; k < 8; k++) {
                int rr = r + V2_DR[k], cc = c + V2_DC[k];
                if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                int j = rr * g + cc;
                if (Float.isNaN(lake[j]) || lake[j] != lake[i]) continue;
                float nd = dist[i] + (V2_DR[k] != 0 && V2_DC[k] != 0 ? 1.4142135f : 1f);
                if (nd >= dist[j]) continue;
                dist[j] = nd;
                parent[j] = i;
                root[j] = root[i];
                heap.push(pack(nd, j));
            }
        }

        Map<Integer, int[]> bands = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            if (root[i] < 0) continue;
            int b = (int) dist[i];
            int[] cnt = bands.get(root[i]);
            if (cnt == null || b >= cnt.length) {
                cnt = cnt == null ? new int[Math.max(64, b + 1)] : Arrays.copyOf(cnt, Math.max(b + 1, cnt.length * 2));
                bands.put(root[i], cnt);
            }
            cnt[b]++;
        }

        float[] vr = new float[n], vc = new float[n], speed = new float[n];
        for (int i = 0; i < n; i++) {
            if (root[i] < 0) continue;
            int at = i;
            for (int s = 0; s < LAKE_TANGENT_CELLS && parent[at] >= 0; s++) at = parent[at];
            float dr = at / g - i / g, dc = at % g - i % g;
            float len = (float) Math.sqrt(dr * dr + dc * dc);
            if (len > 0f) {
                vr[i] = dr / len;
                vc[i] = dc / len;
            }
            int[] cnt = bands.get(root[i]);
            int b = (int) dist[i];
            float w = cnt[b];
            if (b > 0) w = (w + cnt[b - 1]) * 0.5f;
            float vOut = exitSpeed[root[i]], wOut = exitWidth[root[i]];
            float body = Math.max(LAKE_MIN_SPEED, Math.min(LAKE_BODY_SPEED, vOut * wOut / Math.max(wOut, w)));
            float funnel = LAKE_FUNNEL_CELLS + 2f * wOut;
            float t = Math.max(0f, 1f - dist[i] / funnel);
            t = t * t * (3f - 2f * t);
            speed[i] = body + (Math.max(body, vOut) - body) * t;
        }

        int[] inlet = new int[n];
        float[] reach = new float[n];
        Arrays.fill(inlet, -1);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int c0 = 0; c0 < n; c0++) {
            if (!channel[c0] || !Float.isNaN(lake[c0])) continue;
            int j = down[c0];
            if (j < 0 || root[j] < 0 || inlet[j] >= 0) continue;
            inlet[j] = c0;
            reach[j] = 0f;
            queue[tail++] = j;
        }
        while (head < tail) {
            int i = queue[head++];
            int src = inlet[i];
            float radius = LAKE_PLUME_CELLS + 1.5f * Math.max(1f, widthM[src] / CELL_SIZE_M);
            float w = 1f - reach[i] / radius;
            w *= w;
            float rr0 = endR[src] - posR[src], cc0 = endC[src] - posC[src];
            float rl = (float) Math.sqrt(rr0 * rr0 + cc0 * cc0);
            if (rl > 0f) {
                float br = vr[i] * (1f - w) + rr0 / rl * w, bc = vc[i] * (1f - w) + cc0 / rl * w;
                float bl = (float) Math.sqrt(br * br + bc * bc);
                if (bl > 1.0e-3f) {
                    vr[i] = br / bl;
                    vc[i] = bc / bl;
                }
            }
            speed[i] = Math.max(speed[i], speed[i] * (1f - w) + riverSpeedV2(src, down, channel, level, g) * LAKE_PLUME_KEEP * w);
            int r = i / g, c = i % g;
            for (int k = 0; k < 8; k += 1) {
                if (V2_DR[k] != 0 && V2_DC[k] != 0) continue;
                int rr = r + V2_DR[k], cc = c + V2_DC[k];
                if (rr < 0 || cc < 0 || rr >= g || cc >= g) continue;
                int j = rr * g + cc;
                if (root[j] < 0 || inlet[j] >= 0 || lake[j] != lake[i] || reach[i] + 1f >= radius) continue;
                inlet[j] = src;
                reach[j] = reach[i] + 1f;
                queue[tail++] = j;
            }
        }

        int off = margin - CROP_RIM;
        float[] out = new float[3 * CROP_N * CROP_N];
        Arrays.fill(out, Float.NaN);
        for (int cr = 0; cr < CROP_N; cr++) {
            for (int cc = 0; cc < CROP_N; cc++) {
                int i = (cr + off) * g + cc + off;
                if (root[i] < 0) continue;
                float sr = 0f, sc = 0f;
                int r = i / g, c = i % g;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cl = c + dc;
                        if (rr < 0 || cl < 0 || rr >= g || cl >= g) continue;
                        int j = rr * g + cl;
                        if (root[j] != root[i]) continue;
                        sr += vr[j];
                        sc += vc[j];
                    }
                }
                float sl = (float) Math.sqrt(sr * sr + sc * sc);
                if (sl < 1.0e-3f) continue;
                int o = 3 * (cr * CROP_N + cc);
                out[o] = sr / sl;
                out[o + 1] = sc / sl;
                out[o + 2] = speed[i];
            }
        }
        return out;
    }

    // Lake current at a native position as row and column direction and Streams speed; false where the water is still
    public static boolean sampleLakeFlow(WorldPipeline pipeline, float nativeI, float nativeJ, float blockM, float[] out) {
        int ri = Math.floorDiv((int) Math.floor(nativeI), REGION_PX);
        int rj = Math.floorDiv((int) Math.floor(nativeJ), REGION_PX);
        Region region = regionFor(pipeline, ri, rj, blockM);
        if (region == null || region.lakeFlow == null) return false;
        float gi = nativeI - (ri * REGION_PX - MARGIN_PX) - CROP_OFF - 0.5f;
        float gj = nativeJ - (rj * REGION_PX - MARGIN_PX) - CROP_OFF - 0.5f;
        int i0 = (int) Math.floor(gi), j0 = (int) Math.floor(gj);
        if (i0 < 0 || j0 < 0 || i0 >= CROP_N - 1 || j0 >= CROP_N - 1) return false;
        float ti = gi - i0, tj = gj - j0;
        float sr = 0f, sc = 0f, sv = 0f, sw = 0f;
        for (int k = 0; k < 4; k++) {
            int a = (i0 + (k >> 1)) * CROP_N + j0 + (k & 1);
            float w = ((k >> 1) == 1 ? ti : 1f - ti) * ((k & 1) == 1 ? tj : 1f - tj);
            int o = 3 * a;
            if (Float.isNaN(region.lakeFlow[o]) || w <= 0f) continue;
            sr += region.lakeFlow[o] * w;
            sc += region.lakeFlow[o + 1] * w;
            sv += region.lakeFlow[o + 2] * w;
            sw += w;
        }
        if (sw < 0.2f) return false;
        float len = (float) Math.sqrt(sr * sr + sc * sc);
        if (len < 1.0e-4f) return false;
        out[0] = sr / len;
        out[1] = sc / len;
        out[2] = sv / sw;
        return true;
    }

    private static float[] riverFreeboard(boolean[] channel, float[] lake, int[] down, int[] order, int popped) {
        float[] fb = new float[channel.length];
        Arrays.fill(fb, V2_RIVER_FREEBOARD_BLOCKS);
        for (int k = popped - 1; k >= 0; k--) {
            int i = order[k], d = down[i];
            if (!channel[i] || d < 0 || !channel[d]) continue;
            fb[d] = Math.min(fb[d], (Float.isNaN(lake[i]) ? fb[i] : FREEBOARD_BLOCKS) + V2_FREEBOARD_RAMP);
        }
        for (int k = 0; k < popped; k++) {
            int i = order[k], d = down[i];
            if (!channel[i] || d < 0 || !channel[d]) continue;
            fb[i] = Math.min(fb[i], (Float.isNaN(lake[d]) ? fb[d] : FREEBOARD_BLOCKS) + V2_FREEBOARD_RAMP);
        }
        return fb;
    }

    private static float[] cropV2(float[] full, int g, int margin) {
        float[] out = new float[CROP_N * CROP_N];
        int off = margin - CROP_RIM;
        for (int r = 0; r < CROP_N; r++) {
            System.arraycopy(full, (off + r) * g + off, out, r * CROP_N, CROP_N);
        }
        return out;
    }

    private static final int SEGMENT_FIELDS = 9;

    // Blocks a river surface is held below the ground of its own cell; lakes keep FREEBOARD_BLOCKS
    private static final float V2_RIVER_FREEBOARD_BLOCKS = 1.5f;

    // Blocks of freeboard a river gains per native cell away from a lake
    private static final float V2_FREEBOARD_RAMP = 0.125f;

    // Water slope above which a segment drops as one fall at its head
    private static final float V2_FALL_SLOPE = 0.6f;

    private static float segmentEndLevel(int s, int[] down, boolean[] channel, float[] elev, float[] level) {
        int d = down[s];
        return d >= 0 && channel[d] ? level[d] : d >= 0 && elev[d] < 0f ? 0f : level[s];
    }

    private static float[] placeInflow(float[] crossings, float[] elev, int g, int windowI, int windowJ) {
        if (crossings.length == 0) {
            return null;
        }
        int lc = RiverNetwork.LC;
        float[] inflow = new float[g * g];
        for (int k = 0; k + 4 < crossings.length; k += 5) {
            int pi = (int) crossings[k + 2], pj = (int) crossings[k + 3];
            int best = -1;
            for (int dr = 0; dr < lc; dr++) {
                for (int dc = 0; dc < lc; dc++) {
                    int r = pi * lc + dr - windowI, c = pj * lc + dc - windowJ;
                    if (r < 1 || c < 1 || r >= g - 1 || c >= g - 1) continue;
                    int i = r * g + c;
                    if (elev[i] < 0f) continue;
                    if (best < 0 || elev[i] < elev[best]) best = i;
                }
            }
            if (best >= 0) {
                inflow[best] += crossings[k + 4];
            }
        }
        return inflow;
    }

    private static float segmentDistance(float r, float c, float ar, float ac, float br, float bc) {
        float vr = br - ar, vc = bc - ac;
        float len2 = vr * vr + vc * vc;
        float t = len2 > 1.0e-6f ? Math.max(0f, Math.min(1f, ((r - ar) * vr + (c - ac) * vc) / len2)) : 0f;
        float dr = r - (ar + t * vr), dc = c - (ac + t * vc);
        return (float) Math.sqrt(dr * dr + dc * dc);
    }

    private static float valleyFloorV2(float[] elev, int g, int r0, int c0, float perpR, float perpC) {
        float base = elev[r0 * g + c0];
        int cells = 0;
        for (int side = -1; side <= 1; side += 2) {
            for (int s = 1; s <= CONFINEMENT_SCAN_CELLS; s++) {
                int r = Math.round(r0 + perpR * s * side), c = Math.round(c0 + perpC * s * side);
                if (r < 0 || c < 0 || r >= g || c >= g || elev[r * g + c] > base + CONFINEMENT_RISE_M) {
                    break;
                }
                cells++;
            }
        }
        return Math.max(1, cells) * CELL_SIZE_M;
    }

    private static final int[] V2_DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] V2_DC = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] V2_FR = {-1, -1, -1, 0};
    private static final int[] V2_FC = {-1, 0, 1, -1};

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

}
