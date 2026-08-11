package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.UniformType;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

/** Verifies the fixed-Iris core draw catalog without constructing a renderer. */
final class IrisMetalCoreGbufferPipelinesTest {
    @Test
    void routesFixedCoreFamiliesToTheExpectedShaderKeys() {
        assertEquals(
                ShaderKey.TERRAIN_SOLID,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.SOLID_TERRAIN,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false)
                )
        );
        assertEquals(
                ShaderKey.HAND_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_SOLID,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, true, true, false)
                )
        );
        assertEquals(
                ShaderKey.BLOCK_ENTITY_DIFFUSE,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_CUTOUT,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, true)
                )
        );
        assertEquals(
                ShaderKey.SHADOW_ENTITIES_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_CUTOUT,
                        new IrisMetalCoreGbufferPipelines.RenderState(true, false, false, false)
                )
        );
        assertEquals(
                ShaderKey.SHADOW_ENTITIES_CUTOUT,
                IrisMetalCoreGbufferPipelines.resolve(
                        RenderPipelines.ENTITY_SHADOW,
                        new IrisMetalCoreGbufferPipelines.RenderState(true, false, false, false)
                )
        );
    }

    @Test
    void catalogHasBothMainAndShadowRoutesAndFailsClosedForUnknownPipelines() {
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelineCount(false) > 0);
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelineCount(true) > 0);
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(false).contains(RenderPipelines.SKY));
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(false).contains(RenderPipelines.LINES_DEPTH_BIAS));
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(false)
                .contains(RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET));
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(true).contains(RenderPipelines.ENTITY_CUTOUT));
        assertTrue(IrisMetalCoreGbufferPipelines.mappedPipelines(true)
                .contains(RenderPipelines.TEXT_GRAYSCALE_POLYGON_OFFSET));

        RenderPipeline unknown = RenderPipeline.builder()
                .withLocation("minecraft/test_unmapped_iris_pipeline")
                .withVertexShader("metallum_test/unmapped_vertex")
                .withFragmentShader("metallum_test/unmapped_fragment")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .build();
        assertNull(
                IrisMetalCoreGbufferPipelines.resolve(
                        unknown,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false)
                )
        );
    }

    @Test
    void fixedMinecraftStaticCatalogHasNoUnclassifiedPipeline() {
        assertTrue(
                IrisMetalCoreGbufferPipelines.unclassifiedStaticPipelines().isEmpty(),
                () -> "Unclassified fixed Minecraft pipelines: "
                        + IrisMetalCoreGbufferPipelines.unclassifiedStaticPipelines()
        );
        assertEquals(
                IrisMetalCoreGbufferPipelines.DrawOwnership.EXPLICIT_NON_OWNED,
                IrisMetalCoreGbufferPipelines.ownership(
                        RenderPipelines.GUI,
                        new IrisMetalCoreGbufferPipelines.RenderState(false, false, false, false)
                )
        );
    }

    @Test
    void catalogEnumeratesTheFixedIrisShaderFamilies() {
        Set<ShaderKey> required = IrisMetalCoreGbufferPipelines.requiredShaderKeys();
        assertTrue(required.containsAll(Set.of(
                ShaderKey.BASIC,
                ShaderKey.BE_TRANSLUCENT,
                ShaderKey.BLOCK_ENTITY,
                ShaderKey.BLOCK_ENTITY_DIFFUSE,
                ShaderKey.CLOUDS,
                ShaderKey.CRUMBLING,
                ShaderKey.ENTITIES_CUTOUT,
                ShaderKey.ENTITIES_CUTOUT_DIFFUSE,
                ShaderKey.ENTITIES_EYES,
                ShaderKey.ENTITIES_EYES_TRANS,
                ShaderKey.ENTITIES_SOLID,
                ShaderKey.ENTITIES_TRANSLUCENT,
                ShaderKey.GLINT,
                ShaderKey.HAND_CUTOUT,
                ShaderKey.HAND_CUTOUT_DIFFUSE,
                ShaderKey.HAND_TEXT,
                ShaderKey.HAND_TEXT_TRANSLUCENT,
                ShaderKey.HAND_TRANSLUCENT,
                ShaderKey.HAND_WATER_DIFFUSE,
                ShaderKey.LEASH,
                ShaderKey.LIGHTNING,
                ShaderKey.LINES,
                ShaderKey.MOVING_BLOCK,
                ShaderKey.PARTICLES,
                ShaderKey.PARTICLES_TRANS,
                ShaderKey.SKY_BASIC,
                ShaderKey.SKY_BASIC_COLOR,
                ShaderKey.SKY_TEXTURED,
                ShaderKey.TERRAIN_CUTOUT,
                ShaderKey.TERRAIN_SOLID,
                ShaderKey.TERRAIN_TRANSLUCENT,
                ShaderKey.TEXT,
                ShaderKey.TEXT_BE,
                ShaderKey.TEXT_BG,
                ShaderKey.TEXT_INTENSITY,
                ShaderKey.TEXT_INTENSITY_BE,
                ShaderKey.WEATHER,
                ShaderKey.SHADOW_BASIC,
                ShaderKey.SHADOW_BEACON_BEAM,
                ShaderKey.SHADOW_BLOCK,
                ShaderKey.SHADOW_ENTITIES_CUTOUT,
                ShaderKey.SHADOW_LEASH,
                ShaderKey.SHADOW_LIGHTNING,
                ShaderKey.SHADOW_LINES,
                ShaderKey.SHADOW_PARTICLES,
                ShaderKey.SHADOW_TERRAIN_CUTOUT,
                ShaderKey.SHADOW_TEXT,
                ShaderKey.SHADOW_TEXT_BG,
                ShaderKey.SHADOW_TEXT_INTENSITY,
                ShaderKey.SHADOW_TEX,
                ShaderKey.SHADOW_TRANSLUCENT
        )));
        required.forEach(key -> assertNotNull(key.getProgram(), key::getName));
    }

    @Test
    void shadowDisabledAdmissionCanUseTheMainCatalogOnly() {
        Set<ShaderKey> mainOnly = IrisMetalCoreGbufferPipelines.requiredShaderKeys(false);

        assertTrue(mainOnly.contains(ShaderKey.TERRAIN_SOLID));
        assertTrue(mainOnly.contains(ShaderKey.TEXT));
        assertTrue(mainOnly.stream().noneMatch(ShaderKey::isShadow));
    }

    @Test
    void minecraftCloudPipelineKeepsCloudFacesAsTheFixedTypedProvider() {
        BindGroupLayout.UniformDescription cloudFaces =
                BindGroupLayout.flattenUniforms(RenderPipelines.CLOUDS.getBindGroupLayouts()).stream()
                        .filter(uniform -> uniform.name().equals("CloudFaces"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Minecraft CLOUDS pipeline lost CloudFaces"));

        assertEquals(UniformType.TEXEL_BUFFER, cloudFaces.type());
        assertEquals(GpuFormat.R8_SINT, cloudFaces.gpuFormat());
        assertTrue(IrisMetalTexelBufferAbi.isFixedProvider(cloudFaces.name()));
    }

    @Test
    void proceduralCloudRoutesDoNotRequireAPhysicalVertexStream() {
        assertNull(RenderPipelines.CLOUDS.getVertexFormatBinding(0));
        assertNull(RenderPipelines.FLAT_CLOUDS.getVertexFormatBinding(0));
        assertTrue(IrisMetalCoreGbufferPipelines.allowsNoPhysicalVertexFormat(ShaderKey.CLOUDS));
        assertFalse(IrisMetalCoreGbufferPipelines.allowsNoPhysicalVertexFormat(ShaderKey.TERRAIN_SOLID));
    }

    @Test
    void fixedIrisCloudAlbedoUsesTheLevelTextureOnlyForCloudPrograms() {
        assertTrue(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("gtexture", ShaderKey.CLOUDS));
        assertTrue(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("texture", ShaderKey.CLOUDS_SODIUM));
        assertTrue(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("tex", ShaderKey.CLOUDS));
        assertFalse(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("gtexture", ShaderKey.TERRAIN_SOLID));
        assertFalse(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("lightmap", ShaderKey.CLOUDS));
        assertFalse(IrisMetalCoreDrawBridge.isCloudAlbedoSampler("gtexture", ShaderKey.SHADOW_TEX));
    }

    @Test
    void restoresMojangNamesForIrisPrefixedBuiltInUniformBlocks() {
        String remapped = IrisMetalGlslLinker.remapVanillaBuiltInUniformBlocks("""
                layout(std140) uniform iris_DynamicTransforms { mat4 ModelViewMat; };
                layout(std140) uniform iris_Projection { mat4 Projection; };
                layout(std140) uniform iris_Fog { vec4 FogColor; };
                layout(std140) uniform iris_Globals { vec4 Globals; };
                layout(std140) uniform iris_CloudInfo { vec4 CloudInfo; };
                """);

        assertTrue(remapped.contains("uniform DynamicTransforms {"));
        assertTrue(remapped.contains("uniform Projection {"));
        assertTrue(remapped.contains("uniform " + IrisMetalGlslLinker.IRIS_FOG_BLOCK_NAME + " {"));
        assertTrue(remapped.contains("uniform Globals {"));
        assertTrue(remapped.contains("uniform CloudInfo {"));
    }
}
