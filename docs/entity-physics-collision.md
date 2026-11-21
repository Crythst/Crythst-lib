# Entity ↔ physics structure collision

This document describes how normal Minecraft entities collide with Crythst
physics structures. Collision is integrated into vanilla entity movement:
there is **no** end-of-tick manager, no post-move correction and no contact
teleport on either side.

The implementation lives in:

- `io.mikaple.crythst.mixin.entity.EntityStructureCollisionMixin` — injects into
  `Entity.move`;
- `io.mikaple.crythst.integration.minecraft.collision.EntityStructureCollision`
  — selects the data source and drives one movement call;
- `...collision.StructureMotionSolver` — the pure substep movement kernel;
- `...collision.StructureCollisionBox` / `StructureCollisionSource` — the
  read-only structure data abstraction.

The pure geometry helper `EntityStructureSolver` is still used for the
minimum-translation-vector (MTV) math. It is no longer applied by a post-tick
manager.

## One-way rule

The pass is intentionally asymmetric:

- Entities (mobs, items, players, ...) are moved out of / stopped by a
  structure.
- Structures are **never** moved, accelerated or otherwise affected by an
  entity.

The kernel only reads a structure's pose and its immutable `VoxelBox` list. It
never calls `applyCentralImpulse`, `applyImpulseAt`, `setPose`, `teleport` or
any other mutating method on a physics body. Walking into a floating structure
therefore cannot push it around.

## Mixin target and injection

Vanilla `Entity.move(MoverType, Vec3)` asks `collide(motion)` exactly once how
much of the requested motion is allowed by world blocks, then moves by that
result and derives step handling, fall damage and the
`onGround`/`horizontalCollision`/`verticalCollision`/`verticalCollisionBelow`
flags from it.

`EntityStructureCollisionMixin` redirects that single call:

```java
@Redirect(
        method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(value = "INVOKE",
                target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
private Vec3 crythst$resolveStructureMotion(Entity instance, Vec3 motion) { ... }
```

The private vanilla method is reached through the `EntityCollideInvoker`
interface mixin, so the replacement can run vanilla collision for the
structure-clear motion without recursing into the redirect.

Because the redirect participates in the same movement call:

- the entity is moved once, by vanilla `move`, to the final position;
- sliding is produced by vanilla's axis clipping plus the contact-normal
  velocity filter, not by a sign-flipped MTV applied after the fact;
- step-up, ramps, fall distance and the collision flags keep their vanilla
  meaning;
- no `ServerPlayer.connection.teleport` contact packet is sent, so the client
  and server copies do not fight for authority.

## Algorithm

`EntityStructureCollision.resolveMovement` performs one call:

1. Select a read-only structure source for the entity's dimension.
2. Build the entity AABB and requested motion.
3. Run `StructureMotionSolver.solve`:
   - broad phase: structures whose world AABB intersects the swept entity box;
   - substeps: 8 for any player (so `LocalPlayer` and `ServerPlayer` run the
     same loop), otherwise `clamp(|motion| / (0.25 / 16), 1, 10)`;
   - per substep, advance the candidate AABB by `motion / substeps` and resolve
     penetrations against each candidate structure;
   - up to 3 MTV passes per structure, so one merged box can push the entity
     into a neighbouring box;
   - at most 8 nearest structures per entity and 256 merged boxes per structure
     per pass.
4. Pass the resulting motion to vanilla `collide`.
5. Resolve a shallow residual overlap if vanilla clipping pushed the entity
   into a structure (a world wall next to a structure corner).
6. Remove the velocity component pointing into every contact normal. All
   tangential components stay; that is what makes a wall slide and a floor jump
   work.
7. Return the final motion. Vanilla `move` performs the actual movement and
   sets the flags.

### MTV and support

`EntityStructureSolver` uses all 15 SAT axes for rejection but only the six
face axes for the MTV. Cross axes are unstable for de-penetration at edges.

The movement kernel uses a much tighter support budget than the old post-tick
pass. A support (world-up) exit wins while the entity is grounded or
descending, but only for about one substep of fall. The old 0.6 m budget would
have let a zero-step-height mob climb a 0.5 m wall. A true step-up is handled
explicitly:

- on a horizontal contact, if vanilla says the entity is on the ground and its
  `maxUpStep()` is non-zero, the kernel tests the AABB lifted by that step
  height;
- if the lifted box is clear, the lift is added to the allowed motion and the
  substep continues from there.

The lift becomes part of the motion vanilla applies, so it is not a teleport.

### Velocity

For every contact normal `n`, the kernel removes the negative component

```text
v' = v - n * min(0, v · n)
```

No impulse is invented and no sign is flipped. Repeated floor+wall contacts in
the same tick use every normal, so a corner cannot accumulate downward velocity
behind a wall correction.

## Data sources

`StructureCollisionSources.forEntity` chooses one implementation:

| Side | Source | Pose used |
| --- | --- | --- |
| Dedicated server | `LevelPhysics.structures()` | authoritative `structure.pose()` |
| Integrated server | `LevelPhysics.structures()` | authoritative `structure.pose()` |
| Client, singleplayer | `ClientPhysicsView.structures()` | newest published pose via `renderStateAt(now + timelineDelay)` |
| Client, multiplayer | `ClientStructureCache.snapshots()` | last synced snapshot pose + merged boxes rebuilt from solid cells |

The client classes are loaded through a holder that is only reached after
`FabricLoader.getEnvironmentType() == CLIENT`, so a dedicated server never
loads them.

The server source caches one snapshot per level and game tick. Entity movement
runs before the physics tick for that same tick, so every entity sees the same
pose, and a split/reindex or structure creation is visible on the next tick.

## Client/server parity

Both `LocalPlayer` and `ServerPlayer` run the same kernel against the same
poses. The server no longer sends contact teleports and the old client
end-of-tick predictor no longer runs, so there is only one authority per side:

- the server simulates its own copy and accepts the client's movement packets
  through the normal vanilla validation;
- the client simulates its own copy and never receives a contact correction
  packet;
- a network pose may be up to one tick behind the server. Corrections are
  capped by `StructureCollisionSource.maxCorrection()` (4 m server, 1 m client)
  so a stale pose cannot fling an entity.

## Removed old authority

The following paths are no longer active:

- `EntityPhysicsCollisionManager.register()` — no-op;
  `onEndServerTick` — no-op. The class remains only for the read-only
  `/crythst entitydebug` inspector.
- `EntityPhysicsCollisionManager`'s per-tick `ServerPlayer.connection.teleport`
  correction and prediction deferral — deleted.
- `ClientPlayerStructureCollision.register()` — no-op; the END_CLIENT_TICK
  correction is gone.
- `EntityStructureRidingManager` no longer teleports players for a moving
  platform. It adds the platform's per-tick anchor delta to the player's
  velocity instead (inherited velocity only); non-player carry still uses
  `Entity.setPos`.

The entrypoints still contain the historical calls; remove them when the
shared files are next edited:

```java
// PhysicsTicker.register()
EntityPhysicsCollisionManager.register();                     // now a no-op
EntityPhysicsCollisionManager.setEntityFilter(...);           // still needed

// CrythstClient.onInitializeClient()
io.mikaple.crythst.integration.minecraft.entity.client
        .ClientPlayerStructureCollision.register();           // now a no-op
```

`setEntityFilter` is still required by `PhysicsTicker`: it excludes Create
contraption entities from the new movement path as well.

## Edge cases

- **Rotated floor**: the MTV follows the structure face, and a contact whose
  normal has a world-up component above `0.55` is treated as support.
- **Walking along a wall**: only the into-wall velocity component is removed;
  tangential motion is untouched.
- **Jumping into a ceiling**: the downward-facing normal removes upward
  velocity. `verticalCollisionBelow` is not set, so the entity does not become
  grounded.
- **Landing on an edge/corner**: substeps keep the overlap small, and the
  support exit is preferred for one substep of fall, so a landing resolves up
  instead of sideways.
- **Thin walls / high speed**: 8-10 substeps and the previous-centre hint stop
  normal movement from crossing a one-block wall. Extremely fast motion (well
  over 4 blocks/tick) can still tunnel through a sub-block-thick structure.
- **Entity already inside a structure**: the substep solver applies the full
  MTV on the first tick (bounded by the source cap) and then only maintains
  contact. There is no periodic teleport.
- **Dimension change / split / reindex**: the server source is keyed by
  `LevelPhysics` and rebuilt per tick; the client source checks the active
  dimension and the active sync id, and clears network geometry when either
  changes.

## Diagnostics

`StructureCollisionTrace` stores the last movement collision (side, entity
type, body ids, penetration, normal, motion before/after, substep count,
contact count, support/step flags). Set `-Dcrythst.debug.collision=true` to log
it at most once per second.

A status overlay or command can read it without changing log settings:

```java
final StructureCollisionTrace.Sample sample = StructureCollisionTrace.last();
if (sample != null) {
    player.displayClientMessage(Component.literal(sample.describe()), true);
}
```

The old `EntityPhysicsCollisionManager.inspect(level, entity)` inspector is
unchanged and still reports the structure pose, broad-phase bounds, local query
box and the MTV the resolver would choose.

## Tests

- `src/test/java/io/mikaple/crythst/integration/minecraft/collision/StructureMotionSolverTest.java`
  covers wall sliding, floor support, ceilings, rotated floors, structure
  step-up, high-speed thin-wall tunnelling, velocity filtering, empty sources
  and substep bounds.
- The existing `EntityStructureSolver*` tests cover the SAT/MTV math used by
  the kernel.
- `EntityStructureSolverPhysicsTest` continues to assert that resolving an
  entity never mutates a structure body.

## Limitations

- **Entity AABB only.** Living entities are treated as axis-aligned boxes, not
  capsules or oriented boxes. This matches vanilla's own shape approximation.
- **No structure reaction.** By design, an entity cannot push, rotate or wake a
  structure.
- **No edge back-off on structures.** Vanilla's sneak-edge protection only
  queries world blocks, so a player can walk off a structure edge.
- **No structure block sounds/events.** Contacts do not generate footstep or
  landing sounds, and structure blocks are not part of the vanilla chunk
  collision data.
- **Riding carry is inherited velocity only.** A moving platform adds its
  per-tick delta to a player's velocity; it does not hard-lock the player to the
  anchor, so very fast platform motion can lag by a tick.
- **Residual corners.** If a world block and a structure meet in a tight
  corner, vanilla clipping can push the entity into the structure. One shallow
  residual pass (capped at 1 m) resolves the common case; pathological wedges
  settle over the next tick.
- **Collision margin is a diagnostic value.** The movement kernel separates to
  zero overlap instead of adding `entityCollisionMargin` on every substep,
  which would accumulate into levitation. The config value still drives the
  `/crythst entitydebug` inspector.
