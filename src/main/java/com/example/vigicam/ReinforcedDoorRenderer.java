package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Set;

/**
 * Desenha a Porta Reforçada (modelo reinforced_door.geo.json). Estado travado = animação "off", destravado = "on";
 * a abertura gira o osso "door" em torno do pivô dele (dobradiça).
 */
public class ReinforcedDoorRenderer implements BlockEntityRenderer<ReinforcedDoorBlockEntity> {
    public static final Identifier TEXTURE = new Identifier(VigiCam.MOD_ID, "textures/entity/reinforced_door.png");
    private static final String GEO = "/assets/vigicam/geo/reinforced_door.geo.json";
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Giro máximo do osso "door" (graus, em torno de +Y). */
    private static final float OPEN_ANGLE = 90f;
    /** Luzes indicadoras: sempre acesas (sem sombreamento). */
    private static final Set<String> LIGHT_BONES = Set.of("locked", "locked2", "unlocked", "unlocked2", "green", "green2");

    private static GeoModel model;

    public static GeoModel model() {
        if (model == null) model = new GeoModel(GEO);
        return model;
    }

    public ReinforcedDoorRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(ReinforcedDoorBlockEntity be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        BlockState state = be.getCachedState();
        World world = be.getWorld();
        if (world == null || !(state.getBlock() instanceof ReinforcedDoorBlock)) return;

        // o bloco do mestre fica na base: pega também a luz do bloco do meio (a porta ocupa 3 de altura)
        int mid = WorldRenderer.getLightmapCoordinates(world, be.getPos().up());
        int lit = LightmapTextureManager.pack(
                Math.max(LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getBlockLightCoordinates(mid)),
                Math.max(LightmapTextureManager.getSkyLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(mid)));

        float facing = state.get(ReinforcedDoorBlock.FACING).asRotation();
        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing));
        matrices.scale(1f / 16f, 1f / 16f, 1f / 16f);
        draw(matrices, vcp, lit, OverlayTexture.DEFAULT_UV, state.get(ReinforcedDoorBlock.UNLOCKED), be.progress(tickDelta));
        matrices.pop();
    }

    /**
     * @param m        origem no centro-baixo da porta, 1 px = 1/16 de bloco
     * @param open     0 = fechada ... 1 = totalmente aberta
     */
    public static void draw(MatrixStack m, VertexConsumerProvider vcp, int light, int overlay, boolean unlocked, float open) {
        float eased = open * open * (3f - 2f * open);   // suaviza o começo e o fim do giro
        Map<String, float[]> scales = DoorAnimations.scales(unlocked ? "on" : "off");

        // 1ª passada: o corpo, com a luz do mundo; 2ª passada: só as luzes indicadoras, em brilho máximo
        for (int pass = 0; pass < 2; pass++) {
            final boolean lightsPass = pass == 1;
            model().render(m, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)),
                    lightsPass ? FULL_BRIGHT : light, overlay, new GeoModel.BoneHandler() {
                        @Override
                        public boolean transform(String bone, MatrixStack ms, float px, float py, float pz) {
                            float[] s = scales.get(bone);
                            if (s != null) {
                                if (s[0] == 0f && s[1] == 0f && s[2] == 0f) return false;   // escala 0 = escondido
                                ms.translate(px, py, pz);
                                ms.scale(s[0], s[1], s[2]);
                                ms.translate(-px, -py, -pz);
                            }
                            if ("door".equals(bone)) {   // dobradiça: o pivô "door" gira a folha da porta
                                ms.translate(px, py, pz);
                                ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-OPEN_ANGLE * eased));
                                ms.translate(-px, -py, -pz);
                            }
                            return true;
                        }

                        @Override
                        public boolean drawCubes(String bone) {
                            return LIGHT_BONES.contains(bone) == lightsPass;
                        }
                    });
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(ReinforcedDoorBlockEntity be) {
        return true;   // a porta ocupa 3x3 blocos e a folha aberta passa da espessura do bloco
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }
}
