package com.metallum.mixin.terrain;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only terminal-state access needed to compact work vanilla would discard in poll(). */
@Mixin(SectionRenderDispatcher.RenderSection.SectionTask.class)
interface SectionTaskTerrainAdmissionAccessor {
    @Accessor("isCancelled")
    AtomicBoolean metallum$isCancelled();

    @Accessor("isCompleted")
    AtomicBoolean metallum$isCompleted();
}
