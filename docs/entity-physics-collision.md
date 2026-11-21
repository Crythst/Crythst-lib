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
   - Prefer a **world-up support exit** while the entity is resting or
     descending. If the upward overlap is shallow enough to be a floor or a
     step (default `0.6` m plus the downward speed), it wins even when a
     sideways overlap is a few millimetres smaller. This stops a landing on the
     edge of a platform from being shoved off sideways.
   - Bias remaining ties toward the upward escape. This keeps entities resting
     on a floor instead of being squeezed sideways by floating-point ties.
   - Choose the sign of each face axis using the entity AABB centre from the
     previous tick **only while the current centre is still inside that box's
     slab**. Once the centre is clearly outside, the current side is
     unambiguous and a stale/interpolated hint cannot push the entity back into
     the surface.
5. Add the configured `entityCollisionMargin` to the MTV and translate the
   entity. Up to three passes are run per structure so one merged box cannot
   leave the entity inside a neighbouring box.
6. Remove the velocity component pointing into **every** contact normal (one per
   pass), not just the last one. All tangential components are left untouched,
   so walking, sliding along a wall and jumping are preserved even when a floor
   and a wall are resolved in the same tick.

Penetrations at or below the contact slop (`2e-3` m for gameplay, `1e-4` m for
the legacy overloads) are **resting contact** and produce no correction.
Without this, the margin would generate a small correction on every tick the
entity merely touches a surface. The slop is applied to the chosen exit, so a
real landing is still resolved even if another face of the same merged box only
grazes the surface.

### Standing on top vs. embedded in a side

The shallowest penetration axis usually distinguishes the two cases, and the
support preference covers the ambiguous edge cases:

- Feet slightly below the top of a floor: vertical overlap is small, so the MTV
  points up. Downward velocity is removed, `setOnGround(true)` is set and fall
  distance is reset. This helps mob AI and fall damage even though the
  structure is not a vanilla block.
- Landing with most of the body past the edge: the horizontal overlap can be
  smaller than the vertical one. The support preference still picks up while
  the vertical overlap is within the step/landing budget, so the player stands
  on the edge instead of being pushed into the air.
- Body overlapping a tall wall: the vertical overlap spans the whole body and
  exceeds the support budget, so the MTV points out of the wall and the inward
  velocity component is removed while the tangential component is kept.

A tie-break prefers an upward normal when two face axes have effectively equal
depth. The previous-tick hint only changes the sign, and only while the centre
is inside the box.

### Authority model for players

Sending a `ClientboundPlayerPositionPacket` every contact tick is actively
harmful: the client applies the packet with `Vec3.ZERO` velocity, so a player
touching a wall or standing on a floor loses all momentum and feels glued.
The passes therefore elect a single visible authority:

- **The local client predicts and owns the player position** when the client
  runs the Crythst prediction layer. The server resolves the same geometry for
  diagnostics and ground state, but does **not** teleport for corrections below
  `0.5` m. The client's next movement packet carries the corrected position.
- **The server teleports** when the client has no prediction channel (a vanilla
  client) or when the correction is larger than `0.5` m (for example, the
  client skipped it because the target would enter a vanilla block). Even then
  the teleport carries the tangential velocity instead of zeroing it.
- **Singleplayer always counts as prediction-capable**, so both copies never
  teleport-loop against each other.
- **The riding/carry pass** only teleports when the structure-local anchor
  actually moved by more than `1e-6` m or the yaw changed. A static structure
  therefore produces no carry packet at all, and a moving one keeps the
  entity's velocity in the packet.

The client uses the **newest published pose** for collision, while rendering
uses the one-tick-delayed `PoseTimeline` sample. Using the delayed render pose
for collision made a moving structure push against a surface the server had
already left, which showed up as alternating corrections. The newest pose is
obtained without touching Bullet from the client thread by sampling
`renderStateAt(now + renderTimelineDelayNanos())`.

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

Server players are normally server-authoritative only through teleport packets,
while the client predicts vanilla movement. The collision and riding passes
follow the authority model above: they prefer the client's prediction and only
teleport when the client cannot have applied the correction. Other entities
continue to use `Entity.setPos(...)` and never cause a packet.


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

### Correction trace

`StructureCollisionDebug` keeps the last correction attempt from both the
client and server paths: penetration, chosen normal, offset, velocity before and
after, whether it was applied, and why it was skipped or deferred. It also
counts corrections, deferrals and rapid normal reversals (a flip counter, so
oscillation is measurable instead of subjective).

Set `-Dcrythst.debug.collision=true` to log the trace at most once per second.
The latest trace is always available in memory, so an overlay or a debug command
can show it without changing log settings:

```java
final StructureCollisionDebug.Sample sample = StructureCollisionDebug.last();
if (sample != null) {
    player.displayClientMessage(Component.literal(sample.describe()), true);
}
```

Example line:

```text
CLIENT_PREDICTION applied body=- pen=0.0784 n=(0.000,1.000,0.000)
off=(0.000,0.098,0.000) v=(0.150,-0.078,0.000)->(0.150,0.000,0.000) (support)
```

`SERVER_PLAYER_DEFERRED` means the server detected the contact but left the
visible correction to the client; `SERVER_PLAYER_TELEPORT` means an
authoritative fallback was sent.

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
- **Client-side prediction is still an approximation.** Structures are not part
  of the vanilla chunk collision data, so the client resolves them after vanilla
  movement. The predictor uses the newest published pose, the same solver and
  the same margin as the server, but it cannot feed the structures into
  vanilla's swept movement. Very fast motion can therefore tunnel for one tick
  before the correction lands. A vanilla client has no predictor at all; the
  server falls back to teleporting it (with tangential velocity preserved).
- **Mismatched client config.** A remote client with
  `entityPhysicsCollisionEnabled=false` will not predict even though the server
  defers small corrections to it. Keep the feature enabled on both sides when
  playing multiplayer. Gross penetrations are still teleported by the server.
- **Carry teleports are still required for moving platforms.** A player riding
  a moving structure is carried by a teleport because vanilla movement has no
  platform velocity. The packet now preserves the player's own velocity and is
  skipped entirely when the anchor did not move, so this is much less sticky,
  but continuous fast motion still sends one packet per tick.
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

`EntityStructureSolverContactTest` covers the gameplay policy:

- edge landings resolve up instead of sideways (and the legacy path is asserted
  to shove sideways, so the regression is explicit),
- tall walls still resolve horizontally,
- sub-slop resting contact is ignored,
- support is only used for grounded/descending callers,
- fast landings on a corner still resolve up,
- support wins over a shallower horizontal from another merged box,
- the previous-centre hint cannot flip the sign once the centre is outside the
  box, but still prevents tunnelling while it is inside.

`ClientStructureCollisionResolverTest` covers the bounded client correction and
the `StructureCollisionContactSimulationTest` tick loop covers:

- standing on a floor without horizontal push or height drift,
- walking while tangential velocity is preserved,
- walking along a wall with only the into-wall component removed and a stable
  normal,
- jumping into a ceiling,
- landing on a corner,
- diagonal wall approach,
- low-speed resting contact,
- a single grazing contact not starting a shove loop.

`StructureCollisionDebugTest` covers the trace counters and flip detection, and
`EntityStructureRidingMathTest` covers the no-op carry decision (a static
structure must not teleport). `EntityStructureSolverPhysicsTest` continues to
assert that resolving entities never mutates the structure body.
