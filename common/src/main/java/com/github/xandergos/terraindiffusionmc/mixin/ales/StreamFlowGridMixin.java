package com.github.xandergos.terraindiffusionmc.mixin.ales;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.streamsreflowing.flow.StreamFlowGrid", remap = false)
public abstract class StreamFlowGridMixin {

    @Inject(method = "columnFallback(Lnet/minecraft/world/level/BlockGetter;III)B", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private static void terrainDiffusion$noFallback(BlockGetter level, int x, int y, int z,
                                                    CallbackInfoReturnable<Byte> cir) {
        if (level instanceof ServerLevel server
                && server.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource) {
            cir.setReturnValue((byte) -1);
        }
    }
}
