package com.metallum.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.BufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes Sodium's authoritative per-section mesh allocations to the
 * producer-side metadata hook. This remains a Sodium compatibility boundary;
 * the Metal renderer itself must not treat these allocations as draw authority.
 */
@Mixin(SectionRenderDataStorage.class)
public interface SectionRenderDataStorageAccessor {
    @Accessor("vertexAllocations")
    BufferSegment[] metallum$getVertexAllocations();

    @Accessor("elementAllocations")
    BufferSegment[] metallum$getElementAllocations();

    @Accessor("sharedIndexAllocation")
    BufferSegment metallum$getSharedIndexAllocation();
}
