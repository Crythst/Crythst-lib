#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D Sampler0; // pre-portal colour snapshot
uniform sampler2D Sampler1; // this layer's opening mask
uniform sampler2D Sampler2; // immediate parent layer mask (1.0 for layer 1)

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float mask = texture(Sampler1, texCoord).r;
    float parent = texture(Sampler2, texCoord).r;
    if (mask * parent >= 0.5) {
        // Inside the (possibly nested) opening: keep the destination pixels
        // just rendered into the main framebuffer.
        discard;
    }
    // Outside the opening: restore exactly what was there before the
    // destination pass, including the pixels overwritten by the full-screen
    // destination sky pass.
    fragColor = texture(Sampler0, texCoord);
}
