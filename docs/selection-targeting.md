# Structure selection and targeting

Crythst uses Minecraft's own block-clip path for physics-structure voxels.
There is no separate `ClientStructureRay`, `StructureHit`, targeting singleton or
custom white-box selection renderer. `Minecraft.pick`, the vanilla block-outline
extractor, mining, placement and pick-block all see the same
`StructureBlockHitResult`.

## One clip result

`ClientLevelClipMixin` adds an ordinary `clip(ClipContext)` method to
`ClientLevel`:

```java
public BlockHitResult clip(ClipContext context) {
    if (!StructureSelection.enabled()) {
        return StructureSelection.vanillaClip(level, context);
    }
    return StructureSelection.clip(level, context, ClientStructureSelection.views());
}
```

`StructureSelection.clip` performs two passes:

1. a re-implementation of vanilla `BlockGetter.clip` (blocks and fluids);
2. a structure-only voxel DDA over the client candidates.

The structure hit wins only when it is **strictly closer** than the real hit,
with a `1.0e-4` floating-point tolerance (`REAL_HIT_EPSILON`). If the vanilla
clip is a miss, a structure hit is returned directly. Entities are handled by
vanilla's own entity pick; an entity in front means the current
`Minecraft.hitResult` is not a structure hit, so vanilla keeps the click.

Client candidates come from `ClientStructureSelection.views()`:

- singleplayer: `ClientPhysicsView.structures()`, sampled at the current frame
  time with `renderStateAt(now)`;
- multiplayer: `ClientStructureCache.snapshots()`, sampled through
  `ClientStructureCache.interpolatedPose(bodyId, now)`.

A live integrated-server body wins over a duplicate network body id.

## The hit type

`StructureBlockHitResult extends BlockHitResult`, so existing vanilla consumers
work unchanged:

| Field | Meaning |
| --- | --- |
| `getLocation()` | world-space hit point |
| `getDirection()` | outward world face |
| `getBlockPos()` | real world block containing the hit point (often air) |
| `bodyId()` | physics body / synced snapshot id |
| `structurePose()` | structure-origin pose used by the raycast (not the COM body pose) |
| `voxelX()` / `voxelY()` / `voxelZ()` | structure-local voxel coordinates |
| `localFace()` | structure-local outward face |
| `worldFace()` | transformed world-space cardinal face |
| `blockScale()` | metres per voxel |
| `blockState()` | state of the hit voxel |
| `worldHit()` | `getLocation()` as a JOML vector |
| `adjacentX/Y/Z()` | voxel plus one step along `localFace()` (placement target) |
| `distance()` | world-space distance from the ray origin |
| `structure()` | authoritative `PhysicsStructure`, or `null` for a client snapshot |

The voxel DDA is shared by the client clip and the server interaction path
(`StructureRaycaster`). It transforms the world ray into structure-local voxel
space, so negative coordinates, rotation and non-unit `blockScale` need no
special cases.

## Outline hand-off

`StructureSelectionOutline` subscribes to
`LevelExtractionEvents.AFTER_BLOCK_OUTLINE_EXTRACTION`. When the current hit is a
structure hit it:

1. resolves the block-local `VoxelShape` with `BlockState#getShape` through a
   read-only structure-local `BlockGetter`;
2. publishes a `BlockOutlineRenderState` containing that shape;
3. pairs that exact state object with a `StructureOutlineTarget`.

`StructureBlockOutlineMixin` injects at the head of
`LevelRenderer#submitBlockOutline` and recognises only that state by identity.
It applies the structure voxel pose and delegates to vanilla's private
`submitHitOutline` through `LevelRendererOutlineInvoker`, then cancels the
default axis-aligned submission. The block-local shape is submitted unchanged,
so stairs/slabs keep their exact shape and arbitrary structure rotation and
`blockScale` are exact. Render types, colours, high-contrast handling and
screen-space line width remain vanilla. See
[structure-outline.md](structure-outline.md).

## Click paths

All click paths are server-authoritative and use the same raycast:

- **Mining.** `ClientPreAttackCallback` reads the current
  `StructureBlockHitResult` and sends `MINE`. The server re-raycasts from its own
  eye and `PlayerGravity.lookDirection(player)`, checks real-block occlusion and
  calls `StructureInteraction.breakBlock`.
- **Placement.** `StructurePlacementMixin` runs at the head of
  `MultiPlayerGameMode.useItemOn`, before 26.3 client prediction. For a
  structure hit it consumes the click and sends only `PLACE`, so vanilla never
  runs `BlockItem.place` on the client and no ghost block or duplicate place
  sound appears. The server resolves the block state from a real
  `BlockPlaceContext`, including stairs/slabs/logs orientation.
- **Pick block.** `StructurePickBlockMixin` runs at the head of
  `Minecraft.pickBlockOrEntity`. For a structure hit it sends `PICK` or
  `PICK_WITH_DATA` (Ctrl) and cancels vanilla, so no
  `ServerboundPickItemFromBlockPacket` is sent for an air position. The server
  resolves the stored state and calls vanilla's private
  `ServerGamePacketListenerImpl#tryPickItem` through the invoker.
- **Commands.** `/crythstmine` and `/crythstplace` call the same
  `StructureInteraction` methods for diagnostics.

The `crythst:structure_action` payload carries only one action byte
(`MINE`, `PLACE`, `SHOVEL_WATER`, `PICK`, `PICK_WITH_DATA`). It carries no
coordinates or block state. The server re-runs the raycast and placement/state
resolution, so a modified client cannot name an arbitrary body or voxel.

## Server occlusion and reach

`StructureInteraction.isStructureInFrontOfRealBlocks` performs a server-side
`ClipContext.Block.OUTLINE` / `Fluid.NONE` clip from the player's eye. The
structure must be within `FRONT_SLACK` (`0.02` blocks) of the real hit or closer.
Reach uses `ServerPlayer.blockInteractionRange()`; no separate Crythst reach
attribute is added.

## Limitations

- **Multiplayer depends on structure sync.** Without a synced snapshot the
  client cannot see the structure voxels, so selection falls back to the real
  world. Block state, collision and outline all use the synced palette.
- **Break progress is not vanilla-style.** A click removes one voxel
  immediately. Hardness, tool speed, break animation and
  `START/STOP/ABORT_DESTROY_BLOCK` are not modelled; hold-to-mine repeats every
  five client ticks.
- **No drops or statistics.** `BlockChangeListener` is the hook, but the default
  implementation does not spawn items, call `Block.getDrops`, award stats or
  damage tools.
- **Placement neighbours are virtual-air.** The server builds a real
  `BlockPlaceContext`, but `BlockPlaceContext` reads the real `Level` for
  neighbour-dependent state. Fences therefore do not visually connect to
  adjacent structure voxels unless the real world happens to contain them, and
  waterlogging/support checks are not applied. Block entities carried by the
  stack are not created inside the structure.
- **Placement is main-hand only.** Off-hand placement keeps vanilla behaviour.
- **No right-click block interaction dispatch.** `interactBlock` only returns
  the stored state; containers and block-use logic are not forwarded.
- **Structure blocks are not in vanilla collision.** Selection and
  `Entity.move` collision see them because Crythst hooks those paths. Other
  vanilla systems (`Level.getBlockCollisions`, pathfinding, vanilla placement
  checks, explosions) do not.
- **Network pose lag.** Rendering/selection use the timestamped interpolated
  pose; the network collision source uses the newest synced pose. A fast-moving
  remote structure can have a collision surface roughly one sync interval ahead
  of the rendered outline.
