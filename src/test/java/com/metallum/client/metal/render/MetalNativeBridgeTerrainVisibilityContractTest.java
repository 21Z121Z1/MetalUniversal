package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.MAC)
class MetalNativeBridgeTerrainVisibilityContractTest {
    @Test
    void invalidNativeProbeInputsReturnBeforeDispatch() {
        assertTrue(MetalNativeBridge.isNullHandle(
                MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                        MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, 0, 0L
                )
        ));
        assertEquals(0, MetalNativeBridge.terrainVisibilityProbePoll(
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, -1,
                MemorySegment.NULL, MemorySegment.NULL, -1
        ));
    }

}
