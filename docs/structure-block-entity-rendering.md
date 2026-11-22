# Structure block-entity rendering

Status: singleplayer implementation. Multiplayer block-entity NBT sync is still
incomplete.

## Why a separate pass is required

`BatchedStructureRenderer` draws baked block models only. Vanilla blocks such
as signs, banners, skulls, beds, chests, shulker boxes, bells and moving pistons
either have no block model or require a `BlockEntityRenderer` for their visible
geometry or data.

`StructureBlockSnapshots` now carries:

- the copied block-state grid;
- structure-local block-entity NBT (`StructureBlockEntityData`);
- `moving_piston` moved state/progress/direction entries;
- a block-entity revision so snapshots rebuild when inventories/sign text/etc.
  change, not only when blocks change.

`PhysicsStructure.blockEntityRevision()` is bumped whenever the auxiliary
block-entity mirror writes new NBT.

## Vanilla block-entity pass

`client.render.structure.blockentity.StructureBlockEntityRenderer`:

1. Reads the structure snapshot for each rendered body.
2. Recreates a client-side `BlockEntity` with
   `BlockEntity.loadStatic(localWorldPos, state, nbt, registryAccess)`.
3. Extracts the vanilla `BlockEntityRenderState` through
   `BlockEntityRenderDispatcher`.
4. Submits it with `StructureOutlineTransform.cameraRelativeMatrix`, so the
   BER model follows the structure rotation and `blockScale`.

Vanilla renderers therefore keep their own models, item stacks, sign text,
banner patterns, skull profiles and shulker colors.

## Moving pistons

`StructureMovingBlockRenderer` uses
`SubmitNodeCollector#submitMovingBlock`. The snapshot stores the moved
`blockState`, `progress`, `facing`, `extending` and `source` from the
`PistonMovingBlockEntity`. The offset follows vanilla
`getExtendedProgress`: extending moves from `-1` to `0`, retracting from `+1`
to `0`. Moving blocks are not baked into the static section mesh.

## Limitations

- Singleplayer/integrated server only; remote clients do not receive structure
  block-entity NBT yet.
- `BlockEntityRenderDispatcher` extracts against the host `ClientLevel` at the
  projected world position. A BER that queries neighbouring real blocks (for
  example double-chest connectivity) may see air instead of the structure
  neighbour.
- Per-frame synthetic `BlockEntity` creation is cached per snapshot revision;
  modded BEs that assume a fully consistent `Level` may still fail. Exceptions
  skip that entity instead of breaking the frame.
