package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import com.metallum.client.metal.render.IrisMetalPipelineOverrides.TerrainKind;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.GlslProgram;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.ReflectedUniformBlock;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.StageKind;
import com.metallum.client.metal.render.MetalIrisShaderCompiler.UniformMember;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.IrisDefines;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import net.minecraft.resources.Identifier;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * B2-1 offline gate: for every local pack fixture, the sodium terrain
 * programs must travel the FULL executable path — patchSodium, pair-link,
 * synthetic RenderPipeline, the stock compile chain (vanilla GlslCompiler,
 * by-name rebind, SPIRV-Cross), and a real-device PSO — exactly as the
 * in-game pipeline-override hook will run them.
 *
 * <p>Also proves the std140 layout table against glslang's own reflection:
 * every member offset computed by {@code computeStd140Layout} must equal the
 * offset in the compiled SPIR-V for both stages.</p>
 *
 * <p>Artifacts (patched/wrapped GLSL, layout, resources) are always written
 * to {@code build/reports/metallum/sodium-terrain/} — they are the ground
 * truth the in-game uniform provider and terrain-pass wiring are built
 * against.</p>
 */
@EnabledOnOs(OS.MAC)
final class MetalIrisSodiumTerrainTest {
    private MetalDevice device;
    /** Cleared per pack; the pre-prewarm guard is only meaningful once. */
    private boolean prewarmed;
    private final List<String> notes = new ArrayList<>();

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
                "Iris sodium terrain device",
                MemorySegment.NULL
        );
    }

    @AfterEach
    void closeDevice() {
        IrisMetalPipelineOverrides.setExtendedTerrainTargets(false);
        IrisMetalPipelineOverrides.deactivate();
        WorldRenderingSettings.INSTANCE.setVertexFormat(null);
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void terrainProgramsCompileToDevicePipelines() throws IOException {
        List<Path> packs = discoverPacks();
        assertFalse(packs.isEmpty(),
                "No shader pack fixtures found. Provision run/shaderpacks/*.zip per docs/iris-audit/runbook.md");

        Iris.testing = true;
        // This gate verifies translation and PSO creation, which do not depend
        // on the terrain pass's attachment count; the runtime gate that keeps
        // multi-target kinds native until the pass is extended (handoff S6) is
        // lifted here so every kind is exercised end to end.
        IrisMetalPipelineOverrides.setExtendedTerrainTargets(true);
        // The same XHFP chunk format the in-game runtime configures
        // (IrisRenderingPipeline ctor bytecode: FormatAnalyzer.createFormat(true,true,true,true)).
        WorldRenderingSettings.INSTANCE.setVertexFormat(FormatAnalyzer.createFormat(true, true, true, true));

        for (Path pack : packs) {
            runPack(pack);
        }
        for (String note : notes) {
            System.out.println("[sodium-terrain] " + note);
        }
    }

    private void runPack(final Path packZip) throws IOException {
        String packName = packZip.getFileName().toString();
        try (FileSystem fs = FileSystems.newFileSystem(packZip)) {
            Path shaders = fs.getPath("/shaders");
            assertTrue(Files.isDirectory(shaders), packName + " has no /shaders directory");
            ShaderPack pack = loadPack(packName, shaders);
            ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

            this.prewarmed = false;
            IrisMetalPipelineOverrides.Instance instance =
                    IrisMetalPipelineOverrides.activate(set, new Object2ObjectOpenHashMap<>());
            try {
                boolean anyKind = false;
                for (TerrainKind kind : TerrainKind.values()) {
                    GlslProgram program = instance.program(kind);
                    if (program == null) {
                        notes.add(packName + " " + kind + ": no translated program (see log for cause)");
                        continue;
                    }
                    anyKind = true;
                    dumpProgram(packName, kind, program);
                    verifyStd140(packName, kind, program);
                    compileToDevice(packName, kind, instance, program);
                }
                assertTrue(anyKind, packName + ": no terrain kind translated at all");
            } finally {
                IrisMetalPipelineOverrides.deactivate();
            }
        }
    }

    /** The four resources sodium's DefaultChunkRenderer binds itself before drawing. */
    private static final java.util.Set<String> SODIUM_SUPPLIED_RESOURCES = java.util.Set.of(
            "u_Globals", "u_SectionTimeInfo", "u_BlockTex", "u_LightTex", "push_constants");

    private void compileToDevice(
            final String packName,
            final TerrainKind kind,
            final IrisMetalPipelineOverrides.Instance instance,
            final GlslProgram program
    ) {
        RenderPipeline fake = fakeSodiumPipeline(kind);
        assertEquals(kind, IrisMetalPipelineOverrides.Instance.discriminate(fake),
                packName + " " + kind + ": fake pipeline discrimination mismatch");
        MetalCompiledRenderPipeline compiled = IrisMetalPipelineOverrides.tryCompile(device, fake, null);
        assertNotNull(compiled,
                packName + " " + kind + ": override compile returned null (fail-open path hit; see log + dumps)");
        assertTrue(compiled.isValid(), packName + " " + kind + ": PSO invalid");

        List<String> resourceNames = compiled.resources().stream()
                .map(MetalCompiledRenderPipeline.ResourceBinding::name)
                .toList();
        if (program.hasUniformBlock()) {
            assertTrue(resourceNames.contains(MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                    packName + " " + kind + ": resources lack " + MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME
                            + "; got " + resourceNames);
        }
        verifyUniformSupply(packName, kind, instance, program, compiled);
        notes.add(packName + " " + kind + ": PSO ok; drawBuffers="
                + Arrays.toString(program.drawBuffers())
                + "; uniforms=" + program.uniformLayout().size()
                + " (block " + program.uniformBlockSize() + "B)"
                + "; samplers=" + program.samplers().stream().map(MetalIrisShaderCompiler.SamplerDecl::name).toList()
                + "; resources=" + resourceNames);
    }

    /**
     * Every resource the compiled override declares must be resolvable at draw
     * time, either by sodium (which binds its own four) or by the registry's
     * fallback. A name no one supplies would throw
     * {@code Missing uniform/sampler} on the first terrain draw in game, so the
     * whole binding table is walked here rather than trusting the PSO alone.
     *
     * <p>Sodium's own names are simulated as already bound, which is what
     * {@code DefaultChunkRenderer} does before drawing.</p>
     */
    private void verifyUniformSupply(
            final String packName,
            final TerrainKind kind,
            final IrisMetalPipelineOverrides.Instance instance,
            final GlslProgram program,
            final MetalCompiledRenderPipeline compiled
    ) {
        Map<String, MetalRenderPass.TextureViewAndSampler> boundBySodium = Map.of();

        // Regression guard for handoff §6 iteration 5: before prewarm, the
        // draw-path resolvers must be pure lookups. Allocating or uploading
        // there would end the live render encoder and crash the next binding
        // write, which is exactly how the first in-world run died. Only
        // meaningful before the first prewarm, hence once per pack.
        if (!this.prewarmed) {
            assertNull(IrisMetalPipelineOverrides.fallbackTexture(device, compiled, "noisetex", boundBySodium),
                    packName + " " + kind + ": fallbackTexture allocated on the draw path before prewarm");
            assertNull(IrisMetalPipelineOverrides.fallbackUniform(
                            device, compiled, MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME),
                    packName + " " + kind + ": fallbackUniform allocated on the draw path before prewarm");
            this.prewarmed = true;
        }

        // Everything the draw path needs is created here, off the encoder.
        IrisMetalPipelineOverrides.updateFrame();

        for (MetalCompiledRenderPipeline.ResourceBinding binding : compiled.resources()) {
            if (SODIUM_SUPPLIED_RESOURCES.contains(binding.name())) {
                continue;
            }
            switch (binding.kind()) {
                case SAMPLED_IMAGE -> assertNotNull(
                        IrisMetalPipelineOverrides.fallbackTexture(device, compiled, binding.name(), boundBySodium),
                        packName + " " + kind + ": nothing supplies sampler '" + binding.name() + "'");
                case UNIFORM_BUFFER -> assertNotNull(
                        IrisMetalPipelineOverrides.fallbackUniform(device, compiled, binding.name()),
                        packName + " " + kind + ": nothing supplies uniform '" + binding.name() + "'");
                case TEXEL_BUFFER -> { /* sodium's u_SectionTimeInfo only; covered above */ }
            }
        }

        // The block must actually be filled, not just allocated: check the
        // identity model-view the neutral frame writes lands at its offset.
        if (!program.hasUniformBlock()) {
            return;
        }
        UniformMember modelView = program.uniformLayout().stream()
                .filter(m -> m.name().equals("gbufferModelView"))
                .findFirst().orElse(null);
        if (modelView == null) {
            return;
        }
        java.nio.ByteBuffer data = instance.uniformStaging(kind);
        assertNotNull(data, packName + " " + kind + ": uniform block was never filled");
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                float expected = column == row ? 1.0f : 0.0f;
                assertEquals(expected,
                        data.getFloat(modelView.offset() + (column * 4 + row) * Float.BYTES), 0.0f,
                        packName + " " + kind + ": gbufferModelView[" + column + "][" + row + "] not written");
            }
        }
    }

    private void verifyStd140(final String packName, final TerrainKind kind, final GlslProgram program) {
        if (!program.hasUniformBlock()) {
            return;
        }
        Map<String, UniformMember> computed = new java.util.LinkedHashMap<>();
        for (UniformMember member : program.uniformLayout()) {
            computed.put(member.name(), member);
        }
        for (StageKind stage : new StageKind[]{StageKind.VERTEX, StageKind.FRAGMENT}) {
            String source = stage == StageKind.VERTEX ? program.vertexGlsl() : program.fragmentGlsl();
            ReflectedUniformBlock reflected = MetalIrisShaderCompiler.reflectUniformBlock(
                    program.name() + "/" + stage, stage, source, MetalIrisShaderCompiler.UNIFORM_BLOCK_NAME);
            assertNotNull(reflected, packName + " " + kind + " " + stage + ": block not found in SPIR-V reflection");
            for (Map.Entry<String, Integer> entry : reflected.memberOffsets().entrySet()) {
                UniformMember member = computed.get(entry.getKey());
                assertNotNull(member, packName + " " + kind + " " + stage
                        + ": reflected member " + entry.getKey() + " missing from computed layout");
                assertEquals(member.offset(), entry.getValue().intValue(),
                        packName + " " + kind + " " + stage + ": std140 offset mismatch for " + entry.getKey());
            }
            assertTrue(program.uniformBlockSize() >= reflected.declaredSize(),
                    packName + " " + kind + " " + stage + ": computed block size " + program.uniformBlockSize()
                            + " < declared " + reflected.declaredSize());
        }
    }

    /**
     * Stand-in for the RenderPipeline sodium's ShaderChunkRenderer.createShader
     * builds at runtime: sodium namespace, one main-framebuffer color target,
     * CUTOUT discriminated via shader defines and translucent via blending —
     * the exact properties Iris's own IrisPipelines.getPipeline consults.
     */
    private static RenderPipeline fakeSodiumPipeline(final TerrainKind kind) {
        // Sodium's real terrain bind group (ShaderChunkRenderer.<clinit> bytecode):
        // samplers u_LightTex/u_BlockTex, UBO u_Globals, texel buffer
        // u_SectionTimeInfo (R32_SINT).
        com.mojang.blaze3d.pipeline.BindGroupLayout sodiumLayout = com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
                .withSampler("u_LightTex")
                .withSampler("u_BlockTex")
                .withUniform("u_Globals", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
                .withUniform("u_SectionTimeInfo", com.mojang.blaze3d.shaders.UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT)
                .build();
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("sodium", "test_chunk_shader_" + kind.name().toLowerCase(Locale.ROOT)))
                .withVertexShader(Identifier.fromNamespaceAndPath("sodium", "test_chunk_shader_v"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("sodium", "test_chunk_shader_f"))
                .withCull(true)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(sodiumLayout)
                .withVertexBinding(0, DefaultVertexFormat.BLOCK);
        if (kind == TerrainKind.TRANSLUCENT) {
            builder.withColorTargetState(0, new ColorTargetState(
                    Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        } else {
            builder.withColorTargetState(0, new ColorTargetState(
                    Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
        }
        if (kind == TerrainKind.CUTOUT) {
            builder.withShaderDefine("CUTOUT");
        }
        return builder.build();
    }

    private void dumpProgram(final String packName, final TerrainKind kind, final GlslProgram program) throws IOException {
        Path dir = Path.of("build/reports/metallum/sodium-terrain",
                packName.replaceAll("[^a-zA-Z0-9_.-]", "_"), kind.name().toLowerCase(Locale.ROOT));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("vertex.patched.glsl"), program.vertexPatched());
        Files.writeString(dir.resolve("fragment.patched.glsl"), program.fragmentPatched());
        Files.writeString(dir.resolve("vertex.wrapped.glsl"), program.vertexGlsl());
        Files.writeString(dir.resolve("fragment.wrapped.glsl"), program.fragmentGlsl());
        StringBuilder meta = new StringBuilder();
        meta.append("# ").append(program.name()).append(" (").append(kind).append(")\n\n");
        meta.append("drawBuffers: ").append(Arrays.toString(program.drawBuffers())).append("\n\n");
        meta.append("uniform block (").append(program.uniformBlockSize()).append(" bytes):\n\n");
        meta.append("| member | type | array | offset | size |\n|---|---|---|---|---|\n");
        for (UniformMember member : program.uniformLayout()) {
            meta.append("| ").append(member.name()).append(" | ").append(member.type())
                    .append(" | ").append(member.arrayCount())
                    .append(" | ").append(member.offset())
                    .append(" | ").append(member.byteSize()).append(" |\n");
        }
        meta.append("\nsamplers:\n\n");
        for (MetalIrisShaderCompiler.SamplerDecl sampler : program.samplers()) {
            meta.append("- ").append(sampler.name()).append(" : ").append(sampler.glslType()).append("\n");
        }
        meta.append("\nuniform blocks: ").append(program.uniformBlockNames()).append("\n");
        Files.writeString(dir.resolve("meta.md"), meta.toString());
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
            return StandardMacros.createStandardEnvironmentDefines();
        } catch (Throwable t) {
            notes.add("environment defines: fallback list (StandardMacros failed headlessly: "
                    + t.getClass().getSimpleName() + ")");
        }
        ImmutableList.Builder<StringPair> builder = ImmutableList.builder();
        builder.add(new StringPair("MC_VERSION", "12602"));
        builder.add(new StringPair("MC_GL_VERSION", "460"));
        builder.add(new StringPair("MC_GLSL_VERSION", "460"));
        builder.add(new StringPair("MC_OS_MAC", ""));
        try {
            builder.addAll(IrisDefines.createIrisReplacements());
        } catch (Throwable ignored) {
            // pure-Iris replacements are additive; skip if unavailable headlessly
        }
        return builder.build();
    }
}
