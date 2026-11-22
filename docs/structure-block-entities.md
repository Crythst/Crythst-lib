# Structure block entities: animation, editing and feedback

This note covers the interaction/render side of structured block entities.

## Chest lid animation

Chest openness is transient `BlockEntity` state driven by an opener counter; it
is not part of saved NBT. `StructureBlockSnapshots.Snapshot` therefore carries a
small per-voxel `openCountAt(...)` map alongside the captured NBT:

- `EmptyPhysicsLevel#captureBlockEntityOpenCounts` reads only registered
  block-entity positions and only keeps `ChestBlockEntity`s.
- A changed opener count rebuilds the snapshot even when `contentRevision` and
  `blockEntityRevision` are unchanged.
- `StructureBlockEntityRenderer` reuses the synthetic client block entities for
  animation-only rebuilds, feeds the count to the vanilla
  `ChestBlockEntity.triggerEvent(1, count)`, and ticks the lid controller only
  while a chest is opening or closing.
- The block state (and therefore `ChestType.LEFT/RIGHT` and `FACING`) still
  comes from the snapshot, so double-chest orientation is preserved.
- Chest open/close sounds continue to go through the auxiliary level's existing
  sound projection.

Normal and trapped chests are supported. Ender chests and shulker boxes have
their own private animation controllers and are not currently mirrored.

## Sign editing

Vanilla sends `ClientboundOpenSignEditorPacket`/`ServerboundSignUpdatePacket`
with a real `BlockPos`. Structure signs live in the auxiliary void level, so the
host client cannot resolve them. The structure path is:

1. Right-clicking a sign runs a server-side mirror of
   `SignBlock#useWithoutItem`, using a structure-local front/back calculation.
2. `structure_sign_open` (S2C) carries body id, structure-local voxel, slot,
   block-state id, the sign NBT and an approximate host position for the
   editor's distance check.
3. The client builds a synthetic `SignBlockEntity` and opens the vanilla sign
   editor UI. It never sends a vanilla sign-update packet.
4. `structure_sign_update` (C2S) carries body id, structure-local voxel, slot
   and the four lines.
5. The server re-checks the editor UUID, reach and line of sight, applies normal
   text filtering, writes the auxiliary `SignBlockEntity`, mirrors the NBT and
   refreshes the local snapshot immediately.

Hanging signs use the same path and the vanilla hanging-sign editor.
Honeycomb/glow-ink applicators are applied through the same structure-local
slot calculation, so waxing/glowing affects the face the player is looking at.

## Break particles

Break feedback no longer uses vanilla level event `2001`, which works in integer
`BlockPos` space. `StructureFeedback` emits `BlockParticleOption` samples at
exact points computed from the structure pose, voxel and local fraction, with
rotation-aware velocities scaled by `blockScale`. Auxiliary-level `2001` events
are suppressed while the redirect path emits its own burst, and otherwise
intercepted and converted to the same pose-aware burst so there is no duplicate.

## Limitations

- The block-entity snapshot bridge is currently singleplayer only; multiplayer
  block-entity NBT/animation sync is not complete.
- Debris samples use the full voxel volume, not the block's per-state collision
  shape.
