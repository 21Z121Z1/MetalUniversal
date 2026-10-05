package com.metallum.mixin;

import com.metallum.client.sodium.SodiumPerformanceOptions;
import com.metallum.client.ClientPerformanceOptions;
import com.metallum.client.chunk.ChunkPipelineOptions;
import com.metallum.client.storage.ChunkStorageOptions;
import com.metallum.client.metal.render.bridge.NativePlatform;
import com.metallum.client.metal.MacThreadQos;
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
    private static final String BACKEND_COMPARISON_ATLAS_MIXIN =
            "com.metallum.mixin.render.TextureAtlasAnimationValidationMixin";
    private static final String BACKEND_COMPARISON_LIGHTMAP_MIXIN =
            "com.metallum.mixin.render.LightmapFlickerValidationMixin";
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
    private static final String SODIUM_CULL_RECOVERY_MIXIN =
            "com.metallum.mixin.sodium.SodiumCullRecoveryMixin";
    private static final String SODIUM_CULL_REUSE_VERIFY_MIXIN =
            "com.metallum.mixin.sodium.SodiumCullReuseVerifierMixin";
    private static final String SODIUM_VISIBILITY_SWEEP_MIXIN =
            "com.metallum.mixin.sodium.DirectionalVisGraphSweepMixin";
    private static final String SODIUM_BLOCK_RENDERER_REFS_MIXIN =
            "com.metallum.mixin.sodium.BlockRendererCachedReferencesMixin";
    private static final String SODIUM_ENTITY_BOX_MIXIN =
            "com.metallum.mixin.sodium.SodiumEntityCullingBoxMixin";
    private static final String SODIUM_DRAW_MERGE_MIXIN =
            "com.metallum.mixin.sodium.VKMultiDrawBatchMergeMixin";
    private static final String ENTITY_BOX_CONSUMER_MIXIN =
            "com.metallum.mixin.render.EntityRendererCullingBoxReuseMixin";
    private static final String MODEL_PART_INDEXED_MIXIN =
            "com.metallum.mixin.render.ModelPartIndexedCompileMixin";
    private static final String QOS_RENDER_MIXIN =
            "com.metallum.mixin.qos.RenderThreadQosMixin";
    private static final String QOS_SERVER_MIXIN =
            "com.metallum.mixin.qos.ServerThreadQosMixin";
    private static final String QOS_WORKER_MIXIN =
            "com.metallum.mixin.qos.MinecraftWorkerQosMixin";
    private static final String QOS_MESH_MIXIN =
            "com.metallum.mixin.sodium.SodiumMeshQosMixin";
    private static final String QOS_CULL_MIXIN =
            "com.metallum.mixin.sodium.SodiumCullQosMixin";
    private static final String SODIUM_REGION_LOOKUP_CACHE_MIXIN =
            "com.metallum.mixin.sodium.VisibleChunkCollectorRegionCacheMixin";
    private static final String SODIUM_CLONE_CACHE_OPTIMIZATION_MIXIN =
            "com.metallum.mixin.sodium.ClonedChunkSectionCacheOptimizationMixin";
    private static final String SODIUM_SLICE_BOUNDS_MIXIN =
            "com.metallum.mixin.sodium.LevelSliceBoundsFastPathMixin";
    private static final String SODIUM_BIOME_UNIFORM_MIXIN =
            "com.metallum.mixin.sodium.LevelBiomeUniformFastPathMixin";
    private static final String SODIUM_SHARED_AIR_MIXIN =
            "com.metallum.mixin.sodium.LevelSliceSharedAirMixin";
    private static final String STARTUP_LAZY_NARRATOR_MIXIN =
            "com.metallum.mixin.startup.GameNarratorDeferredMixin";
    private static final String CHUNK_SAVE_SKIP_MIXIN =
            "com.metallum.mixin.storage.RegionFileStorageSaveSkipMixin";

    private static final String CHUNK_SECTION_INDEX_MIXIN =
            "com.metallum.mixin.chunk.ChunkSectionIndexCacheMixin";
    private static final String CHUNK_PALETTE_CODEC_MIXIN =
            "com.metallum.mixin.chunk.PalettedContainerFactoryFastCodecMixin";
    private static final Set<String> CHUNK_POI_MIXINS = Set.of(
            "com.metallum.mixin.chunk.PoiSectionFastPathAccessor",
            "com.metallum.mixin.chunk.SectionStorageFastPathAccessor",
            "com.metallum.mixin.chunk.AcquirePoiFastPathMixin"
    );
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    private boolean isAppleRuntime;
    private boolean isDefaultGraphicsApi;

    @Override
    public void onLoad(String mixinPackage) {
        this.isAppleRuntime = NativePlatform.current() != NativePlatform.UNSUPPORTED;
        this.isDefaultGraphicsApi = Boolean.getBoolean("metallum.validation.forceMetal")
                || isDefaultGraphicsApiSelected();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.isAppleRuntime) {
            return false;
        }
        if (BACKEND_FRAME_COMPARISON_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_GAME_RENDERER_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_SERVER_MIXIN.equals(mixinClassName)
                || BACKEND_FRAME_COMPARISON_DELTA_TRACKER_MIXIN.equals(mixinClassName)
                || BACKEND_COMPARISON_ATLAS_MIXIN.equals(mixinClassName)
                || BACKEND_COMPARISON_LIGHTMAP_MIXIN.equals(mixinClassName)) {
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
        if (CHUNK_SAVE_SKIP_MIXIN.equals(mixinClassName)) {
            boolean selected = this.isDefaultGraphicsApi && ChunkStorageOptions.chunkSaveSkipEnabled();
            if (selected && Boolean.getBoolean("metallum.ci.e2e")) {
                System.setProperty("metallum.ci.semantic.chunkSaveSkip.selected", "true");
            }
            return selected;
        }
        if (CHUNK_SECTION_INDEX_MIXIN.equals(mixinClassName)) {
            return this.isDefaultGraphicsApi && ChunkPipelineOptions.sectionIndexCacheEnabled();
        }
        if (CHUNK_PALETTE_CODEC_MIXIN.equals(mixinClassName)) {
            return this.isDefaultGraphicsApi && ChunkPipelineOptions.paletteCodecEnabled();
        }
        if (CHUNK_POI_MIXINS.contains(mixinClassName)) {
            return this.isDefaultGraphicsApi && ChunkPipelineOptions.poiSearchMode() != ChunkPipelineOptions.Mode.OFF;
        }
        if (STARTUP_LAZY_NARRATOR_MIXIN.equals(mixinClassName)) {
            return this.isDefaultGraphicsApi && Boolean.getBoolean("metallum.opt.lazyNarrator");
        }
        if (QOS_RENDER_MIXIN.equals(mixinClassName)) {
            return this.shouldApplyQosMixin("render");
        }
        if (QOS_SERVER_MIXIN.equals(mixinClassName)) {
            return this.shouldApplyQosMixin("server");
        }
        if (QOS_WORKER_MIXIN.equals(mixinClassName)) {
            return this.shouldApplyQosMixin("worker");
        }
        if (QOS_MESH_MIXIN.equals(mixinClassName)) {
            return FabricLoader.getInstance().isModLoaded("sodium") && this.shouldApplyQosMixin("mesh");
        }
        if (QOS_CULL_MIXIN.equals(mixinClassName)) {
            return FabricLoader.getInstance().isModLoaded("sodium") && this.shouldApplyQosMixin("cull");
        }
        if (SODIUM_CULL_RECOVERY_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "cullRecovery",
                    SodiumPerformanceOptions.cullRecoveryEnabled()
            );
        }
        if (SODIUM_CULL_REUSE_VERIFY_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "cullReuse",
                    SodiumPerformanceOptions.cullReuseAnyModeEnabled()
            );
        }
        if (SODIUM_VISIBILITY_SWEEP_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "visibilitySweep",
                    SodiumPerformanceOptions.visibilitySweepAnyModeEnabled()
            );
        }
        if (SODIUM_BLOCK_RENDERER_REFS_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "blockRendererRefs",
                    SodiumPerformanceOptions.blockRendererRefsEnabled()
            );
        }
        if (SODIUM_ENTITY_BOX_MIXIN.equals(mixinClassName)
                || ENTITY_BOX_CONSUMER_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "entityBoxReuse",
                    SodiumPerformanceOptions.entityBoxReuseEnabled()
            );
        }
        if (SODIUM_DRAW_MERGE_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "drawMerge",
                    SodiumPerformanceOptions.drawMergeEnabled()
            );
        }
        if (MODEL_PART_INDEXED_MIXIN.equals(mixinClassName)) {
            boolean selected = this.isDefaultGraphicsApi && ClientPerformanceOptions.modelPartIndexedLoopEnabled();
            if (selected && Boolean.getBoolean("metallum.ci.e2e")) {
                System.setProperty("metallum.ci.semantic.modelPartIndexed.selected", "true");
            }
            return selected;
        }
        if (SODIUM_REGION_LOOKUP_CACHE_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "regionLookupCache",
                    SodiumPerformanceOptions.regionLookupCacheEnabled()
            );
        }
        if (SODIUM_CLONE_CACHE_OPTIMIZATION_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "cloneCache",
                    SodiumPerformanceOptions.cloneCacheTuningEnabled()
            );
        }
        if (SODIUM_SLICE_BOUNDS_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "sliceBounds",
                    SodiumPerformanceOptions.sliceBoundsEnabled()
            );
        }
        if (SODIUM_BIOME_UNIFORM_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "biomeUniform",
                    SodiumPerformanceOptions.biomeUniformEnabled()
            );
        }
        if (SODIUM_SHARED_AIR_MIXIN.equals(mixinClassName)) {
            return this.shouldApplySodiumSemanticMixin(
                    "sharedAir",
                    SodiumPerformanceOptions.sharedAirSliceEnabled()
            );
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

    private boolean shouldApplyQosMixin(String role) {
        boolean selected = this.isDefaultGraphicsApi && MacThreadQos.configured(role);
        if (selected && Boolean.getBoolean("metallum.ci.e2e")) {
            System.setProperty("metallum.ci.qos." + role + ".selected", "true");
        }
        return selected;
    }

    private boolean shouldApplySodiumSemanticMixin(String evidenceKey, boolean enabled) {
        if (!this.isDefaultGraphicsApi || !enabled || !hasSupportedSodiumSemanticTarget()) {
            return false;
        }
        if (Boolean.getBoolean("metallum.ci.e2e")) {
            System.setProperty("metallum.ci.sodiumSemantic." + evidenceKey + ".selected", "true");
        }
        return true;
    }

    private static boolean hasSupportedSodiumSemanticTarget() {
        return FabricLoader.getInstance()
                .getModContainer("sodium")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .map(SodiumPerformanceOptions::supportsSemanticMixins)
                .orElse(false);
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
