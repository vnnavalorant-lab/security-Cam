package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    // na visão térmica todas as entidades vão para o framebuffer de contorno (como se estivessem brilhando)
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void vigicam$outlineAll(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (CameraViewClient.isThermal()) cir.setReturnValue(true);
    }
}
