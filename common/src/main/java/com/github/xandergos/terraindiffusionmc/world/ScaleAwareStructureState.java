package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.RegistryAccess;

public interface ScaleAwareStructureState {
    void terrainDiffusion$configureStructureScale(int scale, RegistryAccess registries);
}
