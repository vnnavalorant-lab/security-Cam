package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PostEffectProcessor.class)
public abstract class PostEffectProcessorMixin {
    @Shadow public abstract Framebuffer getSecondaryTarget(String name);

    private static Framebuffer vigicam$entities() {
        return ((WorldRendererAccessor) MinecraftClient.getInstance().worldRenderer)
                .vigicam$getEntityOutlinesFramebuffer();
    }

    // expõe o framebuffer de silhuetas das entidades como o alvo "vigicam:entities" nos JSONs de post
    @Inject(method = "getTarget", at = @At("HEAD"), cancellable = true)
    private void vigicam$entityTarget(String name, CallbackInfoReturnable<Framebuffer> cir) {
        if ("vigicam:entities".equals(name)) cir.setReturnValue(vigicam$entities());
    }

    // impede o blur/sobel do vanilla de transformar as silhuetas em simples contornos
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vigicam$keepRawSilhouettes(float tickDelta, CallbackInfo ci) {
        if (!CameraViewClient.isThermal()) return;
        Framebuffer outline = vigicam$entities();
        if (outline != null && getSecondaryTarget("final") == outline) ci.cancel();
    }
}
