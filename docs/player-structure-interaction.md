# Player ↔ physics-structure interaction

This document describes the first-version interaction layer for blocks that live
inside a Crythst `PhysicsStructure` instead of Minecraft's block grid.

Status: **server-authoritative core + real vanilla clip**. `ClientLevel`
overrides `clip(ClipContext)` through `ClientLevelClipMixin`, so
`Minecraft.pick`, the vanilla outline extractor and every click path read one
`StructureBlockHitResult`. Fabric's interaction callbacks and one custom action
payload remain the authoritative server path; the server re-runs the same
structure raycast against `LevelPhysics`.

---

## 1. What works

| Requirement | Implemented path |
| --- | --- |
| Left-click a structure block to break it (creative and survival) | `StructureInteraction.breakBlock(...)` via `AttackBlockCallback` and the client `MINE` payload |
| Breaking applies a reaction impulse to the body | `structure.applyImpulseAt(-look * 400 * miningImpulseScale, hitPoint)` |
| Right-click a structure block to place a block | `StructureInteraction.placeBlock(...)` via `UseBlockCallback`, `UseItemCallback`, and the `PLACE` payload for virtual-air targets |
| Player-facing orientation on placement | server-built `BlockPlaceContext` + `Block#getStateForPlacement`, so stairs/slabs/logs/pillars/fences/trapdoors use yaw, pitch, clicked face and click position |
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
key has `0`. Using `clickCount == 0` as the "click" signal delays the first
payload to the next tick and drops quick clicks entirely. The current code uses
`clickCount != 0` for the initial edit and a 5-tick repeat while held.

---

## 2. Files

New server/integration files:

- `src/main/java/io/mikaple/crythst/integration/minecraft/selection/StructureSelection.java`
  — merged vanilla + structure clip and authoritative server raycast
- `.../selection/StructureRaycaster.java` — shared voxel DDA
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
- `.../interaction/StructureRayMath.java`
- `.../interaction/StructurePlacementMath.java` — pure world-face / block-click
  geometry shared by placement diagnostics

New client helper files:

- `.../interaction/client/ClientStructureSelection.java` — integrated render
  snapshots + multiplayer `ClientStructureCache` as one candidate list
- `.../interaction/client/ClientStructureInteraction.java`
- `.../client/render/structure/selection/StructureSelectionOutline.java` —
  vanilla outline render-state override
- `.../mixin/client/ClientLevelClipMixin.java` — adds `clip(ClipContext)` to
  `ClientLevel`

Edited support files:

- `.../integration/minecraft/storage/RestoredStructureVolume.java` — added the
  full `setBlock(...)` edit entry point used by the interaction adapter.
- `.../integration/minecraft/storage/SavedStructureVolume.java` — persistence
  now reads explicit states from `StatefulVoxelVolume` (restored structures), so
  edits do not fall back to stone on the next save.

No core physics file, config file or `fabric.mod.json` was edited. The only
mixin-config change is adding `ClientLevelClipMixin` to
`crythst-client.mixins.json` (and removing the retired post-pick hit mixin).

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

This registers `ClientPreAttackCallback`, an end-of-client-tick placement poll,
and the vanilla outline override. `ClientLevelClipMixin` adds
`ClientLevel.clip(ClipContext)`; vanilla picking, outline extraction and
interaction now observe the same `StructureBlockHitResult` in
`Minecraft.hitResult`. The pre-attack hook and placement poll read that hit
through `ClientStructureSelection.current(...)`; they do not re-raycast a
second target. A real block or entity in front means the current hit is not a
structure hit and vanilla keeps the click. `SHOVEL_WATER` still falls back to
the real fluid clip when no structure target is active. The placement poll
sends `PLACE` only when the held item is a `BlockItem` and the current hit is
the structure.

The old custom white-box renderer is disabled. The event registration is:

```java
LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION.register(
        StructureSelectionOutline::afterBlockOutlineExtraction);
```

It is called from `ClientStructureInteraction.register();`, so no extra
entrypoint line is needed. The existing
`LevelRenderEvents.BEFORE_GIZMOS.register(StructureSelectionRenderer::render);`
line is now a no-op and may be deleted later.

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

// Lower-level overload when a state is already known.
StructureInteraction.PlaceResult pr = StructureInteraction.placeBlock(
        player, hit, Blocks.STONE.defaultBlockState());

// The resolved placement state (also applies the stack's BLOCK_STATE
// component, if present).
BlockState placement = StructureInteraction.placementState(
        player, player.getMainHandItem(), hit);

// Placeholder for right-click block logic. Returns the stored BlockState
// (or null) without changing anything.
BlockState state = StructureInteraction.interactBlock(player, hit);

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
7. Plays the stored state's break sound when a state exists and notifies
   registered `BlockChangeListener`s.

### 4.2 Placement behaviour

1. Accepts `MutableVoxelVolume` and `RestoredStructureVolume`.
2. Target is `voxel + face`, resolved from the ray normal in structure-local
   space. The same face is transformed to world space for the placement
   context, so a rotated structure still places into the visually adjacent
   cell.
3. The server builds a `BlockPlaceContext` from the player's main hand, the
   `StructureBlockHitResult` returned by the authoritative raycast and the
   player's current look vector. It calls
   `Block#getStateForPlacement(context)` — the same hook
   `BlockItem.getPlacementState` delegates to — and falls back to the default
   state when the block returns `null`. Stairs use player yaw and click
   fraction, slabs use clicked face + top/bottom fraction, pillars/logs use the
   clicked world face axis, and fences/trapdoors use their normal placement
   rules. `BlockItem.canPlace` is intentionally not consulted because structure
   voxels are not in the real `Level`.
4. The stack's `minecraft:block_state` component is applied to the resolved
   state, matching vanilla's placement-component handling.
5. If the target is inside the bounds it must be empty.
6. If the target is outside (outer face), the bounds are expanded by one cell,
   all existing solids/mass/material/states are copied into a new
   `MutableVoxelVolume`, and the structure is reindexed. A restored volume
   therefore becomes editable and expandable after the first outside
   placement.
7. Mass/material come from `MinecraftPhysics.blockPhysics().properties(state)`.
8. Stores the state in the stateful volume or `StructureBlockStates`.
9. Reindexes and refreshes the auxiliary block-ticking level.
10. Applies a small reaction impulse (`400 * 0.3 * miningImpulseScale`).
11. Plays the state's place sound and notifies listeners.

`beginPlacement(player)` collapses duplicate right-click paths (vanilla
`UseBlockCallback`/`UseItemCallback` plus the client `PLACE` payload) within two
server ticks. This is what prevents one physical click from placing two blocks.

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

---

## 5. Network payload

`crythst:structure_action` is a C2S `CustomPacketPayload` with one byte:

- `0` = `MINE`
- `1` = `PLACE`
- `2` = `SHOVEL_WATER`

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
   virtual blocks. The network snapshot factory currently records non
   `BlockVoxelVolume` cells as stone, so block appearance (not occupancy) is
   approximate for mutable/restored structures.
2. **No vanilla-style mining progress.** A click breaks one voxel immediately.
   Tool speed, hardness, break animation, `START/STOP/ABORT_DESTROY_BLOCK`
   progress and cooldowns are not modelled. Hold-to-mine repeats every 5 client
   ticks.
3. **No drops or statistics.** The state side table and `BlockChangeListener`
   are the hooks, but the default implementation does not spawn items, call
   `Block.getDrops`, award stats or damage tools.
4. **Placement context neighbours are virtual-air.** Orientation and
   click-based properties are correct, but `BlockPlaceContext` still reads the
   real `Level` for neighbour-dependent state. Fences therefore do not visually
   connect to adjacent structure voxels unless the world happens to contain the
   same blocks, and waterlogged/support checks are not applied. Block entities
   carried by the stack are not created inside the structure. The item is
   shrunk by one in survival only.
5. **Expansion copies the whole grid.** Building on an outer face creates a new
   expanded `MutableVoxelVolume` and copies every solid cell/state. This is fine
   for the current structure sizes but is O(volume) and changes the volume
   identity (the sync layer then sends a fresh snapshot).
6. **Read-only volumes cannot be edited.** `BlockVoxelVolume` is a live view over
   real blocks; breaking/placing correctly fails. Editing real terrain through
   the structure API is out of scope.
7. **No right-click interaction dispatch.** `interactBlock` only returns the
   stored state. Container/block-use logic is not forwarded.
8. **Occlusion uses a small tolerance.** `ClientLevel.clip` installs the
   structure hit only when it is strictly closer than the real block/fluid hit.
   The server re-raycasts from the player's eye and compares against a
   server-side `ClipContext` using a 0.02-block tolerance to absorb independent
   ray quantisation. Entities are not considered by the server payload path.
9. **Water recoil does not push a structure body.** It applies to the player
   only. A future version could apply the opposite impulse to a body if the
   water is inside/adjacent to a structure.
10. **The client `PLACE` path is a tick poll, not a vanilla callback.** It uses
    an edge-detected `keyUse` poll because Fabric exposes no pre-use-client
    callback. Because the hit result carries a real world `BlockPos`, vanilla
    also sends `useOn`; `beginPlacement` collapses the duplicate.
11. **Arbitrarily rotated outlines are enclosing boxes.** The vanilla
    `VoxelShape` used by `BlockOutlineRenderState` is axis-aligned. An
    axis-aligned identity pose at `blockScale == 1` gets the exact block-state
    shape; scaled or 90-degree-rotated structures get the transformed union of
    the state shape's boxes, which is exact for grid-aligned rotations. For an
    arbitrary rotation each transformed box is represented by its world AABB, so
    the outline over-approximates the rendered voxel. The line style, colour and
    width are always vanilla.

### Suggested next step for full client-authoritative mining

1. Extend the network snapshot factory to read `StatefulVoxelVolume` and
   `StructureBlockStates`, so mutable/restored structures keep real block
   appearances in multiplayer.
2. Extend the real-hit path with a break-progress record attached to the
   selected voxel (body id + voxel + face are already available on
   `StructureBlockHitResult`).
3. Send a validated `START/STOP/ABORT` payload with body id + voxel + sequence;
   the server re-checks reach, occupancy and ownership before editing.
4. Run vanilla hardness/tool-speed logic against the synced state, then emit
   break progress, particles, sounds, drops and statistics from the server.

---

## 9. Diagnostics and tests

- `/crythstinteraction ray` isolates server-side raycast/volume problems from
  client input problems.
- `/crythstinteraction mine|place|water` exercise the server API directly.
- `StructureRayMathTest` unit-tests the pure slab ray/AABB math and entry
  normals used by the voxel traversal.
- `StructureRaycasterTest` drives the shared structure DDA: nearest cell,
  entry faces, negative coordinates, `blockScale`, rotated poses, reach, and
  the real-block-wins rule of `StructureSelection.clip`.
- `StructurePlacementMathTest` verifies world-space face transforms
  (including a 90-degree yaw), the synthetic click staying inside the clicked
  voxel, stair/slab click fractions, pillar axis source and the exact-grid
  outline predicate.
- `StructurePlacementVanillaRulesTest` calls the real
  `StairBlock`/`SlabBlock`/`RotatedPillarBlock#getStateForPlacement` hooks with
  the synthetic click and asserts stairs facing/half, slab type and log axis.
- `StructureSelectionMathTest` verifies voxel selection and local face
  resolution for positive, negative and rotated poses.
- `RestoredStructureVolumeEditTest` verifies that a restored volume supports
  break and place edits (mass/material included).

Compile/test commands used:

```bash
./gradlew compileJava compileTestJava --rerun-tasks --no-daemon
./gradlew test --tests 'io.mikaple.crythst.integration.minecraft.interaction.StructureRayMathTest' \
                --tests 'io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolumeEditTest' \
                --rerun-tasks --no-daemon
```
