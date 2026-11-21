package io.mikaple.crythst.physics.bullet;

import io.mikaple.crythst.Crythst;
import io.mikaple.crythst.physics.PhysicsException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the libbulletjme JNI library from resources bundled in the mod jar.
 *
 * <p>Desktop natives live under {@code native/<os>/<arch>/}. Android natives
 * (FCL, PojavLauncher, ...) live under {@code native/android/<abi>/}. Extraction
 * is used because launchers rarely put mod natives on
 * {@code java.library.path}. Overrides:</p>
 *
 * <ul>
 *   <li>{@code -Dcrythst.bullet.nativePath=/absolute/path/libbulletjme.so}</li>
 *   <li>{@code -Dcrythst.bullet.nativeDir=/writable/dir}</li>
 *   <li>{@code -Dcrythst.android=true} to force Android detection</li>
 * </ul>
 */
public final class BulletNatives {
    public static final String LIBRARY_NAME = "bulletjme";
    private static final Object LOCK = new Object();

    private static volatile boolean loaded;
    private static volatile String loadedFrom;

    private BulletNatives() {
    }

    public static boolean isLoaded() {
        if (loaded) {
            return true;
        }
        try {
            // Triggers UnsatisfiedLinkError when the JNI library is absent.
            com.jme3.bullet.util.NativeLibrary.versionNumber();
            loaded = true;
            loadedFrom = "already loaded by another mod";
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String loadedFrom() {
        return loadedFrom;
    }

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }

            final String explicit = System.getProperty("crythst.bullet.nativePath");
            if (explicit != null && !explicit.isBlank()) {
                loadExplicit(Path.of(explicit));
                return;
            }

            // If some other mod already loaded the exact JNI library, reuse it.
            if (isLoaded()) {
                return;
            }

            final boolean android = isAndroid();
            final String resource = android ? androidResource() : desktopResource();
            final String fileName = fileNameForCurrentPlatform();

            try (InputStream stream = BulletNatives.class.getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new PhysicsException("Bundled native library is missing: " + resource
                            + " (platform: " + System.getProperty("os.name") + "/"
                            + System.getProperty("os.arch") + ", android=" + android + ")");
                }

                final Path target = extractionTarget(fileName);
                Files.createDirectories(target.getParent());
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
                System.load(target.toAbsolutePath().toString());
                loaded = true;
                loadedFrom = target.toAbsolutePath().toString();
                Crythst.LOGGER.info("Loaded libbulletjme from {}", loadedFrom);
            } catch (PhysicsException e) {
                throw e;
            } catch (IOException | UnsatisfiedLinkError e) {
                // Fall back to a launcher-provided native path. This is common
                // on Android, where dlopen from app-private storage can be
                // blocked by the OS.
                if (trySystemLoad()) {
                    return;
                }
                throw new PhysicsException("Could not load libbulletjme (" + resource + ")", e);
            }

            validateVersion();
        }
    }

    private static void loadExplicit(Path path) {
        try {
            if (!Files.isRegularFile(path)) {
                throw new PhysicsException("crythst.bullet.nativePath does not exist: " + path);
            }
            System.load(path.toAbsolutePath().toString());
            loaded = true;
            loadedFrom = path.toAbsolutePath().toString();
            Crythst.LOGGER.info("Loaded libbulletjme from explicit path {}", loadedFrom);
            validateVersion();
        } catch (PhysicsException e) {
            throw e;
        } catch (UnsatisfiedLinkError e) {
            throw new PhysicsException("Could not load " + path, e);
        }
    }

    private static boolean trySystemLoad() {
        try {
            System.loadLibrary(LIBRARY_NAME);
            if (isLoaded()) {
                loadedFrom = "System.loadLibrary(" + LIBRARY_NAME + ")";
                Crythst.LOGGER.info("Loaded libbulletjme through the launcher's native path");
                return true;
            }
        } catch (Throwable ignored) {
            // Expected on most desktop setups.
        }
        return false;
    }

    private static void validateVersion() {
        try {
            final String version = com.jme3.bullet.util.NativeLibrary.versionNumber();
            Crythst.LOGGER.info("libbulletjme native version {} (double precision: {})",
                    version, com.jme3.bullet.util.NativeLibrary.isDoublePrecision());
        } catch (Throwable e) {
            throw new PhysicsException("libbulletjme loaded but failed to report its version", e);
        }
    }

    private static Path extractionTarget(String fileName) {
        final String configuredDir = System.getProperty("crythst.bullet.nativeDir");
        final Path directory;
        if (configuredDir != null && !configuredDir.isBlank()) {
            directory = Path.of(configuredDir);
        } else {
            directory = Path.of(System.getProperty("java.io.tmpdir"), "crythst-bullet-23.2.0");
        }
        return directory.resolve(fileName);
    }

    private static String fileNameForCurrentPlatform() {
        if (isAndroid()) {
            return "libbulletjme-" + androidAbi() + ".so";
        }
        final String os = desktopOs();
        return switch (os) {
            case "windows" -> "bulletjme-" + desktopArch() + ".dll";
            case "osx" -> "libbulletjme-" + desktopArch() + ".dylib";
            default -> "libbulletjme-" + desktopArch() + ".so";
        };
    }

    private static String desktopResource() {
        final String os = desktopOs();
        final String arch = desktopArch();
        final String fileName = switch (os) {
            case "windows" -> "bulletjme.dll";
            case "osx" -> "libbulletjme.dylib";
            default -> "libbulletjme.so";
        };
        return "/native/" + os + "/" + arch + "/" + fileName;
    }

    private static String androidResource() {
        return "/native/android/" + androidAbi() + "/libbulletjme.so";
    }

    private static String desktopOs() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "osx";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new PhysicsException("Unsupported operating system: " + os);
    }

    private static String desktopArch() {
        final String arch = normalizeArch(System.getProperty("os.arch", ""));
        return switch (arch) {
            case "x86_64" -> "x86_64";
            case "arm64" -> "arm64";
            default -> throw new PhysicsException("Unsupported CPU architecture: " + arch);
        };
    }

    public static boolean isAndroid() {
        if (Boolean.getBoolean("crythst.android")) {
            return true;
        }
        final String runtime = System.getProperty("java.runtime.name", "").toLowerCase(Locale.ROOT);
        final String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        final String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        if (runtime.contains("android") || vendor.contains("android") || vmName.contains("android")) {
            return true;
        }
        try {
            Class.forName("android.os.Build", false, BulletNatives.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            // Desktop JVM.
        }
        for (String key : System.getenv().keySet()) {
            if (key.startsWith("POJAV") || key.startsWith("FCL")) {
                return true;
            }
        }
        return false;
    }

    private static String androidAbi() {
        final String arch = normalizeArch(System.getProperty("os.arch", ""));
        return switch (arch) {
            case "arm64" -> "arm64-v8a";
            case "arm32" -> "armeabi-v7a";
            case "x86_64" -> "x86_64";
            case "x86" -> "x86";
            case "riscv64" -> "riscv64";
            default -> throw new PhysicsException("Unsupported Android ABI: " + arch);
        };
    }

    private static String normalizeArch(String arch) {
        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64", "x86-64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            case "arm", "armv7", "armv7l", "armhf" -> "arm32";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "riscv64" -> "riscv64";
            default -> arch;
        };
    }
}
