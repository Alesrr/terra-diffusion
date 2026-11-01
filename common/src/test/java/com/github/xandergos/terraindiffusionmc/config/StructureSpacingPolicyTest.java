package com.github.xandergos.terraindiffusionmc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import com.github.xandergos.terraindiffusionmc.platform.PlatformPaths;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StructureSpacingPolicyTest {
    @BeforeAll
    static void isolateConfig(@TempDir Path directory) {
        PlatformPaths.configure(directory.resolve("config"), directory);
    }

    @Test
    void autoPreservesTheSavedPolicyAndOverridesAreExplicit() {
        assertTrue(TerrainDiffusionConfig.resolveStructureSpacing("auto", true));
        assertFalse(TerrainDiffusionConfig.resolveStructureSpacing("auto", false));
        assertTrue(TerrainDiffusionConfig.resolveStructureSpacing("true", false));
        assertFalse(TerrainDiffusionConfig.resolveStructureSpacing("false", true));
        assertThrows(IllegalArgumentException.class,
                () -> TerrainDiffusionConfig.resolveStructureSpacing("treu", true));
    }
}
