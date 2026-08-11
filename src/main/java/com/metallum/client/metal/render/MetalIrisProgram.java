package com.metallum.client.metal.render;

import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.pipeline.programs.IrisProgram;

import java.util.HashMap;
import java.util.Map;

/**
 * Metal-backend implementation of Iris's {@link IrisProgram} interface; the
 * Metal equivalent of Iris's GL-backed {@code ExtendedShader}.
 *
 * <p>An Iris shaderpack program, on the GL backend, is represented by an
 * {@code ExtendedShader} (a {@code GlProgram} subclass) whose
 * {@code iris$setupState} calls {@code _glUseProgram}, uploads uniforms, binds
 * samplers via {@code ProgramSamplers.update()}, and binds the
 * before/after-translucent {@code GlFramebuffer}. None of those GL operations
 * are valid on Metal: there is no GL context, no GL program handle, and Metal
 * render-pass attachments are set at pass-creation time rather than via FBO
 * binding.
 *
 * <p>{@code MetalIrisProgram} replaces that GL setup with the Metal idiom:
 * <ul>
 *   <li>it holds a reference to the shaderpack's pre-compiled
 *       {@link MetalCompiledRenderPipeline} (constructed and cached by
 *       {@link MetalCrossShaderCompiler#compileShaderpackPipeline} during
 *       {@code ShaderCreator.link} interception);</li>
 *   <li>{@link #iris$setupState} installs that pipeline onto the currently
 *       active {@link MetalRenderPass} (retrieved via
 *       {@link MetalDeviceRegistry#getActiveDevice()}
 *       {@code .createCommandEncoder().currentRenderPass()}) by calling
 *       {@link MetalRenderPass#setCompiledPipeline}, then re-binds each
 *       render-pass sampler whose name matches a {@code SAMPLED_IMAGE}
 *       {@link MetalCompiledRenderPipeline.ResourceBinding} in the shaderpack
 *       pipeline's reflected resource table;</li>
 *   <li>the actual Metal encoder calls ({@code setRenderPipelineState},
 *       {@code setVertexTexture:atIndex:}/{@code setFragmentTexture:atIndex:},
 *       {@code setVertexSamplerState:atIndex:}/{@code setFragmentSamplerState:atIndex:})
 *       are deferred to {@link MetalRenderPass#bindDrawState} on the next draw,
 *       matching Metal's lazy state-push model.</li>
 * </ul>
 *
 * <p><b>No GL entry points are called.</b> Iris's
 * {@code ProgramUniforms.update()}/{@code ProgramSamplers.update()} still run
 * (they are invoked from the GL {@code ExtendedShader} path), but the
 * Task 3 mixins ({@code IrisRenderSystemMixin}/{@code GlStateManagerMixin})
 * short-circuit their GL calls with sentinel handles, so they are no-ops on
 * Metal. {@code MetalIrisProgram} sources its sampler state directly from the
 * {@code samplers} {@link HashMap} passed to {@code iris$setupState} (the
 * render pass's bound textures), not from Iris's GL sampler machinery.
 *
 * <p><b>Package placement.</b> This class lives in
 * {@code com.metallum.client.metal.render} (not an {@code .iris} sub-package)
 * so it can access the package-private {@link MetalCompiledRenderPipeline},
 * {@link MetalCrossShaderCompiler#getCachedShaderpackPipeline},
 * {@link MetalRenderPass#setCompiledPipeline}, {@link MetalCommandEncoder},
 * and {@link MetalDevice} without widening their visibility.
 *
 * <p><b>Framebuffer bridging (Task 7).</b> Iris's GL
 * {@code iris$setupState} ends by calling
 * {@code writingToBeforeTranslucent.bind()} / {@code writingToAfterTranslucent.bind()}
 * to bind the gbuffer FBO. On Metal this has no equivalent: a Metal render
 * pass's color/depth attachments are fixed at pass-creation time (via
 * {@code RenderPassDescriptor} in {@code MetalCommandEncoder.createRenderPass}).
 * {@link #iris$setupState} therefore performs <b>no</b> framebuffer operation;
 * see the inline note in {@link #iris$setupState}.
 */
@Environment(EnvType.CLIENT)
public final class MetalIrisProgram implements IrisProgram {
    private final String name;
    private final MetalCompiledRenderPipeline compiledPipeline;
    private boolean isSetUp;

    /**
     * Construct a {@code MetalIrisProgram} for the named shaderpack program.
     *
     * <p>The matching {@link MetalCompiledRenderPipeline} must already be
     * cached under {@code name} (populated by
     * {@link MetalCrossShaderCompiler#compileShaderpackPipeline}, which the
     * {@code ShaderCreatorMixin} invokes during {@code ShaderCreator.link}
     * interception before this constructor runs). If no cached pipeline exists
     * an {@link IllegalStateException} is thrown so the failure surfaces at
     * registration time with a clear message rather than as a
     * {@code NullPointerException} during render dispatch.
     *
     * @param name the shaderpack program name (the cache key).
     * @throws IllegalStateException if no cached Metal pipeline exists for
     *                               {@code name}.
     */
    public MetalIrisProgram(final String name) {
        this.name = name;
        final MetalCompiledRenderPipeline pipeline = MetalCrossShaderCompiler.getCachedShaderpackPipeline(name);
        if (pipeline == null) {
            throw new IllegalStateException(
                    "[MetalUniversal/Iris] No cached Metal render pipeline for shaderpack program '" + name
                            + "'; MetalCrossShaderCompiler.compileShaderpackPipeline must be called "
                            + "before constructing MetalIrisProgram."
            );
        }
        this.compiledPipeline = pipeline;
    }

    /**
     * @return the shaderpack program name (the cache key used by
     *         {@link MetalCrossShaderCompiler}).
     */
    public String name() {
        return name;
    }

    /**
     * @return the pre-compiled shaderpack Metal render pipeline held by this
     *         program. Non-null.
     */
    public MetalCompiledRenderPipeline compiledPipeline() {
        return compiledPipeline;
    }

    /**
     * Install this program's shaderpack Metal pipeline onto the currently
     * active {@link MetalRenderPass} and bind the render pass's samplers
     * against the shaderpack's reflected {@code SAMPLED_IMAGE} resource slots.
     *
     * <p>Implements {@link IrisProgram#iris$setupState}. Mirrors the intent of
     * {@code ExtendedShader.iris$setupState} (GL path): "make this pass draw
     * with my shaderpack program, with the render pass's textures available as
     * samplers". The mechanism is entirely Metal-native:
     * <ol>
     *   <li>{@link MetalRenderPass#setCompiledPipeline} swaps in the shaderpack
     *       {@link MetalCompiledRenderPipeline} and marks pipeline/vertex-buffer
     *       state dirty.</li>
     *   <li>for each entry in {@code samplers} whose name matches a
     *       {@code SAMPLED_IMAGE} {@link MetalCompiledRenderPipeline.ResourceBinding},
     *       {@link MetalRenderPass#bindTexture} records the texture+ sampler
     *       into the pass's sampler table and marks that descriptor dirty.</li>
     *   <li>on the next draw, {@link MetalRenderPass#bindDrawState} pushes the
     *       new pipeline state and re-binds every dirty descriptor
     *       ({@code setRenderPipelineState}, {@code setTextureAndSampler}
     *       &harr; {@code setVertexTexture}/{@code setFragmentTexture}/
     *       {@code setVertexSamplerState}/{@code setFragmentSamplerState}).</li>
     * </ol>
     *
     * <p><b>Non-Metal no-op.</b> If no active {@link MetalDevice} is registered
     * (or no render pass is currently active) the method sets {@code isSetUp}
     * and returns without touching any encoder. {@code MetalIrisProgram}
     * instances are only created on the Metal path, so a {@code null} device
     * here is purely defensive.
     *
     * <p><b>Framebuffer bridging (Task 7) is a no-op.</b> Metal render-pass
     * color/depth attachments are fixed when the pass is created (via
     * {@code RenderPassDescriptor} in
     * {@code MetalCommandEncoder.createRenderPass}); Iris's
     * {@code GlFramebuffer.bind()} is a GL-only FBO-binding concept with no
     * Metal counterpart. Re-creating the render pass or mutating attachments
     * here would break the Metal render-pass lifecycle, so we intentionally do
     * nothing framebuffer-related. The shaderpack's before/after-translucent
     * gbuffer targets are already the attachments of the active pass.
     *
     * <p><b>Uniforms.</b> Per the simplified adapter decision (SubTask 4.4),
     * uniform upload is not handled here: Iris's {@code ProgramUniforms.update()}
     * runs on the GL path and is short-circuited by the Task 3 mixins. The
     * shaderpack's uniform buffers will be wired to Metal in a later task.
     *
     * @param samplers the active render pass's sampler map (texture-view +
     *                 sampler pairs, keyed by name). May be empty; may be
     *                 {@code null} (treated as empty).
     * @param albedoTex the albedo texture view extracted from
     *                  {@code samplers["Sampler0"]} (or {@code null}). Used by
     *                  the GL path for intensity-swizzle; ignored on Metal.
     */
    @Override
    public void iris$setupState(
            final HashMap<String, GlRenderPass.TextureViewAndSampler> samplers,
            final GpuTextureView albedoTex
    ) {
        isSetUp = true;

        final MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            // Defensive: MetalIrisProgram is only constructed on the Metal
            // path, so a null device here means we are being invoked outside a
            // Metal session. No encoder to touch.
            return;
        }

        final MetalCommandEncoder encoder = device.createCommandEncoder();
        final MetalRenderPass pass = encoder.currentRenderPass();
        if (pass == null) {
            // No active Metal render pass. The dispatch mixin (Task 5) is
            // responsible for calling iris$setupState while a render pass is
            // active; if it has not been wired yet, there is nothing to bind.
            return;
        }

        // Swap in the shaderpack's pre-compiled Metal render pipeline state.
        // This replaces whatever vanilla pipeline the pass was using; the next
        // bindDrawState pushes the new MTLRenderPipelineState, depth-stencil
        // state, cull/fill mode, and re-evaluates every descriptor against the
        // shaderpack's reflected resource table.
        pass.setCompiledPipeline(compiledPipeline);

        // Re-bind the render pass's samplers against the shaderpack pipeline's
        // SAMPLED_IMAGE resource slots. bindTexture stores into the pass's
        // sampler HashMap by name and marks the descriptor dirty; bindDrawState
        // later calls setTextureAndSampler (which dispatches to
        // setVertexTexture/setFragmentTexture/setVertexSamplerState/
        // setFragmentSamplerState per the binding's stageMask). Only samplers
        // whose name matches a SAMPLED_IMAGE ResourceBinding are bound; extras
        // are ignored. A shaderpack sampler with no matching entry in
        // `samplers` will surface as a "Missing sampler" error at draw time
        // (MetalRenderPass.pushDescriptor), matching Metal's strict binding
        // semantics.
        if (samplers != null && !samplers.isEmpty()) {
            for (final Map.Entry<String, GlRenderPass.TextureViewAndSampler> entry : samplers.entrySet()) {
                final String samplerName = entry.getKey();
                final MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(samplerName);
                if (binding == null
                        || binding.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                    continue;
                }
                final GlRenderPass.TextureViewAndSampler value = entry.getValue();
                if (value == null) {
                    continue;
                }
                final GpuTextureView view = value.view();
                final GpuSampler sampler = value.sampler();
                if (view != null && sampler != null) {
                    pass.bindTexture(samplerName, view, sampler);
                }
            }
        }

        // Task 7 (GlFramebuffer -> Metal render-pass bridging): intentionally
        // no framebuffer operation. See javadoc above.
    }

    /**
     * Mark this program as no longer set up.
     *
     * <p>Implements {@link IrisProgram#iris$clearState}. The GL path
     * ({@code ExtendedShader.iris$clearState}) clears active GL uniforms/
     * samplers and restores blend mode. On Metal, render-pass state (pipeline
     * + descriptor bindings) is transient: it is naturally reset when the next
     * render pass is created ({@code MetalCommandEncoder.createRenderPass}).
     * No explicit encoder cleanup is needed, so this method only resets the
     * {@code isSetUp} flag (consumed by the dispatch mixin's
     * {@code iris$isSetUp} guard).
     */
    @Override
    public void iris$clearState() {
        isSetUp = false;
    }

    /**
     * Look up a uniform block's Metal binding index by name.
     *
     * <p>Implements {@link IrisProgram#iris$getBlockIndex}. The GL path
     * ({@code ExtendedShader.iris$getBlockIndex}) calls
     * {@code glGetUniformBlockIndex(program, name)}. On Metal there is no GL
     * program handle, so the {@code program} argument is ignored and the block
     * is resolved by name against the shaderpack pipeline's reflected
     * {@link MetalCompiledRenderPipeline.ResourceBinding} table (uniform
     * buffers are reflected as {@code UNIFORM_BUFFER} resources).
     *
     * <p>Mirrors the GL path's name-prefixing: if {@code uniformBlockName}
     * already contains {@code "u_"} it is looked up verbatim; otherwise the
     * {@code "iris_"}-prefixed form is also tried (Iris prefixes its uniform
     * block names with {@code iris_}).
     *
     * @param program            ignored on Metal (no GL program handle).
     * @param uniformBlockName the uniform block name to resolve.
     * @return the Metal binding index, or {@code -1} if no matching resource.
     */
    @Override
    public int iris$getBlockIndex(final int program, final CharSequence uniformBlockName) {
        if (uniformBlockName == null) {
            return -1;
        }
        final String blockName = uniformBlockName.toString();
        final String metalBlockName = switch (blockName) {
            case "Fog", "iris_Fog", IrisMetalGlslLinker.IRIS_FOG_BLOCK_NAME ->
                    IrisMetalGlslLinker.IRIS_FOG_BLOCK_NAME;
            default -> blockName;
        };
        MetalCompiledRenderPipeline.ResourceBinding binding = compiledPipeline.resource(metalBlockName);
        if (binding == null && !blockName.contains("u_")) {
            binding = compiledPipeline.resource("iris_" + metalBlockName);
        }
        return binding != null ? binding.bindingIndex() : -1;
    }

    /**
     * @return whether {@link #iris$setupState} has been called more recently
     *         than {@link #iris$clearState}. Consumed by the dispatch mixin
     *         (analogous to iris-ref's {@code MixinGlCommandEncoder}
     *         {@code trySetup RETURN} guard) to avoid re-setting-up state on
     *         every draw within the same pass.
     */
    @Override
    public boolean iris$isSetUp() {
        return isSetUp;
    }
}
