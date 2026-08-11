package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Linked Iris raster programs owned by one world-pipeline generation. */
@Environment(EnvType.CLIENT)
final class IrisMetalWorldPrograms implements AutoCloseable {
    private final int generation;
    private final IrisMetalProgramFrontend frontend;
    private final Map<SodiumKey, Optional<IrisMetalGlslLinker.LinkedRasterProgram>> sodiumPrograms =
            new HashMap<>();
    private final Map<VanillaKey, Optional<IrisMetalGlslLinker.LinkedRasterProgram>> vanillaPrograms =
            new HashMap<>();
    private final Map<CompositeKey, IrisMetalGlslLinker.LinkedRasterProgram> compositePrograms =
            new HashMap<>();
    private final Map<ComputeKey, IrisMetalProgramFrontend.ComputeProgram> computePrograms =
            new HashMap<>();
    private boolean closed;

    IrisMetalWorldPrograms(final int generation, final ProgramSet programSet) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris generation must be positive: " + generation);
        }
        this.generation = generation;
        this.frontend = new IrisMetalProgramFrontend(
                Objects.requireNonNull(programSet, "programSet")
        );
    }

    int generation() {
        return this.generation;
    }

    synchronized Optional<IrisMetalGlslLinker.LinkedRasterProgram> sodium(
            final ProgramId requested,
            final AlphaTest fallbackAlpha
    ) {
        ensureOpen();
        SodiumKey key = new SodiumKey(
                Objects.requireNonNull(requested, "requested"),
                Objects.requireNonNull(fallbackAlpha, "fallbackAlpha")
        );
        return this.sodiumPrograms.computeIfAbsent(key, ignored ->
                this.frontend.resolve(requested)
                        .map(resolved -> IrisMetalGlslLinker.linkSodium(
                                this.frontend.patchSodium(resolved, fallbackAlpha)
                        ))
        );
    }

    synchronized Optional<IrisMetalGlslLinker.LinkedRasterProgram> vanilla(
            final ProgramId requested,
            final AlphaTest fallbackAlpha,
            final boolean lines,
            final boolean clouds,
            final ShaderAttributeInputs inputs
    ) {
        ensureOpen();
        VanillaKey key = new VanillaKey(
                Objects.requireNonNull(requested, "requested"),
                Objects.requireNonNull(fallbackAlpha, "fallbackAlpha"),
                lines,
                clouds,
                Objects.requireNonNull(inputs, "inputs")
        );
        return this.vanillaPrograms.computeIfAbsent(key, ignored ->
                this.frontend.resolve(requested)
                        .map(resolved -> IrisMetalGlslLinker.linkDefault(
                                this.frontend.patchVanilla(
                                        resolved, fallbackAlpha, lines, clouds, inputs
                                )
                        ))
        );
    }

    /** Resolves a fixed-Iris core draw through the same frontend as terrain and post passes. */
    synchronized Optional<IrisMetalGlslLinker.LinkedRasterProgram> core(final ShaderKey key) {
        Objects.requireNonNull(key, "key");
        boolean lines = key == ShaderKey.LINES && this.frontend.has(ProgramId.Line);
        boolean clouds = key == ShaderKey.CLOUDS || key == ShaderKey.CLOUDS_SODIUM;
        ShaderAttributeInputs inputs = key.getVertexFormat() == null
                ? new ShaderAttributeInputs(true, true, true, true, true)
                : new ShaderAttributeInputs(
                        key.getVertexFormat(),
                        key.shouldIgnoreLightmap(),
                        lines,
                        key.isGlint(),
                        key.isText(),
                        false
                );
        return vanilla(key.getProgram(), key.getAlphaTest(), lines, clouds, inputs);
    }

    synchronized IrisMetalGlslLinker.LinkedRasterProgram composite(
            final ProgramSource source,
            final TextureStage stage
    ) {
        ensureOpen();
        CompositeKey key = new CompositeKey(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(stage, "stage")
        );
        return this.compositePrograms.computeIfAbsent(key, ignored ->
                IrisMetalGlslLinker.linkDefault(this.frontend.patchComposite(source, stage))
        );
    }

    synchronized IrisMetalGlslLinker.LinkedRasterProgram finalProgram() {
        ensureOpen();
        return this.frontend.resolve(ProgramId.Final)
                .map(resolved -> IrisMetalGlslLinker.linkDefault(
                        this.frontend.patchComposite(
                                resolved.source(),
                                TextureStage.COMPOSITE_AND_FINAL
                        )
                ))
                .orElse(null);
    }

    synchronized IrisMetalProgramFrontend.ComputeProgram compute(
            final ComputeSource source,
            final TextureStage stage
    ) {
        ensureOpen();
        ComputeKey key = new ComputeKey(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(stage, "stage")
        );
        return this.computePrograms.computeIfAbsent(
                key,
                ignored -> this.frontend.patchCompute(source, stage)
        );
    }

    synchronized int cachedProgramCount() {
        return this.sodiumPrograms.size()
                + this.vanillaPrograms.size()
                + this.compositePrograms.size()
                + this.computePrograms.size();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Iris Metal program generation " + this.generation + " is closed"
            );
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.sodiumPrograms.clear();
        this.vanillaPrograms.clear();
        this.compositePrograms.clear();
        this.computePrograms.clear();
    }

    private record SodiumKey(ProgramId requested, AlphaTest fallbackAlpha) {
    }

    private record VanillaKey(
            ProgramId requested,
            AlphaTest fallbackAlpha,
            boolean lines,
            boolean clouds,
            ShaderAttributeInputs inputs
    ) {
    }

    private record CompositeKey(ProgramSource source, TextureStage stage) {
    }

    private record ComputeKey(ComputeSource source, TextureStage stage) {
    }
}
