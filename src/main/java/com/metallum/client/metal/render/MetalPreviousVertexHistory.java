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
 * Capturing Position at that boundary gives the next successful source frame the exact
 * already-posed vertices without reimplementing living animation, boat paddles, or other CPU
 * deformation.</p>
 *
 * <p>Pending positions replace previous positions only after the source frame was accepted. A
 * complete per-object draw manifest must also match before a previous stream can be consumed;
 * this prevents an optional feature draw from shifting ordinal correspondence. Explicit particle
 * IDs may cross group partitions only with a complete, compatible old transform/material/uniform
 * proof for each current draw. Other producer manifests retain ordinal correspondence.</p>
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

    record SurfaceIdentity(RenderPipeline pipeline, Map<String, TextureIdentity> textures, boolean depthAttachmentPresent,
                           MetalPreviousVertexReplay.RasterState raster) {
        SurfaceIdentity(RenderPipeline pipeline,Map<String,TextureIdentity> textures,boolean depthAttachmentPresent) {
            this(pipeline,textures,depthAttachmentPresent,MetalPreviousVertexReplay.RasterState.UNBIASED);
        }
        SurfaceIdentity(RenderPipeline pipeline,Map<String,TextureIdentity> textures) {this(pipeline,textures,true);}
        SurfaceIdentity { textures = Map.copyOf(textures); }
    }

    record TextureIdentity(MetalAllocationIdentity allocation, int baseMip, int mipLevels,
                           com.mojang.renderpearl.api.textures.AddressMode addressU,
                           com.mojang.renderpearl.api.textures.AddressMode addressV,
                           com.mojang.renderpearl.api.textures.FilterMode minFilter,
                           com.mojang.renderpearl.api.textures.FilterMode magFilter,
                           int anisotropy, java.util.OptionalDouble maxLod) {}

    /** Actual unsigned vertex bounds plus the same source's uniform witness; not visibility proof. */
    record ParticleAlpha(int min, int max, boolean unitModulator) {
        ParticleAlpha withUniforms(final @Nullable MetalSourceUniforms uniforms) {
            return new ParticleAlpha(min,max,isUnitParticleModulator(uniforms));
        }
        boolean fullSourceInputs() {return min==255 && max==255 && unitModulator;}
    }

    private record Snapshot(Signature signature, Positions positions, @Nullable DrawTransform transform,
                            @Nullable SurfaceIdentity surface, @Nullable List<Long> particleIds,
                            @Nullable MetalSourceUniforms sourceUniforms, boolean particleBatch,
                            @Nullable ParticleAlpha particleAlpha) {
        Snapshot withTransform(final @Nullable DrawTransform value) {
            return new Snapshot(signature, positions, value, surface, particleIds,sourceUniforms,particleBatch,particleAlpha);
        }
        Snapshot withSurface(final @Nullable SurfaceIdentity value) {
            return new Snapshot(signature, positions, transform, value, particleIds,sourceUniforms,particleBatch,particleAlpha);
        }
        Snapshot withSourceUniforms(final @Nullable MetalSourceUniforms value) {
            return new Snapshot(signature,positions,transform,surface,particleIds,value,particleBatch,
                    particleAlpha==null ? null : particleAlpha.withUniforms(value));
        }
    }

    private static final Map<DrawKey, Snapshot> PREVIOUS = new HashMap<>();
    private static final Map<DrawKey, Snapshot> PENDING = new HashMap<>();
    private static final Map<ObjectKey, Map<DrawKey, Snapshot>> PARTICLE_MATCHES = new HashMap<>();
    private static final Map<ObjectKey, Integer> PREVIOUS_DRAW_COUNTS = new HashMap<>();
    private static final Map<ObjectKey, Integer> CURRENT_DRAW_COUNTS = new HashMap<>();
    private static @Nullable CameraPosition previousCamera;
    private static @Nullable CameraPosition pendingCamera;
    private static boolean frameOpen;

    private MetalPreviousVertexHistory() {
    }

    static void beginFrame() {
        PENDING.clear();
        PARTICLE_MATCHES.clear();
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
        PARTICLE_MATCHES.remove(object);
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
        // Freeze the actual packed alpha before freeVertexData releases these slices. Never
        // reconstruct old evidence from the current upload, nor retain the source allocation.
        ParticleAlpha alpha = captureParticleAlpha(format, buffers);
        stagePending(token.key(), new Snapshot(signature, new Positions(positions), null, null, null, null, false, alpha));
        if (measure) {
            long positionBytes = Math.multiplyExact((long) vertexCount, 3L * Float.BYTES);
            MetalFxMotionTelemetry.recordHistoryCapture(
                    vertexCount,
                    positionBytes + (alpha == null ? 0L : vertexCount),
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
            stagePending(token.key(), new Snapshot(signature, new Positions(positions), null, null, null,null,false,null));
        } else if (frameOpen && token != null) {
            PENDING.remove(token.key());
            PARTICLE_MATCHES.remove(token.key().object());
        }
    }

    /** Exactly four vertices and six indices per actual SingleQuadParticle emission. */
    static void stageParticleIdentities(final @Nullable DrawToken token, final List<Long> ids) {
        stageQuadIdentities(token,ids,true);
    }

    /** Direct weather/cloud quads provide persistent identities, including signed column coordinates. */
    static void stageQuadIdentities(final @Nullable DrawToken token,final List<Long> ids) {
        stageQuadIdentities(token,ids,false);
    }

    private static void stageQuadIdentities(final @Nullable DrawToken token,final List<Long> ids,final boolean positiveOnly) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot == null) return;
        Signature s = snapshot.signature();
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> id == null || (positiveOnly && id <= 0))
                || s.topology() != PrimitiveTopology.QUADS
                || !(s.elements().equals(com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getElements())
                        && s.vertexSize()==com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE.getVertexSize()
                    || !positiveOnly && s.elements().equals(com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR.getElements())
                        && s.vertexSize()==com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR.getVertexSize())
                || s.vertexCount() != (long)ids.size()*4 || s.indexCount() != (long)ids.size()*6) {
            PENDING.remove(token.key());
            PARTICLE_MATCHES.remove(token.key().object());
            return;
        }
        stagePending(token.key(), new Snapshot(s, snapshot.positions(), snapshot.transform(), snapshot.surface(),
                List.copyOf(ids),snapshot.sourceUniforms(),positiveOnly,snapshot.particleAlpha()));
    }

    /** Attaches uniforms to the same pending draw/geometry transaction, never a separate history. */
    static void stageDrawTransform(final @Nullable DrawToken token, final @Nullable DrawTransform transform) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot != null) {
            stagePending(token.key(), snapshot.withTransform(transform != null && transform.finite() ? transform : null));
        }
    }

    static void stageSurface(final @Nullable DrawToken token, final @Nullable SurfaceIdentity surface) {
        Snapshot snapshot = frameOpen && token != null ? PENDING.get(token.key()) : null;
        if (snapshot != null) stagePending(token.key(), snapshot.withSurface(surface));
    }

    static void stageSourceUniforms(final @Nullable DrawToken token, final @Nullable MetalSourceUniforms uniforms) {
        Snapshot snapshot=frameOpen && token!=null ? PENDING.get(token.key()) : null;
        if(snapshot!=null)stagePending(token.key(),snapshot.withSourceUniforms(uniforms));
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
            if(PENDING.get(key).sourceUniforms()==null || previousFor(key).sourceUniforms()==null
                    || PENDING.get(key).transform()==null || previousFor(key).transform()==null)return null;
        }
        return previousFor(token.key()).sourceUniforms();
    }

    static @Nullable ParticleAlpha matchedPreviousParticleAlpha(final @Nullable DrawToken token) {
        return token != null && objectManifestMatches(token.key().object()) ? previousFor(token.key()).particleAlpha() : null;
    }

    static boolean isUnitParticleModulator(final @Nullable MetalSourceUniforms uniforms) {
        if(uniforms==null)return false;
        float alpha=uniforms.colorModulator().w;
        // Only exactly unit alpha is audited here. Do not clamp partial, negative or >1 inputs.
        return Float.isFinite(alpha) && alpha==1;
    }

    /** Visibility resources are checked after the complete source manifest has been staged. */
    static boolean matchingSurfaceManifest(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) return false;
        ObjectKey object = token.key().object();
        for (int ordinal = 0; ordinal < CURRENT_DRAW_COUNTS.get(object); ordinal++) {
            DrawKey key = new DrawKey(object, ordinal);
            SurfaceIdentity current = PENDING.get(key).surface(), previous = previousFor(key).surface();
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
            if (PENDING.get(key).transform() == null || previousFor(key).transform() == null) {
                return null;
            }
        }
        Snapshot snapshot = previousFor(token.key());
        return snapshot == null ? null : snapshot.transform();
    }

    /** Returns history only when every staged draw of the owning object has an exact match. */
    static @Nullable float[] matchedPreviousPositions(final @Nullable DrawToken token) {
        if (token == null || !objectManifestMatches(token.key().object())) {
            return null;
        }
        Snapshot current = PENDING.get(token.key());
        Snapshot remapped = particleMatches(token.key().object()).get(token.key());
        if (remapped != null) return remapped.positions().values();
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
        if (currentCount <= 0) return false;
        if (particleMatches(object).size() == currentCount) return true;
        if (currentCount != PREVIOUS_DRAW_COUNTS.getOrDefault(object, -1)) return false;
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

    private static void stagePending(final DrawKey key, final Snapshot snapshot) {
        PENDING.put(key, snapshot);
        PARTICLE_MATCHES.remove(key.object());
    }

    /** Caller first proves the complete manifest; ordinal fallback never crosses a group. */
    private static Snapshot previousFor(final DrawKey key) {
        Snapshot remapped = particleMatches(key.object()).get(key);
        return remapped != null ? remapped : java.util.Objects.requireNonNull(PREVIOUS.get(key), "Missing proven previous draw");
    }

    private static Map<DrawKey, Snapshot> particleMatches(final ObjectKey object) {
        Map<DrawKey, Snapshot> cached = PARTICLE_MATCHES.get(object);
        if (cached != null) return cached;
        Map<DrawKey, Snapshot> matches = new HashMap<>();
        var current = particleBatches(object, PENDING, CURRENT_DRAW_COUNTS.getOrDefault(object, 0));
        var previous = particleBatches(object, PREVIOUS, PREVIOUS_DRAW_COUNTS.getOrDefault(object, 0));
        if (current != null && previous != null) {
            var resolved = MetalParticleQuadHistory.remap(previous, current,
                    (fresh, old) -> compatible(fresh, old) && fresh.transform() != null && old.transform() != null
                            && fresh.sourceUniforms() != null && old.sourceUniforms() != null
                            && fresh.surface() != null && fresh.surface().equals(old.surface()),
                    (a, b) -> a.transform().equals(b.transform()) && a.surface().equals(b.surface())
                            && a.sourceUniforms().sameHistoryInputs(b.sourceUniforms()));
            for (int ordinal = 0; ordinal < resolved.size(); ordinal++) {
                var match = resolved.get(ordinal);
                if (match.isEmpty()) continue;
                Snapshot fresh = current.get(ordinal).proof(), old = match.get().previousProof();
                matches.put(new DrawKey(object, ordinal), new Snapshot(fresh.signature(), new Positions(match.get().positions()),
                        old.transform(), old.surface(), fresh.particleIds(), old.sourceUniforms(), true,
                        combinedParticleAlpha(match.get().previousSources())));
            }
        }
        Map<DrawKey, Snapshot> immutable = Map.copyOf(matches);
        PARTICLE_MATCHES.put(object, immutable);
        return immutable;
    }

    /** All actual old sources must retain their bounds and unit uniform witness after an ID remap. */
    private static @Nullable ParticleAlpha combinedParticleAlpha(final List<Snapshot> sources) {
        if(sources.isEmpty())return null;
        int min=255,max=0;boolean unit=true;
        for (Snapshot source : sources) {
            if (source.particleAlpha() == null) return null;
            var alpha=source.particleAlpha();
            min=Math.min(min,alpha.min());max=Math.max(max,alpha.max());unit&=alpha.unitModulator();
        }
        return new ParticleAlpha(min,max,unit);
    }

    private static @Nullable ParticleAlpha captureParticleAlpha(final VertexFormat format, final List<ByteBuffer> buffers) {
        var particle = com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE;
        var color = format.getElement("Color");
        if (!format.getElements().equals(particle.getElements()) || format.getVertexSize() != particle.getVertexSize()
                || color == null || color.format() != GpuFormat.RGBA8_UNORM) return null;
        // extractPositions has already validated every complete slice and the total vertex count.
        int min=255,max=0;
        for (ByteBuffer buffer : buffers) {
            for (int offset = buffer.position() + color.offset() + 3; offset < buffer.limit(); offset += format.getVertexSize()) {
                int alpha=Byte.toUnsignedInt(buffer.get(offset));
                min=Math.min(min,alpha);max=Math.max(max,alpha);
            }
        }
        return new ParticleAlpha(min,max,false);
    }

    private static @Nullable List<MetalParticleQuadHistory.Batch<Snapshot>> particleBatches(
            final ObjectKey object, final Map<DrawKey, Snapshot> snapshots, final int count) {
        if (count <= 0) return null;
        var batches = new ArrayList<MetalParticleQuadHistory.Batch<Snapshot>>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            Snapshot snapshot = snapshots.get(new DrawKey(object, ordinal));
            // Explicit SingleQuadParticle identities only. Weather/cloud and entity
            // manifests retain their existing complete ordinal/layout contract.
            if (snapshot == null || !snapshot.particleBatch() || snapshot.particleIds() == null) return null;
            batches.add(new MetalParticleQuadHistory.Batch<>(snapshot.particleIds(), snapshot.positions().values(), snapshot));
        }
        return batches;
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
        PARTICLE_MATCHES.clear();
        CURRENT_DRAW_COUNTS.clear();
        pendingCamera = null;
        frameOpen = false;
    }

    static void discardFrame() {
        PENDING.clear();
        PARTICLE_MATCHES.clear();
        CURRENT_DRAW_COUNTS.clear();
        pendingCamera = null;
        frameOpen = false;
    }

    static void reset() {
        boolean wasOpen = frameOpen;
        PREVIOUS.clear();
        PREVIOUS_DRAW_COUNTS.clear();
        PENDING.clear();
        PARTICLE_MATCHES.clear();
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
