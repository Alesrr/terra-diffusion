package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldScaleSettingsStateTest {
    @Test
    void oldSavesKeepTheirGridAndNewSavesPersistTheNewPolicy() {
        CompoundTag old = new CompoundTag();
        old.putInt("scale", 4);
        old.putBoolean("explicit_scale", true);
        var legacy = WorldScaleSettingsState.fromNbt(old, null);
        assertEquals(4, legacy.getScale());
        assertTrue(legacy.hasExplicitScale());
        assertFalse(legacy.scaleStructureSpacing());
        assertFalse(legacy.isDirty());

        var fresh = WorldScaleSettingsState.createDefault();
        fresh.setScale(4);
        assertTrue(fresh.scaleStructureSpacing());
        var reloaded = WorldScaleSettingsState.fromNbt(fresh.save(new CompoundTag(), null), null);
        assertTrue(reloaded.scaleStructureSpacing());
        reloaded.setScaleStructureSpacing(false);
        assertTrue(reloaded.isDirty());
        assertFalse(WorldScaleSettingsState.fromNbt(reloaded.save(new CompoundTag(), null), null).scaleStructureSpacing());
    }

    @Test
    void anExplicitAdoptionPersistsAndCorruptLegacySettingsNeverAutoEnableScaling() {
        var legacy = WorldScaleSettingsState.fromNbt(new CompoundTag(), null);
        legacy.setScaleStructureSpacing(true);
        assertTrue(legacy.isDirty());
        assertTrue(WorldScaleSettingsState.fromNbt(legacy.save(new CompoundTag(), null), null).scaleStructureSpacing());
        CompoundTag invalid = new CompoundTag();
        invalid.putString("scale", "invalid");
        assertFalse(WorldScaleSettingsState.fromNbt(invalid, null).scaleStructureSpacing());
    }
}
