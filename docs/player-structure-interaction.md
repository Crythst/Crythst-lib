# Player ↔ physics-structure interaction

This document describes the first-version interaction layer for blocks that live
inside a Crythst `PhysicsStructure` instead of Minecraft's block grid.

Status: **server-authoritative core + real vanilla clip**. `ClientLevel`
overrides `clip(ClipContext)` through `ClientLevelClipMixin`, so
`Minecraft.pick`, the vanilla outline extractor and every click path read one
`StructureBlockHitResult`. Fabric's interaction callbacks and one custom action
payload remain the authoritative server path; the server re-runs the same
structure raycast against `LevelPhysics`. The hit type, merge rule and outline
hand-off are described in [selection-targeting.md](selection-targeting.md).

---

## 1. What works

| Requirement | Implemented path |
| --- | --- |
| Left-click a structure block to break it | survival uses vanilla hardness/tool/effect progress and sends `MINE` only at 1.0; creative sends one immediate `MINE` |
| Breaking applies a reaction impulse to the body | `structure.applyImpulseAt(-look * 400 * miningImpulseScale, hitPoint)` |
| Break progress overlay | `StructureBreakOverlay` submits the vanilla breaking model with the structure voxel's rotated/scaled pose |
| Right-click a structure block to place a block | `StructureInteraction.placeBlock(...)` via `UseBlockCallback`, `UseItemCallback`, and the `PLACE` payload for virtual-air targets |
| Right-click a structure block to use it (empty hand, tool, bucket, food) | `StructureInteraction.interactBlock(...)` via the `USE` payload: live auxiliary state, vanilla `useItemOn`/`useWithoutItem`, containers open their menu |
| Right-click with a spawn egg / item frame / registered entity item | `StructureEntityPlacement.place(...)`: server re-raycast, surface-point math, vanilla entity spawn, no real support block required |
| Middle-click a structure block to pick it | `StructureInteraction.pickBlock(...)` via the client `PICK` / `PICK_WITH_DATA` payload and vanilla `tryPickItem` |
| Placement validation | `StructurePlacementValidation` rejects solid/non-replaceable targets, unsupported interior cells, `canSurvive` failures and exact-shape entity obstruction before any mutation |
| Structure-local orientation | `StructurePlacementContext` transforms the click point and look into the structure frame, so stairs/slabs/logs/pillars use local yaw/pitch/face/click fraction; no world-frame leakage |
| Selection outline | vanilla outline state built from the same `StructureBlockHitResult`; no custom white box |
| Real selection target | `ClientLevel.clip(ClipContext)` merges the nearest structure hit into the vanilla clip result; `Minecraft.hitResult` is authoritative without a post-pick override |
| Shovel + left-click water applies player recoil | `StructureInteraction.shovelWater(...)` via the client `SHOVEL_WATER` payload |
| Default behaviour unchanged | Every hook returns `PASS` unless a structure is actually in front of a real block / the ray is water and a shovel is held |
| Restored structures can be edited | `RestoredStructureVolume.setBlock(...)` plus the interaction adapter |
| Building on an outer face | the volume is expanded by one cell into a new mutable volume |

The structure hit is client-side prediction only. The existing Fabric callbacks
already run on the server at the correct `START_DESTROY_BLOCK` / `useItemOn` /
`useItem` points, and the server re-raycasts before changing physics state.

### 1.1 The click-count trap

Fabric's `ClientPreAttackCallback` is invoked from `Minecraft.handleKeybinds`
*before* vanilla consumes the attack key, and its third argument is
`KeyMapping.clickCount`. A fresh press therefore has `clickCount != 0`; a held
key has `0`. The mining controller does not need the click count for its
progress: it advances once per client tick while the key is held. `clickCount`
is still useful as a diagnostic and must not be treated as "the only tick that
may mine".

### 1.2 Mining progress algorithm

`StructureMiningController` replaces the old instant break plus five-tick
repeat. Every held-attack tick:

1. Builds a target token from `bodyId`, structure voxel and `BlockState`
   identity, plus the held `ItemStack`. A change in any of these resets the
   session to zero. Releasing attack, losing the structure hit, leaving reach or
   a state change also resets it.
2. Checks `blockActionRestricted`, the item cooldown, `ItemStack#canDestroyBlock`
   and (in creative) the `GameMasterBlock` permission. A blocked target still
   suppresses vanilla block breaking but accumulates no progress.
3. Reads `state.getDestroySpeed(level, pos)`, `player.getDestroySpeed(state)` and
   `player.hasCorrectToolForDrops(state)`. `Player#getDestroySpeed` already
   includes tool speed, Efficiency, Haste/Mining Fatigue, the block-break-speed
   attribute, submerged mining and the off-ground penalty.
4. Computes `destroySpeed / hardness / (correctTool ? 30 : 100)` and adds it to
   the session. Creative returns a break immediately instead. `MINE` is sent
   **only** when progress reaches `1.0`; a completed target is marked so a
   stale hit cannot emit repeated packets until the crosshair moves.
5. Publishes the progress for the break overlay.

The pure state machine (`MiningProgress`) and formula (`MiningMath`) do not
reference Minecraft classes and are unit-tested directly.

### 1.3 Break overlay

`StructureBreakOverlay` listens to Fabric's `LevelRenderEvents.COLLECT_SUBMITS`.
When a session has progress it collects the same `BlockStateModelPart`s vanilla
uses for the state, applies the structure voxel's camera-relative transform
(pose rotation and `blockScale`) via `StructureOutlineTransform`, and calls
`SubmitNodeCollector#submitBreakingBlockModel` with the vanilla crack stage
`0..9`. The real `blockDestruction` map is never touched, so a rotated or scaled
voxel cannot draw its crack at an unrelated axis-aligned `BlockPos`.

States with `RenderShape.INVISIBLE` (chests, signs and other block-entity
renderers) have no block model to overlay; their progress is still tracked and
the break completes normally. The overlay is texture/depth identical to vanilla
because it uses the vanilla breaking submission; only the transform differs.

---

## 2. Files

New server/integration files:

- `src/main/java/io/mikaple/crythst/integration/minecraft/selection/StructureSelection.java`
  — merged vanilla + structure clip and authoritative server raycast
- `.../selection/StructureRaycaster.java` — shared voxel DDA plus exact
  `BlockState#getShape` cell tests
- `.../selection/StructureVoxelShapes.java` — read-only structure-local
  `VoxelShape` lookup used by selection, matching the outline's lookup
- `.../selection/StructureBlockHitResult.java` — vanilla `BlockHitResult`
  carrying body id, pose, voxel, local face, block scale and block state
- `.../selection/StructureVoxelView.java` — server / integrated / network view
  abstraction
- `.../selection/PhysicsStructureView.java`
- `.../interaction/StructureInteraction.java`
- `.../interaction/StructureBlockStates.java`
- `.../interaction/StructureAction.java`
- `.../interaction/StructureActionPayload.java`
- `.../interaction/StructureInteractionEvents.java`
- `.../interaction/StructureInteractionCommand.java`
- `.../interaction/StructureFeedback.java` — shared server-side break particles
  and positional break/place sound (placement is sound-only)
- `.../interaction/StructureRayMath.java`
- `.../interaction/StructurePlacementMath.java` — pure world/local face,
  look/yaw/pitch and click geometry shared by placement diagnostics
- `.../interaction/StructurePlacementContext.java` — structure-local
  `BlockPlaceContext` used for orientation resolution
- `.../interaction/StructurePlacementLevel.java` — virtual `LevelReader` that
  maps structure-local neighbours plus the real world outside the volume
- `.../interaction/StructurePlacementValidation.java` — replaceability,
  support, `canSurvive`, exact-shape entity obstruction and rotated/scaled
  world-shape helpers
- `.../interaction/StructureItemUse.java` — shared client/server decision for
  block items, spawn eggs, item frames and registered entity items
- `.../entity/placement/StructureEntityPlacement.java` — entity placement
  registry plus built-in spawn-egg and item-frame handlers
- `.../entity/placement/StructureEntityPlacementHandler.java` — public handler
  interface for Create seats and other modded entity items
- `.../entity/placement/StructureEntityPlacementContext.java` — handler context
- `.../entity/placement/StructureEntityPlacementMath.java` — pure surface
  point, normal and item-frame geometry for all six faces, rotated/scaled poses

New client helper files:

- `.../interaction/client/ClientStructureSelection.java` — integrated render
  snapshots + multiplayer `ClientStructureCache` as one candidate list
- `.../interaction/client/ClientStructureInteraction.java`
- `.../interaction/client/StructureMiningController.java` — held-attack routing,
  restrictions, target reset and `MINE` threshold
- `.../interaction/client/MiningMath.java` — pure vanilla progress formula and
  crack-stage mapping
- `.../interaction/client/MiningProgress.java` — pure per-target session state
  machine (creative instant, survival accumulation, reset, no stale repeats)
- `.../interaction/client/StructureBreakProgress.java` — immutable overlay
  snapshot
- `.../client/render/structure/breakoverlay/StructureBreakOverlay.java` —
  structure-aware vanilla crack submission at the voxel pose
- `.../client/render/structure/selection/StructureSelectionOutline.java` —
  vanilla outline render-state override
- `.../mixin/client/ClientLevelClipMixin.java` — adds `clip(ClipContext)` to
  `ClientLevel`
- `.../mixin/client/StructurePlacementMixin.java` — consumes structure
  right-clicks before client-side prediction in `MultiPlayerGameMode.useItemOn`
  and routes them to `PLACE` or `USE`
- `.../mixin/client/StructurePickBlockMixin.java` — redirects middle-click
  pick-block for structure hits
- `.../mixin/AuxiliaryMenuPositionMixin.java` — maps auxiliary block-entity
  positions to the host structure block for container menu validity
- `.../mixin/ServerGamePacketListenerImplInvoker.java` — exposes vanilla
  `tryPickItem` for server-side pick-block

Edited support files:

- `.../integration/minecraft/storage/RestoredStructureVolume.java` — added the
  full `setBlock(...)` edit entry point used by the interaction adapter.
- `.../integration/minecraft/storage/SavedStructureVolume.java` — persistence
  now reads explicit states from `StatefulVoxelVolume` (restored structures), so
  edits do not fall back to stone on the next save.

The relevant client mixin entries are `StructurePickBlockMixin`,
`StructurePlacementMixin`, `StructureBlockOutlineMixin` and
`ClientLevelClipMixin` in `crythst-client.mixins.json`;
`ServerGamePacketListenerImplInvoker` and `AuxiliaryMenuPositionMixin` are in
`crythst.mixins.json`. The retired post-pick hit mixin is gone.

---

## 3. Required wiring by the main integration owner

### 3.1 Common entrypoint

In `CrythstFabric.onInitialize()`, after `MinecraftPhysics.init(config);`:

```java
io.mikaple.crythst.integration.minecraft.interaction.StructureInteractionEvents.registerCommon();
```

This registers:

- the `crythst:structure_action` C2S payload type,
- the `ServerPlayNetworking` receiver (executes on the server thread),
- `AttackBlockCallback`, `UseBlockCallback` and `UseItemCallback`,
- the diagnostic commands `/crythstinteraction ...`, `/crythstmine` and
  `/crythstplace`.

### 3.2 Client entrypoint

In `CrythstClient.onInitializeClient()`:

```java
io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction.register();
```

This registers `ClientPreAttackCallback` and the vanilla outline override.
`ClientLevelClipMixin` adds `ClientLevel.clip(ClipContext)`; vanilla picking,
outline extraction and interaction now observe the same
`StructureBlockHitResult` in `Minecraft.hitResult`. The pre-attack hook reads
that hit through `ClientStructureSelection.current(...)`; it does not re-raycast
a second target. A real block or entity in front means the current hit is not a
structure hit and vanilla keeps the click. `SHOVEL_WATER` still falls back to
the real fluid clip when no structure target is active.

Right-click is intercepted by `StructurePlacementMixin` at the head of
`MultiPlayerGameMode.useItemOn`, before 26.3's client prediction runs. When the
hit is a `StructureBlockHitResult` the click is consumed and one payload is
sent: `PLACE` for block items, spawn eggs, item frames and registered entity
items, or `USE` for an empty hand, tools, buckets, food and other items.
Vanilla never runs `BlockItem.place`/`SpawnEggItem.useOn` on the client and
never sends a use-on-block packet for the structure position, which prevents
the one-tick real-world ghost block and duplicate local sounds.

Middle-click is intercepted by `StructurePickBlockMixin` at the head of
`Minecraft.pickBlockOrEntity`. A structure hit sends `PICK` or
`PICK_WITH_DATA` and cancels vanilla, so no
`ServerboundPickItemFromBlockPacket` is sent for an air position.

The old custom white-box renderer and its `StructureSelectionRenderer` class are
gone. The outline is the vanilla extraction/submission path:

```java
LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(
        StructureSelectionOutline::afterBlockOutlineExtraction);
```

`StructureSelectionOutline.register()` is called from
`ClientStructureInteraction.register();`, so no extra entrypoint line is
needed. `StructureBlockOutlineMixin` replaces only the submission of the state
that `StructureSelectionOutline` published; real-block outlines are untouched.
See [structure-outline.md](structure-outline.md) for the pose maths.

### 3.3 Commands

The interaction commands are registered automatically:

- `/crythstinteraction ray` — print the server raycast result, volume class,
  editability, voxel, face, distance and stored state
- `/crythstinteraction mine` / `/crythstinteraction place`
- `/crythstinteraction water` — test shovel recoil
- `/crythstmine` and `/crythstplace` — short aliases

To expose them under the existing `/crythst` tree, add these nodes to
`CrythstCommand.register(...)`:

```java
.then(Commands.literal("mine")
        .executes(io.mikaple.crythst.integration.minecraft.interaction.StructureInteractionCommand::mine))
.then(Commands.literal("place")
        .executes(io.mikaple.crythst.integration.minecraft.interaction.StructureInteractionCommand::place))
```

The methods are public and accept a Brigadier `CommandContext<CommandSourceStack>`.

---

## 4. Server API

All methods are server-thread only and return result records instead of throwing
for normal gameplay failures.

All interaction raycasts use `PlayerGravity.lookDirection(player)`, so the
crosshair direction matches the gravity-rotated camera. When no directional
gravity applies this is the plain vanilla look vector.

```java
// Raycast the player's eye/look against all live PhysicsStructure bodies.
StructureBlockHitResult hit = StructureInteraction.raycast(ServerPlayer player);
StructureBlockHitResult hit = StructureInteraction.raycast(ServerPlayer player, double reach);

// Break the hit voxel. Applies the mining reaction impulse.
StructureInteraction.BreakResult br = StructureInteraction.breakBlock(player, hit);
// br.success(), br.structureRemoved(), br.previousState()

// Place the held stack's block on the face adjacent to the hit. The server
// resolves orientation from the current look/face; no state crosses the wire.
StructureInteraction.PlaceResult pr = StructureInteraction.placeBlock(
        player, hit, player.getMainHandItem());

// Entity items (spawn egg, item frame, registered modded item). The result is
// success once the handler added an entity; consume one stack only on success.
StructureEntityPlacementHandler.Result er = StructureEntityPlacement.place(
        player, player.getMainHandItem(), hit, InteractionHand.MAIN_HAND);
if (er.success()) {
    player.getMainHandItem().consume(1, player);
}

// Register a modded entity item from common initialization. Handlers add the
// entity themselves and must not consume the stack.
StructureEntityPlacement.register(MyItems.SEAT, context -> {
    // context.spawnPoint(), context.face(), context.spawnBlockPos() ...
    return StructureEntityPlacementHandler.Result.placed(seat);
});

// Lower-level overload when a state is already known.
StructureInteraction.PlaceResult pr = StructureInteraction.placeBlock(
        player, hit, Blocks.STONE.defaultBlockState());

// The resolved placement state (also applies the stack's BLOCK_STATE
// component, if present).
BlockState placement = StructureInteraction.placementState(
        player, player.getMainHandItem(), hit);

// Right-click use. Re-raycasts, reads the live auxiliary block entity/state,
// opens a container menu or runs vanilla useItemOn/useWithoutItem, and projects
// sounds/particles to the host world. Returns PASS when the live block is
// unavailable; only consumesAction() results cancel vanilla and item use.
InteractionResult use = StructureInteraction.interactBlock(player, hit);

// Shovel + water recoil. Returns whether recoil was applied.
boolean recoiled = StructureInteraction.shovelWater(player);

// Entry point used by the C2S payload.
boolean handled = StructureInteraction.handleAction(player, StructureAction.MINE);

// Right-click duplicate guard shared by UseBlock/UseItem/PLACE.
boolean firstPlacementThisClick = StructureInteraction.beginPlacement(player);
```

Useful pieces of `StructureBlockHitResult`:

```java
hit.structure();        // backing PhysicsStructure, or null for a client snapshot
hit.bodyId();           // physics body / synced snapshot id
hit.structurePose();    // structure pose used by the raycast
hit.voxelX();           // resolved structure-local voxel
hit.voxelY();
hit.voxelZ();
hit.localFace();        // structure-local cardinal direction
hit.worldFace();        // transformed world-space cardinal face
hit.blockScale();       // metres per voxel
hit.blockState();       // state of the hit voxel
hit.getBlockPos();      // real world BlockPos containing the hit
hit.getLocation();      // world-space hit point
hit.adjacentX();        // voxel + local-face step (placement target)
hit.adjacentY();
hit.adjacentZ();
hit.distance();         // world-space distance from the ray origin
```

The outline renderer can consume the same object directly: `bodyId()`,
`structurePose()`, `voxelX/Y/Z`, `blockScale()` and `blockState()` are public
and immutable for the lifetime of the hit.

### 4.1 Break behaviour

`handleAction(MINE)` re-raycasts from the player's eye/look, rejects a real
block that is closer, applies the one-break-per-server-tick
`beginMine(player)` sanity guard, and only then calls `breakBlock`. The client
cannot name a body, voxel or progress value.

1. Accepts `MutableVoxelVolume` and `RestoredStructureVolume` through the
   internal edit adapter (`BlockVoxelVolume` remains read-only).
2. Reads and removes the stored block state (stateful volume first, side table
   second).
3. Clears the voxel's solidity and mass.
4. If the volume is now empty, calls `LevelPhysics.removeStructure(structure)`
   instead of `reindex` because `PhysicsStructure.reindex` rejects an empty
   volume.
5. Otherwise calls `structure.reindex(volume)` and
   `levelPhysics.refreshStructure(structure, volume)`. A failed reindex rolls
   the voxel back.
6. Applies `-look * 400 * miningImpulseScale` at the ray hit point through
   `PhysicsStructure.applyImpulseAt`.
7. Emits the vanilla destroy-block event at the removed voxel's world centre:
   `ServerLevel.levelEvent(null, LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK,
   BlockPos.containing(centre), Block.getId(state))` (id 2001). The client
   expands this into the vanilla destroy-block particle burst (4×4×4 for a full
   cube) plus the state's break sound. States that opt out of terrain particles
   use a small `sendParticles(...)` fallback. The event is emitted before the
   refresh pass can split/close the body.
8. Notifies registered `BlockChangeListener`s.

### 4.2 Placement behaviour

1. Accepts `MutableVoxelVolume` and `RestoredStructureVolume`.
2. Target is `voxel + localFace`, resolved from the ray normal in
   structure-local space. The target must be exactly adjacent to the clicked
   structure voxel.
3. The server builds a `StructurePlacementContext` from the player's main hand
   and the authoritative hit. It transforms the world click point to
   structure-local voxel metres (`pose^-1(worldPoint) / blockScale`) and the
   world look vector to the local frame (`pose^-1(lookDirection)`), then
   derives local yaw/pitch, horizontal facing, nearest looking directions and
   click location. No fake `Player` is allocated and the real player is not
   mutated. `Block#getStateForPlacement(context)` sees only local orientation,
   so stairs (`FACING`/`HALF`), logs/pillars (`AXIS`), slabs (`TYPE`) and
   trapdoors are correct for rotated/scaled structures and directional-gravity
   cameras. The stack's `minecraft:block_state` component is applied afterwards
   exactly as in vanilla.
4. Validation runs before any mutation:
   - the target must be air or a replaceable state (tag, block rule, water or
     grass). A solid occupied cell rejects;
   - the clicked voxel must be solid, or the target must itself be a non-air
     replaceable cell (water/grass) that still touches solid structure. This
     rejects arbitrary interior empty cells from a malformed ray;
   - `placedState.canSurvive(virtualLevel, localTarget)` must pass. The virtual
     `LevelReader` answers structure-local neighbours from the volume and maps
     only positions outside the bounds to the real world, so a torch cannot
     "float" just because the real world has air beneath it;
   - the placed state's exact collision shape, transformed by the structure
     pose and scale, must not intersect a blocking entity. Players/mobs use
     vanilla `blocksBuilding`; hanging entities such as item frames are also
     included. The shape test is an oriented-box vs world-AABB SAT, so rotated
     slabs/stairs/fences are not over-rejected by an enclosing cube.
   Any failure returns `PlaceResult.FAILED` and the volume, body, stack, sound,
   particles and listeners are untouched.
5. If the target is outside (outer face), the bounds are expanded by one cell,
   all existing solids/mass/material/states are copied into a new
   `MutableVoxelVolume`, and the structure is reindexed. A restored volume
   therefore becomes editable and expandable after the first outside
   placement. The same local frame and validation apply.
6. Mass/material come from `MinecraftPhysics.blockPhysics().properties(state)`.
7. Stores the state in the stateful volume or `StructureBlockStates`.
8. Reindexes and refreshes the auxiliary block-ticking level.
9. Applies a small reaction impulse (`400 * 0.3 * miningImpulseScale`).
10. Emits the state's place sound exactly once at the new voxel centre. No
    placement particles are emitted; only breaking uses the vanilla destroy
    burst.
11. Notifies listeners and consumes one item (`ItemStack.consume`, so creative
    mode is unaffected).

`StructurePlacementMixin` consumes structure right-clicks at the head of
`MultiPlayerGameMode.useItemOn`, so the client performs no predictive
`BlockItem.place` and sends no `ServerboundUseItemOnPacket`. The same mixin also
consumes spawn eggs, item frames and registered entity items through
`StructureItemUse.shouldHandleStructureUse(...)`; the server re-raycasts and
chooses the block or entity path from the authoritative main-hand stack.
Fabric's `UseBlockCallback`/`UseItemCallback` run at the head of
`ServerPlayerGameMode.useItemOn`/`useItem` as a fallback, before vanilla's
`BlockItem.place` or `SpawnEggItem.useOn`, so every entry path either cancels
vanilla use or goes straight through the structure path. Only one placement and
one place sound are produced.

`beginPlacement(player)` still collapses duplicate right-click paths (vanilla
`UseBlockCallback`/`UseItemCallback` plus the client `PLACE` payload) within two
server ticks.

### 4.3 Shovel + water

- Main hand must match `ItemTags.SHOVELS` (there is no `ShovelItem` class in
  26.3).
- The server re-raycasts fluids with `PlayerGravity.lookDirection(player)`,
  `ClipContext.Block.OUTLINE` / `ClipContext.Fluid.ANY`.
- Requires a non-empty water fluid (`fluid.is(FluidTags.WATER)`).
- Recoil is `-look * 0.55 * shovelWaterReaction` plus
  `world.up() * 0.18 * shovelWaterReaction`.
- The new velocity is mirrored with
  `ClientboundSetEntityMotionPacket`, because server-side player position is
  client-authoritative.
- Plays `SoundEvents.GENERIC_SPLASH`.

### 4.4 Pick block

1. `StructurePickBlockMixin` intercepts `Minecraft.pickBlockOrEntity` at
   `HEAD`. When `Minecraft.hitResult` is a `StructureBlockHitResult`, it
   cancels vanilla and sends `PICK` or `PICK_WITH_DATA` depending on
   `Minecraft.hasControlDown()`. Vanilla never sends
   `ServerboundPickItemFromBlockPacket` for the virtual position.
2. The server re-raycasts authoritatively and applies the normal
   structure-in-front-of-real-blocks check. A genuinely closer real block keeps
   the vanilla pick path.
3. `StructureInteraction.pickBlock(...)` reads the stored `BlockState` from the
   structure hit (with the side-table fallback), calls
   `BlockState#getCloneItemStack(level, pos, includeData &&
   player.hasInfiniteMaterials())`, and hands the non-empty stack to vanilla's
   private `ServerGamePacketListenerImpl#tryPickItem` through
   `ServerGamePacketListenerImplInvoker`.
4. Creative therefore adds/picks the item in the hotbar/inventory; survival
   selects an existing matching stack and otherwise picks nothing, exactly like
   vanilla.
5. Ctrl+middle-click data is structure-safe: a real block entity is read only
   when the real world genuinely contains the same block at the hit position;
   otherwise the structure's captured block-entity NBT is loaded from
   `StructureBlockEntityData` using structure-local voxel coordinates.

### 4.5 Entity placement on structures

Vanilla spawn eggs and hanging entities use the real world `BlockPos` and block
state. A structure voxel is air in that grid, so vanilla either spawns the
entity inside the pointed block or drops the frame for lack of support. The
entity path fixes this without trusting client coordinates:

1. Client: `StructureItemUse.shouldHandleStructureUse(...)` accepts block items,
   every `SpawnEggItem`, `Items.ITEM_FRAME`, `Items.GLOW_ITEM_FRAME` and any item
   with a registered handler. `StructurePlacementMixin` consumes the click and
   sends the existing `PLACE` payload; no vanilla use packet and no local
   prediction run.
2. Server: `StructureInteraction.handleAction(..., PLACE)` re-raycasts, applies
   the same real-block occlusion and duplicate-click checks, then dispatches to
   `StructureInteraction.placeBlock` or `StructureEntityPlacement.place`.
3. `StructureEntityPlacementMath.surfacePoint(hit)` computes:
   - `faceCenter`: clicked structure-voxel face centre transformed by the
     structure pose and `blockScale`;
   - `normal`: true outward world normal (non-cardinal for rotated bodies);
   - `facePoint` / `hitPoint`: the same point moved 0.05–0.2 blocks outward
     (`DEFAULT_SURFACE_EPSILON = 0.05`), so it is outside the voxel;
   - `spawnBlockPos`: the real-world air cell just outside the face.
4. Built-in spawn eggs call `EntityType.spawn(..., SPAWN_ITEM_USE, ...)`, which
   preserves `minecraft:entity_data` (baby/variant/custom name), random yaw and
   `finalizeSpawn`, then move the entity to `facePoint`.
5. Built-in frames create the correct `ITEM_FRAME`/`GLOW_ITEM_FRAME` entity via
   the entity type factory, set the vanilla `Fixed` flag through the normal NBT
   load path, place the frame 1/32 block outside the surface and set the
   orientation-aware bounding box. This bypasses `survives()` because the real
   support block is air and prevents the periodic support check from dropping
   the frame.
6. After success the caller consumes one item with `ItemStack.consume(1,
   player)`; creative players are unaffected. Handlers must add their entity
   themselves and must not consume the stack.

Modded entity items register a handler in common/server initialisation:

```java
StructureEntityPlacement.register(MyItems.SEAT, context -> {
    SeatEntity seat = new SeatEntity(context.level());
    seat.moveTo(context.spawnPoint().x, context.spawnPoint().y, context.spawnPoint().z);
    context.level().addFreshEntity(seat);
    return StructureEntityPlacementHandler.Result.placed(seat);
});
```

The handler context exposes `player()`, `level()`, `stack()`, `hit()`,
`surface()`, `hand()`, `hitPoint()`, `spawnPoint()`, `face()` and
`spawnBlockPos()`. Paintings are intentionally left to vanilla for now:
`Painting.create` chooses a variant by scanning real wall blocks and has no
structure-aware equivalent.

The existing `PLACE` payload ordinal is unchanged; the server chooses block vs.
entity from the current authoritative stack. No coordinates are sent, so a
modified client cannot choose the entity position.



`crythst:structure_action` is a C2S `CustomPacketPayload` with one byte:

- `0` = `MINE`
- `1` = `PLACE` (block item, spawn egg, item frame or registered entity item)
- `2` = `SHOVEL_WATER`
- `3` = `PICK`
- `4` = `PICK_WITH_DATA`

The three original ordinals are unchanged; pick actions append to the enum so
the one-byte payload keeps working with existing action ids.

It carries no coordinates and no block state. The server re-runs the
authoritative raycast from the player's server-side eye position and look
vector, so a modified client cannot name an arbitrary structure/voxel or force
an orientation. `PLACE` recomputes the placement state from the server's
current main-hand stack, server-side look direction and the server-resolved hit
face, then applies the same footprint/occlusion checks as the block callbacks.

Registration uses the 26.3 signatures:

```java
PayloadTypeRegistry.serverboundPlay().register(
        StructureActionPayload.TYPE, StructureActionPayload.STREAM_CODEC);

ServerPlayNetworking.registerGlobalReceiver(StructureActionPayload.TYPE,
        (payload, context) -> context.server().execute(
                () -> StructureInteraction.handleAction(context.player(), payload.action())));
```

---

## 6. Block states

Stateful volumes (`RestoredStructureVolume`, and any future
`StatefulVoxelVolume`) are read first:

```java
StatefulVoxelVolume stateful = (StatefulVoxelVolume) volume;
stateful.blockState(x, y, z);
```

`MutableVoxelVolume` stores only solidity, mass and material, so its Minecraft
block states are kept in a side table:

```java
StructureBlockStates.get(structure, x, y, z);
StructureBlockStates.set(structure, x, y, z, state);
StructureBlockStates.remove(structure, x, y, z);
StructureBlockStates.clear(structure);
StructureBlockStates.size(structure);
StructureBlockStates.describe(structure); // diagnostics
```

The table is weakly keyed by `PhysicsStructure`. Volumes created with
`VoxelVolumes.filled(...)` have no initial states; breaking those voxels works,
but there is no state-dependent sound and no drop. `RestoredStructureVolume`
already stores its own states, so the side table is only a fallback/mirror for
plain mutable volumes.

`StructureInteraction.addBlockChangeListener(...)` receives the previous and new
state, which is the intended hook for drops, statistics, particles or gameplay
mods.

---

## 7. Config

Uses the existing fields; no config changes were made:

- `structureBlockInteractionEnabled` — master switch. All API and hooks return
  no-op/failure when false.
- `miningImpulseScale` — scales both the mining reaction and the smaller
  placement reaction.
- `shovelWaterReaction` — scales the water recoil. `0` disables it.

Reach is not a new config field. It uses
`ServerPlayer.blockInteractionRange()` (and the equivalent client-side method)
so vanilla reach attributes and modifiers still apply.

---

## 8. Limitations / what is still missing for full client-authoritative mining

1. **Multiplayer depends on structure sync.** `ClientStructureSelection` first
   uses singleplayer `ClientPhysicsView` snapshots, then falls back to
   `ClientStructureCache`. The latter is fed by `StructureSync`; if sync is
   disabled or the structure exceeds the sync limits, the client cannot see the
   virtual blocks. `StructureSnapshotFactory` reads `BlockVoxelVolume` states
   first and the `StructureBlockStates` side table second. Persistence mirrors
   restored states into that side table, so restored structures keep their
   appearance; a custom `StatefulVoxelVolume` that is not mirrored and is not a
   `BlockVoxelVolume` still falls back to stone in the network palette.
2. **Mining progress is client-computed.** The client owns visual progress and
   sends `MINE` only at completion; the server re-raycasts and applies a
   one-break-per-tick rate guard through `StructureInteraction.beginMine(...)`,
   but it cannot independently prove how long the client held the key. A
   modified client can therefore break at most one structure voxel per server
   tick, which is still bounded by reach, occlusion and occupancy checks.
   `START/STOP/ABORT_DESTROY_BLOCK` packets are not used, and no progress is
   sent over the wire. Adventure-mode `blockActionRestricted` and world-
   dependent `Block#getDestroySpeed` overrides are evaluated at the real
   `BlockPos`, because those vanilla APIs take a real `Level` rather than the
   structure's virtual grid; the common fixed-hardness case is exact.
3. **Some states have no model crack.** `StructureBreakOverlay` uses the vanilla
   breaking block model, so `RenderShape.INVISIBLE` states (chests, signs,
   banners with block-entity renderers) show progress only through the action
   and the final break effect. A future version could add a screen-space bar or
   per-state particles.
4. **No drops or statistics.** The state side table and `BlockChangeListener`
   are the hooks, but the default implementation does not spawn items, call
   `Block.getDrops`, award stats or damage tools.
5. **Placement orientation is local, but `getLevel()` is still real.**
   `StructurePlacementContext` supplies local yaw/pitch, face, click location
   and nearest-looking directions, and validation uses the virtual
   `StructurePlacementLevel`. However `BlockPlaceContext.getLevel()` returns a
   `Level`, not a `LevelReader`, so a modded `getStateForPlacement` that asks
   `context.getLevel()` for neighbour states (or vanilla fence/wall
   connection/waterlogging helpers) still sees the real world at the
   structure-local coordinates. Fence connections and block entities carried
   by the stack are not modelled. The local frame also means a modded
   placement hook that expects world-space `getClickedPos()`/`getClickLocation()`
   will receive structure-local coordinates by design.
6. **Expansion copies the whole grid.** Building on an outer face creates a new
   expanded `MutableVoxelVolume` and copies every solid cell/state. This is fine
   for the current structure sizes but is O(volume) and changes the volume
   identity (the sync layer then sends a fresh snapshot).
7. **Read-only volumes cannot be edited.** `BlockVoxelVolume` is a live view over
   real blocks; breaking/placing correctly fails. Editing real terrain through
   the structure API is out of scope.
8. **Right-click use runs in the auxiliary void level.** `interactBlock` reads
   the live auxiliary block entity/state and runs vanilla block use there.
   Buttons, levers, doors, trapdoors, bells and similar blocks update the
   auxiliary copy; the state mirror then updates the structure volume/side
   table and reindexes the collider. Auxiliary chunks are never force-loaded: an
   unloaded chunk returns `PASS`. Container menus are bound to the auxiliary
   block entity, so the menu-validity mixin maps the block position back to the
   structure's current host position; a custom menu that serialises its own
   `BlockPos` may still see auxiliary coordinates. Interaction sounds, level
   events and particles are projected to the host level while the use runs;
   ambient block logic outside an interaction remains silent. Custom stateful
   volumes other than `RestoredStructureVolume` only receive the side-table
   mirror, and inventories live only in the auxiliary block entity (they are
   mirrored to persistence, not to a real host block).
9. **Occlusion uses a small tolerance.** `ClientLevel.clip` installs the
   structure hit only when it is strictly closer than the real block/fluid hit.
   The server re-raycasts from the player's eye and compares against a
   server-side `ClipContext` using a 0.02-block tolerance to absorb independent
   ray quantisation. Entities are not considered by the server payload path.
10. **Water recoil does not push a structure body.** It applies to the player
   only. A future version could apply the opposite impulse to a body if the
   water is inside/adjacent to a structure.
11. **Right-click is main-hand only.** `StructurePlacementMixin` consumes the
    main-hand `MultiPlayerGameMode.useItemOn` call before client prediction.
    Block items, spawn eggs, item/glow frames and registered entity items send
    `PLACE`; everything else sends `USE`. Off-hand clicks keep vanilla behaviour
    and therefore do not edit or use a structure.
12. **Placed entities are not attached to moving structures.** Spawn eggs and
    item frames are spawned stably at the surface, but carry/passenger motion
    and structure-local anchoring are out of scope. Item frames use the vanilla
    `Fixed` flag because the structure voxel is air in the real level; if the
    structure moves or disappears, the frame stays where it was placed. A future
    attachment layer can use `StructureAttachment`/the riding manager.
13. **Paintings are not structure-aware.** `Painting.create` searches real wall
    blocks for a fitting variant. Item frame and glow frame are supported; a
    painting falls back to vanilla behaviour. Custom entity items need a
    registered `StructureEntityPlacementHandler`; handlers must not trust client
    coordinates and must not consume the held stack themselves.
14. **The outline pose is exact, but it is still a selection overlay.** The
    block-local `VoxelShape` is transformed by the structure pose and
    `blockScale`, so arbitrary rotations and scales are not converted to an
    enclosing AABB. The line style, colour and screen-space width are vanilla.
    The remaining limitation is synchronization: in multiplayer the outline
    uses the client's interpolated structure pose and block-state snapshot, so
    it can lag a fast-moving server body by the sync/render interval. If the
    client has no snapshot for the body, the shape falls back to a full cube.

### Suggested next step for full client-authoritative mining

The progress record, the client threshold packet and the structure-aware crack
overlay are now implemented. Remaining work:

1. Extend the network snapshot factory to read `StatefulVoxelVolume` directly
   (not only `BlockVoxelVolume` plus `StructureBlockStates`), so every custom
   stateful volume keeps real block appearances in multiplayer without relying
   on the side-table mirror.
2. Optionally send a validated `START/STOP/ABORT` progress payload with body id
   + voxel + sequence if the server ever needs to verify timing instead of only
   rate-limiting the final break.
3. Emit drops, statistics and tool durability damage from the server's
   authoritative break, using the existing `BlockChangeListener` hook.

---

## 9. Diagnostics and tests

- `/crythstinteraction ray` isolates server-side raycast/volume problems from
  client input problems.
- `/crythstinteraction mine|place|water` exercise the server API directly.
- `StructureRayMathTest` unit-tests the pure slab ray/AABB math and entry
  normals used by the voxel traversal.
- `StructureRaycasterTest` drives the shared structure DDA: nearest cell,
  entry faces, negative coordinates, `blockScale`, rotated poses, reach, and
  the real-block-wins rule of `StructureSelection.clip`. It also covers exact
  slab/stair shape hits (including the empty half of a partial cell), all six
  faces, rotated/scaled hits, shape-surface distance with a non-unit
  `blockScale`, and that the outline target's voxel/world hit equal the raycast
  result.
- `StructurePlacementMathTest` verifies world-space face transforms
  (including a 90-degree yaw), the synthetic click staying inside the clicked
  voxel, stair/slab click fractions, pillar axis source and the exact-grid
  outline predicate.
- `StructurePlacementVanillaRulesTest` calls the real
  `StairBlock`/`SlabBlock`/`RotatedPillarBlock#getStateForPlacement` hooks with
  the synthetic click and asserts stairs facing/half, slab type and log axis.
- `StructureUseRoutingTest` checks `USE` vs `PLACE` item routing (empty hand,
  tools, buckets and food use `USE`; block items and entity items use `PLACE`),
  the real-block-wins occlusion rule, and the non-consuming failure for a null
  hit/unavailable live auxiliary block.
  It also covers the structure-local context: rotated/scaled stairs, all six
  local log axes, slab fractions with `blockScale != 1`, and a gravity-rotated
  look where local yaw/pitch differ from the world yaw.
- `StructurePlacementValidationTest` is the pure validation matrix: full block
  vs player AABB, bottom-slab exact shape, torch support from a structure
  neighbour, water/grass replacement vs solid or non-replaceable cells,
  exposed vs interior empty cells, and rotated/scaled obstruction.
- `StructureEntityPlacementMathTest` covers the six-face surface point and
  outward normal, rotated/scaled structures, spawn points staying outside the
  clicked voxel, the item-frame facing/offset/bounding box and the shared
  client/server item-handling decision (including a registered custom item).
- `StructureFeedbackTest` locks in the sound-only placement contract and the
  vanilla destroy-block break effect.
- `StructureSelectionMathTest` verifies voxel selection and local face
  resolution for positive, negative and rotated poses.
- `MiningMathTest` covers the pure vanilla formula: stone with/without the
  correct tool, tool/effect speed folded into `Player#getDestroySpeed`,
  unbreakable/instant blocks, clamping and crack stages.
- `MiningProgressTest` covers the pure session: survival breaks only at 1.0, a
  completed stale target emits no repeat, target/tool changes reset progress,
  creative breaks once immediately, and losing the target clears the session.
- `StructureMiningRoutingTest` verifies structure-vs-water routing and that no
  target leaves vanilla attack handling alone.
- `RestoredStructureVolumeEditTest` verifies that a restored volume supports
  break and place edits (mass/material included).

Compile/test commands used:

```bash
./gradlew compileJava compileTestJava --rerun-tasks --no-daemon
./gradlew test --tests 'io.mikaple.crythst.integration.minecraft.interaction.client.*' \
                --tests 'io.mikaple.crythst.integration.minecraft.interaction.StructureRayMathTest' \
                --tests 'io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolumeEditTest' \
                --rerun-tasks --no-daemon
```
