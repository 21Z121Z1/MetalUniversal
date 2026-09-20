package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Calls the production Mach sampler in this test JVM, without requiring a window. */
final class NativeProcessMemoryIntegrationTest {
    @Test
    void samplesCurrentJvmResidentMemoryThroughTheProductionAbi() {
        for (int index = 0; index < 4; index++) {
            var sample = MetalNativeBridge.metallum_process_memory_sample();
            assertTrue(sample.successful(), sample.toString());
            assertEquals(0, sample.kernelStatus());
            assertTrue(sample.returnedWordCount() >= NativeProcessMemory.MIN_TASK_INFO_WORDS);
            assertTrue(sample.residentBytes() > 0);
            assertTrue(sample.physicalFootprintBytes() > 0);
            assertTrue(sample.lifetimeResidentPeakBytes() > 0);
        }
    }
}
