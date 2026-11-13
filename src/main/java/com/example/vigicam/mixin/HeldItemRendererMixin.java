package com.example.vigicam.mixin;

import com.example.vigicam.RemoteItem;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {
    // controle na mão de fora: o item já desenha os DOIS braços, então não desenha o braço vazio da mão principal por cima
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void vigicam$noDuplicateArm(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                        float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                        VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        if (hand == Hand.MAIN_HAND && item.isEmpty() && player.getOffHandStack().getItem() instanceof RemoteItem) {
            ci.cancel();
        }
    }
}
