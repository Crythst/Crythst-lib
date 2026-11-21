# Player OBB collision under non-default gravity

## Overview

Vanilla Minecraft resolves player movement with a **Y-up axis-aligned bounding
box (AABB)**. Crythst's player-gravity feature lets gravity point in an
arbitrary direction, but Minecraft's movement sweep still predicts a Y-up AABB.
This makes a sideways or upside-down player sink into floors and clip through
walls even though gravity is pulling them correctly.

The player OBB collision pass is a **post-move correction**:

1. Vanilla `Player` / `LivingEntity` movement runs first, using the vanilla
   AABB.
2. At the end of each player tick, `PlayerObbCollision` builds an oriented box
   (OBB) whose local `+Y` follows the player's gravity up direction.
3. It finds the shallowest penetration against nearby terrain blocks and
   Crythst physics structures.
4. It moves the player out along the minimum translation vector (MTV), then
   removes the part of the velocity that still points into the surface.
5. On the server, the corrected position is sent with
   `ServerPlayer.connection.teleport`.

With default gravity (`PlayerGravity` returns nothing, or a world gravity of
exactly `(0,-1,0)`) the entire pass is skipped, so vanilla behaviour is
unchanged.

This pass is **not** the general entity/structure collision path. Ordinary
entities, including players under default gravity, collide with structures
inside vanilla `Entity.move` through `EntityStructureCollision` (see
[entity-physics-collision.md](entity-physics-collision.md)). `PlayerObbCollision`
only adds the tilted-box correction that vanilla's Y-up movement cannot express
when gravity is non-default.

## Files

| File | Purpose |
| --- | --- |
| `integration/minecraft/player/PlayerObb.java` | Builds the player OBB and computes SAT minimum translation vectors. |
| `integration/minecraft/player/PlayerObbCollision.java` | Tick coordinator: terrain + structure queries, correction, velocity cleanup, server teleport. |
| `mixin/player/ServerPlayerObbMixin.java` | Runs the pass at the tail of `ServerPlayer.tick` (common mixin, so it also runs in integrated servers). |
| `mixin/player/LocalPlayerObbMixin.java` | Runs the pass at the tail of `LocalPlayer.tick` for client-side prediction. |
| `resources/crythst-player.mixins.json` | Mixin configuration for the two player mixins. |

`fabric.mod.json` must register the new config (this file is owned elsewhere):

```json
"mixins": [
  "crythst.mixins.json",
  "crythst-player.mixins.json"
]
```

## Geometry

### OBB construction

Minecraft's entity position is the **feet anchor** of the gravity frame: the
body extends from `position` along the gravity up direction. For player gravity
up direction `u` (unit vector, opposite to `PlayerGravity.direction(player)`),
the OBB is:

```text
half extents: (bbWidth / 2, bbHeight / 2, bbWidth / 2)
centre:       position + u * (bbHeight / 2)
feet:         centre - u * (bbHeight / 2) == position
rotation:     shortest quaternion mapping local +Y to u
```

The same feet anchor is used by the rendered avatar and by the one eye position
`position + Q * (0, eyeHeight, 0)`, so all three stay together when `u` changes.

Because player width is applied on both local X and Z, the arbitrary roll
component of the rotation does not affect the collision shape.

### SAT and the minimum translation vector

`PlayerObb.penetration(boxA, boxB)` runs the standard 15-axis separating-axis
test:

- 3 face axes of `boxA`,
- 3 face axes of `boxB`,
- 9 edge-edge axes (`axisA × axisB`), skipping degenerate crosses.

For each unit-length axis `n`, with `d = centerA - centerB`:

```text
radiusA = Σ |n · axisA[i]| * halfExtentsA[i]
radiusB = Σ |n · axisB[j]| * halfExtentsB[j]
overlap = radiusA + radiusB - |d · n|
```

If any `overlap <= 0`, the boxes are separated and there is no push. Otherwise
the axis with the smallest overlap is the MTV axis, and the push is:

```text
push = sign(d · n) * (minOverlap + ε) * n
```

The sign points from the obstacle toward the player, so adding `push` moves the
player out. Terrain block shapes and structure voxel boxes are both converted
to `OrientedBox` instances; the AABB case is simply an identity-rotation OBB.
This is the same SAT formulation already used by
`io.mikaple.crythst.math.OrientedBox.intersects`, extended with the minimum
penetration depth.

### Terrain sampling

The player OBB is converted to a world AABB, expanded by 0.25 blocks, and passed
to `Level.getBlockCollisions(player, query)`. Every returned `VoxelShape` is
split with `toAabbs()`; each resulting world-space AABB is tested against the
player OBB. This reuses vanilla's loaded-chunk, world-border and
collision-context handling instead of hand-rolling block iteration.

### Physics structures

On the server, `MinecraftPhysics.getIfPresent(serverLevel)` supplies the
level's `PhysicsStructure` list. A structure is broad-phase rejected when its
body AABB does not intersect the player's expanded AABB. Otherwise every merged
`VoxelBox` from `structure.collider().boxes()` is transformed by
`structure.pose()`:

```text
localCentre = voxelCentre * blockScale
worldCentre = pose.transformPoint(localCentre)
halfExtents = (voxelSize * blockScale) / 2
rotation    = pose.rotation()
```

The voxel centres are **unshifted**, matching the render convention and the
main `Entity.move` collision path. `structure.pose()` is already the
structure-origin pose; subtracting the centre of mass again would shift every
box by `-R*COM`.

The player OBB is then tested against each oriented voxel box. The structure
boxes are physics-collider boxes, not individual block states, so a merged box
is treated as one solid rectangular obstacle.

## Correction loop

`PlayerObbCollision.tick` runs at most three iterations per player tick:

```text
push = shallowest penetration among all terrain and structure boxes
delta = push * playerObbCollisionStrength   (clamped to 0..1)
delta is capped at 1.5 blocks per iteration
player.setPos(player.position + delta)
```

Three iterations handle corners where resolving one contact exposes another
(floor + wall, two structure boxes, and so on). Pushes shorter than `1e-4`
blocks are ignored to prevent jitter.

After the loop the velocity component pointing into the surface is removed:

```text
outward = normalize(totalCorrection)
intoSurface = deltaMovement · outward
if intoSurface < 0: deltaMovement -= outward * intoSurface
```

This does not cancel tangential motion; it only stops inward velocity from
accumulating while the post-move correction holds the player on the surface.

### Server/client behaviour

- **Client (`LocalPlayer`)**: the same terrain correction runs locally after
  the client tick, providing immediate local prediction.
- **Server (`ServerPlayer`)**: terrain and Crythst structures are checked. If a
  correction was made, the authoritative position is sent to the client with
  `connection.teleport(x, y, z, yaw, pitch)`.
- **Integrated server**: `ServerPlayerObbMixin` is in the common mixin list, so
  it runs inside the integrated server as well as on a dedicated server.

## Configuration

From `CrythstConfig`:

```json
{
  "playerObbCollision": true,
  "playerObbCollisionStrength": 0.6
}
```

- `playerObbCollision` disables the entire pass.
- `playerObbCollisionStrength` is the fraction of the MTV applied per
  iteration. `1.0` snaps out in one iteration; lower values look softer but
  recover over several ticks.
- The global `enabled` switch also disables the pass.

## Limitations

This is intentionally a pragmatic first version, not a replacement for
vanilla movement:

1. **Vanilla movement still predicts an AABB.** The player's `getBoundingBox()`
   and `Entity.move` remain Y-up. Tilted OBB contacts are only corrected after
   the vanilla move has already happened.
2. **Post-move correction can look like sinking or popping.** A fast-moving
   player can penetrate deeply during one tick before the correction runs. The
   1.5-block per-iteration cap also means extreme penetrations take several
   ticks to resolve.
3. **Server correction may rubber-band.** The server teleports the client to the
   corrected position. A client whose local prediction differs (especially
   around structures, which this particular pass only queries on the server)
   can snap back.
4. **Structures use merged collider boxes.** Slabs, stairs, fences and other
   non-full blocks inside a structure are approximated by the merged voxel
   boxes produced by `VoxelColliderBuilder`; they are not re-read per block.
5. **This pass only queries structures on the server.** `PlayerObbCollision`
   has no client structure source of its own, so its client-side prediction
   covers terrain only. The separate `Entity.move` collision path *does* run on
   the client against local or network snapshots; this limitation applies only
   to the non-default-gravity OBB post-correction.
6. **Velocity handling is a projection, not a full contact solver.** Only the
   inward component is removed; there is no friction, restitution or
   character-controller stepping along the tilted surface.
7. **No entity, fluid or world-border OBB tests.** Terrain uses
   `getBlockCollisions`, which includes block/border collisions; ordinary
   entity/player structure collision is handled by `EntityStructureCollision`
   inside `Entity.move`, not by this pass.
8. **Passengers, spectators, sleeping and dead players are skipped.** Their
   vanilla handling is left untouched.

## Next steps for a true OBB movement solver

1. Replace the post-move pass with an OBB swept-move routine:
   - orient the desired movement vector in the player's gravity frame,
   - sweep the OBB against block AABBs and structure OBBs with a conservative
     advancement or binary-search time of impact,
   - slide along the contact plane instead of only pushing out afterwards.
2. Add a proper character controller step: ground detection along `-up`,
   step-up height, slope limit, and velocity projection including friction.
3. Give this OBB pass the same client structure source already used by
   `EntityStructureCollision` (`ClientPhysicsView` / `ClientStructureCache`) so
   tilted-gravity structure contacts can be predicted locally too.
4. Replace `connection.teleport` corrections with a small reconciliation
   protocol (server correction delta + client replay) to avoid rubber-banding.
5. Extend the pass to entity collisions and fluid volumes once the entity
   collision workstream exposes its OBB data.
6. Add unit tests for SAT edge cases (containment, face contact, edge-edge
   contact, anti-parallel gravity) using the pure-math helpers.
