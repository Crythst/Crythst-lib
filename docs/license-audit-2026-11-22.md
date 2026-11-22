# Crythst × Sable 来源审计（简化版）

**日期：** 2026-11-22
**性质：** 工程来源审计，不是法律意见。
**快照：** Crythst `44ddb9a` + 工作区 WIP，370 主 Java / 56,305 行；104 测试文件 / 14,963 行；596 测试 / 102 测试类。
**对照：** Sable `9b1a3d1`（2.0.7，769 Java，5,854 Rust）与 sable-port `113035f`（3.0.0，574 Java，5,854 Rust）。

完整数据见 `docs/license-audit-data-2026-11-22.tsv`。上中下游边界见 README 的“上中下游关系”和 `NOTICE`。

## 结论

- **代码表达层面：低风险。** 没有整文件复制，没有大段连续复制，没有注释或资源复制。
- **API/结构层面：中等风险。** 存在 11 个同名公共类、相近的包结构/术语和少量 API 别名；`NOTICE` 已明确披露这些对齐。
- **许可证层面：不能仅凭审计宣布零风险。** 发布前仍需律师复核 PolyForm Shield 的 Notice/Noncompete 和 API 结构的可版权性。

## 检查方法

全部 370 个主 Java 文件参与，**不按文件大小过滤**（包括 record、payload、Mixin accessor、配置类、13 行小类）。

1. 去注释/import/空白后做整文件 SHA-256；
2. 5-gram token Jaccard；
3. 行级 diff，排除只含括号/分号的噪声行；
4. 注释、字符串、资源 SHA-256 重合；
5. 公共类名/方法名/字段名交集。

## 关键证据

| 检查 | 结果 |
|---|---:|
| 归一化后完全相同的文件（Sable / sable-port） | **0 / 0** |
| 最高 5-gram Jaccard（≥200 token） | **0.060 / 0.071** |
| 非平凡代码行最高匹配总数 | **9 行** |
| 非平凡代码最长连续匹配 | **6 行** |
| 小文件（≤120 代码行）最高非平凡匹配 | **3 行** |
| ≥20 行连续复制块 | **0** |
| ≥35 字符注释精确重合 | **0** |
| 资源 SHA-256 完全重合 | **0 / 94** |
| 唯一 Sable 字符串重合 | 未发现（重合均为通用词/Minecraft API 描述） |
| Rust/native 共享 | 无；Crythst 用 Bullet，Sable 用 Rapier |

### 行级匹配说明

原始 diff 会把 `}`、`);` 算成匹配；例如 `StructureSavedData` 与 `SubLevelPhysicsSystem` 的“32 行匹配”全部是闭合括号，非平凡匹配为 0。

排除噪声后，最高的一对是：

```text
Crythst api/assembly/StructureGather.java
vs Sable api/SubLevelAssemblyHelper.java
非平凡匹配：9 行（6 + 3）
最长连续：6 行
```

6 行是通用包围盒 min/max，3 行是通用邻域 `for` 循环；没有实质方法体复制。

小文件最高 3 行，且均为通用行，例如：

- `this.minX/minY/minZ = ...`；
- `Minecraft.getInstance()` + null 检查；
- `RenderTypes.linesTranslucent...()` 分支；
- `@Inject(method = "move(...)")` 注解行。

**小文件层面未发现整文件、短方法或小类复制。**

## API/类结构相似

| 类 | 标识符 Jaccard | 说明 |
|---|---:|---|
| `ForceGroup` | **0.561** | 字段语义对齐；有 `name()` / `defaultDisplayed()` 别名 |
| `RigidBodyHandle` | 0.331 | 同名、方法名接近；实现不同 |
| `QueuedForceGroup` | 0.247 | 同名；实现不同 |
| `MassData` | 0.244 | 同名；类 vs 接口 |
| `PhysicsPipeline` | 0.232 | 同名；包装类 vs 接口 |
| `FreeConstraintConfiguration` | 0.194 | 同名 |
| `DebugRendererMixin` | 0.194 | 同名 Mixin；实现不同 |
| `RotaryConstraintConfiguration` | 0.185 | 同名 |
| `FixedConstraintConfiguration` | 0.162 | 同名 |
| `GenericConstraintConfiguration` | 0.123 | 同名；语义不同 |
| `VoxelColliderData` | 0.098 | 同名；类 vs 接口 |

最明显的证据是 `ForceGroup`：

- Sable：`name / description / color / defaultDisplayed`
- Crythst：`label / description / color / debug`
- Crythst 明确提供 `name()` / `defaultDisplayed()` 兼容别名，并在注释中说明“matching Sable terminology”。

这不是大段代码复制，但与“public API surface 与 Sable 无关”的说法冲突。当前 `NOTICE` 已改为披露“少量 API/类布局/术语有意对齐，实现独立”。

## 风险分级

| 范围 | 风险 | 判断 |
|---|---|---|
| 物理核心、Bullet 后端、Rust/native、传送门、网络/存储、资源 | 低 | 不同引擎/数据结构；无复制证据 |
| 批量渲染、遮挡 BFS、结构选择、空 ServerLevel | 低–中 | 思路受 Sable 启发，表达式不同 |
| 公共 API 类名/包结构/术语 | 中 | 11 个同名类；`ForceGroup` 等 API 形状接近 |
| `NOTICE` 表述 | 已修正 | 已披露 API/类布局对齐；不再声称 API surface 无关 |

## 建议

### P0

1. 保持 `NOTICE` 当前口径：独立实现、无源文件复制、API/类布局/术语对齐已披露。
2. 如目标是严格 MIT 独立，重写/改名高风险 API 类：
   - `ForceGroup`、`QueuedForceGroup`、`MassData`、`RigidBodyHandle`、`PhysicsPipeline`、约束配置、`VoxelColliderData`；
   - 去掉 `name()` / `defaultDisplayed()` / `getOrCreateForceGroup` 等 Sable 术语别名。
3. 清理可轻易消除的精确匹配：
   - `StructureGather.Bounds.of` 的 6 行 min/max；
   - `StructureArgumentType.getExamples` 的通用模式。

### P1

- 加入 CI 审计：归一化文件哈希、5-gram 阈值、非平凡连续匹配、小文件匹配、注释/资源哈希、同名 API 报告。
- 保留 git 历史和设计文档；新代码走“规格 → 独立实现 → 测试”流程。

### P2

- 发布前让律师复核 PolyForm Shield 的 Required Notice / Noncompete，以及 API 结构的可版权性。
- 若未来引入 Sable/sable-port 代码，按 PolyForm Shield 处理或重写，不能仅以 MIT 发布。

## 相关文件

- `NOTICE`：上游/中游/下游与对齐声明。
- `THIRD_PARTY_NOTICES.md`：第三方与参考项目。
- `docs/license-audit-data-2026-11-22.tsv`：逐项数据。
- README “上中下游关系”。
