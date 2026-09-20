package com.metallum.client.metal.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NumericBindingDiagnosticsTest {
    @Test
    void gateIsExplicitAndCountersAreResettableFacts() {
        NumericBindingDiagnostics.reset();
        NumericBindingDiagnostics.Snapshot before = NumericBindingDiagnostics.snapshot();
        assertEquals(Boolean.getBoolean(NumericBindingDiagnostics.PROPERTY), before.enabled());
        assertTrue(before.numericStorageBufferCalls() >= 0L);
        assertTrue(before.numericUniformCalls() >= 0L);
        assertTrue(before.resourceScanSteps() >= 0L);
        assertTrue(before.descriptorNameParseCalls() >= 0L);
        assertTrue(before.numericToNameDispatches() >= 0L);

        NumericBindingDiagnostics.recordNumericStorageBufferCall();
        NumericBindingDiagnostics.recordNumericUniformCall();
        NumericBindingDiagnostics.recordResourceScanStep();
        NumericBindingDiagnostics.recordDescriptorNameParse();
        NumericBindingDiagnostics.recordNumericToNameDispatch();
        NumericBindingDiagnostics.Snapshot after = NumericBindingDiagnostics.snapshot();
        if (before.enabled()) {
            assertEquals(1L, after.numericStorageBufferCalls());
            assertEquals(1L, after.numericUniformCalls());
            assertEquals(1L, after.resourceScanSteps());
            assertEquals(1L, after.descriptorNameParseCalls());
            assertEquals(1L, after.numericToNameDispatches());
        } else {
            assertEquals(before.numericStorageBufferCalls(), after.numericStorageBufferCalls());
            assertEquals(before.numericUniformCalls(), after.numericUniformCalls());
            assertEquals(before.resourceScanSteps(), after.resourceScanSteps());
            assertEquals(before.descriptorNameParseCalls(), after.descriptorNameParseCalls());
            assertEquals(before.numericToNameDispatches(), after.numericToNameDispatches());
        }

        NumericBindingDiagnostics.reset();
        NumericBindingDiagnostics.Snapshot reset = NumericBindingDiagnostics.snapshot();
        assertEquals(0L, reset.numericStorageBufferCalls());
        assertEquals(0L, reset.numericUniformCalls());
        assertEquals(0L, reset.resourceScanSteps());
        assertEquals(0L, reset.descriptorNameParseCalls());
        assertEquals(0L, reset.numericToNameDispatches());
    }
}
