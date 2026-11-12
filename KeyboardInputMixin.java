package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {
    // congela o movimento do player enquanto está na câmera
    @Inject(method = "tick", at = @At("TAIL"))
    private void vigicam$freeze(boolean slowDown, float f, CallbackInfo ci) {
        if (CameraViewClient.isActive()) {
            this.movementForward = 0f;
            this.movementSideways = 0f;
            this.jumping = false;
            this.sneaking = false;
        }
    }
}
