package net.irisshaders.iris.gl.shader;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.helpers.StringPair;

/**
 * TEST-CLASSPATH SHADOW of Iris's StandardMacros (see the shadow of
 * {@link net.irisshaders.iris.Iris} for the mechanism and rationale).
 *
 * <p>The real class builds the pack preprocessor environment from live GL
 * queries ({@code glGetString(GL_VERSION)}, capability probes), which cannot
 * run headlessly. {@code ShaderPack}'s constructor reaches it unconditionally
 * via {@code IrisDefines.createIrisReplacements()}. This shadow returns a
 * fixed, modern GL 4.6 macOS environment; per-pack results in the translation
 * matrix must be read with that pinned environment in mind.</p>
 */
public class StandardMacros {
    public static ImmutableList<StringPair> createStandardEnvironmentDefines() {
        ImmutableList.Builder<StringPair> defines = ImmutableList.builder();
        defines.add(new StringPair("MC_VERSION", "260200"));
        defines.add(new StringPair("MC_GL_VERSION", "460"));
        defines.add(new StringPair("MC_GLSL_VERSION", "460"));
        defines.add(new StringPair("MC_OS_MAC", ""));
        defines.add(new StringPair("MC_GL_VENDOR_APPLE", ""));
        defines.add(new StringPair("MC_GL_RENDERER_OTHER", ""));
        defines.add(new StringPair("MC_NORMAL_MAP", ""));
        defines.add(new StringPair("MC_SPECULAR_MAP", ""));
        defines.add(new StringPair("MC_RENDER_QUALITY", "1.0"));
        defines.add(new StringPair("MC_SHADOW_QUALITY", "1.0"));
        defines.add(new StringPair("MC_HAND_DEPTH", "0.125"));
        defines.add(new StringPair("MC_GL_ARB_shader_texture_lod", ""));
        defines.add(new StringPair("MC_GL_EXT_gpu_shader4", ""));
        defines.add(new StringPair("IS_IRIS", ""));
        // The real StandardMacros exports one MC_RENDER_STAGE_<NAME> constant
        // per WorldRenderingPhase ordinal (packs compare renderStage against
        // them, e.g. BSL's gbuffers_skybasic star pass).
        for (net.irisshaders.iris.pipeline.WorldRenderingPhase phase
                : net.irisshaders.iris.pipeline.WorldRenderingPhase.values()) {
            defines.add(new StringPair("MC_RENDER_STAGE_" + phase.name(), String.valueOf(phase.ordinal())));
        }
        return defines.build();
    }

    public static String getMcVersion() {
        return "260200";
    }

    public static String getFormattedIrisVersion() {
        return "1.11.2";
    }

    public static String formatVersionString(final String version) {
        return version;
    }

    public static String getGlVersion(final int name) {
        return "460";
    }
}
