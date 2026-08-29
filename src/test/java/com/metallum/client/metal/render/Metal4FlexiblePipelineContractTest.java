package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4FlexiblePipelineContractTest {
    @Test
    void flexiblePipelineIsOptionalTerminalAndGenerationScoped() throws Exception {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String deviceSource = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        ));
        String bridgeSource = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
        ));

        assertTrue(deviceSource.contains("metallum.opt.metal4FlexiblePso"));
        assertTrue(deviceSource.contains("metallum_set_metal4_flexible_pso_enabled"));
        assertTrue(deviceSource.contains("metallum_metal4_flexible_pso_reset"));
        assertTrue(bridgeSource.contains("metallum_set_metal4_flexible_pso_enabled"));
        assertTrue(bridgeSource.contains("metallum_metal4_flexible_pso_reset"));

        assertTrue(nativeSource.contains("flexibleColorState: Bool = false"));
        assertTrue(nativeSource.contains("d.pixelFormat = .unspecialized"));
        assertTrue(nativeSource.contains("d.writeMask = .unspecialized"));
        assertTrue(nativeSource.contains("d.blendingState = .unspecialized"));
        assertTrue(nativeSource.contains("d.sourceRGBBlendFactor = .unspecialized"));
        assertTrue(nativeSource.contains("d.rgbBlendOperation = .unspecialized"));
        assertTrue(nativeSource.contains("makeRenderPipelineDescriptorForSpecialization()"));
        assertTrue(nativeSource.contains("makeRenderPipelineStateBySpecialization("));
        assertTrue(nativeSource.contains("pipeline: base"));

        // Common-state cache identity must not contain concrete color format or blend state.
        int keyStart = nativeSource.indexOf("private struct Metal4FlexiblePipelineBaseKey");
        int keyEnd = nativeSource.indexOf("private struct SamplerKey", keyStart);
        assertTrue(keyStart >= 0 && keyEnd > keyStart);
        String key = nativeSource.substring(keyStart, keyEnd);
        assertTrue(!key.contains("colorFormat"));
        assertTrue(!key.contains("blend"));

        // Reload closes Java PSOs and resets native bases before MTLFunction handles are released.
        int closePipelines = deviceSource.indexOf(
                "this.compiledPipelines.values().forEach(MetalCompiledRenderPipeline::close)"
        );
        int resetBases = deviceSource.indexOf("metallum_metal4_flexible_pso_reset", closePipelines);
        int releaseFunctions = deviceSource.indexOf("this.functionCache.values()", resetBases);
        assertTrue(closePipelines >= 0 && resetBases > closePipelines && releaseFunctions > resetBases);
    }
}
