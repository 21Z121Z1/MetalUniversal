package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redistributable post-chain conformance. The third-party BSL/Potato tests stay
 * as compatibility audits; this gate ensures deferred/composite/final parsing,
 * translation and Metal PSO preparation always execute in CI.
 */
@EnabledOnOs(OS.MAC)
final class IrisMetalBundledPostChainCompilationTest {
    @Test
    void bundledDeferredCompositeAndFinalProgramsBuildMetalPipelines() throws Exception {
        Iris.testing = true;
        ShaderPack pack = new ShaderPack(fixturePath(), StandardMacros.createStandardEnvironmentDefines(), false);
        ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

        GpuFormat[] formats = new GpuFormat[17];
        Arrays.fill(formats, GpuFormat.RGBA8_UNORM);
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource fallback = (identifier, type) -> null;
        MetalDevice device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Bundled Iris post-chain conformance device",
                MemorySegment.NULL
        );
        try {
            try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(device, formats, 32, 8);
                 IrisMetalPostChain chain = IrisMetalPostChain.create(0, programSet, formats.length, new BitSet())) {
                assertFalse(chain.passInfos(IrisMetalPostChain.Stage.DEFERRED).isEmpty(),
                        "bundled fixture must expose deferred passes");
                assertFalse(chain.passInfos(IrisMetalPostChain.Stage.COMPOSITE).isEmpty(),
                        "bundled fixture must expose composite passes");
                assertTrue(chain.hasFinalShader(), "bundled fixture must expose final shader");
                chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);
            }
        } finally {
            MetalFxManager.close();
            device.close();
        }
    }

    private static Path fixturePath() throws URISyntaxException {
        var resource = IrisMetalBundledPostChainCompilationTest.class
                .getResource("/iris-conformance-compute/shaders");
        assertNotNull(resource, "missing bundled Iris compute/post-chain fixture");
        return Path.of(resource.toURI());
    }
}
