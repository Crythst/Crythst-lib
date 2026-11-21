# Terrain collision

Status: server-authoritative static Bullet bodies generated from Minecraft
blocks, refreshed per tick around the world AABB and motion reach of every
dynamic body.

## Why structures clipped through terrain

The previous cache was keyed off `body.aabb()` expanded by a fixed
`worldCollisionRadius` (8 blocks) and was only recomputed every
`worldCollisionRefreshTicks` (10 ticks). That produced three independent ways
for a structure to intersect terrain:

1. **Motion outran the cache.** A structure falling or pushed faster than
   roughly 0.8 blocks/tick could enter a section that had never been built.
   Physics had no collider there for up to ten ticks, so the body penetrated
   terrain before contact could be generated.
2. **The cap was global and first-body-wins.** `worldCollisionMaxSections` is
   64. With the radius margin a moderately large structure already spans
   `4 x 4 x 4 = 64` sections; the loop then stopped while iterating the first
   body and later bodies received no terrain at all. Large or tilted
   structures were covered only near one corner.
3. **Block edits deleted collision first.** `invalidateAt` removed the
   section body immediately and only rebuilt it on the next refresh. Any body
   over that section for one tick fell through the hole.

A fourth, smaller error was shape fidelity: every non-air block with a
non-empty collision shape was collapsed into a full one-metre cube. Bodies
therefore stopped on invisible cubes above slabs, carpets and snow, and the
visual block could appear to intersect the body.

## Current design

### Wanted region

`TerrainCollisionRegion` (pure, no Minecraft imports) computes, for every
non-terrain body:

```
current AABB
  + linear velocity * seconds until next physics advance
  + 1/2 * acceleration * seconds^2
  + angular speed * seconds * bounding-sphere radius
  + worldCollisionRadius static margin
  + worldCollisionLookahead along gravity
```

The result is capped per axis (`MAX_MOTION_EXPANSION = 64` blocks) so a
corrupt velocity cannot request millions of sections. `seconds` is the actual
`simulationDelta` passed by `LevelPhysics.tick`, not a hard-coded 1/20 s.

The current AABB sections are placed first in each body's request list, then
the motion-expanded sections nearest the body. Sections are selected by a fair
round-robin across bodies. The configured `worldCollisionMaxSections` is a
soft target: the effective cap is raised to cover every section intersecting
the current AABBs plus an anticipation budget (at least eight, at most 32
sections), up to a hard safety limit of 512. If even that is not enough, the
nearest sections are used and a rate-limited warning is logged.

### Refresh policy

`TerrainCollisionRegion.needsRefresh` returns true when:

- a block edit is queued (`invalidateAt`),
- a wanted section is missing and its retry backoff has elapsed,
- the wanted set differs from the cached set (the body entered a new section),
- or `worldCollisionRefreshTicks` elapsed (periodic verification/retry).

The wanted set is recomputed **every server tick**, not only on the periodic
refresh, so entering a new section builds its body before the next physics
step.

### No remove/recreate gap

Block edits and replacements are built eagerly:

1. the replacement section body is created on the physics thread,
2. the map is updated,
3. only then is the old native body removed.

If the replacement cannot be built yet (unloaded chunk or a failing block
state), the old body stays in place and the build is retried. There is never a
refresh in which a section is absent while a body still needs it.

Empty loaded sections are cached, so sky rows are not rescanned.

### Exact block shapes

`TerrainSectionVolume` keeps full cubes on the greedy merged path (one box per
large rectangular volume). Non-full states (slabs, stairs, fences, snow,
carpets, modded shapes) store the boxes returned by
`BlockState#getCollisionShape(...).toAabbs()` in absolute voxel coordinates.
They are emitted as `VoxelCellPart`s, so Bullet collides with the same surface
the player sees. Shapes are clamped to their owning block so a mod shape
cannot silently introduce geometry in an untracked neighbour section.

### CCD

`CcdMotionGuard` runs before each physics advance and enables Bullet CCD on a
dynamic body when its travel over the horizon exceeds half of its smallest
half extent (minimum 0.05 blocks). CCD is never switched off: Bullet's motion
threshold keeps resting bodies cheap, and disabling it again would make a
single fast tick permanently weaken the body. Explicitly configured CCD
(`StructureDef#ccd`) is preserved.

### Coordinate and origin handling

Terrain voxel coordinates are absolute block coordinates with
`blockScale = 1.0`. `VoxelColliderData` stores its centre of mass in those
voxel coordinates, and `VoxelColliderData#toCompoundShape()` emits child boxes
relative to that centre. The static body position is therefore the centre of
mass itself:

```java
position = sectionOrigin * scale + (centerOfMass - sectionOrigin) * scale
         = centerOfMass * scale
```

`TerrainSectionGeometry.staticBodyPosition` implements this and is covered by
negative-coordinate tests. `rebaseOrigin` shifts every Bullet body by the same
delta, so terrain bodies and structures remain aligned.

## Configuration

| Field | Default | Meaning now |
| --- | --- | --- |
| `worldCollisionEnabled` | `true` | Master switch. Disabling removes all terrain bodies. |
| `worldCollisionRadius` | `8.0` | Static safety margin around the AABB (blocks). |
| `worldCollisionRefreshTicks` | `10` | Maximum age of the cache / retry interval. Region changes and edits refresh immediately; this is no longer the motion horizon. |
| `worldCollisionMaxSections` | `64` | Soft target for native terrain bodies. May be raised to cover a body's current AABB (hard limit 512). |
| `worldCollisionLookahead` | `32.0` | Extra reach along gravity, useful for falling structures. |

`CcdMotionGuard` has no configuration; the thresholds are constants in
`physics/world/CcdMotionGuard.java`.

## Diagnostics API

`LevelPhysics` exposes an immutable snapshot:

```java
TerrainCollisionSnapshot snapshot = physics.terrainSnapshot();
// alias for render-thread call sites:
TerrainCollisionSnapshot renderSnapshot = physics.renderTerrainSnapshot();
```

`TerrainCollisionSnapshot` (`physics/world`) contains:

- `cachedSections`, `bodySections`, `emptySections`, `wantedSections`,
  `missingSections`, `boxCount`;
- `refreshCount`, `lastRefreshTick`, `lastRefreshReason`
  (`"region-changed"`, `"invalidation"`, `"missing-sections"`, `"periodic"`,
  `"ensure"`, `"never"`);
- `sections`: key, world-space section bounds, static `bodyId` (`-1` for an
  empty cached section), merged `VoxelBox` list and exact `VoxelCellPart` list;
- `regions`: one entry per non-terrain body with its current AABB, the
  motion-expanded AABB, required/planned section counts and
  `outsideTerrain` (true when the current AABB is not fully cached).

`TerrainCollision` also logs a rate-limited warning (at most once per body per
200 ticks) when a body's AABB is not fully covered.

### F3+F6 `physics_terrain` snippet

The debug renderer files are owned by another change. To add the layer:

```java
// CrythstDebugEntries
public static final Identifier PHYSICS_TERRAIN =
        Identifier.fromNamespaceAndPath("crythst", "physics_terrain");
// add PHYSICS_TERRAIN to CrythstDebugEntries.ALL

// ClientPhysicsView
private static volatile TerrainCollisionSnapshot TERRAIN;
// in tick(), after DEBUG = physics.renderDebugSnapshot():
TERRAIN = physics.renderTerrainSnapshot();
// in clear(): TERRAIN = null;
public static TerrainCollisionSnapshot terrain() { return TERRAIN; }

// CrythstPhysicsDebugRenderer.emitGizmos
if (all || enabled(CrythstDebugEntries.PHYSICS_TERRAIN)) {
    PhysicsGizmoRenderer.emitTerrain(ClientPhysicsView.terrain(), partialTicks);
}
```

Drawing suggestion:

- section bounds: faint white outline; empty sections need not be drawn;
- `section.boxes()`: `VoxelBox` occupies
  `[minX, minX + sizeX] x [minY, ...] x [minZ, ...]` in world metres at scale
  1; draw as a box outline, one colour per `bodyId`;
- `section.parts()`: same, but fractional; use a distinct colour so partial
  collision shapes are visible;
- `Region.expanded*`: yellow outline;
- `Region.outsideTerrain`: red outline and include `bodyId` in the label.

The snapshot is replaced atomically, so the renderer can read it directly; do
not call `PhysicsWorld` or `TerrainCollision` from the render thread.

## Tests

- `TerrainCollisionRegionTest` — AABB to section set (including negative
  coordinates and boundaries), velocity/angular/gravity expansion, expansion
  cap, nearest-section ordering, refresh policy, block-edit invalidation, and
  section bounds.
- `TerrainSectionVolumeTest` — exact partial-shape extraction, multi-box
  stairs, full-cube detection and coexistence of merged/partial cells.
- `TerrainSectionGeometryTest` — existing floor-semantics and centre-of-mass /
  body-position tests.
- `TerrainCollisionTest` (physics/world) — Bullet integration for positive and
  negative sections and terrain filtering.
- `CcdMotionGuardTest` — fast bodies get CCD, resting bodies do not.

## Limitations

- Terrain collision is still rebuilt from `ServerLevel#getBlockState` on the
  server thread. A large structure entering many new sections in one tick can
  cost a frame; the 512-body hard limit bounds the worst case.
- Chunks that are not fully loaded contribute no collision; the body's
  `outsideTerrain` flag and warning expose this rather than silently pretending
  the section is empty.
- Blocks whose collision depends on a neighbour more than one block away are
  not tracked by the section invalidation heuristic; vanilla shapes only
  depend on direct neighbours.
- The exact-shape path allocates a `VoxelCellPart` list for every non-full
  block. Terrain made mostly of full cubes keeps the fast merged path.
- `UnstuckResolver` is still a last-resort impulse based on the body centre; it
  does not perform general OBB de-penetration. It is not needed for the normal
  contact path once the terrain section exists.
- CCD prevents tunnelling through existing geometry; it cannot create a
  terrain body in a section that has not been built. The per-tick region check
  is what keeps that window closed.
