package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** GPU resources owned and retired atomically by one Iris world generation. */
final class IrisMetalWorldResources implements AutoCloseable {
    private final MetalDevice device;
    private final int generation;
    private final IrisMetalRenderTargets renderTargets;
    private final IrisMetalCustomTextures customTextures;
    private final IrisMetalNoiseTexture noiseTexture;
    private boolean closed;

    IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final ProgramSet programSet,
            final int width,
            final int height
    ) {
        this(
                device,
                generation,
                IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()),
                width,
                height,
                programSet.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings(),
                Set.of(),
                programSet.getPack().getCustomTextureDataMap(),
                programSet.getPackDirectives().getNoiseTextureResolution(),
                programSet.getPack().getCustomNoiseTexture()
        );
    }

    IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> customDefinitions,
            final int noiseResolution,
            final @Nullable CustomTextureData customNoise
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris generation must be positive: " + generation);
        }
        this.generation = generation;

        IrisMetalRenderTargets newTargets = null;
        IrisMetalCustomTextures newCustomTextures = null;
        IrisMetalNoiseTexture newNoiseTexture = null;
        try {
            newTargets = new IrisMetalRenderTargets(
                    device, formats, width, height, targetSettings, mipmappedTargets
            );
            newCustomTextures = new IrisMetalCustomTextures(device, customDefinitions);
            newCustomTextures.prewarmAll();
            newNoiseTexture = new IrisMetalNoiseTexture(device, noiseResolution, customNoise);
        } catch (RuntimeException | Error failure) {
            closePartial(newTargets, newCustomTextures, newNoiseTexture);
            throw failure;
        }
        this.renderTargets = newTargets;
        this.customTextures = newCustomTextures;
        this.noiseTexture = newNoiseTexture;
    }

    int generation() {
        return this.generation;
    }

    boolean isOwnedBy(final MetalDevice expected) {
        return this.device == expected;
    }

    IrisMetalRenderTargets renderTargets() {
        ensureOpen();
        return this.renderTargets;
    }

    IrisMetalCustomTextures customTextures() {
        ensureOpen();
        return this.customTextures;
    }

    IrisMetalNoiseTexture noiseTexture() {
        ensureOpen();
        return this.noiseTexture;
    }

    void resize(final int width, final int height) {
        ensureOpen();
        this.renderTargets.resize(width, height);
    }

    private static void closePartial(
            final @Nullable IrisMetalRenderTargets targets,
            final @Nullable IrisMetalCustomTextures customTextures,
            final @Nullable IrisMetalNoiseTexture noiseTexture
    ) {
        if (noiseTexture != null) {
            noiseTexture.close();
        }
        if (customTextures != null) {
            customTextures.close();
        }
        if (targets != null) {
            targets.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal generation " + this.generation + " is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        closePartial(this.renderTargets, this.customTextures, this.noiseTexture);
    }
}
