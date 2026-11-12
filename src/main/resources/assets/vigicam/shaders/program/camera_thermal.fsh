#version 150

uniform sampler2D DiffuseSampler;      // cena
uniform sampler2D MainDepthSampler;    // profundidade da cena
uniform sampler2D EntitySampler;       // silhuetas das entidades (R = calor 0..1, A = máscara)
uniform sampler2D EntityDepthSampler;  // profundidade das entidades
uniform vec2 OutSize;
uniform float Time;                    // 0..1 a cada ~20s

in vec2 texCoord;
out vec4 fragColor;

// true = enxerga entidades através das paredes
const bool XRAY = false;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

// paleta térmica: azul escuro -> azul -> ciano -> verde -> amarelo -> laranja -> vermelho -> branco
vec3 heatColor(float t) {
    const vec3 P[8] = vec3[8](
        vec3(0.02, 0.00, 0.20),
        vec3(0.00, 0.12, 0.85),
        vec3(0.00, 0.75, 0.95),
        vec3(0.10, 0.85, 0.20),
        vec3(1.00, 0.95, 0.05),
        vec3(1.00, 0.45, 0.00),
        vec3(0.95, 0.05, 0.25),
        vec3(1.00, 0.97, 0.92)
    );
    float x = clamp(t, 0.0, 1.0) * 7.0;
    int i = int(min(floor(x), 6.0));
    return mix(P[i], P[i + 1], x - float(i));
}

// entidade visível neste ponto? (silhueta presente e não tapada por bloco)
float entityHeatAt(vec2 uv, float sceneDepth) {
    vec4 e = texture(EntitySampler, uv);
    if (e.a < 0.5) return 0.0;
    float ed = texture(EntityDepthSampler, uv).r;
    if (!XRAY && ed > sceneDepth + 0.000002) return 0.0;
    return e.r;
}

void main() {
    // leve pixelização: sensor térmico tem resolução baixa
    float cell = max(1.0, floor(OutSize.y / 360.0));
    vec2 uv = (floor(texCoord * OutSize / cell) + 0.5) * cell / OutSize;

    vec3 scene = texture(DiffuseSampler, uv).rgb;
    float lum = dot(scene, vec3(0.299, 0.587, 0.114));
    float sceneDepth = texture(MainDepthSampler, uv).r;
    bool sky = sceneDepth >= 0.99999;

    // ---------- 1) TEMPERATURA DO AMBIENTE ----------
    float t = 0.10 + 0.34 * smoothstep(0.05, 0.85, lum);          // iluminado = mais quente

    float fire = smoothstep(0.25, 0.60, scene.r - scene.b) * smoothstep(0.55, 0.90, scene.r);
    t += fire * 0.55;                                              // lava, fogo, tocha

    float cold = smoothstep(0.10, 0.35, scene.b - scene.r);
    t *= 1.0 - 0.5 * cold;                                         // água / gelo = frio

    t *= mix(1.0, 0.6, smoothstep(0.9980, 0.99995, sceneDepth));   // longe esfria um pouco

    if (sky) t = 0.02 + 0.04 * lum;                                // céu: o mais frio

    // ---------- 2) ENTIDADES ----------
    float heat = entityHeatAt(uv, sceneDepth);

    // brilho (halo) de calor ao redor dos corpos
    float rad = OutSize.y / 135.0;
    float halo = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec2 dir = vec2(cos(a), sin(a)) / OutSize;
        halo += entityHeatAt(uv + dir * rad * 0.5, sceneDepth);
        halo += entityHeatAt(uv + dir * rad, sceneDepth);
    }
    halo /= 16.0;

    if (heat > 0.0) {
        t = heat * clamp(0.80 + 0.35 * lum, 0.0, 1.1);             // mantém um pouco do detalhe do modelo
    } else {
        t = max(t, halo * 0.60);
    }

    // ---------- 3) SENSOR ----------
    t += (rand(uv * 700.0 + Time * 60.0) - 0.5) * 0.02;            // ruído
    t += 0.01 * sin(gl_FragCoord.y * 0.8 + Time * 400.0);          // faixas

    vec3 col = heatColor(t);

    vec2 c = texCoord * 2.0 - 1.0;
    col *= 1.0 - 0.30 * dot(c, c) * 0.5;                           // vinheta

    fragColor = vec4(col, 1.0);
}
