package com.github.xandergos.terraindiffusionmc.pipeline;

public final class DeepCaverns {

    public static final boolean ENABLED =
            !"false".equals(System.getProperty("terradiff.deep"));

    public static final int TOP =
            Integer.parseInt(System.getProperty("terradiff.deepTop", "8"));
    public static final int BOTTOM =
            Integer.parseInt(System.getProperty("terradiff.deepBottom", "-184"));
    public static final int LAVA_Y =
            Integer.parseInt(System.getProperty("terradiff.deepLavaY", "-182"));

    public static final int AIR = 0;
    public static final int WATER = 1;
    public static final int LAVA = 2;

    public static final float FAR = 4096f;

    private static final int BANDS =
            Integer.parseInt(System.getProperty("terradiff.deepBands", "4"));
    private static final float SPACING =
            Float.parseFloat(System.getProperty("terradiff.deepSpacing", "54"));
    private static final float FIRST_FLOOR =
            Float.parseFloat(System.getProperty("terradiff.deepFirstFloor", "-12"));
    private static final float BAND_HEIGHT =
            Float.parseFloat(System.getProperty("terradiff.deepBandHeight", "38"));

    private static final float FLOOR_RELIEF =
            Float.parseFloat(System.getProperty("terradiff.deepFloorRelief", "12"));
    private static final float FLOOR_DETAIL =
            Float.parseFloat(System.getProperty("terradiff.deepFloorDetail", "2.5"));

    private static final float ROOM_THRESHOLD =
            Float.parseFloat(System.getProperty("terradiff.deepRoom", "0.32"));
    private static final float LATERAL_K =
            Float.parseFloat(System.getProperty("terradiff.deepLateral", "26"));
    private static final float HEIGHT_MIN =
            Float.parseFloat(System.getProperty("terradiff.deepHeightMin", "12"));
    private static final float HEIGHT_MAX =
            Float.parseFloat(System.getProperty("terradiff.deepHeightMax", "32"));
    private static final float WALL_NOISE =
            Float.parseFloat(System.getProperty("terradiff.deepWallNoise", "14"));
    private static final float CEIL_NOISE =
            Float.parseFloat(System.getProperty("terradiff.deepCeilNoise", "10"));
    private static final float CHEESE_K =
            Float.parseFloat(System.getProperty("terradiff.deepCheeseK", "22"));
    private static final float CHEESE_YSQUASH =
            Float.parseFloat(System.getProperty("terradiff.deepCheeseSquash", "1.5"));
    private static final float REGION_BIAS =
            Float.parseFloat(System.getProperty("terradiff.deepRegionBias", "0.35"));
    private static final float CAP_SLOPE =
            Float.parseFloat(System.getProperty("terradiff.deepCapSlope", "0.7"));

    private static final float PILLAR_THRESHOLD =
            Float.parseFloat(System.getProperty("terradiff.deepPillar", "0.60"));
    private static final float PILLAR_K =
            Float.parseFloat(System.getProperty("terradiff.deepPillarK", "30"));

    private static final int LAKE_SEAL =
            Integer.parseInt(System.getProperty("terradiff.deepLakeSeal", "4"));

    // How far the cavern ceiling wanders below #TOP
    private static final float CONTACT_ROUGH =
            Float.parseFloat(System.getProperty("terradiff.deepContactRough", "6"));

    private static final float LAKE_RISE =
            Float.parseFloat(System.getProperty("terradiff.deepLakeRise", "-2"));

    private static final float CHIM_THICK =
            Float.parseFloat(System.getProperty("terradiff.deepChimney", "0.055"));
    private static final float CHIM_K =
            Float.parseFloat(System.getProperty("terradiff.deepChimneyK", "22"));
    private static final float CHIM_YSQUASH =
            Float.parseFloat(System.getProperty("terradiff.deepChimneySquash", "0.28"));

    private static final int CHIM_STEP = 16;
    private static final int CHIM_SAMPLES = (TOP - BOTTOM) / CHIM_STEP + 2;

    private static FastNoiseLite cheese;
    private static FastNoiseLite region;
    private static FastNoiseLite floorNoise;
    private static FastNoiseLite floorDetail;
    private static FastNoiseLite pillar;
    private static FastNoiseLite heightNoise;
    private static FastNoiseLite chimA;
    private static FastNoiseLite chimB;
    private static volatile int GEN;

    // Per-thread columns over a 32x32 block neighbourhood, slotted by the low bits of x and z
    private static final int COL_BITS = 5;
    private static final int COL_MASK = (1 << COL_BITS) - 1;
    private static final ThreadLocal<Col[]> COLS = ThreadLocal.withInitial(() -> new Col[1 << (2 * COL_BITS)]);

    static {
        setSeed(0L);
    }

    private DeepCaverns() {
    }

    public static synchronized void setSeed(long seed) {
        int s = (int) (seed ^ (seed >>> 32));
        cheese = make(s ^ 0x0CEE5, 1f / 48f, 2);
        region = make(s ^ 0x0DEE1, 1f / 190f, 2);
        floorNoise = make(s ^ 0x0F100, 1f / 260f, 2);
        floorDetail = make(s ^ 0x0F1A7, 1f / 70f, 2);
        pillar = make(s ^ 0x0D111, 1f / 30f, 2);
        heightNoise = make(s ^ 0x0BE16, 1f / 90f, 2);
        chimA = make(s ^ 0x0C41F, 1f / 70f, 2);
        chimB = make(s ^ 0x0C42F, 1f / 70f, 2);
        GEN++;
    }

    private static FastNoiseLite make(int seed, float frequency, int octaves) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(frequency);
        fnl.SetFractalOctaves(octaves);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private static final class Col {
        int gen = -1;
        int x = Integer.MIN_VALUE;
        int z = Integer.MIN_VALUE;
        final float[] floor = new float[BANDS];
        final float[] ceil = new float[BANDS];
        final float[] lat = new float[BANDS];
        final float[] lake = new float[BANDS];
        float bias;
        float pillarCut;
        float topY;
        final float[] ca = new float[CHIM_SAMPLES];
        final float[] cb = new float[CHIM_SAMPLES];
    }

    private static Col column(int x, int z) {
        Col[] cols = COLS.get();
        int slot = ((z & COL_MASK) << COL_BITS) | (x & COL_MASK);
        Col c = cols[slot];
        if (c == null) {
            c = new Col();
            cols[slot] = c;
        }
        if (c.x == x && c.z == z && c.gen == GEN) return c;
        c.gen = GEN;
        c.x = x;
        c.z = z;

        float fx = x, fz = z;
        c.bias = region.GetNoise(fx, fz) * REGION_BIAS;
        c.pillarCut = (PILLAR_THRESHOLD - pillar.GetNoise(fx, fz)) * PILLAR_K;
        float cn = heightNoise.GetNoise(fx * 0.55f, fz * 0.55f) * 2.2f;
        if (cn < -1f) cn = -1f; else if (cn > 1f) cn = 1f;
        c.topY = TOP - CONTACT_ROUGH * (0.5f + 0.5f * cn);

        for (int b = 0; b < BANDS; b++) {
            float ox = b * 7919f, oz = b * 6151f;
            float nominal = FIRST_FLOOR - b * SPACING;
            c.floor[b] = nominal
                    + floorNoise.GetNoise(fx + ox, fz + oz) * FLOOR_RELIEF
                    + floorDetail.GetNoise(fx + ox, fz + oz) * FLOOR_DETAIL;
            c.lat[b] = (region.GetNoise(fx + ox, fz + oz) - ROOM_THRESHOLD) * LATERAL_K;
            float hn = (heightNoise.GetNoise(fx + ox, fz + oz) + 1f) * 0.5f;
            c.ceil[b] = c.floor[b] + HEIGHT_MIN + (HEIGHT_MAX - HEIGHT_MIN) * hn;
            if (b == 0 && c.ceil[b] < TOP + 2f) c.ceil[b] = TOP + 2f;
            c.lake[b] = nominal + LAKE_RISE;
        }

        for (int i = 0; i < CHIM_SAMPLES; i++) {
            float sy = (BOTTOM + i * (float) CHIM_STEP) * CHIM_YSQUASH;
            c.ca[i] = chimA.GetNoise(fx, sy, fz);
            c.cb[i] = chimB.GetNoise(fx, sy, fz);
        }
        return c;
    }

    private static float chimneyAt(Col c, float y) {
        float t = (y - BOTTOM) / CHIM_STEP;
        int i = (int) Math.floor(t);
        if (i < 0) i = 0;
        if (i >= CHIM_SAMPLES - 1) i = CHIM_SAMPLES - 2;
        float f = t - i;
        float a = c.ca[i] + (c.ca[i + 1] - c.ca[i]) * f;
        float b2 = c.cb[i] + (c.cb[i + 1] - c.cb[i]) * f;
        float o = CHIM_THICK - Math.abs(a);
        float p = CHIM_THICK - Math.abs(b2);
        return (o < p ? o : p) * CHIM_K;
    }

    // Signed distance in blocks; negative inside a cavern, #FAR in solid rock
    private static boolean lakeBed(Col c, int y) {
        for (int b = 0; b < BANDS; b++) {
            if (c.floor[b] >= c.lake[b]) continue;
            if (y < c.lake[b] && y >= c.floor[b] - LAKE_SEAL) return true;
        }
        return false;
    }

    public static float density(int x, int y, int z) {
        if (!ENABLED || y > TOP || y < BOTTOM) return FAR;
        Col c = column(x, z);
        if (y > c.topY) return FAR;

        float best = chimneyAt(c, y);
        // A shaft must not punch through the bed of a lake it passes under: the water drains into
        if (best > 0f && lakeBed(c, y)) best = 0f;

        int band = -1;
        float above = 0f;
        for (int b = 0; b < BANDS; b++) {
            float h = y - c.floor[b];
            if (h >= 0f && h <= BAND_HEIGHT) {
                band = b;
                above = h;
                break;
            }
        }

        if (band >= 0) {
            float n3 = cheese.GetNoise(x, y * CHEESE_YSQUASH, z);
            float o = c.lat[band] + c.bias * LATERAL_K + n3 * WALL_NOISE;
            float ceil = c.ceil[band] - y + n3 * CEIL_NOISE;
            if (ceil < o) o = ceil;
            if (above < o) o = above;
            if (c.pillarCut < o) o = c.pillarCut;
            if (o > best) best = o;
        }

        return best > 0f ? -best : FAR;
    }

    // What fills an open cavern block: #AIR, #WATER or #LAVA
    public static int fluid(int x, int y, int z) {
        if (!ENABLED || y > TOP || y < BOTTOM) return AIR;
        if (y <= LAVA_Y) return LAVA;
        Col c = column(x, z);
        for (int b = 0; b < BANDS; b++) {
            if (y < c.floor[b] || y >= c.lake[b] || y - c.floor[b] > BAND_HEIGHT) continue;
            int floorY = (int) Math.floor(c.floor[b]);
            if (density(x, floorY - 1, z) < 0f) return AIR;
            return WATER;
        }
        return AIR;
    }
}
