package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.commands.GpuFence;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
final class MetalFence implements GpuFence {
    private final MetalCommandEncoder encoder;
    private final long submitIndex;
    private boolean closedOrCompleted;

    MetalFence(final MetalCommandEncoder encoder, final long submitIndex) {
        this.encoder = encoder;
        this.submitIndex = submitIndex;
    }

    @Override
    public void close() {
        this.closedOrCompleted = true;
    }

    @Override
    public boolean awaitCompletion(final long timeoutNS) {
        if (!this.closedOrCompleted) {
            // RenderPearl uses -1 for an unbounded wait (not a zero-time poll).
            // Round positive sub-millisecond waits up without overflowing.
            long timeoutMs = timeoutNS < 0L ? Long.MAX_VALUE
                    : timeoutNS / 1_000_000L + (timeoutNS % 1_000_000L == 0L ? 0L : 1L);
            this.closedOrCompleted = this.encoder.awaitSubmitCompletion(this.submitIndex, timeoutMs);
        }
        return this.closedOrCompleted;
    }
}
