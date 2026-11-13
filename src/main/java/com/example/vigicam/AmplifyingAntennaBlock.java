package com.example.vigicam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/** Antena amplificadora: quem coloca é o dono; amplia o sinal dos drones do dono (+50 blocos de alcance). */
public class AmplifyingAntennaBlock extends HorizontalFacingBlock implements BlockEntityProvider {
    /** Verdadeiro enquanto um drone do dono está usando o sinal desta antena (partículas mais intensas). */
    public static final BooleanProperty ACTIVE = BooleanProperty.of("active");

    // a base do modelo ocupa quase o bloco inteiro; o mastro sobe além dele (desenhado pelo renderer)
    private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 16, 15);

    public AmplifyingAntennaBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(ACTIVE, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()).with(ACTIVE, false);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPE;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;   // desenhado pelo AntennaBlockEntityRenderer
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new AmplifyingAntennaBlockEntity(pos, state);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient && placer instanceof PlayerEntity p
                && world.getBlockEntity(pos) instanceof AmplifyingAntennaBlockEntity be) {
            be.setOwner(p.getUuid(), p.getGameProfile().getName());
            p.sendMessage(Text.literal("§aAntena instalada. §7Seus drones ganham §f+"
                    + (int) AntennaNetwork.RANGE_BONUS + " blocos§7 de alcance perto dela."), true);
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (!world.isClient && world.getBlockEntity(pos) instanceof AmplifyingAntennaBlockEntity be) {
            boolean mine = player.getUuid().equals(be.getOwner());
            String owner = be.getOwnerName().isEmpty() ? "desconhecido" : be.getOwnerName();
            player.sendMessage(Text.literal(mine
                    ? "§aAntena amplificadora §7· sua · +" + (int) AntennaNetwork.RANGE_BONUS + " blocos de alcance"
                    : "§eAntena amplificadora §7· dono: §f" + owner), true);
        }
        return ActionResult.SUCCESS;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (type != VigiCam.ANTENNA_BE) return null;
        BlockEntityTicker<AmplifyingAntennaBlockEntity> ticker = world.isClient
                ? AmplifyingAntennaBlockEntity::clientTick
                : AmplifyingAntennaBlockEntity::serverTick;
        return (BlockEntityTicker<T>) ticker;
    }
}
