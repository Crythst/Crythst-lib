# Physics timing and structure render interpolation

This document records the root cause of the "structure visibly trembles/jitters"
bug and the timing policy now used by the Minecraft integration.

## Symptom

Large moving physics structures shimmer or tremble even though the collision
result looks plausible. The effect is most obvious on falling/tumbling
structures and while the camera moves.

## Root cause

`LevelPhysics.tick()` used to feed `world.advance()` the **wall-clock time
between `END_SERVER_TICK` callbacks**:

```java
double delta = (System.nanoTime() - lastTickNanos) / 1e9;
world.advance(delta);
for (PhysicsStructure structure : structures) {
    structure.syncRenderPose();
}
```

Render poses are published once per server tick, and renderers sample them with
Minecraft's tick partial tick (`DeltaTracker#getGameTimeDeltaPartialTick`).
That interpolation is only correct if each server tick advances the same amount
of simulated time. Wall-clock deltas do not guarantee that:

- the integrated server may run several ticks back-to-back after a frame spike,
- the scheduler may deliver ticks a few milliseconds early or late,
- `0.05 / (1/60)` can land on a floating-point boundary and run 2 steps instead
  of 3.

The result is a sequence like 3, 2, 4, 3, 2 steps per rendered tick. The
renderer still sweeps alpha from 0 to 1 at a constant rate, so the structure
moves slowly for one tick, then fast for the next. That is the visible
trembling. Changing the fixed step from 1/60 to 1/120 reduces the relative
error but does not remove the cause, and doubles CPU cost.

`rebaseOrigin` is not involved: it only runs after the structure is 2048 blocks
from the origin and preserves world-space poses. Per-structure contact/sleep
jitter exists in Bullet too, but it was not the dominant effect here; stable
step counts eliminate the tick-rate component.

## Chosen policy

**Tick-locked simulation + one timestamped render-pose publish per server tick +
a client-side, one-tick-delayed pose timeline.**

- `FixedStepClock` is fed exactly one server tick of game time per tick:
  `1 / level.getServer().tickRateManager().tickrate()`.
- At 20 TPS with the default `fixedTimeStep = 1/60`, every tick runs exactly
  three substeps.
- `syncRenderPose()` runs once per tick, **after** the whole substep batch.
  It records the pose together with `System.nanoTime()` in
  `PoseTimeline` (`physics/tick`).
- The physics clock's accumulator (`world.interpolationAlpha()`) is **not** the
  render alpha. It describes the remainder to the next substep, and substeps
  are executed in a burst. Using it while publishing only tick-boundary poses
  would make the structure crawl for most of the tick and then jump.
- `/tick` rate changes are respected: the budget is read every tick.
- When the tick rate manager is frozen (and not manually stepping), physics and
  block logic are skipped, but the current render pose is retained.

## Remaining moving-jitter: server tick vs client partial tick

The first fix made the simulation tick-locked, which removed resting/step-count
jitter. Moving structures still trembled because the render pose was sampled
with Minecraft's **client** partial tick while poses are published on the
**server** tick:

- the integrated server runs its own tick thread; its 20 Hz tick boundaries are
  not phase-locked to the client's 20 Hz tick boundaries;
- when a server tick lands in the middle of a client tick, the
  previous/current pose pair advances while the client alpha keeps rising from
  its old value. The rendered pose jumps by roughly half a tick of motion, then
  the alpha restarts. This is a sawtooth in velocity, visible as trembling
  while the structure or the camera moves;
- in multiplayer the renderer previously read `ClientStructureSnapshot.pose()`
  directly, so the same problem was worse: a raw ~20 Hz pose with no
  interpolation at all.

The fix is to stop using a tick-clock alpha for structures and to sample a
**timestamped timeline** at frame rate:

- `PoseTimeline` stores the previous and current pose with the monotonic time
  each was published. Render sampling uses `now - delay`, where `delay` is a
  smoothed (EMA) estimate of the publish interval, clamped to
  `[10 ms, 250 ms]`. At 20 TPS this is one tick (50 ms).
- The sample point is always between the previous and current pose. The
  timeline never extrapolates: if a publish is late it holds the current pose
  until the next update arrives.
- Explicit teleports (`PhysicsStructure#teleport`), direct pose sets, reindexing
  and structure splits (new body id) reset the timeline. Implicit jumps
  (`> 16` blocks or `> 120°` in one update, or a stale gap with movement) also
  snap instead of interpolating.
- The same sampler is used for local singleplayer poses
  (`PhysicsStructure#renderStateAt`) and network poses
  (`ClientStructureCache#interpolatedPose`, fed by `applyPose` timestamps).
- All renderers that call `PhysicsStructure#renderState(double)` now receive
  the timestamped frame-time sample. The `alpha` argument is retained for
  source compatibility but is no longer used for pose selection.
  `PhysicsStructure#interpolatedPose(double alpha)` remains available as the
  legacy deterministic tick-alpha sampler.

This preserves exactly one physics step per game tick and adds the same
one-pose delay vanilla uses for entity interpolation, not a fixed extra tick of
lag. When the tick rate changes, the EMA converges to the new interval within a
few ticks.

## Fixed step choice: 1/60 vs 1/120

The default remains **1/60 s (16.67 ms)**.

- Once step count is stable, 1/60 is smooth at Minecraft's 20 TPS and typical
  60-240 FPS render rates.
- 1/120 doubles Bullet work and contact solving for every structure. It can help
  very fast rotation or thin/fast collision, but was not needed to remove the
  trembling.
- Users who want 1/120 can set `fixedTimeStep = 0.008333333333333333`.
  `CrythstConfig#sanitised()` raises `maxSubSteps` to at least 7 for 20 TPS, so
  a stale `maxSubSteps = 4` cannot silently halve simulation speed.
- If you change the server tick rate with `/tick rate`, prefer a fixed step
  that divides the tick duration (for example 1/60 at 20 or 30 TPS, 1/120 at
  20, 40 or 60 TPS). Otherwise the step count per tick must alternate by one,
  which is inherent to integer substeps.

## Configuration

| Field | Default | Meaning |
| --- | --- | --- |
| `fixedTimeStep` | `1/60` | Physics substep length in seconds. |
| `maxSubSteps` | `8` | Catch-up cap per tick. Sanitising raises it if `fixedTimeStep` is small. |
| `serverTickTiming` | `true` | Use one server tick of game time instead of wall-clock deltas. |
| `maxFrameSeconds` | `0.25` | Wall-clock catch-up cap; used only when `serverTickTiming = false`. |

## Diagnostics

Run:

```text
/crythst timing
```

Example:

```text
Crythst timing | mode=server-tick tick=1420 wallDt=50.01ms simDt=50.00ms
steps=3 fixedAlpha=0.000 poseDelta=0.0184m poseAngle=0.0012rad
stepJitter=0.00 poseJitter=0.00003m | render pose = timestamped, one-tick delay
```

The server-side command only knows about tick-to-tick pose deltas. The current
`/crythst timing` implementation also appends the batched renderer's
rate-limited counter. It resolves
`BatchedStructureRenderer.poseDiagnosticsText()` reflectively, so the command
still works on a dedicated server (where the client class is absent), and adds
the text only when that class is available.

`BatchedStructureRenderer.poseDiagnosticsText()` reports the maximum
frame-to-frame structure pose delta over the last two seconds and the number of
tracked bodies. It is also logged at `DEBUG` at most once per two seconds when
the delta exceeds 5 cm/frame.

Fields:

- `wallDt`: measured wall time since the previous tick (diagnostic only in
  server-tick mode).
- `simDt`: time actually handed to `FixedStepClock`.
- `steps`: fixed substeps run this tick.
- `fixedAlpha`: `FixedStepClock` remainder (not the render alpha).
- `poseDelta` / `poseAngle`: largest change between the two server-tick poses
  in the legacy tick-alpha buffer (translation in metres, rotation in radians).
  These are simulation-side values; rendered frame deltas come from
  `BatchedStructureRenderer#poseDiagnosticsText()`.
- `stepJitter`: mean absolute deviation of step counts over 40 ticks. A stable
  3-step tick reports `0.00`; alternating 2/4 reports `1.00`.
- `poseJitter`: mean absolute tick-to-tick change of the per-tick pose delta
  over 40 ticks. Constant motion is near zero; variable substep counts are
  clearly non-zero.

`/crythst status` and `/crythst align` are unchanged.

## Multiplayer path

The server still sends pose updates only when the structure moved (1 mm /
1e-8 quaternion threshold), so a resting structure produces no network traffic.

`ClientStructureCache.applyPose` now timestamps each received pose into the same
`PoseTimeline` used by local structures. `BatchedStructureRenderer` samples
`ClientStructureCache#interpolatedPose(bodyId, frameNanos)` instead of reading
the raw snapshot pose. A full sync/resync is treated as a snap point, and body
removal drops the timeline. The result is frame-rate smooth remote structures
with one smoothed receive interval of delay; network arrival jitter that exceeds
that window is held (clamped), never extrapolated.
