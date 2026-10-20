package com.metallum.client.metal.render;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.client.metal.render.mtl.MTLIndexType;
import com.metallum.client.validation.contract.RenderContractRuntime;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Bounded, explicitly requested GPU input evidence for the hosted conformance client. */
final class MetalSodiumDrawDiagnostics {
    private static final String OUTPUT = System.getProperty("metallum.ci.sodiumDrawEvidenceDir", "");
    private static long lastFrame = -1;

    private MetalSodiumDrawDiagnostics() { }

    static void capture(final MetalDevice device, final MetalCommandEncoder encoder,
                        final MetalCompiledRenderPipeline pipeline, final GpuBufferSlice vertices,
                        final MetalGpuBuffer indices, final MTLIndexType indexType,
                        final IntBuffer commands, final int drawCount,
                        final Map<String, GpuBufferSlice> uniforms) {
        if (OUTPUT.isBlank() || vertices == null || drawCount <= 0
                || !uniforms.containsKey("u_Globals") || pipeline.vertexStride(0) != 20) return;
        long frame = RenderContractRuntime.currentFrameId();
        if (frame < 1 || frame > 8 || frame == lastFrame) return;
        lastFrame = frame;
        Path root = Path.of(OUTPUT).resolve("frame-" + frame);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("frameId", frame);
        metadata.addProperty("pipelineId", pipeline.validationPipelineId());
        metadata.addProperty("vertexStride", pipeline.vertexStride(0));
        metadata.addProperty("metalVertexBufferSlot", pipeline.firstAvailableVertexBufferSlot());
        metadata.addProperty("vertexBufferBytes", vertices.length());
        metadata.addProperty("vertexSliceOffset", vertices.offset());
        metadata.addProperty("vertexAllocationId", ((MetalGpuBuffer) vertices.buffer()).allocationId());
        metadata.addProperty("indexAllocationId", indices.allocationId());
        metadata.addProperty("indexBytes", indexType.bytes);
        metadata.addProperty("drawCount", drawCount);
        JsonArray records = new JsonArray();
        for (int i = 0; i < Math.min(drawCount, 8); i++) {
            int offset = commands.position() + i * 3;
            JsonObject record = new JsonObject();
            record.addProperty("firstIndex", commands.get(offset));
            record.addProperty("indexCount", commands.get(offset + 1));
            record.addProperty("baseVertex", commands.get(offset + 2));
            records.add(record);
        }
        metadata.add("commands", records);
        int firstIndex = commands.get(commands.position());
        int indexCount = commands.get(commands.position() + 1);
        int baseVertex = commands.get(commands.position() + 2);
        long vertexOffset = (long) baseVertex * pipeline.vertexStride(0);
        long indexOffset = (long) firstIndex * indexType.bytes;
        if (vertexOffset >= 0 && vertexOffset < vertices.length()
                && indexOffset >= 0 && indexOffset < indices.size() && indexCount > 0) {
            captureBuffer(device, encoder, vertices.slice(vertexOffset,
                    Math.min(64L * pipeline.vertexStride(0), vertices.length() - vertexOffset)), root.resolve("vertices.bin"));
            captureBuffer(device, encoder, indices.slice(indexOffset,
                    Math.min((long) Math.min(indexCount, 96) * indexType.bytes, indices.size() - indexOffset)), root.resolve("indices.bin"));
            for (String name : new String[]{"u_Globals", "push_constants"}) {
                GpuBufferSlice slice = uniforms.get(name);
                if (slice != null) captureBuffer(device, encoder,
                        slice.slice(0, Math.min(slice.length(), name.equals("push_constants") ? 20 : 256)),
                        root.resolve(name + ".bin"));
            }
        } else metadata.addProperty("captureRejected", "draw input range is outside its live buffer");
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve("draw.json"), new GsonBuilder().setPrettyPrinting().create().toJson(metadata));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static void captureBuffer(final MetalDevice device, final MetalCommandEncoder encoder,
                                      final GpuBufferSlice source, final Path path) {
        int bytes = Math.toIntExact(source.length());
        MetalGpuBuffer readback = (MetalGpuBuffer) device.createBuffer(() -> "Sodium input evidence",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
        // These inputs may have GPU writers. Keep their reads out of an upload batch.
        encoder.endEncoder();
        encoder.copyToBuffer(source, readback.slice(0, bytes));
        encoder.endEncoder();
        encoder.queueForDestroy(() -> {
            try {
                byte[] data = new byte[bytes];
                readback.currentStorage().get(data);
                Files.createDirectories(path.getParent());
                Files.write(path, data);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
            finally { readback.close(); }
        });
    }
}
