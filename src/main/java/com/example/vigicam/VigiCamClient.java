package com.example.vigicam;

import com.example.vigicam.mixin.GameRendererInvoker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VigiCamClient implements ClientModInitializer {
    private static final Identifier SHADER_CAM = new Identifier(VigiCam.MOD_ID, "shaders/post/camera_view.json");
    private static final Identifier SHADER_NIGHT = new Identifier(VigiCam.MOD_ID, "shaders/post/camera_vhs.json");
    private static final Identifier SHADER_THERMAL = new Identifier(VigiCam.MOD_ID, "shaders/post/camera_thermal.json");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a");

    private static final double SCAN_RANGE = 48.0;
    private static final int MAX_TARGETS = 16;

    private static KeyBinding toggleKey, manualKey, nextKey;

    // resultado do scan térmico (atualizado no tick)
    private static final List<Entity> targets = new ArrayList<>();
    private static float maxC = 0f, minC = 0f;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vigicam.toggle_vision", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.vigicam"));
        manualKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vigicam.toggle_manual", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.vigicam"));
        nextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vigicam.next_device", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.vigicam"));

        HandModels.register();
        ClientPlayNetworking.registerGlobalReceiver(VigiCam.PILOT_SYNC, (client, handler, buf, sender) -> {
            java.util.UUID who = buf.readUuid();
            boolean on = buf.readBoolean();
            client.execute(() -> PilotState.set(who, on));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PilotState.clear());

        EntityModelLayerRegistry.registerModelLayer(DroneModel.LAYER, DroneModel::getTexturedModelData);
        EntityRendererRegistry.register(VigiCam.DRONE, DroneEntityRenderer::new);
        BlockEntityRendererFactories.register(VigiCam.CAMERA_BE, CameraBlockEntityRenderer::new);
        BlockEntityRendererFactories.register(VigiCam.ANTENNA_BE, AntennaBlockEntityRenderer::new);

        // partículas de sinal da antena (desenho próprio, brilho aditivo)
        net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry pfr =
                net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry.getInstance();
        pfr.register(VigiCam.SIGNAL_WAVE, (type, world, x, y, z, vx, vy, vz) -> SignalParticle.wave(world, x, y, z, vx, vy));
        pfr.register(VigiCam.SIGNAL_GLOW, (type, world, x, y, z, vx, vy, vz) -> SignalParticle.glow(world, x, y, z));
        pfr.register(VigiCam.SIGNAL_SPARK, (type, world, x, y, z, vx, vy, vz) -> SignalParticle.spark(world, x, y, z, vx, vy, vz));

        // o servidor avisou que o drone perdeu o sinal: sai da visão dele
        ClientPlayNetworking.registerGlobalReceiver(VigiCam.SIGNAL_LOST, (client, handler, buf, sender) ->
                client.execute(() -> {
                    if (CameraViewClient.isActive() && CameraViewClient.isDrone()) stopView(client);
                }));

        ClientPlayNetworking.registerGlobalReceiver(VigiCam.VIEW_CAMERA, (client, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            float yaw = buf.readFloat();
            int idx = buf.readInt(), cnt = buf.readInt();
            client.execute(() -> {
                boolean fresh = CameraViewClient.startBlock(client, pos, yaw, idx, cnt);
                applyShader(client, fresh);
                if (fresh) sendViewState(true);
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(VigiCam.VIEW_DRONE, (client, handler, buf, sender) -> {
            int id = buf.readInt();
            int idx = buf.readInt(), cnt = buf.readInt();
            client.execute(() -> {
                boolean fresh = CameraViewClient.startDrone(client, id, idx, cnt);
                applyShader(client, fresh);
                if (fresh) sendViewState(true);
            });
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

    /** Carrega o shader da visão atual (noturna ou térmica). */
    private static void applyShader(MinecraftClient mc, boolean force) {
        if (!force && !CameraViewClient.isActive()) return;
        Identifier id = switch (CameraViewClient.getMode()) {
            case THERMAL -> SHADER_THERMAL;
            case NIGHT -> SHADER_NIGHT;
            default -> SHADER_CAM;
        };
        ((GameRendererInvoker) mc.gameRenderer).invokeLoadPostProcessor(id);
    }

    /** Avisa o servidor (que repassa aos jogadores próximos) se estou usando câmera/drone: as mãos mexem no controle. */
    private static void sendViewState(boolean on) {
        if (!ClientPlayNetworking.canSend(VigiCam.VIEW_STATE)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(on);
        ClientPlayNetworking.send(VigiCam.VIEW_STATE, buf);
    }

    private static void stopView(MinecraftClient mc) {
        sendViewState(false);
        if (CameraViewClient.isDrone()) ClientPlayNetworking.send(VigiCam.DRONE_RELEASE, PacketByteBufs.empty());
        mc.gameRenderer.disablePostProcessor();
        CameraViewClient.stop(mc);
        targets.clear();
        if (mc.player != null) {
            mc.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal("Transmissão encerrada · Scute60"), false);
            farewellTicks = 20;   // some depois de 1 segundo
        }
    }

    /** Ticks restantes até apagar a mensagem de despedida da action bar (0 = nada a apagar). */
    private static int farewellTicks;

    private static void tick(MinecraftClient mc) {
        if (farewellTicks > 0 && --farewellTicks == 0) {
            mc.inGameHud.setOverlayMessage(net.minecraft.text.Text.empty(), false);
        }
        if (!CameraViewClient.isActive()) {
            while (toggleKey.wasPressed()) { /* descarta */ }
            while (manualKey.wasPressed()) { /* descarta */ }
            while (nextKey.wasPressed()) { /* descarta */ }
            return;
        }
        boolean sneak = mc.options.sneakKey.isPressed();
        boolean gone;
        if (CameraViewClient.isDrone()) {
            gone = CameraViewClient.droneLost();
        } else {
            gone = mc.world == null
                    || !(mc.world.getBlockState(CameraViewClient.getBlockPos()).getBlock() instanceof SurveillanceCameraBlock);
        }
        if (mc.player == null || mc.world == null || gone || mc.player.hurtTime > 0
                || CameraViewClient.shouldExit(sneak)) {
            stopView(mc);
            return;
        }

        CameraViewClient.tick(mc);

        boolean toggled = false;
        while (toggleKey.wasPressed()) toggled = !toggled;
        if (toggled) {
            CameraViewClient.toggleMode();
            applyShader(mc, false);
        }
        while (manualKey.wasPressed()) CameraViewClient.toggleManual(mc, mc.getTickDelta());
        while (nextKey.wasPressed()) ClientPlayNetworking.send(VigiCam.CYCLE_DEVICE, PacketByteBufs.empty());

        if (CameraViewClient.isDrone()) sendDroneInput(mc);
        if (CameraViewClient.isThermal() && mc.player.age % 2 == 0) scanEntities(mc);
    }

    /** WASD move, Espaço sobe, Ctrl desce; o drone gira para onde o mouse olha. */
    private static void sendDroneInput(MinecraftClient mc) {
        float forward = (mc.options.forwardKey.isPressed() ? 1f : 0f) - (mc.options.backKey.isPressed() ? 1f : 0f);
        float strafe = (mc.options.rightKey.isPressed() ? 1f : 0f) - (mc.options.leftKey.isPressed() ? 1f : 0f);
        float vertical = (mc.options.jumpKey.isPressed() ? 1f : 0f) - (mc.options.sprintKey.isPressed() ? 1f : 0f);
        CameraViewClient.setInputLevel(Math.max(Math.abs(forward), Math.max(Math.abs(strafe), Math.abs(vertical))));
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeFloat(forward);
        buf.writeFloat(strafe);
        buf.writeFloat(vertical);
        buf.writeFloat(CameraViewClient.getViewYaw());      // o jogador não gira: a visão tem ângulos próprios
        buf.writeFloat(CameraViewClient.getViewPitch());
        ClientPlayNetworking.send(VigiCam.DRONE_INPUT, buf);
    }

    /** Procura entidades vivas à vista da câmera (para marcadores e para o Max/Min do HUD). */
    private static void scanEntities(MinecraftClient mc) {
        Vec3d cam = CameraViewClient.getPos(1f);
        targets.clear();

        List<Entity> found = mc.world.getOtherEntities(null,
                Box.of(cam, SCAN_RANGE * 2, SCAN_RANGE * 2, SCAN_RANGE * 2),
                e -> (e instanceof LivingEntity || e instanceof DroneEntity) && e.isAlive()
                        && !CameraViewClient.isDrone(e.getId())
                        && e.squaredDistanceTo(cam) <= SCAN_RANGE * SCAN_RANGE);
        found.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(cam)));

        float hottest = Float.NEGATIVE_INFINITY;
        for (Entity e : found) {
            hottest = Math.max(hottest, HeatUtil.celsius(e));
            if (targets.size() >= MAX_TARGETS) continue;
            Vec3d center = e.getBoundingBox().getCenter();
            boolean clear = mc.world.raycast(new RaycastContext(cam, center,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player))
                    .getType() == HitResult.Type.MISS;
            if (clear) targets.add(e);
        }

        float biome = mc.world.getBiome(BlockPos.ofFloored(cam)).value().getTemperature();
        minC = biome * 25f - 5f - (mc.world.isDay() ? 0f : 5f);
        maxC = Math.max(minC + 12f, hottest);
    }

    private static void renderHud(DrawContext ctx, float tickDelta) {
        if (!CameraViewClient.isActive()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        boolean thermal = CameraViewClient.isThermal();
        boolean drone = CameraViewClient.isDrone();
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        boolean night = CameraViewClient.isNight();
        int color = night ? 0xFF55FF55 : 0xFFFFFFFF;   // verde só na visão noturna
        int len = 20, t = 2, m = 12;

        boolean blink = (mc.player.age / 10) % 2 == 0;
        ctx.drawText(mc.textRenderer, (blink ? "● " : "  ") + "LIVE", m + 6, m + 6, 0xFFFF3030, true);

        // título e modo de controle
        String title = drone ? "DRONE" : "CAM";
        if (CameraViewClient.getCount() > 0) title += "  " + (CameraViewClient.getIndex() + 1) + "/" + CameraViewClient.getCount();
        title += thermal ? "  TÉRMICA" : (night ? "  NOTURNA" : "  NORMAL");
        ctx.drawText(mc.textRenderer, title, m + 6, m + 18, color, true);
        if (drone) {
            ctx.drawText(mc.textRenderer, "PILOTO", m + 6, m + 30, 0xFFFFD040, true);
        } else if (CameraViewClient.isManual()) {
            ctx.drawText(mc.textRenderer, "MANUAL", m + 6, m + 30, 0xFFFFD040, true);
        } else {
            ctx.drawText(mc.textRenderer, "AUTO", m + 6, m + 30, 0xFF909090, true);
        }

        String time = LocalTime.now().format(CLOCK);
        ctx.drawText(mc.textRenderer, time, w - m - 6 - mc.textRenderer.getWidth(time), m + 6, color, true);

        String nextVis = switch (CameraViewClient.nextMode()) {
            case NIGHT -> "visão noturna";
            case THERMAL -> "visão térmica";
            default -> "visão normal";
        };
        String vis = "[" + toggleKey.getBoundKeyLocalizedText().getString() + "] " + nextVis;
        String hint = drone
                ? "WASD mover  |  Espaço/Ctrl subir/descer  |  Shift sair  |  " + vis
                : "Shift sair  |  " + vis + "  |  [" + manualKey.getBoundKeyLocalizedText().getString() + "] "
                        + (CameraViewClient.isManual() ? "varredura automática" : "mover visão");
        ctx.drawCenteredTextWithShadow(mc.textRenderer, hint, w / 2, h - 34, color);
        if (CameraViewClient.getCount() > 1) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer,
                    "[" + nextKey.getBoundKeyLocalizedText().getString() + "] próximo dispositivo", w / 2, h - 46, 0xFFB0B0B0);
        }

        // cantos do enquadramento
        ctx.fill(m, m, m + len, m + t, color);                 ctx.fill(m, m, m + t, m + len, color);
        ctx.fill(w - m - len, m, w - m, m + t, color);         ctx.fill(w - m - t, m, w - m, m + len, color);
        ctx.fill(m, h - m - t, m + len, h - m, color);         ctx.fill(m, h - m - len, m + t, h - m, color);
        ctx.fill(w - m - len, h - m - t, w - m, h - m, color); ctx.fill(w - m - t, h - m - len, w - m, h - m, color);

        if (drone) renderDroneTelemetry(ctx, mc, w, h, m, color);

        // sem sinal: o chunk onde a câmera está não chegou no cliente
        Vec3d cam = CameraViewClient.getPos(tickDelta);
        if (!mc.world.getChunkManager().isChunkLoaded(((int) Math.floor(cam.x)) >> 4, ((int) Math.floor(cam.z)) >> 4)) {
            ctx.fill(0, 0, w, h, 0xCC000000);
            ctx.drawCenteredTextWithShadow(mc.textRenderer, "SEM SINAL", w / 2, h / 2 - 4, 0xFFFF4040);
        }

        if (thermal) renderThermalHud(ctx, mc, tickDelta, w, h, m);
    }

    private static void renderDroneTelemetry(DrawContext ctx, MinecraftClient mc, int w, int h, int m, int color) {
        DroneEntity d = CameraViewClient.getDrone(mc);
        if (d == null) return;
        double dist = d.distanceTo(mc.player);
        double speed = d.getPos().distanceTo(new Vec3d(d.prevX, d.prevY, d.prevZ)) * 20.0;
        int x = m + 6, y = h - m - 40;
        ctx.drawText(mc.textRenderer, String.format("ALT  %.1f m", d.getY()), x, y, color, true);
        ctx.drawText(mc.textRenderer, String.format("DIST %.0f m", dist), x, y + 10, color, true);
        ctx.drawText(mc.textRenderer, String.format("VEL  %.1f m/s", speed), x, y + 20, color, true);

        // barra de sinal (cai conforme se afasta de quem pilota)
        float sig = (float) Math.max(0.0, 1.0 - dist / d.getSignalRange());
        int bx = x + 76, by = y + 20, bw = 40;
        ctx.fill(bx, by, bx + bw, by + 7, 0x88000000);
        ctx.fill(bx, by, bx + (int) (bw * sig), by + 7, sig > 0.25f ? 0xFF55FF55 : 0xFFFF4040);
        if (d.isAmplified()) {   // antena do piloto dando sinal
            ctx.drawText(mc.textRenderer, "▲ ANTENA +" + (int) (d.getSignalRange() - DroneEntity.RANGE) + " m", bx, by - 10, 0xFF55FF55, true);
        }
        if (sig < 0.1f) ctx.drawCenteredTextWithShadow(mc.textRenderer, "SINAL FRACO", w / 2, m + 40, 0xFFFF4040);
    }

    // ------------------------------------------------------------------ HUD térmico

    private static void renderThermalHud(DrawContext ctx, MinecraftClient mc, float tickDelta, int w, int h, int m) {
        // leitura Max / Min
        String max = String.format("%.1f", maxC), min = String.format("%.1f", minC);
        int bx = m + 6, by = m + 46;
        ctx.fill(bx - 3, by - 3, bx + 92, by + 22, 0x99000000);
        ctx.drawText(mc.textRenderer, "Max", bx, by, 0xFFCCCCCC, false);
        ctx.drawText(mc.textRenderer, "Min", bx + 40, by, 0xFFCCCCCC, false);
        ctx.drawText(mc.textRenderer, "°C", bx + 76, by, 0xFFCCCCCC, false);
        ctx.drawText(mc.textRenderer, max, bx, by + 11, 0xFFFFFFFF, false);
        ctx.drawText(mc.textRenderer, min, bx + 40, by + 11, 0xFFFFFFFF, false);

        // barra de escala à direita
        int sx1 = w - m - 12, sx2 = w - m - 5, sy1 = h / 2 - 60, sy2 = h / 2 + 60;
        int steps = 7;
        for (int i = 0; i < steps; i++) {
            float t0 = 1f - i / (float) steps, t1 = 1f - (i + 1) / (float) steps;
            int y0 = sy1 + (sy2 - sy1) * i / steps, y1 = sy1 + (sy2 - sy1) * (i + 1) / steps;
            ctx.fillGradient(sx1, y0, sx2, y1, HeatUtil.palette(t0), HeatUtil.palette(t1));
        }
        ctx.drawText(mc.textRenderer, max, sx1 - mc.textRenderer.getWidth(max) - 3, sy1 - 3, 0xFFFFFFFF, true);
        ctx.drawText(mc.textRenderer, min, sx1 - mc.textRenderer.getWidth(min) - 3, sy2 - 6, 0xFFFFFFFF, true);

        // retículo vermelho
        int cx = w / 2, cy = h / 2, red = 0xFFFF2020;
        ctx.fill(cx - 10, cy - 1, cx - 3, cy + 1, red);
        ctx.fill(cx + 3, cy - 1, cx + 10, cy + 1, red);
        ctx.fill(cx - 1, cy - 10, cx + 1, cy - 3, red);
        ctx.fill(cx - 1, cy + 3, cx + 1, cy + 10, red);

        // marcadores nas entidades
        Vec3d cam = CameraViewClient.getPos(tickDelta);
        float yaw = CameraViewClient.getYaw(tickDelta), pitch = CameraViewClient.getPitch(tickDelta);
        Vec3d f = Vec3d.fromPolar(pitch, yaw);
        Vec3d r = f.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d u = r.crossProduct(f);
        double tanHalf = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        double aspect = w / (double) h;

        Entity locked = null;
        double lockedDist = Double.MAX_VALUE;

        for (Entity e : targets) {
            if (!e.isAlive()) continue;
            Vec3d lerp = e.getLerpedPos(tickDelta);
            Box b = e.getBoundingBox().offset(lerp.subtract(e.getPos()));
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            boolean ok = true;
            for (int i = 0; i < 8 && ok; i++) {
                Vec3d p = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY,
                        (i & 4) == 0 ? b.minZ : b.maxZ).subtract(cam);
                double depth = p.dotProduct(f);
                if (depth < 0.1) { ok = false; break; }
                double sx = (p.dotProduct(r) / depth / (tanHalf * aspect) * 0.5 + 0.5) * w;
                double sy = (0.5 - p.dotProduct(u) / depth / tanHalf * 0.5) * h;
                minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
            }
            if (!ok || maxX < 0 || minX > w || maxY < 0 || minY > h) continue;

            int col = HeatUtil.palette(Math.max(0.35f, HeatUtil.heat(e)));
            drawBrackets(ctx, (int) minX - 2, (int) minY - 2, (int) maxX + 2, (int) maxY + 2, col);

            String label = e.getName().getString() + "  " + String.format("%.1f°C", HeatUtil.celsius(e));
            ctx.drawText(mc.textRenderer, label, (int) minX - 2, (int) minY - 12, col, true);

            if (minX <= cx && cx <= maxX && minY <= cy && cy <= maxY && e.squaredDistanceTo(cam) < lockedDist) {
                locked = e;
                lockedDist = e.squaredDistanceTo(cam);
            }
        }

        if (locked != null) {
            String s = String.format("%.1f°C", HeatUtil.celsius(locked));
            ctx.drawCenteredTextWithShadow(mc.textRenderer, s, cx, cy + 16, 0xFFFF4040);
        }
    }

    private static void drawBrackets(DrawContext ctx, int x0, int y0, int x1, int y1, int col) {
        int l = Math.max(3, Math.min(8, Math.min(x1 - x0, y1 - y0) / 3));
        ctx.fill(x0, y0, x0 + l, y0 + 1, col);     ctx.fill(x0, y0, x0 + 1, y0 + l, col);
        ctx.fill(x1 - l, y0, x1, y0 + 1, col);     ctx.fill(x1 - 1, y0, x1, y0 + l, col);
        ctx.fill(x0, y1 - 1, x0 + l, y1, col);     ctx.fill(x0, y1 - l, x0 + 1, y1, col);
        ctx.fill(x1 - l, y1 - 1, x1, y1, col);     ctx.fill(x1 - 1, y1 - l, x1, y1, col);
    }
}
