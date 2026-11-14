package com.example.vigicam;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lê as animações "on" e "off" da porta (assets/vigicam/animations/reinforced_door.animation.json, exportadas do
 * Blockbench). São poses fixas: cada osso listado recebe a escala indicada (escala 0 = osso escondido).
 */
public final class DoorAnimations {
    private DoorAnimations() {}

    private static final String PATH = "/assets/vigicam/animations/reinforced_door.animation.json";
    private static Map<String, Map<String, float[]>> animations;

    /** Escala por osso da animação ("on" = destravada, "off" = travada). */
    public static Map<String, float[]> scales(String animation) {
        if (animations == null) animations = load();
        return animations.getOrDefault(animation, Map.of());
    }

    private static Map<String, Map<String, float[]>> load() {
        Map<String, Map<String, float[]>> out = new HashMap<>();
        try (InputStream in = DoorAnimations.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("Animações não encontradas: " + PATH);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> a : root.getAsJsonObject("animations").entrySet()) {
                Map<String, float[]> bones = new HashMap<>();
                for (Map.Entry<String, JsonElement> b : a.getValue().getAsJsonObject().getAsJsonObject("bones").entrySet()) {
                    JsonObject o = b.getValue().getAsJsonObject();
                    if (!o.has("scale")) continue;
                    var arr = o.getAsJsonArray("scale");
                    bones.put(b.getKey(), new float[]{arr.get(0).getAsFloat(), arr.get(1).getAsFloat(), arr.get(2).getAsFloat()});
                }
                out.put(a.getKey(), bones);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Falha ao carregar " + PATH, ex);
        }
        return out;
    }
}
