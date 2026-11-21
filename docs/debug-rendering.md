# Debug rendering (F3 + F6)

Crythst no longer draws physics wireframes unconditionally and no longer uses
`/crythst debug` as the master overlay switch. The overlays are normal vanilla
debug entries, configured in **F3 + F6 → Debug Renderers**.

## Entries and defaults

`DebugScreenEntriesMixin` registers the entries in `DebugScreenEntries`. The
`crythst:physics*` entries are renderer-category entries with no default
profile, so vanilla reports them as `NEVER` until the player enables them.
`crythst:structure_target` is added to every profile as `IN_OVERLAY`.

| Entry | Category | Default | What it draws |
| --- | --- | --- | --- |
| `crythst:physics` | renderer | `NEVER` | Master switch: enables every layer below. |
| `crythst:physics_colliders` | renderer | `NEVER` | Every merged collider `VoxelBox` of every structure, coloured by material, plus custom fractional collider parts. |
| `crythst:physics_bounds` | renderer | `NEVER` | The structure's oriented bounding box, its world AABB, and a `crythst #id` label with `active`/`sleeping`. |
| `crythst:physics_dynamics` | renderer | `NEVER` | Structure origin, centre of mass, the origin→COM line, and the structure-local forward/up/right axes. |
| `crythst:physics_contacts` | renderer | `NEVER` | Physics origin, gravity/up arrows, and contact points/normals from the debug snapshot. |
| `crythst:physics_bodies` | renderer | `NEVER` | Filled OBBs for loose dynamic/kinematic bodies that are not structures, terrain or static. |
| `crythst:structure_target` | screen text | `IN_OVERLAY` | F3 text for the structure voxel under the crosshair. |

`crythst:physics` is strictly a master switch. Individual layers can also be
enabled on their own; enabling both is idempotent because each layer is only
emitted once per frame.

## What `structure_target` shows

When `Minecraft.hitResult` is a `StructureBlockHitResult`, the text entry adds:

```text
Crythst structure body #<body id>
Structure voxel: <x>, <y>, <z>          (structure-local)
Structure block: <BlockState>
Structure pose: (<x>, <y>, <z>) scale <blockScale>
World hit: (<x>, <y>, <z>) face <world face>
```

It does not draw geometry and works in multiplayer whenever the client has a
synced structure hit.

## What each physics layer draws

- **Colliders.** `PhysicsGizmoRenderer.emitColliders` walks
  `StructureRenderState.collider().boxes()` and draws each merged box in the
  structure's interpolated pose. It also draws `customParts()` in yellow, which
  are the exact Bullet compound children for blocks with fractional collision
  shapes.
- **Bounds.** `emitBounds` draws the outer OBB (cyan edge/fill), the world-space
  AABB (teal), and a billboard label. The label suffix comes from the matching
  `BodyDebugInfo` in the debug snapshot.
- **Dynamics.** `emitDynamics` draws the structure origin, the centre of mass,
  and three local axes. The axis length is derived from the collider height, so
  small structures remain readable.
- **Contacts.** `emitContacts` draws the physics origin, world `up` and gravity
  directions, then every `ContactPoint` and its normal from
  `PhysicsDebugSnapshot#contacts()`.
- **Bodies.** `LooseBodyRenderer.emit` draws OBBs from the debug snapshot for
  bodies that are not structures, not terrain and not static. `/crythst cube`
  is the usual way to create one.

All structure layers sample `StructureRenderState` through
`PhysicsStructure#renderStateAt(frameNanos)`, i.e. the same timestamped
`PoseTimeline` as normal structure rendering and selection.

## Data source and limitations

`CrythstPhysicsDebugRenderer` is added to vanilla's active renderer list only
while `crythst:physics` is enabled. It reads `ClientPhysicsView`, which is fed
from `LevelPhysics.renderStructures()` and
`LevelPhysics.renderDebugSnapshot()` once per client tick.

- **Singleplayer only for the physics layers.** `ClientPhysicsView` requires an
  integrated server. A dedicated multiplayer client has no local `LevelPhysics`,
  so the `crythst:physics*` layers draw nothing. Structure rendering, selection
  and entity collision still work from `ClientStructureCache`; the debug
  overlays do not.
- **Contacts are usually empty.** The current integration always publishes the
  debug snapshot, but it does not call
  `PhysicsWorld#setDebugContactCapture(true)` automatically. `contacts` is
  therefore empty unless a caller explicitly enables capture; the contacts
  layer still shows the origin and gravity/up axes.
- **`/crythst debug` is a legacy switch.** It flips `CrythstConfig#debug` at
  runtime (without saving the config), but the F3 + F6 entries ignore that field
  and the snapshot is published regardless. Use F3 + F6 to control the overlay.
- **No per-block state rendering in debug layers.** Colliders show merged
  physics boxes, not individual block models. Use the normal structure renderer
  for the visual block grid.
- **Debug layers are read-only.** They never mutate a body, pose or collider.

## F3 + B player hitboxes

Vanilla's `EntityHitboxDebugRenderer` always draws an axis-aligned box. For a
player with directional gravity that box is wrong: the collision shape is an
OBB whose local `+Y` follows `PlayerGravity.up(player)`.

`EntityHitboxDebugRendererMixin` cancels `showHitboxes` for those players, and
`PlayerObbDebugRenderer` (registered while `ENTITY_HITBOXES` is enabled) draws
the same OBB the collision pass uses. Its feet anchor is the entity position,
so its centre is `position + up * (bbHeight / 2)`, and it is shifted by the
entity's render interpolation so it coincides with the rendered avatar. The
camera, avatar and this OBB all consume the same feet frame and
`PlayerGravity.viewRotation` / `up`; see
[player-gravity-camera.md](player-gravity-camera.md).

## Related diagnostics

`/crythst timing`, `/crythst align` and `/crythst entitydebug` are command-line
diagnostics and do not require any debug entry to be enabled. See
[physics-timing.md](physics-timing.md) and
[entity-physics-collision.md](entity-physics-collision.md).
