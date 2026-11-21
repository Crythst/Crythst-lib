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
snapshots on a client.

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

The original engine methods (`engine()`, `createWorld(...)`,
`backendDescription()`, `isAvailable()`) remain available for callers that need
the raw physics engine.

---

## 11. Coverage and gaps

Covered by the native API:

- rigid-body impulse/velocity/teleport handle, centre-of-mass, mass tensor;
- per-level physics pipeline with body-id operations and no raw stepping;
- block collision shape, custom centre of mass, dynamic collider, assembly and
  block-change listeners, post-data-pack physics properties;
- server and client structure containers, observers, container-ready events and
  a Brigadier structure argument;
- entity containment, velocity, sub-level-aware distance, interpolated eye
  position, local push/pop and `projectOutOfStructure`;
- world-space gather/assemble/disassemble with block-entity capture and
  `AssemblyTransform`;
- block-entity physics actors, per-substep force groups and the internal
  `StructurePhysicsRuntime`;
- client render views, timestamped render poses, pose providers, clip/pick
  extensions and tracking/inherited velocity;
- pure coordinate/quaternion helpers in `StructureMath`;
- per-substep pre/post events and a container-ready event.

Not covered yet:

- public constraints (fixed/rotary/generic 6-DOF), ropes, box physics objects or
  generic pipeline extension points (the core `PhysicsWorld` still has low-level
  constraint support);
- loading tickets, tracking-point saved data and schematic serialization
  context;
- a `SubLevel`-like second `Level` view with plot/plot-yard allocation and
  plot-local chunk holders;
- propeller/lift-provider/actor parity for every Sable block API;
- force totals/debug UI and a public force-group renderer;
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
- Hook invocation, actor/force callbacks, event dispatch, body operations,
  assembly and server containers: server thread.
- Client render views, pose providers, clip/pick extensions and tracking:
  client thread only; do not class-load them on a dedicated server.
- `PhysicsStructure.pose()` is safe to read for rendering; do not call
  `body()`/`RigidBodyHandle` methods from the render thread.
- Public lookup methods marked nullable return `null` when the level is a
  client level without the requested data, has no physics world, or the
  requested body/structure is gone.
- `structures(...)`, `structuresIn(...)`, `structures()`, `snapshots()` and
  client view lists never return `null`.
