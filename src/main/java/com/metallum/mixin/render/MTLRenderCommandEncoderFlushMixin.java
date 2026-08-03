package com.metallum.mixin.render;

import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MetalRenderStateFlushable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.lang.foreign.MemorySegment;

/** Narrow bridge for draw paths that call the native batch ABI directly. */
@Mixin(MTLRenderCommandEncoder.class)
public abstract class MTLRenderCommandEncoderFlushMixin implements MetalRenderStateFlushable {
    @Invoker("flushState")
    protected abstract void metallum$invokeFlushState(MemorySegment encoder);

    @Override
    public void metallum$flushPendingRenderState() {
        MTLRenderCommandEncoder encoder = (MTLRenderCommandEncoder) (Object) this;
        this.metallum$invokeFlushState(encoder.handle());
    }
}
