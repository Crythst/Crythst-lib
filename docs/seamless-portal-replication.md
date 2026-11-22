# 无缝传送门复刻路线指南

> 状态：设计指导（2026-11-22）  
> 适用：Crythst 0.1.0 / Minecraft 26.3 / Fabric  
> 关联：[沉浸式传送门 v1](immersive-portal.md)（当前实现）、[TODO](../TODO.md)、[架构](architecture.md)

## 0. 先看结论

当前 `integration.portal` + `client.render.portal` 的实现**不是一个 SeamlessPortals 复刻**，而是：

> 离屏渲染目标世界 → 把结果当纹理贴到一个四边形上 → 在 tick 末尾把实体 teleport 过去。

它适合做“传送门形状的实时监控画面 + 传送器”，但无法达到无缝传送门的核心体验：

- 没有逐像素洞口（stencil / mask）；
- 没有每帧正确视差（纹理只有 2–10 Hz）；
- 没有相机连续穿越（服务端 tick teleport）；
- 没有递归、多人、移动传送门、跨维度渲染；
- 目标世界必须落在主相机 `ViewArea` 内，否则只剩雾色；
- UV 在靠近平面时会严重溢出，再被 `clamp` 成拉伸/白块。

**本指南的决策：停止在离屏纹理路线上继续打补丁。**  
如果目标是复刻 SeamlessPortals，需要把传送门重新实现成**世界空间渲染变换**，而不是一个带纹理的物体。

---

## 1. 目标与非目标

### 1.1 目标

做一个真正的无缝传送门：

1. 从入口看过去，目标世界**每帧**按当前相机实时渲染。
2. 玩家/实体穿过平面时，位置、速度、视角、重力方向连续，不出现“瞬移感”。
3. 支持任意形状洞口（至少矩形 + 多边形）、任意朝向。
4. 支持移动传送门（挂在物理结构上）。
5. 支持同维度、跨维度、多人。
6. 至少递归 2 层（目标世界里还能看到另一个传送门）。
7. 与现有渲染管线、Sodium/Iris、实体/方块实体/粒子/雾/天空兼容。
8. 性能与“洞口像素数”相关，而不是每帧重画整个目标世界。

### 1.2 非目标（本阶段不要做）

- 不再优化 `PortalOffscreenPass` 的捕获频率/分辨率来“接近无缝”。
- 不把 `PortalSurfaceRenderer` 的 quad 修得更漂亮。
- 不把传送门继续绑在 `LevelPhysics` / 辅助 void level 上。
- 不要求第一版就支持所有模组；先保证原版 + 一个 shaderpack 场景。

---

## 2. 当前实现的边界（为什么修不动）

| 现状 | 直接后果 |
|---|---|
| 每个 portal 一个 `TextureTarget` | 只记录“某个相机某一刻”的画面，移动视角没有新信息 |
| 捕获间隔 100–500ms | 2–10 FPS，明显延迟/抖动 |
| 表面是矩形 quad | 不能做非矩形洞口、逐像素裁剪、边缘遮挡 |
| 只在主相机 `ViewArea` 内找 section | 目标在渲染距离外/未加载时只剩雾色 |
| 捕获用主相机 fog color 清屏 | 目标为空时就是白/雾色四边形 |
| UV 溢出后 `clamp` | 近平面/相机贴近时整块拉伸、糊成白色 |
| 穿越在 `END_SERVER_TICK` teleport | 无法消除瞬移感，依赖淡入淡出遮盖 |
| 没有 portal 网络包 | 多人客户端看不到传送门 |
| 物理世界按维度独立 | 跨维度结构/物理体不可行 |

仓库自身也承认这些限制：`docs/immersive-portal.md` §9 和 `TODO.md` 的验收标准大量仍是 `[~]`。这不是“再修一个 bug”能跨过的差距。

---

## 3. 架构决策（ADR）

### ADR-1：传送门是“世界渲染变换”，不是“带纹理的物体”

**决策：** 每帧用入口到出口的刚体变换 `T` 构造传送门相机，并把目标世界直接画进当前帧 framebuffer，用 stencil/mask 限制在洞口内。

**理由：**

- 只有每帧重画，才有正确视差、深度、遮挡、实体运动。
- 纹理方案的所有限制都是这个错误抽象的必然结果。

**后果：**

- 需要深入 `LevelExtractor` / `LevelRenderer` / framegraph。
- 需要处理目标相机的 render state、可见 section、实体、BE、天空、粒子、雾。
- 不能再只依赖 `BEFORE_GIZMOS` 的一个 quad 提交点。

---

### ADR-2：目标世界必须在同一帧、同一 FBO 中绘制

**决策：** 目标世界绘制发生在主 pass 内部，绑定主 color+depth，而不是独立离屏 target。

**理由：**

- 共享 depth 才能正确处理“洞口前后的遮挡”。
- 同一 FBO 才能使用 stencil，且递归层数可以通过 stencil 值叠加。
- 避免“纹理采样 → 四边形”的额外模糊/延迟/状态隔离问题。

**注意：**

- 26.3 的渲染是 render-state extraction + framegraph；不能简单地把 `LevelRenderer.render` 再调用一次就完事。
- 需要为目标相机单独 extract 一份 `LevelRenderState`（或等价结构），尤其是：
  - `visibleSections` / `nearbyVisibleSections`
  - 可见实体、方块实体
  - 天空、云、天气、粒子
  - camera/frustum/projection
- 参考实现路径（只读学习，不要直接复制到 MIT 项目）：
  - `qouteall/imm_ptl/core/render/renderer/RendererUsingStencil.java`
  - `qouteall/imm_ptl/core/render/FrontClipping.java`
  - `qouteall/imm_ptl/core/render/ViewAreaRenderer.java`
  - `com/warwa/seamlessportals/render/StencilPortalRenderer.java`
  - `com/warwa/seamlessportals/render/PortalContextSwitch.java`

---

### ADR-3：服务端权威 + 客户端连续

**决策：**

- 客户端负责相机和移动的连续预测；
- 服务端负责最终穿越判定、位置校正和状态同步；
- 穿越不是“过线后 teleport”，而是“相机继续沿变换后的空间移动，服务端确认后校正”。

**理由：**

- 只有服务端 tick 末 teleport，必然有一帧以上的跳变。
- 淡入淡出只能遮住问题，不能解决问题。

---

### ADR-4：传送门独立于物理/void level

**决策：** 传送门属于 level/render/world 层；`PhysicsStructure` 只提供“可选的运动平台/移动 portal 载体”。

**理由：**

- 当前 `LevelPhysics` 是每维度一个物理世界，跨维度天然冲突。
- 辅助 void level 是方块逻辑副本，不是可渲染的目标世界视图。
- 把 portal 绑在物理结构上，会把两个复杂系统的生命周期耦合在一起。

---

### ADR-5：许可证与来源

**决策：** 先做许可证审计，再决定“参考架构”还是“复用代码”。

- SeamlessPortals 仓库根 `LICENSE` 是 Apache-2.0，但其中包含 qouteall/Immersive Portals 谱系代码；不要假设整个仓库可以整体改成 MIT。
- 如果只学习算法/架构，自己重写，保持 MIT。
- 如果直接复用文件，保留版权与许可声明，并按 Apache-2.0 做合规处理。
- 本文不构成法律意见。

---

## 4. 目标架构

### 4.1 模块划分

```text
integration/portal/
  PortalShape          洞口形状（矩形/多边形）+ 局部坐标系
  PortalTransform      入口↔出口刚体变换（点/方向/四元数/速度）
  PortalLink           双向/单向链接、稳定 UUID
  PortalRegistry       服务端注册表 + 持久化
  PortalTraversal      服务端权威穿越（实体/玩家/载具/投射物）

client/render/portal/
  PortalRenderPass     主 pass 内的 stencil + 目标世界绘制
  PortalCamera         相机变换、投影、近平面
  PortalClip           齐次空间近平面裁剪
  PortalVisibility     目标相机的 section/实体/BE 提取
  PortalMask           stencil/mask 写入与递归层管理
  PortalDebug          洞口/法线/递归层/性能可视化

network/portal/
  PortalSync           S2C 定义/链接/移除/移动
  ClientPortalCache    渲染线程只读快照
```

### 4.2 数据模型（示意）

```java
public record PortalShape(
        Vector3d origin,
        Vector3d right,
        Vector3d up,
        Vector3d normal,
        List<Vector2d> outline // 洞口局部多边形，至少 4 点
) {
    public boolean containsLocal(double x, double y) { ... }
}

public final class PortalLink {
    UUID id;
    ResourceKey<Level> dimension;
    PortalShape shape;
    UUID destinationId;
    boolean bidirectional;
}

public interface PortalTransform {
    Vector3d transformPoint(Vector3dc p);
    Vector3d transformDirection(Vector3dc v);
    Quaterniond transformRotation(Quaterniondc q);
    PortalTransform inverse();
}
```

关键约束：

- `normal = right × up`，局部坐标 `z` 沿 normal。
- 入口到出口的旋转满足：
  - `Q * nA = -nB`
  - `Q * uA = uB`
  - `Q * rA = -rB`
- 进入速度 `-nA` 映射到 `+nB`，从出口外侧离开。

### 4.3 渲染流程（26.3）

```text
主帧:
  extract(mainCamera) → mainState

  for each visible portal (near-to-far):
      portalCamera = T * mainCamera
      destState    = extractDestination(portal.destination, portalCamera)

      bind main framebuffer (color + depth)
      writePortalShapeToStencil(portal.shape)       // GL_INCR / layer++
      clearDepthInsidePortal()
      setStencilTest(EQUAL, layer)
      setClipPlane(portal.plane)
      renderLevel(destState, portalCamera)
      restoreStencilAndClip()

  draw main HUD / gizmos
```

要点：

1. **不要复用主相机的 `LevelRenderState`**。必须为目标相机提取目标世界状态。
2. **不要移动共享 `ViewArea`**。26.3 的 section storage 会因中心变化 reset mesh；目标相机需要自己的可见集合/存储视图。
3. **近平面裁剪**：使用入口平面作为 clip plane，避免看到出口背后的几何；相机贴近平面时不能整块消失。
4. **递归**：stencil 值按层递增；嵌套 portal 绘制前检查当前层数，超过上限回退。
5. **目标世界可见性**：目标端必须在客户端已加载；需要 chunk ticket / 加载策略，而不是“主相机 ViewArea 里有就有”。

### 4.4 相机与穿越

```text
客户端每帧:
  camera = T(camera) when crossing begins
  keep velocity continuous
  fade = 0 (或极短)

服务端每 tick:
  if entity crossed plane inside shape:
      apply T to position/velocity/rotation
      mark cooldown
      send correction if client diverged
```

必须处理：

- 玩家、普通实体、掉落物、箭、载具、乘客、拴绳；
- 高速投射物：线段扫掠（CCD），不能只采样起点/终点；
- 骑乘：乘客随载具一起变换；
- 自定义重力：用 `T` 同时变换 `up`，不要只转 yaw/pitch；
- 防止出口立即回穿：10 tick 冷却 + 出口侧偏移/速度方向判定。

### 4.5 网络

最小包集：

```text
S2C PortalDefinition  (id, dimension, shape, link, version)
S2C PortalLinkUpdate  (id, destinationId, version)
S2C PortalRemove      (id)
S2C PortalPoseUpdate  (id, pose, tick)   // 移动传送门
```

客户端只读缓存，渲染线程不碰服务器对象。  
跨维度目标需要服务端发送目标维度信息并保持目标 chunk 加载。

---

## 5. 从当前代码迁移

### 5.1 保留

| 文件 | 处理 |
|---|---|
| `integration/portal/PortalPlane.java` | 保留数学，扩展为 `PortalShape`（多边形） |
| `integration/portal/PortalTransform.java` | 保留，补 inverse/速度/角速度测试 |
| `integration/portal/PortalSavedData.java` | 保留持久化，补版本迁移 |
| `integration/portal/PortalManager.java` | 保留注册表，拆分服务端/客户端 |
| `integration/portal/Portal.java` | 保留实体模型 |
| `integration/fabric/portal/PortalCommand.java` | 保留命令，补显式坐标/朝向创建 |
| `integration/portal/PortalCrossing.java` | 保留为数学工具；服务端/客户端语义重写 |

### 5.2 替换

| 文件 | 原因 |
|---|---|
| `client/render/portal/PortalOffscreenPass.java` | 离屏 pass 整体移除 |
| `client/render/portal/PortalViewTarget.java` | 不再需要每 portal 离屏纹理 |
| `client/render/portal/PortalViewTexture.java` | 不再把 RenderTarget 包成纹理 |
| `client/render/portal/PortalSurfaceRenderer.java` | 改为 stencil/mask 洞口绘制 |
| `client/render/portal/PortalRenderManager.java` | 改为 `PortalRenderPass` |
| `client/render/portal/PortalCaptureMixins` | 改为目标相机 extract 钩子 |
| `client/render/portal/PortalSectionVisibility.java` | 改为目标相机专属可见性 |
| `client/render/portal/PortalTeleportFade.java` | 连续穿越后删除/降为调试 |
| `integration/portal/PortalTeleporter.java` | 重写穿越语义，不再 tick 末 teleport 为主路径 |

### 5.3 新增

- `PortalShape` / `PortalMask` / `PortalRenderPass`
- `PortalVisibility`（目标相机 section/entity/BE 提取）
- `PortalClip`（齐次近平面裁剪）
- `network/portal/*`
- 游戏内自动测试与截图回归。

---

## 6. 里程碑（每步都要有退出条件）

### M0：冻结与基线

- 停止在离屏路径上加功能；只修崩溃。
- 记录当前基线：1080p 单 portal 的帧时间、捕获时间、截图。
- 完成许可证/来源审计。

**退出：** 基线数据 + 明确“要替换哪些文件”。

### M1：形状与变换

- `PortalShape` 支持矩形 + 多边形。
- `PortalTransform` 完整单测：点/方向/四元数/速度/角速度/双向往返。
- 客户端只画洞口线框，不做目标世界。

**退出：** 入口/出口任意朝向下，线框位置、法线、变换一致。

### M2：单 portal 直接渲染（同维度）

- 主 pass 内用 `T(camera)` 渲染目标世界。
- stencil 写入洞口 + 深度清理 + `EQUAL` 绘制。
- 近平面裁剪。
- 目标相机独立可见性；不移动主 `ViewArea`。
- 删除离屏纹理路径。

**退出：**

- 玩家左右移动时，透过洞口看到正确视差；
- 目标端地形/实体/BE/天空/雾正确；
- 洞口外完全不受影响；
- 单 portal 1080p 增量成本 < 3ms（目标值，按机器调整）。

### M3：玩家连续穿越（同维度）

- 客户端相机连续进入目标空间；
- 服务端权威校正，不再依赖黑屏；
- `xo/yo/zo`、速度、fallDistance、onGround、yaw/pitch 正确；
- 冷却与出口防回穿。

**退出：** 录屏中相机无跳变；高速/慢速/斜向/后退穿门都连续。

### M4：实体与移动 portal

- 普通实体、掉落物、箭（CCD）、载具、乘客、拴绳。
- 移动 portal：`PortalShape` 跟随 `PhysicsStructure` 的插值 pose。
- 结构/刚体穿越（同维度）。

**退出：** 移动平台上穿门不抖、不卡、不丢失速度。

### M5：多人 + 跨维度 + 递归 2

- S2C portal 定义/链接/移除/pose。
- 跨维度目标 world view + chunk ticket。
- stencil 递归 2 层。
- 客户端只读快照。

**退出：** 专用服务器 + 2 客户端；双方看到同一 portal；跨维度穿门不崩。

### M6：兼容与性能

- Sodium/Iris/shaderpack 基本兼容。
- 半透明、阴影、粒子、天气、雾。
- 性能剖析：洞口像素成本、section 提取、状态切换。
- 回归测试与截图对比。

**退出：** 目标场景下无明显闪烁、漏光、深度错误；性能预算达标。

---

## 7. 测试计划

### 7.1 单元测试（保留并扩展）

- `PortalTransformTest`：往返、逆变换、速度/角速度、任意朝向。
- `PortalShapeTest`：多边形包含、边界、退化形状。
- `PortalCrossingTest`：慢速/高速/反向/擦边。
- `PortalPersistenceTest`：版本迁移、损坏数据。

### 7.2 游戏内自动测试

用 Fabric client gametest 或脚本化 `runClient`：

1. 创建 A/B 两个 portal，固定位置与朝向。
2. 相机从 A 前 5 格移动到 A 后 5 格，逐帧记录相机位置。
3. 断言：
   - 穿越前后位置连续（无 > 0.5 格跳变）；
   - 速度方向按 `T` 变换；
   - 洞口内截图包含目标端独有方块；
   - 洞口外像素不变。
4. 递归 2 层：目标世界再放一个 portal，断言第二层可见。
5. 性能：固定场景跑 600 帧，记录 1% low / 平均帧时间。

### 7.3 视觉回归

- 每个里程碑保存一组固定机位截图：
  - 正对、斜视、贴近、穿过瞬间、洞口边缘。
- 对比目标端独有颜色/图案，避免“白块也算通过”。

### 7.4 失败模式检查表

| 现象 | 优先检查 |
|---|---|
| 白块/雾色 | 目标世界是否加载；目标相机可见性；clear color；是否根本没画目标 |
| 黑块 | stencil 是否写入；FBO 是否绑定；depth test；color mask |
| 拉伸/糊 | UV/投影矩阵；近平面裁剪；是否仍在用旧纹理重投影 |
| 洞口漏光 | stencil 测试/深度清理；clip plane |
| 目标世界出现在洞口外 | stencil 未生效 / FBO 切换错误 |
| 递归闪烁 | stencil 层管理；捕获/绘制顺序 |
| 穿门跳变 | 客户端预测；服务端校正；`xo/yo/zo` |
| 目标世界卡顿 | 是否每帧重提取全量 section；是否移动了共享 ViewArea |

---

## 8. 调试与开关（建议）

保留/新增：

```text
-Dcrythst.portal.render=false          关闭 portal 渲染
-Dcrythst.portal.stencil=true          强制 stencil 路径
-Dcrythst.portal.debugMask=true        可视化 stencil/mask
-Dcrythst.portal.debugClip=true        可视化近平面裁剪
-Dcrythst.portal.recursion=2           递归上限
-Dcrythst.portal.trace=true            每帧输出 portal pass 时间/层数
```

日志每次只输出一条聚合行，避免刷屏：

```text
[portal] visible=2 layer=1 sections=... extract=...ms draw=...ms total=...ms
```

---

## 9. 风险

1. **26.3 render-state extraction 变化快**：目标相机 extract 是最大不确定项；先做最小可用版本，再补实体/BE/粒子。
2. **Sodium/Iris**：stencil 与 shaderpack 的 framegraph 可能冲突；M6 再处理，不要在 M2 卡住。
3. **跨维度**：客户端世界加载、chunk ticket、实体同步是独立大工程；不要和单维度渲染混在一起做。
4. **许可证**：参考实现来自 Apache-2.0 仓库，但包含上游谱系；复用前按文件审计。
5. **范围**：不要再把 portal 和物理/void level 绑在一起；否则每个系统都会互相拖累。

---

## 10. 最终判断标准

只有以下全部成立，才可以称为“复刻 SeamlessPortals”：

- [ ] 玩家左右移动时，透过 portal 的视差每帧正确；
- [ ] 相机穿门无跳变，不依赖黑屏；
- [ ] 洞口是逐像素的，不是矩形贴图；
- [ ] 目标端实体/方块实体/粒子/天空/雾正确；
- [ ] 递归至少 2 层；
- [ ] 同维度 + 跨维度 + 多人；
- [ ] 移动传送门；
- [ ] 性能与洞口像素相关，而不是重画整个世界；
- [ ] 有游戏内自动测试和截图回归。

在此之前，现有 `docs/immersive-portal.md` 应标记为 **“离屏纹理原型，不是无缝实现”**，避免后续开发继续在错误方向上投入。
