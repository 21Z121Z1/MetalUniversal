package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalIrisBindingTokenLayout;
import com.metallum.client.metal.render.MetalTokenBindingPass;
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
 * and diagnostics, but the descriptor mutation path consumes the immutable
 * token sequence attached to the translated shader program. No String-to-token
 * lookup occurs while binding a post/final raster pass.</p>
 */
@Mixin(targets = "com.metallum.client.metal.render.IrisMetalPostChain")
public abstract class IrisMetalPostChainBindingTokenMixin {
    @Unique
    private static final ThreadLocal<BindingCursor> metallum$BINDING_CURSOR =
            ThreadLocal.withInitial(BindingCursor::new);

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
        metallum$BINDING_CURSOR.get().reset(layout);
    }

    @Inject(method = "bindResources", at = @At("RETURN"))
    private static void metallum$finishTokenBindings(final CallbackInfo ci) {
        metallum$BINDING_CURSOR.get().verifyCompleteAndClear();
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
        MetalTokenBindingPass tokenPass = metallum$tokenPass(renderPass);
        MetalBindingToken token = metallum$BINDING_CURSOR.get().nextUniformOrTexel(name);
        tokenPass.metallum$setUniform(token, name, value);
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
        MetalTokenBindingPass tokenPass = metallum$tokenPass(renderPass);
        MetalBindingToken token = metallum$BINDING_CURSOR.get().nextSampler(name);
        tokenPass.metallum$bindTexture(token, name, textureView, sampler);
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
        MetalTokenBindingPass tokenPass = metallum$tokenPass(renderPass);
        MetalBindingToken token = metallum$BINDING_CURSOR.get().nextSampler(name);
        tokenPass.metallum$bindStorageImage(token, name, textureView);
    }

    @Unique
    private static MetalTokenBindingPass metallum$tokenPass(final Object renderPass) {
        if (renderPass instanceof MetalTokenBindingPass tokenPass) {
            return tokenPass;
        }
        throw new IllegalStateException(
                "MetalRenderPass is missing the token-native private binding surface"
        );
    }

    @Unique
    private static final class BindingCursor {
        private MetalIrisBindingTokenLayout layout;
        private int uniformIndex;
        private int samplerIndex;

        private void reset(final MetalIrisBindingTokenLayout layout) {
            this.layout = layout;
            this.uniformIndex = 0;
            this.samplerIndex = 0;
        }

        private MetalBindingToken nextUniformOrTexel(final String actualName) {
            MetalIrisBindingTokenLayout current = requireLayout();
            if (this.uniformIndex < current.metallum$uniformBindingCount()) {
                int index = this.uniformIndex++;
                verifyName(current.metallum$uniformBindingName(index), actualName, "uniform", index);
                return current.metallum$uniformBindingToken(index);
            }
            return nextSampler(actualName);
        }

        private MetalBindingToken nextSampler(final String actualName) {
            MetalIrisBindingTokenLayout current = requireLayout();
            int index = this.samplerIndex++;
            if (index >= current.metallum$samplerBindingCount()) {
                throw new IllegalStateException(
                        "Iris raster binding emitted more sampler operations than its compiled token layout"
                );
            }
            verifyName(current.metallum$samplerBindingName(index), actualName, "sampler", index);
            return current.metallum$samplerBindingToken(index);
        }

        private void verifyCompleteAndClear() {
            MetalIrisBindingTokenLayout current = requireLayout();
            try {
                if (this.uniformIndex != current.metallum$uniformBindingCount()
                        || this.samplerIndex != current.metallum$samplerBindingCount()) {
                    throw new IllegalStateException(
                            "Iris raster binding/token sequence diverged: consumed uniforms="
                                    + this.uniformIndex + "/" + current.metallum$uniformBindingCount()
                                    + ", samplers=" + this.samplerIndex + "/"
                                    + current.metallum$samplerBindingCount()
                    );
                }
            } finally {
                this.layout = null;
                this.uniformIndex = 0;
                this.samplerIndex = 0;
            }
        }

        private MetalIrisBindingTokenLayout requireLayout() {
            if (this.layout == null) {
                throw new IllegalStateException("Iris raster token binding cursor is not active");
            }
            return this.layout;
        }

        private static void verifyName(
                final String expected,
                final String actual,
                final String kind,
                final int index
        ) {
            if (expected != actual && !expected.equals(actual)) {
                throw new IllegalStateException(
                        "Iris " + kind + " binding order diverged at slot " + index
                                + ": compiled='" + expected + "', runtime='" + actual + "'"
                );
            }
        }
    }
}
