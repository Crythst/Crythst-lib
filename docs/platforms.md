# 平台、原生库与启动器

Crythst 把 libbulletjme 的原生库直接打进 mod jar，运行时根据操作系统、CPU 架构和 Android ABI 选择并解压。本文说明选择逻辑、支持矩阵、启动器注意事项、`SpRelease` → `DpRelease` 切换方法以及 jar 体积取舍。

---

## 1. 支持矩阵

### 桌面端

| 操作系统 | 架构 | jar 内资源 | 解压后的文件名 |
| --- | --- | --- | --- |
| Windows | x86_64 | `native/windows/x86_64/bulletjme.dll` | `bulletjme-x86_64.dll` |
| Windows | arm64 | `native/windows/arm64/bulletjme.dll` | `bulletjme-arm64.dll` |
| macOS | x86_64 | `native/osx/x86_64/libbulletjme.dylib` | `libbulletjme-x86_64.dylib` |
| macOS | arm64 | `native/osx/arm64/libbulletjme.dylib` | `libbulletjme-arm64.dylib` |
| Linux | x86_64 | `native/linux/x86_64/libbulletjme.so` | `libbulletjme-x86_64.so` |
| Linux | arm64 | `native/linux/arm64/libbulletjme.so` | `libbulletjme-arm64.so` |

### Android（FCL / PojavLauncher 等）

| ABI | jar 内资源 | 解压后的文件名 |
| --- | --- | --- |
| `arm64-v8a` | `native/android/arm64-v8a/libbulletjme.so` | `libbulletjme-arm64-v8a.so` |
| `armeabi-v7a` | `native/android/armeabi-v7a/libbulletjme.so` | `libbulletjme-armeabi-v7a.so` |
| `x86` | `native/android/x86/libbulletjme.so` | `libbulletjme-x86.so` |
| `x86_64` | `native/android/x86_64/libbulletjme.so` | `libbulletjme-x86_64.so` |
| `riscv64` | `native/android/riscv64/libbulletjme.so` | `libbulletjme-riscv64.so` |

---

## 2. 原生库选择逻辑

入口是 `io.mikaple.crythst.physics.bullet.BulletNatives#load()`。它是线程安全的，内部用双重检查锁保证只加载一次。

### 2.1 加载顺序

```text
1. -Dcrythst.bullet.nativePath 指定了文件？
      是 → System.load(绝对路径) → 校验版本 → 完成
      否 ↓
2. 原生库已经被其他模组加载？
      是 → 复用 → 完成
      否 ↓
3. 判断是否 Android
      是 → 资源 /native/android/<abi>/libbulletjme.so
      否 → 资源 /native/<os>/<arch>/<文件名>
       ↓
4. 解压到 nativeDir（默认 java.io.tmpdir/crythst-bullet-23.2.0）
       ↓
5. System.load(解压后的文件)
       ↓
6. 启动器回退：如果解压/加载失败，再尝试 System.loadLibrary("bulletjme")
       ↓
7. 调用 NativeLibrary.versionNumber() 校验并记录版本
```

如果通过 `BulletNatives#isLoaded()` 检测到原生库已经被另一个模组加载，它会返回 `true`，`loadedFrom()` 可能记录为 `"already loaded by another mod"`，Crythst 会直接复用。

### 2.2 桌面系统识别

`desktopOs()` 根据 `os.name`：

| `os.name` 包含 | 内部名称 | 资源目录 |
| --- | --- | --- |
| `win` | `windows` | `native/windows/...` |
| `mac` / `darwin` | `osx` | `native/osx/...` |
| `linux` | `linux` | `native/linux/...` |
| 其他 | 抛 `PhysicsException` | — |

### 2.3 架构归一化

`normalizeArch(os.arch)`：

| `os.arch` 的常见值 | 归一化结果 |
| --- | --- |
| `x86_64`, `amd64`, `x86-64` | `x86_64` |
| `aarch64`, `arm64` | `arm64` |
| `arm`, `armv7`, `armv7l`, `armhf` | `arm32` |
| `x86`, `i386`, `i486`, `i586`, `i686` | `x86` |
| `riscv64` | `riscv64` |
| 其他 | 原样返回，最终由平台检查抛异常 |

桌面端只接受 `x86_64` 和 `arm64`；其他桌面架构会抛 `PhysicsException("Unsupported CPU architecture: ...")`。

### 2.4 Android 识别

`isAndroid()` 依次检查：

1. `-Dcrythst.android=true`；
2. `java.runtime.name`、`java.vendor`、`java.vm.name` 是否包含 `android`；
3. 能否加载 `android.os.Build`；
4. 环境变量中是否存在以 `POJAV` 或 `FCL` 开头的键。

任意一项命中就按 Android 处理。

### 2.5 Android ABI 映射

| 归一化架构 | ABI |
| --- | --- |
| `arm64` | `arm64-v8a` |
| `arm32` | `armeabi-v7a` |
| `x86_64` | `x86_64` |
| `x86` | `x86` |
| `riscv64` | `riscv64` |
| 其他 | 抛 `PhysicsException("Unsupported Android ABI: ...")` |

注意：这里用的是当前 **JVM 进程** 的 `os.arch`，不是设备理论支持的 ABI。32 位启动器在 64 位设备上可能报告 `armv7l`，此时会选择 `armeabi-v7a`，这是正确的。

### 2.6 解压位置

默认：

```text
${java.io.tmpdir}/crythst-bullet-23.2.0/
```

可用 `-Dcrythst.bullet.nativeDir=/writable/dir` 覆盖。解压目录必须：

- 可写；
- 允许 `System.load` / `dlopen`；
- 最好位于应用私有目录（Android）或稳定的临时目录（桌面）。

如果同一目录已有旧版本文件，`Files.copy(..., REPLACE_EXISTING)` 会覆盖。

---

## 3. 覆盖属性

| 属性 | 行为 | 适用场景 |
| --- | --- | --- |
| `-Dcrythst.bullet.nativePath=/abs/path/libbulletjme.so` | 直接 `System.load` 该文件，不读 jar 内资源 | 启动器已有正确原生库、调试自定义构建、jar 资源损坏 |
| `-Dcrythst.bullet.nativeDir=/abs/dir` | 把 jar 内原生库解压到指定目录 | Android 私有目录、`/tmp` 不可执行的系统、多个实例隔离 |
| `-Dcrythst.android=true` | 强制走 Android 资源路径 | 启动器伪装成桌面 JVM、自动检测失败 |

示例（Pojav/FCL 的 JVM 参数）：

```text
-Dcrythst.bullet.nativeDir=/data/user/0/<package>/files/crythst
```

示例（改用外部库）：

```text
-Dcrythst.bullet.nativePath=/opt/mods/native/libbulletjme.so
```

> `nativePath` 指向的文件必须与当前进程的 OS/ABI 和 libbulletjme 版本匹配。Crythst 会调用 `NativeLibrary.versionNumber()` 校验能否正常加载并报告版本，但不会强制拒绝不同版本；版本不一致可能导致 JNI 符号或行为不兼容。

---

## 4. FCL / PojavLauncher 注意事项

### 4.1 优先使用启动器提供的库

很多 Android 启动器会通过 `java.library.path` 暴露 `libbulletjme.so`。Crythst 先尝试解压 jar 内对应 ABI 的 `.so`；如果失败，再由 `trySystemLoad()` 回退到 `System.loadLibrary("bulletjme")`。如果成功，日志类似：

```text
Loaded libbulletjme through the launcher's native path
```

这通常比从 jar 解压更可靠，因为启动器更清楚 Android 的库加载限制。

### 4.2 解压失败与回退

Android 10+ 对可执行文件、私有目录和 `dlopen` 有额外限制。某些启动器不允许从应用私有目录 `dlopen` 解压出来的 `.so`。Crythst 的行为：

1. 先尝试解压并 `System.load`；
2. 如果抛 `IOException` 或 `UnsatisfiedLinkError`，再尝试 `System.loadLibrary("bulletjme")`；
3. 仍失败则抛 `PhysicsException("Could not load libbulletjme (...)", cause)`。

如果日志显示解压路径但加载失败，优先：

- 设置 `-Dcrythst.bullet.nativeDir` 到启动器可加载的目录；
- 或设置 `-Dcrythst.bullet.nativePath` 指向启动器已有的 `libbulletjme.so`；
- 确认进程 ABI 与文件 ABI 一致（`arm64-v8a` 进程不能加载 `armeabi-v7a` 的库，反之亦然）。

### 4.3 ABI 与 `os.arch`

部分启动器可能报告不常见的 `os.arch`（例如 `arm64-v8a`、`aarch64`、`armv8l`）。Crythst 已归一化常见别名；如果仍报 `Unsupported Android ABI`，用 `-Dcrythst.bullet.nativePath` 直接指定正确文件。

### 4.4 32 位与 64 位

- 32 位 Android 进程使用 `armeabi-v7a` 或 `x86`；
- 64 位 Android 进程使用 `arm64-v8a`、`x86_64` 或 `riscv64`；
- 设备支持 64 位不代表启动器进程是 64 位；以 `os.arch` 为准。

### 4.5 其他模组已加载 bulletjme

如果另一个模组已经加载了 libbulletjme，Crythst 会复用。此时：

- 日志会显示 `already loaded by another mod`；
- 实际版本以 `NativeLibrary.versionNumber()` 为准；
- 若版本差异很大，可能出现 `NoSuchMethodError` / `UnsatisfiedLinkError`；请统一到 libbulletjme 23.2.0 或联系该模组作者。

### 4.6 桌面端 ARM

- Linux arm64、Windows arm64、macOS arm64 都包含原生库；
- macOS 在 Rosetta 2 下运行 x86_64 JVM 时，`os.arch` 通常是 `x86_64`，会加载 x86_64 dylib；原生 arm64 JVM 会加载 arm64 dylib；
- Windows on ARM 上如果使用 x86_64 JVM（模拟），会加载 x86_64 DLL；原生 arm64 JVM 会加载 arm64 DLL。

---

## 5. `SpRelease` → `DpRelease`

libbulletjme 提供单精度（`SpRelease`）和双精度（`DpRelease`）两种原生构建。默认是 `SpRelease`：

```properties
# gradle.properties
libbulletjme_version=23.2.0
libbulletjme_native_flavour=SpRelease
```

### 5.1 切换方法

把 `libbulletjme_native_flavour` 改成 `DpRelease`：

```properties
libbulletjme_native_flavour=DpRelease
```

然后重新构建：

```bash
./gradlew clean build
```

`build.gradle` 中所有平台依赖都使用同一个 classifier：

```groovy
bulletNatives "com.github.stephengold:Libbulletjme-Linux64:${libbulletjme_version}:${libbulletjme_native_flavour}"
// ... Linux_ARM64 / Windows64 / Windows_ARM64 / MacOSX64 / MacOSX_ARM64
bulletAndroidNatives "com.github.stephengold:Libbulletjme-Android:${libbulletjme_version}:${libbulletjme_native_flavour}@aar"
```

因此改一处即可让桌面和 Android 都切换到双精度原生库。**Java 类不需要改动**；libbulletjme 的 JVM 类与原生库通过同一套 JNI 接口通信。

### 5.2 验证

启动或测试时，`BulletNatives#validateVersion()` 会记录：

```text
libbulletjme native version 23.2.0 (double precision: false)
```

切换到 `DpRelease` 后应显示 `true`。

### 5.3 取舍

| 方面 | `SpRelease`（默认） | `DpRelease` |
| --- | --- | --- |
| 坐标精度 | 单精度；Crythst 用可重定位原点补偿 | 双精度；远坐标更稳定 |
| 内存/带宽 | 较低 | 较高 |
| 性能 | 通常略快 | 通常略慢 |
| 原生库体积 | 较小 | 较大 |
| 适用场景 | 普通世界、性能优先 | 超大世界、远坐标、对精度敏感 |

即使使用 `DpRelease`，Crythst 的原点重定位仍然有意义：它把模拟区域保持在 Bullet 原生空间的中心附近，减少 broadphase 和求解器的数值压力。

### 5.4 清理旧资源

原生库是通过 `collectNativeLibraries` 复制到 `build/generated/native-resources` 再打进 jar 的。切换 flavour 后建议执行 `./gradlew clean build`，避免旧的 `SpRelease` 文件残留在增量构建目录中。

---

## 6. jar 体积

当前构建把所有平台的原生库都打进主 jar：

- 11 个原生文件（6 个桌面 + 5 个 Android）；
- 解压后总计约 **35.7 MB**；
- 压缩后的完整 jar 约 **15 MB**（随版本和压缩率略有变化）。

`sourcesJar` 通过以下配置排除原生库：

```groovy
tasks.named('sourcesJar').configure {
    dependsOn tasks.named('collectNativeLibraries')
    exclude 'native/**'
}
```

### 6.1 裁剪思路

如果只面向部分平台，可以在 `build.gradle` 中删除不需要的 `bulletNatives` / `bulletAndroidNatives` 行，或只保留目标 ABI。示例思路（不要直接照抄到本仓库，按需修改）：

```groovy
// 只保留 Linux x86_64 桌面原生库
bulletNatives "com.github.stephengold:Libbulletjme-Linux64:${libbulletjme_version}:${libbulletjme_native_flavour}"
```

Android 如果只保留 `arm64-v8a`，仍需要 `Libbulletjme-Android` AAR 的 `jni/` 内容；`collectNativeLibraries` 会把 `jni/**` 重映射到 `native/android/**`。裁剪后再清理构建目录。

### 6.2 不要只删资源

如果你从 jar 中排除 `native/**`，必须确保运行环境通过以下方式之一提供原生库：

- `-Dcrythst.bullet.nativePath`；
- 启动器的 `java.library.path`；
- 另一个模组已经加载了兼容的 libbulletjme。

否则会在首次创建物理世界时抛 `PhysicsException`。

### 6.3 取舍建议

- **发布通用 jar**：保留全部 11 个原生库，开箱即用；
- **桌面专用包**：移除 Android AAR，jar 明显变小；
- **Android 专用包**：移除 6 个桌面原生库，只保留目标 ABI；
- **性能/精度优先**：使用 `DpRelease`，接受更大的原生库和略慢的求解。

---

## 7. 构建与运行

```bash
# 完整构建（会先收集原生库）
./gradlew build

# 运行测试（测试任务依赖 collectNativeLibraries）
./gradlew test

# 开发客户端 / 服务端
./gradlew runClient
./gradlew runServer
```

要求 JDK 25。Gradle 通过 toolchain 检查 `java_version=25`。

原生库收集任务：

- `unpackAndroidAar`：从 `Libbulletjme-Android` AAR 中取出 `jni/**`；
- `collectNativeLibraries`：从 6 个桌面 artifact 中取出 `native/**`，把 Android 的 `jni/**` 重映射为 `native/android/**`，统一复制到 `build/generated/native-resources`；
- `processResources` 依赖它，所以 `build` / `runClient` 会自动执行。

---

## 8. 故障排查

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `PhysicsException: Bundled native library is missing: /native/...` | OS/架构不在支持矩阵，或资源未打进 jar | 检查 `os.name` / `os.arch`；用 `nativePath`；确认 `build.gradle` 包含对应 artifact |
| `PhysicsException: Unsupported operating system` | 非 Windows/macOS/Linux 桌面系统 | 使用 Android 检测或换平台；Crythst 桌面端不支持其他 OS |
| `PhysicsException: Unsupported CPU architecture` | 桌面 `os.arch` 不是 x86_64/arm64 | 使用对应架构的 JVM；或用 `nativePath` 提供库 |
| `PhysicsException: Unsupported Android ABI` | `os.arch` 未映射 | 用 `-Dcrythst.bullet.nativePath` 指定正确 ABI 的 `.so` |
| `UnsatisfiedLinkError`（解压后） | 目录不可执行、ABI 不匹配、Android `dlopen` 限制 | 设置 `nativeDir` 到应用私有/可执行目录；或 `nativePath`；确认 ABI |
| Android 上解压失败但 `System.loadLibrary` 成功 | 启动器库路径可用 | 正常行为，日志显示 “launcher's native path” |
| 日志显示 `already loaded by another mod` | 其他模组先加载了 bulletjme | 确认版本兼容；必要时移除重复模组或统一版本 |
| 日志 `double precision: false` | 当前是 `SpRelease` | 若需要双精度，改 `libbulletjme_native_flavour=DpRelease` 并 `clean build` |
| 启动后没有调试线框 | 只在单人集成服务器启用；`debug` 未开启 | 单人世界执行 `/crythst debug`；多人环境暂不支持 |
| `jar` 过大 | 包含全部平台原生库 | 参考第 6 节裁剪 |

---

## 9. 许可

libbulletjme 的原生库和 JVM 类遵循其自身许可：

- libbulletjme：BSD 3-Clause；
- Bullet Physics：zlib；
- V-HACD：BSD 风格许可。

Crythst 本身是 MIT。完整第三方声明见仓库根目录的 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
