package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/** Desenha o modelo da antena (77 cubos) e uma 2ª camada brilhante só com os LEDs vermelhos, que pulsam. */
public class AntennaBlockEntityRenderer implements BlockEntityRenderer<AmplifyingAntennaBlockEntity> {
    public static final Identifier TEXTURE = new Identifier(VigiCam.MOD_ID, "textures/entity/amplifying_antenna.png");
    public static final Identifier GLOW = new Identifier(VigiCam.MOD_ID, "textures/entity/amplifying_antenna_glow.png");
    private static final String GEO = "/assets/vigicam/geo/amplifying_antenna.geo.json";
    private static final int FULL_BRIGHT = 0xF000F0;

    private static GeoModel model;

    public static GeoModel model() {
        if (model == null) model = new GeoModel(GEO);
        return model;
    }

    public AntennaBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(AmplifyingAntennaBlockEntity be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        BlockState state = be.getCachedState();
        World world = be.getWorld();
        if (world == null || !(state.getBlock() instanceof AmplifyingAntennaBlock)) return;

        float facing = state.get(AmplifyingAntennaBlock.FACING).asRotation();
        boolean active = state.get(AmplifyingAntennaBlock.ACTIVE);
        float time = world.getTime() + tickDelta;

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing));
        matrices.scale(1f / 16f, 1f / 16f, 1f / 16f);

        model().render(matrices, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)), light, OverlayTexture.DEFAULT_UV, 0f);

        // LEDs: pulsam devagar; com o drone ligado piscam rápido (transmitindo)
        float pulse = active ? 0.7f + 0.3f * MathHelper.sin(time * 0.9f) : 0.55f + 0.35f * MathHelper.sin(time * 0.2f);
        model().render(matrices, vcp.getBuffer(RenderLayer.getEyes(GLOW)), FULL_BRIGHT, OverlayTexture.DEFAULT_UV, 0f,
                255, 255, 255, (int) (255 * MathHelper.clamp(pulse, 0f, 1f)));
        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(AmplifyingAntennaBlockEntity be) {
        return true;   // o mastro tem ~4 blocos de altura
    }

    @Override
    public int getRenderDistance() {
        return 96;
    }
}
