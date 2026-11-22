# Third-party notices

Crythst is MIT-licensed. This is an engineering notice, not legal advice.
The upstream/midstream/downstream relation is summarized in `NOTICE` and the
README section “上中下游关系”.

## Bundled / runtime

### libbulletjme 23.2.0

- https://github.com/stephengold/Libbulletjme
- BSD 3-Clause (Java/JNI glue); Bullet Physics zlib; V-HACD BSD-style.
- JVM classes are nested in the jar; desktop and Android natives are unpacked
  into `native/` and loaded at runtime.
- Full license: https://github.com/stephengold/Libbulletjme/blob/master/LICENSE

## Build / runtime APIs (not bundled)

- **Fabric Loader / Fabric API / Fabric Loom** — Apache License 2.0.
- **Minecraft** — Mojang Studios/Microsoft; compile/runtime dependency, not
  distributed. Test textures under `assets/crythst/textures/test/**` are
  Minecraft-derived fixtures, excluded from the release jar, and must not be
  redistributed separately.
- **JOML** — MIT; provided by Minecraft at runtime.

## Adapted code

- **EnderTech** — Copyright (c) 2026 Mikaple. Portions of special rendering
  under `io.mikaple.crythst.client.render` are adapted from EnderTech and
  relicensed under MIT by the same copyright holder. The original EnderTech
  project remains All-Rights-Reserved for third parties.

## Compatibility dependency (not bundled)

- **Create-Fly** — https://github.com/LyXith/create-fly-fly; `compileOnly` via
  JitPack; not in the runtime jar. Review its license/provenance independently
  before enabling the Create compatibility layer in a release.

## Reference projects (not dependencies)

- **Sable** — https://github.com/ryanhcode/sable; RyanHCode; PolyForm Shield
  1.0.0. Upstream problem-space/API reference; no Sable source files, comments
  or resources copied into Crythst.
- **sable-port** — https://github.com/LyXith/sable-port; maintained by mikaple
  (the same author as Crythst); PolyForm Shield 1.0.0. 26.3 Fabric
  port/adaptation and engineering reference; not a dependency. Its history
  began from an imported 2.0.3-fabric.26.3-port, not a direct fork of Sable.
  The audit found no copied source files; limited generic patterns and
  API/class-layout/term alignment are documented in
  `docs/license-audit-2026-11-22.md`.

## Summary

- Runtime jar: Crythst (MIT), libbulletjme, and explicitly included components.
- Not bundled: Fabric tooling, Minecraft, JOML, EnderTech original, Create-Fly,
  Sable, sable-port.
- Development-only: Minecraft-derived test textures, excluded from the jar.
