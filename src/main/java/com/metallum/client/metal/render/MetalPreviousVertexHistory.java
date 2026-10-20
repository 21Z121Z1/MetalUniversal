package com.metallum.client.metal.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transactional history of the actual CPU-generated positions submitted by a staged draw.
 *
 * <p>Minecraft 26.3 keeps staged-draw vertex slices alive until freeVertexData during upload.
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

    /** CPU copies of the actual source draw's uniforms, before shader translation's clip-Y flip. */
    record DrawTransform(Matrix4f projection, Matrix4f modelView, Matrix4f unjitteredProjection) {
        DrawTransform {
            projection = new Matrix4f(projection);
            modelView = new Matrix4f(modelView);
            unjitteredProjection = new Matrix4f(unjitteredProjection);
        }

        DrawTransform(final Matrix4f projection, final Matrix4f modelView) {
            this(projection, modelView, projection);
        }

        @Override
        public Matrix4f projection() { return new Matrix4f(projection); }

        @Override
        public Matrix4f modelView() { return new Matrix4f(modelView); }

        @Override
        public Matrix4f unjitteredProjection() { return new Matrix4f(unjitteredProjection); }

        boolean finite() {
            return MetalFxMath.isFinite(projection) && MetalFxMath.isFinite(modelView) && MetalFxMath.isFinite(unjitteredProjection);
        }
    }

    private record Positions(float[] values) {
        Positions {
            values = values.clone();
        }
        @Override
        public float[] values() {
            return values.clone();
        }
    }

    record SurfaceIdentity(RenderPipeline pipeline, Map<String, TextureIdentity> textures) {
        SurfaceIdentity { textures = Map.copyOf(textures); }
    }

    record TextureIdentity(MetalAllocationIdentity allocation, int baseMip, int mipLevels,
                           com.mojang.renderpearl.api.textures.AddressMode addressU,
                           com.mojang.renderpearl.api.textures.AddressMode addressV,
                           com.mojang.renderpearl.api.textures.FilterMode minFilter,
                           com.mojang.renderpearl.api.textures.FilterMode magFilter,
                           int anisotropy, java.util.OptionalDouble maxLod) {}

    private record Snapshot(Signature signature, Positions positions, @Nullable DrawTransform transform,
                            @Nullable SurfaceIdentity surface, @Nullable List<Long> particleIds,
                            @Nullable MetalSourceUniforms sourceUniforms) {
        Snapshot withTransform(final @Nullable DrawTransform value) {
            return new Snapshot(signature, positions, value, surface, particleIds,sourceUniforms);
        }
        Snapshot withSurface(final @Nullable SurfaceIdentity value) {
            return new Snapshot(signature, positions, transform, value, particleIds,sourceUniforms);
        }
        Snapshot withSourceUniforms(final @Nullable MetalSourceUniforms value) {
            return new Snapshot(signature,positions,transform,surface,particleIds,value);
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
        final boolean measure = MetalFxMotionTelemetry.enabled();
        final long cpuStartNanos = measure ? System.nanoTime() : 0L;
        if (!frameOpen || token == null || format == null || topology == null
                || slices == null || slices.isEmpty() || vertexCount <= 0 || indexCount <= 0) {
            return;
        }
        ArrayList<ByteBuffer> buffers = new ArrayList<>(slices.size());
        for (ByteBufferBuilder.Result slice : slices) {
            if (slice == null) {
                return;
            }
            ByteBuffer buffer = slice.byteBuffer();
            buffers.add(buffer);
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
        if (measure) {
            long positionBytes = Math.multiplyExact((long) vertexCount, 3L * Float.BYTES);
            MetalFxMotionTelemetry.recordHistoryCapture(
                    vertexCount,
                    positionBytes,
                    positionBytes,
                    System.nanoTime() - cpuStartNanos
            );
        }
    }

    static void stageSnapshot(
            final @Nullable DrawToken token,
            final Signature signature,
            final float[] positions
    ) {
        if (frameOpen && token != null && signature != null && positions != null
                && positions.length == signature.vertexCount() * 3) {
            PENDING.put(token.key(), new Snapshot(signature, new Positions(positions), null, null, null,null));
        }
    }

    /** Exactly four vertices and six indices per actual SingleQuadParticle emission. */
    static void stageParticleIdentities(final @Nullable DrawToken token, final List<Long> ids) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot == null) return;
        Signature s = snapshot.signature();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id <= 0)
                || s.topology() != PrimitiveTopology.QUADS
                || !s.elements().equals(com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getElements())
                || s.vertexSize() != com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getVertexSize()
                || s.vertexCount() != (long)ids.size()*4 || s.indexCount() != (long)ids.size()*6) {
            PENDING.remove(token.key());
            return;
        }
        PENDING.put(token.key(), new Snapshot(s, snapshot.positions(), snapshot.transform(), snapshot.surface(), List.copyOf(ids),snapshot.sourceUniforms()));
    }

    /** Attaches uniforms to the same pending draw/geometry transaction, never a separate history. */
    static void stageDrawTransform(final @Nullable DrawToken token, final @Nullable DrawTransform transform) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot != null) {
            PENDING.put(token.key(), snapshot.withTransform(transform != null && transform.finite() ? transform : null));
        }
    }

    static void stageSurface(final @Nullable DrawToken token, final @Nullable SurfaceIdentity surface) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot != null) PENDING.put(token.key(), snapshot.withSurface(surface));
    }

    static void stageSourceUniforms(final @Nullable DrawToken token, final @Nullable MetalSourceUniforms uniforms) {
        Snapshot snapshot=frameOpen && token!=null ? PENDING.get(token.key()) : null;
        if(snapshot!=null)PENDING.put(token.key(),snapshot.withSourceUniforms(uniforms));
    }

    static @Nullable MetalSourceUniforms currentSourceUniforms(final @Nullable DrawToken token) {
        Snapshot snapshot=frameOpen && token!=null ? PENDING.get(token.key()) : null;
        return snapshot==null || snapshot.transform()==null ? null : snapshot.sourceUniforms();
    }

    static @Nullable MetalSourceUniforms matchedPreviousSourceUniforms(final @Nullable DrawToken token) {
        if(token==null || !objectManifestMatches(token.key().object()))return null;
        ObjectKey object=token.key().object();
        for(int ordinal=0;ordinal<CURRENT_DRAW_COUNTS.get(object);ordinal++) {
            DrawKey key=new DrawKey(object,ordinal);
            if(PENDING.get(key).sourceUniforms()==null || PREVIOUS.get(key).sourceUniforms()==null
                    || PENDING.get(key).transform()==null || PREVIOUS.get(key).transform()==null)return null;
        }
        return PREVIOUS.get(token.key()).sourceUniforms();
    }

    /** Visibility resources are checked after the complete source manifest has been staged. */
    static boolean matchingSurfaceManifest(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) return false;
        ObjectKey object = token.key().object();
        for (int ordinal = 0; ordinal < CURRENT_DRAW_COUNTS.get(object); ordinal++) {
            DrawKey key = new DrawKey(object, ordinal);
            SurfaceIdentity current = PENDING.get(key).surface(), previous = PREVIOUS.get(key).surface();
            if (current == null || !current.equals(previous)) return false;
        }
        return true;
    }

    static @Nullable DrawTransform currentDrawTransform(final @Nullable DrawToken token) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        return snapshot == null ? null : snapshot.transform();
    }

    /** Current ownership does not require a previous manifest, but must have captured real geometry. */
    static int currentVertexCount(final @Nullable DrawToken token) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        return snapshot == null || !snapshot.signature().pipelineKey().equals(token.pipelineKey())
                ? 0 : snapshot.signature().vertexCount();
    }

    /** A hand needs every draw's uniforms as well as its complete previous vertex manifest. */
    static @Nullable DrawTransform matchedPreviousDrawTransform(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) {
            return null;
        }
        ObjectKey object = token.key().object();
        for (int ordinal = 0; ordinal < CURRENT_DRAW_COUNTS.get(object); ordinal++) {
            DrawKey key = new DrawKey(object, ordinal);
            if (PENDING.get(key).transform() == null || PREVIOUS.get(key).transform() == null) {
                return null;
            }
        }
        Snapshot snapshot = PREVIOUS.get(token.key());
        return snapshot == null ? null : snapshot.transform();
    }

    /** Returns history only when every staged draw of the owning object has an exact match. */
    static @Nullable float[] matchedPreviousPositions(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) {
            return null;
        }
        Snapshot current = PENDING.get(token.key());
        Snapshot previous = PREVIOUS.get(token.key());
        if (current == null || previous == null || !compatible(current, previous)) {
            return null;
        }
        if (current.particleIds() != null) return remapParticlePositions(current, previous);
        return previous.positions().values();
    }

    private static boolean compatible(final Snapshot current, final Snapshot previous) {
        if (current.particleIds() == null && previous.particleIds() == null) {
            return current.signature().equals(previous.signature());
        }
        if (current.particleIds() == null || previous.particleIds() == null) return false;
        Signature a = current.signature(), b = previous.signature();
        return a.pipelineKey().equals(b.pipelineKey()) && a.elements().equals(b.elements())
                && a.vertexSize() == b.vertexSize() && a.topology() == b.topology();
    }

    private static float[] remapParticlePositions(final Snapshot current, final Snapshot previous) {
        Map<Long, List<Integer>> previousQuads = new HashMap<>();
        for (int i = 0; i < previous.particleIds().size(); i++) {
            previousQuads.computeIfAbsent(previous.particleIds().get(i), ignored -> new ArrayList<>()).add(i);
        }
        Map<Long, Integer> currentCounts = new HashMap<>(), occurrences = new HashMap<>();
        current.particleIds().forEach(id -> currentCounts.merge(id, 1, Integer::sum));
        float[] output = new float[current.signature().vertexCount()*3];
        java.util.Arrays.fill(output, Float.NaN);
        for (int i = 0; i < current.particleIds().size(); i++) {
            long id = current.particleIds().get(i);
            int occurrence = occurrences.merge(id, 1, Integer::sum)-1;
            List<Integer> old = previousQuads.get(id);
            // One particle can emit multiple quads. Preserve their emission order only
            // if its emission count is unchanged; a changed count is ambiguous for that
            // particle, without invalidating unrelated particles in the same layer.
            if (old != null && old.size() == currentCounts.get(id)) {
                System.arraycopy(previous.positions.values, old.get(occurrence)*12, output, i*12, 12);
            }
        }
        return output;
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
            if (current == null || previous == null || !compatible(current, previous)) {
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
        // Snapshots, positions, transforms and resource identities are immutable. Advancing the
        // transaction transfers references; attaching uniforms must not recopy every posed vertex.
        PREVIOUS.putAll(PENDING);
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
