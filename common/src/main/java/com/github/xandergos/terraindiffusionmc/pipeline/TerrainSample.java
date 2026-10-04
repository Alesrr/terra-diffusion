package com.github.xandergos.terraindiffusionmc.pipeline;

public final class TerrainSample {
    public int worldX, worldZ;

    public float elev;

    public float altM;

    public float slope;

    public float temp;

    public float tReg, relElev, moist, frost;

    public float tStd;

    public float precip;

    public float pCV;

    public float aridity;

    public float treeMoisture;

    public float growingSeason;

    public float effTreeMoisture;

    public float bareThreshold;

    public boolean treesNone, treesSparse, treesForest, treesDense, treesRainforest;

    public boolean barren;
    public boolean tooArid, tooCold;

    public boolean slopeMedium, slopeBare;
    public boolean hasSnow;

    public boolean isOcean;

    public boolean mountains;

    public boolean lowland;

    public boolean frozen, cold, cool, temperate, warm, hot;
}
