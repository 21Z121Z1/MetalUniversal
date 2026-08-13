package com.metallum.client.metal.render.bridge;

import java.util.Locale;

/**
 * Pure runtime policy shared by early mixin selection and the native bridge.
 *
 * <p>This class deliberately has no FFM, Fabric, or LWJGL dependencies. Mixin
 * configuration runs before the native bridge is initialized, so platform
 * defaults must be resolvable without loading native-access machinery.</p>
 */
public final class MetalRuntimeEnvironment {
    public static final String RENDER_COMMAND_PACKET_PROPERTY =
            "metallum.opt.renderCommandPacket";

    private MetalRuntimeEnvironment() {
    }

    /**
     * Returns whether the current JVM is hosted by an iOS launcher.
     *
     * <p>Amethyst/Pojav JVMs can report {@code Mac OS X}; their sandbox paths
     * are therefore part of the platform identity.</p>
     */
    public static boolean isIOS() {
        return isIOS(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("java.io.tmpdir", ""),
                System.getProperty("user.home", ""),
                System.getProperty("pojav.launcher") != null,
                System.getProperty("org.pojavlauncher") != null
        );
    }

    /** Returns whether Metal mixins are valid on this Apple runtime. */
    public static boolean isAppleMetalPlatform() {
        return isAppleMetalPlatform(System.getProperty("os.name", ""), isIOS());
    }

    static boolean isAppleMetalPlatform(final String osName, final boolean ios) {
        return ios || osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Resolves the ordered render-command packet switch.
     *
     * <p>An explicit property always wins. Desktop behavior remains default-on.
     * On iOS the property defaults off because physical A17 Pro validation found
     * an AGXMetal access fault in the packet decoder path; the established
     * state-only packet/direct-draw path remains available.</p>
     */
    public static boolean renderCommandPacketEnabled() {
        return renderCommandPacketEnabled(
                System.getProperty(RENDER_COMMAND_PACKET_PROPERTY),
                isIOS()
        );
    }

    static boolean isIOS(
            final String osName,
            final String osArch,
            final String tmpDir,
            final String userHome,
            final boolean pojavLauncherProperty,
            final boolean orgPojavLauncherProperty
    ) {
        String normalizedName = osName.toLowerCase(Locale.ROOT);
        String normalizedArch = osArch.toLowerCase(Locale.ROOT);
        if (normalizedName.contains("ios")) {
            return true;
        }
        if (pojavLauncherProperty || orgPojavLauncherProperty) {
            return true;
        }
        if (isMobileContainerPath(tmpDir) || isMobileContainerPath(userHome)) {
            return true;
        }
        return normalizedName.contains("darwin")
                && normalizedArch.contains("aarch64")
                && !normalizedName.contains("mac");
    }

    static boolean renderCommandPacketEnabled(
            final String configuredValue,
            final boolean ios
    ) {
        if (configuredValue != null) {
            return !"false".equalsIgnoreCase(configuredValue);
        }
        return !ios;
    }

    private static boolean isMobileContainerPath(final String path) {
        return path.contains("/var/mobile/") || path.contains("/var/containers/");
    }
}
