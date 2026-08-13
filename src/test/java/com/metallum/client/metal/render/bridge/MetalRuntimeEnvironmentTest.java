package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalRuntimeEnvironmentTest {
    @Test
    void detectsAmethystWhenJvmReportsMacOs() {
        assertTrue(MetalRuntimeEnvironment.isIOS(
                "Mac OS X",
                "aarch64",
                "/private/var/mobile/Containers/Data/Application/UUID/tmp/",
                "/private/var/mobile/Containers/Data/Application/UUID/Documents",
                false,
                false
        ));
    }

    @Test
    void doesNotClassifyAppleSiliconMacAsIos() {
        assertFalse(MetalRuntimeEnvironment.isIOS(
                "Mac OS X",
                "aarch64",
                "/var/folders/example/T/",
                "/Users/example",
                false,
                false
        ));
    }

    @Test
    void renderCommandPacketsDefaultOffOnIos() {
        assertFalse(MetalRuntimeEnvironment.renderCommandPacketEnabled(null, true));
    }

    @Test
    void renderCommandPacketsRemainDefaultOnOnDesktop() {
        assertTrue(MetalRuntimeEnvironment.renderCommandPacketEnabled(null, false));
    }

    @Test
    void explicitRenderCommandPacketValueOverridesPlatformDefault() {
        assertTrue(MetalRuntimeEnvironment.renderCommandPacketEnabled("true", true));
        assertFalse(MetalRuntimeEnvironment.renderCommandPacketEnabled("false", false));
    }
}
