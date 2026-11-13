package com.example.vigicam;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Estado da visão de câmera no cliente: câmera fixa (bloco) ou drone, modo de visão e controle manual. */
public final class CameraViewClient {
    private CameraViewClient() {}

    public enum Mode { CAM, NIGHT, THERMAL }
    public enum Kind { BLOCK, DRONE }

    public static final float SWEEP_AMPL = 35f;
    public static final float SWEEP_STEP = 1f / 40f;       // rad/tick da varredura automática
    private static final float AUTO_PITCH = 12f;
    private static final float MANUAL_YAW_LIMIT = 110f;    // quanto a câmera fixa pode girar para cada lado
    private static final float PITCH_MIN = -50f, PITCH_MAX = 80f;
    private static final int BLEND_TICKS = 20;
    public static final double DRONE_EYE = 0.2;
    private static final double LENS_DIST = 0.72;           // pivô -> lente (blocos)
    private static final double LENS_HEIGHT = 7.5 / 16.0;    // altura da lente no modelo

    private static boolean active;
    private static Kind kind = Kind.BLOCK;
    private static Mode mode = Mode.CAM;
    private static boolean armed;

    // câmera fixa
    private static BlockPos pos = BlockPos.ORIGIN;
    private static float baseYaw;
    private static boolean manual;
    private static float sweepPhase;
    private static float blendYaw, blendPitch;
    private static int blendTicks;

    // drone
    private static int droneId = -1;
    private static boolean droneSeen, alignPending;
    private static int droneMissing;
    private static Vec3d lastPos = Vec3d.ZERO;

    // posição da lista do controle remoto
    private static int index = -1, count = 0;

    // ângulos da visão (mouse). O corpo/cabeça do jogador NÃO gira enquanto ele usa a câmera/drone:
    // o mouse mexe só aqui (ver EntityMixin.changeLookDirection).
    private static float viewYaw, viewPitch;
    // 0..1: intensidade do que o piloto está apertando (para as mãos "mexendo" no controle)
    private static float inputLevel;

    // ------------------------------------------------------------------ estado

    public static boolean isActive() { return active; }
    public static Kind getKind() { return kind; }
    public static boolean isDrone() { return active && kind == Kind.DRONE; }
    public static boolean isDrone(int entityId) { return isDrone() && droneId == entityId; }
    public static boolean isThermal() { return active && mode == Mode.THERMAL; }
    public static boolean isManual() { return manual; }
    public static Mode getMode() { return mode; }
    public static boolean isNight() { return active && mode == Mode.NIGHT; }
    public static Mode nextMode() {
        return switch (mode) { case CAM -> Mode.NIGHT; case NIGHT -> Mode.THERMAL; case THERMAL -> Mode.CAM; };
    }
    /** Alterna a visão: normal (VHS + fisheye + visor) -> noturna (verde) -> térmica -> normal. */
    public static void toggleMode() { mode = nextMode(); }
    public static BlockPos getBlockPos() { return pos; }
    public static int getDroneId() { return droneId; }
    public static int getIndex() { return index; }
    public static int getCount() { return count; }

    /** @return true se a visão acabou de ser ativada (e o shader precisa ser carregado). */
    public static boolean startBlock(MinecraftClient mc, BlockPos p, float yaw, int idx, int cnt) {
        boolean fresh = begin(mc);
        kind = Kind.BLOCK;
        pos = p;
        baseYaw = yaw;
        manual = false;
        blendTicks = 0;
        // começa a varredura na mesma fase da animação do modelo no mundo (sem pulo ao entrar na câmera)
        sweepPhase = mc.world != null ? mc.world.getTime() * SWEEP_STEP : 0f;
        droneId = -1;
        index = idx;
        count = cnt;
        return fresh;
    }

    public static boolean startDrone(MinecraftClient mc, int id, int idx, int cnt) {
        boolean fresh = begin(mc);
        kind = Kind.DRONE;
        droneId = id;
        droneSeen = false;
        droneMissing = 0;
        alignPending = true;
        manual = false;
        index = idx;
        count = cnt;
        return fresh;
    }

    private static boolean begin(MinecraftClient mc) {
        if (active) return false;
        active = true;
        armed = false;
        mode = Mode.CAM;
        if (mc.player != null) {
            viewYaw = mc.player.getYaw();
            viewPitch = mc.player.getPitch();
        }
        inputLevel = 0f;
        return true;
    }

    public static void stop(MinecraftClient mc) {
        active = false;
        inputLevel = 0f;
        manual = false;
        droneId = -1;
        index = -1;
        count = 0;
        mode = Mode.CAM;
    }

    public static boolean shouldExit(boolean sneakPressed) {
        if (!sneakPressed) armed = true;
        return armed && sneakPressed;
    }

    /** Drone sumiu (destruído / saiu do alcance de rastreamento)? */
    public static boolean droneLost() {
        return kind == Kind.DRONE && ((droneSeen && droneMissing > 0) || droneMissing > 60);
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        sweepPhase += SWEEP_STEP;      // a varredura continua "rodando" mesmo no modo manual
        if (blendTicks > 0) blendTicks--;

        if (kind == Kind.DRONE) {
            DroneEntity d = getDrone(mc);
            if (d == null) {
                droneMissing++;
            } else {
                droneSeen = true;
                droneMissing = 0;
                if (alignPending) {
                    viewYaw = d.getYaw();
                    viewPitch = d.getPitch();
                    alignPending = false;
                }
            }
        }
    }

    /** Atalho "mover visão": alterna entre olhar com o mouse e a varredura automática (só câmera fixa). */
    public static boolean toggleManual(MinecraftClient mc, float tickDelta) {
        if (kind != Kind.BLOCK || mc.player == null) return false;
        float yaw = getYaw(mc, tickDelta), pitch = getPitch(mc, tickDelta);
        if (!manual) {
            manual = true;
            viewYaw = yaw;                          // continua de onde a câmera estava, sem pulo
            viewPitch = pitch;
        } else {
            manual = false;
            blendYaw = yaw;
            blendPitch = pitch;
            blendTicks = BLEND_TICKS;              // volta suavemente para a varredura
        }
        return true;
    }

    // ------------------------------------------------------------------ pose da câmera

    @org.jetbrains.annotations.Nullable
    public static DroneEntity getDrone(MinecraftClient mc) {
        if (mc.world == null || droneId < 0) return null;
        Entity e = mc.world.getEntityById(droneId);
        return (e instanceof DroneEntity d && !d.isRemoved()) ? d : null;
    }

    public static Vec3d getPos(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (kind == Kind.DRONE) {
            DroneEntity d = getDrone(mc);
            if (d != null) {
                double r = Math.toRadians(getYaw(mc, tickDelta));
                Vec3d p = d.getLerpedPos(tickDelta).add(-Math.sin(r) * 0.2, DRONE_EYE, Math.cos(r) * 0.2);
                lastPos = p;
            }
            return lastPos;
        }
        // câmera fixa: na lente do modelo. O pivô "root" fica 2px atrás do centro do bloco (lado da base)
        // e a lente está ~0,7 bloco à frente dele, na direção para onde a câmera está olhando agora.
        double fr = Math.toRadians(baseYaw);
        double px = pos.getX() + 0.5 + Math.sin(fr) * (2.0 / 16.0);
        double pz = pos.getZ() + 0.5 - Math.cos(fr) * (2.0 / 16.0);
        double ry = Math.toRadians(getYaw(MinecraftClient.getInstance(), tickDelta));
        return new Vec3d(px - Math.sin(ry) * LENS_DIST, pos.getY() + LENS_HEIGHT, pz + Math.cos(ry) * LENS_DIST);
    }

    public static float getYaw(float tickDelta) { return getYaw(MinecraftClient.getInstance(), tickDelta); }
    public static float getPitch(float tickDelta) { return getPitch(MinecraftClient.getInstance(), tickDelta); }

    private static float getYaw(MinecraftClient mc, float td) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return baseYaw;
        if (kind == Kind.DRONE) return viewYaw;                      // o drone vira para onde eu olho
        if (manual) {
            float rel = MathHelper.wrapDegrees(viewYaw - baseYaw);
            viewYaw = baseYaw + MathHelper.clamp(rel, -MANUAL_YAW_LIMIT, MANUAL_YAW_LIMIT);
            return viewYaw;
        }
        float sweep = baseYaw + SWEEP_AMPL * (float) Math.sin(sweepPhase + td * SWEEP_STEP);
        if (blendTicks > 0) {
            float b = smooth(1f - (blendTicks - td) / BLEND_TICKS);
            return blendYaw + MathHelper.wrapDegrees(sweep - blendYaw) * b;
        }
        return sweep;
    }

    private static float getPitch(MinecraftClient mc, float td) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return AUTO_PITCH;
        if (kind == Kind.DRONE) return MathHelper.clamp(viewPitch, -90f, 90f);
        if (manual) {
            viewPitch = MathHelper.clamp(viewPitch, PITCH_MIN, PITCH_MAX);
            return viewPitch;
        }
        if (blendTicks > 0) {
            float b = smooth(1f - (blendTicks - td) / BLEND_TICKS);
            return blendPitch + (AUTO_PITCH - blendPitch) * b;
        }
        return AUTO_PITCH;
    }

    private static float smooth(float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    // ------------------------------------------------------------------ mouse / entrada

    /** Mouse enquanto usa câmera/drone: gira só a visão (mesma sensibilidade do vanilla), nunca o jogador. */
    public static void lookDelta(double dx, double dy) {
        viewYaw += (float) dx * 0.15f;
        viewPitch = MathHelper.clamp(viewPitch + (float) dy * 0.15f, -90f, 90f);
    }

    public static float getViewYaw() { return viewYaw; }
    public static float getViewPitch() { return viewPitch; }
    public static float getInputLevel() { return inputLevel; }
    public static void setInputLevel(float v) { inputLevel = v; }
}
