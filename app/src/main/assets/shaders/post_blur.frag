#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uImage;
uniform vec2 uStep;
uniform float uThreshold;
vec3 sampleColor(vec2 uv) {
    vec3 c = texture(uImage, uv).rgb;
    if (uThreshold >= 0.0) c *= smoothstep(max(0.0, uThreshold - .1), uThreshold + .1, max(c.r, max(c.g, c.b)));
    return c;
}
void main() {
    vec3 c = sampleColor(vUv) * .227027;
    c += (sampleColor(vUv + uStep * 1.384615) + sampleColor(vUv - uStep * 1.384615)) * .316216;
    c += (sampleColor(vUv + uStep * 3.230769) + sampleColor(vUv - uStep * 3.230769)) * .070270;
    fragColor = vec4(c, 1.0);
}
