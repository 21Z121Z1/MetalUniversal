package com.metallum.client.metal.render;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Renderer-owned identity for one native Metal allocation generation.
 *
 * <p>The allocation id is process-wide across textures and buffers. The
 * generation is monotonic for a logical resource label, so replacing a
 * backing cannot silently retain the old generation. Validation may observe
 * this value, but never allocates or advances it.</p>
 */
record MetalAllocationIdentity(long allocationId, long generation) {
    MetalAllocationIdentity {
        if (allocationId <= 0L || generation <= 0L) {
            throw new IllegalArgumentException("Metal allocation identity values must be positive");
        }
    }

    static MetalAllocationIdentity allocate(final String logicalLabel) {
        return MetalAllocationIdentityAuthority.allocate(logicalLabel);
    }
}

/** The single process-wide allocation/generation authority for Metal resources. */
final class MetalAllocationIdentityAuthority {
    private static final AtomicLong NEXT_ALLOCATION_ID = new AtomicLong(1L);
    private static final ConcurrentHashMap<String, AtomicLong> NEXT_GENERATION_BY_LABEL =
            new ConcurrentHashMap<>();

    private MetalAllocationIdentityAuthority() {
    }

    static MetalAllocationIdentity allocate(final String logicalLabel) {
        String normalizedLabel = normalize(logicalLabel);
        long allocationId = NEXT_ALLOCATION_ID.getAndIncrement();
        long generation = NEXT_GENERATION_BY_LABEL
                .computeIfAbsent(normalizedLabel, ignored -> new AtomicLong())
                .incrementAndGet();
        return new MetalAllocationIdentity(allocationId, generation);
    }

    private static String normalize(final String logicalLabel) {
        if (logicalLabel == null || logicalLabel.isBlank()) {
            return "metal-resource";
        }
        return logicalLabel;
    }
}
