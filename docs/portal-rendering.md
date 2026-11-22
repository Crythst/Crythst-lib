# Portal rendering and teleport (same-FBO implementation)

> Status: 2026-11-22 rewrite.
> Scope: `integration/portal`, `client/render/portal`, `network/portal`, and the
> portal-specific client mixins.  
> Design context: [`seamless-portal-replication.md`](seamless-portal-replication.md).

## 1. What is implemented

The default path is now a **world render transform**, not a per-portal offscreen
texture:

1. At the end of `LevelExtractor.extract`, each visible linked portal gets a
   destination `LevelRenderState` extracted for its transformed camera.
2. At the end of `LevelRenderer.addMainPass`, a portal pass is added to the
   vanilla `FrameGraphBuilder`.
3. That pass declares a read/write dependency on the **main** colour+depth
   handle and draws the destination world directly into it.
4. The opening is a per-pixel mask built from the real portal polygon. The
   previous main pixels are restored outside the mask with a RenderPearl
   composite pass.
5. The destination camera uses an oblique projection whose near plane is the
   exit portal plane, so geometry behind the exit cannot leak.
6. A second recursion layer extracts/draws a portal seen inside the destination
   view.

`PortalRenderPass`-style offscreen captures are no longer used unless the
explicit `-Dcrythst.portal.offscreenFallback=true` debug flag is set. There is no
texture-quad surface on the default path.

## 2. Exact render path

```text
LevelExtractor.extract (main camera)
  └─ @RETURN  PortalCaptureMixin
       └─ PortalRenderManager.onExtractEnd
            ├─ PortalViewCamera.build            T(mainCamera) + oblique exit clip
            ├─ PortalVisibility.extract
            │    ├─ swap LevelExtractor.levelRenderState -> new LevelRenderState
            │    ├─ swap LevelRenderer.visibleSections -> destination list
            │    ├─ point the real Camera at the destination (scoped)
            │    ├─ LevelExtractor.extract (nested, guarded)
            │    └─ restore every shared field
            ├─ PortalMaskGeometry.build          project + clip opening polygon
            └─ child portal (layer 2, same pipeline)

LevelRenderer.render
  └─ addMainPass
       └─ @RETURN  PortalLevelRendererMixin
            └─ PortalRenderManager.addFramePass
                 ├─ FrameGraphBuilder.addPass("crythst_portals")
                 ├─ disableCulling()
                 └─ readsAndWrites(LevelTargetBundle.main)

frame.execute
  └─ main pass (vanilla)
  └─ crythst_portals
       ├─ copy main colour+depth -> per-layer snapshot
       ├─ draw opening polygon -> per-layer RGBA8 mask
       │    (depth-tested against the snapshot depth, reverse-Z)
       ├─ draw the same polygon into main depth as far (WRITE_NONE colour)
       ├─ clear main colour to destination fog (full frame; composite restores)
       ├─ SkyRenderer.render (destination sky/fog)
       ├─ pre-prepared ChunkSectionsToRender + FeatureRenderDispatcher
       ├─ composite snapshot colour outside mask * parent mask
       └─ depth-restore pass: snapshot depth outside mask * parent mask only
```

The chunk draw groups are prepared once per frame during extraction into a
dedicated mapped `DynamicGpuDataStorageMapped`, not during framegraph execution.
That avoids the shared `DynamicGpuData` mapped/instanced storage being closed and
recreated at capacity 2 every frame.

The destination pass uses the following RenderPearl/Fabric API surface:

- `FrameGraphBuilder` / `FramePass` / `ResourceHandle`
- `RenderPassDescriptor` with the main `GpuTextureView` colour and depth
  attachments, plus a `RenderPass.RenderArea` scoped to the opening bounds
- `ChunkSectionsToRender.renderGroup` and `FeatureRenderDispatcher.PreparedFrame`
- `SkyRenderer.render`, `FogRenderer`, `GlobalSettingsUniform`,
  `ProjectionMatrixBuffer`
- `CommandEncoder.copyTextureToTexture` and `CommandEncoder.clearColorTexture`
- custom `RenderPipeline`s (`crythst:portal_mask`, `crythst:portal_depth_clear`,
  `crythst:portal_depth_restore`, `crythst:portal_composite`)
- `DepthStencilState` for the mask read-only depth test and the depth passes

## 3. Why the opening is a mask, not hardware stencil

The guide's preferred implementation is a stencil attachment with
`GL_INCR`/`EQUAL` per recursion layer. In this version of RenderPearl that state
is **not exposed**:

```java
public record DepthStencilState(
        CompareOp depthTest,
        boolean writeDepth,
        float depthBiasScaleFactor,
        float depthBiasConstant) { ... }
```

There are no stencil compare, reference, write-mask or pass-op fields.
`RenderPassDescriptor` likewise has only colour and depth attachments. Stencil
formats exist in `GpuFormat`, but no pipeline state can use them.

The implemented equivalent is a real per-pixel, depth-tested mask:

- the portal polygon is clipped in homogeneous space and triangulated; each
  vertex carries the entry/parent plane's reverse-Z depth,
- the triangles are rasterized into an RGBA8 mask attachment while depth-testing
  against the per-layer depth snapshot with `GREATER_THAN_OR_EQUAL`,
- the same polygon is drawn into the main depth as reverse-Z far, opening only
  the verified portal pixels,
- the destination world is drawn into the **main** colour+depth attachments,
- a composite pass keeps destination pixels where the mask is set and restores
  the snapshot colour elsewhere,
- a depth-restore pass puts the snapshot depth back **outside** the final mask,
  leaving destination depth inside it.

This is a mask pass, not a texture quad. It is not described as hardware stencil
in code or docs.

## 4. Destination state and section visibility

`PortalVisibility` creates a **separate** `LevelRenderState`; it never reuses the
main camera's state. It swaps:

- `LevelExtractor.levelRenderState`,
- `Camera` position/rotation/cull/captured frustums,
- `LevelRenderer.visibleSections` / `nearbyVisibleSections`,
- the section dispatcher camera position.

All of them are restored in a `finally` block. The shared `ViewArea` is not
re-centred. Destination sections are selected from the existing main-camera
storage by `PortalSectionVisibility`, bounded by
`MAX_VISIBLE_SECTIONS`/`MAX_SCANNED_SECTIONS`.

Consequence: the destination area must already be covered by the main camera's
`ViewArea` and loaded chunks. A separate destination view area/chunk ticket is
still required for distant or cross-dimension views.

## 5. Recursion

- Default `-Dcrythst.portal.recursion=2`.
- Layer 1 = portal in the main camera.
- Layer 2 = portal visible in layer 1's destination camera.
- Ancestor ids are excluded; over-limit children are not extracted.
- Layer 2 multiplies its mask by layer 1's mask, so it cannot paint outside the
  outer opening.
- Layer 2's mask is depth-tested against layer 1's *destination* depth (the
  layer snapshot), so a wall in front of portal 2 occludes it instead of being
  painted over.
- Depth is restored only outside `mask2 * mask1`; layer 1's destination depth
  survives inside the outer opening for layer 2 and later depth consumers.
- The current cap is 2 layers. Supporting deeper chains would require baking the
  full ancestor-mask product into each layer or a stencil stack; the public
  RenderPearl mask path only has one parent-mask sampler.

## 6. Teleport and crossing

Server (`PortalTeleporter`):

- swept body-centre segment vs the polygon (projectiles included),
- entity/player position, velocity, yaw/pitch, fall distance and on-ground state,
- vehicle passengers carried with the same rigid offset,
- physics bodies with pose, linear velocity, angular velocity, rotation and a per-body gravity/up override,
- cross-dimension entity teleport via `Entity.teleport`,
- 10-tick cooldown on both sides of the link (anti-return),
- leashes are removed on cross-dimension traversal; same-dimension leashes are
  kept for the vanilla leash tick.

Client (`PortalCrossingController`):

- same-dimension local player predicts the same rigid transform at the crossing,
  so the camera continues instead of waiting for the server tick,
- updates `oldPosAndRot` so interpolation does not smear,
- cross-dimension crossing waits for the authoritative server transition,
- no mandatory fade; the legacy fade is opt-in (`-Dcrythst.portal.fade=true`).

After predicting, the client sends a small `crythst:portal_traversal` intent
packet containing the pre-transform swept samples. The server prefers its own
swept segment reconstructed from `xo/yo/zo` + the current body centre. If the
server has not yet received that movement (latency), it accepts the client
prediction only when its intersection point is within 2.5 blocks of the
authoritative body centre. Placement, velocity and rotation are read from the
server player (`getEyePosition`, `getKnownMovement`/delta movement, yaw/pitch),
never from a client-supplied target. Unverifiable predictions get an
authoritative `player.connection.teleport(...)` correction.

## 7. Sync and moving portals

- `PortalManager` is the only authority.
- `PortalManager.revision()` changes on create/link/remove/move/reshape/load.
- `PortalSyncServer` sends the complete immutable registry on join, request and
  revision change.
- `PortalSyncClient` publishes it to `PortalClientCache` on the client thread.
- `PortalTraversalPayload` is the only C2S portal packet: a validated crossing
  notice, not a target position.
- `PortalManager.move(name, plane)` updates the pose without changing the UUID
  or link; clients receive the new pose in the next full snapshot.
- A moving carrier is bound with `PortalManager.attach(name, structure)` (or
  `/crythst portal attach <name> [bodyId]`). The portal then stores a
  `PortalAnchor` — the carrier body id plus the plane in the carrier's local
  space — instead of an absolute plane. Nothing is re-broadcast per tick: the
  server resolves the world plane from `PhysicsStructure#pose()` at teleport
  time, and the client resolves it from
  `ClientStructureCache#interpolatedPose(bodyId, nowNanos)` once per frame
  through `PortalClientCache.effective(nowNanos)`. `detach` freezes the portal
  at its current world plane.
- The carrier bodies themselves are never teleported by their own portals, and
  an entity stepping out of a carried portal inherits the exit carrier's
  velocity so it is not left behind.

## 8. Feature flags

```text
-Dcrythst.portal.render=false             disable the portal renderer
-Dcrythst.portal.stencil=true             enable the mask/destination path (default)
-Dcrythst.portal.offscreenFallback=true   explicit legacy debug escape hatch
-Dcrythst.portal.debugMask=true           draw opening outlines
-Dcrythst.portal.debugClip=true           reserved for clip visualisation
-Dcrythst.portal.trace=true               one aggregated line per second
-Dcrythst.portal.recursion=2              max layers (1..2)
-Dcrythst.portal.fade=true                opt-in legacy debug fade
```

Two F3 + F6 entries come from the mod itself (both off by default):

| Entry | What it shows |
| --- | --- |
| `crythst:portal` | opening outline, plane normal, right/up axes, destination outline + link line (same dimension only), the traversal box, and a label with name, size, link and carrier body id. |
| `crythst:portal_stats` | portals rendered, deepest recursion layer, extracted chunk sections, extraction and pass-recording milliseconds, and the active recursion/per-round/target caps. |

## 9. Acceptance against the guide's section 10

| Criterion | State |
| --- | --- |
| Per-frame parallax through the opening | **Implemented.** Destination state is extracted and drawn every frame. |
| Camera crossing without a snap / black screen | **Implemented for same-dimension links** by client prediction + server correction. Cross-dimension waits for the server transition. |
| Per-pixel opening, not a rectangular texture | **Implemented** as a clipped polygon mask, depth-tested against the per-layer scene depth, plus composite/depth-restore. Hardware stencil is blocked by RenderPearl. |
| Destination entities / block entities / particles / sky / fog | **Implemented** for entities, block entities, particles, sky, fog, clouds, weather and world border. OIT/see-through/always-on-top features, block-breaking overlays, transient blocks and destination gizmos are not yet submitted. |
| At least 2 recursion layers | **Implemented** (layer 1 + layer 2); layer 2 is depth-tested against layer 1's destination depth and depth is restored only outside `mask2 * mask1`. |
| Same-dimension + cross-dimension + multiplayer | Same-dimension render + teleport, cross-dimension teleport, multiplayer registry sync. Cross-dimension **rendering** is blocked (one `ClientLevel`). |
| Moving portals | **Implemented** through `PortalAnchor`: the server samples `PhysicsStructure#pose()` and the client samples `ClientStructureCache#interpolatedPose` every frame (see §7). |
| Cost scales with opening pixels, not whole-world redraw | **Partial.** Destination terrain/entities are scissored to the opening bounds, but the destination section extraction is full visible-set extraction and the sky/snapshot are full-frame. See §10. |
| In-game automated test + screenshot regression | **Blocked in this change.** No client/gametest run was permitted; pure tests cover the math. |

## 10. Performance tradeoff

The destination draw itself is scoped:

- `RenderPass.RenderArea` limits terrain/entity/BE fill and the mask/depth-clear
  passes to the portal's screen rectangle,
- the polygon depth clear writes only the clipped opening,
- chunk draw groups are prepared once per frame during extraction with a
  dedicated, pre-sized mapped storage (no per-frame UBO recreation/log spam).

Three operations remain full-frame:

1. **snapshot colour+depth** per rendered layer, because composite/depth-restore
   need the pre-layer pixels/depth;
2. **destination sky**, because `SkyRenderer.render` creates its own full-screen
   `RenderPass` and exposes no render-area parameter;
3. **composite and depth-restore full-screen triangles** (the depth restore
   discards inside the mask, but still visits the screen).

The destination section extract also walks the destination visible set from the
main `ViewArea`, so its CPU cost scales with destination sections rather than
opening pixels. Profiling on the target machine is required before claiming the
3 ms target from M2; the trace line reports extract/draw timings.

## 11. Remaining blockers with proof

1. **Hardware stencil is not expressible.**
   `com.mojang.renderpearl.api.pipeline.DepthStencilState` has only
   `depthTest`, `writeDepth`, `depthBiasScaleFactor`, `depthBiasConstant`.
   `RenderPassDescriptor` has only colour/depth attachment lists. The mask path
   is the closest true per-pixel implementation.
2. **Cross-dimension rendering needs a second client level.**
   The client has one `Minecraft.level`/`ClientLevel`; `PortalSnapshot.viewableIn`
   filters cross-dimension destinations. Teleport works; rendering does not.
3. **Distant destination sections need a separate view area/ticket.**
   Moving the shared `ViewArea` resets section meshes in 26.3. This rewrite
   deliberately does not do that; a separate destination `ViewArea` is future
   work.
4. **Scoped sky requires either raw GL or a rewritten sky pass.**
   `SkyRenderer.render` hardcodes a full-screen render pass around its private
   target. The pass therefore keeps a full-frame snapshot/composite.
5. **OIT and advanced feature phases are not mirrored into the destination pass.**
   The portal pass uses the classic opaque/translucent groups and the solid /
   translucent / after-terrain feature phases.
6. **No automated in-game visual regression.**
   There is no Fabric client-gametest setup in this repository and this change
   was not allowed to launch `runClient`. Pure tests cover mask clipping,
   recursion, crossing sweeps, camera continuity, transforms and persistence.

## 12. Tests

Pure tests (no Minecraft bootstrap):

- `PortalMaskGeometryTest`: front-facing mask, behind-camera rejection,
  near-plane clipping, off-screen bounds, reverse-Z plane depth, and depth
  classification.
- `PortalMaskPolicyTest`: layer mask product, destination keep/discard and
  snapshot-depth restore policy.
- `PortalTraversalAuthorityTest`: server-segment precedence and the 2.5-block
  latency fallback for client predictions.
- `PortalTeleportMathTest`: finite samples, server-body radius and transform
  math.
- `PortalRecursionTest`: layer increments, limit, ancestor exclusion.
- `PortalCrossingSweepTest`: swept projectile-style segment, polygon rejection,
  exit safe zone.
- `PortalCameraMathTest`: rigid camera pose, projective identity on the plane,
  path continuity across the seam, oblique exit clip.
- `PortalTeleportMathTest`: pose/velocity/angular/up transform, inverse
  round-trip, player feet, exit bias, swept plane parameter.
- `PortalShapeTest`: rectangle/polygon containment and crossing.
- `PortalPersistenceTest`: v1→v2 rectangle migration, polygon outline round trip.
- `PortalTraversalTest` and `PortalCameraSanityTest` remain green.
- `PortalCaptureSchedulerTest` is retained for the demoted legacy capture
  scheduler.
