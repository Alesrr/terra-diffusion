package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;

import java.util.Collection;

public final class SnowFreeBiomes {
    private record Signature(float temperature, int skyColor, int fogColor, int grassColor) {
        static Signature of(Biome biome) {
            BiomeSpecialEffects effects = biome.getSpecialEffects();
            return new Signature(biome.getBaseTemperature(), effects.getSkyColor(), effects.getFogColor(),
                    effects.getGrassColorOverride().orElse(-1));
        }

        boolean matches(Biome biome) {
            if (biome.getBaseTemperature() != temperature) return false;
            BiomeSpecialEffects effects = biome.getSpecialEffects();
            return effects.getSkyColor() == skyColor && effects.getFogColor() == fogColor
                    && effects.getGrassColorOverride().orElse(-1) == grassColor;
        }
    }

    private static volatile Signature[] signatures = new Signature[0];

    private SnowFreeBiomes() {
    }

    static void set(Collection<Biome> biomes) {
        signatures = biomes.stream().map(Signature::of).toArray(Signature[]::new);
    }

    public static boolean contains(Biome biome) {
        for (Signature signature : signatures) {
            if (signature.matches(biome)) return true;
        }
        return false;
    }
}
