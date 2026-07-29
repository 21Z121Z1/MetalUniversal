package com.metallum.client.metal.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link MetalIrisProgram} instances, keyed by
 * shaderpack program name.
 *
 * <p>This registry is the connective tissue between the Iris shaderpack
 * compilation intercept (Task 6: {@code ShaderCreatorMixin}) and the Metal
 * render-dispatch path (Task 5):
 * <ul>
 *   <li>Task 6 ({@code ShaderCreatorMixin}) calls
 *       {@link MetalCrossShaderCompiler#compileShaderpackPipeline} to construct
 *       and cache the {@link MetalCompiledRenderPipeline}, then constructs a
 *       {@link MetalIrisProgram} and {@link #register}s it here under the
 *       program name.</li>
 *   <li>Task 5 (the dispatch mixin, analogous to iris-ref's
 *       {@code MixinGlCommandEncoder}) looks up the program by name via
 *       {@link #get} during render-pass setup and calls
 *       {@code iris$setupState}/{@code iris$clearState} on it.</li>
 * </ul>
 *
 * <p><b>Thread safety.</b> Backed by a {@link ConcurrentHashMap}. All accessors
 * are non-blocking. The registry is read on the render thread (dispatch) and
 * written on the render thread (shaderpack compilation), so contention is not
 * expected, but {@code ConcurrentHashMap} is used defensively in case a
 * shaderpack reload overlaps with a render frame.
 *
 * <p><b>Lifecycle.</b> Entries persist until overwritten by a re-registration
 * with the same name (shaderpack reload recompiles and re-registers). There is
 * no explicit eviction: program names are a bounded set determined by the
 * shaderpack, so the registry's size is bounded by the shaderpack's program
 * count.
 */
@Environment(EnvType.CLIENT)
public final class MetalIrisProgramRegistry {
    private static final ConcurrentHashMap<String, MetalIrisProgram> PROGRAMS = new ConcurrentHashMap<>();

    private MetalIrisProgramRegistry() {
    }

    /**
     * Register (or replace) a {@link MetalIrisProgram} under its
     * {@link MetalIrisProgram#name() program name}.
     *
     * <p>Called by Task 6 ({@code ShaderCreatorMixin}) after the program's
     * Metal render pipeline has been compiled and cached. A re-registration
     * with the same name (e.g. on shaderpack reload) overwrites the prior
     * entry; the displaced program's {@link MetalCompiledRenderPipeline} is
     * left to be reclaimed by {@link MetalCrossShaderCompiler}'s pipeline
     * cache eviction.
     *
     * @param program the program to register. Its name is used as the key.
     */
    public static void register(final MetalIrisProgram program) {
        PROGRAMS.put(program.name(), program);
    }

    /**
     * Look up a {@link MetalIrisProgram} by shaderpack program name.
     *
     * <p>Called by Task 5 (the dispatch mixin) to retrieve the program whose
     * {@code iris$setupState} should be called for the current render pass.
     *
     * @param name the shaderpack program name.
     * @return the registered program, or {@code null} if no program has been
     *         registered under {@code name} (e.g. a vanilla/vanilla-shader
     *         program that has no shaderpack equivalent).
     */
    @Nullable
    public static MetalIrisProgram get(final String name) {
        return PROGRAMS.get(name);
    }

    /**
     * @param name the shaderpack program name.
     * @return {@code true} if a {@link MetalIrisProgram} has been registered
     *         under {@code name}.
     */
    public static boolean has(final String name) {
        return PROGRAMS.containsKey(name);
    }
}
