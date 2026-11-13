package com.example.vigicam;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.server.world.ServerWorld;

public class DroneItem extends Item {
    public DroneItem(Settings settings) { super(settings); }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World world = ctx.getWorld();
        if (!(world instanceof ServerWorld sw)) return ActionResult.SUCCESS;

        BlockPos pos = ctx.getBlockPos().offset(ctx.getSide());
        if (!sw.getBlockState(pos).getCollisionShape(sw, pos).isEmpty()) return ActionResult.FAIL;

        DroneEntity drone = VigiCam.DRONE.create(sw);
        if (drone == null) return ActionResult.FAIL;
        drone.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, ctx.getPlayerYaw(), 0f);
        sw.spawnEntity(drone);

        PlayerEntity player = ctx.getPlayer();
        if (player == null || !player.getAbilities().creativeMode) ctx.getStack().decrement(1);
        return ActionResult.CONSUME;
    }
}
