package com.metallum.e2e.mixin;

import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only read access to the existing section-task buffer ownership state. */
@Mixin(SectionRenderDispatcher.class)
public interface StationaryDispatcherAccessor {
    @Accessor("bufferPool") SectionBufferBuilderPool metallum$bufferPool();
}
