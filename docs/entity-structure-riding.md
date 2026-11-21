# Entities riding physics structures

This document describes the server-side pass that lets entities stand on, and be
carried by, a Crythst physics structure. It is implemented by:

- `io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingManager`
  (tick loop, public API, allowlists, diagnostics),
- `io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingMath`
  (pure local-point, support-probe and yaw maths),
- `io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureAttachment`
  (read-only attachment snapshot).

It is deliberately separate from the movement-integrated structure collision
in `EntityStructureCollision`, which runs inside vanilla `Entity.move`. That
pass stops entities from entering a structure; this pass keeps entities that
are already on top of one attached to it when the structure translates or
rotates. The old post-tick `EntityPhysicsCollisionManager` is a diagnostic-only
no-op.

## One-way rule

The riding pass only reads a structure:

- `PhysicsStructure.pose()`
- `PhysicsStructure.collider()` and its immutable `VoxelBox` list
- `PhysicsStructure.obb()` for the broad phase and for the allowlist radius

It never calls `applyCentralImpulse`, `applyImpulseAt`, `setPose`, `teleport`,
`setKinematic` or any other mutating method. An entity therefore cannot push,
rotate or wake a structure through this pass. That matches the existing
one-way collision rule; see
[entity-physics-collision.md](entity-physics-collision.md).

## Tick placement and wiring

The pass must run **after** the physics tick. It does not need to run after a
separate collision pass because ordinary structure collision now runs inside
vanilla entity movement; by the time this manager carries an entity, its own
movement for the tick has already been resolved. In the current integration it
is registered by `PhysicsTicker.register()`. An equivalent external wiring is:

```java
PhysicsTicker.register();
EntityStructureRidingManager.register();
```

`EntityStructureRidingManager.register()` subscribes to
`ServerTickEvents.END_SERVER_TICK` itself, plus `ServerLevelEvents.UNLOAD`
(attachments for that dimension are dropped) and
`ServerLifecycleEvents.SERVER_STOPPED` (the attachment map is cleared).
Calling it more than once is harmless.

The effective event order is:

1. Vanilla entity ticks, including `Entity.move` and the structure collision
   inside it.
2. `MinecraftPhysics::tick` advances the physics world at
   `END_SERVER_TICK` and publishes the new poses.
3. `EntityStructureRidingManager` applies the structure's pose delta and
   re-anchors attachments.

Because riding runs last, the entity's own movement and collision result for
the tick are adopted into the new local point instead of being applied twice.

## Automatic support detection

For every `ServerLevel` that already has a `LevelPhysics`, the pass iterates
eligible entities and tests nearby structures.

An entity is considered supported when the centre of its foot box (plus its four
inset corners) is within a small vertical tolerance of a merged collider box
top:

```text
gap = feetLocalY - boxTopLocalY
supported when -tolerance <= gap <= tolerance
```

The default tolerance is `0.08` m. The horizontal test uses the local X/Z bounds
of the merged boxes with a `0.02` m edge slack. A centre hit is always enough;
if the centre is off the surface, at least three of the four foot corners must
agree. The footprint is taken from the vanilla entity bounding box, so this is
an AABB approximation rather than an oriented per-limb shape.

Collider boxes are expressed in unshifted structure-local metres, the same
frame as `PhysicsStructure.pose()` (the body pose already accounts for the
centre of mass). No second centre-of-mass subtraction is applied.

When a support is found, the manager stores:

- the `PhysicsStructure` reference and its body id,
- `localPoint = pose.inverseTransformPoint(entity.position())`,
- `localYaw = entityYaw - structureYaw`,
- the pose at which the local point was measured.

## Carrying

On each later tick the manager computes the world-space anchor delta:

```text
expected = lastPose.transformPoint(localPoint)
target   = currentPose.transformPoint(localPoint)
delta    = target - expected
```

The entity's current position already contains its own movement (walking,
gravity, collision correction). The manager adds `delta` to that position, so
the entity keeps its own movement while also moving with the structure. It then
re-anchors:

```text
localPoint = currentPose.inverseTransformPoint(newPosition)
```

That re-anchor is what allows a mob to walk on a moving platform and what makes
the next collision correction visible. Without it the entity would be frozen at
one spot on the structure.

While attached, the pass:

- sets `onGround` to `true` and resets fall distance (the structure is not a
  vanilla block, so vanilla would otherwise not report ground),
- removes downward velocity when it is negative, so gravity does not build up
  while the manager is already holding the entity on the surface,
- applies the structure's yaw delta to the entity yaw when `rotateYaw` is
  enabled (default `true`),
- for a `ServerPlayer`, adds the platform's per-tick anchor delta to the
  player's velocity instead of writing a position. Vanilla movement then
  carries the player, and the client stays authoritative over its own position.
  Non-player entities use `Entity.setPos(...)`.

No impulse, force, contact teleport or transform is ever applied to the
structure body.

## Explicit binding API

Entities can be bound without relying on the foot probe:

```java
import io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingManager;
import io.mikaple.crythst.physics.structure.PhysicsStructure;
import org.joml.Vector3d;

// localPoint is in structure-local metres, the same space as structure.pose().
boolean ok = EntityStructureRidingManager.bind(entity, structure, new Vector3d(0.0, 0.5, 0.0));

// Or bind at the entity's current position.
EntityStructureRidingManager.bind(entity, structure);

EntityStructureRidingManager.unbind(entity);
boolean bound = EntityStructureRidingManager.isBound(entity);
```

An explicit binding is a **hard lock** for non-player entities: each tick they
are placed at `pose.transformPoint(localPoint)`, and yaw is held relative to the
structure when `rotateYaw` is enabled. For a `ServerPlayer` the anchor delta is
added to velocity instead, preserving client movement authority. Explicit
bindings are useful for seats, contraption anchors, or an entity that is not
detected by the support probe.

An attachment is removed when:

- the structure is closed or no longer part of the level's physics world,
- the entity is removed, dies, becomes a spectator or passenger, or is no longer
  returned by the level,
- the entity drifts farther than `maxAttachmentDistance` (default `4.0` m) from
  its last expected anchor,
- the structure pose delta is larger than `MAX_CARRY_DELTA` (`16.0` m), which is
  treated as a teleport rather than carrying,
- an automatic (non-explicit, non-allowlisted) attachment can no longer find a
  supporting surface after the carry.

Jumping is covered by the support test: after the vanilla jump the feet are
outside the contact tolerance, so the automatic attachment is dropped. Explicit
non-allowlisted bindings also drop when the entity moves upward more than
`0.25` m above the hard-locked anchor with upward velocity. Allowlisted bindings
intentionally stay bound until unbound or out of range, because modded
contraption entities often have their own motion.

## Allowlist (including Create contraption entities)

Automatic support already handles normal items, mobs and players. Some entities
should stay bound even when the foot probe cannot find a surface. The manager
provides two allowlist hooks:

```java
// By registry type:
EntityStructureRidingManager.addAllowedEntityType(EntityType.ITEM);
EntityStructureRidingManager.addAllowedEntityType(EntityType.ARMOR_STAND);

// By class name (superclasses and interfaces are also checked):
EntityStructureRidingManager.addAllowlistedClassName(
        "com.example.mymod.MyContraptionEntity");
```

Default class names are:

- `com.zurrtum.create.content.contraptions.AbstractContraptionEntity`
- `com.simibubi.create.content.contraptions.AbstractContraptionEntity`

They are matched with `Class#getName()` while walking the entity's superclass
and interface hierarchy. **No name is passed to `Class.forName` and no Create
dependency is added**, so a missing optional mod is harmless. A class-name
allowlist entry for a class that is not present simply never matches.

Allowlisted entities are attached when they are supported or when their centre
is within `allowlistAttachRadius` (default `2.0` m) of a structure OBB. Once
attached, an allowlisted entity stays attached while it remains within
`maxAttachmentDistance`; it is not dropped when the support probe loses the
surface. The radius is intentionally generous because contraption entities are
often anchored at their centre rather than their feet.

## Collision interaction and double movement

Ordinary structure collision runs earlier, inside vanilla `Entity.move`, so the
entity's position already contains its own walking, gravity and collision
result. This pass adds only the structure's pose delta and re-anchors. The two
paths therefore do not fight each other. If the movement solver ended up far
from the expected anchor, the `maxAttachmentDistance` check drops the
attachment and the automatic probe can re-attach on a later tick if the entity
lands on the structure again.

The manager does not add an impulse or a reaction force to the structure, and it
does not transfer entity mass or velocity into the rigid body.

## Runtime tuning

There are no dedicated `CrythstConfig` fields yet. The pass uses static defaults
and public setters:

```java
EntityStructureRidingManager.setEnabled(true);
EntityStructureRidingManager.setRotateYaw(true);
EntityStructureRidingManager.setSupportTolerance(0.08);
EntityStructureRidingManager.setMaxAttachmentDistance(4.0);
EntityStructureRidingManager.setAllowlistAttachRadius(2.0);
EntityStructureRidingManager.setEntityFilter(entity -> true);
```

Suggested future config fields (to be wired by the main integration task):

| Field | Default | Meaning |
| --- | --- | --- |
| `entityStructureRidingEnabled` | `true` | master switch |
| `entityStructureRidingSupportTolerance` | `0.08` | vertical contact tolerance |
| `entityStructureRidingMaxDistance` | `4.0` | drop distance from the anchor |
| `entityStructureRidingRotateYaw` | `true` | apply structure yaw delta |
| `entityStructureRidingAllowlistRadius` | `2.0` | allowlist OBB attach radius |

Per-tick diagnostics:

```java
EntityStructureRidingManager.TickStats stats = EntityStructureRidingManager.lastStats();
// levels, structures, entities, attached, carried, autoAttached, detached, nanos
```

## Eligibility

The pass skips:

- removed and dead entities,
- spectators,
- passengers (their vehicle is responsible for them),
- `Display` entities and `EnderDragonPart`,
- entities with `noPhysics`, unless they are explicitly bound or allowlisted,
- entities rejected by `setEntityFilter`.

Explicit bindings and allowlisted entities may use `noPhysics`; this is
deliberate for modded contraption entities.

## Performance

| Budget | Value |
| --- | --- |
| Entities scanned per level per tick | `2048` |
| Structures considered per entity | `8` nearest |
| Merged boxes examined per structure | `256` |
| Pose delta treated as carrying | `16 m` |

Structure poses, collider box lists and broad-phase bounds are snapshotted once
per level per tick. The support probe transforms only the foot samples, not
every box, and bails out on the first box budget. Attachments are stored in one
server-thread `HashMap` keyed by entity UUID.

## Limitations

- **Server-authoritative carry.** Attachments are detected and carried on the
  server. Players inherit the platform delta as velocity and are not
  contact-teleported; non-player entities are moved with `Entity.setPos`.
  Clients still predict their own vanilla movement and do not run this support
  probe themselves.
- **No client-side carry prediction.** A fast-moving or rotating structure can
  make a carried entity look one tick behind on a client. The client receives
  ordinary entity position updates; no custom carry packet or carry
  interpolation is added.
- **Vanilla interpolation.** Carried entities keep vanilla old/current position
  interpolation. With a rotating platform the visual position can lag slightly
  between ticks, especially for fast yaw changes. `xOld`/`yOld`/`zOld` are not
  rewritten.
- **No per-entity render rotation yet.** Position and yaw are carried, but there
  is no entity render-pose hook for arbitrary tilted structures. A structure
  pitched or rolled far from vertical will carry the entity's feet correctly,
  but the entity model is not rendered in the structure's full frame.
- **AABB footprint.** Support uses the vanilla entity bounding box projected
  onto the structure, not a capsule or per-limb shape.
- **Local "up" is structure +Y.** The probe treats the top face of a merged box
  in structure-local space as a surface. This is correct for floors relative to
  the structure but does not inspect arbitrary face normals.
- **Yaw only.** The structure's full rotation is applied to the anchor point,
  but only a yaw delta is applied to the entity's body orientation.
- **No passengers.** A passenger follows its vehicle; this pass skips it.
- **No structure reaction.** Entities cannot push, rotate or wake a structure,
  by design. There is no mass or momentum transfer in either direction.
- **No block contact events.** No footsteps, sounds, `verticalCollision` flags
  or per-block contact callbacks are generated.
- **Automatic binding is intentionally conservative.** An entity standing mostly
  off an edge (only one or two foot corners over the surface) is left to the
  collision pass rather than being carried.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/entity/riding/EntityStructureRidingMathTest.java`
covers the pure maths without a Minecraft server:

- local-point/world-point round trip under translation and rotation,
- top-surface detection for an identity pose,
- small positive and negative vertical gaps,
- rotated structure poses,
- rejection of a tall side and of a distant box,
- the centre/majority-corner rule at an edge,
- the box budget,
- non-1.0 block scales,
- Minecraft yaw convention and angle wrapping,
- carrying a local point through a pose delta.

The manager itself is thin orchestration around these helpers; it has no
headless unit test because it needs a `ServerLevel` and live entities.
