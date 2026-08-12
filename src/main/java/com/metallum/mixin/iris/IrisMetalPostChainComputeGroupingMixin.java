package com.metallum.mixin.iris;

import com.metallum.client.metal.render.IrisMetalComputeGroupingRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.List;

/** Starts hazard-checked grouping scopes around real post-chain compute arrays. */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainComputeGroupingMixin {
    @Shadow @Final
    private boolean concurrentCompute;

    /**
     * Keeps the grouping plan scoped to one complete post-chain invocation.
     *
     * <p>A paired HEAD/RETURN injection leaves the thread-local plan installed
     * when a dispatch, native encoder operation, or resource lookup throws.
     * The next unrelated compute pass could then mistake a stale encoder for
     * the approved group. The method wrapper covers both normal and exceptional
     * exits without adding state to the per-dispatch path.</p>
     */
    @WrapMethod(method = "executeComputeGroup", require = 1)
    private void metallum$executeComputeGroupWithCleanup(
            @Coerce final Object device,
            @Coerce final Object targets,
            @Coerce final Object resources,
            final List<?> computes,
            final List<String> executed,
            final Operation<Void> original
    ) {
        try {
            IrisMetalComputeGroupingRuntime.begin(computes, this.concurrentCompute);
            original.call(device, targets, resources, computes, executed);
        } finally {
            IrisMetalComputeGroupingRuntime.abort();
        }
    }
}
