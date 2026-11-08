package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

import static com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeIds.*;

public final class TerralithClassifier {
    public static final short NONE = 0;

    private static final int BOREAL = 2, TEMPERATE = 3, WARM = 4, TROPICAL = 5;

    private static final int HYPERARID = 0, ARID = 1, SEMIARID = 2, SUBHUMID = 3, HUMID = 4, WET = 5;

    private static final float[] HEAT_EDGES_C = {-6f, 1f, 8f, 15f, 21f};

    private static final float[] WET_EDGES = {0.15f, 0.32f, 0.55f, 0.85f, 1.35f};

    // Warm semi-arid ground drier than this is shrubland, the band beyond the badlands fringe
    private static final float SHRUB_EDGE_MOIST = 0.42f;

    // Share of the local temperature, against the regional one, that picks the heat class
    private static final float RELIEF_WEIGHT = 1f;

    // Regional moisture bounds of desert, red desert and the badlands fringe
    private static final float DESERT_MOIST = 0.20f;
    private static final float MESA_MOIST = 0.24f;
    private static final float FRINGE_MOIST = 0.27f;

    // Desert needs this local temperature; red desert reaches down to the flat bound on level ground, the high one on mountains
    private static final float DESERT_C = 13.5f;
    private static final float MESA_FLAT_C = 12f;
    private static final float MESA_HIGH_C = 6f;

    // Red sand belt between desert and terracotta, in moisture above the desert bound and degrees below it
    private static final float BELT_MOIST = 0.008f;
    private static final float BELT_C = 1.0f;

    // Moister desert inside this margin of the red desert is lush desert
    private static final float LUSH_MARGIN = 0.03f;

    // Fringe at or above this temperature carries acacia, below it oak
    private static final float FRINGE_HOT_C = 18f;

    // Width of the arid highlands band around the badlands, in degrees below their bound and moisture beyond the fringe
    private static final float SHELL_C = 3f;
    private static final float SHELL_MOIST = 0.06f;

    private static final int NOT_ARID = 0, DESERT_ZONE = 1, MESA_ZONE = 2, FRINGE_ZONE = 3;

    private static final float BOREAL_CAP_C = 4f;
    private static final float TEMPERATE_CAP_C = 8f;
    private static final float WARM_CAP_C = 14f;

    private static final float SNOW_LINE_C = -3f;
    private static final float TREE_LINE_C = -6f;
    private static final float ICE_C = -13f;
    private static final float GEM_PEAK_C = -3f;
    private static final float WARM_ROCK_C = 10f;

    private static final float UPPER_M = 180f;
    private static final float HIGH_M = 380f;
    private static final float VALLEY_M = -120f;
    private static final float BASIN_M = -50f;
    private static final float PLATEAU_M = 900f;
    private static final float LOWLAND_M = 150f;
    private static final float WETLAND_REL_M = 20f;
    private static final float COAST_BASE_M = 400f;
    private static final float ASHEN_RISE_M = 60f;
    private static final float RELIEF_SPLIT_M = 400f;
    private static final float COAST_CLIFF_M = 700f;
    private static final float SNOW_FLAT_M = 400f;

    private static final float GENTLE = 0.25f;
    private static final float STEEP = 0.55f;

    private static final float FROZEN_SEA_C = -3f;
    private static final float COLD_SEA_C = 8f;
    private static final float LUKEWARM_SEA_C = 15f;
    private static final float WARM_SEA_C = 20f;
    private static final float DEEP_SEA_BLOCKS = 30f;
    private static final float SHORE_BLOCKS = 2f;

    // Share of sea within the relief window that makes low ground a shore, about 13 blocks from a straight coast
    private static final float SHORE_SEA_SHARE = 0.25f;

    private static final FastNoiseLite VARIANT_BROAD = makeFnl(0x7A1E, 1f / 3000f);
    private static final FastNoiseLite VARIANT_LOCAL = makeFnl(0x5C0D, 1f / 1200f);
    private static final FastNoiseLite SHOWPIECE = makeFnl(0x9E37, 1f / 1800f);
    private static final FastNoiseLite SHOWPIECE_PICK = makeFnl(0x2B7F, 1f / 6000f);
    private static final FastNoiseLite VOLCANIC = makeFnl(0x1F55, 1f / 5000f);
    private static final FastNoiseLite OASIS = makeFnl(0x0A515, 1f / 700f);
    private static final FastNoiseLite GEOTHERMAL = makeFnl(0x6E07, 1f / 2200f);
    private static final FastNoiseLite EMERALD = makeFnl(0x3E3D, 1f / 8000f, 1);
    private static final FastNoiseLite CLEARING = makeFnl(0x0C1EA, 1f / 700f);
    private static final float Q_TOP_05 = 0.657f;
    private static final float Q_TOP_10 = 0.624f;
    private static final float Q_TOP_20 = 0.581f;
    private static final float Q_TOP_25 = 0.565f;
    private static final float Q_TOP_33 = 0.542f;
    private static final float Q_MEDIAN = 0.500f;
    private static final float Q_BOT_33 = 0.458f;
    private static final float Q_BOT_25 = 0.435f;

    private static final float SHOWPIECE_THRESHOLD = Q_TOP_10;
    private static final float SNOWY_SHOWPIECE_THRESHOLD = Q_TOP_25;
    private static final float CANYON_SHOWPIECE_THRESHOLD = Q_TOP_20;
    private static final float MESA_SHOWPIECE_THRESHOLD = Q_TOP_20;
    private static final float SHOWPIECE_MIN_MOIST = 0.7f;
    private static final float VOLCANIC_THRESHOLD = Q_TOP_05;
    private static final float OASIS_THRESHOLD = 0.71f;
    private static final float GEOTHERMAL_THRESHOLD = Q_TOP_10;
    private static final float EMERALD_THRESHOLD = 0.703f;
    private static final float CLEARING_THRESHOLD = Q_TOP_10;
    private static final float GLACIER_THRESHOLD = Q_TOP_25;
    private static final float MASSIF_BIAS_PER_M = 0.00025f;

    private static FastNoiseLite makeFnl(int seed, float freq) {
        return makeFnl(seed, freq, 4);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int octaves) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFrequency(freq);
        fnl.SetFractalOctaves(octaves);
        fnl.SetFractalLacunarity(2f);
        fnl.SetFractalGain(0.5f);
        return fnl;
    }

    private TerralithClassifier() {
    }

    private static float v01(FastNoiseLite noise, TerrainSample s) {
        return (noise.GetNoise(s.worldX, s.worldZ) + 1f) * 0.5f;
    }

    private static int classOf(float value, float[] edges) {
        int k = 0;
        while (k < edges.length && value >= edges[k]) k++;
        return k;
    }

    private static int heatOf(TerrainSample s) {
        float blended = s.tReg + RELIEF_WEIGHT * (s.temp - s.tReg);
        int heat = classOf(blended, HEAT_EDGES_C);
        if (s.temp < BOREAL_CAP_C) return BOREAL;
        if (s.temp < TEMPERATE_CAP_C) heat = Math.min(heat, TEMPERATE);
        else if (s.temp < WARM_CAP_C) heat = Math.min(heat, WARM);
        return Math.max(heat, BOREAL);
    }

    public static short river(TerrainSample s) {
        if (s.frost < SNOW_LINE_C) return BiomeClassifier.FROZEN_RIVER;
        if (s.tReg >= WARM_SEA_C) return WARM_RIVER;
        return BiomeClassifier.RIVER;
    }

    public static short pick(TerrainSample s) {
        if (s.isOcean) {
            return ocean(s);
        }

        int zoneHeat = classOf(s.tReg, HEAT_EDGES_C);
        int wet = classOf(s.moist, WET_EDGES);
        boolean snowy = s.frost < SNOW_LINE_C;

        if (s.elev < SHORE_BLOCKS * metresPerBlock() && s.seaShare >= SHORE_SEA_SHARE) {
            if (s.slope >= STEEP) return BiomeClassifier.STONY_SHORE;
            if (s.slope < GENTLE) return beach(s, heatOf(s), wet, snowy);
        }
        int zone = snowy ? NOT_ARID : aridZone(s);
        if (zone != NOT_ARID) {
            return arid(s, zone);
        }
        short rest = pickRest(s, zoneHeat, wet, snowy);
        if (desertFamily(rest) || terracotta(rest)) return dryOutside(s);
        return !snowy && temperateGrass(rest) && aridShell(s) ? ARID_HIGHLANDS : rest;
    }

    static boolean terracotta(short b) {
        return b == BiomeClassifier.BADLANDS || b == BiomeClassifier.ERODED_BADLANDS || b == WHITE_MESA || b == WARPED_MESA
                || b == PAINTED_MOUNTAINS || b == BRYCE_CANYON || b == RED_OASIS;
    }

    // Dry ground outside the arid zones
    private static short dryOutside(TerrainSample s) {
        if (s.slope >= STEEP) return SAVANNA_SLOPES;
        if (s.zoneTemp < FRINGE_HOT_C) return ARID_HIGHLANDS;
        return s.moist < FRINGE_MOIST + SHELL_MOIST ? SAVANNA_BADLANDS : FRACTURED_SAVANNA;
    }

    static boolean redSandBelt(TerrainSample s) {
        return s.moist < DESERT_MOIST + BELT_MOIST && s.zoneTemp >= DESERT_C - BELT_C;
    }

    static float mesaC(TerrainSample s) {
        float rise = Math.max(0f, Math.min(1f, s.relElev / HIGH_M));
        return MESA_FLAT_C + (MESA_HIGH_C - MESA_FLAT_C) * rise;
    }

    // Desert, red desert or badlands fringe, by local temperature and regional moisture
    static int aridZone(TerrainSample s) {
        if (s.moist >= FRINGE_MOIST || s.zoneTemp < mesaC(s)) return NOT_ARID;
        if (s.moist < DESERT_MOIST && s.zoneTemp >= DESERT_C) return DESERT_ZONE;
        return s.moist < MESA_MOIST ? MESA_ZONE : FRINGE_ZONE;
    }

    static boolean desertFamily(short b) {
        return b == BiomeClassifier.DESERT || b == ANCIENT_SANDS || b == LUSH_DESERT || b == SANDSTONE_VALLEY
                || b == DESERT_CANYON || b == DESERT_SPIRES || b == DESERT_OASIS;
    }

    // Just outside the badlands zones, too cold or too moist for them
    static boolean aridShell(TerrainSample s) {
        return s.moist < FRINGE_MOIST + SHELL_MOIST && s.zoneTemp >= mesaC(s) - SHELL_C;
    }

    static boolean temperateGrass(short b) {
        return b == STEPPE || b == HIGHLANDS || b == ALPINE_HIGHLANDS || b == TEMPERATE_HIGHLANDS || b == BRUSHLAND
                || b == BiomeClassifier.PLAINS || b == BiomeClassifier.MEADOW || b == BiomeClassifier.FOREST_SPARSE
                || b == BiomeClassifier.TAIGA_SPARSE || b == BiomeClassifier.WINDSWEPT_HILLS;
    }

    private static short arid(TerrainSample s, int zone) {
        boolean cliff = s.slope >= s.bareThreshold;
        boolean steep = s.slope >= STEEP, gentle = s.slope < GENTLE;
        boolean high = s.relElev >= HIGH_M, upper = s.relElev >= UPPER_M, valley = s.relElev <= VALLEY_M;
        boolean oasis = !upper && gentle && v01(OASIS, s) > OASIS_THRESHOLD;
        float broad = v01(VARIANT_BROAD, s);
        switch (zone) {
            case DESERT_ZONE:
                if (cliff || (upper && steep)) return DESERT_SPIRES;
                if (upper) return DESERT_CANYON;
                if (valley) return SANDSTONE_VALLEY;
                if (oasis) return DESERT_OASIS;
                if (broad > Q_TOP_20) return ANCIENT_SANDS;
                return s.moist >= DESERT_MOIST - LUSH_MARGIN ? LUSH_DESERT : BiomeClassifier.DESERT;
            case MESA_ZONE:
                if (redSandBelt(s)) return BiomeClassifier.BADLANDS;
                if (cliff || (upper && steep)) return BRYCE_CANYON;
                if (high) return gentle && v01(SHOWPIECE, s) > SHOWPIECE_THRESHOLD ? WARPED_MESA : PAINTED_MOUNTAINS;
                if (upper) return broad > Q_MEDIAN ? WHITE_MESA : BiomeClassifier.ERODED_BADLANDS;
                if (oasis) return RED_OASIS;
                return BiomeClassifier.ERODED_BADLANDS;
            default:
                boolean hot = s.zoneTemp >= FRINGE_HOT_C;
                if (cliff) return hot ? SAVANNA_SLOPES : BiomeClassifier.WOODED_BADLANDS;
                return hot ? SAVANNA_BADLANDS : BiomeClassifier.WOODED_BADLANDS;
        }
    }

    private static short pickRest(TerrainSample s, int zoneHeat, int wet, boolean snowy) {
        if (s.slope >= s.bareThreshold) {
            return cliff(s, heatOf(s), wet, snowy);
        }
        if (snowy) {
            return snowy(s, zoneHeat, wet);
        }

        int heat = heatOf(s);

        short showpiece = showpiece(s, heat, wet);
        if (showpiece != NONE) {
            return showpiece;
        }

        if (s.relElev >= HIGH_M) return high(s, heat, zoneHeat, wet);
        if (s.relElev >= UPPER_M || s.slope >= STEEP) return upper(s, heat, wet);
        if (s.relElev <= VALLEY_M) return valley(s, heat, wet);
        if (s.altM - s.relElev >= PLATEAU_M && s.slope < GENTLE) return plateau(s, heat, wet);
        return base(s, heat, wet);
    }

    private static float metresPerBlock() {
        return WorldPipelineModelConfig.nativeResolution()
                / WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
    }

    static boolean deepSea(TerrainSample s) {
        return -s.elev >= DEEP_SEA_BLOCKS * metresPerBlock();
    }

    private static short ocean(TerrainSample s) {
        boolean deep = deepSea(s);
        if (s.tReg < FROZEN_SEA_C) return deep ? BiomeClassifier.DEEP_FROZEN_OCEAN : BiomeClassifier.FROZEN_OCEAN;
        if (s.tReg < COLD_SEA_C) return deep ? BiomeClassifier.DEEP_COLD_OCEAN : BiomeClassifier.COLD_OCEAN;
        if (s.tReg >= WARM_SEA_C) return deep ? DEEP_WARM_OCEAN : BiomeClassifier.WARM_OCEAN;
        if (s.tReg >= LUKEWARM_SEA_C) return deep ? BiomeClassifier.DEEP_LUKEWARM_OCEAN : BiomeClassifier.LUKEWARM_OCEAN;
        return deep ? BiomeClassifier.DEEP_OCEAN : BiomeClassifier.OCEAN;
    }

    private static short beach(TerrainSample s, int heat, int wet, boolean snowy) {
        if (snowy) return BiomeClassifier.SNOWY_BEACH;
        float local = v01(VARIANT_LOCAL, s);
        if (heat == BOREAL && wet >= SEMIARID && local < Q_MEDIAN) return GRAVEL_BEACH;
        if (heat == TEMPERATE && wet >= SEMIARID && local < Q_BOT_25) return GRAVEL_BEACH;
        return BiomeClassifier.BEACH;
    }

    private static boolean volcanic(TerrainSample s) {
        return v01(VOLCANIC, s) > VOLCANIC_THRESHOLD;
    }

    private static float massif(FastNoiseLite noise, TerrainSample s) {
        return v01(noise, s) + MASSIF_BIAS_PER_M * (s.relElev - HIGH_M);
    }

    private static boolean wetland(TerrainSample s) {
        return s.altM < LOWLAND_M && s.relElev < WETLAND_REL_M && s.slope < GENTLE;
    }

    private static boolean clearing(TerrainSample s) {
        return v01(CLEARING, s) > CLEARING_THRESHOLD;
    }

    private static short showpiece(TerrainSample s, int heat, int wet) {
        boolean raised = s.relElev >= UPPER_M || s.slope >= GENTLE;

        float volcanism = heat >= TEMPERATE ? v01(VOLCANIC, s) : 0f;
        if (volcanism > VOLCANIC_THRESHOLD) {
            if (s.relElev >= HIGH_M) return NONE;
            if (s.relElev >= ASHEN_RISE_M && heat >= WARM && wet >= ARID && wet <= SEMIARID) return ASHEN_SAVANNA;
            if (heat == TROPICAL && wet >= HUMID) return TROPICAL_JUNGLE;
            return NONE;
        }

        if (s.relElev >= HIGH_M) {
            return NONE;
        }

        if (heat >= WARM && wet <= ARID && !raised && v01(OASIS, s) > OASIS_THRESHOLD) {
            return wet == HYPERARID ? DESERT_OASIS : RED_OASIS;
        }

        if (heat <= TEMPERATE && wet >= SUBHUMID && raised && v01(GEOTHERMAL, s) > GEOTHERMAL_THRESHOLD) {
            return YELLOWSTONE;
        }

        if (s.moist < SHOWPIECE_MIN_MOIST || v01(SHOWPIECE, s) <= SHOWPIECE_THRESHOLD) {
            return NONE;
        }
        if (heat == TROPICAL) {
            if (wet < HUMID) return NONE;
            return raised ? AMETHYST_CANYON : AMETHYST_RAINFOREST;
        }
        if (heat == BOREAL) {
            return NONE;
        }

        float which = v01(SHOWPIECE_PICK, s);
        if (which < Q_BOT_25) return raised ? LAVENDER_VALLEY : LAVENDER_FOREST;
        if (which < Q_MEDIAN) return raised ? SAKURA_VALLEY : SAKURA_GROVE;
        if (which < Q_TOP_25) return raised ? MOONLIGHT_VALLEY : MOONLIGHT_GROVE;
        return raised ? BLOOMING_PLATEAU : BLOOMING_VALLEY;
    }

    private static short cliff(TerrainSample s, int heat, int wet, boolean snowy) {
        boolean coastal = s.altM < COAST_CLIFF_M && s.altM - s.relElev < COAST_BASE_M;
        float local = v01(VARIANT_LOCAL, s);
        if (snowy) {
            if (coastal) {
                return s.temp < BiomeClassifier.FROZEN_SURFACE_C
                        ? FROZEN_CLIFFS
                        : BiomeClassifier.SNOWY_SLOPES;
            }
            if (!BiomeClassifier.frozenSurface(s)) return BiomeClassifier.SNOWY_SLOPES;
            if (s.temp < ICE_C && local > GLACIER_THRESHOLD) return GLACIAL_CHASM;
            return local < Q_BOT_25 ? FROZEN_CLIFFS : BiomeClassifier.FROZEN_PEAKS;
        }
        if (heat >= TEMPERATE && s.relElev < HIGH_M && volcanic(s)) return BASALT_CLIFFS;
        if (heat >= WARM && wet <= SEMIARID) {
            if (wet == HYPERARID) return DESERT_SPIRES;
            if (wet == ARID) return BRYCE_CANYON;
            return SAVANNA_SLOPES;
        }
        if (heat == TROPICAL && wet >= HUMID) {
            return v01(SHOWPIECE, s) > CANYON_SHOWPIECE_THRESHOLD ? AMETHYST_CANYON : ROCKY_JUNGLE;
        }
        if (coastal && heat >= TEMPERATE) {
            return local > Q_MEDIAN ? WHITE_CLIFFS : GRANITE_CLIFFS;
        }
        if (heat == BOREAL) return WINDSWEPT_SPIRES;
        if (wet >= HUMID) return local > Q_TOP_25 ? WHITE_CLIFFS : YOSEMITE_CLIFFS;
        return local > Q_TOP_25 ? GRANITE_CLIFFS : STONY_SPIRES;
    }

    private static short snowy(TerrainSample s, int zoneHeat, int wet) {
        boolean steep = s.slope >= STEEP;
        boolean high = s.relElev >= HIGH_M;
        boolean hill = s.relElev >= UPPER_M || s.slope >= GENTLE;
        boolean trees = s.temp > TREE_LINE_C;
        boolean lowFlat = s.slope < GENTLE && (s.altM < SNOW_FLAT_M || s.relElev < BASIN_M);
        boolean marsh = wetland(s);
        float broad = v01(VARIANT_BROAD, s);

        if (s.relElev >= UPPER_M && s.temp < GEM_PEAK_C && massif(EMERALD, s) > EMERALD_THRESHOLD) {
            return EMERALD_PEAKS;
        }

        if (s.temp < ICE_C && (high || steep) && BiomeClassifier.frozenSurface(s)
                && v01(VARIANT_LOCAL, s) > GLACIER_THRESHOLD) {
            return GLACIAL_CHASM;
        }

        if (steep) {
            if (BiomeClassifier.frozenSurface(s)) return BiomeClassifier.FROZEN_PEAKS;
            if (wet >= HUMID && trees) return BiomeClassifier.GROVE;
            return BiomeClassifier.SNOWY_SLOPES;
        }

        if (wet <= ARID) {
            if (hill) {
                if (zoneHeat >= BOREAL) return SNOWY_BADLANDS;
                return v01(VARIANT_LOCAL, s) > Q_MEDIAN ? ROCKY_SHRUBLAND : COLD_SHRUBLAND;
            }
            if (wet == HYPERARID) return GRAVEL_DESERT;
            return zoneHeat >= BOREAL ? SNOWY_BADLANDS : COLD_SHRUBLAND;
        }

        if (high && !trees) {
            return BiomeClassifier.SNOWY_SLOPES;
        }

        if (!trees || wet == SEMIARID) {
            if (hill) return BiomeClassifier.SNOWY_SLOPES;
            if (wet == WET && marsh) return ICE_MARSH;
            if (wet == SEMIARID && !trees) return GRAVEL_DESERT;
            return lowFlat ? WINTRY_LOWLANDS : BiomeClassifier.SNOWY_PLAINS;
        }

        if (wet == SUBHUMID) {
            if (hill) return broad > Q_MEDIAN ? SNOWY_MAPLE_FOREST : BiomeClassifier.GROVE;
            return BiomeClassifier.SNOWY_TAIGA_SPARSE;
        }

        if (wet == HUMID) {
            if (high) return ALPINE_GROVE;
            if (v01(SHOWPIECE, s) > SNOWY_SHOWPIECE_THRESHOLD) return SNOWY_CHERRY_GROVE;
            if (hill) return broad > Q_MEDIAN ? SNOWY_SHIELD : BiomeClassifier.GROVE;
            return broad > Q_MEDIAN ? WINTRY_FOREST : BiomeClassifier.SNOWY_TAIGA;
        }

        if (marsh) return ICE_MARSH;
        if (high) return ALPINE_GROVE;
        return hill ? SIBERIAN_GROVE : SIBERIAN_TAIGA;
    }

    private static short high(TerrainSample s, int heat, int zoneHeat, int wet) {
        boolean steep = s.slope >= STEEP;
        boolean gentle = s.slope < GENTLE;
        float broad = v01(VARIANT_BROAD, s);

        if (s.temp >= WARM_ROCK_C) {
            if (wet <= SEMIARID) {
                if (heat >= WARM) {
                    if (steep) return BRYCE_CANYON;
                    if (gentle && wet <= ARID && v01(SHOWPIECE, s) > SHOWPIECE_THRESHOLD) return WARPED_MESA;
                    return PAINTED_MOUNTAINS;
                }
                return steep ? BiomeClassifier.STONY_PEAKS : ROCKY_MOUNTAINS;
            }
            if (wet == SUBHUMID) {
                if (heat >= WARM) return steep ? STONY_SPIRES : TEMPERATE_HIGHLANDS;
                return steep ? WINDSWEPT_SPIRES : ROCKY_MOUNTAINS;
            }
            if (heat == TROPICAL) return wet == WET && broad > Q_MEDIAN ? CLOUD_FOREST : JUNGLE_MOUNTAINS;
            if (wet == WET) return CLOUD_FOREST;
            if (heat == WARM) return steep ? STONY_SPIRES : FORESTED_HIGHLANDS;
            return steep ? YOSEMITE_CLIFFS : HAZE_MOUNTAIN;
        }

        if (wet <= SEMIARID) {
            if (steep) return zoneHeat >= WARM || broad > Q_MEDIAN ? BiomeClassifier.STONY_PEAKS : WINDSWEPT_SPIRES;
            return gentle ? HIGHLANDS : ROCKY_MOUNTAINS;
        }
        if (gentle) return BiomeClassifier.MEADOW;
        if (steep) return WINDSWEPT_SPIRES;
        if (wet == WET) return HAZE_MOUNTAIN;
        return broad > Q_MEDIAN ? ROCKY_MOUNTAINS : HAZE_MOUNTAIN;
    }

    private static short upper(TerrainSample s, int heat, int wet) {
        boolean steep = s.slope >= STEEP;
        float broad = v01(VARIANT_BROAD, s);
        switch (heat) {
            case TROPICAL:
                switch (wet) {
                    case HYPERARID: return steep ? DESERT_SPIRES : DESERT_CANYON;
                    case ARID: return steep ? BRYCE_CANYON : mesa(s);
                    case SEMIARID: return steep ? SAVANNA_SLOPES : broad > Q_MEDIAN ? SAVANNA_BADLANDS : FRACTURED_SAVANNA;
                    case SUBHUMID: return TEMPERATE_HIGHLANDS;
                    default: return steep ? JUNGLE_MOUNTAINS : ROCKY_JUNGLE;
                }
            case WARM:
                switch (wet) {
                    case HYPERARID: return steep ? DESERT_SPIRES : SANDSTONE_VALLEY;
                    case ARID: return steep ? SAVANNA_SLOPES : ARID_HIGHLANDS;
                    case SEMIARID: return steep ? SAVANNA_SLOPES : broad > Q_MEDIAN ? ARID_HIGHLANDS : SAVANNA_BADLANDS;
                    case SUBHUMID: return TEMPERATE_HIGHLANDS;
                    case HUMID: return steep ? STONY_SPIRES : FORESTED_HIGHLANDS;
                    default: return steep ? STONY_SPIRES : HAZE_MOUNTAIN;
                }
            case TEMPERATE:
                switch (wet) {
                    case HYPERARID: case ARID: return steep ? BiomeClassifier.WINDSWEPT_HILLS : dryRise(s);
                    case SEMIARID: return steep ? BiomeClassifier.WINDSWEPT_HILLS : HIGHLANDS;
                    case SUBHUMID: return steep ? STONY_SPIRES : TEMPERATE_HIGHLANDS;
                    case HUMID: return steep ? YOSEMITE_CLIFFS : FORESTED_HIGHLANDS;
                    default: return steep ? YOSEMITE_CLIFFS : HAZE_MOUNTAIN;
                }
            default:
                switch (wet) {
                    case HYPERARID: case ARID: return steep ? BiomeClassifier.WINDSWEPT_HILLS : dryRise(s);
                    case SEMIARID: return steep ? WINDSWEPT_SPIRES : ALPINE_HIGHLANDS;
                    case SUBHUMID: return steep ? WINDSWEPT_SPIRES : ALPINE_HIGHLANDS;
                    case HUMID: return steep ? WINDSWEPT_SPIRES : broad > Q_MEDIAN ? SHIELD : FORESTED_HIGHLANDS;
                    default: return steep ? WINDSWEPT_SPIRES : HAZE_MOUNTAIN;
                }
        }
    }

    private static short plateau(TerrainSample s, int heat, int wet) {
        float broad = v01(VARIANT_BROAD, s);
        switch (heat) {
            case TROPICAL:
            case WARM:
                switch (wet) {
                    case HYPERARID: case ARID: return mesa(s);
                    case SEMIARID: return heat == TROPICAL ? SAVANNA_BADLANDS : ARID_HIGHLANDS;
                    case SUBHUMID: return TEMPERATE_HIGHLANDS;
                    case HUMID: return heat == TROPICAL ? ROCKY_JUNGLE : FORESTED_HIGHLANDS;
                    default: return heat == TROPICAL ? TROPICAL_JUNGLE : FORESTED_HIGHLANDS;
                }
            case TEMPERATE:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.FOREST_SPARSE, Q_TOP_25);
                    case SEMIARID: return broad > Q_MEDIAN ? BiomeClassifier.MEADOW : HIGHLANDS;
                    case SUBHUMID: return TEMPERATE_HIGHLANDS;
                    default: return FORESTED_HIGHLANDS;
                }
            default:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.TAIGA_SPARSE, Q_TOP_25);
                    case SEMIARID: return HIGHLANDS;
                    case SUBHUMID: return ALPINE_HIGHLANDS;
                    default: return broad > Q_MEDIAN ? SHIELD : FORESTED_HIGHLANDS;
                }
        }
    }

    private static short steppe(TerrainSample s, short grove, float groveAbove) {
        return s.moist >= WET_EDGES[0] && v01(VARIANT_LOCAL, s) > groveAbove ? grove : STEPPE;
    }

    private static short dryRise(TerrainSample s) {
        return v01(VARIANT_LOCAL, s) > Q_BOT_33 ? BiomeClassifier.WINDSWEPT_HILLS : STEPPE;
    }

    private static short mesa(TerrainSample s) {
        if (v01(SHOWPIECE, s) > MESA_SHOWPIECE_THRESHOLD) {
            return v01(SHOWPIECE_PICK, s) < Q_MEDIAN ? WARPED_MESA : WHITE_MESA;
        }
        return BiomeClassifier.BADLANDS;
    }

    private static short valley(TerrainSample s, int heat, int wet) {
        boolean flatLow = wetland(s);
        switch (heat) {
            case TROPICAL:
                switch (wet) {
                    case HYPERARID: case ARID: return SANDSTONE_VALLEY;
                    case SEMIARID: return BiomeClassifier.SAVANNA;
                    case SUBHUMID: return BRUSHLAND;
                    case HUMID: return BiomeClassifier.JUNGLE;
                    default: return flatLow ? ORCHID_SWAMP : TROPICAL_JUNGLE;
                }
            case WARM:
                switch (wet) {
                    case HYPERARID: case ARID: return SANDSTONE_VALLEY;
                    case SEMIARID: return BRUSHLAND;
                    case SUBHUMID: return BiomeClassifier.FOREST_SPARSE;
                    case HUMID: return BiomeClassifier.FOREST;
                    default: return flatLow ? BiomeClassifier.SWAMP : BiomeClassifier.FOREST;
                }
            case TEMPERATE:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.FOREST_SPARSE, Q_MEDIAN);
                    case SEMIARID: return VALLEY_CLEARING;
                    case SUBHUMID: return YOSEMITE_LOWLANDS;
                    default: return LUSH_VALLEY;
                }
            default:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.TAIGA_SPARSE, Q_MEDIAN);
                    case SEMIARID: return VALLEY_CLEARING;
                    case SUBHUMID: return BiomeClassifier.TAIGA_SPARSE;
                    default: return LUSH_VALLEY;
                }
        }
    }

    private static short base(TerrainSample s, int heat, int wet) {
        boolean flatLow = wetland(s);
        float broad = v01(VARIANT_BROAD, s);
        switch (heat) {
            case TROPICAL:
                switch (wet) {
                    case HYPERARID: return broad > Q_TOP_20 ? ANCIENT_SANDS : BiomeClassifier.DESERT;
                    case ARID: return s.relElev + RELIEF_SPLIT_M * (broad - Q_MEDIAN) > 0f ? mesa(s) : LUSH_DESERT;
                    case SEMIARID: return broad > Q_TOP_33 ? HOT_SHRUBLAND : BiomeClassifier.SAVANNA;
                    case SUBHUMID: return broad > Q_MEDIAN ? BiomeClassifier.FOREST_SPARSE : BRUSHLAND;
                    case HUMID: return BiomeClassifier.JUNGLE;
                    default: return flatLow ? ORCHID_SWAMP : TROPICAL_JUNGLE;
                }
            case WARM:
                switch (wet) {
                    case HYPERARID: return broad > Q_TOP_20 ? ANCIENT_SANDS : BiomeClassifier.DESERT;
                    case ARID: return SHRUBLAND;
                    case SEMIARID: return s.moist < SHRUB_EDGE_MOIST ? SHRUBLAND : BRUSHLAND;
                    case SUBHUMID: return BiomeClassifier.FOREST_SPARSE;
                    case HUMID: return BiomeClassifier.FOREST;
                    default: return flatLow ? BiomeClassifier.SWAMP : BiomeClassifier.FOREST;
                }
            case TEMPERATE:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.FOREST_SPARSE, Q_TOP_25);
                    case SEMIARID: return v01(VARIANT_LOCAL, s) > Q_TOP_25 ? BiomeClassifier.FOREST_SPARSE : broad > Q_MEDIAN ? BiomeClassifier.PLAINS : STEPPE;
                    case SUBHUMID: return clearing(s) ? VALLEY_CLEARING : BiomeClassifier.FOREST_SPARSE;
                    case HUMID: return BiomeClassifier.FOREST;
                    default: return flatLow ? BiomeClassifier.SWAMP : YOSEMITE_LOWLANDS;
                }
            default:
                switch (wet) {
                    case HYPERARID: case ARID: return steppe(s, BiomeClassifier.TAIGA_SPARSE, Q_TOP_25);
                    case SEMIARID: return broad > Q_MEDIAN ? HIGHLANDS : ALPINE_HIGHLANDS;
                    case SUBHUMID: return broad > Q_TOP_33 ? BIRCH_TAIGA : BiomeClassifier.TAIGA_SPARSE;
                    case HUMID:
                        if (clearing(s)) return SHIELD_CLEARING;
                        if (broad > Q_TOP_33) return SHIELD;
                        return broad < Q_BOT_33 ? BIRCH_TAIGA : BiomeClassifier.TAIGA;
                    default: return SHIELD;
                }
        }
    }
}
