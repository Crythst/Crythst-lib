#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float sphericalVertexDistance;
layout(location = 3) in float cylindricalVertexDistance;
layout(location = 4) in vec3 localPosition;

layout(location = 0) out vec4 fragColor;

void main() {
    // The texture was rendered from the linked portal's virtual camera with the
    // same projection as the main camera. The quad's clip-space position is
    // therefore the correct destination sample; the sampler is clamp-to-edge,
    // so partially off-screen portals do not wrap or smear.
    vec2 uv = texCoord0;
    vec4 color = texture(Sampler0, uv);

    // The captured scene is already lit/fogged; keep the surface opaque so it
    // uses the solid phase and does not require a custom OIT pipeline set.
    color.rgb *= vertexColor.rgb;
    color.a = 1.0;
    color *= ColorModulator;
    fragColor = color;
}
