package com.example.vigicam.mixin;

import com.example.vigicam.RemoteItem;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Unique private boolean vigicam$reentry;

    @Shadow
    protected abstract void renderFirstPersonItem(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                                  float swingProgress, ItemStack item, float equipProgress,
                                                  MatrixStack matrices, VertexConsumerProvider vcp, int light);

    // controle na mão de fora: o item já desenha os DOIS braços, então não desenha o braço vazio da mão principal por cima
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void vigicam$noDuplicateArm(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                                        float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                                        VertexConsumerProvider vcp, int light, CallbackInfo ci) {
        if (hand == Hand.MAIN_HAND && item.isEmpty() && player.getOffHandStack().getItem() instanceof RemoteItem) {
            ci.cancel();
            return;
        }
        // Com o controle na mão, clicar (esquerdo ou direito) NÃO balança o item/braços inteiros: o "swing" do vanilla
        // e o abaixar do item (reset do equip ao atacar/usar) são zerados. O clique vira só a animação da mão
        // apertando a tela (HeldItemRenderers.remoteFirstPerson).
        if (!vigicam$reentry && item.getItem() instanceof RemoteItem && (swingProgress != 0f || equipProgress != 0f)) {
            vigicam$reentry = true;
            try {
                // atenção: no vanilla, equipProgress 0 = item totalmente levantado, 1 = abaixado (fora da tela)
                renderFirstPersonItem(player, tickDelta, pitch, hand, 0f, item, 0f, matrices, vcp, light);
            } finally {
                vigicam$reentry = false;
            }
            ci.cancel();
        }
    }
}
