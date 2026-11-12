package com.example.vigicam;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Estado do "estou vendo pela câmera" (somente cliente). */
public final class CameraViewClient {
    private static BlockPos pos = null;
    private static float baseYaw = 0f;
    private static boolean armed = false;

    public enum Mode { NIGHT, THERMAL }
    private static Mode mode = Mode.NIGHT;

    private CameraViewClient() {}

    public static boolean isActive() { return pos != null; }
    public static BlockPos getBlockPos() { return pos; }

    public static void start(BlockPos p, float yaw) {
        pos = p;
        baseYaw = yaw;
        armed = false;
        mode = Mode.NIGHT;
    }

    public static void stop() { pos = null; mode = Mode.NIGHT; }

    public static Mode getMode() { return mode; }
    public static boolean isThermal() { return pos != null && mode == Mode.THERMAL; }
    public static void toggleMode() { mode = (mode == Mode.NIGHT) ? Mode.THERMAL : Mode.NIGHT; }

    /** Sai ao apertar Shift, mas só depois de ter soltado o Shift uma vez (evita sair na hora). */
    public static boolean shouldExit(boolean sneakPressed) {
        if (!sneakPressed) { armed = true; return false; }
        return armed;
    }

    /** Posição logo à frente da lente. */
    public static Vec3d getPos() {
        double rad = Math.toRadians(baseYaw);
        return new Vec3d(
                pos.getX() + 0.5 - Math.sin(rad) * 0.6,
                pos.getY() + 0.5,
                pos.getZ() + 0.5 + Math.cos(rad) * 0.6);
    }

    /** Câmera varrendo de um lado para o outro, como câmera de segurança. */
    public static float getYaw(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float t = (mc.player != null ? mc.player.age : 0) + tickDelta;
        return baseYaw + 35f * (float) Math.sin(t / 40.0);
    }

    public static float getPitch() { return 12f; }
}
