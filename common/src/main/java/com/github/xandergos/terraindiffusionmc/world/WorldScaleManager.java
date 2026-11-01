package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.server.level.ServerLevel;

// Runtime access for world-scoped terrain scale
public final class WorldScaleManager {
    public static final int DEFAULT_SCALE = 2;
    private static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;
    private static final String SETTINGS_ID = "terrain_diffusion_world_settings";

    private static volatile int currentScale = DEFAULT_SCALE;

    private WorldScaleManager() {
    }

    // Loads or creates per-world scale settings and sets the active runtime value
    public static void initializeForWorld(ServerLevel serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = settingsForWorld(serverWorld);

        if (!worldScaleSettingsState.hasExplicitScale()) {
            Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
            int resolvedScale = pendingScale != null ? pendingScale : DEFAULT_SCALE;
            worldScaleSettingsState.setScale(resolvedScale);
        }

        currentScale = clampScale(worldScaleSettingsState.getScale());
    }

    public static WorldScaleSettingsState settingsForWorld(ServerLevel serverWorld) {
        WorldScaleSettingsState worldScaleSettingsState = serverWorld.getDataStorage()
                .get(WorldScaleSettingsState.TYPE, SETTINGS_ID);
        if (worldScaleSettingsState == null) {
            worldScaleSettingsState = WorldScaleSettingsState.createDefault();
            // A pre-existing world may predate even the scale-settings record.
            boolean freshTerrainWorld = !serverWorld.getServer().getWorldData().overworldData().isInitialized()
                    && serverWorld.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource;
            worldScaleSettingsState.setScaleStructureSpacing(freshTerrainWorld);
            serverWorld.getDataStorage().set(SETTINGS_ID, worldScaleSettingsState);
        }

        return worldScaleSettingsState;
    }

    // Returns the currently active world scale
    public static int getCurrentScale() {
        return currentScale;
    }

    // Updates world scale for the currently loaded world and persists it immediately
    public static void setCurrentScale(ServerLevel serverWorld, int configuredScale) {
        int clampedScale = clampScale(configuredScale);

        WorldScaleSettingsState worldScaleSettingsState = settingsForWorld(serverWorld);

        worldScaleSettingsState.setScale(clampedScale);
        currentScale = clampedScale;
    }

    // Clamps world scale to supported runtime bounds
    public static int clampScale(int configuredScale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, configuredScale));
    }
}
