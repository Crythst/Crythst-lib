# 浮力（Buoyancy）

本文说明 Crythst 的水/流体浮力实现：注册方式、力模型、水线采样、末地石密度规则、手动步进注意事项和已知限制。

相关代码：

- `io.mikaple.crythst.integration.minecraft.buoyancy.BuoyancyManager`
- `io.mikaple.crythst.integration.minecraft.buoyancy.BuoyancyMath`
- `src/test/java/io/mikaple/crythst/integration/minecraft/buoyancy/BuoyancyMathTest.java`

---

## 1. 注册与步进顺序

在 Fabric 公共初始化入口中调用一次：

```java
import io.mikaple.crythst.integration.minecraft.buoyancy.BuoyancyManager;

BuoyancyManager.register();
```

`register()` 监听 `ServerTickEvents.START_SERVER_TICK`。每个服务器 tick 开始时，管理器为每个已存在的 `LevelPhysics` 安装一个 `PhysicsWorld` 的 `StepListener`（只安装一次）。之后每次真正执行固定步进（`world.step`）前，监听器都会施加浮力。

> 为什么不是直接在 START tick 施力？Bullet 在每次 `stepSimulation` 末尾调用 `clearForces()`，而 20 TPS 的服务器每 tick 通常执行约 3 个 `1/60 s` 固定步。只在 START tick 施力会让浮力只作用于第一个子步，等效强度仅约 1/3。挂在 per-step 监听器上可以保证每个固定步都有力，同时也不会重复施加。

同一固定步内，浮力对以下对象各施加一次：

- `LevelPhysics.structures()` 中所有 `DYNAMIC` 结构（通过 `PhysicsStructure.body()`）；
- `physics.world().bodies()` 中不属于任何结构的松散动态刚体。

结构刚体同时出现在 `world.bodies()` 里，管理器用 body id 集合去重，不会重复施力。

### 手动 `step()` 的注意事项

浮力由 per-step 监听器施加，因此 `LevelPhysics.step(int)` 以及确定性测试中的手动步进**也会自动获得浮力**，每个固定步恰好一次，不会重复。只有在**没有**调用 `BuoyancyManager.register()` 时，才需要自己调用：

```java
BuoyancyManager.apply(levelPhysics);
levelPhysics.step(1);
```

如果已经 `register()`，再手动调用 `apply(LevelPhysics)` 会给下一步额外叠加一次力，请避免。

暂停或关闭的物理世界会被跳过；`buoyancyEnabled = false` 时管理器不施加任何力。

---

## 2. 力模型

对每个受浮力影响的动态刚体：

```
submergedVolume = solidVolume * submergedFraction
displacedMass   = waterDensity * submergedVolume
netLift         = max(0, (displacedMass - bodyMass)) * gravityMagnitude
buoyantForce    = displacedMass * gravityMagnitude
```

- 当 `netLift == 0`（物体比水重，或浸没量不足以抵消体重）时，不施加浮力，刚体按正常重力下沉。
- 当 `netLift > 0` 时，施加 `world.up() * buoyantForce`。
- Bullet 自己会施加重力 `bodyMass * g`，所以**不能**把 `netLift` 再作为额外力施加，否则体重会被减两次。施加完整的 `displacedMass * g` 后，最终净力正好是 `netLift`：

```
buoyantForce - bodyMass * g = netLift
```

例如完全浸没的末地石（`500 kg`、`1 m³`）：`buoyantForce = 9810 N` 向上，重力 `4905 N` 向下，净上举 `4905 N`。

其中：

- `waterDensity` 来自配置（默认 `1000 kg/m³`）；
- `gravityMagnitude` 来自 `PhysicsWorld.gravityMagnitude()`；
- `up` 来自 `PhysicsWorld.up()`，即世界重力的反方向，**不是硬编码的 +Y**。因此任意方向重力（包括侧向重力）都可用。

### `buoyancyDamping` 的语义

`buoyancyDamping`（默认 `0.5`）用作**水的线性粘性阻力系数（kg/s）**：

```
dragCoefficient = buoyancyDamping * displacedMass
dragForce       = -dragCoefficient * linearVelocity
```

不使用它直接乘 `netLift`。原因：末地石为 `500 kg/m³`、水为 `1000 kg/m³`，完全淹没时净上举质量是 `500 kg`；若再乘默认的 `0.5`，上举力只剩一半，仍小于自身重力，末地石会下沉。作为速度阻力使用既能抑制水线附近的振荡，又不改变密度决定的平衡点。

阻力只在物体受到正上举力（即确实在水中）时施加，并会跳过速度接近零的情况。

---

## 3. 浸没比例与水线检测

每个固定步对刚体形状做**离散网格采样**：

- 结构：遍历 `VoxelColliderData.boxes()` 的合并碰撞盒，把每个盒子的中心/尺寸/旋转转换到世界空间，在盒内取 `3 × 3 × 3 = 27` 个采样点；每个盒子的体积乘以该盒的浸没比例后累加。
- 松散刚体：对 `body.shape().localBounds()` 经 `body.transform()` 得到的 OBB 采样，用 `shape.volume()` 作为实体体积；没有解析体积时退回局部 AABB 体积。

每个采样点调用：

```java
BlockPos pos = BlockPos.containing(x, y, z);
if (!level.isLoaded(pos)) return false;          // 不触发区块加载
FluidState fluid = level.getFluidState(pos);
if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) return false;
return y < pos.getY() + fluid.getHeight(level, pos); // 流动水的实际液面
```

实现要点：

- 使用 `FluidTags.WATER`，所以水源、流动水和水logged方块都算水；
- 先 `isLoaded` 再读取流体状态，未加载区块按“干燥”处理，避免物理 tick 中同步生成区块；
- 用 `FluidState.getHeight(...)` 而不是简单地把整个方块高度当作水面，减少流动水/半砖液面的误差。

---

## 4. 末地石与密度

`BlockPhysicsMapper` 已经把末地石映射为 `config.endStoneBlockMass`（默认 `500 kg`）。`BlockVoxelVolume` 构建结构时，每个末地石方块的体积是 1 m³，因此：

```
density = bodyMass / solidVolume
        = 500 / 1
        = 500 kg/m³
```

低于水的 `1000 kg/m³`，完全浸没时净上举为正，所以末地石结构会上浮。浮力代码不包含“末地石”特判；它只读取结构刚体的实际质量和几何体积（松散刚体则读取 `body.mass()` / `shape.volume()`），因此任何低密度方块组合都会以同样规则上浮。

---

## 5. 已知限制

1. **采样近似**：浸没比例来自 27 点/盒的离散采样，水线位置精度约为盒边长的 `1/3`，不是解析的“水与实体相交体积”。大盒子或极薄结构可能有可见误差。
2. **仅水**：当前只识别 `FluidTags.WATER`，不区分水、岩浆、其他模组流体，也没有每流体密度。`waterDensity` 是全局值。
3. **无流体压力/流动推力**：不模拟水流方向、波浪、压力梯度或排水；只施加上举力和线性阻力。
4. **单个上举力作用点**：力施加在质心（`applyCentralForce`），因此浮力不会让结构因浮心与质心不重合而自动扶正/倾覆。
5. **松散刚体的形状近似**：球体、胶囊、凸包等使用其 OBB 采样，边角可能把“盒外但水中”的采样计入浸没体积。
6. **未加载区块视为干燥**，不会为了浮力加载区块。
7. **手动步进**：已 `register()` 时，`LevelPhysics.step()` 每个固定步会自动获得浮力；未注册时才需要手动调用 `apply(LevelPhysics)`。不要在已注册时重复手动调用。
8. **睡眠刚体**：管理器每个固定步仍会检测并施力，并在施力时 `activate()`；完全无水时不会唤醒刚体。
