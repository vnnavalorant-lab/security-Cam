package com.example.vigicam;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Estado do controle remoto de drones (lado servidor): quem pilota qual drone e o último input recebido. */
public final class DroneControl {
    private DroneControl() {}

    public record Input(float forward, float strafe, float vertical, float yaw, float pitch, long time) {}

    private static final Map<UUID, Input> INPUTS = new HashMap<>();
    private static final Map<UUID, DroneEntity> CONTROLLING = new HashMap<>();

    public static boolean begin(ServerPlayerEntity sp, DroneEntity drone, int index, int count) {
        UUID owner = drone.getController();
        if (owner != null && !owner.equals(sp.getUuid())) {
            if (sp.getServer() != null && sp.getServer().getPlayerManager().getPlayer(owner) != null) {
                sp.sendMessage(Text.literal("§cEste drone já está sendo pilotado por outro jogador."), true);
                return false;
            }
            release(owner);   // piloto antigo não está mais online
        }
        release(sp.getUuid());   // solta o drone anterior (se houver)
        drone.setController(sp.getUuid());
        CONTROLLING.put(sp.getUuid(), drone);
        INPUTS.put(sp.getUuid(), new Input(0, 0, 0, drone.getYaw(), drone.getPitch(), sp.getWorld().getTime()));
        VigiCam.sendViewDrone(sp, drone.getId(), index, count);
        return true;
    }

    public static void release(UUID player) {
        INPUTS.remove(player);
        DroneEntity d = CONTROLLING.remove(player);
        if (d != null && player.equals(d.getController())) d.clearController();
    }

    public static void update(ServerPlayerEntity p, float forward, float strafe, float vertical, float yaw, float pitch) {
        if (!CONTROLLING.containsKey(p.getUuid())) return;
        if (!Float.isFinite(forward) || !Float.isFinite(strafe) || !Float.isFinite(vertical)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        INPUTS.put(p.getUuid(), new Input(MathHelper.clamp(forward, -1f, 1f), MathHelper.clamp(strafe, -1f, 1f),
                MathHelper.clamp(vertical, -1f, 1f), yaw, MathHelper.clamp(pitch, -90f, 90f), p.getWorld().getTime()));
    }

    public static Input input(UUID player) { return INPUTS.get(player); }

    public static void clear() {
        INPUTS.clear();
        CONTROLLING.clear();
    }
}
