from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)


snapshot_path = Path("src/main/java/com/metallum/client/metal/render/TerrainCandidateSnapshot.java")
snapshot = snapshot_path.read_text()
snapshot = once(
    snapshot,
    "    public static final int GPU_VISIBILITY_MATRIX_BYTES = 16 * Float.BYTES;\n",
    "    public static final int GPU_VISIBILITY_MATRIX_BYTES = 16 * Float.BYTES;\n"
    "    /** Persistent scene record: int4 section origin + two float4 bounds blocks. */\n"
    "    public static final int GPU_VISIBILITY_SCENE_CANDIDATE_STRIDE_BYTES = 48;\n"
    "    /** Per-frame scene state: float4x4 matrix + int4 camera block + float4 fraction. */\n"
    "    public static final int GPU_VISIBILITY_SCENE_FRAME_BYTES = 96;\n",
    "scene ABI constants",
)
snapshot = once(
    snapshot,
    "    private final List<Candidate> candidates;\n    private final long epoch;\n",
    "    private final List<Candidate> candidates;\n"
    "    private final long epoch;\n"
    "    /** Changes only when the authoritative mesh/candidate scene changes. */\n"
    "    private final long sceneGeneration;\n",
    "scene generation field",
)
old_ctors = '''    TerrainCandidateSnapshot(
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        this(0L, camera, visibilityTransform, candidates);
    }

    TerrainCandidateSnapshot(
            final long epoch,
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("Terrain candidate snapshot epoch must be non-negative");
        }
        this.epoch = epoch;
        this.camera = Objects.requireNonNull(camera, "camera");
        this.visibilityTransform = Objects.requireNonNull(visibilityTransform, "visibilityTransform");
        this.candidates = List.copyOf(candidates);
    }

    public long epoch() {
        return epoch;
    }
'''
new_ctors = '''    TerrainCandidateSnapshot(
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        this(0L, 0L, camera, visibilityTransform, candidates);
    }

    TerrainCandidateSnapshot(
            final long epoch,
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        this(epoch, 0L, camera, visibilityTransform, candidates);
    }

    TerrainCandidateSnapshot(
            final long epoch,
            final long sceneGeneration,
            final CameraPosition camera,
            final VisibilityTransform visibilityTransform,
            final List<Candidate> candidates
    ) {
        if (epoch < 0L) {
            throw new IllegalArgumentException("Terrain candidate snapshot epoch must be non-negative");
        }
        if (sceneGeneration < 0L) {
            throw new IllegalArgumentException("Terrain scene generation must be non-negative");
        }
        this.epoch = epoch;
        this.sceneGeneration = sceneGeneration;
        this.camera = Objects.requireNonNull(camera, "camera");
        this.visibilityTransform = Objects.requireNonNull(visibilityTransform, "visibilityTransform");
        this.candidates = List.copyOf(candidates);
    }

    public long epoch() {
        return epoch;
    }

    public long sceneGeneration() {
        return sceneGeneration;
    }
'''
snapshot = once(snapshot, old_ctors, new_ctors, "scene generation constructors")
insert_anchor = '''    static int gpuVisibilityWordCount(final int candidateCount) {
'''
scene_methods = '''    /**
     * Packs camera-independent terrain bounds for a native generation-owned GPU scene.
     * Integer section-block origins preserve large-world precision; only local bounds
     * are narrowed to float32. The camera is deliberately absent from this buffer.
     */
    public MemorySegment packGpuVisibilitySceneCandidates(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        if (candidates.isEmpty()) {
            return MemorySegment.NULL;
        }
        final long bytes = Math.multiplyExact(
                (long) candidates.size(), GPU_VISIBILITY_SCENE_CANDIDATE_STRIDE_BYTES
        );
        final MemorySegment packed = arena.allocate(bytes, 16);
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            SectionIdentity section = candidate.section();
            int blockX = sectionBlock(section.sectionX());
            int blockY = sectionBlock(section.sectionY());
            int blockZ = sectionBlock(section.sectionZ());
            Aabb aabb = candidate.worldAabb();
            double minX = aabb.minX() - blockX;
            double minY = aabb.minY() - blockY;
            double minZ = aabb.minZ() - blockZ;
            double maxX = aabb.maxX() - blockX;
            double maxY = aabb.maxY() - blockY;
            double maxZ = aabb.maxZ() - blockZ;
            long offset = (long) index * GPU_VISIBILITY_SCENE_CANDIDATE_STRIDE_BYTES;
            packed.set(ValueLayout.JAVA_INT, offset, blockX);
            packed.set(ValueLayout.JAVA_INT, offset + 4, blockY);
            packed.set(ValueLayout.JAVA_INT, offset + 8, blockZ);
            packed.set(ValueLayout.JAVA_INT, offset + 12, 0);
            packed.set(ValueLayout.JAVA_FLOAT, offset + 16, conservativeMin(minX));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 20, conservativeMin(minY));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 24, conservativeMin(minZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 28, conservativeMax(maxX));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 32, conservativeMax(maxY));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 36, conservativeMax(maxZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 40,
                    checkedRange(minX, minY, minZ, maxX, maxY, maxZ));
            packed.set(ValueLayout.JAVA_FLOAT, offset + 44, 0.0F);
        }
        return packed;
    }

    /**
     * Packs the only visibility state that changes with camera motion. Camera
     * position is split into exact integer block coordinates plus a sub-block
     * fraction, so the GPU subtracts large world coordinates in integer space
     * before converting the small relative distance to float32.
     */
    public MemorySegment packGpuVisibilitySceneFrame(final Arena arena) {
        Objects.requireNonNull(arena, "arena");
        final MemorySegment packed = arena.allocate(GPU_VISIBILITY_SCENE_FRAME_BYTES, 16);
        final float[] matrix = new float[]{
                visibilityTransform.m00(), visibilityTransform.m01(),
                visibilityTransform.m02(), visibilityTransform.m03(),
                visibilityTransform.m10(), visibilityTransform.m11(),
                visibilityTransform.m12(), visibilityTransform.m13(),
                visibilityTransform.m20(), visibilityTransform.m21(),
                visibilityTransform.m22(), visibilityTransform.m23(),
                visibilityTransform.m30(), visibilityTransform.m31(),
                visibilityTransform.m32(), visibilityTransform.m33()
        };
        for (int index = 0; index < matrix.length; index++) {
            if (!Float.isFinite(matrix[index])) {
                throw new IllegalArgumentException("Terrain visibility matrix contains a non-finite value");
            }
            packed.set(ValueLayout.JAVA_FLOAT, (long) index * Float.BYTES, matrix[index]);
        }
        int cameraBlockX = cameraBlock(camera.x());
        int cameraBlockY = cameraBlock(camera.y());
        int cameraBlockZ = cameraBlock(camera.z());
        packed.set(ValueLayout.JAVA_INT, 64, cameraBlockX);
        packed.set(ValueLayout.JAVA_INT, 68, cameraBlockY);
        packed.set(ValueLayout.JAVA_INT, 72, cameraBlockZ);
        packed.set(ValueLayout.JAVA_INT, 76, 0);
        packed.set(ValueLayout.JAVA_FLOAT, 80, checkedFloat(camera.x() - cameraBlockX));
        packed.set(ValueLayout.JAVA_FLOAT, 84, checkedFloat(camera.y() - cameraBlockY));
        packed.set(ValueLayout.JAVA_FLOAT, 88, checkedFloat(camera.z() - cameraBlockZ));
        packed.set(ValueLayout.JAVA_FLOAT, 92, 0.0F);
        return packed;
    }

    static long gpuVisibilitySceneCandidateBytes(final int candidateCount) {
        if (candidateCount < 0 || candidateCount > GPU_VISIBILITY_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Terrain visibility candidate count exceeds the bounded ABI");
        }
        return Math.multiplyExact((long) candidateCount, GPU_VISIBILITY_SCENE_CANDIDATE_STRIDE_BYTES);
    }

    private static int sectionBlock(final int sectionCoordinate) {
        return Math.toIntExact(Math.multiplyExact((long) sectionCoordinate, 16L));
    }

    private static int cameraBlock(final double coordinate) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException("Terrain visibility camera coordinate is non-finite");
        }
        double floored = Math.floor(coordinate);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Terrain visibility camera block is outside int32");
        }
        return (int) floored;
    }

'''
snapshot = once(snapshot, insert_anchor, scene_methods + insert_anchor, "persistent scene packers")
snapshot_path.write_text(snapshot)

registry_path = Path("src/main/java/com/metallum/client/metal/render/TerrainCandidateRegistry.java")
registry = registry_path.read_text()
registry = once(
    registry,
    "    static final class StateMachine {\n        private final Map<SectionKey, MutableSection> sections = new HashMap<>();\n",
    "    static final class StateMachine {\n"
    "        private final Map<SectionKey, MutableSection> sections = new HashMap<>();\n"
    "        /** Monotonic identity for the ordered mesh/candidate scene, independent of camera epochs. */\n"
    "        private long sceneGeneration = 1L;\n\n"
    "        private void bumpSceneGeneration() {\n"
    "            if (sceneGeneration == Long.MAX_VALUE) {\n"
    "                throw new IllegalStateException(\"Terrain scene generation exhausted\");\n"
    "            }\n"
    "            sceneGeneration++;\n"
    "        }\n",
    "registry scene generation",
)
registry = once(
    registry,
    '''        void onSectionAdded(final SectionKey key) {
            sections.put(key, new MutableSection(key));
        }
''',
    '''        void onSectionAdded(final SectionKey key) {
            MutableSection previous = sections.put(key, new MutableSection(key));
            if (previous == null || previous.ready || !previous.meshes.isEmpty()) {
                bumpSceneGeneration();
            }
        }
''',
    "section add generation",
)
registry = once(
    registry,
    '''        void onBuilt(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            section.ready = true;
            // BuiltSectionInfo is published before Sodium's later mesh upload
            // owner.  Drop the prior generation here so an old live segment
            // cannot masquerade as the newly built mesh.
            section.meshes.clear();
        }
''',
    '''        void onBuilt(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            boolean changed = !section.ready || !section.meshes.isEmpty();
            section.ready = true;
            // BuiltSectionInfo is published before Sodium's later mesh upload
            // owner. Drop the prior generation here so an old live segment
            // cannot masquerade as the newly built mesh.
            section.meshes.clear();
            if (changed) bumpSceneGeneration();
        }
''',
    "built generation",
)
registry = once(
    registry,
    '''        void onNotReady(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            section.ready = false;
            section.meshes.clear();
        }
''',
    '''        void onNotReady(final SectionKey key) {
            MutableSection section = sections.computeIfAbsent(key, MutableSection::new);
            boolean changed = section.ready || !section.meshes.isEmpty();
            section.ready = false;
            section.meshes.clear();
            if (changed) bumpSceneGeneration();
        }
''',
    "not ready generation",
)
old_mesh = '''            if (candidate == null) {
                section.meshes.put(pass, new MeshState(key, pass, null, storage));
            } else {
                section.meshes.put(pass, new MeshState(key, pass, candidate, storage));
            }
'''
new_mesh = '''            MeshState previous = section.meshes.get(pass);
            MeshState next = new MeshState(key, pass, candidate, storage);
            if (previous == null
                    || previous.storage != storage
                    || !java.util.Objects.equals(previous.candidate, candidate)) {
                section.meshes.put(pass, next);
                bumpSceneGeneration();
            }
'''
registry = once(registry, old_mesh, new_mesh, "mesh generation")
registry = once(
    registry,
    '''        void onStorageDeleted(final Object storage) {
            for (MutableSection section : sections.values()) {
                section.meshes.values().removeIf(mesh -> mesh.storage == storage);
            }
        }
''',
    '''        void onStorageDeleted(final Object storage) {
            boolean changed = false;
            for (MutableSection section : sections.values()) {
                changed |= section.meshes.values().removeIf(mesh -> mesh.storage == storage);
            }
            if (changed) bumpSceneGeneration();
        }
''',
    "storage delete generation",
)
registry = once(
    registry,
    '''        void onSectionRemoved(final SectionKey key) {
            sections.remove(key);
        }
''',
    '''        void onSectionRemoved(final SectionKey key) {
            if (sections.remove(key) != null) {
                bumpSceneGeneration();
            }
        }
''',
    "section remove generation",
)
registry = once(
    registry,
    "            return new TerrainCandidateSnapshot(epoch, camera, transform, candidates);\n",
    "            return new TerrainCandidateSnapshot(epoch, sceneGeneration, camera, transform, candidates);\n",
    "snapshot scene generation",
)
registry_path.write_text(registry)

test_path = Path("src/test/java/com/metallum/client/metal/render/TerrainPersistentGpuSceneContractTest.java")
test_path.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class TerrainPersistentGpuSceneContractTest {
    private static final TerrainCandidateSnapshot.VisibilityTransform IDENTITY =
            new TerrainCandidateSnapshot.VisibilityTransform(
                    1, 0, 0, 0,
                    0, 1, 0, 0,
                    0, 0, 1, 0,
                    0, 0, 0, 1
            );

    @Test
    void sceneRecordsAreCameraIndependentWhileFrameBlockTracksCamera() {
        TerrainCandidateSnapshot.Candidate candidate = candidate(1_800_000, -4, -1_800_000);
        TerrainCandidateSnapshot first = new TerrainCandidateSnapshot(
                7L, 31L,
                new TerrainCandidateSnapshot.CameraPosition(28_800_001.25, -63.5, -28_799_998.75),
                IDENTITY,
                List.of(candidate)
        );
        TerrainCandidateSnapshot second = new TerrainCandidateSnapshot(
                8L, 31L,
                new TerrainCandidateSnapshot.CameraPosition(28_800_009.75, -61.25, -28_799_990.5),
                IDENTITY,
                List.of(candidate)
        );
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment firstScene = first.packGpuVisibilitySceneCandidates(arena);
            MemorySegment secondScene = second.packGpuVisibilitySceneCandidates(arena);
            assertArrayEquals(firstScene.toArray(ValueLayout.JAVA_BYTE), secondScene.toArray(ValueLayout.JAVA_BYTE));
            assertEquals(31L, first.sceneGeneration());
            assertEquals(TerrainCandidateSnapshot.GPU_VISIBILITY_SCENE_CANDIDATE_STRIDE_BYTES,
                    firstScene.byteSize());

            // Section origin stays exact int32 even near the Minecraft world border.
            assertEquals(28_800_000, firstScene.get(ValueLayout.JAVA_INT, 0));
            assertEquals(-64, firstScene.get(ValueLayout.JAVA_INT, 4));
            assertEquals(-28_800_000, firstScene.get(ValueLayout.JAVA_INT, 8));
            assertEquals(0.0f, firstScene.get(ValueLayout.JAVA_FLOAT, 16));
            assertEquals(16.0f, firstScene.get(ValueLayout.JAVA_FLOAT, 28));

            MemorySegment firstFrame = first.packGpuVisibilitySceneFrame(arena);
            MemorySegment secondFrame = second.packGpuVisibilitySceneFrame(arena);
            assertEquals(TerrainCandidateSnapshot.GPU_VISIBILITY_SCENE_FRAME_BYTES, firstFrame.byteSize());
            assertNotEquals(firstFrame.get(ValueLayout.JAVA_INT, 64), secondFrame.get(ValueLayout.JAVA_INT, 64));
            assertEquals(0.25f, firstFrame.get(ValueLayout.JAVA_FLOAT, 80));
            assertEquals(0.5f, firstFrame.get(ValueLayout.JAVA_FLOAT, 84));
            assertEquals(0.25f, firstFrame.get(ValueLayout.JAVA_FLOAT, 88));
        }
    }

    @Test
    void sceneGenerationRejectsNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainCandidateSnapshot(
                1L, -1L,
                new TerrainCandidateSnapshot.CameraPosition(0, 0, 0),
                IDENTITY,
                List.of()
        ));
    }

    private static TerrainCandidateSnapshot.Candidate candidate(
            final int sectionX,
            final int sectionY,
            final int sectionZ
    ) {
        Object vertex = new Object();
        Object index = new Object();
        TerrainCandidateSnapshot.AllocationIdentity vertexIdentity =
                new TerrainCandidateSnapshot.AllocationIdentity(vertex, 0, 64, 1);
        TerrainCandidateSnapshot.AllocationIdentity indexIdentity =
                new TerrainCandidateSnapshot.AllocationIdentity(index, 0, 64, 1);
        int blockX = Math.multiplyExact(sectionX, 16);
        int blockY = Math.multiplyExact(sectionY, 16);
        int blockZ = Math.multiplyExact(sectionZ, 16);
        return new TerrainCandidateSnapshot.Candidate(
                new TerrainCandidateSnapshot.SectionIdentity(0, 0, 0, 0, sectionX, sectionY, sectionZ),
                new TerrainCandidateSnapshot.Aabb(
                        blockX, blockY, blockZ,
                        blockX + 16.0, blockY + 16.0, blockZ + 16.0
                ),
                TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                false,
                vertexIdentity,
                indexIdentity,
                List.of()
        );
    }
}
''')

print("persistent terrain scene Java contract staged")
