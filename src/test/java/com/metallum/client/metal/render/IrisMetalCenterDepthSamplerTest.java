package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
final class IrisMetalCenterDepthSamplerTest {
    private MetalDevice device;

    @AfterEach
    void closeDevice() {
        if (this.device != null) {
            this.device.close();
        }
    }

    @Test
    void samplesCenterDepthAndAdvancesHalfLifeHistoryOnMetal() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource fallback = (identifier, type) -> null;
        this.device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris center-depth integration device",
                MemorySegment.NULL
        );

        int depthUsage = GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_COPY_SRC;
        try (MetalGpuTexture liveDepth = (MetalGpuTexture) this.device.createTexture(
                "iris-center-depth-input", depthUsage, GpuFormat.D32_FLOAT, 4, 4, 1, 1
        ); MetalGpuTextureView liveDepthView = (MetalGpuTextureView) this.device.createTextureView(liveDepth);
             IrisMetalCenterDepthSampler centerDepth = new IrisMetalCenterDepthSampler(
                     this.device, 7, 1.0F, fallback
             )) {
            assertEquals(GpuFormat.R32_FLOAT, centerDepth.currentTexture().getFormat());
            assertEquals(GpuFormat.R32_FLOAT, centerDepth.historyTexture().getFormat());
            assertEquals(1, centerDepth.currentTexture().getWidth(0));
            assertEquals(1, centerDepth.currentTexture().getHeight(0));
            assertEquals(1, centerDepth.historyTexture().getWidth(0));
            assertEquals(1, centerDepth.historyTexture().getHeight(0));

            MetalRenderPass.TextureViewAndSampler binding = centerDepth.binding();
            assertSame(centerDepth.historyTexture(), binding.textureView().texture());
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeU());
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeV());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMinFilter());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMagFilter());
            assertTrue(Float.isNaN(readback(centerDepth.historyTexture()).getFloat(0)));

            MetalCommandEncoder encoder = this.device.createCommandEncoder();
            encoder.clearDepthTexture(liveDepth, 0.25);
            centerDepth.sample(liveDepthView, 0.1F);
            encoder.submit();
            this.device.waitForSubmittedGpuWork();
            assertEquals(0.25F, readback(centerDepth.currentTexture()).getFloat(0), 0.001F);
            assertEquals(0.25F, readback(centerDepth.historyTexture()).getFloat(0), 0.001F);

            encoder.clearDepthTexture(liveDepth, 0.75);
            centerDepth.sample(liveDepthView, 0.1F);
            encoder.submit();
            this.device.waitForSubmittedGpuWork();
            assertEquals(0.5F, readback(centerDepth.currentTexture()).getFloat(0), 0.001F);
            assertEquals(0.5F, readback(centerDepth.historyTexture()).getFloat(0), 0.001F);

            centerDepth.close();
            centerDepth.close();
            assertThrows(IllegalStateException.class, centerDepth::binding);
        }
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) this.device.createBuffer(
                () -> "iris center-depth readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                Float.BYTES
        )) {
            MetalCommandEncoder encoder = this.device.createCommandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            this.device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(Float.BYTES).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(Float.BYTES).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return copy;
        }
    }
}
