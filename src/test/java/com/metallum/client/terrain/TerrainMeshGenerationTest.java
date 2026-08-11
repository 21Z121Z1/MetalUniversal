package com.metallum.client.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class TerrainMeshGenerationTest {
    @Test
    void materialMapEpochIsPartOfMeshIdentity() {
        long beforeMaps = TerrainMeshGeneration.token(7, 0);
        long afterMaps = TerrainMeshGeneration.token(7, 1);

        assertNotEquals(beforeMaps, afterMaps);
        assertEquals(TerrainMeshGeneration.token(7, 1), afterMaps);
    }

    @Test
    void pipelineGenerationIsPartOfMeshIdentity() {
        assertNotEquals(
                TerrainMeshGeneration.token(7, 1),
                TerrainMeshGeneration.token(8, 1)
        );
    }

    @Test
    void unstampedBuildsCannotBeCurrent() {
        assertNotEquals(
                TerrainMeshGeneration.current(),
                TerrainMeshGeneration.UNSTAMPED
        );
    }
}
