package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Atomically pairs one Sodium terrain draw with its Iris PSO and attachments. */
public final class IrisMetalTerrainBridge {
    private static final ThreadLocal<TerrainContext> ACTIVE_TERRAIN = new ThreadLocal<>();

    private IrisMetalTerrainBridge() {
    }

    public static void begin(final TerrainRenderPass pass) {
        MetalWorldRenderingPipeline pipeline = activePipeline();
        if (pipeline == null) {
            ACTIVE_TERRAIN.remove();
            return;
        }

        ShaderKey key = shaderKey(pass);
        Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                pipeline.programs().sodium(key.getProgram(), key.getAlphaTest());
        if (linked.isEmpty()) {
            ACTIVE_TERRAIN.remove();
            return;
        }
        int[] drawBuffers = linked.orElseThrow().program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        ACTIVE_TERRAIN.set(new TerrainContext(pipeline, key, drawBuffers));
    }

    public static void end() {
        ACTIVE_TERRAIN.remove();
    }

    public static @Nullable RenderPass createRenderPass(
            final CommandEncoder encoder,
            final Supplier<String> label,
            final GpuTextureView sceneColor,
            final Optional<Vector4fc> clearColor,
            final GpuTextureView sceneDepth,
            final OptionalDouble clearDepth
    ) {
        TerrainContext context = currentContext();
        if (context == null) {
            return null;
        }
        RenderPassDescriptor descriptor = context.pipeline().resources().renderTargets()
                .createTerrainWriteDescriptor(
                        label.get(),
                        context.drawBuffers(),
                        sceneColor,
                        clearColor.orElse(null),
                        sceneDepth,
                        clearDepth.isPresent() ? clearDepth.getAsDouble() : null
                );
        return encoder.createRenderPass(descriptor);
    }

    static @Nullable MetalCompiledRenderPipeline compiledPipeline(
            final MetalDevice device,
            final RenderPipeline source
    ) {
        TerrainContext context = currentContext();
        if (context == null) {
            return null;
        }
        if (!source.getLocation().getNamespace().contains("sodium")) {
            throw new IllegalArgumentException(
                    "Iris Metal terrain received a non-Sodium pipeline " + source.getLocation()
            );
        }
        if (!context.pipeline().compiledPrograms().isOwnedBy(device)) {
            throw new IllegalStateException("Iris Metal terrain PSO crossed Metal device ownership");
        }

        var state = IrisMetalCompiledPrograms.RasterState.from(
                source, source.getVertexFormatBinding(0)
        );
        return context.pipeline().compiledPrograms()
                .sodium(context.key().getProgram(), context.key().getAlphaTest(), state)
                .orElseThrow(() -> new IllegalStateException(
                        "Iris Metal terrain program disappeared during draw: " + context.key()
                ));
    }

    private static @Nullable TerrainContext currentContext() {
        TerrainContext context = ACTIVE_TERRAIN.get();
        if (context == null) {
            return null;
        }
        if (activePipeline() != context.pipeline()) {
            ACTIVE_TERRAIN.remove();
            throw new IllegalStateException("Iris Metal terrain context crossed world generations");
        }
        return context;
    }

    private static @Nullable MetalWorldRenderingPipeline activePipeline() {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof MetalWorldRenderingPipeline metal ? metal : null;
    }

    private static ShaderKey shaderKey(final TerrainRenderPass pass) {
        boolean shadow = ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        if (pass.isTranslucent()) {
            return shadow
                    ? ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
                    : ShaderKey.SODIUM_TERRAIN_TRANSLUCENT;
        }
        if (pass.supportsFragmentDiscard()) {
            return shadow
                    ? ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT
                    : ShaderKey.SODIUM_TERRAIN_CUTOUT;
        }
        return shadow
                ? ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID
                : ShaderKey.SODIUM_TERRAIN_SOLID;
    }

    private record TerrainContext(
            MetalWorldRenderingPipeline pipeline,
            ShaderKey key,
            int[] drawBuffers
    ) {
        private TerrainContext {
            drawBuffers = Arrays.copyOf(drawBuffers, drawBuffers.length);
        }

        @Override
        public int[] drawBuffers() {
            return Arrays.copyOf(this.drawBuffers, this.drawBuffers.length);
        }
    }
}
