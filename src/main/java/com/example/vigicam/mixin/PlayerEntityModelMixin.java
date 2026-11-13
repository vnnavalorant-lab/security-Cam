package com.example.vigicam.mixin;

import com.example.vigicam.PilotState;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {
    // com o controle remoto: duas mãos nas pontas, como um controle de videogame; ao usar câmera/drone as mãos escorregam
    @Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void vigicam$gamepad(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress,
                                 float headYaw, float headPitch, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity p) || !PilotState.holdsRemote(p)) return;
        PlayerEntityModel<?> m = (PlayerEntityModel<?>) (Object) this;

        float t = animationProgress;
        float a = PilotState.amplitude(p);
        float hp = m.head.pitch;

        // base: antebraços à frente do peito, mãos juntando-se nas pontas do controle
        float rp = -1.05f + hp * 0.5f, lp = rp;
        float ry = -0.17f, ly = 0.17f;

        if (a > 0f) {   // "dedos" nos botões/manches: as mãos deslizam um pouco para os lados e para cima/baixo
            rp += (float) Math.sin(t * 0.43f) * 0.06f * a;
            ry += (float) Math.cos(t * 0.31f) * 0.07f * a;
            lp += (float) Math.sin(t * 0.43f + 2.1f) * 0.06f * a;
            ly -= (float) Math.cos(t * 0.37f + 1.0f) * 0.07f * a;
        }

        m.rightArm.pitch = rp; m.rightArm.yaw = ry; m.rightArm.roll = 0f;
        m.leftArm.pitch = lp;  m.leftArm.yaw = ly;  m.leftArm.roll = 0f;
        m.rightSleeve.copyTransform(m.rightArm);
        m.leftSleeve.copyTransform(m.leftArm);
    }
}
