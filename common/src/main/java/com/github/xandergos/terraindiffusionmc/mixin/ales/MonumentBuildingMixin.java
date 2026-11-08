package com.github.xandergos.terraindiffusionmc.mixin.ales;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public abstract class MonumentBuildingMixin extends StructurePiece {
    @Shadow
    @Final
    private List<? extends StructurePiece> childPieces;

    protected MonumentBuildingMixin(StructurePieceType type, int genDepth, BoundingBox box) {
        super(type, genDepth, box);
    }

    @Override
    public void move(int x, int y, int z) {
        super.move(x, y, z);
        for (StructurePiece child : this.childPieces) {
            child.move(x, y, z);
        }
    }
}
