# Player gravity: camera and collision frame

This document describes how a directional player gravity field (a non-down
"down" vector) rotates the client camera, what API the rest of the codebase
uses to get the player's gravity frame, and what is intentionally left to
vanilla.

The feature is implemented by:

| File | Purpose |
| --- | --- |
| `integration/minecraft/PlayerGravity.java` | Per-player gravity direction plus the gravity-frame math (`up`, `viewRotation`, `lookDirection`, `hasDirectionalGravity`). |
| `mixin/client/CameraGravityMixin.java` | Rotates the extracted camera render state into the gravity frame. |
| `resources/crythst-client.mixins.json` | Client mixin config for the class above. |

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
  identity when no directional gravity applies.
* `lookDirection(Player)` is `viewRotation` applied to
  `player.getLookAngle()`, for interaction raycasts and other gameplay code
  that must match what the rotated camera shows. Without directional gravity
  it is a plain copy of the vanilla look vector.

### Collision box

The collision code must build the player OBB with `PlayerGravity.up(player)` as
its local `+Y` (equivalently, `PlayerGravity.direction(player)` as local
down). `up()` never returns `null`, so the default path naturally produces the
vanilla axis-aligned box.

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
Quaternionf Q = viewRotation(player);           // +Y -> player up
state.orientation.set(Q * state.orientation);  // left/product in world space
```

Left-multiplying carries the whole camera basis (forward, up and left) into
the gravity frame. At zero pitch the camera's local up becomes exactly the
player's up; at other pitches it is the player's up tilted by pitch, which is
the expected rigid-body result. The view matrix is then rebuilt exactly like
vanilla:

```java
state.viewRotationMatrix.rotation(conjugate(state.orientation));
```

The culling frustum is rebuilt from that matrix, `state.projectionMatrix` and
the frustum's own camera position, so terrain is not culled against the
unrotated view. The camera's `forwardVector()`, `upVector()` and `leftVector()`
are also rotated, keeping sound, particles and other camera-basis consumers
consistent with the picture.

The camera position is not changed; only its orientation.

## Mouse look and view direction

Mouse input still writes the vanilla player yaw/pitch. Those values are
interpreted in the gravity-local frame by the composition above: yaw is the
rotation around the *gravity* up axis as seen on screen, and pitch tilts around
the camera's local right axis. The rendered view direction is therefore

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
* Third-person views are rotated too, which keeps the world consistent with
  the player's gravity frame.
* Dedicated-server gravity sync is not implemented; the client and integrated
  server share the `PlayerGravity` map in singleplayer.
* `LOOK` gravity mode derives "down" from the vanilla look vector. Because the
  camera is then rotated by `Q`, the resulting screenshot is a fixed point of
  that mapping only approximately; this mode is intended for experimentation,
  not for precise platforming.
* The client was not run as part of this change (`runClient` was intentionally
  skipped); validation was by compilation and by checking the JOML composition
  against `Camera.getViewRotationMatrix`'s conjugate convention.
