#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0; // pre-layer depth snapshot
uniform sampler2D Sampler1; // this layer's opening mask
uniform sampler2D Sampler2; // immediate parent layer mask (1.0 for layer 1)

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float own = texture(Sampler1, texCoord).r;
    float parent = texture(Sampler2, texCoord).r;
    if (own * parent >= 0.5) {
        // Inside the (possibly nested) opening: keep the destination depth so
        // deeper recursion layers and later passes see layer-local geometry.
        discard;
    }
    // Outside the final mask: restore the exact pre-layer depth. This preserves
    // destination depth inside the opening instead of copying the snapshot over
    // the whole frame.
    gl_FragDepth = texture(Sampler0, texCoord).r;
    fragColor = vec4(0.0);
}
