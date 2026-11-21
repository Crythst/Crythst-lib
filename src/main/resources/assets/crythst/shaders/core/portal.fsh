#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:fog.glsl>

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 localPosition;

layout(location = 0) out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// One layer of procedural stars in a cell grid. Returns the luminance of the
// layer at the given coordinate.
float starLayer(vec2 uv, float scale, float speed, float seed, float threshold) {
    vec2 p = uv * scale + vec2(seed * 17.0, seed * 31.0);
    vec2 cell = floor(p);
    vec2 local = fract(p) - 0.5;

    float h = hash21(cell + seed);
    float on = smoothstep(threshold, 1.0, h);
    if (on <= 0.0) {
        return 0.0;
    }

    vec2 offset = (vec2(hash21(cell + 1.3), hash21(cell + 7.9)) - 0.5) * 0.55;
    float radius = 0.045 + 0.10 * hash21(cell + seed + 4.7);
    float glow = 1.0 - smoothstep(0.0, radius, length(local - offset));

    // Each star twinkles at a slightly different phase; GameTime is continuous
    // across the 24000-tick day wrap because the phase is only used in sin().
    float twinkle = 0.55 + 0.45 * sin(GameTime * speed + h * 6.2831853);
    return glow * on * twinkle;
}

void main() {
    // texCoord0 is the portal's screen-space UV. Fallback surfaces therefore
    // cover exactly the same screen footprint as the captured view would.
    vec2 p = texCoord0 * 2.0 - 1.0;
    float radius = length(p);
    float angle = atan(p.y, p.x);

    // Swirling sampling coordinates for the star layers.
    float swirl = angle + GameTime * 0.025 + radius * 2.15;
    vec2 swirlUv = vec2(cos(swirl), sin(swirl)) * radius * 0.55 + 0.5;

    // Deep violet / teal nebula, with the classic end-portal green-cyan tint.
    vec3 color = vec3(0.010, 0.006, 0.026);
    float bands = 0.5 + 0.5 * sin(swirl * 3.0 + GameTime * 0.35);
    color += mix(vec3(0.20, 0.04, 0.32), vec3(0.02, 0.16, 0.22), bands) * (0.26 + 0.18 * bands);
    color += vec3(0.02, 0.08, 0.14) * (1.0 - smoothstep(0.0, 1.15, radius));

    // Three parallax star layers with different drift speeds.
    float stars = 0.0;
    stars += starLayer(swirlUv, 9.0, 0.85, 1.0, 0.90);
    stars += starLayer(swirlUv + vec2(0.37, 0.11), 17.0, -1.35, 2.0, 0.93);
    stars += starLayer(swirlUv * 1.7 + vec2(0.71, 0.29), 31.0, 1.65, 3.0, 0.95);
    color += vec3(0.76, 0.74, 1.0) * stars;

    // Dark rim so the quad visually reads as a framed surface.
    color = mix(color, vec3(0.020, 0.006, 0.055), smoothstep(0.86, 1.38, radius));

    // Soft core glow.
    color += vec3(0.10, 0.03, 0.18) * pow(max(0.0, 1.0 - radius), 3.0);

    // Opaque surface: the pipeline uses the solid phase and writes depth.
    vec4 outColor = vec4(color, 1.0) * vertexColor;
    outColor *= ColorModulator;
    outColor.a = 1.0;
    fragColor = outColor;
}
