#version 330
#extension GL_ARB_separate_shader_objects : require

// globals.glsl 提供 GameTime（star 特效用到）
#include <minecraft:globals.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif
layout(location = 2) in vec4 vertexColor;
#ifndef OIT_ALPHA_ONLY
layout(location = 3) in vec4 lightMapColor;
layout(location = 4) in vec4 overlayColor;
#endif
layout(location = 5) in vec2 texCoord0;
#ifdef GLINT
layout(location = 6) in vec2 texCoordGlint;
#endif
layout(location = 7) in vec2 starCoord;
// 真实几何：视空间法线（朝摄像机）与视空间顶点位置（相机在原点）——射线-凹腔求交用
layout(location = 8) in vec3 starNormalView;
layout(location = 9) in vec3 starViewPos;
// 模型视空间缩放（GUI 放大 10 倍等）：凹陷深度乘它保持深宽比一致
layout(location = 10) in float starModelScale;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// 当前像素是否是星空区域（自发光用，见 calculateFinalColor）
bool starMask = false;

#ifndef OIT_ALPHA_ONLY
vec4 calculateFinalColor(vec4 color) {
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    // 星空区域自发光：不乘光照图，这样在黑暗处也一样亮；其余像素保持原样
    if (!starMask) {
        color *= lightMapColor;
    }

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
}
#endif

// ===== star 特效：程序化星空（模仿末地传送门）=====
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// 时间相位速率：写成 TAU×整数/24000 —— GameTime 每游戏日回绕（ticks 24000→0），
// 此时相位恰好是 TAU 的整数倍，sin 完全连续 → 修复每游戏日一次的"神秘跳变"。
const float TWINKLE_RATE = 6.283185307 * 955.0 / 24000.0;   // ≈0.25001，与原 0.25 同速
const float HUE_RATE = 6.283185307 * 191.0 / 24000.0;       // ≈0.04999，与原 0.05 同速

// 一层星星：把 uv 切成 scale×scale 个格子，每个格子按概率出现一颗会闪烁的星。
// 用 3x3 邻域采样，避免方块星星在格子边界被硬切。
// 星星不做平移漂移：GameTime 按天回绕，任何线性漂移都会在回绕瞬间整片跳位
// （这就是"神秘跳变"）；星星固定在空间里（末地传送门式），只保留闪烁动画。
float starLayer(vec2 uv, float scale, float chance, float ticks) {
    vec2 p = uv * scale;
    vec2 cell = floor(p);
    vec2 local = fract(p);

    float sum = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 neighbor = cell + vec2(float(x), float(y));
            if (hash21(neighbor) > chance) {
                continue;
            }

            // End-portal style: stars are hard-edged square blocks, never round
            // sprites. Their centre is kept inside the cell so the square cannot
            // bleed across the 3x3 neighbourhood.
            vec2 starPos = vec2(hash21(neighbor + 7.19), hash21(neighbor + 3.71)) * 0.8 + 0.1
                    + vec2(float(x), float(y));
            float size = 0.10 + 0.22 * hash21(neighbor + 11.3);
            float twinkle = 0.35 + 0.65 * step(0.45,
                    fract(ticks * TWINKLE_RATE / 6.283185307 + hash21(neighbor + 5.5)));

            vec2 delta = local - starPos;
            // Hard square core, no halo: a pixel is either on or off.
            float square = step(abs(delta.x), size) * step(abs(delta.y), size);
            sum += square * twinkle;
        }
    }
    return sum;
}

// ===== star 特效：凹进去的侧面（紫色带阴影）=====
// 掩码判定的共享常量（同时给 main 和下面的距离场用）
const vec3 MASK_COLOR = vec3(0.0);   // 掩码颜色：纯黑
const float MASK_TOLERANCE = 0.1;    // 容差，容忍双线性/mipmap 插值出来的近黑边缘像素

bool isMaskTexel(vec3 rgb) {
    return all(lessThan(abs(rgb - MASK_COLOR), vec3(MASK_TOLERANCE)));
}

// 凹底（星空地板）texel = 不透明的掩码黑。
// 透明像素（包括 RGBA 0,0,0,0 的透明黑）绝不算地板——之前只看颜色不看 alpha，
// 拐角处偏移落点常是透明 → 误判成地板 → 拐角缺壁。
bool isFloorTexel(vec4 c) {
    return c.a > 0.5 && isMaskTexel(c.rgb);
}

// 连续的"是地板"权重（用来取平滑的墙面法线）：1 = 不透明掩码，0 = 实体/透明。
// 双线性与 mip 混合让它在边界平滑过渡——梯度场逐像素连续，没有量化胞元
float floorWeight(vec4 s) {
    return s.a * (1.0 - smoothstep(0.0, MASK_TOLERANCE, length(s.rgb)));
}

// 凹陷深度（模型单位，物品模型边长为 1）：凹腔 = 掩码区域沿 -法线向下挤出这么多的棱柱，
// fsh 里做真实射线求交——深度是物体自身属性，随视距等比缩放，不随远近变化。
// 实际使用时乘 starModelScale：GUI 等上下文把模型整体放大，深宽比要保持一致。
// 正对视角时射线直达凹底（看不到壁面）；倾斜越大侧壁展开越多（透视正确）。
const float RECESS_DEPTH_WORLD = 0.1;   // 手持距离（约 0.5 格）下的观感约等于 0.2 的偏移

// （原 cavityWallDistance 8 方向量化行军已删除：墙面法线改用 floorWeight 连续梯度）

// 四层不同尺度的星星叠加：尺度越小星越多，层间偏移产生纵深感
vec3 starfield(vec2 uv, float ticks) {
    // 末地传送门式的深空底色
    vec3 color = vec3(0.004, 0.025, 0.035);

    float stars = 0.0;
    stars += starLayer(uv,        18.0, 0.50, ticks) * 1.00;  // 大而亮的近星
    stars += starLayer(uv + 3.7,  36.0, 0.50, ticks) * 0.80;
    stars += starLayer(uv + 9.1,  72.0, 0.50, ticks) * 0.60;
    stars += starLayer(uv + 17.3, 140.0, 0.55, ticks) * 0.40; // 密集的远景小星

    // 星星整体走蓝绿（青 + 偏绿两个色相混一点）
    vec3 starColor = mix(vec3(0.20, 0.95, 0.85), vec3(0.40, 1.00, 0.45), 0.5 + 0.5 * sin(ticks * HUE_RATE));
    color += starColor * stars;
    return color;
}
// ===== star 特效函数结束 =====

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    // GameTime 是 0..1 的昼夜循环进度，乘 24000 得到 tick 数
    float ticks = GameTime * 24000.0;
    // 屏幕 1 像素对应的 UV 步长（凹腔壁面行军用 textureGrad 采样）。
    // 导数必须在分支外计算——非一致控制流里的导数结果未定义。
    vec2 pxU = dFdx(texCoord0);
    vec2 pxV = dFdy(texCoord0);

    // 全透明像素丢弃：掩码判定只看颜色，不先剔除的话透明黑 (0,0,0,0) 会被误判成星空掩码。
    // 阈值保持 0.05（不要调回 0.1）：轮廓双线性混合出的 0.05~0.1 像素保留渲染，边缘才不会有透明缝。
    if (color.a < 0.05) {
        discard;
    }

    // ===== star 特效：掩码（纯黑像素）渲染星空 + 凹陷侧面 =====
    // 注意必须在 color *= vertexColor 之前判断，否则顶点光照会把暗色像素也判成黑色
    bool isMask = isMaskTexel(color.rgb);

    color *= vertexColor * ColorModulator;

    if (isMask) {
        starMask = true;   // 自发光：calculateFinalColor 里会跳过光照图
        color.a = 1.0;     // 轮廓边缘的半透明混合像素补成不透明，不透出背景细缝
        // 末地传送门式：星空按屏幕投影坐标生成，星星固定在空间里，
        // 物品像一扇看向太空的窗——移动时手部摆动会让物品划过星空，产生视差。
        // 乘纵横比校正，保证星星是正圆而不是椭圆
        vec2 spaceUv = starCoord * vec2(ScreenSize.x / ScreenSize.y, 1.0);
        vec3 space = starfield(spaceUv, ticks);

        // ===== 真实几何：逐像素射线-凹腔求交（视空间，相机在原点）=====
        // 凹腔 = 掩码区域沿 -法线向下挤出 RECESS_DEPTH_WORLD 的棱柱。视线射线穿过
        // 当前像素 P（在开口平面上，且在掩码内 → 射线必然从开口进入腔体），之后
        // 只有两种几何结局：
        //   1) 先打中凹底平面（投影落点仍在掩码内）→ 星空地板
        //   2) 先穿过侧壁（面内路径先出掩码边界）→ 紫色壁面，深度 = 出口处进度
        // 近侧壁被表面自身遮住（射线从开口进入），天然不可见——这是几何推论而非特判。
        // 全部按真实透视投影求交：任何角度壁面都是投影后的直线边界（矩形/梯形），
        // 随视距等比缩放，没有屏幕空间偏移近似、没有定死的像素宽度。
        vec3 P = starViewPos;
        vec3 n = starNormalView;
        float h = RECESS_DEPTH_WORLD * starModelScale;  // 深度换算到当前上下文的视空间尺度（GUI 放大10倍等）
        // 视线射线：从当前像素 NDC 用逆投影矩阵构造——透视投影下射线汇聚于相机，
        // 正交投影（GUI）下射线互相平行。绝不能用 normalize(P)：那只对透视成立，
        // 正交时物品越偏离屏幕中心假倾斜越大，凹腔会被极端斜切（"像从极端角度看"）。
        vec2 ndcNow = (gl_FragCoord.xy / ScreenSize) * 2.0 - 1.0;
        mat4 invProj = inverse(ProjMat);
        vec4 cNear = invProj * vec4(ndcNow, 1.0, 1.0);
        vec4 cFar = invProj * vec4(ndcNow, -1.0, 1.0);
        vec3 O = cNear.xyz / cNear.w;                   // 射线起点（near 平面上的像素投影点）
        vec3 Dv = cFar.xyz / cFar.w - O;
        vec3 D = Dv * inversesqrt(dot(Dv, Dv));         // 指向屏幕深处
        float dn = dot(D, n);
        vec2 uvFloor = texCoord0;
        float pathPx = 0.0;

        if (abs(dn) > 1e-5) {
            // 射线 X = O + t·D 与凹底平面 {X·n = P·n - h} 求交（n 朝相机 → h 在 -n 侧，t 更深）
            float tFloor = (dot(P, n) - h - dot(O, n)) / dn;
            vec3 F = O + D * tFloor;
            // 纹理坐标住在开口平面上：把凹底交点 F 沿法线抬回开口平面 F' = F + h·n，
            // 投影 F'（不能投影 F——射线上的点投影重合，delta 恒为 0，会退化扭曲）
            vec3 Fp = F + h * n;
            vec4 clipF = ProjMat * vec4(Fp, 1.0);
            // 当前像素的精确 NDC（gl_FragCoord，不用插值的 starCoord 避免误差）
            vec2 ndcP = ndcNow;
            vec2 deltaPx = (clipF.xy / clipF.w - ndcP) * (ScreenSize * 0.5);
            uvFloor = texCoord0 + pxU * deltaPx.x + pxV * deltaPx.y;
            pathPx = length(deltaPx);
        }

        if (pathPx < 1.0) {
            color.rgb = space;   // 几乎正对：凹底≈开口，看不到壁面
        } else {
            // 路由完全由"面内路径是否先出掩码"决定：出口必在自己贴图内（掩码四周
            // 有贴图余量），从不依赖落点采样 → 图集相邻贴图不可能干扰判定
            // 沿面内路径 P→F'（面内进度与深度线性对应）找掩码出口
            vec2 pathUv = uvFloor - texCoord0;
            float exitT = -1.0;
            vec2 exitUv = texCoord0;
            for (int i = 1; i <= 480; i++) {
                float t = float(i) / pathPx;   // 每步 1 屏幕像素
                if (t > 1.0) break;
                vec2 uv = texCoord0 + pathUv * t;
                if (!isFloorTexel(textureGrad(Sampler0, uv, pxU, pxV))) {
                    exitT = t;
                    exitUv = uv;
                    break;
                }
            }

            if (exitT < 0.0) {
                color.rgb = space;   // 路径全程在掩码内 → 射线直达凹底（含超长路径兜底）
            } else {
                // 深度 = 出口处的面内进度（0 = 开口边缘，1 = 凹底）
                float depthT = clamp(exitT, 0.0, 1.0);
                // 方向性明暗：取开口边缘"地板权重 w"的连续梯度作为墙面朝向。
                // 之前用 8 方向行军的量化方向，相邻像素落入不同方向胞元会翻转亮度
                // （摩尔纹/噪点的来源）；连续梯度场逐像素平滑，没有胞元边界。
                vec2 g;
                g.x = floorWeight(textureGrad(Sampler0, exitUv + pxU * 2.0, pxU, pxV))
                    - floorWeight(textureGrad(Sampler0, exitUv - pxU * 2.0, pxU, pxV));
                g.y = floorWeight(textureGrad(Sampler0, exitUv + pxV * 2.0, pxU, pxV))
                    - floorWeight(textureGrad(Sampler0, exitUv - pxV * 2.0, pxU, pxV));
                // ∇w 指向地板（掩码）一侧 = 墙面朝向腔内
                vec2 face = dot(g, g) > 1e-8 ? normalize(g) : vec2(0.0, 1.0);
                float faceLight = 0.5 + 0.5 * dot(face, vec2(-0.7071, 0.7071));
                vec3 wallColor = mix(vec3(0.18, 0.06, 0.34), vec3(0.76, 0.44, 1.00), faceLight);
                // 阴影渐变更缓：smoothstep 两头平缓，最深处仍留 60% 亮度
                wallColor *= mix(1.0, 0.60, smoothstep(0.0, 1.0, depthT));
                wallColor *= (vertexColor * ColorModulator).rgb * lightMapColor.rgb;  // 顶点光照 + 世界光照
                color.rgb = wallColor;
            }
        }
    }

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
