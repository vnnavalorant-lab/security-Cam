package com.example.vigicam;

import com.example.vigicam.mixin.GameRendererInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class VigiCamClient implements ClientModInitializer {
    private static final Identifier SHADER = new Identifier(VigiCam.MOD_ID, "shaders/post/camera_vhs.json");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(VigiCam.VIEW_CAMERA, (client, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            float yaw = buf.readFloat();
            client.execute(() -> startView(client, pos, yaw));
        });

        ClientTickEvents.END_CLIENT_TICK.register(VigiCamClient::tick);
        HudRenderCallback.EVENT.register(VigiCamClient::renderHud);

        // enquanto está na câmera, não deixa quebrar/usar/atacar nada
        AttackBlockCallback.EVENT.register((p, w, h, pos, dir) ->
                CameraViewClient.isActive() ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((p, w, h, hit) ->
                CameraViewClient.isActive() ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((p, w, h, e, hit) ->
                CameraViewClient.isActive() ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((p, w, h) ->
                CameraViewClient.isActive()
                        ? TypedActionResult.fail(p.getStackInHand(h))
                        : TypedActionResult.pass(p.getStackInHand(h)));
    }

    private static void startView(MinecraftClient mc, BlockPos pos, float yaw) {
        if (CameraViewClient.isActive()) return;
        ((GameRendererInvoker) mc.gameRenderer).invokeLoadPostProcessor(SHADER);
        CameraViewClient.start(pos, yaw);
    }

    private static void stopView(MinecraftClient mc) {
        mc.gameRenderer.disablePostProcessor();
        CameraViewClient.stop();
    }

    private static void tick(MinecraftClient mc) {
        if (!CameraViewClient.isActive()) return;
        boolean sneak = mc.options.sneakKey.isPressed();
        if (mc.player == null || mc.world == null
                || !(mc.world.getBlockState(CameraViewClient.getBlockPos()).getBlock() instanceof SurveillanceCameraBlock)
                || mc.player.hurtTime > 0
                || CameraViewClient.shouldExit(sneak)) {
            stopView(mc);
        }
    }

    private static void renderHud(DrawContext ctx, float tickDelta) {
        if (!CameraViewClient.isActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int green = 0xFF55FF55, len = 20, t = 2, m = 12;

        boolean blink = mc.player != null && (mc.player.age / 10) % 2 == 0;
        ctx.drawText(mc.textRenderer, (blink ? "● " : "  ") + "LIVE", m + 6, m + 6, 0xFFFF3030, true);

        String time = LocalTime.now().format(CLOCK);
        ctx.drawText(mc.textRenderer, time, w - m - 6 - mc.textRenderer.getWidth(time), m + 6, green, true);
        ctx.drawCenteredTextWithShadow(mc.textRenderer, "Shift para sair", w / 2, h - 34, green);

        // cantos do enquadramento
        ctx.fill(m, m, m + len, m + t, green);                 ctx.fill(m, m, m + t, m + len, green);
        ctx.fill(w - m - len, m, w - m, m + t, green);         ctx.fill(w - m - t, m, w - m, m + len, green);
        ctx.fill(m, h - m - t, m + len, h - m, green);         ctx.fill(m, h - m - len, m + t, h - m, green);
        ctx.fill(w - m - len, h - m - t, w - m, h - m, green); ctx.fill(w - m - t, h - m - len, w - m, h - m, green);
    }
}
