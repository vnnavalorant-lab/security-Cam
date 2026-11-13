package com.example.vigicam;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.World;

/** Desenha o modelo da câmera; o pivô "root" varre de um lado para o outro (ou segue a visão de quem está usando). */
public class CameraBlockEntityRenderer implements BlockEntityRenderer<SurveillanceCameraBlockEntity> {
    public CameraBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    @Override
    public void render(SurveillanceCameraBlockEntity be, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light, int overlay) {
        World world = be.getWorld();
        BlockState state = be.getCachedState();
        if (world == null || !(state.getBlock() instanceof SurveillanceCameraBlock)) return;

        float facing = state.get(SurveillanceCameraBlock.FACING).asRotation();

        // giro do corpo em relação ao eixo da câmera (graus de yaw do Minecraft)
        float delta;
        if (CameraViewClient.isActive() && CameraViewClient.getKind() == CameraViewClient.Kind.BLOCK
                && CameraViewClient.getBlockPos().equals(be.getPos())) {
            delta = MathHelper.wrapDegrees(CameraViewClient.getYaw(tickDelta) - facing);   // igual à visão
        } else {
            delta = CameraViewClient.SWEEP_AMPL
                    * (float) Math.sin((world.getTime() + tickDelta) * CameraViewClient.SWEEP_STEP);
        }

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing));
        matrices.scale(1f / 16f, 1f / 16f, 1f / 16f);

        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(SecurityCameraModel.TEXTURE));
        SecurityCameraModel.get().render(matrices, vc, light, OverlayTexture.DEFAULT_UV, -delta);
        matrices.pop();
    }
}
