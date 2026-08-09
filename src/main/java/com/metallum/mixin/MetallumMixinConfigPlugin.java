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
    private static final String BACKEND_FRAME_COMPARISON_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonMixin";
    private static final String BACKEND_FRAME_COMPARISON_GAME_RENDERER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonGameRendererMixin";
    private static final String BACKEND_FRAME_COMPARISON_SERVER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonServerMixin";
    private static final String BACKEND_FRAME_COMPARISON_DELTA_TRACKER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonDeltaTrackerMixin";
    private static final String SODIUM_ARENA_REUSE_FIX_MIXIN =
            "com.metallum.mixin.sodium.GlBufferArenaReuseFixMixin";
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    private boolean isMacOs;
    private boolean isDefaultGraphicsApi;

    @Override
    public void onLoad(String mixinPackage) {
        String osName = System.getProperty("os.name", "");
        this.isMacOs = osName.toLowerCase(Locale.ROOT).contains("mac");
        this.isDefaultGraphicsApi = Boolean.getBoolean("metallum.validation.forceMetal")
                || isDefaultGraphicsApiSelected();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isMacOs) {
            return false;
        }
        if (BACKEND_FRAME_COMPARISON_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_GAME_RENDERER_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_SERVER_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_DELTA_TRACKER_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("metallum.backend.compare.enabled");
        }
        if (SODIUM_ARENA_REUSE_FIX_MIXIN.equals(mixinClassName)) {
            // Correctness is the default for the M4 main renderer. Setting the
            // property to false is a diagnostic kill-switch only; it must not
            // be required to opt into the lifetime fix.
            return Boolean.parseBoolean(System.getProperty(
                            "metallum.opt.sodiumDisableArenaBufferReuse", "true"))
                    && Boolean.parseBoolean(System.getProperty(
                            "metallum.opt.metal4MainRenderer", "false"))
                    && FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.contains(".mixin.iris.")) {
            // Iris-dormancy compat shims: only meaningful when Iris is present
            // and the default (Metal-first) backend selection is active. The
            // injected handlers additionally check the LIVE backend at runtime
            // so a Vulkan/GL fallback leaves Iris untouched.
            return FabricLoader.getInstance().isModLoaded("iris") && this.isDefaultGraphicsApi;
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
