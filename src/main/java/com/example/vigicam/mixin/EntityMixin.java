package com.example.vigicam.mixin;

import com.example.vigicam.CameraViewClient;
import com.example.vigicam.HeatUtil;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
}
