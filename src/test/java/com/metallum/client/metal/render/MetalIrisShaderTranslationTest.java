package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.StageKind;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.TranslatedProgram;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.TranslatedStage;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.TranslationException;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.IrisDefines;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B2-2 audit harness: run every program of every locally provisioned real
 * shader pack through the production translation chain (Iris ShaderPack
 * loader -> TransformPatcher -> {@link MetalIrisShaderCompiler} -> MSL ->
 * actual MTLLibrary compile on the system device) and emit a per-program
 * matrix.
 *
 * <p>Pack fixtures are NOT in git (shader packs are not redistributable);
 * they live in {@code run/shaderpacks/*.zip} (see docs/iris-audit/runbook.md).
 * Bring-up gates asserted here: each pack must load headlessly, and each pack
 * must have at least one program that survives the full chain including the
 * device MSL compile. The full matrix is written to
 * {@code build/reports/metallum/iris_shader_translation.md}; per-stage
 * failures are expected while B2 is in progress and are recorded, not
 * asserted away — the acceptance bar for Phase 1 remains full-chain
 * rendering, not this harness.</p>
 */
@EnabledOnOs(OS.MAC)
final class MetalIrisShaderTranslationTest {
    private static final int DUMP_LIMIT = 12;

    private MetalDevice device;
    private final List<Row> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int dumpsWritten;

    private record Row(
            String pack,
            String program,
            String family,
            StageKind stage,
            boolean ok,
            String phase,
            String detail,
            boolean forced450,
            String drawBuffers
    ) {
    }

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource source = (identifier, type) -> null;
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris shader translation device",
                MemorySegment.NULL
        );
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void translateAllProgramsOfAllLocalPacks() throws IOException {
        List<Path> packs = discoverPacks();
        assertFalse(packs.isEmpty(),
                "No shader pack fixtures found. Provision run/shaderpacks/*.zip per docs/iris-audit/runbook.md");

        Iris.testing = true;
        for (Path pack : packs) {
            translatePack(pack);
        }

        Path report = writeReport();
        printSummary(report);

        for (Path pack : packs) {
            String packName = pack.getFileName().toString();
            List<Row> packRows = rows.stream().filter(r -> r.pack.equals(packName)).toList();
            assertFalse(packRows.isEmpty(), packName + ": pack produced no translatable programs");
            Map<String, Boolean> fullOk = new java.util.LinkedHashMap<>();
            for (Row row : packRows) {
                fullOk.merge(row.program, row.ok, Boolean::logicalAnd);
            }
            assertTrue(fullOk.containsValue(Boolean.TRUE),
                    packName + ": no program survived the full GLSL->MSL->device chain; see " + report);
        }
    }

    private List<Path> discoverPacks() throws IOException {
        Path dir = Path.of(System.getProperty("metallum.iris.shaderpack.dir", "run/shaderpacks"));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted()
                    .toList();
        }
    }

    private void translatePack(final Path packZip) throws IOException {
        String packName = packZip.getFileName().toString();
        try (FileSystem fs = FileSystems.newFileSystem(packZip)) {
            Path shaders = fs.getPath("/shaders");
            assertTrue(Files.isDirectory(shaders), packName + " has no /shaders directory");

            ShaderPack pack = loadPack(packName, shaders);
            ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

            Set<String> seenSources = new HashSet<>();

            for (ProgramArrayId arrayId : ProgramArrayId.values()) {
                TextureStage stage = stageFor(arrayId);
                for (ProgramSource source : set.getComposite(arrayId)) {
                    if (source != null && source.isValid() && seenSources.add(source.getName())) {
                        translateCompositeProgram(packName, arrayId.name().toLowerCase(Locale.ROOT), source, stage);
                    }
                }
                for (ComputeSource[] group : set.getCompute(arrayId)) {
                    translateComputeGroup(packName, arrayId.name().toLowerCase(Locale.ROOT), group, stage, seenSources);
                }
            }
            translateComputeGroup(packName, "setup", set.getSetup(), TextureStage.SETUP, seenSources);
            translateComputeGroup(packName, "shadowcomp", set.getShadowCompute(), TextureStage.SHADOWCOMP, seenSources);
            translateComputeGroup(packName, "final", set.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, seenSources);

            for (ProgramId programId : ProgramId.values()) {
                if (programId.name().startsWith("Dh")) {
                    continue; // Distant Horizons programs are out of scope for the Metal line
                }
                Optional<ProgramSource> maybe = set.get(programId);
                if (maybe.isEmpty()) {
                    continue;
                }
                ProgramSource source = maybe.get();
                if (!source.isValid() || !seenSources.add(source.getName())) {
                    continue;
                }
                if (programId == ProgramId.Final) {
                    translateCompositeProgram(packName, "final", source, TextureStage.COMPOSITE_AND_FINAL);
                } else {
                    translateGbuffersProgram(packName, programId, source);
                }
            }
        }
    }

    private ShaderPack loadPack(final String packName, final Path shaders) {
        ImmutableList<StringPair> defines = environmentDefines();
        Throwable first = null;
        for (boolean flag : new boolean[]{false, true}) {
            try {
                return new ShaderPack(shaders, defines, flag);
            } catch (Throwable t) {
                if (first == null) {
                    first = t;
                }
            }
        }
        fail(packName + ": ShaderPack failed to load headlessly: " + first, first);
        throw new IllegalStateException("unreachable");
    }

    private ImmutableList<StringPair> environmentDefines() {
        try {
            ImmutableList<StringPair> standard = StandardMacros.createStandardEnvironmentDefines();
            notes.add("environment defines: StandardMacros test shadow (pinned GL 4.6 / macOS environment; "
                    + "see src/test/java/net/irisshaders/iris/gl/shader/StandardMacros.java)");
            return standard;
        } catch (Throwable t) {
            notes.add("environment defines: fallback list (StandardMacros failed headlessly: "
                    + t.getClass().getSimpleName() + ")");
        }
        ImmutableList.Builder<StringPair> builder = ImmutableList.builder();
        builder.add(new StringPair("MC_VERSION", "12602"));
        builder.add(new StringPair("MC_GL_VERSION", "460"));
        builder.add(new StringPair("MC_GLSL_VERSION", "460"));
        builder.add(new StringPair("MC_OS_MAC", ""));
        builder.add(new StringPair("MC_GL_VENDOR_APPLE", ""));
        builder.add(new StringPair("MC_GL_RENDERER_OTHER", ""));
        builder.add(new StringPair("MC_NORMAL_MAP", ""));
        builder.add(new StringPair("MC_SPECULAR_MAP", ""));
        builder.add(new StringPair("MC_RENDER_QUALITY", "1.0"));
        builder.add(new StringPair("MC_SHADOW_QUALITY", "1.0"));
        builder.add(new StringPair("MC_HAND_DEPTH", "0.125"));
        try {
            builder.addAll(IrisDefines.createIrisReplacements());
        } catch (Throwable ignored) {
            // pure-Iris replacements are additive; skip if unavailable headlessly
        }
        return builder.build();
    }

    private static TextureStage stageFor(final ProgramArrayId arrayId) {
        EnumMap<ProgramArrayId, TextureStage> map = new EnumMap<>(ProgramArrayId.class);
        map.put(ProgramArrayId.Setup, TextureStage.SETUP);
        map.put(ProgramArrayId.Begin, TextureStage.BEGIN);
        map.put(ProgramArrayId.ShadowComposite, TextureStage.SHADOWCOMP);
        map.put(ProgramArrayId.Prepare, TextureStage.PREPARE);
        map.put(ProgramArrayId.Deferred, TextureStage.DEFERRED);
        map.put(ProgramArrayId.Composite, TextureStage.COMPOSITE_AND_FINAL);
        return map.getOrDefault(arrayId, TextureStage.COMPOSITE_AND_FINAL);
    }

    private void translateCompositeProgram(
            final String pack, final String family, final ProgramSource source, final TextureStage stage
    ) {
        String name = source.getName();
        String drawBuffers = Arrays.toString(source.getDirectives().getDrawBuffers());
        try {
            TranslatedProgram program = MetalIrisShaderCompiler.translateComposite(
                    name,
                    source.getVertexSource().orElseThrow(() -> new TranslationException(
                            name, MetalIrisShaderCompiler.PHASE_PATCH, StageKind.VERTEX, "missing vertex source")),
                    source.getGeometrySource().orElse(null),
                    source.getFragmentSource().orElseThrow(() -> new TranslationException(
                            name, MetalIrisShaderCompiler.PHASE_PATCH, StageKind.FRAGMENT, "missing fragment source")),
                    stage
            );
            recordStages(pack, name, family, program, drawBuffers);
        } catch (TranslationException e) {
            recordFailure(pack, name, family, e, drawBuffers);
        }
    }

    private void translateGbuffersProgram(final String pack, final ProgramId programId, final ProgramSource source) {
        String name = source.getName();
        String family = programId.getGroup().name().toLowerCase(Locale.ROOT);
        String drawBuffers = Arrays.toString(source.getDirectives().getDrawBuffers());
        try {
            TranslatedProgram program = MetalIrisShaderCompiler.translateVanillaGbuffers(name, source);
            recordStages(pack, name, family, program, drawBuffers);
        } catch (TranslationException e) {
            recordFailure(pack, name, family, e, drawBuffers);
        }
    }

    private void translateComputeGroup(
            final String pack,
            final String family,
            final ComputeSource[] group,
            final TextureStage stage,
            final Set<String> seenSources
    ) {
        if (group == null) {
            return;
        }
        for (ComputeSource compute : group) {
            if (compute == null || !compute.isValid() || compute.getSource().isEmpty()) {
                continue;
            }
            if (!seenSources.add(compute.getName())) {
                continue;
            }
            String name = compute.getName();
            try {
                TranslatedProgram program = MetalIrisShaderCompiler.translateCompute(name, compute.getSource().get(), stage);
                recordStages(pack, name, family, program, "-");
            } catch (TranslationException e) {
                recordFailure(pack, name, family, e, "-");
            }
        }
    }

    private void recordStages(
            final String pack, final String name, final String family,
            final TranslatedProgram program, final String drawBuffers
    ) {
        program.vertex().ifPresent(s -> recordDeviceCompile(pack, name, family, s, drawBuffers));
        program.fragment().ifPresent(s -> recordDeviceCompile(pack, name, family, s, drawBuffers));
        program.compute().ifPresent(s -> recordDeviceCompile(pack, name, family, s, drawBuffers));
    }

    private void recordDeviceCompile(
            final String pack, final String name, final String family,
            final TranslatedStage stage, final String drawBuffers
    ) {
        MemorySegment function = device.getOrCompileFunction(stage.msl(), stage.entryPoint());
        if (MetalNativeBridge.isNullHandle(function)) {
            rows.add(new Row(pack, name, family, stage.kind(), false, "msl-device-compile",
                    "MTLLibrary rejected the generated MSL (details on stderr via NSLog)", stage.forcedVersion450(), drawBuffers));
            dumpStage(pack, name, stage);
        } else {
            rows.add(new Row(pack, name, family, stage.kind(), true, "-", "-", stage.forcedVersion450(), drawBuffers));
        }
    }

    private void recordFailure(
            final String pack, final String name, final String family,
            final TranslationException e, final String drawBuffers
    ) {
        String detail = firstLine(e.getMessage());
        StageKind kind = e.stageKind();
        if (kind == null) {
            // program-level failure (patch/unsupported stage): one row per absent stage result
            rows.add(new Row(pack, name, family, StageKind.VERTEX, false, e.phase(), detail, false, drawBuffers));
            rows.add(new Row(pack, name, family, StageKind.FRAGMENT, false, e.phase(), detail, false, drawBuffers));
        } else {
            rows.add(new Row(pack, name, family, kind, false, e.phase(), detail, false, drawBuffers));
        }
        dumpFailure(pack, name, e);
    }

    private void dumpStage(final String pack, final String name, final TranslatedStage stage) {
        if (dumpsWritten >= DUMP_LIMIT) {
            return;
        }
        try {
            Path dir = Path.of("build/reports/metallum/translation-dumps", sanitize(pack), sanitize(name));
            Files.createDirectories(dir);
            String prefix = stage.kind().name().toLowerCase(Locale.ROOT);
            Files.writeString(dir.resolve(prefix + ".patched.glsl"), stage.patchedGlsl());
            Files.writeString(dir.resolve(prefix + ".wrapped.glsl"), stage.wrappedGlsl());
            Files.writeString(dir.resolve(prefix + ".msl"), stage.msl());
            dumpsWritten++;
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private void dumpFailure(final String pack, final String name, final TranslationException e) {
        if (dumpsWritten >= DUMP_LIMIT) {
            return;
        }
        try {
            Path dir = Path.of("build/reports/metallum/translation-dumps", sanitize(pack), sanitize(name));
            Files.createDirectories(dir);
            StringBuilder sb = new StringBuilder();
            sb.append("phase: ").append(e.phase()).append('\n');
            sb.append("stage: ").append(e.stageKind()).append('\n');
            sb.append("message:\n").append(e.getMessage()).append('\n');
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                sb.append("cause: ").append(cause).append('\n');
            }
            Files.writeString(dir.resolve("failure.txt"), sb.toString());
            if (e.sourceDump() != null) {
                Files.writeString(dir.resolve("failing-source.glsl"), e.sourceDump());
            }
            dumpsWritten++;
        } catch (IOException ignored) {
            // diagnostics only
        }
    }

    private static String sanitize(final String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String firstLine(final String message) {
        if (message == null) {
            return "(no message)";
        }
        int newline = message.indexOf('\n');
        String line = newline >= 0 ? message.substring(0, newline) : message;
        return line.length() > 220 ? line.substring(0, 220) + "…" : line;
    }

    private Path writeReport() throws IOException {
        Path report = Path.of("build/reports/metallum/iris_shader_translation.md");
        Files.createDirectories(report.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Iris shader-pack translation matrix (GLSL -> SPIR-V -> MSL -> MTLLibrary)\n\n");
        for (String note : notes) {
            sb.append("- ").append(note).append('\n');
        }
        sb.append('\n');
        for (String pack : rows.stream().map(Row::pack).distinct().toList()) {
            List<Row> packRows = rows.stream().filter(r -> r.pack.equals(pack)).toList();
            long ok = packRows.stream().filter(Row::ok).count();
            sb.append("## ").append(pack).append(" — ").append(ok).append('/').append(packRows.size())
                    .append(" stages OK\n\n");
            sb.append("| program | family | stage | status | phase | drawbuffers | forced450 | detail |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            for (Row row : packRows) {
                sb.append("| ").append(row.program)
                        .append(" | ").append(row.family)
                        .append(" | ").append(row.stage.name().toLowerCase(Locale.ROOT))
                        .append(" | ").append(row.ok ? "OK" : "FAIL")
                        .append(" | ").append(row.phase)
                        .append(" | ").append(row.drawBuffers)
                        .append(" | ").append(row.forced450 ? "yes" : "-")
                        .append(" | ").append(row.detail.replace("|", "\\|"))
                        .append(" |\n");
            }
            sb.append('\n');
        }
        Files.writeString(report, sb.toString());
        return report;
    }

    private void printSummary(final Path report) {
        for (String pack : rows.stream().map(Row::pack).distinct().toList()) {
            List<Row> packRows = rows.stream().filter(r -> r.pack.equals(pack)).toList();
            long ok = packRows.stream().filter(Row::ok).count();
            Map<String, Long> failsByPhase = new java.util.TreeMap<>();
            packRows.stream().filter(r -> !r.ok).forEach(r -> failsByPhase.merge(r.phase, 1L, Long::sum));
            System.out.printf("[translation] %s: %d/%d stages OK; failures by phase: %s%n",
                    pack, ok, packRows.size(), failsByPhase);
        }
        System.out.println("[translation] full matrix: " + report.toAbsolutePath());
    }
}
