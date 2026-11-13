package com.example.vigicam;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.state.property.IntProperty;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/** Antena amplificadora: quem coloca é o dono; amplia o sinal dos drones do dono (+50 blocos de alcance). */
public class AmplifyingAntennaBlock extends HorizontalFacingBlock implements BlockEntityProvider {
    /** Verdadeiro enquanto um drone do dono está usando o sinal desta antena (partículas mais intensas). */
    public static final BooleanProperty ACTIVE = BooleanProperty.of("active");

    /** Qual dos 4 blocos de altura é este (0 = base, com o modelo e o dono; 1-3 = só hitbox do mastro). */
    public static final IntProperty PART = IntProperty.of("part", 0, 3);
    public static final int HEIGHT = 4;

    // a antena tem 4 blocos de altura: cada bloco tem a hitbox do trecho do modelo que passa por ele
    private static final VoxelShape[] SHAPES = {
            Block.createCuboidShape(1, 0, 1, 15, 16, 15),    // base
            Block.createCuboidShape(4, 0, 4, 12, 16, 12),    // mastro baixo
            Block.createCuboidShape(1, 0, 1, 15, 16, 15),    // conjunto de painéis (largo)
            Block.createCuboidShape(6, 0, 6, 10, 16, 10)     // ponta do mastro
    };

    public AmplifyingAntennaBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(ACTIVE, false).with(PART, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE, PART);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // precisa de espaço livre para os 4 blocos de altura
        World w = ctx.getWorld();
        BlockPos base = ctx.getBlockPos();
        if (base.getY() + HEIGHT - 1 >= w.getTopY()) return null;
        for (int i = 1; i < HEIGHT; i++) {
            if (!w.getBlockState(base.up(i)).isReplaceable()) return null;
        }
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
                .with(ACTIVE, false).with(PART, 0);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPES[state.get(PART)];
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;   // desenhado pelo AntennaBlockEntityRenderer
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(PART) == 0 ? new AmplifyingAntennaBlockEntity(pos, state) : null;   // só a base tem entidade
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (!world.isClient) {
            for (int i = 1; i < HEIGHT; i++) {
                world.setBlockState(pos.up(i), state.with(PART, i).with(ACTIVE, false), Block.NOTIFY_ALL);
            }
        }
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
        BlockPos basePos = pos.down(state.get(PART));
        if (!world.isClient && world.getBlockEntity(basePos) instanceof AmplifyingAntennaBlockEntity be) {
            boolean mine = player.getUuid().equals(be.getOwner());
            String owner = be.getOwnerName().isEmpty() ? "desconhecido" : be.getOwnerName();
            player.sendMessage(Text.literal(mine
                    ? "§aAntena amplificadora §7· sua · +" + (int) AntennaNetwork.RANGE_BONUS + " blocos de alcance"
                    : "§eAntena amplificadora §7· dono: §f" + owner), true);
        }
        return ActionResult.SUCCESS;
    }

    /** Quebrar qualquer trecho remove a antena inteira (o item cai uma vez só). */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            int part = state.get(PART);
            BlockPos base = pos.down(part);
            if (part != 0 && !player.isCreative()) {
                BlockState bs = world.getBlockState(base);
                if (bs.isOf(this)) dropStacks(bs, world, base, world.getBlockEntity(base), player, player.getMainHandStack());
            }
            for (int i = 0; i < HEIGHT; i++) {
                BlockPos p = base.up(i);
                if (!p.equals(pos) && world.getBlockState(p).isOf(this)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 35);   // sem drops, sem atualizações em cadeia
                }
            }
        }
        super.onBreak(world, pos, state, player);
    }

    /** Se algum trecho da coluna sumir (explosão, comando...), o resto some junto. */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        int part = state.get(PART);
        if ((direction == Direction.UP && part < HEIGHT - 1 && !neighborState.isOf(this))
                || (direction == Direction.DOWN && part > 0 && !neighborState.isOf(this))) {
            return Blocks.AIR.getDefaultState();
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
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
