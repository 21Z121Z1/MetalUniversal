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
    private static final String TERRAIN_ICB_SCOPE_MIXIN =
            "com.metallum.mixin.sodium.DefaultChunkRendererTerrainIcbScopeMixin";
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
        if (RENDER_COMMAND_PACKET_MIXIN.equals(mixinClassName)
                || RENDER_COMMAND_PACKET_BOUNDARY_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("metallum.opt.renderCommandPacket")
                    && this.isDefaultGraphicsApi;
        }
        if (TERRAIN_ICB_SCOPE_MIXIN.equals(mixinClassName)) {
            return Boolean.getBoolean("metallum.opt.terrainIcbPilot")
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
