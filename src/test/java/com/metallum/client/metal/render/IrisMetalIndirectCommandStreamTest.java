package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class IrisMetalIndirectCommandStreamTest {
    @Test
    void compatibleDrawsShareOneBatch() {
        IrisMetalIndirectCommandStream stream = new IrisMetalIndirectCommandStream();
        stream.append("terrain", "gbuffer", 42L,
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0));
        stream.append("terrain", "gbuffer", 42L,
                new IrisMetalIndirectCommandStream.IndexedDraw(18, 1, 12, 4, 0));

        List<IrisMetalIndirectCommandStream.Batch> batches = stream.finish();
        assertEquals(1, batches.size());
        assertEquals(2, batches.getFirst().draws().size());
    }

    @Test
    void pipelineAttachmentOrAbiChangeSplitsBatch() {
        IrisMetalIndirectCommandStream stream = new IrisMetalIndirectCommandStream();
        stream.append("terrain", "gbuffer", 42L,
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0));
        stream.append("terrain-shadow", "shadow", 42L,
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0));
        stream.append("terrain-shadow", "shadow", 43L,
                new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, 0, 0));

        assertEquals(3, stream.finish().size());
    }

    @Test
    void packedSodiumCommandsCopyInOriginalOrder() {
        ByteBuffer storage = MemoryUtil.memAlloc(2 * 20).order(ByteOrder.nativeOrder());
        try {
            IntBuffer commands = storage.asIntBuffer();
            commands.put(new int[]{12, 1, 0, -4, 0, 18, 2, 12, 7, 1});
            assertEquals(
                    List.of(
                            new IrisMetalIndirectCommandStream.IndexedDraw(12, 1, 0, -4, 0),
                            new IrisMetalIndirectCommandStream.IndexedDraw(18, 2, 12, 7, 1)
                    ),
                    IrisMetalIndirectCommandStream.copyIndexedCommands(MemoryUtil.memAddress(storage), 2)
            );
        } finally {
            MemoryUtil.memFree(storage);
        }
    }
}
