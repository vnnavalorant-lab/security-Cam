package com.example.vigicam.mixin;

import com.example.vigicam.VigiSounds;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {
    // cada clique (qualquer botão) com o controle na mão produz o som do botão, uma vez
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void vigicam$pressSound(long window, int button, int action, int mods, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS) return;
        VigiSounds.remoteClick(MinecraftClient.getInstance());
    }
}
