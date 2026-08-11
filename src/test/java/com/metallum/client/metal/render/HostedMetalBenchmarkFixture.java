package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;

import java.lang.foreign.MemorySegment;

/** Test-only owner for a real offscreen Metal device/queue/render target. */
public final class HostedMetalBenchmarkFixture implements AutoCloseable {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 16;
    private static final int TEXTURE_USAGE =
            GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final MetalGpuTexture texture;

    public HostedMetalBenchmarkFixture() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        if (MetalNativeBridge.isNullHandle(nativeDevice)) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice returned null");
        }
        ShaderSource source = (identifier, type) -> null;
        this.device = new MetalDevice(
                source,
                new GpuDebugOptions(2, false, false, false),
                nativeDevice,
                MemorySegment.NULL,
                "Hosted Metal paired-path benchmark",
                MemorySegment.NULL
        );
        this.encoder = device.commandEncoder();
        this.texture = (MetalGpuTexture) device.createTexture(
                "hosted-paired-state-target",
                TEXTURE_USAGE,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );

        // Drain any device-construction uploads before the first warmup sample.
        this.encoder.endEncoder();
        this.encoder.submit();
        this.device.waitForSubmittedGpuWork();
    }

    public MTLRenderCommandEncoder makeRenderEncoder() {
        this.encoder.endEncoder();
        return this.encoder.commandBuffer().makeRenderCommandEncoder(
                this.texture.nativeHandle(),
                MemorySegment.NULL,
                WIDTH,
                HEIGHT,
                0,
                0.0F,
                0.0F,
                0.0F,
                1.0F,
                0,
                1.0
        );
    }

    public void submitAndWait() {
        this.encoder.submit();
        this.device.waitForSubmittedGpuWork();
    }

    public int width() {
        return WIDTH;
    }

    public int height() {
        return HEIGHT;
    }

    @Override
    public void close() {
        this.texture.close();
        MetalFxManager.close();
        this.device.close();
    }
}
