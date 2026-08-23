package com.metallum.client.metal.render;

import com.metallum.client.validation.contract.RenderTraceRecorder;
import com.metallum.client.validation.contract.ResourceIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalAllocationIdentityTest {
    @Test
    void disabledTracingStillUsesOneCrossTypeAllocationAuthority() {
        MetalAllocationIdentity texture = MetalAllocationIdentityAuthority.allocate("identity-texture");
        MetalAllocationIdentity buffer = MetalAllocationIdentityAuthority.allocate("identity-buffer");
        MetalAllocationIdentity recreatedTexture = MetalAllocationIdentityAuthority.allocate("identity-texture");

        assertNotEquals(texture.allocationId(), buffer.allocationId());
        assertNotEquals(buffer.allocationId(), recreatedTexture.allocationId());
        assertTrue(recreatedTexture.generation() > texture.generation());
    }

    @Test
    void recorderConsumesRendererGenerationWithoutAllocatingAnotherOne() throws Exception {
        Path output = Files.createTempDirectory("metal-allocation-identity-");
        MetalAllocationIdentity allocation = MetalAllocationIdentityAuthority.allocate("identity-recorder");
        RenderTraceRecorder recorder = new RenderTraceRecorder(output, "allocation-identity", "test", 2, 4, 8);
        try {
            ResourceIdentity first = recorder.identifyAllocation(
                    "identity-recorder",
                    allocation.allocationId(),
                    allocation.generation(),
                    "metal-resource-" + allocation.allocationId(),
                    "BUFFER",
                    16,
                    1,
                    1,
                    0,
                    1,
                    3
            );
            ResourceIdentity same = recorder.identifyAllocation(
                    "identity-recorder",
                    allocation.allocationId(),
                    allocation.generation(),
                    "metal-resource-" + allocation.allocationId(),
                    "BUFFER",
                    16,
                    1,
                    1,
                    0,
                    1,
                    3
            );
            assertEquals(allocation.allocationId(), first.runtimeId());
            assertEquals(allocation.generation(), first.generation());
            assertEquals(first, same);
        } finally {
            recorder.close();
            deleteRecursively(output);
        }
    }

    private static void deleteRecursively(final Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });
        }
    }
}
