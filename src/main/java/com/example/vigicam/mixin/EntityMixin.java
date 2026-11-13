package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import com.example.vigicam.HeatUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    // a "cor de time" é a cor com que a silhueta é desenhada no framebuffer de contorno:
    // aqui ela carrega o calor da entidade (R=G=B=calor) para o shader ler.
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void vigicam$heatColor(CallbackInfoReturnable<Integer> cir) {
        if (CameraViewClient.isThermal()) {
            int h = HeatUtil.heatByte((Entity) (Object) this);
            cir.setReturnValue((h << 16) | (h << 8) | h);
        }
    }

    // enquanto usa câmera/drone o jogador fica PARADO (corpo e cabeça não giram): o mouse só mexe na visão
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void vigicam$freezeLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if ((Object) this instanceof ClientPlayerEntity && CameraViewClient.isActive()) {
            CameraViewClient.lookDelta(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }
}
