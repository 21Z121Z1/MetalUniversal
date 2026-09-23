package com.metallum.client.validation.contract;

/** Bounded selector for expensive per-producer manifest evidence. */
public record ProducerCapturePolicy(
        boolean enabled,
        String semanticPassSelector,
        int firstProducer,
        int lastProducer,
        int maxDetailedProducers
) {
    public ProducerCapturePolicy {
        semanticPassSelector = semanticPassSelector == null ? "" : semanticPassSelector.trim();
        if (firstProducer < 0 || lastProducer < firstProducer || maxDetailedProducers <= 0) {
            throw new IllegalArgumentException("Invalid producer capture policy");
        }
    }

    public static ProducerCapturePolicy fromSystemProperties(final boolean defaultEnabled) {
        boolean enabled = Boolean.parseBoolean(System.getProperty(
                "metallum.renderContract.captureProducers",
                Boolean.toString(defaultEnabled)
        ));
        String selector = System.getProperty(
                "metallum.renderContract.tracePass",
                System.getProperty("metallum.validation.tracePass", "")
        );
        String range = System.getProperty(
                "metallum.renderContract.producerRange",
                System.getProperty("metallum.validation.producerRange", "")
        ).trim();
        int first = 0;
        int last = Integer.MAX_VALUE;
        if (!range.isEmpty()) {
            String[] parts = range.split(":", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "producerRange must use inclusive start:end syntax: " + range
                );
            }
            first = parseNonNegative(parts[0], "producerRange start");
            last = parseNonNegative(parts[1], "producerRange end");
        }
        int maxDetailed = integerProperty("metallum.renderContract.maxDetailedProducers", 1_000_000);
        return new ProducerCapturePolicy(enabled, selector, first, last, maxDetailed);
    }

    public boolean matchesPass(final String semanticPassId) {
        return semanticPassSelector.isEmpty() || semanticPassSelector.equals(semanticPassId);
    }

    public boolean captures(final String semanticPassId, final int producerIndex, final int currentDetails) {
        return enabled
                && matchesPass(semanticPassId)
                && producerIndex >= firstProducer
                && producerIndex <= lastProducer
                && currentDetails < maxDetailedProducers;
    }

    /**
     * A producer detail stream is complete only when the selected pass was
     * observed without an intentional range or detail-count boundary. The
     * old implementation inferred completeness from the configured defaults,
     * which could claim a complete stream after a runtime budget had already
     * truncated it.
     */
    public boolean completeForPass(
            final String semanticPassId,
            final int observedProducerCount,
            final boolean producerDetailsTruncated
    ) {
        return enabled
                && matchesPass(semanticPassId)
                && firstProducer == 0
                && (lastProducer == Integer.MAX_VALUE
                        || observedProducerCount == 0
                        || lastProducer >= observedProducerCount - 1)
                && observedProducerCount <= maxDetailedProducers
                && !producerDetailsTruncated;
    }

    public String descriptor() {
        return "enabled=" + enabled
                + ",pass=" + (semanticPassSelector.isEmpty() ? "*" : semanticPassSelector)
                + ",range=" + firstProducer + ":" + (lastProducer == Integer.MAX_VALUE ? "*" : lastProducer)
                + ",maxDetailed=" + maxDetailedProducers;
    }

    private static int parseNonNegative(final String value, final String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(field + " must be a non-negative integer: " + value);
        }
    }

    private static int integerProperty(final String name, final int fallback) {
        try {
            int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(name + " must be a positive integer");
        }
    }
}
