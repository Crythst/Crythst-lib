# Structure splitting

When a physics structure is broken into disconnected solid pieces, the remaining
blocks should become independent rigid bodies instead of one body whose collision
shape contains floating islands. The implementation lives in:

```
io.mikaple.crythst.integration.minecraft.structure.split.StructureSplitter
```

It is a server-thread utility. It does not register events or change any existing
package; the caller decides when to split and when to remove a massless body.

## Public API

```java
// Pure flood fill: deterministic, bounded, ignores air.
List<StructureSplitter.Component> findComponents(VoxelVolume volume);

// True when the volume has no solids or VoxelColliderBuilder reports mass <= 0.
boolean isMassless(VoxelVolume volume);

// Removes the structure (and its side-table entries) when it is massless.
boolean removeIfMassless(LevelPhysics physics, PhysicsStructure original, VoxelVolume volume);

// Splits into one body per component. Returns List.of(original) when at most one
// component exists, an empty list when the body was removed as massless, and the
// new structures otherwise.
List<PhysicsStructure> splitIfNeeded(LevelPhysics physics, PhysicsStructure original, VoxelVolume volume);
```

`Component` exposes:

* `bounds()` – tight component bounds (inclusive min, exclusive max);
* `cells()` – solid cells in deterministic Y, then Z, then X order;
* `boxes()` – greedy-merged `VoxelBox` list for diagnostics;
* `cellCount()`.

Components themselves are returned in the same deterministic scan order.
Connectivity is **26-neighbour**: face, edge and corner contact all keep two
solid cells in the same body. A body only splits when a component is separated
by at least one empty cell in every surrounding direction.

The flood fill is limited to `StructureSplitter.MAX_CELLS` (the default voxel
collider limit, currently 4,000,000 cells including air). Larger volumes throw an
`IllegalArgumentException` instead of scanning an unbounded grid.

## Wiring

The splitter is already wired into `LevelPhysics.refreshStructure`:

```java
public List<PhysicsStructure> refreshStructure(PhysicsStructure structure, VoxelVolume volume) {
    // ... refresh the auxiliary void level ...
    return StructureSplitter.splitIfNeeded(this, structure, volume);
}
```

For a non-empty remaining volume, every interaction edit calls
`structure.reindex(volume)` and then
`physics.refreshStructure(structure, volume)`. A last voxel / zero-mass volume
is detected before reindex (which rejects empty volumes) and removed with
`removeIfMassless`. The refresh therefore automatically:

* removes a body whose remaining total mass is zero;
* splits the remaining cells into one body per 26-neighbour component;
* returns the bodies that now represent the edited structure.

The return value matters when the original was split or removed. The original
`structure` is closed in that case, so callers must not keep using it:

```java
final List<PhysicsStructure> bodies =
        levelPhysics.refreshStructure(structure, volume);
if (bodies.size() == 1 && bodies.get(0) == structure) {
    // still one body
} else if (!bodies.isEmpty()) {
    // original was split; reaction impulse targets the nearest split
}
```

`StructureInteraction.breakBlock` already handles this: it captures the feedback
point before the edit, emits break feedback before the refresh, and applies the
mining impulse to the split body whose centre is nearest the hit point.
`removeIfMassless` and `splitIfNeeded` are public so other callers can use the
same policy explicitly.

## What is preserved

For every component, `splitIfNeeded` builds a `MutableVoxelVolume` with the
**same bounds as the original** and copies:

* solid flags;
* per-cell `blockMass`;
* per-cell `PhysicsMaterial`;
* all owned block states, including non-colliding logic blocks (wire, torches,
  repeaters, observers, fluids), carried through an internal
  `StatefulVoxelVolume` so the auxiliary ticking level sees them immediately;
* pending block/fluid ticks, assigned to the component that owns the tick
  position. Non-solid positions use the nearest component, so a repeater or
  fluid tick is never dropped merely because it has no collider cell;
* block-entity NBT from `StructureBlockEntityData`, filtered to the cells owned
  by that component.

Each `StructureDef` reuses:

* the live structure pose (`original.pose()`), so blocks do not jump;
* the live body type (dynamic, kinematic or static);
* the body material;
* `original.massScale()`;
* the block scale;
* CCD from the live body;
* the original's linear and angular velocity, gravity scale and gravity override.

The new `PhysicsStructure` recomputes the centre of mass and inertia from its own
collider, so an asymmetric split gets the correct COM automatically.

The new bodies are created before the original is removed. If any creation fails,
the partial splits are closed and the original is kept.

## Limitations

* **Same bounds, not tight bounds.** All split volumes keep the original bounds.
  This keeps voxel/world coordinates and the pose identical, at the cost of
  retaining the original grid dimensions in each split volume.
* **Collider options are partly reconstructed.** `PhysicsStructure` does not
  expose its `VoxelColliderOptions` or collision filter. The split uses
  `VoxelColliderOptions.DEFAULT.withBlockScale(original.blockScale())` and
  `CollisionFilter.DEFAULT`; damping is reset to zero. Greedy merge, merged
  materials and a custom filter are therefore not carried over exactly. The
  resulting collision surface and mass are still correct.
* **Block-entity NBT.** NBT is split by cell ownership; a block entity at a
  non-solid position (for example a sign or hanging block) follows the nearest
  component instead of being dropped.
* **Zero-mass cells.** `MutableVoxelVolume.blockMass` falls back to 1000 kg for
  non-positive stored mass. A whole body with zero mass is still detected and
  removed before copying, but an individual zero-mass cell inside a positive-mass
  body is copied as the mutable-volume default.
* **Determinism is per volume content.** Results do not depend on hash iteration,
  but callers must still run on the server thread; block states and side tables
  are synchronized maps, not a transaction.

## Tests

`src/test/java/io/mikaple/crythst/integration/minecraft/structure/split/StructureSplitterTest.java`
contains pure tests that do not start a Minecraft server:

* one full cube is one component;
* two separated islands become two components in deterministic order;
* diagonal and corner-only contact keeps blocks in one body;
* a connected L shape is one component with multiple merged boxes;
* air-only volumes produce no components;
* empty and zero-mass volumes are reported massless;
* ticks on both solid and non-solid logic cells go to the correct component,
  preserving delay/priority/order;
* unresolved (air) cells fall back to the nearest component deterministically.
