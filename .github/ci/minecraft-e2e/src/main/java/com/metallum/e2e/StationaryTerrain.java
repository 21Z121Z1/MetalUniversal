package com.metallum.e2e;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.concurrent.ArrayBlockingQueue;

/** Test-only pre-window readiness using Vanilla's draw admission, never a GPU wait. */
final class StationaryTerrain {
    private String previous;
    private long stableSince;
    private int consecutive;
    private JsonObject evidence;

    boolean ready(Minecraft client) {
        return observe(capture(client), System.nanoTime());
    }

    boolean observe(JsonObject current, long now) {
        String identity = current == null ? null : current.get("visibleDrawSha256").getAsString();
        if (identity == null || !identity.equals(previous)) {
            consecutive = 0;
            stableSince = now;
        }
        previous = identity;
        if (current == null) return false;
        consecutive++;
        current.addProperty("consecutiveChecks", consecutive);
        current.addProperty("stableNanos", now - stableSince);
        evidence = current;
        return consecutive >= 40 && now - stableSince >= 2_000_000_000L;
    }

    JsonObject evidence() { return evidence.deepCopy(); }

    static JsonObject scheduledSectionWorkEvidence(int taskQueueSize, int bufferCapacity, int freeBuffers) {
        int tasksInFlight = bufferCapacity - freeBuffers;
        boolean valid = taskQueueSize >= 0 && bufferCapacity > 0 && freeBuffers >= 0 && freeBuffers <= bufferCapacity;
        JsonObject result = new JsonObject();
        result.addProperty("taskQueueSize", taskQueueSize);
        result.addProperty("bufferCapacity", bufferCapacity);
        result.addProperty("freeBuffers", freeBuffers);
        result.addProperty("tasksInFlight", tasksInFlight);
        result.addProperty("scheduledSectionWorkComplete", valid && taskQueueSize == 0 && tasksInFlight == 0);
        result.addProperty("authority", "SectionRenderDispatcher task queue and SectionBufferBuilderPool free buffers; already-scheduled section work only");
        return result;
    }

    private static JsonObject scheduledSectionWork(SectionRenderDispatcher dispatcher) {
        var pool = ((com.metallum.e2e.mixin.StationaryDispatcherAccessor) dispatcher).metallum$bufferPool();
        ArrayBlockingQueue<?> freeBuffers =
                ((com.metallum.e2e.mixin.StationaryBufferPoolAccessor) pool).metallum$freeBuffers();
        return scheduledSectionWorkEvidence(
                dispatcher.getCompileQueueSize(), freeBuffers.size() + freeBuffers.remainingCapacity(), freeBuffers.size());
    }

    static JsonObject capture(Minecraft client) {
        var renderer = client.levelRenderer;
        var dispatcher = renderer.sectionRenderDispatcher();
        if (dispatcher == null) return null;
        var graph = (com.metallum.e2e.mixin.StationaryOcclusionAccessor) renderer.sectionOcclusionGraph();
        var graphTask = graph.metallum$fullUpdateTask();
        if (graph.metallum$needsFullUpdate() || graph.metallum$needsFrustumUpdate().get()
                || (graphTask != null && graphTask.state() != java.util.concurrent.Future.State.SUCCESS)) return null;
        if (!scheduledSectionWork(dispatcher).get("scheduledSectionWorkComplete").getAsBoolean()
                || !renderer.hasRenderedAllSections()
                || !renderer.sectionOcclusionGraph().expectedChunks().isEmpty()
                || renderer.visibleSections().isEmpty()) return null;
        var rows = new ArrayList<String>();
        long indexCount = 0;
        JsonObject sectionWork;
        dispatcher.lock();
        try {
            sectionWork = scheduledSectionWork(dispatcher);
            if (!sectionWork.get("scheduledSectionWorkComplete").getAsBoolean()
                    || !renderer.hasRenderedAllSections()) return null;
            for (var section : renderer.visibleSections()) {
                var mesh = section.getSectionMesh();
                if (mesh == CompiledSectionMesh.UNCOMPILED) return null;
                StringBuilder row = new StringBuilder().append(section.getSectionNode());
                for (var layer : ChunkSectionLayer.values()) {
                    var draw = mesh.getSectionDraw(layer);
                    if (draw == null) continue;
                    if (mesh instanceof CompiledSectionMesh compiled && (!compiled.isVertexBufferUploaded(layer)
                            || (draw.hasCustomIndexBuffer() && !compiled.isIndexBufferUploaded(layer)))) return null;
                    var slice = dispatcher.getRenderSectionSlice(mesh, layer);
                    if (slice == null || (draw.hasCustomIndexBuffer() && slice.indexBuffer() == null)) return null;
                    row.append(':').append(layer).append('/').append(draw.indexCount())
                            .append('/').append(draw.indexType()).append('/').append(draw.hasCustomIndexBuffer());
                    indexCount += draw.indexCount();
                }
                rows.add(row.toString());
            }
        } finally {
            dispatcher.unlock();
        }
        // Canonical section identity; traversal order and native allocation addresses are not content.
        rows.sort(String::compareTo);
        JsonObject result = new JsonObject();
        var canonicalRows = new com.google.gson.JsonArray();
        rows.forEach(canonicalRows::add);
        result.add("canonicalDrawRows", canonicalRows);
        result.addProperty("visibleSections", rows.size());
        result.addProperty("layerIndexCount", indexCount);
        try {
            result.addProperty("visibleSectionSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rows.stream().map(row -> row.split(":", 2)[0]).collect(java.util.stream.Collectors.joining("\n"))
                            .getBytes(StandardCharsets.UTF_8))));
            result.addProperty("visibleDrawSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        result.add("scheduledSectionWork", sectionWork);
        result.addProperty("authority", "section nodes and admitted layer draw metadata; compiled/uploaded, queue and expected chunks empty; not pixel identity");
        return result;
    }
}
