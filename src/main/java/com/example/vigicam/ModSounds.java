package com.example.vigicam;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/** Sons personalizados do mod (arquivos em assets/vigicam/sounds, declarados em sounds.json). */
public final class ModSounds {
    /** Zumbido do drone: em looping enquanto ele voa. */
    public static final SoundEvent DRONE_FLY = register("drone_fly");
    /** Antena amplificadora: ambiente em looping. */
    public static final SoundEvent ANTENNA_LOOP = register("antenna");
    /** Câmera de segurança: ambiente em looping. */
    public static final SoundEvent CAMERA_LOOP = register("camera");
    /** Clique no controle remoto: toca uma vez por clique do mouse. */
    public static final SoundEvent PRESS_CONTROL = register("press_control");

    /** Painel da porta reforçada (mesmos sons 8-bit do painel de referência): tecla, limpar, erro e destravar. */
    public static final SoundEvent DOOR_KEY = register("door_key");
    public static final SoundEvent DOOR_CLEAR = register("door_clear");
    public static final SoundEvent DOOR_ERROR = register("door_error");
    public static final SoundEvent DOOR_UNLOCK = register("door_unlock");

    /**
     * Painel de biometria (leitor de digital): um pulso 8-bit a cada 135 ms durante a leitura, cada vez mais agudo
     * (BIO_SCAN[i] é o pulso com o tom do progresso i * 135 / 1500 do HTML), o erro ao soltar cedo e o arpejo de sucesso.
     */
    public static final int BIO_PULSES = 12;
    public static final SoundEvent[] BIO_SCAN = new SoundEvent[BIO_PULSES];
    public static final SoundEvent BIO_FAIL = register("bio_fail");
    public static final SoundEvent BIO_SUCCESS = register("bio_success");

    static {
        for (int i = 0; i < BIO_PULSES; i++) BIO_SCAN[i] = register("bio_scan_" + i);
    }

    /** Pendrive hacker (mesmos sons 8-bit do "Terminal de Antena" HTML): encaixar/ejetar, pulso por estágio, estágio travado, erro e vitória. */
    public static final SoundEvent HACK_USB_CONNECT = register("hack_usb_connect");
    public static final SoundEvent HACK_USB_EJECT = register("hack_usb_eject");
    public static final SoundEvent HACK_TICK_1 = register("hack_tick_1");
    public static final SoundEvent HACK_TICK_2 = register("hack_tick_2");
    public static final SoundEvent HACK_TICK_3 = register("hack_tick_3");
    public static final SoundEvent HACK_STAGE_LOCK_1 = register("hack_stage_lock_1");
    public static final SoundEvent HACK_STAGE_LOCK_2 = register("hack_stage_lock_2");
    public static final SoundEvent HACK_SYNC_FAIL = register("hack_sync_fail");
    public static final SoundEvent HACK_VICTORY = register("hack_victory");

    /** Controle remoto (beeps do painel HTML): aba, armar, remover, remover todos, ping, menu, porta trancada/aberta, ver câmera, pilotar. */
    public static final SoundEvent REMOTE_TAB = register("remote_tab");
    public static final SoundEvent REMOTE_ARM = register("remote_arm");
    public static final SoundEvent REMOTE_REMOVE = register("remote_remove");
    public static final SoundEvent REMOTE_REMOVE_ALL = register("remote_remove_all");
    public static final SoundEvent REMOTE_PING = register("remote_ping");
    public static final SoundEvent REMOTE_MENU = register("remote_menu");
    public static final SoundEvent REMOTE_DOOR_LOCK = register("remote_door_lock");
    public static final SoundEvent REMOTE_DOOR_OPEN = register("remote_door_open");
    public static final SoundEvent REMOTE_WATCH = register("remote_watch");
    public static final SoundEvent REMOTE_PILOT = register("remote_pilot");

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = new Identifier(VigiCam.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Só força o carregamento da classe (e o registro dos sons) no início do mod. */
    public static void init() {}
}
