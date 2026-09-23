package com.metallum.client.metal.render;

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
