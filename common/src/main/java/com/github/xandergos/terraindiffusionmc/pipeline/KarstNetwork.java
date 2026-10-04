package com.github.xandergos.terraindiffusionmc.pipeline;

public final class KarstNetwork {

    public static final byte ZONE_PHREATIC = 0;
    public static final byte ZONE_VADOSE = 1;
    public static final byte ZONE_KEYHOLE = 2;
    public static final byte ZONE_DOLINE = 3;
    // A river reach whose water level runs below the ground: the stream goes underground
    public static final byte ZONE_RIVER = 4;
    // Swallow hole or resurgence, joining an underground reach up toward the surface
    public static final byte ZONE_RIVER_SHAFT = 5;

    public static final float FAR = 4096f;

    private static final float FLOOR_CUT =
            Float.parseFloat(System.getProperty("terradiff.karstFloorCut", "0.35"));

    public static final KarstNetwork EMPTY = new KarstNetwork();

    final int count;
    final float[] ax, ay, az;
    final float[] bx, by, bz;
    final float[] rh;
    final float[] rv;
    final byte[] zone;
    final float[] riverY;

    final int cellSize;
    final int originX, originZ;
    final int nx, nz;
    final int[] cellStart;
    final int[] cellItems;
    final float[] cellYMin, cellYMax;

    final int wtOriginX, wtOriginZ, wtStep, wtNX, wtNZ;
    final short[] wtY;

    // Endpoint bounds per segment, for skipping segments that cannot be nearest
    final float[] loX, hiX, loY, hiY, loZ, hiZ;

    private KarstNetwork() {
        this.count = 0;
        this.ax = this.ay = this.az = this.bx = this.by = this.bz = new float[0];
        this.loX = this.hiX = this.loY = this.hiY = this.loZ = this.hiZ = new float[0];
        this.rh = this.rv = new float[0];
        this.zone = new byte[0];
        this.riverY = new float[0];
        this.cellSize = 1;
        this.originX = this.originZ = 0;
        this.nx = this.nz = 0;
        this.cellStart = new int[1];
        this.cellItems = new int[0];
        this.cellYMin = this.cellYMax = new float[0];
        this.wtOriginX = this.wtOriginZ = 0;
        this.wtStep = 1;
        this.wtNX = this.wtNZ = 0;
        this.wtY = new short[0];
    }

    KarstNetwork(int count, float[] ax, float[] ay, float[] az,
                 float[] bx, float[] by, float[] bz,
                 float[] rh, float[] rv, byte[] zone, float[] riverY,
                 int cellSize, int originX, int originZ, int nx, int nz,
                 int wtOriginX, int wtOriginZ, int wtStep, int wtNX, int wtNZ, short[] wtY) {
        this.count = count;
        this.ax = ax; this.ay = ay; this.az = az;
        this.bx = bx; this.by = by; this.bz = bz;
        this.rh = rh; this.rv = rv;
        this.zone = zone;
        this.riverY = riverY;
        this.cellSize = cellSize;
        this.originX = originX; this.originZ = originZ;
        this.nx = nx; this.nz = nz;
        this.wtOriginX = wtOriginX; this.wtOriginZ = wtOriginZ;
        this.wtStep = wtStep; this.wtNX = wtNX; this.wtNZ = wtNZ;
        this.wtY = wtY;

        this.loX = new float[count]; this.hiX = new float[count];
        this.loY = new float[count]; this.hiY = new float[count];
        this.loZ = new float[count]; this.hiZ = new float[count];
        for (int s = 0; s < count; s++) {
            loX[s] = Math.min(ax[s], bx[s]); hiX[s] = Math.max(ax[s], bx[s]);
            loY[s] = Math.min(ay[s], by[s]); hiY[s] = Math.max(ay[s], by[s]);
            loZ[s] = Math.min(az[s], bz[s]); hiZ[s] = Math.max(az[s], bz[s]);
        }

        int cells = nx * nz;
        int[] counts = new int[cells + 1];
        for (int s = 0; s < count; s++) {
            int c0 = cellX(Math.min(ax[s], bx[s]) - rh[s] - 1f);
            int c1 = cellX(Math.max(ax[s], bx[s]) + rh[s] + 1f);
            int d0 = cellZ(Math.min(az[s], bz[s]) - rh[s] - 1f);
            int d1 = cellZ(Math.max(az[s], bz[s]) + rh[s] + 1f);
            for (int c = c0; c <= c1; c++) {
                for (int d = d0; d <= d1; d++) {
                    if (c < 0 || d < 0 || c >= nx || d >= nz) continue;
                    counts[d * nx + c + 1]++;
                }
            }
        }
        for (int i = 0; i < cells; i++) counts[i + 1] += counts[i];
        this.cellStart = counts;
        this.cellItems = new int[counts[cells]];
        int[] cursor = new int[cells];
        this.cellYMin = new float[cells];
        this.cellYMax = new float[cells];
        java.util.Arrays.fill(cellYMin, Float.MAX_VALUE);
        java.util.Arrays.fill(cellYMax, -Float.MAX_VALUE);

        for (int s = 0; s < count; s++) {
            float pad = rv[s] + 1f;
            float lo = Math.min(ay[s], by[s]) - pad;
            float hi = Math.max(ay[s], by[s]) + pad;
            int c0 = cellX(Math.min(ax[s], bx[s]) - rh[s] - 1f);
            int c1 = cellX(Math.max(ax[s], bx[s]) + rh[s] + 1f);
            int d0 = cellZ(Math.min(az[s], bz[s]) - rh[s] - 1f);
            int d1 = cellZ(Math.max(az[s], bz[s]) + rh[s] + 1f);
            for (int c = c0; c <= c1; c++) {
                for (int d = d0; d <= d1; d++) {
                    if (c < 0 || d < 0 || c >= nx || d >= nz) continue;
                    int cell = d * nx + c;
                    cellItems[cellStart[cell] + cursor[cell]++] = s;
                    if (lo < cellYMin[cell]) cellYMin[cell] = lo;
                    if (hi > cellYMax[cell]) cellYMax[cell] = hi;
                }
            }
        }
    }

    private int cellX(float x) {
        return Math.floorDiv((int) Math.floor(x) - originX, cellSize);
    }

    private int cellZ(float z) {
        return Math.floorDiv((int) Math.floor(z) - originZ, cellSize);
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int segmentCount() {
        return count;
    }

    // Signed distance to the nearest conduit wall in blocks; negative inside a conduit
    public float density(float x, float y, float z) {
        return density(x, y, z, false, FAR);
    }

    // Exact where the distance is below limit; otherwise some value at or above limit
    public float density(float x, float y, float z, float limit) {
        return density(x, y, z, false, limit);
    }

    // Distance considering only doline shafts
    public float dolineDensity(float x, float y, float z) {
        return density(x, y, z, true, FAR);
    }

    // Doline distance, exact below limit
    public float dolineDensity(float x, float y, float z, float limit) {
        return density(x, y, z, true, limit);
    }

    // Allowance for float rounding when ruling a segment out, growing with coordinate size
    private static float slack(float x, float y, float z) {
        return 0.02f + 4.0e-6f * (Math.abs(x) + Math.abs(y) + Math.abs(z));
    }

    // Lower bound on a segment's signed distance, from its endpoint bounds
    private float lowerBound(int s, float x, float y, float z, float rhs, float rvs) {
        float out = Math.max(Math.max(loX[s] - x, x - hiX[s]), Math.max(loZ[s] - z, z - hiZ[s])) / rhs;
        float up = Math.max(loY[s] - y, y - hiY[s]) / rvs;
        return (Math.max(out, up) - 1f) * (rhs < rvs ? rhs : rvs);
    }

    // Distance considering only the river conduit itself
    public float riverDensity(float x, float y, float z) {
        return riverDensity(x, y, z, FAR);
    }

    // River conduit distance, exact below limit
    public float riverDensity(float x, float y, float z, float limit) {
        if (count == 0) return FAR;
        int c = cellX(x);
        int d = cellZ(z);
        if (c < 0 || d < 0 || c >= nx || d >= nz) return FAR;
        int cell = d * nx + c;
        int from = cellStart[cell], to = cellStart[cell + 1];
        float slack = slack(x, y, z);
        float best = FAR;
        for (int k = from; k < to; k++) {
            int s = cellItems[k];
            if (zone[s] != ZONE_RIVER) continue;
            if (rh[s] > 0.01f && rv[s] > 0.01f
                    && lowerBound(s, x, y, z, rh[s], rv[s]) > Math.min(best, limit) + slack) continue;
            float ex = bx[s] - ax[s], ey = by[s] - ay[s], ez = bz[s] - az[s];
            float len2 = ex * ex + ey * ey + ez * ez;
            float t = 0f;
            if (len2 > 1.0e-6f) {
                t = ((x - ax[s]) * ex + (y - ay[s]) * ey + (z - az[s]) * ez) / len2;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
            }
            float qx = ax[s] + ex * t, qy = ay[s] + ey * t, qz = az[s] + ez * t;
            float dx = x - qx, dy = y - qy, dz = z - qz;
            float rhs = rh[s], rvs = rv[s];
            if (rhs <= 0.01f || rvs <= 0.01f) continue;
            float hh = (float) Math.sqrt(dx * dx + dz * dz) / rhs;
            float vv = (dy < 0f ? -dy : dy) / rvs;
            float n = (float) Math.sqrt(hh * hh + vv * vv);
            float scale = rhs < rvs ? rhs : rvs;
            float sd = (n - 1f) * scale;
            if (sd < best) best = sd;
        }
        return best;
    }

    private float density(float x, float y, float z, boolean dolinesOnly, float limit) {
        if (count == 0) return FAR;
        int c = cellX(x);
        int d = cellZ(z);
        if (c < 0 || d < 0 || c >= nx || d >= nz) return FAR;
        int cell = d * nx + c;
        int from = cellStart[cell], to = cellStart[cell + 1];
        if (from == to) return FAR;
        if (y < cellYMin[cell] || y > cellYMax[cell]) return FAR;

        float slack = slack(x, y, z);
        float best = FAR;
        for (int k = from; k < to; k++) {
            int s = cellItems[k];
            if (dolinesOnly && zone[s] != ZONE_DOLINE) continue;
            if (rh[s] > 0.01f && rv[s] > 0.01f
                    && lowerBound(s, x, y, z, rh[s], rv[s]) > Math.min(best, limit) + slack) continue;
            float ex = bx[s] - ax[s], ey = by[s] - ay[s], ez = bz[s] - az[s];
            float len2 = ex * ex + ey * ey + ez * ez;
            float t = 0f;
            if (len2 > 1.0e-6f) {
                t = ((x - ax[s]) * ex + (y - ay[s]) * ey + (z - az[s]) * ez) / len2;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
            }
            float qx = ax[s] + ex * t, qy = ay[s] + ey * t, qz = az[s] + ez * t;
            float dx = x - qx, dy = y - qy, dz = z - qz;
            float rhs = rh[s], rvs = rv[s];
            if (rhs <= 0.01f || rvs <= 0.01f) continue;
            if (y < qy - rvs * FLOOR_CUT) continue;
            float hh = (float) Math.sqrt(dx * dx + dz * dz) / rhs;
            float vv = (dy < 0f ? -dy : dy) / rvs;
            float n = (float) Math.sqrt(hh * hh + vv * vv);
            float scale = rhs < rvs ? rhs : rvs;
            float sd = (n - 1f) * scale;
            if (sd < best) best = sd;
        }
        return best;
    }

    // Surface height of the underground river at this position, or Float#NEGATIVE_INFINITY where none
    public float riverWaterY(float x, float y, float z) {
        if (count == 0) return Float.NEGATIVE_INFINITY;
        int c = cellX(x);
        int d = cellZ(z);
        if (c < 0 || d < 0 || c >= nx || d >= nz) return Float.NEGATIVE_INFINITY;
        int cell = d * nx + c;
        int from = cellStart[cell], to = cellStart[cell + 1];
        if (from == to) return Float.NEGATIVE_INFINITY;

        float best = Float.NEGATIVE_INFINITY;
        float bestSd = Float.MAX_VALUE;
        for (int k = from; k < to; k++) {
            int s = cellItems[k];
            if (zone[s] != ZONE_RIVER) continue;
            float ex = bx[s] - ax[s], ey = by[s] - ay[s], ez = bz[s] - az[s];
            float len2 = ex * ex + ey * ey + ez * ez;
            float t = 0f;
            if (len2 > 1.0e-6f) {
                t = ((x - ax[s]) * ex + (y - ay[s]) * ey + (z - az[s]) * ez) / len2;
                if (t < 0f) t = 0f;
                else if (t > 1f) t = 1f;
            }
            float qx = ax[s] + ex * t, qz = az[s] + ez * t;
            float dx = x - qx, dz = z - qz;
            float sd = dx * dx + dz * dz;
            if (sd < bestSd) {
                bestSd = sd;
                best = (ay[s] + ey * t) - riverY[s];
            }
        }
        return best;
    }

    // Water table height in blocks, bilinearly sampled
    public float waterTableY(float x, float z) {
        if (wtNX == 0) return Float.NEGATIVE_INFINITY;
        float fx = (x - wtOriginX) / (float) wtStep;
        float fz = (z - wtOriginZ) / (float) wtStep;
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = fx - x0, tz = fz - z0;
        int x1 = x0 + 1, z1 = z0 + 1;
        x0 = clamp(x0, 0, wtNX - 1); x1 = clamp(x1, 0, wtNX - 1);
        z0 = clamp(z0, 0, wtNZ - 1); z1 = clamp(z1, 0, wtNZ - 1);
        float a = wtY[z0 * wtNX + x0], b = wtY[z0 * wtNX + x1];
        float c = wtY[z1 * wtNX + x0], e = wtY[z1 * wtNX + x1];
        return (a + (b - a) * tx) + ((c + (e - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    public String stats() {
        if (count == 0) return "karst: empty";
        int[] byZone = new int[4];
        float rMin = Float.MAX_VALUE, rMax = 0f, rSum = 0f;
        float yMin = Float.MAX_VALUE, yMax = -Float.MAX_VALUE;
        double len = 0;
        for (int s = 0; s < count; s++) {
            byZone[zone[s]]++;
            float r = rh[s];
            if (r < rMin) rMin = r;
            if (r > rMax) rMax = r;
            rSum += r;
            float lo = Math.min(ay[s], by[s]), hi = Math.max(ay[s], by[s]);
            if (lo < yMin) yMin = lo;
            if (hi > yMax) yMax = hi;
            float dx = bx[s] - ax[s], dy = by[s] - ay[s], dz = bz[s] - az[s];
            len += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return String.format(
                "karst: %d segments, %.0f blocks total, y %.0f..%.0f, rh %.2f/%.2f/%.2f min/mean/max, "
                        + "phreatic %d vadose %d keyhole %d doline %d, index %dx%d cell %d",
                count, len, yMin, yMax, rMin, rSum / count, rMax,
                byZone[0], byZone[1], byZone[2], byZone[3], nx, nz, cellSize);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
