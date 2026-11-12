#version 150

uniform sampler2D DiffuseSampler;
uniform float Time;   // 0..1 a cada ~20s

in vec2 texCoord;
out vec4 fragColor;

float rand(vec2 co) {
    return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
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

    // ---------- 3) CREEPER / VISÃO NOTURNA ----------
    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    lum = pow(clamp(lum * 1.7, 0.0, 1.0), 0.8);
    col = vec3(0.15, 1.0, 0.25) * lum;

    col *= 1.0 - 0.55 * dot(c, c) * 0.5;                              // vinheta

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) col = vec3(0.0);

    fragColor = vec4(col, 1.0);
}
