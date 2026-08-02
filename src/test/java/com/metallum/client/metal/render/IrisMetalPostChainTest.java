package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPostChainTest {
    @Test
    void passReadsFrozenSnapshotThenAppliesDrawBuffersAndExplicitFlipsInIrisOrder() {
        BitSet before = bits(1);
        BitSet history = new BitSet();
        Map<Integer, Boolean> explicit = new LinkedHashMap<>();
        explicit.put(1, false);
        explicit.put(2, true);
        explicit.put(0, true);

        IrisMetalPostChain.FlipTransition transition = IrisMetalPostChain.transition(
                before, history, new int[]{0, 1}, explicit, 4
        );

        // Snapshot is taken before either kind of flip.
        assertEquals(bits(1), transition.readsFromAlt());
        // DRAWBUFFERS flips 0, skips explicitly-false 1. Explicit true then
        // flips 2 and flips 0 a second time, exactly like CompositeRenderer.
        assertEquals(bits(1, 2), transition.stateAfter());
        assertEquals(bits(0, 2), transition.flippedAtLeastOnceAfter());
        assertEquals(bits(1), before, "planner must not mutate its input snapshot");
        assertTrue(history.isEmpty(), "planner must not mutate input history");
    }

    @Test
    void explicitFalseSuppressesImplicitDrawBufferFlip() {
        IrisMetalPostChain.FlipTransition transition = IrisMetalPostChain.transition(
                new BitSet(), new BitSet(), new int[]{0, 3}, Map.of(3, false), 4
        );

        assertEquals(bits(0), transition.stateAfter());
        assertEquals(bits(0), transition.flippedAtLeastOnceAfter());
        assertFalse(transition.stateAfter().get(3));
    }

    @Test
    void preFlipsToggleOnlyStageInputAndDoNotCreateWriteHistory() {
        BitSet before = bits(0, 2);
        BitSet after = IrisMetalPostChain.applyPreFlips(
                before,
                Map.of(0, true, 1, false, 3, true),
                4
        );

        assertEquals(bits(2, 3), after);
        assertEquals(bits(0, 2), before);
    }

    @Test
    void finalHistoryCopiesOnlyFlippedTargetsThatAreNotClearedEveryFrame() {
        Set<Integer> histories = IrisMetalPostChain.finalHistoryTargets(
                bits(0, 2, 4), Set.of(2, 7), 8
        );

        assertEquals(Set.of(0, 4), histories);
    }

    @Test
    void finalStageHasItsOwnExecutionIdentity() {
        assertEquals(
                IrisMetalPostChain.Stage.FINAL,
                new IrisMetalPostChain.PassInfo(
                        IrisMetalPostChain.Stage.FINAL,
                        "final",
                        new int[]{0},
                        new BitSet(),
                        bits(0),
                        new BitSet()
                ).stage()
        );
        assertEquals(
                net.irisshaders.iris.shaderpack.texture.TextureStage.COMPOSITE_AND_FINAL,
                IrisMetalPostChain.Stage.FINAL.textureStage
        );
    }

    @Test
    void transitionRejectsOutOfGenerationTargets() {
        assertThrows(IllegalArgumentException.class, () -> IrisMetalPostChain.transition(
                new BitSet(), new BitSet(), new int[]{4}, Map.of(), 4
        ));
        assertThrows(IllegalArgumentException.class, () -> IrisMetalPostChain.applyPreFlips(
                new BitSet(), Map.of(-1, true), 4
        ));
    }

    @Test
    void customColortexOverrideDeactivatesAfterFirstStageWriteIncludingLegacyAlias() {
        IrisMetalPostChain.PassInfo beforeWrite = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.COMPOSITE,
                "composite",
                new int[]{7},
                bits(7),
                bits(7),
                new BitSet()
        );
        IrisMetalPostChain.PassInfo afterWrite = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.COMPOSITE,
                "composite1",
                new int[]{0},
                new BitSet(),
                bits(0),
                bits(0, 7)
        );

        assertTrue(beforeWrite.allowsCustomTextureOverride("colortex7"));
        assertTrue(beforeWrite.allowsCustomTextureOverride("gaux4"));
        assertFalse(afterWrite.allowsCustomTextureOverride("colortex7"));
        assertFalse(afterWrite.allowsCustomTextureOverride("gaux4"));
        assertFalse(afterWrite.allowsCustomTextureOverride("colortex0"));
        assertFalse(afterWrite.allowsCustomTextureOverride("gcolor"));
        assertTrue(afterWrite.allowsCustomTextureOverride("noisetex"));
    }

    @Test
    void stagePreFlipDoesNotDeactivateCustomOverride() {
        IrisMetalPostChain.PassInfo preFlipped = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.DEFERRED,
                "deferred",
                new int[]{1},
                bits(7),
                bits(1, 7),
                new BitSet()
        );

        assertTrue(preFlipped.allowsCustomTextureOverride("colortex7"));
        assertTrue(preFlipped.allowsCustomTextureOverride("gaux4"));
    }

    @Test
    void legacyRenderTargetSamplersUseIrisColortexOrdering() {
        String[] legacy = {
                "gcolor", "gdepth", "gnormal", "composite",
                "gaux1", "gaux2", "gaux3", "gaux4"
        };
        for (int target = 0; target < legacy.length; target++) {
            assertEquals(target, IrisMetalPostChain.renderTargetIndex(legacy[target]));
            assertEquals(target, IrisMetalPostChain.renderTargetIndex("colortex" + target));
        }
        assertEquals(-1, IrisMetalPipelineOverrides.Instance.gbufferRenderTargetIndex("gcolor"));
        assertEquals(-1, IrisMetalPipelineOverrides.Instance.gbufferRenderTargetIndex("colortex3"));
        assertEquals(4, IrisMetalPipelineOverrides.Instance.gbufferRenderTargetIndex("gaux1"));
        assertEquals(5, IrisMetalPipelineOverrides.Instance.gbufferRenderTargetIndex("colortex5"));
        assertEquals(-1, IrisMetalPostChain.renderTargetIndex("noisetex"));
    }

    @Test
    void fragmentOutputAbiKeepsPackVec3StateAndExportsRgbaAtEveryMainExit() {
        String source = """
                #version 450
                layout(location = 0) out vec3 sceneColor;
                layout(location = 1) flat out uvec2 material;
                void main() {
                    sceneColor = vec3(0.25);
                    if (sceneColor.x < 0.0) return;
                    material = uvec2(7u);
                }
                """;

        String widened = IrisMetalPostChain.widenFragmentOutputsForMetal(source);

        assertTrue(widened.contains("vec3 sceneColor;"));
        assertTrue(widened.contains("layout(location = 0) out vec4 metallum_FragColor_sceneColor;"));
        assertTrue(widened.contains("uvec2 material;"));
        assertTrue(widened.contains("layout(location = 1) out uvec4 metallum_FragColor_material;"));
        assertEquals(2, occurrences(widened, "metallum_FragColor_sceneColor = vec4(sceneColor, 1);"));
        assertEquals(2, occurrences(widened, "metallum_FragColor_material = uvec4(material, 0u, 1u);"));
    }

    @Test
    void resourceProviderReceivesTheDeclaredSamplerType() {
        IrisMetalPostChain.PassInfo pass = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.COMPOSITE,
                "composite",
                new int[]{0},
                new BitSet(),
                bits(0),
                new BitSet()
        );
        MetalIrisShaderCompiler.SamplerDecl comparison =
                new MetalIrisShaderCompiler.SamplerDecl("shadowtex0", "sampler2DShadow");
        AtomicReference<MetalIrisShaderCompiler.SamplerDecl> observed = new AtomicReference<>();
        IrisMetalPostChain.ResourceProvider provider = new IrisMetalPostChain.ResourceProvider() {
            @Override
            public com.mojang.blaze3d.buffers.GpuBufferSlice uniform(
                    final IrisMetalPostChain.PassInfo ignoredPass,
                    final String ignoredBlockName
            ) {
                return null;
            }

            @Override
            public IrisMetalPostChain.TextureBinding texture(
                    final IrisMetalPostChain.PassInfo ignoredPass,
                    final String ignoredSamplerName
            ) {
                throw new AssertionError("type-aware lookup must not discard the sampler declaration");
            }

            @Override
            public IrisMetalPostChain.TextureBinding texture(
                    final IrisMetalPostChain.PassInfo observedPass,
                    final MetalIrisShaderCompiler.SamplerDecl sampler
            ) {
                assertEquals(pass, observedPass);
                observed.set(sampler);
                return null;
            }
        };

        IrisMetalPostChain.externalTexture(provider, pass, comparison);

        assertEquals(comparison, observed.get());
    }

    @Test
    void resourceProviderCarriesTypedTexelBufferFormat() {
        IrisMetalPostChain.PassInfo pass = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.COMPOSITE,
                "composite",
                new int[]{0},
                new BitSet(),
                bits(0),
                new BitSet()
        );
        MetalIrisShaderCompiler.SamplerDecl sampler =
                new MetalIrisShaderCompiler.SamplerDecl("sampleBuffer", "samplerBuffer");
        GpuBufferSlice slice = new GpuBufferSlice(null, 16L, 64L);
        IrisMetalPostChain.TexelBufferBinding binding =
                new IrisMetalPostChain.TexelBufferBinding(slice, com.mojang.blaze3d.GpuFormat.R32_FLOAT);

        IrisMetalPostChain.ResourceProvider provider = new IrisMetalPostChain.ResourceProvider() {
            @Override
            public com.mojang.blaze3d.buffers.GpuBufferSlice uniform(
                    final IrisMetalPostChain.PassInfo ignoredPass,
                    final String ignoredBlockName
            ) {
                return null;
            }

            @Override
            public IrisMetalPostChain.TextureBinding texture(
                    final IrisMetalPostChain.PassInfo ignoredPass,
                    final String ignoredSamplerName
            ) {
                return null;
            }

            @Override
            public IrisMetalPostChain.TexelBufferBinding texelBuffer(
                    final IrisMetalPostChain.PassInfo observedPass,
                    final MetalIrisShaderCompiler.SamplerDecl observedSampler
            ) {
                assertEquals(pass, observedPass);
                assertEquals(sampler, observedSampler);
                return binding;
            }
        };

        assertEquals(binding, provider.texelBuffer(pass, sampler));
        assertEquals(com.mojang.blaze3d.GpuFormat.R32_FLOAT, binding.format());
        assertEquals(64L, binding.slice().length());
    }

    @Test
    void passIdentityCarriesTheFrozenSamplerDeclarations() {
        IrisMetalPostChain.PassInfo pass = new IrisMetalPostChain.PassInfo(
                IrisMetalPostChain.Stage.COMPOSITE,
                "composite",
                new int[]{0},
                new BitSet(),
                bits(0),
                new BitSet(),
                Set.of("shadow", "watershadow", "shadowtex0")
        );

        assertTrue(pass.declaresSampler("watershadow"));
        assertTrue(pass.declaresSampler("shadow"));
        assertFalse(pass.declaresSampler("shadowtex1"));
    }

    @Test
    void shadowSamplerSelectionPreservesTheDeclaredGlslType() {
        MetalIrisShaderCompiler.SamplerDecl regular =
                new MetalIrisShaderCompiler.SamplerDecl("shadowtex0", "sampler2D");
        MetalIrisShaderCompiler.SamplerDecl comparison =
                new MetalIrisShaderCompiler.SamplerDecl("shadowtex0", "sampler2DShadow");

        assertFalse(IrisMetalShadowPipeline.isComparisonSampler(regular));
        assertTrue(IrisMetalShadowPipeline.isComparisonSampler(comparison));
        assertTrue(IrisMetalShadowPipeline.isShadowSamplerName("shadow"));
        assertTrue(IrisMetalShadowPipeline.isShadowSamplerName("watershadow"));
        assertTrue(IrisMetalShadowPipeline.isShadowSamplerName("shadowcolor7"));
        assertFalse(IrisMetalShadowPipeline.isShadowSamplerName("shadowcolorimg0"));
        assertFalse(IrisMetalShadowPipeline.isShadowSamplerName("noisetex"));
    }

    private static BitSet bits(final int... targets) {
        BitSet result = new BitSet();
        for (int target : targets) {
            result.set(target);
        }
        return result;
    }

    private static int occurrences(final String source, final String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
