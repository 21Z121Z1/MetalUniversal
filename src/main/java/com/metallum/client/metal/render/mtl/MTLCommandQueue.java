package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public final class MTLCommandQueue {
    private static final boolean METAL4_REQUESTED =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4", "false"));
    private static final boolean METAL4_MAIN_RENDERER_REQUESTED =
            Boolean.parseBoolean(System.getProperty("metallum.opt.metal4MainRenderer", "false"));

    private MemorySegment handle;
    private final boolean trackMetal4MainRenderer;
    private boolean residencySetEnabled;

    private MTLCommandQueue(final MemorySegment handle, final boolean trackMetal4MainRenderer) {
        this.handle = handle;
        this.trackMetal4MainRenderer = trackMetal4MainRenderer;
    }

    public static MTLCommandQueue create(final MemorySegment device) {
        MemorySegment handle = MetalNativeBridge.MTLDevice_makeCommandQueue(device);
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Failed to create Metal command queue");
        }
        boolean trackMetal4MainRenderer = Metal4MainRendererTelemetry.enabled()
                && METAL4_REQUESTED
                && METAL4_MAIN_RENDERER_REQUESTED
                && MetalNativeBridge.metallum_metal4_supported(device) != 0;
        return new MTLCommandQueue(handle, trackMetal4MainRenderer);
    }

    /**
     * Creates the native residency set and attaches it to this queue (migration
     * spec M3). Must run before any resource is created: allocations made earlier
     * are never added to the set, which is harmless under Metal 3 (residency is
     * automatic) but not once the queue is Metal 4.
     *
     * @return true when the set is active
     */
    public boolean enableResidencySet(final MemorySegment device) {
        boolean enabled = MetalNativeBridge.metallum_residency_set_enable(device, handle) != 0;
        this.residencySetEnabled = enabled;
        return enabled;
    }

    /** Runtime evidence seam: true only after the native set was created and attached. */
    public boolean residencySetEnabled() {
        return this.residencySetEnabled;
    }

    public MTLCommandBuffer makeCommandBuffer(@Nullable final String label) {
        boolean pressure = trackMetal4MainRenderer && Metal4MainRendererTelemetry.shouldMeasureSlotWait();
        long acquireStart = pressure ? System.nanoTime() : 0L;
        MemorySegment commandBuffer = MetalNativeBridge.MTLCommandQueue_makeCommandBuffer(handle, label);
        if (MetalNativeBridge.isNullHandle(commandBuffer)) {
            throw new IllegalStateException("Failed to create MTLCommandBuffer");
        }
        if (trackMetal4MainRenderer) {
            long waitUpperBound = pressure ? Math.max(1L, System.nanoTime() - acquireStart) : 0L;
            Metal4MainRendererTelemetry.recordCommandBufferAcquired(waitUpperBound);
        }
        return new MTLCommandBuffer(commandBuffer, trackMetal4MainRenderer);
    }

    public void close() {
        if (MetalNativeBridge.isNullHandle(handle)) {
            return;
        }
        MetalNativeBridge.metallum_release_object(handle);
        handle = MemorySegment.NULL;
    }
}
