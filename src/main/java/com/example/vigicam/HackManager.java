package com.example.vigicam;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Pendrive Hacker (lado servidor).
 * <p>
 * Fluxo: o jogador segura o pendrive e clica numa Antena Amplificadora de OUTRO jogador -> {@link #openPanel} manda o painel
 * ({@link HackPanelScreen}). O puzzle roda no cliente, mas o servidor acompanha a sessão ({@link Session}): confere a
 * antena, a distância, o pendrive na mão, a ordem e o tempo mínimo entre as etapas. Só quando as 3 etapas chegam em ordem
 * o servidor concede o acesso ({@link Grant}).
 * <p>
 * Acesso: por {@link #DURATION_SECONDS} segundos o hacker passa a ser o "dono efetivo" de TUDO que a vítima colocou
 * (câmeras, portas, drones e antenas). O dono real não perde nada de verdade: o campo de dono guardado em cada bloco/entidade
 * nunca muda, só a pergunta "quem manda aqui agora?" ({@link #effectiveOwner}) tem outra resposta. Quando o tempo acaba, a
 * concessão some e tudo volta ao dono sozinho. As concessões ficam só em memória: se o servidor reiniciar, o acesso acaba.
 */
public final class HackManager {
    private HackManager() {}

    /** Duração do acesso total depois de vencer o puzzle. */
    public static final int DURATION_SECONDS = 5 * 60;
    public static final long DURATION_TICKS = DURATION_SECONDS * 20L;

    /**
     * true: câmeras, drones e (a partir do pendrive) tudo que tem dono só responde ao dono efetivo; dispositivos antigos, sem dono
     * registrado, continuam abertos a todos. false: câmeras e drones continuam livres para todo mundo (a invasão passa a valer só
     * para portas e antenas).
     */
    public static final boolean RESTRICT_ACCESS = true;

    /** Ações do cliente (pacote HACK_ACTION). */
    public static final int ACT_INSERT = 0, ACT_STAGE = 1, ACT_FAIL = 2, ACT_EJECT = 3;
    /** Respostas do servidor (pacote HACK_RESULT). */
    public static final int RES_GRANTED = 0, RES_DENIED = 1;

    private static final double MAX_USE_DIST_SQ = 12 * 12;
    /** Tempo mínimo entre eventos do puzzle: ninguém trava uma etapa mais rápido que isso. */
    private static final long MIN_STAGE_MS = 250;
    private static final long SESSION_TIMEOUT_MS = 2 * 60 * 1000;

    /** Acesso concedido: {@code hacker} manda nas coisas da vítima até {@code expiresAt} (tick do HackManager). */
    public record Grant(UUID hacker, String hackerName, String victimName, long expiresAt) {}

    private static final class Session {
        final BlockPos pos;
        final UUID victim;
        int stage;
        long last;

        Session(BlockPos pos, UUID victim, long last) {
            this.pos = pos.toImmutable();
            this.victim = victim;
            this.last = last;
        }
    }

    /** vítima -> concessão. */
    private static final Map<UUID, Grant> ACTIVE = new HashMap<>();
    /** hacker -> puzzle em andamento. */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** jogador -> dono do dispositivo (câmera/drone) que ele está vendo agora. */
    private static final Map<UUID, UUID> VIEWING = new HashMap<>();
    /** Relógio próprio, em ticks do servidor. */
    private static long now;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(HackManager::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    private static void clear() {
        ACTIVE.clear();
        SESSIONS.clear();
        VIEWING.clear();
        now = 0;
    }

    // ------------------------------------------------------------------ consulta de acesso

    /** Concessão ainda válida sobre as coisas de {@code owner}, ou null. */
    @Nullable
    public static Grant activeFor(@Nullable UUID owner) {
        if (owner == null) return null;
        Grant g = ACTIVE.get(owner);
        return g != null && now < g.expiresAt() ? g : null;
    }

    /** Quem manda nas coisas de {@code owner} agora: o hacker durante a invasão, senão o próprio dono. */
    public static UUID effectiveOwner(UUID owner) {
        Grant g = activeFor(owner);
        return g != null ? g.hacker() : owner;
    }

    public static boolean isEffectiveOwner(PlayerEntity p, @Nullable UUID owner) {
        return owner != null && effectiveOwner(owner).equals(p.getUuid());
    }

    /** O jogador está dentro de uma invasão (pendrive) sobre as coisas de {@code owner}? */
    public static boolean isHacking(PlayerEntity p, @Nullable UUID owner) {
        Grant g = activeFor(owner);
        return g != null && g.hacker().equals(p.getUuid());
    }

    /** Pode usar um dispositivo (câmera/drone) deste dono? Sem dono registrado = livre. */
    public static boolean canUse(PlayerEntity p, @Nullable UUID owner) {
        return !RESTRICT_ACCESS || owner == null || isEffectiveOwner(p, owner);
    }

    public static int remainingSeconds(@Nullable UUID owner) {
        Grant g = activeFor(owner);
        return g == null ? 0 : (int) Math.max(0, (g.expiresAt() - now + 19) / 20);
    }

    public static String clock(int seconds) {
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /** Mensagem padrão quando alguém tenta usar o dispositivo de outro jogador. */
    public static void deny(ServerPlayerEntity sp, @Nullable UUID owner, String ownerName) {
        Grant g = activeFor(owner);
        if (g != null && owner != null && owner.equals(sp.getUuid())) {
            msg(sp, "§cSua rede foi invadida por §f" + g.hackerName() + " §7· volta em " + clock(remainingSeconds(owner)));
        } else {
            msg(sp, "§cAcesso negado §7· dispositivo de §f" + (ownerName == null || ownerName.isEmpty() ? "outro jogador" : ownerName));
        }
    }

    // ------------------------------------------------------------------ quem está vendo o quê

    /** Câmera/drone aberto por {@code player}; {@code owner} null = dispositivo sem dono (não precisa de vigia). */
    public static void noteView(UUID player, @Nullable UUID owner) {
        if (owner == null) VIEWING.remove(player); else VIEWING.put(player, owner);
    }

    public static void clearView(UUID player) {
        VIEWING.remove(player);
    }

    /** Jogador saiu do servidor: esquece a visão e o puzzle dele. */
    public static void dropPlayer(UUID player) {
        VIEWING.remove(player);
        SESSIONS.remove(player);
    }

    /** Tira da visão quem não pode mais usar o dispositivo que está vendo (invasão começou ou terminou). */
    private static void enforceViews(MinecraftServer server) {
        Iterator<Map.Entry<UUID, UUID>> it = VIEWING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> e = it.next();
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
            if (p == null) { it.remove(); continue; }
            if (canUse(p, e.getValue())) continue;
            it.remove();
            DroneControl.release(p.getUuid());
            ServerPlayNetworking.send(p, VigiCam.HACK_END, PacketByteBufs.create());
            msg(p, "§cConexão perdida §7· acesso revogado");
        }
    }

    // ------------------------------------------------------------------ painel e puzzle

    /** Pendrive na mão + clique direito na antena: confere tudo e abre o painel. */
    public static void openPanel(ServerPlayerEntity sp, BlockPos base) {
        if (check(sp, base, true) == null) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(base);
        ServerPlayNetworking.send(sp, VigiCam.HACK_PANEL, buf);
    }

    /**
     * Confere se {@code sp} pode invadir a antena em {@code base}; devolve o dono (a vítima) ou null.
     * Com {@code explain} avisa o jogador do motivo.
     */
    @Nullable
    private static UUID check(ServerPlayerEntity sp, BlockPos base, boolean explain) {
        ServerWorld w = sp.getServerWorld();
        if (!HackerUsbItem.holds(sp)) return null;
        if (sp.squaredDistanceTo(Vec3d.ofCenter(base).add(0, 1, 0)) > MAX_USE_DIST_SQ) return null;
        if (!(w.getBlockEntity(base) instanceof AmplifyingAntennaBlockEntity ant)) return null;
        UUID victim = ant.getOwner();
        if (victim == null) {
            if (explain) msg(sp, "§eAntena sem dono §7· não há rede para invadir");
            return null;
        }
        if (victim.equals(sp.getUuid())) {
            if (explain) msg(sp, "§eEsta antena é sua §7· não dá para invadir a própria rede");
            return null;
        }
        Grant g = activeFor(victim);
        if (g != null) {
            if (explain) {
                if (g.hacker().equals(sp.getUuid())) {
                    msg(sp, "§aVocê já controla a rede de §f" + g.victimName() + " §7· " + clock(remainingSeconds(victim)) + " restantes");
                } else {
                    msg(sp, "§cEsta rede já foi invadida por §f" + g.hackerName());
                }
            }
            return null;
        }
        return victim;
    }

    /** Pedido do painel (cliente -> servidor). O cliente só relata; quem concede o acesso é este método. */
    public static void handleAction(ServerPlayerEntity sp, int type, BlockPos pos) {
        long ms = System.currentTimeMillis();

        if (type == ACT_INSERT) {
            UUID victim = check(sp, pos, false);
            if (victim == null) {
                result(sp, RES_DENIED, 0);
                return;
            }
            SESSIONS.put(sp.getUuid(), new Session(pos, victim, ms));
            return;
        }

        Session s = SESSIONS.get(sp.getUuid());
        if (s == null || !s.pos.equals(pos)) {
            if (type != ACT_EJECT) result(sp, RES_DENIED, 0);
            return;
        }
        switch (type) {
            case ACT_EJECT -> SESSIONS.remove(sp.getUuid());
            case ACT_FAIL -> {
                s.stage = 0;
                s.last = ms;
            }
            case ACT_STAGE -> {
                // tudo conferido de novo a cada etapa: antena ainda lá, mesmo dono, ninguém invadiu antes, tempo plausível
                UUID victim = check(sp, pos, false);
                if (victim == null || !victim.equals(s.victim) || ms - s.last < MIN_STAGE_MS) {
                    SESSIONS.remove(sp.getUuid());
                    result(sp, RES_DENIED, 0);
                    return;
                }
                s.last = ms;
                if (++s.stage >= 3) {
                    SESSIONS.remove(sp.getUuid());
                    grant(sp, victim, sp.getServerWorld(), pos);
                }
            }
            default -> { }
        }
    }

    private static void grant(ServerPlayerEntity hacker, UUID victim, ServerWorld world, BlockPos antenna) {
        MinecraftServer server = hacker.getServer();
        String victimName = "outro jogador";
        if (world.getBlockEntity(antenna) instanceof AmplifyingAntennaBlockEntity ant && !ant.getOwnerName().isEmpty()) {
            victimName = ant.getOwnerName();
        }
        String hackerName = hacker.getGameProfile().getName();
        ACTIVE.put(victim, new Grant(hacker.getUuid(), hackerName, victimName, now + DURATION_TICKS));
        result(hacker, RES_GRANTED, DURATION_SECONDS);
        msg(hacker, "§aAcesso total a §f" + victimName + " §7· câmeras, portas, drones e antenas por " + clock(DURATION_SECONDS));
        if (server != null) {
            ServerPlayerEntity v = server.getPlayerManager().getPlayer(victim);
            if (v != null) {
                v.sendMessage(Text.literal("§c⚠ Sua rede foi invadida por §f" + hackerName
                        + "§c! Câmeras, portas, drones e antenas dele por " + clock(DURATION_SECONDS)), false);
            }
            enforceViews(server);   // a vítima que estava numa câmera/drone dela sai da visão
        }
    }

    private static void result(ServerPlayerEntity sp, int type, int seconds) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(type);
        buf.writeBoolean(type == RES_GRANTED);
        buf.writeInt(seconds);
        ServerPlayNetworking.send(sp, VigiCam.HACK_RESULT, buf);
    }

    private static void msg(ServerPlayerEntity sp, String s) {
        sp.sendMessage(Text.literal(s), true);
    }

    // ------------------------------------------------------------------ tempo

    private static void tick(MinecraftServer server) {
        now++;
        if (now % 10 != 0) return;

        if (!ACTIVE.isEmpty()) {
            Iterator<Map.Entry<UUID, Grant>> it = ACTIVE.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Grant> e = it.next();
                Grant g = e.getValue();
                long left = g.expiresAt() - now;
                if (left <= 0) {
                    it.remove();
                    ServerPlayerEntity h = server.getPlayerManager().getPlayer(g.hacker());
                    if (h != null) msg(h, "§eO acesso do pendrive terminou §7· tudo voltou para §f" + g.victimName());
                    ServerPlayerEntity v = server.getPlayerManager().getPlayer(e.getKey());
                    if (v != null) v.sendMessage(Text.literal("§aSeus dispositivos voltaram a ser seus."), false);
                } else if (left <= 200 && now % 20 == 0) {
                    ServerPlayerEntity h = server.getPlayerManager().getPlayer(g.hacker());
                    if (h != null) msg(h, "§eAcesso do pendrive expira em §f" + (left + 19) / 20 + " s");
                }
            }
            enforceViews(server);
        }

        if (!SESSIONS.isEmpty()) {
            long ms = System.currentTimeMillis();
            SESSIONS.values().removeIf(s -> ms - s.last > SESSION_TIMEOUT_MS);
        }
    }
}
