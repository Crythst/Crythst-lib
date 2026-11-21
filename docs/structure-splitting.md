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

Components themselves are returned in the same deterministic scan order. Diagonal
or corner contact does **not** connect two cells; only the six axis-aligned
neighbours do.

The flood fill is limited to `StructureSplitter.MAX_CELLS` (the default voxel
collider limit, currently 4,000,000 cells including air). Larger volumes throw an
`IllegalArgumentException` instead of scanning an unbounded grid.

## Wiring

No file outside the split package is required, but the splitter only runs when it
is called. The cleanest hook is `LevelPhysics.refreshStructure`, which every
volume change already passes through.

Replace the body of `LevelPhysics.refreshStructure(PhysicsStructure, VoxelVolume)`
with this shape (keep the existing auxiliary-level refresh, but do not return
early just because `subLevel` is disabled):

```java
public void refreshStructure(PhysicsStructure structure, VoxelVolume volume) {
    if (closed) {
        return;
    }
    if (subLevel != null) {
        try {
            subLevel.removeStructure(structure);
            subLevel.addStructure(structure, volume);
        } catch (Throwable t) {
            Crythst.LOGGER.warn("Could not refresh structure body #{} in the auxiliary void level",
                    structure.body().id(), t);
        }
    }
    // NEW: remove an empty/zero-mass body, then split disconnected islands.
    StructureSplitter.splitIfNeeded(this, structure, volume);
}
```

`splitIfNeeded` first checks `isMassless`, so an explicit separate call to
`removeIfMassless` is optional there. If you prefer the removal check before the
auxiliary refresh, use:

```java
if (StructureSplitter.removeIfMassless(this, structure, volume)) {
    return;
}
```

If the interaction layer should react to the returned bodies (for example to
apply the mining impulse to each split), call `splitIfNeeded` there after
`reindex`/`refreshStructure` and branch on the result. A massless check must run
before `PhysicsStructure.reindex`, which rejects empty volumes:

```java
// breakBlock(ServerPlayer, StructureBlockHitResult), after removing the voxel and before reindex:
if (StructureSplitter.removeIfMassless(physics, structure, volume)) {
    return new BreakResult(true, true, previousState);
}
structure.reindex(volume);
physics.refreshStructure(structure, volume);
```

In the interaction layer (`StructureInteraction.breakBlock` / `placeBlock`), the
usual sequence is already:

```java
structure.reindex(volume);
physics.refreshStructure(structure, volume);
```

With the hook above, no extra interaction change is required. The only caveat is
that after `splitIfNeeded` returns more than the original singleton, the passed
`structure` is closed; callers that apply a reaction impulse afterwards should
skip closed structures or apply the impulse to the returned splits:

```java
final List<PhysicsStructure> bodies =
        StructureSplitter.splitIfNeeded(physics, structure, volume);
if (bodies.size() == 1 && bodies.get(0) == structure) {
    // still one body: apply impulse normally
} else if (!bodies.isEmpty()) {
    // original was split or removed; target the returned bodies if needed
}
```

## What is preserved

For every component, `splitIfNeeded` builds a `MutableVoxelVolume` with the
**same bounds as the original** and copies:

* solid flags;
* per-cell `blockMass`;
* per-cell `PhysicsMaterial`;
* block states, preferring a `StatefulVoxelVolume` and falling back to
  `StructureBlockStates`;
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
* **Block states and the auxiliary void level.** New structures are created
  before their `StructureBlockStates` entries can be keyed by the new body, so
  the auxiliary ticking level copies stone for mutable split volumes. The render
  and interaction paths read the side table and see the correct states, matching
  the existing restore-path limitation.
* **Block-entity NBT.** NBT is split by cell ownership, but a block entity whose
  position is outside every solid component is dropped.
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
* empty and zero-mass volumes are reported massless.
