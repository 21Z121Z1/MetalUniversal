package com.metallum.e2e.mixin;

import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only read access to the existing section-task buffer pool. */
@Mixin(SectionBufferBuilderPool.class)
public interface StationaryBufferPoolAccessor {
    @Accessor("freeBuffers") ArrayBlockingQueue<?> metallum$freeBuffers();
}
