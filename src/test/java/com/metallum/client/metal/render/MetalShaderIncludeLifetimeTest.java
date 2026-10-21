package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import static org.junit.jupiter.api.Assertions.*;

/** Real pinned shaderc callbacks, without a Metal device. This does not certify runtime Configs. */
final class MetalShaderIncludeLifetimeTest {
    static void verifyRepeatedCompilerCallbacksWithInitializedDevice() throws Exception {
        var id=Identifier.fromNamespaceAndPath("metallum","callback_lifetime.glsl");
        String text="""
                #ifndef CALLBACK_LIFETIME_BODY
                #define CALLBACK_LIFETIME_BODY
                vec4 callbackPosition(vec3 p) { return vec4(p, 1.0); }
                #endif
                """;
        String glsl="""
                #version 450
                #include <metallum:callback_lifetime.glsl>
                #include <metallum:callback_lifetime.glsl>
                layout(location=0) in vec3 Position;
                void main() { gl_Position=callbackPosition(Position); }
                """;
        var include=ShaderSource.CachedIncludeSource.create(id,text);
        var expected=fingerprint(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        int[] callbacks={0};var observations=new java.util.ArrayList<Map<String,Object>>();
        ShaderSource source=new ShaderSource() {
            @Override public String getShader(Identifier requested,ShaderType stage) {return glsl;}
            @Override public CachedIncludeSource getInclude(Identifier requested) {
                assertEquals(id,requested);callbacks[0]++;
                assertEquals(expected,fingerprint(MetalShaderSourceAdapters.includeContent(include)));
                return include;
            }
            @Override public void close() {}
        };
        try(var compiler=new GlslCompiler(true,true)) {
            for(int i=0;i<8;i++) {
                var defines=RenderPipeline.builder().withLocation("callback_lifetime/"+i)
                        .withVertexShader(id).withFragmentShader(id)
                        .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES)
                        .withColorTargetState(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT)
                        .withShaderDefine("CALLBACK_VARIANT",i).build().getShaderDefines();
                var before=fingerprint(MetalShaderSourceAdapters.includeContent(include));
                try(var module=compiler.compileToSpv(id.toString(),glsl,ShaderType.VERTEX,defines,source)) {
                    assertNotNull(module);
                }
                var after=fingerprint(MetalShaderSourceAdapters.includeContent(include));
                observations.add(Map.of("iteration",i,"before",before,"after",after,"callbacks",callbacks[0]));
                assertEquals(expected,before);assertEquals(expected,after,
                        "A live cached include must not change when shaderc returns");
            }
            assertTrue(callbacks[0]>=8,"The compiler must actually invoke the native include resolver");
        } finally {include.close();}
        var report=new LinkedHashMap<String,Object>();
        report.put("sourceCommit",System.getenv().getOrDefault("METALLUM_SOURCE_SHA","local-unrecorded"));
        report.put("observations",observations);report.put("callbackCount",callbacks[0]);
        report.put("sourceAdmissionGranted",false);
        report.put("boundary","Synthetic native include callback lifetime only. Runtime Configs/reload concurrency and actual shader semantics remain unproven.");
        System.out.println("METAL_NATIVE_INCLUDE_CALLBACK_CONTROL "+new com.google.gson.Gson().toJson(report));
    }

    @Test void declaredNativeLengthExcludesAnAllocatedNonzeroTailWithoutScanningForNul() {
        var result=ShadercIncludeResult.calloc();
        result.source_name(org.lwjgl.system.MemoryUtil.memUTF8("metallum:bounded_include",false));
        result.content(org.lwjgl.system.MemoryUtil.memUTF8("headNOT_PART_OF_INCLUDE\0",false));
        ShadercIncludeResult.ncontent_length(result.address(),4);
        var include=new ShaderSource.CachedIncludeSource(result.address());
        try {
            // The old API's scan remains inside this deliberately larger test allocation.
            assertEquals("headNOT_PART_OF_INCLUDE".length(),result.content().remaining(),
                    "Pin the actual dependency's NUL-scanning getter; it ignores content_length");
            var bounded=MetalShaderSourceAdapters.includeContent(include);assertEquals(4,bounded.remaining());
            assertEquals("head",StandardCharsets.UTF_8.decode(bounded).toString());
            ShadercIncludeResult.ncontent_length(result.address(),0);
            assertEquals(0,MetalShaderSourceAdapters.includeContent(include).remaining());
            ShadercIncludeResult.ncontent_length(result.address(),1024L*1024+1);
            assertThrows(IllegalArgumentException.class,()->MetalShaderSourceAdapters.includeContent(include));
            ShadercIncludeResult.ncontent_length(result.address(),-1);
            assertThrows(IllegalArgumentException.class,()->MetalShaderSourceAdapters.includeContent(include));
        } finally {include.close();}
    }

    private static Map<String,Object> fingerprint(ByteBuffer input) {
        assertNotNull(input);var bytes=input.duplicate();int count=bytes.remaining();
        try {var digest=MessageDigest.getInstance("SHA-256");digest.update(bytes);
            return Map.of("bytes",count,"sha256",HexFormat.of().formatHex(digest.digest()));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
