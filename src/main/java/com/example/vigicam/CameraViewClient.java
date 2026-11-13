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

    public enum Mode { NIGHT, THERMAL }
    public enum Kind { BLOCK, DRONE }

    private static final float SWEEP_AMPL = 35f;
    private static final float SWEEP_STEP = 1f / 40f;      // rad/tick da varredura automática
    private static final float AUTO_PITCH = 12f;
    private static final float MANUAL_YAW_LIMIT = 110f;    // quanto a câmera fixa pode girar para cada lado
    private static final float PITCH_MIN = -50f, PITCH_MAX = 80f;
    private static final int BLEND_TICKS = 20;
    public static final double DRONE_EYE = 0.2;

    private static boolean active;
    private static Kind kind = Kind.BLOCK;
    private static Mode mode = Mode.NIGHT;
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

    // rotação original do jogador (restaurada ao sair)
    private static boolean hasSaved;
    private static float savedYaw, savedPitch;

    // ------------------------------------------------------------------ estado

    public static boolean isActive() { return active; }
    public static Kind getKind() { return kind; }
    public static boolean isDrone() { return active && kind == Kind.DRONE; }
    public static boolean isDrone(int entityId) { return isDrone() && droneId == entityId; }
    public static boolean isThermal() { return active && mode == Mode.THERMAL; }
    public static boolean isManual() { return manual; }
    public static Mode getMode() { return mode; }
    public static void toggleMode() { mode = (mode == Mode.NIGHT) ? Mode.THERMAL : Mode.NIGHT; }
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
        sweepPhase = 0f;
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
        mode = Mode.NIGHT;
        if (mc.player != null) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
            hasSaved = true;
        }
        return true;
    }

    public static void stop(MinecraftClient mc) {
        if (hasSaved && mc.player != null) setPlayerRot(mc.player, savedYaw, savedPitch);
        hasSaved = false;
        active = false;
        manual = false;
        droneId = -1;
        index = -1;
        count = 0;
        mode = Mode.NIGHT;
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
                if (alignPending && mc.player != null) {
                    setPlayerRot(mc.player, d.getYaw(), d.getPitch());
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
            setPlayerRot(mc.player, yaw, pitch);   // continua de onde a câmera estava, sem pulo
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
        // câmera fixa: um pouco à frente do bloco
        double r = Math.toRadians(baseYaw);
        return new Vec3d(pos.getX() + 0.5 - Math.sin(r) * 0.6, pos.getY() + 0.5, pos.getZ() + 0.5 + Math.cos(r) * 0.6);
    }

    public static float getYaw(float tickDelta) { return getYaw(MinecraftClient.getInstance(), tickDelta); }
    public static float getPitch(float tickDelta) { return getPitch(MinecraftClient.getInstance(), tickDelta); }

    private static float getYaw(MinecraftClient mc, float td) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return baseYaw;
        if (kind == Kind.DRONE) return p.getYaw(td);                 // o drone vira para onde eu olho
        if (manual) {
            float yaw = p.getYaw(td);
            float rel = MathHelper.wrapDegrees(yaw - baseYaw);
            float cl = MathHelper.clamp(rel, -MANUAL_YAW_LIMIT, MANUAL_YAW_LIMIT);
            if (cl != rel) {
                float delta = cl - rel;
                p.setYaw(p.getYaw() + delta);
                p.prevYaw += delta;
                yaw += delta;
            }
            return yaw;
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
        if (kind == Kind.DRONE) return MathHelper.clamp(p.getPitch(td), -90f, 90f);
        if (manual) {
            float pitch = p.getPitch(td);
            float cl = MathHelper.clamp(pitch, PITCH_MIN, PITCH_MAX);
            if (cl != pitch) {
                float delta = cl - pitch;
                p.setPitch(p.getPitch() + delta);
                p.prevPitch += delta;
            }
            return cl;
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

    private static void setPlayerRot(ClientPlayerEntity p, float yaw, float pitch) {
        float dy = yaw - p.getYaw(), dp = pitch - p.getPitch();
        p.setYaw(yaw);
        p.setPitch(pitch);
        p.prevYaw += dy;
        p.prevPitch += dp;
    }
}
