package com.metallum.client.metal.render;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Collects the producer observations for exactly one source frame.
 *
 * <p>This is deliberately a small transaction, separate from the MetalFX
 * manager.  A target allocation is not producer activity: callers must report
 * an observation at the real Minecraft submit/draw boundary.  A known
 * producer with no exact replay is retained as {@code REACTIVE_ONLY}; an
 * unknown producer is explicitly unsupported; and a domain with no observed
 * source activity is {@code NOT_PRESENT}.  The receipt can be finalized only
 * against the same {@link FrameSynthesisContract.FrameStamp} that opened the
 * transaction.</p>
 */
final class FrameSynthesisReceiptTracker {
    private record OwnerKey(long objectId, long generation) {
    }

    private static final class MutableReceipt {
        int observedSamples;
        int anonymousExactCandidates;
        int anonymousMotionEncoded;
        final java.util.Set<OwnerKey> exactCandidates = new java.util.HashSet<>();
        final java.util.Set<OwnerKey> motionEncoded = new java.util.HashSet<>();
        boolean unsupported;
        boolean reactiveOnly;
        String unsupportedReason;

        void observe(final int samples) {
            if (samples <= 0) {
                return;
            }
            observedSamples = Math.addExact(observedSamples, samples);
        }

        void markUnsupported(final String reason) {
            unsupported = true;
            if (unsupportedReason == null && reason != null && !reason.isBlank()) {
                unsupportedReason = reason;
            }
        }

        void observeReactive(final int samples) {
            observe(samples);
            if (samples > 0) {
                reactiveOnly = true;
            }
        }
    }

    record Finalized(
            FrameSynthesisContract.FrameStamp stamp,
            FrameSynthesisContract.ProducerCoverageSet coverage
    ) {
        Finalized {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(coverage, "coverage");
        }
    }

    private final EnumMap<FrameSynthesisContract.ProducerDomain, MutableReceipt> receipts =
            new EnumMap<>(FrameSynthesisContract.ProducerDomain.class);
    private FrameSynthesisContract.FrameStamp stamp;
    private Finalized finalized;
    private boolean open;
    private boolean invalidated;

    void beginFrame(final FrameSynthesisContract.FrameStamp frameStamp) {
        Objects.requireNonNull(frameStamp, "frameStamp");
        if (open) {
            throw new IllegalStateException("Previous frame receipt transaction is still open");
        }
        receipts.clear();
        for (FrameSynthesisContract.ProducerDomain domain
                : FrameSynthesisContract.ProducerDomain.values()) {
            receipts.put(domain, new MutableReceipt());
        }
        stamp = frameStamp;
        finalized = null;
        invalidated = false;
        open = true;
    }

    void observe(
            final FrameSynthesisContract.ProducerDomain domain,
            final int samples
    ) {
        mutable(domain).observe(samples);
    }

    void observeUnsupported(
            final FrameSynthesisContract.ProducerDomain domain,
            final int samples,
            final String reason
    ) {
        MutableReceipt receipt = mutable(domain);
        receipt.observe(samples);
        receipt.markUnsupported(reason);
    }

    void observeReactive(
            final FrameSynthesisContract.ProducerDomain domain,
            final int samples
    ) {
        mutable(domain).observeReactive(samples);
    }

    void markExactCandidate(final FrameSynthesisContract.ProducerDomain domain) {
        MutableReceipt receipt = mutable(domain);
        if (receipt.anonymousExactCandidates == Integer.MAX_VALUE) {
            throw new IllegalStateException("Exact producer candidate count overflow");
        }
        receipt.anonymousExactCandidates++;
    }

    void recordMotionEncoded(final FrameSynthesisContract.ProducerDomain domain) {
        MutableReceipt receipt = mutable(domain);
        if (receipt.anonymousMotionEncoded == Integer.MAX_VALUE) {
            throw new IllegalStateException("Producer encode count overflow");
        }
        receipt.anonymousMotionEncoded++;
    }

    void markExactCandidate(
            final FrameSynthesisContract.ProducerDomain domain,
            final long objectId,
            final long generation
    ) {
        mutable(domain).exactCandidates.add(new OwnerKey(objectId, generation));
    }

    void recordMotionEncoded(
            final FrameSynthesisContract.ProducerDomain domain,
            final long objectId,
            final long generation
    ) {
        mutable(domain).motionEncoded.add(new OwnerKey(objectId, generation));
    }

    void invalidateForHistoryDiscontinuity() {
        if (open) {
            invalidated = true;
        }
    }

    boolean matches(final FrameSynthesisContract.FrameStamp expected) {
        return open && !invalidated && stamp.equals(expected);
    }

    Finalized finalizeFrame(final FrameSynthesisContract.FrameStamp expected) {
        Objects.requireNonNull(expected, "expected");
        if (!matches(expected)) {
            throw new IllegalStateException(
                    "Cannot finalize producer receipt for a different or discontinuous source frame"
            );
        }
        if (finalized != null) {
            return finalized;
        }

        java.util.ArrayList<FrameSynthesisContract.ProducerReceipt> result =
                new java.util.ArrayList<>(receipts.size());
        for (Map.Entry<FrameSynthesisContract.ProducerDomain, MutableReceipt> entry
                : receipts.entrySet()) {
            MutableReceipt receipt = entry.getValue();
            FrameSynthesisContract.ProducerCoverage coverage;
            if (receipt.unsupported) {
                coverage = FrameSynthesisContract.ProducerCoverage.UNSUPPORTED;
            } else if (receipt.observedSamples == 0) {
                coverage = FrameSynthesisContract.ProducerCoverage.NOT_PRESENT;
            } else if (hasExactCoverage(receipt)) {
                coverage = FrameSynthesisContract.ProducerCoverage.REAL_MOTION;
            } else {
                coverage = FrameSynthesisContract.ProducerCoverage.REACTIVE_ONLY;
            }
            result.add(new FrameSynthesisContract.ProducerReceipt(
                    entry.getKey(), coverage, receipt.observedSamples
            ));
        }
        finalized = new Finalized(
                expected,
                new FrameSynthesisContract.ProducerCoverageSet(result)
        );
        return finalized;
    }

    private static boolean hasExactCoverage(final MutableReceipt receipt) {
        if (receipt.reactiveOnly) {
            return false;
        }
        return receipt.anonymousExactCandidates == 0
                ? !receipt.exactCandidates.isEmpty()
                && receipt.motionEncoded.containsAll(receipt.exactCandidates)
                : receipt.anonymousMotionEncoded >= receipt.anonymousExactCandidates
                && (receipt.exactCandidates.isEmpty()
                || receipt.motionEncoded.containsAll(receipt.exactCandidates));
    }

    void commitSubmittedFrame() {
        if (open) {
            open = false;
        }
    }

    void discardFrame() {
        if (open) {
            open = false;
            finalized = null;
        }
    }

    void reset() {
        open = false;
        finalized = null;
        invalidated = false;
        stamp = null;
        receipts.clear();
    }

    private MutableReceipt mutable(final FrameSynthesisContract.ProducerDomain domain) {
        Objects.requireNonNull(domain, "domain");
        if (!open || invalidated) {
            throw new IllegalStateException("Producer observation is outside the current source frame");
        }
        if (finalized != null) {
            throw new IllegalStateException("Producer receipt has already been finalized");
        }
        MutableReceipt receipt = receipts.get(domain);
        if (receipt == null) {
            throw new IllegalStateException("Missing receipt domain " + domain);
        }
        return receipt;
    }
}
