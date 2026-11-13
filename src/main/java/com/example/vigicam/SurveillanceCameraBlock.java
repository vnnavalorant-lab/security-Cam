package com.example.vigicam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public class SurveillanceCameraBlock extends HorizontalFacingBlock {
    private static final VoxelShape SHAPE = Block.createCuboidShape(2, 2, 2, 14, 14, 14);

    public SurveillanceCameraBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // a câmera olha para o mesmo lado que o player estava olhando ao colocar
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPE;
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity sp) {
            if (player.getStackInHand(hand).getItem() instanceof RemoteItem) {
                RemoteItem.connectTo(sp, player.getStackInHand(hand), RemoteItem.Link.camera(world, pos));
            } else {
                DroneControl.release(sp.getUuid());
                VigiCam.sendViewCamera(sp, pos, state.get(FACING).asRotation(), -1, 0);
            }
        }
        return ActionResult.SUCCESS;
    }
}
