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

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = new Identifier(VigiCam.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Só força o carregamento da classe (e o registro dos sons) no início do mod. */
    public static void init() {}
}
