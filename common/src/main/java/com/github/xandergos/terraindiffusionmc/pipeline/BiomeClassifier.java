package com.github.xandergos.terraindiffusionmc.pipeline;

// Rule-based biome classifier port of _classify_biome in minecraft_api.py
public final class BiomeClassifier {
    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    private static final float NATIVE_RESOLUTION_M = WorldPipelineModelConfig.nativeResolution();

    private static final float TREELINE_START_M = 1500f;

    private static final float TREELINE_END_M = 3500f;

    private static final float TREELINE_SUMMER_MIN_C = 5f;

    private static final float TREELINE_SUMMER_FULL_C = 12f;

    private static final float TREELINE_MEAN_MIN_C = -12f;

    private static final float TREELINE_MEAN_FULL_C = -2f;

    // Snow cover needs this temperature after noise
    private static final float SNOW_APPEARS_C = -3f;

    // Ice-surfaced mountain biomes need both of these
    static final float FROZEN_SURFACE_C = -12f;

    private static final float FROZEN_SURFACE_M = 4000f;

    private static final float SNOW_TIER1_START_C = -5f;

    private static final float SNOW_TIER1_END_C = -7.5f;

    private static final float SNOW_TIER2_END_C = -10f;

    private static final int LAYERS_PER_TIER = 7;

    private static final int SNOW_BLOCK_DEPTH = 8;

    private static final int MAX_SNOW_DEPTH = SNOW_BLOCK_DEPTH + LAYERS_PER_TIER;

    // Biome IDs
    static final short PLAINS = 1, RIVER = 7, FROZEN_RIVER = 11;
    static final short SNOWY_PLAINS = 3, DESERT = 5, SWAMP = 6;
    static final short FOREST = 8, TAIGA = 15, SNOWY_TAIGA = 16, SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19, JUNGLE = 23, BADLANDS = 26, MEADOW = 29;
    static final short GROVE = 31, SNOWY_SLOPES = 32, FROZEN_PEAKS = 33, STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41, OCEAN = 44, COLD_OCEAN = 46, FROZEN_OCEAN = 48;
    static final short FOREST_SPARSE = 108, TAIGA_SPARSE = 115, SNOWY_TAIGA_SPARSE = 116;

    // Classify biomes for a grid of pixels
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM) {
        return classify(elev, climate, i0, j0, elevPadded, H, W, pixelSizeM, null, null);
    }

    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM,
                                    byte[] snowLayersOut, boolean[] riverMask) {
        return classify(elev, climate, i0, j0, 1, elevPadded, H, W, pixelSizeM, snowLayersOut, riverMask);
    }

    public static short[] classify(float[] elev, float[] climate, int i0, int j0, int coordStep,
                                    float[] elevPadded, int H, int W, float pixelSizeM,
                                    byte[] snowLayersOut, boolean[] riverMask) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c * coordStep, ny = i0 + r * coordStep;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

        // Process per-pixel
        TerrainSample sample = new TerrainSample();
        boolean useTerralith = TerralithCompat.isActive();
        boolean regional = climate.length >= 6 * H * W;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float pet      = potentialEvaporation(temp, tStd);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                float lapse = regional ? Math.max(MIN_LAPSE, -climate[4 * H * W + idx]) : MIN_LAPSE;
                float tReg = regional ? climate[5 * H * W + idx] + tempNoise[idx] : temp;
                float relElev = (tReg - temp) / lapse;
                float moist = precip / potentialEvaporation(tReg, tStd) * seasonPenalty;
                float frost = temp + FROST_NOISE * snowNoise[idx];

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                float summerMaxC = temp + 1.414f * tStd;
                float altitudeLimit = clamp01(1f
                        - (altM - TREELINE_START_M) / (TREELINE_END_M - TREELINE_START_M));
                float summerLimit = clamp01((summerMaxC - TREELINE_SUMMER_MIN_C)
                        / (TREELINE_SUMMER_FULL_C - TREELINE_SUMMER_MIN_C));
                float meanColdLimit = clamp01((temp - TREELINE_MEAN_MIN_C)
                        / (TREELINE_MEAN_FULL_C - TREELINE_MEAN_MIN_C));
                effTreeMoisture *= Math.min(altitudeLimit, Math.min(summerLimit, meanColdLimit));

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < SNOW_APPEARS_C && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -7f;
                boolean cold      = temp >= -7f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                sample.worldX = j0 + c * coordStep;
                sample.worldZ = i0 + r * coordStep;
                sample.elev = elevVal;
                sample.altM = altM;
                sample.slope = slope;
                sample.temp = temp;
                sample.tReg = tReg;
                sample.relElev = relElev;
                sample.moist = moist;
                sample.frost = frost;
                sample.tStd = tStd;
                sample.precip = precip;
                sample.pCV = pCV;
                sample.aridity = aridity;
                sample.treeMoisture = treeMoisture;
                sample.growingSeason = growingSeason;
                sample.effTreeMoisture = effTreeMoisture;
                sample.bareThreshold = bareThreshold;
                sample.treesNone = treesNone;
                sample.treesSparse = treesSparse;
                sample.treesForest = treesForest;
                sample.treesDense = treesDense;
                sample.treesRainforest = treesRainforest;
                sample.barren = barren;
                sample.tooArid = tooArid;
                sample.tooCold = tooCold;
                sample.slopeMedium = slopeMedium;
                sample.slopeBare = slopeBare;
                sample.hasSnow = hasSnow;
                sample.isOcean = isOcean;
                sample.mountains = mountains;
                sample.lowland = lowland;
                sample.frozen = frozen;
                sample.cold = cold;
                sample.cool = cool;
                sample.temperate = temperate;
                sample.warm = warm;
                sample.hot = hot;

                short biome;
                if (!sample.isOcean && riverMask != null && riverMask[idx]) {
                    biome = riverBiome(sample, useTerralith);
                } else {
                    biome = TerralithClassifier.NONE;
                    if (useTerralith) {
                        biome = TerralithClassifier.pick(sample);
                    }
                    if (biome == TerralithClassifier.NONE) {
                        biome = classifyVanilla(sample);
                    }
                }

                out[idx] = biome;
                if (snowLayersOut != null) {
                    snowLayersOut[idx] = snowDepthFor(sample);
                }
            }
        }
        return out;
    }

    // True where a mountain surface may be ice: frozen peaks, frozen cliffs, glacial chasm
    static boolean frozenSurface(TerrainSample s) {
        return s.temp < FROZEN_SURFACE_C && s.altM > FROZEN_SURFACE_M;
    }

    private static byte snowDepthFor(TerrainSample s) {
        float temp = s.temp;
        if (temp > SNOW_TIER1_START_C) {
            return 1;
        }

        if (temp > SNOW_TIER1_END_C) {
            float step = (SNOW_TIER1_START_C - SNOW_TIER1_END_C) / LAYERS_PER_TIER;
            int layers = 1 + (int) ((SNOW_TIER1_START_C - temp) / step);
            return (byte) Math.max(1, Math.min(LAYERS_PER_TIER, layers));
        }

        float step = (SNOW_TIER1_END_C - SNOW_TIER2_END_C) / LAYERS_PER_TIER;
        int stacked = 1 + (int) ((SNOW_TIER1_END_C - temp) / step);
        int depth = SNOW_BLOCK_DEPTH + Math.max(1, Math.min(LAYERS_PER_TIER, stacked));
        return (byte) Math.min(MAX_SNOW_DEPTH, depth);
    }

    private static final float MIN_LAPSE = 0.003f;

    private static final float FROST_NOISE = 0.3f;

    private static float potentialEvaporation(float temp, float tStd) {
        float tEff = Math.max(0f, temp + 0.5f * tStd);
        return Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
    }

    private static short riverBiome(TerrainSample s, boolean useTerralith) {
        if (useTerralith) {
            return TerralithClassifier.river(s);
        }
        if (s.temp <= -3f) {
            return FROZEN_RIVER;
        }
        return RIVER;
    }

    private static short classifyVanilla(TerrainSample s) {
        short biome = PLAINS;

        if (s.isOcean) {
            if (s.frozen) biome = FROZEN_OCEAN;
            else if (s.cold) biome = COLD_OCEAN;
            else if (s.warm || s.hot) biome = WARM_OCEAN;
            else biome = OCEAN;
        } else if (s.mountains) {
            if (s.slopeBare) {
                if (frozenSurface(s)) biome = FROZEN_PEAKS;
                else if (s.hasSnow) biome = SNOWY_SLOPES;
                else biome = STONY_PEAKS;
            } else if (s.hasSnow) {
                if (s.treesNone) biome = SNOWY_SLOPES;
                else if (s.treesSparse || s.treesForest) biome = SNOWY_TAIGA_SPARSE;
                else biome = SNOWY_TAIGA;
            } else if (s.treesNone) {
                if (s.barren) biome = WINDSWEPT_HILLS;
                else if (s.treeMoisture < 0.35f || s.precip < 350f) biome = GROVE;
                else biome = PLAINS;
            } else if (s.treesSparse || s.treesForest) {
                biome = TAIGA_SPARSE;
            } else {
                biome = TAIGA;
            }
        } else {
            // Lowland/midland
            if (s.hasSnow && s.treesNone) {
                biome = SNOWY_PLAINS;
            } else if (s.hasSnow) {
                biome = (s.treesSparse || s.treesForest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
            } else if (s.treesNone) {
                if (s.warm || s.hot) biome = DESERT;
                else if (s.barren && !s.lowland && (s.cold || s.cool || s.temperate)) biome = GROVE;
                else if (s.treeMoisture < 0.35f || s.precip < 350f) biome = GROVE;
                else biome = PLAINS;
            } else if (s.treesSparse || s.treesForest) {
                if (s.hot) biome = JUNGLE;
                else if (s.warm && s.treesSparse && !s.slopeMedium) biome = SAVANNA;
                else if (s.warm && s.treesForest) biome = FOREST_SPARSE;
                else if (s.temperate) biome = FOREST_SPARSE;
                else biome = TAIGA_SPARSE;
            } else if (s.treesDense) {
                if (s.hot) biome = JUNGLE;
                else if (s.warm && s.lowland) biome = SWAMP;
                else if (s.cool || s.cold) biome = TAIGA;
                else biome = FOREST;
            } else {
                if (s.hot || (s.warm && s.temp >= 18f && s.tStd < 5f)) biome = JUNGLE;
                else if (s.lowland) biome = SWAMP;
                else if (s.cool || s.cold) biome = TAIGA;
                else biome = FOREST;
            }
        }

        // Bare slope override for lowland/non-mountain cliffs
        if (s.slopeBare && !s.isOcean && !s.mountains) {
            if (frozenSurface(s)) biome = FROZEN_PEAKS;
            else if (s.hasSnow) biome = SNOWY_SLOPES;
            else biome = STONY_PEAKS;
        }

        return biome;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
