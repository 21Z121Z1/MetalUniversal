package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Loader-boundary tests, not iOS signing/device evidence. */
class IOSRuntimePreflightTest {
    @TempDir Path directory;

    @Test void explicitImageWinsAndSuccessfulBootstrapIsIdempotent() throws Exception {
        Path image = image("signed image.dylib");
        var access = new FakeAccess(); access.name = image("other.dylib").toString();
        var preflight = new IOSRuntimePreflight(access);
        Properties properties = explicit(image);
        assertEquals(image.toRealPath(), preflight.configure(properties));
        properties.setProperty("metallum.ios.spvc.path", directory.resolve("missing").toString());
        assertEquals(image.toRealPath(), preflight.configure(properties));
        assertEquals(1, access.bindingChecks); assertEquals(1, access.loaded.size());
        assertEquals(0, access.imageReads);
        assertEquals(image.toRealPath().toString(), access.name);
    }

    @Test void launcherSignedImageIsPreferredAndLoadFailureNeverFallsBackOrLatchesSuccess() throws Exception {
        Path image = image("libspvc_metallum.dylib");
        var access = new FakeAccess(); access.failLoad = true;
        var preflight = new IOSRuntimePreflight(access);
        var properties = directories(); properties.setProperty("java.library.path", directory.toString());
        assertThrows(IllegalStateException.class, () -> preflight.configure(properties));
        assertNull(access.name); assertEquals(0, access.imageReads);
        access.failLoad = false;
        assertEquals(image.toRealPath(), preflight.configure(properties));
        assertEquals(2, access.bindingChecks); assertEquals(2, access.loaded.size());
        assertEquals(0, access.imageReads);
    }

    @Test void existingLwjglImageIsPreservedWhenNoExplicitOverrideExists() throws Exception {
        Path existing = image("already-selected.dylib"); image("libspvc_metallum.dylib");
        var access = new FakeAccess(); access.name = existing.toString();
        var properties = directories(); properties.setProperty("java.library.path", directory.toString());
        assertEquals(existing.toRealPath(), new IOSRuntimePreflight(access).configure(properties));
        assertEquals(List.of(existing.toRealPath()), access.loaded);
        assertEquals(0, access.imageReads);
    }

    @Test void missingOrEmptyExplicitImagesDoNotSilentlyChooseAnotherLibrary() throws Exception {
        for (String value : List.of("", directory.resolve("missing").toString(),
                Files.createFile(directory.resolve("empty.dylib")).toString())) {
            var access = new FakeAccess(); var properties = directories();
            properties.setProperty("metallum.ios.spvc.path", value);
            assertThrows(IllegalStateException.class, () -> new IOSRuntimePreflight(access).configure(properties));
            assertTrue(access.loaded.isEmpty()); assertEquals(0, access.imageReads);
        }
    }

    @Test void absentJavaBindingsFailBeforeNativeOrConfigurationWork() {
        var access = new FakeAccess(); access.failBindings = true;
        assertThrows(IllegalStateException.class, () -> new IOSRuntimePreflight(access).configure(directories()));
        assertNull(access.name); assertTrue(access.loaded.isEmpty()); assertEquals(0, access.imageReads);
    }

    @Test void aFailedCopyRetriesWithTheWholeImageNotAConsumedStream() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        var access = new FakeAccess() {
            @Override public Path stage(Path target, byte[] bytes) throws IOException {
                assertArrayEquals(image, bytes);
                if (target.equals(first)) throw new IOException("simulated interrupted first copy");
                return super.stage(target, bytes);
            }
        };
        Properties properties = new Properties();
        properties.setProperty("user.home", first.toString()); properties.setProperty("java.io.tmpdir", second.toString());
        Path selected = new IOSRuntimePreflight(access).configure(properties);
        assertEquals(second.toRealPath(), selected.getParent()); assertArrayEquals(access.image, Files.readAllBytes(selected));
        assertEquals(1, access.imageReads); assertEquals(List.of(selected), access.loaded);
    }

    @Test void shortCopyIsRemovedAndNeverLoaded() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Path partial = first.resolve("partial.dylib");
        var access = new FakeAccess() {
            @Override public Path stage(Path target, byte[] bytes) throws IOException {
                return target.equals(first) ? Files.write(partial, new byte[]{1}) : super.stage(target, bytes);
            }
        };
        Properties properties = new Properties();
        properties.setProperty("user.home", first.toString()); properties.setProperty("java.io.tmpdir", second.toString());
        Path selected = new IOSRuntimePreflight(access).configure(properties);
        assertFalse(Files.exists(partial)); assertEquals(List.of(selected), access.loaded);
        assertArrayEquals(access.image, Files.readAllBytes(selected));
    }

    @Test void independentLaunchesNeverOverwriteTheSameExtractedImage() throws Exception {
        Path a = new IOSRuntimePreflight(new FakeAccess()).configure(directories());
        Path b = new IOSRuntimePreflight(new FakeAccess()).configure(directories());
        assertNotEquals(a, b); assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
    }

    @Test void configurationFailureIsNotAReadyBootstrap() throws Exception {
        var access = new FakeAccess(); access.ignoreConfiguration = true;
        var preflight = new IOSRuntimePreflight(access); var properties = explicit(image("signed.dylib"));
        assertThrows(IllegalStateException.class, () -> preflight.configure(properties));
        access.ignoreConfiguration = false;
        assertNotNull(preflight.configure(properties)); assertEquals(2, access.loaded.size());
    }

    @Test void missingOrEmptyBundledImagesRemainFailuresWithoutNativeCalls() {
        for (byte[] image : new byte[][]{null, new byte[0]}) {
            var access = new FakeAccess(); access.image = image;
            var preflight = new IOSRuntimePreflight(access);
            assertThrows(IllegalStateException.class, () -> preflight.configure(directories()));
            assertNull(access.name); assertTrue(access.loaded.isEmpty());
        }
    }

    @Test void prelaunchDoesNotRequireLwjglOrFfmOnDesktopAndMissingMobileModulesFailClearly() throws Exception {
        // Deliberately omit all dependency jars. A desktop prelaunch cannot load
        // the GPU bridge; iOS must report the missing 26.3 binding before dlopen.
        for (String os : List.of("Mac OS X", "Linux", "iOS")) {
            Path log = directory.resolve(os.replace(' ', '-') + ".log");
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Dos.name=" + os, "-Duser.home=" + directory, "-Djava.io.tmpdir=" + directory,
                    "-cp", codePath(Probe.class) + File.pathSeparator + codePath(IOSRuntimePreflight.class),
                    Probe.class.getName(), os);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Bootstrap probe timed out");
                assertEquals(0, process.exitValue(), () -> read(log));
                assertEquals("PASS", Files.readString(log).trim());
            } finally { process.destroyForcibly(); }
        }
    }

    private static String codePath(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (IOException e) { return e.toString(); } }
    private Path image(String name) throws IOException { return Files.write(directory.resolve(name), new byte[]{1,2,3,4}); }
    private Properties directories() { var p = new Properties(); p.setProperty("java.io.tmpdir", directory.toString()); return p; }
    private Properties explicit(Path path) { var p = directories(); p.setProperty("metallum.ios.spvc.path", path.toString()); return p; }

    private static class FakeAccess implements IOSRuntimePreflight.NativeAccess {
        String name; int bindingChecks, imageReads;
        boolean failLoad, failBindings, ignoreConfiguration;
        byte[] image = {11,22,33,44}; final List<Path> loaded = new ArrayList<>();
        public void requireBindings() { bindingChecks++; if (failBindings) throw new IllegalStateException("missing binding"); }
        public String configuredName() { return name; }
        public void configureName(String value) { if (!ignoreConfiguration) name = value; }
        public void load(Path path) { loaded.add(path); if (failLoad) throw new UnsatisfiedLinkError("signing/ABI rejected"); }
        public byte[] bundledImage() throws IOException { imageReads++; if (image == null) throw new IOException("missing resource"); return image; }
        public Path stage(Path target, byte[] bytes) throws IOException { return IOSRuntimePreflight.NativeAccess.super.stage(target, bytes); }
    }
    public static final class Probe {
        public static void main(String[] args) {
            try {
                IOSRuntimePreflight.prepare();
                if (args[0].equals("iOS")) throw new AssertionError("Missing iOS modules were accepted");
            } catch (IllegalStateException missing) {
                if (!args[0].equals("iOS") || !missing.getMessage().contains("Missing binding: org.lwjgl.util.spvc.Spvc")) throw missing;
            }
            System.out.println("PASS");
        }
    }
}
