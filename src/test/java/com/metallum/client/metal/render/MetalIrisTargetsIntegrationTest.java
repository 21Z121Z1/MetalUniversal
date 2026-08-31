package com.metallum.client.metal.render;

import com.metallum.client.metal.render.IrisMetalRenderTargets.RenderPassDescriptorWithViews;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Content-level (no screen observation) validation of the Iris target
 * framework: colortex main/alt ping-pong flipping, snapshot/restore,
 * feedback-loop guard, depthtex0/1/2 copy semantics, shadow targets with
 * state isolation, and resize resets — all through the production backend
 * with GPU readback of every asserted texture.
 */
@EnabledOnOs(OS.MAC)
final class MetalIrisTargetsIntegrationTest {
    private static final int WIDTH = 32;
    private static final int HEIGHT = 8;

    private final Map<String, String> fragmentShaders = new HashMap<>();
    private final Map<String, String> vertexShaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource source = (identifier, type) -> {
            String name = identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1);
            return type == ShaderType.VERTEX
                    ? vertexShaders.getOrDefault(name, FULLSCREEN_VERTEX)
                    : fragmentShaders.get(name);
        };
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris targets integration device",
                MemorySegment.NULL
        );
        encoder = device.commandEncoder();
    }

    @AfterEach
    void closeDevice() {
        MetalFxManager.close();
        if (device != null) {
            device.close();
        }
    }

    @Test
    void pingPongThreePassChainKeepsBothSidesCorrect() {
        registerConstantFragment("iris_red", "vec4(1.0, 0.0, 0.0, 1.0)");
        registerConstantFragment("iris_green", "vec4(0.0, 1.0, 0.0, 1.0)");
        registerConstantFragment("iris_blue", "vec4(0.0, 0.0, 1.0, 1.0)");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RG16_FLOAT}, WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            assertFalse(color.isFlipped(0));
            assertFalse(color.flippedAtLeastOnce(0));

            // Pass 1: write red to the write side (alt), then flip -> reads see red.
            runColorPass(targets, "iris_red", new int[]{0});
            color.flip(0);
            assertTrue(color.isFlipped(0));
            assertTrue(color.flippedAtLeastOnce(0));
            assertRgba(color.readTexture(0), 255, 0, 0, "read side after pass1+flip");

            // Pass 2: write green (lands on the former main), flip again.
            runColorPass(targets, "iris_green", new int[]{0});
            color.flip(0);
            assertFalse(color.isFlipped(0));
            assertRgba(color.readTexture(0), 0, 255, 0, "read side after pass2+flip");
            // History side must still hold pass1's red until overwritten.
            assertRgba(color.writeTexture(0), 255, 0, 0, "write side keeps previous history");

            // Pass 3 without flip: write blue; the read side must stay green.
            runColorPass(targets, "iris_blue", new int[]{0});
            assertRgba(color.readTexture(0), 0, 255, 0, "read side unchanged without flip");
            assertRgba(color.writeTexture(0), 0, 0, 255, "write side holds pass3 output");
        }
    }

    @Test
    void gbufferWritesCurrentReadableSidesWithoutFlipping() {
        fragmentShaders.put("iris_gbuffer_mrt", """
                #version 450
                layout(location=0) out vec4 colortex0;
                layout(location=1) out vec4 colortex2;
                void main() {
                    colortex0 = vec4(1.0, 0.0, 0.0, 1.0);
                    colortex2 = vec4(0.0, 1.0, 0.0, 1.0);
                }
                """);
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT
        )) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            runGbufferPass(targets, "iris_gbuffer_mrt", new int[]{0, 2});

            assertFalse(color.isFlipped(0), "gbuffer must not flip colortex0");
            assertFalse(color.isFlipped(2), "gbuffer must not flip colortex2");
            assertRgba(color.readTexture(0), 255, 0, 0, "gbuffer colortex0 current side");
            assertRgba(color.readTexture(2), 0, 255, 0, "gbuffer colortex2 current side");
        }
    }

    @Test
    void singleTargetGbufferDescriptorUsesGenerationColortexZero() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT
        )) {
            GpuTextureView sceneColor = targets.colorTargets().writeView(0);
            var descriptor = targets.createTerrainWriteDescriptor(
                    "single colortex0",
                    new int[]{0},
                    sceneColor,
                    null,
                    null,
                    null
            );
            GpuTextureView attachment = descriptor.colorAttachments().getFirst().textureView();
            assertSame(targets.colorTargets().readView(0), attachment);
            assertNotSame(sceneColor, attachment,
                    "single-target gbuffer must not bypass generation-owned colortex0");
        }
    }

    @Test
    void snapshotAndRestoreRewindFlipState() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            color.flip(0);
            color.flip(2);
            BitSet snapshot = color.snapshot();
            color.flip(1);
            color.flip(2);
            assertTrue(color.isFlipped(1));
            assertFalse(color.isFlipped(2));
            color.restore(snapshot);
            assertTrue(color.isFlipped(0), "restore must rewind target 0");
            assertFalse(color.isFlipped(1), "restore must rewind target 1");
            assertTrue(color.isFlipped(2), "restore must rewind target 2");
            assertTrue(color.flippedAtLeastOnce(1), "history flag is monotonic across restore");
        }
    }

    @Test
    void feedbackLoopGuardRejectsSameTargetReadWrite() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            IllegalStateException loop = assertThrows(
                    IllegalStateException.class,
                    () -> targets.createWriteDescriptor(
                            "feedback", new int[]{0, 1}, null, false, null, new int[]{1})
            );
            assertTrue(loop.getMessage().contains("feedback loop"));
            // Disjoint read/write sets must pass.
            try (RenderPassDescriptorWithViews ok = targets.createWriteDescriptor(
                    "no-feedback", new int[]{0}, null, false, null, new int[]{1})) {
                assertNotNull(ok.descriptor());
            }
        }
    }

    @Test
    void depthCaptureSemanticsProduceThreeDistinctDepthTextures() {
        registerConstantFragment("iris_depth_pass", "vec4(1.0)");
        registerDepthVertex("iris_depth_025", "0.25");
        registerDepthVertex("iris_depth_050", "0.5");
        registerDepthVertex("iris_depth_075", "0.75");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            // Opaque geometry at z=0.25.
            runDepthPass(targets, "iris_depth_025", 1.0);
            targets.captureNoTranslucentsDepth(encoder);
            // Translucent geometry at z=0.5 (always-pass overwrite for the test).
            runDepthPass(targets, "iris_depth_050", null);
            targets.captureNoHandDepth(encoder);
            // Hand at z=0.75.
            runDepthPass(targets, "iris_depth_075", null);

            assertDepth(targets.noTranslucentsDepthTexture(), 0.25F, "depthtex1 (no translucents)");
            assertDepth(targets.noHandDepthTexture(), 0.5F, "depthtex2 (no hand)");
            assertDepth(targets.mainDepthTexture(), 0.75F, "depthtex0 (main)");
        }
    }

    @Test
    void shadowTargetsHoldDepthAndColorWithIsolationAndResize() {
        registerConstantFragment("iris_shadow_white", "vec4(1.0, 1.0, 1.0, 1.0)");
        registerDepthVertex("iris_shadow_030", "0.3");
        registerDepthVertex("iris_shadow_010", "0.1");
        try (IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM}, 128);
             IrisMetalRenderTargets main = new IrisMetalRenderTargets(
                     device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            assertSame(shadow.colorTargets().mainView(0), shadow.terrainRenderTarget().getColorTextureView(),
                    "Sodium shadow facade must expose Iris shadowcolor0 main");
            assertSame(shadow.shadowDepthView(), shadow.terrainRenderTarget().getDepthTextureView(),
                    "Sodium shadow facade must expose Iris shadow depth");
            assertEquals(128, shadow.terrainRenderTarget().width);
            assertEquals(128, shadow.terrainRenderTarget().height);
            registerConstantFragment("iris_main_red", "vec4(1.0, 0.0, 0.0, 1.0)");
            runColorPass(main, "iris_main_red", new int[]{0});

            // Opaque shadow casters at z=0.3 writing shadowcolor0.
            runShadowPass(shadow, "iris_shadow_030", "iris_shadow_white", 1.0);
            shadow.captureNoTranslucentsDepth(encoder);
            // Translucent casters at z=0.1 afterwards.
            runShadowPass(shadow, "iris_shadow_010", "iris_shadow_white", null);

            assertDepth(shadow.shadowDepthTexture(), 0.1F, "shadowtex0 after translucents");
            assertDepth(shadow.shadowDepthNoTranslucentsTexture(), 0.3F, "shadowtex1 (no translucents)");
            assertRgba(shadow.colorTargets().readTexture(0), 255, 255, 255, "shadowcolor0 main side");

            // Main targets must be untouched by shadow encoding (state isolation).
            assertRgba(main.colorTargets().writeTexture(0), 255, 0, 0, "main colortex isolated from shadow pass");

            // Pack-config resize rebuilds shadow textures at the new square size.
            MetalGpuTexture oldShadowDepth = shadow.shadowDepthTexture();
            MetalGpuTexture oldShadowDepthNoTranslucents = shadow.shadowDepthNoTranslucentsTexture();
            MetalGpuTexture oldShadowColor = shadow.colorTargets().mainTexture(0);
            MetalGpuTextureView oldShadowColorView = shadow.colorTargets().readView(0);
            MetalGpuTextureView oldShadowDepthView = shadow.shadowDepthView();
            shadow.resize(64);
            assertTrue(oldShadowDepth.isClosed(), "resize must retire shadowtex0");
            assertTrue(oldShadowDepthNoTranslucents.isClosed(), "resize must retire shadowtex1");
            assertTrue(oldShadowColor.isClosed(), "resize must retire old shadowcolor texture");
            assertTrue(oldShadowColorView.isClosed(), "resize must retire old shadowcolor view");
            assertTrue(oldShadowDepthView.isClosed(), "resize must retire old shadow depth view");
            assertEquals(64, shadow.resolution());
            assertEquals(64, shadow.shadowDepthTexture().getWidth(0));
            runShadowPass(shadow, "iris_shadow_030", "iris_shadow_white", 1.0);
            assertDepth(shadow.shadowDepthTexture(), 0.3F, "shadowtex0 after resize re-render");
        }
    }

    @Test
    void shadowCompositeMrtWritesAndPublishesBothTargets() {
        fragmentShaders.put("iris_shadow_mrt", """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() {
                    first = vec4(1.0, 0.0, 0.0, 1.0);
                    second = vec4(0.0, 1.0, 0.0, 1.0);
                }
                """);
        try (IrisMetalShadowTargets shadow = new IrisMetalShadowTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                32
        )) {
            BitSet readsFromAlt = new BitSet();
            for (int target = 0; target < 2; target++) {
                encoder.clearColorTexture(shadow.colorTargets().mainTexture(target), new Vector4f(0.0F));
                encoder.clearColorTexture(shadow.colorTargets().altTexture(target), new Vector4f(0.0F));
            }
            RenderPipeline pipeline = RenderPipeline.builder()
                    .withLocation("metallum_iris/iris_shadow_mrt")
                    .withVertexShader("metallum_iris/fullscreen")
                    .withFragmentShader("metallum_iris/iris_shadow_mrt")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withColorTargetState(1, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            try (IrisMetalRenderTargets.RenderPassDescriptorWithViews descriptor =
                         shadow.createShadowCompositeDescriptor(
                                 "iris shadow MRT composite", new int[]{0, 1}, readsFromAlt,
                                 0, 0, 32, 32
                         )) {
                MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
                pass.setPipeline(pipeline);
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertRgba(shadow.colorTargets().mainTexture(0), 0, 0, 0, "shadow MRT main target0 untouched");
            assertRgba(shadow.colorTargets().mainTexture(1), 0, 0, 0, "shadow MRT main target1 untouched");
            assertRgba(shadow.colorTargets().altTexture(0), 255, 0, 0, "shadow MRT alt target0");
            assertRgba(shadow.colorTargets().altTexture(1), 0, 255, 0, "shadow MRT alt target1");

            BitSet published = new BitSet();
            published.set(0, 2);
            shadow.publishFlipState(published);
            assertRgba(shadow.colorTargets().readTexture(0), 255, 0, 0, "published shadow MRT target0");
            assertRgba(shadow.colorTargets().readTexture(1), 0, 255, 0, "published shadow MRT target1");
        }
    }

    @Test
    void resizeResetsFlipStateAndUsesNewExtent() {
        registerConstantFragment("iris_resize_red", "vec4(1.0, 0.0, 0.0, 1.0)");
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            runColorPass(targets, "iris_resize_red", new int[]{0});
            color.flip(0);
            assertTrue(color.isFlipped(0));

            targets.resize(WIDTH * 2, HEIGHT * 2);
            assertFalse(color.isFlipped(0), "resize must reset flip state");
            assertFalse(color.flippedAtLeastOnce(0), "resize must reset flip history");
            assertEquals(WIDTH * 2, targets.width());
            assertEquals(WIDTH * 2, color.readTexture(0).getWidth(0), "textures must be rebuilt at the new extent");

            runColorPass(targets, "iris_resize_red", new int[]{0});
            color.flip(0);
            assertRgba(color.readTexture(0), 255, 0, 0, "post-resize render lands in fresh textures");
        }
    }

    @Test
    void resizeDiscardsDeferredClearsForRetiredTextures() {
        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device, new GpuFormat[]{GpuFormat.RGBA8_UNORM}, WIDTH, HEIGHT)) {
            MetalGpuTexture retired = targets.colorTargets().mainTexture(0);
            encoder.clearColorTexture(retired, new Vector4f(0.25F, 0.5F, 0.75F, 1.0F));
            assertTrue(encoder.hasPendingClear(retired));

            targets.resize(WIDTH * 2, HEIGHT * 2);

            assertTrue(retired.isClosed(), "resize must retire the old texture");
            assertFalse(
                    encoder.hasPendingClear(retired),
                    "retiring a texture must remove its deferred clear"
            );
            assertDoesNotThrow(() -> {
                try (MetalComputePass ignored = encoder.createComputePass()) {
                    // The original failure occurred while this boundary
                    // flushed stale clears from the pre-resize generation.
                }
            });
        }
    }

    @Test
    void postTargetRenderMipmapRenderFollowsPhysicalReadSide() {
        fragmentShaders.put("iris_mip_source", """
                #version 450
                layout(location=0) out vec4 fragColor;
                void main() {
                    fragColor = gl_FragCoord.x < 16.0
                            ? vec4(1.0, 0.0, 0.0, 1.0)
                            : vec4(0.0, 0.0, 1.0, 1.0);
                }
                """);
        fragmentShaders.put("iris_mip_sample", """
                #version 450
                uniform sampler2D SourceSampler;
                layout(location=0) out vec4 fragColor;
                void main() {
                    fragColor = textureLod(SourceSampler, vec2(0.125, 0.5), 2.0);
                }
                """);

        try (IrisMetalRenderTargets targets = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT,
                Map.of(),
                Set.of(0)
        )) {
            IrisMetalPingPongTargets color = targets.colorTargets();
            assertEquals(6, color.mainTexture(0).getMipLevels());
            assertEquals(6, color.altTexture(0).getMipLevels());
            assertEquals(6, color.sampleReadView(0).mipLevels(), "sampled view must expose the complete chain");
            assertEquals(1, color.mainTexture(1).getMipLevels(), "unrequested target must stay single-level");
            assertEquals(
                    MTLSamplerMipFilter.NotMipmapped,
                    ((MetalGpuSampler) targets.colorSampler(0)).mipFilter()
            );

            RenderPipeline sourcePipeline = RenderPipeline.builder()
                    .withLocation("metallum_iris/iris_mip_source")
                    .withVertexShader("metallum_iris/fullscreen")
                    .withFragmentShader("metallum_iris/iris_mip_source")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            MetalRenderPass sourcePass = (MetalRenderPass) encoder.createRenderPass(
                    targets.createTerrainWriteDescriptor(
                            "iris mip source", new int[]{0}, color.writeView(0),
                            new Vector4f(0.0F, 0.0F, 0.0F, 1.0F), null, null
                    )
            );
            sourcePass.setPipeline(sourcePipeline);
            sourcePass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();

            encoder.generateMipmaps(color.readTexture(0));
            targets.enableReadMipmaps(0);
            assertEquals(
                    MTLSamplerMipFilter.Linear,
                    ((MetalGpuSampler) targets.colorSampler(0)).mipFilter()
            );

            BindGroupLayout sampleLayout = BindGroupLayout.builder()
                    .withSampler("SourceSampler")
                    .build();
            RenderPipeline samplePipeline = RenderPipeline.builder()
                    .withLocation("metallum_iris/iris_mip_sample")
                    .withVertexShader("metallum_iris/fullscreen")
                    .withFragmentShader("metallum_iris/iris_mip_sample")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withBindGroupLayout(sampleLayout)
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            try (RenderPassDescriptorWithViews descriptor = targets.createWriteDescriptor(
                    "iris mip sample", new int[]{0}, null, false, null, null
            )) {
                MetalRenderPass samplePass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
                samplePass.setPipeline(samplePipeline);
                samplePass.bindTexture("SourceSampler", color.sampleReadView(0), targets.colorSampler(0));
                samplePass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
            }

            assertRgba(color.writeTexture(0), 255, 0, 0, "LOD2 sampled after render-to-mipmap ordering");

            color.flip(0);
            assertFalse(color.readMipmapsEnabled(0), "generating main mips must not enable alt sampling");
            assertEquals(
                    MTLSamplerMipFilter.NotMipmapped,
                    ((MetalGpuSampler) targets.colorSampler(0)).mipFilter()
            );
            color.flip(0);
            assertTrue(color.readMipmapsEnabled(0), "main side keeps Iris's within-frame stale-mip state");
            targets.resetMipmaps();
            assertFalse(color.readMipmapsEnabled(0), "final reset must clear both physical sides");
        }
    }

    @Test
    void logicalRgbTargetSamplingForcesOpenGlAlphaOne() {
        registerConstantFragment("iris_rgb_physical", "vec4(0.25, 0.5, 0.75, 0.0)");
        fragmentShaders.put("iris_rgb_sample", """
                #version 450
                uniform sampler2D SourceSampler;
                layout(location=0) out vec4 fragColor;
                void main() {
                    fragColor = texture(SourceSampler, vec2(0.5));
                }
                """);

        try (IrisMetalPingPongTargets source = new IrisMetalPingPongTargets(
                device,
                "iris-logical-rgb",
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT,
                Set.of(),
                Set.of(),
                Set.of(0)
        ); IrisMetalRenderTargets output = new IrisMetalRenderTargets(
                device,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                WIDTH,
                HEIGHT
        )) {
            assertSame(source.readView(0), source.storageReadView(0),
                    "storage-image binding must use the unswizzled readable view");
            assertNotSame(source.sampleReadView(0), source.storageReadView(0),
                    "sampled RGB view must remain distinct from the storage-image view");
            assertTrue(
                    MetalPipelineSupport.sameHandle(
                            source.storageReadView(0).nativeHandle(), source.mainTexture(0).nativeHandle()
                    ),
                    "storage-image view must retain the parent texture handle"
            );

            RenderPipeline sourcePipeline = RenderPipeline.builder()
                    .withLocation("metallum_iris/iris_rgb_physical")
                    .withVertexShader("metallum_iris/fullscreen")
                    .withFragmentShader("metallum_iris/iris_rgb_physical")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            RenderPassDescriptor sourceDescriptor = RenderPassDescriptor.create(
                    () -> "logical RGB physical write"
            ).withColorAttachment(
                    source.readView(0),
                    Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F))
            ).withRenderArea(new com.mojang.blaze3d.systems.RenderPass.RenderArea(
                    0, 0, WIDTH, HEIGHT
            ));
            MetalRenderPass sourcePass = (MetalRenderPass) encoder.createRenderPass(sourceDescriptor);
            sourcePass.setPipeline(sourcePipeline);
            sourcePass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();

            BindGroupLayout sampleLayout = BindGroupLayout.builder()
                    .withSampler("SourceSampler")
                    .build();
            RenderPipeline samplePipeline = RenderPipeline.builder()
                    .withLocation("metallum_iris/iris_rgb_sample")
                    .withVertexShader("metallum_iris/fullscreen")
                    .withFragmentShader("metallum_iris/iris_rgb_sample")
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withCull(false)
                    .withBindGroupLayout(sampleLayout)
                    .withColorTargetState(0, new ColorTargetState(
                            Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            try (RenderPassDescriptorWithViews descriptor = output.createWriteDescriptor(
                    "logical RGB sample", new int[]{0}, null, false, null, null
            )) {
                MetalRenderPass samplePass = (MetalRenderPass) encoder.createRenderPass(descriptor.descriptor());
                samplePass.setPipeline(samplePipeline);
                samplePass.bindTexture("SourceSampler", source.sampleReadView(0), output.colorSampler());
                samplePass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
            }
            encoder.submit();
            device.waitForSubmittedGpuWork();

            assertRgba(source.readTexture(0), 64, 128, 191, 0, "physical RGBA backing");
            assertRgba(output.colorTargets().writeTexture(0), 64, 128, 191, 255,
                    "logical RGB sampled value");
        }
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 450
            void main() {
                vec2 positions[3] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 3.0, -1.0),
                    vec2(-1.0,  3.0)
                );
                gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
            }
            """;

    private void registerConstantFragment(final String name, final String color) {
        fragmentShaders.put(name, """
                #version 450
                layout(location=0) out vec4 fragColor;
                void main() { fragColor = %s; }
                """.formatted(color));
    }

    private void registerDepthVertex(final String name, final String z) {
        vertexShaders.put(name, """
                #version 450
                void main() {
                    vec2 positions[3] = vec2[](
                        vec2(-1.0, -1.0),
                        vec2( 3.0, -1.0),
                        vec2(-1.0,  3.0)
                    );
                    gl_Position = vec4(positions[gl_VertexIndex], %s, 1.0);
                }
                """.formatted(z));
    }

    private void runColorPass(final IrisMetalRenderTargets targets, final String fragment, final int[] drawBuffers) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_iris/" + fragment)
                .withVertexShader("metallum_iris/fullscreen")
                .withFragmentShader("metallum_iris/" + fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            builder.withColorTargetState(slot, new ColorTargetState(
                    Optional.empty(),
                    targets.colorTargets().format(drawBuffers[slot]),
                    ColorTargetState.WRITE_ALL));
        }
        RenderPipeline pipeline = builder.build();
        Vector4fc[] clears = new Vector4fc[drawBuffers.length];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            clears[slot] = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
        }
        try (RenderPassDescriptorWithViews pass = targets.createWriteDescriptor(
                "iris pass " + fragment, drawBuffers, clears, false, null, null)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runGbufferPass(
            final IrisMetalRenderTargets targets,
            final String fragment,
            final int[] drawBuffers
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_iris/" + fragment)
                .withVertexShader("metallum_iris/fullscreen")
                .withFragmentShader("metallum_iris/" + fragment)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            builder.withColorTargetState(slot, new ColorTargetState(
                    Optional.empty(),
                    targets.colorTargets().format(drawBuffers[slot]),
                    ColorTargetState.WRITE_ALL
            ));
        }
        RenderPipeline pipeline = builder.build();
        MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(targets.createTerrainWriteDescriptor(
                "iris gbuffer pass " + fragment,
                drawBuffers,
                targets.colorTargets().writeView(0),
                new Vector4f(0.0F, 0.0F, 0.0F, 1.0F),
                null,
                null
        ));
        renderPass.setPipeline(pipeline);
        renderPass.draw(3, 1, 0, 0);
        encoder.submitRenderPass();
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runDepthPass(final IrisMetalRenderTargets targets, final String vertexName, final Double clearDepth) {
        registerConstantFragment("iris_depth_fill", "vec4(1.0)");
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_iris/depth_" + vertexName)
                .withVertexShader("metallum_iris/" + vertexName)
                .withFragmentShader("metallum_iris/iris_depth_fill")
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        try (RenderPassDescriptorWithViews pass = targets.createWriteDescriptor(
                "iris depth pass " + vertexName,
                new int[]{0},
                new Vector4fc[]{new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)},
                true,
                clearDepth,
                null)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void runShadowPass(
            final IrisMetalShadowTargets shadow,
            final String vertexName,
            final String fragmentName,
            final Double clearDepth
    ) {
        RenderPipeline pipeline = RenderPipeline.builder()
                .withLocation("metallum_iris/shadow_" + vertexName)
                .withVertexShader("metallum_iris/" + vertexName)
                .withFragmentShader("metallum_iris/" + fragmentName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true))
                .withColorTargetState(0, new ColorTargetState(
                        Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .build();
        try (IrisMetalRenderTargets.RenderPassDescriptorWithViews pass = shadow.createShadowWriteDescriptor(
                "iris shadow pass " + vertexName,
                new int[]{0},
                new Vector4fc[]{new Vector4f(0.0F, 0.0F, 0.0F, 0.0F)},
                clearDepth)) {
            MetalRenderPass renderPass = (MetalRenderPass) encoder.createRenderPass(pass.descriptor());
            renderPass.setPipeline(pipeline);
            renderPass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
        }
        encoder.submit();
        device.waitForSubmittedGpuWork();
    }

    private void assertRgba(final MetalGpuTexture texture, final int red, final int green, final int blue, final String label) {
        assertRgba(texture, red, green, blue, -1, label);
    }

    private void assertRgba(
            final MetalGpuTexture texture,
            final int red,
            final int green,
            final int blue,
            final int alpha,
            final String label
    ) {
        ByteBuffer data = readback(texture);
        assertByteNear(data.get(0), red, label + " red");
        assertByteNear(data.get(1), green, label + " green");
        assertByteNear(data.get(2), blue, label + " blue");
        if (alpha >= 0) {
            assertByteNear(data.get(3), alpha, label + " alpha");
        }
    }

    private void assertDepth(final MetalGpuTexture texture, final float expected, final String label) {
        ByteBuffer data = readback(texture);
        assertEquals(expected, data.order(ByteOrder.nativeOrder()).getFloat(0), 0.001F, label);
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris targets readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    private static void assertByteNear(final byte actualByte, final int expected, final String label) {
        int actual = Byte.toUnsignedInt(actualByte);
        assertTrue(Math.abs(actual - expected) <= 2, label + ": expected " + expected + ", got " + actual);
    }
}
