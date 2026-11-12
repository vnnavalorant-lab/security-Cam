package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // esconde a mão do player enquanto está vendo pela câmera
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void vigicam$hideHand(MatrixStack matrices, Camera camera, float tickDelta, CallbackInfo ci) {
        if (CameraViewClient.isActive()) ci.cancel();
    }
}
