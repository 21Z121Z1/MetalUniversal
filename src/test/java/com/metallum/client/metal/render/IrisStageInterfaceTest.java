package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrisStageInterfaceTest {
    private static final String VERTEX = """
            #version 450
            in vec3 Position;
            out mat3 basis;
            out vec2 uv;
            void main() { gl_Position = vec4(Position, 1); basis = mat3(1); uv = Position.xy; }
            """;
    private static final String FRAGMENT = """
            #version 450
            in vec2 uv;
            in mat3 basis;
            out vec4 color;
            void main() { color = vec4(uv, basis[0].x, 1); }
            """;

    @Test
    void reorderedMatrixInterfaceCompilesWithoutAutomaticLocations() {
        var linked = MetalIrisShaderCompiler.linkPatchedPair("reordered", VERTEX, FRAGMENT, new int[]{0});
        Map<String, Integer> outputs = locations(linked.vertexGlsl(), ShaderType.VERTEX, false);
        Map<String, Integer> inputs = locations(linked.fragmentGlsl(), ShaderType.FRAGMENT, true);
        assertEquals(outputs, inputs);
        assertEquals(0, outputs.get("basis"));
        assertEquals(3, outputs.get("uv"));
        assertEquals(0, locations(linked.vertexGlsl(), ShaderType.VERTEX, true).get("Position"));
        assertEquals(0, locations(linked.fragmentGlsl(), ShaderType.FRAGMENT, false).get("color"));
    }

    @Test
    void explicitRangesAndArraysRemainIntact() {
        String vertex = VERTEX.replace("out mat3 basis;", "layout(location = 4) out mat3 basis;")
                .replace("out vec2 uv;", "out vec2 uv[2];").replace("uv = Position.xy", "uv[0] = Position.xy; uv[1] = Position.xy");
        String fragment = FRAGMENT.replace("in vec2 uv;", "in vec2 uv[2];").replace("vec4(uv,", "vec4(uv[0] + uv[1],");
        var linked = MetalIrisShaderCompiler.linkPatchedPair("ranges", vertex, fragment, new int[]{0});
        Map<String, Integer> outputs = locations(linked.vertexGlsl(), ShaderType.VERTEX, false);
        assertEquals(outputs, locations(linked.fragmentGlsl(), ShaderType.FRAGMENT, true));
        assertEquals(4, outputs.get("basis"));
        assertEquals(0, outputs.get("uv"));
    }

    @Test
    void explicitLocationCanLinkDifferentNames() {
        String vertex = VERTEX.replace("out vec2 uv;", "layout(location = 8) out vec2 uv;");
        String fragment = FRAGMENT.replace("in vec2 uv;", "layout(location = 8) in vec2 other;").replace("vec4(uv,", "vec4(other,");
        var linked = MetalIrisShaderCompiler.linkPatchedPair("explicit", vertex, fragment, new int[]{0});
        assertEquals(8, locations(linked.vertexGlsl(), ShaderType.VERTEX, false).get("uv"));
        assertEquals(8, locations(linked.fragmentGlsl(), ShaderType.FRAGMENT, true).get("other"));
    }

    @Test
    void incompatibleAndMissingProducersFailBeforePipelineActivation() {
        assertThrows(MetalIrisShaderCompiler.TranslationException.class, () ->
                MetalIrisShaderCompiler.linkPatchedPair("missing", VERTEX,
                        FRAGMENT.replace("uv", "absent"), new int[]{0}));
        assertThrows(MetalIrisShaderCompiler.TranslationException.class, () ->
                MetalIrisShaderCompiler.linkPatchedPair("mismatch", VERTEX,
                        FRAGMENT.replace("mat3 basis", "mat4 basis"), new int[]{0}));
    }

    @Test
    void conflictingExplicitLocationsFailClosed() {
        assertThrows(MetalIrisShaderCompiler.TranslationException.class, () ->
                MetalIrisShaderCompiler.linkPatchedPair("conflict",
                        VERTEX.replace("out vec2 uv;", "layout(location = 8) out vec2 uv;"),
                        FRAGMENT.replace("in vec2 uv;", "layout(location = 9) in vec2 uv;"), new int[]{0}));
    }

    private static Map<String, Integer> locations(String source, ShaderType stage, boolean inputs) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        assertNotEquals(0L, compiler);
        assertNotEquals(0L, options);
        try {
            Shaderc.shaderc_compile_options_set_target_env(options,
                    Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            // The independent compiler must reject any missing interface locations.
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, false);
            int kind = stage == ShaderType.VERTEX ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "interface-test", "main", options);
            try {
                assertEquals(Shaderc.shaderc_compilation_status_success,
                        Shaderc.shaderc_result_get_compilation_status(result), Shaderc.shaderc_result_get_error_message(result));
                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                assertNotNull(bytes);
                ByteBuffer owned = MemoryUtil.memAlloc(bytes.remaining());
                owned.put(bytes.duplicate()).flip();
                try (var module = new SPIRVModule(owned, stage)) {
                    Map<String, Integer> locations = new HashMap<>();
                    for (var variable : inputs ? module.reflect().inputs() : module.reflect().outputs()) {
                        locations.put(variable.name(), variable.location());
                    }
                    return locations;
                } catch (Exception exception) {
                    throw new AssertionError("SPIR-V reflection failed for " + stage, exception);
                }
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
