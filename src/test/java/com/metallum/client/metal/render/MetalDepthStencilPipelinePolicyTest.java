package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalDepthStencilPipelinePolicyTest {
    @Test
    void appleSiliconShippingTargetRejectsDepth24Stencil8() {
        assertFalse(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth24Unorm_Stencil8,
                MTLPixelFormat.Depth24Unorm_Stencil8
        ));
    }

    @Test
    void shippingTargetAcceptsCanonicalDepthStencilSignatures() {
        assertTrue(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Invalid,
                MTLPixelFormat.Invalid
        ));
        assertTrue(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth16Unorm,
                MTLPixelFormat.Invalid
        ));
        assertTrue(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth32Float,
                MTLPixelFormat.Invalid
        ));
        assertTrue(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth32Float_Stencil8,
                MTLPixelFormat.Depth32Float_Stencil8
        ));
        assertTrue(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Invalid,
                MTLPixelFormat.Stencil8
        ));
    }

    @Test
    void mismatchedPackedDepthStencilSignatureFailsClosed() {
        assertFalse(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth32Float_Stencil8,
                MTLPixelFormat.Invalid
        ));
        assertFalse(MetalCompiledRenderPipeline.isSupportedDepthStencilFormatPair(
                MTLPixelFormat.Depth32Float,
                MTLPixelFormat.Stencil8
        ));
    }
}
