package com.example.vigicam;

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
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Painel de acesso da Porta Reforçada, baseado no "Terminal de Acesso" HTML: chassi octogonal em degraus (pixel art),
 * parafusos, LED, visor com scanlines e cadeado, teclado numérico 3x4 em "pedra" e os mesmos sons 8-bit
 * (tecla, limpar, erro, destravar - ver {@link ModSounds}).
 * <p>
 * Todo o desenho é feito num espaço de design de {@link #W} px de largura (metade do HTML original) e escalado para caber na tela.
 * <ul>
 *   <li>Qualquer jogador: digita a senha ({@link ReinforcedDoorBlockEntity#PIN_LENGTH} dígitos). Quem decide se abre é o servidor.</li>
 *   <li>Dono: vê a barra extra com [ABRIR] (abre sem senha) e [SENHA] (troca a senha). Os outros jogadores nem recebem esses botões.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class DoorPanelScreen extends Screen {
    private static final int W = 160;
    private static final int PIN = ReinforcedDoorBlockEntity.PIN_LENGTH;

    private enum Mode { NORMAL, OWNER, CHANGE }

    private enum Style { STONE, RED, GREEN }

    /** Cores de cada estado do painel (RGB, sem alpha). */
    private enum Theme {
        //   LED/texto, LED claro, LED escuro, texto da senha, cadeado claro, cadeado sombra, buraco, fundo do visor (de fora pra dentro)
        RED(0xFF2A2A, 0xFF7A7A, 0xA80000, 0xFF4D4D, 0xFF7878, 0xB80000, 0x120404, new int[]{0x0D0303, 0x150505, 0x1D0707, 0x240808}),
        GREEN(0x00FF44, 0x8CFFAA, 0x009929, 0x00FF44, 0x9EFFB8, 0x009E2A, 0x041408, new int[]{0x030D06, 0x06180B, 0x082210, 0x092912}),
        AMBER(0xFFB000, 0xFFE08A, 0xA06E00, 0xFFB84D, 0xFFD97A, 0xA87000, 0x140E03, new int[]{0x0D0903, 0x171004, 0x211705, 0x2A1D06});

        final int main, ledLight, ledDark, text, lockLight, lockShade, key;
        final int[] bg;

        Theme(int main, int ledLight, int ledDark, int text, int lockLight, int lockShade, int key, int[] bg) {
            this.main = main; this.ledLight = ledLight; this.ledDark = ledDark; this.text = text;
            this.lockLight = lockLight; this.lockShade = lockShade; this.key = key; this.bg = bg;
        }
    }

    /** Cadeado em pixel art 16x16 (A = cor, H = brilho, S = sombra, K = buraco da chave). */
    private static final String[] LOCK_CLOSED = {
            "................",
            "......HHHH......",
            ".....AAAAAA.....",
            "....AAA..AAA....",
            "....AA....AA....",
            "....AA....AA....",
            "....AA....AA....",
            "...HHHHHHHHHH...",
            "...AAAAAAAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAAAAAAA...",
            "...SSSSSSSSSS...",
            "................"};
    private static final String[] LOCK_OPEN = {
            "......HHHH......",
            ".....AA..AA.....",
            "....AA....AA....",
            "....AA....AA....",
            "....AA..........",
            "....AA..........",
            "....AA..........",
            "...HHHHHHHHHH...",
            "...AAAAAAAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAKKAAAA...",
            "...AAAAAAAAAA...",
            "...SSSSSSSSSS...",
            "................"};

    private static final class Btn {
        final int x, y, w, h;
        final String id;
        final Style style;

        Btn(int x, int y, int w, int h, String id, Style style) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.id = id; this.style = style;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private final BlockPos pos;
    private final boolean owner;
    private final int panelH;   // altura do painel (o field "height" da Screen é a da janela) (o do dono tem a barra extra)
    private final List<Btn> buttons = new ArrayList<>();
    private final List<int[]> texture = new ArrayList<>();   // {x, y, cor}: ladrilhos da textura "deepslate"
    private final StringBuilder input = new StringBuilder();

    private Mode mode;
    private boolean unlocked, waiting;
    private long validateAt, errStart, errUntil, savedUntil, closeAt, waitDeadline, pressedUntil;
    private String pressedId = "";

    private float scale = 1f;
    private int originX, originY;

    public DoorPanelScreen(BlockPos pos, boolean owner) {
        super(Text.literal("Painel de acesso"));
        this.pos = pos;
        this.owner = owner;
        this.mode = owner ? Mode.OWNER : Mode.NORMAL;
        this.panelH = owner ? 271 : 249;

        String[] rows = {"123", "456", "789"};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) buttons.add(new Btn(15 + c * 45, 120 + r * 27, 40, 22, rows[r].substring(c, c + 1), Style.STONE));
        }
        buttons.add(new Btn(15, 201, 40, 22, "clear", Style.RED));
        buttons.add(new Btn(60, 201, 40, 22, "0", Style.STONE));
        buttons.add(new Btn(105, 201, 40, 22, "enter", Style.GREEN));
        if (owner) {   // só o dono recebe estes botões
            buttons.add(new Btn(15, 229, 62, 16, "open", Style.GREEN));
            buttons.add(new Btn(83, 229, 62, 16, "change", Style.STONE));
        }

        for (int cy = 4; cy + 4 <= panelH - 4; cy += 4) {
            for (int cx = 4; cx + 4 <= W - 4; cx += 4) {
                if (!inOct(cx, cy, 4, 4, W - 8, panelH - 8, 4) || !inOct(cx + 3, cy, 4, 4, W - 8, panelH - 8, 4)
                        || !inOct(cx, cy + 3, 4, 4, W - 8, panelH - 8, 4) || !inOct(cx + 3, cy + 3, 4, 4, W - 8, panelH - 8, 4)) continue;
                int gx = cx / 4, gy = cy / 4, n = (gx * 31 + gy * 17) % 7;
                int color = n == 0 ? 0xFF1D1D20 : n == 1 ? 0xFF2B2B30 : (gx + gy) % 2 == 0 ? 0xFF212125 : 0;
                if (color != 0) texture.add(new int[]{cx, cy, color});
            }
        }
    }

    @Override
    protected void init() {
        scale = Math.min(1f, (this.height - 8f) / panelH);   // this.height = altura da janela
        originX = Math.round((this.width - W * scale) / 2f);
        originY = Math.round((this.height - panelH * scale) / 2f);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ resposta do servidor

    /** Resultado de um pedido (tipos em {@link ReinforcedDoorBlock}). */
    public void onResult(int type, boolean ok) {
        waiting = false;
        long now = now();
        if (type == ReinforcedDoorBlock.ACT_SET_PIN) {
            if (ok) {
                sound(ModSounds.DOOR_UNLOCK);
                savedUntil = now + 1300;
                input.setLength(0);
                mode = Mode.OWNER;
            } else error();
        } else if (ok) {
            unlocked = true;
            sound(ModSounds.DOOR_UNLOCK);
            closeAt = now + 1100;
        } else {
            error();
        }
    }

    // ------------------------------------------------------------------ lógica do teclado

    private static long now() {
        return Util.getMeasuringTimeMs();
    }

    private static void sound(SoundEvent event) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(event, 1.0f, 1.0f));
    }

    private boolean errActive(long now) {
        return errUntil > 0 && now < errUntil;
    }

    private void error() {
        sound(ModSounds.DOOR_ERROR);
        errStart = now();
        errUntil = errStart + 650;
        validateAt = 0;
    }

    private void send(int type, String pin) {
        waiting = true;
        waitDeadline = now() + 1500;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(type);
        buf.writeBlockPos(pos);
        buf.writeString(pin);
        ClientPlayNetworking.send(VigiCam.DOOR_ACTION, buf);
    }

    private void digit(char d) {
        if (unlocked || input.length() >= PIN) return;
        sound(ModSounds.DOOR_KEY);
        input.append(d);
        if (mode != Mode.CHANGE && input.length() == PIN) validateAt = now() + 180;   // confere sozinho no último dígito
    }

    private void clear() {
        if (unlocked) return;
        sound(ModSounds.DOOR_CLEAR);
        input.setLength(0);
        validateAt = 0;
    }

    private void enter() {
        if (unlocked || waiting) return;
        if (input.length() < PIN) {
            if (input.length() == 0) sound(ModSounds.DOOR_KEY); else error();
            return;
        }
        send(mode == Mode.CHANGE ? ReinforcedDoorBlock.ACT_SET_PIN : ReinforcedDoorBlock.ACT_TRY_PIN, input.toString());
    }

    private void press(String id) {
        pressedId = id;
        pressedUntil = now() + 110;
    }

    private void activate(String id) {
        press(id);
        switch (id) {
            case "clear" -> clear();
            case "enter" -> enter();
            case "open" -> {
                if (!unlocked && !waiting) { sound(ModSounds.DOOR_KEY); send(ReinforcedDoorBlock.ACT_OWNER_OPEN, ""); }
            }
            case "change" -> {
                if (unlocked) return;
                sound(ModSounds.DOOR_KEY);
                input.setLength(0);
                validateAt = 0;
                mode = mode == Mode.CHANGE ? Mode.OWNER : Mode.CHANGE;
            }
            default -> digit(id.charAt(0));
        }
    }

    @Override
    public void tick() {
        long now = now();
        if (validateAt > 0 && now >= validateAt) {
            validateAt = 0;
            if (mode != Mode.CHANGE && input.length() == PIN && !unlocked && !waiting) send(ReinforcedDoorBlock.ACT_TRY_PIN, input.toString());
        }
        if (errUntil > 0 && now >= errUntil) {   // a senha errada some do visor
            errUntil = 0;
            input.setLength(0);
        }
        if (waiting && now > waitDeadline) waiting = false;
        if (closeAt > 0 && now >= closeAt) close();
    }

    // ------------------------------------------------------------------ entrada

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            double x = (mouseX - originX) / scale, y = (mouseY - originY) / scale;
            for (Btn b : buttons) {
                if (b.contains(x, y)) {
                    activate(b.id);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            activate(String.valueOf((char) ('0' + keyCode - GLFW.GLFW_KEY_0)));
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            activate(String.valueOf((char) ('0' + keyCode - GLFW.GLFW_KEY_KP_0)));
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!unlocked && input.length() > 0) {
                    sound(ModSounds.DOOR_KEY);
                    input.setLength(input.length() - 1);
                    validateAt = 0;
                }
                return true;
            }
            case GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_C -> { activate("clear"); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { activate("enter"); return true; }
            default -> { return super.keyPressed(keyCode, scanCode, modifiers); }
        }
    }

    // ------------------------------------------------------------------ estado visual

    private Theme theme(long now) {
        if (unlocked) return Theme.GREEN;
        if (errActive(now)) return Theme.RED;
        if (now < savedUntil) return Theme.GREEN;
        if (mode == Mode.CHANGE) return Theme.AMBER;
        return owner ? Theme.GREEN : Theme.RED;
    }

    private String status(long now) {
        if (unlocked) return "ABERTO";
        if (errActive(now)) return "NEGADO";
        if (now < savedUntil) return "SALVO";
        if (mode == Mode.CHANGE) return "NOVA SENHA";
        return owner ? "DONO" : "TRANCADO";
    }

    private String label(Btn b) {
        return switch (b.id) {
            case "clear" -> "X";
            case "enter" -> "OK";
            case "open" -> "ABRIR";
            case "change" -> mode == Mode.CHANGE ? "VOLTAR" : "SENHA";
            default -> b.id;
        };
    }

    /** Tremor em degraus de pixel ao errar a senha. */
    private int shake(long now) {
        if (errStart == 0) return 0;
        long t = now - errStart;
        if (t < 64) return -4;
        if (t < 128) return 4;
        if (t < 192) return -2;
        if (t < 256) return 2;
        return 0;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        this.renderBackground(c);
        long now = now();
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(originX + shake(now) * scale, originY, 0);
        m.scale(scale, scale, 1f);
        drawPanel(c, now, (mouseX - originX) / scale, (mouseY - originY) / scale);
        m.pop();
    }

    private void drawPanel(DrawContext c, long now, double mx, double my) {
        Theme th = theme(now);

        // chassi: sombra, contorno, bisel e corpo (todos octogonais em degraus)
        oct(c, 0, 7, W, panelH, 4, 0x47000000);
        oct(c, 0, 0, W, panelH, 4, 0xFF141417);
        oct(c, 2, 2, W - 4, panelH - 4, 4, 0xFF3A3A40);
        oct(c, 4, 4, W - 8, panelH - 8, 4, 0xFF252529);
        for (int[] t : texture) c.fill(t[0], t[1], t[0] + 4, t[1] + 4, t[2]);
        c.fill(16, 4, W - 16, 6, 0xFF333338);
        c.fill(4, 16, 6, panelH - 16, 0xFF333338);
        c.fill(16, panelH - 6, W - 16, panelH - 4, 0xFF161619);
        c.fill(W - 6, 16, W - 4, panelH - 16, 0xFF161619);

        // topo: parafusos e LED
        screw(c, 14, 13);
        screw(c, W - 20, 13);
        c.fill(64, 12, 100, 24, 0xFF3C3C42);
        c.fill(62, 10, 98, 22, 0xFF0E0E10);
        c.fill(64, 12, 96, 20, 0xFF121215);
        c.fill(64, 12, 96, 13, 0xFF08080A);
        c.fill(64, 12, 65, 20, 0xFF08080A);
        c.fill(62, 9, 98, 23, argb(0x10, th.main));
        c.fill(66 - 4, 13 - 4, 94 + 4, 19 + 4, argb(0x14, th.main));
        c.fill(66 - 2, 13 - 2, 94 + 2, 19 + 2, argb(0x26, th.main));
        c.fill(66, 13, 94, 19, argb(0xFF, th.main));
        c.fill(66, 13, 94, 14, argb(0xFF, th.ledLight));
        c.fill(66, 18, 94, 19, argb(0xFF, th.ledDark));

        drawVisor(c, now, th);

        for (Btn b : buttons) {
            boolean pressed = b.id.equals(pressedId) && now < pressedUntil;
            drawButton(c, b, b.contains(mx, my), pressed);
        }

        int by = (owner ? 245 : 223) + 8;
        screw(c, 18, by);
        screw(c, W - 24, by);
        c.fill(64, by + 1, 96, by + 5, 0xFF141417);
        c.fill(64, by + 5, 96, by + 6, 0xFF3C3C42);
    }

    private void drawVisor(DrawContext c, long now, Theme th) {
        // moldura recuada
        int fx = 12, fy = 28, fw = 136, fh = 84;
        c.fill(fx, fy, fx + fw, fy + fh, 0xFF0E0E10);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF18181B);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + 4, 0xFF0B0B0D);
        c.fill(fx + 2, fy + 2, fx + 4, fy + fh - 2, 0xFF0B0B0D);
        c.fill(fx + 2, fy + fh - 4, fx + fw - 2, fy + fh - 2, 0xFF38383E);
        c.fill(fx + fw - 4, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF38383E);

        // tela
        int x0 = 16, y0 = 32, x1 = 144, y1 = 108;
        c.fill(x0, y0, x1, y1, 0xFF09090B);
        x0++; y0++; x1--; y1--;
        boolean flash = errActive(now) && ((now - errStart) / 90) % 2 == 1;   // piscada vermelha ao errar
        if (flash) {
            c.fill(x0, y0, x1, y1, 0xFF4A0808);
        } else {
            int[] insetX = {0, 8, 20, 34, 48}, insetY = {0, 5, 12, 20, 28};   // brilho central (gradiente radial em degraus)
            for (int i = 0; i < 4; i++) c.fill(x0 + insetX[i], y0 + insetY[i], x1 - insetX[i], y1 - insetY[i], argb(0xFF, th.bg[i]));
        }

        boolean lockOpen = th != Theme.RED;
        drawLock(c, lockOpen, th, 56, lockOpen ? 48 : 45);   // o arco do cadeado aberto começa uma linha acima do fechado

        c.drawCenteredTextWithShadow(this.textRenderer, status(now), 80, 37, argb(0xFF, th.main));
        c.drawCenteredTextWithShadow(this.textRenderer, pinText(), 80, 95, argb(0xFF, th.text));

        // scanlines
        for (int y = y0; y < y1; y += 2) c.fill(x0, y + 1, x1, y + 2, 0x52000000);
    }

    private String pinText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PIN; i++) {
            if (i > 0) sb.append(' ');
            if (unlocked) sb.append('*');
            else if (i < input.length()) sb.append(mode == Mode.CHANGE ? input.charAt(i) : '*');   // ao trocar a senha o dono vê o que digita
            else sb.append('_');
        }
        return sb.toString();
    }

    private void drawLock(DrawContext c, boolean open, Theme th, int lx, int ly) {
        String[] g = open ? LOCK_OPEN : LOCK_CLOSED;
        for (int layer = 2; layer >= 1; layer--) {   // brilho: o desenho "engordado" em 2 camadas translúcidas
            int color = argb(layer == 2 ? 0x12 : 0x1C, th.main);
            for (int r = -layer; r < 16 + layer; r++) {
                int start = Integer.MIN_VALUE;
                for (int col = -layer; col <= 16 + layer; col++) {
                    boolean on = col < 16 + layer && near(g, col, r, layer);
                    if (on && start == Integer.MIN_VALUE) start = col;
                    if (!on && start != Integer.MIN_VALUE) {
                        c.fill(lx + start * 3, ly + r * 3, lx + col * 3, ly + (r + 1) * 3, color);
                        start = Integer.MIN_VALUE;
                    }
                }
            }
        }
        for (int r = 0; r < 16; r++) {
            int col = 0;
            while (col < 16) {
                char ch = g[r].charAt(col);
                int end = col;
                while (end < 16 && g[r].charAt(end) == ch) end++;
                if (ch != '.') {
                    int rgb = switch (ch) {
                        case 'H' -> th.lockLight;
                        case 'S' -> th.lockShade;
                        case 'K' -> th.key;
                        default -> th.main;
                    };
                    c.fill(lx + col * 3, ly + r * 3, lx + end * 3, ly + (r + 1) * 3, argb(0xFF, rgb));
                }
                col = end;
            }
        }
    }

    private static boolean near(String[] g, int col, int row, int d) {
        for (int r = Math.max(0, row - d); r <= Math.min(15, row + d); r++) {
            for (int k = Math.max(0, col - d); k <= Math.min(15, col + d); k++) if (g[r].charAt(k) != '.') return true;
        }
        return false;
    }

    private void drawButton(DrawContext c, Btn b, boolean hover, boolean pressed) {
        int base, hi, lo, txt;
        switch (b.style) {
            case RED -> {
                base = pressed ? 0x421B1B : hover ? 0x6E3131 : 0x5C2828;
                hi = pressed ? 0x260E0E : hover ? 0x9E4A4A : 0x873E3E;
                lo = pressed ? 0x5E2828 : hover ? 0x421A1A : 0x381616;
                txt = hover ? 0xFFFFFF : 0xFF8A8A;
            }
            case GREEN -> {
                base = pressed ? 0x1A4024 : hover ? 0x2E6E3F : 0x255933;
                hi = pressed ? 0x0E2414 : hover ? 0x49A361 : 0x3B874F;
                lo = pressed ? 0x2B633A : hover ? 0x1B4225 : 0x15361E;
                txt = hover ? 0xFFFFFF : 0x8AFFAA;
            }
            default -> {
                base = pressed ? 0x35353B : hover ? 0x575761 : 0x4A4A52;
                hi = pressed ? 0x1E1E22 : hover ? 0x82828E : 0x6E6E78;
                lo = pressed ? 0x4E4E56 : hover ? 0x333339 : 0x2B2B30;
                txt = hover ? 0xFFFFFF : 0xEAEAEA;
            }
        }
        int x = b.x + (pressed ? 1 : 0), y = b.y + (pressed ? 1 : 0), w = b.w, h = b.h;
        c.fill(x, y, x + w, y + h, 0xFF101012);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, argb(0xFF, base));
        c.fill(x + 1, y + 1, x + w - 1, y + 3, argb(0xFF, hi));
        c.fill(x + 1, y + 1, x + 3, y + h - 1, argb(0xFF, hi));
        c.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, argb(0xFF, lo));
        c.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, argb(0xFF, lo));
        c.drawCenteredTextWithShadow(this.textRenderer, label(b), x + w / 2, y + (h - 8) / 2 + 1, argb(0xFF, txt));
    }

    private static void screw(DrawContext c, int x, int y) {
        c.fill(x, y, x + 6, y + 6, 0xFF121214);
        c.fill(x + 1, y + 1, x + 5, y + 5, 0xFF3D3D43);
        c.fill(x + 1, y + 1, x + 5, y + 2, 0xFF575760);
        c.fill(x + 1, y + 1, x + 2, y + 5, 0xFF575760);
        c.fill(x + 1, y + 4, x + 5, y + 5, 0xFF222226);
        c.fill(x + 4, y + 1, x + 5, y + 5, 0xFF222226);
        c.fill(x + 1, y + 2, x + 5, y + 3, 0xFF141417);   // fenda
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    /** Retângulo com os 4 cantos cortados em 3 degraus de {@code step} px (o recorte em escada do chassi). */
    private static void oct(DrawContext c, int x, int y, int w, int h, int step, int color) {
        int s3 = step * 3;
        for (int i = 0; i < 3; i++) {
            int inset = s3 - i * step;
            c.fill(x + inset, y + i * step, x + w - inset, y + (i + 1) * step, color);
            c.fill(x + inset, y + h - (i + 1) * step, x + w - inset, y + h - i * step, color);
        }
        c.fill(x, y + s3, x + w, y + h - s3, color);
    }

    private static boolean inOct(int px, int py, int x, int y, int w, int h, int step) {
        if (px < x || px >= x + w || py < y || py >= y + h) return false;
        int s3 = step * 3;
        int dx = Math.min(px - x, x + w - 1 - px), dy = Math.min(py - y, y + h - 1 - py);
        if (dx >= s3 || dy >= s3) return true;
        return dx >= s3 - (dy / step) * step;
    }
}
