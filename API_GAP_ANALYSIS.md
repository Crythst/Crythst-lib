# Create Aeronautics × Crythst API gap analysis

Date: 2026-11-22

> **API status update:** native API v2 has since landed structure containers and
> observers, client render views/pose providers/clip-pick extensions, world-space
> assembly/disassembly, block-entity actors and queued force groups, and
> `StructureMath`. The remaining blockers below are still accurate: a
> `SubLevel`/plot-like level view, constraints/ropes, tickets/tracking points and
> render/BE compatibility. See [`docs/api.md`](docs/api.md) for the current API.

Scope: analysis of `~/IdeaProjects/create-aeronautics-fabric` (Create Aeronautics,
Offroad, Simulated) against the current Crythst native API and internals. The goal is
to answer one question:

> Can Crythst's API replace Sable for Create Aeronautics by adding API surface?

Short answer: **not by API additions alone.** Create Aeronautics uses both Sable's
public API and its internal sub-level/plot architecture. A full replacement needs a
sub-level world model, not just physics handles. The details and a staged plan follow.

---

## 1. Usage scale

- **193 Java files** import `dev.ryanhcode.sable.*`.
- The largest surfaces are the world/sub-level model, not the physics handles:

| Import | Files / imports |
|---|---:|
| `dev.ryanhcode.sable.Sable` | 94 |
| `dev.ryanhcode.sable.sublevel.SubLevel` | 71 |
| `dev.ryanhcode.sable.companion.math.JOMLConversion` | 52 |
| `dev.ryanhcode.sable.api.SubLevelHelper` | 36 |
| `dev.ryanhcode.sable.sublevel.ServerSubLevel` | 32 |
| `dev.ryanhcode.sable.api.sublevel.SubLevelContainer` | 24 |
| `dev.ryanhcode.sable.sublevel.ClientSubLevel` | 23 |
| `dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem` | 20 |
| `dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer` | 16 |
| `dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle` | 15 |
| `dev.ryanhcode.sable.api.physics.constraint.*` | 8 import kinds |
| `dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor` | 9 |
| `dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener` | 9 |
| `dev.ryanhcode.sable.api.physics.force.*` | 4 import kinds |
| `dev.ryanhcode.sable.sublevel.plot.LevelPlot` | 6 |

Call-site frequency confirms the same picture:

- `Sable.HELPER.getContaining(...)` — 107 calls
- `Sable.HELPER.projectOutOfSubLevel(...)` — 32
- `Sable.HELPER.distanceSquaredWithSubLevels(...)` — 27
- `SubLevel#logicalPose()` — 101
- `SubLevel#getBlockEntity/getBlockState/getRandom/setBlockAndUpdate/...` — hundreds
- `SubLevel#getPlot()` — 24
- `pipeline.addConstraint(...)` — used by docking connectors, handles, bearings, ropes
- `SubLevel#renderPose()` — 26
- `SubLevel#getRenderData()` — client rendering

This is not a "physics handle plus a few hooks" integration. Create Aeronautics treats
a Sable sub-level as a **second `Level`** with its own block grid, plot, block entities,
entities, chunk/light behavior, render data and network state.

---

## 2. What Crythst has today

Implemented and stable enough to build on:

- `PhysicsStructure` + `LevelPhysics`: world-space OBB rigid bodies with voxel colliders.
- Voxel collision, terrain collision, OBB SAT, ray/sweep, characters.
- Auxiliary void `ServerLevel` (`EmptyPhysicsLevel`) that ticks copied structure blocks
  and block entities in remapped regions.
- Batched structure rendering, per-structure render poses and block-state rendering
  (block-entity render dispatch is still missing).
- Structure persistence and multiplayer sync.
- Movement-integrated entity collision and `ClientLevel.clip` structure selection.
- Native API v1:
  - `RigidBodyHandle`, `PhysicsPipeline`, `MassData`
  - block hooks: collision shape, custom COM, dynamic collider, assembly/change
    listeners, physics properties
  - `EntityStructureUtil` (containing, local projection, velocity)
  - `CrythstEvents` pre/post physics tick, container-ready
  - `CrythstApi` facade.
- API v2: block-entity physics actors and queued force groups (needed by EnderTech).
- API v2: per-level server/client structure containers, observers and ready events.
- API v2: world-space gather/assemble/disassemble and `AssemblyTransform`.
- API v2: client render views, pose providers, clip/pick extensions and tracking.
- API v2: `StructureMath`.

What it does **not** have:

- A public `SubLevel`-like object that behaves like a `Level` over a structure's blocks.
- Plot/plot-yard allocation, plot-local coordinates, plot chunk holders.
- Assembly semantics that move blocks into a plot/sub-level; current assembly is
  world-space only.
- Physics constraints, ropes, box physics objects, force totals.
- Block-entity render dispatch and full Create renderer compatibility.
- Loading tickets, tracking points, water occlusion, heat maps, sub-level storage.
- A client network container that returns `PhysicsStructure`; it returns immutable
  snapshots.
- Mixin-facing extension interfaces used by downstream mods
  (`ClipContextExtension`, `LevelPoseProviderExtension`, `EntityMovementExtension`,
  `SubLevelContainerHolder`, …), although equivalent native operations exist for
  clip, tracking and pose sampling.

---

## 3. Gap matrix

Legend: **P0** = required before a meaningful CA port; **P1** = required for most
gameplay features; **P2** = integration/polish; **P3** = optional.

| Area | Create Aeronautics usage | Crythst now | Gap | Priority |
|---|---|---|---|---|
| Sub-level as a level | `SubLevel` used as `Level`: `getBlockState`, `setBlockAndUpdate`, `getBlockEntity`, `addFreshEntity`, `addParticle`, `playSound`, `getChunkSource`, `registryAccess`, `dimension`, `getGameTime` | `EmptyPhysicsLevel` exists internally but is not exposed as a per-structure `Level` view; coordinates are remapped to void regions | **StructureLevelView** with world↔local/plot coordinate projection, block/fluid/BE/entity access, sound/particles, chunk/registry access | **P0** |
| Containers/lifecycle | `SubLevelContainer.getContainer(level)`, `ServerSubLevelContainer`, `ClientSubLevelContainer`, observers, container-ready events | API v2 `StructureContainer`/`ServerStructureContainer`, observers, ready events and `StructureArgumentType`; client network container returns snapshots | Plot/sub-level lifecycle and client `PhysicsStructure` containers | **P0/P1** |
| Assembly/disassembly | `SubLevelAssemblyHelper.assembleBlocks`, `gatherConnectedBlocks`, `moveBlocks`, `moveTrackingPoints`, `AssemblyTransform` | API v2 world-space `StructureGather`/`StructureAssembly`/`AssemblyTransform`; captures BE NBT, leaves entities in world | plot-center/sub-level assembly and entity transfer | **P0/P1** |
| Entity/world queries | `Sable.HELPER.getContaining`, `getContainingClient`, `getTrackingSubLevel`, `projectOutOfSubLevel`, `distanceSquaredWithSubLevels`, `getEyePositionInterpolated`, `getVelocity` | API v2 `EntityStructureUtil` adds `projectOutOfStructure`, `distanceSquaredWithStructures`, interpolated eye position; `EntityStructureTracking` covers client tracking/inherited velocity | Sable-named adapters and client `PhysicsStructure` lookup | **P1** |
| Physics handles | `RigidBodyHandle` velocities, impulses, mass | `RigidBodyHandle`/`PhysicsPipeline` v1 | mostly covered; verify force/torque units and per-body handles | **P1** |
| Force groups | `ForceGroup`, `QueuedForceGroup`, `applyAndRecordPointForce`, `ForceGroups`, `ForceTotal` | API v2 `ForceGroup`/`QueuedForceGroup` with N and N·m, per-substep conversion; actor discovery needs auxiliary level | force totals and debug UI | **P1/P2** |
| Constraints | fixed/free/rotary/generic configurations and handles; `addConstraint/removeConstraint` | none | Bullet 6-DOF/fixed/rotary adapters, persistence and handles | **P1** |
| Ropes/box objects | rope/box physics objects and handles | none | rope/box bodies and handles | **P2** |
| Block hooks/actors | `BlockEntitySubLevelActor`, `BlockSubLevelAssemblyListener`, `BlockSubLevelLiftProvider`, `BlockSubLevelCollisionShape`, propellers | API v2 `BlockEntityStructureActor`, shape/assembly/change hooks | lift provider, propeller interface, broader actor parity | **P1/P2** |
| Mass/COM | `getMassTracker().getMass/getCenterOfMass`, per-block mass | `MassData`/`RigidBodyHandle` | merged mass tracker and per-cell APIs | **P1** |
| Client render | `SubLevel#renderPose`, `getRenderData`, custom BE renderers, render regions | API v2 `StructureRenderView`/`StructureRenderData`/`StructureRenderPose`/`StructurePoseProvider`; own batched renderer | block-entity render dispatcher integration | **P2** |
| Selection/clip extensions | `ClipContextExtension` (ignored sub-level, ignore main level), `LevelPoseProviderExtension` | API v2 `StructureClipExtension` (thread-local ignore) and `StructurePickExtension`; client pose provider | Sable-named interface adapters | **P2** |
| Movement extensions | `EntityMovementExtension` tracking sub-level/inherited velocity; `LivingEntityMovementExtension` | API v2 `EntityStructureTracking` exposes tracking structure and inherited velocity client-side | Sable extension interfaces and authoritative client containers | **P2** |
| Persistence/storage | `SubLevelData`, holding sub-levels, `GlobalSavedSubLevelPointer` | structure persistence exists, different model | plot/sub-level storage compatibility or adapter | **P2** |
| Tickets/tracking points | `SubLevelLoadingTicket`, `SubLevelTrackingPointSavedData`, `TrackingPoint` | none | loading tickets + world tracking points | **P2** |
| Water/heat | `WaterOcclusionContainer`, `SubLevelHeatMapManager` | none | optional systems | **P3** |
| Platform/commands | `SableEventPlatform`, `SubLevelArgumentType`, command helpers | API v2 `StructureContainerEvents`, `CrythstEvents`, `StructureArgumentType` | Sable platform bridge and broader command helpers | **P2** |
| Internals used by CA | `LevelPlot`, `PlotChunkHolder`, `ServerLevelPlot`, render dispatcher, mixin interfaces | different architecture | cannot be covered by a clean native API; CA needs porting or an emulation layer | **blocking for drop-in** |

---

## 4. The core architectural mismatch

Sable's model: every sub-level owns a **plot** in a distant plot-yard. Blocks live in
plot coordinates. `SubLevel` exposes a level-like view, `Sable.HELPER.getContaining`
maps a world position to the sub-level whose pose covers it, and rendering/collision
project the plot pose into the world.

Crythst's model: a structure's blocks live in a **world-space OBB**; the auxiliary void
level stores a remapped copy for block ticking, and rendering/collision use the
structure pose directly. This is simpler and was chosen deliberately, but it means:

- There is no plot-local `Level` to hand to Create Aeronautics code.
- `getBlockEntity`/`setBlock` on a `SubLevel` in CA code cannot be served by the void
  level copy without a coordinate/projection layer.
- Assembly/disassembly semantics differ: Crythst edits a world-space volume; CA expects
  "move blocks from world to plot" and "plot center block".
- `LevelPlot`/`PlotChunkHolder`-based code cannot be emulated without a plot abstraction.

A clean native API can cover the **semantic operations** (assemble, query, force,
constraint), but CA's code must be ported to that API. A binary drop-in replacement for
unmodified CA would require `dev.ryanhcode.sable.*` shims **including internal plot/render
classes**, which is effectively reimplementing Sable and conflicts with the project's
independent-implementation policy.

---

## 5. Recommended staged plan

### Stage A — structure level view + container (P0, largest)

- `StructureLevelView`/`StructureBlockAccess`: implement read/write block, fluid, block
  entity, particle, sound, registry, chunk access at structure-local coordinates, with
  an explicit world↔local transform. Back it with the existing void level but hide the
  remapping.
- `StructureContainer` per dimension: lookup by position/id, allocate/remove, server and
  client variants, observers, ready event carrying the container.
- `StructureAssembly`: gather connected blocks (26-neighbour), assemble from world,
  disassemble back, preserve pose/velocity/BE/entities, expose an assembly transform.
- Client container + interpolation state.

### Stage B — physics parity (P1)

- Constraints: fixed/free/rotary/generic configurations, handles, add/remove.
- Force totals/debug UI (force groups themselves are v2) and per-body mass tracker.
- Lift providers, propellers and broader block-entity actor parity.
- Tracking structure and inherited velocity are exposed by `EntityStructureTracking`.

### Stage C — client/render + compatibility (P2)

- `StructureRenderData`/pose API exists; render-region integration remains.
- Block-entity render dispatch for structure BEs.
- Clip-context extension and client pose-provider extension.
- Create/Sable renderer compatibility bridge (if porting CA).
- Loading tickets, tracking points, persistence adapter.

### Stage D — optional systems (P3)

- Water occlusion, heat maps, ropes/box objects, schematic context, command argument
  and platform event bridge.

---

## 6. What is actually needed for the two known consumers

- **EnderTech**: only block-entity actor callbacks, queued force groups, handle
  velocities/mass, pose/level/mass access, `atCenterOf`. The in-progress API v2 covers
  all of it.
- **Create Aeronautics**: everything in Stage A + B + C; Stage A is the blocker. Do not
  claim "full Sable replacement" until a structure level view, container, assembly and
  constraints exist.

---

## 7. Effort / risk

- Stage A: large (weeks to months), touches block storage, assembly, persistence,
  entities, client sync and rendering.
- Stage B: medium; constraints are the main new physics work.
- Stage C: medium-large; render/network compatibility is the risky part.
- Drop-in binary compatibility with unmodified CA: very high risk and license-sensitive;
  not recommended under the current "native API only" decision.

The realistic target is: **Crythst-native port of Create Aeronautics / EnderTech**, with
a stable API that covers the same operations. It is not achievable by adding a few
methods to the existing `RigidBodyHandle` API.
