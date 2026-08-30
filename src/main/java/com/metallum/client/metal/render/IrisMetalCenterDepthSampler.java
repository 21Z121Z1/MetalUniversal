package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Metal implementation of Iris's temporally smoothed center-depth sampler. */
@Environment(EnvType.CLIENT)
final class IrisMetalCenterDepthSampler implements AutoCloseable {
    static final String SAMPLER_NAME = "iris_centerDepthSmooth";

    private static final double LN2 = Math.log(2.0);
    private static final int PARAMETER_BYTES = 16;
    private static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;
    private static final String VERTEX_SOURCE = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;
    private static final String FRAGMENT_SOURCE = """
            #version 450
            layout(std140) uniform CenterDepthParameters {
                float lastFrameTime;
                float decay;
                vec2 padding;
            };
            uniform sampler2D depth;
            uniform sampler2D altDepth;
            layout(location = 0) out float iris_fragColor;

            void main() {
                float currentDepth = texture(depth, vec2(0.5)).r;
                float weight = 1.0 - exp(-decay * lastFrameTime);
                float oldDepth = texture(altDepth, vec2(0.5)).r;
                if (isnan(oldDepth)) {
                    oldDepth = currentDepth;
                }
                iris_fragColor = mix(oldDepth, currentDepth, weight);
            }
            """;

    private final MetalDevice device;
    private final MetalGpuTexture currentTexture;
    private final MetalGpuTexture historyTexture;
    private final MetalGpuTextureView currentView;
    private final MetalGpuTextureView historyView;
    private final MetalGpuSampler sampler;
    private final GpuBuffer parameters;
    private final ByteBuffer parameterStaging;
    private final RenderPipeline pipeline;
    private final float decay;
    private boolean closed;

    /**
     * Mirrors Iris 1.11.2's {@code CenterDepthSampler} at commit
     * {@code 20e226b14fd2c3ba192e16ae2c8af4a27987767c}. The generated source
     * is composed with {@code fallback} so later lazy PSO compilation retains
     * both Mojang and Iris shader sources.
     */
    IrisMetalCenterDepthSampler(
            final MetalDevice device,
            final int generation,
            final float halfLife,
            final ShaderSource fallback
    ) {
        this.device = Objects.requireNonNull(device, "device");
        Objects.requireNonNull(fallback, "fallback");
        this.decay = (float) (1.0F / ((halfLife * 0.1) / LN2));

        String base = "iris/gen" + generation + "/center_depth";
        Identifier vertexId = Identifier.fromNamespaceAndPath("metallum", base + "_v");
        Identifier fragmentId = Identifier.fromNamespaceAndPath("metallum", base + "_f");
        ShaderSource source = (identifier, type) -> {
            if (identifier.equals(vertexId) && type == ShaderType.VERTEX) {
                return VERTEX_SOURCE;
            }
            if (identifier.equals(fragmentId) && type == ShaderType.FRAGMENT) {
                return FRAGMENT_SOURCE;
            }
            return fallback.get(identifier, type);
        };
        BindGroupLayout resources = BindGroupLayout.builder()
                .withUniform("CenterDepthParameters", UniformType.UNIFORM_BUFFER)
                .withSampler("depth")
                .withSampler("altDepth")
                .build();
        this.pipeline = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("metallum", base))
                .withVertexShader(vertexId)
                .withFragmentShader(fragmentId)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withBindGroupLayout(resources)
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.R32_FLOAT, ColorTargetState.WRITE_RED
                ))
                .build();
        CompiledRenderPipeline compiled = device.precompilePipeline(this.pipeline, source);
        if (!device.asyncPrewarmEnabled() && !compiled.isValid()) {
            throw new IllegalStateException("Metal center-depth render pipeline is invalid");
        }

        this.currentTexture = (MetalGpuTexture) device.createTexture(
                "metallum:iris_center_depth_current",
                TEXTURE_USAGE,
                GpuFormat.R32_FLOAT,
                1,
                1,
                1,
                1
        );
        this.historyTexture = (MetalGpuTexture) device.createTexture(
                "metallum:iris_center_depth_history",
                TEXTURE_USAGE,
                GpuFormat.R32_FLOAT,
                1,
                1,
                1,
                1
        );
        this.currentTexture.registerAllocationIdentity();
        this.historyTexture.registerAllocationIdentity();
        this.currentView = (MetalGpuTextureView) device.createTextureView(this.currentTexture);
        this.historyView = (MetalGpuTextureView) device.createTextureView(this.historyTexture);
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.of(0.0)
        );
        this.parameters = device.createBuffer(
                () -> "metallum:iris_center_depth_parameters",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                PARAMETER_BYTES
        );
        this.parameterStaging = ByteBuffer.allocateDirect(PARAMETER_BYTES).order(ByteOrder.nativeOrder());

        ByteBuffer initialHistory = ByteBuffer.allocateDirect(Float.BYTES).order(ByteOrder.nativeOrder());
        initialHistory.putFloat(0, Float.NaN);
        device.commandEncoder().writeToTexture(
                this.historyTexture,
                initialHistory,
                0,
                0,
                0,
                0,
                1,
                1
        );
    }

    /** Samples live depth at the texture center, smooths it, then advances history. */
    void sample(final GpuTextureView liveDepth, final float lastFrameTime) {
        ensureOpen();
        Objects.requireNonNull(liveDepth, "liveDepth");
        if (liveDepth.isClosed()) {
            throw new IllegalArgumentException("Live center-depth texture view is closed");
        }

        this.parameterStaging.putFloat(0, lastFrameTime);
        this.parameterStaging.putFloat(Float.BYTES, this.decay);
        this.parameterStaging.putLong(2 * Float.BYTES, 0L);
        this.parameterStaging.position(0);
        this.parameterStaging.limit(PARAMETER_BYTES);

        MetalCommandEncoder encoder = this.device.commandEncoder();
        encoder.writeToBuffer(this.parameters.slice(), this.parameterStaging);
        RenderPassDescriptor descriptor = RenderPassDescriptor
                .create(() -> "Iris centerDepthSmooth sampler")
                .withColorAttachment(this.currentView, Optional.empty())
                .withRenderArea(new RenderPass.RenderArea(0, 0, 1, 1));
        MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
        try {
            pass.setPipeline(this.pipeline);
            pass.setUniform("CenterDepthParameters", this.parameters);
            pass.bindTexture("depth", liveDepth, this.sampler);
            pass.bindTexture("altDepth", this.historyView, this.sampler);
            pass.draw(3, 1, 0, 0);
        } finally {
            encoder.submitRenderPass();
        }
        encoder.copyTextureToTexture(
                this.currentTexture,
                this.historyTexture,
                0,
                0,
                0,
                0,
                0,
                1,
                1
        );
    }

    MetalRenderPass.TextureViewAndSampler binding() {
        ensureOpen();
        return new MetalRenderPass.TextureViewAndSampler(this.historyView, this.sampler);
    }

    MetalGpuTexture currentTexture() {
        ensureOpen();
        return this.currentTexture;
    }

    MetalGpuTexture historyTexture() {
        ensureOpen();
        return this.historyTexture;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris center-depth sampler is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.currentView.close();
        this.historyView.close();
        this.currentTexture.close();
        this.historyTexture.close();
        this.sampler.close();
        this.parameters.close();
    }
}
