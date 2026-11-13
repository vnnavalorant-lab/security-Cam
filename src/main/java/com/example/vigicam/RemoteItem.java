package com.example.vigicam;

import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controle remoto: guarda até 8 câmeras/drones vinculados.
 * Shift + botão direito numa câmera/drone vincula (ou desvincula); botão direito no ar conecta ao selecionado;
 * dentro da visão, a tecla "próximo dispositivo" (padrão R) troca para o próximo da lista.
 */
public class RemoteItem extends Item {
    public static final int MAX_LINKS = 8;
    public static final double MAX_DISTANCE = 160.0;

    public record Link(boolean drone, String dim, @Nullable BlockPos pos, @Nullable UUID id) {
        public static Link camera(World w, BlockPos p) { return new Link(false, dimOf(w), p, null); }
        public static Link drone(World w, DroneEntity d) { return new Link(true, dimOf(w), null, d.getUuid()); }

        boolean same(Link o) {
            return drone == o.drone && dim.equals(o.dim) && Objects.equals(pos, o.pos) && Objects.equals(id, o.id);
        }
    }

    public RemoteItem(Settings settings) { super(settings); }

    private static String dimOf(World w) { return w.getRegistryKey().getValue().toString(); }

    // ------------------------------------------------------------------ uso

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) {
            List<Link> links = read(stack);
            if (links.isEmpty()) {
                msg(sp, "§eNenhum dispositivo vinculado. Shift + botão direito numa câmera ou drone para vincular.");
            } else {
                int sel = Math.floorMod(stack.getOrCreateNbt().getInt("Sel"), links.size());
                connect(sp, links.get(sel), sel, links.size());
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World w = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        if (!(w.getBlockState(pos).getBlock() instanceof SurveillanceCameraBlock)) return ActionResult.PASS;
        if (!w.isClient && ctx.getPlayer() instanceof ServerPlayerEntity sp) {
            if (sp.isSneaking()) toggleLink(sp, ctx.getStack(), Link.camera(w, pos));
            else connectTo(sp, ctx.getStack(), Link.camera(w, pos));
        }
        return ActionResult.success(w.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        List<Link> links = read(stack);
        tooltip.add(Text.literal("Vinculados: " + links.size() + "/" + MAX_LINKS).styled(s -> s.withColor(0xAAAAAA)));
        tooltip.add(Text.literal("Shift + clique direito: vincular câmera/drone").styled(s -> s.withColor(0x777777)));
        tooltip.add(Text.literal("Clique direito: conectar ao selecionado").styled(s -> s.withColor(0x777777)));
    }

    // ------------------------------------------------------------------ vínculos

    public static void toggleLink(ServerPlayerEntity sp, ItemStack stack, Link link) {
        List<Link> links = read(stack);
        int found = -1;
        for (int i = 0; i < links.size(); i++) if (links.get(i).same(link)) found = i;
        if (found >= 0) {
            links.remove(found);
            msg(sp, "§eDispositivo desvinculado (" + links.size() + "/" + MAX_LINKS + ")");
        } else if (links.size() >= MAX_LINKS) {
            msg(sp, "§cO controle já está cheio (" + MAX_LINKS + ").");
            return;
        } else {
            links.add(link);
            stack.getOrCreateNbt().putInt("Sel", links.size() - 1);
            msg(sp, "§aDispositivo vinculado (" + links.size() + "/" + MAX_LINKS + ")");
        }
        write(stack, links);
    }

    /** Conecta direto num dispositivo (selecionando-o se já estiver vinculado). */
    public static void connectTo(ServerPlayerEntity sp, ItemStack stack, Link link) {
        List<Link> links = read(stack);
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).same(link)) {
                stack.getOrCreateNbt().putInt("Sel", i);
                connect(sp, link, i, links.size());
                return;
            }
        }
        connect(sp, link, -1, 0);
    }

    /** Tecla "próximo dispositivo": passa para o seguinte da lista do controle que o jogador segura. */
    public static void cycle(ServerPlayerEntity sp) {
        ItemStack stack = sp.getMainHandStack();
        if (!(stack.getItem() instanceof RemoteItem)) stack = sp.getOffHandStack();
        if (!(stack.getItem() instanceof RemoteItem)) {
            msg(sp, "§eSegure o controle remoto para trocar de dispositivo.");
            return;
        }
        List<Link> links = read(stack);
        if (links.isEmpty()) return;
        int sel = (Math.floorMod(stack.getOrCreateNbt().getInt("Sel"), links.size()) + 1) % links.size();
        stack.getOrCreateNbt().putInt("Sel", sel);
        connect(sp, links.get(sel), sel, links.size());
    }

    // ------------------------------------------------------------------ conexão

    public static boolean connect(ServerPlayerEntity sp, Link link, int index, int count) {
        ServerWorld w = sp.getServerWorld();
        if (!dimOf(w).equals(link.dim())) {
            msg(sp, "§cDispositivo em outra dimensão.");
            return false;
        }
        if (link.drone()) {
            Entity e = link.id() == null ? null : w.getEntity(link.id());
            if (!(e instanceof DroneEntity d) || d.isRemoved()) {
                msg(sp, "§cDrone indisponível (destruído ou fora de alcance).");
                return false;
            }
            if (d.squaredDistanceTo(sp) > MAX_DISTANCE * MAX_DISTANCE) {
                msg(sp, "§cDrone fora de alcance.");
                return false;
            }
            return DroneControl.begin(sp, d, index, count);
        }
        BlockPos p = link.pos();
        if (p == null || !w.isChunkLoaded(p) || !(w.getBlockState(p).getBlock() instanceof SurveillanceCameraBlock)) {
            msg(sp, "§cCâmera indisponível.");
            return false;
        }
        if (p.getSquaredDistance(sp.getPos()) > MAX_DISTANCE * MAX_DISTANCE) {
            msg(sp, "§cCâmera fora de alcance.");
            return false;
        }
        DroneControl.release(sp.getUuid());
        VigiCam.sendViewCamera(sp, p, w.getBlockState(p).get(HorizontalFacingBlock.FACING).asRotation(), index, count);
        return true;
    }

    private static void msg(ServerPlayerEntity sp, String s) { sp.sendMessage(Text.literal(s), true); }

    // ------------------------------------------------------------------ NBT

    private static List<Link> read(ItemStack stack) {
        List<Link> out = new ArrayList<>();
        NbtCompound tag = stack.getNbt();
        if (tag == null) return out;
        NbtList list = tag.getList("Links", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound c = list.getCompound(i);
            boolean drone = c.getBoolean("Drone");
            String dim = c.getString("Dim");
            if (drone) out.add(new Link(true, dim, null, c.getUuid("Id")));
            else out.add(new Link(false, dim, NbtHelper.toBlockPos(c.getCompound("Pos")), null));
        }
        return out;
    }

    private static void write(ItemStack stack, List<Link> links) {
        NbtList list = new NbtList();
        for (Link l : links) {
            NbtCompound c = new NbtCompound();
            c.putBoolean("Drone", l.drone());
            c.putString("Dim", l.dim());
            if (l.drone()) c.putUuid("Id", l.id());
            else c.put("Pos", NbtHelper.fromBlockPos(l.pos()));
            list.add(c);
        }
        NbtCompound tag = stack.getOrCreateNbt();
        tag.put("Links", list);
        if (links.isEmpty()) tag.putInt("Sel", 0);
        else tag.putInt("Sel", Math.floorMod(tag.getInt("Sel"), links.size()));
    }
}
