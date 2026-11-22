# Crythst

**Crythst** 是一个独立的、MIT 许可的 **大型模块化 Fabric 库**，面向 **Minecraft / Fabric 26.3**。
它不是内容模组，也不是单一的物理引擎：物理、体素结构、世界碰撞、任意方向重力与玩家 OBB 校正、实体交互、真实方块渲染、特殊模型/着色器、纹理生成、持久化、多人同步和传送门都以可组合的子系统提供。

- 模组 ID：`crythst`
- Java 包名：`io.mikaple.crythst`
- 当前版本：`0.1.0`
- 物理后端：**libbulletjme 23.2.0**（JNI / Bullet Physics）
- 运行环境：Java 25、Fabric Loader 0.19.5+、Fabric API 0.161.0+26.3
- 平台：Windows / macOS / Linux（x86_64、arm64）+ Android（FCL、PojavLauncher 等）

> 功能模组可以把 Crythst 作为底层依赖，只取需要的模块；也可以同时使用物理结构、渲染、交互和同步，构建“会移动/会倒塌/可交互的方块世界”。

## 30 秒了解 Crythst

Minecraft 原版把方块放在固定的世界网格里。Crythst 允许把一块方块区域“拿起来”，变成一个会移动、旋转、碰撞并承载实体的**物理结构**。

它适合做：

- 可驾驶/可飞行的方块载具、飞船、列车；
- 会旋转、翻倒、被撞击的方块平台；
- 需要保留原版方块模型、选择、挖掘、放置、容器和红石行为的移动结构。

Crythst 提供：

- Bullet 刚体 + 体素碰撞体；
- 结构方块的真实模型渲染与 section 批量缓存；
- 结构上的原版方块逻辑（辅助 void level）；
- 玩家/实体碰撞、方向重力、选择/交互、持久化与多人同步。

Crythst 不提供：

- 开箱即用的飞船/载具内容（这是使用它的模组的职责）；
- Sable 的 `dev.ryanhcode.sable.*` API 兼容包；
- 完整的 plot/sub-level 世界模型。当前实现是“宿主世界 + 每结构辅助 void 区域”。

### 新读者阅读顺序

1. 先看下面的 [快速开始](#快速开始)，把库跑起来；
2. 读 [docs/architecture.md](docs/architecture.md) 了解服务端/客户端、物理世界和渲染的分层；
3. 做功能模组时看 [docs/api.md](docs/api.md) 和 [docs/api-cookbook.md](docs/api-cookbook.md)；
4. 做移动结构玩法时看 [docs/player-structure-interaction.md](docs/player-structure-interaction.md)、[docs/entity-physics-collision.md](docs/entity-physics-collision.md) 和 [docs/void-level-ticking.md](docs/void-level-ticking.md)；
5. 评估 Sable / Create Aeronautics 兼容性时看 [API_GAP_ANALYSIS.md](API_GAP_ANALYSIS.md)。

## 关键概念

| 概念 | 含义 |
| --- | --- |
| 物理体 body | Bullet 中的一个刚体，拥有质量、速度、pose 和碰撞形状 |
| 结构体素 voxel | 结构局部整数网格中的一格；一个结构由多个体素组成 |
| 结构体 `PhysicsStructure` | 物理体 + 体素体积 + 材质/质量 + 渲染/碰撞/交互入口 |
| pose / `blockScale` | pose 是结构局部坐标到世界坐标的刚体变换；`blockScale` 是一格的世界尺寸 |
| 辅助 void level | 每个宿主维度懒创建的真实 `ServerLevel`，保存结构方块副本并运行原版方块逻辑 |
| 客户端快照 | 多人环境下客户端拿到的只读结构/姿态数据，用于渲染、选择和预测 |

## 与 Sable / sable-port 的关系

Crythst 是**独立实现**，不是 Sable 或 sable-port 的 fork，也不包含它们的代码。

- **Sable** 是上游项目，探索“可移动方块结构的刚体仿真”，使用 **PolyForm Shield License 1.0.0**。
- **sable-port** 是 Sable 面向 Fabric 的移植/适配版本，同样使用 **PolyForm Shield License 1.0.0**。
- Crythst 的设计问题空间受到二者启发；批量渲染、section 遮挡、选择、辅助方块逻辑等部分参考了其思路和术语，但代码独立实现。
- 本仓库未复制 Sable/sable-port 的源代码、注释或资源文件；当前审计未发现整文件或大段连续代码复制。
- 少量公共 API 名称、包结构和术语有意对齐（如 `ForceGroup`、`MassData`、`RigidBodyHandle`、`PhysicsPipeline`、约束配置、`VoxelColliderData`），这些类的实现是独立的。
- Crythst 不提供 `dev.ryanhcode.sable.*` 兼容包；面向 Sable 的模组需要按 Crythst API 移植。
- 若未来引入任何 Sable/sable-port 代码，必须按 PolyForm Shield 处理，不能继续仅以 MIT 发布。

来源与证据见 `docs/license-audit-2026-11-22.md`。以上是工程来源说明，**不构成法律意见**。

## 模块

| 模块 | 职责 | 主要入口 |
| --- | --- | --- |
| `physics` | 刚体、约束、查询、角色、固定步长、可重定位原点 | `CrythstApi`, `PhysicsWorld`, `RigidBodyHandle` |
| `physics.voxel` / `structure` | 体素贪心碰撞、质量/惯性、移动方块结构 | `VoxelColliderBuilder`, `PhysicsStructure` |
| `integration.minecraft` | 每维度物理世界、辅助 void level 方块逻辑、地形碰撞、玩家重力 | `MinecraftPhysics`, `LevelPhysics`, `PlayerGravity` |
| `integration.minecraft.entity` | 实体与结构碰撞（`Entity.move` 内）、实体停留在结构上、Create 实体软兼容 | `EntityStructureCollision`, `EntityStructureRidingManager` |
| `integration.minecraft.interaction` | 选择/射线、挖掘/放置结构方块、拾取方块、反作用冲量、铲水反作用 | `StructureSelection`, `StructureInteraction` |
| `integration.minecraft.storage` | 结构快照、方块实体 NBT、持久化、稳定 UUID | `StructurePersistence`, `StructureSavedData` |
| `network.structure` | 多人结构快照/姿态/移除同步 | `StructureSync`, `ClientStructureCache` |
| `client.render.structure` | 真实方块模型渲染、分 section 批量缓存、结构轮廓、F3+F6 调试层 | `BatchedStructureRenderer`, `StructureSelectionOutline`, `CrythstPhysicsDebugRenderer` |
| `client.render.item/block` | 物品 special model、star 着色器、无 BER submit 的方块特殊渲染 | `StarItemModelRenderer`, `SpecialBlockRenderManager` |
| `client.texture` | 灰度图着色、外轮廓、运行时实体纹理（无资源重载） | `GrayscaleColorizer`, `GrayscaleFormula`, `RuntimeTextureCache` |
| `integration.portal` | 传送门平面、传送变换、沉浸式表面 | `PortalManager`, `PortalRenderer` |
| `integration.create` | Create-Fly 软兼容（compileOnly） | `CreateCompat`, `CreateStructureBridge` |

## 目录

- [30 秒了解 Crythst](#30-秒了解-crythst)
- [关键概念](#关键概念)
- [与 Sable / sable-port 的关系](#与-sable--sable-port-的关系)
- [模块](#模块)
- [核心能力](#核心能力)
- [平台支持](#平台支持)
- [快速开始](#快速开始)
- [包布局](#包布局)
- [构建](#构建)
- [命令](#命令)
- [许可与来源声明](#许可与来源声明)
- [状态与路线图](#状态与路线图)
- [TODO](TODO.md)

---

## 核心能力

### 物理与结构

| 能力 | 说明 | 主要入口 |
| --- | --- | --- |
| 刚体物理 | 静态 / 运动学 / 动态刚体，质量、阻尼、CCD、碰撞过滤、休眠控制 | `BodyDef`, `BodyHandle` |
| 体素结构 | 把方块网格变成可旋转、可翻倒的复合刚体 | `VoxelVolume`, `StructureDef`, `PhysicsStructure` |
| 贪心合并碰撞盒 | 相邻同材质方块合并为尽量少的长方体 | `VoxelColliderBuilder` |
| 完整 3×3 惯性张量 | 质心、转动惯量、惯性积和平行轴项 | `VoxelColliderData#inertia()` |
| 数据驱动方块质量 | 方块/tag 的 mass/friction/restitution/buoyancy，服务端数据包热重载 | `BlockPhysicsRegistry` |
| 任意方向重力 | 重力是三维向量；默认向下，支持世界/每刚体覆盖 | `WorldSettings#gravity()`, `BodyDef#gravityOverride()` |
| OBB 与 15 轴 SAT | 倾斜包围盒相交与穿透向量 | `BodyHandle#obb()`, `OrientedBox` |
| 射线 / 扫掠 / 约束 | 世界空间查询；固定、铰链、滑轨、点约束 | `RayQuery`, `SweepQuery`, `*ConstraintDef` |
| 固定步长 + 插值 | 物理定步运行；渲染使用带时间戳的一 tick 延迟姿态时间线 | `FixedStepClock`, `PoseTimeline` |
| 可重定位双精度原点 | 外部世界 `double`，内部 Bullet 局部原点 | `PhysicsWorld#rebaseOrigin()` |

### 世界与玩法

| 能力 | 说明 | 主要入口 |
| --- | --- | --- |
| 每维度物理世界 | 每个 `ServerLevel` 自动维护独立世界 | `MinecraftPhysics`, `LevelPhysics` |
| 地形碰撞 | 16³ section 静态合并体、缓存、方块编辑失效、碰撞组 | `TerrainCollision` |
| 辅助 void level 方块逻辑 | 懒创建真实 `ServerLevel`，复制结构方块并运行原版方块 tick/邻居/红石/流体逻辑 | `EmptyPhysicsLevel` |
| 玩家重力方向 | 修改空中重力、相机朝向、OBB 碰撞和交互视线 | `PlayerGravity`, `PlayerObbCollision` |
| 实体碰撞/搭乘 | 碰撞集成进原版 `Entity.move`，实体被结构挡住但不推动结构；站在结构上的实体随结构移动 | `EntityStructureCollision`, `EntityStructureRidingManager` |
| 结构选择/交互 | 结构命中共用原版 `Level.clip`；原版轮廓、挖掘/放置/中键拾取、反作用冲量、铲水反作用 | `StructureSelection`, `StructureInteraction` |
| 浮力 | 水/流体排水浮力，任意重力方向；末地石低密度浮起 | `BuoyancyManager` |
| Create 兼容 | 软检测 + compileOnly，contraption 实体可停留在结构上 | `CreateCompat`, `CreateStructureBridge` |

### 渲染与纹理

| 能力 | 说明 | 主要入口 |
| --- | --- | --- |
| 真实结构渲染 | 26.3 方块模型 + 按 16³ section/渲染层批量缓存 | `BatchedStructureRenderer` |
| 调试渲染 | F3+F6 分层条目：合并碰撞盒、OBB/AABB、质心/坐标轴、接触点/重力、loose bodies；默认全部关闭 | `CrythstDebugEntries`, `CrythstPhysicsDebugRenderer` |
| 方块特殊渲染 | 不走 `BlockEntityRenderer#submit`，在 level 渲染事件提交 | `SpecialBlockRenderManager` |
| 物品 special model | star 着色器、专用 RenderType、灰度/纯黑掩码 | `StarItemModelRenderer` |
| 灰度图公式 | 原始公式、平滑 ramp、vanilla 3–8 色带、线性光/伽马 | `GrayscaleFormula`, `SmoothGrayscaleColorizer` |
| 运行时实体纹理 | `DynamicTexture` 直接注册，不触发资源重载 | `RuntimeTextureCache`, `EntityGrayscaleTextures` |
| 沉浸式传送门 | 传送平面、实体/玩家/物理体穿越、表面渲染 | `PortalManager`, `PortalRenderer` |

### 数据与多人

| 能力 | 说明 | 主要入口 |
| --- | --- | --- |
| 结构持久化 | 稳定 UUID、姿态、体素/方块状态、方块实体 NBT | `StructurePersistence`, `StructureSavedData` |
| 多人同步 | 结构快照/姿态/移除，客户端只读缓存 | `StructureSync`, `ClientStructureCache` |
| 调试快照 | 不可变刚体/OBB/AABB/统计，安全跨线程 | `PhysicsDebugSnapshot` |
| `/crythst` 命令 | 状态、暂停、步进、重力、玩家重力、demo/cube、unstuck | `CrythstCommand` |

### 关于“完整惯性张量”

`VoxelColliderBuilder` 会按每个合并盒的质量、尺寸和相对质心位置累加：

- 盒自身的主惯性项；
- 平行轴项；
- 非零的惯性积 `Ixy`、`Ixz`、`Iyz`。

结果保存在 `VoxelColliderData#inertia()` 中。Bullet 侧的刚体使用复合碰撞形状推导其旋转惯量；Crythst 同时把完整张量暴露给需要自定义旋转、受力分析或调试的调用方。

---

## 平台支持

原生库已经打进 mod jar，运行时按操作系统/架构选择并解压到临时目录。

| 平台 | 架构 | 资源路径 |
| --- | --- | --- |
| Windows | x86_64、arm64 | `native/windows/<arch>/bulletjme.dll` |
| macOS | x86_64、arm64 | `native/osx/<arch>/libbulletjme.dylib` |
| Linux | x86_64、arm64 | `native/linux/<arch>/libbulletjme.so` |
| Android（FCL / PojavLauncher 等） | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`、`riscv64` | `native/android/<abi>/libbulletjme.so` |

### 原生库加载顺序

`BulletNatives#load()` 依次尝试：

1. `-Dcrythst.bullet.nativePath=/absolute/path/libbulletjme.so`（显式文件）；
2. 如果原生库已经被其他模组加载，直接复用；
3. 从 jar 内解压到可写目录后用 `System.load(...)` 加载；
4. 如果解压或 `dlopen` 失败，回退到启动器提供的 `java.library.path`（`System.loadLibrary("bulletjme")`）。

### 覆盖属性

| 属性 | 作用 | 示例 |
| --- | --- | --- |
| `-Dcrythst.bullet.nativePath` | 直接指定原生库文件，跳过内置资源 | `-Dcrythst.bullet.nativePath=/data/.../libbulletjme.so` |
| `-Dcrythst.bullet.nativeDir` | 指定解压目录（必须可写） | `-Dcrythst.bullet.nativeDir=/tmp/crythst` |
| `-Dcrythst.android=true` | 强制按 Android 资源路径加载 | `-Dcrythst.android=true` |

### 启动器与调试渲染

- **FCL / PojavLauncher**：这些启动器通常会把原生库放在自己的目录。Crythst 会先尝试解压 jar 内的 Android ABI 版本；如果 Android 的应用私有目录不允许 `dlopen`，再回退到启动器的 `System.loadLibrary`。仍失败时请使用 `nativePath` 或 `nativeDir`。
- **客户端调试渲染**：`crythst:physics*` 调试层目前只在**单人游戏（集成服务器）**中工作。客户端通过 `ClientPhysicsView` 读取同一进程内服务端发布的只读快照，再由 `CrythstPhysicsDebugRenderer` 绘制；条目在 **F3+F6 → Debug Renderers** 中按层启用，默认全部关闭。多人服务器没有本地物理世界，因此这些调试层不会显示（结构本身的渲染、选择和碰撞仍使用网络快照）。详见 [docs/debug-rendering.md](docs/debug-rendering.md)。
- 桌面端 jar 同时带有多套原生库，体积较大；裁剪方式见 [docs/platforms.md](docs/platforms.md)。

更详细的平台选择逻辑、ABI 说明和 `SpRelease` → `DpRelease` 切换方法见 [docs/platforms.md](docs/platforms.md)。

---

## 公开 API（native v2）

第三方模组应优先使用 `io.mikaple.crythst.api`：

| 能力 | 入口 |
| --- | --- |
| 结构/刚体查询 | `CrythstApi.structures`, `container`, `structuresIn`, `structureAt`, `body` |
| 物理操作 | `CrythstApi.physics(ServerLevel)` → `PhysicsPipeline` / `RigidBodyHandle` |
| 结构容器/观察者 | `StructureContainer`, `ServerStructureContainer`, `StructureContainerEvents`, `StructureObserver`, `StructureArgumentType` |
| 方块钩子 | `StructureBlockHooks` + `BlockStructure*` 接口 |
| 实体工具 | `EntityStructureUtil`（containing、velocity、distance、eye interpolation、local push/pop、projectOutOfStructure） |
| 组装/拆解 | `StructureGather`, `StructureAssembly`, `AssemblyOptions`, `DisassembleOptions`, `AssemblyTransform` |
| 方块实体 actor/力 | `BlockEntityStructureActor`, `StructurePhysicsContext`, `ForceGroup`, `QueuedForceGroup` |
| 客户端视图/姿态/选择 | `api/client` 的 render view、pose provider、clip/pick extension、tracking |
| 数学工具 | `StructureMath` |
| 事件 | `CrythstEvents`（固定物理子步 pre/post tick）+ `StructureContainerEvents` |

```java
ServerStructureContainer container = CrythstApi.container(serverLevel);
PhysicsPipeline physics = CrythstApi.physics(serverLevel);
physics.applyImpulseAtPoint(bodyId, worldPoint, new Vector3d(0.0, 800.0, 0.0));
```

完整签名、单位、线程约束、钩子优先级、组装/actor/force 语义和当前缺口见 [docs/api.md](docs/api.md)，常见配方见 [docs/api-cookbook.md](docs/api-cookbook.md)。多人客户端容器返回只读 `ClientStructureSnapshot`，不是 `PhysicsStructure`；组装是世界空间操作，不是 plot/sub-level 组装。公开 API 不提供 `dev.ryanhcode.sable.*` 兼容包；Create Aeronautics 差距分析见 [API_GAP_ANALYSIS.md](API_GAP_ANALYSIS.md)。

## 快速开始

> 所有 `PhysicsWorld` / `BodyHandle` 的调用都必须在**同一个线程**（通常是 Minecraft 服务端线程）上进行。渲染线程只读取不可变快照。

### 1. 创建物理世界并添加刚体

```java
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.physics.PhysicsEngine;
import io.mikaple.crythst.physics.body.BodyDef;
import io.mikaple.crythst.physics.body.BodyHandle;
import io.mikaple.crythst.physics.material.PhysicsMaterial;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.physics.world.PhysicsWorld;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;

PhysicsEngine engine = CrythstApi.engine(); // 进程级单例，不要每个世界重复创建

try (PhysicsWorld world = engine.createWorld(
        WorldSettings.defaults()
                .withFixedTimeStep(1.0 / 60.0)
                .withMaxSubSteps(4)
                .withSolverIterations(10))) {

    BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
            .position(new Vector3d(0.0, 5.0, 0.0))
            .material(PhysicsMaterial.STONE)
            .damping(0.02, 0.05)
            .ccd(true)
            .build());

    // 推进一个固定步长
    world.step(1.0 / 60.0);

    // 或者按真实时间推进：内部会运行 0..maxSubSteps 个固定步，并保留插值 alpha
    // int steps = world.advance(0.016);
}
```

### 2. 构建体素结构

`MutableVoxelVolume` 是纯内存方块网格；`StructureDef` 描述质量和姿态；`PhysicsStructure` 负责生成合并碰撞盒并创建刚体。

```java
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;

// 局部坐标：x/y/z ∈ [0, 3)，最大边界不包含
IntBounds3 bounds = new IntBounds3(0, 0, 0, 3, 3, 3);

MutableVoxelVolume volume = new MutableVoxelVolume(bounds);
volume.setBlock(0, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
volume.setBlock(1, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
// 也可以直接填满：
// MutableVoxelVolume volume = VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.STONE);

StructureDef definition = StructureDef.builder(volume)
        .pose(Transform3d.of(new Vector3d(10.0, 20.0, 10.0)))
        .type(BodyType.DYNAMIC)
        .material(PhysicsMaterial.STONE)
        .colliderOptions(VoxelColliderOptions.DEFAULT.withBlockScale(1.0))
        .massScale(1.0)
        .damping(0.02, 0.05)
        .ccd(true)
        .build();

PhysicsStructure structure = new PhysicsStructure(world, definition);

// 每个服务端 tick 结束后发布一次姿态（不要每个固定 substep 调用）
structure.syncRenderPose();
// 渲染线程按帧时间采样带时间戳的时间线（约一 tick 延迟）
Transform3d renderPose = structure.interpolatedPoseAt(System.nanoTime());
// 或一次性取渲染快照：
// StructureRenderState state = structure.renderStateAt(System.nanoTime());

// 修改方块后重建碰撞体，同时尽量保持世界姿态
// structure.reindex(newVolume);

// 不再使用时：
structure.close();
```

如果结构属于 `LevelPhysics`，请使用 `levelPhysics.createStructure(definition)` 创建、`levelPhysics.removeStructure(structure)` 删除，以便结构列表和调试快照同步更新。

### 3. 任意方向重力与每刚体覆盖

```java
// 让“下”指向 +X：重力向量就是 +X 方向
WorldSettings sideways = WorldSettings.defaults()
        .withGravity(new Vector3d(9.81, 0.0, 0.0));

// 也可以用方向 + 大小构造（静态工厂方法）
WorldSettings marsLike = WorldSettings.withGravity(
        new Vector3d(0.0, -1.0, 0.0), 3.71);

// 运行时修改整个世界
world.setGravity(new Vector3d(0.0, 0.0, -9.81));
Vector3d up = world.up(); // 与重力相反的单位向量；零重力时回退到 +Y

// 单个刚体覆盖世界重力（单位 m/s²，任意方向）
BodyHandle localGravity = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
        .position(new Vector3d(0.0, 20.0, 0.0))
        .gravityOverride(new Vector3d(0.0, 9.81, 0.0))
        .build());

localGravity.setGravityOverride(new Vector3d(0.0, 0.0, -3.0));
localGravity.clearGravityOverride();
```

### 4. 玩家重力方向

默认玩家使用原版向下重力；只有设置了每玩家覆盖时才接管。单人游戏中客户端与服务端共享方向，因此客户端预测也一致。

```java
import io.mikaple.crythst.integration.minecraft.PlayerGravity;

PlayerGravity.setFixed(player, new Vector3d(9.81, 0.0, 0.0)); // “下” = +X
PlayerGravity.setLook(player);                                // “下” = 采样时的视线方向（只采样一次）
PlayerGravity.reset(player);                                  // 恢复原版向下
```

游戏内命令：

```text
/crythst playergravity look
/crythst playergravity 9.81 0 0
/crythst playergravity reset
```

> 方向重力会旋转客户端相机姿态，并在非默认重力下启用 `PlayerObbCollision` 的移动后 OBB 校正；原版 `Entity.move`、跳跃和多数移动逻辑仍以 Y 轴向上预测，完整的 swept OBB 玩家移动仍是后续工作。世界重力通过 `/crythst gravity` 修改时，未单独覆盖的玩家也会跟随。

### 世界地形碰撞

`LevelPhysics` 默认在动力学刚体周围加载 Minecraft 地形碰撞：

- 以 16×16×16 section 为单位，把方块碰撞形状贪心合并成静态 Bullet 刚体；
- 按刚体当前 AABB 加运动预取范围加载，并在刚体进入新 section 时刷新；缓存上限可配置
  （默认 `worldCollisionRadius = 8`、`worldCollisionMaxSections = 64`）；
- 方块编辑会使对应 section 失效并在下一轮重建；
- 静态地形刚体带 `TerrainBody` 标记，调试渲染不会把它们画成普通刚体。

实现细节和当前限制以 [docs/terrain-collision.md](docs/terrain-collision.md) 为准。

### 5. 方块形式快速测试

```text
/crythst demo          # 生成 3x3x3 倾斜方块结构 + 9x1x9 地面
/crythst cube          # 在视线前方生成一个 1m 石块刚体
/crythst clear         # 清空结构与测试刚体
/crythst debug         # 翻转历史 config.debug 字段（F3+F6 调试层需单独启用）
```

默认重力是向下的 `(0,-9.81,0)`；可直接用 `/crythst gravity 0 -9.81 0` 恢复。

### 6. 查询 OBB、射线和扫掠

```java
import io.mikaple.crythst.math.OrientedBox;
import io.mikaple.crythst.physics.query.BodyFilter;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.RayQuery;
import io.mikaple.crythst.physics.query.SweepHit;
import io.mikaple.crythst.physics.query.SweepQuery;
import org.joml.Quaterniond;

// 射线：从 (0,20,0) 向 -Y 打 30 米
List<RayHit> hits = world.raycast(RayQuery.fromDirection(
        new Vector3d(0.0, 20.0, 0.0),
        new Vector3d(0.0, -1.0, 0.0),
        30.0));

if (!hits.isEmpty()) {
    RayHit hit = hits.get(0);       // 默认按 fraction 从近到远排序
    BodyHandle target = hit.body();
    Vector3d point = hit.point();
    Vector3d normal = hit.normal();
    double distance = hit.distance();
}

// 也可以用两个端点构造射线，并排除某个刚体
List<RayHit> filtered = world.raycast(
        RayQuery.between(new Vector3d(0, 20, 0), new Vector3d(0, 0, 0))
                .withFilter(BodyFilter.excluding(localGravity)));

// 扫掠：把一个球从 from 移动到 to
SweepHit first = world.sweep(SweepQuery.between(
        Shapes.sphere(0.5),
        Transform3d.of(new Vector3d(0.0, 20.0, 0.0)),
        Transform3d.of(new Vector3d(0.0, 0.0, 0.0))))
        .stream().findFirst().orElse(null);

// OBB 重叠查询：世界空间中心、半尺寸、旋转
List<BodyHandle> overlapping = world.query(new OrientedBox(
        new Vector3d(0.0, 5.0, 0.0),
        new Vector3d(2.0, 2.0, 2.0),
        new Quaterniond()));

// 每个刚体自己的紧致 OBB
OrientedBox boxBounds = overlapping.isEmpty() ? null : overlapping.get(0).obb();
```

`OrientedBox` 提供 `intersects(Aabb)`、`intersects(OrientedBox)`、`contains`、`toAabb`、`corners`、`closestPoint` 等工具；`PhysicsWorld#query` 使用 15 轴 SAT 做精确的 OBB/OBB 相交。

### 5. Minecraft 服务端集成

```java
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.server.level.ServerLevel;

ServerLevel level = ...; // 服务端线程
LevelPhysics physics = MinecraftPhysics.get(level);

// 读取世界中的方块区域（绝对方块坐标；最大边界不包含）
IntBounds3 bounds = new IntBounds3(x0, y0, z0, x1, y1, z1);
PhysicsStructure structure = physics.createStructure(
        StructureDef.builder(MinecraftPhysics.volume(level, bounds))
                .pose(Transform3d.IDENTITY) // 体素坐标本身就是绝对世界坐标
                .type(BodyType.DYNAMIC)
                .build());
```

`MinecraftPhysics` 默认由 `PhysicsTicker` 在 `ServerTickEvents.END_SERVER_TICK` 中推进；每个维度一个 `LevelPhysics`。

---

## 包布局

| 包 | 职责 | 代表类型 |
| --- | --- | --- |
| `io.mikaple.crythst` | 模组常量与日志 | `Crythst` |
| `io.mikaple.crythst.api` | 库的公共入口 | `CrythstApi` |
| `io.mikaple.crythst.config` | JSON 配置 | `CrythstConfig` |
| `io.mikaple.crythst.math` | 双精度数学类型 | `Transform3d`, `Aabb`, `OrientedBox`, `InterpolatedPose`, `CrythstMath` |
| `io.mikaple.crythst.physics.tick` | 固定步长与渲染姿态时间线 | `FixedStepClock`, `PoseTimeline`, `PhysicsTimingMonitor` |
| `io.mikaple.crythst.physics` | 后端无关的引擎接口与异常 | `PhysicsEngine`, `PhysicsException` |
| `io.mikaple.crythst.physics.world` | 世界、设置、步进统计 | `PhysicsWorld`, `WorldSettings`, `StepStats`, `Broadphase` |
| `io.mikaple.crythst.physics.body` | 刚体定义、句柄、快照 | `BodyDef`, `BodyHandle`, `BodySnapshot`, `BodyType` |
| `io.mikaple.crythst.physics.shape` | 碰撞形状 | `BoxShape`, `SphereShape`, `CapsuleShape`, `CompoundShape`, `Shapes` |
| `io.mikaple.crythst.physics.voxel` | 体素网格、贪心合并、质量/惯性 | `VoxelVolume`, `MutableVoxelVolume`, `VoxelColliderBuilder`, `VoxelColliderData` |
| `io.mikaple.crythst.physics.structure` | 单个移动方块结构 | `StructureDef`, `PhysicsStructure`, `StructureRenderState` |
| `io.mikaple.crythst.physics.constraint` | 约束定义与句柄 | `FixedConstraintDef`, `HingeConstraintDef`, `SliderConstraintDef`, `PointConstraintDef` |
| `io.mikaple.crythst.physics.character` | 运动学角色控制器 | `CharacterDef`, `CharacterController` |
| `io.mikaple.crythst.physics.query` | 射线、扫掠、OBB 查询 | `RayQuery`, `RayHit`, `SweepQuery`, `SweepHit`, `BodyFilter` |
| `io.mikaple.crythst.physics.collision` | 碰撞过滤和接触事件 | `CollisionFilter`, `ContactEvent`, `ContactListener`, `ContactPoint` |
| `io.mikaple.crythst.physics.material` | 摩擦、弹性、密度 | `PhysicsMaterial` |
| `io.mikaple.crythst.physics.debug` | 调试快照 | `PhysicsDebugSnapshot`, `BodyDebugInfo` |
| `io.mikaple.crythst.physics.bullet` | libbulletjme 后端实现 | `BulletPhysicsEngine`, `BulletPhysicsWorld`, `BulletBodyHandle`, `BulletNatives` |
| `io.mikaple.crythst.integration.minecraft` | Minecraft 服务端集成 | `MinecraftPhysics`, `LevelPhysics`, `PhysicsTicker`, `BlockVoxelVolume` |
| `io.mikaple.crythst.integration.fabric` | Fabric 入口、命令、客户端调试 | `CrythstFabric`, `CrythstCommand`, `CrythstClient`, `CrythstPhysicsDebugRenderer` |
| `io.mikaple.crythst.client.render.item` | 物品 special model / star 着色器 | `StarItemModelRenderer`, `CrythstItemSpecialRenderers` |
| `io.mikaple.crythst.client.render.block` | 无 BER submit 的方块特殊渲染 | `SpecialBlockRenderManager`, `ItemModelBlockRenderer` |
| `io.mikaple.crythst.integration.minecraft.mass` | 数据驱动方块质量/摩擦/浮力 | `BlockPhysicsRegistry`, `BlockMassReload` |
| `io.mikaple.crythst.integration.minecraft.player` | 玩家 OBB 碰撞与重力姿态 | `PlayerObbCollision`, `PlayerObb` |
| `io.mikaple.crythst.integration.minecraft.entity` | 实体碰撞与结构搭乘 | `EntityStructureCollision`, `EntityStructureRidingManager` |
| `io.mikaple.crythst.integration.minecraft.selection` | 结构选择、射线与命中结果 | `StructureSelection`, `StructureRaycaster`, `StructureBlockHitResult` |
| `io.mikaple.crythst.integration.minecraft.interaction` | 结构方块交互/反作用力/拾取 | `StructureInteraction`, `StructureActionPayload`, `StructureFeedback`, `StructureItemUse` |
| `io.mikaple.crythst.integration.minecraft.entity.placement` | 结构表面实体放置（刷怪蛋/展示框/模组实体物品） | `StructureEntityPlacement`, `StructureEntityPlacementHandler`, `StructureEntityPlacementMath` |
| `io.mikaple.crythst.integration.minecraft.buoyancy` | 流体浮力 | `BuoyancyManager`, `BuoyancyMath` |
| `io.mikaple.crythst.integration.minecraft.storage` | 结构/方块实体持久化 | `StructurePersistence`, `StructureSavedData` |
| `io.mikaple.crythst.integration.portal` | 传送门与传送变换 | `PortalManager`, `PortalTeleporter` |
| `io.mikaple.crythst.integration.create` | Create-Fly 软兼容 | `CreateCompat`, `CreateStructureBridge` |
| `io.mikaple.crythst.network.structure` | 多人结构同步协议 | `StructureSyncProtocol`, `StructureSnapshotPayload` |
| `io.mikaple.crythst.client.network` | 客户端结构快照缓存 | `ClientStructureCache` |
| `io.mikaple.crythst.client.render.structure` | 真实/批量结构渲染 | `BatchedStructureRenderer`, `RealStructureBlockRenderer` |
| `io.mikaple.crythst.client.render.portal` | 传送门表面渲染 | `PortalRenderer` |
| `io.mikaple.crythst.client.texture` | 灰度图单/双色着色与外轮廓 | `GrayscaleColorizer`, `GrayscaleFormula` |
| `io.mikaple.crythst.client.texture.runtime` | 运行时实体纹理（无资源重载） | `RuntimeTextureCache`, `EntityGrayscaleTextures` |
| `io.mikaple.crythst.util` | 参数校验与计时代码 | `Validate`, `StepProfiler` |

更详细的模块依赖方向、线程模型、固定步长、原点重定位、贪心合并、SAT、结构生命周期和调试渲染路径见 [docs/architecture.md](docs/architecture.md)。
常用任务的完整配方见 [docs/api-cookbook.md](docs/api-cookbook.md)。

特殊渲染与纹理：

- [docs/item-special-rendering.md](docs/item-special-rendering.md)
- [docs/block-special-rendering.md](docs/block-special-rendering.md)
- [docs/grayscale-colorization.md](docs/grayscale-colorization.md)

---

## 构建

要求 **JDK 25**（`gradle.properties` 中的 `java_version=25`）。Gradle 会通过 Java toolchain 检查版本。

```bash
# 完整构建（编译 + 测试 + jar）
./gradlew build

# 只运行测试
./gradlew test

# 启动开发客户端（单人）
./gradlew runClient

# 启动开发服务端
./gradlew runServer
```

构建产物：

- `build/libs/crythst-26.3-0.1.0.jar`：包含模组类、`fabric.mod.json`、许可证文件和全部平台原生库；
- `build/libs/crythst-26.3-0.1.0-sources.jar`：源码 jar（不包含原生库）。

原生库由 `collectNativeLibraries` 任务从 libbulletjme 的各平台 artifact 解压到 `build/generated/native-resources`，再作为资源打进主 jar。`sourcesJar` 明确排除了 `native/**`。

---

## 命令

`/crythst` 需要游戏管理员权限（`Commands.LEVEL_GAMEMASTERS`）。命令作用于执行者所在维度的 `LevelPhysics`。

| 命令 | 作用 |
| --- | --- |
| `/crythst status` | 显示后端版本、刚体/约束数量、唤醒刚体数、接触数、步进耗时、重力、`up`、当前 origin |
| `/crythst bodies` | 列出前 20 个刚体的 ID、类型、位置、质量和唤醒状态 |
| `/crythst pause` | 暂停该维度的物理推进 |
| `/crythst resume` | 恢复推进 |
| `/crythst step` | 手动运行 1 个固定步 |
| `/crythst step <count>` | 手动运行 `count` 个固定步（1–600） |
| `/crythst gravity <x> <y> <z>` | 设置该维度世界重力向量 |
| `/crythst demo` | 在玩家附近生成一个 9×1×9 静态地面和一个带倾斜/旋转的 3×3×3 动态结构 |
| `/crythst cube` | 在视线前方生成一个 1m 石块刚体，用于快速方块形式测试 |
| `/crythst clear` | 删除该维度所有 Crythst 结构与测试刚体 |
| `/crythst playergravity look` | 玩家重力方向改为视线方向（看向哪里就向哪里下落） |
| `/crythst playergravity <x> <y> <z>` | 玩家重力固定为指定方向 |
| `/crythst playergravity reset` | 恢复原版向下重力 |
| `/crythst unstuck` | 立即尝试把卡在方块里的物理体/玩家推出来 |
| `/crythst timing` | 固定步长、步数/抖动、姿态诊断和渲染帧姿态诊断 |
| `/crythst align` | 检查 body/structure/COM 与碰撞、渲染坐标约定 |
| `/crythst entitydebug` | 打印附近实体的结构碰撞诊断 |
| `/crythst debug` | 翻转历史 `config.debug` 字段；F3+F6 的 `crythst:physics*` 调试层由各自条目控制 |
| `/crythstmine` / `/crythstplace` | 对物理结构体素做服务端挖掘/放置诊断 |
| `/crythst portal create/link/remove/list` | 创建、连接、删除和列出传送门 |

单人集成服务器下渲染、选择、碰撞和调试层直接读取服务端发布的快照；多人环境通过 `StructureSync` → `ClientStructureCache` 同步结构快照/姿态，渲染、选择与实体碰撞读取客户端缓存。调试层 `crythst:physics*` 仍只在单人（有本地 `ClientPhysicsView`）时绘制。

---

## 许可与来源声明

Crythst 本身以 **MIT License** 发布，版权归 mikaple 所有。完整文本见 [LICENSE](LICENSE)。  
本声明是工程来源说明，**不构成法律意见**；正式发布前应进行独立的许可证复核。

### 来源声明

Crythst 是独立实现，不是 Sable 或 sable-port 的 fork，也不包含它们的源代码、注释或资源。设计问题空间受到 Sable/sable-port 启发，少量公共 API 名称、包结构和术语有意对齐；详细边界与证据见上文 [与 Sable / sable-port 的关系](#与-sable--sable-port-的关系) 和 `docs/license-audit-2026-11-22.md`。Sable 与 sable-port 均使用 PolyForm Shield License 1.0.0；若未来引入其代码，必须按其条款处理，不能继续仅以 MIT 发布。

来源审计是工程说明，不是法律意见；正式发布前应进行独立的许可证复核。

### 第三方组件

- **libbulletjme 23.2.0**：BSD 3-Clause（Java/JNI 胶水层）；Bullet Physics 为 zlib 许可，V-HACD 为其 BSD 风格许可；
- **Fabric Loader / Fabric API / Fabric Loom**：Apache License 2.0；
- **Minecraft**：Mojang/Microsoft 版权，仅作为编译/运行依赖，不随本库分发；
- **JOML**：MIT，由 Minecraft 在运行时提供；
- **EnderTech**：`io.mikaple.crythst.client.render` 下部分 special rendering 改编自 EnderTech，并由同一版权人重新以 MIT 授权；原 EnderTech 项目对第三方仍为 All-Rights-Reserved；
- **Create-Fly**：仅作为 `compileOnly` 兼容性依赖，不进入运行时 jar；其代码与许可证不随 Crythst 分发。

详见 [NOTICE](NOTICE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 状态与路线图

当前 `0.1.0` 已经具备：

- 刚体、体素结构、贪心碰撞盒、完整 3×3 惯性张量；
- 数据驱动方块质量、摩擦、弹性、浮力和末地石低密度；
- 任意方向重力、玩家相机姿态、非默认重力下的玩家 OBB 后置碰撞校正；
- 每维度物理世界、辅助 void level 方块逻辑、按 section 缓存且随编辑失效的地形碰撞；
- 集成进 `Entity.move` 的实体单向结构碰撞、实体停留在移动结构上、Create-Fly 软兼容；
- 结构选择共用原版 `clip`、原版轮廓、挖掘/放置/中键拾取、挖掘反作用力、铲水反作用力；
- 结构按 26 邻接自动分裂，零质量体自动移除；
- 真实方块模型渲染 + 分 section/渲染层批量缓存；
- 物品 star special model、无 BER submit 的方块特殊渲染；
- 灰度图多公式、外轮廓、原版钻石素材、运行时实体纹理（无资源重载）；
- F3+F6 分层的 `crythst:physics*` 调试渲染（默认关闭）和 `crythst:structure_target` 屏幕文本；
- native API v2：结构容器/观察者/命令参数、客户端 render view/pose provider/clip/pick/tracking、世界空间组装/拆解、方块实体 actor + force group、`StructureMath`；
- 结构持久化（含方块实体 NBT）、多人结构同步；
- 沉浸式传送门 v1（传送、玩家/实体/物理体、动画表面）。

下一步：

- 传送门“看到另一侧”的实时视口、递归/模板缓冲；
- 移动传送门、跨维度传送、传送门持久化与同步；
- 结构方块实体客户端渲染与更完整的 BE tick；
- native API 后续：constraints、ropes、box objects、loading tickets、tracking points、force totals/debug UI；
- 真正的 swept OBB 玩家移动求解（`PlayerObbCollision` 当前仍是移动后校正）；
- 更多 Create 渲染/交互兼容和性能剖析。

完整拆解与验收标准见 [TODO.md](TODO.md)。

## 文档索引

**架构与玩法**

- [架构说明](docs/architecture.md)
- [结构选择与瞄准](docs/selection-targeting.md)
- [结构方块轮廓](docs/structure-outline.md)
- [玩家 ↔ 结构交互](docs/player-structure-interaction.md)
- [实体 ↔ 结构碰撞](docs/entity-physics-collision.md)
- [实体停留在结构上](docs/entity-structure-riding.md)
- [辅助空世界实体投影](docs/structure-entity-projection.md)
- [辅助空世界方块逻辑](docs/structure-world-logic.md)
- [结构分裂](docs/structure-splitting.md)
- [玩家 OBB 碰撞（非默认重力）](docs/player-obb-collision.md)
- [玩家重力与相机](docs/player-gravity-camera.md)
- [物理计时与渲染插值](docs/physics-timing.md)
- [地形碰撞](docs/terrain-collision.md)
- [浮力](docs/buoyancy.md)
- [方块质量](docs/block-mass.md)
- [沉浸式传送门](docs/immersive-portal.md)
- [Create 兼容](docs/create-compatibility.md)

**渲染、调试与资源**

- [调试渲染（F3+F6）](docs/debug-rendering.md)
- [真实结构渲染（逐方块）](docs/real-structure-rendering.md)
- [批量结构渲染](docs/batched-structure-rendering.md)
- [方块特殊渲染](docs/block-special-rendering.md)
- [物品特殊渲染](docs/item-special-rendering.md)
- [灰度图着色](docs/grayscale-colorization.md)
- [灰度图公式](docs/grayscale-formulas.md)
- [灰度素材](docs/grayscale-assets.md)
- [运行时实体纹理](docs/runtime-entity-textures.md)

**数据、网络与平台**

- [结构持久化](docs/structure-persistence.md)
- [多人同步](docs/multiplayer-sync.md)
- [平台与原生库](docs/platforms.md)
- [公开 API（native v2）](docs/api.md)
- [API 配方](docs/api-cookbook.md)
- [Create Aeronautics API 差距分析](API_GAP_ANALYSIS.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [设计声明](NOTICE)
