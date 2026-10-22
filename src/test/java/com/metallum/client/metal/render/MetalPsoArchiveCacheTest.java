package com.metallum.client.metal.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class MetalPsoArchiveCacheTest {
    @TempDir Path directory;
    Path nativeLibrary;
    Path systemBuild;

    @BeforeEach void inputs() throws IOException {
        nativeLibrary = Files.writeString(directory.resolve("native-extraction-one.dylib"), "native build one");
        systemBuild = Files.writeString(directory.resolve("SystemVersion.plist"), "OS build one");
    }

    @Test void validationNeverSelectsAnUninstrumentedOrLegacyArchive() throws IOException {
        Path legacy = Files.writeString(directory.resolve("pso.binaryarchive"), "legacy bytes");
        Path ordinary = archive("Apple GPU", false, Map.of());
        Files.writeString(ordinary, "ordinary archive");
        Path validated = archive("Apple GPU", false, Map.of("MTL_DEBUG_LAYER", "1", "MTL_SHADER_VALIDATION", "1"));
        assertNotEquals(legacy, ordinary);
        assertNotEquals(ordinary, validated);
        assertFalse(Files.exists(validated), "a different compiler context must start cold");
        Files.writeString(validated, "validated archive");
        assertEquals(validated, archive("Apple GPU", false, Map.of("MTL_SHADER_VALIDATION", "1", "MTL_DEBUG_LAYER", "1")));
        assertEquals("ordinary archive", Files.readString(ordinary));
        assertEquals("legacy bytes", Files.readString(legacy), "legacy data must remain untouched");
    }

    @Test void deviceBackendSystemAndNativeChangesInvalidateTheNamespace() throws IOException {
        Path original = archive("Apple GPU", false, Map.of());
        assertNotEquals(original, archive("Other GPU", false, Map.of()));
        assertNotEquals(original, archive("Apple GPU", true, Map.of()));
        Files.writeString(systemBuild, "OS build two");
        Path changedSystem = archive("Apple GPU", false, Map.of());
        assertNotEquals(original, changedSystem);
        Files.writeString(nativeLibrary, "native build two");
        assertNotEquals(changedSystem, archive("Apple GPU", false, Map.of()));
    }

    @Test void extractionNamesDoNotInvalidateIdenticalNativeBytes() throws IOException {
        Path original = archive("Apple GPU", false, Map.of());
        nativeLibrary = Files.copy(nativeLibrary, directory.resolve("native-extraction-two.dylib"));
        assertEquals(original, archive("Apple GPU", false, Map.of()));
    }

    @Test void instrumentationSelectionsAreOrderedAndUnrelatedEnvironmentIsIgnored() throws IOException {
        var environment = new LinkedHashMap<String, String>();
        environment.put("MTL_SHADER_VALIDATION_ENABLE_PIPELINES", "pipeline-a");
        environment.put("MTL_DEBUG_LAYER", "1");
        Path first = archive("Apple GPU", false, environment);
        assertEquals(first, archive("Apple GPU", false, Map.of(
                "MTL_DEBUG_LAYER", "1", "MTL_SHADER_VALIDATION_ENABLE_PIPELINES", "pipeline-a", "CI", "true")));
        environment.put("MTL_SHADER_VALIDATION_ENABLE_PIPELINES", "pipeline-b");
        assertNotEquals(first, archive("Apple GPU", false, environment));
        assertNotEquals(archive("Apple GPU", false, Map.of()),
                archive("Apple GPU", false, Map.of("METAL_DEVICE_WRAPPER_TYPE", "1")));
    }

    @Test void missingIdentityRejectsArchiveAdmission() {
        assertThrows(IOException.class, () -> MetalPsoArchiveCache.path(
                directory, "Apple GPU", false, null, systemBuild, Map.of()));
        assertThrows(IOException.class, () -> MetalPsoArchiveCache.path(
                directory, "Apple GPU", false, directory.resolve("absent-native"), systemBuild, Map.of()));
    }

    private Path archive(String device, boolean metal4, Map<String, String> environment) throws IOException {
        return MetalPsoArchiveCache.path(directory, device, metal4, nativeLibrary, systemBuild, environment);
    }
}
