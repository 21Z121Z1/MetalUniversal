package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalIrisBindingTokenLayout;
import com.metallum.client.metal.render.MetalIrisTokenBindingSession;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes MetalUniversal-owned Iris raster bindings through precompiled tokens.
 *
 * <p>The original loops retain their compatibility names for resource lookup
 * and diagnostics, but descriptor mutation consumes the immutable token sequence
 * attached to the translated shader program. Cursor state lives on the render
 * pass, so the inner binding loop has no String-to-token or ThreadLocal lookup.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainBindingTokenMixin {
    @Inject(method = "bindResources", at = @At("HEAD"))
    private static void metallum$beginTokenBindings(
            @Coerce final Object renderPass,
            @Coerce final Object info,
            @Coerce final Object program,
            @Coerce final Object globalBlend,
            @Coerce final Object targets,
            @Coerce final Object resources,
            final CallbackInfo ci
    ) {
        if (!(program instanceof MetalIrisBindingTokenLayout layout)) {
            throw new IllegalStateException(
                    "Iris raster program is missing its compiled Metal binding-token layout"
            );
        }
        metallum$tokenSession(renderPass).metallum$beginIrisBindings(layout);
    }

    @Inject(method = "bindResources", at = @At("RETURN"))
    private static void metallum$finishTokenBindings(
            @Coerce final Object renderPass,
            @Coerce final Object info,
            @Coerce final Object program,
            @Coerce final Object globalBlend,
            @Coerce final Object targets,
            @Coerce final Object resources,
            final CallbackInfo ci
    ) {
        metallum$tokenSession(renderPass).metallum$finishIrisBindings();
    }

    @Redirect(
            method = "bindResources",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/metallum/client/metal/render/MetalRenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"
            )
    )
    private static void metallum$setUniformByToken(
            @Coerce final Object renderPass,
            final String name,
            final GpuBufferSlice value
    ) {
        MetalIrisTokenBindingSession session = metallum$tokenSession(renderPass);
        MetalBindingToken token = session.metallum$nextIrisUniformOrTexel(name);
        session.metallum$setUniform(token, name, value);
    }

    @Redirect(
            method = "bindResources",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/metallum/client/metal/render/MetalRenderPass;bindTexture(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;Lcom/mojang/blaze3d/textures/GpuSampler;)V"
            )
    )
    private static void metallum$bindTextureByToken(
            @Coerce final Object renderPass,
            final String name,
            final GpuTextureView textureView,
            final GpuSampler sampler
    ) {
        MetalIrisTokenBindingSession session = metallum$tokenSession(renderPass);
        MetalBindingToken token = session.metallum$nextIrisSampler(name);
        session.metallum$bindTexture(token, name, textureView, sampler);
    }

    @Redirect(
            method = "bindResources",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/metallum/client/metal/render/MetalRenderPass;bindStorageImage(Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
            )
    )
    private static void metallum$bindStorageImageByToken(
            @Coerce final Object renderPass,
            final String name,
            final GpuTextureView textureView
    ) {
        MetalIrisTokenBindingSession session = metallum$tokenSession(renderPass);
        MetalBindingToken token = session.metallum$nextIrisSampler(name);
        session.metallum$bindStorageImage(token, name, textureView);
    }

    @Unique
    private static MetalIrisTokenBindingSession metallum$tokenSession(final Object renderPass) {
        if (renderPass instanceof MetalIrisTokenBindingSession session) {
            return session;
        }
        throw new IllegalStateException(
                "MetalRenderPass is missing the Iris token-native binding session"
        );
    }
}
