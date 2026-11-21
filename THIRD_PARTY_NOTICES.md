# Third-party notices

Crythst is licensed under the MIT License. It depends on the following
components at build or runtime time.

## libbulletjme

- Project: https://github.com/stephengold/Libbulletjme
- Version: 23.2.0
- License: BSD 3-Clause (Java and JNI glue), with Bullet Physics under the
  zlib license and V-HACD under its own BSD-style license.
- Usage: JVM classes are nested inside the mod jar; native libraries for
  Windows/macOS/Linux (x86_64, arm64) and Android (arm64-v8a, armeabi-v7a,
  x86, x86_64, riscv64) are unpacked into `native/` resources and loaded at
  runtime.

The full libbulletjme license text is available at
https://github.com/stephengold/Libbulletjme/blob/master/LICENSE

## Fabric Loader / Fabric API / Fabric Loom

- Projects: https://fabricmc.net/
- License: Apache License 2.0
- Usage: build tooling and mod loader/runtime APIs (not bundled).

## Minecraft

- Copyright Mojang Studios / Microsoft.
- Usage: compile-time and runtime dependency; not distributed by this project.

## JOML

- Project: https://github.com/JOML-CI/JOML
- License: MIT
- Usage: public math types. JOML is provided by Minecraft at runtime.
