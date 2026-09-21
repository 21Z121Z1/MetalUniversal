package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MetalFrameGenerationSemanticGateSourceContractTest {
    @Test
    void blockEntityDispatcherUsesExceptionSafeExactInvokeRedirect() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/render/BlockEntityRenderDispatcherMetalFxMixin.java"
        ));
        assertTrue(source.contains("@Redirect("));
        assertTrue(source.contains("BlockEntityRenderer;submit"));
        assertTrue(source.contains("try {"));
        assertTrue(source.contains("finally {"));
        assertFalse(source.contains("@Inject(method = \"submit\", at = @At(\"HEAD\"))"));
    }

    @Test
    void auditedBlockEntityAllowlistCannotAdmitSubclassesImplicitly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalBlockEntityExactMotion.java"
        ));
        assertTrue(source.contains("stateClass == ChestRenderState.class"));
        assertTrue(source.contains("stateClass == BannerRenderState.class"));
        assertTrue(source.contains("stateClass == ShulkerBoxRenderState.class"));
        assertTrue(source.contains("stateClass == BellRenderState.class"));
        assertFalse(source.contains("isAssignableFrom"));
    }

    @Test
    void IrisSelectionAndFinalAdmissionBothFailClosed() throws Exception {
        String iris = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/IrisMetalPipelineOverrides.java"
        ));
        String manager = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
        ));
        assertTrue(iris.contains("MetalFxManager.observeIrisMotionSemanticsUnproven();"));
        assertTrue(manager.contains("irisMotionSemanticsUnprovenThisFrame"));
        assertTrue(manager.contains("iris-active-pipeline-motion-semantics-unproven"));
        assertTrue(manager.contains("!IrisMetalPipelineOverrides.frameGenerationMotionSemanticsProven()"));
    }
}
