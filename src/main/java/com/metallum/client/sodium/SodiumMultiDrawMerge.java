package com.metallum.client.sodium;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMultiDrawIndexedInfoEXT;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/** Conservative contiguous-range merge for Sodium's VK_MULTIDRAW command ABI. */
public final class SodiumMultiDrawMerge {
    static final long MAX_SHARED_INDEX_ELEMENTS = 16_384L * 6L;
    private static final int STRIDE = VkMultiDrawIndexedInfoEXT.SIZEOF / Integer.BYTES;
    private static final int COUNT = VkMultiDrawIndexedInfoEXT.INDEXCOUNT / Integer.BYTES;
    private static final int FIRST = VkMultiDrawIndexedInfoEXT.FIRSTINDEX / Integer.BYTES;
    private static final int BASE = VkMultiDrawIndexedInfoEXT.VERTEXOFFSET / Integer.BYTES;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private SodiumMultiDrawMerge() {
    }

    public static Result merge(long sourceAddress, int drawCount) {
        if (sourceAddress == 0L || drawCount < 2) return null;

        Scratch scratch = SCRATCH.get();
        IntBuffer output = scratch.ensure(drawCount * STRIDE);
        output.clear();

        long currentCount = uint(read(sourceAddress, 0, COUNT));
        long currentFirst = uint(read(sourceAddress, 0, FIRST));
        long currentBase = uint(read(sourceAddress, 0, BASE));
        int out = 0;

        for (int input = 1; input < drawCount; input++) {
            long nextCount = uint(read(sourceAddress, input, COUNT));
            long nextFirst = uint(read(sourceAddress, input, FIRST));
            long nextBase = uint(read(sourceAddress, input, BASE));

            if (canMerge(currentCount, currentFirst, currentBase, nextCount, nextFirst, nextBase)) {
                currentCount += nextCount;
                continue;
            }

            write(output, out++, currentCount, currentFirst, currentBase);
            currentCount = nextCount;
            currentFirst = nextFirst;
            currentBase = nextBase;
        }
        write(output, out++, currentCount, currentFirst, currentBase);

        if (out == drawCount) return null;
        output.limit(out * STRIDE);
        output.position(0);
        return new Result(output, out, drawCount - out);
    }

    static boolean canMerge(
            long currentCount,
            long currentFirst,
            long currentBase,
            long nextCount,
            long nextFirst,
            long nextBase
    ) {
        if (currentFirst != 0L || nextFirst != 0L || currentCount == 0L || nextCount == 0L) return false;
        if (currentCount % 6L != 0L) return false;
        if (currentCount + nextCount > MAX_SHARED_INDEX_ELEMENTS) return false;
        long currentVertices = (currentCount / 6L) * 4L;
        return nextBase == currentBase + currentVertices;
    }

    private static int read(long address, int command, int field) {
        return MemoryUtil.memGetInt(address + (long) command * VkMultiDrawIndexedInfoEXT.SIZEOF
                + (long) field * Integer.BYTES);
    }

    private static void write(IntBuffer output, int command, long count, long first, long base) {
        int offset = command * STRIDE;
        for (int i = 0; i < STRIDE; i++) output.put(offset + i, 0);
        output.put(offset + COUNT, (int) count);
        output.put(offset + FIRST, (int) first);
        output.put(offset + BASE, (int) base);
    }

    private static long uint(int value) {
        return Integer.toUnsignedLong(value);
    }

    public record Result(IntBuffer commands, int drawCount, int mergedRanges) {}

    private static final class Scratch {
        private ByteBuffer bytes = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

        IntBuffer ensure(int ints) {
            int bytesNeeded = Math.multiplyExact(ints, Integer.BYTES);
            if (bytes.capacity() < bytesNeeded) {
                int capacity = Math.max(bytesNeeded, Math.max(1024, bytes.capacity() * 2));
                bytes = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
            }
            return bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        }
    }
}
