# Block mass and physics profiles

This document describes the data-driven block mass system:

- `io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsProfile`
- `io.mikaple.crythst.integration.minecraft.mass.BlockPhysicsRegistry`
- `io.mikaple.crythst.integration.minecraft.mass.BlockMassReload`
- `io.mikaple.crythst.integration.minecraft.BlockPhysicsMapper`
- `src/main/resources/data/crythst/block_physics.json`

## Units

| Field | Unit | Meaning |
| --- | --- | --- |
| `mass` | kg per block | Mass of one cubic metre. One Minecraft block is one metre on a side. |
| `friction` | coefficient | Coulomb friction; `0` is frictionless, `1` is high friction. Values above `1` are accepted by the parser but unusual. |
| `restitution` | coefficient, `0..1` | Bounce. `0` is inelastic, `1` is perfectly elastic. |
| `rollingFriction` | coefficient | Optional rolling resistance, `0` disables it. |
| `spinningFriction` | coefficient | Optional spinning resistance, `0` disables it. |
| `buoyancyScale` | multiplier | Optional multiplier for displaced volume in the buoyancy pass. `1.0` is normal. |

`BlockPhysicsProfile#toMaterial()` builds a `PhysicsMaterial` whose `density`
equals `mass` (`kg/m³`). Voxel structures pass an explicit per-block mass to
Bullet, so this density matters mainly for loose bodies that derive mass from
shape volume.

## Data pack file

Path:

```
data/crythst/block_physics.json
```

Example:

```json
{
  "defaults": { "mass": 1000, "friction": 0.7, "restitution": 0.0 },
  "blocks": {
    "minecraft:stone": { "mass": 2400, "friction": 0.7 },
    "minecraft:end_stone": { "mass": 500, "buoyancyScale": 1.0 },
    "#minecraft:planks": { "mass": 700, "friction": 0.6 }
  },
  "tags": {
    "minecraft:logs": { "mass": 800, "friction": 0.6 }
  }
}
```

- `defaults` is optional. Every field is optional and inherits from the
  `CrythstConfig` fallback. When `defaults` is present it replaces the config
  fallback for the whole data pack.
- `blocks` contains exact block ids. Any entry whose key starts with `#` is a
  tag entry and behaves exactly like an entry in `tags`.
- `tags` contains block tag ids. The leading `#` is optional.
- Every block/tag entry is sparse: omitted fields inherit from the effective
  defaults. For example, `{"friction": 0.4}` only changes friction.
- Unknown fields are ignored. Invalid field values (negative mass, a string
  where a number is expected, restitution outside `0..1`, ...) are logged and
  ignored individually, so the field falls back to its default. A non-object
  entry is skipped.
- `{}, [], null` or a missing file all mean "use config defaults".
- A structurally invalid document (`"blocks": []`, malformed JSON, ...) is
  rejected and the previous registry snapshot is kept.

The built-in file shipped by Crythst contains a small set of common blocks and
vanilla tags. Other mods and data packs can override it or add their own block
ids.

## Lookup precedence

For a block state, `BlockPhysicsRegistry` resolves in this order:

1. Exact block id entry (`minecraft:stone`).
2. Block tag entry. If several configured tags match, the first one in
   data-file order wins. Iteration is over the registry's configured order, not
   over the block's tag set, so the result is deterministic.
3. Data-pack `defaults`.
4. Config fallback from `CrythstConfig` (`defaultBlockMass`,
   `defaultFriction`, `defaultRestitution`, rolling/spinning `0`,
   `buoyancyScale 1`).

There is one built-in exception after these steps: when neither a block entry
nor a tag entry matches `minecraft:end_stone`, the mapper keeps the legacy
low-density fallback `CrythstConfig#endStoneBlockMass` (default `500 kg`) and
`PhysicsMaterial.STONE`. This preserves buoyant end stone even on servers with
no data pack. An explicit block or tag entry always wins.

When no data pack is present, the mapper also keeps the old data-free surface
heuristic (ice, wood/planks/logs, metal, dirt/grass/sand/gravel) and the legacy
material density `defaultBlockMass / 1000`.

## How structures consume the data

`BlockVoxelVolume` implements `VoxelVolume` and asks the mapper for every voxel:

- `blockMass(x, y, z)` → per-block mass in kg.
- `blockMaterial(x, y, z)` → `PhysicsMaterial` used for surface and rolling
  properties.

`VoxelColliderBuilder` greedily merges adjacent cells only when mass and
material match, then computes:

- total mass as the sum of merged-box masses;
- exact centre of mass weighted by mass;
- the full 3×3 inertia tensor, including parallel-axis terms, so asymmetric
  structures tumble correctly.

Thus changing `mass` changes both the total weight and rotational inertia.
Changing `friction`/`restitution` changes contact response. The buoyancy pass
reads the same collider geometry and body mass; `BlockPhysicsRegistry#profile`
exposes `buoyancyScale` for a buoyancy implementation that wants to multiply
the displaced volume per block.

Non-full blocks (slabs, stairs, fences, ...) are still one voxel cell with the
mass of their full block. The collider uses collision shape occupancy, not the
visual shape's fractional volume.

## Reload behaviour

Register the server-data listener once from the common initializer:

```java
import io.mikaple.crythst.integration.minecraft.mass.BlockMassReload;

BlockMassReload.register();
```

Only `PackType.SERVER_DATA` is registered, so a single-player client does not
start a second client-side resource reload. On every server data reload
(`/reload`, data-pack changes, world load) the listener:

1. reads `data/crythst/block_physics.json` from the server resource manager;
2. parses it into an immutable snapshot;
3. atomically publishes the snapshot through a volatile field and clears the
   per-block tag cache.

Readers never lock. A missing file calls `reset()`, which discards all data
entries and restores config defaults. A parse failure logs a warning and keeps
the previous snapshot. `BlockMassReload.reloadNow(ResourceManager)` is exposed
for integrations that already own the reload lifecycle.

Changing `CrythstConfig` values requires restarting or reloading the world:
`BlockPhysicsMapper.defaults(config)` publishes the new fallback and
re-resolves all stored data entries.

## Tests

Pure JVM tests live in
`src/test/java/io/mikaple/crythst/integration/minecraft/mass/`. They parse
sample JSON, check exact/tag/default precedence, invalid values, End Stone
fallback, and mass→material density without starting a Minecraft server.

```
./gradlew test --no-daemon --tests '*BlockMass*'
```
