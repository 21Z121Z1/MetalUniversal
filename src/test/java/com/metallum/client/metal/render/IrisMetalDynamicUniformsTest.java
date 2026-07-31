package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that core draw-local values are materialized at draw time. */
final class IrisMetalDynamicUniformsTest {
    @Test
    void materializesDrawLocalValuesAndDoesNotInventMissingTextureState() {
        List<IrisMetalGlslLinker.UniformMember> layout = List.of(
                member("int", "entityId", 0, 0),
                member("ivec2", "atlasSize", 0, 16),
                member("int", "gtextureId", 0, 24),
                member("ivec2", "gtextureSize", 0, 32),
                member("int", "textureReloadCount", 0, 40),
                member("ivec4", "blendFunc", 0, 48),
                member("int", "renderStage", 0, 64)
        );
        ByteBuffer base = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());
        ByteBuffer output = ByteBuffer.allocate(80).order(ByteOrder.nativeOrder());

        IrisMetalUniformValues.materializeCoreDrawUniforms(
                base,
                layout,
                output,
                null,
                null,
                7,
                42,
                11,
                new IrisMetalUniformValues.DrawUniformContext(
                        null,
                        128,
                        64,
                        Optional.of(BlendFunction.ADDITIVE)
                )
        );

        assertEquals(42, output.getInt(0));
        assertEquals(128, output.getInt(16));
        assertEquals(64, output.getInt(20));
        assertEquals(0, output.getInt(24), "missing gtexture must not use a dummy id");
        assertEquals(0, output.getInt(32));
        assertEquals(0, output.getInt(36));
        assertEquals(11, output.getInt(40));
        assertArrayEquals(new int[]{1, 1, 1, 1}, ints(output, 48, 4));
        assertEquals(7, output.getInt(64));
    }

    @Test
    void absentBlendFunctionIsRepresentedAsNoBlend() {
        assertArrayEquals(
                new int[]{0, 0, 0, 0},
                IrisMetalUniformValues.irisBlendFunc(Optional.empty())
        );
    }

    private static IrisMetalGlslLinker.UniformMember member(
            final String type,
            final String name,
            final int arrayCount,
            final int offset
    ) {
        int byteSize = type.equals("ivec4") ? 16 : type.equals("ivec2") ? 8 : 4;
        return new IrisMetalGlslLinker.UniformMember(type, name, arrayCount, offset, byteSize);
    }

    private static int[] ints(final ByteBuffer buffer, final int offset, final int count) {
        int[] values = new int[count];
        for (int index = 0; index < count; index++) {
            values[index] = buffer.getInt(offset + index * Integer.BYTES);
        }
        return values;
    }
}
