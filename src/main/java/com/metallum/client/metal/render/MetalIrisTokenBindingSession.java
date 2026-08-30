package com.metallum.client.metal.render;

/**
 * Pass-local cursor over an Iris program's immutable token layout.
 *
 * <p>The cursor lives on the render pass rather than in a ThreadLocal so the
 * binding loop performs only field/array accesses. Separate render passes can
 * therefore be encoded independently without sharing mutable cursor state.</p>
 */
public interface MetalIrisTokenBindingSession extends MetalTokenBindingPass {
    void metallum$beginIrisBindings(MetalIrisBindingTokenLayout layout);

    MetalBindingToken metallum$nextIrisUniformOrTexel(String compatibilityName);

    MetalBindingToken metallum$nextIrisSampler(String compatibilityName);

    void metallum$finishIrisBindings();
}
