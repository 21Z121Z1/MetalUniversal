package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalPsoArchiveIdentityTest {
    @Test
    void digestAndFilenameAreDeterministicForOnePipelineContract() {
        MetalPsoArchiveIdentity first = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "sha256:pack", "mrt-v3", false
        );
        MetalPsoArchiveIdentity second = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "sha256:pack", "mrt-v3", false
        );
        assertEquals(first.digest(), second.digest());
        assertEquals(first.filename(), second.filename());
        assertTrue(first.filename().startsWith("pso-v2-"));
        assertTrue(first.filename().endsWith(".binaryarchive"));
        assertTrue(first.exactShaderPackIdentity());
    }

    @Test
    void platformOrPackChangesCannotReuseTheSameArchive() {
        MetalPsoArchiveIdentity platform = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "sha256:pack", "mrt-v3", false
        );
        MetalPsoArchiveIdentity otherPack = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "sha256:other", "mrt-v3", false
        );
        MetalPsoArchiveIdentity metal4 = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "sha256:pack", "mrt-v3", true
        );
        assertNotEquals(platform.digest(), otherPack.digest());
        assertNotEquals(platform.digest(), metal4.digest());
        assertNotEquals(platform.filename(), metal4.filename());
    }

    @Test
    void unknownPackIdentityIsNotExact() {
        MetalPsoArchiveIdentity unknown = new MetalPsoArchiveIdentity(
                "macOS", "26.0", "Apple M1 Pro", "iris-abi", "unknown", "mrt-v3", false
        );
        assertTrue(!unknown.exactShaderPackIdentity());
    }
}
