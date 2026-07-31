package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.pbr.TextureTracker;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Binds generation-owned values for a core draw without manufacturing fallbacks. */
final class IrisMetalDynamicDrawBindings {
    private IrisMetalDynamicDrawBindings() {
    }

    static void bindCore(
            final MetalRenderPass pass,
            final IrisMetalCoreDrawBridge.CoreDrawOverride draw
    ) {
        MetalCompiledRenderPipeline.ResourceBinding uniforms = draw.compiled()
                .resource(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME);
        if (uniforms == null) {
            return;
        }
        GpuBufferSlice base = draw.pipeline().uniformSlice(draw.key());
        int blockSize = draw.pipeline().coreDrawBlockSize(draw.key());
        if (blockSize == 0) {
            pass.setUniform(uniforms.name(), base);
            return;
        }
        ByteBuffer dynamicTransforms = readable(pass.uniformSlice("DynamicTransforms"), "DynamicTransforms");
        ByteBuffer projection = readable(pass.uniformSlice("Projection"), "Projection");
        IrisMetalUniformValues.DrawUniformContext context = drawContext(pass, draw);
        GpuBufferSlice slice;
        try (GpuBufferSlice.MappedView mapped = pass.allocateTransient(
                blockSize, 16L, GpuBuffer.USAGE_UNIFORM
        )) {
            draw.pipeline().materializeCoreDrawUniforms(
                    draw.key(), mapped.data(), dynamicTransforms, projection, context
            );
            slice = mapped.slice();
        }
        pass.setUniform(uniforms.name(), slice);
    }

    private static IrisMetalUniformValues.DrawUniformContext drawContext(
            final MetalRenderPass pass,
            final IrisMetalCoreDrawBridge.CoreDrawOverride draw
    ) {
        MetalRenderPass.TextureViewAndSampler gtexture = first(
                pass.boundTexture("Sampler0"),
                pass.boundTexture("u_BlockTex"),
                pass.boundTexture("gtexture")
        );
        int atlasWidth = 0;
        int atlasHeight = 0;
        if (gtexture != null && gtexture.textureView().texture() instanceof MetalGpuTexture texture
                && TextureTracker.INSTANCE.getTexture(texture.iris$getGlId()) instanceof TextureAtlas) {
            atlasWidth = gtexture.textureView().getWidth(0);
            atlasHeight = gtexture.textureView().getHeight(0);
        }

        Optional<BlendFunction> blend = Optional.empty();
        ColorTargetState target = draw.source().getColorTargetState();
        if (target != null) {
            blend = target.blendFunction();
        }
        BlendModeOverride override = draw.program().program().directives()
                .getBlendModeOverride()
                .orElseGet(() -> draw.key().getProgram().getBlendModeOverride());
        if (override != null) {
            blend = IrisMetalCompiledPrograms.irisBlendFunction(override);
        }
        return new IrisMetalUniformValues.DrawUniformContext(
                gtexture == null ? null : gtexture.textureView(),
                atlasWidth,
                atlasHeight,
                blend
        );
    }

    private static MetalRenderPass.@Nullable TextureViewAndSampler first(
            final MetalRenderPass.@Nullable TextureViewAndSampler... values
    ) {
        for (MetalRenderPass.TextureViewAndSampler value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static @Nullable ByteBuffer readable(
            final @Nullable GpuBufferSlice slice,
            final String name
    ) {
        if (slice == null) {
            return null;
        }
        if (!(slice.buffer() instanceof MetalGpuBuffer buffer)) {
            throw new IllegalStateException("Iris core draw " + name + " is not backed by Metal");
        }
        try {
            return buffer.sliceStorage(slice.offset(), slice.length());
        } catch (IllegalStateException failure) {
            throw new IllegalStateException(
                    "Iris core draw " + name + " uniform data is not CPU-readable", failure
            );
        }
    }
}
