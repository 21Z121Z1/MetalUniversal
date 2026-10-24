package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalBufferProvisionerTest {
    @AfterEach
    void clear() {
        System.clearProperty(MetalBufferProvisioner.ENABLE_PROPERTY);
        System.clearProperty(MetalBufferProvisioner.THRESHOLD_PROPERTY);
    }

    @Test
    void onlyLargePrivateBuffersAreEligible() {
        long privateOptions = MTLResourceOptions.of(MTLStorageMode.Private, MTLHazardTrackingMode.Untracked);
        long sharedOptions = MTLResourceOptions.of(MTLStorageMode.Shared, MTLHazardTrackingMode.Untracked);

        assertFalse(MetalBufferProvisioner.eligible(64L << 20, privateOptions));

        System.setProperty(MetalBufferProvisioner.ENABLE_PROPERTY, "true");
        System.setProperty(MetalBufferProvisioner.THRESHOLD_PROPERTY, Long.toString(8L << 20));

        assertFalse(MetalBufferProvisioner.eligible(4L << 20, privateOptions));
        assertTrue(MetalBufferProvisioner.eligible(8L << 20, privateOptions));
        assertFalse(MetalBufferProvisioner.eligible(64L << 20, sharedOptions));
    }
}
