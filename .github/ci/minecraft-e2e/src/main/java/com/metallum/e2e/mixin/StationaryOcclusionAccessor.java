package com.metallum.e2e.mixin;

import net.minecraft.client.renderer.SectionOcclusionGraph;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test-only, read-only completion checks; never waits on or consumes the graph's work. */
@Mixin(SectionOcclusionGraph.class)
public interface StationaryOcclusionAccessor {
    @Accessor("fullUpdateTask") Future<?> metallum$fullUpdateTask();
    @Accessor("needsFullUpdate") boolean metallum$needsFullUpdate();
    @Accessor("needsFrustumUpdate") AtomicBoolean metallum$needsFrustumUpdate();
}
