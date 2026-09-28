package com.metallum.client.metal.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Adds an R8 MRT receipt to the original RenderPearl fragment program.
 *
 * <p>No sampling, alpha comparison, derivative, geometry, or existing output is
 * rewritten. A new scalar output receives 1 immediately before each normal
 * return of the fragment entry point; killed/demoted invocations cannot write
 * a render target. The original SPIR-V is never mutated. This is a shader
 * translation operation, not a second GLSL frontend or a Vulkan runtime.</p>
 */
final class MetalFxCutoutSpirv {
    // SPIR-V unified core grammar. Keep opcode names visible to the structural
    // contracts; hosted tests execute the translated shipping MSL as well.
    private static final int MAGIC = 0x07230203;
    private static final int ENTRY_POINT = 15, TYPE_FLOAT = 22, TYPE_POINTER = 32;
    private static final int CONSTANT = 43, FUNCTION = 54, FUNCTION_END = 56;
    private static final int VARIABLE = 59, STORE = 62, DECORATE = 71;
    private static final int KILL = 252, RETURN = 253, RETURN_VALUE = 254;
    private static final int TERMINATE_INVOCATION = 4416, DEMOTE_TO_HELPER = 5380;
    private static final int FRAGMENT = 4, OUTPUT = 3, LOCATION = 30;
    static final int COVERAGE_LOCATION = 1;

    private MetalFxCutoutSpirv() {}

    static Optional<byte[]> addCoverage(final ByteBuffer module) {
        ByteBuffer input = module.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        if (input.remaining() < 20 || input.remaining() % Integer.BYTES != 0) {
            throw new IllegalArgumentException("Truncated SPIR-V header/word");
        }
        int[] words = new int[input.remaining() / Integer.BYTES];
        input.asIntBuffer().get(words);
        if (words[0] != MAGIC || words[3] <= 0 || words[4] != 0) {
            throw new IllegalArgumentException("Invalid SPIR-V module header");
        }
        List<int[]> instructions = new ArrayList<>();
        for (int cursor = 5; cursor < words.length;) {
            int count = words[cursor] >>> 16;
            if (count == 0 || count > words.length - cursor) {
                throw new IllegalArgumentException("Invalid SPIR-V instruction length at " + cursor);
            }
            instructions.add(Arrays.copyOfRange(words, cursor, cursor + count));
            cursor += count;
        }
        boolean discards = instructions.stream().anyMatch(i -> {
            int op = i[0] & 0xffff;
            return op == KILL || op == TERMINATE_INVOCATION || op == DEMOTE_TO_HELPER;
        });
        if (!discards) return Optional.empty();

        int entry = -1, floatType = -1;
        int entryCount = 0;
        Set<Integer> outputs = new HashSet<>();
        for (int[] i : instructions) {
            int op = i[0] & 0xffff;
            if (op == ENTRY_POINT) {
                require(i.length >= 4 && i[1] == FRAGMENT, "Expected one fragment entry point");
                entry = i[2];
                entryCount++;
            } else if (op == TYPE_FLOAT && i.length == 3 && i[2] == 32) {
                floatType = i[1];
            } else if (op == VARIABLE && i.length >= 4 && i[3] == OUTPUT) {
                outputs.add(i[2]);
            }
        }
        require(entryCount == 1 && entry > 0 && floatType > 0, "Unsupported fragment interface");
        boolean colorZero = false;
        int pointerType = -1, one = -1;
        for (int[] i : instructions) {
            int op = i[0] & 0xffff;
            if (op == DECORATE && i.length >= 4 && outputs.contains(i[1]) && i[2] == LOCATION) {
                require(i[3] == 0, "Fragment already owns an auxiliary color location");
                colorZero = true;
            } else if (op == TYPE_POINTER && i.length == 4 && i[2] == OUTPUT && i[3] == floatType) {
                pointerType = i[1];
            } else if (op == CONSTANT && i.length == 4 && i[1] == floatType
                    && i[3] == Float.floatToRawIntBits(1.0f)) {
                one = i[2];
            }
        }
        require(colorZero, "Coverage requires the original location-zero scene output");
        int next = words[3];
        boolean newPointer = pointerType < 0, newOne = one < 0;
        if (newPointer) pointerType = next++;
        if (newOne) one = next++;
        int coverage = next++;
        require(next > coverage && coverage > 0, "SPIR-V id bound overflow");

        List<int[]> transformed = new ArrayList<>();
        boolean annotated = false, declared = false;
        int function = -1, returns = 0;
        for (int[] original : instructions) {
            int op = original[0] & 0xffff;
            if (!annotated && op >= 19 && op <= 39) {
                transformed.add(instruction(DECORATE, coverage, LOCATION, COVERAGE_LOCATION));
                annotated = true;
            }
            if (!declared && op == FUNCTION) {
                if (newPointer) transformed.add(instruction(TYPE_POINTER, pointerType, OUTPUT, floatType));
                if (newOne) transformed.add(instruction(CONSTANT, floatType, one, Float.floatToRawIntBits(1f)));
                transformed.add(instruction(VARIABLE, pointerType, coverage, OUTPUT));
                declared = true;
            }
            if (op == FUNCTION) {
                require(original.length == 5, "Malformed OpFunction");
                function = original[2];
            }
            if (function == entry && op == RETURN) {
                transformed.add(instruction(STORE, coverage, one));
                returns++;
            }
            require(function != entry || op != RETURN_VALUE, "Fragment entry must return void");
            if (op == ENTRY_POINT) {
                int[] expanded = Arrays.copyOf(original, original.length + 1);
                require(expanded.length <= 0xffff, "Fragment interface too large");
                expanded[0] = (expanded.length << 16) | ENTRY_POINT;
                expanded[expanded.length - 1] = coverage;
                transformed.add(expanded);
            } else {
                transformed.add(original);
            }
            if (op == FUNCTION_END) function = -1;
        }
        require(annotated && declared && returns > 0, "No normal fragment exit for coverage");
        int size = 5 + transformed.stream().mapToInt(i -> i.length).sum();
        ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(size, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < 5; index++) result.putInt(index == 3 ? next : words[index]);
        for (int[] i : transformed) for (int word : i) result.putInt(word);
        return Optional.of(result.array());
    }

    private static int[] instruction(int op, int... operands) {
        int[] result = new int[operands.length + 1];
        result[0] = (result.length << 16) | op;
        System.arraycopy(operands, 0, result, 1, operands.length);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
