package com.example.vigicam.mixin;

import com.example.vigicam.HeldItemRenderers;
import com.example.vigicam.RemoteItem;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemFeatureRenderer.class)
public abstract class HeldItemFeatureRendererMixin {
    @Shadow @Final private HeldItemRenderer heldItemRenderer;

    // 3ª pessoa (e outros jogadores): o controle fica ENTRE as duas mãos, seguro pelas pontas, e acompanha as mãos
    @Inject(method = "renderItem", at = @At("HEAD"), cancellable = true)
    private void vigicam$remote(LivingEntity entity, ItemStack stack, ModelTransformationMode mode, Arm arm,
                                MatrixStack matrices, VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        if (!(stack.getItem() instanceof RemoteItem)) return;
        Object model = ((FeatureRenderer<?, ?>) (Object) this).getContextModel();
        if (!(model instanceof BipedEntityModel<?> biped)) return;   // outros modelos: comportamento normal

        Vector3f r = handPoint(biped.rightArm), l = handPoint(biped.leftArm);
        float cx = (r.x + l.x) / 2f, cy = (r.y + l.y) / 2f, cz = (r.z + l.z) / 2f;

        matrices.push();
        matrices.translate(cx / 16f, cy / 16f, cz / 16f);
        matrices.scale(-1f, -1f, 1f);   // do quadro do modelo (Y para baixo) para o quadro "em pé" do item
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-30f));   // tela virada para o rosto, topo para longe
        HeldItemRenderers.remoteHeld(matrices, vcp, light, OverlayTexture.DEFAULT_UV, HeldItemRenderers.REMOTE_PX_THIRD_PERSON);
        matrices.pop();
        ci.cancel();
    }

    /** Onde está a "mão" (ponta do braço) agora, no quadro do modelo, em pixels. */
    private static Vector3f handPoint(ModelPart arm) {
        Vector3f v = new Vector3f(0f, 10f, 0f);
        new Matrix3f().rotationZYX(arm.roll, arm.yaw, arm.pitch).transform(v);
        return v.add(arm.pivotX, arm.pivotY, arm.pivotZ);
    }
}
