package com.metallum.e2e;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts the complete lifetime of one Vanilla section-dispatcher task invocation. */
public final class StationaryTaskActivity {
    private final AtomicInteger activeInvocations = new AtomicInteger();

    public int activeInvocations() {
        return activeInvocations.get();
    }

    public void run(Runnable operation) {
        activeInvocations.incrementAndGet();
        try {
            operation.run();
        } finally {
            activeInvocations.decrementAndGet();
        }
    }
}
