package com.metallum.client.metal.render.bridge;

import org.lwjgl.system.Configuration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Select the full SPIRV-Cross C/MSL library before Spvc initializes its static
 * library handle. This bootstrap must not initialize MetalNativeBridge.
 * A 26.2 GLFW launcher is not a substitute for the 26.3 SDL runtime.
 */
public final class IOSRuntimePreflight {
    private static final String IMAGE = "libspvc_metallum.dylib";
    private static final String RESOURCE = "/natives/ios/libspvc.dylib";
    private static final String[] DIRECTORIES = {"pojav.launcher.home", "POJAV_HOME", "user.home", "java.io.tmpdir"};
    private final NativeAccess access;
    private Path configuredLibrary;

    IOSRuntimePreflight(NativeAccess access) {
        this.access = access;
    }

    public static void prepare() {
        if (NativePlatform.current() == NativePlatform.IOS) Holder.INSTANCE.configure(System.getProperties());
    }

    private static final class Holder {
        private static final IOSRuntimePreflight INSTANCE = new IOSRuntimePreflight(new LwjglAccess());
    }

    synchronized Path configure(Properties properties) {
        if (configuredLibrary != null) return configuredLibrary;
        access.requireBindings(); // Check availability without initializing Spvc or SDL.
        Path selected = preferredImage(properties);
        if (selected != null) {
            // An explicit or launcher-bundled image is authoritative. Never hide
            // its signing/ABI/configuration failure behind a different extracted image.
            configuredLibrary = loadAndConfigure(selected);
            return configuredLibrary;
        }
        final byte[] image;
        try {
            image = access.bundledImage();
            if (image.length == 0) throw new IOException("empty bundled SPIRV-Cross image");
        } catch (IOException failure) {
            throw new IllegalStateException("iOS SPIRV-Cross is unavailable; bundle/sign " + IMAGE
                    + " in the launcher or supply metallum.ios.spvc.path", failure);
        }
        var failures = new IllegalStateException("iOS SPIRV-Cross could not be loaded from any declared directory; "
                + "the launcher must provide a correctly signed full C/MSL library");
        var directories = new LinkedHashSet<Path>();
        for (String key : DIRECTORIES) {
            String value = properties.getProperty(key);
            if (value == null || value.isBlank()) continue;
            try { directories.add(Path.of(value).toAbsolutePath().normalize()); }
            catch (RuntimeException invalid) { failures.addSuppressed(invalid); }
        }
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) continue;
            Path staged = null;
            try {
                // Each attempt receives the entire immutable image. Unique files
                // prevent overwriting a dylib that another process already loaded.
                staged = access.stage(directory, image);
                if (Files.size(staged) != image.length) throw new IOException("short SPIRV-Cross image write");
                configuredLibrary = loadAndConfigure(staged);
                staged.toFile().deleteOnExit();
                return configuredLibrary;
            } catch (IOException | IllegalStateException | SecurityException failure) {
                failures.addSuppressed(failure);
                if (staged != null) {
                    try { Files.deleteIfExists(staged); }
                    catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
            }
        }
        // There is no success latch on a failed attempt; a corrected deployment
        // can retry, but callers cannot continue with an unconfigured Spvc handle.
        throw failures;
    }

    private Path preferredImage(Properties properties) {
        String explicit = properties.getProperty("metallum.ios.spvc.path");
        if (explicit != null) return requireImage(explicit);
        String existing = access.configuredName();
        if (existing != null) return requireImage(existing);
        for (String directory : properties.getProperty("java.library.path", "").split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) continue;
            Path candidate = Path.of(directory).resolve(IMAGE);
            if (Files.exists(candidate)) return requireImage(candidate.toString());
        }
        return null;
    }

    private static Path requireImage(String name) {
        if (name.isBlank()) throw new IllegalStateException("empty explicit iOS SPIRV-Cross path");
        try {
            Path path = Path.of(name).toRealPath();
            if (!Files.isRegularFile(path) || Files.size(path) == 0) throw new IOException("missing/empty native image");
            return path;
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Invalid iOS SPIRV-Cross image: " + name, failure);
        }
    }

    private Path loadAndConfigure(Path image) {
        Path path = requireImage(image.toString());
        try {
            access.load(path); // Normal JVM/OS loader; signing and ABI checks remain authoritative.
            access.configureName(path.toString());
            if (!path.toString().equals(access.configuredName())) {
                throw new IllegalStateException("LWJGL did not retain the selected SPIRV-Cross image");
            }
            return path;
        } catch (UnsatisfiedLinkError | SecurityException failure) {
            throw new IllegalStateException("iOS loader refused selected SPIRV-Cross image: " + path, failure);
        }
    }

    /** The narrow loader boundary permits deterministic tests without a fake Metal runtime. */
    interface NativeAccess {
        void requireBindings();
        String configuredName();
        void configureName(String name);
        void load(Path path);
        byte[] bundledImage() throws IOException;
        default Path stage(Path directory, byte[] image) throws IOException {
            Path path = Files.createTempFile(directory, "metallum-spvc-", ".dylib");
            try { Files.write(path, image); return path; }
            catch (IOException | RuntimeException failure) {
                try { Files.deleteIfExists(path); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }

    private static final class LwjglAccess implements NativeAccess {
        public void requireBindings() {
            ClassLoader loader = IOSRuntimePreflight.class.getClassLoader();
            for (String name : new String[]{"org.lwjgl.util.spvc.Spvc", "org.lwjgl.sdl.SDLVideo"}) {
                try { Class.forName(name, false, loader); }
                catch (ClassNotFoundException | LinkageError failure) {
                    throw new IllegalStateException("Minecraft 26.3 iOS requires coherent LWJGL SPVC and SDL Java/native modules; "
                            + "the 26.2 GLFW-only launcher is incompatible. Missing binding: " + name, failure);
                }
            }
        }
        public String configuredName() { return Configuration.SPVC_LIBRARY_NAME.get(); }
        public void configureName(String name) { Configuration.SPVC_LIBRARY_NAME.set(name); }
        public void load(Path path) { System.load(path.toString()); }
        public byte[] bundledImage() throws IOException {
            try (InputStream stream = IOSRuntimePreflight.class.getResourceAsStream(RESOURCE)) {
                if (stream == null) throw new IOException("missing " + RESOURCE);
                return stream.readAllBytes();
            }
        }
    }
}
