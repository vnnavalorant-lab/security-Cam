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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Porta Reforçada: estrutura de 3 (largura) x 3 (altura) x 1 (profundidade) blocos, desenhada por um único
 * BlockEntityRenderer (ReinforcedDoorRenderer) a partir do bloco central de baixo (COL=1, ROW=0), que é o único com
 * BlockEntity e o único que solta o item.
 * <ul>
 *   <li>Só quebra com picareta de diamante (ou melhor): tags mineable/pickaxe + needs_diamond_tool e requiresTool().</li>
 *   <li>Travada ou só destravada (fechada) = parede sólida. Só depois de aberta (pivô "door" girado) o vão fica livre.</li>
 *   <li>Porta fechada (sempre travada): clique direito abre o painel de senha (DoorPanelScreen). Senha certa destrava (luzes verdes,
 *       animação "on") e, meio segundo depois, abre girando o pivô "door". Só a senha cadastrada abre: nem o dono abre com outra.</li>
 *   <li>Dono (quem colocou): é o único que vê o botão de trocar a senha no painel. Se esquecer a senha e errar
 *       {@link ReinforcedDoorBlockEntity#MAX_OWNER_FAILS} vezes seguidas, o servidor abre a janela de biometria e o cliente troca
 *       o teclado pelo {@link BiometryPanelScreen} (leitor de digital: segurar 1,5 s). Quem confere e libera é o servidor.</li>
 *   <li>Porta aberta: clique direito fecha e trava de novo (animação "off", luzes vermelhas).</li>
 * </ul>
 * O eixo da largura do modelo (+x do .geo.json) aponta para {@code FACING.rotateYClockwise()}.
 */
public class ReinforcedDoorBlock extends HorizontalFacingBlock implements BlockEntityProvider {
    /** Destravada (animação "on"); travada = animação "off". */
    public static final BooleanProperty UNLOCKED = BooleanProperty.of("unlocked");
    public static final BooleanProperty OPEN = BooleanProperty.of("open");
    /** Coluna (0 = esquerda, 1 = centro, 2 = direita, no sentido do eixo da largura) e linha (0 = base) deste bloco. */
    public static final IntProperty COL = IntProperty.of("col", 0, 2);
    public static final IntProperty ROW = IntProperty.of("row", 0, 2);

    public static final int WIDTH = 3, HEIGHT = 3;

    /** Troca de estado sem atualizar vizinhos (evita a cascata de "peça faltando"), mas avisando os clientes. */
    private static final int STATE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** Colisão da porta aberta: [facing 0..3][col][row]. Fechada = bloco inteiro. */
    private static final VoxelShape[][][] OPEN_SHAPES = new VoxelShape[4][WIDTH][HEIGHT];

    static {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            Direction side = facing.rotateYClockwise();
            for (int c = 0; c < WIDTH; c++) {
                for (int r = 0; r < HEIGHT; r++) OPEN_SHAPES[facing.getHorizontal()][c][r] = openShape(side, c, r);
            }
        }
    }

    /**
     * Parte do quadro (modelo, px): batente esquerdo x[-24,-16], batente direito x[16,24] (altura toda) e verga
     * x[-16,16] y[40,48]. O vão (x[-16,16], y[0,40]) fica livre quando a porta abre.
     */
    private static VoxelShape openShape(Direction side, int col, int row) {
        int[][] solid = {{-24, 0, -16, 48}, {16, 0, 24, 48}, {-16, 40, 16, 48}};
        int bx0 = (col - 1) * 16 - 8, by0 = row * 16;
        VoxelShape out = VoxelShapes.empty();
        for (int[] r : solid) {
            int x0 = Math.max(r[0], bx0), x1 = Math.min(r[2], bx0 + 16);
            int y0 = Math.max(r[1], by0), y1 = Math.min(r[3], by0 + 16);
            if (x0 >= x1 || y0 >= y1) continue;
            double lx0 = x0 - bx0, lx1 = x1 - bx0, ly0 = y0 - by0, ly1 = y1 - by0;
            VoxelShape box = switch (side) {
                case EAST -> Block.createCuboidShape(lx0, ly0, 0, lx1, ly1, 16);
                case WEST -> Block.createCuboidShape(16 - lx1, ly0, 0, 16 - lx0, ly1, 16);
                case SOUTH -> Block.createCuboidShape(0, ly0, lx0, 16, ly1, lx1);
                default -> Block.createCuboidShape(0, ly0, 16 - lx1, 16, ly1, 16 - lx0);   // NORTH
            };
            out = VoxelShapes.union(out, box);
        }
        return out.simplify();
    }

    public ReinforcedDoorBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH)
                .with(UNLOCKED, false).with(OPEN, false).with(COL, 1).with(ROW, 0));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING, UNLOCKED, OPEN, COL, ROW);
    }

    // ------------------------------------------------------------------ geometria da estrutura

    /** Posição do bloco (col, row) da estrutura cujo bloco central de baixo está em {@code master}. */
    public static BlockPos partPos(BlockPos master, Direction facing, int col, int row) {
        return master.offset(facing.rotateYClockwise(), col - 1).up(row);
    }

    /** Posição do bloco central de baixo (o "mestre") a partir de qualquer bloco da estrutura. */
    public static BlockPos masterPos(BlockPos pos, BlockState state) {
        return pos.offset(state.get(FACING).rotateYClockwise(), 1 - state.get(COL)).down(state.get(ROW));
    }

    // ------------------------------------------------------------------ colocação

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        World w = ctx.getWorld();
        BlockPos master = ctx.getBlockPos();
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
        if (master.getY() + HEIGHT - 1 >= w.getTopY()) return null;
        for (int c = 0; c < WIDTH; c++) {
            for (int r = 0; r < HEIGHT; r++) {
                if (c == 1 && r == 0) continue;
                if (!w.getBlockState(partPos(master, facing, c, r)).isReplaceable()) return null;
            }
        }
        return getDefaultState().with(FACING, facing).with(COL, 1).with(ROW, 0);
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (world.isClient) return;
        // quem coloca é o dono; a senha inicial é sorteada (o dono não precisa dela e pode trocá-la no painel)
        if (world.getBlockEntity(pos) instanceof ReinforcedDoorBlockEntity be) {
            if (placer instanceof PlayerEntity p) be.setOwner(p);
            be.ensurePin(world.random);
        }
        Direction facing = state.get(FACING);
        for (int c = 0; c < WIDTH; c++) {
            for (int r = 0; r < HEIGHT; r++) {
                if (c == 1 && r == 0) continue;
                world.setBlockState(partPos(pos, facing, c, r), state.with(COL, c).with(ROW, r), Block.NOTIFY_ALL);
            }
        }
    }

    // ------------------------------------------------------------------ formas

    /** Seleção: sempre a parede 3x3x1 inteira (assim dá para clicar no vão para fechar a porta aberta). */
    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return VoxelShapes.fullCube();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        if (!state.get(OPEN)) return VoxelShapes.fullCube();   // travada ou só destravada: parede sólida; aberta: dá para atravessar o vão
        return OPEN_SHAPES[state.get(FACING).getHorizontal()][state.get(COL)][state.get(ROW)];
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;   // desenhado pelo ReinforcedDoorRenderer
    }

    @Override
    public float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
        return 1.0f;
    }

    // ------------------------------------------------------------------ uso: painel de senha

    /** Tipos de ação do cliente (pacote DOOR_ACTION). */
    public static final int ACT_TRY_PIN = 0, ACT_SET_PIN = 2;
    /** Só servidor -> cliente: o dono errou demais; o painel troca para o leitor de digital. (1 era o antigo "abrir sem senha": não existe mais.) */
    public static final int ACT_BIOMETRY = 3;
    /** Cliente -> servidor (e a resposta): o dono segurou o leitor de digital pelo tempo todo. Só vale com a janela de biometria aberta. */
    public static final int ACT_BIO_DONE = 4;
    /** Ticks entre as luzes ficarem verdes e a porta começar a abrir. */
    private static final int OPEN_DELAY = 10;
    private static final double MAX_USE_DIST_SQ = 12 * 12;

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                              Hand hand, BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;

        BlockPos master = masterPos(pos, state);
        if (!(world.getBlockEntity(master) instanceof ReinforcedDoorBlockEntity be)) return ActionResult.PASS;

        if (state.get(OPEN)) {
            // aberta: fecha e trava de novo (animação "off")
            apply(world, master, state, false, false);
            play(world, master, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 1.0f);
            play(world, master, SoundEvents.BLOCK_LEVER_CLICK, 0.5f);
            world.emitGameEvent(player, GameEvent.BLOCK_CLOSE, master);
            return ActionResult.CONSUME;
        }

        // porta de um mundo antigo / comando: quem usar primeiro vira o dono
        if (!be.hasOwner()) {
            be.setOwner(player);
            player.sendMessage(Text.literal("§aVocê agora é o dono desta porta"), true);
        }
        be.ensurePin(world.random);

        boolean isOwner = be.isOwner(player);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(master);
        buf.writeBoolean(isOwner);
        // o dono que já errou demais volta direto para o leitor de digital (o teclado só entra de novo depois da biometria)
        buf.writeBoolean(isOwner && be.isBiometryPending(world.getTime()));
        ServerPlayNetworking.send(sp, VigiCam.DOOR_PANEL, buf);
        return ActionResult.CONSUME;
    }

    /** Pedido do painel (cliente -> servidor). Tudo é conferido aqui: o cliente nunca decide se pode abrir. */
    public static void handleAction(ServerPlayerEntity player, int type, BlockPos pos, String pin) {
        World world = player.getWorld();
        if (player.squaredDistanceTo(Vec3d.ofCenter(pos).add(0, 1, 0)) > MAX_USE_DIST_SQ) return;
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ReinforcedDoorBlock door)) return;
        if (state.get(COL) != 1 || state.get(ROW) != 0 || state.get(OPEN)) return;
        if (!(world.getBlockEntity(pos) instanceof ReinforcedDoorBlockEntity be)) return;

        boolean ok = false;
        switch (type) {
            case ACT_TRY_PIN -> {
                // vale para todo mundo, dono inclusive: só a senha cadastrada abre
                ok = be.checkPin(pin);
                if (ok) {
                    be.resetOwnerFails();
                    be.clearBiometry();
                    door.unlockAndOpen(world, pos, state, player);
                } else if (be.isOwner(player) && be.registerOwnerFail()) {
                    // dono errou 3 vezes seguidas: abre a janela de biometria e o cliente troca para o leitor de digital
                    be.startBiometry(world.getTime());
                    sendResult(player, ACT_BIOMETRY, true);
                    return;
                } else {
                    playExcept(world, pos, player, ModSounds.DOOR_ERROR, 0.5f);
                }
            }
            case ACT_SET_PIN -> {
                ok = be.isOwner(player) && ReinforcedDoorBlockEntity.isValidPin(pin);
                if (ok) be.setPin(pin);
            }
            case ACT_BIO_DONE -> {
                // leitura de digital concluída: só o dono, só com a janela aberta (3 erros seguidos) e há tempo suficiente
                ok = be.isOwner(player) && be.canFinishBiometry(world.getTime());
                if (ok) {
                    be.resetOwnerFails();
                    be.clearBiometry();
                    door.unlockAndOpen(world, pos, state, player);
                }
            }
            default -> { return; }   // inclui o antigo "abrir sem senha" e o ACT_BIOMETRY (que só o servidor envia): ignorados
        }
        sendResult(player, type, ok);
    }

    private static void sendResult(ServerPlayerEntity player, int type, boolean ok) {
        PacketByteBuf out = PacketByteBufs.create();
        out.writeByte(type);
        out.writeBoolean(ok);
        ServerPlayNetworking.send(player, VigiCam.DOOR_RESULT, out);
    }

    /** Destrava (luzes verdes) agora e abre a porta {@link #OPEN_DELAY} ticks depois. */
    private void unlockAndOpen(World world, BlockPos master, BlockState state, PlayerEntity player) {
        apply(world, master, state, true, false);
        playExcept(world, master, player, ModSounds.DOOR_UNLOCK, 0.8f);   // quem digitou já ouviu o som no painel
        world.scheduleBlockTick(master, this, OPEN_DELAY);
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (state.get(UNLOCKED) && !state.get(OPEN) && state.get(COL) == 1 && state.get(ROW) == 0) {
            apply(world, pos, state, true, true);
            play(world, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN, 1.0f);
            world.emitGameEvent(null, GameEvent.BLOCK_OPEN, pos);
        }
    }

    private static void playExcept(World world, BlockPos master, PlayerEntity except, SoundEvent sound, float volume) {
        world.playSound(except, master.up(), sound, SoundCategory.BLOCKS, volume, 1.0f);
    }

    private static void play(World world, BlockPos master, SoundEvent sound, float pitchBase) {
        world.playSound(null, master.up(), sound, SoundCategory.BLOCKS, 1.0f, pitchBase + world.random.nextFloat() * 0.1f);
    }

    /** Aplica o novo estado (destravada/aberta) aos 9 blocos da estrutura. */
    private void apply(World world, BlockPos master, BlockState any, boolean unlocked, boolean open) {
        Direction facing = any.get(FACING);
        for (int c = 0; c < WIDTH; c++) {
            for (int r = 0; r < HEIGHT; r++) {
                BlockPos p = partPos(master, facing, c, r);
                BlockState s = world.getBlockState(p);
                if (s.isOf(this)) world.setBlockState(p, s.with(UNLOCKED, unlocked).with(OPEN, open), STATE_FLAGS);
            }
        }
    }

    // ------------------------------------------------------------------ quebrar

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return state.get(COL) == 1 && state.get(ROW) == 0 ? new ReinforcedDoorBlockEntity(pos, state) : null;
    }

    /** Quebrar qualquer bloco remove a porta inteira; o item cai uma vez só e só com a ferramenta certa. */
    @Override
    public void onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient) {
            BlockPos master = masterPos(pos, state);
            Direction facing = state.get(FACING);
            boolean isMaster = pos.equals(master);
            if (!isMaster && !player.isCreative() && player.canHarvest(state)) {
                BlockState ms = world.getBlockState(master);
                if (ms.isOf(this)) dropStacks(ms, world, master, world.getBlockEntity(master), player, player.getMainHandStack());
            }
            // o mestre sai primeiro: assim a cascata de atualizações nunca solta um segundo item
            for (int r = 0; r < HEIGHT; r++) {
                for (int c = 0; c < WIDTH; c++) {
                    BlockPos p = partPos(master, facing, c, r);
                    if (c == 1 && r == 0 && isMaster) continue;   // quem está sendo quebrado o jogo remove sozinho
                    if (!p.equals(pos) && world.getBlockState(p).isOf(this)) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState(), 35);   // sem drops
                    }
                }
            }
        }
        super.onBreak(world, pos, state, player);
    }

    /** Se algum pedaço sumir (explosão, comando...), o resto some junto. */
    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        int c = state.get(COL), r = state.get(ROW);
        Direction side = state.get(FACING).rotateYClockwise();
        boolean needsNeighbor = (direction == side && c < WIDTH - 1)
                || (direction == side.getOpposite() && c > 0)
                || (direction == Direction.UP && r < HEIGHT - 1)
                || (direction == Direction.DOWN && r > 0);
        if (needsNeighbor && !neighborState.isOf(this)) return Blocks.AIR.getDefaultState();
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (!world.isClient || type != VigiCam.REINFORCED_DOOR_BE) return null;
        BlockEntityTicker<ReinforcedDoorBlockEntity> ticker = ReinforcedDoorBlockEntity::clientTick;
        return (BlockEntityTicker<T>) ticker;
    }
}
