package com.metallum.mixin;

import com.metallum.client.metal.render.MetalTerrainIcbScope;
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
    private static final String PREFERRED_GRAPHICS_API_MIXIN =
            "com.metallum.mixin.render.PreferredGraphicsApiMixin";
    private static final String BACKEND_FRAME_COMPARISON_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonMixin";
    private static final String BACKEND_FRAME_COMPARISON_GAME_RENDERER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonGameRendererMixin";
    private static final String BACKEND_FRAME_COMPARISON_SERVER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonServerMixin";
    private static final String BACKEND_FRAME_COMPARISON_DELTA_TRACKER_MIXIN =
            "com.metallum.mixin.render.BackendFrameComparisonDeltaTrackerMixin";
    private static final String RENDER_COMMAND_PACKET_MIXIN =
            "com.metallum.mixin.render.MTLRenderCommandEncoderPacketMixin";
    private static final String RENDER_COMMAND_PACKET_BOUNDARY_MIXIN =
            "com.metallum.mixin.render.MetalRenderPassCommandPacketBoundaryMixin";
    private static final String HOT_PATH_TELEMETRY_REPORT_MIXIN =
            "com.metallum.mixin.render.MetalHotPathTelemetryReportMixin";
    private static final String VALIDATION_FRAME_DRIVER_MIXIN =
            "com.metallum.mixin.render.MinecraftMetalFxMixin";
    private static final String TERRAIN_ICB_SCOPE_MIXIN =
            "com.metallum.mixin.sodium.DefaultChunkRendererTerrainIcbScopeMixin";
    private static final String SODIUM_ARENA_REUSE_FIX_MIXIN =
            "com.metallum.mixin.sodium.GlBufferArenaReuseFixMixin";
    private static final String PREFERRED_GRAPHICS_BACKEND_OPTION = "preferredGraphicsBackend";
    private static final String DEFAULT_GRAPHICS_BACKEND = "\"default\"";

    private boolean isAppleRuntime;
    private boolean isDefaultGraphicsApi;

    @Override
    public void onLoad(String mixinPackage) {
        this.isAppleRuntime = isSupportedAppleRuntime();
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
                || BACKEND_FRAME_COMPARISON_DELTA_TRACKER_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("metallum.backend.compare.enabled");
        }
        if (VALIDATION_FRAME_DRIVER_MIXIN.equals(mixinClassName)
                && Boolean.getBoolean("metallum.validation.enabled")) {
            // The mixin keeps its MetalFX redirects dormant on non-Metal
            // backends, but its before/after hooks drive the same deterministic
            // scene for the stock OpenGL differential lane.
            return true;
        }
        if (RENDER_COMMAND_PACKET_MIXIN.equals(mixinClassName)
                || RENDER_COMMAND_PACKET_BOUNDARY_MIXIN.equals(mixinClassName)) {
            return !"false".equalsIgnoreCase(System.getProperty(
                    "metallum.opt.renderCommandPacket", "true"))
                    && this.isDefaultGraphicsApi;
        }
        if (HOT_PATH_TELEMETRY_REPORT_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("metallum.hotpath.telemetry")
                    && this.isDefaultGraphicsApi;
        }
        if (TERRAIN_ICB_SCOPE_MIXIN.equals(mixinClassName)) {
            return MetalTerrainIcbScope.configuredEnabled()
                    && FabricLoader.getInstance().isModLoaded("sodium")
                    && this.isDefaultGraphicsApi;
        }
        if (SODIUM_ARENA_REUSE_FIX_MIXIN.equals(mixinClassName)) {
            return Boolean.parseBoolean(System.getProperty(
                            "metallum.opt.sodiumDisableArenaBufferReuse", "false"))
                    && Boolean.parseBoolean(System.getProperty(
                            "metallum.opt.metal4MainRenderer", "false"))
                    && FabricLoader.getInstance().isModLoaded("sodium")
                    && this.isDefaultGraphicsApi;
        }
        if (mixinClassName.contains(".mixin.sodium.")) {
            return FabricLoader.getInstance().isModLoaded("sodium");
        }
        if (mixinClassName.contains(".mixin.iris.")) {
            return FabricLoader.getInstance().isModLoaded("iris")
                    && this.isDefaultGraphicsApi;
        }
        return PREFERRED_GRAPHICS_API_MIXIN.equals(mixinClassName)
                || this.isDefaultGraphicsApi;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
    }

    private static boolean isSupportedAppleRuntime() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac") || osName.contains("ios")) {
            return true;
        }
        if (System.getProperty("pojav.launcher") != null
                || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }

        String tmpDir = System.getProperty("java.io.tmpdir", "");
        String userHome = System.getProperty("user.home", "");
        if (isIOSContainerPath(tmpDir) || isIOSContainerPath(userHome)) {
            return true;
        }

        return osName.contains("darwin") && osArch.contains("aarch64");
    }

    private static boolean isIOSContainerPath(final String path) {
        return path.contains("/var/mobile/") || path.contains("/var/containers/");
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
