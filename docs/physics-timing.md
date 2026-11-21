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

**Tick-locked simulation + one render-pose publish per server tick + Minecraft
tick partial tick as the render alpha.**

- `FixedStepClock` is fed exactly one server tick of game time per tick:
  `1 / level.getServer().tickRateManager().tickrate()`.
- At 20 TPS with the default `fixedTimeStep = 1/60`, every tick runs exactly
  three substeps.
- `syncRenderPose()` runs once per tick, **after** the whole substep batch.
  The renderer interpolates the previous tick-boundary pose to the current
  tick-boundary pose using the client partial tick.
- The physics clock's accumulator (`world.interpolationAlpha()`) is **not** the
  render alpha. It describes the remainder to the next substep, and substeps
  are executed in a burst. Using it while publishing only tick-boundary poses
  would make the structure crawl for most of the tick and then jump.
- `/tick` rate changes are respected: the budget is read every tick.
- When the tick rate manager is frozen (and not manually stepping), physics and
  block logic are skipped, but the current render pose is retained.

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
stepJitter=0.00 poseJitter=0.00003m | pose alpha = Minecraft tick partial tick
```

Fields:

- `wallDt`: measured wall time since the previous tick (diagnostic only in
  server-tick mode).
- `simDt`: time actually handed to `FixedStepClock`.
- `steps`: fixed substeps run this tick.
- `fixedAlpha`: `FixedStepClock` remainder (not the render alpha).
- `poseDelta` / `poseAngle`: largest change between the two poses currently in
  the structure's interpolation buffer (translation in metres, rotation in
  radians).
- `stepJitter`: mean absolute deviation of step counts over 40 ticks. A stable
  3-step tick reports `0.00`; alternating 2/4 reports `1.00`.
- `poseJitter`: mean absolute tick-to-tick change of the per-tick pose delta
  over 40 ticks. Constant motion is near zero; variable substep counts are
  clearly non-zero.

`/crythst status` and `/crythst align` are unchanged.

## Multiplayer limitation

The singleplayer render path (`ClientPhysicsView` -> `PhysicsStructure` ->
`InterpolatedPose`) now uses the stable tick policy above.

The synced multiplayer path is different: `BatchedStructureRenderer` reads the
latest `ClientStructureSnapshot.pose()` directly from `ClientStructureCache`.
That cache is intentionally passive and currently has no interpolation buffer,
so remote structures still update at ~20 Hz without smoothing. The server-side
pose diff already uses a 1 mm / 1e-8 quaternion threshold to avoid sending
resting-body noise.

Fixing remote interpolation requires a small change in the client network/cache
layer (store the previous pose and timestamp/sequence in
`ClientStructureSnapshot`, sample between them in the renderer). That package is
owned by the multiplayer sync work and was intentionally not modified here.
