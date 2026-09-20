package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import org.junit.jupiter.api.Test;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Production FFM/device allocation tests; no offscreen image or residency claim. */
final class NativeResourceAllocationsGpuTest {
    private static final boolean ENABLED = "1".equals(System.getenv("METALLUM_RESOURCE_ALLOCATION_TRACE"));

    private static NativeResourceAllocations.Snapshot snapshot() {
        var result = MetalNativeBridge.metallum_resource_allocations_snapshot();
        assertEquals(ENABLED, result.enabled());
        assertEquals(0, result.droppedRows());
        assertEquals(0, result.invalidEvents());
        assertEquals(result.rows().size(), result.liveRowCount());
        assertEquals(result.totalAllocatedBytes(), result.rows().stream().mapToLong(NativeResourceAllocations.Row::allocatedBytes).sum());
        assertEquals(result.liveRowCount(), new HashSet<>(result.rows().stream().map(NativeResourceAllocations.Row::resourceId).toList()).size());
        if (!ENABLED) assertEquals(0, result.createdResources());
        return result;
    }

    @Test void viewsDoNotDoubleCountAndRegistryDoesNotRetainResources() {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        MemorySegment buffer = MemorySegment.NULL, texture = MemorySegment.NULL, view = MemorySegment.NULL;
        try {
            var before = snapshot();
            buffer = MetalNativeBridge.metallum_create_buffer(device, 4096, 0);
            texture = MetalNativeBridge.metallum_create_texture_2d(device, MTLPixelFormat.RGBA8Unorm,
                    16, 16, 1, 1, 0, MTLTextureUsage.ShaderRead.value | MTLTextureUsage.RenderTarget.value,
                    MTLStorageMode.Private, "allocation-probe");
            assertFalse(MetalNativeBridge.isNullHandle(buffer));
            assertFalse(MetalNativeBridge.isNullHandle(texture));
            var created = snapshot();
            if (ENABLED) {
                assertEquals(before.createdResources() + 2, created.createdResources());
                assertEquals(before.liveRowCount() + 2, created.liveRowCount());
                assertTrue(created.totalAllocatedBytes() >= before.totalAllocatedBytes() + 4096);
            }
            view = MetalNativeBridge.metallum_create_texture_view(texture, 0, 1);
            assertFalse(MetalNativeBridge.isNullHandle(view));
            assertEquals(created, snapshot());
            MetalNativeBridge.metallum_release_object(view); view = MemorySegment.NULL;
            assertEquals(created, snapshot());
            view = MetalNativeBridge.metallum_create_texture_view(texture, 0, 1);
            assertFalse(MetalNativeBridge.isNullHandle(view));
            MetalNativeBridge.metallum_release_object(texture); texture = MemorySegment.NULL;
            assertEquals(created, snapshot(), "a surviving view must keep its parent backing observable");
            MetalNativeBridge.metallum_release_object(view); view = MemorySegment.NULL;
            MetalNativeBridge.metallum_release_object(buffer); buffer = MemorySegment.NULL;
            var retired = snapshot();
            assertEquals(before.liveRowCount(), retired.liveRowCount());
            assertEquals(before.totalAllocatedBytes(), retired.totalAllocatedBytes());
            assertEquals(created.createdResources(), retired.createdResources());
        } finally {
            MetalNativeBridge.metallum_release_object(view);
            MetalNativeBridge.metallum_release_object(texture);
            MetalNativeBridge.metallum_release_object(buffer);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    @Test void memorylessAndBufferViewsPreserveBackingAccounting() {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        MemorySegment buffer = MemorySegment.NULL, memoryless = MemorySegment.NULL, view = MemorySegment.NULL;
        try {
            var before = snapshot();
            // Production buffer-texture views require the backing buffer to
            // use the same untracked hazard mode as their descriptor.
            buffer = MetalNativeBridge.metallum_create_buffer(device, 4096,
                    MTLResourceOptions.of(MTLStorageMode.Shared, MTLHazardTrackingMode.Untracked));
            memoryless = MetalNativeBridge.metallum_create_texture_2d(device, MTLPixelFormat.RGBA8Unorm,
                    16, 16, 1, 1, 0, MTLTextureUsage.RenderTarget.value,
                    MTLStorageMode.Memoryless, "allocation-memoryless");
            assertFalse(MetalNativeBridge.isNullHandle(memoryless));
            var created = snapshot();
            if (ENABLED) {
                assertEquals(before.createdResources() + 2, created.createdResources());
                var row = created.rows().stream().filter(NativeResourceAllocations.Row::memoryless).findFirst().orElseThrow();
                assertEquals(0, row.allocatedBytes());
                assertEquals(1, row.kind());
            }
            view = MetalNativeBridge.metallum_create_buffer_texture_view(buffer,
                    MTLPixelFormat.RGBA8Unorm.value, 0, 16, 1, 256);
            assertFalse(MetalNativeBridge.isNullHandle(view));
            assertEquals(created, snapshot());
            MetalNativeBridge.metallum_release_object(buffer); buffer = MemorySegment.NULL;
            assertEquals(created, snapshot(), "buffer-backed view must preserve observed buffer allocation");
            MetalNativeBridge.metallum_release_object(view); view = MemorySegment.NULL;
            MetalNativeBridge.metallum_release_object(memoryless); memoryless = MemorySegment.NULL;
            var retired = snapshot();
            assertEquals(before.liveRowCount(), retired.liveRowCount());
            assertEquals(before.totalAllocatedBytes(), retired.totalAllocatedBytes());
        } finally {
            MetalNativeBridge.metallum_release_object(view);
            MetalNativeBridge.metallum_release_object(memoryless);
            MetalNativeBridge.metallum_release_object(buffer);
            MetalNativeBridge.metallum_release_object(device);
        }
    }

    @Test void concurrentFactoriesAndSnapshotsRemainConsistent() throws Exception {
        MemorySegment device = MetalNativeBridge.metallum_create_system_default_device();
        try (var workers = Executors.newFixedThreadPool(3)) {
            var before = snapshot();
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 3; worker++) futures.add(workers.submit(() -> {
                for (int index = 0; index < 32; index++) {
                    var buffer = MetalNativeBridge.metallum_create_buffer(device, 1024, 0);
                    assertFalse(MetalNativeBridge.isNullHandle(buffer));
                    try { snapshot(); } finally { MetalNativeBridge.metallum_release_object(buffer); }
                }
            }));
            for (var future : futures) future.get();
            var after = snapshot();
            assertEquals(before.liveRowCount(), after.liveRowCount());
            assertEquals(before.totalAllocatedBytes(), after.totalAllocatedBytes());
            assertEquals(before.createdResources() + (ENABLED ? 96 : 0), after.createdResources());
        } finally { MetalNativeBridge.metallum_release_object(device); }
    }
}
