package com.metallum.client.metal.render;

import com.mojang.renderpearl.backend.api.RenderPassBackend;

/**
 * Stable MetalUniversal-owned bridge from the RenderPearl frontend pass to its
 * backend pass. Minecraft 26.3 owns this boundary; Sodium is deliberately not
 * involved so vanilla terrain and optional Sodium terrain share one execution
 * path.
 */
public interface RenderPearlBackendAccess {
    RenderPassBackend metallum$getBackend();
}
