# Create compatibility (soft coupling)

This document describes Crythst's optional Create integration: detection of
Create contraption entities and a small anchor bridge that keeps them on a
physics structure. The implementation lives in
`io.mikaple.crythst.integration.create` and has **no compile-time dependency on
Create**. Crythst still builds, tests and runs when Create is not installed.

Reference build: **Create-Fly 26.3 / 6.0.9-1** (`com.zurrtum.create`). Upstream
Create's `com.simibubi.create` package names are also accepted.

## Files

| File | Purpose |
| --- | --- |
| `CreateCompat` | Class-name detection, cached public-API reflection, `shouldStayOnStructure`, optional `register()` |
| `CreateReflection` | Package-private cached `Method` lookup; public methods only |
| `CreateStructureBridge` | Per-entity attachment registry, local↔world transform, server tick, passenger carry |
| `StructureAttachmentTarget` | Interface for the entity riding manager (works without the registry) |
| `StructureAttachment` | Immutable `UUID + PhysicsStructure + local Transform3d` record |
| `docs/create-compatibility.md` | This document |

Tests:

- `src/test/java/io/mikaple/crythst/integration/create/CreateCompatTest.java`
- `src/test/java/io/mikaple/crythst/integration/create/CreateStructureBridgeTest.java`

## Design rule: soft detection only

Nothing in this package imports `com.zurrtum.create.*` or
`com.simibubi.create.*`. Detection uses:

```java
Class.forName(name, /*initialize=*/ false, loader);
```

and API calls use `Class#getMethod` plus `Method#invoke`. Only **public**
methods are reachable; `setAccessible(true)` is never used. Every reflective
failure returns `empty`/`false` instead of throwing.

Detected/known names:

- `com.zurrtum.create.Create` (marker)
- `com.simibubi.create.Create` (marker)
- `com.{zurrtum,simibubi}.create.content.contraptions.AbstractContraptionEntity`
- `com.{zurrtum,simibubi}.create.content.contraptions.ContraptionEntity`
  (older/alternate base name; Create-Fly 6.0.x uses the `Abstract…` name)

Known subclasses are covered by `Class#isInstance` once a base resolves:
`ControlledContraptionEntity`, `OrientedContraptionEntity`,
`GantryContraptionEntity`, `CarriageContraptionEntity`, and add-on subclasses.
For the class-name-only path, any Create-package class whose simple name ends in
`ContraptionEntity` is accepted.

### Limits of the soft check

- A future Create that renames the package or base class is treated as "not
  installed". The layer then stays dormant; nothing crashes. Check the startup
  log for `detected base classes`.
- Obfuscated, shaded or relocated Create builds are not detected.
- Detection answers "is this class present", not "is this API version
  compatible". Renamed methods degrade to empty/false per call.
- `isLoaded()` caches the first result for the process. This matches Fabric's
  immutable mod-loading lifecycle.
- No Create types are mentioned in `fabric.mod.json`; the integration cannot
  force Create to load.

## What Create exposes (Create-Fly 26.3 / 6.0.9-1)

Read from `~/IdeaProjects/Create-Fly`; only public members are used.

### `AbstractContraptionEntity` (base class)

- `Vec3 getAnchorVec()`, `Vec3 getPrevAnchorVec()`
- `Vec3 toGlobalVector(Vec3, float)`, `toGlobalVector(Vec3, float, boolean)`
- `Vec3 toLocalVector(Vec3, float)`, `toLocalVector(Vec3, float, boolean)`
- `abstract Vec3 applyRotation(Vec3, float)` / `reverseRotation`
- `void setPos(double,double,double)` override: also moves the contraption
  bounding box (`setBoundingBox(contraption.bounds.move(anchor))`)
- `void move(double,double,double)`
- `Optional<UUID> getControllingPlayer()`,
  `void setControllingPlayer(@Nullable LivingEntity)`
- `void setDeltaMovement(Vec3)` is a **no-op**; movement must go through
  `setPos` / `move` / `setContraptionMotion`
- `Contraption getContraption()`, `isStalled()`, `handleStallInformation(...)`,
  `disassemble()`
- passengers: `getPassengers()`, `addSittingPassenger`, `removePassenger`,
  `positionRider`, `getDismountLocationForPassenger`, `canAddPassenger`
- tick: `tick()` → `contraption.tickStorage(this)`, `tickContraption()`,
  `tickActors()`; this is where block actors and mounted storage advance

`getAnchorVec()` is `position()` for the base class, but
`OrientedContraptionEntity` subtracts `(0.5, 0, 0.5)`. The bridge therefore
stores and writes **`Entity.position()` space**, not anchor-vector space, so no
offset drift accumulates.

### Subclasses

- `ControlledContraptionEntity` (bearing/piston): position and angle are driven
  by its `IControlContraption` block entity each tick. It discards itself when
  the controller disappears; its `setPos` only reacts on the client.
- `OrientedContraptionEntity` (minecart/coupling): yaw/pitch and position come
  from the vehicle/coupling; `getAnchorVec()` is offset by half a block.
- `GantryContraptionEntity`: `tickContraption()` calls `move()` and
  `disassemble()` when the gantry shaft/controller is gone.
- `CarriageContraptionEntity extends OrientedContraptionEntity` (trains).

### No `ContraptionEntity` class in 6.0.x

The abstract base is `AbstractContraptionEntity`; there is no concrete
`ContraptionEntity`. Both names are still probed so the layer works across
Create branches.

## Crythst side

`PhysicsStructure.pose()` maps structure-local metres to world space
(including the centre-of-mass offset). `PhysicsStructure.velocityAt(point)`
gives the body velocity at a world point. The bridge composes:

```
worldAnchor = structure.pose() * localAnchor
localAnchor = structure.pose()⁻¹ * worldAnchor
```

`Transform3d.compose(other)` means `this * other`, so
`structure.pose().compose(local)` is the correct order.

## API

### Detection

```java
CreateCompat.isLoaded();                    // Create present in this process?
CreateCompat.isContraptionEntity(entity);   // base-class / class-name check
CreateCompat.isRidingContraption(player);   // vehicle chain contains contraption
CreateCompat.shouldStayOnStructure(entity); // either of the above
CreateCompat.isCreateClass(name);
CreateCompat.isContraptionClassName(name);
CreateCompat.detectedContraptionBaseClasses();
```

### Reflective Create access (never throws)

```java
CreateCompat.anchorPosition(entity);                 // getAnchorVec()
CreateCompat.previousAnchorPosition(entity);         // getPrevAnchorVec()
CreateCompat.toGlobalVector(entity, local, partial); // 2-arg, then 3-arg fallback
CreateCompat.toLocalVector(entity, global, partial);
CreateCompat.controllingPlayer(entity);              // Optional<UUID>
CreateCompat.setControllingPlayer(entity, player);   // true if invoked
```

`Entity#setPos` is called directly; no reflection is needed because Create
overrides the vanilla public method.

### Attach / anchor

```java
// Capture current entity position into structure-local space.
Optional<StructureAttachment> a = CreateStructureBridge.attach(contraption, structure);

// Or supply an explicit local pose / local position.
CreateStructureBridge.attach(contraption, structure, localTransform);
CreateStructureBridge.attachAtLocalPosition(contraption, structure, localPos);

// Tick (idempotent; the lazy END_SERVER_TICK hook does this automatically).
CreateStructureBridge.tick(contraption);
CreateStructureBridge.tickAll(serverLevel);

// Lifecycle.
CreateStructureBridge.detach(contraption);
CreateStructureBridge.detachStructure(structure);
CreateStructureBridge.detachLevel(serverLevel); // level unload / dimension change
CreateStructureBridge.prune(serverLevel);       // drops removed entities / closed structures
CreateStructureBridge.clear();
```

When the ticker is installed, the bridge also subscribes to
`ServerLevelEvents.UNLOAD` (detaches that level) and
`ServerLifecycleEvents.SERVER_STOPPED` (clears everything). Callers that drive
`tickAll` manually own this cleanup.

`attach` fails (returns empty) for a removed entity or closed structure. The
registry keys entities by identity (`ConcurrentHashMap<Entity, …>`); values
contain no entity reference, so the record itself cannot keep an unloaded entity
alive.

### Caller-owned targets (recommended for the riding manager)

The riding manager can avoid the registry entirely:

```java
StructureAttachmentTarget target = new StructureAttachmentTarget() {
    @Override public PhysicsStructure structure() { return structure; }
    @Override public Transform3d localTransform() { return local; }
};
CreateStructureBridge.apply(rider, target);          // writes position + passengers
CreateStructureBridge.apply(rider, target, false);   // applyYaw = false
```

`StructureAttachmentTarget` also provides `structureId()`, `worldTransform()`,
`worldVelocity()` and `onPoseApplied(worldTransform)`.

## Exact integration points for the entity-riding manager

The current riding manager
(`io.mikaple.crythst.integration.minecraft.entity.riding.EntityStructureRidingManager`)
already owns a hard-lock binding (`bind` / `unbind`, `localPoint` + `localYaw`)
and allowlists
`com.{zurrtum,simibubi}.create.content.contraptions.AbstractContraptionEntity`
by default. Choose **one** owner per entity so the two systems do not write
different poses/yaw in the same tick:

- If the riding manager binds the contraption, use
  `EntityStructureRidingManager.bind(contraption, structure, localPoint)` and
  skip `CreateStructureBridge.attach` for that entity. Use
  `CreateCompat.shouldStayOnStructure` to extend eligibility to passengers and
  to prevent the collision pass from pushing the contraption.
- If Create-specific behaviour is needed (reflection anchor helpers,
  `StructureAttachmentTarget`, passenger delta carry, `onPoseApplied`), use
  `CreateStructureBridge`. Do not also register the same entity in the riding
  manager.

The manager's automatic probe skips `entity.isPassenger()`, so an Oriented
contraption riding a minecart is not picked up automatically; the Create layer's
`shouldStayOnStructure` and explicit bind cover that case.

1. **Eligibility.** Treat `CreateCompat.shouldStayOnStructure(entity)` as
   "must stay on a structure". This is true for the contraption entity itself
   and for anything whose vehicle chain reaches a contraption (seats, players,
   nested contraptions, minecarts).

2. **Collision filter.** `PhysicsTicker.register()` already installs:

   ```java
   EntityPhysicsCollisionManager.setEntityFilter(
           entity -> !CreateCompat.shouldStayOnStructure(entity));
   ```

   That filter feeds `StructureCollisionSettings`, which the
   `Entity.move`-integrated collision path also consults. If another filter
   already exists, compose with `&&` in the component that installed it; there
   is no getter for the previous predicate.

3. **Tick placement.** Call the bridge after the physics world has advanced:

   ```java
   // PhysicsTicker / END_SERVER_TICK, after MinecraftPhysics::tick
   for (ServerLevel level : server.getAllLevels()) {
       CreateStructureBridge.tickAll(level);
   }
   ```

   `CreateStructureBridge.registerTicker()` is idempotent and is also invoked
   lazily by `attach`/`CreateCompat.register()` when Create is present. If the
   riding manager already owns the post-physics tick, prefer `tickAll(level)`
   and skip the lazy hook (same result; repeated application is idempotent).
   `EntityStructureRidingManager` already registers its own END_SERVER_TICK
   callback; add the bridge call there only if the registry path is used.

4. **Attachment lifecycle.**
   - On contraption/player entering a structure (or when the riding manager
     decides the entity is supported): `attach(entity, structure)`.
   - On disassembly, discard, chunk unload or structure removal:
     `detach(entity)` or `detachStructure(structure)`.
   - Persist `StructureAttachment.entityId()`, `structureId()` and
     `localTransform()` if anchors should survive a restart; after load,
     `attach(entity, structure, savedLocal)`.
   - `prune(level)` is a safety net for missed detach calls.

5. **Passengers.** `applyWorldTransform` translates the vehicle's passengers by
   the same world delta (recursively, depth ≤
   `CreateStructureBridge.MAX_PASSENGER_DEPTH`). Vanilla `positionRider` then
   corrects their exact seat offset on the next entity tick. Do not call
   `setDeltaMovement` on a contraption entity: Create overrides it as a no-op.

6. **Platform velocity.** Use `target.worldVelocity()` (or
   `structure.velocityAt(anchor)`) to inherit movement. This is appropriate for
   ordinary passengers; contraptions should be moved by `setPos` only.

7. **Client sync.** The server calls the vanilla `Entity#setPos` path, so
   ordinary entity tracking replicates the contraption position. No custom
   packet is needed for v1. If a custom path is added later, sample the
   timestamped render pose (`PhysicsStructure#renderStateAt(nowNanos)` or
   `#interpolatedPoseAt(nowNanos)`) rather than the legacy tick-alpha
   `interpolatedPose(alpha)`.

8. **Control hand-off.** `CreateCompat.setControllingPlayer(entity, player)` and
   `controllingPlayer(entity)` wrap the public Create API. Do not call private
   fields such as `ControlledContraptionEntity.angle`.

## Behaviour and conflicts to know

- **Create still owns its tick.** The bridge runs at the end of the server
  tick, after Create's entity tick. A `GantryContraptionEntity` or a
  `ControlledContraptionEntity` will still move itself / check its controller
  first; anchoring then overrides the final position. Expect fighting for
  vehicles that are actively driven by a block entity or minecart.
- **Recommended v1 scope:** free-standing contraption entities that are not
  currently controlled by a Create block entity. For bearing/piston/gantry
  contraptions, either let Create drive them or define an explicit "anchored"
  mode that detaches the controller first.
- **Rotations are not forced.** `applyYaw` is opt-in because Create's rendering
  uses its internal yaw/pitch/angle state. Writing `Entity#setYRot` can make a
  contraption render sideways relative to its stored rotation.
- **No forces are applied to structures.** Like the entity collision pass, the
  bridge is one-way: it reads `pose()`/`velocityAt()` and moves entities only.
- **All coordinates are world coordinates.** Crythst structures are real
  physics bodies in the `ServerLevel`, not a separate plot/sub-level coordinate
  space. There is no `2e7` plot offset to reconcile.

## What Sable had to adapt, and what applies here

Sable's Create mixins live under
`dev.ryanhcode.sable.mixin.compatibility.create` and are **rendering/UI/reach**
fixes for plot coordinates (documented in
`~/IdeaProjects/sable-port/docs/port-notes.md`, "Create 兼容"):

- `AABBOutlineMixin`, `BlockClusterOutlineMixin`, `ItemOutlineMixin`,
  `LineOutlineMixin`, `ValueBoxMixin` — outlines drawn with
  `pos - camera` where `pos` is a plot coordinate (~2e7).
- `GhostBlockRendererMixin`, `TransparentGhostBlockRendererMixin` — placement
  previews drawn in plot space.
- `ChainConveyorRendererMixin` — far/near LOD decided with a world camera and a
  plot-space chain midpoint.
- `AllHandleMixin` — server reach check comparing plot block position with the
  player's world position, which rejects every Create BE configuration.
- `SuperGlueSelectionHandlerMixin` — selection/outline handling.
- `LinkBlockEntityRendererMixin`, `RedstoneLinkNetworkHandlerMixin` — plot-space
  links and rendering.

These are **out of scope for Crythst v1**. Crythst structures are not a separate
plot dimension; their blocks and entities live at normal world coordinates, so
camera subtraction, reach checks and ghost previews already use one coordinate
space. What is conceptually relevant:

- contraption/tick state must stay server-authoritative while rendering is
  client-side;
- Create keeps rotation in its own fields, so only the public position path is
  safe to drive;
- block actors, mounted storage and interaction are driven by Create's own
  entity tick and block entities, not by the physics structure.

## What remains (deliberately deferred)

- **Contraption block actors and movement behaviours**: dispensers, drills,
  harvesters, rollers, ploughs, seats, controls, etc. still run inside Create's
  `tickActors()` against the contraption's own transform. They are not remapped
  onto the physics structure.
- **Mounted storage**: `MountedItemStorageWrapper`, fuel items, mounted
  inventories, `tickStorage` continue to belong to Create's entity tick.
- **Client rendering integration**: contraption rendering, outlines,
  super-glue selection, filter outlines and ghost previews were not touched.
  Sable's plot-space mixins are not needed for world-space structures, but a
  custom contraption-structure renderer may be desirable later.
- **Contraption collision**: the bridge does not resolve contraption-vs-
  structure collision or apply reaction impulses. That belongs to the entity
  riding/collision manager.
- **Active controller hand-off**: bearing/piston/gantry controllers are not
  detached or re-parented. Anchoring such a contraption can fight its block
  entity.
- **Persistence/sync of anchors**: the registry is in-memory. Persist
  `entityId + structureId + localTransform` in the riding manager if anchors
  must survive a restart or chunk unload.

## Registration

Add one line to the common initialiser (the file is owned by another task, so
this is intentionally not edited here):

```java
// io.mikaple.crythst.integration.fabric.CrythstFabric#onInitialize()
CreateCompat.register(); // no-op and returns false when Create is absent
```

`CreateCompat.register()`:

- logs once (`enabled` with detected base classes, or `debug` when absent);
- calls `CreateStructureBridge.registerTicker()` only when Create is present;
- never throws and never loads a Create class.

The bridge also lazily registers the same ticker on the first `attach`, so a
missed registration line does not leave anchors stale.

## Verification

```bash
# Builds with no Create on the compile classpath.
./gradlew compileJava compileTestJava --offline

# 13 pure tests (6 detection + 7 transform/fallback), no runClient.
./gradlew test --tests 'io.mikaple.crythst.integration.create.*' --offline
```

The tests assert that:

- `isLoaded()` is `false` in the Create-free test classpath;
- known `com.zurrtum.create` / `com.simibubi.create` names are recognised and
  unrelated names are not;
- all helpers return empty/false for `null`, and `register()` is a safe no-op;
- local↔world transforms round-trip with translation and rotation;
- entity yaw converts to a JOML rotation and back;
- an empty registry, `null` levels and `null` entities never throw.
