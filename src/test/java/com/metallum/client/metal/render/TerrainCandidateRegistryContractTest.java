package com.metallum.client.metal.render;

import com.metallum.mixin.sodium.SectionRenderDataStorageOwner;
import com.metallum.mixin.sodium.SectionRenderDataStorageAccessor;
import net.caffeinemc.mods.sodium.client.gpu.arena.GlBufferSegment;
import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

final class TerrainCandidateRegistryContractTest {
    @Test
    void emptyAndUnuploadedSectionsAreExcludedUntilMeshUpload() {
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(4, 8, -2, 17);
        machine.onSectionAdded(key);
        machine.onBuilt(key);

        assertEquals(0, snapshot(machine).candidates().size());

        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 1L), new Object());
        assertEquals(1, snapshot(machine).candidates().size());

        machine.onNotReady(key);
        assertEquals(0, snapshot(machine).candidates().size());
    }

    @Test
    void replacementPublishesTheNewPerAllocationGeneration() {
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(0, 0, 0, 3);
        machine.onSectionAdded(key);
        machine.onBuilt(key);
        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 1L), new Object());
        assertEquals(1L, snapshot(machine).candidates().get(0).vertexAllocation().generation());

        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 2L), new Object());
        assertEquals(2L, snapshot(machine).candidates().get(0).vertexAllocation().generation());
    }

    @Test
    void regionBasesUseSodiumsAsymmetricDimensionsForNegativeCoordinates() {
        int regionX = -3;
        int regionY = -2;
        int regionZ = 5;
        int localIndex = LocalSectionIndex.pack(7, 3, 1);
        FakeOwner owner = new FakeOwner(
                regionX, regionY, regionZ,
                regionX * 8, regionY * 4, regionZ * 8,
                false
        );

        TerrainCandidateRegistry.SectionKey key = TerrainCandidateRegistry.keyOf(owner, localIndex);

        assertEquals(regionX * 8 + 7, key.sectionX());
        assertEquals(regionY * 4 + 3, key.sectionY());
        assertEquals(regionZ * 8 + 1, key.sectionZ());
    }

    @Test
    void storagePlaceholderSurvivesUntilOpaqueSharedIndexBecomesAvailable() {
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(0, 0, 0, 3);
        FakeOwner storage = new FakeOwner(0, 0, 0, 0, 0, 0, false);
        machine.onSectionAdded(key);
        machine.onBuilt(key);

        machine.onMesh(key, null, storage);
        assertEquals(0, snapshot(machine).candidates().size());

        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 7L), storage);
        assertEquals(1, snapshot(machine).candidates().size());
    }

    @Test
    void actualStorageCandidatesRequireLiveRecordsEvenWhenRecordListIsEmpty() {
        FakeStorage storage = new FakeStorage(0, 0, 0, 0, 0, 0, false);
        try {
            TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
            TerrainCandidateRegistry.SectionKey key = TerrainCandidateRegistry.keyOf(storage, 3);
            machine.onSectionAdded(key);
            machine.onBuilt(key);
            machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 1L), storage);

            assertEquals(0, snapshot(machine).candidates().size());
        } finally {
            storage.delete();
        }
    }

    @Test
    void freeAndRemoveInvalidateCandidates() {
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(2, 3, 4, 5);
        machine.onSectionAdded(key);
        machine.onBuilt(key);
        FakeOwner storage = new FakeOwner(2, 3, 4, 16, 12, 32, true);
        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT, 1L), storage);
        assertEquals(1, snapshot(machine).candidates().size());

        machine.onMesh(key, null, storage);
        assertEquals(0, snapshot(machine).candidates().size());

        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT, 3L), storage);
        machine.onSectionRemoved(key);
        assertEquals(0, snapshot(machine).candidates().size());
    }

    @Test
    void passIdentityIsPreservedForOpaqueAndTranslucentMeshes() {
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(1, 2, 3, 9);
        machine.onSectionAdded(key);
        machine.onBuilt(key);
        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 1L), new Object());
        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT, 4L), new Object());

        assertEquals(
                List.of(TerrainCandidateSnapshot.TerrainPass.OPAQUE,
                        TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT),
                snapshot(machine).candidates().stream().map(TerrainCandidateSnapshot.Candidate::pass).toList()
        );
    }

    @Test
    void cameraAndMatrixAreDeepCopiedAndFeatureOffIsZeroOp() {
        Matrix4f source = new Matrix4f().identity().m30(12.0F);
        TerrainCandidateSnapshot.VisibilityTransform transform =
                TerrainCandidateSnapshot.VisibilityTransform.copyOf(source);
        source.m30(99.0F);
        assertEquals(12.0F, transform.m30());
        assertEquals(12.0F, transform.toMatrix().m30());
        TerrainCandidateRegistry.StateMachine machine = new TerrainCandidateRegistry.StateMachine();
        TerrainCandidateRegistry.SectionKey key = key(0, 0, 0, 1);
        machine.onSectionAdded(key);
        machine.onBuilt(key);
        machine.onMesh(key, candidate(key, TerrainCandidateSnapshot.TerrainPass.OPAQUE, 1L), new Object());
        assertEquals(10.0, snapshot(machine).camera().x());
        assertEquals(99.0, machine.snapshot(
                new TerrainCandidateSnapshot.CameraPosition(99.0, 20.0, 30.0),
                transform
        ).camera().x());
        if (!TerrainCandidateRegistry.enabled()) {
            assertFalse(TerrainCandidateRegistry.enabled());
            assertNull(TerrainCandidateRegistry.latestSnapshot());
        }
    }

    @Test
    void resetClearsWorldSnapshotStateWhenEnabled() {
        if (!TerrainCandidateRegistry.enabled()) {
            TerrainCandidateRegistry.reset();
            assertNull(TerrainCandidateRegistry.latestSnapshot());
            return;
        }

        TerrainCandidateRegistry.captureFrame(1.0, 2.0, 3.0, new Matrix4f().identity());
        TerrainCandidateRegistry.installOpaqueIdentityForContractTest(
                new TerrainCandidateSnapshot.AllocationIdentity(
                        new Object(), 0L, 16L, 1L,
                        new MetalAllocationIdentity(9001L, 1L)
                )
        );
        assertTrue(TerrainCandidateRegistry.statePresentForContractTest());
        assertTrue(TerrainCandidateRegistry.opaqueIdentityPresentForContractTest());
        assertEquals(0, TerrainCandidateRegistry.latestSnapshot().candidates().size());
        TerrainCandidateRegistry.reset();
        assertFalse(TerrainCandidateRegistry.statePresentForContractTest());
        assertFalse(TerrainCandidateRegistry.opaqueIdentityPresentForContractTest());
        assertNull(TerrainCandidateRegistry.latestSnapshot());
    }

    private static TerrainCandidateSnapshot snapshot(final TerrainCandidateRegistry.StateMachine machine) {
        return machine.snapshot(
                new TerrainCandidateSnapshot.CameraPosition(10.0, 20.0, 30.0),
                new TerrainCandidateSnapshot.VisibilityTransform(
                        1, 0, 0, 0,
                        0, 1, 0, 0,
                        0, 0, 1, 0,
                        0, 0, 0, 1
                )
        );
    }

    private static TerrainCandidateRegistry.SectionKey key(
            final int regionX,
            final int regionY,
            final int regionZ,
            final int localIndex
    ) {
        return new TerrainCandidateRegistry.SectionKey(
                regionX, regionY, regionZ, localIndex,
                regionX * 16 + 1, regionY * 16 + 2, regionZ * 16 + 3
        );
    }

    private static TerrainCandidateSnapshot.Candidate candidate(
            final TerrainCandidateRegistry.SectionKey key,
            final TerrainCandidateSnapshot.TerrainPass pass,
            final long generation
    ) {
        TerrainCandidateSnapshot.AllocationIdentity vertex =
                new TerrainCandidateSnapshot.AllocationIdentity(
                        new Object(), generation * 16L, 16L, generation
                );
        TerrainCandidateSnapshot.AllocationIdentity index =
                new TerrainCandidateSnapshot.AllocationIdentity(
                        new Object(), generation * 32L, 32L, generation
                );
        TerrainCandidateSnapshot.SectionIdentity section = new TerrainCandidateSnapshot.SectionIdentity(
                key.regionX(), key.regionY(), key.regionZ(), key.localIndex(),
                key.sectionX(), key.sectionY(), key.sectionZ()
        );
        return new TerrainCandidateSnapshot.Candidate(
                section,
                new TerrainCandidateSnapshot.Aabb(
                        key.sectionX() * 16.0, key.sectionY() * 16.0, key.sectionZ() * 16.0,
                        key.sectionX() * 16.0 + 16.0,
                        key.sectionY() * 16.0 + 16.0,
                        key.sectionZ() * 16.0 + 16.0
                ),
                pass,
                pass == TerrainCandidateSnapshot.TerrainPass.TRANSLUCENT,
                vertex,
                index
        );
    }

    private static final class FakeOwner implements SectionRenderDataStorageOwner {
        private final int regionX;
        private final int regionY;
        private final int regionZ;
        private final int baseChunkX;
        private final int baseChunkY;
        private final int baseChunkZ;
        private final boolean translucent;

        private FakeOwner(
                final int regionX,
                final int regionY,
                final int regionZ,
                final int baseChunkX,
                final int baseChunkY,
                final int baseChunkZ,
                final boolean translucent
        ) {
            this.regionX = regionX;
            this.regionY = regionY;
            this.regionZ = regionZ;
            this.baseChunkX = baseChunkX;
            this.baseChunkY = baseChunkY;
            this.baseChunkZ = baseChunkZ;
            this.translucent = translucent;
        }

        @Override
        public void metallum$setOwner(
                final int regionX,
                final int regionY,
                final int regionZ,
                final int baseChunkX,
                final int baseChunkY,
                final int baseChunkZ,
                final boolean translucent
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean metallum$hasOwner() {
            return true;
        }

        @Override
        public int metallum$regionX() {
            return regionX;
        }

        @Override
        public int metallum$regionY() {
            return regionY;
        }

        @Override
        public int metallum$regionZ() {
            return regionZ;
        }

        @Override
        public int metallum$baseChunkX() {
            return baseChunkX;
        }

        @Override
        public int metallum$baseChunkY() {
            return baseChunkY;
        }

        @Override
        public int metallum$baseChunkZ() {
            return baseChunkZ;
        }

        @Override
        public boolean metallum$isTranslucent() {
            return translucent;
        }
    }

    private static final class FakeStorage extends SectionRenderDataStorage
            implements SectionRenderDataStorageOwner, SectionRenderDataStorageAccessor {
        private final FakeOwner owner;

        private FakeStorage(
                final int regionX,
                final int regionY,
                final int regionZ,
                final int baseChunkX,
                final int baseChunkY,
                final int baseChunkZ,
                final boolean translucent
        ) {
            super(false);
            this.owner = new FakeOwner(
                    regionX, regionY, regionZ,
                    baseChunkX, baseChunkY, baseChunkZ, translucent
            );
        }

        @Override
        public GlBufferSegment[] metallum$getVertexAllocations() {
            return null;
        }

        @Override
        public GlBufferSegment[] metallum$getElementAllocations() {
            return null;
        }

        @Override
        public GlBufferSegment metallum$getSharedIndexAllocation() {
            return null;
        }

        @Override
        public void metallum$setOwner(
                final int regionX,
                final int regionY,
                final int regionZ,
                final int baseChunkX,
                final int baseChunkY,
                final int baseChunkZ,
                final boolean translucent
        ) {
            owner.metallum$setOwner(
                    regionX, regionY, regionZ,
                    baseChunkX, baseChunkY, baseChunkZ, translucent
            );
        }

        @Override
        public boolean metallum$hasOwner() {
            return owner.metallum$hasOwner();
        }

        @Override
        public int metallum$regionX() {
            return owner.metallum$regionX();
        }

        @Override
        public int metallum$regionY() {
            return owner.metallum$regionY();
        }

        @Override
        public int metallum$regionZ() {
            return owner.metallum$regionZ();
        }

        @Override
        public int metallum$baseChunkX() {
            return owner.metallum$baseChunkX();
        }

        @Override
        public int metallum$baseChunkY() {
            return owner.metallum$baseChunkY();
        }

        @Override
        public int metallum$baseChunkZ() {
            return owner.metallum$baseChunkZ();
        }

        @Override
        public boolean metallum$isTranslucent() {
            return owner.metallum$isTranslucent();
        }
    }
}
