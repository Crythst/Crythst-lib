# Schematic context and force debug rendering

This document covers two additive APIs:

- `io.mikaple.crythst.api.schematic` — decode vanilla structure NBT or a
  Crythst volume into a `StructureDef`/`PhysicsStructure` without touching the
  host world.
- `crythst:physics_forces` — a bounded F3 + F6 debug layer for per-structure
  force totals and force groups.

## Schematic API

### Loading

```java
CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
Schematic schematic = Schematic.load(tag, level);          // Level is a HolderLookup.Provider
// or: Schematic.loadCompressed(path, level);
// or: Schematic.load(tag, registries.lookupOrThrow(Registries.BLOCK));

StructureDef definition = schematic.toStructureDef(
        SchematicOptions.defaults()
                .withPose(pose)
                .withBlockScale(0.5));

PhysicsStructure structure = schematic.instantiate(physics, options);
```

`Schematic.instantiate` calls `LevelPhysics.createStructure`, so the blocks
exist only in the physics volume and the auxiliary ticking level. The host
level is never modified into a plot/region. This is different from
`StructureAssembly`, which captures and removes real world blocks.

`SchematicOptions` controls:

| Field | Default | Meaning |
| --- | --- | --- |
| `pose` | identity | structure-local metres → world space |
| `blockScale` | `1.0` | metres per voxel |
| `type` | `DYNAMIC` | rigid-body type |
| `material` | default material | fallback surface material |
| `massScale` | `1.0` | collider mass multiplier |
| `linearDamping` / `angularDamping` | `0` | body damping |
| `ccd` | `false` | continuous collision detection |
| `attachBlockEntities` | `true` | reattach decoded block-entity NBT |

Convenience overloads exist for an identity pose, a pose alone, and a pose plus
block scale.

### Exposed data

`Schematic` exposes:

- `format()` — `VANILLA_STRUCTURE` or `CRYTHST_VOLUME`.
- `bounds()`, `sizeX()`, `sizeY()`, `sizeZ()`.
- `volume()` — a `SchematicVolume` implementing `StatefulVoxelVolume`.
- `palette()` — distinct block states in first-seen order.
- `nonAirBlockCount()` and `solidBlockCount()`.
- `blockStateAt(x, y, z)`.
- `blockEntities()` — defensive copy keyed by structure-local voxel relative to
  the volume minimum.
- `dataVersion()`.

`SchematicVolume` keeps solidity, per-cell mass, material and block state
separately. Non-solid stateful blocks such as redstone wire, torches, buttons
and fluids stay in the volume and are copied into the auxiliary ticking level,
but do not participate in the collider. Solid detection is the same rule used
for assembly: a full collision shape or a non-empty partial shape (stairs,
slabs, fences, ...).

Block-entity NBT is stored in `StructureBlockEntityData` and passed to
`LevelPhysics.applyBlockEntities` during instantiation. A chest, furnace,
hopper, sign or modded block entity therefore keeps its contents and state when
the auxiliary level is enabled.

### Supported formats

#### Vanilla structure NBT

Read fields:

```text
DataVersion      optional int
size             int[3]                 required
palette          list of block states   required unless palettes is present
palettes         list of palettes       first palette is used
blocks           list:
  pos            int[3]
  state          int palette index
  nbt            optional compound     preserved
entities         ignored
```

This is the format produced by vanilla structure blocks and most template
datasets. `Schematic.toTag()` writes the same shape after normalising the
volume to a zero-based bounding box, so a loaded schematic can be saved and read
again.

#### Crythst volume NBT

A tag containing both `Bounds` and `Solid` is decoded through
`SavedStructureVolume.fromTag`. The existing Crythst layout is reused:

```text
Bounds            int[6]
Solid             byte[]
MassPalette       list<double>        optional
MassIndex         int[]               optional
MaterialPalette   list<compound>      optional
MaterialIndex     int[]               optional
StatePalette      list<compound>      optional
StateIndex        int[]               optional
```

This is the format written by `SavedStructureVolume.toTag()`. It is useful for
small hand-authored/test volumes and for schematics already exported by
Crythst. It has no block-entity table; use vanilla structure NBT when block
entities matter.

### Limits

`SchematicLimits.DEFAULT` is:

| Limit | Value |
| --- | --- |
| each axis | 1024 |
| dense cells | 4,000,000 |
| non-air blocks | 1,000,000 |
| compressed NBT heap | 64 MiB |

`SchematicLimits.validateBounds` runs before the dense arrays are allocated, so
a malicious `size[3]` cannot force a huge allocation. The NBT quota bounds the
compressed reader as well. Pass a custom `SchematicLimits` to `load` or
`loadCompressed` to tighten or relax the limits.

### Unsupported / ignored input

- **Sponge schematic v2/v3** (`BlockData`, `Palette`, `Width`/`Height`/`Length`)
  is explicitly rejected with a `SchematicException`; it is not silently
  misread as vanilla structure NBT.
- **Entities** in vanilla `entities` tags are ignored. Crythst structures have
  no entity container; entity projection is a gameplay feature of the auxiliary
  level, not a schematic import feature.
- **Multiple palettes** in vanilla `palettes` are accepted but only the first
  palette is used.
- **DataFixerUpper migration** is not applied. A schematic from a very old
  Minecraft version with renamed blocks/states may fail to resolve its palette;
  convert it with vanilla tools first.
- **Structure processors, jigsaw metadata and loot tables** are not run. The
  raw palette states and raw block-entity NBT are preserved.
- The loader requires a block registry lookup (`HolderGetter<Block>`); there is
  no hard-coded block table.

## Force debug layer

### Enabling

The new entry is:

```text
F3 + F6 → Debug Renderers → crythst:physics_forces
```

It is a `RENDERER` entry with no profile assignment, so it defaults to `NEVER`.
The `crythst:physics` master entry also enables it. Individual Crythst layers
now add the shared debug renderer without requiring the master entry.

Singleplayer only, like the other `crythst:physics*` layers: the data comes from
the integrated server through `ClientPhysicsView`.

### What it draws

For each structure, starting at its world-space centre of mass:

- **Total force arrow** in warm yellow. Length is a bounded, monotonic function
  of `ForceTotals.forceMagnitude()`; 20 kN maps to half of the 3 m maximum, and
  the length never reaches the maximum.
- **Force-group arrows** in each `ForceGroup`'s colour. Groups start slightly
  offset vertically so coincident contributions remain visible. Up to eight
  groups per structure are drawn.
- **Billboard labels**:

  ```text
  crythst #<body id>  F <force>  |T| <torque>  m <mass>
  <group label>: F <force>  |T| <torque>
  ```

  Force uses `N`, `kN`, `MN`; torque uses `N·m`, `kN·m`, `MN·m`; mass uses
  `kg`, `t`, `kt`.

The values are world-space newtons and newton-metres, recorded before the
substep accumulator is converted to an impulse. Torque is shown as text only.
The structure's own mass is obtained with `StructureMassTracker` on the server
thread when the snapshot is published; the renderer never calls
`StructureMassTracker` itself.

### Bounds and data flow

`LevelPhysics` publishes at most 64 `ForceDebugSnapshot`s per tick. A snapshot
contains the body id, collider mass, structure origin, world centre of mass and
the latest immutable `ForceTotals`. The renderer further caps a frame at 64
structures, 256 arrows and 3 group labels per structure. There is no logging on
the render path and no contact-capture change.

The layer is read-only: it never mutates a body, pose, collider or force group.

## Tests

- `src/test/java/io/mikaple/crythst/api/schematic/SchematicTest.java` covers
  vanilla structure decoding, stateful non-solid blocks, block-entity
  preservation/defensive copies, vanilla NBT round-trip, pose/scale options,
  size/block limits, Crythst volume decoding and Sponge rejection.
- `src/test/java/io/mikaple/crythst/client/render/debug/ForceDebugOverlayTest.java`
  covers bounded/monotonic arrow scaling, direction-preserving vector scaling,
  force/torque/mass labels, group labels and immutable snapshot copies.

Run them with:

```text
./gradlew test --tests 'io.mikaple.crythst.api.schematic.*' \
    --tests 'io.mikaple.crythst.client.render.debug.*'
```

## Remaining limits

- No Sponge schematic import (v2/v3), no world-edit `.schem`.
- No DataFixerUpper migration for old `DataVersion`s.
- Only the first vanilla palette is used; structure processors and entity
  projection from `entities` are not applied.
- Block entities are reattached only when the auxiliary ticking level is
  available. With `subLevelEnabled = false`, the NBT remains in
  `StructureBlockEntityData` for a later save but the live block entity does not
  tick.
- The force layer is singleplayer-only because the client has no local physics
  world on dedicated servers. A network-synced force channel would be required
  to show it in multiplayer.
- Contacts still require explicit `PhysicsWorld#setDebugContactCapture(true)`;
  this change does not enable contact capture automatically.
