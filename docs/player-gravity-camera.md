# Player gravity: camera and collision frame

This document describes how a directional player gravity field (a non-down
"down" vector) rotates the client camera, what API the rest of the codebase
uses to get the player's gravity frame, and what is intentionally left to
vanilla.

The feature is implemented by:

| File | Purpose |
| --- | --- |
| `integration/minecraft/PlayerGravity.java` | Per-player gravity direction plus the gravity-frame math (`up`, `viewRotation`, `lookDirection`, `rotationForUp`, `rotateOffset`, `hasDirectionalGravity`). |
| `integration/minecraft/player/PlayerRenderFrame.java` | Pure feet-frame helpers shared by the camera, avatar, name tag and tests. |
| `mixin/client/CameraGravityMixin.java` | Places the extracted camera eye/position and orientation in the feet frame. |
| `mixin/client/AvatarRendererGravityMixin.java` | Rotates the third-person avatar around its feet. |
| `mixin/client/AvatarNameTagGravityMixin.java` | Moves the name-tag anchor into the same frame. |
| `integration/fabric/client/PlayerObbDebugRenderer.java` | Draws the oriented F3 + B hitbox. |
| `mixin/client/EntityHitboxDebugRendererMixin.java` | Suppresses the vanilla axis-aligned F3 + B box for these players. |
| `resources/crythst-client.mixins.json` | Client mixin config for the classes above. |

## Enabling

The camera side is gated by `CrythstConfig#playerCameraGravity` (default
`true`). Gravity itself is installed with `/crythst playergravity ...` or
`PlayerGravity.setFixed/setWorldDirection/setLook`. With no override (or an
override that is numerically straight down) **nothing is changed**: every
client injection returns before touching the render state, so the default
down-gravity experience is bit-for-bit identical to vanilla.

## Gravity frame API

`PlayerGravity` exposes the frame that both the camera and the collision code
must agree on:

```java
Vector3d down = PlayerGravity.direction(player);          // null = vanilla
Vector3d up   = PlayerGravity.up(player);                 // -down, or (0,1,0)
boolean  active = PlayerGravity.hasDirectionalGravity(p); // safe guard
Quaterniond q = PlayerGravity.viewRotation(player);       // +Y -> up
Vector3d look = PlayerGravity.lookDirection(player);      // q * vanilla look
```

* `direction(Player)` is unchanged: it returns the downward unit vector, or
  `null` when vanilla gravity applies.
* `up(Player)` is always a usable unit vector and falls back to world up
  `(0,1,0)`.
* `hasDirectionalGravity(Player)` is `false` for `null` and for directions
  within `1e-4 rad` of `(0,-1,0)`. Use it as the early-out in any client or
  gameplay code that would otherwise alter vanilla.
* `viewRotation(Player)` is the shortest-arc rotation that maps world `+Y`
  onto `up`, i.e. vanilla space into the player's gravity frame. It is the
  identity when no directional gravity applies. Its pure core is
  `rotationForUp(Vector3dc)`.
* `lookDirection(Player)` is `viewRotation` applied to
  `player.getLookAngle()`, for interaction raycasts and other gameplay code
  that must match what the rotated camera shows. Without directional gravity
  it is a plain copy of the vanilla look vector.
* `rotateOffset(rotation, eye, cameraPosition)` applies the same rotation to a
  camera offset: `eye + Q * (cameraPosition - eye)`. A zero offset (first
  person) is unchanged. The live client camera uses the feet-frame equivalent
  (`PlayerRenderFrame`: `feet + Q * (vanillaCameraPosition - feet)`) so its eye
  moves with the avatar and OBB; see [Camera math](#camera-math).

### `LOOK` mode is a snapshot

`/crythst playergravity look` (and `PlayerGravity.setLook`) samples the player's
view direction **once**, at the moment it is called, and stores it as the
gravity down vector. It deliberately does **not** keep following the mouse.

Following the live look is a feedback loop: with
`Q = rotationTo(worldUp, -look)`, the rendered forward `Q * look` is invariant
under yaw at zero pitch (it stays world up), so the world only rolls and the
camera never turns. Freezing `Q` makes every later mouse movement a normal
rotation inside the gravity frame. Re-run the command to sample a new
direction.

If you issue the command while looking straight down, the sampled down vector is
vanilla down `(0,-1,0)`, `hasDirectionalGravity` stays `false`, and no camera,
avatar or hitbox rotation happens. **Look horizontally (for example at a wall)
when enabling `look` mode** to see an effect. The sample is logged as:

```text
Crythst player gravity look snapshot: down=(x, y, z)
```

### Collision box

The non-default-gravity collision pass builds the player OBB with
`PlayerGravity.up(player)` as its local `+Y` (equivalently,
`PlayerGravity.direction(player)` as local down). `up()` never returns `null`,
so the default path naturally produces the vanilla axis-aligned box. See
[player-obb-collision.md](player-obb-collision.md) for that post-move pass; it
is separate from the movement-integrated `EntityStructureCollision` path.

## Camera math

Vanilla's `Camera` builds its orientation from yaw/pitch:

```
R = rotationYXZ(pi - yaw, -pitch, 0)
```

`R` maps camera-local coordinates to world coordinates; `R * (0,1,0)` is the
camera's world up. `Camera.getViewRotationMatrix` is the inverse of `R`, built
as `Matrix4f().rotation(conjugate(R))`.

`CameraGravityMixin` injects at the end of
`Camera.extractRenderState(CameraRenderState, DeltaTracker)` and, only when
`hasDirectionalGravity(player)` is true, computes:

```java
Quaternionf Q = viewRotation(player);              // +Y -> player up
Quaternionf R = ((Camera) (Object) this).rotation(); // authoritative vanilla
state.orientation.set(Q * R);
```

The source is the `Camera`'s own rotation, not `state.orientation`. Portal
capture and other nested extraction paths can call `extractRenderState` again
on a state that has already been gravity-rotated; reading `Camera#rotation()`
makes the injection idempotent and prevents `Q` from being applied twice.

This gives exactly the requested frame:

```
forward'  = Q * R * (0,0,-1) = Q * vanillaLook
local up' = Q * (0,1,0)      = gravity up
```

At zero pitch the rendered view is horizontal in the gravity frame. At other
pitches its component along `up` is unchanged from vanilla (the pitch is
preserved). The view matrix is rebuilt exactly like vanilla:

```java
state.viewRotationMatrix.rotation(conjugate(state.orientation));
```

### Camera position: feet frame

Vanilla places the camera at
`position + (0, eyeHeight, 0) + thirdPersonOffset` in the world-up frame.
Crythst uses the same feet anchor as the avatar and the OBB and rotates that
whole vector around `position`:

```java
state.pos = position + Q * (vanillaCameraPosition - position);
state.blockPos = BlockPos.containing(state.pos);
```

This is exactly:

```text
eye'    = position + Q * (0, eyeHeight, 0)
camera' = eye' + Q * thirdPersonOffset
```

for third person, and `camera' = eye'` in first person. The first-person eye is
therefore moved too: it is no longer the vanilla vertical eye. That is required
for consistency — the (invisible) avatar and its collision OBB pivot around the
feet, so the camera must pivot around the same point or the view drifts away
from the body when gravity is tilted.

`state.xRot`/`state.yRot` are then derived from the rotated forward vector.
The culling frustum is rebuilt from the rotated view matrix,
`state.projectionMatrix` and `state.pos`. The camera's
`forwardVector()`, `upVector()` and `leftVector()` are also rotated, keeping
sound, particles and other camera-basis consumers consistent with the picture.
The orientation still rotates independently of the position; that is the point
of the feature.

Once per second the client logs the applied frame:

```text
Crythst gravity camera: q=(x, y, z, w) up=(x, y, z) forward=(x, y, z) posOffset=(x, y, z)
```

`posOffset` is `state.pos - eye'`, so it is `(0.000, 0.000, 0.000)` in first
person and the rotated camera offset in third person. The line is emitted at
most once per second while gravity is directional, `playerCameraGravity` is
enabled and the camera belongs to the local player.

### Avatar and F3 + B

`AvatarRendererGravityMixin` targets `LivingEntityRenderer.setupRotations` (the
declaring class, filtered to `AvatarRenderState`) and injects at its head. The
pose stack is already translated to the entity's feet and uniformly scaled, so
right-multiplying the current matrix by `rotation(Q)` yields

```text
T_feet * S(state.scale) * Q * R_bodyYaw
       * S(-1, -1, 1) * S_model * T(0, -1.501, 0)
```

The whole model turns around its feet, before vanilla's yaw/pose transforms in
matrix order but in the same world frame as the camera. The `(-1,-1,1)` flip
and `-1.501` translation are inside `Q`, so they cannot move the pivot. Held
items and armour are layers inside that pose stack and rotate with it.

`AvatarNameTagGravityMixin` moves the name-tag attachment separately because it
is submitted after the model stack is popped. `submitNameTag` adds its own
world-space `+0.5` after the attachment, so the mixin stores
`Q * (attachment + (0, 0.5, 0)) - (0, 0.5, 0)`. The final world anchor is then
`position + Q * (attachment + (0, 0.5, 0))`, above the rotated head. The text
stays camera-billboarded.

For F3 + B, `EntityHitboxDebugRendererMixin` cancels vanilla's axis-aligned
`showHitboxes` for directional-gravity players and `PlayerObbDebugRenderer`
draws the OBB built from the same `PlayerGravity.up(player)` vector. The OBB
centre is `position + up * (bbHeight / 2)` and its feet point is the entity
position. The debug box is shifted by the entity's render interpolation so it
sits on the rendered avatar rather than one tick behind it.

## Mouse look and view direction

Mouse input still writes the vanilla player yaw/pitch, and those values are
interpreted in the gravity-local frame by the composition above. `Q` is frozen
when gravity is installed, so yaw turns the view around the gravity up axis and
pitch tilts it normally. The rendered view direction is

```
viewDirection = Q * player.getLookAngle()
```

which is exactly `PlayerGravity.lookDirection(player)`. Use that method for
raycasts, block interaction and any gameplay direction that must match the
camera; do not use `player.getLookAngle()` directly while directional gravity
is active.

Note that the stored `player.getYRot()`/`getXRot()` remain vanilla values.
A future input-level mixin could remap mouse deltas into the gravity frame so
that yaw accumulates around `up` instead of world `+Y`; that is not part of
this change.

## Registration

`fabric.mod.json` must list the client mixin config next to the existing one:

```json
"mixins": [
  "crythst.mixins.json",
  "crythst-client.mixins.json"
]
```

`crythst-client.mixins.json` uses package
`io.mikaple.crythst.mixin.client`, `compatibilityLevel` `JAVA_25`, and the
`client` list (so it is never loaded on a dedicated server).

## Scope and limitations

* Only the local player's camera is rotated. If the camera is attached to a
  different entity (spectator follow, panorama), the render state is left
  untouched.
* Third-person views are rotated too: orientation, eye offset, avatar, name-tag
  anchor and F3 + B OBB all use the same `Q`, which keeps the whole scene
  consistent with the player's gravity frame.
* Dedicated-server gravity sync is not implemented; the client and integrated
  server share the `PlayerGravity` map in singleplayer.
* `LOOK` gravity mode is a snapshot, not a live follow. Re-run
  `/crythst playergravity look` to sample a new direction. Sampling while
  looking straight down yields vanilla gravity, so aim horizontally.
* Third-person camera collision (`Camera#getMaxZoom`) still runs in the vanilla
  frame before the offset is rotated. The resulting position is rigidly
  rotated, so the distance is preserved, but the obstacle clipping ray is not
  re-cast in the rotated frame.
* The client was not run as part of this change (`runClient` was intentionally
  skipped); validation was by compilation and the pure frame tests in
  `PlayerGravityFrameTest`.
