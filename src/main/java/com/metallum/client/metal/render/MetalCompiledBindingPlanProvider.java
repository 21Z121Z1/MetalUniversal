package com.metallum.client.metal.render;

/** Implemented by compiled Metal pipelines through a Mixin-owned dense plan. */
public interface MetalCompiledBindingPlanProvider {
    MetalCompiledBindingPlan metallum$bindingPlan();
}
