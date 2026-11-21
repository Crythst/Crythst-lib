# Structure block outline

The crosshair outline for a physics-structure voxel is rendered by Minecraft's
own block-outline path. There is no custom white box and no separately
tessellated line geometry.

## Extraction

`StructureSelectionOutline` (in
`client/render/structure/selection/`) subscribes to
`LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION`.

- If `Minecraft.hitResult` is a `StructureBlockHitResult`, it builds a
  `StructureOutlineTarget` from the hit (body id, structure pose, voxel
  coordinates, `blockScale`, block state, world hit point).
- `StructureOutlineShape` resolves the block-local `VoxelShape` with
  `BlockState#getShape`. Queries go through a small read-only `BlockGetter`
  whose coordinates are structure-local voxels, so stairs/slabs use their state
  properties and fences/walls see the structure's own neighbours.
- It publishes a `BlockOutlineRenderState` whose shape is that block-local
  shape. The vanilla render state is paired with the target in
  `StructureOutlineState` (identity match).

When the hit result is a real block, an entity or a miss, the handler does
nothing and vanilla rendering is untouched.

## Submission

`StructureBlockOutlineMixin` injects at the head of
`LevelRenderer#submitBlockOutline`. If the current
`BlockOutlineRenderState` is the one published for a structure voxel, it:

1. builds `translate(voxelWorld - camera) * rotate(structureQuaternion) *
   scale(blockScale)` (`StructureOutlineTransform`), and
2. submits the unchanged block-local shape through the invoker for vanilla's
   private `submitHitOutline`.

The invoker (`LevelRendererOutlineInvoker`) keeps the vanilla render types,
outline colours, `appropriateLineWidth`, translucent/after-terrain handling and
the high-contrast branch (`secondaryBlockOutline` + `linesDepthBias`). The
injection then cancels the default axis-aligned submission.

Because the *pose matrix* is applied to the unchanged block-local shape, an
arbitrary structure rotation or `blockScale` is exact: line vertices are
transformed by the same rotation and scale as the rendered voxel. Only the line
width itself stays in screen space and does not scale with `blockScale`, which
matches vanilla's behaviour for a scaled block.

## Selection agreement

`StructureRaycaster` resolves the same block-local shape with
`BlockState#getShape` through the same structure-local lookup semantics as
`StructureOutlineShape`, then intersects the ray with its boxes. The voxel and
world hit carried by `StructureBlockHitResult` are therefore the cell whose
visible shape was hit, not merely the first full-cell DDA cell. Slabs, stairs
and fences cannot be selected through their empty half, and the outline target
built from the hit points at exactly that cell.

## Contract for the selection layer

`StructureOutlineTarget` is the only thing the outline needs:

```java
record StructureOutlineTarget(
    long bodyId,
    Transform3d pose,       // structure-origin pose, not the COM body pose
    int voxelX, int voxelY, int voxelZ,
    double blockScale,      // metres per voxel
    BlockState state,
    Vector3d worldHit)
```

The current adapter is `StructureOutlineTarget.from(StructureBlockHitResult)`.
If the selection hit type changes, add an adapter with the same fields; the
mixin and pose maths do not need to change.

## Limitations

- The outline is one `VoxelShape` cell. It matches the voxel's state shape, but
  blocks whose visual model differs from their outline shape still show the
  outline shape, exactly as vanilla does.
- `CollisionContext` is the local player's context, as in vanilla. A
  context-sensitive shape that consults the entity's world position may be
  approximate inside a moving structure.
- A real block or entity that wins the crosshair comparison suppresses the
  structure outline; that decision is made once in the selection layer.
- The mixin cancels vanilla's default `LevelRenderer#submitBlockOutline` call
  for the published structure state because the oriented submission replaces
  it. Real-block outlines still run through the original method.
- In multiplayer the outline follows the same interpolated pose used for
  selection. A structure whose block-state snapshot is missing on the client
  falls back to a full-cube shape (`Shapes.block()`); structure sync must be
  active for the correct state shape and neighbours.
- The outline is visual only. It does not add the structure to vanilla block
  collision, pathfinding or block-placement checks.
