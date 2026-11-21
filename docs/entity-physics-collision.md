# Entity ↔ physics structure collision

This document describes the one-way collision pass that makes normal Minecraft
entities collide with Crythst physics structures. It is implemented by
`io.mikaple.crythst.integration.minecraft.entity.EntityPhysicsCollisionManager`
and the pure geometry helper
`io.mikaple.crythst.integration.minecraft.entity.EntityStructureSolver`.

## One-way rule

The pass is intentionally asymmetric:

- Entities (mobs, items, players, ...) are moved out of a structure.
- Structures are **never** moved, accelerated or otherwise affected by an
  entity.

The manager only reads a structure's current pose and its immutable
`VoxelColliderData` box list. It never calls `applyCentralImpulse`,
`applyImpulseAt`, `setPose`, `teleport` or any other mutating method on the
structure body. Walking into a floating structure therefore cannot push it
around; the entity is simply stopped at its surface.

This is a gameplay-collision convention, not a physical conservation law. A
future version could optionally apply a reaction impulse based on the entity's
mass and relative velocity, but that would be a separate, explicitly enabled
feature. The current implementation has no reaction path at all.

## Tick placement and wiring

`PhysicsTicker.register()` subscribes, in this order:

1. `MinecraftPhysics::tick` on `ServerTickEvents.END_SERVER_TICK`
2. `EntityPhysicsCollisionManager.register()` on the same event

Registration order matters: the collision pass runs after the physics world has
advanced and the structures have updated their body poses for the tick. A
structure created or removed during the tick is visible as soon as
`LevelPhysics.structures()` reflects it.

The manager is disabled when:

- `entityPhysicsCollisionEnabled` is `false`, or
- the physics backend is unavailable (`MinecraftPhysics.available()` is false).

When a level has no structures the snapshot is empty and the pass costs one
`structures().isEmpty()` check plus the per-level lookup. Default behaviour is
unchanged.

## Algorithm

Each server tick:

1. For every `ServerLevel` with a `LevelPhysics`, snapshot its live structures:
   - current `PhysicsStructure.pose()` (local voxel metres → world),
   - the merged `VoxelBox` list from `VoxelColliderData`,
   - `blockScale`,
   - the body's world AABB from `PhysicsStructure.aabb()` for the broad phase.
2. Iterate `ServerLevel.getAllEntities()` and skip ineligible entities.
3. Broad phase: keep structures whose world AABB intersects the entity AABB
   expanded by the collision margin. Only the nearest eight are resolved.
4. Narrow phase, per structure, per entity:
   - Transform the entity AABB into structure-local voxel space and cull merged
     boxes against that local query box. This avoids transforming distant
     boxes.
   - Reject each candidate with an AABB/OBB world-space check and the full
     15-axis separating-axis test (3 entity axes, 3 structure axes, 9 cross
     axes).
   - Choose the shallowest of the six **face** axes as the minimum translation
     vector (MTV). Cross axes are only used for rejection: de-penetrating along
     a diagonal cross axis is unstable at edges, so it is not used for motion.
   - Bias ties toward the upward escape. This keeps entities resting on a floor
     instead of being squeezed sideways by floating-point ties.
   - Choose the sign of each face axis using the entity AABB centre from the
     previous tick when available. This sends an entity back toward the side it
     entered from instead of through a thin wall after it crossed the wall's
     mid-plane.
5. Add the configured `entityCollisionMargin` to the MTV and translate the
   entity. Up to three passes are run per structure so one merged box cannot
   leave the entity inside a neighbouring box.
6. Remove the velocity component pointing into the structure. This prevents the
   next tick from re-penetrating and lets horizontal movement slide along a
   wall.

The solver treats penetrations of `<= 1e-4` m as resting contact. Combined with
the margin, an entity that is already outside is not moved, and a resting
entity does not receive a correction every tick unless gravity or movement
actually re-penetrates the surface.

### Standing on top vs. embedded in a side

The shallowest penetration axis naturally distinguishes the two cases:

- Feet slightly below the top of a floor: vertical overlap is small, so the MTV
  points up. Downward velocity is removed, `setOnGround(true)` is set and fall
  distance is reset. This helps mob AI and fall damage even though the
  structure is not a vanilla block.
- Body overlapping a wall: horizontal overlap is small, so the MTV points out of
  the wall and the inward velocity component is removed.

A tie-break prefers an upward normal when two face axes have effectively equal
depth. The previous-tick hint only changes the sign, never which axis is
shallowest.

### Pose convention and broad phase

The solver receives the structure-origin pose (`structure.pose()`) and the
unshifted merged boxes. This is equivalent to the body convention:

```text
structurePose * (voxel * blockScale)
bodyPose   * (voxel * blockScale - centreOfMassOffset)
```

`PhysicsStructure#pose()` already subtracts the rotated centre-of-mass offset
from the body position, so both expressions map the same voxel centre into the
world. `EntityStructureSolverTest#structurePoseAndBodyPoseProduceTheSameWorldBox`
guards this equivalence.

The broad phase deliberately uses `PhysicsStructure#aabb()` (Bullet's
world AABB) rather than reconstructing one from `collider.localBounds()`.
`localBounds()` is centre-of-mass-relative and must be paired with the body
pose; pairing it with the structure-origin pose shifts the bounds by a rotated
COM offset.

### Players

Server players are server-authoritative only through teleport packets; the
client predicts vanilla movement. The manager therefore calls
`ServerPlayer.connection.teleport(...)` after computing the correction and then
re-applies the adjusted velocity. This keeps the server copy and the client
copy in the same place. Other entities use `Entity.setPos`.

## Eligibility and filtering

The manager always skips:

- removed entities and entities that are not alive,
- spectators,
- passengers (their vehicle is handled instead),
- entities with `noPhysics`,
- display entities (visual only),
- `EnderDragonPart` sub-entities.

An optional filter can be installed at runtime:

```java
EntityPhysicsCollisionManager.setEntityFilter(entity ->
        entity.getType() != EntityType.ARMOR_STAND);
```

Returning `false` is a blacklist. Combined with a type check it can also act as
a whitelist. The filter is evaluated after the base exclusions.

## Diagnostics

`/crythst entitydebug` (permission level: game masters) inspects up to four
entities within eight blocks of the command source. For each entity it prints:

- the entity type, UUID, world position and world AABB,
- each nearby structure's body id, collision pose (position + quaternion), body
  AABB and the local voxel query box,
- the chosen MTV (`hit normal`, penetration depth and world offset), or
  `hit=none`,
- `centerInside`, i.e. whether the entity centre lies in the structure OBB.

The command runs even when `entityPhysicsCollisionEnabled` is `false`, so a
presumed collision failure can be inspected without changing the config. The
same data is available programmatically through
`EntityPhysicsCollisionManager.inspect(level, entity)` and
`EntityPhysicsCollisionManager.inspectNear(level, centre, radius, max)`.

## Performance

The design bounds every loop:

| Budget | Value | Purpose |
| --- | --- | --- |
| Entities per level per tick | 2048 | Caps a pathological entity count |
| Structures per entity | 8 nearest | Bounds broad-phase work |
| Merged boxes per structure per pass | 256 narrow-phase candidates | Bounds SAT work |
| Resolution passes per structure | 3 | Handles adjacent merged boxes |
| Total correction per tick | 8 m | Safety valve against runaway teleports |

Additional properties:

- Structure poses and AABB bounds are snapshotted once per level per tick, not
  once per entity.
- The local-space query box rejects most boxes before any per-box rotation or
  SAT work.
- Immutable collider box lists are shared; no box data is copied.
- The pass allocates only small temporaries per entity/structure pair. It does
  not load chunks, query blocks or touch Bullet per box.
- `EntityPhysicsCollisionManager.lastStats()` exposes the last tick's level,
  structure, entity and correction counts plus elapsed nanoseconds for
  profiling.

## Limitations and approximations

- **No swept collision.** The pass resolves overlaps after the entity has
  moved. The previous-centre hint prevents most mid-plane crossings on thin
  structures, but an entity that starts a tick already past the far side of a
  structure can still be resolved to that side. Extremely fast entities can
  still tunnel completely between samples.
- **Entity AABB only.** Living entities are treated as axis-aligned boxes, not
  capsules or oriented boxes. This is the same shape vanilla uses for most
  entity collision, but it is approximate for tall mobs and rotated poses.
- **Vanilla collision still runs.** Entities continue to collide with world
  blocks, other entities and pistons. This pass only adds structure collision;
  it does not replace or disable vanilla movement.
- **Client-side prediction.** Structures are not part of the vanilla chunk
  collision data. Singleplayer/dedicated clients cannot predict collisions with
  them, which is why players are corrected via teleport. While a player rests
  on a structure the server may need to send a correction every tick; the
  riding layer may also send its own carry teleport. A client-aware prediction
  layer is out of scope for this task.
- **No structure reaction.** By design, an entity cannot push, rotate or wake a
  structure. Two entities cannot use a structure as a shared movable platform
  with mass transfer.
- **Sequential multi-structure resolution.** When two structures overlap the
  same entity, each is resolved in turn. A pathological arrangement can leave a
  small residual overlap; the next tick corrects it.
- **Margin is a gap, not a skin.** `entityCollisionMargin` is added to the MTV,
  so entities hover by that amount above a structure. The default `0.02` m is
  visually negligible.
- **No block-level contact events.** The pass does not generate footsteps,
  sounds or contact callbacks, and does not set `verticalCollision` flags. It
  only corrects position, velocity and ground state.

## Future work

A symmetric mode could compute an impulse on the structure from the entity's
mass, relative velocity and contact normal, scaled by a configurable coupling
factor. It should remain opt-in and mass-clamped, because a player or item
pushing a multi-tonne structure would otherwise behave unrealistically. That
reaction path is deliberately absent from the current implementation so the
one-way guarantee is easy to reason about and test.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/entity/EntityStructureSolverTest.java`
covers:

- upward resolution when standing on a floor,
- sideways resolution against a wall,
- no movement when the entity is already clear,
- rotated structure poses using world-space normals,
- straddling adjacent merged boxes,
- upward tie-breaking on exact overlap,
- local-query rejection of a distant structure,
- block-scale handling,
- the box budget.
