package com.example.vigicam;

/** Tipos, cores e IDs dos dispositivos do controle remoto (iguais aos do painel HTML: CAM-01, DRN-01, DOOR-01...). */
public final class RemoteDevices {
    private RemoteDevices() {}

    public static final int CAM = 0, DRONE = 1, DOOR = 2;

    /** Paleta do painel: cada dispositivo vinculado ganha uma cor (o MAX_LINKS do controle é 8, então nunca repete). */
    public static final int[] RGB = {0xE2453C, 0xE8B63A, 0x6DC46A, 0x3FB6E8, 0x9B6BE8, 0xE86BC1, 0xF28A3A, 0xE8E8E8, 0x2FD1C0, 0xA0522D};
    public static final String[] NAMES = {"vermelha", "amarela", "verde", "azul", "roxa", "rosa", "laranja", "branca", "ciano", "marrom"};

    public static String prefix(int kind) {
        return kind == DRONE ? "DRN" : kind == DOOR ? "DOOR" : "CAM";
    }

    public static String id(int kind, int seq) {
        return String.format("%s-%02d", prefix(kind), seq);
    }

    /** Nome no singular, como no HTML (usado nas mensagens de remoção). */
    public static String noun(int kind) {
        return kind == DRONE ? "drone" : kind == DOOR ? "porta" : "camera";
    }

    public static int color(int index) {
        return RGB[Math.floorMod(index, RGB.length)];
    }
}
