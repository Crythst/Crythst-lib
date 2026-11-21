# 物品特殊模型渲染（star 特效）

本文说明 Crythst 内置的 star 特殊物品模型：如何注册、如何用数据驱动方式挂到物品上、贴图怎么画，以及它和底层渲染管线的关系。

这条链路让一个**普通物品模型**（任意 `ModelTemplate` 生成的模型）用自定义 `star` 着色器渲染。物品的几何、UV、显示变换仍由基础模型提供；着色器只负责把贴图里的透明区域变成跳动的轮廓光环、把纯黑区域变成带紫色侧壁的凹陷星空。

---

## 1. 组成

| 文件 | 作用 |
| --- | --- |
| `io.mikaple.crythst.client.render.item.StarItemModelRenderer` | `SpecialModelRenderer<Item>`；烘焙基础模型、替换每个 quad 的 RenderType |
| `io.mikaple.crythst.client.render.item.StarRenderTypes` | 按图集缓存 star `RenderType`，复制 `RenderPipelines.ITEM_CUTOUT` 并替换着色器 |
| `io.mikaple.crythst.client.render.item.CrythstItemSpecialRenderers` | 公开入口：id 常量与 `register()` |
| `net.minecraft.client.renderer.rendertype.CrythstRenderTypes` | 同包桥接，调用包私有的 `RenderType.create` |
| `assets/crythst/shaders/core/star.vsh` / `star.fsh` | 顶点/片元着色器 |

命名约定：

| 名称 | 值 |
| --- | --- |
| 特殊模型类型 id | `crythst:star` |
| 着色器 id | `crythst:core/star` |
| 管线 location | `crythst:star_item` |
| RenderType 名称 | `crythst_star` |

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

## 4. 渲染流程

1. **模型烘焙**：`StarItemModelRenderer.Unbaked#bake(BakingContext)` 把 context 转成
   `ItemModel.BakingContext`，取出 `ModelBaker`。
2. **烘焙基础几何**：`resolvedModel.bakeTopGeometry(textureSlots, baker, BlockModelRotation.IDENTITY)`
   得到 `QuadCollection`。这里得到的 quad 与普通物品渲染完全一致。
3. **替换 RenderType**：`withStarShader` 为每个 quad 重建 `BakedQuad.MaterialInfo`，只把
   `itemRenderType()` 换成 star `RenderType`。`sprite`、`layer`、`itemGlintRenderType`、
   `itemGlintSpecialRenderType`、`tintIndex`、`shadeDirectionOverride`、`lightEmission`
   全部原样保留，因此附魔光效、染色等行为不变。
4. **按图集取 RenderType**：`StarRenderTypes.forAtlas(atlas)` 以图集 id 为 key 缓存；
   物品图和方块图各自得到独立的 `RenderType`。
5. **提交绘制**：`submit()` 直接走 `SubmitNodeCollector.submitItem(...)`。特殊模型拿不到当前
   `ItemDisplayContext`，因为几何变换已经在 `LayerRenderState` 里完成，所以传
   `ItemDisplayContext.NONE`。
6. **管线**：`StarRenderTypes.buildPipeline()` 复制 `RenderPipelines.ITEM_CUTOUT` 的绑定组、
   顶点格式、深度/模板、剔除等状态，只把着色器换成 `crythst:core/star`，并开启第一颜色目标的
   alpha 混合（轮廓环是半透明的）。管线不需要注册，`PipelineCache` 首次使用时按当前资源包里的
   着色器源码按需编译。

`CrythstRenderTypes` 放在 `net.minecraft.client.renderer.rendertype` 包里，是为了和
`RenderType` 同包、直接调用包私有的 `RenderType.create(String, RenderSetup)`，避免 Mixin
accessor 或反射。Fabric API 的 transitive access widener 也把该方法放宽为 public，但保留这个
桥接类可以让实现不依赖那条 widening。

---

## 5. 贴图约定

star 着色器按像素分支，因此基础模型的贴图需要按约定绘制：

| 贴图像素 | 渲染结果 |
| --- | --- |
| **透明轮廓**（alpha < 0.05） | 被丢弃。轮廓外侧留出的透明边用于双线性过滤，避免边缘出现透明缝 |
| **全透明但被轮廓包围的区域** | 不作为星空；轮廓判定在丢弃之后 |
| **纯黑掩码**（RGB ≈ 0，alpha > 0.5） | 凹陷星空：程序化星场 + 紫色侧壁，自发光（跳过光照图） |
| **半透明近黑边缘**（双线性/mipmap 过渡） | 按掩码容差 `MASK_TOLERANCE = 0.1` 吸收，保证边缘平滑 |
| **其余颜色** | 正常采样原贴图，受顶点色、光照图、雾、附魔光效影响 |

要点：

* **透明轮廓**：物品外轮廓之外要留 1px 以上的全透明边，且边缘像素不要用半透明黑去"描边"，
  否则会被当成近黑掩码。轮廓双线性混合出的 `alpha 0.05~0.1` 像素仍会保留渲染，不要为了
  "干净"把阈值调回 0.1。
* **纯黑掩码**：想要星空/凹陷的区域画成 `#000000` 不透明；不要用 `#00000000`（透明黑）当掩码，
  它会被 `alpha < 0.05` 提前丢弃。
* **掩码四周留余量**：凹陷壁面的射线行军会在贴图内寻找掩码出口，掩码区域四周需要留出非掩码
  像素，避免采样到图集里相邻的贴图。
* 同一张贴图也能用于方块物品（`CUBE_ALL` 等）；透视关系按真实几何计算，不依赖固定的 UV 布局。

---

## 6. 注意事项

* 基础模型必须能烘焙出至少一个 quad，否则物品不可见；`StarItemModelRenderer` 会在日志里
  输出 `baked zero quads` 警告。
* 特殊模型不会拿到 `ItemDisplayContext`；需要按上下文切换模型时，用原版
  `ItemModelUtils.select(new DisplayContext(), ...)` 在外层选择（每个分支各自挂 star 特殊模型）。
* 自定义 `RenderType` 会参与模型烘焙 worker 线程；`StarRenderTypes` 用 `ConcurrentHashMap`
  缓存图集、用双重检查锁初始化管线。
* 着色器依赖 `GameTime`（闪烁动画）、`ScreenSize`（星场纵横比与射线构造）、`ProjMat`
  （逆投影）等原版 uniform；不要在自定义管线里移除这些 uniform 或改顶点格式。

---

## 7. 与 EnderTech 的差异

本实现从 EnderTech 的 `StarItemModelRenderer` / `StarRenderTypes` / `EnderTechRenderTypes`
移植，26.3 API 完全一致，差异仅在命名与注册入口：

| EnderTech | Crythst |
| --- | --- |
| `io.mikaple.endertech.client.models.*` | `io.mikaple.crythst.client.render.item.*` |
| `EnderTechRenderTypes.create` | `CrythstRenderTypes.create` |
| `Endertech.id("core/star")` | `CrythstItemSpecialRenderers.STAR_SHADER` |
| `Endertech.id("star_item")` | `CrythstItemSpecialRenderers.STAR_PIPELINE` |
| `ender_tech_star` | `crythst_star` |
| `ender_tech:star` | `crythst:star` |
| `assets/ender_tech/shaders/core/star.*` | `assets/crythst/shaders/core/star.*` |
| 在 `EndertechClient` 里直接 `ID_MAPPER.put` | `CrythstItemSpecialRenderers.register()`（幂等） |

GLSL 源码逐字节一致（仅资源路径命名空间变化），没有着色器逻辑改动。
