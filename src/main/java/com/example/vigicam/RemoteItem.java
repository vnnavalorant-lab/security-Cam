package com.example.vigicam;

import net.minecraft.block.BlockState;
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
 * Controle remoto: guarda até 8 câmeras / drones / portas reforçadas vinculados.
 * <ul>
 *   <li>Botão direito (no ar, ou numa câmera/drone): abre o painel de dispositivos ({@link RemotePanel} / {@link RemotePanelScreen}).
 *       Lá: duplo clique num item = ver a câmera, pilotar o drone ou abrir/fechar a porta; PING, lixeira e radar.</li>
 *   <li>Shift + botão direito numa câmera, drone ou porta: vincula (ou desvincula).</li>
 *   <li>Dentro da visão, a tecla "próximo dispositivo" (padrão R) passa para a próxima câmera/drone da lista.</li>
 * </ul>
 */
public class RemoteItem extends Item {
    public static final int MAX_LINKS = 8;
    public static final double MAX_DISTANCE = 160.0;
    public static final int CAM = RemoteDevices.CAM, DRONE = RemoteDevices.DRONE, DOOR = RemoteDevices.DOOR;

    /**
     * Dispositivo vinculado. {@code color} (índice da paleta) e {@code seq} (o número do ID, CAM-01...) são dados na hora de vincular e
     * não mudam mais. Em drones, {@code pos} é só a última posição conhecida (para mostrar no radar quando estiver offline).
     */
    public record Link(int kind, String dim, @Nullable BlockPos pos, @Nullable UUID id, int color, int seq) {
        public static Link camera(World w, BlockPos p) { return new Link(CAM, dimOf(w), p.toImmutable(), null, -1, 0); }
        public static Link drone(World w, DroneEntity d) { return new Link(DRONE, dimOf(w), null, d.getUuid(), -1, 0); }
        public static Link door(World w, BlockPos master) { return new Link(DOOR, dimOf(w), master.toImmutable(), null, -1, 0); }

        public boolean drone() { return kind == DRONE; }
        public boolean door() { return kind == DOOR; }

        boolean same(Link o) {
            if (kind != o.kind || !dim.equals(o.dim)) return false;
            return kind == DRONE ? Objects.equals(id, o.id) : Objects.equals(pos, o.pos);
        }

        Link styled(int color, int seq) { return new Link(kind, dim, pos, id, color, seq); }
        Link at(BlockPos p) { return new Link(kind, dim, p, id, color, seq); }
    }

    public RemoteItem(Settings settings) { super(settings); }

    private static String dimOf(World w) { return w.getRegistryKey().getValue().toString(); }

    public static String idOf(Link l) { return RemoteDevices.id(l.kind(), l.seq()); }

    // ------------------------------------------------------------------ uso

    /** Botão direito no ar: abre o painel de dispositivos (e só depois, lá dentro, o jogador escolhe o que fazer). */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient && user instanceof ServerPlayerEntity sp) RemotePanel.open(sp);
        return TypedActionResult.success(stack, world.isClient);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext ctx) {
        World w = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        BlockState state = w.getBlockState(pos);
        boolean camera = state.getBlock() instanceof SurveillanceCameraBlock;
        boolean door = state.getBlock() instanceof ReinforcedDoorBlock;
        if (!camera && !door) return ActionResult.PASS;
        if (!w.isClient && ctx.getPlayer() instanceof ServerPlayerEntity sp) {
            if (sp.isSneaking()) {
                Link link = camera ? Link.camera(w, pos) : Link.door(w, ReinforcedDoorBlock.masterPos(pos, state));
                toggleLink(sp, ctx.getStack(), link);
            } else if (camera) {
                RemotePanel.open(sp);
            } else {
                return ActionResult.PASS;     // porta sem Shift: o clique é da própria porta (painel da senha)
            }
        }
        return ActionResult.success(w.isClient);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        List<Link> links = read(stack);
        normalize(links);       // mesma numeração que o servidor dá (CAM-01...), igual à do painel
        tooltip.add(Text.literal("Vinculados: " + links.size() + "/" + MAX_LINKS).styled(s -> s.withColor(0xAAAAAA)));
        for (int kind = CAM; kind <= DOOR; kind++) {
            List<String> ids = new ArrayList<>();
            for (Link l : links) if (l.kind() == kind) ids.add(idOf(l));
            if (ids.isEmpty()) continue;
            String label = kind == CAM ? "Câmeras" : kind == DRONE ? "Drones" : "Portas";
            tooltip.add(Text.literal(label + " (" + ids.size() + "): " + String.join(", ", ids)).styled(s -> s.withColor(0x888888)));
        }
        tooltip.add(Text.literal("Shift + clique direito: vincular câmera/drone/porta").styled(s -> s.withColor(0x777777)));
        tooltip.add(Text.literal("Clique direito: abrir o painel de dispositivos").styled(s -> s.withColor(0x777777)));
    }

    // ------------------------------------------------------------------ vínculos

    public static void toggleLink(ServerPlayerEntity sp, ItemStack stack, Link link) {
        List<Link> links = readNorm(stack);
        int found = -1;
        for (int i = 0; i < links.size(); i++) if (links.get(i).same(link)) found = i;
        if (found >= 0) {
            Link before = selected(stack, links);
            links.remove(found);
            keepSelection(stack, links, before);
            msg(sp, "§eDispositivo desvinculado (" + links.size() + "/" + MAX_LINKS + ")");
        } else if (links.size() >= MAX_LINKS) {
            msg(sp, "§cO controle já está cheio (" + MAX_LINKS + ").");
            return;
        } else if (!hasAccess(sp, link)) {
            return;
        } else {
            links.add(link);
            normalize(links);
            stack.getOrCreateNbt().putInt("Sel", links.size() - 1);
            msg(sp, "§aDispositivo vinculado (" + links.size() + "/" + MAX_LINKS + ")");
        }
        write(stack, links);
    }

    /** Tecla "próximo dispositivo": passa para a próxima câmera/drone da lista (portas não têm visão e são puladas). */
    public static void cycle(ServerPlayerEntity sp) {
        ItemStack stack = sp.getMainHandStack();
        if (!(stack.getItem() instanceof RemoteItem)) stack = sp.getOffHandStack();
        if (!(stack.getItem() instanceof RemoteItem)) {
            msg(sp, "§eSegure o controle remoto para trocar de dispositivo.");
            return;
        }
        List<Link> links = readNorm(stack);
        int n = links.size();
        if (n == 0) return;
        int sel = Math.floorMod(stack.getOrCreateNbt().getInt("Sel"), n);
        for (int step = 1; step <= n; step++) {
            int j = (sel + step) % n;
            if (links.get(j).door()) continue;
            stack.getOrCreateNbt().putInt("Sel", j);
            int[] vp = viewPos(links, j);
            connect(sp, links.get(j), vp[0], vp[1]);
            return;
        }
    }

    /** {posição entre os dispositivos com visão (câmeras e drones), total deles}, para o "2/5" do HUD. */
    public static int[] viewPos(List<Link> links, int i) {
        int idx = 0, count = 0;
        for (int j = 0; j < links.size(); j++) {
            if (links.get(j).door()) continue;
            if (j < i) idx++;
            count++;
        }
        return new int[]{idx, count};
    }

    // ------------------------------------------------------------------ conexão

    public static boolean connect(ServerPlayerEntity sp, Link link, int index, int count) {
        ServerWorld w = sp.getServerWorld();
        if (link.door()) return false;
        if (!dimOf(w).equals(link.dim())) {
            msg(sp, "§cDispositivo em outra dimensão.");
            return false;
        }
        if (!hasAccess(sp, link)) return false;
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
            return DroneControl.begin(sp, d, index, count, link.seq());
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
        VigiCam.sendViewCamera(sp, p, w.getBlockState(p).get(HorizontalFacingBlock.FACING).asRotation(), index, count, link.seq());
        return true;
    }

    /** O vínculo que a tecla "próximo" usa como ponto de partida, ou null. */
    static Link selected(ItemStack stack, List<Link> links) {
        if (links.isEmpty()) return null;
        return links.get(Math.floorMod(stack.getOrCreateNbt().getInt("Sel"), links.size()));
    }

    /** Depois de apagar vínculos, o "selecionado" continua sendo o mesmo dispositivo (e não o que ocupou o lugar dele). */
    static void keepSelection(ItemStack stack, List<Link> links, @Nullable Link before) {
        if (before == null) return;
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).same(before)) {
                stack.getOrCreateNbt().putInt("Sel", i);
                return;
            }
        }
    }

    /**
     * Abre ({@code open}) ou fecha e tranca uma porta, à distância e sem mensagem (para "abrir/fechar todas").
     * @return 1 se mudou, 0 se já estava assim, -1 se não deu (offline, longe, outra dimensão ou sem acesso)
     */
    public static int setDoor(ServerPlayerEntity sp, Link link, boolean open) {
        ServerWorld w = sp.getServerWorld();
        BlockPos p = link.pos();
        if (!link.door() || !dimOf(w).equals(link.dim()) || p == null || !w.isChunkLoaded(p)
                || !(w.getBlockState(p).getBlock() instanceof ReinforcedDoorBlock door)) return -1;
        if (p.getSquaredDistance(sp.getPos()) > MAX_DISTANCE * MAX_DISTANCE) return -1;
        if (w.getBlockEntity(p) instanceof ReinforcedDoorBlockEntity be && be.hasOwner() && !be.isOwner(sp)) return -1;
        BlockState st = w.getBlockState(p);
        boolean isOpen = st.get(ReinforcedDoorBlock.OPEN);
        if (isOpen == open) return 0;
        if (open) door.remoteOpen(w, p, st); else door.remoteClose(w, p, st);
        return 1;
    }

    /** Abre a porta (se estiver fechada) ou fecha e tranca (se estiver aberta), à distância. Devolve a mensagem do painel. */
    public static String toggleDoor(ServerPlayerEntity sp, Link link) {
        ServerWorld w = sp.getServerWorld();
        String id = idOf(link);
        BlockPos p = link.pos();
        if (!dimOf(w).equals(link.dim())) return "Dispositivo em outra dimensao.";
        if (p == null || !w.isChunkLoaded(p) || !(w.getBlockState(p).getBlock() instanceof ReinforcedDoorBlock door)) {
            return id + " indisponivel.";
        }
        if (p.getSquaredDistance(sp.getPos()) > MAX_DISTANCE * MAX_DISTANCE) return id + " fora de alcance.";
        if (!hasAccess(sp, link)) return "Sem acesso a " + id + ".";
        BlockState st = w.getBlockState(p);
        if (st.get(ReinforcedDoorBlock.OPEN)) {
            door.remoteClose(w, p, st);
            return id + " foi trancada.";
        }
        door.remoteOpen(w, p, st);
        return id + " foi aberta.";
    }

    /**
     * O jogador pode usar este dispositivo? Dispositivos de outro jogador só respondem ao dono (ou a quem invadiu a rede dele
     * com o Pendrive Hacker, enquanto durar o acesso). Se não puder, o próprio método avisa o motivo.
     */
    private static boolean hasAccess(ServerPlayerEntity sp, Link link) {
        ServerWorld w = sp.getServerWorld();
        UUID owner = null;
        String name = "";
        if (link.drone()) {
            if (link.id() != null && w.getEntity(link.id()) instanceof DroneEntity d) {
                owner = d.getOwner();
                name = d.getOwnerName();
            }
        } else if (link.door()) {
            // porta: só o dono (ou o hacker) comanda; porta sem dono (mundo antigo) é livre
            if (link.pos() != null && w.isChunkLoaded(link.pos())
                    && w.getBlockEntity(link.pos()) instanceof ReinforcedDoorBlockEntity be && be.hasOwner() && !be.isOwner(sp)) {
                HackManager.deny(sp, be.getOwnerUuid(), "");
                return false;
            }
            return true;
        } else if (link.pos() != null && w.isChunkLoaded(link.pos())
                && w.getBlockEntity(link.pos()) instanceof SurveillanceCameraBlockEntity cam) {
            owner = cam.getOwner();
            name = cam.getOwnerName();
        }
        if (HackManager.canUse(sp, owner)) return true;
        HackManager.deny(sp, owner, name);
        return false;
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
            int kind = c.contains("Kind") ? c.getInt("Kind") : (c.getBoolean("Drone") ? DRONE : CAM);   // vínculos antigos só têm "Drone"
            String dim = c.getString("Dim");
            UUID id = c.containsUuid("Id") ? c.getUuid("Id") : null;
            BlockPos pos = c.contains("Pos", NbtElement.COMPOUND_TYPE) ? NbtHelper.toBlockPos(c.getCompound("Pos")) : null;
            if (kind == DRONE ? id == null : pos == null) continue;
            out.add(new Link(kind, dim, pos, id, c.contains("Col") ? c.getInt("Col") : -1, c.getInt("Seq")));
        }
        return out;
    }

    /** Lê os vínculos e dá cor e número (CAM-01...) aos que ainda não têm (vínculos de versões antigas). */
    public static List<Link> readNorm(ItemStack stack) {
        List<Link> links = read(stack);
        if (normalize(links)) write(stack, links);
        return links;
    }

    private static boolean normalize(List<Link> links) {
        boolean changed = false;
        boolean[] usedColor = new boolean[RemoteDevices.RGB.length];
        for (Link l : links) if (l.color() >= 0 && l.color() < usedColor.length) usedColor[l.color()] = true;
        for (int i = 0; i < links.size(); i++) {
            Link l = links.get(i);
            int color = l.color(), seq = l.seq();
            if (color < 0 || color >= usedColor.length) {
                color = 0;
                while (color < usedColor.length && usedColor[color]) color++;
                if (color >= usedColor.length) color = i % usedColor.length;
                else usedColor[color] = true;
            }
            if (seq <= 0) {
                seq = 1;
                while (seqUsed(links, l.kind(), seq)) seq++;
            }
            if (color != l.color() || seq != l.seq()) {
                links.set(i, l.styled(color, seq));
                changed = true;
            }
        }
        return changed;
    }

    private static boolean seqUsed(List<Link> links, int kind, int seq) {
        for (Link x : links) if (x.kind() == kind && x.seq() == seq) return true;
        return false;
    }

    static void write(ItemStack stack, List<Link> links) {
        NbtList list = new NbtList();
        for (Link l : links) {
            NbtCompound c = new NbtCompound();
            c.putInt("Kind", l.kind());
            c.putBoolean("Drone", l.drone());      // compatibilidade com versões antigas
            c.putString("Dim", l.dim());
            if (l.id() != null) c.putUuid("Id", l.id());
            if (l.pos() != null) c.put("Pos", NbtHelper.fromBlockPos(l.pos()));
            c.putInt("Col", l.color());
            c.putInt("Seq", l.seq());
            list.add(c);
        }
        NbtCompound tag = stack.getOrCreateNbt();
        tag.put("Links", list);
        if (links.isEmpty()) tag.putInt("Sel", 0);
        else tag.putInt("Sel", Math.floorMod(tag.getInt("Sel"), links.size()));
    }
}
