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
| Break a structure block | OK / PARTIAL fallback | The redirect path uses vanilla `destroyBlock` (drops, XP, stats, durability, tool rules). The custom expanding fallback still lacks drops/XP/stats. |
| Place a block | OK / PARTIAL fallback | Redirected placement runs vanilla `BlockItem.place` in the void copy with structure-local look/context and host-world occlusion checks. Neighbour-sensitive shape updates still see the virtual structure neighbourhood. |
| Right-click block use | OK | Vanilla `useItemOn`/`useWithoutItem`/`ItemStack.useOn` run against the live auxiliary block; menus, buttons, doors, dispensers and sounds are projected. |
| Block action restrictions / cooldowns | PARTIAL | Reach/occlusion exists; adventure/spectator/tool cooldown rules are incomplete. |
| Block entity GUI | OK | Menus open from auxiliary BEs; `AuxiliaryMenuPositionMixin` maps validity back to the structure position. |
| Block drops and item entities | OK / PARTIAL fallback | Redirect breaks use vanilla `destroyBlock` and project drops/XP. The custom fallback still does not generate drops. |
| Block placement context / orientation | PARTIAL | Being fixed for structure rotation and directional gravity. |
| Block support and `canSurvive` | OK | `canSurvive` and placement checks see the virtual structure neighbourhood; cross-boundary support is still missing. |

## 2. Ticking and redstone

| Logic | Status | Notes / gap |
|---|---|---|
| Random ticks | OK | Active sections random-tick in the auxiliary level; rate is global config, not per-structure. |
| Scheduled block ticks | OK | Due ticks are collected globally and run by priority/subtick order; delays survive save/load, refresh and split. |
| Fluid ticks / flow | OK inside copy | Water/lava flow inside the void copy. Host fluid and cross-boundary flow are still separate. |
| Block entity ticks | OK | Vanilla `tickBlockEntities` plus a bounded fallback closes chunk/entity-section gates; some global/BE systems still expect a full level. |
| Neighbour updates | PARTIAL | Edits can update the void copy, but cross-boundary updates (structure ↔ host, structure ↔ structure) are missing. |
| Redstone propagation | OK inside copy | Repeaters, observers, torches and comparators work inside the structure copy. Host↔structure and structure↔structure redstone are missing. |
| Pistons / moving blocks | OK inside copy | Piston movement edits the void copy and mirrors into the structure; moving pistons render through the vanilla moving-block feature. |
| Falling blocks (sand/gravel) | PARTIAL | Falling-block entities spawned in the void are ejected to the host; they do not become structure voxels. |
| Fire/spread/decay | PARTIAL | Scheduled fire and random decay run inside the copy; cross-boundary spread is missing. |
| Crops/bonemeal | PARTIAL | Random growth runs inside the copy; bonemeal is still routed as a normal item use and needs in-game validation. |
| Note blocks/jukeboxes | OK | Auxiliary sounds and level events project to host coordinates. |

## 3. Fluids, buoyancy and environment

| Logic | Status | Notes / gap |
|---|---|---|
| Water/lava inside a structure | PARTIAL | Fluid ticks/flow run in the void copy; buoyancy still reads host-world fluid at the body position. |
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
| AI/pathfinding | PARTIAL | A `PathNavigationRegion` bridge makes vanilla ground navigation see structure blocks, fluids and collision shapes, with cache invalidation and bounded queries. Modded navigators and AI that reads `Level` directly still see host air. See `docs/structure-pathfinding.md`. |
| Entity block interactions | PARTIAL | Arrows/projectiles collide, but their `onHitBlock` sees the real world block, not the structure state. |
| Minecarts/boats on structure rails/water | MISSING | Vehicle movement queries host blocks/fluids. |
| Entity projection from void level | OK | Ordinary entities are ejected to the host with pose/velocity mapping; decorations/seats stay as host mirrors. |
| Explosions affecting structure/entities | MISSING | TNT in the void level affects the void copy; it does not damage host entities or apply structure impulses. |
| Mob griefing / enderman pickup | MISSING | Host AI cannot edit structure blocks. |
| Leashes, seats, item frames | OK / PARTIAL | Item frames and seats are host mirrors that follow the structure pose; riders are repositioned. Split/removal lifecycle is handled. |

## 5. Rendering, sound and feedback

| Logic | Status | Notes / gap |
|---|---|---|
| Structure block rendering | OK | Batched renderer with exact partial models. |
| Break/place particles and sound | OK | Break/place feedback uses exact pose-aware world positions; auxiliary block events/sounds/particles project to the host. |
| Block entity rendering | OK singleplayer | Vanilla BERs are recreated from snapshot NBT and submitted at the structure pose; chest lid animation and sign editing are structure-aware. Multiplayer BE NBT sync is still incomplete. |
| Breaking crack overlay | OK | Vanilla breaking model submitted with the structure voxel pose/scale. |
| Selection/outline | OK | Exact `BlockState` shape raycast; vanilla outline from the same hit. |
| Light/weather/clouds | Host world | Structure does not participate in world light/weather volumes. |

## 6. Persistence, networking and world boundaries

| Logic | Status | Notes / gap |
|---|---|---|
| Block/entity persistence | PARTIAL | Structure blocks/BE NBT persist; void-level transient entities do not. |
| Multiplayer block/BE sync | PARTIAL | Block states sync; BE NBT is not fully networked. |
| Chunk loading / tickets | PARTIAL | Auxiliary chunks are ticketed; native API now has host loading tickets/tracking points, but they are not Sable plot tickets. |
| World border / build height | PARTIAL | Host-level checks exist; structure-local edits at extreme positions need explicit policy. |
| Game events / sculk / vibrations | PARTIAL | Interaction sounds/particles/events project; sculk/vibration/sculk-sensor graphs are not bridged. |
| Statistics/advancements | PARTIAL | Redirected break/use runs vanilla stats/advancement triggers; custom fallback edits do not. |
| Commands/selectors | PARTIAL | Structure command argument exists; vanilla selectors do not see structure blocks. |

## 7. Automation and cross-boundary logic

| Logic | Status | Notes / gap |
|---|---|---|
| Dispenser/dropper | OK | Vanilla use runs in the void copy and spawned entities/items project to the host. |
| Hoppers/containers inside structure | PARTIAL | Void-level BEs transfer between structure containers; host containers across the boundary are not connected. |
| Comparators/observers | OK inside copy | Both work inside the structure copy; host↔structure signals are missing. |
| Redstone input from host | MISSING | A host lever/button does not power structure blocks, and vice versa. |
| Structure-to-structure power/hoppers | MISSING | No shared neighbour graph. |
| Create contraptions / modded automation | PARTIAL | Soft compatibility exists; modded BEs that require a full `Level`/plot still need a structure level facade. |

---

## 8. Root causes

1. **The auxiliary level is not the host level.** Blocks are copied; anything
   that reads the host grid (AI, minecarts, redstone from outside, explosions,
   game events, host fluid) sees air.
2. **The auxiliary level runs block logic, not a full world tick.**
   Scheduled/fluid/random ticks, block events, neighbour updates, block
   entities and edit mirroring now run. Entity AI, weather, natural spawning,
   raids and world-border rules intentionally do not.
3. **No structure level view.** Most vanilla logic needs a `Level`/`BlockGetter`
   that maps structure-local coordinates; the void copy is hidden behind a
   region remap and is not a public level. A lightweight `StructureLevelView`
   now exists (P2), and vanilla ground pathfinding has a region bridge; there is
   still no full `Level` facade or coverage for modded navigators.
4. **Interactions are redirected to the void copy**, so most vanilla use/break
   rules work. Entity items, signs, menus and custom fallback edits still need
   structure-aware bridges.
5. **Cross-boundary graphs are missing** (structure ↔ host, structure ↔
   structure): neighbour updates, redstone, hoppers, fluids and explosions.

---

## 9. Recommended order

### P0 — void world ticking — **implemented**
- Scheduled block/fluid ticks, random ticks, block events, neighbour updates
  and block entities run on active regions.
- Edits mirror back to the structure volume and refresh reindex/split/collision/
  render.
- Void block events/sounds/particles and ordinary entities project to the host.
- Redirected break/placement uses vanilla drops/tool rules.

### P1 — interaction parity — **implemented with fallbacks**
- Structure-local `BlockPlaceContext` orientation and validation.
- Structure-local `useItemOn`/`useWithoutItem`/`ItemStack.useOn`, menus and
  sign editing.
- Survival mining progress, creative cooldown, crack overlay and pose-aware
  break particles.
- Item-frame/seat host mirrors and moving-structure attachment.
- Custom expanding placement/break fallback still lacks full drops/stats.

### P2 — structure level view — **implemented (lightweight view)**
- Public `StructureLevelView` over the auxiliary void region, with
  `BlockGetter`/`CollisionGetter` adapters and exact block shapes. Used by
  raycasts/outline and the vanilla ground-navigation region bridge.
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

The honest summary: **rendering, collision and block-world simulation inside a
structure are broadly working. Cross-boundary world logic, modded/advanced AI,
explosions, host light/weather and mods that require a full structure `Level`
remain the real gaps.**
