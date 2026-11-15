package com.example.vigicam;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Painel de dispositivos do controle remoto (lado servidor). O cliente ({@link RemotePanelScreen}) só mostra e pede;
 * quem sabe se a câmera/drone/porta está online, quem é o dono, o estado da porta e quem pode usar é o servidor,
 * que manda a lista inteira ({@code REMOTE_DATA}) ao abrir o painel, a cada ação e a cada meio segundo (o painel pede).
 */
public final class RemotePanel {
    private RemotePanel() {}

    /** Ações do cliente (pacote REMOTE_ACTION; o segundo número é o índice do dispositivo, ou o tipo em REMOVE_ALL). */
    public static final int ACT_REFRESH = 0, ACT_PING = 1, ACT_USE = 2, ACT_REMOVE = 3, ACT_REMOVE_ALL = 4;

    private static final String OFFLINE_MSG = "Dispositivo OFFLINE. Sem conexao com o controle.";

    /** Estado de um dispositivo para o painel. */
    private record Info(ServerWorld world, boolean online, boolean hasPos, double x, double y, double z, String name, boolean locked) {}

    public static void open(ServerPlayerEntity sp) {
        if (held(sp).isEmpty()) return;
        send(sp, true, "");
    }

    private static ItemStack held(PlayerEntity p) {
        if (p.getMainHandStack().getItem() instanceof RemoteItem) return p.getMainHandStack();
        if (p.getOffHandStack().getItem() instanceof RemoteItem) return p.getOffHandStack();
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ ações do painel

    public static void handle(ServerPlayerEntity sp, int type, int index) {
        ItemStack stack = held(sp);
        if (stack.isEmpty()) return;
        List<RemoteItem.Link> links = RemoteItem.readNorm(stack);
        boolean valid = index >= 0 && index < links.size();

        switch (type) {
            case ACT_REFRESH -> send(sp, false, "");

            case ACT_PING -> {
                if (!valid) return;
                RemoteItem.Link l = links.get(index);
                Info in = resolve(sp, l);
                if (!in.online()) {
                    send(sp, false, OFFLINE_MSG);
                    return;
                }
                RemotePings.start(in.world(), new Vec3d(in.x(), in.y(), in.z()), l.drone() ? l.id() : null, RemoteDevices.color(l.color()));
                send(sp, false, "PING " + RemoteItem.idOf(l) + " (cor " + RemoteDevices.NAMES[l.color()] + "): ele apita e solta particulas no mundo.");
            }

            case ACT_USE -> {
                if (!valid) return;
                RemoteItem.Link l = links.get(index);
                if (!resolve(sp, l).online()) {
                    send(sp, false, OFFLINE_MSG);
                    return;
                }
                if (l.door()) {
                    send(sp, false, RemoteItem.toggleDoor(sp, l));
                    return;
                }
                int[] vp = RemoteItem.viewPos(links, index);
                stack.getOrCreateNbt().putInt("Sel", index);        // a tecla "próximo" continua a partir deste
                String id = RemoteItem.idOf(l);
                if (!RemoteItem.connect(sp, l, vp[0], vp[1])) send(sp, false, "Sem conexao com " + id + ".");
                // com sucesso a visão abre e o cliente fecha o painel
            }

            case ACT_REMOVE -> {
                if (!valid) return;
                RemoteItem.Link gone = links.remove(index);
                RemoteItem.write(stack, links);
                send(sp, false, RemoteItem.idOf(gone) + " removido do controle.");
            }

            case ACT_REMOVE_ALL -> {
                int before = links.size();
                links.removeIf(l -> l.kind() == index);
                int n = before - links.size();
                RemoteItem.write(stack, links);
                String noun = RemoteDevices.noun(index);
                send(sp, false, n + " " + (n == 1 ? noun + " removido(a)" : noun + "s removidos(as)") + ".");
            }

            default -> { }
        }
    }

    // ------------------------------------------------------------------ estado dos dispositivos

    private static Info resolve(ServerPlayerEntity sp, RemoteItem.Link link) {
        MinecraftServer server = sp.getServer();
        ServerWorld w = server == null ? null
                : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, new Identifier(link.dim())));
        BlockPos lp = link.pos();
        Vec3d fallback = lp == null ? Vec3d.ZERO : Vec3d.ofCenter(lp);
        String base = link.drone() ? "Drone" : link.door() ? "Porta" : "Camera";
        if (w == null) return new Info(sp.getServerWorld(), false, lp != null, fallback.x, fallback.y, fallback.z, base, false);

        if (link.drone()) {
            if (link.id() != null && w.getEntity(link.id()) instanceof DroneEntity d && !d.isRemoved()) {
                Vec3d p = d.getPos();
                return new Info(w, HackManager.canUse(sp, d.getOwner()), true, p.x, p.y, p.z, named(base, d.getOwnerName()), false);
            }
            return new Info(w, false, lp != null, fallback.x, fallback.y, fallback.z, base, false);
        }

        if (lp == null || !w.isChunkLoaded(lp)) return new Info(w, false, lp != null, fallback.x, fallback.y, fallback.z, base, false);
        BlockState st = w.getBlockState(lp);

        if (link.door()) {
            if (!(st.getBlock() instanceof ReinforcedDoorBlock) || !(w.getBlockEntity(lp) instanceof ReinforcedDoorBlockEntity be)) {
                return new Info(w, false, true, fallback.x, fallback.y, fallback.z, base, false);
            }
            boolean access = !be.hasOwner() || be.isOwner(sp);
            String owner = "";
            if (be.hasOwner() && server != null) {
                ServerPlayerEntity op = server.getPlayerManager().getPlayer(be.getOwnerUuid());
                if (op != null) owner = op.getGameProfile().getName();
            }
            return new Info(w, access, true, lp.getX() + 0.5, lp.getY() + 1.5, lp.getZ() + 0.5, named(base, owner), !st.get(ReinforcedDoorBlock.OPEN));
        }

        if (!(st.getBlock() instanceof SurveillanceCameraBlock) || !(w.getBlockEntity(lp) instanceof SurveillanceCameraBlockEntity cam)) {
            return new Info(w, false, true, fallback.x, fallback.y, fallback.z, base, false);
        }
        return new Info(w, HackManager.canUse(sp, cam.getOwner()), true, fallback.x, fallback.y, fallback.z, named(base, cam.getOwnerName()), false);
    }

    private static String named(String base, String owner) {
        return owner == null || owner.isEmpty() ? base : base + " de " + owner;
    }

    // ------------------------------------------------------------------ pacote REMOTE_DATA

    private static void send(ServerPlayerEntity sp, boolean open, String msg) {
        ItemStack stack = held(sp);
        List<RemoteItem.Link> links = stack.isEmpty() ? List.of() : RemoteItem.readNorm(stack);

        // última posição conhecida dos drones (para o radar, quando estiverem offline); só grava se andou bastante
        Map<Integer, BlockPos> moved = new HashMap<>();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(open);
        buf.writeString(msg);
        buf.writeVarInt(links.size());
        for (int i = 0; i < links.size(); i++) {
            RemoteItem.Link l = links.get(i);
            Info in = resolve(sp, l);
            if (l.drone() && in.online() && (l.pos() == null || l.pos().getSquaredDistance(in.x(), in.y(), in.z()) > 16)) {
                moved.put(i, BlockPos.ofFloored(in.x(), in.y(), in.z()));
            }
            buf.writeByte(l.kind());
            buf.writeByte(l.seq());
            buf.writeByte(l.color());
            buf.writeBoolean(in.online());
            buf.writeString(in.name());
            buf.writeString(l.dim());
            buf.writeBoolean(in.hasPos());
            buf.writeInt((int) Math.floor(in.x()));
            buf.writeInt((int) Math.floor(in.y()));
            buf.writeInt((int) Math.floor(in.z()));
            buf.writeBoolean(in.locked());
        }
        if (!moved.isEmpty()) {
            for (Map.Entry<Integer, BlockPos> e : moved.entrySet()) links.set(e.getKey(), links.get(e.getKey()).at(e.getValue()));
            RemoteItem.write(stack, links);
        }
        ServerPlayNetworking.send(sp, VigiCam.REMOTE_DATA, buf);
    }
}
