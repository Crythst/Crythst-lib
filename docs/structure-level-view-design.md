# StructureLevelView design (Sable/CA compatibility target)

Status: design only. This is the P0 item from `API_GAP_ANALYSIS.md`; the native
API does not implement it yet.

## Problem

Sable's `SubLevel` is used by Create Aeronautics as a **Level**: block/fluid/block
entity access, block writes, particles, sounds, chunk/registry access, entity
spawning, and plot-local coordinates. Crythst structures are world-space rigid
bodies with an auxiliary void `ServerLevel` that stores a remapped copy of the
blocks for ticking. That copy is an implementation detail, not a usable per-
structure `Level`.

To let code written against a sub-level run on Crythst (after import/package
porting), Crythst needs a first-class **structure level view** that presents a
structure's blocks as a level, without exposing the void-region remapping.

## Proposed API

```java
public interface StructureLevelView extends BlockGetter /* + CollisionGetter */ {
    PhysicsStructure structure();
    Level hostLevel();                 // dimension the structure lives in
    boolean isClientSide();

    // Block/fluid/BE access in structure-local voxel coordinates.
    BlockState getBlockState(BlockPos localPos);
    FluidState getFluidState(BlockPos localPos);
    BlockEntity getBlockEntity(BlockPos localPos);
    boolean setBlock(BlockPos localPos, BlockState state, int flags, int recursion);
    boolean setBlockAndUpdate(BlockPos localPos, BlockState state);

    // World projection.
    Vector3d localToWorld(Vector3dc localMetres, Vector3d dest);
    Vector3d worldToLocal(Vector3dc world, Vector3d dest);
    BlockPos worldBlockToLocal(BlockPos worldBlock);
    BlockPos localBlockToWorld(BlockPos localBlock);

    // Side effects projected to the world.
    void playSound(Player except, BlockPos localPos, SoundEvent sound, SoundSource source,
                   float volume, float pitch);
    void addParticle(ParticleOptions particle, double x, double y, double z,
                     double dx, double dy, double dz);
    void addFreshEntity(Entity entity); // project position/velocity/rotation

    LevelChunk getChunk(int chunkX, int chunkZ); // if a real Level facade is needed
    RegistryAccess registryAccess();
}
```

Two implementation levels:

1. **Lightweight view** (recommended first): a `BlockGetter`/`CollisionGetter`
   adapter backed by the structure volume and the `EmptyPhysicsLevel` copy.
   This covers most queries and is safe to hand to raycasts, shapes, BERs and
   block-entity logic. Writes delegate to the structure volume + reindex +
   auxiliary level refresh.
2. **Full `Level` facade**: a `Level` subclass per structure (or a shared
   delegating Level with a per-thread current structure). This is required only
   if downstream code stores a `Level` reference or calls APIs that are not on
   `BlockGetter`. It is much heavier (chunk source, lighting, entities, tick
   lists, dimension data) and should be a later step.

## Coordinate model

- Native structure voxel `(x,y,z)` is the volume index. It may be negative.
- Local metres = `voxel * blockScale`; world = `structurePose * localMetres`.
- The auxiliary void level stores a region with a base offset. The view hides
  this with an internal `local -> region` mapping and never exposes region
  coordinates.
- `getBlockState(local)` should fall back to air outside the volume bounds.
- `setBlock` must reindex the structure, refresh the auxiliary level, refresh
  actor caches and invalidate terrain/render caches; return the previous state.
- Block entity positions are structure-local in the public view; the view
  translates to the auxiliary level's region positions internally.

## World side effects

- Sounds/particles use the transformed world position of the local block centre.
- `addFreshEntity` transforms the entity pose and velocity, and adds it to the
  host level (never the void level). For client-side calls, use the client
  level and mark the entity as client-only.
- `destroyBlock`/`removeBlock` call structure interaction, not vanilla world
  destruction.
- Redstone/neighbour updates: the auxiliary level already ticks block logic;
  the view should forward `updateNeighborsAt` in local space and let the void
  level handle it. Do not run neighbour updates in the host world at the world
  position.

## Container integration

- `StructureContainer` owns the view registry: one view per `PhysicsStructure`.
- A client view uses the render/network pose; writes are server-only.
- `StructureLevelView` is not stable across `reindex`/split/remove; document that
  callers must reacquire it, or keep a stable wrapper with an epoch counter.

## Compatibility boundary

- This view makes **ported** CA code possible; unmodified CA still imports
  `dev.ryanhcode.sable.*` and uses `SubLevel`, `LevelPlot`, `PlotChunkHolder`,
  render data and mixin interfaces. Those are not covered by this design.
- Do not claim a drop-in Sable replacement until the plot/assembly/render/
  network layers exist.

## Staged implementation

1. `StructureLevelView` query-only adapter + `StructureContainer.view(id)`.
2. Writes (`setBlock`, `setBlockAndUpdate`, `removeBlock`) with reindex/refresh.
3. World side effects (sounds, particles, entities).
4. `CollisionGetter`/`BlockGetter` shapes so raycasts and block entities use it.
5. Optional full `Level` facade for code that requires a `Level` reference.
6. Client view + network pose awareness.
7. Tests: coordinate round-trips, write/refresh, outside-bounds air, BE lookup,
   sound/particle world positions, no host-world block writes.
