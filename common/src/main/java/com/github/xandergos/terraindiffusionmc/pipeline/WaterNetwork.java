package com.github.xandergos.terraindiffusionmc.pipeline;

public final class WaterNetwork {

    public static final float NO_WATER = -1.0e9f;

    private static final float SEA_LEVEL_M = 0f;

    private static final FastNoiseLite WIDTH_VAR     = makeFnl(0x3C91, 1f / 1400f, 2, 2f, 0.5f);

    private static final FastNoiseLite BANK_ROUGH    = makeFnl(0x1F55, 1f / 11f, 2, 2f, 0.55f);

    private static final float VALLEY_WIDTHS = 2.5f;

    private static final float DELTA_VALLEY_WIDTHS = 1.3f;

    private static final float MIN_CHANNEL_BLOCKS = 5.0f;

    private static final float MIN_CARVE_GATES = 3.0f;

    private static final float BANK_SLOPE_LIMIT = 999f;

    private static final float SOFT_MIN_BLOCKS = 1.5f;

    private static final float CARVE_REACH = 1.0f;

    private static final float CHANNEL_FLAT_FRACTION = 0.3f;

    private static final float WIDTH_VARIATION = 0.25f;

    private static final float FALL_VALLEY_WIDTHS = 1.15f;
    private static final float FALL_WIDTH_FACTOR = 0.62f;

    private static final float FALL_FULL_BLOCKS = 6f;

    private static final float BANK_ROUGH_BLOCKS = 0.9f;

    private static final float MOUTH_BAR_BLOCKS = 2f;

    private static final float PERCH_FULL_BLOCKS = 20f;

    private static final float PERCH_FADE_BLOCKS = 13f;

    private static final FastNoiseLite LAKE_BED = makeFnl(0x2B67, 1f / 40f, 2, 2f, 0.5f);

    private static final FastNoiseLite LAKE_SHORE = makeFnl(0x6E3D, 1f / 14f, 2, 2f, 0.5f);

    private static final float LAKE_SHORE_ROUGH_BLOCKS = 2.5f;

    private static final float LAKE_DEPTH_BLOCKS = 12f;

    // Distance from the shore over which a lake bed reaches about two thirds of its full depth
    private static final float LAKE_SHELF_BLOCKS = 24f;

    private static final float LAKE_BED_VARIATION = 0.3f;

    private static final float LAKE_BANK_BLOCKS = 4f;

    // Height of the valley floor above the river surface
    private static final float BANK_BLOCKS = 1f;

    // Water slope, in blocks per block, from which a river is cut only STEEP_DEPTH_BLOCKS below its surface
    private static final float STEEP_SLOPE = 0.25f;

    private static final float GENTLE_SLOPE = 0.08f;

    private static final float STEEP_DEPTH_BLOCKS = 0.75f;

    public static final class Sample {
        public float bedElevM;
        public float waterSurfaceM;

        public boolean hasWater() {
            return waterSurfaceM > NO_WATER;
        }
    }

    private WaterNetwork() {
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(freq);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float fadeOut(float t) {
        return 1f - smoothstep(clamp01(t));
    }

    private static int drawnBlock(float meters, float metersPerBlock) {
        return (int) Math.floor(Math.floor(meters) / metersPerBlock);
    }

    private static float snapToBlock(float meters, float metersPerBlock) {
        return (float) Math.ceil(Math.floor(meters / metersPerBlock) * metersPerBlock);
    }

    public static void sample(int worldX, int worldZ, float elevM, float smoothElevM,
                              float channelDistBlocks, float channelWaterM,
                              float channelWidthM, float channelDepthM, float fallBlocks, float channelSlope,
                              float lakeSurfaceM, float lakeShoreBlocks, float deltaWeight,
                              float metersPerBlock, int scale, Sample out) {
        out.bedElevM = elevM;
        out.waterSurfaceM = NO_WATER;

        if (elevM < 0f) {
            return;
        }

        float levelM = channelWaterM;
        float surfaceM = SEA_LEVEL_M;
        if (!Float.isNaN(levelM) && levelM > SEA_LEVEL_M) {
            surfaceM = snapToBlock(levelM, metersPerBlock);
        }

        float distBlocks = channelDistBlocks;

        float fall = clamp01(fallBlocks / FALL_FULL_BLOCKS);

        float widthBlocks = Math.max(MIN_CHANNEL_BLOCKS, channelWidthM / metersPerBlock);
        widthBlocks *= 1f + (FALL_WIDTH_FACTOR - 1f) * fall;
        float widen = WIDTH_VARIATION * Math.max(0f, WIDTH_VAR.GetNoise(worldX, worldZ));
        float halfWidth = 0.5f * widthBlocks * (1f + widen);

        float valleyWidths = VALLEY_WIDTHS
                + (DELTA_VALLEY_WIDTHS - VALLEY_WIDTHS) * clamp01(deltaWeight);

        valleyWidths += (FALL_VALLEY_WIDTHS - valleyWidths) * fall;

        float steep = smoothstep(clamp01((channelSlope - GENTLE_SLOPE) / (STEEP_SLOPE - GENTLE_SLOPE)));
        float minCarveM = (MIN_CARVE_GATES * 0.5f + (STEEP_DEPTH_BLOCKS - MIN_CARVE_GATES * 0.5f) * steep) * metersPerBlock;
        float riverDepth = Math.max(minCarveM, channelDepthM + (minCarveM - channelDepthM) * steep);

        float inner = CHANNEL_FLAT_FRACTION * halfWidth;
        float wallRun = (riverDepth / metersPerBlock) * 1.5f / Math.max(0.05f, BANK_SLOPE_LIMIT);
        float outer = Math.max(halfWidth * CARVE_REACH, inner + Math.max(1.0e-3f, wallRun));
        float channel = distBlocks < 0f ? 0f
                : (distBlocks <= inner ? 1f
                        : fadeOut((distBlocks - inner) / (outer - inner)));

        float valley = distBlocks < 0f ? 0f : fadeOut(distBlocks / (halfWidth * valleyWidths));

        float perchFade = fadeOut((elevM - surfaceM - PERCH_FULL_BLOCKS * metersPerBlock)
                / (PERCH_FADE_BLOCKS * metersPerBlock));
        channel *= perchFade;
        valley *= perchFade;

        boolean nearBasin = lakeSurfaceM > NO_WATER;
        float basinM = lakeSurfaceM;
        float shore = nearBasin ? lakeShoreBlocks + LAKE_SHORE.GetNoise(worldX, worldZ) * LAKE_SHORE_ROUGH_BLOCKS : 0f;

        float basinWet = nearBasin ? clamp01(1f + shore / LAKE_BANK_BLOCKS) : 0f;
        float wet = Math.max(valley, basinWet);
        float groundM = elevM + (smoothElevM - elevM) * clamp01(wet);

        float bankM = BANK_BLOCKS * metersPerBlock * (1f - channel);
        float bed = groundM + (surfaceM + bankM - groundM) * valley;

        bed -= riverDepth * channel;

        float need = surfaceM - minCarveM;
        float k = Math.max(1.0e-3f, SOFT_MIN_BLOCKS * metersPerBlock * (1f - 0.75f * steep));
        float h = clamp01(0.5f + 0.5f * (bed - need) / k);
        float soft = bed + (need - bed) * h - k * h * (1f - h);
        bed += (soft - bed) * channel;

        // Cuts a river at sea level through the bar in front of its mouth
        if (surfaceM <= SEA_LEVEL_M && valley > 0f && groundM < MOUTH_BAR_BLOCKS * metersPerBlock) {
            float throughBar = groundM + (SEA_LEVEL_M - metersPerBlock - groundM) * valley;
            if (throughBar < bed) {
                bed = throughBar;
            }
        }

        float ramp = valley * (1f - channel);
        float roughened = bed
                + BANK_ROUGH.GetNoise(worldX, worldZ) * BANK_ROUGH_BLOCKS * metersPerBlock * ramp;

        if (bed >= surfaceM) {
            roughened = Math.max(roughened, Math.min(bed, surfaceM + bankM));
        }
        bed = roughened;
        if (bed >= surfaceM && valley > 0f) {
            bed = Math.max(bed, Math.min(groundM, surfaceM + BANK_BLOCKS * metersPerBlock));
        }

        if (bed > groundM) {
            bed = groundM;
        }

        boolean inBasin = nearBasin && (shore > 0f || shore > -scale && bed < basinM);
        if (inBasin && shore > 0f) {
            float full = LAKE_DEPTH_BLOCKS * (1f + LAKE_BED_VARIATION * LAKE_BED.GetNoise(worldX, worldZ));
            float depth = full * (1f - (float) Math.exp(-shore / LAKE_SHELF_BLOCKS));
            bed = Math.min(bed, basinM - depth * metersPerBlock);
        } else if (nearBasin && bed > basinM) {
            bed = basinM + (bed - basinM) * smoothstep(clamp01(-shore / LAKE_BANK_BLOCKS));
        }

        out.bedElevM = bed;

        boolean drawsAWholeBlock = drawnBlock(surfaceM, metersPerBlock) > drawnBlock(bed, metersPerBlock);
        boolean withinReach = distBlocks >= 0f && distBlocks <= halfWidth;
        if (drawsAWholeBlock && withinReach) {
            out.waterSurfaceM = surfaceM;
        }

        if (inBasin && bed < basinM
                && (!out.hasWater() || basinM > out.waterSurfaceM)) {
            out.waterSurfaceM = basinM;
        }
    }
}
