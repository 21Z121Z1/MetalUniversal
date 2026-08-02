package com.metallum.client.metal.render;

import com.google.common.collect.ImmutableList;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Device gate for the installed Potato and BSL deferred/composite/final program sets. */
@EnabledOnOs(OS.MAC)
final class IrisMetalPostChainCompilationTest {
    @Test
    void potatoPostProgramsBuildMetalPipelines() throws Exception {
        Path packPath = Path.of(System.getProperty(
                "metallum.iris.potato.path", "run/shaderpacks/potato-shaders.zip"
        ));
        assumeTrue(
                Files.isRegularFile(packPath),
                "SKIPPED: missing Potato shader-pack fixture: " + packPath
        );

        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(packPath)) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    environmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            GpuFormat[] formats = new GpuFormat[17];
            Arrays.fill(formats, GpuFormat.RGBA8_UNORM);

            MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
            assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
            ShaderSource fallback = (identifier, type) -> null;
            MetalDevice device = new MetalDevice(
                    fallback,
                    new GpuDebugOptions(2, true, true, true),
                    nativeDevice,
                    MemorySegment.NULL,
                    "Iris Potato post-chain compilation device",
                    MemorySegment.NULL
            );
            try {
                try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                        device, formats, 32, 8
                ); IrisMetalPostChain chain = IrisMetalPostChain.create(
                        1, programSet, formats.length, new BitSet()
                )) {
                    assertFalse(chain.passInfos(IrisMetalPostChain.Stage.DEFERRED).isEmpty());
                    assertFalse(chain.passInfos(IrisMetalPostChain.Stage.COMPOSITE).isEmpty());
                    assertTrue(chain.hasFinalShader());
                    assertEquals(Set.of(0), chain.mipmappedTargets(),
                            "Potato composite4 requires a full colortex0 mip chain");

                    chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);
                }
            } finally {
                MetalFxManager.close();
                device.close();
            }
        }
    }

    @Test
    void bslPostProgramsBuildMetalPipelines() throws Exception {
        Path packPath = Path.of(System.getProperty(
                "metallum.iris.bsl.path", "run/shaderpacks/bsl-shaders.zip"
        ));
        assumeTrue(
                Files.isRegularFile(packPath),
                "SKIPPED: missing BSL shader-pack fixture: " + packPath
        );

        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(packPath)) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    environmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            GpuFormat[] formats = new GpuFormat[17];
            Arrays.fill(formats, GpuFormat.RGBA8_UNORM);

            MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
            assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
            ShaderSource fallback = (identifier, type) -> null;
            MetalDevice device = new MetalDevice(
                    fallback,
                    new GpuDebugOptions(2, true, true, true),
                    nativeDevice,
                    MemorySegment.NULL,
                    "Iris BSL post-chain compilation device",
                    MemorySegment.NULL
            );
            try {
                try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                        device, formats, 32, 8
                ); IrisMetalPostChain chain = IrisMetalPostChain.create(
                        2, programSet, formats.length, new BitSet()
                )) {
                    assertFalse(chain.passInfos(IrisMetalPostChain.Stage.DEFERRED).isEmpty());
                    assertFalse(chain.passInfos(IrisMetalPostChain.Stage.COMPOSITE).isEmpty());
                    assertTrue(chain.hasFinalShader());
                    assertEquals(Set.of("sampler2DShadow"), chain.samplerTypes("shadowtex0"));
                    assertEquals(Set.of("sampler2DShadow"), chain.samplerTypes("shadowtex1"));
                    assertEquals(Set.of("sampler2D"), chain.samplerTypes("shadowcolor0"));

                    chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);
                }
            } finally {
                MetalFxManager.close();
                device.close();
            }
        }
    }

    private static ImmutableList<StringPair> environmentDefines() {
        return StandardMacros.createStandardEnvironmentDefines();
    }
}
