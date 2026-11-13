package com.example.vigicam;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Sons do lado do cliente: loops (drone, antena, câmera) e o clique do controle remoto. */
public final class VigiSounds {
    /** Volumes (os arquivos já foram nivelados juntos: ambiente, nada alto). */
    private static final float DRONE_VOLUME = 1.0f, ANTENNA_VOLUME = 0.9f, CAMERA_VOLUME = 0.9f, PRESS_VOLUME = 1.0f;

    private static final Map<BlockPos, BlockLoop> BLOCK_LOOPS = new HashMap<>();
    private static final Map<Integer, DroneLoop> DRONE_LOOPS = new HashMap<>();

    private VigiSounds() {}

    /** Ao sair do mundo: esquece os loops (o SoundManager já para tudo sozinho). */
    public static void reset() {
        BLOCK_LOOPS.clear();
        DRONE_LOOPS.clear();
    }

    // ------------------------------------------------------------------ blocos (antena / câmera)

    public static void antennaTick(BlockPos pos, long time) {
        ensureBlockLoop(pos, VigiCam.AMPLIFYING_ANTENNA, ModSounds.ANTENNA_LOOP, ANTENNA_VOLUME, 2.0, time);
    }

    public static void cameraTick(BlockPos pos, long time) {
        ensureBlockLoop(pos, VigiCam.SURVEILLANCE_CAMERA, ModSounds.CAMERA_LOOP, CAMERA_VOLUME, 0.5, time);
    }

    private static void ensureBlockLoop(BlockPos pos, Block block, SoundEvent ev, float vol, double yOff, long time) {
        MinecraftClient mc = MinecraftClient.getInstance();
        SoundManager sm = mc.getSoundManager();
        BlockLoop s = BLOCK_LOOPS.get(pos);
        if (s == null || s.isDone()) {
            s = new BlockLoop(ev, vol, pos, block, yOff);
            BLOCK_LOOPS.put(pos.toImmutable(), s);
            sm.play(s);
        } else if (time % 20 == 0 && !sm.isPlaying(s)) {
            // o jogador estava longe demais e o som foi cortado: recomeça quando chegar perto
            sm.stop(s);
            sm.play(s);
        }
    }

    private static final class BlockLoop extends MovingSoundInstance {
        private final BlockPos pos;
        private final Block block;

        BlockLoop(SoundEvent ev, float vol, BlockPos pos, Block block, double yOff) {
            super(ev, SoundCategory.BLOCKS, SoundInstance.createRandom());
            this.pos = pos.toImmutable();
            this.block = block;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = vol;
            this.pitch = 1.0f;
            this.x = pos.getX() + 0.5;
            this.y = pos.getY() + yOff;
            this.z = pos.getZ() + 0.5;
        }

        @Override
        public void tick() {
            var w = MinecraftClient.getInstance().world;
            // bloco quebrado / mundo trocado / chunk descarregado: o som acaba
            if (w == null || !w.getBlockState(pos).isOf(block)) setDone();
        }
    }

    // ------------------------------------------------------------------ drone

    /** Chamado todo tick pelo drone no cliente: o zumbido toca enquanto ele voa (ligado) e para ao pousar. */
    public static void droneTick(DroneEntity d) {
        SoundManager sm = MinecraftClient.getInstance().getSoundManager();
        DroneLoop s = DRONE_LOOPS.get(d.getId());
        if (d.isRemoved() || !d.isPowered()) {
            if (s != null) {
                s.finish();
                sm.stop(s);
                DRONE_LOOPS.remove(d.getId());
            }
            return;
        }
        if (s == null || s.isDone()) {
            s = new DroneLoop(d);
            DRONE_LOOPS.put(d.getId(), s);
            sm.play(s);
        } else if (d.age % 20 == 0 && !sm.isPlaying(s)) {
            sm.stop(s);
            sm.play(s);
        }
    }

    private static final class DroneLoop extends MovingSoundInstance {
        private final DroneEntity drone;

        DroneLoop(DroneEntity drone) {
            super(ModSounds.DRONE_FLY, SoundCategory.NEUTRAL, SoundInstance.createRandom());
            this.drone = drone;
            this.repeat = true;
            this.repeatDelay = 0;
            this.volume = DRONE_VOLUME;
            this.pitch = 1.0f;
            this.x = drone.getX();
            this.y = drone.getY();
            this.z = drone.getZ();
        }

        /** setDone() é protegido: este método deixa a classe de fora encerrar o loop. */
        void finish() { setDone(); }

        @Override
        public void tick() {
            if (drone.isRemoved() || !drone.isPowered()) {   // parou de voar (pousou/desligou/destruído)
                setDone();
                return;
            }
            this.x = drone.getX();
            this.y = drone.getY();
            this.z = drone.getZ();
        }
    }

    // ------------------------------------------------------------------ controle remoto

    /** Clique do mouse (qualquer botão) com o controle remoto em uma das mãos: toca o som uma vez por clique. */
    public static void remoteClick(MinecraftClient mc) {
        if (mc.player == null || mc.currentScreen != null) return;
        if (!(mc.player.getMainHandStack().getItem() instanceof RemoteItem)
                && !(mc.player.getOffHandStack().getItem() instanceof RemoteItem)) return;
        mc.getSoundManager().play(PositionedSoundInstance.master(ModSounds.PRESS_CONTROL, 1.0f, PRESS_VOLUME));
    }
}
