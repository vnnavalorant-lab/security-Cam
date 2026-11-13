package com.example.vigicam;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.client.render.entity.PlayerModelPart;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Desenho dos itens do mod na mão (primeira/terceira pessoa). Convenção do quadro de coordenadas aqui:
 * origem no centro do item, +X = direita do jogador, +Y = cima, +Z = para o rosto do jogador, frente do item = -Z.
 */
public final class HeldItemRenderers {
    private HeldItemRenderers() {}

    private static final Identifier DRONE_TEX = new Identifier(VigiCam.MOD_ID, "textures/entity/drone.png");
    private static DroneModel droneModel;

    // ------------------------------------------------------------------ drone (modelo 3D, rotores girando)

    /**
     * Mesmo quadro do GeckoLib (o plugin do Blockbench em que o "display" foi ajustado): depois do display do JSON
     * (aplicado pelo vanilla) o GeoItemRenderer faz translate(0.5, 0.51, 0.5), ou seja, a origem do modelo (y=0, centro x/z)
     * fica no centro do cubo, com Y para cima. Este modelo é de entidade Java (Y para baixo, chão em y=24 px) e o
     * EntityRenderer o vira com scale(-1,-1,1), então: escala (-1/16,-1/16,1/16) e sobe o chão (y=24) para a origem.
     * Nenhum ajuste de posição/escala além disso: quem manda é o "display".
     */
    public static void drone(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (droneModel == null) droneModel = new DroneModel(mc.getEntityModelLoader().getModelPart(DroneModel.LAYER));
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(-1f / 16f, -1f / 16f, 1f / 16f);
        m.translate(0, -24, 0);
        droneModel.setRotorAngle(-((Util.getMeasuringTimeMs() % 250L) / 250f) * 360f);   // 4 voltas/s
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(DRONE_TEX));
        droneModel.render(m, vc, light);
        m.pop();
    }

    // ------------------------------------------------------------------ câmera de segurança (modelo 3D)

    /**
     * Mesmo quadro do GeckoLib (veja o drone): origem do modelo (centro-baixo, y=0) no centro do cubo de display,
     * 1 px = 1/16 de bloco, lente para -Z. Posição/escala/rotação vêm só do "display" do JSON.
     */
    public static void camera(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        float sweep = 22f * (float) Math.sin(Util.getMeasuringTimeMs() / 700.0);   // corpo da câmera "varrendo"
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(1f / 16f, 1f / 16f, 1f / 16f);
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(SecurityCameraModel.TEXTURE));
        SecurityCameraModel.get().render(m, vc, light, overlay, sweep);
        m.pop();
    }

    // ------------------------------------------------------------------ controle remoto (1ª pessoa: com as duas mãos)

    /** Controle seguro pelas pontas, com as duas mãos do jogador (primeira pessoa). */
    public static void remoteFirstPerson(ModelTransformationMode mode, ItemStack stack, MatrixStack m,
                                         VertexConsumerProvider vcp, int light, int overlay) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean right = mode == ModelTransformationMode.FIRST_PERSON_RIGHT_HAND;
        m.push();
        m.translate(0.5, 0.5, 0.5);
        // o vanilla deixa o item no canto inferior da tela; aqui ele vai para o centro/baixo
        m.translate(right ? -0.56 : 0.56, 0.12, 0.10);
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-28f));

        m.push();
        m.scale(0.66f, 0.66f, 0.66f);
        mc.getItemRenderer().renderItem(stack, ModelTransformationMode.GUI, light, overlay, m, vcp, mc.world, 0);
        m.pop();

        if (mc.player != null && !mc.player.isInvisible()
                && mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerEntityRenderer pr) {
            PlayerEntityModel<AbstractClientPlayerEntity> pm = pr.getModel();
            Identifier skin = mc.player.getSkinTexture();
            arm(m, vcp, light, skin, pm.rightArm, pm.rightSleeve, mc.player.isPartVisible(PlayerModelPart.RIGHT_SLEEVE), 1f);
            arm(m, vcp, light, skin, pm.leftArm, pm.leftSleeve, mc.player.isPartVisible(PlayerModelPart.LEFT_SLEEVE), -1f);
        }
        m.pop();
    }

    /** Braço vindo de baixo/trás da tela, com a mão encostada na ponta (lateral) do controle. */
    private static void arm(MatrixStack m, VertexConsumerProvider vcp, int light, Identifier skin,
                            ModelPart arm, ModelPart sleeve, boolean sleeveOn, float side) {
        Vector3f hand = new Vector3f(side * 0.255f, -0.03f, 0.02f);              // ponta do braço (a mão)
        Vector3f back = new Vector3f(side * 0.30f, -0.62f, 0.74f).normalize();    // da mão para o ombro
        Vector3f shoulder = new Vector3f(back).mul(0.62f).add(hand);

        arm.pitch = arm.yaw = arm.roll = 0f;
        sleeve.pitch = sleeve.yaw = sleeve.roll = 0f;
        boolean av = arm.visible, sv = sleeve.visible;
        arm.visible = true;
        sleeve.visible = sleeveOn;

        m.push();
        m.translate(shoulder.x, shoulder.y, shoulder.z);
        // o braço do modelo se estende para +Y a partir do ombro; vira para apontar do ombro até a mão
        m.multiply(new Quaternionf().rotationTo(0f, 1f, 0f, -back.x, -back.y, -back.z));
        m.translate(-arm.pivotX / 16f, -arm.pivotY / 16f, -arm.pivotZ / 16f);   // cancela o pivô do ModelPart
        arm.render(m, vcp.getBuffer(RenderLayer.getEntitySolid(skin)), light, OverlayTexture.DEFAULT_UV);
        sleeve.render(m, vcp.getBuffer(RenderLayer.getEntityTranslucent(skin)), light, OverlayTexture.DEFAULT_UV);
        m.pop();

        arm.visible = av;
        sleeve.visible = sv;
    }
}
