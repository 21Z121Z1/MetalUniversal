package com.metallum.client.metal.render;

/**
 * Process-stable identity for one shader resource name.
 *
 * <p>The token is deliberately backend-neutral: the current Java binding cache
 * uses {@link #id()} immediately, while the later command-stream/argument-table
 * phase can attach pipeline-local physical slots without changing callers.</p>
 */
public record MetalBindingToken(int id, int logicalStorageBinding, int flags) {
    public static final int INVALIDATES_GENERATED_IRIS_BLOCK = 1;

    public MetalBindingToken {
        if (id < 0) {
            throw new IllegalArgumentException("Metal binding token id must be non-negative");
        }
    }

    public boolean invalidatesGeneratedIrisBlock() {
        return (flags & INVALIDATES_GENERATED_IRIS_BLOCK) != 0;
    }

    public boolean isStorageBufferDescriptor() {
        return logicalStorageBinding >= 0;
    }
}
