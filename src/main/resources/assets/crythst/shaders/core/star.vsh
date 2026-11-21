#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;
layout(location = 4) in ivec2 UV2;
#ifdef GLINT_SPECIAL
layout(location = 5) in vec2 UV3;
#endif
layout(location = 6) in vec3 Normal;

#ifndef OIT_ALPHA_ONLY
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) out vec4 lightMapColor;
layout(location = 4) out vec4 overlayColor;
#endif

layout(location = 5) out vec2 texCoord0;
#ifdef GLINT
layout(location = 6) out vec2 texCoordGlint;
#endif
// 星空用的屏幕投影坐标（NDC，纵横比校正交给 fsh）：星星固定在空间里，物品像一扇窗
layout(location = 7) out vec2 starCoord;
// 真实几何求交用：视空间法线（统一朝向摄像机）与视空间顶点位置（相机在原点）
layout(location = 8) out vec3 starNormalView;
layout(location = 9) out vec3 starViewPos;
// 模型在视空间的缩放倍率：凹陷深度按模型单位定义，须乘它才能在任何缩放上下文
// （GUI 里物品被放大 16×0.625=10 倍）保持同样的深宽比。整个 draw 内恒定，插值无害。
layout(location = 10) out float starModelScale;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    starCoord = gl_Position.xy / gl_Position.w;

    starViewPos = (ModelViewMat * vec4(Position, 1.0)).xyz;
    // 表面法线 → 视空间，统一朝向摄像机：视空间相机在原点、物体在 -Z，
    // 朝相机的法线是 +Z——保留 z>0，翻掉背向的
    vec3 nView = mat3(ModelViewMat) * Normal;
    if (dot(nView, nView) < 1e-8) nView = vec3(0.0, 0.0, 1.0);   // 法线缺失：回退为正对视角
    nView = normalize(nView);
    if (nView.z < 0.0) nView = -nView;
    starNormalView = nView;
    // 视空间缩放 = 三个模型轴单位长度的均值（各渲染上下文均为均匀缩放）
    starModelScale = (
        length((ModelViewMat * vec4(1.0, 0.0, 0.0, 0.0)).xyz) +
        length((ModelViewMat * vec4(0.0, 1.0, 0.0, 0.0)).xyz) +
        length((ModelViewMat * vec4(0.0, 0.0, 1.0, 0.0)).xyz)
    ) / 3.0;

    #ifndef OIT_ALPHA_ONLY
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    #endif
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    #ifndef OIT_ALPHA_ONLY
    lightMapColor = sample_lightmap(Sampler2, UV2);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    #endif

    texCoord0 = UV0;
    #ifdef GLINT
    #ifdef GLINT_SPECIAL
    texCoordGlint = (TextureMat * vec4(UV3, 0.0, 1.0)).xy;
    #else
    texCoordGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    #endif
    #endif
}
