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
io.mikaple.crythst.integration.fabric（服务端入口、命令、客户端 gizmo）
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
| 结构姿态 | `InterpolatedPose` 内部的 `volatile Snapshot(previous, current)` | `PhysicsStructure#interpolatedPose` / `renderState` |
| 结构列表 | `LevelPhysics#renderStructures()` 返回 `volatile List<PhysicsStructure>` | `ClientPhysicsView` |
| 结构碰撞数据 | `VoxelColliderData` 不可变对象，字段 `volatile` 替换 | `StructureRenderState#collider()` |
| 调试快照 | `LevelPhysics#renderDebugSnapshot()` 的 `volatile` 字段 | `ClientPhysicsView#debug()` |
| 调试接触点 | `PhysicsDebugSnapshot#contacts()` 中的不可变 `ContactEvent` 列表 | `PhysicsGizmoRenderer` |
| 刚体快照 | `BodySnapshot` 不可变 record | 命令、调试、网络层 |

`InterpolatedPose` 用一条 volatile 引用交换整个 `Snapshot` record，避免“读到一半”的撕裂问题：

```java
private record Snapshot(Transform3d previous, Transform3d current) {}
private volatile Snapshot snapshot = new Snapshot(Transform3d.IDENTITY, Transform3d.IDENTITY);
```

服务端每次 `push(pose)` 产生新的不可变 record；客户端 `sample(alpha)` 只读取局部引用，然后做位置 `lerp` 和旋转 `slerp`。

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
steps = min((int) (accumulator / fixedTimeStep), maxSubSteps);
accumulator -= steps * fixedTimeStep;
```

特点：

- `maxSubSteps` 防止“死亡螺旋”：一帧掉到很慢时不会无限补步；
- 超出 `maxSubSteps` 的时间被丢弃；
- `alpha() = accumulator / fixedTimeStep`，用于渲染插值；
- `alpha` 不会达到 1.0；如果累加器仍然大于等于一个步长，会被压到 `0.999 * fixedTimeStep`；
- `reset()` 清空累加器，用于暂停/恢复。

### 3.2 世界层

`BulletPhysicsWorld` 在构造时创建 `FixedStepClock(settings.fixedTimeStep(), settings.maxSubSteps())`。

- `world.step(fixedTimeStep)`：**强制运行恰好一个固定步**，不受时钟累加器影响；适合确定性测试和 `/crythst step`；
- `world.advance(realSeconds)`：先让时钟计算步数，再循环调用 `step(settings.fixedTimeStep())`，返回实际步数；
- `world.interpolationAlpha()`：直接返回时钟的 alpha；
- `world.setPaused(true)` 会重置时钟，恢复后不会补跑暂停期间的时间。

### 3.3 Minecraft 层

`LevelPhysics.tick()`：

```java
long now = System.nanoTime();
double delta = (now - lastTickNanos) / 1e9;
lastTickNanos = now;
delta = Math.min(delta, config.maxFrameSeconds); // 默认 0.25s
world.advance(delta);
for (PhysicsStructure structure : structures) {
    structure.syncRenderPose();
}
world.setDebugContactCapture(config.debug);
if (config.debug) {
    renderDebugSnapshot = world.debugSnapshot();
}
```

- 用墙钟时间而不是固定 1/20 秒，因此物理步进频率与 Minecraft tick 率解耦；
- `maxFrameSeconds` 限制单次追赶的最大真实时间；
- 即使这一 tick 没有运行物理步，也会 `syncRenderPose()`，让插值缓冲推进到当前姿态（前后帧相同）；
- `setDebugContactCapture(config.debug)` 决定是否在调试快照中保留最近一步的接触流形；关闭时不付 manifold 提取成本；
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

- 收集所有非地形刚体的 AABB，加 `worldCollisionRadius`，并沿重力方向增加 `worldCollisionLookahead` 的预读范围；
- 以 16×16×16 section 为键懒加载；每个 section 用 `VoxelColliderBuilder` 合并成静态复合刚体；
- 地形刚体使用 `TerrainBody` 作为 userData，调试渲染会跳过它们；
- `LevelChunkTerrainMixin` 在 `LevelChunk#setBlockState` 返回后使对应 section 失效，下次刷新重建；
- 缓存上限由 `worldCollisionMaxSections` 控制，刷新间隔由 `worldCollisionRefreshTicks` 控制；
- 当前把所有带碰撞形状的方块近似为整格，楼梯/台阶/栅栏等不是精确形状。

## 9. 玩家重力方向

玩家重力使用一个极小的 Mixin：

- `LivingEntityTravelMixin` 在 `LivingEntity#travelInAir` 返回时注入；
- 默认没有任何覆盖，Mixin 直接返回，原版重力行为不变；
- `PlayerGravity` 以玩家 UUID 保存 `FIXED` 或 `LOOK` 模式；
- 有覆盖时，把 `(direction - (0,-1,0)) * gravity` 叠加到速度上：抵消原版向下的 Y 分量，再加入目标方向的 X/Y/Z 加速度；
- 单人游戏中服务端 `ServerPlayer` 与客户端 `LocalPlayer` 共享同一 UUID 和静态表，所以客户端预测方向一致；
- 专用服务器目前没有把方向同步给客户端，需要后续网络层。

默认方向始终是原版向下 `(0,-1,0)`；`PlayerGravity.reset` 可移除覆盖。

## 9. 结构生命周期

### 9.1 创建

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
9. `renderPose.reset(pose())` 初始化插值缓冲。

### 9.2 运行时姿态

- `pose()`：当前结构姿态（结构局部原点，不是质心）；
- `setPose(Transform3d)`：设置结构姿态并唤醒刚体，同时把姿态 `push` 到渲染缓冲；
- `teleport(Transform3d)`：设置姿态、清速度、清力、唤醒，并 `reset` 渲染缓冲（不插值跨越传送）；
- `syncRenderPose()`：把当前 `pose()` 推入 `InterpolatedPose`；每固定步或每服务端 tick 调用；
- `interpolatedPose(alpha)`：取插值姿态；
- `renderState(alpha)`：返回不可变的 `StructureRenderState`，包含 body id、插值姿态、collider、质心偏移、blockScale。

`StructureRenderState` 的所有重字段都共享不可变数据；它只把姿态采样出来，因此可以安全地跨线程交给渲染器。

### 9.3 方块编辑与重建

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

### 9.4 关闭

- `PhysicsStructure#close()` 幂等，调用 `world.removeBody(body)`；
- `PhysicsStructure#closed()` 返回是否已关闭；
- 如果结构由 `LevelPhysics` 管理，**应使用 `LevelPhysics#removeStructure(structure)`**。它会先从 `structures` 列表移除、刷新 `renderStructures`，再调用 `structure.close()`；直接调用 `close()` 不会从 `LevelPhysics` 的列表里移除引用。
- `LevelPhysics#close()` 会关闭该维度所有结构并关闭 `PhysicsWorld`；
- `MinecraftPhysics#unload(ServerLevel)` 在维度卸载时移除并关闭对应 `LevelPhysics`；
- `MinecraftPhysics#closeAll()` 在服务器停止时关闭所有维度。

### 9.5 类型与材质

- `structure.setKinematic(true/false)` 在 `KINEMATIC` 和 `DYNAMIC` 之间切换；
- `structure.setMaterial(material)` 更新摩擦/弹性/滚动阻力等；
- `structure.body()` 可以直接访问底层 `BodyHandle`，用于施加力/冲量、设置重力覆盖、修改线性/角因子等；
- `structure.applyCentralImpulse(...)` 和 `applyImpulseAt(...)` 是常用快捷方法。

---

## 10. 调试渲染路径

### 10.1 服务端：生成快照

`CrythstConfig#debug` 为 `true` 时，`LevelPhysics` 会在推进物理前后做两件事：

```java
world.setDebugContactCapture(true);              // 保留最近一步的接触流形
renderDebugSnapshot = world.debugSnapshot();     // 生成不可变快照
```

`BulletPhysicsWorld#debugSnapshot()` 返回 `PhysicsDebugSnapshot`：

| 字段 | 内容 |
| --- | --- |
| `backend` / `backendVersion` | 后端名称和版本 |
| `origin` | 当前物理原点 |
| `gravity` / `up` | 世界重力与上方向 |
| `stats` | 最近一步的 `StepStats` |
| `bodies` | 每个刚体的 `BodyDebugInfo`（id、类型、pose、OBB、AABB、active） |
| `contacts` | 最近一步保留的 `ContactEvent` 列表；仅在 `setDebugContactCapture(true)` 时填充 |

快照与结构列表都通过 `volatile` 字段发布：

```java
private volatile List<PhysicsStructure> renderStructures = List.of();
private volatile PhysicsDebugSnapshot renderDebugSnapshot;
```

### 10.2 客户端：拉取 + gizmo 绘制

客户端入口是 `io.mikaple.crythst.integration.fabric.client.CrythstClient`，在 `fabric.mod.json` 的 `client` entrypoint 中注册：

```java
ClientTickEvents.END_CLIENT_TICK.register(ClientPhysicsView::tick);
LevelRenderEvents.BEFORE_GIZMOS.register(PhysicsGizmoRenderer::render);
```

数据流：

```
ClientTickEvents.END_CLIENT_TICK
        │
        ▼
ClientPhysicsView.tick(Minecraft)
        │  client.getSingleplayerServer()
        │  server.getLevel(client.level.dimension())
        │  MinecraftPhysics.getIfPresent(level)
        ▼
STRUCTURES = physics.renderStructures();
DEBUG      = physics.renderDebugSnapshot();
        │
        ▼
LevelRenderEvents.BEFORE_GIZMOS
        │
        ▼
PhysicsGizmoRenderer.render(LevelRenderContext)
        │  alpha = Minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)
        │  structure.renderState(alpha)
        ▼
Gizmos / GizmoStyle
```

`PhysicsGizmoRenderer` 绘制：

- 每个结构的每个合并 `VoxelBox`：按 `blockScale` 和 `centerOfMassOffset` 还原到结构局部米制坐标，再用插值姿态变换成世界空间 OBB；
- 结构整体 OBB（青色）；
- 结构质心（黄色点）；
- 非结构刚体的 OBB 和 AABB；
- 物理原点（橙色点）、`up` 方向（绿色箭头）、重力方向（品红箭头）；
- 调试接触点（红色点）和接触法线（橙色箭头），来自 `PhysicsDebugSnapshot#contacts()`。

使用的 vanilla API：

- `net.minecraft.gizmos.Gizmos`：`point`、`arrow`、`line`、`rect`、`cuboid`；
- `net.minecraft.gizmos.GizmoStyle`：`stroke(...)`、`fill(...)`；
- `net.minecraft.world.phys.AABB` / `Vec3` 作为渲染端类型。

### 10.3 为什么只支持单人

`ClientPhysicsView` 只在 `Minecraft#getSingleplayerServer()` 非空时读取数据：

- 单人游戏中，客户端和集成服务器在同一个 JVM，服务端的 `MinecraftPhysics.LEVELS` 可以直接访问；
- 多人游戏中，客户端没有本地 `LevelPhysics`，物理世界在远端服务器；当前还没有网络同步层，因此 `ClientPhysicsView` 会清空缓存，`PhysicsGizmoRenderer` 不绘制任何东西。

这也是路线图中“多人同步”要解决的问题。

### 10.4 调试开关

- `/crythst debug` 切换 `CrythstFabric.config().debug`；
- 关闭时 `PhysicsGizmoRenderer.render` 直接返回；
- `LevelPhysics` 只在 `debug == true` 时更新 `renderDebugSnapshot`；
- 该开关是运行时状态，不会自动写回 `config/crythst.json`。

---

## 11. 性能与扩展点

- **Broadphase**：`WorldSettings#broadphase()` 支持 `DBVT`（默认）、`AXIS_SWEEP_3`、`SIMPLE`；
- **求解器迭代**：`solverIterations` 默认 10；
- **确定性**：`deterministic == true` 时启用 Bullet 的确定性调度并强制更新 AABB；
- **休眠**：`allowSleep` / `BodyDef#allowSleep`；
- **CCD**：`BodyDef#ccd` / `ccdRadius`，结构也可通过 `StructureDef#ccd` 开启；
- **接触监听**：只有注册了 `ContactListener` 或启用 `setDebugContactCapture(true)` 时才会请求 Bullet 收集 manifold；
- **结构重建**：`reindex` 是 O(体素总数) 的完整重建，不适合每 tick 调用；频繁小改动可以批量合并后再重建；
- **查询**：OBB 查询是 O(n) 后过滤；大量查询时请自行维护空间索引；
- **原生库**：所有平台原生库都打进 jar，首次加载会解压到临时目录；详见 [platforms.md](platforms.md)。
