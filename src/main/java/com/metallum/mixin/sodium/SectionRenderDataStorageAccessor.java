package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes Sodium's authoritative per-section mesh allocations to the
 * producer-side metadata hook.  This is intentionally narrower than an
 * access widener: no render data is copied or mutated.
 */
@Mixin(SectionRenderDataStorage.class)
public interface SectionRenderDataStorageAccessor {
    @Accessor("vertexAllocations")
    GlBufferSegment[] metallum$getVertexAllocations();

    @Accessor("elementAllocations")
    GlBufferSegment[] metallum$getElementAllocations();

    @Accessor("sharedIndexAllocation")
    GlBufferSegment metallum$getSharedIndexAllocation();
}
