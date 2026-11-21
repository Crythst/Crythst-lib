# Structure persistence

Crythst can save the physics structures of a dimension and rebuild them when the
dimension loads again. The implementation lives in
`io.mikaple.crythst.integration.minecraft.storage` and is intentionally separate
from the live physics/render code.

## Wiring

Add one line to `CrythstFabric.onInitialize()` (next to
`PhysicsTicker.register()`):

```java
StructurePersistence.register();
```

`register()` is idempotent and does three things:

* `ServerLevelEvents.LOAD` – read the level's saved records and recreate missing
  structures.
* `ServerLifecycleEvents.BEFORE_SAVE` – auto-discover every live structure and
  capture it before vanilla writes the level's saved-data files.
* Log a single registration message.

No mixins, config keys or `fabric.mod.json` changes are required.

## Data file

`StructureSavedData` is a normal `SavedData` registered as
`crythst:structures`. Vanilla stores it per dimension at:

```
<world>/data/crythst/structures.dat
```

The file is written by vanilla's `SavedDataStorage`; Crythst only marks the data
dirty. The current NBT format version is `1`.

Top level:

```
Version     int
Structures  list of structure compounds
```

One structure compound:

```
Id              int[4]   stable UUID (not the Bullet body id)
Pose            X,Y,Z, QX,QY,QZ,QW
Volume          bounds, solid mask, mass/material palettes, optional states
Parameters      body type, material, collision filter, collider options,
                mass scale, damping, CCD
BlockEntities   optional list of {X,Y,Z,Data} structure-local voxels
```

`SavedStructureVolume` stores:

* inclusive/exclusive `IntBounds3` bounds;
* one solid flag per cell;
* per-cell mass and `PhysicsMaterial`, encoded as palettes plus index arrays;
* an optional block-state palette, encoded with
  `NbtUtils.writeBlockState(BlockState)` and read with
  `NbtUtils.readBlockState(HolderGetter<Block>, CompoundTag)`;
* `-1` state indices for cells without an explicit state. A solid cell without a
  state reports `Blocks.STONE` when queried.

## What is captured

`SavedStructureVolume.fromStructure(PhysicsStructure)` copies:

* `BlockVoxelVolume` – real `BlockState`s and per-cell mass/material from the
  source level;
* `MutableVoxelVolume` – solid cells, per-cell mass/material, and any states held
  in `StructureBlockStates` (the side table used by player edits). Cells without
  a side-table state fall back to `Blocks.STONE`.

`StructureSavedData.capture(ServerLevel, LevelPhysics)` also captures block
entity NBT for `BlockVoxelVolume` structures. For every solid voxel with a block
entity it calls exactly:

```java
BlockEntity be = blockVoxelVolume.level().getBlockEntity(new BlockPos(x, y, z));
CompoundTag nbt = be.saveWithFullMetadata(blockVoxelVolume.level().registryAccess());
```

`saveWithFullMetadata(HolderLookup.Provider)` (26.3) includes `id`, `x`, `y`, `z`
and the block entity's custom data. `saveCustomOnly` would be enough for a future
in-place reattachment but would lose the self-describing metadata; Crythst uses
the full form. `MutableVoxelVolume` has no block-entity source.

## Restore behaviour

On load, each record is decoded with the level's block registry
(`Registries.BLOCK`) and rebuilt with `LevelPhysics.createStructure(...)`. The
saved pose is applied again with `PhysicsStructure.teleport(...)` to reset the
render interpolation buffer. The collider is rebuilt from the saved cells, so
mass, inertia and centre of mass are those of the snapshot.

Restored volumes are fresh `MutableVoxelVolume` instances. This keeps them
editable by the existing interaction layer. Their block states are copied into
`StructureBlockStates` for every solid cell (stone when the snapshot had none).

Duplicate protection:

* every record has a persistent UUID; restored structures are tracked by that
  UUID in the data object and are never created twice by the same data instance;
* as a second guard, a record is skipped if a live structure already has exactly
  the same bounds and pose.

Removal:

* `capture` only keeps structures currently returned by
  `LevelPhysics.structures()`;
* a restored structure that disappeared from that list is removed from the saved
  records, and its physics body is closed if it was still open;
* an ordinary `LevelPhysics.removeStructure(...)` therefore persists as a
  deletion on the next save.

## Not persisted / known limitations

### Identity and lifetime

* The persisted UUID is Crythst's own identity. The Bullet body id and the
  `PhysicsStructure` Java object identity are not stable across reloads.
* Persistence is a snapshot of whatever is in `LevelPhysics.structures()` at
  save time. An external mod that removes a structure from the physics world
  without closing it gets it closed on the next capture.
* Structures created after `ServerLevelEvents.LOAD` are captured on the next
  save, but they are not merged with records that could not be restored. A
  corrupt or unrestorable record is logged and then dropped by the next capture.
* There is no cross-dimension link and no “structure plot” ownership model. Each
  dimension has its own file and records.

### Block entities

* Block-entity NBT is captured and stored, but it is **not reattached** to a live
  block entity on load. A restored structure is a detached voxel snapshot, not
  blocks inserted into the Minecraft grid, so there is nowhere to attach it yet.
  The data remains available through `SavedStructure.blockEntities()` for a
  future in-place reattachment or custom block-entity proxy.
* Client-only block-entity state (render-only fields, client animation/tick
  state, client-side tile data) is not part of the server's
  `saveWithFullMetadata` output and is never persisted.
* For `MutableVoxelVolume` there is no block entity at all.

### Block states and ticking

* Real states come from `BlockVoxelVolume` or `StructureBlockStates`. A plain
  `VoxelVolume`/`MutableVoxelVolume` with an empty side table is restored as
  stone.
* `EmptyPhysicsLevel` only special-cases `BlockVoxelVolume`. A restored structure
  uses a `MutableVoxelVolume`, so its auxiliary void-level copy is made of stone
  even when the saved snapshot contained real states. Gameplay logic that needs
  the original state should read `StructureBlockStates` (restored by this
  module) rather than the void-level blocks.

### Parameter fidelity

A live `PhysicsStructure` does not expose its collision filter, damping, greedy
merge or max-cell options. `StructureParameters.from(PhysicsStructure)` therefore
falls back to `CollisionFilter.DEFAULT`, zero damping and
`VoxelColliderOptions.DEFAULT` (with the structure's block scale) when
auto-discovering a structure. Those values are serialized from then on, but they
cannot be recovered from a structure that existed before the first capture.
`userData` is deliberately never serialized.

### No player-placed volume growth

The snapshot keeps the original bounds. Restored structures cannot grow beyond
those bounds; the interaction layer's placement still requires the target cell to
already be inside the volume. To support growth, the volume bounds and the
persisted snapshot would need a versioned expansion format.

### Multiplayer

There is no network synchronization for structures or block states yet. The
persistence layer is server-authoritative and singleplayer-first; remote clients
still rely on the existing client-side approximation/rendering path.

### Save cost

Capture walks every voxel of every live structure to read states, mass/material
and block entities. For very large structures this is a save-time cost and can
produce a large `.dat` file. There is no size cap or throttling in this first
version.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/storage` contains pure
tests that do not start a Minecraft server:

* `SavedStructureVolumeTest` – solid/mass/material snapshot round-trip, NBT
  round-trip, bounds handling, and pose/mass/centre-of-mass preservation after
  rebuilding a `StructureDef`.
* `StructureSavedDataTest` – `SavedDataType` codec round-trip through `NbtOps`,
  stable UUID replacement, pose and block-entity NBT preservation, empty data,
  and registry-free volumes.

Block-state NBT needs a real block registry and is therefore only exercised by
the live server path, not by these pure tests.
