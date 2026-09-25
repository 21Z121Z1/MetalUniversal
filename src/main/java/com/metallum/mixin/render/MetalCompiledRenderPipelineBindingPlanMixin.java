package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalCompiledBindingPlan;
import com.metallum.client.metal.render.MetalCompiledBindingPlanProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Compiles a dense resource layout once when a Metal pipeline is constructed. */
@Mixin(targets = "com.metallum.client.metal.render.MetalCompiledRenderPipeline")
public abstract class MetalCompiledRenderPipelineBindingPlanMixin
        implements MetalCompiledBindingPlanProvider {
    @Shadow
    @Final
    private List<?> resources;

    @Unique
    private MetalCompiledBindingPlan metallum$bindingPlan;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void metallum$compileBindingPlan(final CallbackInfo ci) {
        this.metallum$bindingPlan = MetalCompiledBindingPlan.compile(this.resources);
    }

    @Override
    public MetalCompiledBindingPlan metallum$bindingPlan() {
        if (this.metallum$bindingPlan == null) {
            throw new IllegalStateException("Metal compiled binding plan was requested before pipeline construction completed");
        }
        return this.metallum$bindingPlan;
    }
}
