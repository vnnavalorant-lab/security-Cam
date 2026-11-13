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

    private ModSounds() {}

    private static SoundEvent register(String name) {
        Identifier id = new Identifier(VigiCam.MOD_ID, name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** Só força o carregamento da classe (e o registro dos sons) no início do mod. */
    public static void init() {}
}
