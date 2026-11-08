package com.github.xandergos.terraindiffusionmc.pipeline;

final class LakeShore {
    private static final float REACH_BLOCKS = 64f;
    private static final double FAR = 1.0e12;

    private final int ci0, cj0, cols, cell;
    private final float[] level;
    private final float[] reach;

    private LakeShore(int ci0, int cj0, int cols, int cell, float[] level, float[] reach) {
        this.ci0 = ci0;
        this.cj0 = cj0;
        this.cols = cols;
        this.cell = cell;
        this.level = level;
        this.reach = reach;
    }

    static LakeShore around(WorldPipeline pipeline, int i1, int j1, int H, int W, int cell, int scale, float blockM) {
        int cap = (int) Math.ceil(REACH_BLOCKS / scale);
        int ti0 = Math.floorDiv(i1, cell), tj0 = Math.floorDiv(j1, cell);
        int ti1 = Math.floorDiv(i1 + H - 1, cell), tj1 = Math.floorDiv(j1 + W - 1, cell);
        int pad = cap + 2;
        int ci0 = ti0 - pad, cj0 = tj0 - pad;
        int rows = ti1 - ti0 + 1 + 2 * pad, cols = tj1 - tj0 + 1 + 2 * pad;
        float[] level = RiverHydrology.lakeWindow(pipeline, ci0, cj0, rows, cols, ti0, tj0, ti1, tj1, blockM);
        boolean near = false;
        for (int r = pad - 1; r <= rows - pad && !near; r++) {
            for (int c = pad - 1; c <= cols - pad; c++) {
                if (!Float.isNaN(level[r * cols + c])) {
                    near = true;
                    break;
                }
            }
        }
        if (!near) return null;
        int n = rows * cols;
        double[] in = new double[n], out = new double[n];
        for (int i = 0; i < n; i++) {
            boolean lake = !Float.isNaN(level[i]);
            in[i] = lake ? FAR : 0.0;
            out[i] = lake ? 0.0 : FAR;
        }
        squaredDistance(in, rows, cols);
        squaredDistance(out, rows, cols);
        float[] reach = new float[n];
        for (int i = 0; i < n; i++) {
            reach[i] = Float.isNaN(level[i])
                    ? -(float) Math.min(cap, Math.sqrt(out[i]) - 0.5)
                    : (float) Math.min(cap, Math.sqrt(in[i]) - 0.5);
        }
        return new LakeShore(ci0, cj0, cols, cell, level, reach);
    }

    boolean sample(int gi, int gj, float[] out) {
        float u = (gi + 0.5f) / cell - 0.5f - ci0, w = (gj + 0.5f) / cell - 0.5f - cj0;
        int r0 = (int) Math.floor(u), c0 = (int) Math.floor(w);
        float fu = u - r0, fw = w - c0;
        int a = r0 * cols + c0, b = a + 1, c = a + cols, d = c + 1;
        float wa = (1f - fu) * (1f - fw), wb = (1f - fu) * fw, wc = fu * (1f - fw), wd = fu * fw;
        float lv = Float.NaN, best = -1f;
        if (!Float.isNaN(level[a]) && wa > best) { best = wa; lv = level[a]; }
        if (!Float.isNaN(level[b]) && wb > best) { best = wb; lv = level[b]; }
        if (!Float.isNaN(level[c]) && wc > best) { best = wc; lv = level[c]; }
        if (!Float.isNaN(level[d]) && wd > best) { lv = level[d]; }
        if (Float.isNaN(lv)) return false;
        out[0] = lv;
        out[1] = wa * reachFor(a, lv) + wb * reachFor(b, lv) + wc * reachFor(c, lv) + wd * reachFor(d, lv);
        return true;
    }

    private float reachFor(int at, float lv) {
        float l = level[at];
        return Float.isNaN(l) || l == lv ? reach[at] : -0.5f;
    }

    private static void squaredDistance(double[] grid, int rows, int cols) {
        int len = Math.max(rows, cols);
        double[] f = new double[len], d = new double[len], z = new double[len + 1];
        int[] v = new int[len];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) f[r] = grid[r * cols + c];
            lowerEnvelope(f, rows, d, v, z);
            for (int r = 0; r < rows; r++) grid[r * cols + c] = d[r];
        }
        for (int r = 0; r < rows; r++) {
            System.arraycopy(grid, r * cols, f, 0, cols);
            lowerEnvelope(f, cols, d, v, z);
            System.arraycopy(d, 0, grid, r * cols, cols);
        }
    }

    private static void lowerEnvelope(double[] f, int len, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < len; q++) {
            double s = cross(f, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = cross(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < len; q++) {
            while (z[k + 1] < q) k++;
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }

    private static double cross(double[] f, int q, int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * (q - p));
    }
}
