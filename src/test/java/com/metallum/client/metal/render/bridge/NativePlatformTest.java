package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class NativePlatformTest {
    @Test void desktopAndMobileSelectionHasOneNonNativeAuthority() {
        assertEquals(NativePlatform.MACOS, detect("Mac OS X", "aarch64", "", false));
        assertEquals(NativePlatform.MACOS, detect("MAC OS X", "x86_64", "/Users/test", false));
        assertEquals(NativePlatform.IOS, detect("iOS", "arm64", "", false));
        assertEquals(NativePlatform.IOS, detect("Darwin", "aarch64", "", false));
        assertEquals(NativePlatform.IOS, detect("Mac OS X", "aarch64", "/private/var/mobile/Containers/Data/Application/example", false));
        assertEquals(NativePlatform.IOS, detect("Darwin", "arm64", "/var/containers/Bundle/Application/example", false));
        assertEquals(NativePlatform.IOS, detect("Mac OS X", "aarch64", "", true));
        assertEquals(NativePlatform.UNSUPPORTED, detect("Linux", "aarch64", "/data/user/pojav", true));
        assertEquals(NativePlatform.UNSUPPORTED, detect("Windows 11", "amd64", "", true));
        assertEquals(NativePlatform.UNSUPPORTED, detect("", "", "", false));
    }
    private static NativePlatform detect(String name, String arch, String home, boolean launcher) {
        Properties properties = new Properties();
        properties.setProperty("os.name", name); properties.setProperty("os.arch", arch);
        properties.setProperty("user.home", home);
        if (launcher) properties.setProperty("pojav.launcher", "true");
        return NativePlatform.detect(properties);
    }
}
