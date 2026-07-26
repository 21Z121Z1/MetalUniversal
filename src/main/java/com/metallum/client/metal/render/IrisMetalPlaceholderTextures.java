package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalDouble;

/**
 * 1×1 stand-ins for the pack samplers B2-1 has no real source for.
 *
 * <p>A pack's {@code gbuffers_terrain} samples whatever the pack author
 * declared — noise textures, shadow maps, previous-pass colour attachments.
 * B2-1 runs the gbuffer program alone, with no shadow pass and no composite
 * chain, so most of those have no content yet. Binding a 1×1 texture keeps the
 * draw valid and makes the missing input visually obvious (a flat contribution)
 * instead of failing the pass.</p>
 *
 * <p>Two flavours are needed because Metal type-checks the binding against the
 * shader's declaration: a colour texture for {@code sampler2D}, and a depth
 * texture with a compare sampler for {@code sampler2DShadow} (which SPIRV-Cross
 * emits as {@code depth2d} + {@code sample_compare}). Binding a colour texture
 * to a shadow sampler is a hard validation failure, not a wrong pixel.</p>
 */
@Environment(EnvType.CLIENT)
final class IrisMetalPlaceholderTextures implements AutoCloseable {
    private static final int SAMPLED_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
    /** Shadow depth also needs the attachment bit; Metal validates usage at bind time. */
    private static final int DEPTH_USAGE = SAMPLED_USAGE | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private final GpuTexture color;
    private final GpuTextureView colorView;
    private final GpuTexture depth;
    private final GpuTextureView depthView;
    private final MetalGpuSampler colorSampler;
    private final MetalGpuSampler shadowSampler;
    private boolean closed;

    IrisMetalPlaceholderTextures(final MetalDevice device) {
        this.color = device.createTexture(
                () -> "metallum:iris_placeholder_color", SAMPLED_USAGE, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
        this.colorView = device.createTextureView(this.color);
        this.depth = device.createTexture(
                () -> "metallum:iris_placeholder_shadow", DEPTH_USAGE, GpuFormat.D32_FLOAT, 1, 1, 1, 1);
        this.depthView = device.createTextureView(this.depth);

        this.colorSampler = new MetalGpuSampler(
                device, AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()
        );
        // LESS_EQUAL against a cleared (1.0) depth texture makes every shadow
        // lookup return "lit", i.e. no spurious shadowing while the shadow pass
        // does not run.
        this.shadowSampler = new MetalGpuSampler(
                device, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty(),
                MTLCompareFunction.LessEqual
        );

        ByteBuffer white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        white.putInt(0, 0xFFFFFFFF);
        device.createCommandEncoder().writeToTexture(this.color, white, 0, 0, 0, 0, 1, 1);
    }

    MetalRenderPass.TextureViewAndSampler color() {
        return new MetalRenderPass.TextureViewAndSampler(this.colorView, this.colorSampler);
    }

    MetalRenderPass.TextureViewAndSampler shadow() {
        return new MetalRenderPass.TextureViewAndSampler(this.depthView, this.shadowSampler);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.colorView.close();
        this.color.close();
        this.depthView.close();
        this.depth.close();
        this.colorSampler.close();
        this.shadowSampler.close();
    }
}
