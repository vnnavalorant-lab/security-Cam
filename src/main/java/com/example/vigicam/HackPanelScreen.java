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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Painel do Pendrive Hacker: o "Terminal de Antena - Puzzle USB" (HTML) dentro do jogo.
 * <p>
 * Tudo segue o HTML: chassi octogonal em degraus, parafusos, LED (vermelho / âmbar-ciano piscando / verde), visor CRT com
 * scanlines e a antena em pixel art (vermelha = bloqueada, verde = hackeada), botão de pedra (AGUARDANDO USB / TRAVAR SINAL /
 * EJETAR USB), porta USB-PORT e o pendrive verde embaixo, que se arrasta (ou se clica) até a porta. Encaixado, o visor vira
 * o puzzle "QUEBRA DE SINAL": 3 estágios, o cursor oscila e é preciso travar o sinal dentro da zona ciano (mais rápido e zona mais
 * estreita a cada estágio). Errar treme o chassi, pisca o visor e volta ao estágio 1. Sons: ver {@link ModSounds} (HACK_*).
 * <p>
 * O desenho usa um espaço de {@link #W} x {@link #TOTAL_H} (metade dos pixels do HTML) escalado para caber na tela. O puzzle é
 * jogado aqui, mas quem concede o acesso é o servidor ({@link HackManager}): o painel só relata inserir / etapa / erro / ejetar
 * e espera a resposta ({@link #onResult}) antes de mostrar "ANTENA HACKEADA".
 */
@Environment(EnvType.CLIENT)
public class HackPanelScreen extends Screen {
    private static final int W = 168;
    private static final int PANEL_H = 236;
    private static final int TOTAL_H = 284;     // painel + vão + pendrive
    private static final int BOTTOM = 207;      // fim da porta USB (para os parafusos de baixo)

    /** Perfis dos recortes em escada (iguais aos do painel da porta). */
    private static final int[] OUTER = {4, 12, 8, 8, 12, 4};
    private static final int[] BEVEL = {4, 11, 7, 7, 11, 4};
    private static final int[] BODY = {3, 10, 6, 6, 10, 3};

    // dificuldade progressiva dos 3 estágios (STAGE_CONFIGS do HTML): velocidade em %/s e largura da zona alvo em %
    private static final float[] SPEED = {0f, 68f, 102f, 140f};
    private static final float[] TARGET_W = {0f, 28f, 20f, 14f};

    private enum State { LOCKED, HACKING, WAITING, HACKED }

    // ------------------------------------------------------------------ geometria (espaço de design)
    private static final int BTN_X = 14, BTN_Y = 146, BTN_W = W - 28, BTN_H = 24;
    private static final int PORT_X = (W - 56) / 2, PORT_Y = 189, PORT_W = 56, PORT_H = 18;
    /** Pendrive: grade 16x22 do SVG do HTML, 1,75 px por célula (o SVG tem 56 px de largura = 28 aqui). */
    private static final float PEN_CELL = 1.75f;
    private static final float PEN_W = 16 * PEN_CELL, PEN_H = 22 * PEN_CELL;
    private static final float REST_X = (W - PEN_W) / 2f, REST_Y = PANEL_H + 9;
    /** Encaixado: os 20 px de cima (conector) entram no meio da porta. */
    private static final float DOCK_Y = PORT_Y + PORT_H / 2f - 5.5f;
    private static final int TRACK_X = 24, TRACK_Y = 84, TRACK_W = W - 48, TRACK_H = 18;

    // pendrive em pixel art (SVG do HTML): {x, y, w, h, cor}; cor 0 = LED do pendrive (muda de cor)
    private static final int[][] PEN = {
            {4, 0, 8, 1, 0x101012}, {3, 1, 1, 5, 0x101012}, {12, 1, 1, 5, 0x101012},                       // contorno do conector
            {4, 1, 8, 5, 0xAEB4BE}, {4, 1, 8, 1, 0xE2E6EC}, {4, 1, 1, 5, 0xE2E6EC}, {11, 1, 1, 5, 0x787E87},  // conector prateado
            {5, 2, 2, 2, 0x232529}, {9, 2, 2, 2, 0x232529},
            {2, 6, 12, 1, 0x101012}, {1, 7, 1, 13, 0x101012}, {14, 7, 1, 13, 0x101012}, {2, 20, 12, 1, 0x101012}, {5, 21, 6, 1, 0x101012},  // contorno do corpo
            {2, 7, 12, 13, 0x14532D}, {2, 7, 12, 1, 0x22C55E}, {2, 7, 1, 13, 0x22C55E}, {13, 7, 1, 13, 0x0A2E18}, {2, 19, 12, 1, 0x0A2E18},  // corpo verde
            {3, 9, 2, 8, 0x00C853}, {11, 9, 2, 8, 0x00C853},                                                // faixas laterais
            {7, 9, 2, 2, 0x00FF66}, {6, 11, 4, 1, 0x00FF66}, {7, 12, 2, 2, 0x00FF66},                      // raio
            {6, 16, 4, 2, 0x0A1A10}, {7, 16, 2, 2, 0},                                                      // LED
            {6, 20, 4, 1, 0x15803D}};

    // antena em pixel art (SVG 24x22): {x, y, w, h, papel}
    private static final int WAVE_IN = 0, WAVE_OUT = 1, MAIN = 2, LIGHT = 3, DARK = 4;
    private static final int[][] ANT = {
            {6, 3, 1, 1, WAVE_IN}, {5, 4, 1, 4, WAVE_IN}, {6, 8, 1, 1, WAVE_IN},
            {17, 3, 1, 1, WAVE_IN}, {18, 4, 1, 4, WAVE_IN}, {17, 8, 1, 1, WAVE_IN},
            {3, 1, 1, 1, WAVE_OUT}, {2, 2, 1, 2, WAVE_OUT}, {1, 4, 1, 4, WAVE_OUT}, {2, 8, 1, 2, WAVE_OUT}, {3, 10, 1, 1, WAVE_OUT},
            {20, 1, 1, 1, WAVE_OUT}, {21, 2, 1, 2, WAVE_OUT}, {22, 4, 1, 4, WAVE_OUT}, {21, 8, 1, 2, WAVE_OUT}, {20, 10, 1, 1, WAVE_OUT},
            {11, 2, 2, 2, LIGHT}, {11, 4, 2, 6, MAIN},
            {8, 8, 8, 1, LIGHT}, {9, 9, 6, 2, MAIN},
            {10, 11, 4, 2, DARK}, {9, 13, 2, 3, MAIN}, {13, 13, 2, 3, MAIN}, {11, 14, 2, 1, LIGHT},
            {8, 16, 2, 4, MAIN}, {14, 16, 2, 4, MAIN}, {10, 17, 4, 1, DARK},
            {6, 20, 12, 2, MAIN}};
    private static final int ANT_CELL = 2, ANT_X = (W - 24 * ANT_CELL) / 2, ANT_Y = 62;

    private static final String[] CODE_TOKENS = {"0", "1", "01", "10", "0x", "FF", "#", "$", "A7", "11"};

    private record Particle(long born, float x, float y, String text, float drift) {}

    private final BlockPos pos;
    private final List<Particle> particles = new ArrayList<>();

    // estado do terminal
    private State state = State.LOCKED;
    private boolean sessionOpen;            // o servidor tem um puzzle aberto para mim (precisa de EJECT ao fechar)
    private boolean refused;                // o servidor recusou
    private long closeAt;
    private int stage = 1;
    private boolean failCooldown;
    private long failStart, failUntil, waitDeadline;

    // puzzle
    private float cursorPos, targetLeft = 36, targetWidth = 28;
    private int cursorDir = 1, lastCenterSide = -1;
    private long lastFrame, nextHexAt, nextParticleAt, dockImpactStart, shakeStart;
    private String hexText = "0xA4F9";

    // pendrive
    private float penX, penY;
    private boolean dragging, moved, touched, penHover, portHover;
    private double grabX, grabY, downX, downY;
    private boolean animating;
    private long animStart;
    private float animFromX, animFromY, animToX, animToY;
    private static final long ANIM_MS = 250;

    private String pressedBtn = "";
    private long pressedUntil;
    private boolean lockKeyHeld;

    private float scale = 1f;
    private int originX, originY;

    public HackPanelScreen(BlockPos pos) {
        super(Text.literal("Terminal de antena"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        scale = Math.min(1f, (this.height - 8f) / TOTAL_H);
        originX = Math.round((this.width - W * scale) / 2f);
        originY = Math.round((this.height - TOTAL_H * scale) / 2f);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        // fechou no meio do puzzle: o servidor descarta a sessão
        if (sessionOpen) send(HackManager.ACT_EJECT);
        sessionOpen = false;
        super.removed();
    }

    // ------------------------------------------------------------------ rede

    private void send(int type) {
        if (!ClientPlayNetworking.canSend(VigiCam.HACK_ACTION)) return;
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(type);
        buf.writeBlockPos(pos);
        ClientPlayNetworking.send(VigiCam.HACK_ACTION, buf);
    }

    /** Resposta do servidor ({@link HackManager#RES_GRANTED} / {@link HackManager#RES_DENIED}). */
    public void onResult(int type, boolean ok) {
        if (ok && type == HackManager.RES_GRANTED) {
            if (state == State.WAITING || state == State.HACKING) completeHack();
        } else if (state != State.HACKED) {
            refuse();
        }
    }

    // ------------------------------------------------------------------ som

    private static void sound(SoundEvent event) {
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(event, 1.0f, 1.0f));
    }

    private static long now() {
        return Util.getMeasuringTimeMs();
    }

    // ------------------------------------------------------------------ lógica do terminal

    /** Estado 2: o pendrive encaixou e o puzzle começa. */
    private void enterPuzzle() {
        if (state != State.LOCKED || refused) return;
        long now = now();
        state = State.HACKING;
        failCooldown = false;
        sessionOpen = true;
        sound(ModSounds.HACK_USB_CONNECT);
        send(HackManager.ACT_INSERT);
        animateTo(0f, DOCK_Y - REST_Y);
        dockImpactStart = now;
        cursorPos = 0;
        cursorDir = 1;
        lastCenterSide = -1;
        lastFrame = now;
        hexText = "0xA4F9";
        nextHexAt = now + 130;
        setupStage(1);
    }

    private void setupStage(int s) {
        stage = s;
        targetWidth = TARGET_W[s];
        float minLeft = 8, maxLeft = 92 - targetWidth;
        targetLeft = (int) (minLeft + ThreadLocalRandom.current().nextDouble() * (maxLeft - minLeft));
    }

    /** Estado 3: o servidor concedeu o acesso (3/3). */
    private void completeHack() {
        state = State.HACKED;
        sessionOpen = false;
        failCooldown = false;
        sound(ModSounds.HACK_VICTORY);
    }

    /** Estado 1: ejeta o pendrive e volta para SINAL BLOQUEADO; depois de ejetar com sucesso o painel fecha. */
    private void eject() {
        if (state == State.LOCKED) return;
        boolean wasHacked = state == State.HACKED;
        state = State.LOCKED;
        failCooldown = false;
        if (sessionOpen) send(HackManager.ACT_EJECT);
        sessionOpen = false;
        sound(ModSounds.HACK_USB_EJECT);
        animateTo(0f, 0f);
        if (wasHacked) closeAt = now() + 500;
    }

    private void refuse() {
        refused = true;
        sessionOpen = false;
        state = State.LOCKED;
        failCooldown = false;
        sound(ModSounds.HACK_SYNC_FAIL);
        startShake();
        failStart = now();
        animateTo(0f, 0f);
        closeAt = now() + 1400;
    }

    private void startShake() {
        shakeStart = now();
    }

    /** Clique em "TRAVAR SINAL": o cursor está dentro da zona alvo (com a mesma folga de 1,5% do HTML)? */
    private void attemptSignalLock() {
        if (state != State.HACKING || failCooldown) return;
        long now = now();
        advanceCursor(now);
        float center = cursorPos + 1.75f;
        float tol = 1.5f;
        boolean hit = center >= targetLeft - tol && center <= targetLeft + targetWidth + tol;
        if (hit) {
            send(HackManager.ACT_STAGE);
            if (stage < 3) {
                sound(stage == 1 ? ModSounds.HACK_STAGE_LOCK_1 : ModSounds.HACK_STAGE_LOCK_2);
                setupStage(stage + 1);
            } else {
                state = State.WAITING;           // 3/3: espera o servidor confirmar
                waitDeadline = now + 3000;
            }
        } else {
            failure(now);
        }
    }

    private void failure(long now) {
        failCooldown = true;
        failStart = now;
        failUntil = now + 550;
        sound(ModSounds.HACK_SYNC_FAIL);
        send(HackManager.ACT_FAIL);
        startShake();
    }

    private void advanceCursor(long now) {
        float dt = Math.min((now - lastFrame) / 1000f, 0.05f);
        lastFrame = now;
        if (failCooldown) return;
        cursorPos += cursorDir * SPEED[stage] * dt;
        float max = 96.5f;
        if (cursorPos >= max) {
            cursorPos = max;
            cursorDir = -1;
        } else if (cursorPos <= 0) {
            cursorPos = 0;
            cursorDir = 1;
        }
        int side = cursorPos >= 48 ? 1 : -1;          // pulso discreto ao cruzar o meio
        if (side != lastCenterSide) {
            lastCenterSide = side;
            sound(stage == 1 ? ModSounds.HACK_TICK_1 : stage == 2 ? ModSounds.HACK_TICK_2 : ModSounds.HACK_TICK_3);
        }
    }

    private void updateLogic(long now) {
        if (state == State.HACKING) {
            if (failCooldown && now >= failUntil) {
                failCooldown = false;
                setupStage(1);                         // errou: volta ao estágio 1/3
            }
            advanceCursor(now);
            if (now >= nextHexAt) {
                nextHexAt = now + 130;
                ThreadLocalRandom r = ThreadLocalRandom.current();
                hexText = String.format("0x%04X %.1f", r.nextInt(0x10000), 140 + r.nextDouble() * 180);
            }
        } else if (state == State.WAITING && now > waitDeadline) {
            refuse();                                  // o servidor não respondeu
        }
        if (now >= nextParticleAt) {                   // partículas de código saem do pendrive o tempo todo
            nextParticleAt = now + 240;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            float px = REST_X + curPenX(now), py = REST_Y + curPenY(now);
            particles.add(new Particle(now, px + PEN_W / 2f + (r.nextFloat() - 0.5f) * 19f, py + PEN_H * 0.35f,
                    CODE_TOKENS[r.nextInt(CODE_TOKENS.length)], (r.nextFloat() - 0.5f) * 13f));
        }
        particles.removeIf(p -> now - p.born() > 1250);
        if (closeAt > 0 && now >= closeAt) close();
    }

    // ------------------------------------------------------------------ pendrive: posição e animação

    private void animateTo(float x, float y) {
        animFromX = penX;
        animFromY = penY;
        animToX = x;
        animToY = y;
        animStart = now();
        animating = true;
        dragging = false;
    }

    private float curPenX(long now) {
        stepAnim(now);
        return penX;
    }

    private float curPenY(long now) {
        stepAnim(now);
        return penY;
    }

    /** cubic-bezier(0.22, 1, 0.36, 1) do HTML, aproximado por uma saída suave. */
    private void stepAnim(long now) {
        if (!animating) return;
        float t = Math.min(1f, (now - animStart) / (float) ANIM_MS);
        float e = 1f - (float) Math.pow(1f - t, 4.0);
        penX = animFromX + (animToX - animFromX) * e;
        penY = animFromY + (animToY - animFromY) * e;
        if (t >= 1f) animating = false;
    }

    // ------------------------------------------------------------------ entrada

    private double toX(double mouseX) { return (mouseX - originX) / scale; }
    private double toY(double mouseY) { return (mouseY - originY) / scale; }

    private boolean overPendrive(double x, double y) {
        float px = REST_X + penX, py = REST_Y + penY;
        return x >= px && x < px + PEN_W && y >= py && y < py + PEN_H + 2;
    }

    private boolean overButton(double x, double y) {
        return x >= BTN_X && x < BTN_X + BTN_W && y >= BTN_Y && y < BTN_Y + BTN_H;
    }

    /** O pendrive está perto da porta? (folgas do HTML na metade) */
    private boolean nearPort(float slack, float below) {
        float px = REST_X + penX, py = REST_Y + penY;
        return px + PEN_W > PORT_X - slack && px < PORT_X + PORT_W + slack
                && py + PEN_H > PORT_Y - slack && py < PORT_Y + PORT_H + below;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || closeAt > 0) return super.mouseClicked(mouseX, mouseY, button);
        double x = toX(mouseX), y = toY(mouseY);

        if (overPendrive(x, y)) {
            touched = true;
            if (state != State.LOCKED) {               // já encaixado: clicar nele ejeta
                eject();
                return true;
            }
            dragging = true;
            moved = false;
            animating = false;
            grabX = x - penX;                          // o pendrive acompanha o mouse a partir de onde foi pego
            grabY = y - penY;
            downX = x;
            downY = y;
            return true;
        }
        if (overButton(x, y)) {
            pressedBtn = "main";
            pressedUntil = now() + 110;
            if (state == State.HACKING) attemptSignalLock();
            else if (state == State.HACKED) eject();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging || state != State.LOCKED) return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        double x = toX(mouseX), y = toY(mouseY);
        float nx = (float) (x - grabX), ny = (float) (y - grabY);
        if (Math.hypot(x - downX, y - downY) > 3) moved = true;   // limiar de 6 px do HTML, na metade
        penX = nx;
        penY = ny;
        portHover = nearPort(12, 18);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            portHover = false;
            if (state == State.LOCKED) {
                if (!moved || nearPort(15, 22.5f)) enterPuzzle();    // clique rápido ou soltou perto da porta: encaixa
                else animateTo(0f, 0f);                              // soltou longe: volta para baixo do painel
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (lockKeyHeld || closeAt > 0) return true;     // segurar a tecla não repete (e.repeat do HTML)
            lockKeyHeld = true;
            pressedBtn = "main";
            pressedUntil = now() + 100;
            switch (state) {
                case HACKING -> attemptSignalLock();
                case LOCKED -> { touched = true; enterPuzzle(); }
                case HACKED -> eject();
                default -> { }
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            lockKeyHeld = false;
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ cores e textos por estado

    /** LED superior: {principal, claro, escuro}. Em HACKING alterna âmbar / ciano a cada 0,2 s (ledPuzzlePulse). */
    private int[] ledColors(long now) {
        if (state == State.HACKED) return new int[]{0x00FF66, 0x99FFBB, 0x00993D};
        if (state == State.HACKING || state == State.WAITING) {
            return (now / 200) % 2 == 0 ? new int[]{0xFFB800, 0xFFE082, 0xB26A00} : new int[]{0x00E5FF, 0x99F6FF, 0x008299};
        }
        return new int[]{0xFF2A2A, 0xFF7A7A, 0xA80000};
    }

    /** Fundo do visor, da borda para o centro (gradiente radial em degraus). */
    private int[] visorBg() {
        return switch (state) {
            case HACKING, WAITING -> new int[]{0x080E12, 0x0A1519, 0x0B1B21, 0x0D2029};
            case HACKED -> new int[]{0x06140B, 0x081D0F, 0x0A2613, 0x0B2B17};
            default -> new int[]{0x0D0808, 0x150808, 0x1B0808, 0x220808};
        };
    }

    private boolean failFlash(long now) {
        long t = now - failStart;
        return failStart > 0 && t >= 0 && t < 320 && (t / 80) % 2 == 1;
    }

    private int shakeX(long now) {
        if (shakeStart == 0) return 0;
        long t = now - shakeStart;
        if (t < 60) return -4;
        if (t < 120) return 4;
        if (t < 180) return -2;
        if (t < 240) return 2;
        return 0;
    }

    /** Impacto vertical ao encaixar o pendrive (dockImpact). */
    private int impactY(long now) {
        if (dockImpactStart == 0) return 0;
        long t = now - dockImpactStart;
        if (t < 80) return -2;
        if (t < 160) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ desenho

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        this.renderBackground(c);
        long now = now();
        updateLogic(now);
        stepAnim(now);
        double mx = toX(mouseX), my = toY(mouseY);
        penHover = !dragging && state == State.LOCKED && overPendrive(mx, my);

        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(originX, originY, 0);
        m.scale(scale, scale, 1f);

        // terminal (treme / "bate" ao encaixar)
        m.push();
        m.translate(shakeX(now), impactY(now), 0);
        drawPanel(c, now, mx, my);
        m.pop();

        // pendrive e partículas ficam por cima e não tremem junto com o chassi
        for (Particle p : particles) drawParticle(c, p, now);
        drawPendrive(c, now);
        m.pop();
    }

    private void drawPanel(DrawContext c, long now, double mx, double my) {
        int[] led = ledColors(now);

        oct(c, 0, 7, W, PANEL_H, OUTER, 0x47000000);
        oct(c, 0, 0, W, PANEL_H, OUTER, 0xFF141417);
        oct(c, 2, 2, W - 4, PANEL_H - 4, BEVEL, 0xFF3A3A40);
        int bx = 4, by = 4, bw = W - 8, bh = PANEL_H - 8;
        oct(c, bx, by, bw, bh, BODY, 0xFF28282C);
        clipFill(c, bx, by, bw, bh, BODY, bx, by, bx + bw, by + 2, 0xFF45454C);
        clipFill(c, bx, by, bw, bh, BODY, bx, by, bx + 2, by + bh, 0xFF45454C);
        clipFill(c, bx, by, bw, bh, BODY, bx, by + bh - 2, bx + bw, by + bh, 0xFF161618);
        clipFill(c, bx, by, bw, bh, BODY, bx + bw - 2, by, bx + bw, by + bh, 0xFF161618);

        screw(c, 18, 17);
        screw(c, W - 23, 17);
        drawLed(c, led);

        drawVisor(c, now);
        drawMainButton(c, now, mx, my);
        drawUsbPort(c);

        int sy = BOTTOM + 8;
        screw(c, 20, sy);
        screw(c, W - 25, sy);
    }

    private void drawLed(DrawContext c, int[] led) {
        int sx = (W - 34) / 2, sy = 14, sw = 34, sh = 12;
        c.fill(sx + 1, sy + 1, sx + sw + 1, sy + sh + 1, 0xFF3C3C42);
        c.fill(sx, sy, sx + sw, sy + sh, 0xFF0E0E10);
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + sh - 2, 0xFF121215);
        c.fill(sx + 2, sy + 2, sx + sw - 2, sy + 3, 0xFF08080A);
        c.fill(sx + 2, sy + 2, sx + 3, sy + sh - 2, 0xFF08080A);

        int x0 = sx + 3, y0 = 17, x1 = sx + sw - 3, y1 = 23;
        c.fill(x0 - 6, y0 - 6, x1 + 6, y1 + 6, argb(0x10, led[0]));
        c.fill(x0 - 4, y0 - 4, x1 + 4, y1 + 4, argb(0x14, led[0]));
        c.fill(x0 - 2, y0 - 2, x1 + 2, y1 + 2, argb(0x26, led[0]));
        c.fill(x0, y0, x1, y1, argb(0xFF, led[0]));
        c.fill(x0, y0, x1, y0 + 1, argb(0xFF, led[1]));
        c.fill(x0, y1 - 1, x1, y1, argb(0xFF, led[2]));
    }

    private void drawVisor(DrawContext c, long now) {
        int fx = 14, fy = 34, fw = W - 28, fh = 104;
        c.fill(fx, fy, fx + fw, fy + fh, 0xFF0E0E10);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF18181B);
        c.fill(fx + 2, fy + 2, fx + fw - 2, fy + 4, 0xFF0B0B0D);
        c.fill(fx + 2, fy + 2, fx + 4, fy + fh - 2, 0xFF0B0B0D);
        c.fill(fx + 2, fy + fh - 4, fx + fw - 2, fy + fh - 2, 0xFF38383E);
        c.fill(fx + fw - 4, fy + 2, fx + fw - 2, fy + fh - 2, 0xFF38383E);

        int x0 = 18, y0 = 38, x1 = W - 18, y1 = 134;
        c.fill(x0, y0, x1, y1, 0xFF09090B);
        x0++; y0++; x1--; y1--;
        if (failFlash(now)) {
            c.fill(x0, y0, x1, y1, 0xFF4D0808);                    // piscada vermelha ao errar
        } else {
            int[] bg = visorBg();
            int[] insetX = {0, 8, 20, 34}, insetTop = {0, 5, 13, 21}, insetBot = {0, 7, 17, 29};
            for (int i = 0; i < 4; i++) {
                c.fill(x0 + insetX[i], y0 + insetTop[i], x1 - insetX[i], y1 - insetBot[i], argb(0xFF, bg[i]));
            }
        }
        for (int y = y0; y < y1; y += 2) c.fill(x0, y + 1, x1, y + 2, 0x52000000);   // scanlines

        if (state == State.HACKING || state == State.WAITING) drawPuzzle(c, now);
        else drawAntennaView(c, now);
    }

    // ---- vista da antena (bloqueada / hackeada)

    private void drawAntennaView(DrawContext c, long now) {
        boolean hacked = state == State.HACKED;
        int main = hacked ? 0x00FF66 : 0xFF2A2A, light = hacked ? 0x9EFFC4 : 0xFF7A7A, dark = hacked ? 0x009E3F : 0xB80000;

        String top, bottom;
        int topColor, bottomColor;
        if (hacked) {
            top = "ANTENA HACKEADA"; topColor = 0x00FF66;
            bottom = "SINAL INTERCEPTADO"; bottomColor = 0x9EFFC4;
        } else if (refused) {
            top = "ACESSO NEGADO"; topColor = 0xFF2A2A;
            bottom = "CONEXAO RECUSADA"; bottomColor = 0xFF7A7A;
        } else {
            top = "SINAL BLOQUEADO"; topColor = 0xFF2A2A;
            bottom = "INSIRA O PENDRIVE"; bottomColor = 0xFF7A7A;
        }
        c.drawCenteredTextWithShadow(this.textRenderer, top, W / 2, 45, argb(0xFF, topColor));
        c.drawCenteredTextWithShadow(this.textRenderer, bottom, W / 2, 118, argb(0xFF, bottomColor));

        // ondas: opacidade em degraus (steps(2)); hackeada pulsa mais rápido
        long periodIn = hacked ? 550 : 1300, periodOut = periodIn, delayOut = hacked ? 250 : 450;
        float opIn = waveOpacity(now, periodIn, 0), opOut = waveOpacity(now, periodOut, delayOut);

        // brilho (drop-shadow do HTML): a silhueta engordada em camadas translúcidas
        for (int[] r : ANT) {
            if (r[4] == WAVE_IN || r[4] == WAVE_OUT) continue;
            glowRect(c, ANT_X + r[0] * ANT_CELL, ANT_Y + r[1] * ANT_CELL, r[2] * ANT_CELL, r[3] * ANT_CELL, main);
        }
        for (int[] r : ANT) {
            int rgb = switch (r[4]) {
                case LIGHT -> light;
                case DARK -> dark;
                default -> main;
            };
            int alpha = switch (r[4]) {
                case WAVE_IN -> (int) (opIn * 255);
                case WAVE_OUT -> (int) (opOut * 255);
                default -> 255;
            };
            c.fill(ANT_X + r[0] * ANT_CELL, ANT_Y + r[1] * ANT_CELL,
                    ANT_X + (r[0] + r[2]) * ANT_CELL, ANT_Y + (r[1] + r[3]) * ANT_CELL, argb(alpha, rgb));
        }
    }

    /** Opacidade da onda: 0,25 -> 1 -> 0,25 em 2 degraus por trecho (keyframes do wavePulse). */
    private static float waveOpacity(long now, long period, long delay) {
        long t = Math.floorMod(now - delay, period);
        float ph = t / (float) period;
        if (ph < 0.25f) return 0.25f;
        if (ph < 0.5f) return 0.625f;
        if (ph < 0.75f) return 1.0f;
        return 0.625f;
    }

    private static void glowRect(DrawContext c, int x, int y, int w, int h, int rgb) {
        c.fill(x - 3, y - 3, x + w + 3, y + h + 3, argb(0x0C, rgb));
        c.fill(x - 1, y - 1, x + w + 1, y + h + 1, argb(0x16, rgb));
    }

    // ---- vista do puzzle ("QUEBRA DE SINAL")

    private void drawPuzzle(DrawContext c, long now) {
        boolean fail = failCooldown;
        boolean waiting = state == State.WAITING;

        String title = fail ? "FALHA DE SINCRONIA!" : waiting ? "SINAL TRAVADO" : "QUEBRA DE SINAL";
        String footer = fail ? "REINICIANDO PROTOCOLO" : waiting ? "INTERCEPTANDO..." : "ALINHE O PULSO CIANO";
        c.drawCenteredTextWithShadow(this.textRenderer, title, W / 2, 44, argb(0xFF, fail ? 0xFF2A2A : 0x00E5FF));
        c.drawCenteredTextWithShadow(this.textRenderer, footer, W / 2, 116, argb(0xFF, fail ? 0xFF7A7A : 0xFFB800));

        // estágio + código hexadecimal (texto menor)
        smallText(c, "ESTAGIO " + (waiting ? 3 : stage) + "/3", TRACK_X, 57, 0x7DD3FC, 0.75f, false);
        smallText(c, hexText, TRACK_X + TRACK_W, 57, 0x7DD3FC, 0.75f, true);

        // 3 nós de criptografia
        int nodeW = 11, nodeH = 5, gap = 6;
        int nx = (W - (3 * nodeW + 2 * gap)) / 2, ny = 71;
        for (int i = 0; i < 3; i++) {
            int step = i + 1;
            boolean solved = waiting || step < stage;
            boolean active = !solved && step == stage;
            int x = nx + i * (nodeW + gap);
            int border = solved ? 0x99FFBB : active ? 0xFFE082 : 0x2F4858;
            int fill = solved ? 0x00FF66 : active ? 0xFFB800 : 0x141C24;
            if (solved || active) c.fill(x - 2, ny - 2, x + nodeW + 2, ny + nodeH + 2, argb(0x30, fill));
            c.fill(x, ny, x + nodeW, ny + nodeH, argb(0xFF, border));
            c.fill(x + 1, ny + 1, x + nodeW - 1, ny + nodeH - 1, argb(0xFF, fill));
        }

        // trilha de alinhamento
        c.fill(TRACK_X, TRACK_Y, TRACK_X + TRACK_W, TRACK_Y + TRACK_H, 0xFF263845);
        int ix = TRACK_X + 2, iy = TRACK_Y + 2, iw = TRACK_W - 4, ih = TRACK_H - 4;
        c.fill(ix, iy, ix + iw, iy + ih, 0xFF05080B);
        c.fill(ix, iy, ix + iw, iy + 1, 0xFF020305);
        c.fill(ix, iy, ix + 1, iy + ih, 0xFF020305);

        // zona alvo ciano
        int tx0 = ix + Math.round(iw * targetLeft / 100f), tx1 = ix + Math.round(iw * (targetLeft + targetWidth) / 100f);
        c.fill(tx0, iy, tx1, iy + ih, 0x4700E5FF);
        c.fill(tx0 + 1, iy, tx1 - 1, iy + ih, 0x2200E5FF);       // brilho interno
        c.fill(tx0, iy, tx0 + 1, iy + ih, 0xFF00E5FF);
        c.fill(tx1 - 1, iy, tx1, iy + ih, 0xFF00E5FF);

        // cursor luminoso
        int cx = ix + Math.round(iw * cursorPos / 100f);
        c.fill(cx - 2, iy, cx + 6, iy + ih, 0x2400E5FF);
        c.fill(cx - 1, iy, cx + 5, iy + ih, 0x40FFFFFF);
        c.fill(cx, iy, cx + 4, iy + ih, 0xFFFFFFFF);
        c.fill(cx, iy, cx + 1, iy + ih, 0xFFFFB800);
        c.fill(cx + 3, iy, cx + 4, iy + ih, 0xFFFFB800);
    }

    /** Texto reduzido (o HTML usa fontes de 7 px dentro de um terminal de 336 px). {@code right}: alinha o fim do texto em x. */
    private void smallText(DrawContext c, String s, int x, int y, int rgb, float k, boolean right) {
        MatrixStack m = c.getMatrices();
        m.push();
        int w = (int) (this.textRenderer.getWidth(s) * k);
        m.translate(right ? x - w : x, y, 0);
        m.scale(k, k, 1f);
        c.drawTextWithShadow(this.textRenderer, s, 0, 0, argb(0xFF, rgb));
        m.pop();
    }

    // ---- botão principal e porta USB

    private void drawMainButton(DrawContext c, long now, double mx, double my) {
        String label;
        int base, hi, lo, txt;
        boolean hover = overButton(mx, my) && (state == State.HACKING || state == State.HACKED);
        boolean pressed = "main".equals(pressedBtn) && now < pressedUntil;
        switch (state) {
            case HACKING, WAITING -> {
                label = "TRAVAR SINAL";
                base = pressed ? 0x0E3747 : hover ? 0x1D637D : 0x164E63;
                hi = pressed ? 0x061E26 : hover ? 0x67E8F9 : 0x22D3EE;
                lo = pressed ? 0x1C5B73 : hover ? 0x0E4459 : 0x083344;
                txt = hover ? 0xFFFFFF : 0xE0F2FE;
            }
            case HACKED -> {
                label = "EJETAR USB";
                base = pressed ? 0x1A4024 : hover ? 0x2E6E3F : 0x255933;
                hi = pressed ? 0x0E2414 : hover ? 0x49A361 : 0x3B874F;
                lo = pressed ? 0x2B633A : hover ? 0x1B4225 : 0x15361E;
                txt = hover ? 0xFFFFFF : 0x8AFFAA;
            }
            default -> {
                label = "AGUARDANDO USB";
                base = 0x4A4A52; hi = 0x6E6E78; lo = 0x2B2B30; txt = 0x9E9EA8;
            }
        }
        int x = BTN_X + (pressed ? 1 : 0), y = BTN_Y + (pressed ? 1 : 0), w = BTN_W, h = BTN_H;
        c.fill(x, y, x + w, y + h, 0xFF101012);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, argb(0xFF, base));
        c.fill(x + 1, y + 1, x + w - 1, y + 3, argb(0xFF, hi));
        c.fill(x + 1, y + 1, x + 3, y + h - 1, argb(0xFF, hi));
        c.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, argb(0xFF, lo));
        c.fill(x + w - 3, y + 3, x + w - 1, y + h - 1, argb(0xFF, lo));
        c.drawCenteredTextWithShadow(this.textRenderer, label, x + w / 2, y + (h - 8) / 2 + 1, argb(0xFF, txt));
    }

    private void drawUsbPort(DrawContext c) {
        // LED da porta + rótulo
        int ledRgb = state == State.HACKED ? 0x00FF66 : (state == State.HACKING || state == State.WAITING) ? 0x00E5FF : 0xFF2A2A;
        String label = "USB-PORT";
        int lw = this.textRenderer.getWidth(label);
        int rowW = 4 + 3 + lw;
        int rx = (W - rowW) / 2, ry = 178;
        c.fill(rx, ry + 2, rx + 4, ry + 6, 0xFF000000);
        c.fill(rx + 1, ry + 3, rx + 3, ry + 5, argb(0xFF, ledRgb));
        c.drawTextWithShadow(this.textRenderer, label, rx + 7, ry, 0xFFB8BBC4);

        // cavidade da porta (brilha ciano ao arrastar o pendrive por perto)
        int x = PORT_X, y = PORT_Y, w = PORT_W, h = PORT_H;
        if (portHover) {
            c.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0x1A00E5FF);
            c.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x3000E5FF);
        }
        c.fill(x, y + 1, x + w, y + h + 1, 0xFF3A3A42);                       // sombra dura embaixo
        c.fill(x, y, x + w, y + h, portHover ? 0xFF00E5FF : 0xFF121215);      // borda
        c.fill(x + 2, y + 2, x + w - 2, y + h - 2, 0xFF09090B);
        c.fill(x + 2, y + 2, x + w - 2, y + 3, 0xFF030304);
        c.fill(x + 2, y + 2, x + 3, y + h - 2, 0xFF030304);
        c.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, 0xFF2F2F37);
        // miolo plástico com 4 contatos dourados
        int mxp = x + 6, myp = y + (h - 7) / 2, mw = w - 12;
        c.fill(mxp, myp, mxp + mw, myp + 7, 0xFF08080A);
        c.fill(mxp + 1, myp + 1, mxp + mw - 1, myp + 6, 0xFF1C1C21);
        int span = mw - 8;
        for (int i = 0; i < 4; i++) {
            int cxp = mxp + 4 + span * (2 * i + 1) / 8 - 2;
            c.fill(cxp, myp + 2, cxp + 4, myp + 5, 0xFFD4AF37);
        }
    }

    // ---- pendrive e partículas

    private void drawPendrive(DrawContext c, long now) {
        float lift = (penHover && !touched && state == State.LOCKED) ? -2f : 0f;
        float px = REST_X + penX, py = REST_Y + penY + lift;
        boolean glow = dragging || penHover;
        boolean hacking = state == State.HACKING || state == State.WAITING;
        int ledRgb = hacking && (now / 110) % 2 == 1 ? 0xFFB800 : 0x00FF66;

        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(px, py, 0);
        m.scale(PEN_CELL, PEN_CELL, 1f);

        // sombra dura e brilho verde (drop-shadow do HTML)
        int shadow = dragging ? 0x61000000 : state == State.LOCKED ? 0x52000000 : 0x73000000;
        float down = dragging ? 2f : 1.5f;
        m.push();
        m.translate(0, down, 0);
        for (int[] r : PEN) {
            if (r[4] == 0x0A1A10 || r[4] == 0) continue;
            c.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], shadow);
        }
        m.pop();
        if (glow) {
            c.fill(1, 0, 15, 22, 0x1A00FF66);
            c.fill(0, 0, 16, 22, 0x0C00FF66);
        }
        for (int[] r : PEN) {
            int rgb = r[4] == 0 ? ledRgb : r[4];
            c.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], argb(0xFF, rgb));
        }
        m.pop();
    }

    private void drawParticle(DrawContext c, Particle p, long now) {
        float t = (now - p.born()) / 1250f;
        float q = (float) Math.floor(t * 8f) / 8f;                       // steps(8)
        float opacity = t < 0.7f ? 1f - (t / 0.7f) * 0.1f : 0.9f * (1f - (t - 0.7f) / 0.3f);
        int alpha = (int) (opacity * 255);
        if (alpha < 8) return;                                          // o texto do jogo ignora alfa quase zero
        float k = 0.5f * (0.95f + 0.1f * q);
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(p.x() + p.drift() * q, p.y() - 17f * q, 0);
        m.scale(k, k, 1f);
        c.drawTextWithShadow(this.textRenderer, p.text(), 0, 0, argb(alpha, 0x00FF66));
        m.pop();
    }

    // ------------------------------------------------------------------ utilidades de desenho (iguais às do painel da porta)

    private static void screw(DrawContext c, int x, int y) {
        c.fill(x, y, x + 5, y + 5, 0xFF38383E);
        c.fill(x + 1, y + 1, x + 4, y + 4, 0xFF1A1A1D);
    }

    private static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    private static int insetAt(int[] profile, int d) {
        for (int i = 0; i < profile.length; i += 2) if (d < profile[i]) return profile[i + 1];
        return 0;
    }

    private static void oct(DrawContext c, int x, int y, int w, int h, int[] profile, int color) {
        clipFill(c, x, y, w, h, profile, x, y, x + w, y + h, color);
    }

    /** Preenche {@code [rx0,rx1) x [ry0,ry1)} só onde cai dentro da forma em escada (x, y, w, h, profile). */
    private static void clipFill(DrawContext c, int x, int y, int w, int h, int[] profile,
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
