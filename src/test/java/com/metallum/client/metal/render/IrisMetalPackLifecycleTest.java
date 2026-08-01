package com.metallum.client.metal.render;

import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalPackLifecycleTest {
    @Test
    void loadsOnlyForEnabledSemanticShaders() {
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, false));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(false, true));
        assertFalse(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, false));
        assertTrue(IrisMetalPackLifecycle.shouldLoadConfiguredPack(true, true));
    }

    @Test
    void disabledTransitionRunsOnlyAfterLiveSemanticGenerationWasDestroyed() {
        IrisMetalPackLifecycle.onSemanticPipelineActivated();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));

        IrisMetalPackLifecycle.onSemanticPipelineDestroyed();
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(false, false));
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, true));
        assertTrue(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
    }

    @Test
    void inactiveCachedDimensionTeardownCannotArmDisabledTransition() {
        IrisMetalPackLifecycle.onSemanticPipelineActivated(41);
        IrisMetalPackLifecycle.onSemanticPipelineSelected(42);

        IrisMetalPackLifecycle.onSemanticPipelineDestroyed(41);
        assertFalse(
                IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false),
                "destroying an inactive cached dimension armed the disable transition"
        );

        IrisMetalPackLifecycle.onSemanticPipelineDestroyed(42);
        assertTrue(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
        assertFalse(IrisMetalPackLifecycle.consumeDisabledReloadTransition(true, false));
    }

    @Test
    void admissionRejectsUnloweredRasterStagesBeforeExecution() {
        UnsupportedOperationException geometryFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateProgramStages(
                        "gbuffers", "geometry_fixture", "void main() {}", null, null
                )
        );
        assertTrue(geometryFailure.getMessage().contains("geometry shaders"));

        UnsupportedOperationException tessellationFailure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateProgramStages(
                        "gbuffers", "tessellation_fixture", null, "void main() {}", "void main() {}"
                )
        );
        assertTrue(tessellationFailure.getMessage().contains("tessellation shaders"));
    }

    @Test
    void admissionRejectsNonPositiveComputeDispatch() {
        ComputeSource compute = new ComputeSource(
                "compute_fixture",
                "#version 430\nlayout(local_size_x=1) in; void main() {}",
                null,
                ShaderProperties.empty()
        );
        compute.setWorkGroups(new Vector3i(0, 1, 1));
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateComputeSource(compute, Map.of())
        );
        assertTrue(failure.getMessage().contains("non-positive absolute workgroups"));
    }

    @Test
    void admissionRejectsPackOwnedSamplerBufferBeforeGenerationPublish() {
        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> IrisMetalPackAdmission.validateSamplerBuffers(
                        "composite", "composite0",
                        "// uniform samplerBuffer ignored;\n"
                                + "layout(binding = 3) uniform samplerBuffer history;"
                )
        );
        assertTrue(failure.getMessage().contains("samplerBuffer 'history'"));
        assertTrue(failure.getMessage().contains("typed provider ABI"));
    }

    @Test
    void admissionLeavesOrdinaryPackSamplersSupported() {
        assertDoesNotThrow(() -> IrisMetalPackAdmission.validateSamplerBuffers(
                "composite", "composite0",
                "layout(binding = 0) uniform sampler2D colortex0;"
        ));
    }

    @Test
    void admissionAcceptsEveryFixedIrisColorSpace() {
        for (ColorSpace colorSpace : ColorSpace.values()) {
            IrisMetalPackAdmission.requireColorSpaceSupported(colorSpace, false);
            IrisMetalPackAdmission.requireColorSpaceSupported(colorSpace, true);
        }
    }
}
