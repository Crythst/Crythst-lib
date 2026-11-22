# Portal rendering and teleport (RenderPearl implementation)

> Status: implementation notes for the 2026-11-22 rewrite.  
> Scope: `integration/portal`, `client/render/portal`, `network/portal`, and the
> portal-specific client mixins.  
> Design context: [`seamless-portal-replication.md`](seamless-portal-replication.md).

## 1. What changed

The old path was an approximate "monitor texture":

- a low-frequency (100–500 ms) capture,
- screen-space UVs stored from a previous frame,
- an unbounded clamp in the shader that smeared near-plane UV overflow,
- no exit-plane clip, so geometry behind the destination portal could leak in,
- main-camera fog copied into the destination scene,
- a tick-end teleport with a mandatory fade.

The new path keeps the only architecture that is expressible through the public
26.3 RenderPearl/Fabric surface: **render the destination scene from the
portal-plane virtual camera into a bounded view texture, then draw it on the
portal opening with projective UVs**. It removes the approximation inside that
architecture:

| Old | New |
| --- | --- |
| fixed 100–500 ms cadence | moved camera/portal captures next frame; stationary refresh 100 ms; adaptive backoff only under cost pressure |
| destination fog copied from main camera | fog recomputed at the destination camera |
| main-camera screen UV from a stale capture | same projective identity, but every moved frame is recaptured; capture stores the exact unmodified main view-projection |
| no exit clip | oblique near-plane projection at the exit portal plane (`PortalClip`) |
| UV `clamp()` in the fragment shader | sampler clamp-to-edge only; no whole-quad smearing |
| one target per portal forever | targets capped (`MAX_ACTIVE_TARGETS`), pruned when portals disappear, reused/resized |
| rectangle only | `PortalShape` rectangle + convex polygon, persisted (v2) and used by crossing/teleport/render |
| tick-end teleport with mandatory fade | server-authoritative rigid transform, preserved velocity/rotation/pose, swept physics-body crossing, optional debug fade |

## 2. Exact data flow

```text
server tick
  PortalTeleporter
    entity/player: body-centre segment vs PortalShape
    physics body: previous-centre -> current-centre swept segment
    apply T to position, velocity, orientation, angular velocity, up
    mark cooldown; no duplicate teleport in the same tick

S2C
  PortalSyncServer -> PortalSyncPayload (authoritative full registry)
  PortalSyncClient -> PortalClientCache (immutable list, revisioned)

main extract
  PortalCaptureMixin (LevelExtractor HEAD)
    PortalRenderManager.capture
      select visible linked portals (current dimension only)
      PortalCaptureScheduler.due
      PortalViewCamera.build
        T(main camera)
        oblique exit-plane projection
      PortalDestinationExtractor
        scoped camera/LevelRenderState/visible-section save+restore
      PortalRenderPass
        LevelRenderer into the portal TextureTarget (RenderPearl only)

main pass (BEFORE_GIZMOS)
  PortalSurfaceRenderer
    PortalShape world outline
    clip polygon in homogeneous space (w > 0)
    UV = main-camera NDC of each vertex
    sample the captured target texture
```

The mapping identity is documented and tested in `PortalCameraMathTest`:

```text
P * V_portal * T(q) == P * V_main * q     for q on the entry plane
```

Therefore the portal quad's own screen position is the correct destination
sample, not an approximation. The destination render uses the same projection
(including view bob/hurt/spin) and an oblique near plane at the exit.

## 3. Why not a same-frame stencil pass?

26.3 renders through a `FrameGraphBuilder` owned privately by `LevelRenderer`.
The public/Fabric API exposes post-world hooks (`LevelRenderEvents`), but not a
way to bind the main color+depth attachments and issue another full
`LevelRenderer` pass with a stencil mask inside the same framegraph. Reproducing
it would require raw GL/stencil state and bypassing RenderPearl, which this
rewrite explicitly avoids. The implemented alternative is the closest correct
RenderPearl approach:

- every moved frame is recaptured,
- the virtual camera and projection are exact,
- the exit plane is the near plane (no leakage),
- the opening is a real polygon and the texture is sampled per pixel,
- depth is written by the portal quad in the normal solid pass.

The remaining gap versus a true stencil framegraph is documented below
(recursion > 1 and cross-dimension destination rendering).

## 4. Opening shapes and linking

- `PortalShape` stores a convex local outline (`right`, `up` metres).
- `PortalShape.rectangle` is the default used by commands.
- `PortalSavedData` version 2 persists an optional outline; version-1 data
  loads as a rectangle.
- `PortalCrossing` uses the polygon, not the bounding rectangle.
- `PortalTransform` maps points, directions, quaternions, velocity, angular
  velocity and the up/gravity hint. The normal flip (`nA -> -nB`) is preserved.
- `PortalManager.move` / `reshape` publish a moving portal pose without changing
  its UUID or link. `/crythst portal move <name>` uses the command source's
  current eye and look direction; a carrier system can call the API directly.

## 5. Server authority and sync

- The server registry remains the only authority (`PortalManager`).
- `PortalManager.revision()` increments on load/create/link/remove/clear.
- `PortalSyncServer` sends a full snapshot on join, on request and whenever the
  revision changes.
- `PortalSyncClient` applies packets on the client thread; the render thread
  only reads `PortalClientCache`.
- Portal textures are never persisted or sent.
- Cross-dimension teleport is supported by the server. Cross-dimension
  *rendering* is not: a client has one loaded `ClientLevel`, so the renderer
  filters destinations to the current dimension (`PortalSnapshot.viewableIn`).

## 6. Feature flags

```text
-Dcrythst.portal.render=false           disable portal rendering
-Dcrythst.portal.capture=false          force the procedural fallback
-Dcrythst.portal.procedural=true        alias for forcing the fallback
-Dcrythst.portal.debugMask=true         draw opening outlines
-Dcrythst.portal.trace=true             one aggregated timing line per second
-Dcrythst.portal.recursion=1            max nested layers (1 = none)
-Dcrythst.portal.capture.scale=1.0      capture resolution scale (0.125..1)
-Dcrythst.portal.capture.dump=true      dump the first captured target
-Dcrythst.portal.debugBlit=true         full-screen texture diagnostic
-Dcrythst.portal.fade=true              opt-in legacy 2-tick debug fade
```

The default path is the correct one. The procedural shader is used only when
capture is disabled/unavailable or before the first successful capture.

## 7. Resource bounds and performance

- At most `MAX_ACTIVE_TARGETS = 4` view targets; each target is capped at
  1920×1080 (default scale 1.0).
- At most one destination render per frame (`MAX_PORTALS_PER_ROUND = 1`).
- Targets are reused while camera/portal pose and age allow; resized targets are
  destroyed before replacement.
- Capture cadence is zero-interval while moving, 100 ms idle refresh, and backs
  off toward 250 ms only when extract+render exceeds the budget.
- Section selection is bounded (`MAX_VISIBLE_SECTIONS = 2048`,
  `MAX_SCANNED_SECTIONS = 65536`) and never moves the shared `ViewArea`.
- Recursive capture is prevented by skipping portal surfaces while capturing.

The dominant cost is the destination `LevelRenderer` pass (terrain/entities/BE),
roughly proportional to the visible destination sections, not the portal screen
area. The design target from the roadmap is a single portal 1080p incremental
cost below ~3 ms; the trace line exposes the actual `extract`/`render`/`total`
values so the number can be measured on the target machine.

## 8. Remaining limitations

1. **Recursion > 1**: the texture path avoids recursion by not drawing portal
   surfaces in a capture. A true layer stack needs the stencil framegraph.
2. **Cross-dimension destination rendering**: teleport works; rendering the
   other dimension requires a second loaded client level.
3. **Moving portals attached to physics structures**: `PortalManager.move`
   streams a new pose and the renderer recaptures per frame, but there is no
   automatic binding that follows a `PhysicsStructure` carrier every tick yet;
   the binding/command layer must call `move`.
4. **Arbitrary gravity/up for players**: physics bodies get the transformed up
   vector; vanilla players still use world-up feet placement because the vanilla
   camera has no gravity frame. Tilted portals keep the camera at `T(eye)`.
5. **ViewArea coverage**: destination sections must already be in the client
   `ViewArea`; the rewrite intentionally does not re-centre it (that resets
   section meshes in 26.3). A separate destination chunk ticket/view area is a
   later milestone.
6. **Sodium/Iris**: the RenderPearl pipeline path is used, but shaderpack
   compatibility still needs visual regression testing.

## 9. Tests

Pure tests (no Minecraft bootstrap):

- `PortalCameraMathTest`: virtual camera pose, `P V T == P V` identity on the
  plane, target aspect/cap, oblique near clip for both camera sides.
- `PortalCaptureSchedulerTest`: immediate moved capture, idle refresh, bounded
  backoff/recovery, clock reset.
- `PortalShapeTest`: rectangle/polygon containment, degenerate outlines,
  polygon-aware crossing.
- `PortalTeleportMathTest`: pose/velocity/angular/up transform, inverse
  round-trip, both traversal directions, player feet, exit bias, swept test.
- `PortalPersistenceTest`: v1→v2 rectangle migration and polygon outline round
  trip.
- Existing `PortalTraversalTest` and `PortalCameraSanityTest` remain green.

Recommended in-game regression (documented roadmap, not automated here):

1. put distinct blocks behind each portal;
2. walk left/right in front of the opening and confirm per-frame parallax;
3. walk, run, fall and back into the portal; confirm no camera snap or double
   entity;
4. move close to the plane and confirm the opening does not white out;
5. place terrain between the transformed camera and the exit and confirm it is
   clipped.
