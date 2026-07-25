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
 *   <li>{@code iris_NormalMat} &larr; inverse-transpose of the model-view
 *       matrix's upper-left 3×3 (computed via JOML, same as Iris's
 *       ExtendedShader)</li>
 *   <li>{@code iris_ProjMat} / {@code iris_ProjectionMatrix} &larr;
 *       {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline#getCapturedProjection()}
 *       (read from Iris's {@code CapturedRenderingState}, captured by
 *       {@code MixinLevelRenderer})</li>
 *   <li>{@code iris_FogColor} &larr;
 *       {@link com.metallum.client.metal.iris.MetalIrisRenderingPipeline#getCapturedFogColor()}
 *       (read from Iris's {@code CapturedRenderingState}, captured by
 *       {@code MixinFogRenderer})</li>
 *   <li>{@code iris_ScreenSize} &larr;
 *       {@code Minecraft.getInstance().gameRenderer.mainRenderTarget()} width/height</li>
 * </ul>
 *
 * <p>Other loose uniforms ({@code iris_TextureMat},
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
        // Zero the FULL buffer once at construction (including alignment
        // padding beyond layout.totalSize()) so unmapped members and padding
        // read as zero before the first marshal() call. marshal()'s
        // zeroStorage() only zeroes the data region (M6-3 optimization).
        zeroFullBuffer();
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
        // M5e+: projection matrix from Iris's CapturedRenderingState (captured by
        // MixinLevelRenderer from GameRendererStorage.sodium$getProjectionMatrix()).
        final Matrix4f projection = MetalIrisRenderer.getCapturedProjection();
        // M5e+: fog color from Iris's CapturedRenderingState (captured by MixinFogRenderer).
        final float[] fogColor = MetalIrisRenderer.getCapturedFogColor();

        if (modelView != null) {
            putMat4(member("iris_ModelViewMat"), modelView);
            putMat4(member("iris_ModelViewMatrix"), modelView);

            // M5e+: iris_NormalMat = inverse-transpose of the upper-left 3×3 of
            // the model-view matrix. Computed the same way as Iris's
            // ExtendedShader: modelView.invert().transpose3x3(Matrix3f).
            try {
                final Matrix4f inv = new Matrix4f(modelView).invert();
                final org.joml.Matrix3f normalMat = new org.joml.Matrix3f();
                inv.transpose3x3(normalMat);
                final float[] normalMat3 = new float[9];
                normalMat.get(normalMat3);
                putMat3(member("iris_NormalMat"), normalMat3);
            } catch (Throwable t) {
                LOGGER.debug("[MetalUniversal] iris_NormalMat not populated for '{}': {}",
                        programName, t.toString());
            }
        }
        if (projection != null) {
            putMat4(member("iris_ProjMat"), projection);
            putMat4(member("iris_ProjectionMatrix"), projection);
        }
        if (fogColor != null && fogColor.length >= 3) {
            putVec4(member("iris_FogColor"),
                    fogColor[0], fogColor[1], fogColor[2], 1.0f);
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

        // M5e+: iris_ColorModulator default is (1,1,1,1) per Iris's
        // ShaderCreator. At zero, all rendered geometry would be black.
        // MC 26.2 has no CPU-side getter for the shader color (it was moved
        // to a GPU buffer), so we use the default. This matches Iris's own
        // fallback when no ExtendedShader sets it.
        putVec4(member("iris_ColorModulator"), 1.0f, 1.0f, 1.0f, 1.0f);

        // M5e+: iris_TextureMat default is identity per Iris's ShaderCreator.
        // At zero, all texture coordinates would be (0,0). MC 26.2 has no
        // CPU-side getter for the texture matrix, so we use identity.
        final LooseUniformMember texMatMember = member("iris_TextureMat");
        if (texMatMember != null) {
            try {
                final int base = texMatMember.offset();
                // Identity matrix in column-major float order:
                // 1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1
                storage.putFloat(base, 1.0f);
                storage.putFloat(base + 4, 0.0f);
                storage.putFloat(base + 8, 0.0f);
                storage.putFloat(base + 12, 0.0f);
                storage.putFloat(base + 16, 0.0f);
                storage.putFloat(base + 20, 1.0f);
                storage.putFloat(base + 24, 0.0f);
                storage.putFloat(base + 28, 0.0f);
                storage.putFloat(base + 32, 0.0f);
                storage.putFloat(base + 36, 0.0f);
                storage.putFloat(base + 40, 1.0f);
                storage.putFloat(base + 44, 0.0f);
                storage.putFloat(base + 48, 0.0f);
                storage.putFloat(base + 52, 0.0f);
                storage.putFloat(base + 56, 0.0f);
                storage.putFloat(base + 60, 1.0f);
            } catch (Throwable t) {
                LOGGER.debug("[MetalUniversal] iris_TextureMat not populated for '{}': {}",
                        programName, t.toString());
            }
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

    /**
     * Writes a std140 mat3 (9 floats, but padded to 48 bytes = 3 vec4 columns
     * in std140 layout). M5e+.
     */
    private void putMat3(@Nullable final LooseUniformMember m, final float[] col3x3) {
        if (m == null || col3x3 == null || col3x3.length < 9) {
            return;
        }
        try {
            final int base = m.offset();
            // std140 mat3 is stored as 3 column vectors, each padded to vec4
            // (16 bytes). Column 0 = (m[0], m[1], m[2], 0),
            // Column 1 = (m[3], m[4], m[5], 0),
            // Column 2 = (m[6], m[7], m[8], 0).
            for (int col = 0; col < 3; col++) {
                final int colBase = base + col * 16; // each column is 16 bytes
                storage.putFloat(colBase, col3x3[col * 3]);
                storage.putFloat(colBase + 4, col3x3[col * 3 + 1]);
                storage.putFloat(colBase + 8, col3x3[col * 3 + 2]);
                storage.putFloat(colBase + 12, 0.0f); // padding
            }
        } catch (Throwable t) {
            LOGGER.debug("[MetalUniversal] Failed to marshal mat3 '{}' for '{}': {}",
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

    /**
     * Zeros the uniform buffer storage in bulk (M6-3). The previous
     * implementation looped byte-by-byte which was O(n) per frame with poor
     * cache utilisation. This version fills 8 bytes (long) at a time, then
     * handles any trailing bytes individually — ~8× faster for typical UBO
     * sizes (256–1024 bytes).
     *
     * <p>Only zeroes up to {@link #layout#totalSize()} (the actual uniform
     * data region), not the full buffer capacity (which may include 16-byte
     * alignment padding). The padding region is zeroed once at construction
     * and never written again.
     */
    private void zeroStorage() {
        final int size = Math.min(layout.totalSize(), storage.capacity());
        // Fast path: fill 8 bytes at a time.
        int i = 0;
        while (i + 8 <= size) {
            storage.putLong(i, 0L);
            i += 8;
        }
        // Trailing bytes (< 8).
        while (i < size) {
            storage.put(i, (byte) 0);
            i++;
        }
    }

    /**
     * Zeros the entire buffer including alignment padding (M6-3). Called once
     * at construction. Uses the same 8-byte-bulk approach as
     * {@link #zeroStorage()} but covers the full {@code storage.capacity()}.
     */
    private void zeroFullBuffer() {
        final int size = storage.capacity();
        int i = 0;
        while (i + 8 <= size) {
            storage.putLong(i, 0L);
            i += 8;
        }
        while (i < size) {
            storage.put(i, (byte) 0);
            i++;
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
