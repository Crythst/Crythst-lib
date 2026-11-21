# Auxiliary-level entity projection

This document describes why dispensers, droppers and other block logic appeared
broken on physics structures, and how entities they spawn are moved back into
the host world.

Implemented by:

- `io.mikaple.crythst.integration.minecraft.entity.projection.StructureEntityProjection`
  (pure mapping, projection API, diagnostics),
- `EmptyPhysicsLevel` (region/chunk views and non-loading entity enumeration),
- `LevelPhysics` (post-tick projection pass and retry/cleanup policy).

## Root cause

Block logic of a physics structure runs in an auxiliary void `ServerLevel`
(`EmptyPhysicsLevel`). Interaction and redstone dispatch use the copied block
entity in that level, so a dispenser or dropper behaves normally there — but
`ServerLevel.addFreshEntity` puts the spawned item, TNT, arrow, minecart, boat,
armor stand, etc. into the **void level**. The void level has no players and is
never rendered, so from the host world the block "cannot be used": nothing
appears, or the item vanishes when the region is refreshed or cleared.

Crythst structures are not a second block grid; the void level is only a
ticking implementation detail. Spawned entities must therefore be projected to
the host level, exactly like the block itself is drawn at
`structure.pose() * voxel * blockScale`.

## Coordinate mapping

Each structure is copied into a reserved region. The region stores:

```text
auxiliaryPos = regionBase + (voxel - volume.bounds().min())
```

Projection uses the inverse:

```text
localVoxel = voidPosition - region.localOrigin
           = voidPosition - (regionBase - volume.bounds().min())

worldPosition = structure.pose() * (localVoxel * blockScale)
worldDirection = structure.pose().rotation * voidDirection
worldRotation  = project rotation of the transformed look vector
```

`RegionView.localOriginX/Y/Z` already includes the volume minimum. This matters
after a split (`StructureSplitter`): a split body may have a non-zero volume minimum, and
ignoring it would shift projected entities by the split offset.

Velocity is only rotated, not scaled: Minecraft velocities are metres per tick,
and the requirement is that the structure pose rotates them. The position,
however, is scaled by `blockScale`.

For rotation, the entity's yaw/pitch is converted to a look vector, transformed
by the structure rotation, and converted back to yaw/pitch. A full 3D structure
rotation is projected onto the closest representable yaw/pitch pair.

## Tick placement

`LevelPhysics.tick()` and `LevelPhysics.step(count)` tick the auxiliary level
first (vanilla `tickChunk` + `tickBlockEntities`), then call the projection pass.
The order is:

1. physics step and render-pose publish,
2. auxiliary chunk/block-entity tick (dispenser fires),
3. projection of every entity in an active region to the host level.

Projection also runs before a region is removed or refreshed, so an entity
spawned in the last tick is not lost with the blocks.

## Scan boundary

The projection pass never loads a chunk:

- `EmptyPhysicsLevel.collectRegionEntities` uses the region's active chunk set
  as a filter (`RegionView` exposes the same bounds for tests).
- It iterates `ServerLevel.getAllEntities()`, which only sees already-loaded
  entity sections.
- Inactive/empty auxiliary chunks are never queried.

## Entity categories

All entities are projected except the explicit skip categories below. Vanilla's
cross-dimension transfer serialises and restores the full NBT, so type-specific
data survives:

| Category | Behaviour |
| --- | --- |
| `ItemEntity` | projected; item stack, count, age and pickup delay preserved |
| `PrimedTnt` | projected; fuse and owner preserved |
| `AbstractArrow` / throwables (snowball, egg, potion, experience bottle) | projected; owner, damage and life preserved |
| minecarts, boats, chest/hopper/furnace minecarts | projected; type and contents preserved |
| armor stands, mobs, falling blocks, fireworks | projected; NBT preserved |
| passengers | not projected separately; the root vehicle transfers them |
| players | never projected (the void level contains none) |
| block-attached entities (item frame, painting, leash knot) | left in the void level and reported; their support block does not exist in the host world |
| entities tagged `crythst.plot_only` | left in the void level and reported |

The `crythst.plot_only` scoreboard tag is the public opt-out for plot-only or
debug entities. It is saved as ordinary entity NBT.

## Duplicate and failure handling

- If the host level already contains the same UUID, the void copy is discarded
  instead of adding a duplicate.
- A failed projection leaves the entity in the auxiliary level and is retried
  after 20 ticks. The first failure is logged once per physics world.
- When a structure region is removed, any entities still left in its chunks are
  discarded so the save-less auxiliary level cannot accumulate orphans.
- The last pass and cumulative count are exposed by
  `LevelPhysics.lastEntityProjection()`, `projectedEntityCount()`,
  `subLevelEntityCount()` and `entityProjectionStatus()`. `/crythst status`
  prints `projectedEntities` and `voidEntities`.

## Public API

```java
StructureEntityProjection.RegionAnchor anchor =
        StructureEntityProjection.RegionAnchor.ofRegion(baseX, baseY, baseZ, volume.bounds());

Vec3 world = StructureEntityProjection.projectPosition(pose, blockScale, anchor, voidPos);
Vec3 motion = StructureEntityProjection.projectDirection(pose, voidMotion);
Vec2 rotation = StructureEntityProjection.projectRotation(pose, yaw, pitch);

StructureEntityProjection.Result result =
        StructureEntityProjection.project(hostLevel, structure, anchor, entity);
```

`project(...)` is the one-entity entry point used by the tick pass.
`projectAll(...)` projects an iterable and returns a `Batch` with per-outcome
counts.

## Limitations

- Block-attached entities are intentionally not projected. A structure's blocks
  are virtual in the host world, so vanilla's support check would pop an item
  frame or painting immediately. A future structure-attachment layer could make
  these projectable.
- A velocity is rotated but not multiplied by `blockScale`; this follows
  Minecraft's metres-per-tick convention and the documented pose-only mapping.
- The projection pass does not force-load the destination chunk. If the host
  chunk is not loaded, vanilla's add path decides how to queue the entity; the
  projection pass itself never scans or loads chunks.
- Entities that a block spawns in an auxiliary chunk that is not part of any
  active region (for example far outside the copied block bounds) are outside
  the scan boundary and are not projected.
