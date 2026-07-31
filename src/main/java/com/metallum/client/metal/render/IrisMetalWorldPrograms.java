package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

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

    synchronized int cachedProgramCount() {
        return this.sodiumPrograms.size()
                + this.vanillaPrograms.size()
                + this.compositePrograms.size();
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
}
