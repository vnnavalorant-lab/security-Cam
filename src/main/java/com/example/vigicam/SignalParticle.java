package com.example.vigicam;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * Partículas do sinal da antena, com desenho próprio (brilho aditivo, sempre "acesas", sem luz do mundo):
 *  - WAVE: anel de onda de rádio, deitado, que se expande a partir da antena girando devagar (arcos tracejados);
 *  - GLOW: halo pulsante na ponta do mastro;
 *  - SPARK: "pacote de dados" luminoso que sai em arco, tremula e se apaga.
 * Usa a folha CUSTOM do vanilla e desenha ele mesmo, então não depende de atlas nem de mixin.
 */
public final class SignalParticle extends Particle {
    private enum Kind { WAVE, GLOW, SPARK }

    private static final Identifier RING = new Identifier(VigiCam.MOD_ID, "textures/particle/signal_ring.png");
    private static final Identifier GLOW = new Identifier(VigiCam.MOD_ID, "textures/particle/signal_glow.png");
    private static final Identifier SPARK = new Identifier(VigiCam.MOD_ID, "textures/particle/signal_spark.png");
    private static final int FULL_BRIGHT = 0xF000F0;

    private final Kind kind;
    private final float maxRadius;   // WAVE: raio final (blocos)
    private final float spin;        // WAVE: giro (rad/tick)
    private final float baseSize;    // GLOW/SPARK: tamanho

    private SignalParticle(ClientWorld world, double x, double y, double z, Kind kind, int life,
                           float maxRadius, float spin, float baseSize) {
        super(world, x, y, z);
        this.kind = kind;
        this.maxAge = life;
        this.maxRadius = maxRadius;
        this.spin = spin;
        this.baseSize = baseSize;
        this.collidesWithWorld = false;
        this.velocityX = 0;
        this.velocityY = 0;
        this.velocityZ = 0;
    }

    // ------------------------------------------------------------------ fábricas

    /** @param strong 1 = drone usando a antena (onda maior e mais rápida); @param mid 1 = onda secundária, menor */
    public static Particle wave(ClientWorld w, double x, double y, double z, double strong, double mid) {
        boolean active = strong > 0.5;
        float radius = (active ? 8.0f : 5.5f) * (mid > 0.5 ? 0.7f : 1.0f);
        float spin = (w.random.nextBoolean() ? 1f : -1f) * (0.025f + w.random.nextFloat() * 0.03f);
        return new SignalParticle(w, x, y, z, Kind.WAVE, active ? 26 : 40, radius, spin, 0f);
    }

    public static Particle glow(ClientWorld w, double x, double y, double z) {
        return new SignalParticle(w, x, y, z, Kind.GLOW, 14, 0f, 0f, 0.42f);
    }

    public static Particle spark(ClientWorld w, double x, double y, double z, double vx, double vy, double vz) {
        SignalParticle p = new SignalParticle(w, x, y, z, Kind.SPARK, 22 + w.random.nextInt(22), 0f, 0f,
                0.05f + w.random.nextFloat() * 0.045f);
        p.velocityX = vx;
        p.velocityY = vy;
        p.velocityZ = vz;
        return p;
    }

    // ------------------------------------------------------------------ lógica

    @Override
    public void tick() {
        this.prevPosX = this.x;
        this.prevPosY = this.y;
        this.prevPosZ = this.z;
        if (this.age++ >= this.maxAge) {
            this.markDead();
            return;
        }
        if (kind == Kind.SPARK) {
            this.velocityX *= 0.93;
            this.velocityZ *= 0.93;
            this.velocityY = this.velocityY * 0.92 - 0.0015;
            this.move(this.velocityX, this.velocityY, this.velocityZ);
        }
    }

    @Override
    public ParticleTextureSheet getType() {
        return ParticleTextureSheet.CUSTOM;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void buildGeometry(VertexConsumer ignored, Camera camera, float tickDelta) {
        float t = MathHelper.clamp((this.age + tickDelta) / (float) this.maxAge, 0f, 1f);
        float r, g, b, a, size;
        Identifier tex;
        switch (kind) {
            case WAVE -> {
                tex = RING;
                size = maxRadius * t;                                   // frente de onda a velocidade constante
                float fadeIn = Math.min(1f, t / 0.08f);
                a = fadeIn * (float) Math.pow(1f - t, 1.4);
                r = 1f; g = 0.12f + 0.38f * (1f - t); b = 0.08f + 0.2f * (1f - t);   // laranja-branco -> vermelho
            }
            case GLOW -> {
                tex = GLOW;
                size = baseSize * (0.75f + 0.5f * MathHelper.sin(t * (float) Math.PI));
                a = 0.9f * MathHelper.sin(t * (float) Math.PI);
                r = 1f; g = 0.2f; b = 0.12f;
            }
            default -> {
                tex = SPARK;
                size = baseSize * (1f - 0.55f * t);
                float flicker = 0.65f + 0.35f * (float) Math.sin((this.age + tickDelta) * 1.9f + this.maxAge);
                a = (1f - t) * flicker;
                r = 1f; g = 0.35f + 0.5f * (1f - t); b = 0.3f + 0.45f * (1f - t);   // branco quente -> vermelho
            }
        }
        if (a <= 0.003f || size <= 0.0005f) return;

        Vec3d cam = camera.getPos();
        float px = (float) (MathHelper.lerp(tickDelta, this.prevPosX, this.x) - cam.x);
        float py = (float) (MathHelper.lerp(tickDelta, this.prevPosY, this.y) - cam.y);
        float pz = (float) (MathHelper.lerp(tickDelta, this.prevPosZ, this.z) - cam.z);

        // quatro cantos (sentido horário na tela), com UV correspondente
        Vector3f[] c = new Vector3f[4];
        if (kind == Kind.WAVE) {
            float ang = spin * (this.age + tickDelta);
            float cs = MathHelper.cos(ang), sn = MathHelper.sin(ang);
            float[][] q = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
            for (int i = 0; i < 4; i++) {
                float sx = q[i][0], sz = q[i][1];
                c[i] = new Vector3f((sx * cs - sz * sn) * size, 0f, (sx * sn + sz * cs) * size);
            }
        } else {
            float[][] q = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
            for (int i = 0; i < 4; i++) {
                c[i] = new Vector3f(q[i][0] * size, q[i][1] * size, 0f).rotate(camera.getRotation());
            }
        }
        float[] us = {0f, 0f, 1f, 1f}, vs = {1f, 0f, 0f, 1f};

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);   // soma de luz
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getParticleProgram);
        RenderSystem.setShaderTexture(0, tex);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder bb = tess.getBuffer();
        bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR_LIGHT);
        for (int i = 0; i < 4; i++) {
            bb.vertex(px + c[i].x, py + c[i].y, pz + c[i].z)
                    .texture(us[i], vs[i])
                    .color(r, g, b, a)
                    .light(FULL_BRIGHT)
                    .next();
        }
        tess.draw();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }
}
