#version 150

// Visão da câmera: fisheye + VHS + visor (vinheta, moldura arredondada, enquadramento de foco e mira).
// Sem tingimento verde: as cores originais são mantidas (levemente dessaturadas, como fita).

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

    // ---------- 2) VHS ----------
    float wobble = sin(uv.y * 60.0 + Time * 6.2831 * 100.0) * 0.0012;
    float glitchLine = step(0.985, rand(vec2(floor(Time * 6.2831 * 40.0), floor(uv.y * 30.0))));
    uv.x += wobble + glitchLine * 0.02;

    float ab = 0.003 + glitchLine * 0.01;
    float r = texture(DiffuseSampler, uv + vec2(ab, 0.0)).r;
    float g = texture(DiffuseSampler, uv).g;
    float b = texture(DiffuseSampler, uv - vec2(ab, 0.0)).b;
    vec3 col = vec3(r, g, b);

    col *= 0.88 + 0.12 * sin(gl_FragCoord.y * 1.6);                  // scanlines
    col += (rand(uv * 900.0 + Time * 50.0) - 0.5) * 0.12;            // ruído

    float dist = abs(fract(uv.y + Time * 10.0) - 0.5);               // barra de tracking
    float bar = 1.0 - smoothstep(0.0, 0.04, dist);
    col *= 1.0 + bar * 0.4;

    // aspecto de fita: um pouco menos saturado e com mais contraste (cores neutras, sem verde)
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(lum), col, 0.82);
    col = (col - 0.5) * 1.08 + 0.52;

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
