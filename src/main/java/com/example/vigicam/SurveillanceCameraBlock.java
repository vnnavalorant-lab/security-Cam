package com.example.vigicam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
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
import org.jetbrains.annotations.Nullable;

public class SurveillanceCameraBlock extends HorizontalFacingBlock implements BlockEntityProvider {
    // o modelo ocupa 6px de largura e vai de uma extremidade do bloco à outra (base encostada na extremidade oposta à lente)
    private static final VoxelShape SHAPE_NS = Block.createCuboidShape(5, 0, 0, 11, 12, 16);
    private static final VoxelShape SHAPE_EW = Block.createCuboidShape(0, 0, 5, 16, 12, 11);

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
        // a câmera é colocada virada para o jogador (lente apontando para ele, base na extremidade oposta)
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return state.get(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }

    // o desenho é feito pelo CameraBlockEntityRenderer (modelo animado)
    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SurveillanceCameraBlockEntity(pos, state);
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

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (!world.isClient || type != VigiCam.CAMERA_BE) return null;   // só o cliente precisa (som em looping)
        BlockEntityTicker<SurveillanceCameraBlockEntity> ticker = SurveillanceCameraBlockEntity::clientTick;
        return (BlockEntityTicker<T>) ticker;
    }
}
