package com.example.vigicam.mixin;

import com.example.vigicam.HandModels;
import com.example.vigicam.VigiCam;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Unique private static boolean vigicam$swapping;

    // na mão, os itens do mod usam o modelo 3D (builtin); em GUI/chão/moldura continua o sprite do ícone
    @Inject(method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformationMode;ZLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;IILnet/minecraft/client/render/model/BakedModel;)V",
            at = @At("HEAD"), cancellable = true)
    private void vigicam$handModel(ItemStack stack, ModelTransformationMode mode, boolean leftHanded, MatrixStack matrices,
                                   VertexConsumerProvider vcp, int light, int overlay, BakedModel model, CallbackInfo ci) {
        if (vigicam$swapping || !HandModels.isHand(mode) || !HandModels.isOurs(stack)) return;
        // o controle remoto em 3ª pessoa é posicionado entre as duas mãos (HeldItemFeatureRendererMixin)
        if (stack.getItem() == VigiCam.REMOTE_ITEM && !HandModels.isFirstPerson(mode)) return;
        BakedModel hand = HandModels.forHand(model, stack);
        if (hand == null) return;
        vigicam$swapping = true;
        try {
            ((ItemRenderer) (Object) this).renderItem(stack, mode, leftHanded, matrices, vcp, light, overlay, hand);
        } finally {
            vigicam$swapping = false;
        }
        ci.cancel();
    }
}
