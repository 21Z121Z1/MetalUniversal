package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalTextureViewFormatTest {
    @Test
    void metalSrgbFormatsUseTheSdkValues() {
        assertEquals(71L, MTLPixelFormat.RGBA8Unorm_sRGB.value);
        assertEquals(81L, MTLPixelFormat.BGRA8Unorm_sRGB.value);
        assertEquals(0x10L, MTLTextureUsage.PixelFormatView.value);
        assertEquals(0x20L, MTLTextureUsage.ShaderAtomic.value);
    }

    @Test
    void onlyByteIdenticalLinearAndSrgbPairsAreViewCompatible() {
        assertTrue(MTLPixelFormat.RGBA8Unorm.isViewCompatibleWith(MTLPixelFormat.RGBA8Unorm_sRGB));
        assertTrue(MTLPixelFormat.RGBA8Unorm_sRGB.isViewCompatibleWith(MTLPixelFormat.RGBA8Unorm));
        assertTrue(MTLPixelFormat.BGRA8Unorm.isViewCompatibleWith(MTLPixelFormat.BGRA8Unorm_sRGB));
        assertTrue(MTLPixelFormat.BGRA8Unorm_sRGB.isViewCompatibleWith(MTLPixelFormat.BGRA8Unorm));
        assertTrue(MTLPixelFormat.RG16Float.isViewCompatibleWith(MTLPixelFormat.RG16Float));

        assertFalse(MTLPixelFormat.RGBA8Unorm.isViewCompatibleWith(MTLPixelFormat.BGRA8Unorm_sRGB));
        assertFalse(MTLPixelFormat.RGBA8Unorm.isViewCompatibleWith(MTLPixelFormat.RGBA8Uint));
        assertFalse(MTLPixelFormat.RG16Float.isViewCompatibleWith(MTLPixelFormat.RGBA8Unorm_sRGB));
    }

    @Test
    void backendPixelFormatViewUsageMapsToMetal() {
        long ordinaryUsage = MetalGpuTexture.toMtlTextureUsage(
                GpuTexture.USAGE_TEXTURE_BINDING,
                MTLPixelFormat.RGBA8Unorm
        );
        assertEquals(0L, ordinaryUsage & MTLTextureUsage.PixelFormatView.value);

        long viewUsage = MetalGpuTexture.toMtlTextureUsage(
                GpuTexture.USAGE_TEXTURE_BINDING | MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW,
                MTLPixelFormat.RGBA8Unorm
        );
        assertEquals(MTLTextureUsage.PixelFormatView.value, viewUsage & MTLTextureUsage.PixelFormatView.value);
        assertEquals(MTLTextureUsage.ShaderRead.value, viewUsage & MTLTextureUsage.ShaderRead.value);
    }
}
