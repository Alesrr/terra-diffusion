package com.github.xandergos.terraindiffusionmc.mixin.ales;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Holder.Reference.class)
public interface HolderReferenceAccessor<T> {
    @Invoker("bindValue")
    void terrainDiffusion$bindValue(T value);
}
