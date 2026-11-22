# Crythst Public API (v2)

Package: `io.mikaple.crythst.api`

This is the supported native surface for third-party mods. It wraps the physics
engine, structure containers, assembly, interaction hooks and client render
views without requiring callers to import mixins. Internals may change between
releases; the types below are the compatibility contract.

The API is server-authoritative. Physics bodies, structures, assembly,
impulses and velocity queries must be used on the server thread. Client code can
read the client-only render views and snapshots but must not call native body
methods. The Create Aeronautics gap analysis lives in
[`API_GAP_ANALYSIS.md`](../API_GAP_ANALYSIS.md).

## Quick start

```java
import io.mikaple.crythst.api.CrythstApi;
import io.mikaple.crythst.api.physics.PhysicsPipeline;
import io.mikaple.crythst.api.physics.RigidBodyHandle;
import io.mikaple.crythst.api.sublevel.ServerStructureContainer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;

PhysicsPipeline physics = CrythstApi.physics(serverLevel); // may create the world
ServerStructureContainer structures = CrythstApi.container(serverLevel);
if (physics != null && structures != null) {
    for (RigidBodyHandle body : physics.structures()) {
        // SI units: N·s, m/s, rad/s, kg, metres.
        body.applyLinearImpulse(new Vector3d(0.0, 500.0, 0.0));
    }
}
```

---

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

### Constraints (`physics/constraint`)

Constraints are created and removed through the pipeline or directly through a
structure. Frames, anchors and axes are always **structure-local metres** for
the structure they belong to; for a world-anchored joint, side B is world-space
and its values are used directly. `ConstraintFrames` exposes the same
structure↔world↔body conversions for custom integrations.

```java
PhysicsPipeline pipeline = CrythstApi.physics(level);
StructureConstraintHandle weld = pipeline.addConstraint(a, b,
        FixedConstraintConfiguration.between(frameA, frameB));
StructureConstraintHandle hinge = pipeline.addConstraint(a, b,
        RotaryConstraintConfiguration.between(anchorA, axisA, anchorB, axisB)
                .withLimits(-Math.PI / 2.0, Math.PI / 2.0)
                .withMotor(1.5, 200.0));
StructureConstraintHandle anchor = pipeline.addConstraint(a,
        FreeConstraintConfiguration.toWorld(new Vector3d(100.0, 70.0, 0.0)));

// Full 6-DOF: per-axis limits, motors and springs.
StructureConstraintHandle sixDof = pipeline.addConstraint(a, b,
        SixDofConstraintConfiguration.between(frameA, frameB,
                DofAxisConfiguration.limited(-1.0, 2.0).withMotor(0.75, 120.0),
                DofAxisConfiguration.locked(),
                DofAxisConfiguration.free(),
                DofAxisConfiguration.limited(-0.5, 0.5),
                DofAxisConfiguration.free(),
                DofAxisConfiguration.free()));

pipeline.removeConstraint(weld);
pipeline.removeConstraints(a); // remove every joint involving a
// Equivalent structure-side convenience methods:
StructureConstraintHandle h = a.addConstraint(b, config);
StructureConstraintHandle w = a.addConstraintToWorld(config);
List<StructureConstraintHandle> mine = a.constraints();
int removed = a.removeAllConstraints();
```

| Configuration | Backend joint | Degrees of freedom |
| --- | --- | --- |
| `FixedConstraintConfiguration` | 6-DOF with all limits zero | none (weld) |
| `FreeConstraintConfiguration` | point-to-point | 3 rotational |
| `RotaryConstraintConfiguration` | hinge | 1 rotational + optional motor |
| `GenericConstraintConfiguration` | slider | 1 translational (local X) + angular limit + optional linear motor |
| `SixDofConstraintConfiguration` | `btGeneric6DofSpring2Constraint` (`New6Dof`) | all 6, independently limited/free/locked, with per-axis motors and springs |

`StructureConstraintHandle` exposes `id()`, `type()`, `enabled()`,
`setEnabled`, `isValid()`, `remove()`, `involves`, `structureA/B`,
`bodyIdA/B`, `configuration()`, local/world frames and anchors
(`localFrameA/B`, `worldFrameA/B`, `localAnchorA/B`, `worldAnchorA/B`,
`axisQuaternionA/B`, `localAxisA/B`, `worldAxisA/B`) and total solver impulse
feedback
(`appliedImpulse`, `setFeedbackEnabled`). Hinge motors use
`supportsMotor`, `setMotorEnabled`, `motorTargetVelocity`, `maxMotorImpulse`.
6-DOF handles additionally expose `supportsDofControl`, `dofConfiguration`,
`setLimits`, `setAxisFree`, `setDofMotor`, `setDofMotorEnabled`,
`setDofMotorTargetVelocity`, `setDofMaxMotorForce`, `setDofSpring`,
`setDofSpringEnabled`, `setDofStiffness` and `setDofDamping`.
`constraints()` on a pipeline returns every joint in the world; on a structure
it returns only joints involving that body.

**6-DOF semantics.** Axis order is X/Y/Z translation then X/Y/Z rotation.
Linear limits/velocities/forces use metres, m/s and newtons; angular ones use
radians, rad/s and newton-metres. `DofAxisConfiguration.disabled()` means the
axis is free (unconstrained); `locked()` pins it at zero and
`limited(lo, hi)` constrains it to a range. Bullet's spring2 joint is used, so
spring stiffness/damping are supported per axis. Bullet exposes only a total
joint impulse, not per-axis impulses; `appliedImpulse()` returns that scalar
(zero unless feedback is available/enabled).

**Persistence.** Constraints created through the API are captured in a
per-dimension `ConstraintSavedData` (`crythst/constraints`). Each record stores
a stable constraint UUID, the stable structure UUIDs from
`StructureSavedData`, the enabled flag and the full structure-local
configuration. On load, structures are restored first, then joints are
recreated; records whose structures no longer exist are dropped. Raw backend
joints without an API configuration are not persisted. Structure removal and
body removal also remove the involved joints, so no live handle points at a
dead body.

**Limitations.** Bullet reports only the total applied joint impulse, not
per-axis components. The rotation order is fixed to XYZ. Re-indexing blocks
without closing the body (`PhysicsStructure.reindex`) keeps existing joints by
body id; if the centre of mass moves, body-local anchors are not automatically
rewritten, so remove and recreate the joint when exact re-anchoring matters.

### Loose boxes (`physics/box`)

```java
StructureBoxObject crate = pipeline.createBox(
        StructureBoxDef.dynamic(new Vector3d(0.5, 0.5, 0.5),
                new Vector3d(10.0, 65.0, 10.0))
                .withMass(80.0)
                .withDamping(0.05, 0.1));
crate.applyImpulse(new Vector3d(0.0, 800.0, 0.0));
StructureBoxObject found = pipeline.box(bodyId);
crate.remove();
```

A `StructureBoxDef` describes half extents, pose, body type, optional mass,
material, filter, damping and CCD. Boxes are ordinary loose rigid bodies: no
voxel grid, no auxiliary block level, and the body origin is the box centre.
`pipeline.physicsWorld()` is the low-level escape hatch used by this helper;
do not call `step`/`advance` through it.

### Ropes (`physics/rope`)

```java
StructureRope rope = StructureRope.between(pipeline,
        shipA, new Vector3d(0.0, 0.5, 0.0),
        shipB, new Vector3d(0.0, 0.5, 0.0));
rope.setLength(3.0); // rest length in metres
StructureRope tether = StructureRope.toWorld(pipeline,
        shipA, new Vector3d(1.0, 1.0, 0.0), worldAnchor);
rope.close();
```

`StructureRope` is a minimal, native impulse rope driven by the public
pre-physics-substep event. It applies equal/opposite impulses at the two anchor
points while the endpoints are farther apart than `length()`, with configurable
`stiffness` (`N/m`), `damping` (`N·s/m`) and `maxTension` (`N`). It exposes
`endpointA()`, `endpointB()`, `isTaut()`, `tension()` and `isValid()`. It is
**not** a solver constraint: it can stretch under load, depends on substep
length, and has no collision/self-collision. Use a point constraint when a
perfectly rigid link is required. Closing the rope removes its listener, and it
also closes itself when an endpoint structure disappears.

---

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

### Lift and propeller actors

`BlockStructureLiftProvider` is a specialized `BlockEntityStructureActor`.
Implementing it on a block entity is enough to queue a lift force once per fixed
substep; discovery and failure isolation use the normal actor path.

```java
public final class ThrusterBlockEntity extends BlockEntity
        implements BlockStructureLiftProvider {
    @Override
    public Vector3dc structureLift(StructurePhysicsContext context) {
        return new Vector3d(0.0, 500.0, 0.0); // N, world space
    }
}
```

The default applies the force at the centre of mass and uses
`BlockStructureLiftProvider.DEFAULT_LIFT_GROUP`. Override `applicationPoint` for
an off-centre anchor and `forceGroup` for a dedicated debug group.

`BlockStructurePropeller` is the propeller-style specialization: implement
`thrustNewtons(context)` and `thrustAxisLocal()`. The default lift rotates the
local axis through the substep pose and returns `axis * thrust` in newtons.
Neither hook adds a new discovery mechanism: the block entity must still be in
the auxiliary ticking level, and the structure's actor cache must be refreshed
after changing actor implementations.

### What is actually wired

| Hook | Wired into | Effect |
| --- | --- | --- |
| collision shape / dynamic collider | `VoxelColliderBuilder` through `VoxelColliderHooks` | fractional parts become Bullet compound-shape children and contribute to mass/inertia |
| custom centre of mass | `VoxelColliderBuilder` | shifts the structure's weighted centre and inertia; block still collides as a full cube unless a shape hook is present |
| physics properties | `VoxelColliderBuilder` cell mass/material | overrides structure mass and surface material after the data pack |
| assembly listener | `LevelPhysics.createStructure` | before body construction and after insertion, once per structure when the block is present |
| block-change listener | `PhysicsStructure.reindex` | diffed against a lazily captured state snapshot; only volumes that expose block states |

Custom fractional parts are part of the Bullet shape and mass but are **not**
added to `VoxelColliderData.boxes()`; they live in `customParts()`. Entity and
player movement, the client predictor, riding support and the diagnostic
inspector consume both lists, so slabs, stairs and fences collide exactly.
Custom-centre-of-mass blocks keep their cube in `boxes()`.

When a block registers no explicit shape/dynamic-collider hook, the collider
adapter falls back to the `BlockState`'s vanilla collision shape. A full cube
still returns `null` and keeps the greedy merged-box fast path; an empty shape
returns an empty part list (mass remains); a partial shape becomes exact
`VoxelCellPart`s. `StatefulVoxelVolume` supplies states directly, while
`MutableVoxelVolume` states authored through
`StructureBlockStates` are used when `PhysicsStructure.reindex` rebuilds the
collider.

---

## 3. Entity utilities

`api/entity/EntityStructureUtil`. All structure-reading methods require a
`ServerLevel`; on a client they return `null`, zero or `false`.

```java
PhysicsStructure structure = EntityStructureUtil.getContaining(level, entity);
PhysicsStructure atPoint = EntityStructureUtil.getContaining(level, vec3);

Vector3d velocity = EntityStructureUtil.getVelocity(level, worldPoint, new Vector3d());
Vector3d velocityAt = EntityStructureUtil.getVelocityAtPoint(structure, worldPoint, new Vector3d());

double distanceSq = EntityStructureUtil.distanceSquaredWithStructures(level, a, b);
Vec3 eye = EntityStructureUtil.getEyePositionInterpolated(entity, partialTick);

EntityStructureUtil.pushEntityLocal(structure, entity);       // stores local frame
// ... structure moves ...
EntityStructureUtil.popEntityLocal(structure, entity);        // applies current pose + velocity

EntityStructureUtil.projectOutOfStructure(structure, entity); // rewrites current state in place
```

- `getContaining` checks the structure OBB **and** the solid voxel, so hollow
  corners do not count as inside.
- `getVelocity` looks up the containing structure and returns the material point
  velocity `v + ω × r` in m/s. `getVelocityAtPoint` skips the lookup and works
  even just outside the solid volume.
- `distanceSquaredWithStructures` compares two points in the same structure-local
  frame when both resolve to that structure; otherwise it is exactly
  `Vec3#distanceToSqr`. A rigid pose preserves distance, so this makes the frame
  explicit rather than changing the numeric value.
- `getEyePositionInterpolated` lerps `xo/yo/zo` to the current position and adds
  the eye height. It is common-side and does **not** apply a client structure
  render pose.
- `pushEntityLocal` stores position, look direction and velocity (converted to
  m/s) in the structure frame. Nothing moves at push time. Frames are a weak
  per-entity stack.
- `popEntityLocal` consumes the last frame, applies the current pose and the
  structure's material velocity, converts m/s back to m/tick and snaps the
  entity. It returns `false` when no frame was pushed.
- `projectOutOfStructure` is the forward counterpart: it treats the entity's
  current position/rotation/velocity as structure-local, rewrites them in world
  space and does not consume a cached frame or add structure velocity.
- The optional `anchor` is an unrotated metre offset added before the inverse
  transform and subtracted after the forward transform.
- Use `clearLocalFrames(entity)` when external code teleports or removes the
  entity.

---

## 4. Structure containers, observers and events

### `sublevel/StructureContainer`

Read-only view of the structures attached to one level.

| Method | Meaning |
| --- | --- |
| `level()` | level this container belongs to |
| `structures()` | immutable `List<PhysicsStructure>` snapshot |
| `byId(long)` | structure with that body id, or `null` |
| `byName(String)` | decimal body id or matching body user-data label, or `null` |
| `containing(Vec3)` | structure whose solid volume contains a world point |
| `intersecting(Aabb)` | structures whose world AABB overlaps |
| `size()` / `isEmpty()` | counts |
| `StructureContainer.getContainer(Level)` | server or client container, or `null` |
| `StructureContainer.getContainer(ServerLevel)` | server container, or `null` |

`byName` is a convenience, not a persistent naming system: structures do not
have a dedicated name field, so a non-numeric label resolves only when the
body user data's string form matches.

### `sublevel/ServerStructureContainer`

The authoritative server container adds lifecycle operations and observers:

```java
ServerStructureContainer container = CrythstApi.container(serverLevel);
PhysicsStructure structure = container.createStructure(definition);
container.removeStructure(structure);
List<PhysicsStructure> afterRefresh = container.refreshStructure(structure);
container.addObserver(observer);
```

Observers are diffed by polling the authoritative structure list once per
server tick, plus immediately after calls made through the container's own
create/remove/refresh methods. A split reports the original removal and the new
bodies' additions.

### `sublevel/StructureObserver`

```java
onStructureAdded(PhysicsStructure structure);
onStructureRemoved(PhysicsStructure structure);
onStructureRefreshed(PhysicsStructure structure);
```

`onStructureRefreshed` runs only when an explicit container refresh left the
same body alive; a split is reported as remove/add.

### `sublevel/StructureContainerEvents`

```java
AutoCloseable ready = StructureContainerEvents.registerContainerReady(container -> { ... });
AutoCloseable observers = StructureContainerEvents.registerObserver(observer);
StructureContainerEvents.clear();
```

`registerContainerReady` fires once per new server physics world.
`registerObserver` attaches to every existing and future server container. All
callbacks run on the server thread. This bus is separate from `CrythstEvents`
because it exposes the actual container rather than only the pre/post tick.

### `sublevel/StructureArgumentType`

Brigadier argument for a structure reference:

```java
.then(Commands.argument("structure", StructureArgumentType.structure())
        .executes(ctx -> {
            PhysicsStructure structure =
                    StructureArgumentType.getStructure(ctx, "structure");
            ...
        }))
```

Accepted forms: decimal body id (`42`), hex id (`0x2a` or `#42`), or a
name/label (`ship`, `name:ship`). Parsing produces a `Selector`; the command body
resolves it through the command source's container.

### Client container limitation

`ClientStructureContainer` is reachable through
`CrythstApi.container(clientLevel)` / `StructureContainer.getContainer(level)`:

```java
public List<PhysicsStructure> structures();              // integrated client only
public List<ClientStructureSnapshot> snapshots();        // multiplayer sync cache
public @Nullable ClientStructureSnapshot snapshot(long bodyId);
public int snapshotCount();
public boolean networkIsEmpty();
```

In multiplayer, `structures()` is empty. Use `snapshots()`, `snapshot(id)` or
the client render views below; the network container deliberately returns
immutable snapshots, not `PhysicsStructure` objects. Because the default
`byId` / `containing` methods use `structures()`, they do not resolve network
snapshots on a client. `ClientStructureContainer` adds the snapshot-native
queries `snapshotContaining(Vec3)` and `snapshotsIntersecting(Aabb)`; they use
the immutable pose/cell data and never read a native body.

### Loading tickets (`sublevel/ticket`)

Crythst keeps the auxiliary ticking region of every live structure loaded. A
`StructureLoadingTicket` additionally pins the **host** chunks covered by the
structure's world AABB, which is useful while a structure crosses terrain that
would otherwise be unloaded:

```java
StructureLoadingTickets.StructureLoadingTicket ticket =
        StructureLoadingTickets.acquire(serverLevel, structure, 1); // 1 chunk radius
ticket.refresh();          // after the structure moved
ticket.isRegionActive();   // auxiliary ticking region exists
ticket.chunkCount();
ticket.release();
```

Tickets are reference-counted per chunk across handles, so independent systems
can share a region. `StructureLoadingTickets.refreshAll(level)`,
`tickets(level)`, `tickets(level, bodyId)` and `releaseAll(level)` cover
lifecycle and diagnostics. Tickets are runtime state and are not persisted; a
ticket does not protect a structure from explicit removal.
`ServerStructureContainer.acquireLoadingTicket(...)` is a convenience wrapper.

### Tracking points (`sublevel/tracking`)

`StructureTrackingPoints` stores named structure-local points with the world
position captured at save time. The data is a normal per-level `SavedData`, so
vanilla writes it with the world; points survive reloads even though body ids
do not.

```java
StructureTrackingPoints points = CrythstApi.trackingPoints(serverLevel); // or container.trackingPoints()
StructureTrackingPoint dock = points.add("dock", structure, new Vector3d(1.0, 2.0, 3.0),
        true, 1); // keep host chunks loaded within one chunk
points.get(dock.id());
points.byName("dock");
points.forStructure(bodyId);
points.worldPosition(dock);
points.nearest(worldPoint, 8.0);
points.nearby(worldPoint, 16.0);

points.refreshTickets();        // re-bind after a world reload
points.captureWorldPositions(); // update saved fallback positions
points.remove(dock);
points.clear();
```

Resolution order is current body id, optional stable structure identity
(captured from `StructureSavedData` when available), then the structure
containing the saved world position. `keepLoaded` points reacquire a ticket on
`refreshTickets()` and release it on removal. `StructureTrackingPoint` has a
plain NBT encoding (`toTag`/`fromTag`) and
`StructureTrackingPoints.writePoints/readPoints` for tooling and tests.

### Structure level view

`StructureLevelView` (obtained with `container.view(bodyId)` or
`CrythstApi.view(...)`) is a level-like read/write surface over one structure's
local voxel grid. Its `BlockPos` arguments are always structure-local; the
auxiliary void region coordinates are hidden.

```java
StructureLevelView view = container.view(bodyId);
BlockState state = view.getBlockState(localPos);
FluidState fluid = view.getFluidState(localPos);
BlockEntity be = view.getBlockEntity(localPos);
view.setBlockAndUpdate(localPos, Blocks.STONE.defaultBlockState());
view.registryAccess();

Vector3d world = view.voxelCenterToWorld(x, y, z, new Vector3d());
BlockPos local = view.worldBlockToLocal(worldBlock);
AABB worldBox = view.localVoxelBoxToWorldAabb(localBox);
List<Entity> nearby = view.getEntitiesInLocalBox(localBox, null);
view.addFreshEntityAtLocal(entity, localMetres);
view.playSound(null, localPos, sound, SoundSource.BLOCKS, 1.0f, 1.0f);
view.addParticle(particle, localX, localY, localZ, 0.0, 0.0, 0.0);

ResourceKey<Level> dimension = view.dimension();
long gameTime = view.gameTime();
long dayTime = view.dayTime();
RandomSource random = view.random();
LevelChunk chunk = view.getChunkIfLoaded(localChunkX, localChunkZ); // non-loading
boolean loaded = view.isChunkLoaded(localChunkX, localChunkZ);
boolean pinned = view.isKeptLoaded();
```

Writes are authoritative on server views, reindex the collider, refresh the
auxiliary region and advance `epoch()`. Client views are read-only. Entities are
host-world entities: `addFreshEntity` and `getEntitiesInLocalBox` do not create
an entity container, and the entity query is a coarse world-AABB superset for
rotated structures. Chunk queries never load a chunk. `dimension`, game time,
random, sound and particle helpers project through the structure pose. A full
`Level` facade (lighting, tick lists, pathfinding, cross-boundary neighbour
graph) remains deferred.

---

## 5. Client API

All types in `api/client` are client-only. Do not class-load them on a
dedicated server.

### Render views

`StructureRenderView` is a read-only, render-thread-safe view of one structure:

```java
List<StructureRenderView> all = StructureRenderView.all();       // current partial tick
List<StructureRenderView> same = StructureRenderView.all(0.5);   // explicit partial tick
StructureRenderView one = StructureRenderView.get(bodyId);

long id = one.bodyId();
StructureRenderPose pose = one.renderPose();
Transform3d sampled = one.pose();
Transform3d previous = one.previousPose();
Transform3d current = one.currentPose();
Vector3d velocity = one.velocityAt(worldPoint);
```

`StructureRenderView.Source` is `INTEGRATED_CLIENT` or `NETWORK`. A live
integrated-client body wins over a network snapshot with the same id.
`StructureRenderData` is the immutable record implementation; its
`worldBounds(bounds, pose, blockScale)` helper computes the same world AABB used
by selection and rendering.

`StructureRenderPose` exposes the sampled pose, both timeline endpoints and the
publish interval. `linearVelocity()`, `angularVelocity()` and
`velocityAt(point)` are finite differences over the render timeline in m/s and
rad/s. They are safe on the render thread and work for network structures;
physics code should still use the authoritative body velocity.

### Pose providers

```java
AutoCloseable handle = StructurePoseProvider.register((bodyId, partialTick, frameNanos) -> {
    // return a pose, or null to fall through
    return null;
});
```

Providers are queried in reverse registration order (newest wins). Returning
`null` falls through to the next provider and then to the built-in timestamped
timeline sampler. The default sampler ignores `partialTick` and uses the same
one-tick-delayed `PoseTimeline` as normal rendering; a provider that needs a
different clock (for example a Create renderer) may use it.

### Clip and pick extensions

`StructureClipExtension` exposes the same merged vanilla + structure clip used
by `ClientLevel.clip`:

```java
BlockHitResult hit = StructureClipExtension.clip(clientLevel, clipContext);
try (AutoCloseable ignored = StructureClipExtension.ignore(bodyId)) {
    // structure bodyId is omitted from this thread's clips
}
try (AutoCloseable ignored = StructureClipExtension.ignoreAll()) {
    // only real blocks are considered
}
```

Ignores are thread-local and nestable. Real blocks always keep precedence.
`StructurePickExtension.current()` returns the `StructureBlockHitResult` already
installed in `Minecraft.hitResult`; `raycast(reach)` performs a structure-only
raycast with the gravity-adjusted look vector. Prefer `current()` for normal
picking so the hit is not re-raycast with a different quantisation. Ignores
affect subsequent clips/raycasts, not a hit object that was already produced.

### Client tracking and inherited velocity

`EntityStructureTracking` provides a client-thread tracking structure and
material velocity for camera, music, rendering or riding effects:

```java
StructureRenderView tracking = EntityStructureTracking.getTrackingStructure(entity);
Vector3d inherited = EntityStructureTracking.getInheritedVelocity(entity); // m/s

EntityStructureTracking.track(entity, tracking); // explicit, preferred after a contact
EntityStructureTracking.track(entity, bodyId);
EntityStructureTracking.clear(entity);
```

The fallback uses the most recent client collision trace when it is recent and
geometrically overlaps the entity, then the nearest render AABB. Inherited
velocity is a finite difference of the timestamped render pose pair, including
the angular term; it never reads a native body.

---

## 6. Assembly and disassembly

All assembly operations are server-thread only and operate on **world-space**
blocks. This is not Sable's plot/sub-level assembly: a Crythst structure has no
distant plot or second block grid. See
[`API_GAP_ANALYSIS.md`](../API_GAP_ANALYSIS.md) for the architectural
difference.

### Gathering

`assembly/StructureGather` finds a connected block region:

```java
StructureGather.GatherResult result = StructureGather.gatherConnectedBlocks(
        serverLevel, origin, maxBlocks, frontierFilter);
if (result.success()) {
    List<BlockPos> blocks = result.blocks();
    StructureGather.Bounds bounds = result.bounds();
}
```

- Connectivity is **26-neighbour**; diagonals and vertical diagonals connect.
- Air, fluids and blocks without a collision shape are skipped and do not
  propagate the flood fill.
- `frontierFilter` is an additional veto (claim/protection checks).
- Results are deterministic (`x`, then `y`, then `z`).
- `Status` is `OK`, `NO_BLOCKS` or `TOO_MANY`. `TOO_MANY` carries the first
  `limit` positions for diagnostics but must not be assembled.
- `MAX_BLOCKS` is `1_000_000`.

### Assemble

```java
PhysicsStructure structure = StructureAssembly.assemble(
        serverLevel, anchor, blocks,
        AssemblyOptions.defaults()
                .withPose(Transform3d.of(new Vector3d(0.0, 1.0, 0.0)))
                .withType(BodyType.DYNAMIC)
                .withBlockScale(1.0)
                .withRemoveFromWorld(true));
```

`AssemblyOptions` defaults: identity pose, `DYNAMIC`, default material,
`massScale = 1`, `blockScale = 1`, remove source blocks, copy block entities,
keep entities. The anchor is a world block corner; with default options the
captured minimum block stays at its original position. `pose` is relative to
the anchor (`anchor + posePosition + rotation * (capturedMin - anchor)`).

Safety contract:

- The world is not modified until the volume and rigid body exist.
- If volume/body creation or later removal fails, removed blocks are restored
  and the body is closed.
- Removal uses update-suppression flags, not `destroyBlock`, so assembly does
  not drop items.
- Block-entity NBT is captured before removal and reattached to the auxiliary
  ticking level when that level is enabled.
- Entities are **never** transferred into a structure. `keepEntities` only
  documents intent; Crythst structures have no entity container. Entities are
  left in the world.

### Disassemble

```java
StructureAssembly.disassemble(serverLevel, structure,
        DisassembleOptions.defaults()
                .withRemoveStructure(true)
                .withBlockEntities(true)
                .withDrops(false));
```

`DisassembleOptions` defaults: remove structure `true`, restore block entities
`true`, drop blocks that do not fit `false`, suppress neighbour updates `true`.
Voxel coordinates are unshifted; the current structure pose and `blockScale`
map them back to world blocks. Destinations outside the build height or occupied
by a non-replaceable block are skipped; optionally an item is dropped.

`AssemblyTransform` exposes the same mapping directly:

```java
AssemblyTransform transform = new AssemblyTransform(structure.pose(), structure.blockScale());
Vector3d centre = transform.voxelCenter(x, y, z);
BlockPos worldPos = transform.voxelToWorldBlock(x, y, z);
Vector3d voxel = transform.worldToVoxel(worldPoint);
BlockPos voxelBlock = transform.worldBlockToVoxel(worldBlock);
```

### Assembly limitations

- **World-space, not plot-based.** No plot-yard allocation, plot chunk holders,
  or second `Level` view. Create Aeronautics code that expects `SubLevel` block
  access must be ported.
- **No entity transfer.** Passengers, leads, seats and contraption entities are
  not moved into the structure.
- **Assembled/replaced blocks are real world edits.** Other mods, lighting,
  neighbours and block entities see ordinary world changes.
- **Disassembly can skip crowded destinations.** Blocks that do not fit are
  dropped only if `dropBlocksThatDoNotFit` is enabled. Otherwise they are not
  placed; if `removeStructure` is also true, those skipped blocks are lost.
  Disassemble with drops enabled or into a clear area when preserving every
  block matters.

---

## 7. Block-entity actors and force groups

### `block/BlockEntityStructureActor`

```java
public final class MyBlockEntity extends BlockEntity
        implements BlockEntityStructureActor {
    @Override
    public void structurePhysicsTick(StructurePhysicsContext context) {
        QueuedForceGroup lift = context.forceGroup(MY_GROUP);
        lift.applyPointForce(context.centerOfMassWorld(),
                new Vector3d(0.0, 500.0, 0.0));
    }
}
```

Actors are invoked once per fixed physics substep, immediately before
integration, on the server thread. Use the supplied substep time; there may be
zero, one or several substeps per server tick. Direct velocity changes through
`context.body()` are allowed and take effect in the same substep.

Discovery requires the block entity to be present in the **auxiliary ticking
level**. If `subLevelEnabled` is false or the void level could not be created,
no actors run. The actor list is cached per structure and rebuilt after
structure creation, refresh/reindex, persistence restore and removal. Adding a
new actor implementation to a live block entity without recreating or
refreshing the structure is not discovered; call the refresh path after changing
actors.

### `block/StructurePhysicsContext`

| Method | Meaning |
| --- | --- |
| `structure()` | live `PhysicsStructure` |
| `level()` | real host `ServerLevel`, not the auxiliary level |
| `structurePose()` | immutable structure-origin pose for this substep |
| `body()` | `RigidBodyHandle` valid for the callback |
| `massData()` | mass/inertia for this substep |
| `bodyId()` | numeric body id |
| `centerOfMassWorld()` | read-only world-space COM |
| `forceGroup(ForceGroup)` | per-structure accumulator |

The context and force groups are ephemeral: do not cache them across substeps.
The block entity's own `getBlockPos()` is a position in the auxiliary level;
map structure-local coordinates through `structurePose()` for the real level.

### `physics/force/ForceGroup`

Immutable grouping/display metadata: `label` (`Component`), optional
`description`, packed `0xRRGGBB` `color`, and `debug`/`defaultDisplayed`.
Groups are compared by record equality, so use a stable instance or equal value
for the lifetime of the block entity. Debug rendering is advisory only; Crythst
does not currently render a force-group panel.

### `physics/force/QueuedForceGroup`

Mutable per-structure, per-substep accumulator:

```java
void applyPointForce(Vector3dc worldPoint, Vector3dc forceNewtons);
void applyLinearAndAngularForce(Vector3dc forceNewtons, Vector3dc torqueNewtonMetres);
Vector3d accumulatedForce(Vector3d dest);
Vector3d accumulatedTorque(Vector3d dest);
```

Units are newtons and `N·m`; application points are world-space metres.
Crythst converts the totals to impulses at the end of the substep:
`N·s = N * dt`, `N·m·s = N·m * dt`, then applies one linear/angular impulse pair.
Do not multiply by the time step yourself and do not retain the accumulator.

### `physics/force/ForceTotals`

`ForceTotals` is an immutable snapshot of the forces/torques contributed
through force groups during the most recent fixed substep:

```java
ForceTotals totals = pipeline.forceTotals(structure);
Vector3d force = totals.force();                 // N
Vector3d torque = totals.torque();               // N·m
for (ForceTotals.GroupForce group : totals.groups()) {
    group.group();
    group.forceNewtons();
    group.torqueNewtonMetres();
}
ForceTotals.empty(bodyId);                       // no actor contribution
```

Values are recorded **before** conversion to impulses, so they remain in
newtons/newton-metres. Only actor force groups are included; direct impulses and
forces applied through the pre-step event are not. `LevelPhysics#forceTotals`
and `LevelPhysics#forceTotalsStatus` provide the same read-only diagnostic for
commands.

### `physics/StructureMassTracker`

```java
StructureMassTracker mass = pipeline.massTracker(structure);
double total = mass.mass();                      // kg, collider mass * massScale
double cell = mass.cellMass(1, 0, 3);            // kg for one local voxel
double atWorld = mass.massAtWorld(worldPoint);   // kg for the containing cell
Vector3d com = mass.centerOfMassWorld();         // metres
int solid = mass.solidCellCount();
```

Total mass, inertia and centre of mass come from the collider, so custom shape,
dynamic-collider and physics-property hooks are reflected. Per-cell masses come
from the volume. With custom fractional colliders the sum of cell masses can
differ from the total; use `mass()` for physics and cell values for gameplay or
diagnostics.

### `physics/StructurePhysicsRuntime`

One runtime belongs to a `LevelPhysics`. `LevelPhysics` invokes
`runSubstep(structure, fixedTimeStep)` from its `StepListener.beforeStep` hook;
mods normally do not construct or call it. It is useful for diagnostics or a
custom level integration:

```java
runtime.runSubstep(structure, dt);
runtime.forget(structure);     // after refresh/removal
runtime.clear();
int states = runtime.cachedStructureCount();
int groups = runtime.cachedForceGroupCount();
```

Actor failures are logged once per failing class for that structure and do not
stop the remaining actors or force application. The runtime caches one force
accumulator per `ForceGroup` per structure; the cache is dropped on
refresh/removal. This is an internal-ish integration type, not a gameplay setup
requirement.

---

## 8. Math utilities

`api/math/StructureMath` is pure, side-safe coordinate/quaternion glue:

```java
Vector3d centre = StructureMath.atCenterOf(blockPos);
Vector3d lower = StructureMath.atLowerCornerOf(blockPos);
Vec3 mojang = StructureMath.toMojang(jomlVector);
Vector3d joml = StructureMath.toJoml(mojangVector);
Quaterniond q = StructureMath.toJoml(mojangQuaternion);
Quaternionf f = StructureMath.toMojang(jomlQuaternion);
```

`ZERO` and `QUAT_IDENTITY` are exposed as read-only JOML interfaces. Methods
with a `dest` argument overwrite and return it; methods without one allocate.

---

## 9. Events

### `api/CrythstEvents`

Thread-safe bus for pre/post physics substeps and the older level/pipeline
container-ready event. It does not depend on Fabric's event API.

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

### `api/sublevel/StructureContainerEvents`

This is the container-aware event layer: `registerContainerReady` and
`registerObserver` are described in section 4.

---

## 10. Global facade

| Method | Result |
| --- | --- |
| `CrythstApi.structures(Level)` | immutable list of live `PhysicsStructure`, empty on the client |
| `CrythstApi.container(Level)` | server or client `StructureContainer`, or `null` |
| `CrythstApi.container(ServerLevel)` | `ServerStructureContainer`, or `null` if no world yet |
| `CrythstApi.structuresIn(Level, Aabb)` | structures overlapping a world AABB |
| `CrythstApi.structuresIn(Level, net.minecraft.world.phys.AABB)` | Minecraft-vector overload |
| `CrythstApi.structureAt(Level, Vec3)` / `structureContaining` | structure whose solid volume contains the point |
| `CrythstApi.structureAt(Level, Vector3dc)` | JOML overload |
| `CrythstApi.physics(ServerLevel)` | `PhysicsPipeline`, creating the level world if needed; `null` if unavailable |
| `CrythstApi.body(Level, long)` | `RigidBodyHandle` from an existing world, without creating one |
| `CrythstApi.acquireLoadingTicket(ServerLevel, PhysicsStructure, int)` | host-chunk loading ticket for a structure |
| `CrythstApi.trackingPoints(ServerLevel)` | persistent structure tracking points for a level |

The original engine methods (`engine()`, `createWorld(...)`,
`backendDescription()`, `isAvailable()`) remain available for callers that need
the raw physics engine.

---

## 11. Coverage and gaps

Covered by the native API:

- rigid-body impulse/velocity/teleport handle, centre-of-mass, mass tensor;
- per-level physics pipeline with body-id operations and no raw stepping;
- fixed/free/rotary/prismatic/6-DOF structure constraints, handles, add/remove,
  world-space frame conversion, hinge motors and per-axis 6-DOF limits/motors/springs;
- loose box bodies and a minimal substep-driven rope between structure/world
  anchors;
- per-cell and total mass tracking, centre of mass, and last-substep force
  totals per body and per force group;
- block collision shape, custom centre of mass, dynamic collider, assembly and
  block-change listeners, post-data-pack physics properties, lift/propeller
  actors;
- server and client structure containers, observers, container-ready events and
  a Brigadier structure argument;
- entity containment, velocity, sub-level-aware distance, interpolated eye
  position, local push/pop and `projectOutOfStructure`;
- world-space gather/assemble/disassemble with block-entity capture and
  `AssemblyTransform`;
- block-entity physics actors, per-substep force groups and the internal
  `StructurePhysicsRuntime`;
- structure level views with block/fluid/BE access, writes, entities, particles,
  sounds, metadata, random source and non-loading chunk queries;
- host-chunk loading tickets and persistent world-space tracking points;
- client render views, timestamped render poses, pose providers, clip/pick
  extensions and tracking/inherited velocity;
- pure coordinate/quaternion helpers in `StructureMath`;
- per-substep pre/post events and a container-ready event.

Not covered yet:

- per-axis solver impulse readback for 6-DOF joints (Bullet exposes only the
  total joint impulse);
- constraint persistence for raw backend joints that have no API configuration
  (only constraints created through `PhysicsPipeline`/`PhysicsStructure` carry
  a serializable configuration);
- rope solver constraints, rope collision/self-collision and non-impulse rope
  dynamics;
- a `Level`-subclass `SubLevel` facade with plot/plot-yard allocation and
  plot-local chunk holders;
- propeller/lift-provider parity for every Sable block API (the native
  lift/propeller actor hooks are implemented);
- a public force-group renderer/debug panel (totals and status text are
  available);
- client-authoritative structure containers (the network client container
  returns immutable snapshots, not `PhysicsStructure` objects);
- block-entity render dispatch for structure block entities;
- full binary drop-in compatibility with unmodified Create Aeronautics or
  `dev.ryanhcode.sable.*` shims (intentionally not provided);
- custom fractional collision parts in legacy `boxes()` consumers (Bullet and
  mass properties are correct);
- inertia tensors for loose bodies in `MassData` (zero by design).

See [`API_GAP_ANALYSIS.md`](../API_GAP_ANALYSIS.md) for the Create Aeronautics
gap matrix and staged plan.

---

## 12. Threading and nullability summary

- Registry writes: any thread.
- Hook invocation, actor/force callbacks, constraint/box/rope operations, event
  dispatch, body operations, assembly, loading tickets, tracking-point mutation
  and server containers: server thread.
- Tracking-point reads (`all`, `byName`, `worldPosition`, NBT helpers) are safe
  wherever the saved data is available; the underlying `SavedData` is not
  thread-safe, so use the server thread when mixing reads and writes.
- Client render views, pose providers, clip/pick extensions and tracking:
  client thread only; do not class-load them on a dedicated server.
- `PhysicsStructure.pose()` is safe to read for rendering; do not call
  `body()`/`RigidBodyHandle` methods from the render thread.
- Public lookup methods marked nullable return `null` when the level is a
  client level without the requested data, has no physics world, or the
  requested body/structure is gone.
- `structures(...)`, `structuresIn(...)`, `structures()`, `snapshots()` and
  client view lists never return `null`.
