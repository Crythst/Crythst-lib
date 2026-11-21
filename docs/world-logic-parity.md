# World-logic parity on physics bodies

Status: audit / design discussion. This lists what "normal world logic" means in
Minecraft and how much of it currently works for blocks that live inside a
Crythst physics structure.

The current implementation is deliberately a **world-space rigid body with an
auxiliary void `ServerLevel` copy**. That copy is ticked for block logic, but it
is not the host world. Anything that assumes "the host level contains this
block" needs an explicit bridge or a real structure level view.

Legend: **OK** = works; **PARTIAL** = works only inside the void copy or only
for some cases; **MISSING** = not implemented.

---

## 1. Block lifecycle and player interaction

| Logic | Status | Notes / gap |
|---|---|---|
| Break a structure block | PARTIAL | Authoritative break + particles/sound + splitting exist. **No item drops, XP, stats, tool durability or enchantment effects.** Survival progress is being redesigned. |
| Place a block | PARTIAL | Server-authoritative placement, orientation is being moved into structure-local frame, validity checks are being added. No neighbour-shape updates (fences, walls, stairs) beyond `getStateForPlacement`. |
| Right-click block use | PARTIAL | Being added: block entities/`MenuProvider` and `useWithoutItem`/`useItemOn` in the auxiliary level. Sounds/menus need host projection. |
| Block action restrictions / cooldowns | PARTIAL | Reach/occlusion exists; adventure/spectator/tool cooldown rules are incomplete. |
| Block entity GUI | PARTIAL | Live void-level BEs exist; opening a menu needs a stable host-facing position/provider. |
| Block drops and item entities | MISSING | Breaks do not call `Block.getDrops`/`dropResources`; dispenser/dropper items are being projected from the void level but generic drops are not. |
| Block placement context / orientation | PARTIAL | Being fixed for structure rotation and directional gravity. |
| Block support and `canSurvive` | PARTIAL | Being added; neighbour-sensitive shapes currently see a virtual structure neighbourhood only. |

## 2. Ticking and redstone

| Logic | Status | Notes / gap |
|---|---|---|
| Random ticks | PARTIAL | `EmptyPhysicsLevel.tick` calls `tickChunk(chunk, randomTickSpeed)`, so grass/crops/leaves inside the void copy can random-tick. Rate is global config, not per-structure. |
| Scheduled block ticks | MISSING | The void level is never `tick()`ed; repeater/observer/piston/fire/fluid scheduled ticks do not run. |
| Fluid ticks / flow | MISSING | Water/lava copied into the void level do not flow or update neighbours; host fluid is a separate world. |
| Block entity ticks | OK/PARTIAL | `tickBlockEntities()` runs; some global/BE systems expect the level tick or game events. |
| Neighbour updates | PARTIAL | Edits can update the void copy, but cross-boundary updates (structure ↔ host, structure ↔ structure) are missing. |
| Redstone propagation | MISSING | No scheduled ticks/neighbour graph means repeaters, observers, comparators and most redstone are inert or stale. |
| Pistons / moving blocks | MISSING | Piston movement creates moving-block state in the void level, not a change to the structure volume/body. |
| Falling blocks (sand/gravel) | MISSING | Vanilla falling-block entities are created in the void level and are not projected back into the structure. |
| Fire/spread/decay | PARTIAL | Random-tick-only paths may run; scheduled fire/leaf decay is missing. |
| Crops/bonemeal | PARTIAL | Random ticks may grow crops; bonemeal use/neighbour effects are not bridged. |
| Note blocks/jukeboxes | PARTIAL | Playback inside the void level needs host sound projection. |

## 3. Fluids, buoyancy and environment

| Logic | Status | Notes / gap |
|---|---|---|
| Water/lava inside a structure | MISSING | Not simulated as fluid; buoyancy reads host-world fluid at the body position. |
| Buoyancy | PARTIAL | Uses host water, not structure-contained water; exact shape uses coarse collider boxes (documented fallback). |
| Rain/snow/weather | PARTIAL | Host weather renders; structure blocks do not receive weather ticks/lightning. |
| Sky/light occlusion | PARTIAL | Batched renderer samples world light and applies AO; structure geometry does not occlude the world light engine or sky light propagation. |
| Interior lighting | PARTIAL | Interior rooms inherit nearby world light; no structure light propagation. |
| Temperature/freezing | MISSING | No structure-level environment probe. |

## 4. Entities, AI and physics

| Logic | Status | Notes / gap |
|---|---|---|
| Player/entity collision | OK (terrain/structures) | Movement-integrated; exact partial block shapes are being added. |
| Riding/carry on structures | PARTIAL | Velocity carry for players, position carry for other entities; no full Sable-style tracking/plot relation. |
| Entity spawning on structures | PARTIAL | Spawn eggs/item frames surface placement being added. No natural mob spawning on structures. |
| AI/pathfinding | MISSING | Mob navigation queries the host world, where structure blocks are air. Mobs cannot path on/around structures. |
| Entity block interactions | PARTIAL | Arrows/projectiles collide, but their `onHitBlock` sees the real world block, not the structure state. |
| Minecarts/boats on structure rails/water | MISSING | Vehicle movement queries host blocks/fluids. |
| Entity projection from void level | IN PROGRESS | Dispenser/dropper items and other void-spawned entities need projection to the host world. |
| Explosions affecting structure/entities | MISSING | TNT in the void level affects the void copy; it does not damage host entities or apply structure impulses. |
| Mob griefing / enderman pickup | MISSING | Host AI cannot edit structure blocks. |
| Leashes, seats, item frames | PARTIAL | Item frames are fixed to the surface but do not follow a moving body; seats/handlers are being added. |

## 5. Rendering, sound and feedback

| Logic | Status | Notes / gap |
|---|---|---|
| Structure block rendering | OK | Batched renderer with exact partial models. |
| Break/place particles and sound | PARTIAL | Block break event is projected; generic void-level block events (dispenser, note block, piston) need projection. |
| Block entity rendering | PARTIAL | Supported for copied BEs; client-sync of BE NBT is incomplete. |
| Breaking crack overlay | PARTIAL | Being added for structure mining; a rotated/scaled crack mesh may be approximate. |
| Selection/outline | PARTIAL | Vanilla outline; exact block-shape raycast is being fixed. |
| Light/weather/clouds | Host world | Structure does not participate in world light/weather volumes. |

## 6. Persistence, networking and world boundaries

| Logic | Status | Notes / gap |
|---|---|---|
| Block/entity persistence | PARTIAL | Structure blocks/BE NBT persist; void-level transient entities do not. |
| Multiplayer block/BE sync | PARTIAL | Block states sync; BE NBT is not fully networked. |
| Chunk loading / tickets | PARTIAL | Auxiliary chunks are ticketed; host chunks around a structure are terrain-collision-loaded. No Sable-style loading tickets/tracking points. |
| World border / build height | PARTIAL | Host-level checks exist; structure-local edits at extreme positions need explicit policy. |
| Game events / sculk / vibrations | MISSING | Void-level game events are not forwarded to the host level. |
| Statistics/advancements | MISSING | Structure edits do not award stats/advancements. |
| Commands/selectors | PARTIAL | Structure command argument exists; vanilla selectors do not see structure blocks. |

## 7. Automation and cross-boundary logic

| Logic | Status | Notes / gap |
|---|---|---|
| Dispenser/dropper | PARTIAL | Use path being added; spawned entities need host projection. |
| Hoppers/containers inside structure | PARTIAL | Void-level BEs transfer between structure containers; host containers across the boundary are not connected. |
| Comparators/observers | MISSING | Depend on scheduled ticks/neighbour updates. |
| Redstone input from host | MISSING | A host lever/button does not power structure blocks, and vice versa. |
| Structure-to-structure power/hoppers | MISSING | No shared neighbour graph. |
| Create contraptions / modded automation | PARTIAL | Soft compatibility exists, but modded BEs often assume a real `Level`. |

---

## 8. Root causes

1. **The auxiliary level is not the host level.** Blocks are copied; anything
   that reads the host grid (AI, minecarts, redstone from outside, explosions,
   game events, host fluid) sees air.
2. **Only part of a level tick runs.** `tickChunk` + `tickBlockEntities` is not
   the full `ServerLevel.tick` (scheduled block ticks, fluid ticks, entity
   ticks, game events, etc.).
3. **No structure level view.** Most vanilla logic needs a `Level`/`BlockGetter`
   that maps structure-local coordinates; the void copy is hidden behind a
   region remap and is not a public level. A lightweight `StructureLevelView`
   now exists (P2), but there is still no full `Level` facade or AI/pathfinding
   bridge.
4. **Interactions are implemented per action**, not by letting vanilla run
   against a real level. This is why drops, tool rules, menus, projectiles and
   redstone each need separate bridges.
5. **Cross-boundary graphs are missing** (structure ↔ host, structure ↔
   structure): neighbour updates, redstone, hoppers, fluids and explosions.

---

## 9. Recommended order

### P0 — make the void copy a real ticking world
- Run scheduled block/fluid ticks and neighbour updates in the void level, not
  only `tickChunk`/`tickBlockEntities`.
- Map edits back to the structure volume (and reindex/split/collision/render).
- Project void-level block events/sounds/particles to host coordinates.
- Project void-level entities (dispenser items, TNT, arrows) to the host.
- Real block drops/tool rules for structure breaking/placement.

### P1 — interaction parity
- Structure-local `BlockPlaceContext` (orientation) — in progress.
- Structure-local block use (`useWithoutItem`/`useItemOn`, menus) — in progress.
- Survival mining progress + crack overlay — in progress.
- Item-frame/seat stability and moving-structure attachment.

### P2 — structure level view — **implemented (lightweight view)**
- Public `StructureLevelView` over the auxiliary void region, with
  `BlockGetter`/`CollisionGetter` adapters and exact block shapes. Used by
  raycasts/outline now; AI/pathfinding integration is still deferred.
- Server views read through `EmptyPhysicsLevel.liveBlock`, write through the
  void block grid and rely on the P0 edit-propagation pass. `operationTarget`
  exposes the void `ServerLevel`/position for a future player-operation
  redirector. A full `Level` facade is deferred.
- Structure-aware `getBlockState`/`getFluidState`/`getBlockEntity` for host
  queries; host↔structure neighbour and game-event bridges remain deferred.

### P3 — advanced parity
- Explosions affecting structure bodies and host entities; TNT physics.
- Cross-boundary redstone/hoppers; fluid flow; falling blocks/pistons.
- Structure light occlusion/sky light, weather, mob spawning and vehicle logic.
- Sable-style plot/assembly/tickets if a true drop-in ecosystem is required.

The honest summary: **rendering, collision and basic edit interactions work;
world simulation parity is still partial.** The void level gives us block
entities and random ticks, but not a full world.
