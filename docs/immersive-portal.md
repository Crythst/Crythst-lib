# 沉浸式传送门 v1（Immersive Portal, first milestone）

状态：**v1 静态传送门已实现**（模型 / 命令 / 真实目标场景捕获渲染 / 实体与物理体传送）。
传送门表面会把链接目标端的真实世界渲染到离屏纹理；递归深度目前为 1 层。
持久化、多人同步、移动传送门、跨维度物理体、递归 >1 仍属于后续 TODO。

本文对应 `TODO.md` 中“沉浸式传送门”里程碑的第一阶段。设计目标是先把
**两个静态平面之间的正确坐标/速度/朝向变换**做对，再在其上叠加递归视口。

---

## 1. 代码位置

| 路径 | 作用 |
| --- | --- |
| `integration/portal/PortalPlane.java` | 传送门平面：原点、法线、右/上基、宽高、局部↔世界坐标、平面触发体 |
| `integration/portal/Portal.java` | 传送门定义：稳定 UUID、名称、维度、平面、链接 id |
| `integration/portal/PortalSnapshot.java` | 跨线程只读快照（服务器 → 客户端） |
| `integration/portal/PortalTransform.java` | 入口平面到出口平面的刚体旋转、点/方向/四元数变换、yaw/pitch 转换 |
| `integration/portal/PortalManager.java` | 静态注册表、创建/链接/删除/列表、服务器 tick 与生命周期注册 |
| `integration/portal/PortalTeleporter.java` | 每 tick 的实体/玩家/物理体穿越、冷却与递归保护 |
| `integration/fabric/portal/PortalCommand.java` | `/crythst portal ...` 命令（合并进已有 `/crythst`） |
| `client/render/portal/PortalClientCache.java` | 渲染线程只读的不可变快照缓存 |
| `client/render/portal/PortalClientView.java` | 客户端 tick 从单机集成服务器刷新快照 |
| `client/render/portal/PortalCapture.java` | `END_EXTRACTION` 中的目标场景离屏捕获与节流 |
| `client/render/portal/PortalViewTexture.java` | 把离屏 `TextureTarget` 包装为 `TextureManager` 纹理 |
| `client/render/portal/PortalRenderer.java` | `BEFORE_GIZMOS` 中的相机相对四边面渲染 + 回退色块 |
| `client/render/portal/PortalRenderTypes.java` | 程序化 / 目标视图管线与 RenderType |
| `resources/assets/crythst/shaders/core/portal.vsh/.fsh` | 旋涡星空程序化表面（使用 `GameTime`） |
| `resources/assets/crythst/shaders/core/portal_view.fsh` | 采样目标场景离屏纹理的投影表面 |

未改动任何已有集成/渲染/物理核心文件，也未改动 `build.gradle`、`fabric.mod.json`、
mixin 配置或 `CrythstConfig`。

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
`PortalRenderer.register()` 会注册客户端 tick 快照刷新、目标场景捕获钩子
（`LevelExtractionEvents.END_EXTRACTION`）和 `LevelRenderEvents.BEFORE_GIZMOS` 渲染回调。

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

为什么法线要取反：实体从 `A` 的**正面**进入，应当从 `B` 的**正面**出来并远离
`B` 表面。若使用 `nA -> nB`，玩家会在出口正面继续朝平面里走，立即再次穿越，
形成循环。`nA -> -nB` 让进入速度 `-nA` 映射为 `+nB`，正确地从出口前面向外离开。

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
pExit = pB + side * 0.6 * nB
```

同时每实体/物理体有 **10 tick 冷却**，并保证同一 tick 最多传送一次，避免
出口薄板立即把对象送回入口。

---

## 6. 实体 / 玩家穿越

服务器 tick（物理 tick 之后）：

1. 按维度遍历传送门，只处理 `linkedPortalId` 有效的门户对。
2. 对每个实体：
   - 使用实体包围盒中心测试入口平面触发体；
   - 过滤 removed、死亡、观察者、乘客、`noPhysics`、`Display`、末影龙部件；
   - 计算 `vB`、`qB`、目标点、出口推出；
   - 保留 `fallDistance` 与 `onGround`（合理近似）。
3. 应用方式：
   - **同维度玩家**：`ServerPlayer.connection.teleport(PositionMoveRotation, Set.of())`，
     位置、变换后的速度与 yaw/pitch 一起发送，避免在门缝处丢失动量；
   - **同维度其它实体**：`setPos` + `setYRot` / `setXRot` + `setOldPosAndRot` +
     `setDeltaMovement`；
   - **跨维度**：构造 `TeleportTransition(targetLevel, pos, velocity, yaw, pitch,
     Set.of(), DO_NOTHING)` 并调用 `entity.teleport(...)`。传送转换里直接带上
     变换后的速度。
4. `ENTITY_READY` 记录 UUID 的冷却到期 tick；服务器停止时清空。

v1 不处理骑乘关系、拴绳、乘客传递、Create 实体特殊逻辑；这些属于后续兼容工作。

---

## 7. 物理体 / 结构穿越

同维度链接时，遍历 `PhysicsWorld.bodies()`：

- 跳过：
  - `userData instanceof TerrainBody`；
  - `BodyType.STATIC`；
  - `removeRequested()` / `!inWorld()`。
- 使用 body OBB 中心测试入口平面触发体。
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

---

## 8. 渲染 v1：真实目标场景捕获

v1 **不再是纯程序化假表面**：可见传送门会把链接目标端的真实世界渲染到离屏纹理，再投影回
入口平面。递归深度目前为 **1 层**（目标场景中的嵌套传送门回退为程序化表面）。

### 数据流

```text
PortalManager (server thread)
        │  snapshots()（同步、不可变；含目标平面与目标维度）
        ▼
PortalClientView (client tick, 单机)
        │  IntegratedServer.execute(...)
        ▼
PortalClientCache (volatile immutable List<PortalSnapshot>)
        │
        ├──► PortalCapture (LevelExtractionEvents.END_EXTRACTION, render thread)
        │      1. 由主相机 + 链接变换构造传送门相机
        │      2. 临时替换 LevelRenderState.cameraRenderState
        │      3. 二次调用 LevelRenderer.render(...)
        │      4. copyColorFrom(mainTarget) → 每传送门离屏 TextureTarget
        │      5. 注册/更新 PortalViewTexture（TextureManager，不触发 reload）
        │
        ▼
PortalRenderer (BEFORE_GIZMOS)
        └── 用屏幕空间 UV 采样离屏纹理；无捕获时回退程序化表面
```

- 渲染线程**不访问** `PortalManager` 或服务器对象；快照包含目标平面。
- 单机每个客户端 tick 最多排队一个快照任务；服务器慢时沿用上一帧快照。
- 多人游戏在 v1 中没有传送门网络包，缓存为空（后续同步里程碑解决）。

### 为什么二次 `LevelRenderer.render` 可行

- `LevelExtractionEvents.END_EXTRACTION` 在当前帧的渲染状态（含插值相机）提取完成之后、
  `LevelRenderer.render` 主 pass 开始之前触发；此时没有活动的 framegraph pass。
- `LevelRenderer.render` 的 `main` target 来自 `GameRenderer.mainRenderTarget()`（主目标）。
  捕获 pass 先写入主目标，随后 `RenderTarget.copyColorFrom` 立即复制到每传送门离屏目标；
  主 pass 随后会清空主目标，因此不会污染最终画面。
- 捕获时临时把 `LevelRenderState.cameraRenderState` 换成传送门相机，结束后恢复。
- `LevelRenderer.submitFeatures` 会清空 `entityRenderStates` / `blockEntityRenderStates` /
  `blockBreakingRenderStates`；捕获前后会保存并恢复这些列表，保证主 pass 仍能绘制实体与方块实体。
- 捕获期间将 `shouldUseMultiDrawIndirectForTerrain` 置 false，走稳定的非 indirect 路径。

### 传送门相机

设主相机位置 `P`、方向四元数 `q`，链接变换为 `T`（旋转 `Q`、平移）：

```text
P' = T.transformPoint(P)
q' = Q * q
viewRotationMatrix' = conjugate(q')
projectionMatrix'  = 主相机投影（完全相同）
```

目标场景用与主相机**完全相同的投影**渲染。因此入口平面上的任意屏幕点在目标渲染中位于
同一屏幕位置；把入口四边面的裁剪空间坐标换算成 UV 即可得到正确的投影映射：

```text
u = ndc.x * 0.5 + 0.5
v = ndc.y * 0.5 + 0.5      // 与 vanilla screenquad.vsh 的 NDC→UV 约定一致
```

### 表面管线

- 两个管线共用顶点着色器 `crythst:core/portal.vsh` 和顶点格式 `POSITION_TEX_COLOR`：
  - `crythst:core/portal`：程序化旋涡星空（回退 / 嵌套传送门）；
  - `crythst:core/portal_view`：采样 `Sampler0`（离屏目标场景）。
- 两者都是**不透明管线**（无混合、写深度），避免 26.3 自定义半透明 RenderType 需要
  单独构建 OIT pipeline set 的问题；表面在 solid 阶段绘制并按深度正确遮挡。
- 每个传送门一个离屏 `TextureTarget` 和一个 `PortalViewTexture`（`AbstractTexture` 包装），
  通过 `TextureManager.register` 注册；视图变化时直接替换 wrapper 的 `textureView`，
  **不会触发资源重载**。
- 每个传送门一个按纹理 id 缓存的 `RenderType`，渲染时按 RenderType 分组提交。

### 递归、性能与回退

- **递归深度 1**：捕获 pass 中 `PortalCapture.isCapturing()` 为 true，嵌套传送门一律使用
  程序化表面，绝不采样正在写入的纹理，因此没有反馈循环。更深递归需要多张离屏纹理和多趟
  捕获，留给后续 TODO。
- 捕获节流：最多每 **100 ms**（10 Hz）捕获一轮；每轮最多 **2 个**最近的可见传送门；
  距离超过 **160 格**不捕获。未捕获的帧继续使用上一张纹理。
- 可见性：主相机 `cullFrustum` 对入口平面 AABB 裁剪；跨维度链接不捕获（回退程序化）。
- 回退：
  - 管线无法构建 → `RenderTypes.debugFilledBox()` 紫色四边形；
  - 捕获失败/纹理不可用 → 程序化 `crythst:core/portal` 表面；
  - 嵌套传送门 → 程序化表面。

### 可见性

- 距离超过 512 格不渲染；
- 使用相机 `cullFrustum` 对平面 AABB 做视锥裁剪；
- 多个表面按 RenderType 分组、组内从远到近提交。

**仍未实现**：递归深度 > 1、模板缓冲/平面反射、摄像机穿面无缝过渡、天空/洞口边缘的
逐像素模板处理。v1 的目标是“表面确实显示目标端真实几何”，而不是最终形态。

---

## 9. 当前限制（v1）

- **递归深度只有 1 层**：目标场景是真实的，但目标场景里的嵌套传送门回退为程序化表面。
- 没有模板缓冲/平面反射/逐像素洞口；表面是整块不透明四边形。
- 摄像机穿过平面时仍是瞬移式 `connection.teleport`，没有视觉上的无缝过渡。
- 捕获通过二次 `LevelRenderer.render` 实现，每轮会额外提交一次完整世界渲染：
  - 默认 10 Hz、最多 2 个最近传送门、160 格内；
  - 会触发一次额外的 `submitFeatures`（其他模组的 LevelRender 事件监听器也会被调用），
    虽然实体/方块实体列表会保存并恢复，但属于 v1 的性能/兼容性取舍；
  - 目标维度必须有已加载区块；未加载区域显示为雾色。
- 捕获 pass 不渲染天空（`shouldRenderSky=false`），开阔天空方向显示为雾色。
- 跨维度链接：实体可用 `TeleportTransition` 穿越，但不会做目标维度离屏捕获（回退程序化）。
- 没有传送门框架方块、点火方式、数据驱动定义、颜色/音效配置。
- 没有持久化；服务器重启后传送门消失，UUID 不会恢复。
- 没有网络同步；多人客户端不显示传送门。
- 传送门是静态的，不能挂在 `PhysicsStructure` 上随结构运动。
- 物理体不跨维度（按维度独立的 `PhysicsWorld`）。
- 大结构以 OBB 中心作为穿越判定，不是逐方块/逐 OBB 深度检测。
- 玩家任意方向重力（`PlayerGravity`）下的朝向变换尚未与自定义 up 组合。
- 骑乘、乘客、拴绳、Create 实体兼容未处理。
- 高速投射物只做离散位置检测，没有连续碰撞（CCD）扫掠。
- 冷却/递归保护只存在内存中，不持久化。
- 传送门没有实体/方块碰撞体，玩家可以自由走进触发板。
- 离屏 `TextureTarget` 使用主目标同分辨率（未降采样）；窗口尺寸变化时重建。

---

## 10. 后续 TODO 建议

1. **递归视口**：v1 已有 1 层真实目标场景捕获；下一步是多离屏纹理 + 多趟捕获（深度 2~3）、
   模板缓冲/逐像素洞口、近裁剪面与相机穿面过渡。
2. **持久化**：`SavedData` 保存 UUID/名称/维度/平面/链接；服务器重启后恢复稳定 UUID。
3. **网络同步**：链接与平面快照包；客户端只读快照；与结构同步包共用版本/分片策略。
4. **移动传送门**：挂在 `PhysicsStructure` 上，入口平面跟随插值后的结构位姿。
5. **框架与数据驱动**：框架方块/tag、最小/最大尺寸、点火方式、颜色/音效。
6. **跨维度物理体**：结构迁移时的区块/tick/持久化归属与物理世界切换。
7. **连续碰撞**：为投射物和高速实体做平面扫掠，避免一 tick 穿过薄板。
8. **兼容层**：骑乘/乘客/拴绳、Create contraption、自定义重力方向。
9. **调试 gizmo**：入口矩形、法线、链接变换、触发包围盒、递归层数统计。
10. **性能**：
    - 降低离屏分辨率（当前与主目标同分辨率）；
    - 只对真正占据屏幕的最近传送门捕获；
    - 捕获帧预算/自适应降频；
    - 评估是否用更轻量的第二相机 pass 替代完整的 `LevelRenderer.render`。
11. **捕获隔离**：评估在捕获 pass 中屏蔽其他模组的 LevelRender 事件，减少二次提交副作用
    （需要新的 mixin 或更细的渲染钩子）。
