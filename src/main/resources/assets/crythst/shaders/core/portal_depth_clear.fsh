#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) out vec4 fragColor;

void main() {
    // The pipeline's color write mask is zero; this output exists only to keep
    // the pipeline valid. Only the depth attachment is written.
    fragColor = vec4(0.0);
}
