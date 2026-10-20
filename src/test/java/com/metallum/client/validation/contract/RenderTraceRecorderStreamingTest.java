package com.metallum.client.validation.contract;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

final class RenderTraceRecorderStreamingTest {
    @Test
    void largeManifestAndBothByteLimitsRemainValidInARestrictedHeap(@TempDir Path root) throws Exception {
        String classpath = Stream.of(RenderTraceRecorderMemoryFixture.class, RenderTraceRecorder.class, Gson.class)
                .map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                }).distinct().collect(Collectors.joining(File.pathSeparator));
        Path log = root.resolve("restricted-heap.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx96m", "-cp", classpath, RenderTraceRecorderMemoryFixture.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(90, TimeUnit.SECONDS), "Restricted-heap fixture timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("BOUNDED_MANIFEST_MEMORY_PASS"));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
