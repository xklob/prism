#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform vec2 uResolution;
uniform vec2 uTouch;
uniform float uTime;
uniform float uRotation;
uniform float uColorPhase;
uniform float uMorphTime;
uniform float uIntensity;
uniform float uComplexity;
uniform float uSymmetry;
uniform float uDistortion;
uniform float uZoom;
uniform float uLineWidth;
uniform float uHue;
uniform float uSaturation;
uniform float uContrast;
uniform int uMode;
uniform int uPreviousMode;
uniform float uTransition;
uniform int uPalette;
uniform vec4 uAudio;
uniform float uBeat;
const float PI = 3.14159265359;
const float TAU = 6.28318530718;

mat2 rotate(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }
vec3 palette(float t) {
    t += uHue + uColorPhase + uAudio.z * 0.14;
    if (uPalette == 0) return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
    vec3 a = vec3(0.48, 0.12, 0.68), b = vec3(1.0, 0.22, 0.39), c = vec3(1.0, 0.69, 0.22);
    if (uPalette == 2) { a = vec3(0.16, 0.20, 0.79); b = vec3(0.16, 0.72, 0.91); c = vec3(0.57, 1.0, 0.78); }
    if (uPalette == 3) { a = vec3(0.43, 0.12, 1.0); b = vec3(0.84, 0.17, 0.85); c = vec3(0.77, 1.0, 0.18); }
    float phase = fract(t) * 3.0;
    if (phase < 1.0) return mix(a, b, smoothstep(0.0, 1.0, phase));
    if (phase < 2.0) return mix(b, c, smoothstep(0.0, 1.0, phase - 1.0));
    return mix(c, a, smoothstep(0.0, 1.0, phase - 2.0));
}
// Pixel-width contours with a one-pixel antialiasing fringe. No glow or blur tails.
float line(float field, float weight) {
    float gradient = max(length(vec2(dFdx(field), dFdy(field))), 0.00001);
    float pixels = abs(field) / gradient;
    float halfWidth = uLineWidth * weight * max(1.0, min(uResolution.x, uResolution.y) / 720.0) * 0.5;
    return 1.0 - smoothstep(max(0.0, halfWidth - 0.65), halfWidth + 0.65, pixels);
}
float periodic(float phase, float weight) { return line(sin(phase), weight); }
vec2 fold(vec2 p, float segments) {
    float r = length(p), wedge = TAU / segments;
    float a = abs(mod(atan(p.y, p.x) + wedge * 0.5, wedge) - wedge * 0.5);
    return vec2(cos(a), sin(a)) * r;
}

vec3 aurora(vec2 p, float t) {
    vec2 q = p * 1.5;
    float m = uMorphTime;
    for (int i = 0; i < 5; i++) {
        float f = float(i);
        q += (uDistortion * 0.31 + uAudio.y * 0.19) * vec2(sin(q.y * 1.7 + m * 0.3 + f + uAudio.x * 0.5), cos(q.x * 1.65 - m * 0.23 + f * 1.7));
        q = rotate(0.31) * q;
    }
    float frequency = (9.0 + uComplexity * 26.0) * (1.0 + uAudio.z * 0.12);
    float phase = q.x * frequency + sin(q.y * (1.5 + uSymmetry * 0.2)) * 2.2 - t * 0.4;
    float crossing = q.y * frequency * 0.82 + cos(q.x * 2.1 - m * 0.2 + uAudio.y) * 2.2;
    float ribbons = periodic(phase, 1.0);
    float threads = periodic(crossing, 0.85);
    float etch = periodic(phase * 2.0 + crossing * 0.3, 0.65);
    vec3 c = palette(q.x * 0.13 + q.y * 0.10) * ribbons;
    c += palette(q.y * 0.14 + 0.32) * threads * 0.72;
    c += palette(q.x * 0.1 + 0.64) * etch * (0.15 + uComplexity * 0.27);
    float cells = sin(phase) * sin(crossing);
    c += palette(cells * 0.07 + q.y * 0.1) * step(0.88, cells) * 0.10;
    return c;
}

vec3 kaleido(vec2 p, float t) {
    vec2 q = fold(p, uSymmetry);
    float radius = length(p);
    vec3 c = vec3(0.0);
    int layers = 3 + int(uComplexity * 6.0);
    float m = uMorphTime;
    for (int i = 0; i < 9; i++) {
        if (i >= layers) break;
        float f = float(i);
        q = abs(q) - vec2(0.46 + 0.05 * sin(m * 0.4 + f) + uAudio.x * 0.08, 0.19 + uDistortion * 0.085 + uAudio.z * 0.026);
        q = rotate(0.52 + uDistortion * 0.34 + sin(m * 0.15) * 0.08 + uAudio.y * 0.24) * q * 1.46;
        float diamond = abs(q.x) + abs(q.y) - 0.29;
        float circle = length(q - vec2(0.12, 0.0)) - 0.20 - uAudio.z * 0.045;
        float frame = max(abs(q.x), abs(q.y)) - 0.33;
        float detail = line(diamond, 1.0) + line(circle, 0.75) * 0.65 + line(frame, 0.65) * 0.38;
        c += palette(f * 0.115 + radius * 0.15) * detail * (0.67 - f * 0.035);
    }
    c += palette(radius * 0.3 + 0.2) * periodic(radius * (11.0 + uComplexity * 11.0) - t * 0.12, 0.7) * 0.25;
    return c;
}

vec3 wormhole(vec2 p, float t) {
    float r = max(length(p), 0.018);
    float a = atan(p.y, p.x);
    float depth = -log(r) * (3.0 + uComplexity * 4.0) + t * 0.75 + uAudio.x * 0.85 + uBeat * 0.32;
    float around = a * uSymmetry / TAU + (uDistortion + uAudio.y * 0.65) * (depth * 0.20 + sin(depth * 0.4 + uMorphTime * 0.2));
    vec2 tile = vec2(around, depth);
    vec2 cell = fract(tile) - 0.5;
    float circuit = periodic(PI * tile.x, 1.0) + periodic(PI * tile.y, 0.85);
    float diagonal = periodic(PI * (tile.x + tile.y), 0.65);
    float inlay = line(max(abs(cell.x), abs(cell.y)) - 0.31, 0.8);
    float gem = line(abs(cell.x) + abs(cell.y) - 0.20 - uAudio.z * 0.10, 0.75);
    vec3 c = palette(depth * 0.055 + a / TAU * 0.2) * circuit * 0.8;
    c += palette(depth * 0.055 + 0.35) * (diagonal * 0.3 + inlay * 0.55 + gem * 0.65);
    return c * smoothstep(0.022, 0.10, r);
}

vec3 julia(vec2 p, float t) {
    vec2 z = p * 1.1;
    float shape = uDistortion / 1.5;
    vec2 constant = mix(vec2(-0.745, 0.186), vec2(-0.40, 0.59), shape);
    constant += vec2(sin(uMorphTime * 0.12), cos(uMorphTime * 0.1)) * 0.009;
    // Small movements of c produce large, intricate changes at the fractal boundary.
    constant += vec2(uAudio.y * 0.052, uAudio.x * 0.025 - uAudio.y * 0.028);
    float escaped = 0.0, count = 0.0, trap = 10.0;
    int iterations = 40 + int(uComplexity * 104.0);
    for (int i = 0; i < 144; i++) {
        if (i >= iterations) break;
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + constant;
        trap = min(trap, abs(length(z) - 0.7 - uAudio.z * 0.13));
        float magnitude = dot(z, z);
        if (magnitude > 128.0) {
            count = float(i) + 1.0 - log2(max(0.001, log2(magnitude) * 0.5));
            escaped = 1.0;
            break;
        }
    }
    float contours = periodic(count * (1.8 + uAudio.z * 0.25), 1.0);
    vec3 outside = palette(count * 0.026) * (0.18 + contours * 0.82);
    vec3 inside = palette(trap * 6.0 + 0.3) * periodic(trap * 140.0, 1.0) * 0.65;
    return mix(inside, outside, escaped);
}

vec3 scene(int mode, vec2 p, float t) {
    if (mode == 0) return aurora(p, t);
    if (mode == 1) return kaleido(p, t);
    if (mode == 2) return wormhole(p, t);
    return julia(p, t);
}
void main() {
    vec2 p = (vUv * 2.0 - 1.0) * uResolution / min(uResolution.x, uResolution.y);
    p -= uTouch * 0.3;
    // The same scene remains visible with audio off; every audio term then equals zero.
    float expansion = 1.0 + uAudio.x * 0.30 + uBeat * 0.10;
    p = rotate(uRotation + uAudio.y * 0.10) * p * (0.95 / (uZoom * expansion));
    vec3 color = scene(uMode, p, uTime);
    if (uTransition < 1.0) color = mix(scene(uPreviousMode, p, uTime), color, smoothstep(0.0, 1.0, uTransition));
    color = clamp(color * uIntensity * 1.22, 0.0, 1.0);
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(vec3(luminance), color, uSaturation), 0.0, 1.0);
    color = pow(color, vec3(uContrast));
    fragColor = vec4(max(color, vec3(0.004, 0.006, 0.012)), 1.0);
}
