package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Existe para o BlockEntityRenderer desenhar (e animar) o bloco e para tocar o som ambiente da câmera. */
public class SurveillanceCameraBlockEntity extends BlockEntity {
    public SurveillanceCameraBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.CAMERA_BE, pos, state);
    }

    /** Só o cliente chama: mantém o som da câmera em looping. */
    public static void clientTick(World world, BlockPos pos, BlockState state, SurveillanceCameraBlockEntity be) {
        VigiSounds.cameraTick(pos, world.getTime());
    }
}
