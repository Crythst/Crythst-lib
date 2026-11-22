# Crythst TODO

状态标记：`[ ]` 未开始，`[~]` 进行中，`[x]` 已完成。

## 下一步重点：沉浸式传送门（Immersive Portal）

> 当前 `docs/immersive-portal.md` 描述的是**离屏纹理原型**，不是无缝实现。
> 重构路线、架构决策、里程碑和验收标准见
> [`docs/seamless-portal-replication.md`](docs/seamless-portal-replication.md)。

目标：做一套真正“可穿过去”的传送门，而不是简单地把玩家传送到另一个坐标。优先支持静态传送门，再扩展到物理结构上的移动传送门。

### 设计范围

- [~] **传送门定义与数据**
  - 数据驱动：框架方块/tag、最小/最大尺寸、点火方式、目标维度与目标坐标、朝向、颜色/音效。
  - 同一对传送门共享稳定 ID；支持单向/双向、多个目标。
  - 与现有 `LevelPhysics` / 子关卡（空 `ServerLevel`）生命周期解耦，避免每个维度重复加载。
  - v1 已实现：静态注册表、稳定 UUID、命名、双向链接、命令创建，以及 `SavedData` 持久化；
    数据驱动框架/多目标仍待做。
- [~] **入口/出口平面与坐标变换**
  - 每个传送门有入口平面（位置、法线、右/上基向量、宽高）。
  - 建立入口到出口的刚体变换：位置、旋转、缩放（1:1）、速度/角速度变换。
  - 支持实体、投掷物、掉落物、玩家、物理刚体、结构方块穿越。
  - v1 已实现：静态平面、刚体旋转（法线反向、上方向对齐）、点/方向/四元数变换、实体与物理体穿越。
- [~] **沉浸式渲染**
  - 传送门表面实时“看到另一侧”：递归视口 / 模板缓冲 / 平面反射，而不是静态贴图。
  - 摄像机穿过平面时无缝切换；处理近裁剪面、递归深度上限、性能降级。
  - 支持任意朝向，包括天花板/地板传送门；与玩家重力方向（现有 `PlayerGravity`）组合。
  - 传送门边缘、遮挡、雾、光照、实体阴影正确。
  - v1 已实现：`END_EXTRACTION` 中用传送门相机二次调用 `LevelRenderer.render`，把真实目标场景
    捕获到每传送门离屏纹理，再以屏幕空间投影贴回入口平面；递归深度 1（嵌套传送门回退程序化表面）。
    递归 >1、模板缓冲、摄像机无缝过渡、移动传送门仍未做。
- [~] **实体/玩家穿越**
  - 玩家：位置、速度、视角 yaw/pitch、重力方向一起变换；避免瞬移感和碰撞箱穿插。
  - 普通实体：保留速度、骑乘关系、拴绳、乘客；Create 等模组实体走兼容钩子。
  - 投射物：连续碰撞检测，防止高速穿门。
  - 穿门冷却/递归保护，防止同一 tick 反复穿越。
  - v1 已实现：位置/速度/yaw-pitch/fallDistance/onGround、10 tick 冷却、每 tick 一次；
    玩家目标按变换后的眼睛位置反推脚部，客户端用短黑屏淡入淡出遮盖瞬移；骑乘/拴绳/乘客/CCD/
    自定义重力仍待做。
- [~] **物理体与结构穿越**
  - 物理刚体/`PhysicsStructure` 穿过传送门时变换位姿、线速度、角速度。
  - 与 OBB、世界地形碰撞、结构间碰撞配合，避免在门平面卡住。
  - 移动传送门（挂在物理结构上）：传送门自身随结构运动，入口平面使用插值后的结构位姿。
  - 结构跨维度/跨传送门时的区块、tick、持久化归属。
  - v1 已实现：同维度静态物理体/结构以 OBB 中心触发，变换位姿/线速度/角速度并同步 render pose；
    移动传送门已实现（`PortalAnchor`，见验收标准）；跨维度物理体未做。
- [~] **网络与持久化**
  - 传送门链接、框架状态、目标信息同步；客户端渲染只读快照。
  - 保存/加载传送门链接；服务器重启后 ID 稳定。
  - 与多人结构同步包共用版本/分片策略。
  - v1 已实现：主世界 `SavedData` 持久化稳定 UUID/名称/维度/平面/链接，启动加载并清理无效链接；
    客户端捕获纹理不持久化。多人同步仍未做。
- [x] **可视化与调试**
  - `/crythst portal` 创建/连接/删除/列出传送门。
  - 调试 gizmo：入口平面、法线、目标变换、穿越包围盒。
  - 传送门渲染性能统计（递归层数、每帧耗时）。
  - 已实现命令与穿门淡入淡出；F3 + F6 新增 `crythst:portal`（开口轮廓、法线、
    right/up 轴、同维度出口轮廓与连线、穿越包围盒、名称/尺寸/载体标签）和
    `crythst:portal_stats`（渲染门数、最大递归层、区块段数、提取耗时、pass 录制耗时、
    当前递归/每轮/目标上限）；两者默认关闭，按 F3 + F6 打开。

### 验收标准（第一版）

- [~] 两个静态传送门之间，玩家/掉落物/箭可以双向穿越，速度方向按出口平面正确变换。
- [~] 摄像机穿过平面时没有明显跳变；递归看到另一侧至少 1 层。
  - 已实现目标场景离屏捕获（深度 1）；摄像机本身仍需后续做平滑过渡。
- [~] 物理方块结构可以整体穿过传送门，位姿/速度/角速度连续。
- [x] 传送门在移动物理结构上时，入口平面跟随结构位姿，实体不会被甩出。
  - 已实现 `PortalAnchor`（载体 body id + 载体局部平面）：服务端每 tick 用 `PhysicsStructure#pose()`
    重新解出世界平面，客户端每帧用 `ClientStructureCache#interpolatedPose` 解出插值平面，
    所以门随结构平移/旋转，且不会以 20 Hz 步进。穿门时跳过载体自身，并把出口载体的速度
    附加给穿越实体（避免被甩出）。`/crythst portal attach <name> [bodyId]` / `detach <name>`。
  - 跨维度物理体、载体内实体的相对速度继承仍是后续工作。
- [~] 单人 + 专用服务器均不崩溃；重启后传送门链接仍存在。
  - 已实现主世界 `SavedData` 持久化与启动恢复；多人同步仍待做。
- [x] 不触发 Minecraft 资源重载；动态纹理遵守现有 `RuntimeTextureCache` 约定。
  - 传送门视图纹理直接包装离屏 `RenderTarget`，通过 `TextureManager` 注册，不触发重载。

### 依赖/前置

- [x] 真实结构渲染（`RealStructureBlockRenderer` + `BatchedStructureRenderer`）
- [x] 方块质量参数（`BlockPhysicsRegistry`）
- [x] 结构 BE 数据与持久化（含本地坐标和辅助空 Level 重挂载）
- [x] 多人结构同步（快照/姿态/移除 + 客户端缓存）
- [x] 实体停留在物理体上（`EntityStructureRiding`）
- [x] Create 兼容（软依赖 + compileOnly）

---

## 其他待办

- [x] 真实结构渲染（26.3 方块模型 + 分 section 批量缓存）
- [x] 方块质量/摩擦/弹性数据包
- [x] 结构方块实体（BE）数据复制/重挂载；tick 走辅助空 Level
- [x] 结构持久化（保存/加载、稳定 UUID）
- [x] 多人结构同步（快照/姿态/移除 + 客户端缓存，快照/增量/分片）
- [x] 玩家任意方向重力：相机、OBB 碰撞、实体/方块交互
- [x] 实体/玩家停留在物理体上，Create 实体软兼容
- [x] 浮力（水/末地石）
- [x] 玩家挖掘/放置结构方块、铲水反作用力
- [x] 灰度图着色 + 原版钻石素材测试
- [x] 运行时实体纹理生成（无资源重载）
- [x] 物品/方块特殊渲染（star、无 BER submit）
- [x] 结构渲染与碰撞统一坐标系（structure pose + 未偏移体素坐标）
- [x] 物理 tick 锁定服务端 tick，消除渲染抖动；`/crythst timing` 诊断
- [x] 结构破坏后自动按 26 邻接（面/棱/角）连通性分裂；整体质量归零时直接移除
- [x] 选中框按命中面向内步进，修复 + 面选中相邻空格
- [x] 结构内部面剔除、体素 AO、按暴露面采样世界光照
- [x] 实体-结构碰撞集成进原版 `Entity.move`/`collide`：子步进、滑动、支撑/台阶、速度投影，不再逐 tick 传送玩家
- [x] 客户端碰撞使用本地姿态时间线/多人快照，服务端与客户端运行同一求解器
- [x] 结构方块破坏/放置粒子与按世界坐标衰减的声音；破坏粒子按结构位姿精确发射，不再用 `BlockPos` 取整的 2001
- [x] 结构方块实体渲染：原版 BER 重建/提交、箱子开盖动画、结构感知告示牌编辑
- [~] 传送门渲染/传送重写进行中：按 `docs/seamless-portal-replication.md` 真 stencil/同 FBO、连续穿越
- [x] 传送门捕获改用有界 section 选择，修复退化相机导致的渲染线程卡死
- [x] 渲染姿态改为时间戳插值，单人与网络结构均按帧平滑
- [x] 传送门目标场景按传送门相机重新提取，直接渲染进离屏纹理；不再切换 multi-draw 缓冲
- [x] 穿门改为眼睛越过平面触发，可半身进入；淡入淡出缩短
- [x] 放置状态走 `BlockPlaceContext`，楼梯/台阶/原木朝向正确
- [x] 选择轮廓使用原版 `BlockOutlineRenderState`，命中结果与交互共用
- [x] 结构碰撞不再逐 tick 传送玩家，修复贴墙/落地黏住
- [x] 中键拾取结构方块（`PICK` / `PICK_WITH_DATA` + 服务端 `tryPickItem`）
- [x] 放置预测在客户端取消，修复幽灵方块与双重放置音效
- [x] F3+F6 分层调试条目 `crythst:physics*`，默认全部关闭；`crythst:structure_target` 默认显示在 F3 文本
- [x] 渲染姿态统一为 timestamped `PoseTimeline`（本地与网络结构），不再用客户端 partial tick 采样
- [x] 地形 section 碰撞随方块编辑失效，并按刚体运动范围预取
- [x] 调试接触点层在 `config.debug` / `/crythst debug` 下自动开启 `setDebugContactCapture`
- [~] 多人结构的渲染/选择使用延迟姿态，而客户端碰撞使用最新快照；快速移动的远端结构可能有一个同步间隔的碰撞/视觉差异
- [~] `PlayerObbCollision` 仍是移动后校正；新增 `PlayerObbSweep` 用扫掠 SAT
  （15 条 Minkowski 轴）把单 tick 穿过薄障碍的运动截断在首次接触处，穿透/隧穿
  问题已在纯数学层覆盖并有单元测试；沿接触面滑动、结构 OBB 的扫掠以及客户端
  结构查询边界仍未做。

### 当前真实缺口（来自 docs 审计）

- [x] **跨边界图**：structure↔host / structure↔structure redstone、neighbour、hopper、fluid、game event；旋转/缩放面映射仍是主导轴近似。
- [~] **AI / pathfinding**：结构感知 `PathNavigationRegion`/节点评估已实现；minecart/boat、任意模组直接 `Level` 查询仍缺。
- [ ] **完整结构 `Level` 视图**：`StructureLevelView` 已有，但 Sable 式 plot/sub-level、chunk holder、光照/pathfinding 集成仍缺。
- [x] **完整 6-DOF 约束 + 约束持久化**：每轴限位/电机、Bullet 映射、按结构 UUID + 局部锚点保存。
- [x] **多人结构 BE NBT / 动画 / 告示牌同步**：protocol v3 分片/dirty 更新；超限条目回退模型。
- [x] **自定义 fallback 破坏的 drops/stats**：已生成 vanilla drops、工具耐久和挖掘统计；XP 仍由 redirect 路径负责。
- [x] **force debug UI / 自动接触点采集**：`crythst:physics_forces` + `config.debug` 自动 capture。
- [~] **传送门**：同帧 masked pass、递归 2、连续穿越已实现；移动载体/跨维度目标渲染/硬件 stencil 仍缺。
- [x] **schematic 上下文**：vanilla structure NBT + Crythst volume NBT 载入/实例化；Sponge 未支持。

## Native API v2

- [x] `StructureContainer` / `ServerStructureContainer` / `StructureObserver` / `StructureContainerEvents` / `StructureArgumentType`
- [x] `api/client`：`StructureRenderView` / `StructureRenderData` / `StructureRenderPose` / `StructurePoseProvider` / `StructureClipExtension` / `StructurePickExtension` / `EntityStructureTracking`
- [x] `api/assembly`：`StructureGather`（26 邻接）、`StructureAssembly`、`AssemblyOptions`、`DisassembleOptions`、`AssemblyTransform`
- [x] `api/block`：`BlockEntityStructureActor` + `StructurePhysicsContext`
- [x] `api/physics/force`：`ForceGroup` / `QueuedForceGroup`（N、N·m，子步结束转冲量）
- [x] `api/math/StructureMath` 与 `EntityStructureUtil` 的 `projectOutOfStructure` / `distanceSquaredWithStructures` / `getEyePositionInterpolated`
- [x] `PlayerObbCollision` 结构盒改为 structure pose + 未偏移体素（不再二次减 COM）
- [x] constraints：fixed / free / rotary / prismatic 句柄、`PhysicsPipeline`/`PhysicsStructure` 增删与查询
- [x] constraints：完整每轴 6-DOF、电机/限位和约束持久化
- [x] ropes / box physics objects：冲量绳、dynamic/static/kinematic box、句柄与 impulse/teleport/remove
- [x] loading tickets、tracking-point SavedData、按维度持久化与重绑
- [x] schematic 上下文、host↔structure redstone/hoppers/fluid 图
- [ ] plot / plot-yard / `SubLevel` 风格的第二 `Level` 视图（Create Aeronautics drop-in 的阻塞项）
- [x] force totals、`StructureMassTracker`、propeller / lift-provider hook
- [~] 网络客户端容器：多人仍是只读 `ClientStructureSnapshot`；单人可提供 live `PhysicsStructure` 视图
