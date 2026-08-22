package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalBindingNameProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the immutable sampler name without reflection during program token compilation. */
@Mixin(targets = "com.metallum.client.metal.render.MetalIrisShaderCompiler$SamplerDecl")
public interface IrisMetalSamplerDeclBindingNameMixin extends MetalBindingNameProvider {
    @Override
    @Accessor(value = "name", remap = false)
    String metallum$bindingName();
}
