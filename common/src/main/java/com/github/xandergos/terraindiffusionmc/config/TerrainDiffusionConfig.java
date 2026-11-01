package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "terrain-diffusion-mc.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;
    private static final int DEFAULT_TILE_CACHE_MB = 512;

    static {
        loadDefaults();
        Path configPath = resolveConfigPath();
        if (configPath != null) {
            loadOverrides(configPath);
        }
    }

    private TerrainDiffusionConfig() {
    }

    // Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU)
    public static String inferenceDevice() {
        String device = readString("inference.device", "gpu");
        // On the CPU build "gpu" is meaningless (no dedicated GPU provider), so treat it as "auto":
        if ("cpu".equals(BUILD_VARIANT)) {
            return "auto";
        }
        return device;
    }

    // Whether to offload inactive models from VRAM between pipeline stages
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", DEFAULT_OFFLOAD_MODELS);
    }

    // TCP port for the local terrain explorer HTTP server
    public static int explorerPort() {
        return readInt("explorer.port", DEFAULT_EXPLORER_PORT);
    }

    // Whether to validate SHA-256 for pre-existing local model files before use
    public static boolean validateModel() {
        return readBoolean("validate_model", DEFAULT_VALIDATE_MODEL);
    }

    // Initial coarse-pixel radius for spawn land search (NxN region centered at origin)
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    // Maximum coarse-pixel region size for spawn land search before giving up
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    public static boolean snowDepthScaling() {
        return readBoolean("snow.depth_scaling", true);
    }

    public static int snowMaxLayersOverVegetation() {
        return Math.max(1, Math.min(8, readInt("snow.max_layers_over_vegetation", 6)));
    }

    public static boolean terralithEnabled() {
        return readBoolean("terralith.enabled", true);
    }

    public static boolean scaleStructureSpacing() {
        return readBoolean("structures.scale_spacing", true);
    }

    public static boolean terralithInjectSurfaceRules() {
        return readBoolean("terralith.inject_surface_rules", true);
    }

    // Decoded model windows kept in memory, per tensor
    public static int tileCacheMb() {
        String override = System.getProperty("terradiff.tileCacheMb");
        if (override != null) {
            try {
                return Math.max(16, Integer.parseInt(override.trim()));
            } catch (NumberFormatException ignored) {
                // fall through to the config value
            }
        }
        return Math.max(16, readInt("tile_cache_mb", DEFAULT_TILE_CACHE_MB));
    }

    // Whether to adapt TFMG's oil and striated ore generation to this dimension
    public static boolean tfmgEnabled() {
        return readBoolean("tfmg.enabled", true);
    }

    // Y the oil deposit and oil well features start from
    public static int tfmgOilY() {
        return readInt("tfmg.oil_y", -189);
    }

    // Block count of a striated ore body
    public static int tfmgStriatedOreSize() {
        return readInt("tfmg.striated_ore_size", 64);
    }

    // One placement in this many produces a deposit
    public static int tfmgDepositRarity() {
        return readInt("tfmg.oil_deposit_rarity", 20);
    }

    // Upper bound on the pockets a single deposit placement scatters
    public static int tfmgDepositBlobs() {
        return readInt("tfmg.oil_deposit_blobs", 6);
    }

    // Upper bound on the height of one oil pocket, in blocks
    public static int tfmgDepositHeight() {
        return readInt("tfmg.oil_deposit_height", 25);
    }

    // How far pockets of one deposit wander from the placement
    public static int tfmgDepositSpread() {
        return readInt("tfmg.oil_deposit_spread", 8);
    }

    // Where an oil well's column stops, relative to the surface
    public static int tfmgWellSurfaceOffset() {
        return readInt("tfmg.oil_well_surface_offset", 0);
    }

    // Radius of the slick an oil well leaves at the surface
    public static int tfmgWellPoolRadius() {
        return readInt("tfmg.oil_well_pool_radius", 8);
    }

    // Region side length in blocks
    private static int tileSizeCache;

    public static int tileSize() {
        int cached = tileSizeCache;
        if (cached > 0) {
            return cached;
        }
        int configuredTileSize = readInt("tile_size", DEFAULT_TILE_SIZE);
        if (configuredTileSize <= 0 || !isPowerOfTwo(configuredTileSize)) {
            System.err.println("Invalid tile_size: " + configuredTileSize + ", using default " + DEFAULT_TILE_SIZE);
            configuredTileSize = DEFAULT_TILE_SIZE;
        }
        tileSizeCache = configuredTileSize;
        return configuredTileSize;
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "gpu");
            PROPERTIES.setProperty("validate_model", String.valueOf(DEFAULT_VALIDATE_MODEL));
            PROPERTIES.setProperty("tile_size", String.valueOf(DEFAULT_TILE_SIZE));
            PROPERTIES.setProperty("tile_cache_mb", String.valueOf(DEFAULT_TILE_CACHE_MB));
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    private static Path resolveConfigPath() {
        try {
            return PlatformPaths.configDir().resolve(FILE_NAME);
        } catch (RuntimeException e) {
            System.err.println("Loader config directory unavailable: " + e.getMessage());
            return null;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream != null) {
                Files.copy(defaultConfigInputStream, configPath);
                return;
            }
            System.err.println("Default config resource not found: " + RESOURCE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }
}
