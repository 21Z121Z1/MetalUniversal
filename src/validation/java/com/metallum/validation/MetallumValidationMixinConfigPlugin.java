package com.metallum.validation;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Applies validation-only mixins only for explicitly requested runs. */
public final class MetallumValidationMixinConfigPlugin implements IMixinConfigPlugin {
    private static final String LIFECYCLE_MIXIN = "IrisMetalLifecycleValidationMixin";

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac") || !FabricLoader.getInstance().isModLoaded("iris")) {
            return false;
        }
        if (mixinClassName.endsWith(LIFECYCLE_MIXIN)) {
            return Boolean.getBoolean("metallum.iris.validation.enabled");
        }
        return Boolean.getBoolean("metallum.iris.trace")
                && Boolean.getBoolean("metallum.iris.openglTrace");
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo
    ) {
    }

    @Override
    public void postApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo
    ) {
    }
}
