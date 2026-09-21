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
    private static final String VANILLA_TERRAIN_TASK_ACCESSOR =
            "com.metallum.mixin.terrain.SectionTaskTerrainAdmissionAccessor";
    private static final Set<String> VANILLA_TERRAIN_ADMISSION_MIXINS = Set.of(
            "com.metallum.mixin.terrain.SectionTaskDynamicQueueAdmissionMixin"
    );
    private static final Set<String> VANILLA_TERRAIN_GENERATION_MIXINS = Set.of(
            "com.metallum.mixin.terrain.LevelExtractorTerrainGenerationMixin",
            "com.metallum.mixin.terrain.RenderSectionTerrainGenerationMixin",
            "com.metallum.mixin.terrain.CompileTaskTerrainGenerationMixin"
    );
    private static final String VANILLA_TERRAIN_ADMISSION_PROPERTY = "metallum.terrain.vanillaAdmission";
    private static final Set<String> VANILLA_TERRAIN_SLICE_CACHE_MIXINS = Set.of(
            "com.metallum.mixin.terrain.CompiledSectionMeshSliceCacheMixin",
            "com.metallum.mixin.terrain.SectionRenderDispatcherSliceCacheMixin",
            "com.metallum.mixin.terrain.UberGpuBufferSliceInvalidationMixin"
    );
    private static final String VANILLA_TERRAIN_GENERATION_PROPERTY = "metallum.terrain.vanillaGenerationGuard";
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
        if (VANILLA_TERRAIN_TASK_ACCESSOR.equals(mixinClassName)) {
            FabricLoader loader = FabricLoader.getInstance();
            return this.isDefaultGraphicsApi
                    && (Boolean.getBoolean(VANILLA_TERRAIN_ADMISSION_PROPERTY)
                            || Boolean.getBoolean(VANILLA_TERRAIN_GENERATION_PROPERTY))
                    && !loader.isModLoaded("sodium")
                    && !loader.isModLoaded("iris");
        }
        if (VANILLA_TERRAIN_ADMISSION_MIXINS.contains(mixinClassName)) {
            FabricLoader loader = FabricLoader.getInstance();
            return this.isDefaultGraphicsApi
                    && Boolean.getBoolean(VANILLA_TERRAIN_ADMISSION_PROPERTY)
                    && !loader.isModLoaded("sodium")
                    && !loader.isModLoaded("iris");
        }
        if (VANILLA_TERRAIN_GENERATION_MIXINS.contains(mixinClassName)) {
            FabricLoader loader = FabricLoader.getInstance();
            return this.isDefaultGraphicsApi
                    && Boolean.getBoolean(VANILLA_TERRAIN_GENERATION_PROPERTY)
                    && !loader.isModLoaded("sodium")
                    && !loader.isModLoaded("iris");
        }
        if (VANILLA_TERRAIN_SLICE_CACHE_MIXINS.contains(mixinClassName)) {
            FabricLoader loader = FabricLoader.getInstance();
            return this.isDefaultGraphicsApi && Boolean.getBoolean("metallum.opt.terrainSliceCache")
                    && !loader.isModLoaded("sodium") && !loader.isModLoaded("iris");
        }
        if (mixinClassName.contains(".mixin.terrain.")) {
            FabricLoader loader = FabricLoader.getInstance();
            // Frame evidence needs only the actual layer-submission hook, not terrain lifecycle tracing.
            boolean frameEvidenceDrawHook = Boolean.getBoolean("metallum.frameEvidence.enabled")
                    && mixinClassName.equals("com.metallum.mixin.terrain.ChunkSectionsDrawTerrainWorkMixin");
            return this.isDefaultGraphicsApi
                    && (Boolean.getBoolean("metallum.terrain.vanillaWorkEvents") || frameEvidenceDrawHook)
                    && !loader.isModLoaded("sodium")
                    && !loader.isModLoaded("iris");
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.equals("com.metallum.mixin.render.MetalRenderPassBindingCacheMixin")
                || mixinClassName.equals("com.metallum.mixin.render.MetalCompiledRenderPipelineBindingPlanMixin")) {
            // These name/token compatibility caches serve the optional producers.
            // RenderPearl's indexed Vanilla path needs neither their per-pass maps
            // nor their callbacks on every uniform binding.
            FabricLoader loader = FabricLoader.getInstance();
            return this.isDefaultGraphicsApi && (loader.isModLoaded("sodium") || loader.isModLoaded("iris"));
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
