package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Compiles packaged GLSL and real Minecraft includes, without a Metal device or Iris. */
final class MetalMotionShaderCompilationTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/metallum/shaders/core");
    private static final Pattern INCLUDE = Pattern.compile(
            "(?m)^\\s*#(?:include|moj_import)\\s+<([a-z0-9_./:-]+)>\\s*$");
    private static final String SEPARATE = "#extension GL_ARB_separate_shader_objects : require\n";

    static Stream<Arguments> packagedVariants() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        List<Path> paths;
        try (var files = Files.list(SHADERS)) {
            paths = files.filter(path -> path.getFileName().toString().contains("motion."))
                    .sorted().toList();
        }
        assertEquals(16, paths.size(), "Review the shader matrix when adding/removing a motion shader");
        for (Path path : paths) {
            String name = path.getFileName().toString();
            List<List<String>> variants = List.of(List.of());
            if (name.startsWith("text_previous")) {
                variants = new ArrayList<>();
                for (int flags = 0; flags < 8; flags++) {
                    List<String> defines = new ArrayList<>();
                    if ((flags & 1) != 0) defines.add("IS_GUI");
                    if ((flags & 2) != 0) defines.add("IS_SEE_THROUGH");
                    if ((flags & 4) != 0) defines.add("IS_GRAYSCALE");
                    variants.add(defines);
                }
            } else if (name.startsWith("text_background")) {
                variants = List.of(List.of(), List.of("IS_SEE_THROUGH"));
            } else if (name.startsWith("block_") || name.startsWith("entity_")) {
                variants = List.of(List.of(), List.of("ALPHA_CUTOUT=0.5"));
            }
            for (List<String> defines : variants) cases.add(Arguments.of(name, defines));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("packagedVariants")
    void compilesActualPackagedShaderAtItsDeclaredVersion(String name, List<String> defines) throws IOException {
        String resource = "assets/metallum/shaders/core/" + name;
        String packaged = readResource(resource);
        assertEquals(Files.readString(SHADERS.resolve(name)), packaged,
                "The compiler must test the same shader that the production JAR packages");
        assertTrue(packaged.startsWith("#version 330\n" + SEPARATE));
        String source = expandIncludes(resource, new HashSet<>());
        int stage = name.endsWith(".vsh") ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
        Compilation result = compile(source, stage, name, defines);
        assertEquals(Shaderc.shaderc_compilation_status_success, result.status(), result.message());
    }

    @Test
    void independentCompilerRejectsExplicitStageLocationsWithoutRequiredExtension() {
        String source = """
                #version 330
                layout(location = 0) out vec2 motion;
                void main() { motion = vec2(0); gl_Position = vec4(0, 0, 0, 1); }
                """;
        assertNotEquals(Shaderc.shaderc_compilation_status_success,
                compile(source, Shaderc.shaderc_vertex_shader, "missing-extension", List.of()).status());
        Compilation enabled = compile(source.replace("#version 330\n", "#version 330\n" + SEPARATE),
                Shaderc.shaderc_vertex_shader, "declared-extension", List.of());
        assertEquals(Shaderc.shaderc_compilation_status_success, enabled.status(), enabled.message());
    }

    // Only expand imports. Do not invent vanilla uniforms, change versions or auto-map locations.
    // Conditional source is left intact for the real GLSL preprocessor.
    private static String expandIncludes(String resource, Set<String> active) throws IOException {
        if (!active.add(resource)) throw new IOException("Recursive shader import: " + resource);
        try {
            var matcher = INCLUDE.matcher(readResource(resource));
            StringBuilder expanded = new StringBuilder();
            while (matcher.find()) {
                String[] id = matcher.group(1).split(":", 2);
                String namespace = id.length == 2 ? id[0] : "minecraft";
                String path = id.length == 2 ? id[1] : id[0];
                if (path.contains("..")) throw new IOException("Unexpected shader import: " + path);
                String included = expandIncludes("assets/" + namespace + "/shaders/include/" + path, active);
                matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(included + "\n"));
            }
            matcher.appendTail(expanded);
            return expanded.toString();
        } finally {
            active.remove(resource);
        }
    }

    private static String readResource(String path) throws IOException {
        try (var stream = MetalMotionShaderCompilationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Actual shader resource missing: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Compilation(int status, String message) {}

    private static Compilation compile(String source, int stage, String name, List<String> defines) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        assertNotEquals(0L, compiler);
        try {
            long options = Shaderc.shaderc_compile_options_initialize();
            assertNotEquals(0L, options);
            try {
                Shaderc.shaderc_compile_options_set_target_env(options,
                        Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                Shaderc.shaderc_compile_options_set_auto_map_locations(options, false);
                for (String define : defines) {
                    String[] parts = define.split("=", 2);
                    Shaderc.shaderc_compile_options_add_macro_definition(options, parts[0],
                            parts.length == 2 ? parts[1] : "1");
                }
                long result = Shaderc.shaderc_compile_into_spv(compiler, source, stage, name, "main", options);
                assertNotEquals(0L, result);
                try {
                    return new Compilation(Shaderc.shaderc_result_get_compilation_status(result),
                            Shaderc.shaderc_result_get_error_message(result));
                } finally {
                    Shaderc.shaderc_result_release(result);
                }
            } finally {
                Shaderc.shaderc_compile_options_release(options);
            }
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
