package com.metallum.client.metal.render.bridge;

import com.metallum.Metallum;
import org.lwjgl.system.Configuration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Performs the pieces of the iOS/Amethyst runtime bootstrap that have to happen
 * before Minecraft initializes LWJGL's SPIRV-Cross bindings.
 *
 * <p>Amethyst replaces Mojang's platform LWJGL jars with its launcher-provided
 * aggregate LWJGL runtime. Minecraft 26.2 now loads {@code lwjgl-spvc} during
 * {@code NativeLibrariesBootstrap}, so both the Java bindings and a full
 * SPIRV-Cross C library with the MSL backend must be visible before that point.
 */
public final class IOSRuntimePreflight {
    private static final String SPVC_CLASS = "org.lwjgl.util.spvc.Spvc";
    private static final String SPVC_RESOURCE = "/natives/ios/libspvc.dylib";
    private static final String SPVC_FILE_NAME = "libspvc_metallum.dylib";
    private static final String SPVC_PATH_PROPERTY = "metallum.ios.spvc.path";

    private IOSRuntimePreflight() {
    }

    public static void prepare() {
        if (!looksLikeIOSRuntime()) {
            // Preserve the existing desktop bootstrap behaviour.
            MetalNativeBridge.ensureSpvcLibraryConfigured();
            return;
        }

        requireSpvcJavaBindings();
        Path configuredLibrary = configureSpvcLibraryStrict();

        // Do not call MetalNativeBridge.ensureSpvcLibraryConfigured() here on
        // iOS. That legacy helper always tries the jar-extraction path and can
        // replace Configuration.SPVC_LIBRARY_NAME after we deliberately chose a
        // launcher-bundled, code-signed Frameworks dylib. Minecraft's
        // NativeLibrariesBootstrap initializes Spvc after this pre-launch hook
        // and will therefore bind directly to the path selected below.
        String configured = Configuration.SPVC_LIBRARY_NAME.get();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "MetalUniversal iOS bootstrap configured SPIRV-Cross, but "
                            + "LWJGL Configuration.SPVC_LIBRARY_NAME is empty"
            );
        }

        Path effectivePath = Path.of(configured);
        validateNativeImage(effectivePath, "configured iOS SPIRV-Cross library");
        if (!effectivePath.equals(configuredLibrary)) {
            throw new IllegalStateException(
                    "iOS SPIRV-Cross configuration changed unexpectedly during pre-launch: "
                            + configuredLibrary + " -> " + effectivePath
            );
        }

        Metallum.LOGGER.info(
                "iOS/Amethyst runtime preflight ready: SPVC Java bindings present, native library={}",
                effectivePath
        );
    }

    static boolean looksLikeIOSRuntime() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osName.contains("ios")) {
            return true;
        }
        if (System.getProperty("pojav.launcher") != null
                || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }

        String tmpDir = System.getProperty("java.io.tmpdir", "");
        String userHome = System.getProperty("user.home", "");
        if (isIOSContainerPath(tmpDir) || isIOSContainerPath(userHome)) {
            return true;
        }

        return osName.contains("darwin")
                && osArch.contains("aarch64")
                && !osName.contains("mac");
    }

    private static boolean isIOSContainerPath(final String path) {
        return path.contains("/var/mobile/") || path.contains("/var/containers/");
    }

    private static void requireSpvcJavaBindings() {
        try {
            Class.forName(SPVC_CLASS, false, IOSRuntimePreflight.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Minecraft 26.2 on Amethyst requires LWJGL SPVC Java bindings, but "
                            + SPVC_CLASS + " is not on the runtime class path. "
                            + "Use an Amethyst build whose aggregate lwjgl.jar includes "
                            + "org.lwjgl:lwjgl-spvc:3.4.1 or newer.",
                    e
            );
        }
    }

    private static Path configureSpvcLibraryStrict() {
        // Production path: prefer a dylib that the launcher bundled into its
        // signed Frameworks directory. This avoids relying on Amethyst's dyld
        // library-validation bypass for a dylib extracted from a mod jar.
        Path signedLibrary = findBundledSpvcLibrary();
        if (signedLibrary != null) {
            try {
                loadAndConfigure(signedLibrary);
                Metallum.LOGGER.info("Using launcher-bundled iOS SPIRV-Cross: {}", signedLibrary);
                return signedLibrary;
            } catch (UnsatisfiedLinkError | SecurityException failure) {
                throw new IllegalStateException(
                        "Found launcher-bundled iOS SPIRV-Cross but dyld refused to load it: "
                                + signedLibrary,
                        failure
                );
            }
        }

        // Development fallback: extract the copy carried by the mod. This is
        // useful on Amethyst builds with the dyld validation bypass enabled.
        final byte[] image;
        try (InputStream stream = IOSRuntimePreflight.class.getResourceAsStream(SPVC_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "MetalUniversal jar is missing its iOS SPIRV-Cross native: " + SPVC_RESOURCE
                );
            }
            image = stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled iOS SPIRV-Cross native", e);
        }

        if (image.length == 0) {
            throw new IllegalStateException("Bundled iOS SPIRV-Cross native is empty: " + SPVC_RESOURCE);
        }

        Throwable lastFailure = null;
        for (String property : new String[]{"pojav.launcher.home", "POJAV_HOME", "user.home", "java.io.tmpdir"}) {
            String rawDirectory = System.getProperty(property);
            if (rawDirectory == null || rawDirectory.isBlank()) {
                continue;
            }

            Path directory;
            try {
                directory = Path.of(rawDirectory);
            } catch (RuntimeException invalidPath) {
                lastFailure = invalidPath;
                continue;
            }
            if (!Files.isDirectory(directory)) {
                continue;
            }

            Path library = directory.resolve(SPVC_FILE_NAME).toAbsolutePath();
            try {
                Files.write(
                        library,
                        image,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
                if (Files.size(library) != image.length) {
                    throw new IOException(
                            "Short write for " + library + ": expected " + image.length
                                    + " bytes, got " + Files.size(library)
                    );
                }

                library.toFile().deleteOnExit();
                loadAndConfigure(library);
                Metallum.LOGGER.warn(
                        "Using extracted iOS SPIRV-Cross from {}; prefer a signed {} in Amethyst Frameworks",
                        library,
                        SPVC_FILE_NAME
                );
                return library;
            } catch (IOException | UnsatisfiedLinkError | SecurityException failure) {
                lastFailure = failure;
            }
        }

        throw new IllegalStateException(
                "Failed to load iOS SPIRV-Cross. No signed " + SPVC_FILE_NAME
                        + " was found in metallum.ios.spvc.path/java.library.path, and the bundled "
                        + "fallback could not be loaded from a writable directory. Enable Amethyst's "
                        + "dyld library-validation bypass for development, or bundle/sign the native "
                        + "in the launcher Frameworks directory.",
                lastFailure
        );
    }

    private static Path findBundledSpvcLibrary() {
        String explicit = System.getProperty(SPVC_PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            Path candidate = Path.of(explicit).toAbsolutePath();
            validateNativeImage(candidate, SPVC_PATH_PROPERTY);
            return candidate;
        }

        String libraryPath = System.getProperty("java.library.path", "");
        for (String rawDirectory : libraryPath.split(File.pathSeparator)) {
            if (rawDirectory == null || rawDirectory.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(rawDirectory).resolve(SPVC_FILE_NAME).toAbsolutePath();
                if (Files.isRegularFile(candidate) && Files.size(candidate) > 0L) {
                    return candidate;
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep scanning later java.library.path entries.
            }
        }
        return null;
    }

    private static void loadAndConfigure(final Path library) {
        validateNativeImage(library, "iOS SPIRV-Cross library");
        System.load(library.toAbsolutePath().toString());
        Configuration.SPVC_LIBRARY_NAME.set(library.toAbsolutePath().toString());
    }

    private static void validateNativeImage(final Path library, final String description) {
        try {
            if (!Files.isRegularFile(library) || Files.size(library) <= 0L) {
                throw new IllegalStateException(description + " is missing or empty: " + library);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not validate " + description + ": " + library, e);
        }
    }
}
