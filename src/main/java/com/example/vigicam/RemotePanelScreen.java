package com.example.vigicam;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Painel de dispositivos do controle remoto: o "Controle Remoto Minecraft" (HTML) dentro do jogo. Abre sozinho quando o jogador
 * clica com o botão direito no controle.
 * <ul>
 *   <li>Abas CAMERAS / DRONES / PORTAS com a contagem; cartões com ícone, nome, ID colorido (CAM-01...), coordenadas, direção e distância,
 *       estado (ONLINE / OFFLINE / TRANCADA / ABERTA), sinal do drone, botão PING e lixeira (1º clique arma "SIM?", o 2º apaga).</li>
 *   <li>Radar de 120 blocos com todos os dispositivos (câmera = quadrado, drone = losango, porta = retângulo), varredura, e o selecionado em destaque.</li>
 *   <li>Duplo clique num cartão ou ponto: menu com VER VIDEOS AO VIVO / PILOTAR DRONE / ABRIR ou FECHAR PORTA. APAGAR TODOS (confirma) no rodapé.</li>
 *   <li>Os beeps do HTML tocam nos mesmos momentos ({@link ModSounds} REMOTE_*).</li>
 * </ul>
 * A lista vem do servidor ({@link RemotePanel}) e é renovada a cada meio segundo; tudo que muda algo (ping, usar, apagar) é um pedido ao servidor.
 */
@Environment(EnvType.CLIENT)
public class RemotePanelScreen extends Screen {
    /** Um dispositivo vinculado. {@code index} é a posição na lista do controle (o que o servidor entende). */
    public record Entry(int index, int kind, int seq, int color, boolean online, String name, String dim,
                        boolean hasPos, int x, int y, int z, boolean locked) {}

    private static final int W = 520, H = 252;
    private static final int SX0 = 17, SX1 = W - 17, SY0 = 16, SY1 = 237;          // tela interna (dentro do bisel)
    private static final int TAB_X = 24, TAB_W = 84, TAB_Y = 44, TAB_H = 30, TAB_STEP = 34;
    private static final int LX = 114, LY = 44, LW = 264, LH = 168;                  // lista de cartões
    private static final int CW = 125, CH = 60, GAP = 6;
    private static final int RX = 384, RY = 53, RS = 112;                            // radar
    private static final int RANGE = 120;
    private static final int FBX = 418, FBY = 221, FBW = 78, FBH = 13;               // botão APAGAR TODOS
    private static final int DBW = 70, OPEN_X = FBX - 4 - DBW, CLOSE_X = OPEN_X - 4 - DBW;   // ABRIR TODAS / FECHAR TODAS (aba de portas)

    // perfis dos recortes em degraus {altura do degrau, recuo} do HTML (clip-path .stair), na metade dos pixels
    private static final int[] O1 = {6, 14, 14, 6}, O2 = {5, 12, 12, 5}, O3 = {4, 11, 11, 4}, BEZEL = {3, 9, 9, 3}, SCREEN = {2, 7, 7, 2};

    private static final String[] LABEL = {"CAMERAS", "DRONES", "PORTAS REFORCADAS"};
    private static final String[] TAB_NAME = {"CAMERAS", "DRONES", "PORTAS"};
    private static final String[] COMPASS = {"N", "NE", "L", "SE", "S", "SO", "O", "NO"};
    private static final String DEFAULT_MSG = "Clique 2x num item para ver as opcoes.";
    private static final Identifier[] ICONS = {
            new Identifier(VigiCam.MOD_ID, "textures/gui/remote_cam.png"),
            new Identifier(VigiCam.MOD_ID, "textures/gui/remote_drone.png"),
            new Identifier(VigiCam.MOD_ID, "textures/gui/remote_door.png")};

    // varredura do radar: células de 3 px dentro do círculo, com o ângulo de cada uma (horário a partir do norte)
    private static final int CELL = 3;
    private static final float[] CELL_X, CELL_Y, CELL_A;

    static {
        List<float[]> cells = new ArrayList<>();
        float c = RS / 2f, r = (RS - 6) / 2f;
        for (int i = 0; i < RS / CELL; i++) {
            for (int j = 0; j < RS / CELL; j++) {
                float cx = i * CELL + CELL / 2f, cy = j * CELL + CELL / 2f;
                if (Math.hypot(cx - c, cy - c) > r) continue;
                float a = (float) Math.toDegrees(Math.atan2(cx - c, -(cy - c)));
                cells.add(new float[]{i * CELL, j * CELL, a < 0 ? a + 360f : a});
            }
        }
        CELL_X = new float[cells.size()];
        CELL_Y = new float[cells.size()];
        CELL_A = new float[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            CELL_X[i] = cells.get(i)[0];
            CELL_Y[i] = cells.get(i)[1];
            CELL_A[i] = cells.get(i)[2];
        }
    }

    private List<Entry> entries;
    private int tab;
    private String sel, armed, dlgKey, lastKey, pingKey;
    private long armUntil, lastClickAt, pingStart, pingUntil, msgUntil;
    private String msg = DEFAULT_MSG;
    private float scroll;
    private int tickCount;

    private float scale = 1f;
    private int originX, originY;

    public RemotePanelScreen(List<Entry> entries, String message) {
        super(Text.literal("Controle remoto"));
        this.entries = entries;
        if (message != null && !message.isEmpty()) say(message);
    }

    @Override
    protected void init() {
        scale = Math.min(1f, Math.min((this.height - 8f) / (H + 6), (this.width - 8f) / W));
        originX = Math.round((this.width - W * scale) / 2f);
        originY = Math.round((this.height - (H + 6) * scale) / 2f);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        if (++tickCount % 10 == 0) send(RemotePanel.ACT_REFRESH, 0);
    }

    /** Lista nova do servidor (a seleção, a confirmação e o menu se mantêm pelo ID do dispositivo). */
    public void update(List<Entry> list, String message) {
        this.entries = list;
        if (sel != null && find(sel) == null) sel = null;
        if (armed != null && !armed.startsWith("all:") && find(armed) == null) armed = null;
        if (dlgKey != null && find(dlgKey) == null) dlgKey = null;
        clampScroll();
        if (message != null && !message.isEmpty()) say(message);
    }

    // ------------------------------------------------------------------ utilidades

    private static long now() {
        return Util.getMeasuringTimeMs();
    }

    private static void snd(SoundEvent e) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(e, 1.0f, 1.0f));
    }

    private void send(int type, int index) {
        if (!ClientPlayNetworking.canSend(VigiCam.REMOTE_ACTION)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(type);
        buf.writeInt(index);
        ClientPlayNetworking.send(VigiCam.REMOTE_ACTION, buf);
    }

    private void say(String s) {
        msg = s;
        msgUntil = now() + 3500;
    }

    private static String key(Entry e) {
        return e.kind() + ":" + e.seq();
    }

    private Entry find(String key) {
        for (Entry e : entries) if (key(e).equals(key)) return e;
        return null;
    }

    private List<Entry> visible() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.kind() == tab) out.add(e);
        return out;
    }

    private String curDim() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    private boolean sameDim(Entry e) {
        return e.dim().equals(curDim());
    }

    private static String dimLabel(String dim) {
        String p = dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        if (p.equals("the_nether")) return "NETHER";
        if (p.equals("the_end")) return "END";
        return p.toUpperCase();
    }

    /** {distância, ângulo (graus, horário a partir do norte), diferença de altura, dx, dz}. */
    private double[] geo(Entry e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double mx = mc.player == null ? 0 : Math.floor(mc.player.getX());
        double my = mc.player == null ? 0 : Math.floor(mc.player.getY());
        double mz = mc.player == null ? 0 : Math.floor(mc.player.getZ());
        double dx = e.x() - mx, dy = e.y() - my, dz = e.z() - mz;
        double a = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        return new double[]{Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)), a, dy, dx, dz};
    }

    private static String compass(double angle) {
        return COMPASS[(int) Math.round(angle / 45) % 8];
    }

    private int contentHeight() {
        int rows = (visible().size() + 1) / 2;
        return rows == 0 ? 0 : rows * (CH + GAP) - GAP;
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight() - LH)));
    }

    private double toX(double mouseX) { return (mouseX - originX) / scale; }
    private double toY(double mouseY) { return (mouseY - originY) / scale; }

    private static boolean in(double x, double y, double rx, double ry, double rw, double rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ geometria dos cartões

    private int cardX(int i) { return LX + (i % 2) * (CW + GAP); }
    private int cardY(int i) { return Math.round(LY + (i / 2) * (CH + GAP) - scroll); }

    private boolean isArmed(Entry e) { return armed != null && armed.equals(key(e)); }

    private int[] trashRect(Entry e, int cx, int cy) {
        return isArmed(e) ? new int[]{cx + CW - 20, cy + 4, 17, 10} : new int[]{cx + CW - 13, cy + 4, 10, 10};
    }

    private int[] pingRect(int cx, int cy) {
        return new int[]{cx + 44, cy + CH - 12, 24, 9};
    }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        double x = toX(mouseX), y = toY(mouseY);
        long now = now();

        if (dlgKey != null) return clickDialog(x, y);

        // abas
        for (int i = 0; i < 3; i++) {
            if (in(x, y, TAB_X, TAB_Y + i * TAB_STEP, TAB_W, TAB_H)) {
                tab = i;
                armed = null;
                scroll = 0;
                snd(ModSounds.REMOTE_TAB);
                return true;
            }
        }

        // aba de portas: abrir / fechar todas de uma vez
        if (tab == RemoteDevices.DOOR && in(x, y, CLOSE_X, FBY, DBW * 2 + 4, FBH)) {
            boolean open = x >= OPEN_X;
            if (!anyDoorOnline()) return true;
            armed = null;
            snd(open ? ModSounds.REMOTE_DOOR_OPEN : ModSounds.REMOTE_DOOR_LOCK);
            send(RemotePanel.ACT_DOORS_ALL, open ? 1 : 0);
            return true;
        }

        // APAGAR TODOS (precisa confirmar)
        if (in(x, y, FBX, FBY, FBW, FBH)) {
            if (visible().isEmpty()) return true;
            String k = "all:" + tab;
            if (k.equals(armed)) {
                send(RemotePanel.ACT_REMOVE_ALL, tab);
                snd(ModSounds.REMOTE_REMOVE_ALL);
                armed = null;
            } else {
                arm(k);
                snd(ModSounds.REMOTE_ARM);
                say("Clique de novo para apagar TODOS em " + LABEL[tab] + ".");
            }
            return true;
        }

        // pontos do radar (o de cima primeiro)
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            int[] p = radarPos(e);
            if (p == null) continue;
            if (Math.hypot(x - p[0], y - p[1]) <= 5) {
                String k = key(e);
                select(k, true);
                doubleClick(k, now);
                return true;
            }
        }

        // cartões
        if (in(x, y, LX, LY, LW - 7, LH)) {
            List<Entry> vis = visible();
            for (int i = 0; i < vis.size(); i++) {
                Entry e = vis.get(i);
                int cx = cardX(i), cy = cardY(i);
                if (!in(x, y, cx, cy, CW, CH)) continue;
                String k = key(e);
                int[] tr = trashRect(e, cx, cy);
                int[] pg = pingRect(cx, cy);
                if (in(x, y, tr[0], tr[1], tr[2], tr[3])) {
                    if (isArmed(e)) {
                        send(RemotePanel.ACT_REMOVE, e.index());
                        snd(ModSounds.REMOTE_REMOVE);
                        armed = null;
                    } else {
                        arm(k);
                        snd(ModSounds.REMOTE_ARM);
                        say("Clique de novo para apagar " + RemoteDevices.id(e.kind(), e.seq()) + ".");
                    }
                } else if (in(x, y, pg[0], pg[1], pg[2], pg[3])) {
                    if (e.online()) {
                        sel = k;
                        pingKey = k;
                        pingStart = now;
                        pingUntil = now + 3200;
                        snd(ModSounds.REMOTE_PING);
                        send(RemotePanel.ACT_PING, e.index());
                    }
                } else {
                    select(k, false);
                    doubleClick(k, now);
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean anyDoorOnline() {
        for (Entry e : entries) if (e.kind() == RemoteDevices.DOOR && e.online()) return true;
        return false;
    }

    private void arm(String k) {
        armed = k;
        armUntil = now() + 3000;
    }

    private void select(String k, boolean fromRadar) {
        sel = k;
        Entry e = find(k);
        if (fromRadar && e != null && e.kind() != tab) {
            tab = e.kind();
            armed = null;
            scroll = 0;
        }
        if (fromRadar && e != null) {                       // rola a lista até o cartão
            List<Entry> vis = visible();
            for (int i = 0; i < vis.size(); i++) {
                if (!key(vis.get(i)).equals(k)) continue;
                int top = (i / 2) * (CH + GAP);
                if (top < scroll) scroll = top;
                else if (top + CH > scroll + LH) scroll = top + CH - LH;
                clampScroll();
            }
        }
    }

    private void doubleClick(String k, long now) {
        if (k.equals(lastKey) && now - lastClickAt < 450) {
            lastKey = null;
            dlgKey = k;
            snd(ModSounds.REMOTE_MENU);
        } else {
            lastKey = k;
            lastClickAt = now;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (dlgKey == null && in(toX(mouseX), toY(mouseY), LX, LY, LW, LH)) {
            scroll -= (float) (amount * 20);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && dlgKey != null) {   // ESC fecha só o menu, como no HTML
            dlgKey = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- menu do dispositivo (duplo clique)

    private static final int DLG_W = 170;

    private int dlgHeight(int qLines) {
        return 7 + 34 + 6 + Math.round(qLines * 6.5f) + 4 + 16 + 4 + 16 + 7;
    }

    private String question(Entry e) {
        if (!e.online()) return "Dispositivo OFFLINE. Sem conexao com o controle.";
        return switch (e.kind()) {
            case RemoteDevices.CAM -> "O que voce quer fazer com esta camera?";
            case RemoteDevices.DRONE -> "O que voce quer fazer com este drone?";
            default -> "O que voce quer fazer com esta porta?";
        };
    }

    private String actionLabel(Entry e) {
        return switch (e.kind()) {
            case RemoteDevices.CAM -> "VER VIDEOS AO VIVO";
            case RemoteDevices.DRONE -> "PILOTAR DRONE";
            default -> e.locked() ? "ABRIR PORTA" : "FECHAR PORTA";
        };
    }

    private boolean clickDialog(double x, double y) {
        Entry e = find(dlgKey);
        if (e == null) {
            dlgKey = null;
            return true;
        }
        List<String> q = wrap(question(e), 0.6f, DLG_W - 14);
        int dh = dlgHeight(q.size());
        int dx = (W - DLG_W) / 2, dy = (SY0 + SY1) / 2 - dh / 2 + 3;
        int by1 = dy + dh - 7 - 16 - 4 - 16, by2 = dy + dh - 7 - 16;
        if (in(x, y, dx + 7, by1, DLG_W - 14, 16)) {
            if (!e.online()) return true;
            dlgKey = null;
            switch (e.kind()) {
                case RemoteDevices.CAM -> snd(ModSounds.REMOTE_WATCH);
                case RemoteDevices.DRONE -> snd(ModSounds.REMOTE_PILOT);
                default -> snd(e.locked() ? ModSounds.REMOTE_DOOR_OPEN : ModSounds.REMOTE_DOOR_LOCK);
            }
            send(RemotePanel.ACT_USE, e.index());
            return true;
        }
        if (in(x, y, dx + 7, by2, DLG_W - 14, 16)) dlgKey = null;
        return true;
    }

    // ------------------------------------------------------------------ texto

    private void text(DrawContext c, String s, float x, float y, float k, int rgb) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(k, k, 1f);
        c.drawText(this.textRenderer, s, 0, 0, argb(0xFF, rgb), false);
        m.pop();
    }

    private void textShadow(DrawContext c, String s, float x, float y, float k, int rgb) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(k, k, 1f);
        c.drawTextWithShadow(this.textRenderer, s, 0, 0, argb(0xFF, rgb));
        m.pop();
    }

    private float tw(String s, float k) {
        return this.textRenderer.getWidth(s) * k;
    }

    private String fit(String s, float k, float maxW) {
        if (tw(s, k) <= maxW) return s;
        while (s.length() > 1 && tw(s + "..", k) > maxW) s = s.substring(0, s.length() - 1);
        return s + "..";
    }

    private List<String> wrap(String s, float k, float maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (tw(t, k) > maxW && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        this.renderBackground(c);
        long now = now();
        if (armed != null && now > armUntil) armed = null;
        if (pingKey != null && now > pingUntil) pingKey = null;
        if (now > msgUntil) msg = DEFAULT_MSG;
        double mx = toX(mouseX), my = toY(mouseY);

        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(originX, originY, 0);
        m.scale(scale, scale, 1f);

        drawChassis(c);
        drawHead(c);
        drawTabs(c);
        drawList(c, now, mx, my);
        drawRadar(c, now);
        drawFoot(c, mx, my);
        if (dlgKey != null) drawDialog(c, mx, my);
        m.pop();
    }

    // ---- chassi de pedra, bisel e tela

    private void drawChassis(DrawContext c) {
        shape(c, 0, 6, W, H, O1, 0x66000000);                    // sombra dura (drop-shadow 0 12px)
        shape(c, 0, 0, W, H, O1, 0xFF1B1B19);
        shape(c, 2, 2, W - 4, H - 4, O2, 0xFF9C9C97);
        shape(c, 4, 4, W - 8, H - 8, O3, 0xFF7D7D78);
        clip(c, 4, 4, W - 8, H - 8, O3, 4, H - 4 - 2, W - 4, H - 4, 0xFF5A5A56);       // sombra interna inferior
        clip(c, 4, 4, W - 8, H - 8, O3, W - 4 - 2, 4, W - 4, H - 4, 0xFF5A5A56);       // e direita
        shape(c, 14, 13, W - 28, H - 13 - 12, BEZEL, 0xFF101214);
        shape(c, SX0, SY0, SX1 - SX0, SY1 - SY0, SCREEN, 0xFF36424D);
    }

    private void drawHead(DrawContext c) {
        clip(c, SX0, SY0, SX1 - SX0, SY1 - SY0, SCREEN, SX0, SY0, SX1, SY0 + 22, 0xFF8794A5);
        c.fill(SX0 + 7, SY0 + 20, SX1 - 7, SY0 + 22, 0xFF6B7889);
        text(c, "CONTROLE REMOTO > DISPOSITIVOS", SX0 + 11, SY0 + 8, 0.8f, 0x10171D);

        int on = 0, off = 0;
        for (Entry e : entries) if (e.online()) on++; else off++;
        String sOff = off + " OFFLINE", sOn = on + " ONLINE";
        float xr = SX1 - 8;
        float x1 = xr - tw(sOff, 0.7f);
        text(c, sOff, x1, SY0 + 8, 0.7f, 0x10171D);
        led(c, (int) x1 - 11, SY0 + 8, 0xE2453C, 0xF58B84, 0xA02820);
        float x2 = x1 - 16 - tw(sOn, 0.7f);
        text(c, sOn, x2, SY0 + 8, 0.7f, 0x10171D);
        led(c, (int) x2 - 11, SY0 + 8, 0x6DC46A, 0xA5E6A2, 0x3E8C3B);
    }

    private void led(DrawContext c, int x, int y, int base, int hi, int lo) {
        c.fill(x, y, x + 7, y + 5, argb(0xFF, base));
        c.fill(x, y, x + 7, y + 2, argb(0xFF, hi));
        c.fill(x, y + 4, x + 7, y + 5, argb(0xFF, lo));
    }

    private void drawTabs(DrawContext c) {
        for (int i = 0; i < 3; i++) {
            int x = TAB_X, y = TAB_Y + i * TAB_STEP;
            boolean on = tab == i;
            if (on) c.fill(x - 2, y - 2, x + TAB_W + 2, y + TAB_H + 2, 0xFFA5E6A2);
            bevel(c, x, y, TAB_W, TAB_H, on ? 0xFF5B6B7D : 0xFF27313A, on ? 0xFF9FB0C4 : 0xFF3F4D59, on ? 0xFF2F3B48 : 0xFF161D23, 1);
            icon(c, i, x + 4, y + 5, 20, true, 2);
            text(c, TAB_NAME[i], x + 28, y + 7, 0.6f, 0xD8DDE4);
            int n = 0;
            for (Entry e : entries) if (e.kind() == i) n++;
            text(c, n + (n == 1 ? " item" : " itens"), x + 28, y + 16, 0.55f, on ? 0xE8EEF5 : 0x8F9DB0);
        }
    }

    /** Ícone num quadradinho de moldura; {@code pad} = folga interna. */
    private void icon(DrawContext c, int kind, int x, int y, int size, boolean lit, int pad) {
        c.fill(x, y, x + size, y + size, 0xFF4F5B67);
        c.fill(x, y, x + size, y + 1, 0xFF2A323A);
        c.fill(x, y, x + 1, y + size, 0xFF2A323A);
        c.fill(x, y + size - 1, x + size, y + size, 0xFF66737F);
        c.fill(x + size - 1, y, x + size, y + size, 0xFF66737F);
        drawIcon(c, kind, x + pad, y + pad, size - 2 * pad, lit);
    }

    private void drawIcon(DrawContext c, int kind, int x, int y, int size, boolean lit) {
        if (!lit) RenderSystem.setShaderColor(0.45f, 0.45f, 0.45f, 1f);   // offline: cinza e escurecido
        c.drawTexture(ICONS[kind], x, y, size, size, 0f, 0f, 64, 64, 64, 64);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    // ---- lista de cartões

    private void drawList(DrawContext c, long now, double mx, double my) {
        List<Entry> vis = visible();
        if (vis.isEmpty()) {
            String a = "NENHUM DISPOSITIVO CONECTADO", b = "NESTA CATEGORIA.";
            text(c, a, LX + (LW - tw(a, 0.7f)) / 2, LY + 56, 0.7f, 0x8F9DB0);
            text(c, b, LX + (LW - tw(b, 0.7f)) / 2, LY + 68, 0.7f, 0x8F9DB0);
            return;
        }
        int sx1 = Math.round(originX + LX * scale), sy1 = Math.round(originY + LY * scale);
        int sx2 = Math.round(originX + (LX + LW) * scale), sy2 = Math.round(originY + (LY + LH) * scale);
        c.enableScissor(sx1, sy1, sx2, sy2);
        for (int i = 0; i < vis.size(); i++) {
            int cy = cardY(i);
            if (cy + CH < LY - 4 || cy > LY + LH + 4) continue;
            drawCard(c, vis.get(i), cardX(i), cy, now, mx, my);
        }
        c.disableScissor();

        // barra de rolagem (10 px no HTML)
        int content = contentHeight();
        if (content > LH) {
            int bx = LX + LW - 5;
            c.fill(bx, LY, bx + 5, LY + LH, 0xFF1A2128);
            int th = Math.max(12, LH * LH / content);
            int ty = LY + Math.round((LH - th) * (scroll / (content - LH)));
            c.fill(bx + 1, ty, bx + 4, ty + th, 0xFF8794A5);
        }
    }

    private void drawCard(DrawContext c, Entry e, int cx, int cy, long now, double mx, double my) {
        String k = key(e);
        boolean isSel = k.equals(sel), off = !e.online();
        int dc = RemoteDevices.color(e.color());

        if (isSel) c.fill(cx - 2, cy - 2, cx + CW + 2, cy + CH + 2, argb(0xFF, dc));
        boolean pinged = k.equals(pingKey) && ((now - pingStart) / 250) % 2 == 0;       // pisca 4x (pinged)
        bevel(c, cx, cy, CW, CH, pinged ? 0xFF3F5A46 : 0xFF27313A, isSel ? 0xFF9FB0C4 : 0xFF3F4D59, isSel ? 0xFF2F3B48 : 0xFF161D23, 1);

        // ícone (cinza se offline)
        c.fill(cx + 5, cy + 5, cx + 39, cy + 39, 0xFF566370);
        c.fill(cx + 5, cy + 5, cx + 39, cy + 7, 0xFF2A323A);
        c.fill(cx + 5, cy + 5, cx + 7, cy + 39, 0xFF2A323A);
        c.fill(cx + 5, cy + 37, cx + 39, cy + 39, 0xFF6F7D8A);
        c.fill(cx + 37, cy + 5, cx + 39, cy + 39, 0xFF6F7D8A);
        drawIcon(c, e.kind(), cx + 7, cy + 7, 30, !off);

        float ix = cx + 44, iw = CW - 44 - 11;
        float y = cy + 5;
        text(c, fit(e.name(), 0.7f, iw), ix, y, 0.7f, 0xEEF2F7);
        y += 8;
        c.fill((int) ix, (int) y + 1, (int) ix + 4, (int) y + 5, argb(0xFF, dc));          // chip da cor
        outline(c, (int) ix, (int) y + 1, 4, 4, 0x99000000);
        text(c, RemoteDevices.id(e.kind(), e.seq()), ix + 7, y, 0.6f, 0x8F9DB0);
        y += 6;
        if (e.hasPos()) text(c, e.x() + " " + e.y() + " " + e.z(), ix, y, 0.6f, 0xB9C5D3);
        else text(c, "SEM POSICAO", ix, y, 0.6f, 0xE8B63A);
        y += 6;
        if (e.hasPos() && sameDim(e)) {
            double[] g = geo(e);
            arrow(c, (int) ix + 2, (int) y + 2, (float) g[1]);
            String where = (long) g[0] + "m " + compass(g[1]) + (g[2] > 8 ? " ACIMA" : g[2] < -8 ? " ABAIXO" : "");
            text(c, fit(where, 0.6f, iw - 7), ix + 7, y, 0.6f, 0xA5E6A2);
        } else if (e.hasPos()) {
            text(c, dimLabel(e.dim()), ix, y, 0.6f, 0xE8B63A);
        }
        y += 6;
        // estado
        if (off) { led(c, (int) ix, (int) y, 0xE2453C, 0xF58B84, 0xA02820); text(c, "OFFLINE", ix + 10, y, 0.6f, 0xD8DDE4); }
        else if (e.kind() == RemoteDevices.DOOR) {
            if (e.locked()) { led(c, (int) ix, (int) y, 0x6DC46A, 0xA5E6A2, 0x3E8C3B); text(c, "TRANCADA", ix + 10, y, 0.6f, 0xD8DDE4); }
            else { led(c, (int) ix, (int) y, 0xE8B63A, 0xF7DA8A, 0xA57A14); text(c, "ABERTA", ix + 10, y, 0.6f, 0xD8DDE4); }
        } else { led(c, (int) ix, (int) y, 0x6DC46A, 0xA5E6A2, 0x3E8C3B); text(c, "ONLINE", ix + 10, y, 0.6f, 0xD8DDE4); }
        y += 8;
        // drone: barra de sinal (o mod não tem bateria; o sinal cai com a distância até o drone)
        if (e.kind() == RemoteDevices.DRONE) {
            int pct = 0;
            if (!off && e.hasPos() && sameDim(e)) pct = (int) Math.max(0, Math.min(100, 100 - geo(e)[0] / RemoteItem.MAX_DISTANCE * 100));
            int bw = 38;
            c.fill((int) ix, (int) y, (int) ix + bw, (int) y + 5, 0xFF1A2128);
            c.fill((int) ix + 1, (int) y + 1, (int) ix + bw - 1, (int) y + 4, 0xFF0E1317);
            int fw = (bw - 2) * pct / 100;
            c.fill((int) ix + 1, (int) y + 1, (int) ix + 1 + fw, (int) y + 4, pct < 20 ? 0xFFE2453C : pct < 50 ? 0xFFE8B63A : 0xFF6DC46A);
            text(c, "SINAL " + pct + "%", ix + bw + 3, y, 0.5f, 0x8F9DB0);
        }

        // PING
        int[] pg = pingRect(cx, cy);
        bevel(c, pg[0], pg[1], pg[2], pg[3], 0xFF13A04A, 0xFF66F29C, 0xFF0A6630, 1);
        textShadow(c, "PING", pg[0] + (pg[2] - tw("PING", 0.55f)) / 2, pg[1] + 2.5f, 0.55f, 0xFFFFFF);
        if (off) c.fill(pg[0], pg[1], pg[0] + pg[2], pg[1] + pg[3], 0x99_27313A);

        // lixeira / SIM?
        int[] tr = trashRect(e, cx, cy);
        boolean arm = isArmed(e);
        boolean hov = in(mx, my, tr[0], tr[1], tr[2], tr[3]);
        boolean red = arm || hov;
        bevel(c, tr[0], tr[1], tr[2], tr[3], red ? 0xFFC21E1E : 0xFF6B7684, red ? 0xFFFF6A6A : 0xFF9AA6B4, red ? 0xFF7A0F0F : 0xFF444E5A, 1);
        if (arm) {
            if ((now / 500) % 2 == 1) c.fill(tr[0] + 1, tr[1] + 1, tr[0] + tr[2] - 1, tr[1] + tr[3] - 1, 0xFF8F1414);
            textShadow(c, "SIM?", tr[0] + (tr[2] - tw("SIM?", 0.5f)) / 2, tr[1] + 3, 0.5f, 0xFFFFFF);
        } else {
            // lixeira em pixel art 6x6, centrada no botão de 10x10 (2 px de folga em cada lado)
            int tx = tr[0] + (tr[2] - 6) / 2, ty = tr[1] + (tr[3] - 6) / 2;
            int white = 0xFFFFFFFF, slot = red ? 0xFFC21E1E : 0xFF6B7684;
            c.fill(tx + 2, ty, tx + 4, ty + 1, white);              // alça
            c.fill(tx, ty + 1, tx + 6, ty + 2, white);              // tampa
            c.fill(tx + 1, ty + 2, tx + 5, ty + 6, white);          // corpo
            c.fill(tx + 2, ty + 3, tx + 4, ty + 5, slot);           // fendas
        }
    }

    /** Seta (SVG 10x10 do HTML) girada pelo ângulo, centrada em (cx, cy), 5 px. */
    private void arrow(DrawContext c, int cx, int cy, float angle) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(cx, cy, 0);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle));
        m.scale(0.5f, 0.5f, 1f);
        m.translate(-5, -5, 0);
        int col = 0xFFA5E6A2;
        c.fill(4, 0, 6, 2, col);
        c.fill(2, 2, 8, 4, col);
        c.fill(0, 4, 10, 5, col);
        c.fill(4, 5, 6, 10, col);
        m.pop();
    }

    // ---- radar

    /** Posição do ponto no radar (design), ou null se estiver em outra dimensão / sem posição. */
    private int[] radarPos(Entry e) {
        if (!e.hasPos() || !sameDim(e)) return null;
        double[] g = geo(e);
        double dx = g[3], dz = g[4];
        double m = Math.max(Math.abs(dx), Math.abs(dz));
        double k = m > RANGE ? RANGE / m : 1;
        return new int[]{(int) Math.round(RX + RS * (0.5 + dx * k / RANGE * 0.5)), (int) Math.round(RY + RS * (0.5 + dz * k / RANGE * 0.5)),
                k < 1 ? 1 : 0};
    }

    private void drawRadar(DrawContext c, long now) {
        text(c, "RADAR (120 BLOCOS)", RX, TAB_Y, 0.55f, 0x8F9DB0);

        c.fill(RX, RY, RX + RS, RY + RS, 0xFF0F161B);
        c.fill(RX, RY, RX + RS, RY + 2, 0xFF070B0E);
        c.fill(RX, RY, RX + 2, RY + RS, 0xFF070B0E);
        c.fill(RX, RY + RS - 2, RX + RS, RY + RS, 0xFF38454F);
        c.fill(RX + RS - 2, RY, RX + RS, RY + RS, 0xFF38454F);
        for (int i = 1; i < 4; i++) {                                  // grade a cada 25 %
            c.fill(RX + RS * i / 4, RY, RX + RS * i / 4 + 1, RY + RS, 0x2E6DC46A);
            c.fill(RX, RY + RS * i / 4, RX + RS, RY + RS * i / 4 + 1, 0x2E6DC46A);
        }

        // varredura: 32 passos em 3,2 s; a parte clara fica na frente e some em 126 graus (conic-gradient do HTML)
        float theta = (float) ((now % 3200) / 100) * 11.25f;
        for (int i = 0; i < CELL_A.length; i++) {
            float rel = CELL_A[i] - theta;
            if (rel < 0) rel += 360f;
            if (rel >= 126f) continue;
            int a = (int) (0.38f * (1f - rel / 126f) * 255f);
            if (a < 6) continue;
            int x = RX + (int) CELL_X[i], y = RY + (int) CELL_Y[i];
            c.fill(x, y, x + CELL, y + CELL, argb(a, 0x6DC46A));
        }

        text(c, "N", RX + RS / 2f - tw("N", 0.6f) / 2, RY + 3, 0.6f, 0xA5E6A2);
        int mxp = RX + RS / 2, myp = RY + RS / 2;                      // "voce": cruz branca
        c.fill(mxp - 3, myp, mxp + 3, myp + 1, 0xFFFFFFFF);
        c.fill(mxp, myp - 3, mxp + 1, myp + 3, 0xFFFFFFFF);

        // pontos
        for (Entry e : entries) {
            int[] p = radarPos(e);
            if (p == null) continue;
            String k = key(e);
            int dc = RemoteDevices.color(e.color());
            int a = p[2] == 1 ? 0x8C : 0xFF;                           // na borda: mais fraco (fora de alcance)
            if (e.kind() == RemoteDevices.DRONE) {
                for (int r = -3; r <= 3; r++) {                        // losango
                    int half = 3 - Math.abs(r);
                    c.fill(p[0] - half - 1, p[1] + r, p[0] + half + 2, p[1] + r + 1, 0xFF000000);
                }
                for (int r = -2; r <= 2; r++) {
                    int half = 2 - Math.abs(r);
                    c.fill(p[0] - half, p[1] + r, p[0] + half + 1, p[1] + r + 1, argb(a, dc));
                }
            } else if (e.kind() == RemoteDevices.DOOR) {
                c.fill(p[0] - 2, p[1] - 4, p[0] + 3, p[1] + 4, 0xFF000000);
                c.fill(p[0] - 1, p[1] - 3, p[0] + 2, p[1] + 3, argb(a, dc));
            } else {
                c.fill(p[0] - 3, p[1] - 3, p[0] + 4, p[1] + 4, 0xFF000000);
                c.fill(p[0] - 2, p[1] - 2, p[0] + 3, p[1] + 3, argb(a, dc));
            }
            if (k.equals(sel)) outline(c, p[0] - 5, p[1] - 5, 11, 11, 0xFFFFFFFF);
            if (k.equals(pingKey)) {                                   // anel que cresce (rp: 1 s, 6 passos)
                float t = ((now - pingStart) % 1000) / 1000f;
                float step = (float) Math.floor(t * 6) / 6f;
                int half = 3 + Math.round(step * 14);
                outline(c, p[0] - half, p[1] - half, half * 2 + 1, half * 2 + 1, argb((int) ((1f - step) * 255), dc));
            }
        }

        // informação do selecionado
        float y = RY + RS + 3;
        Entry f = sel == null ? null : find(sel);
        if (f != null) {
            text(c, RemoteDevices.id(f.kind(), f.seq()), RX, y, 0.55f, 0xEEF2F7);
            text(c, fit(f.name(), 0.55f, RS - 4 - tw(RemoteDevices.id(f.kind(), f.seq()), 0.55f) - 4),
                    RX + tw(RemoteDevices.id(f.kind(), f.seq()), 0.55f) + 4, y, 0.55f, 0xB9C5D3);
            text(c, "COR " + RemoteDevices.NAMES[f.color()].toUpperCase(), RX, y + 6.5f, 0.55f, 0xB9C5D3);
            if (f.hasPos() && sameDim(f)) {
                double[] g = geo(f);
                text(c, (long) g[0] + " BLOCOS " + compass(g[1]), RX, y + 13, 0.55f, 0xB9C5D3);
                text(c, "Y " + f.y() + " (" + (g[2] > 0 ? "+" : "") + (long) g[2] + ")", RX, y + 19.5f, 0.55f, 0xB9C5D3);
            } else {
                text(c, dimLabel(f.dim()), RX, y + 13, 0.55f, 0xB9C5D3);
            }
        } else {
            text(c, "Clique num ponto ou card", RX, y, 0.55f, 0xB9C5D3);
            text(c, "para localizar.", RX, y + 6.5f, 0.55f, 0xB9C5D3);
        }
        int other = 0;
        for (Entry e : entries) if (!sameDim(e)) other++;
        if (other > 0) text(c, other + " em outra dimensao", RX, y + 27, 0.55f, 0xE8B63A);
    }

    private static void outline(DrawContext c, int x, int y, int w, int h, int color) {
        c.fill(x, y, x + w, y + 1, color);
        c.fill(x, y + h - 1, x + w, y + h, color);
        c.fill(x, y, x + 1, y + h, color);
        c.fill(x + w - 1, y, x + w, y + h, color);
    }

    // ---- rodapé

    private void drawFoot(DrawContext c, double mx, double my) {
        clip(c, SX0, SY0, SX1 - SX0, SY1 - SY0, SCREEN, SX0, SY1 - 19, SX1, SY1, 0xFF2A343D);
        c.fill(SX0 + 7, SY1 - 19, SX1 - 7, SY1 - 17, 0xFF1A2128);
        int msgRight = tab == RemoteDevices.DOOR ? CLOSE_X : FBX;
        text(c, fit(msg, 0.7f, msgRight - SX0 - 22), SX0 + 11, SY1 - 13, 0.7f, 0xB9C5D3);

        if (tab == RemoteDevices.DOOR) {
            boolean none = !anyDoorOnline();
            fbtnColors(c, CLOSE_X, FBY, DBW, FBH, "FECHAR TODAS", 0xFFC9962B, 0xFFF7DA8A, 0xFF7A5A12,
                    !none && in(mx, my, CLOSE_X, FBY, DBW, FBH), none, 0xFF2A343D);
            fbtnColors(c, OPEN_X, FBY, DBW, FBH, "ABRIR TODAS", 0xFF13A04A, 0xFF66F29C, 0xFF0A6630,
                    !none && in(mx, my, OPEN_X, FBY, DBW, FBH), none, 0xFF2A343D);
        }

        boolean empty = visible().isEmpty();
        boolean red = armed != null && armed.equals("all:" + tab);
        String label = red ? "CONFIRMAR?" : "APAGAR TODOS";
        boolean hov = !empty && in(mx, my, FBX, FBY, FBW, FBH);
        fbtn(c, FBX, FBY, FBW, FBH, label, red, hov, empty, 0xFF2A343D);
    }

    /** Botão cinza (ou vermelho / verde) do HTML (.fbtn). */
    private void fbtn(DrawContext c, int x, int y, int w, int h, String label, boolean red, boolean hover, boolean disabled, int bg) {
        int base = red ? 0xFFC21E1E : 0xFF6B6B73, hi = red ? 0xFFFF6A6A : 0xFF9A9AA2, lo = red ? 0xFF7A0F0F : 0xFF4A4A50;
        fbtnColors(c, x, y, w, h, label, base, hi, lo, hover, disabled, bg);
    }

    private void fbtnColors(DrawContext c, int x, int y, int w, int h, String label, int base, int hi, int lo, boolean hover, boolean disabled, int bg) {
        c.fill(x, y, x + w, y + h, 0xFF0E0E10);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, base);
        c.fill(x + 1, y + 1, x + w - 1, y + 2, hi);
        c.fill(x + 1, y + 1, x + 2, y + h - 1, hi);
        c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, lo);
        c.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, lo);
        if (hover) c.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x26FFFFFF);
        textShadow(c, label, x + (w - tw(label, 0.7f)) / 2, y + (h - 5.6f) / 2 + 0.5f, 0.7f, 0xFFFFFF);
        if (disabled) c.fill(x, y, x + w, y + h, argb(0x99, bg & 0xFFFFFF));
    }

    // ---- menu do dispositivo

    private void drawDialog(DrawContext c, double mx, double my) {
        Entry e = find(dlgKey);
        if (e == null) return;
        clip(c, SX0, SY0, SX1 - SX0, SY1 - SY0, SCREEN, SX0, SY0, SX1, SY1, 0xD1080C10);

        List<String> q = wrap(question(e), 0.6f, DLG_W - 14);
        int dh = dlgHeight(q.size());
        int dx = (W - DLG_W) / 2, dy = (SY0 + SY1) / 2 - dh / 2 + 3;
        c.fill(dx - 2, dy - 2, dx + DLG_W + 2, dy + dh + 2, 0xFF0B0F12);
        bevel(c, dx, dy, DLG_W, dh, 0xFF2A343D, 0xFF4A5967, 0xFF151C22, 2);

        icon(c, e.kind(), dx + 7, dy + 7, 34, e.online(), 3);
        text(c, fit(e.name(), 0.9f, DLG_W - 14 - 34 - 6), dx + 7 + 34 + 6, dy + 11, 0.9f, 0xEEF2F7);
        text(c, RemoteDevices.id(e.kind(), e.seq()), dx + 7 + 34 + 6, dy + 24, 0.6f, 0x8F9DB0);

        float ty = dy + 7 + 34 + 6;
        for (String line : q) {
            text(c, line, dx + 7, ty, 0.6f, 0xB9C5D3);
            ty += 6.5f;
        }

        int by1 = dy + dh - 7 - 16 - 4 - 16, by2 = dy + dh - 7 - 16;
        boolean h1 = e.online() && in(mx, my, dx + 7, by1, DLG_W - 14, 16);
        fbtnColors(c, dx + 7, by1, DLG_W - 14, 16, actionLabel(e), 0xFF13A04A, 0xFF66F29C, 0xFF0A6630, h1, !e.online(), 0xFF2A343D);
        boolean h2 = in(mx, my, dx + 7, by2, DLG_W - 14, 16);
        fbtnColors(c, dx + 7, by2, DLG_W - 14, 16, "CANCELAR", 0xFF6B6B73, 0xFF9A9AA2, 0xFF4A4A50, h2, false, 0xFF2A343D);
    }

    // ------------------------------------------------------------------ formas e bevel (iguais às dos outros painéis)

    private static void bevel(DrawContext c, int x, int y, int w, int h, int base, int light, int dark, int t) {
        c.fill(x, y, x + w, y + h, base);
        c.fill(x, y, x + w, y + t, light);
        c.fill(x, y, x + t, y + h, light);
        c.fill(x, y + h - t, x + w, y + h, dark);
        c.fill(x + w - t, y, x + w, y + h, dark);
    }

    private static int insetAt(int[] profile, int d) {
        for (int i = 0; i < profile.length; i += 2) if (d < profile[i]) return profile[i + 1];
        return 0;
    }

    private static void shape(DrawContext c, int x, int y, int w, int h, int[] profile, int color) {
        clip(c, x, y, w, h, profile, x, y, x + w, y + h, color);
    }

    /** Preenche {@code [rx0,rx1) x [ry0,ry1)} só onde cai dentro da forma em degraus (x, y, w, h, profile). */
    private static void clip(DrawContext c, int x, int y, int w, int h, int[] profile,
                             int rx0, int ry0, int rx1, int ry1, int color) {
        boolean open = false;
        int runY = 0, runX0 = 0, runX1 = 0;
        for (int row = ry0; row <= ry1; row++) {
            int x0 = 0, x1 = 0;
            boolean has = false;
            if (row < ry1) {
                int inset = insetAt(profile, Math.min(row - y, y + h - 1 - row));
                x0 = Math.max(rx0, x + inset);
                x1 = Math.min(rx1, x + w - inset);
                has = x0 < x1;
            }
            if (open && (!has || x0 != runX0 || x1 != runX1)) {
                c.fill(runX0, runY, runX1, row, color);
                open = false;
            }
            if (has && !open) {
                open = true;
                runY = row;
                runX0 = x0;
                runX1 = x1;
            }
        }
    }
}
