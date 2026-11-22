# Structure boundary logic

Status: implemented (P3 first slice). This document describes the explicit
cross-boundary graph between the host Minecraft world and blocks that live
inside a Crythst physics structure. It is the companion to
[`world-logic-parity.md`](world-logic-parity.md), which lists the broader parity
audit.

Legend: **OK** = implemented with tests; **PARTIAL** = implemented with a
documented approximation; **NOT DONE** = still missing.

---

## 1. Why a separate bridge

A physics structure is a world-space rigid body plus an auxiliary void
`ServerLevel` (`EmptyPhysicsLevel`) that owns its block logic. The auxiliary
copy is a real level for ticking, but it is not the host level:

- host neighbour updates never reach a structure voxel;
- a host lever is not visible to structure redstone wire;
- a structure lever has no host block to power;
- a hopper cannot see a container on the other side;
- host fluid does not flow into the structure volume.

The bridge is deliberately a **small explicit graph**, not a full second level
facade. It lives in
`io.mikaple.crythst.integration.minecraft.boundary` and is owned by
`LevelPhysics#boundary()`.

The coordinate mapping is always structure-local:

```
host block centre  <->  structure voxel  <->  EmptyPhysicsLevel.RegionView
```

Auxiliary coordinates never appear in a public API. The only public handles are
`PhysicsStructure`, structure-local `BlockPos`, and
`EmptyPhysicsLevel.RegionView` (which already existed for the P2 level view).

---

## 2. Coordinate and face mapping

`BoundaryFaceMapping` maps between a structure's local voxel grid and the host
world through the live pose:

- `localDirection(structure, worldDirection)` rotates the world direction into
  structure space and picks the dominant local axis.
- `worldDirection(structure, localDirection)` is the inverse.
- `hostBlock(structure, x, y, z)` is the host block containing the voxel
  centre.
- `hostBlockOutside(structure, face)` is the adjacent host block.
- `touchingDistance(blockScale)` / `searchRadius(blockScale)` account for
  structures whose `blockScale` differs from 1.

`StructureBoundaryIndex` caches the external shell of a volume: every face whose
neighbour lies outside the voxel bounds. Internal cavity faces are not boundary
faces because the host world cannot touch them. The shell depends on the
volume bounds and `contentRevision`, not on the moving pose, so a sliding or
tumbling structure does not rebuild it every tick.

**Limitation (PARTIAL):** for arbitrary rotations a 45-degree face still has to
choose one of the six host directions. Axis-aligned structures are exact. For
`blockScale != 1`, a host block can touch several structure voxels; the bridge
notifies all of them for neighbour delivery and uses the nearest voxel for a
signal read.

---

## 3. Host -> structure redstone

`ServerLevelBoundaryMixin` injects at the return of
`ServerLevel.updateNeighborsAt(...)` and
`updateNeighborsAtExceptFromFacing(...)`. When the changed host block is near a
structure, the bridge:

1. finds every structure voxel the host block physically touches;
2. maps the structure voxel to its auxiliary position through `RegionView`;
3. calls `AuxiliaryServerLevel.neighborChanged(auxPos, changedBlock, null)`
   once per voxel per tick.

The auxiliary level's `getSignal` / `getDirectSignal` overrides answer reads for
positions just outside a region by:

1. mapping the outside auxiliary position to the host block;
2. rotating the local query direction to the host world;
3. returning `hostLevel.getSignal(hostBlock, worldDirection)` (or the direct
   signal variant).

This is what lets structure wire, torches, repeaters, observers and comparators
read host levers, buttons, torches, redstone blocks and repeaters as if they
were adjacent.

Covered: **OK** for host lever, button, torch, repeater, observer, redstone
block and any custom `BlockState#getSignal`/`getDirectSignal`.

---

## 4. Structure -> host redstone

`AuxiliaryServerLevel.updateNeighborsAt(...)` forwards the changed structure
voxel to the bridge after the void copy has notified its own neighbours. The
bridge walks the external faces of that voxel, maps each to the adjacent host
block, and calls `hostLevel.neighborChanged(hostBlock, structureBlock, null)`.

Separately, `SignalGetterBoundaryMixin` injects into `SignalGetter`'s default
`getSignal` / `getDirectSignal`. If the queried host position is air and belongs
to a structure voxel, the signal is evaluated from the live structure block
state in the auxiliary level. This is intentionally **not** a
`Level#getBlockState` override: host collision, AI, pathfinding and fluid
queries must keep seeing host-world air, otherwise structure collision would be
counted twice.

Covered: **OK** for structure lever, button, repeater, observer, redstone block
and any state whose `getSignal` only depends on its own state.

**Limitation (PARTIAL):** a structure signal source that calls back into its
level for neighbour state sees the auxiliary copy, not the host level. The
bridge answers boundary-adjacent reads, but a custom mod block that stores host
`BlockPos`/`Level` references at construction time needs a real structure level
facade (still a parity gap).

---

## 5. Structure -> structure

Two structures that touch have separate auxiliary regions; their host block
positions are adjacent. Two mechanisms cover the gap:

- signal reads: `auxiliarySignal` maps the outside position to the host block
  and calls `hostLevel.getSignal`, whose mixin resolves the adjacent structure
  voxel. `BoundaryUpdateGuard.beginSignal` caps recursive A -> B -> A chains.
- neighbour delivery: when a structure voxel changes, `onAuxNeighborUpdate` also
  calls `findStructureVoxel(hostBlock)` and delivers `neighborChanged` directly
  to the touching voxel of the other body.

Covered: **OK** for power/neighbour exchange. **PARTIAL** for very small
`blockScale` where one host block may overlap more than two structure voxels;
the nearest-voxel rule is used for signals.

---

## 6. Hoppers and containers

The bridge uses two complementary paths so item stacks, data components and
container NBT are never re-created from snapshots.

### Structure hopper -> host container

`AuxiliaryServerLevel` overrides `getBlockState`, `getBlockEntity` and
`getFluidState` for positions just outside its regions. Vanilla
`HopperBlockEntity#getContainerAt` therefore sees the real host chest/barrel/
furnace/hopper block and its live `Container`. Vanilla hopper extraction,
insertion, slot restrictions, item components and the eight-tick cooldown all
run unchanged. The hopper's own NBT is mirrored back to the structure by the
existing block-entity mirror pass.

### Host hopper -> structure container

The bridge scans loaded host chunks around each structure for
`HopperBlockEntity` positions (cached for 20 ticks / per content revision) and
runs `HopperTransfer` one item at a time. It reuses the public
`HopperBlockEntity.addItem` routine and vanilla's cooldown through the
`HopperBoundaryAccessor` mixin. Both the host hopper and the structure container
are live objects; only one item moves per operation and a failed insert restores
the original stack.

Covered: **OK** for single chests, barrels, furnaces, hoppers, dispensers,
droppers and any `Container` block entity on a straight face.

**Limitations (PARTIAL):**
- double chests across the boundary are not stitched; vanilla
  `ChestBlock#getContainer` runs in the auxiliary level and only sees the
  structure side;
- `WorldlyContainerHolder` blocks that build a wrapper around the level (for
  example a composter) may need a full structure level facade;
- item entities dropped by a structure hopper are handled by the existing
  entity projection pass, not by this bridge.

---

## 7. Fluid at the boundary

`StructureBoundaryBridge.tickFluidBoundary` scans the cached external shell
round-robin under a per-tick budget. `FluidBoundaryPolicy` is a pure decision
table:

| inside | host | bridge-owned host fluid | action |
|---|---|---|---|
| fluid | air/replaceable | no | `FLUID_TO_HOST` |
| air/replaceable | fluid | no | `FLUID_TO_STRUCTURE` |
| fluid | fluid | yes | none (already mirrored) |
| air | fluid | yes | `REMOVE_HOST_FLUID` |
| solid | anything | any | none |

- structure fluid -> host: a legacy fluid block is placed with update flags,
  the host fluid tick is scheduled, and the position is recorded as
  bridge-owned;
- host fluid -> structure: the matching structure boundary cell is set in the
  auxiliary level (which mirrors into the volume/side table) and its fluid tick
  is scheduled;
- removal: when the structure no longer supplies the fluid, only a host block
  that is still the exact bridge-owned fluid block is removed.

Both directions are bounded (`MAX_FLUID_TO_HOST_PER_TICK`,
`MAX_FLUID_TO_STRUCTURE_PER_TICK`, `MAX_FLUID_FACE_SCANS_PER_TICK`) and
loop-suppressed. Bridge-owned host fluids are also TTL-pruned.

Covered: **OK** for water/lava sources and flowing fluid on an exposed face.

**Limitations (PARTIAL):**
- fluid pressure/flow direction is evaluated separately in each level; the
  bridge transfers fluid at face granularity, not a shared flow graph;
- a natural host fluid mirrored into an air structure cell duplicates the
  source until one side drains (bounded per tick, but not physically
  conserving);
- waterlogging and fluidlogged mod blocks are only handled when their
  `BlockState#canBeReplaced` / `getFluidState` agrees with vanilla.

---

## 8. Game events

Host -> structure events are forwarded by `ServerLevelBoundaryMixin` into
`StructureBoundaryBridge.onHostGameEvent` for points inside or one voxel around
a structure. The existing `StructureGameEvents` bridge continues to forward
auxiliary -> host events; the boundary guard suppresses the echo back into the
copy.

Covered: **OK** for sculk/vibration listeners, note blocks and any
`GameEvent` dispatched through `Level#gameEvent`.

**Limitation (PARTIAL):** the event context source entity is only kept when it
belongs to the target level; structure copies never expose void entities to
host listeners.

---

## 9. Budgets, loops and diagnostics

All bridge work is server-thread only and non-loading. Chunks are read with
`getChunkNow`; the bridge never calls `getChunk` to force a load.

Per tick:

| budget | default |
|---|---|
| host neighbour lookups | 4096 |
| auxiliary neighbour lookups | 4096 |
| host hopper transfers | 256 |
| fluid face scans | 2048 |
| fluid -> host | 128 |
| fluid -> structure | 128 |
| game events | 256 |
| touching voxels per host update | 64 |
| host hopper scan chunks | 512 |

`BoundaryUpdateGuard` prevents feedback:

- host -> structure dispatch suppresses the structure -> host return path;
- structure -> host dispatch records the origin structure id; a two-body chain
  may continue to the other body but not bounce back to the sender;
- per-tick dedupe sets collapse repeated notifications for the same host/aux
  position;
- signal evaluation is capped at 32 nested reads and dispatch at 24 nested
  bodies.

`LevelPhysics#voidTickStatus()` now includes a `boundary(...)` segment with
counters for neighbour forwarding, hopper pulls/pushes, fluid transfers/
removals, game events, dropped work, guard drops, cached host hoppers and
failures. `/crythst voiddebug` prints that status line.

---

## 10. Files

Main code:

- `integration/minecraft/boundary/StructureBoundaryBridge.java` - tick phases,
  host/structure dispatch, fluid/hopper/game-event passes, diagnostics.
- `integration/minecraft/boundary/StructureBoundaryIndex.java` - cached
  external shell and live state lookup.
- `integration/minecraft/boundary/BoundaryFace.java`,
  `BoundaryFaceMapping.java` - coordinate/face mapping.
- `integration/minecraft/boundary/BoundarySignals.java` - pure signal formula.
- `integration/minecraft/boundary/HopperTransfer.java` - vanilla-parity
  one-item transfer.
- `integration/minecraft/boundary/FluidBoundaryPolicy.java` - pure fluid
  decision table.
- `integration/minecraft/boundary/BoundaryUpdateGuard.java`,
  `BoundaryWorkBudget.java` - loop suppression and bounded work.
- `integration/minecraft/EmptyPhysicsLevel.java` - boundary fallbacks for
  auxiliary block state/block entity/fluid/signal plus the external `RegionView`
  point mapping; tick phases are unchanged.
- `integration/minecraft/LevelPhysics.java` - owns the bridge and ticks it
  after edit mirroring.
- `mixin/boundary/SignalGetterBoundaryMixin.java`,
  `ServerLevelBoundaryMixin.java`, `HopperBoundaryAccessor.java` - host hooks.

Tests:

- `BoundaryFaceMappingTest` - shell faces, host voxel centres, rotated
  direction round-trip, block-scale search radii.
- `BoundarySignalTest` - redstone block/lever/torch signal and direct signal.
- `HopperTransferTest` - one-item pull, stack merge, full destination, item
  mismatch.
- `FluidBoundaryPolicyTest` - all policy rows and bridge-owned removal.
- `BoundaryUpdateGuardTest` - nesting, depth caps, return-path suppression and
  budgets.

---

## 11. Still missing in this area

- A real `Level`/`BlockGetter` facade that makes structure blocks visible to
  vanilla AI, minecarts/boats and arbitrary mods. The boundary bridge
  intentionally does not override host `getBlockState`.
- Explosions applying structure impulses and damaging host entities. That is
  owned by the separate explosion bridge.
- Double-chest stitching and full `WorldlyContainerHolder` support across the
  boundary.
- A shared redstone/fluid graph with deterministic ordering between a moving
  face and the host block it occupies. The current bridge is event-driven at
  host block resolution.
- Persisting bridge-owned host fluid ownership across save/load. The map is
  transient and only used to remove mirrors within the current session.
