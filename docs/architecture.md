# Crythst 架构说明

本文描述 Crythst 的分层、线程模型、步进时钟、原点重定位、质量/惯性计算、体素合并、OBB 相交、任意重力、结构生命周期和调试渲染路径。

目标读者是准备在 Crythst 之上实现玩法、渲染或网络同步的模组开发者。

---

## 1. 总体分层

Crythst 刻意保持“核心物理 API”与“Minecraft/Fabric 集成”分离。核心物理层只依赖 JOML 和 libbulletjme；Minecraft 类只出现在 `integration.minecraft` 与 `integration.fabric` 中。

```
io.mikaple.crythst.math / util
            ▲
            │
io.mikaple.crythst.physics（核心接口、形状、体素、结构、查询、约束）
            ▲
            ├───────────────┐
            │               │
io.mikaple.crythst.physics.bullet      io.mikaple.crythst.physics.structure
（libbulletjme 实现）                   （核心结构，依赖 PhysicsWorld 接口）
            ▲
            │
io.mikaple.crythst.api（CrythstApi，公共入口）
            ▲
            │
io.mikaple.crythst.config
            ▲
            │
io.mikaple.crythst.integration.minecraft（每维度 LevelPhysics）
            ▲
            │
io.mikaple.crythst.integration.fabric（服务端入口、命令、客户端渲染/调试/交互）
```

### 包依赖方向

| 包 | 依赖 | 说明 |
| --- | --- | --- |
| `util` | 无 | `Validate`、`StepProfiler` |
| `math` | JOML | `Transform3d`、`Aabb`、`OrientedBox`、`InterpolatedPose`、`CrythstMath` |
| `physics`（根） | `math`、`util` | `PhysicsEngine`、`PhysicsException` |
| `physics.world` | `physics` 根、`body`、`query`、`constraint` | 世界接口与设置 |
| `physics.shape` | `math`、`util` | 后端无关的碰撞形状 |
| `physics.voxel` | `math`、`material`、`shape` | 体素网格、贪心合并、质量/惯性 |
| `physics.structure` | `physics.world`、`physics.voxel`、`math` | `PhysicsStructure` 生命周期与渲染姿态 |
| `physics.constraint` | `body`、`math` | 约束定义与句柄接口 |
| `physics.character` | `math` | 角色控制器接口与定义 |
| `physics.query` | `body`、`shape`、`math` | 射线、扫掠、过滤器 |
| `physics.debug` | `math`、`body`、`world` | 不可变调试快照 |
| `physics.bullet` | `physics` 核心、libbulletjme、`Crythst` 日志 | Bullet 后端实现 |
| `api` | `physics`、`physics.bullet` | 进程级 `PhysicsEngine` 单例 |
| `config` | Gson、`Crythst` 日志 | `config/crythst.json` |
| `integration.minecraft` | `api`、`config`、`physics`、Minecraft 服务端类 | 每维度物理世界、方块映射 |
| `integration.fabric` | Fabric API、`integration.minecraft`、命令 API | 主入口、命令、客户端调试 |

**关键约束：**

- 核心物理接口（`PhysicsWorld`、`BodyHandle`、`PhysicsShape` 等）不出现任何 Minecraft 类型；
- 只有 `physics.bullet` 直接接触 `com.jme3.bullet.*`；
- 只有 `integration.minecraft` / `integration.fabric` 直接接触 `net.minecraft.*` 和 Fabric API；
- 功能模组应依赖公共 API（`CrythstApi`、`physics.*`），而不是直接依赖 `BulletPhysicsWorld` 等实现类。

---

## 2. 线程模型

### 2.1 服务端线程拥有 Bullet

`PhysicsWorld` 的 Javadoc 明确说明：实现不是线程安全的，所有方法必须从同一个线程调用。实际运行时就是 **Minecraft 服务端线程**。

```
ServerTickEvents.END_SERVER_TICK
        │
        ▼
MinecraftPhysics.tick(server)
        │  遍历 server.getAllLevels()
        ▼
LevelPhysics.tick()
        │  world.advance(delta)
        │  structure.syncRenderPose()
        │  debugSnapshot()（仅 debug=true）
        │  maybeRebase()
        ▼
BulletPhysicsWorld.step(...) / Bullet 原生空间
```

因此以下操作都只能在服务端线程执行：

- `PhysicsWorld#createBody`、`removeBody`、`step`、`advance`、`setGravity`、`rebaseOrigin`；
- `BodyHandle` 上的任何 setter、力/冲量、`teleport`；
- `PhysicsStructure` 的创建、`reindex`、`setPose`、`close`；
- 约束和角色控制器的创建/修改；
- `ContactListener` / `StepListener` 回调内部（它们本来就在物理线程被调用）。

### 2.2 客户端只读不可变/volatile 快照

客户端渲染线程 **绝不** 直接调用 Bullet 或修改 `PhysicsWorld`。它读取的是服务端线程提前发布的快照：

| 数据 | 发布方式 | 读取方 |
| --- | --- | --- |
| 结构姿态 | `PoseTimeline` 内部的 volatile `State(previous, current, timestamps)`；旧 `InterpolatedPose` 保留用于兼容/诊断 | `PhysicsStructure#interpolatedPoseAt` / `renderStateAt` |
| 结构列表 | `LevelPhysics#renderStructures()` 返回 `volatile List<PhysicsStructure>` | `ClientPhysicsView` |
| 结构碰撞数据 | `VoxelColliderData` 不可变对象，字段 `volatile` 替换 | `StructureRenderState#collider()` |
| 调试快照 | `LevelPhysics#renderDebugSnapshot()` 的 `volatile` 字段 | `ClientPhysicsView#debug()` |
| 调试接触点 | `PhysicsDebugSnapshot#contacts()` 中的不可变 `ContactEvent` 列表；只有显式开启 `setDebugContactCapture(true)` 才填充 | `PhysicsGizmoRenderer#emitContacts` |
| 刚体快照 | `BodySnapshot` 不可变 record | 命令、调试、网络层 |

`PoseTimeline` 用一条 volatile 引用交换整个不可变 `State` record，避免“读到一半”的撕裂问题：

```java
private record State(Transform3d previous, Transform3d current,
                     long previousNanos, long currentNanos, ...) {}
private volatile State state = EMPTY;
```

服务端每个 tick 调用一次 `syncRenderPose()`，把同一姿态连同 `System.nanoTime()` 推入时间线。渲染线程用 `sample(nowNanos)` 在 `now - 一个平滑后的发布间隔` 处采样，只做位置 `lerp` 和旋转 `slerp`，不预测未来。多人结构把收到姿态的时间写入同一个 `PoseTimeline`，因此本地和网络结构共用同一套采样规则。

### 2.3 回调线程

- `ContactListener#onContact`、`StepListener#beforeStep/afterStep` 在物理步进线程（服务端线程）执行；
- 不要在回调里做长时间阻塞操作，也不要把 `BodyHandle` 暴露给渲染线程；
- 如果回调里修改刚体，注意它处于同一次 `step` 的调用栈中，行为与 Bullet 的 manifold 处理顺序相关。

---

## 3. 固定步长时钟

### 3.1 `FixedStepClock`

`io.mikaple.crythst.physics.tick.FixedStepClock` 把可变帧时间转换为固定数量的物理步：

```java
accumulator += realSeconds;
steps = min((int) floor(accumulator / fixedTimeStep + 1e-9), maxSubSteps);
accumulator -= steps * fixedTimeStep;
```

特点：

- `maxSubSteps` 防止“死亡螺旋”：一帧掉到很慢时不会无限补步；
- 超出 `maxSubSteps` 的时间被丢弃；
- `1e-9` 的步数容差避免 `0.3 / 0.1 = 2.9999999999999996` 这类浮点边界少跑一步（表现为 2/4 步交替抖动）；
- `alpha() = accumulator / fixedTimeStep`，保留给手动步进/诊断；
- `alpha` 不会达到 1.0；如果累加器仍然大于等于一个步长，会被压到 `0.999 * fixedTimeStep`；
- `reset()` 清空累加器，用于暂停/恢复。

### 3.2 世界层

`BulletPhysicsWorld` 在构造时创建 `FixedStepClock(settings.fixedTimeStep(), settings.maxSubSteps())`。

- `world.step(fixedTimeStep)`：**强制运行恰好一个固定步**，不受时钟累加器影响；适合确定性测试和 `/crythst step`；
- `world.advance(realSeconds)`：先让时钟计算步数，再循环调用 `step(settings.fixedTimeStep())`，返回实际步数；
- `world.interpolationAlpha()`：直接返回时钟的 alpha；
- `world.setPaused(true)` 会重置时钟，恢复后不会补跑暂停期间的时间。

### 3.3 Minecraft 层

`LevelPhysics.tick()` 每个 `END_SERVER_TICK` 只推进**一个服务端 tick 的游戏时间**
（默认 `1 / tickRate = 50 ms`，而不是两次回调之间的墙钟时间）：

```java
TickRateManager tickRate = level.getServer().tickRateManager();
boolean frozen = tickRate.isFrozen() && !tickRate.isSteppingForward();
double simDelta = frozen ? 0.0 : 1.0 / Math.max(1.0, tickRate.tickrate());
int steps = world.advance(simDelta);
for (PhysicsStructure structure : structures) {
    structure.syncRenderPose(); // exactly once per server tick
}
```

原因：

- 渲染器**不再**用 Minecraft 的 tick partial tick 在两次服务端 tick 姿态之间插值：服务端 tick 与客户端 tick 没有相位锁定，会在某个客户端 tick 内突然换到下一对姿态。现在 `PoseTimeline` 按帧时间采样 `now - 一个平滑后的发布间隔`，本地和网络结构都约延迟一个 tick；
- 如果物理步数由墙钟决定，`END_SERVER_TICK` 的调度抖动会让同一 tick 跑 2/3/4 个 1/60 步，即使姿态时间线正确，模拟速度本身也会忽快忽慢；
- 按固定 tick 时间推进后，20 TPS + 1/60 s 固定步长稳定得到每 tick 3 步；1/120 s 则稳定 6 步（`maxSubSteps` 会自动预留至少 6+1 步）；
- **不要**在 `world.advance` 内部每个 substep 都 `syncRenderPose`：substep 是在 tick 内成批执行的，插值缓冲应该保存 tick 边界姿态，而不是最后一个 substep 的姿态；
- `serverTickTiming = false` 可恢复旧的墙钟追赶模式，仅用于 A/B 测试；
- tick rate 被冻结时跳过物理推进和 block logic，但保留当前 render pose；
- `/crythst timing` 输出 `wallDt`、`simDt`、`steps`、`fixedAlpha`、`poseDelta`、`stepJitter`、`poseJitter`；
- `/crythst step <count>` 调用 `LevelPhysics#step(int)`，直接运行 `world.step(settings.fixedTimeStep())`，用于暂停时逐步检查。

> 注意：`WorldSettings.contactTolerance` 目前保存在设置中，但 Bullet 的扫掠查询使用的是 `SweepQuery#tolerance`，而不是这个全局字段。修改它不会改变 `SweepQuery` 的默认容差。

---

## 4. 原点重定位（rebasable origin）

### 4.1 为什么需要

Minecraft 世界坐标可以达到数百万格。Bullet 原生使用单精度 `float`，在远离原点时会出现明显的精度抖动。Crythst 的做法：

- **公共 API 始终使用世界空间 `double`**（`Transform3d`、`Vector3d`、`Aabb`、`OrientedBox`）；
- Bullet 内部只保存相对于一个可移动 `origin` 的局部 `float` 坐标；
- 当模拟区域漂远时，移动 `origin`，让 Bullet 重新回到数值精度较好的局部范围。

`PhysicsWorld` 暴露：

```java
Vector3d origin();
void rebaseOrigin(Vector3dc newOrigin);
```

### 4.2 转换规则

`BulletConversions` 负责两种坐标的转换：

```java
local = world - origin;
world = local + origin;
```

- 刚体位置、射线端点、扫掠端点、世界锚点约束、接触点都会经过这层转换；
- `BodyHandle#transform()` 返回的是 `toWorld(local, origin)` 后的 `double` 世界坐标；
- `BodyHandle#setTransform()` / `teleport()` 会把世界坐标减去当前 `origin` 再写入 Bullet。

### 4.3 谁触发重定位

`LevelPhysics#maybeRebase()` 在每次 tick 后计算所有刚体位置的平均值：

```java
Vector3d center = average(body.transform().position());
if (center.distanceSquared(world.origin()) > rebaseDistance * rebaseDistance) {
    world.rebaseOrigin(center);
}
```

`rebaseDistance` 来自 `CrythstConfig`，默认 `2048.0` 格。

`BulletPhysicsWorld#rebaseOrigin` 的实现：

1. 计算 `delta = newOrigin - origin`；
2. 对每个 `BulletBodyHandle` 调用 `shiftLocalOrigin(delta)`，把 Bullet 局部位置减去 delta；
3. 更新 `origin` 和 `WorldSettings.origin`。

由于刚体的世界位置 = 局部位置 + origin，局部位置减去 delta、origin 加上 delta，世界位置保持不变。

### 4.4 已知限制

- **世界锚定约束**：创建 `bodyB == null` 的固定/铰链/滑轨/点约束时，世界锚点会被转换成**创建时** origin 下的局部坐标。`rebaseOrigin` 目前只平移刚体，不会重写 joint 内部保存的世界锚点。因此长距离重定位后，这类“锚定到世界”的约束可能出现偏移；需要时请在重定位后重建约束。
- **结构姿态**：`PhysicsStructure` 的 `pose()` 是从刚体世界变换和质心偏移反推出来的，重定位本身不会改变结构世界姿态；但如果你缓存了 `BodyHandle` 的局部坐标或 Bullet 对象，重定位后需要重新读取。
- **原生空间范围**：`CrythstPhysicsSpace` 的 AABB 固定为 `[-100000, 100000]` 的三次方；重定位让内容保持在这个范围内，但极端情况下仍应注意。

---

## 5. 质量与惯性

### 5.1 普通刚体

`BodyDef` 的质量解析规则：

```java
if (!type.infiniteMass()) {
    if (mass > 0) return mass;
    double derived = shape.volume() * material.density();
    if (derived > 0) return derived;
    throw ...
}
return 0; // STATIC / KINEMATIC
```

- 动态刚体必须显式给 `mass`，或者形状可计算体积且材质密度为正；
- `PlaneShape#volume()` 和 `ConvexHullShape#volume()` 返回 `0`，因此这两类动态刚体必须显式指定质量；
- `CompoundShape#volume()` 是子形状体积之和；
- `BodyDef.Builder#build()` 在动态刚体既没有质量、体积又为 0 时直接抛异常。

### 5.2 体素结构

`VoxelColliderBuilder` 先做贪心合并，得到若干 `VoxelBox`，然后 `summarise`：

1. **总质量**：`totalMass = Σ box.cellCount() * box.blockMass()`；
2. **质心**：`centerOfMass = Σ (box.center() * box.mass()) / totalMass`（体素坐标，未缩放）；
3. **每个盒的局部主惯性**：

   ```text
   Ixx = m/12 * (sy² + sz²)
   Iyy = m/12 * (sx² + sz²)
   Izz = m/12 * (sx² + sy²)
   ```

   其中 `sx/sy/sz` 是盒的边长（乘以 `blockScale` 后的米）。

4. **平行轴项**：

   ```text
   Ixx += m * (ry² + rz²)
   Iyy += m * (rx² + rz²)
   Izz += m * (rx² + ry²)
   ```

5. **惯性积**：

   ```text
   Ixy = -m * rx * ry
   Ixz = -m * rx * rz
   Iyz = -m * ry * rz
   ```

   其中 `r = box.center() - centerOfMass`，再乘以 `blockScale`。

结果：

- `VoxelColliderData#mass()`：千克；
- `VoxelColliderData#centerOfMass()`：体素坐标（未缩放）；
- `VoxelColliderData#inertia()`：完整 3×3 张量，单位 kg·m²，关于质心；
- `VoxelColliderData#inverseInertia()`：通过 `CrythstMath.invertInertia` 求逆，奇异时返回 `null`。

### 5.3 复合形状以质心为原点

`VoxelColliderData#toCompoundShape()` 会把每个盒相对质心的偏移作为子形状变换：

```java
offset = (box.center() - centerOfMass) * blockScale;
halfExtents = box.size() * blockScale * 0.5;
builder.add(new BoxShape(halfExtents), Transform3d.of(offset));
```

因此：

- 复合碰撞形状的局部原点 = 结构质心；
- 刚体的 Bullet 位置 = 结构姿态变换后的质心位置；
- `PhysicsStructure#pose()` 会把刚体位置减掉旋转后的 `centerOfMassOffset`，还原成“结构局部原点”的姿态；
- 渲染插值使用的是**结构姿态**，不是刚体质心姿态。

`PhysicsStructure` 创建动态刚体时：

```java
builder.mass(collider.mass() * massScale);
```

`massScale` 来自 `StructureDef#massScale()`，默认 `1.0`。静态/运动学结构不设置质量。

> 实现说明：`VoxelColliderData` 计算并暴露完整惯性张量；Bullet 刚体本身由复合碰撞形状推导旋转惯量。二者使用同一组合并盒和质心，因此旋转行为一致；如果你需要自定义惯量或做受力分析，直接读取 `collider.inertia()`。

---

## 6. 贪心体素合并

`VoxelColliderBuilder#build(VoxelVolume, VoxelColliderOptions)` 的目标是：**用尽量少的长方体覆盖所有实心体素，同时保持轴对齐碰撞表面不变**。

### 6.1 扫描顺序

外层循环：

```text
for y in [minY, maxY)
  for z in [minZ, maxZ)
    for x in [minX, maxX)
```

遇到未访问的实心体素时：

1. 沿 **+X** 扩展，直到不匹配或越界；
2. 在已扩展的 X 宽度上，沿 **+Z** 扩展；
3. 在已扩展的 X×Z 底面上，沿 **+Y** 扩展；
4. 把整个 `extendX × extendY × extendZ` 区域标记为 visited；
5. 生成一个 `VoxelBox(x, y, z, extendX, extendY, extendZ, material, blockMass)`。

### 6.2 匹配条件

`matches(...)` 要求：

- 目标体素是实心；
- 目标体素未被访问；
- 如果 `mergeDifferentMaterials == false`，目标体素的 `blockMass` 和 `PhysicsMaterial` 必须与起始体素完全相同；
- 如果 `mergeDifferentMaterials == true`，只要求实心且未访问，合并后使用**起始体素**的材质和质量。

### 6.3 选项

`VoxelColliderOptions` 是 record：

| 字段 | 默认值 | 作用 |
| --- | --- | --- |
| `greedyMerge` | `true` | 关闭后每个实心体素生成一个 1×1×1 盒 |
| `mergeDifferentMaterials` | `false` | 允许跨材质/质量合并 |
| `blockScale` | `1.0` | 每个体素的米数 |
| `maxCells` | `4_000_000` | 体素总数安全上限，超过直接抛异常 |

便捷方法：

```java
VoxelColliderOptions.DEFAULT
        .withBlockScale(0.5)
        .withGreedyMerge(true)
        .withMergedMaterials(false);
```

### 6.4 复杂度与限制

- 时间复杂度与体素总数和合并盒数量相关；最坏情况近似 O(cells)，但每个盒的扩展会重复检查边界；
- `visited` 数组大小为 `bounds.volume()`，`maxCells` 防止内存爆炸；
- 合并只沿轴对齐方向进行，不会生成旋转盒；
- 合并后的复合形状仍然由轴对齐 `BoxShape` 组成，碰撞表面与逐体素盒一致（对于完整的 1×1×1 方块而言）。

---

## 7. OBB 与 15 轴 SAT

### 7.1 OBB 表示

`OrientedBox` 是 record：

```java
record OrientedBox(Vector3d center, Vector3d halfExtents, Quaterniond rotation)
```

- `center`：世界空间中心；
- `halfExtents`：三个半尺寸；
- `rotation`：单位四元数。

构造时会校验半尺寸为正、归一化四元数。常用构造：

```java
OrientedBox.fromAabb(aabb);                       // 轴对齐
OrientedBox.fromAabb(localBounds, transform);     // 本地 AABB + 刚体变换
localBounds.toOrientedBox(transform);             // Aabb 的便捷方法
```

`BodyHandle#obb()` 的默认实现就是：

```java
OrientedBox.fromAabb(shape().localBounds(), transform())
```

这与 `BodyHandle#aabb()` 不同：`aabb()` 来自 Bullet 的轴对齐包围盒，结构倾斜时会被膨胀；`obb()` 是紧致的、带旋转的包围盒。

### 7.2 15 轴 SAT

`OrientedBox#intersects(OrientedBox)` 使用标准 OBB SAT，检查 15 个潜在分离轴：

1. A 的 3 个面法线（A 的局部轴）；
2. B 的 3 个面法线（B 的局部轴）；
3. A 的每个轴与 B 的每个轴的 9 个叉积轴。

对每个轴，把两个盒投影到轴上；如果存在一个轴上的投影区间不重叠，则两盒分离。

实现细节：

- 使用 `EPSILON = 1e-9` 给 `absR` 加偏置，避免浮点误差导致错误分离；
- 9 个叉积轴用循环下标 `i1=(i+1)%3`、`i2=(i+2)%3` 计算；
- 退化情况（盒轴平行导致叉积接近零）由 epsilon 和标准 SAT 公式覆盖，不额外做特殊分支。

`intersects(Aabb)` 会把 AABB 转成 OBB 后复用同一套测试。

### 7.3 世界查询

`PhysicsWorld#query(OrientedBox box, BodyFilter filter)` 在 Bullet 实现中：

```java
for (BulletBodyHandle body : bodies.values()) {
    if (filter.test(body) && body.obb().intersects(box)) {
        result.add(body);
    }
}
```

- 这是 **O(n)** 的精确后过滤，不是 broadphase 加速查询；
- `BodyFilter.ALL` 是默认过滤器；
- `BodyFilter.excluding(body)` 用 id 排除某个刚体；
- 对大量刚体频繁查询时，建议先用自己的空间索引缩小候选集，或缓存结果。

### 7.4 射线与扫掠

- `RayQuery` 是线段查询（`from` → `to`），不是无限射线；`fromDirection(origin, direction, maxDistance)` 会归一化方向并构造端点；
- Bullet 返回的结果会按 `fraction` 升序排序；
- `SweepQuery` 是凸形状扫掠；`tolerance` 小于等于 0 时回退到 `0.01`；
- 两者都支持 `BodyFilter`；
- 命中结果中的 `fraction` 在 `[0, 1]`，`distance = 总长度 * fraction`。

---

## 8. 任意方向重力

### 8.1 世界重力

`WorldSettings` 的 `gravity` 是普通 `Vector3d`，没有任何“必须朝 -Y”的假设：

```java
WorldSettings.defaults().withGravity(new Vector3d(9.81, 0.0, 0.0));
WorldSettings.withGravity(new Vector3d(0.0, -1.0, 0.0), 3.71);
```

`WorldSettings#up()` 的定义：

```java
if (gravity.lengthSquared() < 1e-12) {
    return new Vector3d(0.0, 1.0, 0.0); // 零重力回退
}
return gravity.negate().normalize();
```

`PhysicsWorld` 也暴露 `gravity()`、`up()`、`gravityMagnitude()`。

运行时修改：

```java
world.setGravity(new Vector3d(0.0, 0.0, -9.81));
```

`BulletPhysicsWorld#setGravity` 会：

1. 更新 `WorldSettings`；
2. 调用 `space.setGravity(...)`；
3. 对所有 **DYNAMIC** 刚体重新 `applyGravity()` 并唤醒。

### 8.2 每刚体覆盖与缩放

`BodyDef` 和 `BodyHandle` 支持：

- `gravityScale(double)` / `setGravityScale(double)`：乘以世界重力；
- `gravityOverride(Vector3dc)` / `setGravityOverride(Vector3dc)`：直接使用该向量，忽略世界重力和 `gravityScale`；
- `clearGravityOverride()`：恢复世界重力 × scale。

`BulletBodyHandle#applyGravity()` 的逻辑：

```java
if (type != DYNAMIC) {
    body.setGravity(0, 0, 0);
} else if (gravityOverride != null) {
    body.setGravity(gravityOverride);
} else {
    body.setGravity(worldGravity * gravityScale);
}
```

注意：

- 在 `createBody` 中，`applyGravity()` 是在刚体加入 `PhysicsSpace` **之后**调用的，因为 Bullet 在加入空间时会用空间重力覆盖每刚体重力；
- `setGravityScale`、`setGravityOverride`、`clearGravityOverride` 都会立即重新应用；
- `setType` 切换到 `STATIC` / `KINEMATIC` 时会把重力清零，切回 `DYNAMIC` 时重新应用。

### 8.3 角色

`CharacterDef` 可以选择显式 `up`；不设置时使用 `world.up()`。`BulletCharacterController` 会把角色的重力设置为 `world.gravity() * def.gravityScale()`，并调用 Bullet 的 `setUp`。因此角色控制器同样支持侧向或倒置重力。

---

## 9. 世界地形碰撞

`LevelPhysics` 持有一个 `TerrainCollision`：

- 收集所有非地形刚体的 AABB，按当前速度和 `worldCollisionLookahead` 扩展运动预取范围；
- 以 16×16×16 section 为键懒加载；每个 section 使用方块的真实碰撞形状（楼梯、台阶、栅栏等保留精确盒）并贪心合并成静态复合刚体；
- 刚体进入新 section 时立即补齐；`worldCollisionRefreshTicks` 是校验/重试上限；
- 地形刚体使用 `TerrainBody` 作为 userData，调试渲染会跳过它们；
- `LevelChunkTerrainMixin` 在方块变化后使对应 section（以及跨 section 边界的相邻 section）失效，先构建替换体再移除旧体，避免一 tick 空洞；
- 缓存上限由 `worldCollisionMaxSections` 控制，`worldCollisionRadius` 仍作为基本范围。

实现细节、软上限和当前限制以 [terrain-collision.md](terrain-collision.md) 为准（该文档由地形碰撞负责人维护）。

## 10. 玩家重力方向

玩家重力使用一个极小的 Mixin：

- `LivingEntityTravelMixin` 在 `LivingEntity#travelInAir` 返回时注入；
- 默认没有任何覆盖，Mixin 直接返回，原版重力行为不变；
- `PlayerGravity` 以玩家 UUID 保存 `FIXED` 或 `LOOK` 模式；`LOOK` 在启用时采样一次视线方向并冻结，避免相机姿态反过来跟随鼠标形成反馈回路；
- 有覆盖时，把 `(direction - (0,-1,0)) * gravity` 叠加到速度上：抵消原版向下的 Y 分量，再加入目标方向的 X/Y/Z 加速度；
- 单人游戏中服务端 `ServerPlayer` 与客户端 `LocalPlayer` 共享同一 UUID 和静态表，所以客户端预测方向一致；
- 专用服务器目前没有把方向同步给客户端，需要后续网络层。

默认方向始终是原版向下 `(0,-1,0)`；`PlayerGravity.reset` 可移除覆盖。

## 11. 结构生命周期

### 11.1 创建

```java
VoxelColliderData collider = VoxelColliderBuilder.build(volume, options);
PhysicsStructure structure = new PhysicsStructure(world, definition);
```

`PhysicsStructure` 构造函数依次做：

1. 保存 `VoxelVolume`、`VoxelColliderOptions`、`blockScale`、`massScale`；
2. 调用 `VoxelColliderBuilder.build` 生成合并盒、质量、质心、惯性；
3. 如果 `collider.mass() <= 0`，抛 `IllegalArgumentException("Structure volume contains no solid blocks")`；
4. 计算 `centerOfMassOffset = collider.centerOfMass() * blockScale`；
5. 计算刚体变换：`bodyTransform = pose.transformPoint(centerOfMassOffset)`，旋转不变；
6. 用 `collider.toCompoundShape()` 创建 `BodyDef`：
   - 类型来自 `StructureDef#type()`；
   - 材质、过滤器、阻尼、CCD、userData 来自 `StructureDef`；
   - 动态刚体质量 = `collider.mass() * massScale`；
7. `world.createBody(...)`；
8. `body.setUserData(this)`，让接触/调试代码可以从刚体找回结构；
9. `renderPose.reset(pose())` 与 `renderTimeline.reset(pose(), now)` 初始化两个渲染缓冲。

### 11.2 运行时姿态

- `pose()`：当前结构姿态（结构局部原点，不是质心）；
- `setPose(Transform3d)`：设置结构姿态并唤醒刚体，同时 `reset` 旧插值缓冲和 `PoseTimeline`（直接设置视为不连续）；
- `teleport(Transform3d)`：设置姿态、清速度、清力、唤醒，并 `reset` 两个渲染缓冲（不插值跨越传送）；
- `syncRenderPose()`：把当前 `pose()` 连同 `System.nanoTime()` 推入 `PoseTimeline`，并同步推入旧 `InterpolatedPose`；每个服务端 tick 调用一次（不是每个固定 substep）；
- `interpolatedPoseAt(nowNanos)` / `renderStateAt(nowNanos)`：按帧时间采样 timestamped 时间线；
- `interpolatedPose(alpha)` / `renderState(alpha)`：旧 API；`renderState(alpha)` 现在忽略 `alpha` 并调用 `renderStateAt(System.nanoTime())`。

`StructureRenderState` 的所有重字段都共享不可变数据；它只把姿态采样出来，因此可以安全地跨线程交给渲染器。

### 11.3 方块编辑与重建

```java
structure.reindex(newVolume);
```

`reindex` 会：

1. 记录旧的刚体世界姿态；
2. 由旧姿态和旧质心偏移反推结构世界位置；
3. 重新构建 `VoxelColliderData`；
4. 替换 `collider` 和 `centerOfMassOffset`；
5. 调用 `body.setShape(newCollider.toCompoundShape(), newMass)`（动态时使用新质量，非动态传 0）；
6. 根据新质心偏移重新计算刚体世界位置，保持结构世界姿态不变；
7. 唤醒刚体并 `syncRenderPose()`。

如果新体积没有实心方块，会抛 `IllegalArgumentException`，旧 collider 不会被替换。

### 11.4 关闭

- `PhysicsStructure#close()` 幂等，调用 `world.removeBody(body)`；
- `PhysicsStructure#closed()` 返回是否已关闭；
- 如果结构由 `LevelPhysics` 管理，**应使用 `LevelPhysics#removeStructure(structure)`**。它会先从 `structures` 列表移除、刷新 `renderStructures`，再调用 `structure.close()`；直接调用 `close()` 不会从 `LevelPhysics` 的列表里移除引用。
- `LevelPhysics#close()` 会关闭该维度所有结构并关闭 `PhysicsWorld`；
- `MinecraftPhysics#unload(ServerLevel)` 在维度卸载时移除并关闭对应 `LevelPhysics`；
- `MinecraftPhysics#closeAll()` 在服务器停止时关闭所有维度。

### 11.5 类型与材质

- `structure.setKinematic(true/false)` 在 `KINEMATIC` 和 `DYNAMIC` 之间切换；
- `structure.setMaterial(material)` 更新摩擦/弹性/滚动阻力等；
- `structure.body()` 可以直接访问底层 `BodyHandle`，用于施加力/冲量、设置重力覆盖、修改线性/角因子等；
- `structure.applyCentralImpulse(...)` 和 `applyImpulseAt(...)` 是常用快捷方法。

---

## 12. 选择、碰撞与调试渲染

### 12.1 服务端：姿态与调试快照

`LevelPhysics.tick()` 每个服务端 tick 推进物理，然后：

```java
for (PhysicsStructure structure : structures) {
    structure.syncRenderPose();               // PoseTimeline + legacy InterpolatedPose
}
renderDebugSnapshot = world.debugSnapshot();  // 不可变快照，每 tick 发布
timing.record(...);
```

`BulletPhysicsWorld#debugSnapshot()` 返回 `PhysicsDebugSnapshot`：

| 字段 | 内容 |
| --- | --- |
| `backend` / `backendVersion` | 后端名称和版本 |
| `origin` | 当前物理原点 |
| `gravity` / `up` | 世界重力与上方向 |
| `stats` | 最近一步的 `StepStats` |
| `bodies` | 每个刚体的 `BodyDebugInfo`（id、类型、pose、OBB、AABB、active、terrain） |
| `contacts` | 最近一步保留的 `ContactEvent` 列表；仅在显式 `setDebugContactCapture(true)` 时填充 |

快照与结构列表都通过 `volatile` 字段发布：

```java
private volatile List<PhysicsStructure> renderStructures = List.of();
private volatile PhysicsDebugSnapshot renderDebugSnapshot;
```

注意：当前集成没有自动调用 `setDebugContactCapture(true)`，所以快照的 `contacts` 通常为空。`crythst:physics_contacts` 层仍会绘制物理原点、重力/上方向轴，但一般没有接触点，除非调用方显式开启了采集。

### 12.2 选择与瞄准

完整命中字段、合并规则和点击路径见 [selection-targeting.md](selection-targeting.md)。

客户端选择只有一条路径：

```
Minecraft.pick / 原版 outline / 交互
        │
        ▼
ClientLevel.clip(ClipContext)            // ClientLevelClipMixin
        │
        ▼
StructureSelection.clip(...)             // 原版 clip + 最近结构 hit 合并
        │  ClientStructureSelection.views()
        │    ├─ 单人：ClientPhysicsView 的 PhysicsStructure
        │    └─ 多人：ClientStructureCache 快照 + PoseTimeline
        ▼
StructureBlockHitResult extends BlockHitResult
```

`StructureBlockHitResult` 同时携带 body id、结构姿态、结构局部体素 x/y/z、局部/世界命中面、`blockScale`、`BlockState`、世界命中点和相邻放置体素。真实方块/流体命中更近时始终优先，结构只在与原版命中比较时严格更近才胜出（`1e-4` 浮点容差）。

因此 `Minecraft.hitResult`、原版轮廓提取、挖掘/放置/拾取都读取同一个命中，不再有第二套结构射线。服务端在 `StructureInteraction.raycast(player)` 中从服务端眼睛和 `PlayerGravity.lookDirection(player)` 重新做权威射线，并用真实方块 clip 做遮挡检查。

轮廓由 `StructureSelectionOutline` 发布 `BlockOutlineRenderState`，`StructureBlockOutlineMixin` 识别同一对象后把结构 pose 和 `blockScale` 应用到原版 `submitHitOutline`。任意旋转/缩放下局部 shape 的顶点会随 pose 精确变换；线型、颜色和屏幕空间线宽保持原版。

### 12.3 实体碰撞

结构碰撞不再有 post-tick 管理器和接触传送：

```
Entity.move(MoverType, Vec3)
        │
        ▼
EntityStructureCollisionMixin (Redirect)
        │
        ▼
EntityStructureCollision.resolveMovement(...)
        │  StructureCollisionSources.forEntity(entity)
        │    ├─ 服务端/集成服务器：LevelPhysics.structures()
        │    ├─ 单人客户端：ClientPhysicsView + renderStateAt(now + delay)
        │    └─ 多人客户端：ClientStructureCache 最新同步姿态
        ▼
StructureMotionSolver.solve(...)
        │  宽阶段 AABB → 子步进 → MTV 多 pass → 支撑/台阶 → 速度投影
        ▼
vanilla Entity.collide(structure-clear motion)
        │
        ▼
vanilla Entity.move 执行最终移动、台阶、落地和碰撞标记
```

结果：

- 实体被结构挡住、沿墙滑动、落在结构上，并与原版 `onGround` / 碰撞标记一致；
- 不发送 `ServerPlayer.connection.teleport`，不再逐 tick 传送玩家；
- 服务端和客户端运行同一求解器，客户端姿态来自本地时间线或网络快照；
- 结构从不被实体推动，求解器只读结构姿态和碰撞盒。

移动平台搭乘由 `EntityStructureRidingManager` 在物理 tick 之后处理：玩家继承平台每 tick 位移到速度，非玩家实体使用 `setPos`。详见 [entity-physics-collision.md](entity-physics-collision.md) 和 [entity-structure-riding.md](entity-structure-riding.md)。

### 12.4 调试渲染：F3 + F6

每个条目的绘制内容和限制见 [debug-rendering.md](debug-rendering.md)。

`DebugScreenEntriesMixin` 把 Crythst 条目注册进原版调试条目表。所有 `physics*` 条目都是 `RENDERER` 类别且不加入任何默认 profile，因此默认 `NEVER`；`crythst:physics` 是总开关，打开后启用下面全部层。

| 条目 | 默认 | 绘制内容 |
| --- | --- | --- |
| `crythst:physics` | NEVER | 总开关，启用所有物理层 |
| `crythst:physics_colliders` | NEVER | 每个结构的合并 `VoxelBox`（按材质着色）和自定义 fractional collider parts |
| `crythst:physics_bounds` | NEVER | 结构整体 OBB、世界 AABB 和 body id/active 标签 |
| `crythst:physics_dynamics` | NEVER | 质心、结构原点、原点→质心连线、结构局部前/上/右轴 |
| `crythst:physics_contacts` | NEVER | 物理原点、重力/上方向箭头、接触点/法线（若已开启采集） |
| `crythst:physics_bodies` | NEVER | 非结构、非地形、非静态的 loose body OBB |
| `crythst:structure_target` | IN_OVERLAY | F3 文本：命中的 body id、结构局部体素、方块状态、结构姿态/缩放、世界命中和面 |

`DebugRendererMixin` 只在 `crythst:physics` 条目启用时把 `CrythstPhysicsDebugRenderer` 加入原版 debug renderer 列表；它再按各子条目调用 `PhysicsGizmoRenderer.emitColliders/emitBounds/emitDynamics/emitContacts` 和 `LooseBodyRenderer.emit`。所有姿态通过 `renderStateAt(frameNanos)` 采样同一个 `PoseTimeline`。

### 12.5 单人/多人边界

`ClientPhysicsView` 只在 `Minecraft#getSingleplayerServer()` 非空时读取本地 `LevelPhysics`：

- 单人：结构渲染、选择、碰撞和 `crythst:physics*` 调试层都可以读取同一进程内的服务端快照；
- 多人：结构渲染、选择、实体碰撞读取 `ClientStructureCache` 网络快照；`crythst:physics*` 调试层没有本地物理世界，因此不绘制；
- `crythst:structure_target` 是纯客户端文本，多人只要同步了结构命中也能显示。

### 12.6 调试命令

- `/crythst timing`：固定步长、步数、抖动、姿态 delta，并在客户端类可用时追加 `BatchedStructureRenderer.poseDiagnosticsText()`；
- `/crythst align`：打印 body = structure + R*COM、碰撞 = body*(voxel-COM)、渲染 = structure*voxel 的一致性检查；
- `/crythst entitydebug`：只读的实体/结构碰撞诊断；
- `/crythst debug`：翻转历史 `CrythstConfig#debug` 字段。当前 F3+F6 调试层不受它控制，`config.debug` 也不参与快照生成。

---

## 13. 性能与扩展点

- **Broadphase**：`WorldSettings#broadphase()` 支持 `DBVT`（默认）、`AXIS_SWEEP_3`、`SIMPLE`；
- **求解器迭代**：`solverIterations` 默认 10；
- **确定性**：`deterministic == true` 时启用 Bullet 的确定性调度并强制更新 AABB；
- **休眠**：`allowSleep` / `BodyDef#allowSleep`；
- **CCD**：`BodyDef#ccd` / `ccdRadius`，结构也可通过 `StructureDef#ccd` 开启；
- **接触监听**：只有注册了 `ContactListener` 或启用 `setDebugContactCapture(true)` 时才会请求 Bullet 收集 manifold；
- **结构重建**：`reindex` 是 O(体素总数) 的完整重建，不适合每 tick 调用；频繁小改动可以批量合并后再重建；
- **查询**：OBB 查询是 O(n) 后过滤；大量查询时请自行维护空间索引；
- **原生库**：所有平台原生库都打进 jar，首次加载会解压到临时目录；详见 [platforms.md](platforms.md)。
