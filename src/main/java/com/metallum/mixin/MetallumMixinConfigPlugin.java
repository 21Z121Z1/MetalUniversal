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
        // The PreferredGraphicsApiMixin only renames the DEFAULT enum's caption
        // to "Prefer Metal" and prepends MetalBackend to the backend try-list.
        // On non-Apple platforms MetalBackend initialization will naturally fail
        // and fall back to Vulkan/OpenGL, so it is safe to always apply this
        // mixin and keep the "Prefer Metal" option visible in the UI.
        if (PREFERRED_GRAPHICS_API_MIXIN.equals(mixinClassName)) {
            return true;
        }
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
        return this.isDefaultGraphicsApi;
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
     * Detects whether we are running on an Apple platform (macOS or iOS),
     * including iOS-via-Android-launcher scenarios such as PojavLauncher or
     * Amethyst, where {@code os.name} reports as "Linux" rather than "Mac".
     */
    private static boolean detectApplePlatform() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Native macOS reports "Mac OS X" / "macOS".
        if (osName.contains("mac")) {
            return true;
        }
        // iOS via PojavLauncher/Amethyst on Android reports "Linux" on aarch64.
        // Distinguish from desktop Linux by looking for launcher-specific signals.
        if (osName.contains("linux") || osName.contains("nix")) {
            String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (osArch.contains("aarch64") && isMobileAppleLauncherPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Heuristic check for the presence of an iOS-on-Android launcher
     * (PojavLauncher/Amethyst) that bridges to Apple's Metal backend.
     */
    private static boolean isMobileAppleLauncherPresent() {
        // PojavLauncher exposes a number of "pojav.*" system properties.
        for (String property : System.getProperties().stringPropertyNames()) {
            if (property.startsWith("pojav.")) {
                return true;
            }
        }
        // Amethyst and PojavLauncher typically nest the game under a launcher
        // directory whose name reflects the launcher.
        try {
            String gameDirName = FabricLoader.getInstance().getGameDir()
                    .getFileName().toString().toLowerCase(Locale.ROOT);
            if (gameDirName.contains("pojav") || gameDirName.contains("amethyst")) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // Defensive: ignore any unexpected path/access issues.
        }
        return false;
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
