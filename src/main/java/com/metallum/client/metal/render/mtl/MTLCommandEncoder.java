package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
public abstract class MTLCommandEncoder {
    MemorySegment handle;

    MTLCommandEncoder(final MemorySegment handle) {
        this.handle = handle;
    }

    public MemorySegment handle() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            throw new IllegalStateException(getClass().getSimpleName() + " is closed");
        }
        return this.handle;
    }

    public void endEncoding() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            return;
        }
        MetalNativeBridge.MTLCommandEncoder_endEncoding(this.handle);
        MetalNativeBridge.metallum_release_object(this.handle);
        this.handle = MemorySegment.NULL;
    }

    /**
     * Ends an encoder while retaining its native bridge for a same-command-
     * buffer Metal 4 transition. The caller must release the returned handle
     * after the native transition has consumed the bridge's queue-buffer lease.
     */
    public MemorySegment endEncodingRetainingHandle() {
        if (MetalNativeBridge.isNullHandle(this.handle)) {
            return MemorySegment.NULL;
        }
        MetalNativeBridge.MTLCommandEncoder_endEncoding(this.handle);
        MemorySegment retained = this.handle;
        this.handle = MemorySegment.NULL;
        return retained;
    }
}
