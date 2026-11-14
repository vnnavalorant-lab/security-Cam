package com.example.vigicam;

import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.math.Direction;
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

    /** Grupo do modelo cujos cubos "block" pegam a textura do bloco vizinho. */
    private static final String FRAME_BONE = "blocks";

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
        // quadro ("blocks"): textura do bloco vizinho; sem vizinho, o quadro fica invisível
        Direction dir = state.get(ReinforcedDoorBlock.FACING);
        Sprite frame = neighborSprite(world, be.getPos(), dir);
        // luz de cada célula do quadro = luz do bloco do mundo naquele lugar (cai junto com os vizinhos, sem faixas escuras)
        java.util.function.IntBinaryOperator cellLight = (col, row) ->
                WorldRenderer.getLightmapCoordinates(world, ReinforcedDoorBlock.partPos(be.getPos(), dir, col, row));
        draw(matrices, vcp, lit, OverlayTexture.DEFAULT_UV, state.get(ReinforcedDoorBlock.UNLOCKED), be.progress(tickDelta), frame, frame != null, cellLight);
        matrices.pop();
    }

    /** Textura do primeiro bloco vizinho à estrutura (esquerda, direita, em cima, embaixo); null se não houver. */
    private static Sprite neighborSprite(World world, BlockPos master, Direction facing) {
        BlockPos[] candidates = new BlockPos[3 * 2 + 3 * 2];
        int n = 0;
        for (int r = 0; r < ReinforcedDoorBlock.HEIGHT; r++) {
            candidates[n++] = ReinforcedDoorBlock.partPos(master, facing, -1, r);
            candidates[n++] = ReinforcedDoorBlock.partPos(master, facing, ReinforcedDoorBlock.WIDTH, r);
        }
        for (int c = 0; c < ReinforcedDoorBlock.WIDTH; c++) {
            candidates[n++] = ReinforcedDoorBlock.partPos(master, facing, c, ReinforcedDoorBlock.HEIGHT);
            candidates[n++] = ReinforcedDoorBlock.partPos(master, facing, c, -1);
        }
        for (BlockPos p : candidates) {
            BlockState s = world.getBlockState(p);
            if (s.isAir() || s.getBlock() instanceof ReinforcedDoorBlock || s.getRenderType() != BlockRenderType.MODEL) continue;
            return MinecraftClient.getInstance().getBlockRenderManager().getModel(s).getParticleSprite();
        }
        return null;
    }

    /**
     * @param m        origem no centro-baixo da porta, 1 px = 1/16 de bloco
     * @param open     0 = fechada ... 1 = totalmente aberta
     * @param frame    textura (do atlas de blocos) para os cubos "block" do grupo "blocks"; null = textura própria do modelo
     * @param showFrame false esconde o grupo "blocks" (sem bloco vizinho)
     */
    public static void draw(MatrixStack m, VertexConsumerProvider vcp, int light, int overlay, boolean unlocked, float open,
                            Sprite frame, boolean showFrame, java.util.function.IntBinaryOperator cellLight) {
        float eased = open * open * (3f - 2f * open);   // suaviza o começo e o fim do giro
        Map<String, float[]> scales = DoorAnimations.scales(unlocked ? "on" : "off");

        // 0: corpo (luz do mundo); 1: luzes indicadoras (brilho máximo); 2: quadro "blocks" com a textura do vizinho
        for (int pass = 0; pass < 3; pass++) {
            if (pass == 2 && (frame == null || !showFrame)) continue;
            final int thisPass = pass;
            model().render(m, vcp.getBuffer(pass == 2 ? RenderLayer.getEntityCutout(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE) : RenderLayer.getEntityCutoutNoCull(TEXTURE)),
                    pass == 1 ? FULL_BRIGHT : light, overlay, new GeoModel.BoneHandler() {
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
                            boolean isFrame = FRAME_BONE.equals(bone);
                            return switch (thisPass) {
                                case 1 -> LIGHT_BONES.contains(bone);
                                case 2 -> isFrame;
                                // corpo: o quadro só entra aqui com a textura própria do modelo (item na mão)
                                default -> !LIGHT_BONES.contains(bone) && (!isFrame || (frame == null && showFrame));
                            };
                        }
                    }, pass == 2 ? frame : null, pass == 2 ? cellLight : null);
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
