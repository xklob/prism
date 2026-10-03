#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform vec2 uResolution;
uniform vec2 uTouch;
uniform float uTime;
uniform float uIntensity;
uniform int uMode;
uniform int uPreviousMode;
uniform float uTransition;
uniform int uPalette;
uniform vec4 uAudio;
uniform float uBeat;
uniform float uBands[32];
const float PI = 3.14159265359;

mat2 rotate(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }
vec3 palette(float t) {
    if (uPalette == 0) return 0.5 + 0.5 * cos(6.28318 * (t + vec3(0.0, 0.32, 0.66)));
    vec3 a = vec3(0.48, 0.12, 0.68);
    vec3 b = vec3(1.0, 0.22, 0.39);
    vec3 c = vec3(1.0, 0.69, 0.22);
    if (uPalette == 2) { a = vec3(0.16, 0.20, 0.79); b = vec3(0.16, 0.72, 0.91); c = vec3(0.57, 1.0, 0.78); }
    if (uPalette == 3) { a = vec3(0.43, 0.12, 1.0); b = vec3(0.84, 0.17, 0.85); c = vec3(0.77, 1.0, 0.18); }
    float phase = fract(t) * 3.0;
    if (phase < 1.0) return mix(a, b, smoothstep(0.0, 1.0, phase));
    if (phase < 2.0) return mix(b, c, smoothstep(0.0, 1.0, phase - 1.0));
    return mix(c, a, smoothstep(0.0, 1.0, phase - 2.0));
}
float band(float position) {
    float index = clamp(position, 0.0, 1.0) * 31.0;
    int low = int(floor(index));
    return mix(uBands[low], uBands[min(low + 1, 31)], fract(index));
}
float glow(float distance, float width) { return width / (abs(distance) + width); }

vec3 aurora(vec2 p, float t) {
    vec2 q = p * 1.75;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        q += 0.36 * vec2(sin(q.y * 1.8 + t * 0.32 + fi * 1.7), cos(q.x * 1.7 - t * 0.27 + fi * 2.1));
        q = rotate(0.34) * q;
    }
    float flow = sin(q.x * 2.8 + q.y * 1.6 + t * 0.25);
    float veins = abs(sin(q.y * 4.2 - q.x * 2.4 + t * 0.3));
    vec3 color = palette(q.x * 0.15 + q.y * 0.12 + t * 0.024);
    color *= 0.22 + 0.52 * pow(0.5 + 0.5 * flow, 2.0);
    color += palette(q.y * 0.14 + 0.28 + t * 0.016) * pow(veins, 22.0) * 0.7;
    color += vec3(0.15, 0.12, 0.27) * pow(veins, 90.0);
    return color;
}

vec3 kaleido(vec2 p, float t) {
    p = rotate(t * 0.05) * p;
    float r = length(p);
    float angle = atan(p.y, p.x);
    float sector = PI / 6.0;
    angle = abs(mod(angle + sector * 0.5, sector) - sector * 0.5);
    vec2 q = vec2(cos(angle), sin(angle)) * r;
    vec3 color = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec2 z = q * (2.4 + fi * 0.9);
        z += vec2(t * 0.1, -t * 0.08);
        z = abs(fract(z + 0.5) - 0.5);
        float d = length(z) - 0.23 - 0.09 * sin(r * 5.0 - t * 0.6 + fi);
        color += palette(fi * 0.17 + r * 0.14 - t * 0.022) * pow(glow(d, 0.018), 1.8) * 0.52;
    }
    return color;
}

vec3 wormhole(vec2 p, float t) {
    float r = max(length(p), 0.018);
    float a = atan(p.y, p.x);
    float depth = -log(r) * 2.5 + t * 0.7;
    float twist = a * 5.0 + sin(depth * 0.8 - t * 0.3) * 1.7;
    float grid = pow(abs(sin(depth * 4.0)), 24.0);
    float rails = pow(abs(cos(twist)), 24.0);
    float web = pow(abs(sin(twist + depth * 2.0)), 40.0);
    vec3 c = palette(depth * 0.08 + a / PI * 0.3 + t * 0.015);
    c *= 0.07 + grid * 0.75 + rails * 0.5 + web * 0.2;
    c *= smoothstep(0.02, 0.26, r);
    c += palette(t * 0.03) * 0.025 / (r + 0.08);
    return c;
}

vec3 pulse(vec2 p, float t) {
    float r = length(p);
    float a = atan(p.y, p.x) + t * 0.065;
    float bass = uAudio.x;
    vec3 color = vec3(0.0);
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float petals = sin(a * (6.0 + 2.0 * mod(fi, 2.0)) + fi * 0.65 + t * 0.3);
        float radius = 0.17 + fi * 0.145 + bass * 0.17 + petals * (0.026 + uAudio.y * 0.085);
        float d = r - radius;
        color += palette(fi * 0.09 - t * 0.027 + bass * 0.15) * pow(glow(d, 0.009 + bass * 0.014), 1.7) * (0.5 + bass * 0.65);
    }
    float sparks = pow(max(0.0, cos(a * 36.0 + t * 0.4)), 18.0);
    color += palette(a / PI + t * 0.05) * sparks * exp(-abs(r - 0.8 - bass * 0.2) * 22.0) * uAudio.z;
    color += palette(0.4 + t * 0.02) * 0.018 / (r + 0.055) * (0.5 + uBeat * 0.8);
    return color;
}

vec3 strings(vec2 p, float t) {
    vec3 color = vec3(0.0);
    for (int i = 0; i < 18; i++) {
        float fi = float(i) / 17.0;
        float frequency = band(fi);
        float y = (fi - 0.5) * 2.25;
        y += sin(p.x * (3.0 + fi * 7.0) + t * (0.4 + fi * 0.3) + fi * 4.0) * (0.04 + frequency * 0.3 + uAudio.x * 0.07);
        y += sin(p.x * 2.0 - t * 0.3) * 0.12;
        float d = p.y - y;
        vec3 c = palette(fi * 0.7 + p.x * 0.08 + t * 0.022);
        color += c * pow(glow(d, 0.007 + frequency * 0.011), 1.8) * (0.35 + frequency * 1.5);
    }
    return color;
}

vec3 nova(vec2 p, float t) {
    float r = length(p);
    float a = atan(p.y, p.x);
    float spectrum = band(abs(sin(a * 1.5 + t * 0.04)));
    float petals = sin(a * 7.0 + sin(a * 3.0 - t * 0.2) * 1.2 + t * 0.23);
    float shape = r - (0.5 + petals * (0.14 + uAudio.x * 0.12) + spectrum * 0.15);
    vec3 color = palette(a / PI * 0.45 + r * 0.35 - t * 0.025) * pow(glow(shape, 0.022 + uAudio.y * 0.025), 1.6);
    float echo = abs(sin(shape * (18.0 + uAudio.z * 10.0) - t * 0.8));
    color += palette(r * 0.65 - t * 0.05 + 0.3) * pow(echo, 28.0) * exp(-abs(shape) * 2.3) * (0.14 + uAudio.w * 0.45);
    float corona = pow(max(0.0, cos(a * 56.0 + sin(r * 8.0 - t))), 24.0);
    color += palette(a / PI + t * 0.02) * corona * exp(-abs(r - 0.95) * 10.0) * (0.1 + uAudio.z);
    return color;
}

vec3 scene(int mode, vec2 p, float t) {
    if (mode == 0) return aurora(p, t);
    if (mode == 1) return kaleido(p, t);
    if (mode == 2) return wormhole(p, t);
    if (mode == 3) return pulse(p, t);
    if (mode == 4) return strings(p, t);
    return nova(p, t);
}

void main() {
    vec2 p = (vUv * 2.0 - 1.0) * uResolution / min(uResolution.x, uResolution.y);
    p -= uTouch * 0.22;
    p *= 0.84;
    vec3 color = scene(uMode, p, uTime);
    if (uTransition < 1.0) color = mix(scene(uPreviousMode, p, uTime), color, smoothstep(0.0, 1.0, uTransition));
    color *= uIntensity * 1.25;
    color = 1.0 - exp(-color * 1.35);
    color *= 0.64 + 0.36 * pow(max(0.0, 1.0 - length(vUv - 0.5) * 1.1), 0.7);
    color += vec3(0.012, 0.014, 0.03);
    fragColor = vec4(color, 1.0);
}
