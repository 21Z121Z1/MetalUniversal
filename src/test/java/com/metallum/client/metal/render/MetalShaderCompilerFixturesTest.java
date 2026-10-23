package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.ArrayList;
import net.minecraft.IdentifierException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Constructs the exact shared descriptors before any Metal device or client is required. */
final class MetalShaderCompilerFixturesTest {
    @Test void compilerFixturesHaveTypedResourceIdsAndCompleteRasterDescriptors() {
        var fixtures=new ArrayList<RenderPipeline>();
        fixtures.add(MetalShaderCompilerFixtures.reloadIncludeLease());
        fixtures.add(MetalShaderCompilerFixtures.canonicalRawLibrary());
        for(int i=0;i<8;i++)fixtures.add(MetalShaderCompilerFixtures.nativeIncludeCallback(i));
        for(var pipeline:fixtures) {
            assertEquals("metallum",pipeline.getLocation().getNamespace());
            assertEquals(PrimitiveTopology.TRIANGLES,pipeline.getPrimitiveTopology());
            assertEquals(java.util.List.of(ColorTargetState.DEFAULT),pipeline.getColorTargetStates());
            assertEquals(java.util.Set.of(ShaderType.VERTEX,ShaderType.FRAGMENT),pipeline.getShaders().keySet());
            assertFalse(pipeline.getShaders().values().stream().anyMatch(id->id.getPath().contains(":")));
        }
        var reload=fixtures.getFirst();
        assertEquals(MetalShaderCompilerFixtures.RELOAD_SHADER,reload.getShaders().get(ShaderType.VERTEX));
        assertEquals(MetalShaderCompilerFixtures.RELOAD_SHADER,reload.getShaders().get(ShaderType.FRAGMENT));
        var original=fixtures.get(1);
        assertEquals(MetalShaderCompilerFixtures.ENTITY,original.getShaders().get(ShaderType.VERTEX));
        assertTrue(original.getShaderDefines().flags().containsAll(java.util.Set.of("NO_OVERLAY","EMISSIVE")));
    }

    @Test void namespaceStringsAreRejectedByBothDefaultNamespaceShaderOverloads() {
        assertThrows(IdentifierException.class,()->RenderPipeline.builder().withVertexShader("metallum:reload_include_lease"));
        assertThrows(IdentifierException.class,()->RenderPipeline.builder().withFragmentShader("metallum:reload_include_lease"));
    }
}
