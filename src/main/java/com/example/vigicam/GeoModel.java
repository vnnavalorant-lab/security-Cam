package com.example.vigicam;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
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
 * Leitor genérico de modelos .geo.json no formato usado pelo mod (exportados do Blockbench, plugin GeckoLib/Bedrock):
 * grupos com cubos (from/to/origin/rotation/inflate) e UV por face. Coordenadas em pixels, 1 px = 1/16 de bloco
 * depois que quem chama aplica a escala; o pivô de nome "root", se existir, gira com o parâmetro rootYaw.
 */
public final class GeoModel {
    public static final String ROOT = "root";

    private static final class Quad {
        final float[] p = new float[12];   // 4 vértices (x,y,z)
        final float[] uv = new float[8];   // 4 vértices (u,v) já normalizados
        float nx, ny, nz;
        int order;      // nº do cubo no modelo: afasta um pouco as faces coplanares (z-fighting) no modo sprite
        boolean hull;   // cubo com from/to invertidos (nº ímpar de eixos): faces viradas para dentro, como no Blockbench
    }

    private static final class Group {
        String name;
        float px, py, pz;
        final List<Quad> quads = new ArrayList<>();
        final List<Group> children = new ArrayList<>();
    }

    private final List<Group> groups = new ArrayList<>();
    private float texW = 64f, texH = 64f;
    private int cubeCounter = 0;

    public GeoModel(String path) {
        try (InputStream in = GeoModel.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Modelo não encontrado: " + path);
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            texW = o.get("texture_width").getAsFloat();
            texH = o.get("texture_height").getAsFloat();
            for (JsonElement e : o.getAsJsonArray("groups")) groups.add(readGroup(e.getAsJsonObject()));
        } catch (Exception ex) {
            throw new RuntimeException("Falha ao carregar o modelo " + path, ex);
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
        // from > to em um nº ímpar de eixos = cubo "espelhado": o Blockbench o mostra com as faces para dentro
        // (casca invertida, usada nos contornos dos LEDs). Mantemos esse comportamento.
        int swapped = (from[0] > to[0] ? 1 : 0) + (from[1] > to[1] ? 1 : 0) + (from[2] > to[2] ? 1 : 0);
        boolean hull = (swapped & 1) == 1;
        int order = cubeCounter++;
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
            q.hull = hull;
            q.order = order;
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
        render(matrices, vc, light, overlay, rootYaw, 255, 255, 255, 255);
    }

    /** Igual ao anterior, com cor/alpha por vértice (0-255): usado nas camadas brilhantes (LEDs) que pulsam. */
    public void render(MatrixStack matrices, VertexConsumer vc, int light, int overlay, float rootYaw,
                       int r, int gr, int b, int a) {
        for (Group g : groups) renderGroup(g, matrices, vc, light, overlay, rootYaw, r, gr, b, a, false, null);
    }

    /** Transformações por osso (animações): deixa quem chama girar/escalar/esconder cada grupo pelo nome. */
    public interface BoneHandler {
        /** Aplica a transformação do osso na matriz (já no pivô em pixels); devolve false para esconder o osso e seus filhos. */
        boolean transform(String bone, MatrixStack m, float px, float py, float pz);

        /** Se os cubos do próprio osso entram nesta passada (os filhos são decididos por eles mesmos). */
        default boolean drawCubes(String bone) { return true; }
    }

    /** Desenha o modelo deixando o {@code handler} animar os ossos (porta: pivô "door", luzes por escala). */
    public void render(MatrixStack matrices, VertexConsumer vc, int light, int overlay, BoneHandler handler) {
        render(matrices, vc, light, overlay, handler, null);
    }

    /**
     * Igual ao anterior, mas se {@code sprite} não for nulo os cubos desenhados usam essa textura (a de um bloco do mundo,
     * do atlas de blocos) em vez das UVs do modelo: cada face é repartida em ladrilhos de 16 px, como um bloco normal.
     */
    public void render(MatrixStack matrices, VertexConsumer vc, int light, int overlay, BoneHandler handler, Sprite sprite) {
        render(matrices, vc, light, overlay, handler, sprite, null);
    }

    /**
     * Modo sprite com a luz de cada célula 16x16x16 do modelo: {@code cellLight(col, row)} (col 0..2 no sentido do eixo x do
     * modelo, a partir de x=-24; row 0..2 a partir de y=0) dá a luz do bloco do mundo naquela célula.
     */
    public void render(MatrixStack matrices, VertexConsumer vc, int light, int overlay, BoneHandler handler, Sprite sprite,
                       java.util.function.IntBinaryOperator cellLight) {
        this.sprite = sprite;
        this.cellLight = cellLight;
        try {
            for (Group g : groups) renderGroup(g, matrices, vc, light, overlay, 0f, 255, 255, 255, 255, false, handler);
        } finally {
            this.sprite = null;
            this.cellLight = null;
        }
    }

    private Sprite sprite;   // só durante um render(..., sprite)
    private java.util.function.IntBinaryOperator cellLight;

    private static void tiledLocal(VertexConsumer vc, Matrix4f pos, Matrix3f nrm, Quad q, Sprite sp, int light, int overlay,
                              int cr, int cg, int cb, int ca) {
        // eixo u: v0 -> v3; eixo v: v0 -> v1 (mesma convenção das UVs lidas do modelo)
        float ux = q.p[9] - q.p[0], uy = q.p[10] - q.p[1], uz = q.p[11] - q.p[2];
        float vx = q.p[3] - q.p[0], vy = q.p[4] - q.p[1], vz = q.p[5] - q.p[2];
        float lu = (float) Math.sqrt(ux * ux + uy * uy + uz * uz), lv = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (lu < 1e-4f || lv < 1e-4f) return;
        int nu = Math.max(1, (int) Math.ceil(lu / 16f - 1e-4)), nv = Math.max(1, (int) Math.ceil(lv / 16f - 1e-4));
        for (int i = 0; i < nu; i++) {
            float s0 = i * 16f / lu, s1 = Math.min(1f, (i + 1) * 16f / lu);
            for (int j = 0; j < nv; j++) {
                float t0 = j * 16f / lv, t1 = Math.min(1f, (j + 1) * 16f / lv);
                float uA = sp.getFrameU(0), uB = sp.getFrameU((s1 - s0) * lu);
                float vA = sp.getFrameV(0), vB = sp.getFrameV((t1 - t0) * lv);
                float[][] st = {{s0, t0, uA, vA}, {s0, t1, uA, vB}, {s1, t1, uB, vB}, {s1, t0, uB, vA}};
                for (float[] c : st) {
                    vc.vertex(pos, q.p[0] + c[0] * ux + c[1] * vx, q.p[1] + c[0] * uy + c[1] * vy, q.p[2] + c[0] * uz + c[1] * vz)
                            .color(cr, cg, cb, ca)
                            .texture(c[2], c[3])
                            .overlay(overlay)
                            .light(light)
                            .normal(nrm, q.nx, q.ny, q.nz)
                            .next();
                }
            }
        }
    }

    private static final float CELL = 16f, GRID_X0 = -24f, GRID_Y0 = 0f, GRID_Z0 = -8f;

    private int lightOf(float x, float y, int fallback) {
        if (cellLight == null) return fallback;
        int col = Math.max(0, Math.min(2, (int) Math.floor((x - GRID_X0) / CELL)));
        int row = Math.max(0, Math.min(2, (int) Math.floor((y - GRID_Y0) / CELL)));
        return cellLight.applyAsInt(col, row);
    }

    /**
     * Desenha a face com a textura de um bloco, alinhada à grade de blocos do modelo (células de 16 px a partir de
     * x=-24, y=0, z=-8): as faces voltadas para os eixos são recortadas nas células e cada pedaço usa as coordenadas
     * da textura daquela célula, então as emendas coincidem com as dos blocos vizinhos. Faces inclinadas (chanfros) usam
     * ladrilhos locais. Faces coplanares de cubos diferentes são afastadas uma fração de pixel (sem z-fighting).
     */
    private void drawSprite(VertexConsumer vc, Matrix4f pos, Matrix3f nrm, Quad q, int light, int overlay,
                            int cr, int cg, int cb, int ca) {
        float off = (q.order % 8) * 0.04f;
        float[][] poly = new float[4][3];
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            poly[i][0] = q.p[i * 3] + q.nx * off;
            poly[i][1] = q.p[i * 3 + 1] + q.ny * off;
            poly[i][2] = q.p[i * 3 + 2] + q.nz * off;
            cx += poly[i][0] * 0.25f;
            cy += poly[i][1] * 0.25f;
        }
        float ax = Math.abs(q.nx), ay = Math.abs(q.ny), az = Math.abs(q.nz);
        int ia, ib;            // eixos do plano da face: a (u) e b (v)
        float a0, b0;          // origem da grade nesses eixos
        if (az > 0.999f)      { ia = 0; ib = 1; a0 = GRID_X0; b0 = GRID_Y0; }
        else if (ay > 0.999f) { ia = 0; ib = 2; a0 = GRID_X0; b0 = GRID_Z0; }
        else if (ax > 0.999f) { ia = 2; ib = 1; a0 = GRID_Z0; b0 = GRID_Y0; }
        else {
            tiledLocal(vc, pos, nrm, q, sprite, lightOf(cx - q.nx * 0.5f, cy - q.ny * 0.5f, light), overlay, cr, cg, cb, ca);
            return;
        }
        float amin = Float.MAX_VALUE, amax = -Float.MAX_VALUE, bmin = Float.MAX_VALUE, bmax = -Float.MAX_VALUE;
        for (float[] v : poly) {
            amin = Math.min(amin, v[ia]); amax = Math.max(amax, v[ia]);
            bmin = Math.min(bmin, v[ib]); bmax = Math.max(bmax, v[ib]);
        }
        int ca0 = (int) Math.floor((amin - a0) / CELL + 1e-4f), ca1 = (int) Math.ceil((amax - a0) / CELL - 1e-4f) - 1;
        int cb0 = (int) Math.floor((bmin - b0) / CELL + 1e-4f), cb1 = (int) Math.ceil((bmax - b0) / CELL - 1e-4f) - 1;
        for (int ca_ = ca0; ca_ <= ca1; ca_++) {
            for (int cb_ = cb0; cb_ <= cb1; cb_++) {
                float la0 = a0 + ca_ * CELL, lb0 = b0 + cb_ * CELL;
                java.util.List<float[]> pts = new ArrayList<>(java.util.Arrays.asList(poly));
                pts = clip(pts, ia, la0, true);
                pts = clip(pts, ia, la0 + CELL, false);
                pts = clip(pts, ib, lb0, true);
                pts = clip(pts, ib, lb0 + CELL, false);
                if (pts.size() < 3) continue;
                float mx = 0, my = 0;
                for (float[] v : pts) { mx += v[0] / pts.size(); my += v[1] / pts.size(); }
                int l = lightOf(mx - q.nx * 0.5f, my - q.ny * 0.5f, light);
                float[][] st = new float[pts.size()][2];
                for (int k = 0; k < st.length; k++) {
                    float la = pts.get(k)[ia] - la0, lb = pts.get(k)[ib] - lb0;
                    float u, v;
                    if (az > 0.999f)      { u = q.nz > 0 ? la : CELL - la; v = CELL - lb; }
                    else if (ay > 0.999f) { u = la; v = q.ny > 0 ? lb : CELL - lb; }
                    else                  { u = q.nx > 0 ? CELL - la : la; v = CELL - lb; }
                    st[k][0] = sprite.getFrameU(u);
                    st[k][1] = sprite.getFrameV(v);
                }
                for (int k = 1; k + 1 < pts.size(); k++) {   // leque de triângulos (último vértice repetido = triângulo)
                    int[] idx = {0, k, k + 1, k + 1};
                    for (int id : idx) {
                        float[] v = pts.get(id);
                        vc.vertex(pos, v[0], v[1], v[2])
                                .color(cr, cg, cb, ca)
                                .texture(st[id][0], st[id][1])
                                .overlay(overlay)
                                .light(l)
                                .normal(nrm, q.nx, q.ny, q.nz)
                                .next();
                    }
                }
            }
        }
    }

    /** Sutherland-Hodgman: mantém a parte do polígono em que coord[axis] >= bound (keepGreater) ou <= bound. */
    private static java.util.List<float[]> clip(java.util.List<float[]> in, int axis, float bound, boolean keepGreater) {
        java.util.List<float[]> out = new ArrayList<>();
        int n = in.size();
        for (int i = 0; i < n; i++) {
            float[] a = in.get(i), b = in.get((i + 1) % n);
            boolean ain = keepGreater ? a[axis] >= bound - 1e-4f : a[axis] <= bound + 1e-4f;
            boolean bin = keepGreater ? b[axis] >= bound - 1e-4f : b[axis] <= bound + 1e-4f;
            if (ain) out.add(a);
            if (ain != bin) {
                float t = (bound - a[axis]) / (b[axis] - a[axis]);
                out.add(new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t});
            }
        }
        return out;
    }

    /**
     * Desenha SÓ os cubos de casca invertida (contornos). Precisa de um RenderLayer COM culling de faces
     * (ex.: entityCutout / eyes): assim só aparecem as paredes de trás, formando o contorno em volta da peça vermelha.
     */
    public void renderHull(MatrixStack matrices, VertexConsumer vc, int light, int overlay, float rootYaw,
                           int r, int gr, int b, int a) {
        for (Group g : groups) renderGroup(g, matrices, vc, light, overlay, rootYaw, r, gr, b, a, true, null);
    }

    private void renderGroup(Group g, MatrixStack m, VertexConsumer vc, int light, int overlay, float rootYaw,
                             int cr, int cg, int cb, int ca, boolean hullPass, BoneHandler handler) {
        m.push();
        if (handler != null && !handler.transform(g.name, m, g.px, g.py, g.pz)) {
            m.pop();
            return;
        }
        if (ROOT.equals(g.name)) {
            m.translate(g.px, g.py, g.pz);
            m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rootYaw));
            m.translate(-g.px, -g.py, -g.pz);
        }
        MatrixStack.Entry e = m.peek();
        Matrix4f pos = e.getPositionMatrix();
        Matrix3f nrm = e.getNormalMatrix();
        boolean draw = handler == null || handler.drawCubes(g.name);
        for (Quad q : g.quads) {
            if (!draw || q.hull != hullPass) continue;
            if (sprite != null) {
                drawSprite(vc, pos, nrm, q, light, overlay, cr, cg, cb, ca);
                continue;
            }
            for (int i = 0; i < 4; i++) {
                vc.vertex(pos, q.p[i * 3], q.p[i * 3 + 1], q.p[i * 3 + 2])
                        .color(cr, cg, cb, ca)
                        .texture(q.uv[i * 2], q.uv[i * 2 + 1])
                        .overlay(overlay)
                        .light(light)
                        .normal(nrm, q.nx, q.ny, q.nz)
                        .next();
            }
        }
        for (Group c : g.children) renderGroup(c, m, vc, light, overlay, rootYaw, cr, cg, cb, ca, hullPass, handler);
        m.pop();
    }
}
