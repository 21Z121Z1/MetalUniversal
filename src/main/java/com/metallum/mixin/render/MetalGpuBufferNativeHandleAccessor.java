package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalGpuBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.lang.foreign.MemorySegment;

@Mixin(MetalGpuBuffer.class)
public interface MetalGpuBufferNativeHandleAccessor {
    @Invoker("nativeHandle")
    MemorySegment metallum$invokeNativeHandle();
}
