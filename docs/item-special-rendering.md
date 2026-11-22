# 物品特殊模型渲染（star 特效）

本文说明 Crythst 内置的 star 特殊物品模型：如何注册、如何用数据驱动方式挂到物品上、贴图怎么画，以及它和底层渲染管线的关系。

这条链路让一个**普通物品模型**（任意 `ModelTemplate` 生成的模型）在正常渲染之上叠加自定义 `star` 特效。物品的几何、UV、显示变换、材质和深度仍由基础模型提供；星空叠加层只把贴图中的不透明纯黑区域变成带紫色侧壁的凹陷星空，其余像素完全不覆盖真实模型。

---

## 1. 组成

| 文件 | 作用 |
| --- | --- |
| `io.mikaple.crythst.client.render.item.StarItemModelRenderer` | `SpecialModelRenderer<Item>`；烘焙基础模型，提交“真实模型 + 星空遮罩”两遍 |
| `io.mikaple.crythst.client.render.item.StarRenderTypes` | 按 `(图集, translucent)` 缓存 star 叠加层 `RenderType`，复制原版物品管线并替换着色器/深度状态；translucent 变体带 OIT 管线 |
| `io.mikaple.crythst.client.render.item.StarRenderPlan` | 纯逻辑：两遍的渲染阶段、深度测试与顺序（可单测） |
| `io.mikaple.crythst.client.render.item.StarMaskMath` | 纯逻辑：掩码阈值、屏幕/NDC/星空 UV、凹陷 UV 投影（可单测） |
| `io.mikaple.crythst.client.render.item.CrythstItemSpecialRenderers` | 公开入口：id 常量与 `register()` |
| `net.minecraft.client.renderer.rendertype.CrythstRenderTypes` | 同包桥接，调用包私有的 `RenderType.create` |
| `assets/crythst/shaders/core/star.vsh` / `star.fsh` | 顶点/片元着色器 |

命名约定：

| 名称 | 值 |
| --- | --- |
| 特殊模型类型 id | `crythst:star` |
| 着色器 id | `crythst:core/star` |
| 管线 location（不透明叠加） | `crythst:star_item` |
| 管线 location（半透明叠加） | `crythst:star_item_translucent` |
| RenderType 名称 | `crythst_star`（不透明）、`crythst_star_translucent` |

---

## 2. 注册

`CrythstItemSpecialRenderers.register()` 把 `StarItemModelRenderer.Unbaked.MAP_CODEC` 注册到
`SpecialModelRenderers.ID_MAPPER`：

```java
SpecialModelRenderers.ID_MAPPER.put(
        Identifier.fromNamespaceAndPath("crythst", "star"),
        StarItemModelRenderer.Unbaked.MAP_CODEC);
```

`register()` 是幂等的（内部有 `registered` 标志），重复调用不会产生重复映射。

**注册时机**：客户端初始化阶段，由客户端模组调用一次。例如：

```java
public final class ExampleClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CrythstItemSpecialRenderers.register();
    }
}
```

`CrythstItemSpecialRenderers` 是纯客户端类，不要在 dedicated server 上加载它。
Crythst 自身不会自动注册（库模组不抢占物品模型 id），由使用方显式调用。

---

## 3. 数据驱动用法

### 3.1 数据生成（推荐）

在 `FabricModelProvider#generateItemModels` 里先创建普通模型，再用
`ItemModelUtils.specialModel(baseModelId, new StarItemModelRenderer.Unbaked(modelId))`
生成 `minecraft:special` 包装器：

```java
Identifier modelId = ModelTemplates.FLAT_ITEM.create(
        ModelLocationUtils.getModelLocation(MY_ITEM),
        TextureMapping.layer0(TextureMapping.getItemTexture(MY_ITEM)),
        itemModelGenerator.modelOutput);

itemModelGenerator.itemModelOutput.accept(
        MY_ITEM,
        ItemModelUtils.specialModel(modelId, new StarItemModelRenderer.Unbaked(modelId)));
```

两个参数通常传同一个模型 id：

* 第一个是 `base`：决定显示变换、粒子材质等 `ModelRenderProperties`，并登记依赖；
* `Unbaked.model`：`StarItemModelRenderer` 在烘焙阶段实际读取并 `bakeTopGeometry` 的模型。

同一个模型同时作为 base 最省事。也可以让 base 指向一个带不同 `display` 的模型，而
`Unbaked.model` 指向真正提供几何的模型。

### 3.2 手写 JSON

上面 datagen 产出的物品模型 JSON 等价于：

```json
{
  "model": {
    "type": "minecraft:special",
    "base": "crythst:item/my_item",
    "model": {
      "type": "crythst:star",
      "model": "crythst:item/my_item"
    }
  }
}
```

* `minecraft:special` 是原版特殊模型包装器，字段为 `base`、可选的 `transformation`、`model`；
* `model.type = "crythst:star"` 对应注册的特殊模型 id；
* `model.model` 是基础模型 id，字段名固定为 `"model"`（与 `Unbaked.MAP_CODEC` 一致）。

基础模型本身仍是普通物品模型，例如：

```json
{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "crythst:item/my_item"
  }
}
```

---

## 4. 渲染流程（真实模型 + 星空遮罩两遍）

star 模型不再是“把原模型的 RenderType 全部换掉、只画一遍”。`StarItemModelRenderer#submit`
对**同一套几何、同一个 pose** 提交两次：

### 4.1 第一遍：真实模型

1. **模型烘焙**：`StarItemModelRenderer.Unbaked#bake(BakingContext)` 把 context 转成
   `ItemModel.BakingContext`，取出 `ModelBaker`。
2. **烘焙基础几何**：`resolvedModel.bakeTopGeometry(textureSlots, baker, BlockModelRotation.IDENTITY)`
   得到 `QuadCollection`。这里得到的 quad 与普通物品渲染完全一致。
3. **原样提交真实模型**：用 `ItemQuads.split(originalQuads)` 按 solid/translucent 分组，
   每个 quad 保留原来的 `itemRenderType()`、`itemGlintRenderType()`、tint、
   `shadeDirectionOverride`、`lightEmission`。`submitItem` 使用
   `hasFoil ? FoilType.STANDARD : FoilType.NONE` 和传入的 `outlineColor`。
   因此 cutout/translucent、附魔光效、轮廓、光照图、overlay、深度写入全部与普通物品一致。

### 4.2 第二遍：星空遮罩叠加

4. **替换叠加层的 RenderType**：`withStarShader` 只重建
   `BakedQuad.MaterialInfo.itemRenderType()`，按原 quad 是否透明选择：
   * 原 quad 不混合 → `crythst_star`（不透明，进入 solid 阶段）；
   * 原 quad 混合 → `crythst_star_translucent`（混合，进入 translucent 阶段）。
5. **管线**：`StarRenderTypes.buildPipeline()` 复制 `RenderPipelines.ITEM_CUTOUT` /
   `ITEM_TRANSLUCENT` 的绑定组、顶点格式、颜色目标格式/混合、剔除等，只把着色器换成
   `crythst:core/star`，定义 `STAR_MASK_ONLY`，并把深度状态改为
   `DepthStencilState(GREATER_THAN_OR_EQUAL, writeDepth = false)`。
   translucent 变体额外带 `OitPipelineSet`，因此 `useImprovedTransparency`（OIT）开启时
   不会因缺少 OIT 管线而崩溃。
6. **提交叠加层**：第二次 `submitItem` 使用同样的几何与 pose，`FoilType.NONE`、
   `outlineColor = 0`。片元着色器先丢弃 `alpha < 0.05` 的像素，然后丢弃所有**非**纯黑
   效果掩码像素；只有“不透明近黑”像素会画成星空/紫色凹壁，并把 alpha 固定为 1。

### 4.3 深度与顺序

* **没有硬件模板测试**：RenderPearl 的 `DepthStencilState` 不暴露 stencil 操作。
  这里用**真实模型写出的深度缓冲**作为轮廓遮罩（silhouette mask），叠加层使用完全相同的
  几何，所以它的深度值与真实模型逐像素一致。
* **不写深度**：叠加层只做 `GREATER_THAN_OR_EQUAL` 深度测试（反向 Z），
  `writeDepth = false`。因此：
  * 星星不能穿过真实模型：真实模型后方/背面的叠加几何会被深度测试拒绝；
  * 星星不能穿过更近的世界/UI 几何：更近的深度更大，测试失败；
  * 不会有 z-fighting：叠加层不写深度，也不与真实模型争夺深度值；只有“效果掩码”像素被
    改写颜色，因此没有描边/halo。
* **阶段一致**：solid quad 的真实模型和叠加层都在 solid 阶段；translucent quad 都在
  translucent 阶段。`ItemFeatureRenderer` 会按 RenderType 合并连续几何，但真实模型的
  RenderType 总是先于叠加层出现，所以所有真实 solid 几何先画完，再画叠加层。
  translucent 阶段按距离排序，相同 pose 的两次提交按提交顺序稳定排列（真实模型在前）。
* **多表面**：立方体等模型有多个面时，背面叠加层的深度比正面更远，会被深度测试拒绝；
  星星只出现在当前可见表面上，不会“透”到背面。
* **上下文**：GUI、第一人称手、掉落物、物品展示框和方块特殊渲染都走
  `ItemStackRenderState#submit`，因此自动获得同样的两遍行为。GUI 物品图集和
  picture-in-picture 都带独立深度附件；`ItemModelBlockRenderer` 也复用物品路径。
* **附魔光效**：真实模型那一遍负责 glint（使用原 `itemGlintRenderType`）；叠加层用
  `FoilType.NONE`，不会重复叠加或把 glint 画到非掩码区域。

`CrythstRenderTypes` 放在 `net.minecraft.client.renderer.rendertype` 包里，是为了和
`RenderType` 同包、直接调用包私有的 `RenderType.create(String, RenderSetup)`，避免 Mixin
accessor 或反射。Fabric API 的 transitive access widener 也把该方法放宽为 public，但保留这个
桥接类可以让实现不依赖那条 widening。


---

## 5. 贴图约定

贴图同时服务两遍渲染：真实模型那一遍按原样采样贴图；星空叠加层只认“效果掩码”。

| 贴图像素 | 真实模型 | 星空叠加层 |
| --- | --- | --- |
| **透明轮廓**（alpha < 0.05） | 被 cutout/alpha 丢弃 | 丢弃，不写任何像素 |
| **全透明但被轮廓包围的区域** | 透明 | 丢弃；透明黑 `(0,0,0,0)` 绝不算掩码 |
| **纯黑掩码**（RGB ≈ 0，alpha > 0.5） | 正常画成黑色 | 凹陷星空：程序化星场 + 紫色侧壁，自发光（跳过光照图），alpha = 1 |
| **半透明近黑边缘**（双线性/mipmap 过渡） | 正常混合 | 按掩码容差 `MASK_TOLERANCE = 0.1` 吸收；alpha 需 > 0.5 |
| **其余颜色** | 正常采样原贴图，受顶点色、光照图、雾、附魔光效影响 | 丢弃（`STAR_MASK_ONLY`），完全不覆盖真实模型 |

轮廓遮罩不在贴图里，也不额外生成一张 mask texture：**真实模型写出的深度缓冲就是轮廓遮罩**，
叠加层用同一几何做 `GREATER_THAN_OR_EQUAL` 深度测试。贴图只负责判断“哪些可见像素是星空窗口”。

要点：

* **透明轮廓**：物品外轮廓之外要留 1px 以上的全透明边，且边缘像素不要用半透明黑去"描边"，
  否则会被当成近黑掩码。轮廓双线性混合出的 `alpha 0.05~0.1` 像素仍会保留渲染，不要为了
  "干净"把阈值调回 0.1。
* **纯黑掩码**：想要星空/凹陷的区域画成 `#000000` 不透明；不要用 `#00000000`（透明黑）当掩码，
  它会被 `alpha < 0.05` 提前丢弃，也不会通过 alpha > 0.5 的掩码判定。
* **掩码四周留余量**：凹陷壁面的射线行军会在贴图内寻找掩码出口，掩码区域四周需要留出非掩码
  像素，避免采样到图集里相邻的贴图。
* 同一张贴图也能用于方块物品（`CUBE_ALL` 等）；透视关系按真实几何计算，不依赖固定的 UV 布局。
* 纯黑掩码区域在真实模型那一遍仍然是黑色；如果叠加层因自定义深度状态未通过测试，用户看到的是
  黑色窗口而不是花屏/穿透，属于安全降级。

---

## 6. 注意事项与局限

* 基础模型必须能烘焙出至少一个 quad，否则物品不可见；`StarItemModelRenderer` 会在日志里
  输出 `baked zero quads` 警告。
* 特殊模型不会拿到 `ItemDisplayContext`；需要按上下文切换模型时，用原版
  `ItemModelUtils.select(new DisplayContext(), ...)` 在外层选择（每个分支各自挂 star 特殊模型）。
* 自定义 `RenderType` 会参与模型烘焙 worker 线程；`StarRenderTypes` 用 `ConcurrentHashMap`
  缓存 `(atlas, translucent)`，用双重检查锁初始化 opaque/translucent 管线与 OIT 管线集。
* 着色器依赖 `GameTime`（闪烁动画）、`ScreenSize`（星场纵横比与射线构造）、`ProjMat`
  （逆投影）等原版 uniform；不要在自定义管线里移除这些 uniform 或改顶点格式。
* **无硬件 stencil**：RenderPearl 不暴露 stencil，轮廓遮罩由真实模型的深度缓冲承担。标准
  物品 RenderType（`ITEM_CUTOUT` / `ITEM_TRANSLUCENT`）都写深度，因此两遍方案成立。如果
  使用的自定义特殊模型把 `itemRenderType` 换成了**不写深度**的 RenderType，叠加层就失去
  可靠的轮廓遮罩：它只能按世界深度测试，可能被错误遮挡或在半透明物体后显示。不要在这种
  模型上使用 star；标准 star 路径不修改真实模型的深度状态。
* **OIT**：translucent 叠加层带 `OitPipelineSet`。自定义 star 着色器如果删掉
  `OIT_ALPHA_ONLY` / `OIT_ACCUMULATE` 分支，`useImprovedTransparency` 开启时会崩溃。
* **叠加层不写深度**，所以不会产生 z-fighting/halo，也不会遮挡后续几何。代价是：同一像素如果
  有多个共面星形窗口（例如自定义模型故意重叠），它们会按提交顺序覆盖，最终显示最后一个；
  标准立方体/平面模型没有这种共面重叠。
* **凹陷是着色效果，不是真实几何深度**：紫色侧壁和“凹底”由片元着色器做射线-平面求交得到，
  叠加层的深度始终等于真实模型表面。因此凹壁不会真正遮挡其他几何，也不会在深度缓冲里留下
  凹腔；它的透视关系只在星空窗口内部成立。这与“星星不能穿透模型/世界”是两件事：后者由
  叠加层对真实模型深度做测试保证。
* **glint 在真实模型那一遍**：星空窗口像素会被叠加层覆盖，因此星星本身不带附魔闪烁；星星之外
  的模型像素保留原版 glint。这是有意取舍，避免两层 glint 叠加。
* 本实现针对 26.3 的 `SubmitNodeCollector#submitItem` 与 OIT 管线；升级 Minecraft 时需要重新
  检查 `ItemQuads.split`、`RenderType#hasBlending` 和 `OitPipelineSet` 的语义。

---

## 7. 与 EnderTech 的差异

命名与注册入口与 EnderTech 对齐，但**渲染管线已重写**：

| EnderTech | Crythst |
| --- | --- |
| `io.mikaple.endertech.client.models.*` | `io.mikaple.crythst.client.render.item.*` |
| `EnderTechRenderTypes.create` | `CrythstRenderTypes.create` |
| `Endertech.id("core/star")` | `CrythstItemSpecialRenderers.STAR_SHADER` |
| `Endertech.id("star_item")` | `CrythstItemSpecialRenderers.STAR_PIPELINE` |
| `ender_tech_star` | `crythst_star` / `crythst_star_translucent` |
| `ender_tech:star` | `crythst:star` |
| `assets/ender_tech/shaders/core/star.*` | `assets/crythst/shaders/core/star.*` |
| 在 `EndertechClient` 里直接 `ID_MAPPER.put` | `CrythstItemSpecialRenderers.register()`（幂等） |

EnderTech 的单遍 star 着色器同时负责真实模型和特效，并使用可混合的颜色目标写深度；
Crythst 改成“真实模型一遍 + 不写深度的 mask 叠加层”，因此 GLSL 与 RenderType 都不再逐字节一致。
