package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.saveddata.SavedData;

// Persisted per-world settings for terrain diffusion
public final class WorldScaleSettingsState extends SavedData {

    // Missing spacing policy means a legacy save, even though newly created worlds opt in.
    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("scale", WorldScaleManager.DEFAULT_SCALE)
                    .forGetter(WorldScaleSettingsState::getScale),
            Codec.BOOL.optionalFieldOf("explicit_scale", false)
                    .forGetter(WorldScaleSettingsState::hasExplicitScale),
            Codec.BOOL.optionalFieldOf("scale_structure_spacing", false)
                    .forGetter(WorldScaleSettingsState::scaleStructureSpacing)
    ).apply(instance, WorldScaleSettingsState::new));

    private int scale;
    private boolean explicitScale;
    private boolean scaleStructureSpacing;

    // Creates a default state for worlds that do not yet have saved terrain diffusion settings
    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale, boolean scaleStructureSpacing) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
        this.scaleStructureSpacing = scaleStructureSpacing;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, true);
    }

    // Type descriptor used by the saved data storage
    public static final SavedData.Factory<WorldScaleSettingsState> TYPE =
            new SavedData.Factory<>(
                    WorldScaleSettingsState::createDefault,
                    WorldScaleSettingsState::fromNbt,
                    null
            );

    // Type descriptor helper
    public static WorldScaleSettingsState fromNbt(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        return CODEC.parse(NbtOps.INSTANCE, nbt)
                .result()
                .orElseGet(() -> new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false, false));
    }

    // Type descriptor helper
    @Override
    public CompoundTag save(CompoundTag nbt, HolderLookup.Provider registryLookup) {
        CODEC.encodeStart(NbtOps.INSTANCE, this)
                .result()
                .ifPresent(encoded -> nbt.merge((CompoundTag) encoded));
        return nbt;
    }

    // Returns the currently persisted world scale
    public int getScale() {
        return scale;
    }

    // Returns whether this world has an explicitly chosen scale
    public boolean hasExplicitScale() {
        return explicitScale;
    }

    public boolean scaleStructureSpacing() {
        return scaleStructureSpacing;
    }

    public void setScaleStructureSpacing(boolean enabled) {
        if (scaleStructureSpacing != enabled) {
            scaleStructureSpacing = enabled;
            setDirty();
        }
    }

    // Applies a new persisted world scale and marks the state dirty
    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        setDirty();
    }
}
