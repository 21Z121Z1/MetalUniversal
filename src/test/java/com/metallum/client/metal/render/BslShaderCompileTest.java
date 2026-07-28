package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangBridge;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end GLSL&#8594;SPIR-V compile fixture using BSL v10.1.3
 * (https://cdn.modrinth.com/data/Q1vvjJYV/versions/hIibTfxn/BSL_v10.1.3.zip).
 *
 * <p>Extracts the bundled BSL shaderpack
 * ({@code src/test/resources/shaderpacks/BSL_v10.1.3.zip}), walks every
 * {@code .vsh}/{@code .fsh} pair under {@code shaders/} (world0), and compiles
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
 * <p>BSL's GLSL uses Optifine/Iris shaderpack conventions (#include via the
 * Iris TransformPatcher, {@code #define}-driven branches). This fixture
 * compiles the <i>raw</i> shaderpack sources without Iris's patcher, so some
 * programs are expected to fail to compile here (missing includes, iris-
 * specific macros). The test therefore partitions programs into
 * {@code compiled} / {@code failed} buckets and asserts only that at least
 * one program compiles to valid SPIR-V — proving the glslang frontend is
 * wired end-to-end. Per-program failures are logged for diagnosis, mirroring
 * the spec's "record BSL constructs that glslang rejects, fix iteratively"
 * step (Task 6 SubTask 6.3).
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

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            // Group .vsh/.fsh by program name (basename without extension).
            Map<String, EnumMap<GlslangBridge.Stage, String>> programs = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                // Only world0 shaders/ (skip world-1/world1 stubs and lang/lib).
                if (!name.startsWith("shaders/") || name.contains("/world-1/") || name.contains("/world1/")) {
                    continue;
                }
                if (name.endsWith(".vsh")) {
                    putProgram(programs, baseName(name), GlslangBridge.Stage.VERTEX, readEntry(zf, e));
                } else if (name.endsWith(".fsh")) {
                    putProgram(programs, baseName(name), GlslangBridge.Stage.FRAGMENT, readEntry(zf, e));
                }
            }

            for (Map.Entry<String, EnumMap<GlslangBridge.Stage, String>> prog : programs.entrySet()) {
                String progName = prog.getKey();
                EnumMap<GlslangBridge.Stage, String> stages = prog.getValue();
                String vsh = stages.get(GlslangBridge.Stage.VERTEX);
                String fsh = stages.get(GlslangBridge.Stage.FRAGMENT);
                if (vsh == null || fsh == null) {
                    continue; // incomplete program
                }
                // Try vertex first.
                try {
                    int[] spv = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.VERTEX, vsh, null);
                    assertValidSpirv(progName + ".vsh", spv);
                    int[] spvF = GlslangBridge.compileGlslToSpv(GlslangBridge.Stage.FRAGMENT, fsh, null);
                    assertValidSpirv(progName + ".fsh", spvF);
                    compiled.add(progName);
                } catch (Throwable t) {
                    failed.put(progName, rootMessage(t));
                }
            }
        }

        // Assert the glslang frontend produces at least one valid SPIR-V from
        // the real BSL shaderpack — proving the end-to-end pipeline. Per-program
        // failures are diagnostic (Task 6.3 iterates on these).
        assertFalse(compiled.isEmpty(),
                "No BSL program compiled to valid SPIR-V. All failed: " + failed);
        // Log a compact summary to aid Task 6.3 diagnosis.
        System.out.println("[BSL fixture] compiled=" + compiled.size()
                + " failed=" + failed.size());
        failed.forEach((n, m) -> System.out.println("  FAIL " + n + ": " + m));
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
