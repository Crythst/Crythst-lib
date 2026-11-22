# 沉浸式传送门（Immersive Portal）

> **状态（2026-11-22，第二次重写）：默认路径已改为同帧、同 FBO 的目标世界渲染 +
> 逐像素洞口 mask。** 旧的“离屏纹理 → 四边形”路径已从默认实现中移除，只剩显式
> `-Dcrythst.portal.offscreenFallback=true` 的调试逃生口；本文不再把它描述为无缝方案。
> 设计依据：[`seamless-portal-replication.md`](seamless-portal-replication.md)；
> 实现细节：[`portal-rendering.md`](portal-rendering.md)。

状态：**v2 同帧传送门已实现**（服务端权威传送 / 客户端连续预测 / 目标相机独立
`LevelRenderState` / 主 color+depth framegraph pass / 多边形逐像素洞口 / 出口平面裁剪 /
2 层递归 / 多人快照同步 / 移动传送门 pose）。跨维度**传送**可用，跨维度**渲染**仍不可用；
目标端远距离 section 仍需独立 `ViewArea`；没有硬件 stencil（RenderPearl 未暴露 stencil
state），使用 mask + composite 等价实现。

本文保留 v1 的坐标/变换/命令/持久化说明，但第 8 节起已改写为 v2 的真实实现与限制。

---

## 1. 代码位置

| 路径 | 作用 |
| --- | --- |
| `integration/portal/PortalPlane.java` | 传送门平面：原点、法线、右/上基、宽高、局部↔世界坐标、平面触发体 |
| `integration/portal/Portal.java` | 传送门定义：稳定 UUID、名称、维度、平面、链接 id |
| `integration/portal/PortalSnapshot.java` | 跨线程只读快照（服务器 → 客户端） |
| `integration/portal/PortalSavedData.java` | 世界存档：稳定 UUID/名称/维度/平面/链接（NBT SavedData） |
| `integration/portal/PortalTransform.java` | 入口平面到出口平面的刚体旋转、点/方向/四元数变换、yaw/pitch 转换 |
| `integration/portal/PortalManager.java` | 静态注册表、创建/链接/删除/列表、服务器 tick 与生命周期注册 |
| `integration/portal/PortalCrossing.java` | 前→后平面穿越检测（上一 tick 采样点 → 当前采样点） |
| `integration/portal/PortalTeleporter.java` | 每 tick 的实体/玩家/物理体穿越、冷却与递归保护 |
| `integration/fabric/portal/PortalCommand.java` | `/crythst portal ...` 命令（合并进已有 `/crythst`） |
| `client/render/portal/PortalClientCache.java` | 渲染线程只读的不可变快照缓存 |
| `client/render/portal/PortalClientView.java` | 客户端 tick 从单机集成服务器刷新快照 |
| `client/render/portal/PortalRenderManager.java` | 每帧目标状态提取、framegraph pass 注册、递归层编排 |
| `client/render/portal/PortalVisibility.java` | 独立目标 `LevelRenderState` 提取 + 有界目标 section 选择 |
| `client/render/portal/PortalRenderPass.java` | 主 color+depth 内的目标世界绘制、mask、snapshot/composite |
| `client/render/portal/PortalMaskGeometry.java` | 纯数学：多边形投影、近平面裁剪、屏幕 bbox |
| `client/render/portal/PortalPassRenderTypes.java` | `crythst:portal_mask` / `crythst:portal_composite` 管线 |
| `client/render/portal/PortalCrossingController.java` | 客户端同维度连续穿越预测（服务端仍权威） |
| `client/render/portal/PortalRecursion.java` | 递归层数与祖先排除的纯策略 |
| `client/render/portal/PortalViewCamera.java` | 链接变换、目标相机位姿、出口斜近平面投影/视锥 |
| `client/render/portal/PortalSectionVisibility.java` | 有界 section 选择与有预算编译（不移动共享 ViewArea） |
| `client/render/portal/PortalSurfaceRenderer.java` | 仅调试轮廓；默认路径不再提交纹理四边形 |
| `client/render/portal/PortalProjection.java` | 与主 pass 一致的投影（bob/hurt/spin） |
| `client/render/portal/PortalCaptureSettings.java` | 系统属性、递归上限、调试开关 |
| `client/render/portal/PortalRenderDiagnostics.java` | 限流日志、timings |
| `client/render/portal/PortalTeleportFade.java` | 可选调试 fade（默认不启用） |
| `mixin/client/PortalCaptureMixin.java` | 在 `LevelExtractor.extract` 返回后提取目标状态 |
| `mixin/client/PortalCameraAccessor.java` | 临时设置/恢复真实 `Camera` 位姿与视锥 |
| `mixin/client/PortalLevelRendererMixin.java` | 在 `addMainPass` 后向 framegraph 注入 `crythst_portals` |
| `client/render/portal/PortalRenderer.java` | 客户端入口 facade（注册管线/事件） |
| `resources/assets/crythst/shaders/core/portal_mask.*` | 屏幕空间洞口 mask |
| `resources/assets/crythst/shaders/core/portal_composite.fsh` | mask 外恢复主帧快照 |

旧离屏类（`PortalViewTarget` / `PortalViewTexture` / `PortalViewRegistry` /
`PortalCaptureScheduler` / `PortalRenderTypes` / `portal_view.fsh`）保留但**不在默认路径**，
仅作为历史/调试代码，不能描述为无缝实现。

---

## 2. 注册（需要手动加入两个入口点）

本里程碑新增文件不会自动生效，需要把下面两行加入现有入口点（本次未编辑这两个文件）：

### `CrythstFabric#onInitialize()`

建议放在 `PhysicsTicker.register();` **之后**，保证传送门 tick 在物理 tick 之后运行：

```java
io.mikaple.crythst.integration.portal.PortalManager.register();
```

完整上下文：

```java
CommandRegistrationCallback.EVENT.register(CrythstCommand::register);
PhysicsTicker.register();
io.mikaple.crythst.integration.portal.PortalManager.register();   // <-- 新增
ServerLifecycleEvents.SERVER_STOPPED.register(server -> { ... });
```

`PortalManager.register()` 内部会注册：

- `CommandRegistrationCallback` → `/crythst portal ...`；
- `ServerTickEvents.END_SERVER_TICK` → 传送检测；
- `ServerLifecycleEvents.SERVER_STOPPED` → 清空传送门和冷却表。

### `CrythstClient#onInitializeClient()`

```java
io.mikaple.crythst.client.render.portal.PortalRenderer.register();
```

建议放在 `StructureSync.registerClient();` 之后、现有 `LevelRenderEvents` 注册附近。
`PortalRenderer.register()` 会注册客户端 tick 快照刷新、连续穿越预测和
`LevelRenderEvents.BEFORE_GIZMOS` 调试轮廓回调；目标状态提取由
`PortalCaptureMixin` 注入 `LevelExtractor.extract` 的 RETURN 触发，
framegraph pass 由 `PortalLevelRendererMixin` 注入 `LevelRenderer.addMainPass` 的 RETURN
注册（两个 mixin 都已在 `crythst-client.mixins.json` 中）。

### 命令注册说明

`PortalCommand` 自己注册一个 `crythst` 字面量根节点。Brigadier 会按名称合并
`CommandNode`，因此会与现有 `CrythstCommand` 的 `/crythst` 合并成
`/crythst status ... portal create ...`，不需要修改 `CrythstCommand`。

---

## 3. 命令

需要 gamemaster（`Commands.LEVEL_GAMEMASTERS`）权限。

```text
/crythst portal create <name> [width] [height]
/crythst portal link <first> <second>
/crythst portal remove <name>
/crythst portal list
```

- `create`
  - 使用命令源实体的**眼睛位置** + 视线方向；非实体命令源使用命令位置 +1.62。
  - 平面放在视线前方 **2 格**。
  - 默认尺寸 `2 x 3`，允许范围 `0.5 .. 16`。
  - 平面法线朝向创建者（视线反方向），因此创建者看到的是正面。
- `link`
  - 双向链接；会先解除两端原有的链接，避免悬挂引用。
  - 不同维度也可以链接：实体可以跨维度，物理体 v1 跳过（见限制）。
- `remove`
  - 删除后自动清除其它传送门指向它的链接。
- `list`
  - 列出名称、维度、原点、尺寸、链接对象。

示例：

```mcfunction
/crythst portal create a 2 3
/crythst portal create b 2 3
/crythst portal link a b
```

---

## 3.5 持久化（v1）

传送门定义保存在**主世界**的 `SavedDataStorage` 中，文件通常位于
`world/data/crythst/portals.dat`。客户端渲染临时附件（snapshot/mask）不会写入存档；加载后由平面数据重新生成。

### 存储格式

```nbt
{
  version: 1,
  portals: [
    {
      id: "uuid-string",
      name: "north_gate",
      dimension: "minecraft:overworld",
      ox: 0.0, oy: 64.0, oz: 0.0,       // origin
      nx: 0.0, ny: 0.0, nz: 1.0,        // normal
      rx: 1.0, ry: 0.0, rz: 0.0,        // right
      width: 2.0,
      height: 3.0,
      linked: "other-portal-uuid"       // optional
    }
  ]
}
```

- `id` 是稳定 UUID；重启后不变。
- `linked` 只保存链接 id；目标端的平面/维度来自目标传送门自己的持久化记录。
- 损坏/缺字段的条目在读取时跳过并记录日志。
- 链接目标不存在时自动解除；单向链接在目标空闲时会修复为双向，否则解除。
- 加载发生在 `SERVER_STARTED`，早于任何传送 tick。
- `create` / `link` / `remove` 会立即 `markDirty()`；`BEFORE_SAVE`、`SERVER_STOPPING` 也会刷新存档。
- `SERVER_STOPPED` 清空内存注册表与冷却表。

### 入口点

现有入口点无需新增行；`PortalManager.register()` 已包含持久化生命周期：

```java
PortalManager.register();
```

> 注意：`CrythstFabric` 中该行目前位于 `PhysicsTicker.register()` 之前，因此传送检测在物理 step
> 之前运行；它使用上一次物理 step 的已提交位姿，然后本 tick 物理继续推进。功能正常；若需要严格
> “物理之后”顺序，把该行移到 `PhysicsTicker.register()` 之后即可（无需其它代码改动）。

---

## 4. 平面与坐标约定

`PortalPlane` 使用右手基 `(right, up, normal)`，满足：

```text
normal = right x up
```

局部坐标以平面中心为原点，单位为米：

```text
x = 沿 right      ∈ [-width/2, +width/2]
y = 沿 up         ∈ [-height/2, +height/2]
z = 沿 normal     （z > 0 为正面）
```

由创建者视线 `look` 推导：

```text
normal = -look
right  = normalize(worldUp x normal)
up     = normalize(normal x right)
origin = eye + 2 * look
```

视线接近竖直时使用 `(0,0,1)` 作为 `worldUp` 回退，避免基退化。

触发体为厚度 `±0.35` 的矩形板：

```text
|z| <= 0.35
|x| <= width/2
|y| <= height/2
```

传送门 v1 是**虚拟平面**：不需要框架方块，也不破坏/放置方块。

---

## 5. 链接变换数学

设入口平面 `A`、出口平面 `B`，各自基为 `(rA, uA, nA)`、`(rB, uB, nB)`。
链接旋转 `Q` 满足：

```text
Q * nA = -nB
Q * uA =  uB
Q * rA = -rB
```

这是一个**纯旋转**（行列式 `+1`），不是镜像。等价地，在入口局部坐标中：

```text
exitLocal = (-x, y, -z)
```

为什么法线要取反：实体从 `A` 的**正面**进入，应当从 `B` 对应的面出来并远离
`B` 表面。若使用 `nA -> nB`，玩家会在出口正面继续朝平面里走，立即再次穿越，
形成循环。`nA -> -nB` 让进入速度 `-nA` 映射为 `+nB`，正确地从出口外面离开。
同一个刚体变换同时适用于**双向**穿越：正面→背面从出口正面离开；背面→正面从出口
背面离开；两者速度都指向远离出口平面的方向，因此视角在穿门瞬间保持连续。

位置与方向变换：

```text
pB = originB + Q * (pA - originA)
vB = Q * vA
qB = Q * qA
wB = Q * wA        （v1：角速度按向量旋转）
```

`PortalTransform` 还提供：

- `transformPoint` / `transformDirection` / `transformRotation`；
- `inverse()`（用于反向穿越）；
- `viewDirection(yaw, pitch)` 与 `yawPitch(direction)`，用于实体朝向变换。

Minecraft 朝向约定：

```text
dir = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
yaw   = atan2(-dir.x, dir.z)
pitch = -asin(dir.y)
```

### 出口推出

映射后位置通常仍落在出口平面的薄板内，因此沿出口法线推出 `0.6` 格：

```text
side = sign(vB · nB)
如果速度几乎平行于平面：side = -sign(entryLocal.z)
如果仍为 0：side = +1
pExit = pB + side * 0.6 * nB      // 物理体
```

玩家不使用这个推出偏移：玩家脚点直接取 `T(eye) - (0, eyeHeight, 0)`，相机在穿门瞬间
正好位于 `T(eye)`，因此视角连续。非玩家实体使用沿**变换后运动方向**的小偏移（0.05 格，
正面/背面穿越会自动落在正确的一侧），物理体仍使用 0.6 格。

同时每实体/物理体有 **10 tick 冷却**，并保证同一 tick 最多传送一次，避免
出口平面立即把对象送回入口。

---

## 6. 实体 / 玩家穿越

服务器 tick 中的传送 pass：

1. 按维度遍历传送门，只处理 `linkedPortalId` 有效的门户对。
2. 对每个实体：
   - 用**上一 tick 采样点 → 当前采样点**的线段检测**双向**平面穿越
     （`PortalCrossing.findCrossing`），并要求交点在 `PortalShape` 多边形内；不再使用厚 0.35 的包围盒中心板；
     穿越时记录一次限流日志（portal、before/after signed distance、inside、结果）；玩家在
     传送门 4 格内时每个玩家最多 1 秒输出一次 attempt 日志，包含 body/eye inside、cooldown
     和 link resolved；
   - 所有实体都用**包围盒中心**（脚点 + 身高/2）作为穿越采样点，当前和上一 tick 都是；
     这样即使传送门 2 格多边形整体低于眼睛（创建时以视线为中心），躯干仍能触发；
     视觉上也正好是“身体大约一半越过平面”时才传送；
   - 过滤 removed、死亡、观察者、乘客、`noPhysics`、`Display`、末影龙部件；
   - 计算 `vB`、`qB`、目标点、出口推出；
   - 保留 `fallDistance` 与 `onGround`（合理近似）。
3. 玩家目标点按**眼睛位置**计算：`feet = T(eye) - (0, eyeHeight, 0)`，且**不加额外偏移**，
   这样 vanilla 相机在穿门瞬间正好位于 `T(eye)`，相对门户的视角连续。非玩家实体沿
   变换后运动方向偏移 0.05 格（自动落到出口对应一侧）。
4. 应用方式：
   - **同维度玩家**：`ServerPlayer.connection.teleport(PositionMoveRotation, Set.of())`，
     位置、变换后的速度与 yaw/pitch 一起发送，避免在门缝处丢失动量；
   - **同维度其它实体**：`setPos` + `setYRot` / `setXRot` + `setOldPosAndRot` +
     `setDeltaMovement`；
   - **跨维度**：构造 `TeleportTransition(targetLevel, pos, velocity, yaw, pitch,
     Set.of(), DO_NOTHING)` 并调用 `entity.teleport(...)`。传送转换里直接带上
     变换后的速度。
5. `ENTITY_READY` 记录 UUID 的冷却到期 tick；服务器停止时清空。

### 客户端连续穿越

`PortalCrossingController` 在客户端 tick 末尾用同样的“上一 tick → 当前 tick”身体中心线段
检测同维度传送门。穿越时立即对本地玩家应用同一个 `PortalTransform`：位置（按 `T(eye)` 反算脚点）、
速度、yaw/pitch、`oldPosAndRot`、`fallDistance`、`onGround`。相机因此连续进入目标空间，
不再等待服务端 tick 末尾的 teleport。

客户端预测后会发送 `crythst:portal_traversal`（只含变换前的 swept 采样，不含目标坐标）。
服务端重新校验多边形穿越和玩家距离，再由自己应用同一个 `T` 并通过
`connection.teleport(...)` 校正；如果判定不同，校正会覆盖客户端预测。
跨维度穿越不做客户端预测（客户端不能切换 `ClientLevel`），等待服务端维度切换。
`PortalTeleportFade` 默认不启用，只有显式 `-Dcrythst.portal.fade=true` 才注册。

骑乘由载具穿越后携带乘客；同维度拴绳保留，跨维度拴绳会解除。Create 等模组实体特殊逻辑
仍属于后续兼容工作。

---

## 7. 物理体 / 结构穿越

同维度链接时，遍历 `PhysicsWorld.bodies()`：

- 跳过：
  - `userData instanceof TerrainBody`；
  - `BodyType.STATIC`；
  - `removeRequested()` / `!inWorld()`。
- 使用 body OBB 中心/上一 tick 中心线段测试入口平面触发体（快速体走 swept 检测）。
- 对 `userData instanceof PhysicsStructure`：
  - 变换 `structure.pose()`（结构原点 + 旋转），
  - `structure.teleport(newPose)`（内部同步 render pose），
  - 写回 body 线速度/角速度并激活。
- 对普通 body：
  - `body.teleport(new Transform3d(pos, rot))`，
  - 写回线速度/角速度并激活。

每个 body 使用 `dimension + bodyId` 作为冷却键，10 tick 冷却、同一 tick 一次。

限制：大型结构只有**质心/OBB 中心**进入触发体时才会传送；结构不是按每个方块
逐个检测的。跨维度链接时物理体不会移动（物理世界按维度独立），会在命令反馈中提示。
物理体变换包含完整 pose（位置 + 旋转）、线速度和角速度；若有 per-body `gravityOverride`，
也会按同一个 `T` 旋转（等价于 up/重力方向变换）。

---

## 8. 渲染 v2：同帧、同 FBO、目标相机

默认路径不再把目标世界渲染到每 portal 的离屏纹理，也不再画四边形。流程：

```text
LevelExtractor.extract (主相机)
  └─ RETURN: PortalRenderManager.onExtractEnd
       ├─ PortalViewCamera.build
       │    T(mainCamera) + 出口平面斜近平面投影
       ├─ PortalVisibility.extract
       │    ├─ 换入独立 LevelRenderState
       │    ├─ 换入目标相机可见 section（不移动共享 ViewArea）
       │    ├─ 临时把真实 Camera 指向目标相机
       │    ├─ 嵌套 LevelExtractor.extract（guard 防递归）
       │    └─ finally 恢复全部共享状态
       ├─ PortalMaskGeometry.build（投影 + w>0 裁剪 + 三角化）
       └─ 递归层 2：目标相机里再看到一个 portal 时重复以上步骤

LevelRenderer.addMainPass
  └─ RETURN: FrameGraphBuilder.addPass("crythst_portals")
       ├─ readsAndWrites(LevelTargetBundle.main)   // 主 color+depth
       └─ executes PortalRenderPass.render

PortalRenderPass（每个 portal/层）
  1. 保存主 color+depth 到该层 snapshot
  2. 把洞口多边形画到该层 mask
  3. mask 对 snapshot depth 做 reverse-Z 深度测试；只标记真正可见的洞口像素
  4. 用同一洞口多边形把主 depth 写成远平面（不写颜色）
  5. 主 color 填目标雾色（全屏，composite 会恢复洞口外）
  6. 目标天空（SkyRenderer.render）
  7. 目标地形/实体/方块实体/粒子/云/天气/世界边界
     直接画进主 color+depth，renderArea 限制在洞口 bbox
  8. composite：mask 内保留目标像素，mask 外恢复 snapshot
  9. depth-restore：只在 `mask * parentMask` 外把 snapshot depth 写回，
     洞口内保留目标深度（供第 2 层遮挡）
```

关键点：

- **同一个 FBO**：入口世界观感和目标世界使用同一张主 color+depth，不再有
  `mainRenderTarget()` 重定向，也没有每 portal 的 `TextureTarget`。
- **逐像素洞口**：洞口是真实多边形，mask 由 GPU 光栅化，不是矩形贴图。
- **目标相机独立状态**：`PortalVisibility` 为目标相机新建 `LevelRenderState`，
  可见 section 来自现有主存储；共享 `ViewArea` 不移动。
- **出口裁剪**：`PortalClip.obliqueNearPlane` 把出口平面变成近平面，出口背后的
  几何不能漏进来。
- **递归 2 层**：第 2 层 mask 会乘第 1 层 mask，不能画到外层洞口之外。
- **性能**：目标地形/实体 fill 被限制在洞口 bbox；但 snapshot 和目标天空仍是全屏。

### 为什么不是硬件 stencil

RenderPearl 的 `DepthStencilState` 只有：

```java
depthTest, writeDepth, depthBiasScaleFactor, depthBiasConstant
```

没有 stencil compare/reference/write-mask/pass-op；`RenderPassDescriptor` 也没有
stencil attachment 参数。因此本项目使用 **mask attachment + composite** 作为
等价的逐像素洞口实现，并在文档中明确这一点，不把它称为硬件 stencil。

### 目标端可见性

`PortalSectionVisibility` 只能从主相机已有的 `ViewArea` 存储里选 section，
不会把共享 `ViewArea` 移到目标端（26.3 移动中心会重置 section mesh）。
因此目标端必须已经在主相机渲染距离内且已加载；远距离目标端、跨维度目标端仍需
独立的 `ViewArea` / chunk ticket。

---

## 9. 当前实现与限制

已实现：

- 同维度、同帧、同 FBO 的目标世界渲染；
- 多边形逐像素洞口（对每层场景深度做 reverse-Z 深度测试的 mask + composite，
  非矩形贴图）；
- 洞口深度按多边形清理、`mask * parentMask` 外恢复 snapshot depth；
- 出口平面斜近平面裁剪；
- 目标端地形、实体、方块实体、粒子、天空、雾、云、天气、世界边界；
- 2 层递归（第 2 层 mask 对第 1 层目标深度做深度测试，不会被第 1 层目标端几何穿透）；
- 同维度客户端连续穿越预测 + 服务端权威校正；
- 玩家/普通实体/投射物 swept 穿越、载具乘客、物理体 pose/速度/角速度、跨维度实体传送；
- 10 tick 冷却与出口防回穿；
- 多人完整快照同步（含 shape/link/pose）；
- `PortalManager.move` 移动传送门；
- 跨维度**传送**（实体）。

未实现 / 受限：

- **跨维度渲染**：客户端只有一个 `ClientLevel`，`PortalSnapshot.viewableIn`
  过滤跨维度目标。跨维度穿门由服务端完成，客户端等待维度切换。
- **远距离目标 section**：没有独立目标 `ViewArea` / chunk ticket。
- **硬件 stencil**：RenderPearl 未暴露 stencil state，见第 8 节。
- **OIT / see-through / always-on-top**：目标 pass 只走 classic opaque/translucent
  和 solid/translucent/after-terrain feature phase；未复制高级透明阶段。
- **方块破坏覆盖、transient block、目标端 gizmo**：未提交。
- **移动传送门自动绑定**：`PortalManager.move` 已可用，但没有每 tick 自动跟随
  `PhysicsStructure` 载具的绑定层。
- **跨维度物理体**：物理世界按维度独立，当前跳过跨维度物理体。
- **水/拴绳细节**：跨维度传送会解除拴绳；同维度保留，交由 vanilla leash tick。
- **游戏内自动测试/截图回归**：本仓库没有 Fabric client-gametest，且本次未运行
  `runClient`；纯数学测试已覆盖 mask、递归、swept crossing、相机连续性和变换。
- **性能预算**：目标 pass 的 fill 与洞口 bbox 相关，但 snapshot、目标天空、
  composite 和 depth-restore 仍是全屏；目标 section 提取仍按目标可见集成本。
  chunk section UBO 已改为提取阶段写入的独立预分配存储，避免每帧重建和日志刷屏。
  需要在目标机器上实测，不能凭空宣称达到 3 ms。

---

## 10. 后续 TODO 建议

1. 独立目标 `ViewArea` / chunk ticket，解决远距离与跨维度渲染。
2. 把目标 sky/cloud 改成接受 `RenderPass.RenderArea` 的 scoped pass，去掉全屏 snapshot。
3. 若 RenderPearl 后续暴露 stencil state，把 mask 层替换为真正的 stencil 层栈。
4. 目标 pass 补齐 OIT、see-through、always-on-top、block breaking、transient blocks。
5. 为移动传送门增加 `PhysicsStructure` 每 tick pose 绑定。
6. 添加 Fabric client gametest：固定 A/B portal，逐帧记录相机位置并断言无 >0.5 格跳变，
   洞口内截图包含目标端独有方块、洞口外像素不变。
