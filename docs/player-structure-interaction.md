# Player ↔ physics-structure interaction

This document describes the first-version interaction layer for blocks that live
inside a Crythst `PhysicsStructure` instead of Minecraft's block grid.

Status: **server-authoritative core + optional client request hook**. It compiles
against Fabric 26.3 / JDK 25, but the two registration lines below still have to
be added by the integration owner. No new mixin is required; Fabric's existing
interaction callbacks and one small custom payload are used instead.

---

## 1. What works

| Requirement | Implemented path |
| --- | --- |
| Left-click a structure block to break it | `StructureInteraction.breakBlock(...)` via `AttackBlockCallback` and the client `MINE` payload |
| Breaking applies a reaction impulse to the body | `structure.applyImpulseAt(-look * 400 * miningImpulseScale, hitPoint)` |
| Right-click a structure block to place a block | `StructureInteraction.placeBlock(...)` via `UseBlockCallback` / `UseItemCallback` |
| Shovel + left-click water applies player recoil | `StructureInteraction.shovelWater(...)` via the client `SHOVEL_WATER` payload |
| Default behaviour unchanged | Every hook returns `PASS` unless a structure is actually in front of the vanilla hit / the ray is water and a shovel is held |

There is no new mixin, so `crythst-interaction.mixins.json` is intentionally not
created. The existing Fabric callbacks already run on the server at the correct
`START_DESTROY_BLOCK` / `useItemOn` / `useItem` points.

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

New client helper files:

- `.../interaction/client/ClientStructureInteraction.java`
- `.../interaction/client/ClientStructureRay.java`

No core physics file, config file, `fabric.mod.json`, `crythst.mixins.json` or
renderer file was edited.

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
- the diagnostic commands `/crythstmine` and `/crythstplace`.

### 3.2 Client entrypoint

In `CrythstClient.onInitializeClient()`:

```java
io.mikaple.crythst.integration.minecraft.interaction.client.ClientStructureInteraction.register();
```

This registers `ClientPreAttackCallback`. It only intercepts a click when the
client can actually see a structure (singleplayer `ClientPhysicsView` snapshots)
or when the main-hand item is in `ItemTags.SHOVELS` and the crosshair is over
water. Otherwise it returns `false` and vanilla handling continues untouched.

### 3.3 Optional `/crythst mine` and `/crythst place` aliases

The commands are already available as `/crythstmine` and `/crythstplace` so no
shared command file had to be modified. To expose them under the existing
`/crythst` tree, add these two nodes to `CrythstCommand.register(...)`:

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

// Place into the cell adjacent to the hit face. Requires a MutableVoxelVolume
// and an in-bounds, empty target cell.
StructureInteraction.PlaceResult pr = StructureInteraction.placeBlock(
        player, hit, Blocks.STONE.defaultBlockState());

// Placeholder for right-click block logic. Returns the side-table BlockState
// (or null) without changing anything.
BlockState state = StructureInteraction.interactBlock(player, hit);

// Shovel + water recoil. Returns whether recoil was applied.
boolean recoiled = StructureInteraction.shovelWater(player);

// Entry point used by the C2S payload.
boolean handled = StructureInteraction.handleAction(player, StructureAction.MINE);
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

1. Rejects non-`MutableVoxelVolume` volumes (`BlockVoxelVolume` is read-only).
2. Removes the side-table block state.
3. Calls `MutableVoxelVolume.setSolid(x, y, z, false)`.
4. If the volume is now empty, calls `LevelPhysics.removeStructure(structure)`
   instead of `reindex` because `PhysicsStructure.reindex` rejects an empty
   volume.
5. Otherwise calls `structure.reindex(volume)` and
   `levelPhysics.refreshStructure(structure, volume)`.
6. Applies `-look * 400 * miningImpulseScale` at the ray hit point through
   `PhysicsStructure.applyImpulseAt`.
7. Plays the stored state's break sound when a state exists and notifies
   registered `BlockChangeListener`s.

### 4.2 Placement behaviour

1. Requires a `MutableVoxelVolume`.
2. Target is `voxel + face`; it must be inside the existing volume bounds and
   empty. The volume is **not** expanded.
3. Mass/material come from `MinecraftPhysics.blockPhysics().properties(state)`.
4. Stores the state in `StructureBlockStates`.
5. Reindexes and refreshes the auxiliary block-ticking level.
6. Applies a small reaction impulse (`400 * 0.3 * miningImpulseScale`).
7. Plays the state's place sound and notifies listeners.

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
- `1` = `SHOVEL_WATER`

It carries no coordinates. The server re-runs the authoritative raycast from the
player's server-side eye position and look vector, so a modified client cannot
name an arbitrary structure/voxel.

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

`MutableVoxelVolume` stores only solidity, mass and material, so Minecraft block
states are kept in a side table:

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
but there is no state-dependent sound and no drop. If a future volume type
stores states itself, this side table can be replaced without changing the
interaction call sites.

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

1. **No structure sync to clients.** `ClientPhysicsView` only publishes
   structure snapshots in singleplayer. In multiplayer the client sees no
   structure blocks and the client pre-attack hook cannot recognise them.
   Structure interaction then only works through the server command or a
   separately synced client mod.
2. **No vanilla-style mining progress.** A click breaks one voxel immediately.
   Tool speed, hardness, break animation, `START/STOP/ABORT_DESTROY_BLOCK`
   progress and cooldowns are not modelled.
3. **No drops or statistics.** The state side table and `BlockChangeListener`
   are the hooks, but the default implementation does not spawn items, call
   `Block.getDrops`, award stats or damage tools.
4. **No block-state fidelity on placement.** Placement uses
   `BlockItem.getBlock().defaultBlockState()`. Orientation, waterlogged state,
   block entities, `BlockPlaceContext` neighbours and `canSurvive` checks are
   not applied. The item is only shrunk by one in survival.
5. **No volume growth.** Placing on the outer face of a structure fails because
   the target voxel is outside the existing `IntBounds3`. Expanding a volume
   would require a new `MutableVoxelVolume` plus pose/centre-of-mass handling.
6. **Read-only volumes cannot be edited.** `BlockVoxelVolume` is a live view over
   real blocks; breaking/placing correctly fails. Editing real terrain through
   the structure API is out of scope.
7. **No right-click interaction dispatch.** `interactBlock` only returns the
   stored state. Container/block-use logic is not forwarded.
8. **Structure entity/terrain occlusion is approximate.** The server compares
   physics ray distance with the client's reported vanilla hit (or a server-side
   `ClipContext` for item use) with a 0.35-block slack. Entities are not
   considered.
9. **Water recoil does not push a structure body.** It applies to the player
   only. A future version could apply the opposite impulse to a body if the
   water is inside/adjacent to a structure.
10. **Client hook is singleplayer-only for structure mining.** This is a direct
    consequence of (1). Shovel-water works in multiplayer because it only needs
    the vanilla fluid ray and the payload.

### Suggested next step for full client-authoritative mining

1. Sync a compact structure view to clients: body id, interpolated pose,
   `VoxelColliderData`/occupancy, block-scale, centre-of-mass offset and the
   per-voxel `BlockState` side table.
2. Add a client-side raycast in `GameRenderer.pick`/`MultiPlayerGameMode` that
   returns a virtual hit (body id + voxel + face) and a break-progress record.
3. Send a validated `START/STOP/ABORT` payload with body id + voxel + sequence;
   the server re-checks reach, occupancy and ownership before editing.
4. Run vanilla hardness/tool-speed logic against the synced state, then emit
   break progress, particles, sounds, drops and statistics from the server.
