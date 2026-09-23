package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Metal 4 queue dependency that makes terrain visibility data safe for
 * both raster consumers and a later GPU-authored ICB compute pass.
 *
 * <p>The visibility probe writes its bitset/compaction buffers in one compute
 * encoder. A future visible-ICB authoring pass is a separate compute encoder on
 * the same queue, so a pass-local encoder barrier is insufficient: the producer
 * must publish dispatch writes to a future dispatch queue stage as well as the
 * existing vertex/fragment consumers.</p>
 */
final class Metal4TerrainVisibilityBarrierContractTest {
    @Test
    void visibilityProbePublishesDispatchWritesToFutureComputeAndRasterConsumers() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int ownerConstruction = source.indexOf("let owner = TerrainGpuVisibilityProbeOwner(");
        assertTrue(ownerConstruction > 0, "terrain visibility probe owner construction moved or disappeared");

        int finalBarrier = source.lastIndexOf("computeEncoder.barrier(", ownerConstruction);
        assertTrue(finalBarrier > 0, "terrain visibility probe no longer has a producer barrier");

        String barrier = source.substring(finalBarrier, ownerConstruction);
        assertTrue(barrier.contains("afterStages: .dispatch"),
                "terrain visibility producer must publish writes from the dispatch stage");
        assertTrue(barrier.contains("beforeQueueStages: [.vertex, .fragment, .dispatch]"),
                "terrain visibility must be visible to raster consumers and a later compute encoder");
        assertTrue(barrier.contains("visibilityOptions: .device"),
                "terrain visibility queue dependency must retain device visibility");
    }
}
