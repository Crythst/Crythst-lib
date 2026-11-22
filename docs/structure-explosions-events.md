# Explosions, game events and player progress on structures

Status: implemented P3 parity slice for explosions, auxiliary→host game events
and the custom-interaction statistics/advancement fallback.

This document complements [`world-logic-parity.md`](world-logic-parity.md).
It describes the hooks that bridge the auxiliary void level and real host world;
it does not claim that redstone, hoppers, pathfinding or a full structure
`Level` facade exist.

---

## 1. Explosions

### Modes and deduplication

`ServerExplosionMixin` calls `StructureExplosions.afterVanillaExplosion` once at
the end of `ServerExplosion#explode`. `EmptyPhysicsLevel.ofLevel(level)` selects
exactly one mode:

| Explosion owner | Mode | What the bridge does |
|---|---|---|
| host `ServerLevel` | host | Runs a structure-local blast trace, removes selected voxels, drops loot, applies impulses |
| auxiliary void level | void | Uses the blocks vanilla already removed from the copy, applies impulses, mirrors splits/removal and damages host entities |

The two modes never both remove structure voxels. A weak identity set
(`StructureExplosions.HANDLED`) also rejects a replayed `ServerExplosion`
object. This is the host+void deduplication; overlapping *host* and
*structure* blocks at the same world position are still two independent block
grids and are intentionally both affected.

### Host mode

1. `ServerExplosion#interactWithBlocks` is injected at `HEAD` only to capture
   the vanilla destroy list **for auxiliary explosions**. Host explosions do
   not use that list.
2. Host mode quick-rejects structures whose expanded AABB cannot contain the
   explosion centre, then converts the centre into the structure pose's local
   metric space.
3. `ExplosionBlast` reproduces vanilla's 16×16×16 shell rays, 0.3-block steps
   and `(resistance + 0.3) * 0.3` power loss. The probe maps local metric
   coordinates to voxels by dividing by `blockScale` and flooring.
4. Resistance comes from the live auxiliary block when available, otherwise
   the structure state side table, otherwise stone for an untyped solid cell.
   `ExplosionDamageCalculator#shouldBlockExplode` is called with the host block
   position, so custom explosion calculators and block-specific refusal rules
   are respected.
5. Selected voxels receive a radial `ExplosionImpulse` at their world centre
   **before** reindex/split, so linear and angular velocity propagate to split
   components.
6. If a live auxiliary region exists, voxels are cleared through
   `EmptyPhysicsLevel#removeBlocksForExplosion` and the normal edit-flush pass
   mirrors state, removes stale block-entity NBT, reindexes, splits and removes
   massless bodies. Without a live region the editable volume is changed
   directly and reindexed once.
7. Loot uses `Block#getDrops` at the host position and `Block#wasExploded`.
   `wasExploded` gives TNT chain reactions: a structure TNT block spawns a
   primed **host** TNT entity.

Only `DESTROY` and `DESTROY_WITH_DECAY` interactions edit structure voxels.
`KEEP` (no block damage) and `TRIGGER_BLOCK` (redstone/wind-charge style trigger
only) do not remove voxels.

### Void mode

Vanilla already ran against the copied blocks. The bridge:

- captures the destroy list before vanilla removes the copy;
- applies approximate radial impulses to the owning structures using the
  pre-removal mass and world centres;
- flushes the edit pass immediately so split/removal and host collision state
  are correct before the next tick;
- re-runs the entity part of the explosion in the host world via
  `ServerLevel#explode(..., ExplosionInteraction.NONE)`, so host players/mobs
  are damaged and knocked back at the transformed centre.

`NONE` maps to `KEEP`, so the recursive host explosion is deliberately ignored
by the host structure pass.

### TNT and falling blocks

- A host TNT/creeper/bed explosion damages structures through host mode.
- A TNT block destroyed inside a structure spawns a primed host TNT through
  `TntBlock#wasExploded`; that host explosion then feeds back into host mode.
- Primed TNT, falling sand/gravel and other entities spawned by block logic in
  the void are projected to the host by the existing
  `StructureEntityProjection` pass. Their fuse/gravity and explosion then run as
  normal host entities, so structure TNT already affects host terrain/entities.
- Falling blocks ejected from a structure do **not** become structure voxels
  again. This remains a documented world-logic limitation.

### Unsafe / deferred pieces

- A custom `Explosion` implementation that is not `ServerExplosion` is not
  intercepted. Vanilla and all standard `Level#explode` callers construct
  `ServerExplosion`.
- Host structure tracing is independent of host terrain occlusion. A structure
  voxel can be selected even if a host wall is between the explosion and the
  structure; host blocks themselves still use vanilla's own ray walk.
- `DESTROY_WITH_DECAY` loot uses the same loot table call as ordinary
  destruction, not vanilla's per-stack explosion-decay roll.
- Structures that overlap the same host block position are treated as separate
  worlds; both may be damaged by one explosion.
- A structure whose volume is a read-only `BlockVoxelVolume` cannot be edited
  by the direct fallback. When a live auxiliary region exists the copy is still
  cleared and mirrored through the normal edit pass; otherwise the structure
  body is left unchanged.
- Split components keep the existing `ComponentVolume` limitation: block-state
  and side-table edits work, but the collider may not reflect them until a
  future volume rewrite. This is an existing structure-volume limitation, not
  specific to explosions.

---

## 2. Game events

`EmptyPhysicsLevel.AuxiliaryServerLevel#gameEvent` first dispatches the event to
listeners inside the copy (structure sculk sensors/redstone keep working), then
calls `StructureGameEvents.forwardFromAuxiliary`.

- Position mapping uses `EmptyPhysicsLevel#projectAuxiliaryPoint`: local voxel →
  `blockScale` → structure pose. This is the same mapping as entity/sound
  projection, so block place/break, sculk sensor/vibration, note block and
  structure TNT prime events land at the visible structure position.
- Events outside every copied region stay in the void level; they are never
  dropped at an arbitrary host coordinate.
- A thread-local bridge depth suppresses re-entrant forwarding if a host
  listener causes another auxiliary event while the bridge is posting. This is
  the feedback-loop guard.
- `GameEventDedup` drops an identical `(event id, mapped block position, source
  entity id)` within the same tick (configurable window). This prevents a
  vanilla path that emits two equivalent events from double-triggering a host
  sculk sensor or note listener.
- A source entity that belongs to the auxiliary level is not passed to host
  listeners; the affected block state and event identity are preserved.

Existing sound/particle/level-event projection remains interaction-scoped
(`beginAuxiliaryAudioProjection`). Game events are forwarded for all auxiliary
block logic, not only player interactions.

---

## 3. Statistics and advancements

`StructurePlayerProgress` is the single helper for the legacy custom
volume-edit fallback. Redirected interactions already run vanilla
`ServerPlayerGameMode`/`BlockItem`/`ItemStack` paths and therefore keep vanilla
stats and triggers.

| Path | Behaviour |
|---|---|
| redirected break/use/place | vanilla stats and advancement triggers |
| custom break | `Stats.BLOCK_MINED`, drops/tool durability/food exhaustion |
| custom place | `Stats.ITEM_USED` + `CriteriaTriggers.PLACED_BLOCK` at the host position |
| custom block use | `ITEM_USED_ON_BLOCK` or `DEFAULT_BLOCK_USE` at the host position |
| redirected use helper (`performUse`) | same `ITEM_USED_ON_BLOCK`/`DEFAULT_BLOCK_USE` triggers as vanilla `ServerPlayerGameMode#useItemOn` |

Vanilla `ServerPlayerGameMode` does not award a block-mined stat separately; it
comes from `Block#playerDestroy`. The custom fallback mirrors that call site
rather than inventing a second trigger. There is no vanilla block-break
advancement trigger to fire, so break parity is the statistic plus loot/tool
rules.

---

## 4. Tests

Pure, no `runClient`/world bootstrap:

- `ExplosionBlastTest` — ray selection, resistance absorption, unique cells,
  block-specific veto and non-positive radius.
- `ExplosionImpulseTest` — radial direction, centre fallback, mass/power scaling
  and distance falloff.
- `GameEventBridgeTest` — pose/scale/anchor event mapping, packed block keys,
  duplicate window, key discrimination and bounded dedup storage.

---

## 5. Hook inventory

| Hook | Purpose |
|---|---|
| `mixin/explosion/ServerExplosionMixin` | capture auxiliary destroy list; run host/void bridge after `explode` |
| `mixin/explosion/ServerExplosionAccessor` | read the source-specific explosion damage calculator |
| `EmptyPhysicsLevel.AuxiliaryServerLevel#gameEvent` override | dispatch locally, then forward to host |
| `EmptyPhysicsLevel#removeBlocksForExplosion` | clear explosion-selected voxels in the live copy |
| `EmptyPhysicsLevel#regionViewAt` | map auxiliary positions back to region-local voxels |
| `integration.minecraft.gameevent.*` | pure blast/impulse/event/dedup/progress helpers |
