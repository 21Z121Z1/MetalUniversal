package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.OptionalDouble;

/** Explicit transfer at the display-referred vanilla scene / linear Temporal boundary.
 * This does not recover HDR radiance or move Minecraft's tone mapping after Temporal.
 */
final class MetalFxColorTransfer implements AutoCloseable {
    private static final Identifier SHADER = Identifier.fromNamespaceAndPath("metallum", "core/metalfx_color_transfer");
    private static final BindGroupLayout INPUT = BindGroupLayout.builder()
            .withUniform("InputColor", UniformType.COMBINED_IMAGE_SAMPLER).build();
    private static final RenderPipeline DECODE = pipeline(true);
    private static final RenderPipeline ENCODE = pipeline(false);
    private final MetalDevice device;
    private final GpuSampler sampler;
    private MetalGpuTexture linearInput, linearOutput;

    MetalFxColorTransfer(MetalDevice device) {
        this.device = device;
        sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
    }

    private static RenderPipeline pipeline(boolean decode) {
        var builder = RenderPipeline.builder().withLocation(Identifier.fromNamespaceAndPath("metallum",
                        "pipeline/metalfx_color_" + (decode ? "decode" : "encode")))
                .withVertexShader(SHADER).withFragmentShader(SHADER).withBindGroupLayout(INPUT)
                .withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.empty(),
                        decode ? GpuFormat.RGBA16_FLOAT : GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        if (decode) builder.withShaderDefine("METALLUM_SRGB_DECODE");
        return builder.build();
    }

    MetalGpuTexture decode(MetalCommandEncoder encoder, MetalGpuTexture source, int outputWidth, int outputHeight) {
        if (source.getFormat() != GpuFormat.RGBA8_UNORM) {
            throw new IllegalArgumentException("Temporal scene transfer requires the audited vanilla RGBA8_UNORM target");
        }
        int width = source.getWidth(0), height = source.getHeight(0);
        if (linearInput == null || linearInput.getWidth(0) != width || linearInput.getHeight(0) != height
                || linearOutput.getWidth(0) != outputWidth || linearOutput.getHeight(0) != outputHeight) {
            // Retire submitted work before replacing residency allocations.
            if (linearInput != null || linearOutput != null) device.waitForSubmittedGpuWork();
            closeTextures();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT
                    | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
            linearInput = (MetalGpuTexture) device.createTexture("MetalFX linear scene input", usage,
                    GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
            linearOutput = (MetalGpuTexture) device.createTexture("MetalFX linear scene output",
                    usage | MetalGpuTexture.USAGE_SHADER_WRITE, GpuFormat.RGBA16_FLOAT, outputWidth, outputHeight, 1, 1);
        }
        draw(encoder, source, linearInput, DECODE);
        return linearInput;
    }

    MetalGpuTexture output() { return linearOutput; }

    void encode(MetalCommandEncoder encoder, MetalGpuTexture destination) {
        if (destination.getFormat() != GpuFormat.RGBA8_UNORM
                || destination.getWidth(0) != linearOutput.getWidth(0)
                || destination.getHeight(0) != linearOutput.getHeight(0)) {
            throw new IllegalArgumentException("Temporal output transfer requires a matching display-referred target");
        }
        draw(encoder, linearOutput, destination, ENCODE);
    }

    private void draw(MetalCommandEncoder encoder, MetalGpuTexture source, MetalGpuTexture destination,
                      RenderPipeline pipeline) {
        // texelFetch preserves raster coordinates and alpha without resampling.
        try (var input = device.createTextureView(source); var output = device.createTextureView(destination);
             var pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "MetalFX color transfer")
                     .withColorAttachment(output, Optional.empty()).build())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("InputColor", input, sampler);
            pass.draw(3, 1, 0, 0);
        }
    }

    private void closeTextures() {
        if (linearInput != null) linearInput.close();
        if (linearOutput != null) linearOutput.close();
        linearInput = null; linearOutput = null;
    }

    @Override public void close() { closeTextures(); sampler.close(); }
}
