package com.metallum.client.metal.render;

/** Caps submitted GPU work without changing the three-slot storage/retirement lifetime. */
final class MetalSubmissionBackpressure {
    private final int limit;

    MetalSubmissionBackpressure(final String configured) {
        int parsed = MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT;
        if (configured != null) {
            try { parsed = Integer.parseInt(configured); }
            catch (NumberFormatException ignored) { }
        }
        this.limit = Math.clamp(parsed, 1, MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT);
    }

    int limit() { return this.limit; }

    /** This submission must have completed before nextIndex can be committed. */
    long completionRequiredBefore(final long nextIndex) {
        return nextIndex - this.limit;
    }
}
