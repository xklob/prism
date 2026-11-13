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
// Beat pulse, bar accent, musical flow, optional treble texture.
uniform vec4 uRhythm;
uniform vec2 uDownbeat; // Brief white flash and fading RGB inversion on beat 1.
uniform float uSpectrum[64];
uniform float uPeaks[64];
uniform int uEqCount;
uniform float uEqSpacing;
uniform int uEqMirror;
uniform int uEqRadial;
uniform int uEqSegments;
const float PI = 3.14159265359;
const float TAU = 6.28318530718;

mat2 rotate(float a) { return mat2(cos(a), -sin(a), sin(a), cos(a)); }
vec3 palette(float t) {
    t += uHue + uColorPhase + (uRhythm.z + uRhythm.w) * 0.14;
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
float periodic(float phase, float weight) {
    // Suppress detail finer than a pixel instead of letting it flicker at the tunnel core.
    float footprint = length(vec2(dFdx(phase), dFdy(phase)));
    return line(sin(phase), weight) * (1.0 - smoothstep(1.1, 2.8, footprint));
}
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
        q += (uDistortion * 0.31 + uRhythm.y * 0.19) * vec2(sin(q.y * 1.7 + m * 0.3 + f + uRhythm.x * 0.5), cos(q.x * 1.65 - m * 0.23 + f * 1.7));
        q = rotate(0.31) * q;
    }
    float frequency = (9.0 + uComplexity * 26.0) * (1.0 + (uRhythm.z + uRhythm.w) * 0.12);
    float phase = q.x * frequency + sin(q.y * (1.5 + uSymmetry * 0.2)) * 2.2 - t * 0.4;
    float crossing = q.y * frequency * 0.82 + cos(q.x * 2.1 - m * 0.2 + uRhythm.y) * 2.2;
    // Broad satin folds give the fine contours a readable structure and sense of depth.
    float foldLight = 0.5 + 0.5 * sin(phase * 0.18 + sin(q.y * 1.2) * 1.4);
    float ribbons = periodic(phase, 1.15);
    float threads = periodic(crossing, 0.72);
    float weave = smoothstep(-0.35, 0.35, sin(phase * 0.18));
    vec3 base = palette(q.x * 0.065 + q.y * 0.045);
    vec3 c = base * (0.035 + pow(foldLight, 2.0) * 0.22);
    c += base * ribbons * (0.45 + foldLight * 0.35);
    c = mix(c, palette(q.y * 0.07 + 0.18) * 0.75, threads * (0.18 + weave * 0.42));
    c += palette(q.x * 0.06 + 0.12) * periodic(phase * 0.25, 1.8) * 0.24;
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
        q = abs(q) - vec2(0.46 + 0.05 * sin(m * 0.4 + f) + uRhythm.x * 0.08, 0.19 + uDistortion * 0.085 + (uRhythm.z + uRhythm.w) * 0.026);
        q = rotate(0.52 + uDistortion * 0.34 + sin(m * 0.15) * 0.08 + uRhythm.y * 0.24) * q * 1.46;
        float diamond = abs(q.x) + abs(q.y) - 0.29;
        float circle = length(q - vec2(0.12, 0.0)) - 0.20 - (uRhythm.z + uRhythm.w) * 0.045;
        float frame = max(abs(q.x), abs(q.y)) - 0.33;
        float detail = max(line(diamond, 1.2), max(line(circle, 0.85) * 0.7, line(frame, 0.7) * 0.3));
        float hierarchy = pow(0.84, f);
        vec3 ink = palette(f * 0.065 + radius * 0.09);
        float facet = (1.0 - smoothstep(0.0, 0.05, diamond)) * smoothstep(-0.32, -0.20, diamond);
        c += ink * facet * (0.10 + 0.08 * smoothstep(-0.3, 0.3, q.y)) * hierarchy;
        c = mix(c, ink, detail * (0.85 * hierarchy + 0.12));
        c += ink * exp(-abs(diamond) * 5.0) * 0.025 * hierarchy;
    }
    float petals = radius * (1.0 + 0.045 * cos(atan(p.y, p.x) * uSymmetry));
    c += palette(radius * 0.1 + 0.12) * periodic(petals * 7.0 - t * 0.07, 1.2) * 0.25;
    c *= 0.82 + 0.18 * cos(radius * 1.7);
    return c;
}

vec3 wormhole(vec2 p, float t) {
    float r = max(length(p), 0.004);
    float a = atan(p.y, p.x);
    float depth = -log(r) * (3.0 + uComplexity * 4.0) + t * 0.75 + uRhythm.x * 0.85 + uRhythm.y * 0.32;
    float around = a * uSymmetry / TAU + (uDistortion + uRhythm.y * 0.65) * (depth * 0.20 + sin(depth * 0.4 + uMorphTime * 0.2));
    vec2 tile = vec2(around, depth);
    vec2 cell = fract(tile) - 0.5;
    float circuit = max(periodic(PI * tile.x, 1.15), periodic(PI * tile.y, 1.0));
    float diagonal = periodic(PI * (tile.x + tile.y), 0.65);
    float inlay = line(max(abs(cell.x), abs(cell.y)) - 0.31, 0.8);
    float gem = line(abs(cell.x) + abs(cell.y) - 0.20 - (uRhythm.z + uRhythm.w) * 0.10, 0.75);
    float detailFade = 1.0 - smoothstep(0.5, 1.5, length(fwidth(tile)));
    // Periodic angular coloring closes the atan seam, including odd facet counts.
    vec3 ink = palette(depth * 0.035 + cos(a * 2.0) * 0.055);
    float panel = pow(max(0.0, 1.0 - max(abs(cell.x), abs(cell.y)) * 2.0), 0.6);
    vec3 c = ink * (0.035 + panel * 0.10) * detailFade;
    c += ink * circuit * 0.75;
    c = mix(c, palette(depth * 0.035 + 0.16), (inlay * 0.42 + gem * 0.32) * detailFade);
    c += ink * diagonal * 0.08;
    return c * smoothstep(0.008, 0.12, r);
}

vec3 mandelbrot(vec2 p, float t) {
    // A smooth out-and-back logarithmic dive avoids a hard reset and float-precision collapse.
    float travel = 0.5 - 0.5 * cos(t * 0.13);
    float zoom = exp2(travel * (4.0 + uDistortion * 3.0));
    vec2 target = vec2(-0.7436439, 0.1318259);
    vec2 center = mix(vec2(-0.5, 0.0), target, 1.0 - 1.0 / zoom);
    vec2 c = center + p * 1.55 / zoom;
    vec2 z = vec2(0.0), derivative = vec2(0.0);
    float count = 0.0, escaped = 0.0, distance = 0.0;
    int iterations = 80 + int(uComplexity * 176.0);
    for (int i = 0; i < 256; i++) {
        if (i >= iterations) break;
        derivative = 2.0 * vec2(z.x * derivative.x - z.y * derivative.y, z.x * derivative.y + z.y * derivative.x) + vec2(1.0, 0.0);
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        float magnitude = dot(z, z);
        if (magnitude > 256.0) {
            count = float(i) + 1.0 - log2(log2(magnitude) * 0.5);
            distance = 0.5 * log(magnitude) * sqrt(magnitude) / max(length(derivative), 0.00001);
            escaped = 1.0;
            break;
        }
    }
    float pixel = 3.1 / (zoom * uZoom * min(uResolution.x, uResolution.y));
    float edge = smoothstep(0.0, pixel * 1.5, distance);
    float contours = periodic(count * 1.25, 0.9);
    vec3 outside = palette(count * 0.025) * (0.30 + 0.38 * edge + 0.22 * contours);
    return mix(vec3(0.008, 0.01, 0.025), outside, escaped * edge);
}

vec3 equalizer(vec2 uv) {
    float coordinate = clamp((uv.x - 0.055) / 0.89, 0.0, 0.99999);
    float vertical = (uv.y - 0.12) / 0.74;
    float side = step(0.055, uv.x) * step(uv.x, 0.945);
    if (uEqRadial == 1) {
        vec2 p = (uv * 2.0 - 1.0) * uResolution / min(uResolution.x, uResolution.y);
        coordinate = fract(atan(p.y, p.x) / TAU + 0.5 + uRotation / TAU);
        vertical = (length(p) - 0.26) / 0.65;
        side = 1.0;
    } else if (uEqMirror == 1) vertical = abs(uv.y - 0.5) / 0.38;
    if (uEqRadial == 1 && uEqMirror == 1) vertical = abs(vertical);
    int count = clamp(uEqCount, 8, 64);
    float bucket = coordinate * float(count);
    int index = min(int(bucket), count - 1);
    float aa = max(fwidth(vertical), 0.001);
    float gap = uEqSpacing * 0.5;
    float horizontal = smoothstep(gap, gap + fwidth(bucket), fract(bucket)) *
        (1.0 - smoothstep(1.0 - gap - fwidth(bucket), 1.0 - gap, fract(bucket)));
    float height = uSpectrum[index];
    float bar = (1.0 - smoothstep(height - aa, height + aa, vertical)) * smoothstep(-aa, aa, vertical) * step(0.001, height);
    if (uEqSegments == 1) bar *= 1.0 - smoothstep(0.72, 0.88, fract(vertical * 32.0));
    float peak = (1.0 - smoothstep(0.003, 0.003 + aa, abs(vertical - uPeaks[index]))) * step(0.006, uPeaks[index]);
    vec3 ink = palette(coordinate * 0.65 + vertical * 0.12);
    float rail = (1.0 - smoothstep(0.0, aa * 1.3, abs(vertical))) * 0.12;
    return (ink * bar * (0.65 + 0.35 * vertical) + mix(ink, vec3(1.0), 0.55) * peak + ink * rail) * horizontal * side;
}

vec3 julia(vec2 p, float t) {
    vec2 z = p * 1.1;
    float shape = uDistortion / 1.5;
    vec2 constant = mix(vec2(-0.745, 0.186), vec2(-0.40, 0.59), shape);
    constant += vec2(sin(uMorphTime * 0.12), cos(uMorphTime * 0.1)) * 0.009;
    // Small movements of c produce large, intricate changes at the fractal boundary.
    constant += vec2(uRhythm.y * 0.052, uRhythm.x * 0.025 - uRhythm.y * 0.028);
    float escaped = 0.0, count = 0.0, trap = 10.0;
    int iterations = 40 + int(uComplexity * 104.0);
    for (int i = 0; i < 144; i++) {
        if (i >= iterations) break;
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + constant;
        trap = min(trap, abs(length(z) - 0.7 - (uRhythm.z + uRhythm.w) * 0.13));
        float magnitude = dot(z, z);
        if (magnitude > 128.0) {
            count = float(i) + 1.0 - log2(max(0.001, log2(magnitude) * 0.5));
            escaped = 1.0;
            break;
        }
    }
    float contours = periodic(count * (1.8 + (uRhythm.z + uRhythm.w) * 0.25), 1.0);
    vec3 outside = palette(count * 0.026) * (0.18 + contours * 0.82);
    vec3 inside = palette(trap * 6.0 + 0.3) * periodic(trap * 140.0, 1.0) * 0.65;
    return mix(inside, outside, escaped);
}

vec3 scene(int mode, vec2 p, float t) {
    if (mode == 0) return aurora(p, t);
    if (mode == 1) return kaleido(p, t);
    if (mode == 2) return wormhole(p, t);
    if (mode == 7) return mandelbrot(p, t);
    if (mode == 8) return equalizer(vUv);
    return julia(p, t);
}
void main() {
    vec2 p = (vUv * 2.0 - 1.0) * uResolution / min(uResolution.x, uResolution.y);
    p -= uTouch * 0.3;
    // The same scene remains visible with audio off; every audio term then equals zero.
    float expansion = 1.0 + uRhythm.x * 0.30 + uRhythm.y * 0.10;
    p = rotate(uRotation + uRhythm.y * 0.10) * p * (0.95 / (uZoom * expansion));
    vec3 color = scene(uMode, p, uTime);
    if (uTransition < 1.0) color = mix(scene(uPreviousMode, p, uTime), color, smoothstep(0.0, 1.0, uTransition));
    color = clamp(color * uIntensity * 1.22, 0.0, 1.0);
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = clamp(mix(vec3(luminance), color, uSaturation), 0.0, 1.0);
    color = pow(color, vec3(uContrast));
    color = max(color, vec3(0.004, 0.006, 0.012));
    color = mix(color, vec3(1.0) - color, uDownbeat.y);
    color = mix(color, vec3(1.0), uDownbeat.x);
    fragColor = vec4(color, 1.0);
}
