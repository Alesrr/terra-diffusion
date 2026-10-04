package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;

public final class KarstHydrology {

    public static final boolean ENABLED =
            !"false".equals(System.getProperty("terradiff.karst"));

    private static final int MARGIN_CELLS =
            Integer.parseInt(System.getProperty("terradiff.karstMargin", "64"));
    private static final int H_STEP_CELLS =
            Integer.parseInt(System.getProperty("terradiff.karstStep", "6"));
    private static final float V_STEP_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstVStep", "12"));
    private static final int MAX_LEVELS =
            Integer.parseInt(System.getProperty("terradiff.karstLevels", "48"));
    private static final int NEIGHBOURS =
            Integer.parseInt(System.getProperty("terradiff.karstNeighbours", "18"));

    private static final int BEDROCK_TOP_Y = -187;
    private static final int FLOOR_Y = DeepCaverns.TOP - 4;
    private static final int KARST_MAX_Y =
            Integer.parseInt(System.getProperty("terradiff.karstMaxY", "320"));
    private static final float ROOF_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstRoof", "18"));

    private static final float WT_GRADIENT =
            Float.parseFloat(System.getProperty("terradiff.karstGradient", "0.035"));
    private static final float VADOSE_NEAR_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstVadoseNear", "2"));
    private static final float VADOSE_GROWTH =
            Float.parseFloat(System.getProperty("terradiff.karstVadoseGrowth", "0.075"));
    private static final float VADOSE_MAX_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstVadoseMax", "72"));
    private static final float NO_RIVER_WT_DROP =
            Float.parseFloat(System.getProperty("terradiff.karstDryDrop", "48"));

    private static final float PHREATIC_ANISO =
            Float.parseFloat(System.getProperty("terradiff.karstPhreaticAniso", "3.5"));
    private static final float VADOSE_ANISO =
            Float.parseFloat(System.getProperty("terradiff.karstVadoseAniso", "2.5"));
    private static final float HORIZON_W =
            Float.parseFloat(System.getProperty("terradiff.karstHorizon", "1.6"));
    private static final float HORIZON_REACH =
            Float.parseFloat(System.getProperty("terradiff.karstHorizonReach", "14"));
    private static final float FRACTURE_W =
            Float.parseFloat(System.getProperty("terradiff.karstFracture", "0.22"));
    private static final float DEEP_W =
            Float.parseFloat(System.getProperty("terradiff.karstDeep", "0.9"));
    private static final float PHREATIC_DEPTH_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstPhreaticDepth", "18"));

    private static final int FOSSIL_LEVELS =
            Integer.parseInt(System.getProperty("terradiff.karstFossilLevels", "2"));
    private static final int DEEP_HORIZONS =
            Integer.parseInt(System.getProperty("terradiff.karstDeepHorizons", "1"));
    private static final float DEEP_HORIZON_SPACING =
            Float.parseFloat(System.getProperty("terradiff.karstDeepSpacing", "45"));
    private static final float CONTACT_OFFSET =
            Float.parseFloat(System.getProperty("terradiff.karstContact", "6"));
    private static final float FOSSIL_MIN_SPACING =
            Float.parseFloat(System.getProperty("terradiff.karstFossilMin", "9"));

    private static final float SPRING_MIN_ACC =
            Float.parseFloat(System.getProperty("terradiff.karstSpringAcc", "4000"));
    private static final float SINK_MIN_ACC =
            Float.parseFloat(System.getProperty("terradiff.karstSinkAcc", "6"));
    private static final float FILL_MIN_M =
            Float.parseFloat(System.getProperty("terradiff.karstFillMin", "1.5"));
    private static final int SINK_SEPARATION =
            Integer.parseInt(System.getProperty("terradiff.karstSinkSep", "1"));
    private static final int MAX_SINKS =
            Integer.parseInt(System.getProperty("terradiff.karstMaxSinks", "40000"));

    private static final int SINK_RATE =
            Integer.parseInt(System.getProperty("terradiff.karstSinkRate", "420"));

    private static final int DOLINE_COUNT =
            Integer.parseInt(System.getProperty("terradiff.karstDolines", "4"));
    private static final int DOLINE_SEPARATION =
            Integer.parseInt(System.getProperty("terradiff.karstDolineSep", "40"));
    private static final float DOLINE_R_TOP =
            Float.parseFloat(System.getProperty("terradiff.karstDolineTop", "9"));
    private static final float DOLINE_R_BOTTOM =
            Float.parseFloat(System.getProperty("terradiff.karstDolineBottom", "3.5"));
    private static final float DOLINE_EXTRA =
            Float.parseFloat(System.getProperty("terradiff.karstDolineExtra", "8"));
    private static final float DOLINE_WATER_CLEARANCE =
            Float.parseFloat(System.getProperty("terradiff.karstDolineClearance", "40"));
    private static final float DOLINE_FLAT =
            Float.parseFloat(System.getProperty("terradiff.karstDolineFlat", "14"));

    private static final float SINK_DROP_BLOCKS = 14f;
    private static final float SPRING_DROP_BLOCKS = 4f;

    private static final float R_MIN =
            Float.parseFloat(System.getProperty("terradiff.karstRMin", "7.2"));
    private static final float R_GAIN =
            Float.parseFloat(System.getProperty("terradiff.karstRGain", "2.4"));
    private static final float R_MAX =
            Float.parseFloat(System.getProperty("terradiff.karstRMax", "9.5"));
    private static final float ACC_REF = 50f;

    private static final int SUBDIVISIONS =
            Integer.parseInt(System.getProperty("terradiff.karstSubdiv", "3"));
    private static final float WANDER =
            Float.parseFloat(System.getProperty("terradiff.karstWander", "0.45"));
    private static final float TAPER_BLOCKS =
            Float.parseFloat(System.getProperty("terradiff.karstTaper", "40"));
    private static final int DEADEND_RATE =
            Integer.parseInt(System.getProperty("terradiff.karstDeadends", "55"));
    private static final float DEADEND_FLOW =
            Float.parseFloat(System.getProperty("terradiff.karstDeadendFlow", "8"));
    private static final int MAX_SEGMENTS = 600000;

    private static final float JITTER =
            Float.parseFloat(System.getProperty("terradiff.karstJitter", "0.40"));

    private static final float KEYHOLE_MIN_R =
            Float.parseFloat(System.getProperty("terradiff.karstKeyholeMinR", "2.5"));

    private static final float VADOSE_ASPECT =
            Float.parseFloat(System.getProperty("terradiff.karstVadoseAspect", "1.45"));

    private static final float NATIVE_RES = 30f;

    private static final FastNoiseLite FRACTURE_ANGLE = noise(0xCA1E, 1f / 900f, 2);
    private static final FastNoiseLite WANDER_X = noise(0x5A17, 1f / 60f, 2);
    private static final FastNoiseLite WANDER_Y = noise(0x5A18, 1f / 60f, 2);
    private static final FastNoiseLite WANDER_Z = noise(0x5A19, 1f / 60f, 2);

    private static FastNoiseLite noise(int seed, float frequency, int octaves) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(frequency);
        fnl.SetFractalOctaves(octaves);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private KarstHydrology() {
    }

    private static final boolean RIVER_UNDER =
            !"false".equals(System.getProperty("terradiff.riverUnder"));

    // Blocks the water must sit below the ground before the reach is routed underground
    private static final float UNDER_MIN_PERCH =
            Float.parseFloat(System.getProperty("terradiff.riverUnderPerch", "40"));

    private static final float UNDER_R_MIN =
            Float.parseFloat(System.getProperty("terradiff.riverUnderRMin", "4.5"));
    private static final float UNDER_R_MAX =
            Float.parseFloat(System.getProperty("terradiff.riverUnderRMax", "9.0"));

    // Headroom above the water line, as a share of the passage's vertical radius
    private static final int MAX_RUN_CELLS =
            Integer.parseInt(System.getProperty("terradiff.riverUnderMaxRun", "400"));

    private static final float UNDER_HEADROOM =
            Float.parseFloat(System.getProperty("terradiff.riverUnderHeadroom", "0.30"));

    // Routes a river underground wherever its solved level runs far below the ground
    private static void emitUnderground(Emitter em, float[] elev, boolean[] channel, float[] water,
                                        float[] widthM, int[] down, int grid,
                                        int windowI, int windowJ, int k0, int kn,
                                        float blocksPerCell, float blockM, short[] groundY) {
        int runs = 0, emitted = 0, abandoned = 0;
        int[] chain = new int[MAX_RUN_CELLS + 2];

        for (int u = 1; u < kn - 1; u++) {
            for (int v = 1; v < kn - 1; v++) {
                int head = (k0 + u) * grid + (k0 + v);
                if (!channel[head] || Float.isNaN(water[head])) continue;
                if (perchOf(elev, water, head, blockM) >= UNDER_MIN_PERCH) continue;

                int first = step(down, channel, water, grid, head);
                if (first < 0 || perchOf(elev, water, first, blockM) < UNDER_MIN_PERCH) continue;

                int len = 0;
                chain[len++] = head;
                int at = first;
                boolean closed = false;
                while (len < MAX_RUN_CELLS) {
                    chain[len++] = at;
                    int next = step(down, channel, water, grid, at);
                    if (next < 0) break;
                    if (perchOf(elev, water, next, blockM) < UNDER_MIN_PERCH) {
                        chain[len++] = next;
                        closed = true;
                        break;
                    }
                    at = next;
                }
                if (!closed) {
                    abandoned++;
                    continue;
                }

                runs++;
                for (int k = 0; k + 1 < len; k++) {
                    int a = chain[k], b = chain[k + 1];
                    float r = widthM[a] / blockM * 0.5f;
                    if (r < UNDER_R_MIN) r = UNDER_R_MIN;
                    if (r > UNDER_R_MAX) r = UNDER_R_MAX;
                    float drop = r * UNDER_HEADROOM;
                    float ax = (windowJ + (a % grid)) * blocksPerCell;
                    float az = (windowI + (a / grid)) * blocksPerCell;
                    float bx = (windowJ + (b % grid)) * blocksPerCell;
                    float bz = (windowI + (b / grid)) * blocksPerCell;
                    em.addRiver(ax, toY(water[a], blockM) + drop, az,
                            bx, toY(water[b], blockM) + drop, bz,
                            r, r, drop, KarstNetwork.ZONE_RIVER);
                    emitted++;
                }
                // The shafts go on the DRY side of each junction
                raiseShaft(em, chain[1], grid, k0, kn, windowI, windowJ, blocksPerCell, blockM,
                        water, widthM, groundY);
                raiseShaft(em, chain[len - 2], grid, k0, kn, windowI, windowJ, blocksPerCell,
                        blockM, water, widthM, groundY);
            }
        }
        if (Boolean.getBoolean("terradiff.diag")) {
            System.err.printf("diag underground river: %d sinking reaches (%d segments), "
                    + "%d dropped for never resurfacing%n", runs, emitted, abandoned);
        }
    }

    private static float perchOf(float[] elev, float[] water, int i, float blockM) {
        return (elev[i] - water[i]) / blockM;
    }

    // Downstream neighbour, provided it is still a channel cell with a solved level
    private static int step(int[] down, boolean[] channel, float[] water, int grid, int i) {
        int d = down[i];
        if (d < 0 || d == i || d >= channel.length) return -1;
        int dr = (d / grid) - (i / grid), dc = (d % grid) - (i % grid);
        if (Math.abs(dr) > 1 || Math.abs(dc) > 1) return -1;
        if (!channel[d] || Float.isNaN(water[d])) return -1;
        return d;
    }

    // Swallow hole or resurgence: joins the conduit end to daylight
    private static void raiseShaft(Emitter em, int i, int grid, int k0, int kn,
                                   int windowI, int windowJ, float blocksPerCell, float blockM,
                                   float[] water, float[] widthM, short[] groundY) {
        int u = (i / grid) - k0, v = (i % grid) - k0;
        if (u < 0 || v < 0 || u >= kn || v >= kn) return;
        float r = widthM[i] / blockM * 0.5f;
        if (r < UNDER_R_MIN) r = UNDER_R_MIN;
        if (r > UNDER_R_MAX) r = UNDER_R_MAX;
        float drop = r * UNDER_HEADROOM;
        float x = (windowJ + v + k0) * blocksPerCell;
        float z = (windowI + u + k0) * blocksPerCell;
        float y0 = toY(water[i], blockM) + drop;
        float y1 = groundY[u * kn + v] + 1f;
        if (y1 <= y0) return;
        em.addRiver(x, y0, z, x, y1, z, r * 0.7f, r * 0.7f, drop,
                KarstNetwork.ZONE_RIVER_SHAFT);
    }

    // Whether the neighbour on the given side is itself underground, so a shaft is only raised where
    private static boolean submerged(float[] elev, float[] water, boolean[] channel, int[] down,
                                     int grid, int i, float blockM, boolean upstream) {
        if (upstream) {
            int r = i / grid, c = i % grid;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    int j = (r + dr) * grid + (c + dc);
                    if (j < 0 || j >= channel.length || !channel[j] || Float.isNaN(water[j])) continue;
                    int jd = down[j];
                    if (jd != i) continue;
                    if ((elev[j] - water[j]) / blockM >= UNDER_MIN_PERCH) return true;
                }
            }
            return false;
        }
        int d = down[i];
        if (d < 0 || d == i || d >= channel.length || !channel[d] || Float.isNaN(water[d])) {
            return false;
        }
        return (elev[d] - water[d]) / blockM >= UNDER_MIN_PERCH;
    }

    private static int toY(float metres, float blockM) {
        if (metres >= 0f) return (int) (metres / blockM) + 63;
        float depthBlocks = -metres / blockM;
        float limited = 196f * depthBlocks / (depthBlocks + 196f);
        return -((int) limited) - 1 + 63;
    }

    public static KarstNetwork solve(int regionI, int regionJ,
                                     int windowI, int windowJ, int grid,
                                     float[] elev, float[] acc, boolean[] channel,
                                     float[] fillDepth, float[] water, float[] lake,
                                     float[] widthM, int[] down,
                                     int regionPx, int marginPx, float blockM) {
        if (!ENABLED) return KarstNetwork.EMPTY;

        final float blocksPerCell = NATIVE_RES / blockM;
        final int km = Math.min(MARGIN_CELLS, marginPx);
        final int k0 = marginPx - km;
        final int kn = regionPx + 2 * km;
        if (kn <= 0 || k0 < 0 || k0 + kn > grid) return KarstNetwork.EMPTY;

        final int hs = Math.max(1, H_STEP_CELLS);
        final int nxz = kn / hs;
        if (nxz < 4) return KarstNetwork.EMPTY;

        // ground and water table over the karst window
        short[] groundY = new short[kn * kn];
        float[] srcY = new float[kn * kn];
        float[] dist = new float[kn * kn];
        Arrays.fill(srcY, Float.NaN);
        Arrays.fill(dist, Float.MAX_VALUE);
        boolean anySource = false;

        for (int u = 0; u < kn; u++) {
            int rowW = (k0 + u) * grid + k0;
            int rowK = u * kn;
            for (int v = 0; v < kn; v++) {
                int w = rowW + v;
                groundY[rowK + v] = (short) toY(elev[w], blockM);
                float lvl = water[w];
                boolean wet = !Float.isNaN(lvl) && lvl > elev[w];
                if (!wet) {
                    float lk = lake[w];
                    if (!Float.isNaN(lk) && lk > elev[w]) {
                        lvl = lk;
                        wet = true;
                    }
                }
                if (wet) {
                    srcY[rowK + v] = toY(lvl, blockM);
                    dist[rowK + v] = 0f;
                    anySource = true;
                }
            }
        }
        chamfer(srcY, dist, kn);

        short[] wt = new short[kn * kn];
        for (int i = 0; i < kn * kn; i++) {
            float vadose = Math.min(VADOSE_MAX_BLOCKS,
                    VADOSE_NEAR_BLOCKS + VADOSE_GROWTH * dist[i] * blocksPerCell);
            float cap = groundY[i] - vadose;
            float v;
            if (!anySource || Float.isNaN(srcY[i])) {
                v = groundY[i] - NO_RIVER_WT_DROP;
            } else {
                float rise = srcY[i] + WT_GRADIENT * dist[i] * blocksPerCell;
                v = Math.min(cap, rise);
            }
            if (v < FLOOR_Y) v = FLOOR_Y;
            if (v > groundY[i]) v = groundY[i];
            wt[i] = (short) Math.round(v);
        }

        // lattice
        int[] colCell = new int[nxz * nxz];
        short[] colSurf = new short[nxz * nxz];
        short[] colWt = new short[nxz * nxz];
        float[] colPosX = new float[nxz * nxz];
        float[] colPosZ = new float[nxz * nxz];
        final float jitter = JITTER * hs * blocksPerCell;
        int maxSurf = Integer.MIN_VALUE;
        for (int cu = 0; cu < nxz; cu++) {
            int u = cu * hs + hs / 2;
            for (int cv = 0; cv < nxz; cv++) {
                int v = cv * hs + hs / 2;
                int cell = u * kn + v;
                int col = cu * nxz + cv;
                colCell[col] = cell;
                colSurf[col] = groundY[cell];
                colWt[col] = wt[cell];
                int hx = mix((windowJ + k0 + v) * 73856093 ^ (windowI + k0 + u) * 19349663);
                int hz = mix(hx ^ 0x2545F491);
                colPosX[col] = colX(windowJ, k0, cv, hs, blocksPerCell)
                        + ((hx >>> 8 & 0xFFFF) / 65535f - 0.5f) * 2f * jitter;
                colPosZ[col] = colZ(windowI, k0, cu, hs, blocksPerCell)
                        + ((hz >>> 8 & 0xFFFF) / 65535f - 0.5f) * 2f * jitter;
                if (groundY[cell] > maxSurf) maxSurf = groundY[cell];
            }
        }

        int yTop = Math.min(maxSurf - (int) ROOF_BLOCKS, KARST_MAX_Y);
        if (yTop <= FLOOR_Y + 8) return KarstNetwork.EMPTY;

        float vs = V_STEP_BLOCKS;
        int levels = (int) Math.ceil((yTop - FLOOR_Y) / vs) + 1;
        if (levels > MAX_LEVELS) {
            levels = MAX_LEVELS;
            vs = (yTop - FLOOR_Y) / (float) (levels - 1);
        }
        final int K = levels;
        final float vstep = vs;
        final int nodes = nxz * nxz * K;

        float[] nodeDist = new float[nodes];
        int[] prev = new int[nodes];
        Arrays.fill(nodeDist, Float.MAX_VALUE);
        Arrays.fill(prev, -1);

        LongHeap heap = new LongHeap(Math.max(1024, nodes / 8));

        // springs
        int[] springLevel = new int[nxz * nxz];
        Arrays.fill(springLevel, -1);
        int springCount = 0;
        for (int u = 0; u < kn; u++) {
            int rowW = (k0 + u) * grid + k0;
            for (int v = 0; v < kn; v++) {
                int w = rowW + v;
                if (!channel[w] || elev[w] < 0f || Float.isNaN(water[w])) continue;
                if (acc[w] < SPRING_MIN_ACC) continue;
                int col = (u / hs) * nxz + (v / hs);
                if (col < 0 || col >= nxz * nxz) continue;
                if (u / hs >= nxz || v / hs >= nxz) continue;
                int y = toY(water[w], blockM) - (int) SPRING_DROP_BLOCKS;
                int k = level(y, vstep, K);
                if (k < 0) continue;
                if (springLevel[col] < 0 || k < springLevel[col]) springLevel[col] = k;
            }
        }
        for (int col = 0; col < nxz * nxz; col++) {
            int k = springLevel[col];
            if (k < 0) continue;
            if (blocked(colSurf[col], k, vstep)) {
                k = level(colSurf[col] - (int) ROOF_BLOCKS - 1, vstep, K);
                if (k < 0) continue;
            }
            int n = col * K + k;
            if (nodeDist[n] == 0f) continue;
            nodeDist[n] = 0f;
            heap.push(0f, n);
            springCount++;
        }

        if (springCount == 0) {
            int best = -1, bestY = Integer.MAX_VALUE;
            for (int col = 0; col < nxz * nxz; col++) {
                if (colSurf[col] < bestY) { bestY = colSurf[col]; best = col; }
            }
            if (best < 0) return KarstNetwork.EMPTY;
            int k = level(colWt[best], vstep, K);
            if (k < 0) return KarstNetwork.EMPTY;
            int n = best * K + k;
            nodeDist[n] = 0f;
            heap.push(0f, n);
            springCount = 1;
        }

        // Dijkstra
        final int[] dU = {1, -1, 0, 0, 1, 1, -1, -1, 0, 0, 1, -1, 0, 0, 1, 1, -1, -1};
        final int[] dV = {0, 0, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1};
        final int[] dK = {0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 1, 1, 1, 1, -1, -1, -1, -1};
        final int nb = Math.min(NEIGHBOURS, dU.length);

        final float hStepBlocks = hs * blocksPerCell;

        // Fracture direction per column and horizontal step, shared by every level
        float[] fracCos = null, fracSin = null;
        if (FRACTURE_W > 0f) {
            fracCos = new float[nxz * nxz * 9];
            fracSin = new float[nxz * nxz * 9];
            for (int col = 0; col < nxz * nxz; col++) {
                int cu = col / nxz, cv = col % nxz;
                for (int h = 0; h < 9; h++) {
                    int nu = cu + h / 3 - 1, nv = cv + h % 3 - 1;
                    if (nu < 0 || nv < 0 || nu >= nxz || nv >= nxz) continue;
                    int ncol = nu * nxz + nv;
                    float midX = 0.5f * (colPosX[col] + colPosX[ncol]);
                    float midZ = 0.5f * (colPosZ[col] + colPosZ[ncol]);
                    float ang = FRACTURE_ANGLE.GetNoise(midX, midZ) * (float) Math.PI;
                    fracCos[col * 9 + h] = (float) Math.cos(ang);
                    fracSin[col * 9 + h] = (float) Math.sin(ang);
                }
            }
        }

        while (!heap.isEmpty()) {
            long top = heap.pop();
            int n = (int) (top & 0xFFFFFFFFL);
            float d = Float.intBitsToFloat((int) (top >>> 32));
            if (d > nodeDist[n]) continue;

            int k = n % K;
            int col = n / K;
            int cu = col / nxz, cv = col % nxz;
            float y0 = FLOOR_Y + k * vstep;
            float x0 = colPosX[col];
            float z0 = colPosZ[col];

            for (int e = 0; e < nb; e++) {
                int nu = cu + dU[e], nv = cv + dV[e], nk = k + dK[e];
                if (nu < 0 || nv < 0 || nu >= nxz || nv >= nxz || nk < 0 || nk >= K) continue;
                int ncol = nu * nxz + nv;
                if (blocked(colSurf[ncol], nk, vstep)) continue;
                int m = ncol * K + nk;

                float y1 = FLOOR_Y + nk * vstep;
                float x1 = colPosX[ncol];
                float z1 = colPosZ[ncol];

                float ex = x1 - x0, ey = y1 - y0, ez = z1 - z0;
                float len = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (len <= 0f) continue;
                float vert = Math.abs(ey) / len;

                float midY = 0.5f * (y0 + y1);
                float table = 0.5f * (colWt[col] + colWt[ncol]);
                float ground = 0.5f * (colSurf[col] + colSurf[ncol]);
                float hbest = horizonDistance(midY, table, ground);

                float cost;
                if (midY < table || hbest < HORIZON_REACH) {
                    cost = len * (1f + PHREATIC_ANISO * vert);
                } else {
                    cost = len * (1f + VADOSE_ANISO * (1f - vert));
                }

                if (HORIZON_W > 0f) {
                    float t = hbest / HORIZON_REACH;
                    if (t > 1f) t = 1f;
                    cost += HORIZON_W * len * t;
                }

                if (FRACTURE_W > 0f) {
                    int h = col * 9 + (dU[e] + 1) * 3 + (dV[e] + 1);
                    float ca = fracCos[h], sa = fracSin[h];
                    float hx = ex / len, hz = ez / len;
                    float a1 = Math.abs(hx * ca + hz * sa);
                    float a2 = Math.abs(-hx * sa + hz * ca);
                    float align = Math.max(a1, a2);
                    cost += FRACTURE_W * len * (1f - align);
                }

                if (DEEP_W > 0f) {
                    float limit = table - PHREATIC_DEPTH_BLOCKS;
                    if (midY < limit) {
                        cost += DEEP_W * len * (limit - midY) / PHREATIC_DEPTH_BLOCKS;
                    }
                }

                float nd = d + cost;
                if (nd < nodeDist[m]) {
                    nodeDist[m] = nd;
                    prev[m] = n;
                    heap.push(nd, m);
                }
            }
        }

        // sinks
        float[] colAcc = new float[nxz * nxz];
        boolean[] colDoline = new boolean[nxz * nxz];
        int[] colSinkLevel = new int[nxz * nxz];
        Arrays.fill(colSinkLevel, -1);
        for (int u = 0; u < kn; u++) {
            int rowW = (k0 + u) * grid + k0;
            int cu = u / hs;
            if (cu >= nxz) continue;
            for (int v = 0; v < kn; v++) {
                int cv = v / hs;
                if (cv >= nxz) continue;
                int w = rowW + v;
                if (elev[w] < 0f || channel[w]) continue;
                boolean doline = fillDepth[w] > FILL_MIN_M;
                if (!doline && acc[w] < SINK_MIN_ACC) continue;
                float score = acc[w] * (doline ? 4f : 1f);
                int col = cu * nxz + cv;
                if (((mix(col * 0x9E3779B1) >>> 3) & 1023) >= SINK_RATE) continue;
                if (score > colAcc[col]) {
                    colAcc[col] = score;
                    colDoline[col] = doline;
                    int y = groundY[u * kn + v] - (int) SINK_DROP_BLOCKS;
                    colSinkLevel[col] = level(y, vstep, K);
                }
            }
        }

        int cand = 0;
        for (int col = 0; col < nxz * nxz; col++) if (colSinkLevel[col] >= 0) cand++;
        int[] order = new int[cand];
        int oi = 0;
        for (int col = 0; col < nxz * nxz; col++) if (colSinkLevel[col] >= 0) order[oi++] = col;
        final float[] scoreRef = colAcc;
        Integer[] boxed = new Integer[cand];
        for (int i = 0; i < cand; i++) boxed[i] = order[i];
        Arrays.sort(boxed, (a, b) -> Float.compare(scoreRef[b], scoreRef[a]));

        boolean[] taken = new boolean[nxz * nxz];
        int[] sinks = new int[Math.min(cand, MAX_SINKS)];
        float[] sinkFlow = new float[sinks.length];
        int sinkCount = 0;
        for (int i = 0; i < cand && sinkCount < sinks.length; i++) {
            int col = boxed[i];
            int cu = col / nxz, cv = col % nxz;
            boolean clash = false;
            for (int du = -SINK_SEPARATION; du <= SINK_SEPARATION && !clash; du++) {
                for (int dv = -SINK_SEPARATION; dv <= SINK_SEPARATION; dv++) {
                    int nu = cu + du, nv = cv + dv;
                    if (nu < 0 || nv < 0 || nu >= nxz || nv >= nxz) continue;
                    if (taken[nu * nxz + nv]) { clash = true; break; }
                }
            }
            if (clash) continue;
            int k = colSinkLevel[col];
            if (blocked(colSurf[col], k, vstep)) {
                k = level(colSurf[col] - (int) ROOF_BLOCKS - 1, vstep, K);
                if (k < 0) continue;
            }
            int n = col * K + k;
            if (nodeDist[n] >= Float.MAX_VALUE) continue;
            taken[col] = true;
            sinks[sinkCount] = n;
            sinkFlow[sinkCount] = colAcc[col];
            sinkCount++;
        }

        if (sinkCount == 0) return KarstNetwork.EMPTY;

        // flow accumulation along the predecessor forest
        float[] flow = new float[nodes];
        boolean[] onPath = new boolean[nodes];
        for (int s = 0; s < sinkCount; s++) {
            int at = sinks[s];
            float q = sinkFlow[s];
            int guard = 0;
            while (at >= 0 && guard++ < nodes) {
                flow[at] += q;
                onPath[at] = true;
                int p = prev[at];
                if (p < 0) break;
                at = p;
            }
        }

        int deadends = 0;
        if (DEADEND_RATE > 0) {
            for (int n = 0; n < nodes; n++) {
                if (onPath[n] || nodeDist[n] >= Float.MAX_VALUE) continue;
                if (((mix(n) >>> 1) & 1023) >= DEADEND_RATE) continue;
                int col = n / K;
                float y = FLOOR_Y + (n % K) * vstep;
                float table = colWt[col];
                float hbest = horizonDistance(y, table, colSurf[col]);
                if (hbest > HORIZON_REACH * 1.5f) continue;
                int at = n, guard = 0;
                while (at >= 0 && !onPath[at] && guard++ < 512) {
                    onPath[at] = true;
                    flow[at] += DEADEND_FLOW;
                    at = prev[at];
                }
                deadends++;
            }
        }

        // emit
        Emitter em = new Emitter();
        float regionMinX = regionJ * (float) regionPx * blocksPerCell;
        float regionMaxX = (regionJ + 1) * (float) regionPx * blocksPerCell;
        float regionMinZ = regionI * (float) regionPx * blocksPerCell;
        float regionMaxZ = (regionI + 1) * (float) regionPx * blocksPerCell;

        for (int n = 0; n < nodes && em.n < MAX_SEGMENTS; n++) {
            if (!onPath[n]) continue;
            int p = prev[n];
            if (p < 0 || !onPath[p]) continue;

            int k = n % K, col = n / K;
            int pk = p % K, pcol = p / K;
            float ay = FLOOR_Y + k * vstep;
            float ax = colPosX[col];
            float az = colPosZ[col];
            float by = FLOOR_Y + pk * vstep;
            float bxx = colPosX[pcol];
            float bzz = colPosZ[pcol];

            float r = R_MIN + R_GAIN * (float) Math.log10(1.0 + flow[n] / ACC_REF);
            if (r > R_MAX) r = R_MAX;
            if (r < R_MIN) r = R_MIN;

            float tableA = colWt[col], tableB = colWt[pcol];
            float groundA = colSurf[col], groundB = colSurf[pcol];
            float amp = WANDER * Math.min(hStepBlocks, vstep);

            for (int s = 0; s < SUBDIVISIONS && em.n < MAX_SEGMENTS; s++) {
                float t0 = s / (float) SUBDIVISIONS;
                float t1 = (s + 1) / (float) SUBDIVISIONS;
                float p0x = ax + (bxx - ax) * t0, p0y = ay + (by - ay) * t0, p0z = az + (bzz - az) * t0;
                float p1x = ax + (bxx - ax) * t1, p1y = ay + (by - ay) * t1, p1z = az + (bzz - az) * t1;
                if (s > 0) { p0x += wx(p0x, p0y, p0z, amp); p0y += wy(p0x, p0y, p0z, amp); p0z += wz(p0x, p0y, p0z, amp); }
                if (s < SUBDIVISIONS - 1) { p1x += wx(p1x, p1y, p1z, amp); p1y += wy(p1x, p1y, p1z, amp); p1z += wz(p1x, p1y, p1z, amp); }

                float mx = 0.5f * (p0x + p1x), my = 0.5f * (p0y + p1y), mz = 0.5f * (p0z + p1z);
                if (mx < regionMinX || mx >= regionMaxX || mz < regionMinZ || mz >= regionMaxZ) continue;

                float edge = Math.min(Math.min(mx - regionMinX, regionMaxX - mx),
                        Math.min(mz - regionMinZ, regionMaxZ - mz));
                float fade = TAPER_BLOCKS <= 0f ? 1f : Math.min(1f, edge / TAPER_BLOCKS);
                if (fade <= 0.05f) continue;
                if (r * fade < 0.6f) continue;

                float mt = 0.5f * (t0 + t1);
                float table = tableA + (tableB - tableA) * mt;
                float ground = groundA + (groundB - groundA) * mt;
                float reach = r * fade + 2f;
                for (int q = 0; q < 5; q++) {
                    float qx = mx + (q == 1 ? reach : q == 2 ? -reach : 0f);
                    float qz = mz + (q == 3 ? reach : q == 4 ? -reach : 0f);
                    int gv = Math.round(qx / blocksPerCell) - (windowJ + k0);
                    int gu = Math.round(qz / blocksPerCell) - (windowI + k0);
                    if (gu < 0 || gv < 0 || gu >= kn || gv >= kn) continue;
                    float exact = groundY[gu * kn + gv];
                    if (exact < ground) ground = exact;
                }
                byte zone = classify(my, table, ground);
                float rr = r * fade;
                float headroom = ground - Math.max(p0y, p1y) - 3f;
                float capR = headroom / VADOSE_ASPECT;
                if (rr > capR) rr = capR;
                if (rr < 0.6f) continue;
                em.add(p0x, p0y, p0z, p1x, p1y, p1z, rr, zone);
            }
        }

        if (Boolean.getBoolean("terradiff.diag")) {
            float dmin = Float.MAX_VALUE, dmax = 0f;
            double dsum = 0;
            int seeded = 0;
            for (int i = 0; i < kn * kn; i++) {
                if (dist[i] == 0f) seeded++;
                float v = dist[i] == Float.MAX_VALUE ? 99999f : dist[i] * blocksPerCell;
                if (v < dmin) dmin = v;
                if (v > dmax) dmax = v;
                dsum += v;
            }
            int nw = 0, nl = 0;
            for (int u = 0; u < kn; u++) {
                int rw = (k0 + u) * grid + k0;
                for (int v = 0; v < kn; v++) {
                    if (!Float.isNaN(water[rw + v])) nw++;
                    if (!Float.isNaN(lake[rw + v])) nl++;
                }
            }
            System.err.printf("diag karst: non-NaN water %d, non-NaN lake %d, of %d cells%n", nw, nl, kn * kn);
            System.err.printf("diag karst: water-seed cells %d of %d (%.1f%%), distance-to-water blocks min %.0f mean %.0f max %.0f%n",
                    seeded, kn * kn, 100.0 * seeded / (kn * kn), dmin, dsum / (kn * kn), dmax);
            float best = 0f;
            for (int i = 0; i < sinkCount; i++) {
                float w = dist[colCell[sinks[i] / K]] * blocksPerCell;
                if (w > best) best = w;
            }
            System.err.printf("diag karst: %d sinks, furthest from water %.0f blocks%n", sinkCount, best);
        }

        int dolines = 0;
        if (DOLINE_COUNT > 0 && sinkCount > 0) {
            final float[] sf = sinkFlow;
            Integer[] byFlow = new Integer[sinkCount];
            for (int i = 0; i < sinkCount; i++) byFlow[i] = i;
            Arrays.sort(byFlow, (a, c) -> Float.compare(sf[c], sf[a]));
            boolean[] usedDoline = new boolean[nxz * nxz];
            for (int di = 0; di < sinkCount && dolines < DOLINE_COUNT; di++) {
                int node = sinks[byFlow[di]];
                int dcol = node / K;
                int du0 = dcol / nxz, dv0 = dcol % nxz;
                float dx = colPosX[dcol], dz = colPosZ[dcol];
                if (dx < regionMinX + 64f || dx >= regionMaxX - 64f
                        || dz < regionMinZ + 64f || dz >= regionMaxZ - 64f) continue;

                float waterAway = dist[colCell[dcol]] * blocksPerCell;
                if (waterAway < DOLINE_WATER_CLEARANCE) continue;

                boolean clash = false;
                for (int a = -DOLINE_SEPARATION; a <= DOLINE_SEPARATION && !clash; a++) {
                    for (int c = -DOLINE_SEPARATION; c <= DOLINE_SEPARATION; c++) {
                        int nu = du0 + a, nv = dv0 + c;
                        if (nu < 0 || nv < 0 || nu >= nxz || nv >= nxz) continue;
                        if (usedDoline[nu * nxz + nv]) { clash = true; break; }
                    }
                }
                if (clash) continue;

                float sy = colSurf[dcol];
                float lo = sy, hi = sy;
                boolean rim = true;
                for (int a = -2; a <= 2 && rim; a++) {
                    for (int c = -2; c <= 2; c++) {
                        int nu = du0 + a, nv = dv0 + c;
                        if (nu < 0 || nv < 0 || nu >= nxz || nv >= nxz) { rim = false; break; }
                        float g = colSurf[nu * nxz + nv];
                        if (g < lo) lo = g;
                        if (g > hi) hi = g;
                    }
                }
                if (!rim || hi - lo > DOLINE_FLAT) continue;

                usedDoline[dcol] = true;
                dolines++;

                float bottom = FLOOR_Y + (node % K) * vstep - DOLINE_EXTRA;
                int steps = 10;
                float[] sx = new float[steps + 1];
                float[] syv = new float[steps + 1];
                float[] sz = new float[steps + 1];
                for (int i = 0; i <= steps; i++) {
                    float t = i / (float) steps;
                    syv[i] = bottom + (sy - bottom) * t;
                    sx[i] = dx + wx(dx, syv[i], dz, 2.4f);
                    sz[i] = dz + wz(dx, syv[i], dz, 2.4f);
                }
                for (int i = 0; i < steps; i++) {
                    float rt = (i + 0.5f) / steps;
                    float rad = DOLINE_R_BOTTOM + (DOLINE_R_TOP - DOLINE_R_BOTTOM) * rt * rt;
                    em.addDoline(sx[i], syv[i], sz[i], sx[i + 1], syv[i + 1], sz[i + 1], rad);
                }
            }
        }

        if (em.n == 0) return KarstNetwork.EMPTY;

        int wtStep = hs;
        int wtNX = nxz, wtNZ = nxz;
        short[] wtRaster = new short[wtNX * wtNZ];
        for (int cu = 0; cu < nxz; cu++) {
            for (int cv = 0; cv < nxz; cv++) {
                wtRaster[cu * wtNX + cv] = colWt[cu * nxz + cv];
            }
        }
        if (RIVER_UNDER) {
            emitUnderground(em, elev, channel, water, widthM, down, grid,
                    windowI, windowJ, k0, kn, blocksPerCell, blockM, groundY);
        }

        int wtOriginX = Math.round(colX(windowJ, k0, 0, hs, blocksPerCell));
        int wtOriginZ = Math.round(colZ(windowI, k0, 0, hs, blocksPerCell));

        int cellSize = Math.max(8, Math.round(hStepBlocks));
        int originX = (int) Math.floor(regionMinX) - cellSize;
        int originZ = (int) Math.floor(regionMinZ) - cellSize;
        int gx = (int) Math.ceil((regionMaxX - originX) / cellSize) + 2;
        int gz = (int) Math.ceil((regionMaxZ - originZ) / cellSize) + 2;

        return em.build(cellSize, originX, originZ, gx, gz,
                wtOriginX, wtOriginZ, Math.round(hStepBlocks), wtNX, wtNZ, wtRaster);
    }

    private static float fossilStep(float ground, float table) {
        float step = (ground - ROOF_BLOCKS - table) / (FOSSIL_LEVELS + 1);
        return step < FOSSIL_MIN_SPACING ? 0f : step;
    }

    private static float horizonDistance(float y, float table, float ground) {
        float best = Math.abs(y - table);
        float step = fossilStep(ground, table);
        if (step > 0f) {
            for (int f = 1; f <= FOSSIL_LEVELS; f++) {
                float h = Math.abs(y - (table + f * step));
                if (h < best) best = h;
            }
        }
        float contact = Math.abs(y - (DeepCaverns.TOP + CONTACT_OFFSET));
        if (contact < best) best = contact;
        for (int g = 1; g <= DEEP_HORIZONS; g++) {
            float hy = table - g * DEEP_HORIZON_SPACING;
            if (hy < FLOOR_Y) break;
            float h = Math.abs(y - hy);
            if (h < best) best = h;
        }
        return best;
    }

    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }

    private static byte classify(float y, float table, float ground) {
        if (y < table) return KarstNetwork.ZONE_PHREATIC;
        float step = fossilStep(ground, table);
        if (step > 0f) {
            for (int f = 1; f <= FOSSIL_LEVELS; f++) {
                if (Math.abs(y - (table + f * step)) < step * 0.35f) {
                    return KarstNetwork.ZONE_KEYHOLE;
                }
            }
        }
        return KarstNetwork.ZONE_VADOSE;
    }

    private static float wx(float x, float y, float z, float a) { return WANDER_X.GetNoise(x, y, z) * a; }
    private static float wy(float x, float y, float z, float a) { return WANDER_Y.GetNoise(x, y, z) * a * 0.55f; }
    private static float wz(float x, float y, float z, float a) { return WANDER_Z.GetNoise(x, y, z) * a; }

    private static float colX(int windowJ, int k0, int cv, int hs, float blocksPerCell) {
        return (windowJ + k0 + cv * hs + hs * 0.5f) * blocksPerCell;
    }

    private static float colZ(int windowI, int k0, int cu, int hs, float blocksPerCell) {
        return (windowI + k0 + cu * hs + hs * 0.5f) * blocksPerCell;
    }

    private static int level(float y, float vstep, int K) {
        int k = Math.round((y - FLOOR_Y) / vstep);
        if (k < 0) return -1;
        if (k >= K) k = K - 1;
        return k;
    }

    private static boolean blocked(short surfY, int k, float vstep) {
        return FLOOR_Y + k * vstep > surfY - ROOF_BLOCKS;
    }

    private static void chamfer(float[] srcY, float[] dist, int n) {
        final float D = 1f, Q = 1.41421356f;
        for (int u = 0; u < n; u++) {
            for (int v = 0; v < n; v++) {
                int i = u * n + v;
                relax(srcY, dist, i, u - 1, v, n, D);
                relax(srcY, dist, i, u - 1, v - 1, n, Q);
                relax(srcY, dist, i, u - 1, v + 1, n, Q);
                relax(srcY, dist, i, u, v - 1, n, D);
            }
        }
        for (int u = n - 1; u >= 0; u--) {
            for (int v = n - 1; v >= 0; v--) {
                int i = u * n + v;
                relax(srcY, dist, i, u + 1, v, n, D);
                relax(srcY, dist, i, u + 1, v + 1, n, Q);
                relax(srcY, dist, i, u + 1, v - 1, n, Q);
                relax(srcY, dist, i, u, v + 1, n, D);
            }
        }
    }

    private static void relax(float[] srcY, float[] dist, int i, int u, int v, int n, float step) {
        if (u < 0 || v < 0 || u >= n || v >= n) return;
        int j = u * n + v;
        if (dist[j] == Float.MAX_VALUE) return;
        float cand = dist[j] + step;
        if (cand < dist[i]) {
            dist[i] = cand;
            srcY[i] = srcY[j];
        }
    }

    private static final class Emitter {
        int n;
        float[] ax = new float[4096], ay = new float[4096], az = new float[4096];
        float[] bx = new float[4096], by = new float[4096], bz = new float[4096];
        float[] rh = new float[4096], rv = new float[4096];
        byte[] zone = new byte[4096];
        float[] river = new float[4096];

        void add(float x0, float y0, float z0, float x1, float y1, float z1, float r, byte z) {
            ensure();
            float h, v;
            switch (z) {
                case KarstNetwork.ZONE_VADOSE -> { h = r * 0.75f; v = r * VADOSE_ASPECT; }
                case KarstNetwork.ZONE_KEYHOLE -> { h = r; v = r; }
                default -> { h = r; v = r * 0.85f; }
            }
            push(x0, y0, z0, x1, y1, z1, h, v, z);
            if (z == KarstNetwork.ZONE_KEYHOLE && r >= KEYHOLE_MIN_R) {
                float drop = r * 0.85f;
                push(x0, y0 - drop, z0, x1, y1 - drop, z1, r * 0.36f, r * 0.95f, z);
            }
        }

        void addDoline(float x0, float y0, float z0, float x1, float y1, float z1, float r) {
            push(x0, y0, z0, x1, y1, z1, r, r, KarstNetwork.ZONE_DOLINE);
        }

        // A reach of underground river
        void addRiver(float x0, float y0, float z0, float x1, float y1, float z1,
                      float h, float v, float drop, byte z) {
            ensure();
            push(x0, y0, z0, x1, y1, z1, h, v, z);
            river[n - 1] = drop;
        }

        private void push(float x0, float y0, float z0, float x1, float y1, float z1,
                          float h, float v, byte z) {
            ensure();
            ax[n] = x0; ay[n] = y0; az[n] = z0;
            bx[n] = x1; by[n] = y1; bz[n] = z1;
            rh[n] = h; rv[n] = v; zone[n] = z;
            n++;
        }

        private void ensure() {
            if (n < ax.length) return;
            int c = ax.length * 2;
            ax = Arrays.copyOf(ax, c); ay = Arrays.copyOf(ay, c); az = Arrays.copyOf(az, c);
            bx = Arrays.copyOf(bx, c); by = Arrays.copyOf(by, c); bz = Arrays.copyOf(bz, c);
            rh = Arrays.copyOf(rh, c); rv = Arrays.copyOf(rv, c);
            zone = Arrays.copyOf(zone, c);
            river = Arrays.copyOf(river, c);
        }

        KarstNetwork build(int cellSize, int originX, int originZ, int gx, int gz,
                           int wtOX, int wtOZ, int wtStep, int wtNX, int wtNZ, short[] wtY) {
            return new KarstNetwork(n,
                    Arrays.copyOf(ax, n), Arrays.copyOf(ay, n), Arrays.copyOf(az, n),
                    Arrays.copyOf(bx, n), Arrays.copyOf(by, n), Arrays.copyOf(bz, n),
                    Arrays.copyOf(rh, n), Arrays.copyOf(rv, n),
                    Arrays.copyOf(zone, n), Arrays.copyOf(river, n),
                    cellSize, originX, originZ, gx, gz,
                    wtOX, wtOZ, wtStep, wtNX, wtNZ, wtY);
        }
    }

    private static final class LongHeap {
        private long[] a;
        private int size;

        LongHeap(int cap) {
            a = new long[Math.max(16, cap)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(float cost, int index) {
            long key = (((long) Float.floatToRawIntBits(cost)) << 32) | (index & 0xFFFFFFFFL);
            if (size == a.length) a = Arrays.copyOf(a, size * 2);
            int i = size++;
            a[i] = key;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (a[p] <= a[i]) break;
                long t = a[p]; a[p] = a[i]; a[i] = t;
                i = p;
            }
        }

        long pop() {
            long top = a[0];
            a[0] = a[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1, r = l + 1, m = i;
                if (l < size && a[l] < a[m]) m = l;
                if (r < size && a[r] < a[m]) m = r;
                if (m == i) break;
                long t = a[m]; a[m] = a[i]; a[i] = t;
                i = m;
            }
            return top;
        }
    }
}
