package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalIrisProgram;

import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide pending-clear list shared between {@link MetalIrisPipelineMixin}
 * (which appends a {@link MetalIrisProgram} after calling
 * {@code iris$setupState}) and {@link MetalIrisClearMixin} (which calls
 * {@code iris$clearState} on each entry and empties the list when a render pass
 * is submitted).
 *
 * <p>This static holder avoids the need for one mixin to {@code @Shadow} a
 * {@code @Unique} field declared on another mixin's target class, which is
 * awkward and fragile. Iris-ref's {@code MixinGlCommandEncoder} instead stores
 * the equivalent list as a {@code @Unique} instance field directly on the GL
 * command-encoder mixin, because on the GL path both the setup
 * ({@code trySetup RETURN}) and the clear ({@code submitRenderPass HEAD})
 * injectors target the same class ({@code GlCommandEncoder}). On Metal the two
 * concerns split across two package-private classes &mdash; setup is driven by
 * {@code MetalRenderPass.setPipeline} and clear by
 * {@code MetalCommandEncoder.submitRenderPass} &mdash; so a shared static
 * holder is simpler than cross-mixin field access.
 *
 * <p><b>Thread safety.</b> Both {@code setPipeline} and
 * {@code submitRenderPass} execute on the render thread, so an unsynchronized
 * {@link ArrayList} is sufficient.
 */
final class MetalIrisProgramsToClear {
    static final List<MetalIrisProgram> PROGRAMS = new ArrayList<>();

    private MetalIrisProgramsToClear() {
    }
}
