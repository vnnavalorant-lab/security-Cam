package com.example.vigicam;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** (cliente) Quem está usando câmera/drone agora e se alguém está segurando o controle remoto. */
public final class PilotState {
    private PilotState() {}

    private static final Set<UUID> PILOTS = ConcurrentHashMap.newKeySet();

    public static void set(UUID id, boolean on) {
        if (on) PILOTS.add(id); else PILOTS.remove(id);
    }

    public static void clear() { PILOTS.clear(); }

    public static boolean holdsRemote(LivingEntity e) {
        return e.getMainHandStack().getItem() instanceof RemoteItem || e.getOffHandStack().getItem() instanceof RemoteItem;
    }

    /** 0 = só segurando; >0 = as mãos escorregam pelos controles (quanto maior, mais mexem). */
    public static float amplitude(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (p == mc.player) {
            if (!CameraViewClient.isActive()) return 0f;
            return CameraViewClient.isDrone() ? 0.45f + 0.55f * CameraViewClient.getInputLevel() : 0.35f;
        }
        return PILOTS.contains(p.getUuid()) ? 0.8f : 0f;
    }
}
