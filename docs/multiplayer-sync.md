# Multiplayer structure sync (v3)

Crythst can mirror server-side physics structures to clients. The server owns the
authoritative bodies and voxel volumes; clients receive immutable snapshots and
render them without touching a server `Level`, Bullet world, or native body.

This first protocol version is intentionally pragmatic:

- full snapshots on join / dimension change / explicit request,
- end-of-tick diffs for additions, removals, volume changes and poses,
- compact palette + VarInt voxel cells,
- batched snapshots within the vanilla ~1 MiB custom-payload limit,
- block-entity NBT/animation sync in a separate dirty-update payload. See
  [`multiplayer-block-entities.md`](multiplayer-block-entities.md).

## Files

| Area | Files |
| --- | --- |
| Wire records and codecs | `io.mikaple.crythst.network.structure.*` |
| Server broadcaster / registration | `io.mikaple.crythst.integration.fabric.network.StructureSync`, `StructureSyncServer`, `StructureSyncClient`, `StructureSnapshotFactory` |
| Client cache | `io.mikaple.crythst.client.network.ClientStructureCache`, `ClientStructureSnapshot`, `ClientBlockEntitySnapshot` |
| Codec test | `src/test/java/io/mikaple/crythst/network/structure/StructurePayloadCodecTest.java` |

## Channels

All channels are registered in `StructureSyncProtocol.registerPayloads()` on
both physical sides.

| Channel | Direction | Record | Purpose |
| --- | --- | --- | --- |
| `crythst:structure_snapshot` | S2C | `StructureSnapshotPayload` | full or delta snapshots, one or more structures |
| `crythst:structure_block_entities` | S2C | `StructureBlockEntityPayload` | dirty/full block-entity NBT and chest animation |
| `crythst:structure_pose` | S2C | `StructurePosePayload` | batched pose updates for known body ids |
| `crythst:structure_remove` | S2C | `StructureRemovePayload` | remove body ids from the client cache |
| `crythst:structure_request` | C2S | `StructureRequestPayload` | ask for a fresh full snapshot |

The codecs are registered against `FriendlyByteBuf` and therefore also work
with `RegistryFriendlyByteBuf` (which extends it).

## Wire format

### `StructureSnapshotPayload`

```text
VarLong    syncId             // fresh per-player session for each full sync
Identifier dimension          // dimension the structures belong to
boolean    replaceAll         // first packet of a full sync only
VarInt     batchIndex         // 0-based
VarInt     batchCount         // >= 1
VarInt     structureCount
  repeated StructureSnapshot
```

An empty full sync is a single packet with `batchCount = 1`, `batchIndex = 0`,
`replaceAll = true`, `structureCount = 0`; it clears the client cache and adopts
the new session/dimension. Delta snapshots, poses and removals must carry the
same `syncId` and dimension or the client ignores them.

### `StructureSnapshot`

```text
VarLong   bodyId
double[3] pose position
double[4] pose rotation quaternion (x,y,z,w, normalized on decode)
VarInt    bounds minX, minY, minZ, maxX, maxY, maxZ   // inclusive min, exclusive max
VarInt    paletteSize
VarInt[]  palette        // Minecraft block-state registry ids
VarInt    cellCount      // must equal bounds volume
VarInt[]  cells          // 0 = empty, n = palette[n - 1]
double    blockScale     // metres per voxel
double    massScale
double    colliderMass   // unscaled collider mass
double[3] centerOfMass   // voxel coordinates
```

The cell order is `((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)`,
matching `MutableVoxelVolume` and the collider builder. Solid blocks only enter
the palette; empty cells are the single byte `0` in the common case.

### `StructurePosePayload`

```text
VarLong   syncId
VarInt    updateCount
  repeated:
    VarLong bodyId
    double[3] position
    double[4] rotation quaternion
```

A client ignores updates from a stale session and updates for body ids it has
not seen.

### `StructureRemovePayload`

```text
VarLong   syncId
VarInt    bodyIdCount
VarLong[] bodyIds
```

### `StructureRequestPayload`

Empty payload (`StreamCodec.unit`).

## Server broadcaster

`StructureSyncServer` hooks:

- `ServerPlayConnectionEvents.JOIN` — send a full snapshot of the player's
  current level.
- `ServerTickEvents.END_SERVER_TICK` — diff each level's `LevelPhysics`
  structures and send deltas to players in that level.
- `ServerPlayConnectionEvents.DISCONNECT` — drop per-player tracking.
- `ServerLevelEvents.UNLOAD` / `ServerLifecycleEvents.SERVER_STOPPED` — clear
  state.
- `StructureRequestPayload` receiver — re-send a full snapshot.

The diff tracks `(pose, volume identity, collider identity)` per body id:

| Change | Packet |
| --- | --- |
| body id appeared | snapshot batch |
| body id disappeared | remove |
| `volume()` or `collider()` object changed | fresh snapshot |
| pose moved or rotated past a tiny threshold | batched pose update |

The pose threshold is 1 mm in translation and roughly `1e-8` in quaternion dot
product. This avoids sending packets for a resting body's floating-point noise.

If a delta cannot be delivered (player disconnecting, channel not ready), the
player is marked unsynchronised and receives a full snapshot on the next tick.
If a structure becomes unrepresentable after previously being sent, the server
sends a remove so clients do not keep stale blocks.

The broadcaster registers its `END_SERVER_TICK` listener on
`ServerLifecycleEvents.SERVER_STARTED`. That happens after every mod
initializer has run, so the diff always runs after `PhysicsTicker` and reads the
pose produced by the current physics tick, regardless of initializer order.

## Payload limits and batching

Constants live in `StructureSyncLimits`.

| Constant | Value | Meaning |
| --- | --- | --- |
| `PROTOCOL_VERSION` | 3 | bump when layouts change |
| `MAX_CELLS` | 1,048,576 | maximum voxels in one structure |
| `MAX_PALETTE` | 4,096 | maximum distinct block states |
| `MAX_STRUCTURES_PER_BATCH` | 4,096 | structures / removals per packet |
| `MAX_POSE_UPDATES` | 4,096 | pose updates per packet |
| `TARGET_BATCH_BYTES` | 512 KiB | soft snapshot packet target |
| `MAX_STRUCTURE_BYTES` | 768 KiB | single structure is skipped above this |
| `MAX_PAYLOAD_BYTES` | 1 MiB | vanilla custom-payload ceiling |

Snapshots are batched **at structure granularity**: a batch is flushed before
the next structure would exceed `TARGET_BATCH_BYTES`. There is no intra-structure
chunking in v3, so a single structure larger than `MAX_STRUCTURE_BYTES` (or
larger than `MAX_CELLS`) is skipped and logged by the server instead of
disconnecting the client. If intra-structure chunking is added later, it should
extend `StructureSnapshotPayload` with a volume chunk index rather than change
the existing fields silently.

## Client cache

`ClientStructureCache` is a passive, immutable-map cache. Receivers in
`StructureSyncClient` call `context.client().execute(...)`, so all mutations and
reads happen on the client thread. It never reads a server world.

API:

```java
ClientStructureCache.snapshots();     // Collection<ClientStructureSnapshot>
ClientStructureCache.byId();          // immutable Map<Long, ClientStructureSnapshot>
ClientStructureCache.get(bodyId);
ClientStructureCache.size();
ClientStructureCache.revision();      // changes when the map changes
ClientStructureCache.clear();         // disconnect
StructureSync.requestFullSync();       // optional client-side resync
```

`ClientStructureSnapshot` exposes:

```java
long bodyId();
Transform3d pose();
IntBounds3 bounds();
BlockState blockStateAt(int x, int y, int z);
boolean isSolid(int x, int y, int z);
int cellAt(int x, int y, int z);
double blockScale();
double massScale();
double colliderMass();
double mass();                        // colliderMass * massScale
Vector3d centerOfMass();              // voxel coordinates
Vector3d centerOfMassOffset();        // metres
```

Arrays returned by `palette()` / `cells()` are copies. Pose updates share the
internal volume arrays, so they are O(1) rather than copying a whole grid.
`pose()` is the newest received wire pose; renderers should normally call
`ClientStructureCache#interpolatedPose(bodyId, frameNanos)` instead, which
samples the per-body `PoseTimeline` used by local structures as well.

### Renderer example

```java
final long frameNanos = System.nanoTime();
for (ClientStructureSnapshot structure : ClientStructureCache.snapshots()) {
    // Sample the timestamped timeline, not the raw wire pose.
    final Transform3d pose = ClientStructureCache.interpolatedPose(
            structure.bodyId(), frameNanos);
    final double scale = structure.blockScale();
    final IntBounds3 bounds = structure.bounds();

    for (int y = bounds.minY(); y < bounds.maxY(); y++) {
        for (int z = bounds.minZ(); z < bounds.maxZ(); z++) {
            for (int x = bounds.minX(); x < bounds.maxX(); x++) {
                final BlockState state = structure.blockStateAt(x, y, z);
                if (state.isAir()) {
                    continue;
                }
                // The wire pose is the structure-origin pose: voxels are
                // unshifted. Do not subtract the centre of mass again.
                final Vector3d local = new Vector3d(
                        (x + 0.5) * scale,
                        (y + 0.5) * scale,
                        (z + 0.5) * scale);
                final Vector3d world = pose.transformPoint(local);
                // draw state at `world`
            }
        }
    }
}
```

Pose updates arrive once per server tick for moving structures. The client
feeds each received pose into the per-body `PoseTimeline`, which delays the
newest pose by one smoothed receive interval and never extrapolates. Local and
network structures therefore use the same timestamped sampler; teleports and
full resyncs snap instead of interpolating across a discontinuity.

## Registration

Add to the common initializer (`CrythstFabric.onInitialize()`), after
`PhysicsTicker.register()`:

```java
import io.mikaple.crythst.integration.fabric.network.StructureSync;

// existing:
PhysicsTicker.register();
// add:
StructureSync.registerCommon();
```

Add to the client initializer (`CrythstClient.onInitializeClient()`), before
renderer registration:

```java
import io.mikaple.crythst.integration.fabric.network.StructureSync;

@Override
public void onInitializeClient() {
    StructureSync.registerClient();
    // existing renderer registrations...
}
```

`registerPayloads()` is idempotent, so both entrypoints may call through
`StructureSync` safely.

## Diagnostics

Enable packet-level logging with:

```text
-Dcrythst.debug.structureSync=true
```

Status strings are always available without enabling logs:

```java
StructureSync.serverStatusText(); // levels, tracked/sent counts, sessions, skipped bodies, last error
StructureSync.clientStatusText(); // structures, revision, syncId, dimension, last batch, stale counts
StructureSync.logClientStatus();  // logs the client status once
```

A command or debug overlay can call these directly. They include the counts,
revision, last batch, dimension and skipped-body diagnostics required to tell
whether the client is on the same session/dimension as the server.

## Caveats

- **Block-state registry ids are not portable across versions or mod sets.**
  The palette uses `Block.getId(state)` / `Block.stateById(id)`. Client and
  server must have the same block registry. A future version should send block
  registry names plus property values for cross-version compatibility.
- **Block entities have their own v3 payload.** Chests, signs, spawners, etc.
  render with their real block-entity renderer when the server can capture their
  NBT. Extremely large or non-serialisable modded state falls back to the block
  model; see [`multiplayer-block-entities.md`](multiplayer-block-entities.md).
- **Mutable volume states come from the interaction side table.**
  `MutableVoxelVolume` only exposes solidity, so after the `BlockVoxelVolume`
  case `StructureSnapshotFactory` reads `StructureBlockStates` and falls back to
  `Blocks.STONE` for cells without a recorded state. Persistence mirrors
  `RestoredStructureVolume` states into that side table, so restored structures
  keep their appearance; a custom `StatefulVoxelVolume` that is neither a
  `BlockVoxelVolume` nor mirrored falls back to stone.
- **Volume-change detection uses object identity.** `PhysicsStructure.reindex`
  now installs the replacement volume and rebuilds the collider, both of which
  the diff observes. Mutating a volume in place *without* reindexing is still
  not detected and would also leave the physics collider stale; call
  `PhysicsStructure.reindex(...)` (and `LevelPhysics.refreshStructure(...)`)
  after edits.
- **Structure ids are per-level, not global.** Each full sync carries a
  per-player `syncId` plus the dimension. Pose/remove/delta packets from another
  session are ignored, and the client clears its cache on a client-level
  dimension change even if the new full sync is delayed.
- **Structure pose already includes the COM offset.** The client batch renderer
  maps unshifted voxel coordinates through the wire structure pose; subtracting
  the wire COM a second time would double-shift the geometry.
- **Rendering and collision sample different network poses.** Rendering and
  selection use the delayed `PoseTimeline` sample, while the client movement
  collision source uses `ClientStructureSnapshot.pose()` directly. On a
  fast-moving remote structure the collision surface can be one sync interval
  ahead of the rendered outline.
- **Clients without Crythst are skipped.** `ServerPlayNetworking.canSend` gates
  all sends, so a vanilla/other client is unaffected.
- **The C2S request is not rate-limited.** A malicious client could spam
  `structure_request` and force repeated full snapshots. Add a cooldown or
  permission check if the server is exposed to untrusted clients.
- **No compression.** Palettes and VarInt cells keep the common case small, but
  a large heterogeneous structure can approach the cap. Compression can be
  added as a new payload flag without breaking v3 decoding if negotiated.
- **One snapshot per changed structure.** Edits during a tick are coalesced at
  the next `END_SERVER_TICK`; intermediate states are never sent.

## Test

```bash
./gradlew test --tests 'io.mikaple.crythst.network.structure.StructurePayloadCodecTest' --no-daemon
```

The test is pure codec round-tripping (`FriendlyByteBuf` + `Unpooled`) and does
not start a server or client. It covers snapshot, pose, remove and request
payloads plus defensive validation.
