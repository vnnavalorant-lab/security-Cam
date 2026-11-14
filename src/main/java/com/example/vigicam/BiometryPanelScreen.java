package com.example.vigicam;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.block.BlockState;
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

/**
 * Painel de biometria da Porta Reforçada, baseado no "Terminal Biométrico Minecraft" HTML: o mesmo chassi octogonal em degraus,
 * parafusos, LED de 3 estados, visor com scanlines, cadeado pixel-art e barra de progresso, e embaixo o leitor de digital em
 * relevo com o sensor de vidro, a digital em pixel art que acende de cima para baixo e o laser que varre o sensor.
 * <p>
 * Funciona como o HTML:
 * <ul>
 *   <li><b>Segurar</b> o leitor (botão esquerdo do mouse, ou Espaço/Enter) por {@link #SCAN_MS} ms completos. LED, visor e cadeado ficam
 *       âmbar ("LENDO..."), a barra enche, a digital acende em ciano e sai um pulso 8-bit a cada {@link #PULSE_MS} ms, cada vez mais agudo.</li>
 *   <li><b>Soltar antes</b> do fim: som de erro, "LEITURA FALHOU", tremor do painel e, {@link #FAIL_MS} ms depois, volta ao estado trancado.</li>
 *   <li><b>Leitura completa</b>: tudo vira verde esmeralda ("ACESSO LIBERADO", "DIGITAL VALIDADA", cadeado aberto) com o arpejo de sucesso.
 *       No HTML o painel volta a trancar sozinho depois de {@link #UNLOCK_HOLD_MS} ms; aqui a porta já abriu, então é a tela que se fecha.</li>
 * </ul>
 * Quem decide é o servidor: ao completar a leitura a tela manda {@link ReinforcedDoorBlock#ACT_BIO_DONE} e só mostra "liberado" quando
 * o servidor confirma (ele só aceita do dono, com a janela de biometria aberta pelos {@link ReinforcedDoorBlockEntity#MAX_OWNER_FAILS} erros).
 * <p>
 * O desenho usa o mesmo espaço de design do {@link DoorPanelScreen} ({@link #W} px de largura, metade do HTML) e o mesmo escalonamento.
 */
@Environment(EnvType.CLIENT)
public class BiometryPanelScreen extends Screen {
    private static final int W = 166;
    private static final int BOTTOM = 259;          // fim do leitor (igual ao fim do teclado do outro painel)
    private static final int H = BOTTOM + 29;

    /** Tempos do HTML (ms). */
    private static final int SCAN_MS = 1500, UNLOCK_HOLD_MS = 3000, PULSE_MS = 135, FAIL_MS = 420, SERVER_WAIT_MS = 1500;

    /** Leitor (".mc-bio-pad") e sensor de vidro (".mc-sensor-glass", 144 px no HTML). */
    private static final int PAD_X = 14, PAD_Y = 148, PAD_W = 138, PAD_H = 111;
    private static final int SENSOR_X = 47, SENSOR_Y = 162, SENSOR = 72;
    /**
     * Digital: SVG de 112 px de largura (sm:w-28) para 20 colunas = 5,6 px por quadradinho (2,8 aqui), centrada no sensor.
     * Cadeado: o HTML pede sm:w-22, que não existe no Tailwind v3 e é ignorado, então ele fica com 80 px (w-20) = 40 aqui.
     * As duas coisas são desenhadas com escala fracionária na matriz para ficarem do tamanho exato do HTML.
     */
    private static final float FP_X = SENSOR_X + 8f, FP_Y = SENSOR_Y + 5.2f, FP_UNIT = 2.8f;
    private static final int FP_ROWS = 22;
    private static final float LOCK_X = (W - 40) / 2f, LOCK_Y = 66.25f, LOCK_SCALE = 40f / 48f;

    /** Digital em pixel art: {x, y, largura, altura} em quadradinhos, os mesmos retângulos do SVG do HTML (4 arcos + núcleo). */
    private static final int[][] FINGERPRINT = {
            // arco 1 (externo)
            {6, 1, 8, 1}, {4, 2, 2, 1}, {14, 2, 2, 1}, {3, 3, 1, 2}, {16, 3, 1, 2}, {2, 5, 1, 8}, {17, 5, 1, 7},
            {3, 13, 1, 4}, {16, 12, 1, 4}, {4, 17, 1, 2}, {15, 16, 1, 2},
            // arco 2
            {7, 3, 6, 1}, {5, 4, 2, 1}, {13, 4, 2, 1}, {4, 5, 1, 7}, {15, 5, 1, 8}, {5, 12, 1, 6}, {14, 13, 1, 5},
            {6, 18, 1, 2}, {13, 18, 1, 2},
            // arco 3
            {7, 5, 6, 1}, {6, 6, 1, 8}, {13, 6, 1, 9}, {7, 14, 1, 6}, {12, 15, 1, 5}, {8, 20, 2, 1},
            // arco 4
            {8, 7, 4, 1}, {8, 8, 1, 8}, {11, 8, 1, 9}, {9, 16, 1, 4},
            // núcleo central
            {9, 9, 2, 1}, {9, 10, 2, 5}};

    /** Cores de cada estado do painel (RGB, sem alpha): trancado (vermelho), lendo (âmbar) e liberado (verde esmeralda). */
    private enum Theme {
        //   LED/texto, LED claro, LED escuro, cadeado claro, cadeado sombra, buraco da chave, fundo do visor (de fora pra dentro)
        RED(0xFF2A2A, 0xFF7A7A, 0xA80000, 0xFF7878, 0xB80000, 0x120404, new int[]{0x0D0303, 0x150505, 0x1D0707, 0x240808}),
        AMBER(0xFFAA00, 0xFFE082, 0xB26A00, 0xFFE082, 0xB26A00, 0x120404, new int[]{0x0D0902, 0x171003, 0x211703, 0x2B1E04}),
        GREEN(0x00FF66, 0x99FFBB, 0x00993D, 0x9EFFC4, 0x009E3F, 0x04140A, new int[]{0x030D07, 0x05160C, 0x071F11, 0x092916});

        final int main, ledLight, ledDark, lockLight, lockShade, key;
        final int[] bg;

        Theme(int main, int ledLight, int ledDark, int lockLight, int lockShade, int key, int[] bg) {
            this.main = main; this.ledLight = ledLight; this.ledDark = ledDark;
            this.lockLight = lockLight; this.lockShade = lockShade; this.key = key; this.bg = bg;
        }
    }

    private final BlockPos pos;

    private boolean scanning, waiting, unlocked, holdMouse, holdKey;
    private float progress;
    private long scanStart, failStart, failUntil, closeAt, waitDeadline;
    private int pulseIdx;

    private float scale = 1f;
    private int originX, originY;

    public BiometryPanelScreen(BlockPos pos) {
        super(Text.literal("Painel de biometria"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        scale = Math.min(1f, (this.height - 8f) / H);   // this.height = altura da janela
        originX = Math.round((this.width - W * scale) / 2f);
        originY = Math.round((this.height - H * scale) / 2f);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ resposta do servidor

    /** Resultado de um pedido (tipos em {@link ReinforcedDoorBlock}). Só interessa a resposta da leitura. */
    public void onResult(int type, boolean ok) {
        if (type != ReinforcedDoorBlock.ACT_BIO_DONE || !waiting) return;
        waiting = false;
        long now = now();
        if (ok) unlock(now); else fail(now);
    }

    // ------------------------------------------------------------------ lógica da leitura

    private static long now() {
        return Util.getMeasuringTimeMs();
    }

    private static void sound(SoundEvent event) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(event, 1.0f, 1.0f));
    }

    private boolean failing(long now) {
        return failUntil > 0 && now < failUntil;
    }

    /** Começa a leitura ao pressionar e segurar (startBiometricScan do HTML). */
    private void startScan(long now) {
        if (unlocked || scanning || waiting) return;
        scanning = true;
        progress = 0f;
        scanStart = now;
        pulseIdx = 0;
        failStart = 0;
        failUntil = 0;
        update(now);   // o primeiro pulso toca na hora
    }

    /** Soltou antes de completar (cancelBiometricScan do HTML). */
    private void cancelScan() {
        if (!scanning || unlocked) return;
        scanning = false;
        fail(now());
    }

    private void fail(long now) {
        sound(ModSounds.BIO_FAIL);
        failStart = now;
        failUntil = now + FAIL_MS;
    }

    /** 1,5 s completos: pede ao servidor para liberar (completeBiometricUnlock do HTML, só que quem confirma é o servidor). */
    private void completeScan(long now) {
        scanning = false;
        progress = 1f;
        waiting = true;
        waitDeadline = now + SERVER_WAIT_MS;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(ReinforcedDoorBlock.ACT_BIO_DONE);
        buf.writeBlockPos(pos);
        buf.writeString("");
        ClientPlayNetworking.send(VigiCam.DOOR_ACTION, buf);
    }

    private void unlock(long now) {
        unlocked = true;
        progress = 1f;
        sound(ModSounds.BIO_SUCCESS);
        closeAt = now + UNLOCK_HOLD_MS;
    }

    /** Volta ao estado TRANCADO padrão (setLockedState do HTML). */
    private void resetLocked() {
        scanning = false;
        waiting = false;
        unlocked = false;
        progress = 0f;
        failStart = 0;
        failUntil = 0;
    }

    /** Avança o relógio: progresso, pulsos sonoros, fim da leitura, fim do erro e fechamento. Chamado a cada tick e a cada quadro. */
    private void update(long now) {
        if (scanning) {
            progress = Math.min((now - scanStart) / (float) SCAN_MS, 1f);
            int due = Math.min((int) ((now - scanStart) / PULSE_MS), ModSounds.BIO_PULSES - 1);
            if (due >= pulseIdx) {   // um pulso a cada 135 ms, com o tom do progresso (se travar, toca só o mais recente)
                sound(ModSounds.BIO_SCAN[due]);
                pulseIdx = due + 1;
            }
            if (progress >= 1f) completeScan(now);
        }
        if (waiting && now > waitDeadline) {   // o servidor não respondeu
            waiting = false;
            fail(now);
        }
        if (failUntil > 0 && now >= failUntil) {
            failUntil = 0;
            if (!scanning && !waiting && !unlocked) resetLocked();
        }
        if (unlocked && now >= closeAt) close();
    }

    @Override
    public void tick() {
        long now = now();
        update(now);
        // soltou o botão fora da janela (ou o foco foi embora): conta como soltar
        if (holdMouse && scanning) {
            long window = MinecraftClient.getInstance().getWindow().getHandle();
            if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                holdMouse = false;
                cancelScan();
            }
        }
        // a porta abriu por outro caminho (ou sumiu): não há mais o que ler
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!unlocked && mc.world != null) {
            BlockState s = mc.world.getBlockState(pos);
            if (!s.isOf(VigiCam.REINFORCED_DOOR) || s.get(ReinforcedDoorBlock.OPEN)) close();
        }
    }

    // ------------------------------------------------------------------ entrada

    private static boolean inReader(double x, double y) {
        return x >= PAD_X && x < PAD_X + PAD_W && y >= PAD_Y && y < PAD_Y + PAD_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && inReader((mouseX - originX) / scale, (mouseY - originY) / scale)) {
            holdMouse = true;
            startScan(now());
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && holdMouse) {
            holdMouse = false;
            cancelScan();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            holdKey = true;   // segurar Espaço/Enter também lê (o repeat do teclado só chama startScan de novo, que ignora)
            startScan(now());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && holdKey) {
            holdKey = false;
            cancelScan();
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ estado visual

    private Theme theme(long now) {
        if (unlocked) return Theme.GREEN;
        if (scanning || waiting || failing(now)) return Theme.AMBER;   // na falha o HTML mantém o visual de leitura por 420 ms
        return Theme.RED;
    }

    private String status(long now) {
        if (unlocked) return "ACESSO LIBERADO";
        if (failing(now)) return "LEITURA FALHOU";
        if (scanning || waiting) return "LENDO...";
        return "TRANCADO";
    }

    private int statusColor(long now, Theme th) {
        return failing(now) ? Theme.RED.main : th.main;   // "LEITURA FALHOU" é vermelho em cima do visor âmbar
    }

    private String hint(long now) {
        if (unlocked) return "DIGITAL VALIDADA";
        if (scanning || waiting || failing(now)) return "LENDO DIGITAL...";   // o HTML só troca o texto ao resetar
        return "SEGURE PARA LER";
    }

    private int hintColor(long now) {
        if (unlocked) return 0x00FF66;
        if (scanning || waiting || failing(now)) return 0x00E5FF;
        return 0xB8BBC4;
    }

    /** Tremor em degraus de pixel ao falhar a leitura (pixelShake: 0 -> -6 -> 6 -> -4 -> 0 px no HTML, metade aqui). */
    private int shake(long now) {
        if (failStart == 0) return 0;
        long t = now - failStart;
        if (t < 70) return -3;
        if (t < 140) return 3;
        if (t < 210) return -2;
        return 0;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        this.renderBackground(c);
        long now = now();
        update(now);
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(originX + shake(now) * scale, originY, 0);
        m.scale(scale, scale, 1f);
        drawPanel(c, now, (mouseX - originX) / scale, (mouseY - originY) / scale);
        m.pop();
    }

    private void drawPanel(DrawContext c, long now, double mx, double my) {
        Theme th = theme(now);

        // chassi: sombra, contorno, bisel e corpo (os mesmos octógonos em degraus do painel de senha)
        DoorPanelScreen.oct(c, 0, 7, W, H, DoorPanelScreen.OUTER, 0x47000000);
        DoorPanelScreen.oct(c, 0, 0, W, H, DoorPanelScreen.OUTER, 0xFF141417);
        DoorPanelScreen.oct(c, 2, 2, W - 4, H - 4, DoorPanelScreen.BEVEL, 0xFF3A3A40);
        int bx = 4, by = 4, bw = W - 8, bh = H - 8;
        DoorPanelScreen.oct(c, bx, by, bw, bh, DoorPanelScreen.BODY, 0xFF28282C);
        DoorPanelScreen.clipFill(c, bx, by, bw, bh, DoorPanelScreen.BODY, bx, by, bx + bw, by + 2, 0xFF45454C);
        DoorPanelScreen.clipFill(c, bx, by, bw, bh, DoorPanelScreen.BODY, bx, by, bx + 2, by + bh, 0xFF45454C);
        DoorPanelScreen.clipFill(c, bx, by, bw, bh, DoorPanelScreen.BODY, bx, by + bh - 2, bx + bw, by + bh, 0xFF161618);
        DoorPanelScreen.clipFill(c, bx, by, bw, bh, DoorPanelScreen.BODY, bx + bw - 2, by, bx + bw, by + bh, 0xFF161618);

        // topo: parafusos e LED
        DoorPanelScreen.screw(c, 18, 17);
        DoorPanelScreen.screw(c, W - 23, 17);
        drawLed(c, th);

        drawVisor(c, now, th);
        drawReader(c, now, !scanning && inReader(mx, my));

        int sy = BOTTOM + 8;
        DoorPanelScreen.screw(c, 20, sy);
        DoorPanelScreen.screw(c, W - 25, sy);
    }

    private void drawLed(DrawContext c, Theme th) {
        int sx = 66, sy = 14, sw = 34, sh = 12;
        c.fill(sx + 1, sy + 1, sx + sw + 1, sy + sh + 1, 0xFF3C3C42);       // sombra dura embaixo/direita
        c.fill(sx, sy, sx + sw, sy + sh, 0xFF0E0E10);                       // borda
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + sh - 2, 0xFF121215);
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + 3, 0xFF08080A);            // sombra interna em cima/esquerda
        c.fill(sx + 2, sy + 2, sx + 3, sy + sh - 2, 0xFF08080A);

        int x0 = 69, y0 = 17, x1 = 97, y1 = 23;                             // barra do LED
        c.fill(x0 - 6, y0 - 6, x1 + 6, y1 + 6, DoorPanelScreen.argb(0x10, th.main));   // brilho em camadas
        c.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, DoorPanelScreen.argb(0x14, th.main));
        c.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, DoorPanelScreen.argb(0x26, th.main));
        c.fill(x0, y0, x1, y1, DoorPanelScreen.argb(0xFF, th.main));
        c.fill(x0, y0, x1, y0 + 1, DoorPanelScreen.argb(0xFF, th.ledLight));
        c.fill(x0, y1 - 1, x1, y1, DoorPanelScreen.argb(0xFF, th.ledDark));
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

        // tela (o brilho vermelho de erro do HTML fica escondido atrás do gradiente radial, então não há piscada)
        int x0 = 18, y0 = 38, x1 = 148, y1 = 134;
        c.fill(x0, y0, x1, y1, 0xFF09090B);
        x0++; y0++; x1--; y1--;
        int[] insetX = {0, 8, 20, 34}, insetTop = {0, 5, 13, 21}, insetBot = {0, 7, 17, 29};   // gradiente radial em degraus
        for (int i = 0; i < 4; i++) {
            c.fill(x0 + insetX[i], y0 + insetTop[i], x1 - insetX[i], y1 - insetBot[i], DoorPanelScreen.argb(0xFF, th.bg[i]));
        }

        // conteúdo: status, cadeado e barra de progresso
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(LOCK_X, LOCK_Y, 0);
        m.scale(LOCK_SCALE, LOCK_SCALE, 1f);   // o desenho do cadeado é em blocos de 3 px (48 no total); aqui ele fica com 40
        DoorPanelScreen.drawLock(c, unlocked ? DoorPanelScreen.LOCK_OPEN : DoorPanelScreen.LOCK_CLOSED,
                th.main, th.lockLight, th.lockShade, th.key, 0, 0);
        m.pop();
        c.drawCenteredTextWithShadow(this.textRenderer, status(now), W / 2, 45, DoorPanelScreen.argb(0xFF, statusColor(now, th)));
        drawProgress(c);

        // scanlines por cima de tudo (::after com z-index 20 no HTML)
        for (int y = y0; y < y1; y += 2) c.fill(x0, y + 1, x1, y + 2, 0x4D000000);
    }

    /** Barra de progresso pixelada abaixo do cadeado (.mc-progress-box + .mc-progress-fill). */
    private void drawProgress(DrawContext c) {
        int bx = 29, by = 120, bw = 108, bh = 7;
        c.fill(bx, by, bx + bw, by + bh, 0xFF3A3A40);                   // borda
        c.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, 0xFF0B0B0D);
        c.fill(bx + 1, by + 1, bx + bw - 1, by + 2, 0xFF050506);        // sombra interna em cima/esquerda
        c.fill(bx + 1, by + 1, bx + 2, by + bh - 1, 0xFF050506);

        int innerX = bx + 2, innerY = by + 2, innerW = bw - 4, innerH = bh - 4;
        int fillW = innerW * (int) (progress * 100f) / 100;             // largura em % inteiros, como no HTML
        if (fillW <= 0) return;
        int rgb = unlocked ? 0x00FF66 : (scanning || waiting || failUntil > 0) ? 0x00E5FF : 0xFF2A2A;
        c.fill(innerX, innerY, innerX + fillW, innerY + innerH, DoorPanelScreen.argb(0xFF, rgb));
        c.fill(innerX, innerY, innerX + fillW, innerY + 1, 0x59FFFFFF);   // brilho em cima (rgba branco 35%)
    }

    /** Leitor biométrico em relevo escuro texturizado, com o sensor, a digital, o laser e a instrução embaixo. */
    private void drawReader(DrawContext c, long now, boolean hover) {
        boolean hold = scanning;   // .active-hold: só enquanto segura
        int x = PAD_X, y = PAD_Y, w = PAD_W, h = PAD_H;

        c.fill(x, y, x + w, y + h, 0xFF101012);                         // borda
        int ix = x + 2, iy = y + 2, iw = w - 4, ih = h - 4;
        int bg = hold ? 0x181A1F : hover ? 0x25252B : 0x1F1F24;
        c.fill(ix, iy, ix + iw, iy + ih, DoorPanelScreen.argb(0xFF, bg));
        for (int cy = 0; cy * 3 < ih; cy++) {                           // textura xadrez (os triângulos de 6 px do HTML, em 3 px)
            for (int cx = (cy & 1); cx * 3 < iw; cx += 2) {
                int x0 = ix + cx * 3, y0 = iy + cy * 3;
                c.fill(x0, y0, Math.min(x0 + 3, ix + iw), Math.min(y0 + 3, iy + ih), 0x2E000000);
            }
        }
        // relevo (inset): em cima/esquerda por cima de embaixo/direita; apertado enquanto segura
        int tl = hold ? 0x0D0E12 : hover ? 0x484852 : 0x383840;
        int br = hold ? 0x2F333D : hover ? 0x151518 : 0x121215;
        int brw = hold ? 1 : 2;
        c.fill(ix, iy + ih - brw, ix + iw, iy + ih, DoorPanelScreen.argb(0xFF, br));
        c.fill(ix + iw - brw, iy, ix + iw, iy + ih, DoorPanelScreen.argb(0xFF, br));
        c.fill(ix, iy, ix + iw, iy + 2, DoorPanelScreen.argb(0xFF, tl));
        c.fill(ix, iy, ix + 2, iy + ih, DoorPanelScreen.argb(0xFF, tl));

        drawSensor(c);

        c.drawCenteredTextWithShadow(this.textRenderer, hint(now), W / 2, PAD_Y + 96, DoorPanelScreen.argb(0xFF, hintColor(now)));
    }

    private void drawSensor(DrawContext c) {
        int sx = SENSOR_X, sy = SENSOR_Y, s = SENSOR;
        c.fill(sx, sy, sx + s, sy + s, 0xFF141418);                     // borda
        int ix = sx + 2, iy = sy + 2, is = s - 4;                       // área interna 68 x 68
        c.fill(ix, iy, ix + is, iy + is, 0xFF0D0E11);
        c.fill(ix, iy + is - 1, ix + is, iy + is, 0xFF2B2B32);          // relevo recuado
        c.fill(ix + is - 1, iy, ix + is, iy + is, 0xFF2B2B32);
        c.fill(ix, iy, ix + is, iy + 2, 0xFF060608);
        c.fill(ix, iy, ix + 2, iy + is, 0xFF060608);

        drawFingerprint(c);
        if (scanning) drawLaser(c, ix, iy, is);
    }

    /** Digital: camada base cinza e, por cima, a camada acesa revelada de cima para baixo conforme o progresso (clip-path suave). */
    private void drawFingerprint(DrawContext c) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(FP_X, FP_Y, 0);
        m.scale(FP_UNIT, FP_UNIT, 1f);   // daqui em diante 1 unidade = 1 quadradinho da digital
        for (int[] r : FINGERPRINT) c.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF3B3E47);

        float reveal = progress * FP_ROWS;   // altura já iluminada, em quadradinhos (fracionária)
        int lit = unlocked ? 0xFF00FF66 : 0xFF00F0FF;
        for (int[] r : FINGERPRINT) {
            float visible = Math.max(0f, Math.min(reveal - r[1], r[3]));
            if (visible <= 0f) continue;
            m.push();
            m.translate(r[0], r[1], 0);
            m.scale(1f, visible / r[3], 1f);   // só a parte de cima do retângulo que o laser já passou
            c.fill(0, 0, r[2], r[3], lit);
            m.pop();
        }
        m.pop();
    }

    /** Feixe laser vermelho-ciano-vermelho que sobe e desce 1,5 ciclo pelo sensor, com brilho ciano em cima e vermelho embaixo. */
    private void drawLaser(DrawContext c, int ix, int iy, int is) {
        float wave = (float) Math.sin(progress * Math.PI * 3);
        int by = iy + Math.round(((wave + 1f) / 2f) * 94f / 100f * is);

        // brilho (clipado na área do sensor, como o overflow: hidden do HTML)
        int[] alpha = {0x66, 0x44, 0x28, 0x14};
        for (int i = 0; i < alpha.length; i++) {
            fillIn(c, ix, iy, is, by - 1 - i, by - i, DoorPanelScreen.argb(alpha[i], 0x00F0FF));
            fillIn(c, ix, iy, is, by + 2 + i, by + 3 + i, DoorPanelScreen.argb(alpha[i], 0xFF2A2A));
        }
        // feixe: gradiente horizontal #FF2A2A -> #00F0FF (meio) -> #FF2A2A
        for (int i = 0; i < is; i++) {
            float t = Math.abs(i / (float) (is - 1) - 0.5f) * 2f;
            int r = Math.round(0x00 + (0xFF - 0x00) * t), g = Math.round(0xF0 + (0x2A - 0xF0) * t), b = Math.round(0xFF + (0x2A - 0xFF) * t);
            c.fill(ix + i, by, ix + i + 1, by + 2, 0xFF000000 | (r << 16) | (g << 8) | b);
        }
    }

    /** Preenche a faixa horizontal [y0, y1) só dentro do quadrado do sensor. */
    private static void fillIn(DrawContext c, int ix, int iy, int is, int y0, int y1, int color) {
        y0 = Math.max(y0, iy);
        y1 = Math.min(y1, iy + is);
        if (y1 > y0) c.fill(ix, y0, ix + is, y1, color);
    }
}
