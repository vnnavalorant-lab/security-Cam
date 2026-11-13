package com.example.vigicam;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

public class DroneEntityRenderer extends EntityRenderer<DroneEntity> {
    private static final Identifier TEXTURE = new Identifier(VigiCam.MOD_ID, "textures/entity/drone.png");
    /** O modelo tem 2,75 blocos de envergadura; reduzimos para ficar proporcional. */
    private static final float SCALE = 0.6f;

    private final DroneModel model;

    public DroneEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.model = new DroneModel(ctx.getPart(DroneModel.LAYER));
        this.shadowRadius = 0.35f;
    }

    @Override
    public Identifier getTexture(DroneEntity entity) { return TEXTURE; }

    @Override
    public void render(DroneEntity drone, float entityYaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vcp, int light) {
        // não desenha o drone que eu mesmo estou pilotando (a câmera fica no "nariz" dele)
        if (CameraViewClient.isDrone(drone.getId())) return;

        float yaw = drone.prevYaw + MathHelper.wrapDegrees(drone.getYaw() - drone.prevYaw) * tickDelta;

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - yaw));
        // inclina o drone na direção do movimento
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-drone.leanForward));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-drone.leanSide));
        matrices.scale(-1f, -1f, 1f);
        matrices.translate(0f, -1.5f * SCALE, 0f);
        matrices.scale(SCALE, SCALE, SCALE);

        float spin = drone.isPowered() ? (drone.age + tickDelta) * 72f : 0f;   // 4 voltas/s
        model.setRotorAngle(-spin);

        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE));
        model.render(matrices, vc, light);
        matrices.pop();

        super.render(drone, entityYaw, tickDelta, matrices, vcp, light);
    }
}
