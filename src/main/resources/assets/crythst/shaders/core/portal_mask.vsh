#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec3 Position;

void main() {
    // The CPU already clipped the portal polygon against the eye plane and
    // converted it to normalized device coordinates. Position.z carries the
    // portal plane's reverse-Z depth, so the mask pass can depth-test against
    // the main depth snapshot instead of always marking the whole polygon.
    gl_Position = vec4(Position.xy, Position.z, 1.0);
}
