package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.SectionRenderDataStorageAccessor;
import com.metallum.mixin.sodium.SectionRenderDataStorageOwner;
import com.metallum.mixin.sodium.GlBufferSegmentAccessor;
import com.metallum.mixin.sodium.GlBufferSegmentGeneration;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import com.mojang.blaze3d.buffers.GpuBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains only mesh-ready Sodium terrain candidates.  It is lazy and
 * completely inert unless the explicit candidate-snapshot switch is true.
 */
public final class TerrainCandidateRegistry {
    public static final String PROPERTY = "metallum.opt.terrainCandidateSnapshot";
    public static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(PROPERTY, "false")
    );

    private static StateMachine state;
    private static volatile TerrainCandidateSnapshot latest;
    private static TerrainCandidateSnapshot.AllocationIdentity opaqueSharedIndex;

    private TerrainCandidateRegistry() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Called at setupTerrain HEAD, before Sodium schedules/consumes cull work. */
    public static void captureFrame(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final Matrix4fc cullMatrix
    ) {
        if (!ENABLED) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            StateMachine machine = state();
            machine.refreshAll();
            latest = machine.snapshot(
                    new TerrainCandidateSnapshot.CameraPosition(cameraX, cameraY, cameraZ),
                    TerrainCandidateSnapshot.VisibilityTransform.copyOf(cullMatrix)
            );
        }
    }

    public static TerrainCandidateSnapshot latestSnapshot() {
        return ENABLED ? latest : null;
    }

    /** Clears all world-owned state when Sodium changes or unloads its level. */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            state = null;
            latest = null;
            opaqueSharedIndex = null;
        }
    }

    static boolean statePresentForContractTest() {
        synchronized (TerrainCandidateRegistry.class) {
            return state != null;
        }
    }

    static boolean opaqueIdentityPresentForContractTest() {
        synchronized (TerrainCandidateRegistry.class) {
            return opaqueSharedIndex != null;
        }
    }

    static void installOpaqueIdentityForContractTest(
            final TerrainCandidateSnapshot.AllocationIdentity identity
    ) {
        if (!ENABLED) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            opaqueSharedIndex = identity;
        }
    }

    /** Sodium's opaque pass uses this renderer-owned shared index buffer. */
    public static void onOpaqueSharedIndexBuffer(final GpuBuffer buffer) {
        if (!ENABLED) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            if (!(buffer instanceof MetalGpuBuffer metalBuffer)
                    || metalBuffer.isClosed() || metalBuffer.size() <= 0L) {
                opaqueSharedIndex = null;
            } else {
                try {
                    MetalAllocationIdentity backing = metalBuffer.allocationIdentity();
                    opaqueSharedIndex = new TerrainCandidateSnapshot.AllocationIdentity(
                            metalBuffer, 0L, metalBuffer.size(), backing.generation(), backing
                    );
                } catch (RuntimeException exception) {
                    opaqueSharedIndex = null;
                }
            }
            if (state != null) {
                state.refreshAll();
            }
        }
    }

    public static void onSectionAdded(final RenderSection section) {
        if (!ENABLED || section == null) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            state().onSectionAdded(keyOf(section));
        }
    }

    public static void onSectionInfo(final RenderSection section, final BuiltSectionInfo info) {
        if (!ENABLED || section == null) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            StateMachine machine = state();
            SectionKey key = keyOf(section);
            if (info == null || info == BuiltSectionInfo.EMPTY || RenderSectionFlags.isInvisible(info.flags)) {
                machine.onNotReady(key);
                return;
            }
            machine.onBuilt(key);
        }
    }

    public static void onSectionRemoved(final RenderSection section) {
        if (!ENABLED || section == null) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            state().onSectionRemoved(keyOf(section));
        }
    }

    public static void onStorageMutation(final SectionRenderDataStorage storage, final int localIndex) {
        if (!ENABLED || storage == null || !(storage instanceof SectionRenderDataStorageOwner owner)
                || !owner.metallum$hasOwner()) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            StateMachine machine = state();
            if (localIndex >= 0) {
                refresh(machine, owner, storage, localIndex);
            } else {
                for (int index = 0; index < 256; index++) {
                    refresh(machine, owner, storage, index);
                }
            }
        }
    }

    public static void onStorageDeleted(final SectionRenderDataStorage storage) {
        if (!ENABLED || storage == null) {
            return;
        }
        synchronized (TerrainCandidateRegistry.class) {
            state().onStorageDeleted(storage);
        }
    }

    private static StateMachine state() {
        if (state == null) {
            state = new StateMachine();
        }
        return state;
    }

    private static SectionKey keyOf(final RenderSection section) {
        var region = section.getRegion();
        int localIndex = section.getSectionIndex();
        return new SectionKey(
                region.getX(), region.getY(), region.getZ(), localIndex,
                section.getChunkX(), section.getChunkY(), section.getChunkZ()
        );
    }

    private static void refresh(
            final StateMachine machine,
            final SectionKey key,
            final SectionRenderDataStorage storage
    ) {
        if (storage instanceof SectionRenderDataStorageOwner owner && owner.metallum$hasOwner()) {
            refresh(machine, owner, storage, key.localIndex());
        }
    }

    private static void refresh(
            final StateMachine machine,
            final SectionRenderDataStorageOwner owner,
            final SectionRenderDataStorage storage,
            final int localIndex
    ) {
        SectionKey key = keyOf(owner, localIndex);
        MutableSection section = machine.section(key);
        if (section == null || !section.ready) {
            return;
        }
        TerrainCandidateSnapshot.Candidate candidate = candidate(key, owner, storage, localIndex);
        machine.onMesh(key, candidate, storage);
    }

    private static TerrainCandidateSnapshot.Candidate candidate(
            final SectionKey key,
            final SectionRenderDataStorageOwner owner,
            final SectionRenderDataStorage storage,
            final int localIndex
    ) {
        if (!(storage instanceof SectionRenderDataStorageAccessor accessor)
                || localIndex < 0 || localIndex >= 256) {
            return null;
        }
        GlBufferSegment[] vertices = accessor.metallum$getVertexAllocations();
        if (vertices == null || localIndex >= vertices.length) {
            return null;
        }
        GlBufferSegment vertex = vertices[localIndex];
        if (vertex == null) {
            return null;
        }
        long dataPointer;
        try {
            dataPointer = storage.getDataPointer(localIndex);
        } catch (RuntimeException exception) {
            return null;
        }
        if (dataPointer <= 0L) {
            return null;
        }
        boolean localIndexMode;
        try {
            localIndexMode = SectionRenderDataUnsafe.isLocalIndex(dataPointer);
        } catch (RuntimeException exception) {
            return null;
        }
        GlBufferSegment index;
        if (localIndexMode) {
            GlBufferSegment[] elements = accessor.metallum$getElementAllocations();
            index = elements == null || localIndex >= elements.length ? null : elements[localIndex];
        } else {
            index = accessor.metallum$getSharedIndexAllocation();
        }
        TerrainCandidateSnapshot.AllocationIdentity vertexIdentity = allocation(vertex);
        TerrainCandidateSnapshot.AllocationIdentity indexIdentity = allocation(index);
        if (indexIdentity == null && !localIndexMode && !owner.metallum$isTranslucent()) {
            indexIdentity = liveOpaqueSharedIndex();
        }
        if (vertexIdentity == null || indexIdentity == null
                || !vertexIdentity.live() || !indexIdentity.live()) {
            return null;
        }
        final long baseElement;
        final long baseVertex;
        final long[] vertexCounts = new long[net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing.COUNT];
        final int[] facings = new int[vertexCounts.length];
        try {
            baseElement = SectionRenderDataUnsafe.getBaseElement(dataPointer);
            baseVertex = SectionRenderDataUnsafe.getBaseVertex(dataPointer);
            long facingList = localIndexMode
                    ? 0L
                    : SectionRenderDataUnsafe.getFacingList(dataPointer);
            for (int face = 0; face < vertexCounts.length; face++) {
                vertexCounts[face] = SectionRenderDataUnsafe.getVertexCount(dataPointer, face);
                facings[face] = localIndexMode
                        ? face
                        : (int) ((facingList >>> (face * 8)) & 255L);
            }
        } catch (RuntimeException exception) {
            return null;
        }
        int sectionX = key.sectionX();
        int sectionY = key.sectionY();
        int sectionZ = key.sectionZ();
        TerrainCandidateSnapshot.SectionIdentity sectionIdentity = new TerrainCandidateSnapshot.SectionIdentity(
                key.regionX(), key.regionY(), key.regionZ(), localIndex, sectionX, sectionY, sectionZ
        );
        TerrainCandidateSnapshot.Aabb aabb = new TerrainCandidateSnapshot.Aabb(
                sectionX * 16.0, sectionY * 16.0, sectionZ * 16.0,
                sectionX * 16.0 + 16.0, sectionY * 16.0 + 16.0, sectionZ * 16.0 + 16.0
        );
        List<TerrainCandidateSnapshot.IndexedDrawRecord> draws =
                TerrainCandidateDrawMaterializer.materialize(
                        sectionIdentity,
                        owner.metallum$isTranslucent()
                                ? TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT
                                : TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                        localIndexMode,
                        dataPointer,
                        baseElement,
                        baseVertex,
                        vertexCounts,
                        facings,
                        vertexIdentity,
                        indexIdentity
                );
        if (draws == null) {
            return null;
        }
        return new TerrainCandidateSnapshot.Candidate(
                sectionIdentity,
                aabb,
                owner.metallum$isTranslucent()
                        ? TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT
                        : TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                localIndexMode,
                vertexIdentity,
                indexIdentity,
                draws
        );
    }

    private static TerrainCandidateSnapshot.AllocationIdentity allocation(final GlBufferSegment segment) {
        if (segment == null || !(segment instanceof GlBufferSegmentAccessor accessor)
                || accessor.metallum$isFree()
                || !(segment instanceof GlBufferSegmentGeneration generation)) {
            return null;
        }
        long offset = segment.getOffset();
        long length = segment.getLength();
        long stamp = generation.metallum$generation();
        if (offset < 0L || length <= 0L || stamp < 0L) {
            return null;
        }
        return new TerrainCandidateSnapshot.AllocationIdentity(segment, offset, length, stamp, null);
    }

    private static TerrainCandidateSnapshot.AllocationIdentity liveOpaqueSharedIndex() {
        TerrainCandidateSnapshot.AllocationIdentity identity = opaqueSharedIndex;
        if (identity == null || !(identity.allocation() instanceof MetalGpuBuffer metalBuffer)
                || identity.backingIdentity() == null) {
            return null;
        }
        try {
            if (metalBuffer.isClosed() || metalBuffer.size() != identity.length()) {
                opaqueSharedIndex = null;
                return null;
            }
            MetalAllocationIdentity live = metalBuffer.allocationIdentity();
            if (!live.equals(identity.backingIdentity())
                    || live.generation() != identity.generation()) {
                opaqueSharedIndex = null;
                return null;
            }
            return identity;
        } catch (RuntimeException exception) {
            opaqueSharedIndex = null;
            return null;
        }
    }

    /**
     * Re-materializes one published candidate against the live Sodium storage.
     * This is the fail-closed boundary for a future GPU consumer: pointer,
     * allocation object, generation, backing identity, or draw arithmetic
     * changes reject the entire candidate rather than guessing.
     */
    static boolean recordsLive(
            final TerrainCandidateSnapshot.Candidate expected,
            final SectionRenderDataStorage storage
    ) {
        if (!ENABLED || expected == null || storage == null
                || !(storage instanceof SectionRenderDataStorageOwner owner)
                || !owner.metallum$hasOwner()) {
            return false;
        }
        synchronized (TerrainCandidateRegistry.class) {
            TerrainCandidateSnapshot.SectionIdentity section = expected.section();
            if (section.localIndex() < 0 || section.localIndex() >= 256
                    || section.regionX() != owner.metallum$regionX()
                    || section.regionY() != owner.metallum$regionY()
                    || section.regionZ() != owner.metallum$regionZ()
                    || section.sectionX() != owner.metallum$baseChunkX()
                    + LocalSectionIndex.unpackX(section.localIndex())
                    || section.sectionY() != owner.metallum$baseChunkY()
                    + LocalSectionIndex.unpackY(section.localIndex())
                    || section.sectionZ() != owner.metallum$baseChunkZ()
                    + LocalSectionIndex.unpackZ(section.localIndex())) {
                return false;
            }
            TerrainCandidateSnapshot.Candidate current = candidate(
                    new SectionKey(
                            section.regionX(), section.regionY(), section.regionZ(),
                            section.localIndex(), section.sectionX(), section.sectionY(), section.sectionZ()
                    ),
                    owner,
                    storage,
                    section.localIndex()
            );
            return current != null && current.equals(expected) && current.recordsLive();
        }
    }

    record SectionKey(
            int regionX,
            int regionY,
            int regionZ,
            int localIndex,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
    }

    static SectionKey keyOf(
            final SectionRenderDataStorageOwner owner,
            final int localIndex
    ) {
        return new SectionKey(
                owner.metallum$regionX(), owner.metallum$regionY(), owner.metallum$regionZ(),
                localIndex,
                owner.metallum$baseChunkX() + LocalSectionIndex.unpackX(localIndex),
                owner.metallum$baseChunkY() + LocalSectionIndex.unpackY(localIndex),
                owner.metallum$baseChunkZ() + LocalSectionIndex.unpackZ(localIndex)
        );
    }

    /**
     * Small state machine kept separate so contract tests can exercise the
     * registry transitions without constructing Minecraft renderer objects.
     */
    static final class StateMachine {
        private final Map<SectionKey, MutableSection> sections = new HashMap<>();

        void onSectionAdded(final SectionKey key) {
            sections.put(key, new MutableSection(key));
        }

        void onBuilt(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            section.ready = true;
            // BuiltSectionInfo is published before Sodium's later mesh upload
            // owner.  Drop the prior generation here so an old live segment
            // cannot masquerade as the newly built mesh.
            section.meshes.clear();
        }

        void onNotReady(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            section.ready = false;
            section.meshes.clear();
        }

        void onMesh(
                final SectionKey key,
                final TerrainCandidateSnapshot.Candidate candidate,
                final Object storage
        ) {
            MutableSection section = sections.get(key);
            if (section == null || !section.ready) {
                return;
            }
            TerrainCandidateSnapshot.TerrainPass pass = candidate == null
                    ? storage instanceof SectionRenderDataStorageOwner owner && owner.metallum$isTranslucent()
                    ? TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT
                    : TerrainCandidateSnapshot.TerrainPass.OPAQUE
                    : candidate.pass();
            if (candidate == null) {
                section.meshes.put(pass, new MeshState(key, pass, null, storage));
            } else {
                section.meshes.put(pass, new MeshState(key, pass, candidate, storage));
            }
        }

        void onStorageDeleted(final Object storage) {
            for (MutableSection section : sections.values()) {
                section.meshes.values().removeIf(mesh -> mesh.storage == storage);
            }
        }

        void refreshAll() {
            // Storage mutation hooks normally keep these entries current.  A
            // frame revalidation is still required for allocator moves that
            // occur at a later owner boundary (and for ABA generation checks).
            List<MeshState> current = new ArrayList<>();
            for (MutableSection section : sections.values()) {
                current.addAll(section.meshes.values());
            }
            for (MeshState mesh : current) {
                if (mesh.storage instanceof SectionRenderDataStorage storage
                        && storage instanceof SectionRenderDataStorageOwner owner
                        && owner.metallum$hasOwner()) {
                    TerrainCandidateRegistry.onStorageMutation(
                            storage, mesh.key.localIndex()
                    );
                }
            }
        }

        void onSectionRemoved(final SectionKey key) {
            sections.remove(key);
        }

        MutableSection section(final SectionKey key) {
            return sections.get(key);
        }

        TerrainCandidateSnapshot snapshot(
                final TerrainCandidateSnapshot.CameraPosition camera,
                final TerrainCandidateSnapshot.VisibilityTransform transform
        ) {
            List<TerrainCandidateSnapshot.Candidate> candidates = new ArrayList<>();
            for (MutableSection section : sections.values()) {
                if (!section.ready) {
                    continue;
                }
                for (MeshState mesh : section.meshes.values()) {
                    if (mesh.candidate != null
                            && (mesh.candidate.draws().isEmpty()
                            || !(mesh.storage instanceof SectionRenderDataStorage storage)
                            || TerrainCandidateRegistry.recordsLive(mesh.candidate, storage))) {
                        candidates.add(mesh.candidate);
                    }
                }
            }
            candidates.sort((left, right) -> {
                TerrainCandidateSnapshot.SectionIdentity a = left.section();
                TerrainCandidateSnapshot.SectionIdentity b = right.section();
                int result = Integer.compare(a.sectionX(), b.sectionX());
                if (result == 0) result = Integer.compare(a.sectionY(), b.sectionY());
                if (result == 0) result = Integer.compare(a.sectionZ(), b.sectionZ());
                if (result == 0) result = Integer.compare(a.localIndex(), b.localIndex());
                if (result == 0) result = left.pass().compareTo(right.pass());
                return result;
            });
            return new TerrainCandidateSnapshot(camera, transform, candidates);
        }
    }

    static final class MutableSection {
        private final SectionKey key;
        private boolean ready;
        private final EnumMap<TerrainCandidateSnapshot.TerrainPass, MeshState> meshes =
                new EnumMap<>(TerrainCandidateSnapshot.TerrainPass.class);

        private MutableSection(final SectionKey key) {
            this.key = key;
        }
    }

    private static final class MeshState {
        private final SectionKey key;
        private final TerrainCandidateSnapshot.TerrainPass pass;
        private final TerrainCandidateSnapshot.Candidate candidate;
        private final Object storage;

        private MeshState(
                final SectionKey key,
                final TerrainCandidateSnapshot.TerrainPass pass,
                final TerrainCandidateSnapshot.Candidate candidate,
                final Object storage
        ) {
            this.key = key;
            this.pass = pass;
            this.candidate = candidate;
            this.storage = storage;
        }
    }

}
