package com.metallum.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MetallumMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String PREFERRED_GRAPHICS_API_MIXIN = "com.metallum.mixin.render.PreferredGraphicsApiMixin";
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    private boolean isApplePlatform;
    private boolean isDefaultGraphicsApi;

    @Override
    public void onLoad(String mixinPackage) {
        this.isApplePlatform = detectApplePlatform();
        this.isDefaultGraphicsApi = isDefaultGraphicsApiSelected();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isApplePlatform) {
            return false;
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.contains(".mixin.iris.")) {
            // Iris-targeting mixins are only needed when Iris is installed.
            // They self-guard at runtime via MetalIrisBridge.isNonGlBackend(),
            // so they are safe to apply on GL backends too.
            return FabricLoader.getInstance().isModLoaded("iris");
        }
        if (mixinClassName.contains(".mixin.accessor.")) {
            // Accessor mixins expose Mojang internals needed for backend
            // detection. They are harmless on any backend.
            return true;
        }
        return PREFERRED_GRAPHICS_API_MIXIN.equals(mixinClassName) || this.isDefaultGraphicsApi;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    /**
     * Detects whether we are running on an Apple platform (macOS or iOS).
     * <p>
     * This mirrors the pure-Java detection logic in
     * {@code MetalNativeBridge.isIOS()} (without triggering native-library
     * loading, which is too early for the mixin plugin's {@code onLoad}).
     * iOS detection signals (in priority order):
     * <ol>
     *   <li>{@code os.name} contains "ios"</li>
     *   <li>{@code pojav.launcher} / {@code org.pojavlauncher} system property
     *       (PojavLauncher / Amethyst on iOS)</li>
     *   <li>{@code java.io.tmpdir} / {@code user.home} under
     *       {@code /var/mobile/} or {@code /var/containers/} (iOS sandbox)</li>
     *   <li>{@code os.name} contains "darwin" + {@code os.arch} contains
     *       "aarch64" + {@code os.name} does not contain "mac"</li>
     * </ol>
     * macOS is detected via {@code os.name} containing "mac".
     */
    private static boolean detectApplePlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        // macOS
        if (osName.contains("mac")) {
            return true;
        }
        // iOS — os.name explicitly reports "iOS"
        if (osName.contains("ios")) {
            return true;
        }
        // iOS — PojavLauncher / Amethyst on iOS set these properties
        if (System.getProperty("pojav.launcher") != null
                || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }
        // iOS — the JVM on iOS (Azul Zulu via PojavLauncher/Amethyst) often
        // reports os.name as "Mac OS X" or "Darwin". The most reliable signal
        // is the sandbox path: on iOS, java.io.tmpdir and user.home are under
        // /private/var/mobile/Containers/Data/Application/<UUID>/, which never
        // exists on macOS.
        String tmpDir = System.getProperty("java.io.tmpdir", "");
        String userHome = System.getProperty("user.home", "");
        if (tmpDir.contains("/var/mobile/") || tmpDir.contains("/var/containers/")
                || userHome.contains("/var/mobile/") || userHome.contains("/var/containers/")) {
            return true;
        }
        // iOS — Darwin + aarch64 without "Mac" in os.name
        return osName.contains("darwin")
                && osArch.contains("aarch64")
                && !osName.contains("mac");
    }

    private static boolean isDefaultGraphicsApiSelected() {
        Path optionsFile = FabricLoader.getInstance().getGameDir().resolve("options.txt");
        try {
            for (String line : Files.readAllLines(optionsFile)) {
                int separator = line.indexOf(':');
                if (separator <= 0) {
                    continue;
                }
                if (PREFERRED_GRAPHICS_BACKEND_OPTION.equals(line.substring(0, separator))) {
                    String value = line.substring(separator + 1).toLowerCase(Locale.ROOT);
                    return DEFAULT_GRAPHICS_BACKEND.equals(value);
                }
            }
        } catch (IOException ignored) {
        }

        return true;
    }
}
