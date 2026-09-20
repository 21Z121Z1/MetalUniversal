package com.metallum.client.metal.render;

import java.util.concurrent.atomic.LongAdder;

/**
 * Optional fact counters for the compatibility numeric binding surface.
 *
 * <p>Enable before JVM startup with
 * {@code -Dmetallum.validation.numericBindings=true}. Disabled mode performs
 * only one predictable branch at each counter site after class initialization;
 * disabled counter calls do not allocate or synchronize. These counters describe call and lookup work; they are not CPU
 * timers and must not be interpreted as elapsed-cost measurements.</p>
 */
public final class NumericBindingDiagnostics {
    public static final String PROPERTY = "metallum.validation.numericBindings";
    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private static final LongAdder numericStorageBufferCalls = new LongAdder();
    private static final LongAdder numericUniformCalls = new LongAdder();
    private static final LongAdder resourceScanSteps = new LongAdder();
    private static final LongAdder descriptorNameParseCalls = new LongAdder();
    private static final LongAdder numericToNameDispatches = new LongAdder();

    private NumericBindingDiagnostics() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    static void recordNumericStorageBufferCall() {
        if (ENABLED) numericStorageBufferCalls.increment();
    }

    static void recordNumericUniformCall() {
        if (ENABLED) numericUniformCalls.increment();
    }

    static void recordResourceScanStep() {
        if (ENABLED) resourceScanSteps.increment();
    }

    static void recordDescriptorNameParse() {
        if (ENABLED) descriptorNameParseCalls.increment();
    }

    static void recordNumericToNameDispatch() {
        if (ENABLED) numericToNameDispatches.increment();
    }

    /** Test-only reset; production reports use process lifetime and do not reset live adders. */
    static void reset() {
        numericStorageBufferCalls.reset();
        numericUniformCalls.reset();
        resourceScanSteps.reset();
        descriptorNameParseCalls.reset();
        numericToNameDispatches.reset();
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                ENABLED,
                numericStorageBufferCalls.sum(),
                numericUniformCalls.sum(),
                resourceScanSteps.sum(),
                descriptorNameParseCalls.sum(),
                numericToNameDispatches.sum()
        );
    }

    public record Snapshot(
            boolean enabled,
            long numericStorageBufferCalls,
            long numericUniformCalls,
            long resourceScanSteps,
            long descriptorNameParseCalls,
            long numericToNameDispatches
    ) {
    }
}
