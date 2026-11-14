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
 * Painel de acesso da Porta Reforçada, baseado no "Painel de Acesso Industrial" HTML: chassi octogonal em degraus (pixel art),
 * parafusos, LED, visor com scanlines e cadeado, teclado numérico 3x4 em "pedra" e os sons 8-bit
 * (tecla, limpar, erro, destravar - ver {@link ModSounds}).
 * <p>
 * Todo o desenho é feito num espaço de design de {@link #W} px de largura (metade do HTML original) e escalado para caber na tela.
 * <ul>
 *   <li>Qualquer jogador, dono inclusive: digita a senha ({@link ReinforcedDoorBlockEntity#PIN_LENGTH} dígitos). Só a senha
 *       cadastrada abre; quem decide é o servidor.</li>
 *   <li>Dono: vê o botão extra [SENHA] (troca a senha). Os outros jogadores nem recebem esse botão.</li>
 *   <li>Dono que erra {@link ReinforcedDoorBlockEntity#MAX_OWNER_FAILS} vezes seguidas: o servidor avisa
 *       ({@link ReinforcedDoorBlock#ACT_BIOMETRY}) e este painel dá lugar ao {@link BiometryPanelScreen} (leitor de digital).</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class DoorPanelScreen extends Screen {
    private static final int W = 166;
    private static final int BASE_BOTTOM = 259;     // fim do teclado
    private static final int OWNER_BOTTOM = 280;    // fim da barra extra do dono
    private static final int PIN = ReinforcedDoorBlockEntity.PIN_LENGTH;

    /** Perfis dos recortes em escada: pares {até a linha (distância da borda), recuo}. Medidas do clip-path do HTML, na metade. */
    static final int[] OUTER = {4, 12, 8, 8, 12, 4};
    static final int[] BEVEL = {4, 11, 7, 7, 11, 4};
    static final int[] BODY = {3, 10, 6, 6, 10, 3};

    private enum Mode { NORMAL, CHANGE }

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

    /** Cadeado em pixel art 16x16 (A = cor, H = brilho, S = sombra, K = buraco da chave). Os mesmos 16x16 do SVG do HTML. */
    static final String[] LOCK_CLOSED = {
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
    static final String[] LOCK_OPEN = {
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
    private final int panelH;   // altura do painel (o field "height" da Screen é a da janela); o do dono tem a barra extra
    private final int bottom;   // fim do último botão
    private final List<Btn> buttons = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();

    private Mode mode = Mode.NORMAL;
    private boolean unlocked, waiting;
    private long validateAt, errStart, errUntil, savedUntil, closeAt, waitDeadline, pressedUntil;
    private String pressedId = "";

    private float scale = 1f;
    private int originX, originY;

    public DoorPanelScreen(BlockPos pos, boolean owner) {
        super(Text.literal("Painel de acesso"));
        this.pos = pos;
        this.owner = owner;
        this.bottom = owner ? OWNER_BOTTOM : BASE_BOTTOM;
        this.panelH = bottom + 29;

        int[] cols = {14, 62, 110};
        String[] rows = {"123", "456", "789"};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) buttons.add(new Btn(cols[c], 148 + r * 29, 42, 24, rows[r].substring(c, c + 1), Style.STONE));
        }
        buttons.add(new Btn(cols[0], 235, 42, 24, "clear", Style.RED));
        buttons.add(new Btn(cols[1], 235, 42, 24, "0", Style.STONE));
        buttons.add(new Btn(cols[2], 235, 42, 24, "enter", Style.GREEN));
        if (owner) buttons.add(new Btn(14, 264, 138, 16, "change", Style.STONE));   // só o dono recebe este botão
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
        if (type == ReinforcedDoorBlock.ACT_BIOMETRY) {
            // o dono errou demais: o teclado dá lugar ao leitor de digital (o servidor já abriu a janela de biometria)
            if (ok) client().setScreen(new BiometryPanelScreen(pos));
        } else if (type == ReinforcedDoorBlock.ACT_SET_PIN) {
            if (ok) {
                sound(ModSounds.DOOR_UNLOCK);
                savedUntil = now + 1300;
                input.setLength(0);
                mode = Mode.NORMAL;
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

    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    private static void sound(SoundEvent event) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(event, 1.0f, 1.0f));
    }

    /** Painel travado: a porta já abriu. */
    private boolean locked() {
        return unlocked;
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
        if (locked() || waiting || input.length() >= PIN || errActive(now())) return;
        sound(ModSounds.DOOR_KEY);
        input.append(d);
        if (mode != Mode.CHANGE && input.length() == PIN) validateAt = now() + 180;   // confere sozinho no último dígito
    }

    private void clear() {
        if (locked()) return;
        sound(ModSounds.DOOR_CLEAR);
        input.setLength(0);
        validateAt = 0;
    }

    private void enter() {
        if (locked() || waiting) return;
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
            case "change" -> {
                if (locked() || !owner) return;
                sound(ModSounds.DOOR_KEY);
                input.setLength(0);
                validateAt = 0;
                mode = mode == Mode.CHANGE ? Mode.NORMAL : Mode.CHANGE;
            }
            default -> digit(id.charAt(0));
        }
    }

    @Override
    public void tick() {
        long now = now();
        if (validateAt > 0 && now >= validateAt) {
            validateAt = 0;
            if (mode != Mode.CHANGE && input.length() == PIN && !locked() && !waiting) send(ReinforcedDoorBlock.ACT_TRY_PIN, input.toString());
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
                if (!locked() && !waiting && input.length() > 0) {
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
        return Theme.RED;
    }

    private String status(long now) {
        if (unlocked) return "ACESSO LIBERADO";
        if (errActive(now)) return "ACESSO NEGADO";
        if (now < savedUntil) return "SENHA SALVA";
        if (mode == Mode.CHANGE) return "NOVA SENHA";
        return "TRANCADO";
    }

    /** Cadeado aberto: liberado, senha salva ou modo de troca de senha. No erro continua fechado. */
    private boolean lockOpen(long now) {
        if (unlocked) return true;
        if (errActive(now)) return false;
        return now < savedUntil || mode == Mode.CHANGE;
    }

    private String label(Btn b) {
        return switch (b.id) {
            case "clear" -> "X";
            case "enter" -> "OK";
            case "change" -> mode == Mode.CHANGE ? "VOLTAR" : "TROCAR SENHA";
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

        // chassi: sombra, contorno, bisel e corpo "limpo" (todos octogonais em degraus, como no clip-path do HTML)
        oct(c, 0, 7, W, panelH, OUTER, 0x47000000);
        oct(c, 0, 0, W, panelH, OUTER, 0xFF141417);
        oct(c, 2, 2, W - 4, panelH - 4, BEVEL, 0xFF3A3A40);
        int bx = 4, by = 4, bw = W - 8, bh = panelH - 8;
        oct(c, bx, by, bw, bh, BODY, 0xFF28282C);
        // borda 3D do corpo (inset shadow): claro em cima/esquerda, escuro embaixo/direita
        clipFill(c, bx, by, bw, bh, BODY, bx, by, bx + bw, by + 2, 0xFF45454C);
        clipFill(c, bx, by, bw, bh, BODY, bx, by, bx + 2, by + bh, 0xFF45454C);
        clipFill(c, bx, by, bw, bh, BODY, bx, by + bh - 2, bx + bw, by + bh, 0xFF161618);
        clipFill(c, bx, by, bw, bh, BODY, bx + bw - 2, by, bx + bw, by + bh, 0xFF161618);

        // topo: parafusos e LED
        screw(c, 18, 17);
        screw(c, W - 23, 17);
        drawLed(c, th);

        drawVisor(c, now, th);

        for (Btn b : buttons) {
            boolean pressed = b.id.equals(pressedId) && now < pressedUntil;
            drawButton(c, b, !locked() && b.contains(mx, my), pressed);
        }

        int sy = bottom + 8;
        screw(c, 20, sy);
        screw(c, W - 25, sy);
    }

    private void drawLed(DrawContext c, Theme th) {
        int sx = 66, sy = 14, sw = 34, sh = 12;
        c.fill(sx + 1, sy + 1, sx + sw + 1, sy + sh + 1, 0xFF3C3C42);       // sombra dura embaixo/direita
        c.fill(sx, sy, sx + sw, sy + sh, 0xFF0E0E10);                       // borda
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + sh - 2, 0xFF121215);
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + 3, 0xFF08080A);            // sombra interna em cima/esquerda
        c.fill(sx + 2, sy + 2, sx + 3, sy + sh - 2, 0xFF08080A);

        int x0 = 69, y0 = 17, x1 = 97, y1 = 23;                             // barra do LED
        c.fill(x0 - 6, y0 - 6, x1 + 6, y1 + 6, argb(0x10, th.main));        // brilho em camadas
        c.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, argb(0x14, th.main));
        c.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, argb(0x26, th.main));
        c.fill(x0, y0, x1, y1, argb(0xFF, th.main));
        c.fill(x0, y0, x1, y0 + 1, argb(0xFF, th.ledLight));
        c.fill(x0, y1 - 1, x1, y1, argb(0xFF, th.ledDark));
    }

    private void drawVisor(DrawContext c, long now, Theme th) {
        // moldura recuada (slot de inventário)
        int fx = 14, fy = 34, fw = 138, fh = 104;
        c.fill(fx, fy, fx + fw, fy + fh, 0xFF0E0E10);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF18181B);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + 4, 0xFF0B0B0D);
        c.fill(fx + 2, fy + 2, fx + 4, fy + fh - 2, 0xFF0B0B0D);
        c.fill(fx + 2, fy + fh - 4, fx + fw - 2, fy + fh - 2, 0xFF38383E);
        c.fill(fx + fw - 4, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF38383E);

        // tela
        int x0 = 18, y0 = 38, x1 = 148, y1 = 134;
        c.fill(x0, y0, x1, y1, 0xFF09090B);
        x0++; y0++; x1--; y1--;
        boolean flash = errActive(now) && ((now - errStart) / 90) % 2 == 1;   // piscada vermelha ao errar
        if (flash) {
            c.fill(x0, y0, x1, y1, 0xFF4A0808);
        } else {
            // brilho central (gradiente radial em degraus, um pouco acima do meio como no HTML)
            int[] insetX = {0, 8, 20, 34}, insetTop = {0, 5, 13, 21}, insetBot = {0, 7, 17, 29};
            for (int i = 0; i < 4; i++) {
                c.fill(x0 + insetX[i], y0 + insetTop[i], x1 - insetX[i], y1 - insetBot[i], argb(0xFF, th.bg[i]));
            }
        }

        // scanlines (por baixo do cadeado e dos textos, como no HTML)
        for (int y = y0; y < y1; y += 2) c.fill(x0, y + 1, x1, y + 2, 0x52000000);

        drawLock(c, lockOpen(now), th, (W - 48) / 2, 62);

        c.drawCenteredTextWithShadow(this.textRenderer, status(now), W / 2, 45, argb(0xFF, th.main));
        c.drawCenteredTextWithShadow(this.textRenderer, pinText(now), W / 2, 118, argb(0xFF, th.text));
    }

    private String pinText(long now) {
        int filled = input.length();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PIN; i++) {
            if (i > 0) sb.append(' ');
            if (unlocked) sb.append('*');
            else if (i < filled) sb.append(mode == Mode.CHANGE ? input.charAt(i) : '*');   // ao trocar a senha o dono vê o que digita
            else sb.append('_');
        }
        return sb.toString();
    }

    private void drawLock(DrawContext c, boolean open, Theme th, int lx, int ly) {
        drawLock(c, open ? LOCK_OPEN : LOCK_CLOSED, th.main, th.lockLight, th.lockShade, th.key, lx, ly);
    }

    /** Cadeado 16x16 (3 px por quadradinho) com o brilho em 2 camadas. Cores RGB: cor, brilho, sombra e buraco da chave. */
    static void drawLock(DrawContext c, String[] g, int main, int light, int shade, int key, int lx, int ly) {
        for (int layer = 2; layer >= 1; layer--) {   // brilho: o desenho "engordado" em 2 camadas translúcidas
            int color = argb(layer == 2 ? 0x12 : 0x1C, main);
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
                        case 'H' -> light;
                        case 'S' -> shade;
                        case 'K' -> key;
                        default -> main;
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

    /** Parafuso quadrado 5x5 (borda #38383E, miolo #1A1A1D), como o .mc-screw do HTML. */
    static void screw(DrawContext c, int x, int y) {
        c.fill(x, y, x + 5, y + 5, 0xFF38383E);
        c.fill(x + 1, y + 1, x + 4, y + 4, 0xFF1A1A1D);
    }

    static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------ formas em escada

    /** Recuo (em px) da escada na linha que está a {@code d} px da borda de cima ou de baixo. */
    private static int insetAt(int[] profile, int d) {
        for (int i = 0; i < profile.length; i += 2) if (d < profile[i]) return profile[i + 1];
        return 0;
    }

    /** Retângulo com os 4 cantos cortados em escada (o clip-path octogonal do chassi). */
    static void oct(DrawContext c, int x, int y, int w, int h, int[] profile, int color) {
        clipFill(c, x, y, w, h, profile, x, y, x + w, y + h, color);
    }

    /** Preenche o retângulo {@code [rx0,rx1) x [ry0,ry1)} apenas onde ele cai dentro da forma em escada (x, y, w, h, profile). */
    static void clipFill(DrawContext c, int x, int y, int w, int h, int[] profile,
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
            if (open && (!has || x0 != runX0 || x1 != runX1)) {   // fecha o trecho de linhas iguais
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
