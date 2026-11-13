package com.example.vigicam;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityGroup;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;

/** "Temperatura" de cada entidade para a visão térmica. */
public final class HeatUtil {
    private HeatUtil() {}

    private record Profile(float heat, float celsius) {}

    private static Profile profile(Entity e) {
        EntityType<?> t = e.getType();

        if (e instanceof DroneEntity) return new Profile(0.65f, 45f);   // motores quentes
        if (e.isOnFire()) return new Profile(1.00f, 85f);
        if (t == EntityType.BLAZE || t == EntityType.MAGMA_CUBE) return new Profile(1.00f, 120f);
        if (t == EntityType.TNT || t == EntityType.FIREBALL || t == EntityType.SMALL_FIREBALL
                || t == EntityType.DRAGON_FIREBALL) return new Profile(1.00f, 200f);
        if (t == EntityType.STRIDER) return new Profile(0.95f, 70f);

        if (e instanceof LivingEntity le) {
            if (t == EntityType.PLAYER) return new Profile(0.92f, 36.6f);
            if (t == EntityType.SNOW_GOLEM) return new Profile(0.05f, -2f);
            if (t == EntityType.IRON_GOLEM) return new Profile(0.25f, 12f);
            if (t == EntityType.CREEPER) return new Profile(0.50f, 22f);
            if (t == EntityType.ENDERMAN) return new Profile(0.75f, 34f);
            if (t == EntityType.SLIME) return new Profile(0.30f, 15f);
            if (t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER
                    || t == EntityType.SILVERFISH || t == EntityType.ENDERMITE) return new Profile(0.40f, 18f);
            EntityGroup g = le.getGroup();
            if (g == EntityGroup.UNDEAD) return new Profile(0.12f, 6f);
            if (g == EntityGroup.AQUATIC) return new Profile(0.30f, 16f);
            return new Profile(0.85f, 38.5f);   // sangue quente (animais, aldeões, ...)
        }
        return new Profile(0.30f, 15f);          // itens, flechas, minecarts, ...
    }

    /** 0..1, usado para colorir a entidade (e o HUD). */
    public static float heat(Entity e) { return profile(e).heat(); }

    /** Temperatura exibida no HUD, em °C. */
    public static float celsius(Entity e) { return profile(e).celsius(); }

    /** Valor 0..255 gravado no framebuffer de silhuetas (lido como canal R no shader). */
    public static int heatByte(Entity e) { return Math.round(heat(e) * 255f); }

    // mesma paleta do shader
    private static final float[][] PAL = {
            {0.02f, 0.00f, 0.20f}, {0.00f, 0.12f, 0.85f}, {0.00f, 0.75f, 0.95f}, {0.10f, 0.85f, 0.20f},
            {1.00f, 0.95f, 0.05f}, {1.00f, 0.45f, 0.00f}, {0.95f, 0.05f, 0.25f}, {1.00f, 0.97f, 0.92f}
    };

    /** Cor ARGB da paleta térmica para t em 0..1. */
    public static int palette(float t) {
        float x = Math.max(0f, Math.min(1f, t)) * 7f;
        int i = Math.min((int) Math.floor(x), 6);
        float f = x - i;
        int r = Math.round((PAL[i][0] + (PAL[i + 1][0] - PAL[i][0]) * f) * 255f);
        int g = Math.round((PAL[i][1] + (PAL[i + 1][1] - PAL[i][1]) * f) * 255f);
        int b = Math.round((PAL[i][2] + (PAL[i + 1][2] - PAL[i][2]) * f) * 255f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
