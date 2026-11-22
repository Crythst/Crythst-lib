# Multiplayer block-entity sync (protocol v3)

Crythst replicates block-entity NBT and transient animation state for physics
structures. Remote clients can therefore render signs, banners, beds, skulls,
chests and modded block entities with their real `BlockEntityRenderer`, and
chest lids follow the server's opener count.

This page is the block-entity extension of
[`multiplayer-sync.md`](multiplayer-sync.md). Geometry, poses, sessions and
removal semantics are unchanged; protocol version is now **3**.

## What is sent

| Data | Channel | When |
| --- | --- | --- |
| Full block-entity map for a body | `crythst:structure_block_entities` | new body, geometry/volume change, player join or full resync |
| Dirty additions/NBT changes | `crythst:structure_block_entities` | block-entity NBT changed |
| Chest opener counts | `crythst:structure_block_entities` | lid opened/closed (NBT omitted) |
| Removed positions | `crythst:structure_block_entities` | block entity destroyed or filtered out |
| No block entities | no packet | geometry snapshot clears the client map |

Block-entity data is **never sent every tick**. The server captures the live
map only when `PhysicsStructure#blockEntityRevision()` changed, or when a chest
opener count changed, and then sends the minimal dirty set.

## Wire format

### `StructureBlockEntityPayload` (S2C)

```text
VarInt   protocolVersion      // must equal 3
VarLong  syncId               // same session as the geometry snapshot
VarInt   updateCount
  repeated StructureBlockEntityUpdate
```

### `StructureBlockEntityUpdate`

One fragment of one body's revision.

```text
VarLong  bodyId
VarLong  baseRevision         // ignored for replace; required to match on dirty
VarLong  revision             // new revision
boolean  replace              // true on every fragment of a full replacement
VarInt   partIndex            // 0-based
VarInt   partCount
VarInt   entryCount
  repeated entry:
    VarInt   x, y, z          // structure-local voxel, relative to bounds.min
    boolean  hasNbt
    NBT      data             // omitted for animation-only updates
    VarInt   openCount + 1    // 0 = no animation; 1.. = chest opener count
VarInt   removedCount
  repeated removed:
    VarInt   x, y, z
```

Local coordinates use the same space as `StructureSnapshot.bounds()` and the
voxel cell array: `(0,0,0)` is `bounds.min`. Entries with `hasNbt == false`
only update transient state and keep the client's existing NBT (used for chest
opens/closes).

Large bodies are split at entry granularity into `partCount` fragments. A full
replacement sets `replace = true` on every fragment; part 0 starts the
accumulator and the last part commits the new immutable map. A dirty update
normally has `partCount = 1` and `replace = false`; if a dirty set is large
enough to fragment, all parts share the same `baseRevision` and are applied
atomically on the last part.

## Server dirty tracking

`StructureSyncServer` keeps, per level and body:

- the last successfully published block-entity map,
- its global monotonically increasing revision,
- the structure's `blockEntityRevision` and chest opener counts (checked each
  tick, without re-reading NBT unless something changed).

`StructureBlockEntityDelta.compute(...)` produces:

- **added** entries: full NBT;
- **NBT changed** entries: full NBT;
- **animation-only changed** entries: opener count with `hasNbt = false`;
- **removed** positions for block entities that no longer exist.

The source map is captured from `EmptyPhysicsLevel#captureBlockEntities` and
`#captureBlockEntityOpenCounts`, with the persistence side table
(`StructureBlockEntityData`) as a fallback when no auxiliary level exists.
Capture is non-loading and only visits registered block-entity positions.

If a structure's geometry changes or gets a new body id (split/merge), the old
block-entity state is dropped server-side and a full replacement is sent after
the geometry snapshot. A structural removal removes the body's NBT on the
client when the `structure_remove` or replacement geometry arrives.

## Client cache

`ClientStructureCache` stores an immutable `ClientBlockEntitySnapshot` per
body id. Each entry is keyed by `StructureVoxelPos` (body id + local voxel) and
contains:

- a defensive copy of the NBT,
- the transient opener count,
- the body revision the map represents,
- a separate `dataRevision` that only changes when NBT or the entry set
  changed. The renderer reuses synthetic block entities for animation-only
  updates so an in-flight chest lid does not snap closed.

Renderers only read this cache; packet handling and render-thread reads both
happen on the client thread. API access is available through
`ClientStructureContainer#blockEntities(bodyId)` and
`#blockEntityAt(bodyId, x, y, z)`, plus the static
`ClientStructureCache` accessors.

Application rules:

- updates with another `syncId` are ignored;
- unknown body ids are ignored;
- a dirty update is applied only when `baseRevision` equals the cached
  revision;
- a mismatch drops that body's cached NBT and requests a rate-limited full
  resync, rather than rendering a mix of old and new inventory/sign data;
- a geometry snapshot for a body clears its block-entity map first;
- `structure_remove` and full-snapshot resets clear block entities and any
  in-progress fragment accumulator.

## Rendering

`StructureBlockEntityRenderer` consumes both sources:

- singleplayer: `StructureBlockSnapshots.Snapshot` (integrated server);
- multiplayer: `ClientStructureSnapshot` for geometry/pose and
  `ClientBlockEntitySnapshot` for NBT/animation.

It reconstructs a synthetic vanilla/client `BlockEntity` with
`BlockEntity.loadStatic(...)`, submits the vanilla render state at the rotated
and scaled structure voxel, and drives `ChestBlockEntity.triggerEvent(1, ...)`
from the networked opener count. Chest lid controllers are ticked only while a
lid is actually moving.

## Limits and bandwidth

New constants in `StructureSyncLimits`:

| Constant | Value | Meaning |
| --- | --- | --- |
| `MAX_BLOCK_ENTITY_ENTRIES` | 4,096 | entries retained per structure |
| `MAX_BLOCK_ENTITY_ENTRIES_PER_UPDATE` | 2,048 | entries in one fragment |
| `MAX_BLOCK_ENTITY_REMOVALS_PER_UPDATE` | 4,096 | removals in one fragment |
| `MAX_BLOCK_ENTITY_PARTS` | 1,024 | fragments for one body revision |
| `MAX_BLOCK_ENTITY_UPDATES_PER_PAYLOAD` | 1,024 | fragments per packet |
| `MAX_BLOCK_ENTITY_ENTRY_BYTES` | 64 KiB | one NBT tag above this is skipped |
| `MAX_BLOCK_ENTITY_BYTES_PER_STRUCTURE` | 256 KiB | per-structure NBT budget |
| `MAX_BLOCK_ENTITY_UPDATE_BYTES` | 256 KiB | one fragment above this is rejected |
| `TARGET_BLOCK_ENTITY_BATCH_BYTES` | 512 KiB | soft payload target |

Oversized entries are skipped and counted (`blockEntitySkipped` in
`StructureSync.serverStatusText()`); the rest of the structure still syncs. A
body that cannot fit is never sent as one oversized packet. Dirty sets are
usually a handful of entries, and an open/close cycle is a few bytes because
the chest NBT is not resent.

## Diagnostics

`StructureSync.clientStatusText()` now reports:

```text
blockEntityBodies= blockEntityEntries= blockEntityRevision=
blockEntityPayloads= blockEntityEntriesApplied=
blockEntityDesyncs= unknownBlockEntityIds=
```

`StructureSync.serverStatusText()` reports `blockEntityPayloads`,
`blockEntityFragments`, `blockEntityEntries` and `blockEntitySkipped`.

## Limitations

- **Modded block entities are only as portable as their NBT.** A block entity
  whose renderer needs live world/level state, registered capabilities, an open
  menu, or data not present in `saveWithFullMetadata` cannot be reproduced from
  NBT alone. Invalid or unknown NBT is skipped by the renderer and falls back to
  the block model.
- **Clients without the block-entity channel are skipped.** The server checks
  `ServerPlayNetworking.canSend(player, StructureBlockEntityPayload.TYPE)` and
  omits block entities for older clients; geometry and pose sync continue.
- **No live container interaction.** This sync makes chest inventories, sign
  text and similar data render correctly; it is not a server-authoritative
  remote container GUI. Moving items from a remote client is out of scope.
- **Per-entry/per-structure caps can omit very large block entities** (for
  example a book with enormous written content or an oversized modded tag).
  Those entries are reported in diagnostics and fall back to the block model.
- **Transient state other than chest opener count is not modelled.** Ender
  chests and shulker boxes use private animation controllers and remain static.
- **NBT revisions are per server session.** A full resync re-sends every
  published block-entity map with the current revision; clients never
  interpolate or merge block-entity state across sessions.
- **The C2S request remains the only recovery channel.** A base-revision
  mismatch triggers the existing rate-limited `structure_request`; there is no
  separate block-entity-only resync request, so recovery costs one geometry
  full sync.

## Test

```bash
./gradlew test --tests 'io.mikaple.crythst.network.structure.StructureBlockEntitySyncTest' \
  --tests 'io.mikaple.crythst.client.network.ClientBlockEntityCacheTest' --no-daemon
```

The tests cover codec round-tripping (NBT, animation-only entries, removals),
protocol-version rejection, dirty-set diffing, fragmentation/part caps,
oversized-entry rejection, stale base-revision handling, stale body removal and
geometry-reset clearing.
