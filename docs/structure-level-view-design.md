# StructureLevelView design and implementation

Status: **implemented (P2, lightweight view)**. A full `Level` facade, AI /
pathfinding integration and the host↔structure neighbour graph are deferred.

The original design targeted Sable/CA compatibility. This document now records
the concrete implementation and its boundaries.

## Problem

Sable's `SubLevel` is used by Create Aeronautics as a **Level**: block/fluid/block
entity access, block writes, particles, sounds, chunk/registry access, entity
spawning, and plot-local coordinates. Crythst structures are world-space rigid
bodies with an auxiliary void `ServerLevel` that stores a remapped copy of the
blocks for ticking. That copy is an implementation detail, not a usable per-
structure `Level`.

`StructureLevelView` presents one structure as a level-like object by mapping
its local voxels onto the per-structure region of that auxiliary void level.
The region base offset stays internal; game logic runs against the void level,
and writes are mirrored back to the structure volume through the P0 edit
propagation pass.

## Public API

Package: `io.mikaple.crythst.api.sublevel.view`

```java
public interface StructureLevelView {
    @Nullable PhysicsStructure structure();   // null for a network snapshot
    @Nullable Level level();                   // host dimension
    default boolean isClientSide();
    default long bodyId();
    double blockScale();
    IntBounds3 bounds();
    Transform3d pose();

    default int minBuildHeight();
    default int maxBuildHeight();
    default int getMinBuildHeight();
    default int getMaxBuildHeight();
    default int height();
    default boolean isWritable();
    default long epoch();
    default boolean isCurrent();

    BlockState getBlockState(BlockPos localPos);
    default FluidState getFluidState(BlockPos localPos);
    default @Nullable BlockEntity getBlockEntity(BlockPos localPos);
    default boolean isEmptyBlock(BlockPos localPos);
    default Stream<BlockState> getBlockStates(AABB localBox);
    RegistryAccess registryAccess();

    default @Nullable BlockState setBlock(BlockPos local, BlockState state, int flags, int recursion);
    default @Nullable BlockState setBlockAndUpdate(BlockPos local, BlockState state);
    default @Nullable BlockState removeBlock(BlockPos local, boolean moving);

    // native void-level target for future vanilla-operation redirection
    default @Nullable StructureOperationTarget operationTarget(BlockPos localPos);
    default @Nullable StructureOperationTarget operationTarget(BlockHitResult localHit);
    default @Nullable StructureOperationTarget operationTarget(BlockPos localPos,
            Vec3 localHitLocation, Direction direction);

    // local metres/voxels <-> world; sound/particle positions; play/add helpers
    Vector3d localToWorld(Vector3dc localMetres, Vector3d dest);
    Vector3d worldToLocal(Vector3dc world, Vector3d dest);
    Vector3d voxelCenterToWorld(int x, int y, int z, Vector3d dest);
    Vector3d worldToVoxel(Vector3dc world, Vector3d dest);
    BlockPos localBlockToWorld(BlockPos local);
    BlockPos worldBlockToLocal(BlockPos world);
    Vec3 soundPosition(BlockPos local);
    Vec3 particlePosition(Vector3dc localMetres);

    // exact shapes / collisions in local voxel coordinates
    VoxelShape localShape(...);
    VoxelShape localCollisionShape(...);
    List<VoxelShape> getBlockCollisions(@Nullable Entity entity, AABB localBox);
    List<VoxelShape> getCollisions(@Nullable Entity entity, AABB localBox);
    StructureBlockGetter blockGetter();
    StructureCollisionGetter collisionGetter();
}
```

`StructureLevelViews` is the factory/lifecycle surface:

```java
StructureLevelView view = StructureLevelViews.of(container, bodyId); // server or client
StructureLevelView view = StructureLevelViews.of(structure);        // server only
StructureLevelView view = StructureLevelViews.server(structure, host, physics);
StructureLevelView view = StructureLevelViews.client(structure, level, renderPose);
StructureLevelView view = StructureLevelViews.snapshot(networkSnapshot, level);
long epoch = StructureLevelViews.epoch(structure);
```

`StructureContainer.view(long bodyId)` is the recommended entry point:

```java
StructureLevelView view = container.view(bodyId);
BlockState state = view.getBlockState(new BlockPos(x, y, z));
```

`CrythstApi.view(container, bodyId)` and `CrythstApi.view(structure)` forward to
the same factories.

`StructureOperationTarget` is the native hook:

```java
StructureOperationTarget target = view.operationTarget(localPos);
// target.level()       -> auxiliary void ServerLevel
// target.auxiliaryPos() -> internal void block position (opaque)
// target.localPos()    -> public structure-local voxel
// target.hitResult()   -> hit rebuilt in auxiliary coordinates, or null

// Future redirector, deliberately not implemented here:
serverPlayerGameMode.useItemOn(player, target.level(), stack, hand,
        target.hitResult());
```

The target is only returned when its void chunk is loaded. A vanilla operation
therefore cannot force a synchronous chunk load.

### Coordinate model

- Local voxel `(x,y,z)` is exactly the structure volume grid; negative and
  non-zero bound minima are valid.
- Local metres = `voxel * blockScale`; world = `structurePose * localMetres`.
  The centre of a voxel is `((x + 0.5) * blockScale, ...)`.
- Queries outside the volume return air. Server queries never load a chunk: if
  the auxiliary copy's chunk is unloaded, the block reads as air.
- The view maps local voxels through `EmptyPhysicsLevel.regionView(structure)`:
  `RegionView.toAuxiliaryVoxel(x,y,z)` and `RegionView.toLocalVoxel(aux)` are the
  only remap. Auxiliary coordinates are opaque implementation details.
- Block-entity positions returned by server views are auxiliary positions
  internally; callers must use them only for structure-local block logic and
  never expose them as world coordinates.

## Server implementation

`io.mikaple.crythst.integration.minecraft.structure.view.ServerStructureLevelView`

This is a view over the auxiliary void level, not a parallel block abstraction.

- Reads delegate to `LevelPhysics.liveBlock(structure, x, y, z)`, the
  non-loading equivalent of `voidLevel.getBlockState(region.toAuxiliaryVoxel(...))`.
  `liveBlock` is the same copy vanilla block logic ticks; it returns `null` for
  air and for unloaded chunks, which the view reports as air.
- `getFluidState` is derived from that live state. `getBlockEntity` returns the
  live auxiliary block entity. Collision/outline shapes resolve exact
  `BlockState#getShape`/`#getCollisionShape` through those states.
- Writes call `ServerLevel#setBlock` on the **void** block grid, passing the
  caller's flags/recursion so vanilla neighbour and redstone updates run inside
  the void dimension. The P0 edit-propagation pass
  (`LevelPhysics.flushAuxiliaryBlockStates()` →
  `EmptyPhysicsLevel.flushBlockStateChanges()`) then:
  1. mirrors the new state into the structure volume and both side tables;
  2. reindexes the collider;
  3. refreshes/re-copies the auxiliary region and may split the body.
  The view then invalidates actor/collision/terrain caches and advances the
  epoch.
- The return value is the **previous block state**, or `null` when the view is
  read-only, out of bounds, the region/chunk is unavailable, or a write failed.
  Out-of-bounds writes are rejected; volume expansion stays with the
  interaction/assembly layer.
- A write that removes the last solid cell is removed through the P0
  propagation/`StructureSplitter` path.
- If a physics world exists but it has no auxiliary level (void level disabled
  or failed), the view falls back to direct editable-volume writing so standalone
  and degraded deployments keep working.

## Client implementation

`io.mikaple.crythst.integration.minecraft.structure.view.ClientStructureLevelView`

- Singleplayer: read-only over the integrated client's `PhysicsStructure`,
  volume/state side table and a sampled render pose supplied by
  `ClientPhysicsView` / the client container.
- Multiplayer: read-only over an immutable `ClientStructureSnapshot` palette and
  cells.
- Writes and removals always return `null`; `isWritable()` is `false`.
- `operationTarget` always returns `null`; a client view cannot redirect a
  vanilla server operation.
- `getBlockEntity` returns `null` until block-entity NBT is fully mirrored; it
  never falls back to a host-world block entity at the projected position.

## Collision and raycast adapters

- `StructureBlockGetter` implements vanilla `BlockGetter` over a view. Positions
  are local voxels, so `BlockState#getShape`, `canSurvive`, BER-style queries and
  `BlockGetter#clip` work against the structure's own neighbours.
- `StructureCollisionGetter` implements `CollisionGetter`. It returns exact
  `BlockState#getCollisionShape` results in local metre space and no host-world
  or entity collisions. `getChunkForCollisions` returns the view itself, so
  local chunk coordinates are always in range.
- `ViewStructureVoxelView` adapts a view to the existing selection package's
  `StructureVoxelView`. Existing `StructureRaycaster` / `StructureVoxelShapes`
  code can consume the new view without changing its algorithm.
- `StructureLevelView.localShape` / `localCollisionShape` use the same exact
  state shape rules directly.

## Lifecycle and epochs

A view is a thin wrapper around one structure object, not a cache.

- The view becomes stale after a write by another view, a reindex, a split or a
  removal.
- `epoch()` is a process-local monotonic revision per structure.
  `StructureLevelViews.advanceEpoch` is called by server writes. A writing view
  updates its own epoch, so it stays current; every older view becomes stale.
- `isCurrent()` compares the view's epoch with the structure's current epoch and
  also reports closed structures as stale. Callers should reacquire with
  `container.view(bodyId)`.
- Epochs are not persisted or sent over the network. Network snapshots are
  immutable and report `isCurrent() == true` until replaced.

## Container integration

- `StructureContainer.view(id)` is the canonical accessor.
- Server containers return a writable authoritative view.
- Client containers prefer the singleplayer live render structure, then the
  network snapshot with the same body id.
- `MinecraftPhysics.levelPhysicsSnapshot()` lets `StructureLevelViews.of(structure)`
  locate the owning world without creating one. That overload is server-side;
  client code should use the container so writes cannot be attempted against an
  integrated-server world from the render thread.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/structure/view/StructureLevelViewTest.java`

- local point/voxel ↔ world round-trip on a rotated, `blockScale = 0.5`
  structure;
- auxiliary `RegionView` local↔void voxel round-trip and out-of-region
  rejection;
- air/empty fluid/null block entity outside bounds and for an unloaded live
  block;
- fluid and block-entity lookup through the live auxiliary source;
- `setBlock`/`setBlockAndUpdate`/`removeBlock` updating volume, side table,
  collider and epoch, including out-of-bounds rejection (fallback path);
- exact slab collision shape and local-neighbour shape resolution, including the
  selection adapter and raycaster;
- client live view and network snapshot read-only refusal and no operation
  target.

## Deferred

1. **Full `Level` facade.** This view is not a `Level` subclass and does not
   provide chunks, lighting, entities, tick lists, day/night or dimension data.
   Code that stores a `Level` reference still needs a real facade.
2. **Player-operation redirector.** `operationTarget` exposes the void
   `ServerLevel`/position for a future redirect, but no mixin yet calls
   `ServerPlayerGameMode`, `useItemOn` or `destroyBlock` on it. Player
   interaction still uses `StructureInteraction`.
3. **AI / pathfinding integration.** No `PathNavigationRegion` / mob navigation
   bridge is implemented. The view and `CollisionGetter` adapter are the
   intended building block.
4. **Host ↔ structure neighbour graph.** Neighbour updates run inside the void
   dimension (including for view writes), but cross-boundary redstone, hoppers,
   fluids, game events and explosions are not wired.
5. **Entity projection.** Sounds and particles have world-position helpers and
   host-level play/add methods, but `addFreshEntity` is not added. Existing void-
   level entity projection remains the server-side path.
6. **Block-entity network sync.** Client views cannot return block entities yet.
7. **Command/selector integration.** Vanilla selectors and commands still see
   host-world blocks, not structure-local blocks.
8. **Structure outline replacement.** Selection/render can opt into the adapter,
   but the existing outline algorithm has not been replaced.
9. **Incremental view invalidation.** Epochs are per process and per structure;
   there is no persistent generation or network-synchronised revision yet.

## Compatibility boundary

- This view makes **ported** CA-style code possible; unmodified CA still imports
  `dev.ryanhcode.sable.*` and uses `SubLevel`, `LevelPlot`, `PlotChunkHolder`,
  render data and mixin interfaces. Those are not covered.
- Do not claim a drop-in Sable replacement until the plot/assembly/render/
  network layers exist.
