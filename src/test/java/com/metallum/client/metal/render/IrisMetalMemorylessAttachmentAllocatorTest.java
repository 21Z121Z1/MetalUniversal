package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalMemorylessAttachmentAllocatorTest {
    private static IrisMetalOptimizationPlan.ResolvedAttachment attachment(
            final IrisMetalOptimizationPlan.LoadAction load,
            final IrisMetalOptimizationPlan.StoreAction store,
            final IrisMetalOptimizationPlan.LifetimeClassification classification,
            final int nextUse
    ) {
        int pass = 3;
        int lastUse = nextUse >= 0 ? nextUse : pass;
        var lifetime = new IrisMetalOptimizationPlan.AttachmentLifetime(
                "allocation/17/generation/2/mip/0",
                17L,
                2L,
                0,
                pass,
                pass,
                lastUse,
                nextUse,
                nextUse < 0 ? "NONE" : "SAMPLED_READ"
        );
        return new IrisMetalOptimizationPlan.ResolvedAttachment(
                "iris/composite/render/3/test",
                "iris/composite/test",
                0,
                "colortex4",
                17L,
                2L,
                0,
                "main",
                load,
                store,
                pass,
                IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER,
                classification,
                lifetime.allocationKey(),
                lifetime
        );
    }

    @Test
    void onlyResolvedPassLocalDiscardAttachmentIsEligible() {
        assertTrue(IrisMetalMemorylessAttachmentAllocator.eligible(attachment(
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT,
                -1
        )));
        assertFalse(IrisMetalMemorylessAttachmentAllocator.eligible(attachment(
                IrisMetalOptimizationPlan.LoadAction.LOAD,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT,
                -1
        )));
        assertFalse(IrisMetalMemorylessAttachmentAllocator.eligible(attachment(
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.STORE,
                IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT,
                -1
        )));
        assertFalse(IrisMetalMemorylessAttachmentAllocator.eligible(attachment(
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                IrisMetalOptimizationPlan.LifetimeClassification.CONSERVATIVE_PERSISTENT,
                -1
        )));
        assertFalse(IrisMetalMemorylessAttachmentAllocator.eligible(attachment(
                IrisMetalOptimizationPlan.LoadAction.CLEAR,
                IrisMetalOptimizationPlan.StoreAction.DONT_CARE,
                IrisMetalOptimizationPlan.LifetimeClassification.PASS_LOCAL_TRANSIENT,
                4
        )));
    }

    @Test
    void memorylessPhysicalTextureRejectsLongLivedUsages() {
        assertTrue(MetalGpuTexture.memorylessCompatible(
                GpuTexture.USAGE_RENDER_ATTACHMENT,
                MetalTextureDimension.TWO_D,
                1,
                1
        ));
        assertFalse(MetalGpuTexture.memorylessCompatible(
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                MetalTextureDimension.TWO_D,
                1,
                1
        ));
        assertFalse(MetalGpuTexture.memorylessCompatible(
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                MetalTextureDimension.TWO_D,
                1,
                1
        ));
        assertFalse(MetalGpuTexture.memorylessCompatible(
                GpuTexture.USAGE_RENDER_ATTACHMENT | MetalGpuTexture.USAGE_SHADER_WRITE,
                MetalTextureDimension.TWO_D,
                1,
                1
        ));
        assertFalse(MetalGpuTexture.memorylessCompatible(
                GpuTexture.USAGE_RENDER_ATTACHMENT,
                MetalTextureDimension.THREE_D,
                1,
                1
        ));
    }
}
