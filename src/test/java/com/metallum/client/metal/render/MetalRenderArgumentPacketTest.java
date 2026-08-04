package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MetalRenderArgumentPacketTest {
    @Test
    void writesCompiledStageIdsAndResourceMetadata() {
        MetalCompiledRenderPipeline.ResourceBinding texture = binding(
                MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                "u_Texture",
                0,
                1,
                2,
                3
        );
        MetalCompiledRenderPipeline.ResourceBinding buffer = binding(
                MetalCompiledRenderPipeline.ResourceKind.STORAGE_BUFFER,
                "u_Storage",
                4,
                -1,
                -1,
                -1
        );

        try (MetalRenderArgumentPacket packet = new MetalRenderArgumentPacket()) {
            packet.reset();
            packet.appendTexture(texture, MemorySegment.ofAddress(0x1000L), true);
            packet.appendSampler(texture, MemorySegment.ofAddress(0x2000L));
            packet.appendBuffer(buffer, MemorySegment.ofAddress(0x3000L), 128L, false);

            assertEquals(3, packet.entryCount());
            assertEquals(
                    MetalRenderArgumentPacket.HEADER_SIZE + 3 * MetalRenderArgumentPacket.ENTRY_SIZE,
                    packet.finish()
            );

            MemorySegment storage = packet.storage();
            assertEquals(MetalRenderArgumentPacket.MAGIC, storage.get(ValueLayout.JAVA_INT, 0L));
            assertEquals(MetalRenderArgumentPacket.VERSION, storage.get(ValueLayout.JAVA_INT, 4L));
            assertEquals(3, storage.get(ValueLayout.JAVA_INT, 12L));

            long textureEntry = MetalRenderArgumentPacket.HEADER_SIZE;
            assertEquals(MetalRenderArgumentPacket.KIND_TEXTURE,
                    storage.get(ValueLayout.JAVA_INT, textureEntry));
            assertEquals(MetalCompiledRenderPipeline.STAGE_ALL,
                    storage.get(ValueLayout.JAVA_INT, textureEntry + 4L));
            assertEquals(0, storage.get(ValueLayout.JAVA_INT, textureEntry + 8L));
            assertEquals(2, storage.get(ValueLayout.JAVA_INT, textureEntry + 12L));
            assertEquals(0x1000L, storage.get(ValueLayout.JAVA_LONG, textureEntry + 16L));
            assertEquals(MetalRenderArgumentPacket.FLAG_WRITABLE,
                    storage.get(ValueLayout.JAVA_INT, textureEntry + 32L));

            long samplerEntry = textureEntry + MetalRenderArgumentPacket.ENTRY_SIZE;
            assertEquals(MetalRenderArgumentPacket.KIND_SAMPLER,
                    storage.get(ValueLayout.JAVA_INT, samplerEntry));
            assertEquals(1, storage.get(ValueLayout.JAVA_INT, samplerEntry + 8L));
            assertEquals(3, storage.get(ValueLayout.JAVA_INT, samplerEntry + 12L));

            long bufferEntry = samplerEntry + MetalRenderArgumentPacket.ENTRY_SIZE;
            assertEquals(MetalCompiledRenderPipeline.STAGE_VERTEX,
                    storage.get(ValueLayout.JAVA_INT, bufferEntry + 4L));
            assertEquals(4, storage.get(ValueLayout.JAVA_INT, bufferEntry + 8L));
            assertEquals(-1, storage.get(ValueLayout.JAVA_INT, bufferEntry + 12L));
            assertEquals(128L, storage.get(ValueLayout.JAVA_LONG, bufferEntry + 24L));
        }
    }

    @Test
    void rejectsZeroExecutionAndInvalidBindings() {
        MetalCompiledRenderPipeline.ResourceBinding directOnly = new MetalCompiledRenderPipeline.ResourceBinding(
                MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                "push_constants",
                0,
                MetalCompiledRenderPipeline.STAGE_ALL,
                (GpuFormat) null
        );
        try (MetalRenderArgumentPacket packet = new MetalRenderArgumentPacket()) {
            packet.reset();
            assertThrows(IllegalStateException.class, packet::finish);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> packet.appendBuffer(directOnly, MemorySegment.ofAddress(1L), 0L, false)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> packet.appendTexture(
                            binding(MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                                    "u_Texture", 0, 1, -1, -1),
                            MemorySegment.NULL,
                            false
                    )
            );
        }
    }

    @Test
    void boundedCapacityFailsBeforeWritingPastStorage() {
        MetalCompiledRenderPipeline.ResourceBinding buffer = binding(
                MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                "u_Buffer",
                0,
                -1,
                -1,
                -1
        );
        try (MetalRenderArgumentPacket packet = new MetalRenderArgumentPacket()) {
            packet.reset();
            for (int index = 0; index < MetalRenderArgumentPacket.MAX_ENTRIES; index++) {
                packet.appendBuffer(buffer, MemorySegment.ofAddress(index + 1L), 0L, false);
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> packet.appendBuffer(buffer, MemorySegment.ofAddress(0x1001L), 0L, false)
            );
        }
    }

    private static MetalCompiledRenderPipeline.ResourceBinding binding(
            final MetalCompiledRenderPipeline.ResourceKind kind,
            final String name,
            final int vertexPrimary,
            final int vertexSampler,
            final int fragmentPrimary,
            final int fragmentSampler
    ) {
        return new MetalCompiledRenderPipeline.ResourceBinding(
                kind,
                name,
                0,
                MetalCompiledRenderPipeline.STAGE_ALL,
                null,
                vertexPrimary,
                vertexSampler,
                fragmentPrimary,
                fragmentSampler
        );
    }
}
