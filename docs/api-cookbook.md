# Crythst API 配方

本文给出常见任务的完整调用方式，覆盖 native v2 API（容器、客户端视图、组装、actor/force 等）。所有类名、方法名和参数顺序都以仓库源码为准。

> **线程规则**：`PhysicsWorld`、`BodyHandle`、`PhysicsStructure`、约束、角色控制器都只能在**服务端线程**使用。渲染线程只读取 `BodySnapshot`、`StructureRenderState`、`PhysicsDebugSnapshot` 等不可变快照。

---

## 0. 最小可用世界

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

PhysicsEngine engine = CrythstApi.engine(); // 进程级单例

try (PhysicsWorld world = engine.createWorld(WorldSettings.defaults())) {
    BodyHandle box = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
            .position(new Vector3d(0.0, 5.0, 0.0))
            .material(PhysicsMaterial.STONE)
            .build());

    // 确定性单步
    world.step(1.0 / 60.0);

    // 或按真实时间推进（内部运行 0..maxSubSteps 个固定步）
    // int steps = world.advance(0.016);
}
```

`PhysicsWorld` 继承 `AutoCloseable`，推荐用 try-with-resources。`CrythstApi.engine()` 返回共享引擎，**不要**为每个世界重复创建或关闭引擎。

---

## 1. 世界设置

```java
import io.mikaple.crythst.physics.world.Broadphase;
import io.mikaple.crythst.physics.world.WorldSettings;
import org.joml.Vector3d;

WorldSettings settings = WorldSettings.defaults(new Vector3d(0.0, 64.0, 0.0))
        .withFixedTimeStep(1.0 / 60.0)
        .withMaxSubSteps(4)
        .withSolverIterations(10)
        .withBroadphase(Broadphase.DBVT)
        .withSleep(true)
        .withDeterministic(true)
        .withGravity(new Vector3d(0.0, -20.0, 0.0))
        .withOrigin(new Vector3d(0.0, 64.0, 0.0));

PhysicsWorld world = CrythstApi.engine().createWorld(settings);
```

### 字段/方法对照

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `gravity` | `(0, -9.81, 0)` | 任意方向的重力加速度 |
| `fixedTimeStep` | `1/60` | 每个物理步的秒数 |
| `maxSubSteps` | `4` | 单次 `advance` 最多追赶的固定步数 |
| `solverIterations` | `10` | Bullet 求解器迭代次数 |
| `broadphase` | `DBVT` | `DBVT` / `AXIS_SWEEP_3` / `SIMPLE` |
| `origin` | 由 `defaults(origin)` 指定 | 初始物理原点 |
| `allowSleep` | `true` | 是否允许刚体休眠 |
| `deterministic` | `true` | 确定性调度并强制 AABB 更新 |
| `contactTolerance` | `0.04` | 世界设置中的接触容差字段；目前 `SweepQuery` 使用自己的 `tolerance` |

静态工厂方法：

```java
WorldSettings a = WorldSettings.withGravity(new Vector3d(0.0, -1.0, 0.0), 9.81);
WorldSettings b = WorldSettings.defaults(); // 原点 (0,0,0)
```

> `WorldSettings` 是不可变 record；`withXxx` 返回新对象。已创建的 `PhysicsWorld` 不会因为外部 `settings` 变量变化而改变，除非调用 `world.setGravity(...)` 或 `world.rebaseOrigin(...)`。

---

## 2. 刚体

### 2.1 形状

```java
import io.mikaple.crythst.physics.shape.Axis;
import io.mikaple.crythst.physics.shape.BoxShape;
import io.mikaple.crythst.physics.shape.CompoundShape;
import io.mikaple.crythst.physics.shape.ConvexHullShape;
import io.mikaple.crythst.physics.shape.PlaneShape;
import io.mikaple.crythst.physics.shape.Shapes;
import io.mikaple.crythst.math.Transform3d;
import org.joml.Vector3d;

Shapes.cube(1.0);                 // 1×1×1 盒
Shapes.box(2.0, 1.0, 1.0);        // 2×1×1 盒
Shapes.sphere(0.5);               // 半径 0.5
Shapes.capsule(0.3, 1.8);         // 半径 0.3，总高 1.8（Y 轴）
Shapes.cylinder(0.5, 1.5, Axis.Y); // 半径 0.5，高 1.5
Shapes.plane(new Vector3d(0.0, 1.0, 0.0), 0.0);

// 显式构造
new BoxShape(new Vector3d(1.0, 0.5, 0.5));
new PlaneShape(new Vector3d(0.0, 1.0, 0.0), 0.0);
new ConvexHullShape(List.of(
        new Vector3d(-0.5, 0.0, -0.5),
        new Vector3d(0.5, 0.0, -0.5),
        new Vector3d(0.0, 1.0, -0.5),
        new Vector3d(0.0, 0.0, 0.5)));

// 复合形状：子形状 + 本地变换
CompoundShape compound = CompoundShape.builder()
        .add(Shapes.box(1.0, 0.5, 1.0), Transform3d.of(new Vector3d(0.0, 0.0, 0.0)))
        .add(Shapes.sphere(0.3), Transform3d.of(new Vector3d(0.0, 0.75, 0.0)))
        .build();
```

体积为 0 的形状（`PlaneShape`、`ConvexHullShape`）如果要做动态刚体，必须显式给质量。

### 2.2 创建刚体

```java
// 动态
BodyHandle dynamic = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
        .position(new Vector3d(0.0, 10.0, 0.0))
        .mass(500.0)                       // 不写则用体积 × 密度
        .material(PhysicsMaterial.WOOD)
        .linearVelocity(new Vector3d(1.0, 0.0, 0.0))
        .angularVelocity(new Vector3d(0.0, 0.5, 0.0))
        .gravityScale(1.0)
        .damping(0.02, 0.05)
        .ccd(true)
        .ccdRadius(0.2)
        .allowSleep(true)
        .linearFactor(new Vector3d(1.0, 1.0, 1.0))
        .angularFactor(new Vector3d(1.0, 1.0, 1.0))
        .filter(CollisionFilter.ofGroup(2))
        .userData("crate")
        .build());

// 静态
BodyHandle floor = world.createBody(BodyDef.staticBody(PlaneShape.horizontal(0.0)).build());

// 运动学（由代码移动，推动动态刚体）
BodyHandle platform = world.createBody(BodyDef.kinematic(Shapes.box(3.0, 0.5, 3.0))
        .position(new Vector3d(0.0, 2.0, 0.0))
        .build());

// 通用 builder
BodyHandle custom = world.createBody(BodyDef.builder(Shapes.sphere(0.5), BodyType.DYNAMIC)
        .position(new Vector3d(0.0, 15.0, 0.0))
        .mass(10.0)
        .build());
```

### 2.3 运行时操作

```java
dynamic.setLinearVelocity(new Vector3d(0.0, 5.0, 0.0));
dynamic.setAngularVelocity(new Vector3d(0.0, 1.0, 0.0));
dynamic.applyCentralImpulse(new Vector3d(0.0, 20.0, 0.0));
dynamic.applyImpulseAtPoint(new Vector3d(0.0, 0.0, 10.0), new Vector3d(0.5, 1.0, 0.0));
dynamic.applyCentralForce(new Vector3d(0.0, 0.0, 5.0));
dynamic.applyTorque(new Vector3d(0.0, 2.0, 0.0));
dynamic.applyTorqueImpulse(new Vector3d(0.0, 0.5, 0.0));
dynamic.clearForces();

dynamic.setGravityOverride(new Vector3d(0.0, 0.0, 9.81));
dynamic.setGravityScale(0.5);
dynamic.clearGravityOverride();

dynamic.setDamping(0.1, 0.2);
dynamic.setCcd(true, 0.25);
dynamic.setLinearFactor(new Vector3d(1.0, 0.0, 1.0)); // 锁定 Y 平移
dynamic.setAngularFactor(new Vector3d(0.0, 1.0, 0.0)); // 只允许绕 Y 旋转
dynamic.setSleepingThresholds(0.05, 0.05);
dynamic.activate();
dynamic.setUserData("updated");
dynamic.teleport(Transform3d.of(new Vector3d(0.0, 20.0, 0.0)));
```

### 2.4 读取状态

```java
Transform3d pose = dynamic.transform();
Aabb aabb = dynamic.aabb();
OrientedBox obb = dynamic.obb();
double mass = dynamic.mass();
Vector3d linear = dynamic.linearVelocity();
Vector3d angular = dynamic.angularVelocity();
BodySnapshot snapshot = dynamic.snapshot();
boolean active = dynamic.active();
boolean inWorld = dynamic.inWorld();
```

`BodySnapshot` 是不可变 record，可安全交给网络/渲染/命令代码。

### 2.5 材质与碰撞过滤

```java
PhysicsMaterial customMaterial = new PhysicsMaterial(
        0.7,   // friction
        0.1,   // restitution
        0.02,  // rollingFriction
        0.02,  // spinningFriction
        1200.0 // density，kg/m³
);

CollisionFilter layer2 = CollisionFilter.layer(2, 0xFFFF);
CollisionFilter onlyLayer2 = CollisionFilter.layer(2, 1 << 2);
```

内置材质：`DEFAULT`、`STONE`、`DIRT`、`WOOD`、`METAL`、`ICE`、`RUBBER`。

---

## 3. 体素结构与贪心碰撞盒

### 3.1 手工构建 `MutableVoxelVolume`

```java
import io.mikaple.crythst.physics.voxel.IntBounds3;
import io.mikaple.crythst.physics.voxel.MutableVoxelVolume;
import io.mikaple.crythst.physics.voxel.VoxelVolumes;

// 注意：max 是开区间
IntBounds3 bounds = new IntBounds3(0, 0, 0, 4, 3, 2);
MutableVoxelVolume volume = new MutableVoxelVolume(bounds);

volume.setBlock(0, 0, 0, true, 1000.0, PhysicsMaterial.STONE);
volume.setSolid(1, 0, 0, true); // 默认质量 1000
volume.setSolid(2, 0, 0, false);

// 或者整块填满
MutableVoxelVolume filled = VoxelVolumes.filled(bounds, 1000.0, PhysicsMaterial.WOOD);

// 清空所有实心标记
volume.clear();
```

`MutableVoxelVolume#blockMass` 在质量小于等于 0 时返回 `1000.0`，避免出现零质量体素。

### 3.2 创建 `StructureDef` 和 `PhysicsStructure`

```java
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.VoxelColliderOptions;
import org.joml.Quaterniond;
import org.joml.Vector3d;

StructureDef definition = StructureDef.builder(volume)
        .pose(Transform3d.of(
                new Vector3d(10.0, 20.0, 10.0),
                new Quaterniond().rotateY(Math.toRadians(25.0))))
        .type(BodyType.DYNAMIC)
        .material(PhysicsMaterial.STONE)
        .filter(CollisionFilter.DEFAULT)
        .colliderOptions(VoxelColliderOptions.DEFAULT
                .withBlockScale(1.0)
                .withGreedyMerge(true)
                .withMergedMaterials(false))
        .massScale(1.0)
        .damping(0.02, 0.05)
        .ccd(true)
        .userData("my-structure")
        .build();

PhysicsStructure structure = new PhysicsStructure(world, definition);
```

### 3.3 读取碰撞与质量数据

```java
VoxelColliderData collider = structure.collider();

List<VoxelBox> boxes = collider.boxes();          // 合并后的盒子（体素坐标）
double totalMass = collider.mass();               // kg
Vector3d centerOfMass = collider.centerOfMass();  // 体素坐标
Matrix3d inertia = collider.inertia();            // kg·m²，关于质心
Vector3d size = collider.size();                  // 体素坐标下的整体尺寸
double blockScale = collider.blockScale();        // 米/体素

// 世界空间查询
Vector3d blockCenter = structure.blockCenter(1, 0, 0);
Vector3d voxel = structure.worldToVoxel(new Vector3d(), blockCenter);
Vector3d pointVelocity = structure.velocityAt(blockCenter);
OrientedBox obb = structure.obb();
Aabb aabb = structure.aabb();
```

### 3.4 编辑方块后重建

```java
volume.setSolid(0, 0, 0, false);
volume.setSolid(1, 1, 1, true);
structure.reindex(volume);
```

`reindex` 会重新生成合并盒、质心、质量，并保持当前结构世界姿态。新体积必须至少有一个实心方块，否则抛 `IllegalArgumentException`。

### 3.5 移动、传送、类型和材质

```java
// 设置姿态（保留速度，唤醒刚体，推入渲染缓冲）
structure.setPose(Transform3d.of(new Vector3d(20.0, 30.0, 20.0)));

// 传送（清速度、清力、重置插值缓冲）
structure.teleport(Transform3d.of(new Vector3d(30.0, 40.0, 30.0)));

structure.setKinematic(true);  // 变为运动学
structure.setKinematic(false); // 变回动态
structure.setMaterial(PhysicsMaterial.METAL);

structure.applyCentralImpulse(new Vector3d(0.0, 100.0, 0.0));
structure.applyImpulseAt(new Vector3d(0.0, 0.0, 50.0), structure.blockCenter(1, 0, 0));
```

### 3.6 关闭

```java
structure.close(); // 幂等
```

如果结构由 `LevelPhysics` 管理：

```java
levelPhysics.removeStructure(structure);
```

直接 `close()` 只会从物理世界删除刚体，不会从 `LevelPhysics` 的结构列表中移除引用。

---

## 4. 从 Minecraft 世界读取方块区域

### 4.1 绝对坐标体积（推荐用于“把现有建筑变成物理结构”）

```java
import io.mikaple.crythst.integration.minecraft.LevelPhysics;
import io.mikaple.crythst.integration.minecraft.MinecraftPhysics;
import io.mikaple.crythst.math.Transform3d;
import io.mikaple.crythst.physics.body.BodyType;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import io.mikaple.crythst.physics.structure.StructureDef;
import io.mikaple.crythst.physics.voxel.IntBounds3;
import net.minecraft.server.level.ServerLevel;

ServerLevel level = ...; // 必须从服务端线程获取

LevelPhysics physics = MinecraftPhysics.get(level);
IntBounds3 bounds = new IntBounds3(x0, y0, z0, x1, y1, z1); // 开区间 max

PhysicsStructure structure = physics.createStructure(
        StructureDef.builder(MinecraftPhysics.volume(level, bounds))
                .pose(Transform3d.IDENTITY) // 体素坐标已经是绝对世界坐标
                .type(BodyType.DYNAMIC)
                .build());
```

`MinecraftPhysics.volume(level, bounds)` 返回 `BlockVoxelVolume`，它按绝对方块坐标读取 `BlockState`。使用 `Transform3d.IDENTITY` 时，体素的局部坐标就是世界坐标。

### 4.2 局部坐标体积（推荐用于“在指定位置生成新结构”）

```java
IntBounds3 localBounds = new IntBounds3(0, 0, 0, 3, 3, 3);
MutableVoxelVolume volume = VoxelVolumes.filled(localBounds, 1000.0, PhysicsMaterial.STONE);
Transform3d pose = Transform3d.of(new Vector3d(x, y, z));

PhysicsStructure structure = physics.createStructure(
        StructureDef.builder(volume).pose(pose).build());
```

### 4.3 方块映射

默认 `BlockPhysicsMapper` 根据方块 ID 粗略选择材质：

- `ice` → `ICE`；
- `plank` / `wood` / `log` → `WOOD`；
- `iron` / `copper` / `gold` / `steel` → `METAL`；
- `dirt` / `grass` / `sand` / `gravel` → `DIRT`；
- 其他 → 使用配置的默认摩擦/弹性/密度。

自定义：

```java
import io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper;
import io.mikaple.crythst.physics.material.PhysicsMaterial;

MinecraftPhysics.setBlockPhysics(state -> {
    if (state.isAir()) {
        return BlockPhysicsMapper.BlockProperties.of(0.0, PhysicsMaterial.DEFAULT);
    }
    return BlockPhysicsMapper.BlockProperties.of(1000.0, PhysicsMaterial.STONE);
});
```

### 4.4 重要限制

- `BlockVoxelVolume#isSolid` 只判断“方块是否有非空碰撞形状”，然后把整个 1×1×1 体素当作实心立方体；楼梯、台阶、栅栏等**不会**保留精确形状，除非你提供自定义 `VoxelVolume`；
- 读取方块状态需要区块已加载；请在服务端线程、区块可用时构建；
- `MinecraftPhysics.get(level)` 会延迟创建该维度的 `LevelPhysics`；`getIfPresent` 只查询已存在的；
- 修改 `CrythstConfig` 后，已创建的 `LevelPhysics` 不会自动重建。请在首次 `get(level)` 前配置好 `fixedTimeStep`、`maxSubSteps`、`solverIterations` 等。

---

## 5. 重力

### 5.1 世界重力

```java
WorldSettings settings = WorldSettings.defaults()
        .withGravity(new Vector3d(9.81, 0.0, 0.0)); // 下 = +X

PhysicsWorld world = CrythstApi.engine().createWorld(settings);

world.setGravity(new Vector3d(0.0, 0.0, -3.71));
Vector3d gravity = world.gravity();
Vector3d up = world.up();                   // 与重力相反；零重力时 (0,1,0)
double magnitude = world.gravityMagnitude();
```

### 5.2 每刚体覆盖

```java
BodyHandle magnet = world.createBody(BodyDef.dynamic(Shapes.cube(1.0))
        .position(new Vector3d(0.0, 20.0, 0.0))
        .gravityScale(1.0)
        .gravityOverride(new Vector3d(0.0, 0.0, 9.81))
        .build());

magnet.setGravityOverride(new Vector3d(0.0, 9.81, 0.0)); // 覆盖世界重力
magnet.clearGravityOverride();                           // 恢复世界重力 × scale
magnet.setGravityScale(0.25);                            // 只影响“世界重力”模式
```

覆盖向量是加速度（m/s²），不是力；它会**完全忽略** `gravityScale`。

### 5.3 零重力

```java
WorldSettings zeroG = WorldSettings.defaults().withGravity(new Vector3d());
PhysicsWorld space = CrythstApi.engine().createWorld(zeroG);
space.up(); // (0, 1, 0) 回退
```

---

## 6. 查询

### 6.1 射线

```java
import io.mikaple.crythst.physics.query.BodyFilter;
import io.mikaple.crythst.physics.query.RayHit;
import io.mikaple.crythst.physics.query.RayQuery;

RayQuery query = RayQuery.fromDirection(
        new Vector3d(0.0, 20.0, 0.0),
        new Vector3d(0.0, -1.0, 0.0),
        64.0);

List<RayHit> hits = world.raycast(query);
if (!hits.isEmpty()) {
    RayHit nearest = hits.get(0); // 已按 fraction 排序
    BodyHandle body = nearest.body();
    Vector3d point = nearest.point();
    Vector3d normal = nearest.normal();
    double distance = nearest.distance();
}
```

排除某个刚体：

```java
List<RayHit> filtered = world.raycast(
        RayQuery.between(new Vector3d(0, 20, 0), new Vector3d(0, 0, 0))
                .withFilter(BodyFilter.excluding(dynamic)));
```

### 6.2 扫掠

```java
import io.mikaple.crythst.physics.query.SweepHit;
import io.mikaple.crythst.physics.query.SweepQuery;

SweepQuery sweep = SweepQuery.between(
        Shapes.sphere(0.4),
        Transform3d.of(new Vector3d(0.0, 20.0, 0.0)),
        Transform3d.of(new Vector3d(0.0, 0.0, 0.0)));

List<SweepHit> sweepHits = world.sweep(sweep);
if (!sweepHits.isEmpty()) {
    SweepHit hit = sweepHits.get(0);
    BodyHandle body = hit.body();
    Vector3d point = hit.point();
    Vector3d normal = hit.normal();
    double fraction = hit.fraction();
}
```

`sweep` 只接受凸形状；传入非凸形状（例如复合形状）会抛 `IllegalArgumentException`。

### 6.3 OBB 重叠

```java
import io.mikaple.crythst.math.OrientedBox;
import org.joml.Quaterniond;

OrientedBox box = new OrientedBox(
        new Vector3d(0.0, 5.0, 0.0),
        new Vector3d(2.0, 2.0, 2.0),
        new Quaterniond());

List<BodyHandle> overlapping = world.query(box, BodyFilter.ALL);
// 或使用默认过滤器
List<BodyHandle> all = world.query(box);
```

`PhysicsWorld#query` 是遍历所有刚体的 O(n) 精确 SAT 过滤，不是 broadphase 查询。大规模场景建议先用自己的空间索引缩小候选集。

### 6.4 OBB 工具

```java
OrientedBox obb = body.obb();
Aabb aabb = obb.toAabb();
boolean contains = obb.contains(new Vector3d(0.0, 5.0, 0.0));
boolean intersects = obb.intersects(otherObb);
Vector3d closest = obb.closestPoint(new Vector3d(10.0, 5.0, 0.0));
List<Vector3d> corners = obb.corners();
Vector3d[] axes = obb.axes();
```

---

## 7. 约束

约束通过 `world.addConstraint(ConstraintDef)` 创建，返回 `ConstraintHandle`。`bodyB == null` 表示锚定到世界。

### 7.1 固定约束

```java
import io.mikaple.crythst.physics.constraint.FixedConstraintDef;

// 两个刚体：frameA/frameB 是各自刚体局部坐标系中的连接帧
ConstraintHandle weld = world.addConstraint(new FixedConstraintDef(
        bodyA,
        bodyB,
        Transform3d.of(new Vector3d(0.0, 0.5, 0.0)),
        Transform3d.of(new Vector3d(0.0, -0.5, 0.0))));

// 锚定到世界：frameB 是世界空间连接帧
ConstraintHandle anchored = world.addConstraint(new FixedConstraintDef(
        bodyA,
        null,
        Transform3d.IDENTITY,
        Transform3d.of(new Vector3d(10.0, 64.0, 10.0))));
```

### 7.2 铰链

```java
import io.mikaple.crythst.physics.constraint.HingeConstraintDef;

// pivot/axis 在两个刚体的局部坐标系中描述；静止姿态下应指向同一世界方向
ConstraintHandle hinge = world.addConstraint(new HingeConstraintDef(
        door,
        frame,
        new Vector3d(0.0, 0.0, 0.0),
        new Vector3d(0.0, 0.0, 0.0),
        new Vector3d(0.0, 1.0, 0.0),
        new Vector3d(0.0, 1.0, 0.0),
        Math.toRadians(-90.0), // lowerLimit
        Math.toRadians(90.0),  // upperLimit
        false,                 // enableMotor
        0.0,                   // motorTargetVelocity
        0.0));                 // maxMotorImpulse
```

自由铰链快捷方法：

```java
ConstraintHandle freeHinge = world.addConstraint(
        HingeConstraintDef.free(bodyA, bodyB,
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 0.0, 0.0),
                new Vector3d(0.0, 1.0, 0.0),
                new Vector3d(0.0, 1.0, 0.0)));
```

当 `bodyB == null` 时，`pivotB` 是世界空间位置，`axisB` 是世界空间轴。

### 7.3 滑轨

```java
import io.mikaple.crythst.physics.constraint.SliderConstraintDef;

// 沿 frame 的局部 X 轴滑动
ConstraintHandle slider = world.addConstraint(new SliderConstraintDef(
        bodyA,
        bodyB,
        Transform3d.of(new Vector3d(0.0, 0.0, 0.0)),
        Transform3d.of(new Vector3d(0.0, 0.0, 0.0)),
        -2.0,  // lowerLinearLimit
        2.0,   // upperLinearLimit
        0.0,   // lowerAngularLimit
        0.0,   // upperAngularLimit
        true,  // enableLinearMotor
        1.0,   // targetLinearVelocity
        100.0  // maxLinearForce
));
```

自由滑轨：

```java
ConstraintHandle freeSlider = world.addConstraint(SliderConstraintDef.free(
        bodyA, bodyB,
        Transform3d.of(new Vector3d()),
        Transform3d.of(new Vector3d())));
```

### 7.4 点对点

```java
import io.mikaple.crythst.physics.constraint.PointConstraintDef;

ConstraintHandle rope = world.addConstraint(PointConstraintDef.between(
        bodyA, bodyB,
        new Vector3d(0.0, 0.5, 0.0),  // pivotA，bodyA 局部
        new Vector3d(0.0, -0.5, 0.0)  // pivotB，bodyB 局部
));
```

### 7.5 句柄操作

```java
ConstraintHandle handle = world.addConstraint(...);
handle.id();
handle.type();
handle.bodyCount();
handle.setEnabled(false); // 临时禁用
handle.setEnabled(true);
handle.involves(bodyA);
world.removeConstraint(handle);
```

- 删除刚体时，`BulletPhysicsWorld#removeBody` 会自动删除涉及该刚体的约束；
- 世界锚定约束在 `rebaseOrigin` 后不会自动重写世界锚点，长距离重定位后建议重建这类约束。

---

## 8. 角色控制器

```java
import io.mikaple.crythst.physics.character.CharacterController;
import io.mikaple.crythst.physics.character.CharacterDef;

CharacterController character = world.createCharacter(
        CharacterDef.builder(0.3, 1.8) // 半径, 总高
                .pose(Transform3d.of(new Vector3d(0.0, 65.0, 0.0)))
                .stepHeight(0.6)
                .maxSlope(Math.toRadians(50.0))
                .jumpSpeed(8.0)
                .fallSpeed(55.0)
                .gravityScale(1.0)
                .damping(0.0, 0.0)
                .build());

// 每个物理步的位移向量（不是速度）；通常 = 输入方向 * 每步移动距离
character.setWalkDirection(new Vector3d(0.0, 0.0, 0.05));

if (character.onGround()) {
    character.jump();
}

character.setUp(new Vector3d(0.0, 1.0, 0.0));
character.setGravity(new Vector3d(0.0, -9.81, 0.0));
character.setStepHeight(0.6);
character.setMaxSlope(Math.toRadians(45.0));

Transform3d position = character.position();
character.teleport(Transform3d.of(new Vector3d(0.0, 70.0, 0.0)));
character.warp(new Vector3d(0.0, 70.0, 0.0));
character.close();
```

如果不设置 `CharacterDef#up`，控制器使用 `world.up()`。因此任意方向重力下也能正常工作。

---

## 9. 接触与步进回调

```java
import io.mikaple.crythst.physics.collision.ContactEvent;
import io.mikaple.crythst.physics.world.StepListener;
import io.mikaple.crythst.physics.world.StepStats;

world.addContactListener(event -> {
    BodyHandle a = event.bodyA();
    BodyHandle b = event.bodyB();
    double totalImpulse = event.totalImpulse();
    for (ContactPoint point : event.points()) {
        Vector3d position = point.position();
        Vector3d normal = point.normal();
        double impulse = point.impulse();
        double depth = point.depth();
    }
});

world.addStepListener(new StepListener() {
    @Override
    public void beforeStep(PhysicsWorld world, double fixedTimeStep) {
        // 物理步之前
    }

    @Override
    public void afterStep(PhysicsWorld world, double fixedTimeStep, StepStats stats) {
        int bodies = stats.bodyCount();
        int awake = stats.awakeBodyCount();
        int contacts = stats.contactCount();
        double millis = stats.stepMillis();
    }
});
```

回调在物理线程执行。不要在这里调用渲染 API，也不要把可变的 Bullet 句柄交给其他线程。

---

## 10. 固定步长与渲染插值

```java
// 服务端每 tick
world.advance(realDeltaSeconds);

for (PhysicsStructure structure : structures) {
    structure.syncRenderPose();
}

// 客户端/渲染线程：所有结构在同一帧时间采样
long frameNanos = System.nanoTime();
for (PhysicsStructure structure : renderStructures) {
    StructureRenderState state = structure.renderStateAt(frameNanos);
    Transform3d pose = state.pose();
    VoxelColliderData collider = state.collider();
    Vector3d comOffset = state.centerOfMassOffset();
    double blockScale = state.blockScale();
    OrientedBox bounds = state.bounds();
    Aabb aabb = state.aabb();
}
```

- `renderStateAt(frameNanos)` 是渲染线程安全的：它只读取不可变 collider 和 timestamped
  `PoseTimeline` 采样；`renderState(alpha)` 仍保留以兼容旧代码，但已忽略 `alpha`；
- 不要从渲染线程调用 `structure.body()`、`structure.pose()`、`structure.obb()` 等会接触 Bullet 的方法；
- `syncRenderPose()` 每个服务端 tick 调用一次，把同一姿态同时推入旧 `InterpolatedPose`（兼容/诊断）
  和 `PoseTimeline`（实际渲染）；`reset`/`teleport` 用于传送，避免跨越大距离插值；
- 网络结构用 `ClientStructureCache#interpolatedPose(bodyId, frameNanos)`，不要直接读原始 `pose()`。

---

## 11. 调试快照

```java
import io.mikaple.crythst.physics.debug.BodyDebugInfo;
import io.mikaple.crythst.physics.debug.PhysicsDebugSnapshot;

// 接触流形默认不保留；需要时显式开启（会带来每步 manifold 提取成本）
world.setDebugContactCapture(true);

PhysicsDebugSnapshot debug = world.debugSnapshot();
String backend = debug.backend();
String version = debug.backendVersion();
Vector3d origin = debug.origin();
Vector3d gravity = debug.gravity();
Vector3d up = debug.up();
StepStats stats = debug.stats();
List<ContactEvent> contacts = debug.contacts();

for (BodyDebugInfo info : debug.bodies()) {
    long id = info.id();
    BodyType type = info.type();
    Transform3d pose = info.pose();
    OrientedBox obb = info.obb();
    Aabb aabb = info.aabb();
    boolean active = info.active();
}
```

`PhysicsDebugSnapshot` 和 `BodyDebugInfo` 都是不可变 record，可安全交给调试渲染或命令输出。`setDebugContactCapture(false)` 会清空接触列表，避免持续负担。

---

## 12. Minecraft 集成与配置

### 12.1 配置

配置文件：`config/crythst.json`（由 `CrythstConfig#load` 读取，缺失时创建默认文件）。

```java
CrythstConfig config = MinecraftPhysics.config();
config.fixedTimeStep = 1.0 / 120.0;
config.maxSubSteps = 8;
config.serverTickTiming = true; // one server tick of game time per tick
config.solverIterations = 12;
config.allowSleep = true;
config.deterministic = true;
config.maxFrameSeconds = 0.25;
config.rebaseDistance = 2048.0;
config.debug = false; // historical flag; F3+F6 entries control the overlays
config.defaultBlockMass = 1000.0;
config.defaultFriction = 0.7;
config.defaultRestitution = 0.0;
```

> `fixedTimeStep`、`maxSubSteps`、`solverIterations`、`allowSleep`、`deterministic` 在 `LevelPhysics` 构造时读取；已经创建的维度不会自动应用这些改动，需要重新加载世界或重建 `LevelPhysics`。`serverTickTiming`、`maxFrameSeconds`、`rebaseDistance` 等则在每次 tick 时动态读取。`serverTickTiming` 默认开启：每个服务端 tick 只推进一个 tick 的游戏时间，使 20 TPS + 1/60 s 稳定为每 tick 3 个固定步。渲染姿态使用 timestamped `PoseTimeline`（约一 tick 延迟），不再依赖客户端 partial tick。详见 `docs/physics-timing.md`。
>
> `debug` 是历史开关：当前 F3+F6 调试层由 `crythst:physics*` 条目独立控制，`/crythst debug` 只翻转该字段，不会打开或关闭这些条目。

### 12.2 每维度世界

```java
LevelPhysics physics = MinecraftPhysics.get(serverLevel);       // 不存在则创建
LevelPhysics existing = MinecraftPhysics.getIfPresent(serverLevel); // 只查询

PhysicsWorld world = physics.world();
List<PhysicsStructure> structures = physics.structures();
List<PhysicsStructure> renderList = physics.renderStructures();

physics.tick();          // 通常由 PhysicsTicker 自动调用
physics.step(10);        // 暂停时手动步进
physics.setPaused(true);
physics.paused();
```

`MinecraftPhysics#tick(MinecraftServer)` 由 `PhysicsTicker` 注册到 `ServerTickEvents.END_SERVER_TICK`；`ServerLevelEvents.UNLOAD` 会调用 `MinecraftPhysics#unload`；服务器停止时调用 `closeAll`。

### 12.3 命令

| 命令 | 作用 |
| --- | --- |
| `/crythst status` | 后端、数量、步进耗时、重力、up、origin |
| `/crythst bodies` | 前 20 个刚体 |
| `/crythst pause` / `resume` | 暂停/恢复 |
| `/crythst step [count]` | 手动固定步 |
| `/crythst gravity <x> <y> <z>` | 设置世界重力 |
| `/crythst demo` | 生成演示结构 |
| `/crythst clear` | 删除所有结构 |
| `/crythst timing` | 固定步长、姿态抖动和渲染姿态诊断 |
| `/crythst align` | 打印 body/structure/COM 与渲染坐标约定检查 |
| `/crythst entitydebug` | 打印附近实体的结构碰撞诊断 |
| `/crythst debug` | 翻转历史 `config.debug` 字段；F3+F6 调试层不受它控制 |

---

## 13. 引擎可用性与生命周期

```java
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.physics.PhysicsException;

try {
    PhysicsEngine engine = CrythstApi.engine();
    String description = CrythstApi.backendDescription();
} catch (PhysicsException | LinkageError e) {
    // 原生库不可用：检查平台/ABI/nativePath
}

boolean available = CrythstApi.isAvailable();
```

- `CrythstApi.engine()` 是进程级单例；
- `PhysicsEngine#close()` 只是标记引擎关闭，不会卸载 JVM 原生库；
- 每个 `PhysicsWorld` 用完应 `close()`；
- Minecraft 集成中，`LevelPhysics#close()` 会关闭该维度所有结构和世界；`MinecraftPhysics#closeAll()` 在服务器停止时统一处理。

---

## 14. 常见错误与排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Dynamic bodies need either an explicit mass or a shape with computable volume` | 动态刚体使用 `PlaneShape` / `ConvexHullShape` 且没给 `mass` | `.mass(...)` |
| `Invalid voxel bounds` | `IntBounds3` 的 max 小于等于 min | max 是开区间，必须大于 min |
| `Structure volume contains no solid blocks` | 体积没有实心方块 | 至少 `setSolid(..., true)` 或 `VoxelVolumes.filled` |
| `A compound shape needs at least one child` | `CompoundShape.builder().build()` 没有子形状 | 至少 `add(...)` 一个 |
| `A dynamic body must have positive mass` | `BodyHandle#setMass(0)` 用在动态刚体 | 用正质量，或先切换到静态/运动学 |
| `IndexOutOfBoundsException`（体素） | 访问了 `bounds` 之外的坐标 | 检查 `IntBounds3` 的开区间边界 |
| `Voxel volume is too large to colliderise` | 超过 `maxCells`（默认 400 万） | 缩小体积或提高 `VoxelColliderOptions#maxCells` |
| 渲染线程崩溃/数据竞争 | 从渲染线程调用 `BodyHandle` / `PhysicsStructure` 的可变方法 | 只使用 `renderStateAt` / `BodySnapshot` / `PhysicsDebugSnapshot` |
| 结构删除后仍出现在调试列表 | 直接 `structure.close()` 而没有通知 `LevelPhysics` | 用 `levelPhysics.removeStructure(structure)` |
| 重定位后世界锚定约束偏移 | `rebaseOrigin` 不重写 joint 的世界锚点 | 重定位后重建这类约束 |
| 楼梯/台阶变成整块 | 结构体素默认按整格生成合并盒 | 用 `BlockStructureCollisionShape` / `BlockStructureDynamicCollider` 提供精确形状，或自定义 `VoxelVolume` |
| 扫掠抛 `sweep requires a convex shape` | 传入了 `CompoundShape` 等非凸形状 | 用 `BoxShape` / `SphereShape` / `CapsuleShape` / `CylinderShape` / `ConvexHullShape` |
| 调试线框不显示 | 多人环境没有本地 `ClientPhysicsView`，或 F3+F6 中对应 `crythst:physics*` 条目仍是 NEVER | 在单人世界按 F3+F6 启用条目；`/crythst debug` 不再控制这些条目 |
| `PhysicsException: Bundled native library is missing` | 平台/ABI 不在支持范围 | 参考 [platforms.md](platforms.md)，用 `-Dcrythst.bullet.nativePath` |


---

## 15. 玩家重力方向

```java
import io.mikaple.crythst.integration.minecraft.PlayerGravity;

PlayerGravity.setLook(player);                 // 视线方向 = 下
PlayerGravity.setFixed(player, new Vector3d(9.81, 0.0, 0.0)); // 下 = +X
PlayerGravity.reset(player);                   // 回到世界重力/原版向下
```

游戏内：

```text
/crythst playergravity look
/crythst playergravity 9.81 0 0
/crythst playergravity reset
```

默认是原版向下 `(0,-1,0)`。`/crythst gravity <x> <y> <z>` 会同步该维度未单独覆盖的玩家。

---

## 16. 世界地形碰撞

地形碰撞默认开启，由 `LevelPhysics` 自动管理，一般不需要手动调用。需要强制刷新或方块编辑后立即重建时：

```java
LevelPhysics physics = MinecraftPhysics.get(serverLevel);
physics.ensureTerrain();       // 立即加载当前刚体周围的地形
physics.invalidateTerrain();   // 丢弃所有地形缓存（下一次 tick 重建）
```

配置项（`config/crythst.json`）：

```json
{
  "worldCollisionEnabled": true,
  "worldCollisionRadius": 8.0,
  "worldCollisionRefreshTicks": 10,
  "worldCollisionMaxSections": 64,
  "worldCollisionLookahead": 32.0
}
```

---

## 17. 结构容器、观察者与命令参数

```java
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.api.sublevel.ServerStructureContainer;
import io.mikaple.crythst.api.sublevel.StructureContainer;
import io.mikaple.crythst.api.sublevel.StructureContainerEvents;
import io.mikaple.crythst.api.sublevel.StructureObserver;

ServerStructureContainer container = CrythstApi.container(serverLevel);
if (container != null) {
    PhysicsStructure byId = container.byId(bodyId);
    PhysicsStructure byName = container.byName("ship");
    PhysicsStructure containing = container.containing(worldPoint);
    List<PhysicsStructure> nearby = container.intersecting(aabb);

    // 会通过容器自己的 create/remove/refresh 立即通知观察者
    PhysicsStructure created = container.createStructure(definition);
    container.removeStructure(created);
}

AutoCloseable ready = StructureContainerEvents.registerContainerReady(container -> {
    // 每个服务端物理世界创建一次
});

StructureObserver observer = new StructureObserver() {
    @Override public void onStructureAdded(PhysicsStructure structure) { }
    @Override public void onStructureRemoved(PhysicsStructure structure) { }
    @Override public void onStructureRefreshed(PhysicsStructure structure) { }
};
AutoCloseable observerHandle = StructureContainerEvents.registerObserver(observer);
```

命令参数：

```java
import io.mikaple.crythst.api.sublevel.StructureArgumentType;

.then(Commands.argument("structure", StructureArgumentType.structure())
        .executes(ctx -> {
            PhysicsStructure structure =
                    StructureArgumentType.getStructure(ctx, "structure");
            return 1;
        }))
```

支持 `42`、`0x2a`、`#42`、`name:ship`（或直接 `ship`）。结构没有独立名称字段；
非数字名称只在 body user data 的字符串形式匹配时生效。

> 客户端 `CrythstApi.container(clientLevel)` 在多人游戏里 `structures()` 为空，
> 应使用 `ClientStructureContainer#snapshots()` / `snapshot(id)` 或第 18 节的
> `StructureRenderView`。网络容器返回不可变快照，不返回 `PhysicsStructure`。

---

## 18. 客户端渲染视图、姿态、选择与追踪

只在客户端加载 `io.mikaple.crythst.api.client.*`。

### 18.1 Render view / pose

```java
import io.mikaple.crythst.api.client.StructureRenderPose;
import io.mikaple.crythst.api.client.StructureRenderView;

List<StructureRenderView> views = StructureRenderView.all();
StructureRenderView view = StructureRenderView.get(bodyId);

Transform3d pose = view.pose();
Transform3d previous = view.previousPose();
Transform3d current = view.currentPose();
Aabb bounds = view.aabb();
Vector3d velocity = view.velocityAt(worldPoint); // m/s

StructureRenderPose renderPose = view.renderPose();
Vector3d linear = renderPose.linearVelocity();    // m/s
Vector3d angular = renderPose.angularVelocity();  // rad/s
```

集成服务器（单人）结构和网络结构都使用同一套 timestamped 时间线。同一 body id
时集成服务器数据优先。`StructureRenderPose` 的端点与速度是渲染时间线有限差分，
不是权威物理速度。

### 18.2 自定义姿态 provider

```java
import io.mikaple.crythst.api.client.StructurePoseProvider;

AutoCloseable handle = StructurePoseProvider.register((bodyId, partialTick, frameNanos) -> {
    if (bodyId != myBodyId) {
        return null; // 交给下一个 provider / 默认时间线
    }
    return StructureRenderPose.sampled(bodyId, myPose);
});
```

后注册的 provider 优先；返回 `null` 会继续回退。默认 provider 忽略 `partialTick`，
按帧时间采样一 tick 延迟的时间线。

### 18.3 Clip / pick 扩展

```java
import io.mikaple.crythst.api.client.StructureClipExtension;
import io.mikaple.crythst.api.client.StructurePickExtension;

BlockHitResult merged = StructureClipExtension.clip(clientLevel, clipContext);

try (AutoCloseable ignored = StructureClipExtension.ignore(bodyId)) {
    // 当前线程的后续 clip 忽略该结构
}
try (AutoCloseable ignored = StructureClipExtension.ignoreAll()) {
    // 当前线程的后续 clip 只考虑真实方块
}

StructureBlockHitResult hitNow = StructurePickExtension.current();
StructureBlockHitResult hitRay = StructurePickExtension.raycast(reach);
```

`ignore` 是线程本地且可嵌套；它不会修改世界或结构，也不重写已经生成的命中对象。
真实方块始终优先。

### 18.4 客户端追踪 / 继承速度

```java
import io.mikaple.crythst.api.client.EntityStructureTracking;

StructureRenderView tracking =
        EntityStructureTracking.getTrackingStructure(entity);
Vector3d inherited = EntityStructureTracking.getInheritedVelocity(entity); // m/s

EntityStructureTracking.track(entity, tracking); // 有接触时显式记录，最准确
EntityStructureTracking.track(entity, bodyId);
EntityStructureTracking.clear(entity);
```

没有显式记录时，会先用最近一次客户端碰撞 trace（必须新鲜且几何上覆盖实体），
再退回最近的渲染 AABB。速度来自渲染姿态有限差分，包含角速度项，不读 Bullet body。

---

## 19. 世界方块组装与拆解

```java
import io.mikaple.crythst.api.assembly.AssemblyOptions;
import io.mikaple.crythst.api.assembly.DisassembleOptions;
import io.mikaple.crythst.api.assembly.StructureAssembly;
import io.mikaple.crythst.api.assembly.StructureGather;

StructureGather.GatherResult gathered =
        StructureGather.gatherConnectedBlocks(serverLevel, origin, 4096, pos -> true);
if (!gathered.success()) {
    return; // NO_BLOCKS / TOO_MANY
}

PhysicsStructure structure = StructureAssembly.assemble(
        serverLevel,
        gathered.bounds().min(),
        gathered.blocks(),
        AssemblyOptions.defaults()
                .withType(BodyType.DYNAMIC)
                .withBlockScale(1.0)
                .withRemoveFromWorld(true)
                .withBlockEntities(true));

StructureAssembly.disassemble(serverLevel, structure,
        DisassembleOptions.defaults()
                .withRemoveStructure(true)
                .withBlockEntities(true)
                .withDrops(false));
```

- 采集使用 **26 邻接**；空气、流体、无碰撞形状方块不参与也不传播。
- 结果按 `x,y,z` 确定性排序；`MAX_BLOCKS = 1_000_000`。
- `assemble` 先创建 volume/body，再移除世界方块；失败会回滚已移除方块并关闭 body。
- 移除使用抑制更新的 flags，不走 `destroyBlock`，所以不会掉落物品。
- 方块实体 NBT 会捕获，并在辅助空 Level 启用时重挂；实体**不会**被搬进结构。
- `AssemblyOptions.pose` 相对 anchor：默认选项下捕获区域最小方块保持原世界位置。
- `disassemble` 按当前 pose + `blockScale` 把未偏移体素写回世界；越界或不可替换的位置
  默认跳过，`withDrops(true)` 才会掉落。

坐标工具：

```java
import io.mikaple.crythst.api.assembly.AssemblyTransform;

AssemblyTransform transform =
        new AssemblyTransform(structure.pose(), structure.blockScale());
Vector3d centre = transform.voxelCenter(x, y, z);
BlockPos worldPos = transform.voxelToWorldBlock(x, y, z);
Vector3d voxel = transform.worldToVoxel(worldPoint);
```

> 这是**世界空间**组装，不是 Sable 的 plot / sub-level 组装：没有 plot 分配、
> plot chunk holder 或第二个 `Level` 视图。需要 CA 风格 `SubLevel` 的代码必须移植。

---

## 20. 方块实体 actor 与力组

```java
import io.mikaple.crythst.api.block.BlockEntityStructureActor;
import io.mikaple.crythst.api.block.StructurePhysicsContext;
import io.mikaple.crythst.api.physics.force.ForceGroup;
import io.mikaple.crythst.api.physics.force.QueuedForceGroup;

public final class ThrusterBlockEntity extends BlockEntity
        implements BlockEntityStructureActor {
    private static final ForceGroup THRUST = new ForceGroup(
            Component.literal("thruster"), null, 0x66CCFF, true);

    @Override
    public void structurePhysicsTick(StructurePhysicsContext context) {
        QueuedForceGroup group = context.forceGroup(THRUST);
        double throttle = 1.0;
        group.applyPointForce(
                context.centerOfMassWorld(),
                new Vector3d(0.0, 500.0 * throttle, 0.0)); // N
    }
}
```

- actor 每个固定物理子步在积分前调用一次；用 `timeStepSeconds`，不要假设 1/20s。
- 只有位于**辅助空 Level** 的方块实体才会被发现；`subLevelEnabled=false` 或空 Level
  创建失败时没有 actor。
- actor 列表按 structure 缓存，在创建、refresh/reindex、持久化恢复和移除后重建。
  运行中给已有方块实体新增 actor 实现而不同步刷新，不会被发现。
- `context` 和 `QueuedForceGroup` 只在本次回调有效，不要跨子步保存。
- `ForceGroup` 是稳定 key（record equality）；用相同实例/相同字段值避免缓存膨胀。
- 力单位是 N，torque 是 N·m；Crythst 在子步结束用 `dt` 转成 N·s / N·m·s 后施加。
  不要自己乘 `dt`。
- `StructurePhysicsRuntime` 由 `LevelPhysics` 持有并在 `beforeStep` 调用；普通模组
  不需要自己创建。它缓存每个 structure 每个 force group 的累加器，refresh/remove
  会丢弃缓存。

---

## 21. 坐标 / 四元数工具

```java
import io.mikaple.crythst.api.math.StructureMath;

Vector3d blockCentre = StructureMath.atCenterOf(blockPos);       // (x+0.5, y+0.5, z+0.5)
Vector3d blockLower = StructureMath.atLowerCornerOf(blockPos);   // (x, y, z)
Vec3 mc = StructureMath.toMojang(jomlVec);
Vector3d joml = StructureMath.toJoml(mc);
Quaterniond q = StructureMath.toJoml(mcQuaternion);
Quaternionf f = StructureMath.toMojang(jomlQuaternion);
```

`StructureMath` 是纯函数，客户端/服务端都可加载；带 `dest` 的重载会覆盖并返回 dest。
`ZERO` / `QUAT_IDENTITY` 以只读 JOML 接口暴露，不要强转后修改。

---

## 22. 新 API 的限制

- 网络客户端容器返回 `ClientStructureSnapshot`，不是 `PhysicsStructure`；
  `byId` / `containing` 在多人客户端不会解析网络快照。
- 组装是**世界空间**操作，不提供 plot / plot-yard / 第二 `Level` 视图。
- actor 需要辅助空 Level；没有它就没有 actor 发现。
- actor 发现和 force-group 缓存都需要 refresh/reindex/remove 来清理旧状态。
- 尚未提供：constraints、ropes、box objects、loading tickets、tracking-point
  SavedData、schematic 上下文、propeller/lift-provider、force totals/debug UI。
- 不提供 `dev.ryanhcode.sable.*` shim，也不是 Create Aeronautics 的二进制 drop-in
  替代。完整差距矩阵见 [`API_GAP_ANALYSIS.md`](../API_GAP_ANALYSIS.md)。
