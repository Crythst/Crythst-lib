#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;

void main() {
    // Same clipped portal polygon as the mask, but at reverse-Z far (0.0).
    // The destination world can then be drawn through the verified opening.
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
