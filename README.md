# Crythst

**Crythst** 是一个独立的、MIT 许可的 Fabric 物理与仿真库，面向 **Minecraft / Fabric 26.3**。
它不是一个内容模组，而是给其他模组提供刚体、体素结构、角色控制器、空间查询和调试能力的底层库。

- 模组 ID：`crythst`
- Java 包名：`io.mikaple.crythst`
- 当前版本：`0.1.0`
- 物理后端：**libbulletjme 23.2.0**（JNI / Bullet Physics）
- 运行环境：Java 25、Fabric Loader 0.19.5+、Fabric API 0.161.0+26.3

> 本仓库只包含库本身。功能模组可以把 Crythst 作为依赖，在其上实现“会移动/会倒塌的方块结构”、载具、机械、角色物理等玩法。

---

## 目录

- [特性](#特性)
- [平台支持](#平台支持)
- [快速开始](#快速开始)
- [包布局](#包布局)
- [构建](#构建)
- [命令](#命令)
- [许可](#许可)
- [状态与路线图](#状态与路线图)
- [TODO](TODO.md)

---

## 特性

| 能力 | 说明 | 主要入口 |
| --- | --- | --- |
| 刚体物理 | 静态 / 运动学 / 动态刚体，质量、阻尼、CCD、碰撞过滤、休眠控制 | `BodyDef`, `BodyHandle` |
| 体素结构 | 把方块网格变成可旋转、可翻倒的复合刚体 | `VoxelVolume`, `StructureDef`, `PhysicsStructure` |
| 贪心合并碰撞盒 | 将相邻同材质方块合并为尽量少的长方体，保持轴对齐碰撞表面 | `VoxelColliderBuilder` |
| 完整 3×3 惯性张量 | 计算质心、转动惯量、惯性积和平行轴项，非对称结构也能正确翻滚 | `VoxelColliderData#inertia()` |
| 任意方向重力 | 重力是普通三维向量，不假设“下 = -Y”；支持每刚体覆盖，默认仍为向下 `(0,-9.81,0)` | `WorldSettings#gravity()`, `BodyDef#gravityOverride()` |
| 玩家重力方向 | 通过 Mixin 替换玩家空中重力的方向；支持固定方向和“看向哪里就向哪里下落” | `PlayerGravity`, `/crythst playergravity` |
| OBB 与 15 轴 SAT | 每个刚体都有带旋转的包围盒，用分离轴测试判断倾斜盒相交 | `BodyHandle#obb()`, `OrientedBox` |
| 射线与扫掠 | 世界空间射线检测、凸形状扫掠检测，支持过滤器 | `RayQuery`, `SweepQuery` |
| 约束 | 固定、铰链、滑轨、点对点约束；可锚定到世界 | `FixedConstraintDef`, `HingeConstraintDef`, `SliderConstraintDef`, `PointConstraintDef` |
| 角色控制器 | 基于世界 `up()` 的运动学胶囊角色，支持任意重力方向 | `CharacterDef`, `CharacterController` |
| 固定步长 + 插值 | 物理固定步长运行，渲染使用剩余时间做位置/旋转插值 | `FixedStepClock`, `InterpolatedPose` |
| 可重定位双精度原点 | 对外使用世界空间 `double`，内部 Bullet 使用可移动的局部原点 | `PhysicsWorld#rebaseOrigin()` |
| 世界地形碰撞 | 在动力学刚体周围按 16³ 区块段生成静态合并碰撞体；默认开启 | `TerrainCollision`, `TerrainBody` |
| 真实空 Level 方块逻辑 | 每个物理世界懒创建一个真实空 `ServerLevel`，复制结构方块，只 tick 含方块的 chunk | `EmptyPhysicsLevel`, `LevelPhysics` |
| 物品特殊渲染 | 灰度/纯黑掩码 + 自定义着色器（star）的特殊模型烘焙与 RenderType | `StarItemModelRenderer`, `CrythstItemSpecialRenderers` |
| 方块特殊渲染 | 不走 `BlockEntityRenderer#submit`，在 level 渲染事件里按方块位置提交模型 | `SpecialBlockRenderManager`, `ItemModelBlockRenderer` |
| 灰度图着色 | 公式化灰度 → 单色/双色（外轮廓）PNG 像素着色工具 | `GrayscaleColorizer` |
| 调试快照 | 不可变的刚体/OBB/AABB/统计/接触点快照，可安全交给调试渲染或命令 | `PhysicsDebugSnapshot`, `BodyDebugInfo` |
| 每维度一个物理世界 | 服务端为每个 `ServerLevel` 维护独立的 `PhysicsWorld` | `MinecraftPhysics`, `LevelPhysics` |
| `/crythst` 命令 | 查看状态、暂停/继续、手动步进、修改重力、生成演示结构 | `CrythstCommand` |

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
- **客户端调试渲染**：目前只在**单人游戏（集成服务器）**中工作。客户端通过 `ClientPhysicsView` 读取同一进程内服务端发布的只读快照，再由 `PhysicsGizmoRenderer` 在 `LevelRenderEvents.BEFORE_GIZMOS` 中绘制 OBB/AABB、质心、接触点/法线以及重力/上方向。多人服务器没有本地物理世界，因此不会显示。
- 桌面端 jar 同时带有多套原生库，体积较大；裁剪方式见 [docs/platforms.md](docs/platforms.md)。

更详细的平台选择逻辑、ABI 说明和 `SpRelease` → `DpRelease` 切换方法见 [docs/platforms.md](docs/platforms.md)。

---

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

// 每步模拟后把结构姿态送入渲染插值缓冲
structure.syncRenderPose();
Transform3d renderPose = structure.interpolatedPose(world.interpolationAlpha());

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
PlayerGravity.setLook(player);                                // “下” = 玩家视线方向
PlayerGravity.reset(player);                                  // 恢复原版向下
```

游戏内命令：

```text
/crythst playergravity look
/crythst playergravity 9.81 0 0
/crythst playergravity reset
```

> 当前只改重力加速度方向；玩家的碰撞、跳跃和相机仍按原版 Y 轴向上处理，完整 6DoF 玩家移动/相机侧倾是后续工作。世界重力通过 `/crythst gravity` 修改时，未单独覆盖的玩家也会跟随。

### 世界地形碰撞

`LevelPhysics` 默认在动力学刚体周围加载 Minecraft 地形碰撞：

- 以 16×16×16 区块段为单位，把带碰撞形状的方块贪心合并成静态 Bullet 刚体；
- 只在刚体 AABB 周围（默认半径 16m）加载，缓存上限 96 段；
- 静态地形刚体带 `TerrainBody` 标记，客户端调试渲染不会把每一段画成普通刚体；
- 这是第一版：方块编辑不会自动使缓存失效，`/crythst clear` 或 `setBlockPhysics(...)` 会清空缓存；楼梯/台阶按整格近似。

### 5. 方块形式快速测试

```text
/crythst demo          # 生成 3x3x3 倾斜方块结构 + 9x1x9 地面
/crythst cube          # 在视线前方生成一个 1m 石块刚体
/crythst clear         # 清空结构与测试刚体
/crythst debug         # 开关客户端 OBB/接触点/重力可视化
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
| `io.mikaple.crythst.physics.tick` | 固定步长累加器 | `FixedStepClock` |
| `io.mikaple.crythst.physics.debug` | 调试快照 | `PhysicsDebugSnapshot`, `BodyDebugInfo` |
| `io.mikaple.crythst.physics.bullet` | libbulletjme 后端实现 | `BulletPhysicsEngine`, `BulletPhysicsWorld`, `BulletBodyHandle`, `BulletNatives` |
| `io.mikaple.crythst.integration.minecraft` | Minecraft 服务端集成 | `MinecraftPhysics`, `LevelPhysics`, `PhysicsTicker`, `BlockVoxelVolume` |
| `io.mikaple.crythst.integration.fabric` | Fabric 入口、命令、客户端调试 | `CrythstFabric`, `CrythstCommand`, `CrythstClient`, `PhysicsGizmoRenderer` |
| `io.mikaple.crythst.client.render.item` | 物品 special model / star 着色器 | `StarItemModelRenderer`, `CrythstItemSpecialRenderers` |
| `io.mikaple.crythst.client.render.block` | 无 BER submit 的方块特殊渲染 | `SpecialBlockRenderManager`, `ItemModelBlockRenderer` |
| `io.mikaple.crythst.client.texture` | 灰度图单/双色着色与外轮廓 | `GrayscaleColorizer` |
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
| `/crythst debug` | 切换调试叠层开关（运行时修改 `config.debug`，不写回配置文件） |

调试叠层只在单人集成服务器中可用；多人环境下客户端没有本地物理世界可供读取。

---

## 许可

Crythst 本身使用 **MIT License**，版权归 mikaple 所有。完整文本见 [LICENSE](LICENSE)。

设计说明：

- 本项目的物理层在设计上受到 Sable 所探索的问题空间启发（用体素碰撞形状表示可移动方块结构的刚体仿真）；
- **没有复制 Sable 或任何 Sable 移植版本的源代码、类布局、注释文本或资源文件**；
- 贪心体素合并、复合碰撞体、固定步长积分、插值渲染姿态等属于通用技术/公有领域思想，均从零实现。

第三方组件：

- **libbulletjme 23.2.0**：BSD 3-Clause（Java/JNI 胶水层），Bullet Physics 为 zlib 许可，V-HACD 为其 BSD 风格许可；
- **Fabric Loader / Fabric API / Fabric Loom**：Apache License 2.0；
- **Minecraft**：Mojang/Microsoft 版权，仅作为编译/运行依赖，不随本库分发；
- **JOML**：MIT，由 Minecraft 在运行时提供。

详见 [NOTICE](NOTICE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 状态与路线图

当前 `0.1.0` 已经具备：

- 刚体、体素结构、合并碰撞盒、完整惯性张量；
- 任意方向重力与每刚体覆盖；
- OBB/15 轴 SAT、射线、扫掠、约束、角色控制器；
- 固定步长 + 插值、可重定位原点、调试快照；
- 每维度物理世界、`/crythst` 命令；
- 单人集成服务器下的客户端 gizmo 调试渲染。

下一步重点：

1. **客户端方块模型渲染**：用真实的 Minecraft 方块模型/区块渲染移动结构，而不只是调试线框；
2. **多人同步**：把结构姿态、方块变更和物理事件同步到客户端/其他玩家；
3. 更完整的结构编辑、持久化和性能优化。

---

## 文档索引

- [架构说明](docs/architecture.md)
- [平台与原生库](docs/platforms.md)
- [API 配方](docs/api-cookbook.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [设计声明](NOTICE)
