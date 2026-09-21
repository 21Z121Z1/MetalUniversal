package com.metallum.client.metal.render;

/**
 * Process-local admission for terrain ICB producer work.
 *
 * <p>The final compiled PSO is authoritative. Metal may accept the descriptor
 * request and then return a correct fallback PSO that cannot be referenced by
 * an indirect command buffer. Once that happens, upstream snapshot, metadata
 * and candidate production must stop too; otherwise the fallback remains
 * correct but pays the full experimental CPU cost every frame.</p>
 */
final class TerrainIcbRuntimeAdmission {
    private static volatile boolean finalPipelineRejected;

    private TerrainIcbRuntimeAdmission() {
    }

    static boolean gpuIcbAdmitted() {
        return !finalPipelineRejected;
    }

    static void rejectFinalPipeline() {
        finalPipelineRejected = true;
    }

    static void reset() {
        finalPipelineRejected = false;
    }
}
