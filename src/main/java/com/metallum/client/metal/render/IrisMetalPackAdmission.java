package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.buffer.BuiltShaderStorageInfo;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.IndirectPointer;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.joml.Vector2f;
import org.joml.Vector3i;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed capability admission for the fixed Iris 1.11.2 execution surface. */
final class IrisMetalPackAdmission {
    private static final Pattern STORAGE_BUFFER_BINDING = Pattern.compile(
            "layout\\s*\\([^)]*\\bbinding\\s*=\\s*(\\d+)[^)]*\\)\\s*"
                    + "(?:readonly\\s+|writeonly\\s+|coherent\\s+|volatile\\s+|restrict\\s+)*buffer\\b"
    );
    private static final ProgramArrayId[] EXECUTED_RASTER_ARRAYS = {
            ProgramArrayId.Setup,
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

        validateCoreProgramCatalog(programSet);
        IrisMetalStorageImageDeclarations.from(programSet);
        IrisMetalCustomTextures.validatePack(pack);
        IrisMetalComputeResources.validatePack(pack);
    }

    /** Validates explicit render-target formats before any generation resource is created. */
    static void validateRenderTargetFormats(final PackDirectives directives) {
        Objects.requireNonNull(directives, "packDirectives");
        for (Map.Entry<Integer, RenderTargetSettings> entry
                : directives.getRenderTargetDirectives().getRenderTargetSettings().entrySet()) {
            Integer index = entry.getKey();
            if (index == null || index < 0) {
                throw unsupported("render-target", String.valueOf(index),
                        "logical target index must be non-negative");
            }
            RenderTargetSettings settings = entry.getValue();
            if (settings == null || settings.getInternalFormat() == null) {
                continue;
            }
            String internalFormat = settings.getInternalFormat().name();
            try {
                IrisMetalRenderTargetFormats.fromInternalName(internalFormat);
            } catch (IllegalArgumentException failure) {
                throw unsupported(
                        "render-target", "colortex" + index,
                        "internal format " + internalFormat + " has no exact Metal lowering"
                );
            }
        }
    }

    static void requireColorSpaceSupported(
            final ColorSpace colorSpace,
            final boolean packOwnsColorCorrection
    ) {
        Objects.requireNonNull(colorSpace, "colorSpace");
        // This is intentionally exhaustive for Iris 1.11.2. A future enum
        // member must be admitted explicitly after its Metal lowering and
        // readback oracle exist; silently entering the final stage would make
        // the output contract depend on an incomplete shader switch.
        switch (colorSpace) {
            case SRGB, DCI_P3, DISPLAY_P3, REC2020, ADOBE_RGB -> {
                // Pack-owned correction is a valid Iris bypass for every
                // fixed output space; the graph records that ownership later.
                if (packOwnsColorCorrection) {
                    return;
                }
            }
            default -> throw unsupported(
                    "presentation", colorSpace.name(),
                    "no exact fixed-Iris Metal color-space lowering"
            );
        }
    }

    /**
     * Ensures every Mojang draw route in the fixed Iris catalog resolves to an
     * Iris program or Iris fallback before any Metal generation is published.
     */
    private static void validateCoreProgramCatalog(final ProgramSet programSet) {
        ProgramFallbackResolver fallbackResolver = new ProgramFallbackResolver(programSet);
        PackShadowDirectives shadow = programSet.getPackDirectives().getShadowDirectives();
        boolean includeShadow = shadow.isShadowEnabled().orElse(true)
                && fallbackResolver.resolveNullable(ProgramId.ShadowSolid) != null;
        for (ShaderKey key : IrisMetalCoreGbufferPipelines.requiredShaderKeys(includeShadow)) {
            fallbackResolver.resolve(key.getProgram()).ifPresentOrElse(
                    source -> validateProgramSource("core/" + key.getName(), source),
                    () -> {
                        throw unsupported(
                                "core", key.getName(),
                                "no fixed-Iris program or fallback source for " + key.getProgram()
                        );
                    }
            );
        }
    }

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
            for (IrisMetalGlslLinker.SamplerDecl sampler
                    : IrisMetalGlslLinker.inspectSamplerDeclarations(source)) {
                if (sampler.separateImage() || sampler.separateSampler()) {
                    throw unsupported(
                            family,
                            program,
                            "separate image/sampler '" + sampler.name()
                                    + "' has no exact combined texture+sampler Metal ABI"
                    );
                }
                if (sampler.texelBuffer() && !IrisMetalTexelBufferAbi.isFixedProvider(sampler.name())) {
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
        source.getSource().ifPresent(sourceText -> {
            validateSamplerBuffers("compute", source.getName(), sourceText);
            validateStorageBufferDeclarations(source.getName(), sourceText, buffers);
        });
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
            throw unsupported("compute", source.getName(), "indirect dispatch has negative byte offset");
        }
        if ((indirect.offset() & 3L) != 0L) {
            throw unsupported("compute", source.getName(), "indirect dispatch byte offset is not 4-byte aligned");
        }
        if (!buffer.relative() && indirect.offset() > buffer.size() - 12L) {
            throw unsupported(
                    "compute", source.getName(),
                    "indirect dispatch range " + indirect.offset() + "+12 exceeds SSBO "
                            + indirect.buffer() + " size " + buffer.size()
            );
        }
    }

    static void validateStorageBufferDeclarations(
            final String program,
            final String source,
            final Map<Integer, BuiltShaderStorageInfo> buffers
    ) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(buffers, "buffers");
        Matcher matcher = STORAGE_BUFFER_BINDING.matcher(source);
        while (matcher.find()) {
            int binding = Integer.parseInt(matcher.group(1));
            BuiltShaderStorageInfo declared = buffers.get(binding);
            if (declared == null) {
                throw unsupported(
                        "compute", program,
                        "SSBO binding " + binding + " is not declared by the fixed Iris pack ABI"
                );
            }
            if (!declared.relative() && declared.size() <= 0L) {
                throw unsupported(
                        "compute", program,
                        "SSBO binding " + binding + " has no positive storage size"
                );
            }
            if (declared.relative()
                    && (!Float.isFinite(declared.scaleX()) || !Float.isFinite(declared.scaleY())
                    || declared.scaleX() <= 0.0F || declared.scaleY() <= 0.0F)) {
                throw unsupported(
                        "compute", program,
                        "relative SSBO binding " + binding + " has invalid scale"
                );
            }
        }
    }

    private static UnsupportedOperationException unsupported(
            final String family,
            final String program,
            final String reason
    ) {
        return new UnsupportedOperationException(
                "Iris Metal pack admission rejected family=" + family
                        + ", program=" + program + ": " + reason
        );
    }
}
