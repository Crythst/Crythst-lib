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
       ├─ clear main colour+depth only in the portal screen rectangle
       ├─ SkyRenderer.render (destination sky/fog)
       ├─ ChunkSectionsToRender + FeatureRenderDispatcher into main colour+depth
       ├─ composite snapshot outside mask (parent mask for layer 2)
       └─ copy snapshot depth back
```

The destination pass uses the following RenderPearl/Fabric API surface:

- `FrameGraphBuilder` / `FramePass` / `ResourceHandle`
- `RenderPassDescriptor` with the main `GpuTextureView` colour and depth
  attachments, plus a `RenderPass.RenderArea` scoped to the opening bounds
- `ChunkSectionsToRender.renderGroup` and `FeatureRenderDispatcher.PreparedFrame`
- `SkyRenderer.render`, `FogRenderer`, `GlobalSettingsUniform`,
  `ProjectionMatrixBuffer`
- `CommandEncoder.copyTextureToTexture` and
  `CommandEncoder.clearColorAndDepthTextures` with a region
- custom `RenderPipeline`s (`crythst:portal_mask`, `crythst:portal_composite`)

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

The implemented equivalent is a real per-pixel mask:

- the portal polygon is clipped in homogeneous space and triangulated,
- the triangles are rasterized into an RGBA8 mask attachment,
- the destination world is drawn into the **main** colour+depth attachments,
- a composite pass keeps destination pixels where the mask is set and restores
  the snapshot elsewhere.

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
packet containing the pre-transform swept samples. The server re-validates the
polygon crossing and the player's proximity, then applies the rigid transform
itself and sends `player.connection.teleport(...)`. It never accepts a target
vector from the client. If its crossing result differs, the correction
overwrites the prediction.

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
- A moving carrier (physics structure/script) must call `move`; automatic
  per-tick binding to a `PhysicsStructure` is not wired yet.

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

## 9. Acceptance against the guide's section 10

| Criterion | State |
| --- | --- |
| Per-frame parallax through the opening | **Implemented.** Destination state is extracted and drawn every frame. |
| Camera crossing without a snap / black screen | **Implemented for same-dimension links** by client prediction + server correction. Cross-dimension waits for the server transition. |
| Per-pixel opening, not a rectangular texture | **Implemented** as a clipped polygon mask + composite. Hardware stencil is blocked by RenderPearl. |
| Destination entities / block entities / particles / sky / fog | **Implemented** for entities, block entities, particles, sky, fog, clouds, weather and world border. OIT/see-through/always-on-top features, block-breaking overlays, transient blocks and destination gizmos are not yet submitted. |
| At least 2 recursion layers | **Implemented** (layer 1 + layer 2). |
| Same-dimension + cross-dimension + multiplayer | Same-dimension render + teleport, cross-dimension teleport, multiplayer registry sync. Cross-dimension **rendering** is blocked (one `ClientLevel`). |
| Moving portals | Pose updates and per-frame recapture work through `PortalManager.move`; automatic carrier binding is not implemented. |
| Cost scales with opening pixels, not whole-world redraw | **Partial.** Destination terrain/entities are scissored to the opening bounds, but the destination section extraction is full visible-set extraction and the sky/snapshot are full-frame. See §10. |
| In-game automated test + screenshot regression | **Blocked in this change.** No client/gametest run was permitted; pure tests cover the math. |

## 10. Performance tradeoff

The destination draw itself is scoped:

- `RenderPass.RenderArea` limits the terrain/entity/BE fill to the portal's
  screen rectangle,
- `clearColorAndDepthTextures` clears only that rectangle,
- the mask is rasterized only in that rectangle.

However, two operations remain full-frame:

1. **snapshot colour+depth** per rendered layer, because the composite needs the
   pre-portal pixels;
2. **destination sky**, because `SkyRenderer.render` creates its own full-screen
   `RenderPass` and exposes no render-area parameter.

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
  near-plane clipping, off-screen bounds.
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
