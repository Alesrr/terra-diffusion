package com.github.xandergos.terraindiffusionmc.mixin.ales;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OceanMonumentStructure.class)
public class OceanMonumentStructureMixin {

    @Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"))
    private static void terrainDiffusion$keepSeat(ChunkPos chunkPos, long seed, PiecesContainer saved,
                                                 CallbackInfoReturnable<PiecesContainer> cir) {
        PiecesContainer fresh = cir.getReturnValue();
        if (saved.isEmpty() || fresh.isEmpty()) {
            return;
        }
        int drop = saved.pieces().get(0).getBoundingBox().minY() - fresh.pieces().get(0).getBoundingBox().minY();
        if (drop == 0) {
            return;
        }
        for (StructurePiece piece : fresh.pieces()) {
            piece.move(0, drop, 0);
        }
    }
}
