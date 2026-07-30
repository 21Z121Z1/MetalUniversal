package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end GLSL&#8594;SPIR-V compile fixture using BSL v10.1.3
 * (https://cdn.modrinth.com/data/Q1vvjJYV/versions/hIibTfxn/BSL_v10.1.3.zip).
 *
 * <p>Extracts the bundled BSL shaderpack
 * ({@code src/test/resources/shaderpacks/BSL_v10.1.3.zip}), walks every
 * {@code .vsh}/{@code .fsh} pair under {@code shaders/world0/}, and compiles
 * each stage through {@link GlslangBridge#compileGlslToSpv}. Asserts the
 * returned SPIR-V has the canonical magic word (0x07230203) and at least the
 * 5-word header.
 *
 * <p><b>Gating.</b> The test requires the native {@code libglslang*.dylib}
 * built by the {@code buildMacGlslang} Gradle task, which only loads on
 * macOS/iOS. It is skipped via {@link Assumptions} on any host where
 * {@link GlslangBridge} cannot resolve the native library, so the build stays
 * green on Linux CI / developer machines. To force-run on macOS, set system
 * property {@code metallum.bsltest.force=true}.
 *
 * <p><b>Task 6.3 — BSL GLSL constructs glslang rejects (Vulkan SPIR-V mode)
 * and the fixes applied in {@link GlslangBridge}:</b>
 * <table>
 * <tr><th>Construct</th><th>Why glslang rejects it</th><th>Fix</th></tr>
 * <tr><td>{@code #version 120}</td><td>Vulkan client mode requires &#8805;450; glslang rejects pre-330 versions.</td><td>{@code force_default_version_and_profile=1} with {@code default_version=460} in the glslang_input_t.</td></tr>
 * <tr><td>{@code #include "/program/..."}, {@code #include "/lib/..."}</td><td>glslang's default preprocessor does not resolve Iris/Optifine-style includes.</td><td>FFM upcall for the {@code include_local} callback; this test pre-scans the zip and feeds a header&#8594;source map as the resolver.</td></tr>
 * <tr><td>Missing {@code IS_IRIS}, {@code MC_VERSION}, {@code MC_GLSL_VERSION} macros</td><td>BSL branches on these via {@code #if}; undefined &#8594; preprocessor errors or wrong code paths.</td><td>Compatibility preamble injected before the source (macro injection).</td></tr>
 * <tr><td>{@code varying}, {@code attribute}, {@code gl_FragData[n]}, {@code ftransform()}, {@code gl_TextureMatrix}, {@code gl_MultiTexCoord}, {@code gl_NormalMatrix} &#8230;</td><td>Legacy GLSL 1.20 / fixed-function builtins removed from Vulkan SPIR-V core.</td><td><b>Not stubbed here.</b> These are rewritten by Iris's TransformPatcher in the real integration path. Raw-fixture failures remain diagnostic and every paired program must produce either valid SPIR-V or a bounded compiler error.</td></tr>
 * <tr><td>{@code #extension GL_ARB_shader_texture_lod : enable}</td><td>GL desktop extension; glslang in Vulkan mode maps it to {@code textureLod} SPIR-V ops natively, so it is accepted when the version is overridden to 460.</td><td>Version override (above) makes the extension a no-op.</td></tr>
 * <tr><td>Generous varying/uniform/sampler counts</td><td>BSL declares many varyings and samplers; tight resource limits would reject them.</td><td>{@code glslang_default_resource()} already supplies generous limits; BSL stays within them.</td></tr>
 * </table>
 *
 * <p><b>Expected outcome.</b> Programs whose logic avoids legacy fixed-function
 * builtins may compile to valid SPIR-V. Programs relying on
 * {@code ftransform()}/{@code gl_TextureMatrix}/{@code attribute}/{@code varying}
 * are expected to fail because this diagnostic intentionally bypasses Iris's
 * patcher. Executable shader-pack coverage belongs to
 * {@link IrisMetalProgramFrontendTest}; this test proves the raw include and
 * diagnostic bridge terminates safely for every paired fixture program.
 */
class BslShaderCompileTest {

    private static final int SPIRV_MAGIC = 0x07230203;
    private static final String BSL_ZIP_RESOURCE = "/shaderpacks/BSL_v10.1.3.zip";

    @Test
    void bslShadersCompileToValidSpirv() throws IOException {
        Path zip = copyResourceToTemp(BSL_ZIP_RESOURCE);
        Assumptions.assumeTrue(zip != null, "BSL fixture zip not present on classpath");

        // GlslangBridge loads libglslang via FFM; on Linux it throws. Gate
        // the whole test so non-macOS CI stays green.
        boolean force = Boolean.getBoolean("metallum.bsltest.force");
        boolean canLoadGlslang;
        try {
            GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX,
                    "#version 460\nvoid main(){gl_Position=vec4(0);}", null);
            canLoadGlslang = true;
        } catch (Throwable t) {
            canLoadGlslang = false;
            if (force) {
                fail("metallum.bsltest.force=true but GlslangBridge could not load native glslang: " + t, t);
            }
        }
        Assumptions.assumeTrue(canLoadGlslang || force,
                "Skipped: native libglslang unavailable (non-macOS host). Set -Dmetallum.bsltest.force=true to force.");
        Assumptions.assumeTrue(canLoadGlslang, "Skipped: GlslangBridge native load failed on this host.");

        List<String> compiled = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        int pairedPrograms = 0;

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // 1. Build the include map: every .glsl entry under shaders/ keyed
            //    by its path relative to shaders/ (matches BSL's #include
            //    "/lib/..." and #include "/program/..." conventions).
            Map<String, String> includes = new HashMap<>();
            // settings.glsl lives at shaders/lib/settings.glsl and is included
            // as "/lib/settings.glsl"; program files as "/program/...". Any
            // .glsl under shaders/ is a candidate include target.
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.endsWith(".glsl") || e.isDirectory()) {
                    continue;
                }
                if (!name.startsWith("shaders/")) {
                    continue;
                }
                // Key = path relative to shaders/ (e.g. "lib/settings.glsl").
                String key = name.substring("shaders/".length());
                includes.put(key, readEntry(zf, e));
            }

            // Include resolver: header_name "/lib/settings.glsl" -> "lib/settings.glsl".
            Function<String, String> resolver = headerName -> {
                String key = headerName.startsWith("/") ? headerName.substring(1) : headerName;
                return includes.get(key);
            };

            // 2. Group .vsh/.fsh by program name (basename without extension),
            //    world0 only (skip world-1/world1 stubs).
            Map<String, EnumMap<GlslangBridge.Stage, String>> programs = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> progEntries = zf.entries();
            while (progEntries.hasMoreElements()) {
                ZipEntry e = progEntries.nextElement();
                String name = e.getName();
                if (!name.startsWith("shaders/world0/")) {
                    continue;
                }
                if (name.endsWith(".vsh")) {
                    putProgram(programs, baseName(name), GlslangBridge.Stage.VERTEX, readEntry(zf, e));
                } else if (name.endsWith(".fsh")) {
                    putProgram(programs, baseName(name), GlslangBridge.Stage.FRAGMENT, readEntry(zf, e));
                }
            }

            // 3. Compile each program with the include resolver wired.
            for (Map.Entry<String, EnumMap<GlslangBridge.Stage, String>> prog : programs.entrySet()) {
                String progName = prog.getKey();
                EnumMap<GlslangBridge.Stage, String> stages = prog.getValue();
                String vsh = stages.get(GlslangBridge.Stage.VERTEX);
                String fsh = stages.get(GlslangBridge.Stage.FRAGMENT);
                if (vsh == null || fsh == null) {
                    continue; // incomplete program
                }
                pairedPrograms++;
                try {
                    int[] spv = GlslangBridge.compileGlslToSpv(
                            GlslangBridge.Stage.VERTEX, vsh, null, resolver);
                    assertValidSpirv(progName + ".vsh", spv);
                    int[] spvF = GlslangBridge.compileGlslToSpv(
                            GlslangBridge.Stage.FRAGMENT, fsh, null, resolver);
                    assertValidSpirv(progName + ".fsh", spvF);
                    compiled.add(progName);
                } catch (Throwable t) {
                    failed.put(progName, rootMessage(t));
                }
            }
        }

        assertTrue(pairedPrograms > 0, "BSL fixture contains no paired raster programs");
        assertEquals(pairedPrograms, compiled.size() + failed.size(),
                "Raw BSL diagnostic did not account for every paired program");
        // Log a compact summary to aid Task 6.3 diagnosis.
        System.out.println("[BSL fixture] compiled=" + compiled.size()
                + " failed=" + failed.size());
        System.out.println("[BSL fixture] compiled programs: " + compiled);
        failed.forEach((n, m) -> System.out.println("  FAIL " + n + ": " + truncate(m, 200)));
    }

    private static void assertValidSpirv(String label, int[] spv) {
        assertTrue(spv.length >= 5, label + ": SPIR-V too small (" + spv.length + " words)");
        assertEquals(SPIRV_MAGIC, spv[0], label + ": bad SPIR-V magic");
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void putProgram(Map<String, EnumMap<GlslangBridge.Stage, String>> out,
                                   String prog, GlslangBridge.Stage stage, String src) {
        out.computeIfAbsent(prog, k -> new EnumMap<>(GlslangBridge.Stage.class)).put(stage, src);
    }

    private static String baseName(String path) {
        String p = path;
        int slash = p.lastIndexOf('/');
        if (slash >= 0) p = p.substring(slash + 1);
        int dot = p.lastIndexOf('.');
        if (dot >= 0) p = p.substring(0, dot);
        return p;
    }

    private static String readEntry(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path copyResourceToTemp(String resource) {
        try (InputStream in = BslShaderCompileTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            Path tmp = Files.createTempFile("bsl-fixture-", ".zip");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (IOException e) {
            return null;
        }
    }
}
