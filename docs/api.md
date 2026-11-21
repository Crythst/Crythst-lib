# Crythst Public API (v1)

Package: `io.mikaple.crythst.api`

This is the supported surface for third-party mods. It wraps the physics
engine, structure collider and Minecraft integration without requiring callers
to import mixins or client render classes. Internals may change between
releases; the types below are the compatibility contract.

The API is server-authoritative. Physics bodies, structures, impulses and
velocity queries must be used on the server thread. Client code can read
render-safe poses but must not call native body methods.

## Quick start

```java
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

PhysicsPipeline physics = CrythstApi.physics(serverLevel); // may create the world
if (physics != null) {
    for (RigidBodyHandle body : physics.structures()) {
        // SI units: N·s, m/s, rad/s, kg, metres.
        body.applyLinearImpulse(new Vector3d(0.0, 500.0, 0.0));
    }
}
```

## 1. Physics handles and pipeline

### `physics/RigidBodyHandle`

A lightweight view over a `PhysicsStructure` body or a loose body.

| Method | Units / meaning |
| --- | --- |
| `long id()` | body id, unique in its world |
| `boolean isValid()` | body still exists / structure not closed |
| `PhysicsStructure structure()` | wrapped structure, or `null` for a loose body |
| `applyImpulseAtPoint(Vector3dc point, Vector3dc impulse)` | point in world metres, impulse in N·s |
| `applyLinearImpulse(Vector3dc impulse)` | centre-of-mass impulse, N·s |
| `applyAngularImpulse(Vector3dc impulse)` | angular impulse, N·m·s |
| `applyTorqueImpulse(Vector3dc torque)` | alias of angular impulse |
| `applyLinearAndAngularImpulse(Vector3dc linear, Vector3dc angular)` | one call, N·s + N·m·s |
| `getLinearVelocity(Vector3d dest)` | world linear velocity, m/s |
| `getAngularVelocity(Vector3d dest)` | world angular velocity, rad/s |
| `getVelocityAtPoint(Vector3dc point, Vector3d dest)` | `v + ω × r`, m/s |
| `addLinearAndAngularVelocity(Vector3dc linear, Vector3dc angular)` | additive, m/s + rad/s |
| `teleport(Vector3dc position, Quaterniondc orientation)` | structure **origin**, not centre of mass |
| `getPosition()` / `getPosition(Vector3d)` | structure/body origin, world metres |
| `getOrientation()` / `getOrientation(Quaterniond)` | world rotation |
| `getMass()` | kg |
| `getCenterOfMass()` | world-space centre of mass, metres |
| `massData()` | `MassData` in structure/body local frame |

`MassData` exposes `mass()`, `inverseMass()`, `inertia()`,
`inverseInertia()` and `centerOfMass()` as defensive copies. Structure inertia
is scaled by `massScale`; loose bodies report zero inertia because the generic
shape interface has no tensor.

### `physics/PhysicsPipeline`

Server-side adapter around one level's `LevelPhysics`.

```java
PhysicsPipeline pipeline = CrythstApi.physics(level);
RigidBodyHandle body = pipeline.body(id);
pipeline.applyImpulseAtPoint(id, point, new Vector3d(0, 800, 0));
pipeline.applyLinearAndAngularImpulse(id, linear, angular);
pipeline.teleport(id, position, orientation);
pipeline.getLinearVelocity(id, dest);
pipeline.getAngularVelocity(id, dest);
pipeline.addLinearAndAngularVelocity(id, linear, angular);
```

`body(id)` and `containsBody(id)` search structures first, then loose bodies.
`structures()` returns an immutable snapshot. Missing bodies make the mutating
methods return `false`; velocity queries write zero.

The pipeline deliberately does **not** expose stepping. Crythst's fixed-step
clock drives simulation from `LevelPhysics.tick`; manual stepping would break
terrain loading, block logic and render interpolation.

## 2. Block hooks

A block can either implement an interface or be registered in
`StructureBlockHooks`. The registry is thread-safe (`ConcurrentHashMap` plus an
immutable copy-on-write block-entity list).

### Hook interfaces

| Type | Method |
| --- | --- |
| `BlockStructureCollisionShape` | `VoxelShape structureCollisionShape(BlockGetter level, BlockPos pos, BlockState state)` |
| `BlockStructureCustomCenterOfMass` | `Vector3dc structureCenterOfMass(BlockGetter level, BlockPos pos, BlockState state)` |
| `BlockStructureDynamicCollider` | `List<StructureColliderBox> structureCollisionBoxes(BlockGetter level, BlockPos pos, BlockState state)` |
| `BlockStructureAssemblyListener` | `beforeStructureAssembly(StructureAssemblyContext)` / `afterStructureAssembly(context, structure)` |
| `BlockStructureBlockChangeListener` | `onStructureBlockChanged(StructureBlockChange)` |
| `BlockStructurePhysicsProperties` | `BlockPhysicsProfile structurePhysics(BlockGetter level, BlockPos pos, BlockState state)` |

`BlockGetter` and `BlockPos` may be passed as `null` / structure-local
coordinates when the volume has no live level (for example a restored
structure). Implementations must tolerate that.

### Registration and precedence

```java
StructureBlockHooks.registerCollisionShape(Blocks.OAK_SLAB, (level, pos, state) -> ...);
StructureBlockHooks.registerCenterOfMass(Blocks.IRON_BLOCK,
        (level, pos, state) -> new Vector3d(0.5, 0.25, 0.5));
StructureBlockHooks.registerDynamicCollider(MY_BLOCK, (level, pos, state) ->
        List.of(StructureColliderBox.centered(1.0, 0.5, 1.0)));
StructureBlockHooks.registerPhysicsProperties(MY_BLOCK, (level, pos, state) ->
        new BlockPhysicsProfile(500.0, 0.7, 0.0, 0.0, 0.0, 1.0));

// Bundle several hooks at once.
StructureBlockHooks.register(MY_BLOCK, BlockStructureHooks.builder()
        .collisionShape(...)
        .blockChangeListener(...)
        .build());

// Block-entity-type registration; resolved with BlockEntityType.isValid(state).
StructureBlockHooks.registerCenterOfMass(BlockEntityTypes.CHEST, ...);
```

Lookup precedence (highest first):

1. explicit `Block` registration;
2. explicit `BlockEntityType` registration whose `isValid(state)` matches, in
   registration order;
3. interfaces implemented by the block class;
4. vanilla behaviour (full voxel cube, data-pack mass, geometric centre).

Within collision geometry, a dynamic collider wins over a VoxelShape hook.
`BlockStructurePhysicsProperties` is applied **after** the data-driven
`BlockPhysicsRegistry`, so it composes with
`data/crythst/block_physics.json` instead of replacing it. Return `null` to keep
the data-pack result.

### What is actually wired

| Hook | Wired into | Effect |
| --- | --- | --- |
| collision shape / dynamic collider | `VoxelColliderBuilder` through `VoxelColliderHooks` | fractional parts become Bullet compound-shape children and contribute to mass/inertia |
| custom centre of mass | `VoxelColliderBuilder` | shifts the structure's weighted centre and inertia; block still collides as a full cube unless a shape hook is present |
| physics properties | `VoxelColliderBuilder` cell mass/material | overrides structure mass and surface material after the data pack |
| assembly listener | `LevelPhysics.createStructure` | before body construction and after insertion, once per structure when the block is present |
| block-change listener | `PhysicsStructure.reindex` | diffed against a lazily captured state snapshot; only volumes that expose block states |

Known limitation: custom fractional parts are part of the Bullet shape and mass
but are **not** added to `VoxelColliderData.boxes()`. Legacy consumers that
only walk `boxes()` (vanilla-entity structure collision, some render fallbacks,
buoyancy) still see the block's coarse voxel cell. Custom-centre-of-mass blocks
keep their cube in `boxes()`, so only collision-shape overrides have this
caveat.

## 3. Entity utilities

`api/entity/EntityStructureUtil`:

```java
PhysicsStructure structure = EntityStructureUtil.getContaining(level, entity);
PhysicsStructure atPoint = EntityStructureUtil.getContaining(level, vec3);

Vector3d velocity = EntityStructureUtil.getVelocity(level, worldPoint, new Vector3d());

EntityStructureUtil.pushEntityLocal(structure, entity);       // stores local frame
// ... structure moves ...
EntityStructureUtil.popEntityLocal(structure, entity);        // applies current pose + velocity
```

- `getContaining` requires a `ServerLevel`; it returns `null` on a client level.
  It checks the structure OBB **and** the solid voxel, so hollow corners do not
  count as inside.
- `getVelocity` returns the material point velocity `v + ω × r` in m/s.
- `pushEntityLocal` stores position, look direction and velocity in the
  structure frame. Nothing moves at push time.
- `popEntityLocal` transforms the frame through the current pose, adds the
  structure's velocity, converts m/s to Minecraft's m/tick (divides by 20), and
  snaps position/rotation. Frames are a weak per-entity stack; use
  `clearLocalFrames(entity)` when external code teleports/removes the entity.
- The optional `anchor` is an unrotated metre offset added before the inverse
  transform and subtracted after the forward transform (useful for falling
  blocks whose origin is not their centre).

## 4. Events

`api/CrythstEvents` is a small thread-safe bus. It does not depend on Fabric's
event API.

```java
AutoCloseable handle = CrythstEvents.registerPrePhysicsTick(event -> {
    double dt = event.timeStepSeconds();   // seconds
    long index = event.tickIndex();        // per-world fixed substep
    PhysicsPipeline physics = event.physics();
    // apply forces at the substep rate
});

CrythstEvents.registerPostPhysicsTick(event -> { ... });
CrythstEvents.registerStructureContainerReady(event -> { ... });
```

| Event | Firing point | Frequency |
| --- | --- | --- |
| `PrePhysicsTick` / `PhysicsTickEvent` | `PhysicsWorld` `StepListener.beforeStep`, installed by `LevelPhysics` | once per **fixed substep**, before integration |
| `PostPhysicsTick` / `PhysicsTickEvent` | same listener's `afterStep` | once per fixed substep, after integration |
| `StructureContainerReady` | `MinecraftPhysics.get` after a level's `LevelPhysics` is created | once per level/world |

`tickIndex` starts at zero for each level world and increases after every
post-step callback. `timeStepSeconds` is the actual fixed step passed to the
backend (typically `1/config` seconds). Events fire per **fixed substep**, not
once per game tick, so several callbacks can run inside one server tick.

Listeners run in registration order; failures are logged and isolated.

## 5. Global facade

| Method | Result |
| --- | --- |
| `CrythstApi.structures(Level)` | immutable list of live `PhysicsStructure`, empty on the client |
| `CrythstApi.structuresIn(Level, Aabb)` | structures overlapping a world AABB |
| `CrythstApi.structuresIn(Level, net.minecraft.world.phys.AABB)` | Minecraft-vector overload |
| `CrythstApi.structureAt(Level, Vec3)` / `structureContaining` | structure whose solid volume contains the point |
| `CrythstApi.structureAt(Level, Vector3dc)` | JOML overload |
| `CrythstApi.physics(ServerLevel)` | `PhysicsPipeline`, creating the level world if needed; `null` if unavailable |
| `CrythstApi.body(Level, long)` | `RigidBodyHandle` from an existing world, without creating one |

The original engine methods (`engine()`, `createWorld(...)`,
`backendDescription()`) remain available for callers that need the raw physics
engine.

## 6. v1 coverage and gaps

Covered in v1:

- rigid-body impulse/velocity/teleport handle, including centre-of-mass and mass
  tensor reads;
- a per-level physics pipeline with body-id operations and no raw stepping;
- block collision shape, custom centre of mass, dynamic collider, assembly
  listener, block-change listener and post-data-pack physics properties;
- entity containment, local push/pop projection and point velocity including
  angular motion;
- per-substep pre/post physics events and a level-container-ready event;
- server and client-safe structure lookup facade (client lookups are empty).

Not covered in v1 (candidates for v2):

- `dev.ryanhcode.sable.*` compatibility shims (intentionally not provided);
- constraints, force groups, ropes, box/rope physics objects and generic
  pipeline extension points;
- per-block collision callbacks with contact context and collision filtering;
- schematic serialization context, client sub-level containers, tracking
  plugins and loading tickets;
- propeller/actor/lift-provider block APIs and command helper utilities;
- client-side authoritative structure containers (Crythst is server-authoritative
  and synchronises read-only snapshots);
- custom fractional collision parts in the legacy entity-collision and buoyancy
  box lists (Bullet and mass properties are correct);
- block-entity-instance hooks (registrations are keyed by type and receive only
  the block state; no `BlockEntity` object is passed);
- inertia tensors for loose bodies in `MassData` (zero by design in v1).

## 7. Threading and nullability summary

- Registry writes: any thread.
- Hook invocation, event dispatch, body operations: server thread.
- `PhysicsStructure.pose()` is safe to read for rendering; do not call
  `body()`/`RigidBodyHandle` methods from the render thread.
- Public lookup methods marked nullable return `null` when the level is a
  client level, has no physics world, or the requested body/structure is gone.
- `structures(...)`, `structuresIn(...)` and `structures()` never return `null`.
