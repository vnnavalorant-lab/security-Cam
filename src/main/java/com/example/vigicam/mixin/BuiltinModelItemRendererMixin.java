package com.example.vigicam.mixin;

import com.example.vigicam.HandModels;
import com.example.vigicam.HeldItemRenderers;
import com.example.vigicam.VigiCam;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.BuiltinModelItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BuiltinModelItemRenderer.class)
public class BuiltinModelItemRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vigicam$render(ItemStack stack, ModelTransformationMode mode, MatrixStack matrices,
                                VertexConsumerProvider vcp, int light, int overlay, CallbackInfo ci) {
        if (!HandModels.isHand(mode)) return;
        if (stack.getItem() == VigiCam.DRONE_ITEM) {
            HeldItemRenderers.drone(mode, matrices, vcp, light, overlay);
            ci.cancel();
        } else if (stack.getItem() == VigiCam.SURVEILLANCE_CAMERA_ITEM) {
            HeldItemRenderers.camera(mode, matrices, vcp, light, overlay);
            ci.cancel();
        } else if (stack.getItem() == VigiCam.REMOTE_ITEM && HandModels.isFirstPerson(mode)) {
            HeldItemRenderers.remoteFirstPerson(mode, stack, matrices, vcp, light, overlay);
            ci.cancel();
        }
    }
}
