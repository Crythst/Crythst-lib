#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0; // current opening mask
uniform sampler2D Sampler1; // parent opening mask

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (texelFetch(Sampler0, pixel, 0).r * texelFetch(Sampler1, pixel, 0).r < 0.5) {
        discard;
    }
    gl_FragDepth = 0.0;
    fragColor = vec4(0.0);
}
