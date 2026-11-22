# Structure-aware mob AI and pathfinding

Status: implemented for vanilla ground navigation through a
`PathNavigationRegion` bridge. Full `Level`-facade AI and modded navigators
remain out of scope.

Crythst physics structures are world-space rigid bodies. Their blocks are not
part of the host chunk grid, so vanilla pathfinding used to see only the host
world:

- `PathNavigation` built a `PathNavigationRegion` from `ChunkSource#getChunkNow`.
- `WalkNodeEvaluator` asked that region for `getBlockState`,
  `getFluidState`, `getBlockCollisions` and floor heights.
- The region returned host air (or host terrain) at every world position under
  a structure, so mobs could not see platform floors, walls, slabs, stairs,
  water or danger blocks.
- In 26.3 `PathfindingContext` also consults `ServerLevel#getPathTypeCache()`,
  a 4096-entry cache keyed only by block position. Even after a structure
  arrives, a previously cached `OPEN` for host air would win.

The bridge fixes both layers without moving structure blocks into the host
chunk grid and without loading chunks.

## Mixin targets

All targets are client-independent and live in `crythst.mixins.json`
(`io.mikaple.crythst.mixin.ai`).

| Target | Injection | Purpose |
|---|---|---|
| `net.minecraft.world.level.PathNavigationRegion` | `getBlockState(BlockPos)` RETURN | If the host block is air, return the structure block at that world position. |
| same | `getFluidState(BlockPos)` RETURN | If the host fluid is empty, return structure water/lava. |
| same | `getChunkForCollisions(int,int)` HEAD | Return the region itself so `BlockCollisions` reads structure-aware states and shapes instead of raw host chunks. |
| `net.minecraft.world.level.pathfinder.PathfindingContext` | `getPathTypeFromState(int,int,int)` HEAD | Bypass the server-wide position-only `PathTypeCache` when the region has structures. |
| `net.minecraft.world.entity.ai.navigation.GroundPathNavigation` | `findSurfacePosition(LevelChunk, BlockPos, int)` HEAD | Scan a structure-aware column before falling back to vanilla terrain. |
| `net.minecraft.world.entity.ai.navigation.PathNavigation` | `getGroundY(Vec3)` HEAD | Aim the move control at a structure floor instead of the ground below it. |
| same | `isStableDestination(BlockPos)` HEAD | Let AI goals treat a structure surface as a stable destination. |
| `net.minecraft.world.entity.ai.navigation.WalkNodeEvaluator` | `prepare(...)`, `done()`, `getPathTypeOfMob(...)` | Diagnostics only; all node semantics already flow through the region. |
| `net.minecraft.world.entity.Mob` | interface + cached source | Per-mob `StructurePathSource` and extension point. |

No client-only mixin is required. On a physical client the guarded
`ClientStructurePathSource` reads live singleplayer structures or network
snapshots, but the server remains authoritative.

## Data flow

```
PathNavigation.createPath
  -> new PathNavigationRegion(level, from-radius, to+radius)
     -> PathNavigationRegionMixin builds a per-region StructurePathIndex
        -> StructurePathSources.forLevel(level)
           -> ServerStructurePathSource  (authoritative LevelPhysics structures)
           -> ClientStructurePathSource  (live client view / network snapshots)
  -> PathFinder -> WalkNodeEvaluator
     -> PathfindingContext.getPathTypeFromState
        -> bypass global PathTypeCache when structure-aware
        -> WalkNodeEvaluator.getPathTypeFromState(region, pos)
     -> region.getBlockState / getFluidState / getBlockCollisions
        -> host value first, structure value only when host is air/empty
```

`StructurePathIndex` is bucketed by world chunk column. A block lookup tests
only structures whose broad-phase world AABB overlaps that column; very large
structures use a small global list rather than allocating thousands of
buckets. `StructurePathSource#snapshot` filters structures by the navigation
region's AABB before indexing.

## Coordinate mapping

For each structure, world positions are sampled at the centre of the world
block:

```
localMetres = inverse(structure.pose) * (blockX + 0.5, blockY + 0.5, blockZ + 0.5)
localVoxel  = floor(localMetres / blockScale)
```

If `localVoxel` is inside the structure's integer bounds, the structure view's
block/fluid state is used. This supports:

- multiple structures in one region;
- arbitrary rotations and scales;
- translations changing every physics tick;
- slabs, stairs, fences, walls, doors and other neighbour-sensitive shapes,
  because shape resolution receives the same structure-aware getter and sees
  structure neighbours.

The mapping is a grid approximation. For scaled-down or tilted structures the
world cell centre can fall in an air voxel while a corner overlaps a solid
voxel, so those structures also sample the eight cell corners. A voxel strictly
inside a cell without touching its centre/corners can still be missed. Entity
movement still uses the exact OBB/voxel collision path; pathfinding only needs a
conservative, cheap walkable grid.

## Caching and invalidation

- The index is cached per `PathNavigationRegion`; regions are short-lived (one
  path computation).
- The source reports a `stamp` derived from game time, structure count, body
  ids, content epochs and pose bits.
- Every `VALIDATION_INTERVAL` (128) queries the index compares the stamp and
  rebuilds at most `MAX_REBUILDS` (4) times. After that it disables structure
  sampling for the rest of that path and vanilla world blocks remain.
- `StructurePathVolume#isCurrent` also drops closed/stale structure views.

## Chunk loading and server authority

- Server path views are volume/side-table backed
  (`ServerStructureLevelView` with no auxiliary level). They never call
  `getChunk` or create chunks.
- World fallbacks in `StructurePathProbe` use
  `ChunkSource#getChunkNow`; an unloaded chunk is treated as air.
- `GroundPathNavigation.createPath` still returns `null` when the target host
  chunk is not loaded, exactly like vanilla, so a path request never triggers
  a synchronous chunk load.
- All reads are read-only. The mixture does not write blocks, spawn entities or
  mutate physics bodies.

## Performance bounds

Constants in `StructurePathLimits`:

| Bound | Default | Behaviour on overflow |
|---|---|---|
| Structures per region | 64 | Extra structures skipped (diagnostic counter). |
| Chunk buckets per structure | 4096 | Structure indexed globally. |
| Candidates per block lookup | 32 | Remaining candidates skipped for that lookup. |
| Structure lookups per region | 262,144 (system property `crythst.pathfinding.maxStructureQueries`) | Structure sampling stops; vanilla pathfinding continues. |
| Validation interval | 128 queries | Stamp compared, at most 4 rebuilds per region. |
| Surface scan | 128 vertical steps | Returns `null`; vanilla `findSurfacePosition` runs. |

A region with no structures builds one empty index and then takes vanilla code
paths. Scaled or tilted structures may sample up to nine local voxels per world
block (centre plus eight corners) when the centre is air. Diagnostics counters
are exposed by `StructurePathDiagnostics.snapshot()` / `describe()`.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/ai/StructurePathIndexTest.java`
covers:

- world/local mapping for identity, rotated and scaled poses;
- multiple structures and world-block preference;
- slab/stair collision shape selection and slab floor height;
- vanilla node types for structure floors, water, lava, cactus, fences and
  walls;
- structure surface scanning instead of terrain below;
- cache invalidation on pose/content stamp change;
- query-budget fallback.

## What remains

- A full `Level` facade: AI that calls `Level#getBlockState`,
  `Level#clip`, `Level#getEntities` or `Level#getFluidState` outside the region
  bridge still sees the host world. A real structure level view/plot is the
  long-term fix.
- Modded navigators (`FlyingPathNavigation`, `AmphibiousPathNavigation`,
  `WallClimberNavigation`, Create/other mod path evaluators) that construct
  their own regions or read the level directly are not covered. They use the
  same region bridge only if they go through vanilla `PathNavigation`.
- Existing paths are not forcibly recomputed when a structure moves. The next
  path computation sees the new pose; a mob already following a now-invalid
  path relies on vanilla stuck/timeout handling.
- Rotated/scaled path sampling is centre-based (see limitations above).
- Natural mob spawning on structures, minecart/boat navigation, and
  structure-aware mob griefing remain separate gaps.
- Client-side path debug rendering is not added; the existing vanilla debug
  path subscription shows the nodes that the server computed.
