package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transactional history of the actual CPU-generated positions submitted by a staged draw.
 *
 * <p>Minecraft 26.2 keeps staged-draw vertex slices alive until freeVertexData during upload.
 * Capturing only Position at that boundary gives the next successful source frame the exact
 * already-posed vertices without reimplementing living animation, boat paddles, or other CPU
 * deformation.</p>
 *
 * <p>Pending positions replace previous positions only after the source frame was accepted. A
 * complete per-object draw manifest must also match before a previous stream can be consumed;
 * this prevents an optional feature draw from shifting ordinal correspondence.</p>
 */
@Environment(EnvType.CLIENT)
final class MetalPreviousVertexHistory {
    record ObjectKey(long objectId, long generation) {
    }

    record DrawKey(ObjectKey object, int ordinal) {
    }

    record DrawToken(DrawKey key, String pipelineKey) {
    }

    record CameraPosition(double x, double y, double z) {
        boolean finite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }

    record Signature(
            String pipelineKey,
            List<VertexFormatElement> elements,
            int vertexSize,
            PrimitiveTopology topology,
            int vertexCount,
            int indexCount
    ) {
        Signature {
            elements = List.copyOf(elements);
        }
    }

    record Snapshot(Signature signature, float[] positions) {
        Snapshot {
            positions = positions.clone();
        }

        @Override
        public float[] positions() {
            return positions.clone();
        }

        Snapshot copy() {
            return new Snapshot(signature, positions);
        }
    }

    private static final Map<DrawKey, Snapshot> PREVIOUS = new HashMap<>();
    private static final Map<DrawKey, Snapshot> PENDING = new HashMap<>();
    private static final Map<ObjectKey, Integer> PREVIOUS_DRAW_COUNTS = new HashMap<>();
    private static final Map<ObjectKey, Integer> CURRENT_DRAW_COUNTS = new HashMap<>();
    private static @Nullable CameraPosition previousCamera;
    private static @Nullable CameraPosition pendingCamera;
    private static boolean frameOpen;

    private MetalPreviousVertexHistory() {
    }

    static void beginFrame() {
        PENDING.clear();
        CURRENT_DRAW_COUNTS.clear();
        pendingCamera = null;
        frameOpen = true;
    }

    static void observeCamera(final double x, final double y, final double z) {
        CameraPosition camera = new CameraPosition(x, y, z);
        if (frameOpen && camera.finite()) {
            pendingCamera = camera;
        }
    }

    static @Nullable CameraPosition previousCamera() {
        return previousCamera;
    }

    static @Nullable CameraPosition currentCamera() {
        return pendingCamera;
    }

    static @Nullable DrawToken reserveDraw(
            final MetalEntityMotionCapture.Sample sample,
            final RenderPipeline pipeline
    ) {
        if (!frameOpen || sample == null || pipeline == null) {
            return null;
        }
        ObjectKey object = new ObjectKey(sample.objectId(), sample.generation());
        int ordinal = CURRENT_DRAW_COUNTS.getOrDefault(object, 0);
        CURRENT_DRAW_COUNTS.put(object, ordinal + 1);
        return new DrawToken(new DrawKey(object, ordinal), pipeline.getLocation().toString());
    }

    static void capture(
            final @Nullable DrawToken token,
            final VertexFormat format,
            final PrimitiveTopology topology,
            final List<ByteBufferBuilder.Result> slices,
            final int vertexCount,
            final int indexCount
    ) {
        if (!frameOpen || token == null || format == null || topology == null
                || slices == null || slices.isEmpty() || vertexCount <= 0 || indexCount <= 0) {
            return;
        }
        ArrayList<ByteBuffer> buffers = new ArrayList<>(slices.size());
        for (ByteBufferBuilder.Result slice : slices) {
            if (slice == null) {
                return;
            }
            buffers.add(slice.byteBuffer());
        }
        float[] positions = extractPositions(format, buffers, vertexCount);
        if (positions == null) {
            return;
        }
        Signature signature = new Signature(
                token.pipelineKey(),
                format.getElements(),
                format.getVertexSize(),
                topology,
                vertexCount,
                indexCount
        );
        stageSnapshot(token, signature, positions);
    }

    static void stageSnapshot(
            final @Nullable DrawToken token,
            final Signature signature,
            final float[] positions
    ) {
        if (frameOpen && token != null && signature != null && positions != null
                && positions.length == signature.vertexCount() * 3) {
            PENDING.put(token.key(), new Snapshot(signature, positions));
        }
    }

    /** Returns history only when every staged draw of the owning object has an exact match. */
    static @Nullable float[] matchedPreviousPositions(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) {
            return null;
        }
        Snapshot current = PENDING.get(token.key());
        Snapshot previous = PREVIOUS.get(token.key());
        if (current == null || previous == null || !current.signature().equals(previous.signature())) {
            return null;
        }
        return previous.positions();
    }

    static int matchingManifestDrawCount(final long objectId, final long generation) {
        ObjectKey object = new ObjectKey(objectId, generation);
        return objectManifestMatches(object) ? CURRENT_DRAW_COUNTS.getOrDefault(object, 0) : -1;
    }

    static boolean objectManifestMatches(final ObjectKey object) {
        int currentCount = CURRENT_DRAW_COUNTS.getOrDefault(object, 0);
        if (currentCount <= 0 || currentCount != PREVIOUS_DRAW_COUNTS.getOrDefault(object, -1)) {
            return false;
        }
        for (int ordinal = 0; ordinal < currentCount; ordinal++) {
            DrawKey key = new DrawKey(object, ordinal);
            Snapshot current = PENDING.get(key);
            Snapshot previous = PREVIOUS.get(key);
            if (current == null || previous == null || !current.signature().equals(previous.signature())) {
                return false;
            }
        }
        return true;
    }

    static void commitSubmittedFrame() {
        if (!frameOpen) {
            return;
        }
        PREVIOUS.clear();
        for (Map.Entry<DrawKey, Snapshot> entry : PENDING.entrySet()) {
            PREVIOUS.put(entry.getKey(), entry.getValue().copy());
        }
        PREVIOUS_DRAW_COUNTS.clear();
        PREVIOUS_DRAW_COUNTS.putAll(CURRENT_DRAW_COUNTS);
        previousCamera = pendingCamera;
        PENDING.clear();
        CURRENT_DRAW_COUNTS.clear();
        pendingCamera = null;
        frameOpen = false;
    }

    static void discardFrame() {
        PENDING.clear();
        CURRENT_DRAW_COUNTS.clear();
        pendingCamera = null;
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        PREVIOUS.clear();
        PREVIOUS_DRAW_COUNTS.clear();
        PENDING.clear();
        CURRENT_DRAW_COUNTS.clear();
        previousCamera = null;
        pendingCamera = null;
        frameOpen = wasOpen;
    }

    /** Extracts packed float3 Position values without assuming Position is at byte offset zero. */
    static @Nullable float[] extractPositions(
            final VertexFormat format,
            final Iterable<ByteBuffer> buffers,
            final int expectedVertexCount
    ) {
        if (format == null || buffers == null || expectedVertexCount <= 0 || format.getStepRate() != 0) {
            return null;
        }
        VertexFormatElement position = format.getElement("Position");
        if (position == null || position.format() != GpuFormat.RGB32_FLOAT) {
            return null;
        }
        int stride = format.getVertexSize();
        int positionOffset = position.offset();
        if (stride <= 0 || positionOffset < 0 || positionOffset + 12 > stride) {
            return null;
        }

        float[] output = new float[Math.multiplyExact(expectedVertexCount, 3)];
        int writtenVertices = 0;
        for (ByteBuffer source : buffers) {
            if (source == null) {
                return null;
            }
            ByteBuffer bytes = source.duplicate().order(ByteOrder.nativeOrder());
            int remaining = bytes.remaining();
            if (remaining % stride != 0) {
                return null;
            }
            int vertices = remaining / stride;
            if (writtenVertices + vertices > expectedVertexCount) {
                return null;
            }
            int base = bytes.position();
            for (int vertex = 0; vertex < vertices; vertex++) {
                int offset = base + vertex * stride + positionOffset;
                float x = bytes.getFloat(offset);
                float y = bytes.getFloat(offset + 4);
                float z = bytes.getFloat(offset + 8);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                    return null;
                }
                int outputOffset = (writtenVertices + vertex) * 3;
                output[outputOffset] = x;
                output[outputOffset + 1] = y;
                output[outputOffset + 2] = z;
            }
            writtenVertices += vertices;
        }
        return writtenVertices == expectedVertexCount ? output : null;
    }
}
