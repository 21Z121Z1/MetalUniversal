package com.metallum.client.metal.render.bridge;

import java.util.Locale;
import java.util.Properties;

/** Platform selection without initializing LWJGL, FFM, or the renderer. */
public enum NativePlatform {
    MACOS, IOS, UNSUPPORTED;

    public static NativePlatform current() {
        return detect(System.getProperties());
    }

    static NativePlatform detect(Properties properties) {
        String name = properties.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = properties.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (name.contains("ios")) return IOS;
        boolean mac = name.contains("mac");
        boolean darwin = name.contains("darwin");
        // Pojav also runs on Android. A launcher property alone is not iOS evidence.
        if (!mac && !darwin) return UNSUPPORTED;
        if (properties.containsKey("pojav.launcher") || properties.containsKey("org.pojavlauncher")
                || mobileContainer(properties.getProperty("java.io.tmpdir", ""))
                || mobileContainer(properties.getProperty("user.home", ""))) return IOS;
        // Some iOS JDKs report Darwin/aarch64 rather than iOS. Normal macOS JDKs
        // report Mac OS X; preserve the established mobile fallback explicitly.
        if (darwin && !mac && (arch.equals("aarch64") || arch.equals("arm64"))) return IOS;
        return mac ? MACOS : UNSUPPORTED;
    }

    private static boolean mobileContainer(String path) {
        return path.contains("/var/mobile/") || path.contains("/var/containers/");
    }
}
