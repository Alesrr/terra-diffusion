package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LatentRivers {

    private static final int LC = RiverNetwork.LC;
    private static final int LATENT_PER_COARSE = 32;
    private static final int MARGIN = 16;
    private static final int COARSE_TILE = 32;
    private static final int COARSE_MARGIN = 16;
    private static final float NATIVE_PER_COARSE = 256f * 256f;
    private static final float ZONE_HIGH_M = RiverHydrology.V2_HEAD_MIN_M
            - 900f;
    private static final float BASIN_DEPTH_M = 0.5f;
    private static final float ZONE_RISE_M = 400f;
    private static final int ZONE_TILE = 32;
    private static final int ZONE_MARGIN = 32;

    public static final class Window {
        public final float[] crossings;
        public final float[] depth;
        public final float[] acc;
        public final float[] own;
        public final float[] outside;

        Window(float[] crossings, float[] depth, float[] acc, float[] own, float[] outside) {
            this.crossings = crossings;
            this.depth = depth;
            this.acc = acc;
            this.own = own;
            this.outside = outside;
        }
    }

    private static final class CoarseTile {
        final int ci0, cj0, n;
        final float[] acc;
        final float[] depth;

        CoarseTile(int ci0, int cj0, int n, float[] acc, float[] depth) {
            this.ci0 = ci0;
            this.cj0 = cj0;
            this.n = n;
            this.acc = acc;
            this.depth = depth;
        }
    }

    private final WorldPipeline pipeline;
    private final Map<Long, CoarseTile> coarse = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Long, boolean[]> zones = new LinkedHashMap<>(16, 0.75f, true);

    LatentRivers(WorldPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public synchronized void clear() {
        coarse.clear();
        zones.clear();
    }

    public Window forWindow(int windowI, int windowJ, int size) {
        int wi0 = Math.floorDiv(windowI, LC), wj0 = Math.floorDiv(windowJ, LC);
        int lw = Math.floorDiv(windowI + size - 1, LC) - wi0 + 1;
        int li0 = wi0 - MARGIN, lj0 = wj0 - MARGIN, n = lw + 2 * MARGIN;
        float[] lf = pipeline.getLowfreqSlice(li0, lj0, li0 + n, lj0 + n);
        for (int k = 0; k < lf.length; k++) lf[k] = Math.signum(lf[k]) * lf[k] * lf[k];
        RiverNetwork.Params p = new RiverNetwork.Params();
        p.lakeMinCells = Integer.MAX_VALUE;
        RiverNetwork net = RiverNetwork.solve(lf, n, li0, lj0, p);
        long[] byLevel = byFilled(net, n);
        float[] acc = withCoarseFloor(net, li0, lj0, n, byLevel);
        float[] inner = new float[n * n];
        for (int o = n * n - 1; o >= 0; o--) {
            int k = (int) byLevel[o];
            int r = k / n, c = k % n;
            if (r >= MARGIN && c >= MARGIN && r < MARGIN + lw && c < MARGIN + lw) inner[k] += RiverNetwork.NATIVE_CELLS_PER_CELL;
            int d = net.parent[k];
            if (d >= 0) inner[d] += inner[k];
        }

        float[] crossings = new float[0];
        int count = 0;
        for (int r = MARGIN - 1; r <= MARGIN + lw; r++) {
            for (int c = MARGIN - 1; c <= MARGIN + lw; c++) {
                int li = li0 + r, lj = lj0 + c;
                if (inside(li, lj, windowI, windowJ, size)) continue;
                int k = r * n + c;
                int pk = net.parent[k];
                if (pk < 0) continue;
                int pi = li0 + pk / n, pj = lj0 + pk % n;
                if (!inside(pi, pj, windowI, windowJ, size)) continue;
                if (5 * count + 5 > crossings.length) crossings = Arrays.copyOf(crossings, Math.max(40, 2 * crossings.length));
                crossings[5 * count] = li;
                crossings[5 * count + 1] = lj;
                crossings[5 * count + 2] = pi;
                crossings[5 * count + 3] = pj;
                crossings[5 * count + 4] = acc[k];
                count++;
            }
        }

        float[] depth = new float[lw * lw];
        float[] windowAcc = new float[lw * lw];
        float[] own = new float[lw * lw];
        float[] outside = new float[lw * lw];
        for (int r = 0; r < lw; r++) {
            for (int c = 0; c < lw; c++) {
                int k = (r + MARGIN) * n + c + MARGIN;
                depth[r * lw + c] = net.elev[k] < 0f ? 0f : net.filled[k] - net.elev[k];
                windowAcc[r * lw + c] = net.elev[k] < 0f ? 0f : acc[k];
                own[r * lw + c] = net.elev[k] < 0f ? 0f : net.acc[k];
                outside[r * lw + c] = net.elev[k] < 0f ? 0f : Math.max(0f, net.acc[k] - inner[k]);
            }
        }
        return new Window(Arrays.copyOf(crossings, 5 * count), depth, windowAcc, own, outside);
    }

    private static boolean inside(int li, int lj, int windowI, int windowJ, int size) {
        float ci = li * LC + LC * 0.5f, cj = lj * LC + LC * 0.5f;
        return ci >= windowI && cj >= windowJ && ci < windowI + size && cj < windowJ + size;
    }

    private static long[] byFilled(RiverNetwork net, int n) {
        long[] byLevel = new long[n * n];
        for (int k = 0; k < n * n; k++) {
            int bits = Float.floatToIntBits(net.filled[k]);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            byLevel[k] = (((long) bits) << 32) | (k & 0xFFFFFFFFL);
        }
        Arrays.sort(byLevel);
        return byLevel;
    }

    private float[] withCoarseFloor(RiverNetwork net, int li0, int lj0, int n, long[] byLevel) {
        float[] floorAt = new float[n * n];
        int ci0 = Math.floorDiv(li0, LATENT_PER_COARSE), cj0 = Math.floorDiv(lj0, LATENT_PER_COARSE);
        int ci1 = Math.floorDiv(li0 + n - 1, LATENT_PER_COARSE), cj1 = Math.floorDiv(lj0 + n - 1, LATENT_PER_COARSE);
        for (int ci = ci0; ci <= ci1; ci++) {
            for (int cj = cj0; cj <= cj1; cj++) {
                float cAcc = coarseAcc(ci, cj);
                if (cAcc <= 0f) continue;
                int best = -1;
                for (int r = ci * LATENT_PER_COARSE - li0; r < (ci + 1) * LATENT_PER_COARSE - li0; r++) {
                    for (int c = cj * LATENT_PER_COARSE - lj0; c < (cj + 1) * LATENT_PER_COARSE - lj0; c++) {
                        if (r < 1 || c < 1 || r >= n - 1 || c >= n - 1) continue;
                        int k = r * n + c;
                        if (net.elev[k] < 0f) continue;
                        if (best < 0 || net.acc[k] > net.acc[best]) best = k;
                    }
                }
                if (best >= 0) floorAt[best] = Math.max(floorAt[best], cAcc);
            }
        }
        float[] extra = new float[n * n];
        for (int o = n * n - 1; o >= 0; o--) {
            int k = (int) byLevel[o];
            if (floorAt[k] > 0f) {
                float have = net.acc[k] + extra[k];
                if (floorAt[k] > have) extra[k] += floorAt[k] - have;
            }
            if (extra[k] == 0f) continue;
            int d = net.parent[k];
            if (d >= 0) extra[d] += extra[k];
        }
        float[] acc = new float[n * n];
        for (int k = 0; k < n * n; k++) acc[k] = net.acc[k] + extra[k];
        return acc;
    }

    private synchronized float coarseAcc(int ci, int cj) {
        CoarseTile t = coarseTile(ci, cj);
        return t.acc[(ci - t.ci0) * t.n + (cj - t.cj0)];
    }

    public synchronized boolean bigBasin(int windowI, int windowJ, int size) {
        int perCoarse = LC * LATENT_PER_COARSE;
        int ci0 = Math.floorDiv(windowI, perCoarse) - 1, cj0 = Math.floorDiv(windowJ, perCoarse) - 1;
        int ci1 = Math.floorDiv(windowI + size - 1, perCoarse) + 1, cj1 = Math.floorDiv(windowJ + size - 1, perCoarse) + 1;
        for (int ci = ci0; ci <= ci1; ci++) {
            for (int cj = cj0; cj <= cj1; cj++) {
                CoarseTile t = coarseTile(ci, cj);
                if (t.depth[(ci - t.ci0) * t.n + (cj - t.cj0)] > BASIN_DEPTH_M) return true;
            }
        }
        return false;
    }

    public synchronized boolean highZone(int windowI, int windowJ, int size) {
        int perCoarse = LC * LATENT_PER_COARSE;
        int ci0 = Math.floorDiv(windowI, perCoarse) - 1, cj0 = Math.floorDiv(windowJ, perCoarse) - 1;
        int ci1 = Math.floorDiv(windowI + size - 1, perCoarse) + 1, cj1 = Math.floorDiv(windowJ + size - 1, perCoarse) + 1;
        for (int ci = ci0; ci <= ci1; ci++) {
            for (int cj = cj0; cj <= cj1; cj++) {
                if (zoneAt(ci, cj)) return true;
            }
        }
        return false;
    }

    private boolean zoneAt(int ci, int cj) {
        int ti = Math.floorDiv(ci, ZONE_TILE), tj = Math.floorDiv(cj, ZONE_TILE);
        long key = ((long) ti << 32) ^ (tj & 0xFFFFFFFFL);
        boolean[] z = zones.get(key);
        if (z == null) {
            z = buildZone(ti, tj);
            zones.put(key, z);
            if (zones.size() > 16) zones.remove(zones.keySet().iterator().next());
        }
        return z[(ci - ti * ZONE_TILE) * ZONE_TILE + (cj - tj * ZONE_TILE)];
    }

    private boolean[] buildZone(int ti, int tj) {
        int n = ZONE_TILE + 2 * ZONE_MARGIN;
        int c0 = ti * ZONE_TILE - ZONE_MARGIN, d0 = tj * ZONE_TILE - ZONE_MARGIN;
        FloatTensor slice = pipeline.getCoarseSlice(c0, d0, c0 + n, d0 + n);
        int px = n * n;
        float[] elev = new float[px];
        boolean[] reach = new boolean[px];
        Long[] byHeight = new Long[px];
        for (int k = 0; k < px; k++) {
            float w = slice.data[6 * px + k];
            float v = w > 1e-6f ? slice.data[k] / w : 0f;
            elev[k] = Math.signum(v) * v * v;
            reach[k] = elev[k] >= ZONE_HIGH_M;
            int bits = Float.floatToIntBits(-elev[k]);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            byHeight[k] = (((long) bits) << 32) | (k & 0xFFFFFFFFL);
        }
        Arrays.sort(byHeight);
        for (int pass = 0; pass < 3; pass++) {
            for (Long packed : byHeight) {
                int k = (int) (long) packed;
                if (!reach[k]) continue;
                int r = k / n, c = k % n;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || cc < 0 || rr >= n || cc >= n) continue;
                        int j = rr * n + cc;
                        if (elev[j] >= 0f && elev[j] <= elev[k] + ZONE_RISE_M) reach[j] = true;
                    }
                }
            }
        }
        boolean[] out = new boolean[ZONE_TILE * ZONE_TILE];
        for (int r = 0; r < ZONE_TILE; r++) {
            System.arraycopy(reach, (r + ZONE_MARGIN) * n + ZONE_MARGIN, out, r * ZONE_TILE, ZONE_TILE);
        }
        return out;
    }

    private CoarseTile coarseTile(int ci, int cj) {
        int ti = Math.floorDiv(ci, COARSE_TILE), tj = Math.floorDiv(cj, COARSE_TILE);
        long key = ((long) ti << 32) ^ (tj & 0xFFFFFFFFL);
        CoarseTile t = coarse.get(key);
        if (t == null) {
            int n = COARSE_TILE + 2 * COARSE_MARGIN;
            int c0 = ti * COARSE_TILE - COARSE_MARGIN, d0 = tj * COARSE_TILE - COARSE_MARGIN;
            FloatTensor slice = pipeline.getCoarseSlice(c0, d0, c0 + n, d0 + n);
            int px = n * n;
            float[] elev = new float[px];
            for (int k = 0; k < px; k++) {
                float w = slice.data[6 * px + k];
                float v = w > 1e-6f ? slice.data[k] / w : 0f;
                elev[k] = Math.signum(v) * v * v;
            }
            RiverNetwork.Params p = new RiverNetwork.Params();
            p.lakeMinCells = Integer.MAX_VALUE;
            RiverNetwork net = RiverNetwork.solve(elev, n, c0, d0, p);
            float[] acc = new float[px];
            for (int k = 0; k < px; k++) {
                acc[k] = elev[k] < 0f ? 0f : net.acc[k] / RiverNetwork.NATIVE_CELLS_PER_CELL * NATIVE_PER_COARSE;
            }
            float[] depth = new float[px];
            for (int k = 0; k < px; k++) {
                depth[k] = elev[k] < 0f ? 0f : net.filled[k] - elev[k];
            }
            t = new CoarseTile(c0, d0, n, acc, depth);
            coarse.put(key, t);
            if (coarse.size() > 8) {
                coarse.remove(coarse.keySet().iterator().next());
            }
        }
        return t;
    }
}
