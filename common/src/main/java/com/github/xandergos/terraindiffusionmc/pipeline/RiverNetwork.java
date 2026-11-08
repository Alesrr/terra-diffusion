package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RiverNetwork {

    public static final int LC = 8;
    public static final float CELL_M = 30f * LC;
    public static final float NATIVE_CELLS_PER_CELL = LC * LC;

    public static final class Params {
        public float initiation = 225f;
        public float slopeCap = 0.5f;
        public float slopeExponent = 2f;
        public float minSourceElevM = 200f;
        public float seedMinNative = 200000f;
        public float riverThresholdNative = 600f;
        public float dischargeAreaExponent = 0.8f;
        public float bankfullWidthM = 58f;
        public float bankfullDepthM = 9f;
        public float widthQExponent = 0.5f;
        public float depthQExponent = 0.4f;
        public float maxWidthM = 340f;
        public float maxDepthM = 45f;
        public float orderWidthGain = 0.06f;
        public float lakeMinDepthM = 1.5f;
        public int lakeMinCells = 4;
        public float fillEpsilonM = 1.0e-3f;
    }

    public final int n;
    public final int originI, originJ;
    public final float[] elev;
    public final float[] filled;
    public final int[] parent;
    public final float[] acc;
    public final boolean[] channel;
    public final byte[] order;
    public final float[] widthM;
    public final float[] depthM;
    public final int[] lakeBody;
    public final float[] lakeLevel;
    public final List<int[]> reaches;

    private RiverNetwork(int n, int originI, int originJ, float[] elev, float[] filled, int[] parent, float[] acc,
                         boolean[] channel, byte[] order, float[] widthM, float[] depthM,
                         int[] lakeBody, float[] lakeLevel, List<int[]> reaches) {
        this.n = n;
        this.originI = originI;
        this.originJ = originJ;
        this.elev = elev;
        this.filled = filled;
        this.parent = parent;
        this.acc = acc;
        this.channel = channel;
        this.order = order;
        this.widthM = widthM;
        this.depthM = depthM;
        this.lakeBody = lakeBody;
        this.lakeLevel = lakeLevel;
        this.reaches = reaches;
    }

    public float nativeI(int cell) {
        return (originI + cell / n + 0.5f) * LC;
    }

    public float nativeJ(int cell) {
        return (originJ + cell % n + 0.5f) * LC;
    }

    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    public static RiverNetwork solve(float[] elev, int n, int originI, int originJ, Params p) {
        int cells = n * n;
        float[] filled = elev.clone();
        int[] parent = new int[cells];
        Arrays.fill(parent, -1);
        int[] popOrder = new int[cells];
        boolean[] closed = new boolean[cells];
        LongHeap open = new LongHeap(cells);

        for (int i = 0; i < cells; i++) {
            int r = i / n, c = i % n;
            if (elev[i] < 0f || r == 0 || c == 0 || r == n - 1 || c == n - 1) {
                closed[i] = true;
                open.push(pack(filled[i], i));
            }
        }
        int popped = 0;
        while (!open.isEmpty()) {
            int i = (int) open.pop();
            popOrder[popped++] = i;
            int r = i / n, c = i % n;
            for (int k = 0; k < 8; k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (rr < 0 || cc < 0 || rr >= n || cc >= n) continue;
                int j = rr * n + cc;
                if (closed[j]) continue;
                closed[j] = true;
                parent[j] = i;
                if (filled[j] <= filled[i]) {
                    filled[j] = filled[i] + p.fillEpsilonM;
                }
                open.push(pack(filled[j], j));
            }
        }

        float[] acc = new float[cells];
        Arrays.fill(acc, NATIVE_CELLS_PER_CELL);
        int[] children = new int[cells];
        for (int k = popped - 1; k >= 0; k--) {
            int i = popOrder[k];
            int d = parent[i];
            if (d >= 0) {
                acc[d] += acc[i];
            }
        }

        int[] lakeBody = new int[cells];
        Arrays.fill(lakeBody, -1);
        List<Float> levels = new ArrayList<>();
        int[] stack = new int[cells];
        for (int seed = 0; seed < cells; seed++) {
            if (lakeBody[seed] != -1 || elev[seed] < 0f || filled[seed] - elev[seed] < p.lakeMinDepthM) continue;
            int body = levels.size();
            int top = 0, size = 0;
            stack[top++] = seed;
            lakeBody[seed] = body;
            float level = Float.MAX_VALUE;
            List<Integer> members = new ArrayList<>();
            while (top > 0) {
                int i = stack[--top];
                members.add(i);
                size++;
                level = Math.min(level, filled[i]);
                int r = i / n, c = i % n;
                for (int k = 0; k < 8; k++) {
                    int rr = r + DR[k], cc = c + DC[k];
                    if (rr < 0 || cc < 0 || rr >= n || cc >= n) continue;
                    int j = rr * n + cc;
                    if (lakeBody[j] != -1 || elev[j] < 0f || filled[j] - elev[j] <= 0.05f) continue;
                    lakeBody[j] = body;
                    stack[top++] = j;
                }
            }
            if (size < p.lakeMinCells) {
                for (int i : members) lakeBody[i] = -2;
                levels.add(Float.NaN);
            } else {
                levels.add(level);
            }
        }
        float[] lakeLevel = new float[levels.size()];
        for (int k = 0; k < lakeLevel.length; k++) lakeLevel[k] = levels.get(k);
        for (int i = 0; i < cells; i++) if (lakeBody[i] == -2) lakeBody[i] = -1;

        boolean[] channel = new boolean[cells];
        for (int i = 0; i < cells; i++) {
            if (elev[i] < 0f || parent[i] < 0) continue;
            int d = parent[i];
            float dist = (i / n != d / n && i % n != d % n) ? CELL_M * 1.41421356f : CELL_M;
            float slope = Math.max(1.0e-6f, (elev[i] - elev[d]) / dist);
            float gate = Math.min(slope, p.slopeCap);
            boolean continuation = acc[i] >= p.seedMinNative;
            if ((elev[i] >= p.minSourceElevM || continuation)
                    && acc[i] * Math.pow(gate, p.slopeExponent) >= p.initiation) {
                channel[i] = true;
            }
        }
        for (int k = popped - 1; k >= 0; k--) {
            int i = popOrder[k];
            if (!channel[i]) continue;
            int d = parent[i];
            if (d >= 0 && elev[d] >= 0f) channel[d] = true;
        }

        byte[] order = new byte[cells];
        for (int k = popped - 1; k >= 0; k--) {
            int i = popOrder[k];
            if (!channel[i]) continue;
            if (order[i] == 0) order[i] = 1;
            int d = parent[i];
            if (d < 0 || !channel[d]) continue;
            children[d]++;
            if (order[i] > order[d]) {
                order[d] = order[i];
            } else if (order[i] == order[d] && order[d] < 127) {
                order[d] = (byte) (order[i] + 1);
            }
        }

        float[] widthM = new float[cells];
        float[] depthM = new float[cells];
        for (int i = 0; i < cells; i++) {
            if (!channel[i]) continue;
            double area = Math.max(1.0, acc[i] / p.riverThresholdNative);
            double q = Math.pow(area, p.dischargeAreaExponent);
            float w = p.bankfullWidthM * (float) Math.pow(q, p.widthQExponent);
            float dep = p.bankfullDepthM * (float) Math.pow(q, p.depthQExponent);
            w *= 1f + p.orderWidthGain * (order[i] - 1);
            widthM[i] = p.maxWidthM * w / (p.maxWidthM + w);
            depthM[i] = p.maxDepthM * dep / (p.maxDepthM + dep);
        }

        List<int[]> reaches = new ArrayList<>();
        int[] buf = new int[cells];
        for (int k = 0; k < popped; k++) {
            int start = popOrder[popped - 1 - k];
            if (!channel[start]) continue;
            boolean head = children[start] == 0 || children[start] >= 2;
            if (!head) continue;
            if (children[start] >= 2) continue;
            reaches.add(walk(start, parent, channel, children, buf));
        }
        for (int i = 0; i < cells; i++) {
            if (channel[i] && children[i] >= 2) {
                int d = parent[i];
                if (d >= 0 && channel[d]) {
                    int[] tail = walk(d, parent, channel, children, buf);
                    int[] r = new int[tail.length + 1];
                    r[0] = i;
                    System.arraycopy(tail, 0, r, 1, tail.length);
                    reaches.add(r);
                }
            }
        }

        return new RiverNetwork(n, originI, originJ, elev, filled, parent, acc, channel, order,
                widthM, depthM, lakeBody, lakeLevel, reaches);
    }

    private static int[] walk(int start, int[] parent, boolean[] channel, int[] children, int[] buf) {
        int len = 0;
        int at = start;
        buf[len++] = at;
        while (true) {
            int d = parent[at];
            if (d < 0 || !channel[d]) break;
            buf[len++] = d;
            if (children[d] >= 2) break;
            at = d;
        }
        return Arrays.copyOf(buf, len);
    }

    private static long pack(float key, int index) {
        int bits = Float.floatToIntBits(key);
        bits = bits >= 0 ? bits ^ 0x80000000 : ~bits;
        return ((bits & 0xFFFFFFFFL) << 32) | (index & 0xFFFFFFFFL);
    }

    private static final class LongHeap {
        private long[] a;
        private int size;

        LongHeap(int capacity) {
            a = new long[Math.max(16, capacity)];
        }

        boolean isEmpty() {
            return size == 0;
        }

        void push(long v) {
            if (size == a.length) a = Arrays.copyOf(a, size * 2);
            int i = size++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (a[p] <= v) break;
                a[i] = a[p];
                i = p;
            }
            a[i] = v;
        }

        long pop() {
            long top = a[0];
            long last = a[--size];
            int i = 0;
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int m = l + 1 < size && a[l + 1] < a[l] ? l + 1 : l;
                if (a[m] >= last) break;
                a[i] = a[m];
                i = m;
            }
            a[i] = last;
            return top & 0xFFFFFFFFL;
        }
    }
}
