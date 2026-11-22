# Void-level ticking and entity policy

This document records the end-to-end tick path of the auxiliary
`EmptyPhysicsLevel` after the P0 world-logic work.

## Root causes that made copied logic inert

1. **Only colliding blocks were copied.** `VoxelVolume#isSolid` is a collision
   query, not an "is a block" query. Redstone wire, torches, levers, buttons,
   tripwire and fluids have no collision shape, so the old copy loop skipped
   them. `EmptyPhysicsLevel` now copies every non-air block state (falling back
   to stone only for a solid cell with no state).
2. **The copy was never woken up.** Flag `2` (`UPDATE_CLIENTS`) deliberately
   skips neighbour updates. That is correct while the region is incomplete, but
   the copy was left permanently inert. After all blocks exist the level now
   calls `BlockState#onPlace`, `Level#updateNeighborsAt` and schedules fluid
   ticks for every copied position. Repeaters, observers, torches, redstone and
   water/lava therefore start with the same pending work a live placement would
   create.
3. **Active chunks were not promoted to block ticking.** Adding only a
   `PLAYER_SIMULATION` ticket updates the simulation tracker used by
   `shouldTickBlocksAt`, but it does not load/promote the chunk holder. The
   level now adds both `PLAYER_SIMULATION` and `PLAYER_LOADING` tickets at
   `ChunkLevel.byStatus(FullChunkStatus.BLOCK_TICKING)`, runs the vanilla chunk
   lifecycle (`ServerChunkCache#tick(..., false)`), unpacks/registers tick
   containers and registers block-entity tickers. The entity section manager is
   primed for active chunks through `ServerLevelEntityManagerAccessor` (load
   only, no entity ticking) because `LevelChunk.isTicking` also requires loaded
   entity sections. Without this, scheduled ticks had no container and block
   entities never entered `tickBlockEntities`.
4. **Persistence dropped non-solid states.** `SavedStructureVolume` previously
   stored states only for solid cells, so a restored redstone/fluid structure
   came back as stone/air. Non-solid states are now captured and restored.
5. **`LevelTicks#tick` left due ticks queued.** Even with
   `inBlockTickingRange=true`, `BLOCK_TICKING` status and registered
   containers, the auxiliary level's `LevelTicks` pass did not drain scheduled
   work. `EmptyPhysicsLevel` now drains each active chunk's
   `LevelChunkTicks` container directly (sorted by trigger tick, capped by the
   per-tick budget). Tick containers are registered **before**
   `wakeCopiedRegion`, because `LevelTicks#schedule` drops requests for a chunk
   whose container is not registered yet. Containers are unregistered when a
   region is removed so stale ticks cannot accumulate.
6. **Block-entity registration was keyed by chunk position only.** A chunk that
   unloaded and was replaced under the same key kept its "initialized" marker,
   so the new `LevelChunk`'s tick container was never registered. Registration
   now compares the live chunk object and re-unpacks/re-registers a replacement.
7. **Vanilla `tickBlockEntities` has two extra gates.** A registered ticker is
   skipped when the chunk is not `BLOCK_TICKING` or its entity section is not
   loaded, and an unregistered ticker is skipped even when both are true. In
   the auxiliary level the entity sections are promoted manually and a restored
   or freshly created block entity can miss a tick (notably a moving piston
   created by a block event, or a furnace/hopper restored from NBT). The void
   tick now runs a bounded fallback over active chunks that invokes the state
   ticker directly **only** for entries vanilla would skip, so nothing is
   double-ticked. See `blockEntityFallbackTicks` and the
   `loadedChunks`/`beTickers` diagnostics below.
8. **Refresh dropped already-captured ticks.** `LevelPhysics#refreshStructure`
   removes the old region before `EmptyPhysicsLevel#addStructure`, so the
   latter's "capture from the existing region" path never ran. The refresh path
   now captures scheduled ticks *before* removing the region and passes them to
   the copy. Region removal also clears that region's pending ticks, so an
   inactive chunk no longer saves overdue repeater/observer work that fires
   unexpectedly when the chunk is reused.

## Tick phases

`EmptyPhysicsLevel#tick` runs, in order:

1. chunk lifecycle/promotion for active regions;
2. entity ejection (before any entity logic);
3. scheduled block ticks (direct `LevelChunkTicks<Block>` drain per active
   chunk, budget 4096);
4. scheduled fluid ticks (direct `LevelChunkTicks<Fluid>` drain, budget 4096);
5. random block/fluid ticks per active section (configured speed);
6. queued block events (budget 4096, queue cap 8192);
7. vanilla `tickBlockEntities()`, then a direct-ticker fallback for entries
   vanilla skipped because the chunk/entity section was not tickable or the
   ticker was not registered;
8. a second entity ejection for entities spawned by dispensers/events;
9. attached host mirror updates;
10. block-entity NBT mirroring and changed-state mirroring.

Global world border, weather, world clock/time, raids, natural spawning and
entity AI are not ticked. Chunk lifecycle work is limited to tickets/futures;
`ServerChunkCache#tick(..., false)` is used so `tickChunks`/spawning does not
run.

## Entity policy

- Every non-player entity in an active region is ejected. Ordinary entities
  (items, TNT, arrows, minecarts, ...) use `StructureEntityProjection` with the
  structure pose/velocity/rotation. Success removes the void copy; failures are
  retried after a 20-tick cooldown and counted.
- Decorations (`BlockAttachedEntity`/`HangingEntity`: item frames, glow frames,
  paintings, leash knots) and rideable seats are **not** ejected as ordinary
  entities. They are adopted by
  `StructureAttachedEntities` as real host entities (so their full vanilla model
  renders) with a structure-local anchor.
- Attached host mirrors get `noPhysics`, no gravity and invulnerability.
  `EntityStructureCollision` already ignores `noPhysics` entities; riders are
  passengers, so neither is ejected or double-carried by the riding manager.
- A rideable mirror keeps vanilla mounting. Every tick the mirror is moved to
  `structure.pose() * localAnchor`; riders are moved to their own stored
  structure-local anchors, which rotates their offset by the full structure
  rotation while preserving the rider's own yaw/pitch.
- On a split, an attachment is reassigned to the component whose solid voxel or
  collider is nearest the anchor. On structure removal it is released in place
  as a normal host entity; if its host entity was destroyed, the attachment is
  dropped and vanilla dismounts any rider.

## Integration API

`LevelPhysics#attachedEntities()` returns the per-level registry. The placement
handler (owned by the interaction agent) should call, after spawning a real
host item frame/seat:

```java
physics.attachedEntities().attachAtCurrentPosition(entity, structure,
        StructureAttachedEntities.Kind.DECORATION); // or Kind.RIDEABLE
```

Explicit forms are `attachDecoration`, `attachRideable` and `attach` with a
`Vector3dc` structure-local anchor (metres) plus local yaw. Custom seat items
may register their type/class through `registerRideableType` /
`registerRideableClassName`; id/class names containing `seat` are recognised
automatically.

## Diagnostics

- `/crythst voiddebug` prints level state, regions, active chunks, pending and
  cumulative scheduled block/fluid ticks, random ticks, events, neighbours,
  mirrored/skipped blocks, dropped work, entity ejection and attachment
  failures.
- The same line now includes block-entity counters: live BEs, registered
  tickers in active chunks and in the level-wide list, pending tickers,
  loaded/total active chunks, random-ticking sections, chunk
  activations/reinitializations, queued events and fallback BE ticks. A
  structure with random-ticking crops should show `randomSections > 0`;
  redstone-only structures correctly show zero.
- `/crythst timing` appends the same void status.
- `/crythst status` includes ejected and attached counts.
- `EmptyPhysicsLevel#status()` is the one-line cumulative summary.
- Set `-Dcrythst.void.logTicks=true` to log the same status every 100 server
  ticks. It is off by default.

## Test coverage and limitations

Unit tests cover the pure parts: tick budgets/results, region coordinate
mapping, copy-state selection, mirror decisions and the chunk-object
re-registration decision. The live paths (chunk tickets, entity-section load,
vanilla ticker registration and actual redstone/hopper/moving-piston
behaviour) need an in-game or dedicated-server harness; they are not covered by
the JUnit suite. Use `/crythst voiddebug` with the counters above to distinguish
"no ticker registered", "ticker registered but chunk/entity section not tickable"
and "ticker ran but no scheduled work was due". Redstone-only structures
legitimately report `randomTicks=0` and `randomSections=0`.
