package com.metallum.client.terrain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainMeshGenerationTest {
    @Test
    void initialShadersOffStateBypassesRegionValidation() {
        TerrainMeshGeneration.Timeline timeline = new TerrainMeshGeneration.Timeline();

        TerrainMeshGeneration.Stamp initial = timeline.current();

        assertEquals(0L, initial.epoch());
        assertEquals(TerrainMeshGeneration.NO_PIPELINE_GENERATION, initial.pipelineGeneration());
        assertEquals(0, initial.materialMapGeneration());
        assertTrue(initial.renderReady());
        assertFalse(initial.validationRequired());
    }

    @Test
    void pipelineAndMaterialMapPublicationsReceiveDistinctEpochs() {
        TerrainMeshGeneration.Timeline timeline = new TerrainMeshGeneration.Timeline();

        TerrainMeshGeneration.Stamp beforeMaps = timeline.publish(7, 0, false, true);
        TerrainMeshGeneration.Stamp afterMaps = timeline.publish(7, 1, true, true);
        TerrainMeshGeneration.Stamp shadersOff = timeline.publish(
                TerrainMeshGeneration.NO_PIPELINE_GENERATION,
                0,
                true,
                true
        );

        assertNotEquals(beforeMaps.epoch(), afterMaps.epoch());
        assertNotEquals(afterMaps.epoch(), shadersOff.epoch());
        assertEquals(7, afterMaps.pipelineGeneration());
        assertEquals(1, afterMaps.materialMapGeneration());
        assertTrue(afterMaps.renderReady());
        assertTrue(shadersOff.validationRequired());
        assertSame(shadersOff, timeline.current());
    }

    @Test
    void shadersOffTransitionRejectsUntilCompactPipelineIsReady() {
        TerrainMeshGeneration.Timeline timeline = new TerrainMeshGeneration.Timeline();
        timeline.publish(7, 1, true, true);

        TerrainMeshGeneration.Stamp pending = timeline.publish(
                TerrainMeshGeneration.NO_PIPELINE_GENERATION,
                0,
                false,
                true
        );
        assertFalse(pending.renderReady());
        assertTrue(timeline.completeShadersOffTransition());

        TerrainMeshGeneration.Stamp ready = timeline.current();
        assertTrue(ready.renderReady());
        assertTrue(ready.validationRequired());
        assertNotEquals(pending.epoch(), ready.epoch());
        assertFalse(timeline.completeShadersOffTransition());
    }

    @Test
    void delayedWorkerCannotPassAnAbaPipelineTransition() throws Exception {
        TerrainMeshGeneration.Timeline timeline = new TerrainMeshGeneration.Timeline();
        TerrainMeshGeneration.Stamp firstA = timeline.publish(7, 1, true, true);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<TerrainMeshGeneration.Stamp> worker = executor.submit(() -> {
                TerrainMeshGeneration.Stamp stamp = timeline.current();
                captured.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return stamp;
            });

            assertTrue(captured.await(5, TimeUnit.SECONDS));
            TerrainMeshGeneration.Stamp b = timeline.publish(8, 1, true, true);
            TerrainMeshGeneration.Stamp secondA = timeline.publish(7, 1, true, true);
            release.countDown();

            TerrainMeshGeneration.Stamp delayed = worker.get(5, TimeUnit.SECONDS);
            assertSame(firstA, delayed);
            assertNotEquals(delayed.epoch(), b.epoch());
            assertNotEquals(delayed.epoch(), secondA.epoch());
            assertSame(secondA, timeline.current());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
