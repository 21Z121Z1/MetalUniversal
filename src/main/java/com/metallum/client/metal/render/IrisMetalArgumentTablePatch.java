package com.metallum.client.metal.render;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable dirty-entry batch for the future Metal 3 argument-buffer / Metal
 * 4 argument-table execution call.
 *
 * <p>The patch is intentionally separate from the existing native setter
 * path. It freezes the ABI and gives diagnostics a single, deterministic
 * authority to compare against the legacy replay. Until a negotiated native
 * argument-table entry point is present, callers must keep using the setter
 * fallback and this patch remains an activation seam.</p>
 */
public final class IrisMetalArgumentTablePatch {
    public enum Kind { BUFFER, TEXTURE, SAMPLER }

    public record Entry(Kind kind, int index, long address, long offset) {
        public Entry {
            Objects.requireNonNull(kind, "kind");
            if (index < 0 || address < 0L || offset < 0L) {
                throw new IllegalArgumentException("argument patch entry has invalid identity");
            }
        }
    }

    private final long layoutHash;
    private final long snapshotGeneration;
    private final List<Entry> entries;
    private final boolean admitted;
    private final String reason;

    private IrisMetalArgumentTablePatch(
            final long layoutHash,
            final long snapshotGeneration,
            final List<Entry> entries,
            final boolean admitted,
            final String reason
    ) {
        if (layoutHash == 0L) {
            throw new IllegalArgumentException("argument patch layout hash must be non-zero");
        }
        if (snapshotGeneration < 0L) {
            throw new IllegalArgumentException("argument patch generation must be non-negative");
        }
        this.layoutHash = layoutHash;
        this.snapshotGeneration = snapshotGeneration;
        this.entries = List.copyOf(entries);
        this.admitted = admitted;
        this.reason = requireReason(reason);
    }

    static IrisMetalArgumentTablePatch from(final IrisMetalArgumentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<Entry> entries = new ArrayList<>();
        snapshot.dirtyBuffers().stream().forEach(index -> entries.add(new Entry(
                Kind.BUFFER,
                index,
                address(snapshot.buffer(index)),
                snapshot.bufferOffset(index)
        )));
        snapshot.dirtyTextures().stream().forEach(index -> entries.add(new Entry(
                Kind.TEXTURE,
                index,
                address(snapshot.texture(index)),
                0L
        )));
        snapshot.dirtySamplers().stream().forEach(index -> entries.add(new Entry(
                Kind.SAMPLER,
                index,
                address(snapshot.sampler(index)),
                0L
        )));
        return new IrisMetalArgumentTablePatch(
                snapshot.layout().stableHash(),
                snapshot.generation(),
                entries,
                true,
                entries.isEmpty() ? "no-dirty-bindings" : "dirty-bindings"
        );
    }

    public static IrisMetalArgumentTablePatch rejected(final String reason) {
        return new IrisMetalArgumentTablePatch(
                1L,
                0L,
                List.of(),
                false,
                reason
        );
    }

    public long layoutHash() {
        return layoutHash;
    }

    public long snapshotGeneration() {
        return snapshotGeneration;
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean admitted() {
        return admitted;
    }

    public String reason() {
        return reason;
    }

    public boolean hasWork() {
        return admitted && !entries.isEmpty();
    }

    public int byteCount() {
        // Native ABI v1 reserves 32 bytes per entry (kind/index/address/
        // offset) and a fixed 32-byte header. This is a contract estimate,
        // not an allocation; the negotiated bridge remains the authority.
        return Math.addExact(32, Math.multiplyExact(entries.size(), 32));
    }

    private static long address(final MemorySegment segment) {
        return segment == null ? 0L : segment.address();
    }

    private static String requireReason(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("argument patch reason must not be blank");
        }
        return value;
    }
}
