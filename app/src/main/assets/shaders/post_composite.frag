#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uImage;
uniform sampler2D uBlur;
uniform sampler2D uBloom;
uniform float uBlurAmount;
uniform float uBloomAmount;
void main() {
    vec3 c = texture(uImage, vUv).rgb;
    if (uBlurAmount > 0.0) c = mix(c, texture(uBlur, vUv).rgb, uBlurAmount);
    if (uBloomAmount > 0.0) c += texture(uBloom, vUv).rgb * uBloomAmount;
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
