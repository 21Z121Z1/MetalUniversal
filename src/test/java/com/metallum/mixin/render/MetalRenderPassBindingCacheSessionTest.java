package com.metallum.mixin.render;

import com.metallum.client.metal.render.MetalBindingToken;
import com.metallum.client.metal.render.MetalIrisBindingTokenLayout;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalRenderPassBindingCacheSessionTest {
    private static final MetalBindingToken U0 = new MetalBindingToken(100, -1, 0);
    private static final MetalBindingToken U1 = new MetalBindingToken(101, -1, 0);
    private static final MetalBindingToken S0 = new MetalBindingToken(102, -1, 0);
    private static final MetalBindingToken S1 = new MetalBindingToken(103, -1, 0);
    private static final MetalIrisBindingTokenLayout LAYOUT = new TestLayout(
            List.of("u0", "u1"), List.of(U0, U1),
            List.of("s0", "s1"), List.of(S0, S1)
    );

    @Test
    void cursorConsumesUniformsThenTexelSamplerEntriesWithoutLookup() {
        TestPass pass = new TestPass();
        pass.metallum$beginIrisBindings(LAYOUT);

        assertSame(U0, pass.metallum$nextIrisUniformOrTexel("u0"));
        assertSame(U1, pass.metallum$nextIrisUniformOrTexel("u1"));
        assertSame(S0, pass.metallum$nextIrisUniformOrTexel("s0"));
        assertSame(S1, pass.metallum$nextIrisSampler("s1"));

        pass.metallum$finishIrisBindings();
        assertThrows(IllegalStateException.class,
                () -> pass.metallum$nextIrisSampler("s0"));
    }

    @Test
    void cursorFailsClosedOnNameOrShapeDivergence() {
        TestPass wrongName = new TestPass();
        wrongName.metallum$beginIrisBindings(LAYOUT);
        assertThrows(IllegalStateException.class,
                () -> wrongName.metallum$nextIrisUniformOrTexel("renamed"));

        TestPass incomplete = new TestPass();
        incomplete.metallum$beginIrisBindings(LAYOUT);
        incomplete.metallum$nextIrisUniformOrTexel("u0");
        assertThrows(IllegalStateException.class, incomplete::metallum$finishIrisBindings);
        // finish clears even when verification fails, so a fresh session is legal.
        incomplete.metallum$beginIrisBindings(LAYOUT);

        TestPass nested = new TestPass();
        nested.metallum$beginIrisBindings(LAYOUT);
        assertThrows(IllegalStateException.class,
                () -> nested.metallum$beginIrisBindings(LAYOUT));
    }

    private static final class TestPass extends MetalRenderPassBindingCacheMixin {
        @Override
        public void setUniform(final String name, final GpuBufferSlice value) {
        }

        @Override
        public void bindTexture(
                final String name,
                final GpuTextureView textureView,
                final GpuSampler sampler
        ) {
        }

        @Override
        void bindStorageImage(final String name, final GpuTextureView textureView) {
        }
    }

    private record TestLayout(
            List<String> uniformNames,
            List<MetalBindingToken> uniformTokens,
            List<String> samplerNames,
            List<MetalBindingToken> samplerTokens
    ) implements MetalIrisBindingTokenLayout {
        @Override
        public int metallum$uniformBindingCount() {
            return uniformTokens.size();
        }

        @Override
        public MetalBindingToken metallum$uniformBindingToken(final int index) {
            return uniformTokens.get(index);
        }

        @Override
        public String metallum$uniformBindingName(final int index) {
            return uniformNames.get(index);
        }

        @Override
        public int metallum$samplerBindingCount() {
            return samplerTokens.size();
        }

        @Override
        public MetalBindingToken metallum$samplerBindingToken(final int index) {
            return samplerTokens.get(index);
        }

        @Override
        public String metallum$samplerBindingName(final int index) {
            return samplerNames.get(index);
        }
    }
}
