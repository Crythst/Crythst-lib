# Crythst × Sable 代码来源与许可证风险审计

**审计日期：** 2026-11-22  
**性质：** 工程来源审计与风险隔离建议，**不是法律意见**。  
**目的：** 用可复核的证据判断 Crythst 当前代码与 Sable / sable-port 的相似程度，找出需要重写或声明的地方，降低 MIT 发布时的许可证风险。  
**原则：** 如果发现实质性复制，正确做法是合规署名、满足 PolyForm 条款，或重写；**不建议通过删除引用、隐藏来源来“规避”**，那会放大风险。

---

## 1. 审计快照

| 项目 | 版本 / 提交 | 规模 |
|---|---|---|
| Crythst | `44ddb9a`（2026-11-22，含工作区 WIP） | 370 个主 Java 文件 / 56,305 行；104 个测试文件 / 14,963 行；596 个测试 / 102 个测试类 |
| Sable 上游 | `9b1a3d1`，`2.0.7` | 769 个 Java 文件 / 56,413 行；471 个 Mixin 文件；5,854 行 Rust；0 个测试 |
| sable-port | `113035f`，`3.0.0` | 574 个 Java 文件 / 49,128 行；301 个 Mixin 文件；5,854 行 Rust；0 个测试 |

许可证：

- Crythst：**MIT**，`Copyright (c) 2026 mikaple`
- Sable / sable-port：**PolyForm Shield License 1.0.0**，含 Noncompete 与 Required Notice

Crythst 的 git 历史全部由 `mikaple` 提交，没有 Sable 作者提交或合并记录；首次提交为 2026-11-21。这个事实只能证明仓库历史独立，不能单独证明代码独立，因此下面用代码相似度补充证据。

### 不在本次 Sable 对比范围内、但需要单独确认的第三方

- **EnderTech**：`NOTICE` 声明 `client/render` 下部分 special rendering 改编自 EnderTech，并已由同一版权人重新以 MIT 授权。需要保留 EnderTech 的说明，确认“同一版权人”这一事实有书面依据。
- **Create-Fly**：`build.gradle` 通过 JitPack 使用 `com.github.LyXith:create-fly-fly` 作为 `compileOnly` 依赖，兼容层探测 `com.zurrtum.create` / `com.simibubi.create`。该依赖不进入运行时 jar，但其 API/内部类来源和许可证应另行确认。
- **Minecraft / Fabric / Bullet / JOML**：按各自许可证处理；`THIRD_PARTY_NOTICES.md` 已列出主要项。

---

## 2. 审计方法

对 Crythst 与 Sable / sable-port 做五类对比：

1. **归一化整文件哈希**
   - 去掉注释、`package`、`import`、空白；
   - 对剩余内容做 SHA-256；
   - 检查是否存在完全相同的文件。
2. **Token 5-gram Jaccard**
   - 去掉注释/导入；
   - 提取标识符、数字、字符串、符号；
   - 转成 5-gram 集合；
   - 只统计两边都 ≥200 token 的文件，避免小文件假阳性。
3. **行级相似度**
   - 对归一化代码行做 `SequenceMatcher`；
   - 统计连续 ≥3 行的匹配块，以及最长连续匹配。
4. **注释与字符串**
   - 提取 ≥35 字符的注释行，做精确归一化比对；
   - 提取 ≥8 字符的字符串字面量，做精确比对。
5. **资源与 API 结构**
   - 资源文件 SHA-256；
   - 公共类名 / 方法名 / 字段名交集；
   - 包结构、Mixin 目标类、文档中的显式引用。

---

## 3. 证据一：代码表达层面基本独立（含全部小文件）

本节不再按文件大小过滤：**370 个 Crythst 主 Java 文件全部参与**，包括 record、payload、Mixin accessor、配置类、异常类、13 行的小类。

### 3.1 没有任何完全相同的文件

| 对比 | 归一化后完全相同的文件 |
|---|---:|
| Crythst ↔ Sable 上游 | **0 / 370** |
| Crythst ↔ sable-port | **0 / 370** |

这意味着没有“整文件复制后改包名”的情况，也没有小文件级别的整文件复制。

### 3.2 Token 相似度很低

对两边都 ≥200 token 的文件：

| 对比 | 最高 5-gram Jaccard | 超过 0.05 的文件对 |
|---|---:|---:|
| Crythst ↔ Sable 上游 | **0.060** | 4 |
| Crythst ↔ sable-port | **0.071** | 7 |

最高的几对也只是 0.05–0.07，属于通用 Minecraft/Java 结构导致的背景相似度，不构成“大段复制”的证据。

### 3.3 行级匹配：去掉括号噪声后，最长只有 6 行

原始行级 diff 会把大量 `}` / `);` 算成匹配。例如
`StructureSavedData` 与 `SubLevelPhysicsSystem` 的原始“32 行匹配”全部是闭合括号块。

排除只含括号、分号、极短控制语句的行后：

| 检查 | 结果 |
|---|---:|
| 非平凡代码行最高匹配总数 | **9 行** |
| 非平凡代码最长连续匹配 | **6 行** |
| 小文件（≤120 代码行）最高非平凡匹配 | **3 行** |
| 20 行以上连续复制块 | **0** |

最高的一对仍是：

```text
Crythst api/assembly/StructureGather.java
vs Sable api/SubLevelAssemblyHelper.java
非平凡匹配：9 行（6 + 3）
最长连续：6 行
```

6 行内容是通用包围盒累加：

```java
minX = Math.min(minX, pos.getX());
minY = Math.min(minY, pos.getY());
minZ = Math.min(minZ, pos.getZ());
maxX = Math.max(maxX, pos.getX());
maxY = Math.max(maxY, pos.getY());
maxZ = Math.max(maxZ, pos.getZ());
```

另外 3 行是通用 `for (int x = -1; x <= 1; x++)` 邻域循环。没有发现实质性的方法体复制。

小文件中最高的 3 行匹配也全是通用行：

| 小文件对 | 匹配 | 内容 |
|---|---:|---|
| `StructureLightOccluder` vs Sable `BoundedBitVolume3i` | 3 | `this.minX = minX;` / `minY` / `minZ` |
| `StructureOutlineRenderer` vs sable-port `LevelRendererMixin` | 3 | 两个 `RenderTypes.linesTranslucent...()` 分支 |
| `DebugRendererMixin` vs sable-port `SableClientConfig` | 3 | `Minecraft.getInstance()` + null 检查 |
| `EntityStructureCollisionMixin` vs sable-port `EntityMixin` | 3 | `@Inject(method="move(...)")` 的通用注解行 |

### 3.4 注释和资源没有复制

| 检查 | 结果 |
|---|---:|
| ≥35 字符注释的精确重合 | **0** |
| 资源文件 SHA-256 完全重合 | **0 / 94 对** |
| 唯一 Sable 字符串字面量重合 | 未发现 |
| 字符串重合 | 57 条，但全部是 `config`、`volume`、`physics`、`friction`、`restitution`、`dimension`、`height`、`entity`、`rotation`、`gravity`、`paused`、`create`、`remove`、`INVOKE`、`RETURN` 这类通用词或 Minecraft API 描述 |

### 3.5 物理与原生层完全不同

- Crythst 使用 **Bullet / libbulletjme**，没有 Rust 源码。
- Sable / sable-port 使用 **Rapier / Rust**，约 5,854 行 Rust。
- 两者没有共享 native 代码或物理数据结构。
- Crythst 的 `VoxelColliderBuilder`、`PhysicsWorld`、`BulletPhysicsWorld` 与 Sable 的 Rapier 管线是不同实现。

### 3.6 测试与渲染路线不同

- Crythst：596 个测试全部通过，使用 Bullet 冒烟测试、OBB/体素/同步/持久化/传送门测试。
- Sable：0 个 Java 测试。
- Crythst 批量渲染是 CPU section vertex cache + `submitCustomGeometry`；Sable 是原版 section mesh + `ChunkSectionsToRender` 地形管线。
- 传送门是 Crythst 独有；Sable 只有原版下界门相关的 Mixin，没有自定义传送门系统。

**结论：在“代码表达”层面，证据支持独立实现，未发现大段复制。**

---

## 4. 证据二：API / 类结构存在明显相似

这是本次审计真正需要处理的部分。

### 4.1 同名公共类

Crythst 与 Sable 有 **11 个同名公共类**：

| 类名 | Crythst 位置 | Sable 位置 | 标识符 Jaccard |
|---|---|---|---:|
| `ForceGroup` | `api/physics/force/` | `api/physics/force/` | **0.561** |
| `RigidBodyHandle` | `api/physics/` | `api/physics/handle/` | 0.331 |
| `MassData` | `api/physics/` | `api/physics/mass/` | 0.244 |
| `QueuedForceGroup` | `api/physics/force/` | `api/physics/force/` | 0.247 |
| `PhysicsPipeline` | `api/physics/` | `api/physics/` | 0.232 |
| `FreeConstraintConfiguration` | `api/physics/constraint/` | 同名 | 0.194 |
| `RotaryConstraintConfiguration` | `api/physics/constraint/` | 同名 | 0.185 |
| `FixedConstraintConfiguration` | `api/physics/constraint/` | 同名 | 0.162 |
| `GenericConstraintConfiguration` | `api/physics/constraint/` | 同名 | 0.123 |
| `VoxelColliderData` | `physics/voxel/` | `api/physics/collider/` | 0.098 |
| `DebugRendererMixin` | `mixin/client/` | `mixin/debug_render/` | 0.194 |

另外 sable-port 还有同名 Mixin：

- `EntityHitboxDebugRendererMixin`

这些同名类的实现并不完全相同：

- `MassData`：Crythst 是类，Sable 是接口；
- `VoxelColliderData`：Crythst 是类，Sable 是接口；
- `PhysicsPipeline`：Crythst 是服务端 API 包装类，Sable 是物理管线接口；
- `GenericConstraintConfiguration`：Crythst 是滑轨参数，Sable 是 6-DOF 锁轴配置；
- `DebugRendererMixin`、`EntityHitboxDebugRendererMixin`：目标类相同，但注入策略和功能不同。

**但类名、包名和部分方法术语高度重合，属于中等结构风险。**

### 4.2 `ForceGroup` 是最明显的 API 对齐证据

Sable：

```java
public record ForceGroup(
        @NotNull Component name,
        @Nullable Component description,
        int color,
        boolean defaultDisplayed
) {}
```

Crythst：

```java
public record ForceGroup(
        Component label,
        @Nullable Component description,
        int color,
        boolean debug
) {
    public Component name() { return label; }
    public boolean defaultDisplayed() { return debug; }
}
```

Crythst 还明确注释：

```text
Alias for label() matching the terminology used by Sable.
Alias for debug() matching Sable's "default displayed" flag.
```

`api/block/StructurePhysicsContext` 里也有：

```java
/**
 * Alias for forceGroup(ForceGroup) matching Sable's terminology.
 */
default QueuedForceGroup getOrCreateForceGroup(ForceGroup group)
```

这不是“大段代码复制”，但它是**有意的 API 兼容/术语对齐**。这和 NOTICE 中“没有任何 class layout 被复制”的绝对表述存在张力。

### 4.3 其它命名/术语相似

- `LevelPhysics` 中存在 `tickSubLevel()`、`projectSubLevelEntities()`、`clearSubLevel()` 等 Sable 风格命名。
- 代码注释中直接提到：
  - `StructureSelectionIgnore`：`the Crythst equivalent of Sable's ClipContextExtension`
  - `SectionOcclusion`：`mirrors sable's SubLevelBatchedTerrainRenderer#sable$scanVisibleSections`
  - `BatchedStructureRenderer`：`mirrors the "Option C" idea used by sable's ...`
  - `SectionCulling`：`Sable-style enclosed-block test`
  - `EmptyPhysicsLevel`：`deliberately avoids a Sable-style plot`
  - `StructurePhysicsContext`：`matching Sable's terminology`

这些注释证明**设计思路受到 Sable 启发**，也证明 Crythst 没有隐藏来源；但它们是“思想/行为”层面的复用，不是代码表达复制的直接证据。

### 4.4 算法层面的相似

`SectionOcclusion` 的 BFS 与 sable-port 的 `sable$scanVisibleSections` 结构相似：

- 从相机所在 section 开始；
- 向六个方向扩散；
- 完全 opaque 的面阻断；
- 队列/可见集合。

但表达式不同：

- Sable 用 `Long2ObjectOpenHashMap`、`LongArrayFIFOQueue`、`SectionPos.asLong`；
- Crythst 用 flat `boolean[]`、`int[] queue`、线性索引；
- Crythst 明确写了“uses a plain flat array”。

这属于**通用算法思路的独立实现**；但因为它直接点名了 Sable 的方法，审计上应记录为“有参考”。

---

## 5. 风险分级

| 级别 | 范围 | 判断 |
|---|---|---|
| **低** | 物理核心、Bullet 后端、Rust/native、传送门、网络/存储实现、资源文件、测试 | 未发现代码/资源复制；不同引擎、不同数据结构 |
| **低–中** | 批量渲染、section 遮挡、结构选择、交互、空 ServerLevel 逻辑 | 算法/设计受 Sable 启发，但表达式不同；代码相似度低 |
| **中** | 公共 API 类名、包结构、`ForceGroup`/`MassData`/`RigidBodyHandle`/`PhysicsPipeline`/constraint 配置、`VoxelColliderData`、`QueuedForceGroup` | 类布局/术语/API 明显对齐；NOTICE 的“没有 class layout 复制”表述不准确 |
| **需修正** | `NOTICE` 的绝对声明；`ForceGroup` 的 Sable 术语别名；`StructureGather` 的 6 行通用代码；`StructureArgumentType` 的 `getExamples` 模式 | 低成本可整改 |

**总体判断：**

- **代码表达层面：低风险。** 没有整文件复制，没有大段连续复制，没有注释/资源复制，物理后端完全不同。
- **API/结构层面：中等风险。** 同名类、包结构和术语对齐是主要问题；尤其 `ForceGroup` 的字段/别名几乎按 Sable 的 API 形状设计。
- **许可证层面：不能仅靠本报告宣布“安全”。** 需要结合发布方式、是否构成竞争、API 结构在目标法域的可版权性，交给律师判断。

---

## 6. 建议的整改顺序

### P0：先修正声明，不要保留绝对化表述

当前 `NOTICE` 写：

> No source code, class layout, comment text, or resource file from Sable or from any Sable port was copied into this repository.

基于本次审计：

- “没有源代码/注释/资源复制”有证据支持；
- “没有 class layout 复制”**过于绝对**，因为存在同名类、相同包结构和 API 术语对齐。

建议改为更准确的表述，例如：

```text
Crythst is an independent implementation. Its design was inspired by the
problem space explored by Sable. Crythst does not contain copied Sable source
files, comments, or resources. A small number of public API names and terms are
intentionally aligned with Sable for migration/compatibility; their
implementations are independent.
```

最终措辞请让律师确认。**不要删除 Sable 引用**；透明署名比隐藏来源更安全。

### P0：如果目标是严格 MIT/独立实现，重写 API 对齐层

优先处理以下文件：

| 文件 | 动作 |
|---|---|
| `api/physics/force/ForceGroup.java` | 去掉 `name()` / `defaultDisplayed()` 别名；改用 Crythst 自己的字段/术语，或明确标注为兼容层 |
| `api/physics/force/QueuedForceGroup.java` | 改名或重写 API 形状；当前实现不同，但同名 |
| `api/block/StructurePhysicsContext.java` | 删除 `getOrCreateForceGroup` 别名或标注兼容 |
| `api/physics/MassData.java` | 改名/改结构，避免与 Sable 同名接口混淆 |
| `api/physics/RigidBodyHandle.java` | 考虑改名或重命名方法；当前实现不同 |
| `api/physics/PhysicsPipeline.java` | 改名或明确为兼容 API |
| `api/physics/constraint/*Configuration.java` | 重命名 `Fixed/Free/Generic/Rotary` 等类，避免同名布局 |
| `physics/voxel/VoxelColliderData.java` | 改名，避免与 Sable 同名接口 |
| `mixin/client/DebugRendererMixin.java`、`EntityHitboxDebugRendererMixin.java` | 同名但实现不同；可保留，也可改为 Crythst 前缀命名 |

这些类大多很小，重写成本远低于法律风险。

### P1：清理可轻易消除的精确匹配

1. **`StructureGather.Bounds.of`**
   - 把 6 行 min/max 循环改成 `stream` 或辅助方法，消除精确匹配。
2. **`StructureArgumentType.getExamples`**
   - 改写 `EXAMPLES`/`getExamples` 的实现形式。
3. **`SectionOcclusion` / `StructureSelectionIgnore` / `BatchedStructureRenderer`**
   - 保留“受 Sable 启发”的说明；
   - 但不要直接把 Sable 方法名当作实现注释；改成高层行为描述，避免让人误以为逐行翻译。
4. **`LevelPhysics` 的 `tickSubLevel` / `projectSubLevelEntities`**
   - 如果不想保留 Sable 术语，改成 `tickAuxiliaryLevel` / `projectAttachedEntities` 等 Crythst 术语。

### P1：加入自动化许可证审计

建议在 CI 中加入一个轻量脚本，检查：

- 归一化文件 SHA-256 是否与 Sable/sable-port 相同；
- 5-gram Jaccard 是否超过阈值（例如 0.30，针对 ≥200 token 文件）；
- 连续匹配行是否超过 20 行；
- 注释/字符串/资源哈希是否重合；
- 同名公共类/方法是否新增。

把本报告和审计脚本一起放进仓库，形成可复核记录。

### P2：保留独立开发证据

- 保留 git 历史和提交作者；
- 保留设计文档、行为规格、测试；
- 新代码使用“规格 → 自己实现 → 测试”的流程，避免复制粘贴；
- 如果未来引入任何 Sable 代码，立即按 PolyForm Shield 处理：
  - 保留许可证与 Required Notice；
  - 评估 Noncompete；
  - 或者重写后再以 MIT 发布。

---

## 7. 结论

1. **没有证据表明 Crythst 大段复制了 Sable 的源代码、注释或资源。**  
   0 个归一化文件完全相同；最高 token Jaccard 0.071；非平凡代码最长连续匹配 6 行、总数 9 行；小文件最高 3 行；注释/资源 0 重合；Bullet vs Rapier 完全不同。

2. **存在明显的 API/结构对齐，尤其 `ForceGroup`、`RigidBodyHandle`、`MassData`、`PhysicsPipeline`、constraint 配置和 `QueuedForceGroup`。**  
   这不是“代码复制”的证据，但与 NOTICE 的绝对声明冲突，属于需要处理的中等风险。

3. **最稳妥的路线：**
   - 修正 NOTICE，准确描述“独立实现 + 少量 API 兼容/术语对齐”；
   - 重写或改名高风险 API 类；
   - 清理 6 行通用精确匹配；
   - 加入自动审计；
   - 发布前让律师确认。

4. **不要用“删除引用/隐藏来源”来规避许可证。**  
   当前代码里对 Sable 的引用是诚实的工程记录；保留并准确说明，比抹掉更安全。若任何部分被认定为衍生作品，应按 PolyForm Shield 合规，或彻底重写后再以 MIT 发布。

---

## 附录：可复核命令

```bash
# 1. 统计规模
find ~/Projects/Crythst/src/main/java -name '*.java' | wc -l
find ~/IdeaProjects/sable/{common,fabric,neoforge}/src/main/java -name '*.java' | wc -l
find ~/IdeaProjects/sable-port/src/main/java -name '*.java' | wc -l

# 2. 资源哈希对比
python3 - <<'PY'
import os, hashlib
# 对 Crythst 与 Sable 的 resources 逐文件 SHA-256 比较
PY

# 3. 归一化文件哈希 / 5-gram / 行级相似度
#    使用本报告第 2 节描述的方法；本次审计脚本输出：
#    - 完全相同的文件：0（全部 370 个文件，无大小过滤）
#    - 最高 5-gram Jaccard：0.071（≥200 token 文件）
#    - 非平凡代码行最高连续匹配：6 行；总数 9 行（通用 min/max）
#    - 小文件（≤120 代码行）最高非平凡匹配：3 行
#    - 注释精确重合：0
#    - 资源哈希重合：0 / 94
```

> 再次声明：以上是工程来源审计，不构成法律意见。发布前请让熟悉 MIT / PolyForm Shield 的律师复核。
