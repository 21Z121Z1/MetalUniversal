package com.metallum.client.metal.render;

import com.metallum.client.metal.iris.MetalIrisBridge;
import com.metallum.client.metal.iris.MetalIrisBridge.LooseUniformLayout;
import com.metallum.client.metal.iris.MetalIrisBridge.LooseUniformMember;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * Per-program provider that marshals real values for the
 * {@code iris_LooseUniforms} UBO into a single CPU-mapped
 * {@link MetalGpuBuffer}, bound by {@code MetalRenderPass.pushIrisUniformBindings}
 * (M5e) to the reflected {@code [[buffer(N)]]} slot of the same name.
 *
 * <p>Construction allocates a CPU-mapped uniform buffer sized to the layout's
 * total std140 size (rounded up to 16 bytes). {@link #marshal()} is called
 * before each bind to refresh the buffer contents from the live vanilla state
 * ({@link RenderSystem} for camera matrices and fog color,
 * {@link Minecraft#getInstance()} for screen size). Each individual uniform is
 * wrapped in its own try/catch so a single missing API or null capture does
 * not poison the rest of the UBO — the slot stays at the zero it was
 * initialized to.
 *
 * <p><b>M5e design note:</b> this class lives in the {@code render} package so
 * it can access {@link MetalDevice}'s package-private {@code createBuffer}
 * path and {@link MetalGpuBuffer}'s package-private {@code currentStorage}
 * accessor. It therefore MUST NOT reference any {@code net.irisshaders.iris.*}
 * class (Iris is {@code compileOnly} and the {@code render} package is loaded
 * eagerly even when Iris is absent). All uniform values are sourced from
 * vanilla {@link RenderSystem} / {@link Minecraft} APIs that mirror the
 * values Iris's own {@code CapturedRenderingState} captures.
 *
 * <p><b>What is populated today:</b>
 * <ul>
 *   <li>{@code iris_ModelViewMat} / {@code iris_ModelViewMatrix} &larr;
 *       {@link RenderSystem#getModelViewMatrixCopy()} (CPU-side copy)</li>
 *   <li>{@code iris_ProjMat} / {@code iris_ProjectionMatrix} &larr; left at zero
 *       (MC 26.2 moved projection to a GPU buffer; no CPU-side Matrix4f accessor
 *       without Sodium's GameRendererStorage)</li>
 *   <li>{@code iris_FogColor} &larr; left at zero (MC 26.2 moved fog color into
 *       {@link RenderSystem#getShaderFog()}, a GpuBufferSlice, not float[])</li>
 *   <li>{@code iris_ScreenSize} &larr;
 *       {@code Minecraft.getInstance().gameRenderer.mainRenderTarget()} width/height</li>
 * </ul>
 *
 * <p>Other loose uniforms ({@code iris_NormalMat}, {@code iris_TextureMat},
 * {@code iris_ColorModulator}, {@code iris_FogStart/End}, ...) are left at
 * zero — they will be added as their source-of-truth APIs are confirmed for
 * the current MC version. Even partially-zeroed, this UBO is a strict
 * improvement over the prior behaviour of binding a fully-zeroed 16 KiB
 * scratch buffer for the slot.
 *
 * <p>This class is in the {@code com.metallum.client.metal.render} package so
 * it can access {@link MetalDevice}'s package-private {@code createBuffer}
 * path and {@link MetalGpuBuffer}'s package-private {@code currentStorage}
 * accessor.
 */
final class MetalIrisUniformProvider implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("MetalUniversal");

    private final String programName;
    private final LooseUniformLayout layout;
    private final MetalGpuBuffer buffer;
    private final ByteBuffer storage;
    private boolean closed;

    MetalIrisUniformProvider(
            final MetalDevice device,
            final String programName,
            final LooseUniformLayout layout
    ) {
        this.programName = programName;
        this.layout = layout;
        // Metal UBOs must be at least 16 bytes; round totalSize up to 16.
        final long size = Math.max(16L, (long) layout.totalSize());
        this.buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris_loose_uniforms_" + programName,
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                size
        );
        this.storage = this.buffer.currentStorage();
        // Zero once at construction so unmapped members read as zero before
        // the first marshal() call.
        zeroStorage();
    }

    MetalGpuBuffer buffer() {
        return this.buffer;
    }

    LooseUniformLayout layout() {
        return this.layout;
    }

    String programName() {
        return this.programName;
    }

    boolean isClosed() {
        return this.closed || this.buffer.isClosed();
    }

    /**
     * Refills the buffer with the current values of every known Iris uniform.
     * Safe to call every frame; idempotent. The buffer is zeroed first so any
     * member not explicitly written below reads as zero (matching the previous
     * scratch-buffer fallback behaviour for unmapped members).
     */
    void marshal() {
        if (closed) {
            return;
        }
        zeroStorage();

        // MC 26.2: RenderSystem.getModelViewMatrixCopy() returns a CPU-side copy of the
        // model-view matrix (confirmed via Iris ExtendedShader usage). iris_ModelViewMat /
        // iris_ModelViewMatrix are populated from it.
        final Matrix4f modelView = safeGet(() -> new Matrix4f(RenderSystem.getModelViewMatrixCopy()), "ModelViewMatrix");
        // MC 26.2 moved the projection matrix into a GPU buffer (RenderSystem.getProjectionMatrixBuffer());
        // there is no CPU-side Matrix4f accessor without Sodium's GameRendererStorage, so
        // iris_ProjMat / iris_ProjectionMatrix are left at zero (graceful M5e fallback).
        final Matrix4f projection = null;
        // MC 26.2 moved fog color into RenderSystem.getShaderFog() (a GpuBufferSlice, not float[]);
        // iris_FogColor is left at zero (graceful M5e fallback).
        final float[] fogColor = null;

        if (modelView != null) {
            putMat4(member("iris_ModelViewMat"), modelView);
            putMat4(member("iris_ModelViewMatrix"), modelView);
        }
        if (projection != null) {
            putMat4(member("iris_ProjMat"), projection);
            putMat4(member("iris_ProjectionMatrix"), projection);
        }
        if (fogColor != null && fogColor.length >= 3) {
            putVec4(member("iris_FogColor"),
                    fogColor[0], fogColor[1], fogColor[2],
                    fogColor.length >= 4 ? fogColor[3] : 1.0f);
        }

        // iris_ScreenSize — VanillaUniforms uses the main render target dims.
        try {
            final var rt = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            if (rt != null) {
                putVec2(member("iris_ScreenSize"), rt.width, rt.height);
            }
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] iris_ScreenSize not populated for '{}': {}",
                    programName, t.toString());
        }
    }

    @Nullable
    private LooseUniformMember member(final String name) {
        return layout.member(name);
    }

    private void putMat4(@Nullable final LooseUniformMember m, final Matrix4fc value) {
        if (m == null) {
            return;
        }
        try {
            final int base = m.offset();
            // Matrix4fc.get(float[]) returns column-major, 16 floats — exactly
            // the std140 mat4 layout.
            final float[] col = new float[16];
            value.get(col);
            for (int i = 0; i < 16; i++) {
                storage.putFloat(base + i * 4, col[i]);
            }
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] Failed to marshal mat4 '{}' for '{}': {}",
                    m.name(), programName, t.toString());
        }
    }

    private void putVec4(@Nullable final LooseUniformMember m, final float x, final float y, final float z, final float w) {
        if (m == null) {
            return;
        }
        try {
            final int base = m.offset();
            storage.putFloat(base, x);
            storage.putFloat(base + 4, y);
            storage.putFloat(base + 8, z);
            storage.putFloat(base + 12, w);
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] Failed to marshal vec4 '{}' for '{}': {}",
                    m.name(), programName, t.toString());
        }
    }

    private void putVec2(@Nullable final LooseUniformMember m, final float x, final float y) {
        if (m == null) {
            return;
        }
        try {
            final int base = m.offset();
            storage.putFloat(base, x);
            storage.putFloat(base + 4, y);
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] Failed to marshal vec2 '{}' for '{}': {}",
                    m.name(), programName, t.toString());
        }
    }

    private void zeroStorage() {
        for (int i = 0; i < storage.capacity(); i++) {
            storage.put(i, (byte) 0);
        }
    }

    /**
     * Invokes a value supplier defensively — returns {@code null} and logs at
     * debug level if the supplier throws (e.g. because Iris hasn't captured a
     * value yet this frame, or an API changed). The whole point is that a
     * single missing capture must not break the marshal of the other
     * uniforms.
     */
    @Nullable
    private static <T> T safeGet(final java.util.function.Supplier<T> supplier, final String label) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] Captured state '{}' not available: {}", label, t.toString());
            return null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            buffer.close();
        } catch (Exception e) {
            LOGGER.warn("[MetalUniversal] Error closing iris uniform buffer for '{}'", programName, e);
        }
    }
}
