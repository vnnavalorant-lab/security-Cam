package com.example.vigicam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Modelo da câmera de vigilância, lido de assets/vigicam/geo/security_camera.geo.json
 * (exportado do Blockbench: security_camera.bbmodel). Dois pivôs:
 *  - "base": suporte fixo, encostado na extremidade do bloco e centralizado;
 *  - "root": corpo da câmera, filho do base, que gira para um lado e para o outro.
 * As coordenadas estão em pixels, com a origem no centro-baixo do bloco e a lente apontando para -Z (norte).
 */
public final class SecurityCameraModel {
    public static final Identifier TEXTURE = new Identifier(VigiCam.MOD_ID, "textures/entity/security_camera.png");
    private static final String GEO = "/assets/vigicam/geo/security_camera.geo.json";
    public static final String ROOT = "root";

    private static final SecurityCameraModel INSTANCE = new SecurityCameraModel();
    public static SecurityCameraModel get() { return INSTANCE; }

    private static final class Quad {
        final float[] p = new float[12];   // 4 vértices (x,y,z)
        final float[] uv = new float[8];   // 4 vértices (u,v) já normalizados
        float nx, ny, nz;
    }

    private static final class Group {
        String name;
        float px, py, pz;
        final List<Quad> quads = new ArrayList<>();
        final List<Group> children = new ArrayList<>();
    }

    private final List<Group> groups = new ArrayList<>();
    private float texW = 64f, texH = 64f;

    private SecurityCameraModel() {
        try (InputStream in = SecurityCameraModel.class.getResourceAsStream(GEO)) {
            if (in == null) throw new IllegalStateException("Modelo não encontrado: " + GEO);
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            texW = o.get("texture_width").getAsFloat();
            texH = o.get("texture_height").getAsFloat();
            for (JsonElement e : o.getAsJsonArray("groups")) groups.add(readGroup(e.getAsJsonObject()));
        } catch (Exception ex) {
            throw new RuntimeException("Falha ao carregar o modelo da câmera", ex);
        }
    }

    // ------------------------------------------------------------------ leitura

    private Group readGroup(JsonObject o) {
        Group g = new Group();
        g.name = o.get("name").getAsString();
        float[] origin = floats(o.getAsJsonArray("origin"));
        g.px = origin[0]; g.py = origin[1]; g.pz = origin[2];
        for (JsonElement c : o.getAsJsonArray("cubes")) readCube(g, c.getAsJsonObject());
        for (JsonElement c : o.getAsJsonArray("children")) g.children.add(readGroup(c.getAsJsonObject()));
        return g;
    }

    private static float[] floats(JsonArray a) {
        float[] f = new float[a.size()];
        for (int i = 0; i < f.length; i++) f[i] = a.get(i).getAsFloat();
        return f;
    }

    private void readCube(Group g, JsonObject c) {
        float[] from = floats(c.getAsJsonArray("from")), to = floats(c.getAsJsonArray("to"));
        float[] o = floats(c.getAsJsonArray("origin"));
        float[] rot = c.has("rotation") ? floats(c.getAsJsonArray("rotation")) : new float[3];
        float inf = c.has("inflate") ? c.get("inflate").getAsFloat() : 0f;
        float x0 = from[0] - inf, y0 = from[1] - inf, z0 = from[2] - inf;
        float x1 = to[0] + inf, y1 = to[1] + inf, z1 = to[2] + inf;

        // vértices de cada face, sentido anti-horário visto de fora (mesma ordem do JSON de bloco do vanilla)
        for (Map.Entry<String, JsonElement> face : c.getAsJsonObject("faces").entrySet()) {
            float[][] v;
            switch (face.getKey()) {
                case "down"  -> v = new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
                case "up"    -> v = new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
                case "north" -> v = new float[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
                case "south" -> v = new float[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
                case "west"  -> v = new float[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
                case "east"  -> v = new float[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
                default -> { continue; }
            }
            float[] uv = floats(face.getValue().getAsJsonArray());
            Quad q = new Quad();
            for (int i = 0; i < 4; i++) {
                float[] p = rotate(v[i][0] - o[0], v[i][1] - o[1], v[i][2] - o[2], rot);
                q.p[i * 3] = p[0] + o[0];
                q.p[i * 3 + 1] = p[1] + o[1];
                q.p[i * 3 + 2] = p[2] + o[2];
            }
            // (u1,v1) (u1,v2) (u2,v2) (u2,v1)
            float[] us = {uv[0], uv[0], uv[2], uv[2]}, vs = {uv[1], uv[3], uv[3], uv[1]};
            for (int i = 0; i < 4; i++) {
                q.uv[i * 2] = us[i] / texW;
                q.uv[i * 2 + 1] = vs[i] / texH;
            }
            // normal = (v1-v0) x (v2-v1)
            float ax = q.p[3] - q.p[0], ay = q.p[4] - q.p[1], az = q.p[5] - q.p[2];
            float bx = q.p[6] - q.p[3], by = q.p[7] - q.p[4], bz = q.p[8] - q.p[5];
            float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6f) { q.nx = nx / len; q.ny = ny / len; q.nz = nz / len; }
            g.quads.add(q);
        }
    }

    /** Rotação do cubo em torno da própria origem: Z * Y * X (como o Blockbench). */
    private static float[] rotate(float x, float y, float z, float[] r) {
        double rx = Math.toRadians(r[0]), ry = Math.toRadians(r[1]), rz = Math.toRadians(r[2]);
        double ny = y * Math.cos(rx) - z * Math.sin(rx), nz = y * Math.sin(rx) + z * Math.cos(rx);
        y = (float) ny; z = (float) nz;
        double nx = x * Math.cos(ry) + z * Math.sin(ry);
        nz = -x * Math.sin(ry) + z * Math.cos(ry);
        x = (float) nx; z = (float) nz;
        nx = x * Math.cos(rz) - y * Math.sin(rz);
        ny = x * Math.sin(rz) + y * Math.cos(rz);
        return new float[]{(float) nx, (float) ny, z};
    }

    // ------------------------------------------------------------------ desenho

    /**
     * @param matrices já posicionada: origem no centro-baixo do bloco, girada para o lado da câmera e com escala 1/16
     * @param rootYaw  giro (graus, em torno de +Y) aplicado ao pivô "root"
     */
    public void render(MatrixStack matrices, VertexConsumer vc, int light, int overlay, float rootYaw) {
        for (Group g : groups) renderGroup(g, matrices, vc, light, overlay, rootYaw);
    }

    private void renderGroup(Group g, MatrixStack m, VertexConsumer vc, int light, int overlay, float rootYaw) {
        m.push();
        if (ROOT.equals(g.name)) {
            m.translate(g.px, g.py, g.pz);
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rootYaw));
            m.translate(-g.px, -g.py, -g.pz);
        }
        MatrixStack.Entry e = m.peek();
        Matrix4f pos = e.getPositionMatrix();
        Matrix3f nrm = e.getNormalMatrix();
        for (Quad q : g.quads) {
            for (int i = 0; i < 4; i++) {
                vc.vertex(pos, q.p[i * 3], q.p[i * 3 + 1], q.p[i * 3 + 2])
                        .color(255, 255, 255, 255)
                        .texture(q.uv[i * 2], q.uv[i * 2 + 1])
                        .overlay(overlay)
                        .light(light)
                        .normal(nrm, q.nx, q.ny, q.nz)
                        .next();
            }
        }
        for (Group c : g.children) renderGroup(c, m, vc, light, overlay, rootYaw);
        m.pop();
    }
}
