package com.github.xandergos.terraindiffusionmc.pipeline;

// Terralith's underground climate table, matched with vanilla's fitness metric
public final class CaveBiomes {

    public static final int NONE = -1;

    public static final float MIN_DEPTH = 0.15f;

    // Surface height above which cave depth stops following the surface
    private static final int SURFACE_CAP = 128;

    private static final float DEPTH_SPAN = 128f;

    // How far outside its climate box an entry may still be chosen
    private static final long TOLERANCE = (long) (0.2f * 10000.0F);

    public static final String[] IDS = {
            "minecraft:lush_caves",
            "minecraft:dripstone_caves",
            "minecraft:deep_dark",
            "terralith:cave/andesite_caves",
            "terralith:cave/granite_caves",
            "terralith:cave/diorite_caves",
            "terralith:cave/infested_caves",
            "terralith:cave/fungal_caves",
            "terralith:cave/thermal_caves",
            "terralith:cave/underground_jungle",
            "terralith:cave/deep_caves",
            "terralith:cave/mantle_caves",
            "terralith:cave/tuff_caves",
            "terralith:cave/frostfire_caves",
    };

    private static final float[][] P = {
            // t0 t1 h0 h1 c0 c1 e0 e1 w0 w1 d0 d1 off
            { 0.5614f, 0.9123f, 0.2727f, 1f, -1f, 1f, -1f, 1f, -1f, 0.5f, 0.22f, 0.935f, 0.05f },
            { -1f, 1f, -1f, 1f, 0.893f, 1f, -1f, 1f, -1f, 1f, 0.23f, 0.95f, 0.03f },
            { -1f, 1f, -1f, 1f, -1f, 1f, -2f, -0.475f,-1f, 1f, 1.05f, 2f, 0f },
            { 0.4444f, 1f, -1f, 1f, 0.3f, 0.6f, -0.25f, 1f, -0.75f, -0.45f, 0.15f, 0.6f, 0.02f },
            { 0.3f, 1f, -1f, 1f, 0.25f, 0.625f, -1f, 0.25f, -0.45f, 0.15f, 0.15f, 0.6f, 0.02f },
            { -0.6082f, -0.0234f, -1f, 1f, 0.3f, 0.893f, -0.5f, 0.5f, -1f, -0.25f, 0.15f, 0.6f, 0.02f },
            { -1f, 0.0936f, 0f, 0.2f, 0.3f, 1f, -0.8f, 0.5f, 0f, 1f, 0.225f, 0.9f, 0.02f },
            { -0.3f, 1f, -1f, -0.7f, 0.15f, 0.725f,-0.6f, 0.7f, -1f, 1f, 0.225f,0.9f, 0.065f },
            { -1f, 1f, 0f, 0.2f, 0.3f, 1f, -0.8f, 0.3f, -0.4f, 0f, 0.225f, 0.9f, 0.02f },
            {  0.3f, 1f, 0.6f, 1f, -1f, 1f, -1f, 1f, -0.5f,1f, 0.225f,0.95f, 0.035f },
            { -1.0048f,1f, -1f, 1f, -1.2f, 1f, -1f, 1f, -1f, 1f, 1f, 1.2f, 0.1f },
            {  0.1f, 1f, -1f, 1f, -1f, 0.9f, -1f, 0f, -1f, 1f, 1f, 2f, 0.015f },
            { -0.6082f, -0.3158f, -0.4f, 0.4f, 0.3f, 0.8f, -0.2f, 0.2f, -1f, 1f, 1.3f, 2f, 0.05f },
            { -1f, -0.9006f, -1f, 1f, -1f, 1f, 0.275f, 1f, -1f, 1f, 1.3f, 2f, 0.015f },
    };

    private static final long[][] Q = new long[P.length][13];

    static {
        for (int i = 0; i < P.length; i++)
            for (int k = 0; k < 13; k++)
                Q[i][k] = quantize(P[i][k]);
    }

    private static FastNoiseLite weird;
    private static FastNoiseLite jitterT;
    private static FastNoiseLite jitterH;
    private static FastNoiseLite jitterE;

    static {
        setSeed(0L);
    }

    private CaveBiomes() {
    }

    public static synchronized void setSeed(long seed) {
        int s = (int) (seed ^ (seed >>> 32));
        FastNoiseLite fnl = new FastNoiseLite(s ^ 0x0DEED);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(2);
        fnl.SetFrequency(1f / 420f);
        weird = fnl;
        jitterT = make(s ^ 0x0A1A7, 1f / 210f);
        jitterH = make(s ^ 0x0B2B8, 1f / 260f);
        jitterE = make(s ^ 0x0C3C9, 1f / 180f);
    }

    private static FastNoiseLite make(int seed, float frequency) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(2);
        fnl.SetFrequency(frequency);
        return fnl;
    }

    private static float clampUnit(float v) {
        return v < -1f ? -1f : v > 1f ? 1f : v;
    }

    public static float jitterTemp(float t, int x, int z) {
        return jitterT == null ? t : clampUnit(t + 0.15f * jitterT.GetNoise(x, z));
    }

    public static float jitterHumidity(float h, int x, int z) {
        return jitterH == null ? h : clampUnit(h + 0.12f * jitterH.GetNoise(x, z));
    }

    public static float jitterErosion(float e, int x, int z) {
        return jitterE == null ? e : clampUnit(e + 0.18f * jitterE.GetNoise(x, z));
    }

    public static boolean isTerralith(int index) {
        return index >= 3;
    }

    public static float weirdnessAt(int x, int z) {
        FastNoiseLite fnl = weird;
        return fnl == null ? 0f : Math.max(-1f, Math.min(1f, fnl.GetNoise(x, z) * 1.6f));
    }

    public static float depthAt(int surfaceY, int blockY) {
        int cap = Math.min(surfaceY, SURFACE_CAP);
        return (cap - blockY) / DEPTH_SPAN;
    }

    // Elevation stands in for vanilla's ocean-distance continentalness
    public static float continentalnessAt(float elevationMeters) {
        if (elevationMeters <= 0f) return -0.6f;
        float v = 0.05f + 0.20f * (float) Math.log(1.0 + elevationMeters / 30.0);
        return v < -1f ? -1f : v > 1f ? 1f : v;
    }

    private static long quantize(float v) {
        return (long) (v * 10000.0F);
    }

    private static long distance(long min, long max, long v) {
        long over = v - max;
        long under = min - v;
        return over > 0L ? over : Math.max(under, 0L);
    }

    private static final float DEEP_FALLBACK_DEPTH = 1.0f;

    public static int select(float t, float h, float c, float e, float w, float depth,
                             boolean terralith) {
        if (depth < MIN_DEPTH) return NONE;

        long qt = quantize(t), qh = quantize(h), qc = quantize(c);
        long qe = quantize(e), qw = quantize(w), qd = quantize(depth);

        int best = NONE;
        long bestFit = Long.MAX_VALUE;
        for (int i = 0; i < Q.length; i++) {
            if (!terralith && isTerralith(i)) continue;
            long[] r = Q[i];
            long dt = distance(r[0], r[1], qt);
            long dh = distance(r[2], r[3], qh);
            long dc = distance(r[4], r[5], qc);
            long de = distance(r[6], r[7], qe);
            long dw = distance(r[8], r[9], qw);
            long dd = distance(r[10], r[11], qd);
            if (dt > TOLERANCE || dh > TOLERANCE || dc > TOLERANCE
                    || de > TOLERANCE || dw > TOLERANCE || dd > TOLERANCE) continue;
            long fit = sq(dt) + sq(dh) + sq(dc) + sq(de) + sq(dw) + sq(dd) + sq(r[12]);
            if (fit < bestFit) {
                bestFit = fit;
                best = i;
            }
        }
        if (best == NONE && depth >= DEEP_FALLBACK_DEPTH) {
            return nearest(qt, qh, qc, qe, qw, qd, terralith);
        }
        return best;
    }

    // Nearest entry ignoring the tolerance, for depths the table does not cover
    private static int nearest(long qt, long qh, long qc, long qe, long qw, long qd,
                               boolean terralith) {
        int best = NONE;
        long bestFit = Long.MAX_VALUE;
        for (int i = 0; i < Q.length; i++) {
            if (!terralith && isTerralith(i)) continue;
            long[] r = Q[i];
            if (quantize(MIN_DEPTH) > r[11]) continue;
            long fit = sq(distance(r[0], r[1], qt)) + sq(distance(r[2], r[3], qh))
                     + sq(distance(r[4], r[5], qc)) + sq(distance(r[6], r[7], qe))
                     + sq(distance(r[8], r[9], qw)) + sq(distance(r[10], r[11], qd))
                     + sq(r[12]);
            if (fit < bestFit) {
                bestFit = fit;
                best = i;
            }
        }
        return best;
    }

    private static long sq(long v) {
        return v * v;
    }
}
