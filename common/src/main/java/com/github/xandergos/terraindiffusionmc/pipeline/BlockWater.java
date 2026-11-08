package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.world.HeightConverter;

import java.util.Arrays;

public final class BlockWater {

    public static final int MAX_RAMP = 7;
    public static final int CURTAIN = 16;

    private static final int SEA = HeightConverter.SEA_LEVEL;
    private static final int NONE = Integer.MIN_VALUE;
    private static final int CORNER_PASSES = 2;
    private static final int BANK_REACH = 3;
    private static final int THIN_RAMP = 3;
    private static final int[] DR = {-1, 1, 0, 0};
    private static final int[] DC = {0, 0, -1, 1};

    private BlockWater() {
    }

    static byte[] shape(float[] carved, float[] water, float[] natural, float[] frac, int H, int W, float blockM) {
        int n = H * W;
        int[] g = new int[n];
        int[] s = new int[n];
        boolean[] sea = new boolean[n];
        for (int i = 0; i < n; i++) {
            g[i] = blockY(carved[i]);
            if (water[i] > WaterNetwork.NO_WATER) {
                int top = blockY(water[i]);
                s[i] = top > g[i] ? top : NONE;
            } else if (g[i] < SEA) {
                s[i] = SEA;
                sea[i] = true;
            } else {
                s[i] = NONE;
            }
        }

        int[] s0 = s.clone();
        int[] next = s.clone();
        for (int pass = 0; pass < CORNER_PASSES; pass++) {
            boolean changed = false;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int i = r * W + c;
                    if (s[i] == NONE) continue;
                    int first = NONE, second = NONE;
                    for (int k = 0; k < 4; k++) {
                        int rr = r + DR[k], cc = c + DC[k];
                        if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                        int j = rr * W + cc;
                        if (s[j] == NONE || s[j] <= s[i]) continue;
                        if (s[j] > first) {
                            second = first;
                            first = s[j];
                        } else if (s[j] > second) {
                            second = s[j];
                        }
                    }
                    if (second != NONE) {
                        next[i] = second;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
            System.arraycopy(next, 0, s, 0, n);
        }
        for (int i = 0; i < n; i++) {
            if (s[i] == NONE) continue;
            if (sea[i] ? s[i] > SEA : s[i] > blockY(water[i])) {
                water[i] = meters(s[i], blockM);
                sea[i] = false;
            }
        }

        byte[] flow = new byte[n];
        int[] level = new int[n];
        Arrays.fill(level, Integer.MAX_VALUE);
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int i = r * W + c;
                    if (s[i] == NONE) continue;
                    int maxUp = NONE;
                    for (int k = 0; k < 4; k++) {
                        int rr = r + DR[k], cc = c + DC[k];
                        if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                        int j = rr * W + cc;
                        if (s[j] != NONE && s[j] > s[i] && s[j] > maxUp) maxUp = s[j];
                    }
                    if (maxUp == NONE) continue;
                    boolean curtain = maxUp >= s[i] + 2;
                    if (curtain != (pass == 0)) continue;
                    level[i] = curtain ? 0 : 1;
                    if (curtain) flow[i] = (byte) (CURTAIN + Math.min(100, maxUp - s[i]));
                    queue[tail++] = i;
                }
            }
        }
        while (head < tail) {
            int i = queue[head++];
            int l = level[i] + 1;
            if (l > MAX_RAMP) continue;
            int r = i / W, c = i % W;
            for (int k = 0; k < 4; k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                int j = rr * W + cc;
                if (s[j] != s[i] || level[j] <= l) continue;
                level[j] = l;
                queue[tail++] = j;
            }
        }
        for (int i = 0; i < n; i++) {
            if (flow[i] != 0 || level[i] < 1 || level[i] > MAX_RAMP) continue;
            int l = level[i];
            if (l > 1 && frac != null && s[i] == s0[i] && frac[i] >= 0f) {
                l = Math.max(l, Math.round(9f - 9f * frac[i]));
            }
            if (l <= MAX_RAMP) flow[i] = (byte) l;
        }

        int[] need = new int[n];
        Arrays.fill(need, NONE);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (s[i] == NONE) continue;
                int source = sea[i] ? NONE : frac != null && frac[i] < 0f ? s[i] : s[i] + 1;
                int lip = flow[i] == 0 ? NONE : flow[i] > THIN_RAMP && flow[i] <= MAX_RAMP ? s[i] + 1 : s[i] + 2;
                int want = Math.max(source, lip);
                if (want == NONE) continue;
                for (int k = 0; k < 4; k++) {
                    int rr = r + DR[k], cc = c + DC[k];
                    if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                    int j = rr * W + cc;
                    if (s[j] == NONE && want > need[j]) need[j] = want;
                }
            }
        }
        boolean[] bank = new boolean[n];
        for (int j = 0; j < n; j++) bank[j] = need[j] != NONE;
        for (int ring = 0; ring < BANK_REACH; ring++) {
            int[] wider = need.clone();
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int i = r * W + c;
                    if (need[i] == NONE || need[i] < SEA || g[i] >= need[i] || blockY(natural[i]) < need[i]) continue;
                    for (int k = 0; k < 4; k++) {
                        int rr = r + DR[k], cc = c + DC[k];
                        if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                        int j = rr * W + cc;
                        if (s[j] == NONE && !bank[j] && need[i] > wider[j]) wider[j] = need[i];
                    }
                }
            }
            need = wider;
        }
        for (int j = 0; j < n; j++) {
            if (need[j] != NONE && need[j] >= SEA && g[j] < need[j]) {
                carved[j] = Math.max(carved[j], Math.min(meters(need[j], blockM), natural[j]));
            }
        }
        return flow;
    }

    private static int blockY(float m) {
        return HeightConverter.convertToMinecraftHeight((short) Math.max(-32768, Math.min(32767, (int) Math.floor(m))));
    }

    private static float meters(int y, float blockM) {
        return y >= SEA ? (float) Math.ceil((y - SEA) * (double) blockM) : -(float) Math.ceil((SEA - y) * (double) blockM);
    }
}
