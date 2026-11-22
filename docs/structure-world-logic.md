# Structure world-logic parity

This document describes the vanilla tick phases Crythst runs for a physics
structure's auxiliary block copy, how edits flow back into the structure, and
which phases are intentionally omitted.

Implemented by:

- `EmptyPhysicsLevel` (`AuxiliaryServerLevel`, tick phases, edit mirroring,
  bounded work budgets, audio/particle projection),
- `LevelPhysics` (reindex/refresh after mirrored edits, diagnostics),
- `StructureEntityProjection` (entity projection; see
  [structure-entity-projection.md](structure-entity-projection.md)).

## Why the auxiliary level needs more than `tickChunk`

The auxiliary void `ServerLevel` exists so vanilla can run block logic that
would otherwise never execute for a moving structure. Calling only
`tickChunk` + `tickBlockEntities` misses the parts of a server tick that make
repeaters, observers, pistons, fire, crops, water and lava actually progress:

- scheduled block ticks,
- scheduled fluid ticks,
- block events queued by those blocks,
- neighbour/redstone updates produced by them.

The void level still must not run a full `ServerLevel#tick`: that would tick
the world border, weather, raids, entity AI, chunk-source loading, natural
spawners and other host-world systems inside an empty copy.

## Tick phases added (vanilla order)

`EmptyPhysicsLevel.tick(randomTickSpeed)` runs:

1. **Scheduled block ticks**, bounded by `MAX_BLOCK_TICKS_PER_TICK` (4096).
   Due ticks are collected from all active chunks, sorted by
   `TickPriority`/`subTickOrder`, then executed as one batch. Repeaters,
   observers, pistons, fire, crops and any mod block that calls `scheduleTick`
   execute here. Ticks scheduled by the batch run on the next game tick.
2. **Scheduled fluid ticks**, bounded by `MAX_FLUID_TICKS_PER_TICK` (4096),
   using the same global collect/sort/run order. Water/lava flow, fluid mixing
   and fluid-related scheduled updates execute here.
3. **Random block/fluid ticks** per active chunk section, using the same
   `getBlockRandomPos(..., 15)` and section semantics as vanilla
   `ServerLevel#tickChunk`, but without precipitation/thunder.
4. **Block events** in a bounded queue. `AuxiliaryServerLevel#blockEvent`
   queues during the tick and executes the state's `triggerEvent` in this
   phase, matching vanilla's deferred order. Outside a tick (for example an
   interaction), block events execute immediately so chest/note-block feedback
   is not delayed.
5. **Block entities** via `tickBlockEntities()`, which drives dispensers,
   droppers, hoppers, furnaces and other block-entity logic. A bounded
   fallback then directly invokes the state ticker for BEs that vanilla
   skipped because the chunk/entity section was not tickable or the ticker was
   not registered. Entries vanilla ticked are not run twice.

Only chunks in `activeChunks` (chunks that received copied blocks) are read.
`getChunkNow`/`getAllEntities` paths never force a chunk load.

The whole block-logic pass is limited to one run per `gameTime`. The auxiliary
clock is a host-delegating `DerivedLevelData`, so it advances and freezes with
the host. Saved sub-tick orders are relative bookkeeping only: restore assigns
fresh monotonic orders in the captured priority/order sequence, exactly like
vanilla pending ticks, and keeps delays relative to the current game time.

### Phases intentionally not run

- world border,
- weather cycle, precipitation, thunder,
- natural mob spawning / custom spawners,
- raids,
- entity AI and entity ticking (spawned entities are projected to the host
  level instead),
- chunk source loading/generation and ticket processing,
- player sleep/clock/world-time changes,
- debug synchronizers and profiler bookkeeping.

Those remain the host level's responsibility.

## Bounded work

Every phase has a hard budget:

| Work | Budget |
| --- | --- |
| scheduled block ticks | 4096 per tick |
| scheduled fluid ticks | 4096 per tick |
| block events | 4096 per tick (queue cap 8192) |
| neighbour updates | 65536 per tick |
| mirrored block changes | 4096 per tick |
| block-entity NBT mirrors | 256 per tick |

`EmptyPhysicsLevel.TickBudget` is a small pure counter used by these phases.
Dropped work is counted in `TickResult.dropped`. Tick failures are logged by
`reportTickFailure`: immediately on the first failure, then at most once every
200 ticks with a suppressed count.

## Edit propagation

`AuxiliaryServerLevel#setBlock` records every changed auxiliary position. The
record is suppressed while Crythst itself copies or clears a region, so the
copy cannot feed back into the structure.

After the tick phases, `flushBlockStateChanges()` maps each changed auxiliary
block back to structure-local voxels:

```text
localVoxel = auxiliaryPos - region.base + volume.bounds().min()
```

For each change it:

1. reads the live state from the loaded chunk (non-loading),
2. compares it with the volume/side table state (`shouldMirrorState`),
3. updates `MutableVoxelVolume` or `RestoredStructureVolume` (solidity, mass,
   material and state) and the `StructureBlockStates` side table,
4. removes/updates block-entity persistence data when a block entity appears
   or disappears,
5. returns the affected structures once per tick.

`LevelPhysics` then calls `PhysicsStructure#reindex` and refreshes the
block-entity actors once per changed structure, and runs the normal
split/remove-if-massless path. Edits originate in the auxiliary copy, so the
copy is already correct; re-indexing in place avoids re-copying the whole region
and allocating a new region base on every piston/door tick. Volume-bound changes
made by the placement/expansion path still use
`LevelPhysics#refreshStructure`, which re-copies the region.

Block-entity inventories change without a block-state change, so
`mirrorBlockEntities()` also copies live `BlockEntity` NBT into
`StructureBlockEntityData` on a bounded schedule.

### Feedback-loop guard

- copy/clear writes use a suppression depth,
- unchanged states are skipped,
- mirrored edits never write to the auxiliary level directly; the refresh pass
  recopies the volume with suppression enabled,
- changes outside the structure bounds are skipped and counted instead of
  growing the volume implicitly.

## Sounds, particles and block events

The auxiliary level has no players, so leaving audio/particles there means they
are silently lost. `AuxiliaryServerLevel` now maps every emitted point through
the owning structure's pose and scale:

- `playSeededSound` → host level at the transformed position,
- entity-sourced sounds → transformed entity position (players use their host
  position),
- `levelEvent` → host level at the transformed block position,
- `sendParticles` → host level at the transformed particle position.

A point outside every structure region is dropped during background ticking;
during an interaction it is forwarded unchanged because some vanilla blocks
emit at the player position, which is already in host space. Block events are
executed in the void copy; their sounds/particles are projected by the same
path, and the changed block state is mirrored for the structure renderer.

## Vanilla operation redirect (P0 architecture direction)

`StructureOperationRedirector` lets the server run vanilla block break/use/
placement semantics against the auxiliary void level instead of reimplementing
them. It is the primary path and is enabled by default.

Feature flag:

```json
{
  "useVoidLevelOperations": true
}
```

`CrythstConfig#useVoidLevelOperations` defaults to `true`. When enabled:

| Entry point | Vanilla path |
| --- | --- |
| `useOn(player, hit, hand)` | `ServerPlayerGameMode.useItemOn(player, voidLevel, stack, hand, auxiliaryHit)` — menus, `BlockState.useItemOn`, `useWithoutItem`, `ItemStack.useOn`, `BlockItem`/`BlockPlaceContext` placement |
| `useWithoutItem(player, hit)` | `BlockState.useWithoutItem(voidLevel, player, auxiliaryHit)` |
| `breakBlock(player, hit)` | `ServerPlayerGameMode.destroyBlock(auxiliaryPos)` with the real game mode temporarily pointed at the void level, then restored |
| `breakMapped(player, mapped)` | Same `destroyBlock`, using a mining session's already-validated mapping |

The redirector calls `LevelPhysics#flushAuxiliaryBlockStates()` and
`projectAuxiliaryEntities()` afterwards. That mirrors block/BE changes, rebuilds
the collider/render snapshots and projects drops, XP orbs, TNT and other
spawned entities back to the host world. Sounds/particles from the operation
were already projected by `AuxiliaryServerLevel`.

### Long-press mining state machine

`StructureMiningSessions` owns one session per player:

1. Client sends `START_MINE`. Server re-raycasts, checks reach/occlusion and
   maps the hit to the auxiliary block. Creative calls vanilla `destroyBlock`
   immediately and never enters a session.
2. Survival creates a dedicated `ServerPlayerGameMode` pointed at the void
   level and calls vanilla `handleBlockBreakAction(START_DESTROY_BLOCK)`.
3. Each server tick the dedicated game mode advances vanilla progress
   (`BlockState#getDestroyProgress`, tool/enchantment/effect rules) and sends
   crack/level feedback. Client `TICK_MINE` packets are heartbeats only; they
   do not carry a progress value. Missing heartbeats for four ticks abort.
4. At server-side `1.0` the session calls vanilla
   `handleBlockBreakAction(STOP_DESTROY_BLOCK)`, so `destroyBlock` supplies
   drops, XP, statistics and game-master/tool rules. If the progress formulas
   differ by one tick, the redirector's `destroyBlock` completes the same
   target (still exactly once per tick).
5. `STOP_MINE`, target/tool changes, leaving reach, a closer real block, a mode
   change or an auxiliary chunk unload remove the session. The local client
   crack overlay is visual only.

### Fallback policy

`StructureOperationRedirector.shouldFallback(status)` is the routing rule:

- `DISABLED` and `NOT_HANDLED` (for example an unloaded auxiliary chunk)
  use the legacy custom break/use/edit path.
- `OUT_OF_BOUNDS` block placement uses the legacy expanding placement path.
- `HANDLED` and `FAILED` consume the click and never run the custom path, so an
  edit cannot double-apply.

The world raycast remains the authority boundary; client coordinates are never
trusted by the redirector. The custom `breakBlock`/`placeBlock`/`interactBlock`
implementations remain available only for these fallbacks, entity-item
placement (spawn eggs and item frames need surface positioning) and
`/crythstinteraction` diagnostics.

## Block drops and tool rules

Player mining progress and the authoritative break path live in
`StructureInteraction` / the mining agent. This change does **not** duplicate
that path. The current interaction code emits vanilla break feedback,
reindexes, refreshes and notifies `BlockChangeListener`; drops/XP remain the
responsibility of that layer or its listeners. Void-caused edits (piston,
fluid, fire) are mirrored as block-state changes only; they do not fabricate
loot for blocks that vanilla would move or destroy without drops.

## Diagnostics

- `LevelPhysics#lastVoidTick()`: per-tick block/fluid/event/random counts,
  mirrored/skipped blocks, neighbour updates and dropped work.
- `LevelPhysics#voidTickStatus()`: one-line cumulative summary.
- `/crythst status`: prints the latest `voidTicks`, `voidRandomTicks`,
  `voidEvents`, `voidMirrored` and `voidDropped` counters.
- `EmptyPhysicsLevel#totalBlockTicks()`, `totalFluidTicks()`,
  `totalBlockEvents()`, `totalRandomTicks()`, `totalMirroredBlocks()`,
  `totalSkippedBlocks()`, `totalNeighborUpdates()`, `totalDroppedWork()`.
- `EmptyPhysicsLevel#blockEntityDiagnostics()`: live BEs, registered and
  pending tickers, loaded active chunks, random-ticking sections, chunk
  activations/reinitializations and queued events.
  `totalBlockEntityFallbackTicks()` covers the direct fallback pass.

## Limitations

- The volume does not grow when void logic spreads outside the copied bounds
  (for example fire past an edge or fluid into an adjacent air cell). Those
  changes are counted as skipped. Growth is intentionally an interaction-layer
  decision.
- `BlockVoxelVolume` is a read-only view over the host block grid; it cannot be
  mutated by void logic. Its side table is updated, but collision/state
  reindexing only applies to mutable/restored volumes.
- Entity AI is not ticked in the void level. Entities are projected to the host
  level after the block tick and then use normal host entity ticking.
- Neighbour updates beyond the per-tick budget are dropped. This prevents a
  redstone loop from stalling the server, at the cost of incomplete logic in
  pathological structures.

## Implemented tick-path details

See [void-level-ticking.md](void-level-ticking.md) for the concrete root causes
fixed for copied non-solid logic blocks, chunk promotion/tick-container
registration, entity ejection and attached decorations/seats.
