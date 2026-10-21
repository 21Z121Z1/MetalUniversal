package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

/** Test-only descriptors shared by the early JVM tests and the actual client harness. */
final class MetalShaderCompilerFixtures {
    static final Identifier RELOAD_SHADER=id("reload_include_lease");
    static final Identifier CALLBACK_SHADER=id("callback_lifetime");
    static final Identifier ENTITY=Identifier.fromNamespaceAndPath("minecraft","core/entity");

    static RenderPipeline reloadIncludeLease() {
        return base(RELOAD_SHADER,RELOAD_SHADER,RELOAD_SHADER).build();
    }
    static RenderPipeline nativeIncludeCallback(int variant) {
        return base(id("callback_lifetime/variant_"+variant),CALLBACK_SHADER,CALLBACK_SHADER)
                .withShaderDefine("CALLBACK_VARIANT",variant).build();
    }
    static RenderPipeline canonicalRawLibrary() {
        return base(id("source_contract/raw_source_library"),ENTITY,ENTITY)
                .withShaderDefine("NO_OVERLAY").withShaderDefine("EMISSIVE").build();
    }
    private static RenderPipeline.Builder base(Identifier location,Identifier vertex,Identifier fragment) {
        return RenderPipeline.builder().withLocation(location).withVertexShader(vertex).withFragmentShader(fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withColorTargetState(ColorTargetState.DEFAULT).withCull(false);
    }
    private static Identifier id(String path) {return Identifier.fromNamespaceAndPath("metallum",path);}
    private MetalShaderCompilerFixtures() {}
}
