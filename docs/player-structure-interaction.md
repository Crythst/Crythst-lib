# Player ↔ physics-structure interaction

This document describes the first-version interaction layer for blocks that live
inside a Crythst `PhysicsStructure` instead of Minecraft's block grid.

Status: **server-authoritative core + optional client request hook**. The common
and client registration lines are already present in the current tree. No new
mixin is required; Fabric's existing interaction callbacks and one small custom
payload are used instead.

---

## 1. What works

| Requirement | Implemented path |
| --- | --- |
| Left-click a structure block to break it (creative and survival) | `StructureInteraction.breakBlock(...)` via `AttackBlockCallback` and the client `MINE` payload |
| Breaking applies a reaction impulse to the body | `structure.applyImpulseAt(-look * 400 * miningImpulseScale, hitPoint)` |
| Right-click a structure block to place a block | `StructureInteraction.placeBlock(...)` via `UseBlockCallback`, `UseItemCallback`, and the `PLACE` payload for virtual-air targets |
| Shovel + left-click water applies player recoil | `StructureInteraction.shovelWater(...)` via the client `SHOVEL_WATER` payload |
| Default behaviour unchanged | Every hook returns `PASS` unless a structure is actually in front of a real block / the ray is water and a shovel is held |
| Restored structures can be edited | `RestoredStructureVolume.setBlock(...)` plus the interaction adapter |
| Building on an outer face | the volume is expanded by one cell into a new mutable volume |

There is no new mixin, so `crythst-interaction.mixins.json` is intentionally not
created. The existing Fabric callbacks already run on the server at the correct
`START_DESTROY_BLOCK` / `useItemOn` / `useItem` points.

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

- `src/main/java/io/mikaple/crythst/integration/minecraft/interaction/StructureInteraction.java`
- `.../interaction/StructureHit.java`
- `.../interaction/StructureBlockStates.java`
- `.../interaction/StructureAction.java`
- `.../interaction/StructureActionPayload.java`
- `.../interaction/StructureInteractionEvents.java`
- `.../interaction/StructureInteractionCommand.java`
- `.../interaction/StructureRayMath.java`

New client helper files:

- `.../interaction/client/ClientStructureInteraction.java`
- `.../interaction/client/ClientStructureRay.java`

Edited support files:

- `.../integration/minecraft/storage/RestoredStructureVolume.java` — added the
  full `setBlock(...)` edit entry point used by the interaction adapter.
- `.../integration/minecraft/storage/SavedStructureVolume.java` — persistence
  now reads explicit states from `StatefulVoxelVolume` (restored structures), so
  edits do not fall back to stone on the next save.

No core physics file, config file, `fabric.mod.json`, mixin config or renderer
file was edited.

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

This registers `ClientPreAttackCallback` and an end-of-client-tick placement
poll. The pre-attack hook only intercepts when the structure ray wins against a
real-block clip, or when the main-hand item is in `ItemTags.SHOVELS` and the
crosshair is over water. The placement poll only sends `PLACE` when the held
item is a `BlockItem` and no real block is in reach; real block targets stay on
the vanilla `useOn` path and are resolved by the server `UseBlockCallback`.

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
StructureHit hit = StructureInteraction.raycast(ServerPlayer player);
StructureHit hit = StructureInteraction.raycast(ServerPlayer player, double reach);

// Break the hit voxel. Applies the mining reaction impulse.
StructureInteraction.BreakResult br = StructureInteraction.breakBlock(player, hit);
// br.success(), br.structureRemoved(), br.previousState()

// Place on the face adjacent to the hit. Works for mutable and restored
// volumes; an outer-face target expands the volume by one cell.
StructureInteraction.PlaceResult pr = StructureInteraction.placeBlock(
        player, hit, Blocks.STONE.defaultBlockState());

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

Useful pieces of `StructureHit`:

```java
hit.structure();      // PhysicsStructure
hit.rayHit();         // RayHit (body, point, normal, distance)
hit.voxelX();         // resolved structure-local voxel
hit.voxelY();
hit.voxelZ();
hit.face();           // structure-local cardinal direction
hit.adjacentX();      // voxel + face step (placement target)
hit.adjacentY();
hit.adjacentZ();
hit.point();          // world-space hit point
hit.distance();       // world-space distance
```

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
   space.
3. If the target is inside the bounds it must be empty.
4. If the target is outside (outer face), the bounds are expanded by one cell,
   all existing solids/mass/material/states are copied into a new
   `MutableVoxelVolume`, and the structure is reindexed. A restored volume
   therefore becomes editable and expandable after the first outside
   placement.
5. Mass/material come from `MinecraftPhysics.blockPhysics().properties(state)`.
6. Stores the state in the stateful volume or `StructureBlockStates`.
7. Reindexes and refreshes the auxiliary block-ticking level.
8. Applies a small reaction impulse (`400 * 0.3 * miningImpulseScale`).
9. Plays the state's place sound and notifies listeners.

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

It carries no coordinates. The server re-runs the authoritative raycast from the
player's server-side eye position and look vector, so a modified client cannot
name an arbitrary structure/voxel. `PLACE` uses the server's current main-hand
`BlockItem` state and the same footprint/occlusion checks as the block
callbacks.

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

1. **Multiplayer depends on structure sync.** `ClientStructureRay` first uses
   singleplayer `ClientPhysicsView` snapshots, then falls back to
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
4. **No block-state fidelity on placement.** Placement uses
   `BlockItem.getBlock().defaultBlockState()`. Orientation, waterlogged state,
   block entities, `BlockPlaceContext` neighbours and `canSurvive` checks are
   not applied. The item is shrunk by one in survival only.
5. **Expansion copies the whole grid.** Building on an outer face creates a new
   expanded `MutableVoxelVolume` and copies every solid cell/state. This is fine
   for the current structure sizes but is O(volume) and changes the volume
   identity (the sync layer then sends a fresh snapshot).
6. **Read-only volumes cannot be edited.** `BlockVoxelVolume` is a live view over
   real blocks; breaking/placing correctly fails. Editing real terrain through
   the structure API is out of scope.
7. **No right-click interaction dispatch.** `interactBlock` only returns the
   stored state. Container/block-use logic is not forwarded.
8. **Structure entity/terrain occlusion is approximate.** The server compares
   physics ray distance with the client's reported vanilla hit (or a server-side
   `ClipContext` for item use/PLACE) with a 0.35-block slack. Entities are not
   considered by the server payload path.
9. **Water recoil does not push a structure body.** It applies to the player
   only. A future version could apply the opposite impulse to a body if the
   water is inside/adjacent to a structure.
10. **The client `PLACE` path is a tick poll, not a vanilla callback.** It uses
    an edge-detected `keyUse` poll because Fabric exposes no pre-use-client
    callback. Real-block targets stay on the normal `useOn` path; only
    virtual-air targets use `PLACE`.

### Suggested next step for full client-authoritative mining

1. Extend the network snapshot factory to read `StatefulVoxelVolume` and
   `StructureBlockStates`, so mutable/restored structures keep real block
   appearances in multiplayer.
2. Add a client-side raycast in `GameRenderer.pick`/`MultiPlayerGameMode` that
   returns a virtual hit (body id + voxel + face) and a break-progress record.
3. Send a validated `START/STOP/ABORT` payload with body id + voxel + sequence;
   the server re-checks reach, occupancy and ownership before editing.
4. Run vanilla hardness/tool-speed logic against the synced state, then emit
   break progress, particles, sounds, drops and statistics from the server.

---

## 9. Diagnostics and tests

- `/crythstinteraction ray` isolates server-side raycast/volume problems from
  client input problems.
- `/crythstinteraction mine|place|water` exercise the server API directly.
- `StructureRayMathTest` unit-tests the pure slab ray/AABB math used by the
  client raycast.
- `RestoredStructureVolumeEditTest` verifies that a restored volume supports
  break and place edits (mass/material included).

Compile/test commands used:

```bash
./gradlew compileJava compileTestJava --rerun-tasks --no-daemon
./gradlew test --tests 'io.mikaple.crythst.integration.minecraft.interaction.StructureRayMathTest' \
                --tests 'io.mikaple.crythst.integration.minecraft.storage.RestoredStructureVolumeEditTest' \
                --rerun-tasks --no-daemon
```
