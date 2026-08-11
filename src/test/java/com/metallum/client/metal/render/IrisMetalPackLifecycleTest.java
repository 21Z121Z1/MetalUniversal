package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPackLifecycleTest {
    @Test
    void loadsOnlyWhenTheMetalPipelineAndShadersAreEnabled() {
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, false));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, true));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, false));
        assertTrue(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, true));
    }

    @Test
    void consumesOnlyTheLiveGenerationDisabledTransition() {
        IrisMetalPackLifecycle.onSemanticPipelineActivated();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));

        IrisMetalPackLifecycle.onSemanticPipelineDestroyed();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, true));
        assertTrue(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
    }

    @Test
    void rejectsStagesThatHaveNoExactMetalLowering() {
        UnsupportedOperationException geometry = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateProgramStages(
                        "gbuffer", "geometry-pack", "void main(){}", null, null
                )
        );
        assertTrue(geometry.getMessage().contains("geometry"));

        UnsupportedOperationException tessellation = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateProgramStages(
                        "gbuffer", "tess-pack", null, "void main(){}", null
                )
        );
        assertTrue(tessellation.getMessage().contains("tessellation"));
    }

    @Test
    void rejectsUnsupportedRenderTargetFormatsBeforeAllocation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalRenderTargetFormats.fromInternalName("PACK_SPECIFIC_FORMAT")
        );
    }

    @Test
    void admitsFixedTypedBuffersAndRejectsUnknownPackOwnedTexelBuffers() {
        IrisMetalPackAdmission.validateSamplerBuffers(
                "clouds",
                "clouds",
                "layout(binding = 3) uniform isamplerBuffer CloudFaces;"
        );
        IrisMetalPackAdmission.validateSamplerBuffers(
                "terrain",
                "terrain",
                "layout(binding = 4) uniform isamplerBuffer u_SectionTimeInfo;"
        );

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateSamplerBuffers(
                        "composite",
                        "composite0",
                        "layout(binding = 5) uniform samplerBuffer history;"
                )
        );
        assertTrue(failure.getMessage().contains("samplerBuffer 'history'"));
        assertTrue(failure.getMessage().contains("typed provider ABI"));
    }

    @Test
    void rejectsSeparateImageAndSamplerResourcesBeforeMetalBinding() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateSamplerBuffers(
                        "composite",
                        "composite0",
                        "layout(binding = 5) uniform texture2D history;"
                )
        );
        assertTrue(failure.getMessage().contains("separate image/sampler"));

        assertThrows(
                ShaderCompileException.class,
                () -> MetalCrossShaderCompiler.buildShaderpackBindGroupEntries(
                        new MetalCrossShaderCompiler.ShaderpackReflection(
                                List.of(), List.of(), List.of("history"), List.of()
                        ),
                        new MetalCrossShaderCompiler.ShaderpackReflection(
                                List.of(), List.of(), List.of(), List.of()
                        )
                )
        );
    }

    @Test
    void shaderpackBindingsUseCompactDeterministicMetalIndices() throws Exception {
        List<VulkanBindGroupLayout.Entry> entries =
                MetalCrossShaderCompiler.buildShaderpackBindGroupEntries(
                        new MetalCrossShaderCompiler.ShaderpackReflection(
                                List.of("Globals"), List.of("colortex0"), List.of(), List.of()
                        ),
                        new MetalCrossShaderCompiler.ShaderpackReflection(
                                List.of("Globals"), List.of("colortex1"), List.of(), List.of()
                        )
                );
        Map<String, Integer> bindings = MetalCrossShaderCompiler.shaderpackResourceBindings(entries);
        assertEquals(0, bindings.get("Globals"));
        assertEquals(1, bindings.get("colortex0"));
        assertEquals(2, bindings.get("colortex1"));
        assertEquals(3, bindings.size());
    }

    @Test
    void rejectsComputeStorageBindingsWithoutADeclaredIrisBuffer() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateStorageBufferDeclarations(
                        "missing-ssbo",
                        "layout(std430, binding = 4) buffer Values { uint value; };",
                        Map.of()
                )
        );
        assertTrue(failure.getMessage().contains("SSBO binding 4"));

        assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateStorageBufferDeclarations(
                        "invalid-relative-ssbo",
                        "layout(std430, binding = 4) buffer Values { uint value; };",
                        Map.of(4, new BuiltShaderStorageInfo(0L, true, 0.0F, 1.0F, new byte[0]))
                )
        );
    }

    @Test
    void keepsShadowCompositeMipmapOwnershipOutOfMainColorTargets() {
        assertTrue(IrisMetalWorldResources.ownsMainMipmapTargets(ProgramArrayId.Composite));
        assertFalse(IrisMetalWorldResources.ownsMainMipmapTargets(ProgramArrayId.ShadowComposite));
    }

    @Test
    void admitsExactlyTheFixedIrisColorSpaceEnumWithBothOwnershipModes() {
        for (ColorSpace colorSpace : ColorSpace.values()) {
            assertDoesNotThrow(
                    () -> IrisMetalPackAdmission.requireColorSpaceSupported(colorSpace, false),
                    colorSpace::name
            );
            assertDoesNotThrow(
                    () -> IrisMetalPackAdmission.requireColorSpaceSupported(colorSpace, true),
                    () -> "pack-owned " + colorSpace.name()
            );
        }
    }

    @Test
    void rebuildsExecutionGraphWhenTargetsResizeOrDeviceChanges() {
        assertTrue(MetalWorldRenderingPipeline.requiresExecutionGraphRebuild(true, false));
        assertTrue(MetalWorldRenderingPipeline.requiresExecutionGraphRebuild(false, true));
        assertFalse(MetalWorldRenderingPipeline.requiresExecutionGraphRebuild(false, false));
    }
}
