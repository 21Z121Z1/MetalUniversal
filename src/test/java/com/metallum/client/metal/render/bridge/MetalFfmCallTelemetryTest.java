package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MetalFfmCallTelemetryTest {
    @AfterEach
    void resetTelemetry() {
        MetalFfmCallTelemetry.reset();
    }

    @Test
    void instrumentedHandlePreservesSignatureResultAndCountsInvocation() throws Throwable {
        MethodHandle target = MethodHandles.lookup().findStatic(
                MetalFfmCallTelemetryTest.class,
                "plusOne",
                MethodType.methodType(int.class, int.class)
        );

        MethodHandle instrumented = MetalFfmCallTelemetry.instrumentDowncall(target, true);
        int result = (int) instrumented.invokeExact(41);

        assertEquals(target.type(), instrumented.type());
        assertEquals(42, result);
        assertEquals(1L, MetalFfmCallTelemetry.snapshot().downcalls());
    }

    @Test
    void disabledInstrumentationReturnsOriginalHandleWithoutCounting() throws Throwable {
        MethodHandle target = MethodHandles.lookup().findStatic(
                MetalFfmCallTelemetryTest.class,
                "plusOne",
                MethodType.methodType(int.class, int.class)
        );

        MethodHandle uninstrumented = MetalFfmCallTelemetry.instrumentDowncall(target, false);
        int result = (int) uninstrumented.invokeExact(1);

        assertSame(target, uninstrumented);
        assertEquals(2, result);
        assertEquals(0L, MetalFfmCallTelemetry.snapshot().downcalls());
    }

    private static int plusOne(final int value) {
        return value + 1;
    }
}
