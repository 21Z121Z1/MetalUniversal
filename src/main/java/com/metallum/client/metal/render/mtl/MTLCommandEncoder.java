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
        finishEncoding(false);
    }

    /**
     * Ends an encoder while retaining its native bridge for a same-command-
     * buffer Metal 4 transition. The caller must release the returned handle
     * after the native transition has consumed the bridge's queue-buffer lease.
     */
    public MemorySegment endEncodingRetainingHandle() {
        return finishEncoding(true);
    }

    private MemorySegment finishEncoding(final boolean retain) {
        if (this.handle == null || this.handle.address() == 0L) {
            return MemorySegment.NULL;
        }
        MemorySegment ending = this.handle;
        // Reentrant or repeated cleanup must never end/release this lease twice.
        this.handle = MemorySegment.NULL;
        boolean transferred = false;
        try {
            try {
                try {
                    beforeEndEncoding(ending);
                } finally {
                    endNativeEncoding(ending);
                }
            } finally {
                releaseCpuState();
            }
            transferred = retain;
            return retain ? ending : MemorySegment.NULL;
        } finally {
            // On a failed flush there is no caller to receive a retained handle.
            if (!transferred) releaseNativeHandle(ending);
        }
    }

    protected void beforeEndEncoding(final MemorySegment encoder) { }

    protected void releaseCpuState() { }

    protected void endNativeEncoding(final MemorySegment encoder) {
        MetalNativeBridge.MTLCommandEncoder_endEncoding(encoder);
    }

    protected void releaseNativeHandle(final MemorySegment encoder) {
        MetalNativeBridge.metallum_release_object(encoder);
    }
}
