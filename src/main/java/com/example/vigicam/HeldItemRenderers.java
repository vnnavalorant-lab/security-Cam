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

    private static final Identifier REMOTE_TEX = new Identifier(VigiCam.MOD_ID, "textures/entity/remote_control.png");
    private static GeoModel remoteModel;
    /** Centro do corpo do controle no modelo (px): x=0, y entre base (0) e topo do corpo (10), z do meio da espessura. */
    private static final float REMOTE_CY = 5f, REMOTE_CZ = 0.2f;
    /** Tamanho do controle nas duas mãos, em blocos por pixel do modelo (13 px de largura): 1ª pessoa ~0,66 bloco; 3ª pessoa usa a escala 0.6 do display. */
    public static final float REMOTE_PX_FIRST_PERSON = 0.66f / 13f, REMOTE_PX_THIRD_PERSON = 0.6f / 16f;

    // ------------------------------------------------------------------ drone (modelo 3D, rotores girando)

    /**
     * Mesmo quadro do GeckoLib (o plugin do Blockbench em que o "display" foi ajustado): depois do display do JSON
     * (aplicado pelo vanilla) o GeoItemRenderer faz translate(0.5, 0.51, 0.5), ou seja, a origem do modelo (y=0, centro x/z)
     * fica no centro do cubo, com Y para cima. Este modelo é um ModelPart Java (Y para baixo, chão em y=24 px); o ModelPart
     * já divide por 16 internamente (a matriz fica em blocos, sem escala 1/16), e o EntityRenderer o vira com
     * scale(-1,-1,1). Aqui: scale(-1,-1,1) e sobe o chão (24 px = 1,5 bloco) para a origem.
     * Nenhum ajuste de posição/escala além disso: quem manda é o "display".
     */
    public static void drone(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (droneModel == null) droneModel = new DroneModel(mc.getEntityModelLoader().getModelPart(DroneModel.LAYER));
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(-1f, -1f, 1f);
        m.translate(0.0, -24.0 / 16.0, 0.0);
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

    // ------------------------------------------------------------------ antena amplificadora (modelo 3D)

    /** Mesmo quadro da câmera: posição/escala/rotação vêm só do "display" do JSON; LEDs pulsando na camada brilhante. */
    public static void antenna(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        float pulse = 0.65f + 0.35f * (float) Math.sin(Util.getMeasuringTimeMs() / 350.0);
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(1f / 16f, 1f / 16f, 1f / 16f);
        GeoModel model = AntennaBlockEntityRenderer.model();
        model.render(m, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(AntennaBlockEntityRenderer.TEXTURE)), light, overlay, 0f);
        model.renderHull(m, vcp.getBuffer(RenderLayer.getEntityCutout(AntennaBlockEntityRenderer.TEXTURE)), light, overlay, 0f,
                255, 255, 255, 255);
        model.render(m, vcp.getBuffer(RenderLayer.getEyes(AntennaBlockEntityRenderer.GLOW)), 0xF000F0, overlay, 0f,
                255, 255, 255, (int) (255 * pulse));
        model.renderHull(m, vcp.getBuffer(RenderLayer.getEyes(AntennaBlockEntityRenderer.GLOW)), 0xF000F0, overlay, 0f,
                255, 255, 255, (int) (255 * pulse));
        m.pop();
    }

    // ------------------------------------------------------------------ porta reforçada (modelo 3D)

    /** Mesmo quadro da câmera/antena: posição/escala/rotação vêm só do "display" do JSON. Mostra a porta travada e fechada. */
    public static void reinforcedDoor(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(1f / 16f, 1f / 16f, 1f / 16f);
        ReinforcedDoorRenderer.draw(m, vcp, light, overlay, false, 0f);
        m.pop();
    }

    // ------------------------------------------------------------------ controle remoto (modelo 3D)

    private static void renderRemote(MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        if (remoteModel == null) remoteModel = new GeoModel("/assets/vigicam/geo/remote_control.geo.json");
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(REMOTE_TEX));
        remoteModel.render(m, vc, light, overlay, 0f);
    }

    /**
     * Mesmo quadro do GeckoLib (veja o drone) para os contextos sem os braços: GUI, chão, moldura, cabeça, prateleira
     * e quem segura sem ser o jogador. Posição/escala/rotação vêm só do "display" do JSON.
     */
    public static void remote(ModelTransformationMode mode, MatrixStack m, VertexConsumerProvider vcp, int light, int overlay) {
        m.push();
        m.translate(0.5, 0.51, 0.5);
        m.scale(1f / 16f, 1f / 16f, 1f / 16f);
        renderRemote(m, vcp, light, overlay);
        m.pop();
    }

    /**
     * Controle nas duas mãos (o encaixe dos braços não passa pelo display): centro do corpo na origem, em "quadro de item em pé"
     * (+X direita do jogador, +Y cima, +Z para o rosto do jogador). A tela fica no lado -Z do modelo, então ele é girado 180° em Y.
     * @param pxScale blocos por pixel do modelo
     */
    public static void remoteHeld(MatrixStack m, VertexConsumerProvider vcp, int light, int overlay, float pxScale) {
        m.push();
        m.scale(pxScale, pxScale, pxScale);
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f));
        m.translate(0.0, -REMOTE_CY, -REMOTE_CZ);
        renderRemote(m, vcp, light, overlay);
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

        remoteHeld(m, vcp, light, overlay, REMOTE_PX_FIRST_PERSON);

        if (mc.player != null && !mc.player.isInvisible()
                && mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerEntityRenderer pr) {
            PlayerEntityModel<AbstractClientPlayerEntity> pm = pr.getModel();
            Identifier skin = mc.player.getSkinTexture();
            long now = Util.getMeasuringTimeMs();
            pollClicks(mc, now);
            arm(m, vcp, light, skin, pm.rightArm, pm.rightSleeve, mc.player.isPartVisible(PlayerModelPart.RIGHT_SLEEVE), 1f, now - pressStartR);
            arm(m, vcp, light, skin, pm.leftArm, pm.leftSleeve, mc.player.isPartVisible(PlayerModelPart.LEFT_SLEEVE), -1f, now - pressStartL);
        }
        m.pop();
    }

    // ------------------------------------------------------------------ clique = só a mão apertando a tela

    /** Duração do toque, em ms. */
    private static final long PRESS_MS = 300L;
    /** Mão (ponta do braço) em repouso, no lado do controle; "side" = +1 direita, -1 esquerda. */
    private static final float REST_X = 0.255f, REST_Y = -0.03f, REST_Z = 0.02f;
    /** Mão pairando sobre o centro da tela (X é espelhado por lado). Ajuste aqui se a mão ficar longe/dentro da tela. */
    private static final float HOVER_X = 0.09f, HOVER_Y = -0.02f, HOVER_Z = 0.16f;
    /** Quanto a mão avança (para -Z, em direção à tela) no instante do toque. */
    private static final float TAP_DEPTH = 0.045f;

    private static long pressStartR = -10_000L, pressStartL = -10_000L;
    private static boolean prevUse, prevAttack;

    /** Botão direito (usar) -> mão direita aperta; botão esquerdo (atacar) -> mão esquerda aperta. */
    private static void pollClicks(MinecraftClient mc, long now) {
        boolean use = mc.options.useKey.isPressed(), atk = mc.options.attackKey.isPressed();
        if (mc.currentScreen == null && mc.mouse.isCursorLocked()) {
            if (use && !prevUse) pressStartR = now;
            if (atk && !prevAttack) pressStartL = now;
        }
        prevUse = use;
        prevAttack = atk;
    }

    private static float smooth(float x) { x = Math.max(0f, Math.min(1f, x)); return x * x * (3f - 2f * x); }

    /** 0..1: o quanto a mão já foi do lado do controle até a tela (vai, toca, volta). */
    private static float reach(long elapsed) {
        if (elapsed < 0L || elapsed >= PRESS_MS) return 0f;
        float t = elapsed / (float) PRESS_MS;
        if (t < 0.40f) return smooth(t / 0.40f);
        if (t < 0.60f) return 1f;
        return 1f - smooth((t - 0.60f) / 0.40f);
    }

    /** 0..1: o "toque" em si (um empurrãozinho para a tela) no meio da animação. */
    private static float tap(long elapsed) {
        if (elapsed < 0L || elapsed >= PRESS_MS) return 0f;
        float t = elapsed / (float) PRESS_MS;
        if (t < 0.40f || t >= 0.60f) return 0f;
        return (float) Math.sin((t - 0.40f) / 0.20f * Math.PI);
    }

    /**
     * Braço vindo de baixo/trás da tela, com a mão encostada na ponta (lateral) do controle.
     * O OMBRO fica sempre parado (calculado a partir da posição de repouso); no clique só a mão se move e o braço gira em torno do ombro.
     */
    private static void arm(MatrixStack m, VertexConsumerProvider vcp, int light, Identifier skin,
                            ModelPart arm, ModelPart sleeve, boolean sleeveOn, float side, long sincePress) {
        Vector3f rest = new Vector3f(side * REST_X, REST_Y, REST_Z);              // ponta do braço (a mão) em repouso
        Vector3f back = new Vector3f(side * 0.30f, -0.62f, 0.74f).normalize();    // da mão para o ombro
        Vector3f shoulder = new Vector3f(back).mul(0.62f).add(rest);              // fixo: não depende do clique

        float r = reach(sincePress), tp = tap(sincePress);
        Vector3f hand = new Vector3f(
                rest.x + (side * HOVER_X - rest.x) * r,
                rest.y + (HOVER_Y - rest.y) * r,
                rest.z + (HOVER_Z - rest.z) * r - TAP_DEPTH * tp);
        // mantém o comprimento do braço: só muda a direção ombro -> mão
        Vector3f dir = new Vector3f(hand).sub(shoulder).normalize();
        back.set(dir).negate();

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
