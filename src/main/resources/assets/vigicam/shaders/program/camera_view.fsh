#version 150

// Visão da câmera: fisheye + visor (vinheta, moldura arredondada, enquadramento de foco e mira).
// Sem VHS e sem tingimento: as cores originais são mantidas.

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Time;   // 0..1 a cada ~20s

in vec2 texCoord;
out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

// distância assinada até um retângulo de cantos arredondados
float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float aspect = OutSize.x / max(OutSize.y, 1.0);

    // ---------- 1) FISHEYE ----------
    vec2 c = texCoord * 2.0 - 1.0;
    float k = 0.35;
    float r2 = dot(c, c);
    vec2 d = c * (1.0 + k * r2) / (1.0 + k * 2.0);
    vec2 uv = d * 0.5 + 0.5;

    // ---------- 2) IMAGEM LIMPA (sem VHS: sem tremor, aberração, scanlines, ruído nem barra) ----------
    vec3 col = texture(DiffuseSampler, uv).rgb;

    // ---------- 3) VISOR DA CÂMERA ----------
    vec2 p = vec2(c.x * aspect, c.y);                                 // unidades de altura da tela

    // vinheta
    col *= 1.0 - 0.55 * smoothstep(0.45, 1.45, length(c));

    // enquadramento de foco: quatro cantos no centro
    vec2 q = abs(p);
    float s = 0.34, l = 0.08, t = 0.0055;
    float edgeX = step(abs(q.x - s), t) * step(q.y, s + t) * step(s - l, q.y);
    float edgeY = step(abs(q.y - s), t) * step(q.x, s + t) * step(s - l, q.x);
    float frame = max(edgeX, edgeY);

    // mira central (com vazio no meio)
    float crossH = step(abs(p.y), t * 0.6) * step(0.014, abs(p.x)) * step(abs(p.x), 0.05);
    float crossV = step(abs(p.x), t * 0.6) * step(0.014, abs(p.y)) * step(abs(p.y), 0.05);
    float mark = max(frame, max(crossH, crossV));
    float flicker = 0.82 + 0.1 * rand(vec2(floor(Time * 6.2831 * 30.0), 3.0));
    col = mix(col, vec3(0.96), mark * flicker);

    // moldura arredondada (borda preta da lente/visor)
    float sd = sdRoundBox(p, vec2(aspect, 1.0), 0.12);
    col *= 1.0 - smoothstep(-0.02, 0.0, sd);

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) col = vec3(0.0);

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
