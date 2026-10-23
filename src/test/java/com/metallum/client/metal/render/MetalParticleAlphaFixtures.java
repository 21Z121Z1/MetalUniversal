package com.metallum.client.metal.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Real Minecraft allocations: capture before overwriting/closing the source slices. */
final class MetalParticleAlphaFixtures {
    private MetalParticleAlphaFixtures() {}

    static ByteBuffer capture(MetalPreviousVertexHistory.DrawToken token, VertexFormat format,
                              PrimitiveTopology topology, ByteBuffer mesh, int vertices, int indices) {
        int stride = format.getVertexSize(), firstBytes = Math.max(1, vertices / 2) * stride;
        int total = vertices * stride, secondBytes = total - firstBytes;
        try (var a = new ByteBufferBuilder(Math.max(firstBytes, 64));
             var b = new ByteBufferBuilder(Math.max(secondBytes, 64))) {
            ByteBuffer first = mesh.duplicate(); first.position(0).limit(firstBytes);
            MemoryUtil.memByteBuffer(a.reserve(firstBytes), firstBytes).put(first);
            if (secondBytes > 0) {
                ByteBuffer second = mesh.duplicate(); second.position(firstBytes).limit(total);
                MemoryUtil.memByteBuffer(b.reserve(secondBytes), secondBytes).put(second);
            }
            try (var x = a.build(); var y = secondBytes > 0 ? b.build() : null) {
                assertNotNull(x); if (secondBytes > 0) assertNotNull(y);
                var slices = y == null ? List.of(x) : List.of(x, y);
                // Same shipping entry called from the HEAD of Draw.freeVertexData.
                MetalPreviousVertexHistory.capture(token, format, topology, slices, vertices, indices);
                ByteBuffer uploaded = bytes(total);
                for (var slice : slices) uploaded.put(slice.byteBuffer().duplicate());
                uploaded.flip();
                // The real uploaded bytes stay in 'uploaded'. Mutating still-live source storage
                // and then freeing it must not upgrade a zero-alpha history snapshot.
                int alpha = format.getElement("Color").offset() + 3;
                for (var slice : slices) {
                    ByteBuffer retired = slice.byteBuffer();
                    for (int p = retired.position(); p < retired.limit(); p += stride) retired.put(p + alpha, (byte)255);
                }
                return uploaded;
            }
        }
    }

    static ByteBuffer mesh(List<Long> ids, boolean pair, int aAlpha) {
        var format = DefaultVertexFormat.PARTICLE;
        ByteBuffer out = bytes(ids.size() * 4 * format.getVertexSize());
        float[][] corners = {{-1,-1},{1,-1},{1,1},{-1,1}};
        for (int q = 0; q < ids.size(); q++) {
            long id = ids.get(q); float center = pair ? id == 11 ? -.5F : .5F : 0;
            float halfX = pair ? .25F : .5F;
            for (int v = 0; v < 4; v++) {
                int base = (q * 4 + v) * format.getVertexSize();
                out.putFloat(base, center + corners[v][0] * halfX).putFloat(base + 4, corners[v][1] * .5F).putFloat(base + 8, .5F);
                int color = base + format.getElement("Color").offset();
                out.put(color, (byte)255).put(color + 1, (byte)0).put(color + 2, (byte)0)
                        .put(color + 3, (byte)(id == 11 ? aAlpha : 255));
                int uv = base + format.getElement("UV0").offset(); out.putFloat(uv, .25F).putFloat(uv + 4, .5F);
            }
        }
        return out;
    }

    static ByteBuffer bytes(int size) {return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());}
}
