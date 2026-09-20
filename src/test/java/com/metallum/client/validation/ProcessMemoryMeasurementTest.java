package com.metallum.client.validation;

import com.google.gson.GsonBuilder;
import com.metallum.client.metal.render.NativeProcessMemory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProcessMemoryMeasurementTest {
    private static NativeProcessMemory.Sample rss(long bytes) {
        return new NativeProcessMemory.Sample(0, 38, bytes, bytes + 10, 10_000);
    }

    private static ProcessMemoryMeasurement window(int capacity) {
        return new ProcessMemoryMeasurement(capacity, () -> rss(100), new AtomicLong()::getAndIncrement);
    }

    @Test
    void sampledWindowMaximumDoesNotUseLifetimeHighWaterMark() throws Exception {
        long[] resident = {9_999, 10, 30, 20, 50, 40};
        var index = new AtomicInteger();
        var clock = new AtomicLong();
        var window = new ProcessMemoryMeasurement(16, () -> rss(resident[index.getAndIncrement()]),
                () -> clock.getAndAdd(10));
        window.begin(9, 40);
        window.beforeFrame(9, 40);
        window.afterFrame(9, 40);
        window.beforeFrame(9, 41);
        window.afterFrame(9, 41);
        var result = window.finish(9, 42);
        assertTrue(result.complete(), result.toString());
        assertEquals(5, result.sampleCount());
        assertEquals(9_999, result.probeWarmup().sample().residentBytes());
        assertEquals(10, result.probeWarmup().durationNanos());
        assertEquals(50, result.peakResidentBytes());
        assertEquals(60, result.peakPhysicalFootprintBytes());
        assertEquals(10_000, result.lifetimeResidentPeakBytesLast());
        assertEquals(50, result.totalProbeNanos());
        assertEquals(10, result.maxProbeNanos());
        assertEquals(110, result.endOffsetNanos());
        assertEquals("window-drain", result.samples().getLast().phase());
        assertEquals(42, result.samples().getLast().frameId());
        Path path = Path.of("build/agent-state/process-memory-java-fixture.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "processMemory", result,
                "measurementWindow", Map.of("id", 9, "startFrameInclusive", 40,
                        "endFrameExclusive", 42, "completedFrames", 2))) + "\n");
    }

    @Test
    void warmupAndAfterFinishDoNotCallProbeAndNextWindowStartsFresh() {
        var calls = new AtomicInteger();
        var window = new ProcessMemoryMeasurement(16, () -> rss(calls.incrementAndGet()),
                new AtomicLong()::getAndIncrement);
        window.beforeFrame(9, 0);
        window.afterFrame(9, 0);
        assertEquals(0, calls.get());
        window.begin(9, 10);
        window.beforeFrame(9, 10);
        window.afterFrame(9, 10);
        assertTrue(window.finish(9, 11).complete());
        window.beforeFrame(9, 11);
        window.afterFrame(9, 11);
        assertEquals(4, calls.get(), "one explicit warmup probe precedes three window samples");
        window.begin(10, 20);
        window.beforeFrame(10, 20);
        window.afterFrame(10, 20);
        var next = window.finish(10, 21);
        assertTrue(next.complete());
        assertEquals(3, next.sampleCount());
        assertEquals(8, next.peakResidentBytes());
        assertTrue(next.samples().stream().allMatch(row -> row.windowId() == 10));
    }

    @Test
    void capacityOverflowDoesNotFabricateACompleteWindow() {
        var window = window(2);
        window.begin(1, 0);
        window.beforeFrame(1, 0);
        window.afterFrame(1, 0);
        var result = window.finish(1, 1);
        assertFalse(result.complete());
        assertEquals(2, result.sampleCount());
        assertEquals(1, result.droppedSamples());
    }

    @Test
    void missingFrameEndOrWrongIdentityInvalidatesEvidence() {
        var window = window(16);
        window.begin(1, 40);
        window.beforeFrame(2, 40);
        assertFalse(window.finish(1, 41).complete());
        window.begin(3, 80);
        window.beforeFrame(3, 80);
        window.afterFrame(3, 81);
        var wrongFrame = window.finish(3, 82);
        assertFalse(wrongFrame.complete());
        assertTrue(wrongFrame.invalidEvents() > 0);
    }

    @Test
    void kernelFailuresAndProbeExceptionsRemainUnavailable() {
        for (boolean throwsException : new boolean[]{false, true}) {
            var window = new ProcessMemoryMeasurement(16, () -> {
                if (throwsException) throw new IllegalStateException("injected probe failure");
                return new NativeProcessMemory.Sample(5, 38, 0, 0, 0);
            }, new AtomicLong()::getAndIncrement);
            window.begin(1, 0);
            window.beforeFrame(1, 0);
            window.afterFrame(1, 0);
            var result = window.finish(1, 1);
            assertFalse(result.complete());
            assertEquals(3, result.failedSamples());
            assertEquals(throwsException ? -4 : 5, result.samples().getFirst().kernelStatus());
        }
    }

    @Test
    void nonMonotonicProbeClockAndFrameOverflowFailClosed() {
        var clock = new AtomicLong(100);
        var window = new ProcessMemoryMeasurement(16, () -> rss(100), clock::getAndDecrement);
        window.begin(1, 0);
        window.beforeFrame(1, 0);
        window.afterFrame(1, 0);
        assertFalse(window.finish(1, 1).complete());
        var overflow = window(16);
        overflow.begin(2, Long.MAX_VALUE);
        overflow.beforeFrame(2, Long.MAX_VALUE);
        overflow.afterFrame(2, Long.MAX_VALUE);
        assertFalse(overflow.finish(2, Long.MIN_VALUE).complete());
    }

    @Test
    void abiRejectsWrongShapeAndDoesNotPromotePartialOrFailedKernelData() {
        assertTrue(NativeProcessMemory.decode(new long[]{1, 0, 38, 1234, 4567, 9999}).successful());
        assertFalse(NativeProcessMemory.decode(new long[]{1, 0, 37, 1234, 4567, 9999}).successful());
        assertFalse(NativeProcessMemory.decode(new long[]{1, 5, 38, 1234, 4567, 9999}).successful());
        assertThrows(IllegalArgumentException.class, () -> NativeProcessMemory.decode(new long[5]));
        assertThrows(IllegalArgumentException.class, () -> NativeProcessMemory.decode(new long[]{2, 0, 38, 1, 1, 1}));
    }
}
