package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/** Só existe para que o bloco possa ser desenhado (e animado) por um BlockEntityRenderer. */
public class SurveillanceCameraBlockEntity extends BlockEntity {
    public SurveillanceCameraBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.CAMERA_BE, pos, state);
    }
}
