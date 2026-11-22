# Create Aeronautics × Crythst API gap analysis

Date: 2026-11-22

> **API status update (2026-11-22):** the native API now includes structure
> containers/observers, structure level views with block/entity/metadata/chunk
> helpers, world-space assembly, block-entity actors and force groups, force
> totals, per-cell mass tracking, fixed/free/rotary/prismatic constraints,
> loose boxes, impulse ropes, host-chunk loading tickets, persistent tracking
> points and a lift/propeller actor hook. The remaining architectural blockers
> are unchanged: Create Aeronautics' `SubLevel`-as-a-`Level`/plot model, Sable
> binary shims, full 6-DOF constraints, constraint persistence, block-entity
> render dispatch and render-region compatibility. See
> [`docs/api.md`](docs/api.md) for the current API and this file for the
> remaining gap matrix.

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
- Native API additions: structure level views with entities/metadata/chunks,
  force totals, per-cell mass tracker, fixed/free/rotary/prismatic constraints,
  loose box bodies, substep impulse ropes, host-chunk loading tickets,
  persistent tracking points and lift/propeller actors.

What it does **not** have:

- A public `SubLevel`-like object that behaves like a `Level` over a structure's blocks.
  `StructureLevelView` covers blocks, fluids, block entities, writes, entities,
  particles, sounds, registry, dimension/game time, random source and
  non-loading chunk queries, but it is not a `Level` subclass.
- Plot/plot-yard allocation, plot-local coordinates, plot chunk holders.
- Assembly semantics that move blocks into a plot/sub-level; current assembly is
  world-space only.
- A true full 6-DOF constraint with independent per-axis limits/motors.
  Fixed/free/rotary/prismatic joints exist, but constraint persistence across
  reloads is not implemented.
- Full rope/box parity with Sable: loose box bodies and a minimal impulse rope
  exist; rope solver constraints, rope collision and box-specific gameplay
  helpers do not.
- Block-entity render dispatch and full Create renderer compatibility.
- Water occlusion, heat maps, sub-level storage.
- A client network container that returns `PhysicsStructure`; it returns immutable
  snapshots. Singleplayer exposes live render structures, multiplayer exposes
  snapshot-native `snapshotContaining` / `snapshotsIntersecting` queries.
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
| Sub-level as a level | `SubLevel` used as `Level`: `getBlockState`, `setBlockAndUpdate`, `getBlockEntity`, `addFreshEntity`, `addParticle`, `playSound`, `getChunkSource`, `registryAccess`, `dimension`, `getGameTime` | `StructureLevelView` now covers blocks/fluids/BEs/writes/entities/particles/sounds/dimension/game time/random/non-loading chunk queries; not a `Level` subclass | plot-local `Level`, chunk holders, lighting, tick lists and pathfinding remain deferred | **P0 (partial)** |
| Containers/lifecycle | `SubLevelContainer.getContainer(level)`, `ServerSubLevelContainer`, `ClientSubLevelContainer`, observers, container-ready events | API v2 `StructureContainer`/`ServerStructureContainer`, observers, ready events and `StructureArgumentType`; client network container returns snapshots | Plot/sub-level lifecycle and client `PhysicsStructure` containers | **P0/P1** |
| Assembly/disassembly | `SubLevelAssemblyHelper.assembleBlocks`, `gatherConnectedBlocks`, `moveBlocks`, `moveTrackingPoints`, `AssemblyTransform` | API v2 world-space `StructureGather`/`StructureAssembly`/`AssemblyTransform`; captures BE NBT, leaves entities in world | plot-center/sub-level assembly and entity transfer | **P0/P1** |
| Entity/world queries | `Sable.HELPER.getContaining`, `getContainingClient`, `getTrackingSubLevel`, `projectOutOfSubLevel`, `distanceSquaredWithSubLevels`, `getEyePositionInterpolated`, `getVelocity` | API v2 `EntityStructureUtil` adds `projectOutOfStructure`, `distanceSquaredWithStructures`, interpolated eye position; `EntityStructureTracking` covers client tracking/inherited velocity | Sable-named adapters and client `PhysicsStructure` lookup | **P1** |
| Physics handles | `RigidBodyHandle` velocities, impulses, mass | `RigidBodyHandle`/`PhysicsPipeline` v1 plus per-cell `StructureMassTracker` and force totals | covered; loose-body inertia still zero by design | **P1** |
| Force groups | `ForceGroup`, `QueuedForceGroup`, `applyAndRecordPointForce`, `ForceGroups`, `ForceTotal` | `ForceGroup`/`QueuedForceGroup` with N and N·m, per-substep conversion, `ForceTotals` per body/group and status text; actor discovery needs auxiliary level | public force-group renderer/debug panel | **P2** |
| Constraints | fixed/free/rotary/generic configurations and handles; `addConstraint/removeConstraint` | fixed/free/rotary/prismatic configs and handles, pipeline/structure add/remove, world-space frames, hinge motor control | true full 6-DOF and persistence | **P1 (partial)** |
| Ropes/box objects | rope/box physics objects and handles | `StructureBoxObject` loose box bodies; `StructureRope` substep impulse rope with stiffness/damping/max tension | solver rope, rope collision/self-collision | **P2 (partial)** |
| Block hooks/actors | `BlockEntitySubLevelActor`, `BlockSubLevelAssemblyListener`, `BlockSubLevelLiftProvider`, `BlockSubLevelCollisionShape`, propellers | `BlockEntityStructureActor`, shape/assembly/change hooks, `BlockStructureLiftProvider`, `BlockStructurePropeller` wired through the actor force groups | broader Sable actor parity | **P2** |
| Mass/COM | `getMassTracker().getMass/getCenterOfMass`, per-block mass | `MassData`/`RigidBodyHandle`, `StructureMassTracker` per-cell/total/COM | covered; custom fractional colliders can make cell sum differ from collider total | **P1** |
| Client render | `SubLevel#renderPose`, `getRenderData`, custom BE renderers, render regions | `StructureRenderView`/`StructureRenderData`/`StructureRenderPose`/`StructurePoseProvider`; own batched renderer; client snapshot containment/intersection queries | block-entity render dispatcher integration | **P2** |
| Selection/clip extensions | `ClipContextExtension` (ignored sub-level, ignore main level), `LevelPoseProviderExtension` | API v2 `StructureClipExtension` (thread-local ignore) and `StructurePickExtension`; client pose provider | Sable-named interface adapters | **P2** |
| Movement extensions | `EntityMovementExtension` tracking sub-level/inherited velocity; `LivingEntityMovementExtension` | API v2 `EntityStructureTracking` exposes tracking structure and inherited velocity client-side | Sable extension interfaces and authoritative client containers | **P2** |
| Persistence/storage | `SubLevelData`, holding sub-levels, `GlobalSavedSubLevelPointer` | structure persistence exists, different model | plot/sub-level storage compatibility or adapter | **P2** |
| Tickets/tracking points | `SubLevelLoadingTicket`, `SubLevelTrackingPointSavedData`, `TrackingPoint` | host-chunk reference-counted `StructureLoadingTicket`; `StructureTrackingPoints` SavedData with local/world positions, stable-identity rebinding and optional keep-loaded tickets | Sable plot-region ticket semantics (native tickets pin host chunks) | **P2 (partial)** |
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

- **Implemented:** `StructureLevelView` with local block/fluid/BE read/write,
  exact shapes, particles, sounds, registry, dimension/game time, random source,
  entities and non-loading chunk queries. The void-level remapping stays hidden.
- **Implemented:** per-dimension `StructureContainer` (server + client),
  position/id lookup, lifecycle operations, observers, ready events, assembly
  argument and client interpolation/snapshots.
- **Implemented:** world-space `StructureAssembly`/`StructureGather`,
  block-entity capture and `AssemblyTransform`.
- **Deferred:** a `Level`-subclass/plot-local facade, plot chunk holders,
  lighting/tick-list/pathfinding integration and host↔structure neighbour graph.

### Stage B — physics parity (P1)

- **Implemented:** fixed/free/rotary/prismatic constraints, handles and
  add/remove; force totals and per-cell mass tracking; lift providers and
  propeller actors; loose boxes; minimal impulse ropes.
- **Still deferred:** a true full 6-DOF configuration with independent
  per-axis limits/motors, constraint persistence, solver ropes and broader Sable
  actor parity.
- Tracking structure and inherited velocity are exposed by `EntityStructureTracking`.

### Stage C — client/render + compatibility (P2)

- `StructureRenderData`/pose API exists; render-region integration remains.
- Block-entity render dispatch for structure BEs.
- Clip-context extension and client pose-provider extension.
- Create/Sable renderer compatibility bridge (if porting CA).
- **Implemented:** host-chunk loading tickets and persistent tracking points;
  a full plot/sub-level persistence adapter remains deferred.

### Stage D — optional systems (P3)

- Water occlusion, heat maps, ropes/box objects, schematic context, command argument
  and platform event bridge.

---

## 6. What is actually needed for the two known consumers

- **EnderTech**: block-entity actor callbacks, queued force groups, handle
  velocities/mass, pose/level/mass access, `atCenterOf` and now force totals are
  covered.
- **Create Aeronautics**: the native API now covers the semantic operations
  (level view, container, assembly, constraints, lift actors, tracking points),
  but CA code still expects `SubLevel` to *be* a `Level` with a plot. Porting CA
  to `StructureLevelView` and the native container is the realistic path. Do not
  claim a drop-in Sable replacement while the plot/chunk/render layers and
  binary shims are absent.

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
