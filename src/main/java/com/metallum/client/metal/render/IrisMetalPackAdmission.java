package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import org.joml.Vector2f;
import org.joml.Vector3i;

import java.util.Map;
import java.util.Objects;

/** Fail-closed capability admission for the fixed Iris 1.11.2 execution surface. */
final class IrisMetalPackAdmission {
    private static final ProgramArrayId[] EXECUTED_RASTER_ARRAYS = {
            ProgramArrayId.Begin,
            ProgramArrayId.ShadowComposite,
            ProgramArrayId.Prepare,
            ProgramArrayId.Deferred,
            ProgramArrayId.Composite
    };

    private IrisMetalPackAdmission() {
    }

    static void requireSupported(final ProgramSet programSet, final ColorSpace outputColorSpace) {
        Objects.requireNonNull(programSet, "programSet");
        Objects.requireNonNull(outputColorSpace, "outputColorSpace");
        ShaderPack pack = Objects.requireNonNull(programSet.getPack(), "shaderPack");

        validateRenderTargetFormats(programSet.getPackDirectives());
        requireColorSpaceSupported(
                outputColorSpace,
                programSet.getPackDirectives().supportsColorCorrection()
        );

        for (ProgramId id : ProgramId.values()) {
            if (id.getGroup() == ProgramGroup.Dh) {
                continue;
            }
            programSet.get(id).filter(ProgramSource::isValid).ifPresent(source ->
                    validateProgramSource(id.getSourceName(), source)
            );
        }
        for (ProgramArrayId id : EXECUTED_RASTER_ARRAYS) {
            for (ProgramSource source : programSet.getComposite(id)) {
                if (source != null && source.isValid()) {
                    validateProgramSource(id.getSourcePrefix(), source);
                }
            }
            validateComputeGroups(programSet.getCompute(id), pack.getBufferObjects());
        }
        validateComputeGroup(programSet.getSetup(), pack.getBufferObjects());
        validateComputeGroup(programSet.getShadowCompute(), pack.getBufferObjects());
        validateComputeGroup(programSet.getFinalCompute(), pack.getBufferObjects());

        IrisMetalCustomTextures.validatePack(pack);
        IrisMetalComputeResources.validatePack(pack);
    }

    /**
     * Validates every explicit Iris render-target declaration before any
     * generation-owned texture or pipeline resource is created.
     */
    static void validateRenderTargetFormats(final PackDirectives directives) {
        Objects.requireNonNull(directives, "packDirectives");
        for (Map.Entry<Integer, RenderTargetSettings> entry
                : directives.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 0) {
                throw unsupported(
                        "render-target",
                        String.valueOf(index),
                        "logical target index must be non-negative"
                );
            }
            RenderTargetSettings settings = entry.getValue();
            if (settings == null || settings.getInternalFormat() == null) {
                continue;
            }
            String internalFormat = settings.getInternalFormat().name();
            try {
                IrisMetalPipelineOverrides.formatForInternalName(internalFormat);
            } catch (IllegalArgumentException exception) {
                throw unsupported(
                        "render-target",
                        "colortex" + index,
                        "internal format " + internalFormat + " has no exact Metal lowering"
                );
            }
        }
    }

    /** Fixed Iris color spaces are lowered by the post-final Metal pass. */
    static void requireColorSpaceSupported(
            final ColorSpace colorSpace,
            final boolean packOwnsColorCorrection
    ) {
        Objects.requireNonNull(colorSpace, "colorSpace");
        // The fixed enum is exhaustive. Pack-owned color correction bypasses
        // Iris's converter exactly as it does on the OpenGL pipeline.
    }

    static void validateProgramSource(final String family, final ProgramSource source) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(source, "source");
        validateProgramStages(
                family,
                source.getName(),
                source.getGeometrySource().orElse(null),
                source.getTessControlSource().orElse(null),
                source.getTessEvalSource().orElse(null)
        );
        validateSamplerBuffers(
                family,
                source.getName(),
                source.getVertexSource().orElse(null),
                source.getFragmentSource().orElse(null)
        );
    }

    /**
     * Fixed Iris has no pack-owned samplerBuffer provider ABI for raster
     * programs (including post/final) or compute programs. Reject the
     * declaration while the ProgramSet is still being admitted, before a
     * generation can publish textures or PSOs that would fail later in
     * prepare().
     */
    static void validateSamplerBuffers(
            final String family,
            final String program,
            final String... sources
    ) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(sources, "sources");
        for (String source : sources) {
            if (source == null) {
                continue;
            }
            for (MetalIrisShaderCompiler.SamplerDecl sampler
                    : MetalIrisShaderCompiler.inspectSamplerDeclarations(source)) {
                if (sampler.isTexelBuffer()) {
                    throw unsupported(
                            family,
                            program,
                            "pack-owned samplerBuffer '" + sampler.name()
                                    + "' has no fixed Iris typed provider ABI"
                    );
                }
            }
        }
    }

    static void validateProgramStages(
            final String family,
            final String program,
            final String geometry,
            final String tessControl,
            final String tessEval
    ) {
        if (geometry != null) {
            throw unsupported(family, program, "geometry shaders have no exact Metal lowering");
        }
        if (tessControl != null || tessEval != null) {
            throw unsupported(family, program, "tessellation shaders have no exact Metal lowering");
        }
    }

    private static void validateComputeGroups(
            final ComputeSource[][] groups,
            final Map<Integer, BuiltShaderStorageInfo> buffers
    ) {
        if (groups == null) {
            return;
        }
        for (ComputeSource[] group : groups) {
            validateComputeGroup(group, buffers);
        }
    }

    private static void validateComputeGroup(
            final ComputeSource[] group,
            final Map<Integer, BuiltShaderStorageInfo> buffers
    ) {
        if (group == null) {
            return;
        }
        for (ComputeSource source : group) {
            if (source != null && source.isValid()) {
                validateComputeSource(source, buffers);
            }
        }
    }

    static void validateComputeSource(
            final ComputeSource source,
            final Map<Integer, BuiltShaderStorageInfo> buffers
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(buffers, "buffers");
        source.getSource().ifPresent(sourceText -> validateSamplerBuffers(
                "compute", source.getName(), sourceText
        ));
        Vector3i absolute = source.getWorkGroups();
        if (absolute != null && (absolute.x() <= 0 || absolute.y() <= 0 || absolute.z() <= 0)) {
            throw unsupported(
                    "compute", source.getName(),
                    "non-positive absolute workgroups " + absolute.x() + 'x' + absolute.y() + 'x' + absolute.z()
            );
        }
        Vector2f relative = source.getWorkGroupRelative();
        if (relative != null && (!Float.isFinite(relative.x()) || relative.x() <= 0.0F
                || !Float.isFinite(relative.y()) || relative.y() <= 0.0F)) {
            throw unsupported(
                    "compute", source.getName(),
                    "non-positive or non-finite relative workgroups " + relative.x() + 'x' + relative.y()
            );
        }
        IndirectPointer indirect = source.getIndirectPointer();
        if (indirect == null) {
            return;
        }
        BuiltShaderStorageInfo buffer = buffers.get(indirect.buffer());
        if (buffer == null) {
            throw unsupported(
                    "compute", source.getName(),
                    "indirect dispatch references undeclared SSBO binding " + indirect.buffer()
            );
        }
        if (indirect.offset() < 0L) {
            throw unsupported(
                    "compute", source.getName(),
                    "indirect dispatch has negative byte offset " + indirect.offset()
            );
        }
        if (!buffer.relative() && indirect.offset() > buffer.size() - 12L) {
            throw unsupported(
                    "compute", source.getName(),
                    "indirect dispatch range " + indirect.offset() + "+12 exceeds SSBO "
                            + indirect.buffer() + " size " + buffer.size()
            );
        }
    }

    private static IrisMetalPackRejectedException unsupported(
            final String family,
            final String program,
            final String reason
    ) {
        return new IrisMetalPackRejectedException(
                "Iris Metal pack admission rejected family=" + family
                        + ", program=" + program + ": " + reason
        );
    }
}
