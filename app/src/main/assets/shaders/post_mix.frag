#version 300 es
precision highp float;
in vec2 vUv;
out vec4 fragColor;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform float uAmount;
void main() { fragColor = mix(texture(uFrom, vUv), texture(uTo, vUv), uAmount); }
