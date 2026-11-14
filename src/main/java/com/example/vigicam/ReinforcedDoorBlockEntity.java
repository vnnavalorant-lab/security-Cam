package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/** Só existe no bloco central de baixo. No cliente guarda o quanto a porta já abriu (0 = fechada, 1 = aberta) para animar. */
public class ReinforcedDoorBlockEntity extends BlockEntity {
    /** Quanto o progresso anda por tick: abre/fecha em 10 ticks (0,5 s). */
    private static final float STEP = 0.1f;

    private float prev, cur;
    private boolean started;

    public ReinforcedDoorBlockEntity(BlockPos pos, BlockState state) {
        super(VigiCam.REINFORCED_DOOR_BE, pos, state);
    }

    public static void clientTick(World world, BlockPos pos, BlockState state, ReinforcedDoorBlockEntity be) {
        float target = state.get(ReinforcedDoorBlock.OPEN) ? 1f : 0f;
        if (!be.started) {   // porta que já estava aberta ao carregar o chunk não faz a animação
            be.prev = be.cur = target;
            be.started = true;
            return;
        }
        be.prev = be.cur;
        if (be.cur < target) be.cur = Math.min(target, be.cur + STEP);
        else if (be.cur > target) be.cur = Math.max(target, be.cur - STEP);
    }

    /** Progresso suavizado entre ticks, 0..1. */
    public float progress(float tickDelta) {
        return MathHelper.lerp(tickDelta, prev, cur);
    }
}
