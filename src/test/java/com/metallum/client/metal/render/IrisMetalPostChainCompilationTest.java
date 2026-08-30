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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

                    IrisMetalOptimizationBootstrap.onPostChainCreated(chain);
                    chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);
                    IrisMetalOptimizationPlan plan = IrisMetalExperimentalOptimizer.active();
                    assertNotNull(plan, "production post-chain plan must be published");
                    IrisMetalOptimizationPlan.AttachmentLifetimeReceipt before =
                            plan.attachmentLifetimeReceipt();
                    assertNotNull(before, "prepare must publish a live-target attachment receipt");
                    assertEquals(targets.allocationStamp(), before.targetEpoch());
                    Set<Long> liveAllocations = new java.util.HashSet<>();
                    for (int logical = 0; logical < targets.colorTargets().targetCount(); logical++) {
                        liveAllocations.add(targets.colorTargets().mainTexture(logical).allocationId());
                        liveAllocations.add(targets.colorTargets().altTexture(logical).allocationId());
                    }
                    var resolved = before.attachments().stream()
                            .filter(attachment -> attachment.resolution()
                                    == IrisMetalOptimizationPlan.AttachmentResolution.RESOLVED_RASTER)
                            .toList();
                    assertFalse(resolved.isEmpty(),
                            "at least one production raster candidate must bind a live allocation");
                    assertTrue(resolved.stream()
                                    .allMatch(attachment -> liveAllocations.contains(attachment.allocationId())),
                            "production receipt must use live main/alt allocation ids");

                    long oldEpoch = before.targetEpoch();
                    String oldSignature = before.targetSignature();
                    targets.resize(64, 8);
                    IrisMetalOptimizationPlan.AttachmentLifetimeReceipt stale =
                            IrisMetalExperimentalOptimizer.active().attachmentLifetimeReceipt();
                    assertNotNull(stale);
                    assertEquals("STALE_UNRESOLVED", stale.status());
                    assertNotEquals(oldEpoch, stale.targetEpoch());

                    chain.prepare(device, targets, GpuFormat.RGBA8_UNORM, fallback);
                    IrisMetalOptimizationPlan.AttachmentLifetimeReceipt after =
                            IrisMetalExperimentalOptimizer.active().attachmentLifetimeReceipt();
                    assertNotNull(after);
                    assertNotEquals("STALE_UNRESOLVED", after.status());
                    assertNotEquals(oldEpoch, after.targetEpoch());
                    assertNotEquals(oldSignature, after.targetSignature());
                    IrisMetalOptimizationBootstrap.onPostChainClosed();
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
