package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.joml.Vector2f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS-only backend integration test. Unlike MetalMRTSmokeTest.swift, this
 * starts at Mojang's Java RenderPassDescriptor and crosses the production
 * MetalCommandEncoder, pipeline metadata, FFM arrays and indexed Swift ABI.
 */
@EnabledOnOs(OS.MAC)
final class MetalMrtBackendIntegrationTest {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 4;
    private static final int TEXTURE_USAGE =
            com.mojang.blaze3d.textures.GpuTexture.USAGE_RENDER_ATTACHMENT
                    | com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_SRC;

    private static final String VERTEX_SHADER = """
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

    private final Map<String, String> fragmentShaders = new HashMap<>();
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource source = (identifier, type) -> type == ShaderType.VERTEX
                ? VERTEX_SHADER
                : fragmentShaders.get(identifier.getPath().substring(identifier.getPath().lastIndexOf('/') + 1));
        device = new MetalDevice(
                source,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Metal MRT integration device",
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
    void oneAndTwoAttachmentReadback() {
        runRgbaAttachmentCount(1);
        runRgbaAttachmentCount(2);
    }

    @Test
    void mixedThreeAttachmentReadback() {
        runMixedThreeAttachments();
    }

    @Test
    void oldInFlightFailureInvalidatesTheNextFinalizedMotionFrameWithoutThrowing() {
        MetalGpuTexture depth = createContractTexture("race-depth", GpuFormat.D32_FLOAT, 8, 8, 0);
        MetalGpuTexture motion = createContractTexture("race-motion", GpuFormat.RG16_FLOAT, 8, 8, 0);
        MetalGpuTexture reactive = createContractTexture("race-reactive", GpuFormat.R8_UNORM, 8, 8, 0);
        try {
            MotionVectorPipeline pipeline = new MotionVectorPipeline();
            FrameStamp oldStamp = pipeline.beginFrame();
            recordCompleteTransactionReceipts(pipeline.reactiveMasks());
            FinalizedMotionFrame oldFrame = pipeline.finalizeFrame(
                    depth, motion, reactive, 8, 8, new Vector2f(), true
            );
            assertTrue(pipeline.acceptSubmittedFrame(oldFrame));
            assertTrue(pipeline.wasSubmittedFrameAccepted(oldStamp));

            FrameStamp nextStamp = pipeline.beginFrame();
            recordCompleteTransactionReceipts(pipeline.reactiveMasks());
            FinalizedMotionFrame nextFrame = pipeline.finalizeFrame(
                    depth, motion, reactive, 8, 8, new Vector2f(0.25F, -0.25F), false
            );
            assertTrue(pipeline.isCurrentFinalized(nextFrame));

            assertEquals(
                    nextStamp,
                    pipeline.uncommittedSuccessorForRejectedFrame(oldStamp),
                    "the current encoded consumer must be aborted before the old epoch is rejected"
            );
            assertTrue(pipeline.rejectSubmittedFrame(oldStamp));
            assertFalse(pipeline.wasSubmittedFrameAccepted(oldStamp));
            assertFalse(pipeline.wasSubmittedFrameAccepted(nextStamp));
            assertFalse(pipeline.isCurrentFinalized(nextFrame));
            assertFalse(pipeline.acceptSubmittedFrame(nextFrame));
            assertEquals(nextStamp.historyEpoch() + 1L, pipeline.currentStamp().historyEpoch());
            assertNull(
                    pipeline.uncommittedSuccessorForRejectedFrame(oldStamp),
                    "the old stamp must not target a successor after the epoch has advanced"
            );
            pipeline.discardFrame(pipeline.currentStamp());
        } finally {
            reactive.close();
            motion.close();
            depth.close();
        }
    }

    @Test
    void firstFrameTimingSkipsOnlyThatGeneratedPairAndExactRolesRecoverNextFrame() {
        MetalGpuTexture source = createContractTexture(
                "contract-source", GpuFormat.RGBA8_UNORM, 8, 8,
                MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW
        );
        MetalGpuTexture nativeScene = createContractTexture(
                "contract-native", GpuFormat.RGBA8_UNORM, 16, 16,
                MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW
        );
        MetalGpuTexture frameGenerationScene = createContractTexture(
                "contract-framegen", GpuFormat.RGBA8_UNORM, 12, 12,
                MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW
        );
        MetalGpuTexture ui = createContractTexture(
                "contract-ui", GpuFormat.RGBA8_UNORM, 16, 16,
                MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW
        );
        MetalGpuTexture depth = createContractTexture("contract-depth", GpuFormat.D32_FLOAT, 8, 8, 0);
        MetalGpuTexture motion = createContractTexture("contract-motion", GpuFormat.RG16_FLOAT, 8, 8, 0);
        MetalGpuTexture reactive = createContractTexture("contract-reactive", GpuFormat.R8_UNORM, 8, 8, 0);
        MetalGpuTextureView sourceView = device.createTextureView(source, MTLPixelFormat.RGBA8Unorm_sRGB);
        MetalGpuTextureView nativeView = device.createTextureView(nativeScene, MTLPixelFormat.RGBA8Unorm_sRGB);
        MetalGpuTextureView frameGenerationView = device.createTextureView(
                frameGenerationScene, MTLPixelFormat.RGBA8Unorm_sRGB
        );
        MetalGpuTextureView uiView = device.createTextureView(ui, MTLPixelFormat.RGBA8Unorm_sRGB);
        try {
            ColorTextureRole sourceRole = new ColorTextureRole(source, sourceView);
            ColorTextureRole nativeRole = new ColorTextureRole(nativeScene, nativeView);
            ColorTextureRole frameGenerationRole = new ColorTextureRole(
                    frameGenerationScene, frameGenerationView
            );
            ColorTextureRole uiRole = new ColorTextureRole(ui, uiView);
            SceneColorInput sourceInput = SceneColorInput.vanillaSrgb(sourceRole);

            FrameStamp firstStamp = new FrameStamp(1L, 1L);
            FinalizedMotionFrame firstMotion = finalizedContractMotion(
                    firstStamp, depth, motion, reactive, new Vector2f()
            );
            FrameSynthesisInputs first = new FrameSynthesisInputs(
                    firstStamp,
                    sourceInput,
                    firstMotion,
                    nativeRole,
                    frameGenerationRole,
                    uiRole,
                    new CameraFrameInput(70.0F, 0.05F, 1000.0F, 1.0F, 0.0F),
                    true
            );
            assertTrue(first.canEncodeTemporal());
            assertFalse(first.canGenerateFrames(), "an unseeded source delta skips only the first pair");

            FrameStamp nextStamp = new FrameStamp(2L, 1L);
            FinalizedMotionFrame nextMotion = finalizedContractMotion(
                    nextStamp, depth, motion, reactive, new Vector2f(0.25F, -0.25F)
            );
            FrameSynthesisInputs next = new FrameSynthesisInputs(
                    nextStamp,
                    sourceInput,
                    nextMotion,
                    nativeRole,
                    frameGenerationRole,
                    uiRole,
                    new CameraFrameInput(70.0F, 0.05F, 1000.0F, 1.0F, 1.0F / 60.0F),
                    false
            );
            assertTrue(next.canGenerateFrames());
            assertSame(frameGenerationRole, next.frameGenerationColor());
            assertSame(nativeRole, next.sceneOutput());

            MetalFxManager.FrameGenerationFallback fallback =
                    new MetalFxManager.FrameGenerationFallback(nativeScene, ui);
            MetalFxManager.FrameGenerationPresentationPlan lateRejected =
                    new MetalFxManager.FrameGenerationPresentationPlan(fallback, null);
            assertSame(nativeScene, lateRejected.fallback().sceneColor());
            assertSame(ui, lateRejected.fallback().uiColor());
            assertNull(lateRejected.frameGenerationInput(),
                    "late Frame Generation rejection must retain the real-frame fallback");

            MetalFxManager.FrameGenerationInput admittedInput = new MetalFxManager.FrameGenerationInput(
                    frameGenerationRole,
                    nativeRole,
                    uiRole,
                    nextMotion,
                    next.camera(),
                    8,
                    8,
                    nextStamp,
                    17L
            );
            MetalFxManager.FrameGenerationPresentationPlan admitted =
                    new MetalFxManager.FrameGenerationPresentationPlan(fallback, admittedInput);
            assertSame(admittedInput, admitted.frameGenerationInput());
            assertThrows(IllegalArgumentException.class, () ->
                    new MetalFxManager.FrameGenerationPresentationPlan(
                            new MetalFxManager.FrameGenerationFallback(ui, nativeScene),
                            admittedInput
                    ), "Frame Generation and fallback must use the exact same scene/UI bases");
        } finally {
            uiView.close();
            frameGenerationView.close();
            nativeView.close();
            sourceView.close();
            reactive.close();
            motion.close();
            depth.close();
            ui.close();
            frameGenerationScene.close();
            nativeScene.close();
            source.close();
        }
    }

    @Test
    void frameGenerationFallbackRejectsAliasedMismatchedOrClosedTextures() {
        MetalGpuTexture scene = createContractTexture("fallback-scene", GpuFormat.RGBA8_UNORM, 16, 16, 0);
        MetalGpuTexture ui = createContractTexture("fallback-ui", GpuFormat.RGBA8_UNORM, 16, 16, 0);
        MetalGpuTexture wrongExtent = createContractTexture(
                "fallback-wrong-extent", GpuFormat.RGBA8_UNORM, 8, 16, 0
        );
        MetalGpuTexture wrongFormat = createContractTexture(
                "fallback-wrong-format", GpuFormat.R8_UNORM, 16, 16, 0
        );
        MetalGpuTexture closed = createContractTexture("fallback-closed", GpuFormat.RGBA8_UNORM, 16, 16, 0);
        closed.close();
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    new MetalFxManager.FrameGenerationFallback(scene, scene));
            assertThrows(IllegalArgumentException.class, () ->
                    new MetalFxManager.FrameGenerationFallback(scene, wrongExtent));
            assertThrows(IllegalArgumentException.class, () ->
                    new MetalFxManager.FrameGenerationFallback(scene, wrongFormat));
            assertThrows(IllegalArgumentException.class, () ->
                    new MetalFxManager.FrameGenerationFallback(scene, closed));
            assertDoesNotThrow(() -> new MetalFxManager.FrameGenerationFallback(scene, ui));
        } finally {
            wrongFormat.close();
            wrongExtent.close();
            ui.close();
            scene.close();
        }
    }

    @Test
    void finalizedMotionRejectsWrongFormatsDimensionsAndNonFiniteJitter() {
        MetalGpuTexture depth = createContractTexture("invalid-depth", GpuFormat.D32_FLOAT, 8, 8, 0);
        MetalGpuTexture wrongMotion = createContractTexture("invalid-motion", GpuFormat.R8_UNORM, 8, 8, 0);
        MetalGpuTexture motion = createContractTexture("valid-motion", GpuFormat.RG16_FLOAT, 8, 8, 0);
        MetalGpuTexture reactive = createContractTexture("valid-reactive", GpuFormat.R8_UNORM, 8, 8, 0);
        MetalGpuTexture layeredReactive = (MetalGpuTexture) device.createTexture(
                "layered-reactive", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.R8_UNORM,
                8, 8, 2, 1
        );
        MetalGpuTexture mippedMotion = (MetalGpuTexture) device.createTexture(
                "mipped-motion", GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RG16_FLOAT,
                8, 8, 1, 2
        );
        try {
            assertThrows(IllegalArgumentException.class, () -> new FinalizedMotionFrame(
                    new FrameStamp(1L, 1L), depth, wrongMotion, reactive, 8, 8,
                    new Vector2f(), MetalMotionContract.motionVectorScale(8, 8),
                    MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                    DepthConvention.REVERSED_Z, true, realProducerReceipts()
            ));
            assertThrows(IllegalArgumentException.class, () -> new FinalizedMotionFrame(
                    new FrameStamp(1L, 1L), depth, motion, reactive, 4, 8,
                    new Vector2f(), MetalMotionContract.motionVectorScale(4, 8),
                    MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                    DepthConvention.REVERSED_Z, true, realProducerReceipts()
            ));
            assertThrows(IllegalArgumentException.class, () -> new FinalizedMotionFrame(
                    new FrameStamp(1L, 1L), depth, motion, reactive, 8, 8,
                    new Vector2f(Float.NaN, 0.0F), MetalMotionContract.motionVectorScale(8, 8),
                    MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                    DepthConvention.REVERSED_Z, true, realProducerReceipts()
            ));
            assertThrows(IllegalArgumentException.class, () -> new FinalizedMotionFrame(
                    new FrameStamp(1L, 1L), depth, motion, layeredReactive, 8, 8,
                    new Vector2f(), MetalMotionContract.motionVectorScale(8, 8),
                    MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                    DepthConvention.REVERSED_Z, true, realProducerReceipts()
            ));
            assertThrows(IllegalArgumentException.class, () -> new FinalizedMotionFrame(
                    new FrameStamp(1L, 1L), depth, mippedMotion, reactive, 8, 8,
                    new Vector2f(), MetalMotionContract.motionVectorScale(8, 8),
                    MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                    DepthConvention.REVERSED_Z, true, realProducerReceipts()
            ));
        } finally {
            mippedMotion.close();
            layeredReactive.close();
            reactive.close();
            motion.close();
            wrongMotion.close();
            depth.close();
        }
    }

    @Test
    void exposureContractAcceptsAutoAndOneByOneR16fButRejectsInvalidManualTextures() {
        MetalGpuTexture manual = createContractTexture("manual-exposure", GpuFormat.R16_FLOAT, 1, 1, 0);
        MetalGpuTexture wrongFormat = createContractTexture("manual-wrong-format", GpuFormat.R8_UNORM, 1, 1, 0);
        MetalGpuTexture wrongExtent = createContractTexture("manual-wrong-extent", GpuFormat.R16_FLOAT, 2, 1, 0);
        MetalGpuTexture layered = (MetalGpuTexture) device.createTexture(
                "manual-layered",
                GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.R16_FLOAT,
                1,
                1,
                2,
                1
        );
        MetalGpuTexture closed = createContractTexture("manual-closed", GpuFormat.R16_FLOAT, 1, 1, 0);
        closed.close();
        try {
            assertEquals(ExposureInput.Mode.AUTO, ExposureInput.AUTO.mode());
            assertNull(ExposureInput.AUTO.texture());
            assertTrue(Float.isNaN(ExposureInput.AUTO.preExposure()));

            ExposureInput valid = ExposureInput.manualR16f(manual, 2.0F);
            assertEquals(ExposureInput.Mode.MANUAL_R16F, valid.mode());
            assertSame(manual, valid.texture());
            assertEquals(2.0F, valid.preExposure());

            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(wrongFormat, 1.0F));
            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(wrongExtent, 1.0F));
            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(layered, 1.0F));
            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(closed, 1.0F));
            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(manual, Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> ExposureInput.manualR16f(manual, 0.0F));
        } finally {
            layered.close();
            wrongExtent.close();
            wrongFormat.close();
            manual.close();
        }
    }

    @Test
    void producerReceiptsAreCompleteUniqueAndGateTemporalSeparatelyFromFrameGeneration() {
        MetalGpuTexture depth = createContractTexture("receipt-depth", GpuFormat.D32_FLOAT, 8, 8, 0);
        MetalGpuTexture motion = createContractTexture("receipt-motion", GpuFormat.RG16_FLOAT, 8, 8, 0);
        MetalGpuTexture reactive = createContractTexture("receipt-reactive", GpuFormat.R8_UNORM, 8, 8, 0);
        try {
            List<ProducerReceipt> missing = new ArrayList<>(realProducerReceipts());
            missing.removeLast();
            assertThrows(IllegalArgumentException.class, () -> finalizedContractMotion(
                    new FrameStamp(1L, 1L), depth, motion, reactive, new Vector2f(), missing
            ));

            List<ProducerReceipt> duplicate = new ArrayList<>(realProducerReceipts());
            duplicate.add(duplicate.getFirst());
            assertThrows(IllegalArgumentException.class, () -> finalizedContractMotion(
                    new FrameStamp(1L, 1L), depth, motion, reactive, new Vector2f(), duplicate
            ));

            List<ProducerReceipt> reactiveOnly = receiptsWithCoverage(
                    ProducerDomain.TRANSPARENCY, ProducerCoverage.REACTIVE_ONLY
            );
            FinalizedMotionFrame temporalOnly = finalizedContractMotion(
                    new FrameStamp(1L, 1L), depth, motion, reactive, new Vector2f(), reactiveOnly
            );
            assertTrue(temporalOnly.temporalEligible());
            assertTrue(temporalOnly.frameGenerationEligible(),
                    "reactive fallback domains must not make opt-in Frame Generation unreachable");

            List<ProducerReceipt> unsupported = receiptsWithCoverage(
                    ProducerDomain.DYNAMIC_CONTENT, ProducerCoverage.UNSUPPORTED
            );
            FinalizedMotionFrame rejected = finalizedContractMotion(
                    new FrameStamp(1L, 1L), depth, motion, reactive, new Vector2f(), unsupported
            );
            assertFalse(rejected.temporalEligible());
            assertFalse(rejected.frameGenerationEligible());
        } finally {
            reactive.close();
            motion.close();
            depth.close();
        }
    }

    @Test
    void nullMiddleSlotPreservesFragmentLocation() {
        runNullMiddleSlot();
    }

    @Test
    void eightAttachmentSignatureAndReadback() {
        runEightAttachmentSignature();
    }

    @Test
    void perSlotClearLoadStoreBlendAndWriteMask() {
        runPerSlotClearLoadStoreBlendAndWriteMask();
    }

    @Test
    void legacySingleAttachmentAbiStillWorks() {
        verifyLegacySingleAttachmentAbi();
    }

    @Test
    void pipelineRenderPassSignatureMismatchFailsClosed() {
        verifyPipelineRenderPassSignatureMismatch();
    }

    @Test
    void fragmentOutputLocationMismatchFailsClosed() {
        verifyFragmentOutputLocationMismatchFailsClosed();
    }

    @Test
    void fragmentOutputFormatMismatchFailsClosed() {
        verifyFragmentOutputFormatMismatchFailsClosed();
    }

    @Test
    void submitCallbacksTrackFiveSuccessfulInFlightBuffers() {
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (int index = 0; index < 5; index++) {
            encoder.commandBuffer();
            encoder.onCurrentSubmit(committed::incrementAndGet, failed::incrementAndGet);
            encoder.submit();
        }
        device.waitForSubmittedGpuWork();
        assertEquals(5, committed.get(), "every encoded transaction must observe a real command-buffer commit");
        assertEquals(0, failed.get(), "successful Metal command buffers must not poison frame history");
    }

    @Test
    void wholeResourceSrgbViewOwnsAHandleAndKeepsItsBaseAlive() {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "sRGB view lifetime",
                GpuTexture.USAGE_TEXTURE_BINDING | MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        MetalGpuTextureView view = device.createTextureView(texture, MTLPixelFormat.RGBA8Unorm_sRGB);

        assertEquals(MTLPixelFormat.RGBA8Unorm_sRGB, view.mtlPixelFormat());
        MemorySegment baseHandle = texture.nativeHandle();
        MemorySegment viewHandle = view.nativeHandle();
        assertNotEquals(baseHandle.address(), viewHandle.address(),
                "a whole-resource format reinterpretation must not leak the base texture handle");

        texture.close();
        assertEquals(viewHandle.address(), view.nativeHandle().address(),
                "closing the base wrapper must not invalidate a live view");
        view.close();
        assertThrows(IllegalStateException.class, view::nativeHandle);
    }

    @Test
    void srgbViewCreationPreservesUnderlyingRgbaBytes() {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "sRGB byte preservation",
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
                        | MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        ByteBuffer source = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            source.put((byte) 0x1d);
            source.put((byte) 0x80);
            source.put((byte) 0xe7);
            source.put((byte) 0xff);
        }
        source.flip();

        encoder.writeToTexture(texture, source, 0, 0, 0, 0, WIDTH, HEIGHT);
        encoder.submit();
        device.waitForSubmittedGpuWork();
        try (MetalGpuTextureView view = device.createTextureView(texture, MTLPixelFormat.RGBA8Unorm_sRGB)) {
            assertNotEquals(texture.nativeHandle().address(), view.nativeHandle().address());
            ByteBuffer readback = readback(texture);
            for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                int offset = pixel * 4;
                assertEquals(0x1d, Byte.toUnsignedInt(readback.get(offset)));
                assertEquals(0x80, Byte.toUnsignedInt(readback.get(offset + 1)));
                assertEquals(0xe7, Byte.toUnsignedInt(readback.get(offset + 2)));
                assertEquals(0xff, Byte.toUnsignedInt(readback.get(offset + 3)));
            }
        } finally {
            texture.close();
        }
    }

    @Test
    void plainWholeResourceViewStillUsesTheBaseHandle() {
        MetalGpuTexture texture = (MetalGpuTexture) device.createTexture(
                "plain view",
                GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        try (MetalGpuTextureView view = device.createTextureView(texture, MTLPixelFormat.RGBA8Unorm)) {
            assertEquals(texture.nativeHandle().address(), view.nativeHandle().address());
            assertEquals(MTLPixelFormat.RGBA8Unorm, view.mtlPixelFormat());
        } finally {
            texture.close();
        }
    }

    @Test
    void formatViewCreationRejectsMissingUsageIncompatibleFormatsAndClosedBases() {
        MetalGpuTexture withoutViewUsage = (MetalGpuTexture) device.createTexture(
                "no format view usage",
                GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    device.createTextureView(withoutViewUsage, MTLPixelFormat.RGBA8Unorm_sRGB));
        } finally {
            withoutViewUsage.close();
        }

        MetalGpuTexture incompatible = (MetalGpuTexture) device.createTexture(
                "incompatible format view",
                GpuTexture.USAGE_TEXTURE_BINDING | MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    device.createTextureView(incompatible, MTLPixelFormat.BGRA8Unorm_sRGB));
        } finally {
            incompatible.close();
        }

        MetalGpuTexture closed = (MetalGpuTexture) device.createTexture(
                "closed format view base",
                GpuTexture.USAGE_TEXTURE_BINDING | MetalGpuTexture.USAGE_PIXEL_FORMAT_VIEW,
                GpuFormat.RGBA8_UNORM,
                WIDTH,
                HEIGHT,
                1,
                1
        );
        closed.close();
        assertThrows(IllegalArgumentException.class, () ->
                device.createTextureView(closed, MTLPixelFormat.RGBA8Unorm_sRGB));
    }

    private void runRgbaAttachmentCount(final int count) {
        String shaderName = "mrt_rgba_" + count;
        StringBuilder fragment = new StringBuilder("#version 450\n");
        for (int index = 0; index < count; index++) {
            fragment.append("layout(location=").append(index).append(") out vec4 out")
                    .append(index).append(";\n");
        }
        fragment.append("void main() {\n");
        for (int index = 0; index < count; index++) {
            float red = 0.125F * (index + 1);
            fragment.append("out").append(index).append(" = vec4(")
                    .append(red).append(", 0.25, 0.5, 1.0);\n");
        }
        fragment.append("}\n");
        fragmentShaders.put(shaderName, fragment.toString());

        List<GpuFormat> formats = new ArrayList<>();
        for (int index = 0; index < count; index++) formats.add(GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "rgba-" + count);
        render(pipeline, textures, null);

        for (int index = 0; index < count; index++) {
            ByteBuffer data = readback(textures.get(index));
            assertByteNear(data.get(0), Math.round(255.0F * 0.125F * (index + 1)), "RGBA red " + index);
            assertByteNear(data.get(1), 64, "RGBA green " + index);
            assertByteNear(data.get(2), 128, "RGBA blue " + index);
            assertByteNear(data.get(3), 255, "RGBA alpha " + index);
        }
        closeTextures(textures);
    }

    private void runMixedThreeAttachments() {
        String shaderName = "mrt_mixed_three";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=1) out vec2 motion;
                layout(location=2) out float validity;
                void main() {
                    color = vec4(0.25, 0.5, 0.75, 1.0);
                    motion = vec2(-0.25, 0.5);
                    validity = 0.75;
                }
                """);
        List<GpuFormat> formats = List.of(
                GpuFormat.RGBA8_UNORM,
                GpuFormat.RG16_FLOAT,
                GpuFormat.R8_UNORM
        );
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "mixed");
        render(pipeline, textures, List.of(
                new Vector4f(0.1F, 0.2F, 0.3F, 1.0F),
                new Vector4f(0.1F, -0.2F, 0.0F, 1.0F),
                new Vector4f(0.1F, 0.0F, 0.0F, 1.0F)
        ));

        ByteBuffer color = readback(textures.get(0));
        assertByteNear(color.get(0), 64, "mixed color red");
        assertByteNear(color.get(1), 128, "mixed color green");
        assertByteNear(color.get(2), 191, "mixed color blue");
        ByteBuffer motion = readback(textures.get(1)).order(ByteOrder.nativeOrder());
        assertEquals(-0.25F, Float.float16ToFloat(motion.getShort(0)), 0.01F);
        assertEquals(0.5F, Float.float16ToFloat(motion.getShort(2)), 0.01F);
        assertByteNear(readback(textures.get(2)).get(0), 191, "mixed validity");
        closeTextures(textures);
    }

    private void runNullMiddleSlot() {
        String shaderName = "mrt_null_middle";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=2) out float validity;
                void main() {
                    color = vec4(0.75, 0.25, 0.5, 1.0);
                    validity = 0.25;
                }
                """);
        List<GpuFormat> formats = new ArrayList<>();
        formats.add(GpuFormat.RGBA8_UNORM);
        formats.add(null);
        formats.add(GpuFormat.R8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "null-middle");
        render(pipeline, textures, null);
        ByteBuffer color = readback(textures.get(0));
        assertByteNear(color.get(0), 191, "null slot color red");
        assertByteNear(color.get(1), 64, "null slot color green");
        assertByteNear(color.get(2), 128, "null slot color blue");
        assertByteNear(readback(textures.get(2)).get(0), 64, "null slot validity");
        closeTextures(textures);
    }

    private void runEightAttachmentSignature() {
        String shaderName = "mrt_eight";
        StringBuilder fragment = new StringBuilder("#version 450\n");
        for (int index = 0; index < 8; index++) {
            fragment.append("layout(location=").append(index).append(") out vec4 out")
                    .append(index).append(";\n");
        }
        fragment.append("void main() {\n");
        for (int index = 0; index < 8; index++) {
            fragment.append("out").append(index).append(" = vec4(")
                    .append((index + 1) / 16.0F).append(", 0.0, 0.0, 1.0);\n");
        }
        fragment.append("}\n");
        fragmentShaders.put(shaderName, fragment.toString());
        List<GpuFormat> formats = java.util.Collections.nCopies(8, GpuFormat.RGBA8_UNORM);
        RenderPipeline pipeline = pipeline(shaderName, formats, null, ColorTargetState.WRITE_ALL);
        List<MetalGpuTexture> textures = createTextures(formats, "eight");
        render(pipeline, textures, null);
        assertByteNear(readback(textures.get(7)).get(0), 128, "eighth attachment");
        closeTextures(textures);
    }

    private void runPerSlotClearLoadStoreBlendAndWriteMask() {
        String clearShaderName = "mrt_per_slot_clear";
        fragmentShaders.put(clearShaderName, """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() {
                    first = vec4(1.0);
                    second = vec4(1.0);
                }
                """);
        List<GpuFormat> formats = List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM);
        RenderPipeline clearPipeline = pipeline(
                clearShaderName,
                List.of(
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE),
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE)
                )
        );
        List<MetalGpuTexture> textures = createTextures(formats, "per-slot-state");
        render(clearPipeline, textures, List.of(
                new Vector4f(0.1F, 0.2F, 0.3F, 1.0F),
                new Vector4f(0.4F, 0.5F, 0.6F, 1.0F)
        ));

        ByteBuffer clear0 = readback(textures.get(0));
        assertByteNear(clear0.get(0), 26, "slot 0 clear/store red");
        assertByteNear(clear0.get(1), 51, "slot 0 clear/store green");
        ByteBuffer clear1 = readback(textures.get(1));
        assertByteNear(clear1.get(0), 102, "slot 1 clear/store red");
        assertByteNear(clear1.get(1), 128, "slot 1 clear/store green");

        String shaderName = "mrt_per_slot_blend_mask";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 first;
                layout(location=1) out vec4 second;
                void main() {
                    first = vec4(0.25, 0.5, 0.75, 0.5);
                    second = vec4(0.9, 0.25, 0.1, 0.2);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(
                        new ColorTargetState(
                                Optional.of(BlendFunction.ADDITIVE),
                                GpuFormat.RGBA8_UNORM,
                                ColorTargetState.WRITE_RED
                        ),
                        new ColorTargetState(
                                Optional.empty(),
                                GpuFormat.RGBA8_UNORM,
                                ColorTargetState.WRITE_GREEN
                        )
                )
        );
        renderLoad(pipeline, textures);
        ByteBuffer first = readback(textures.getFirst());
        assertByteNear(first.get(0), 89, "slot 0 additive red");
        assertByteNear(first.get(1), 51, "slot 0 masked green preserved load");
        assertByteNear(first.get(2), 77, "slot 0 masked blue preserved load");
        assertByteNear(first.get(3), 255, "slot 0 masked alpha preserved load");
        ByteBuffer second = readback(textures.get(1));
        assertByteNear(second.get(0), 102, "slot 1 masked red preserved load");
        assertByteNear(second.get(1), 64, "slot 1 green write");
        assertByteNear(second.get(2), 153, "slot 1 masked blue preserved load");
        assertByteNear(second.get(3), 255, "slot 1 masked alpha preserved load");
        closeTextures(textures);
    }

    private void verifyLegacySingleAttachmentAbi() {
        List<MetalGpuTexture> textures = createTextures(
                List.of(GpuFormat.RGBA8_UNORM), "legacy-single-attachment"
        );
        MetalGpuTexture texture = textures.getFirst();
        MTLRenderCommandEncoder legacyEncoder = encoder.commandBuffer().makeRenderCommandEncoder(
                texture.nativeHandle(),
                MemorySegment.NULL,
                WIDTH,
                HEIGHT,
                1,
                0.2F,
                0.4F,
                0.6F,
                1.0F,
                0,
                1.0
        );
        legacyEncoder.endEncoding();
        encoder.submit();
        device.waitForSubmittedGpuWork();
        ByteBuffer data = readback(texture);
        assertByteNear(data.get(0), 51, "legacy ABI red");
        assertByteNear(data.get(1), 102, "legacy ABI green");
        assertByteNear(data.get(2), 153, "legacy ABI blue");
        assertByteNear(data.get(3), 255, "legacy ABI alpha");
        closeTextures(textures);
    }

    private void verifyPipelineRenderPassSignatureMismatch() {
        String shaderName = "mrt_signature_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out vec4 color;
                layout(location=1) out vec4 extra;
                void main() {
                    color = vec4(1.0);
                    extra = vec4(0.0);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM, GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        List<MetalGpuTexture> textures = createTextures(List.of(GpuFormat.RGBA8_UNORM), "signature-mismatch");
        try (PassWithViews pass = createPass(textures, null, false)) {
            IllegalArgumentException mismatch = assertThrows(
                    IllegalArgumentException.class,
                    () -> pass.pass().setPipeline(pipeline)
            );
            assertTrue(mismatch.getMessage().contains("signature mismatch"));
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
        closeTextures(textures);
    }

    private void verifyFragmentOutputLocationMismatchFailsClosed() {
        String shaderName = "mrt_fragment_location_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=1) out vec4 wrongLocation;
                void main() {
                    wrongLocation = vec4(1.0);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class,
                () -> device.getOrCompilePipeline(pipeline)
        );
        assertTrue(mismatch.getMessage().contains("Failed to compile Metal cross shader"));
        assertNotNull(mismatch.getCause());
        assertTrue(mismatch.getCause().getMessage().contains("location mismatch"));
    }

    private void verifyFragmentOutputFormatMismatchFailsClosed() {
        String shaderName = "mrt_fragment_format_mismatch";
        fragmentShaders.put(shaderName, """
                #version 450
                layout(location=0) out uvec4 integerColor;
                void main() {
                    integerColor = uvec4(1u, 2u, 3u, 4u);
                }
                """);
        RenderPipeline pipeline = pipeline(
                shaderName,
                List.of(GpuFormat.RGBA8_UNORM),
                null,
                ColorTargetState.WRITE_ALL
        );
        MetalCompiledRenderPipeline compiled = device.getOrCompilePipeline(pipeline);
        assertFalse(compiled.isValid(), "integer output with normalized float target must not create a valid PSO");
    }

    private RenderPipeline pipeline(
            String shaderName,
            List<GpuFormat> formats,
            BlendFunction blend,
            int writeMask
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/mrt_vertex")
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int index = 0; index < formats.size(); index++) {
            GpuFormat format = formats.get(index);
            if (format == null) {
                builder.withUnusedColorTargetState(index);
            } else {
                builder.withColorTargetState(
                        index,
                        new ColorTargetState(Optional.ofNullable(blend), format, writeMask)
                );
            }
        }
        return builder.build();
    }

    private RenderPipeline pipeline(
            String shaderName,
            List<ColorTargetState> targets
    ) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation("metallum_test/" + shaderName)
                .withVertexShader("metallum_test/mrt_vertex")
                .withFragmentShader("metallum_test/" + shaderName)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false);
        for (int index = 0; index < targets.size(); index++) {
            builder.withColorTargetState(index, targets.get(index));
        }
        return builder.build();
    }

    private List<MetalGpuTexture> createTextures(List<GpuFormat> formats, String labelPrefix) {
        List<MetalGpuTexture> result = new ArrayList<>(formats.size());
        for (int index = 0; index < formats.size(); index++) {
            GpuFormat format = formats.get(index);
            result.add(format == null ? null : (MetalGpuTexture) device.createTexture(
                    labelPrefix + "-" + index,
                    TEXTURE_USAGE,
                    format,
                    WIDTH,
                    HEIGHT,
                    1,
                    1
            ));
        }
        return result;
    }

    private MetalGpuTexture createContractTexture(
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int extraUsage
    ) {
        return (MetalGpuTexture) device.createTexture(
                label,
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | extraUsage,
                format,
                width,
                height,
                1,
                1
        );
    }

    private static void recordCompleteTransactionReceipts(final ReactiveMaskPipeline receipts) {
        receipts.recordCameraDepth();
        receipts.recordUnsupported(ProducerDomain.DYNAMIC_CONTENT, 0);
        receipts.recordFirstPerson(false);
        receipts.recordTransparency(true);
        receipts.recordParticlesWeather(true);
        receipts.recordModdedRenderers(false);
    }

    private static FinalizedMotionFrame finalizedContractMotion(
            final FrameStamp stamp,
            final MetalGpuTexture depth,
            final MetalGpuTexture motion,
            final MetalGpuTexture reactive,
            final Vector2f jitter
    ) {
        return finalizedContractMotion(stamp, depth, motion, reactive, jitter, realProducerReceipts());
    }

    private static FinalizedMotionFrame finalizedContractMotion(
            final FrameStamp stamp,
            final MetalGpuTexture depth,
            final MetalGpuTexture motion,
            final MetalGpuTexture reactive,
            final Vector2f jitter,
            final List<ProducerReceipt> receipts
    ) {
        return new FinalizedMotionFrame(
                stamp,
                depth,
                motion,
                reactive,
                8,
                8,
                jitter,
                MetalMotionContract.motionVectorScale(8, 8),
                MotionConvention.PREVIOUS_MINUS_CURRENT_NDC_TOP_LEFT,
                DepthConvention.REVERSED_Z,
                stamp.frameId() == 1L,
                receipts
        );
    }

    private static List<ProducerReceipt> realProducerReceipts() {
        List<ProducerReceipt> receipts = new ArrayList<>(ProducerDomain.values().length);
        for (ProducerDomain domain : ProducerDomain.values()) {
            receipts.add(new ProducerReceipt(domain, ProducerCoverage.REAL_MOTION, 0));
        }
        return List.copyOf(receipts);
    }

    private static List<ProducerReceipt> receiptsWithCoverage(
            final ProducerDomain selectedDomain,
            final ProducerCoverage selectedCoverage
    ) {
        List<ProducerReceipt> receipts = new ArrayList<>(ProducerDomain.values().length);
        for (ProducerDomain domain : ProducerDomain.values()) {
            receipts.add(new ProducerReceipt(
                    domain,
                    domain == selectedDomain ? selectedCoverage : ProducerCoverage.REAL_MOTION,
                    0
            ));
        }
        return List.copyOf(receipts);
    }

    private void render(
            RenderPipeline pipeline,
            List<MetalGpuTexture> textures,
            List<Vector4f> clearColors
    ) {
        try (PassWithViews pass = createPass(textures, clearColors, false)) {
            pass.pass().setPipeline(pipeline);
            pass.pass().draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
    }

    private void renderLoad(
            RenderPipeline pipeline,
            List<MetalGpuTexture> textures
    ) {
        try (PassWithViews pass = createPass(textures, null, true)) {
            pass.pass().setPipeline(pipeline);
            pass.pass().draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
        }
    }

    private PassWithViews createPass(
            List<MetalGpuTexture> textures,
            List<Vector4f> clearColors,
            boolean load
    ) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Java MRT backend integration");
        List<MetalGpuTextureView> views = new ArrayList<>();
        for (int index = 0; index < textures.size(); index++) {
            MetalGpuTexture texture = textures.get(index);
            if (texture == null) {
                descriptor.withUnusedColorAttachment();
            } else {
                MetalGpuTextureView view = new MetalGpuTextureView(texture, 0, 1);
                views.add(view);
                Optional<Vector4fc> clear = load
                        ? Optional.empty()
                        : Optional.of(clearColors == null ? new Vector4f(0.0F) : clearColors.get(index));
                descriptor.withColorAttachment(
                        view,
                        clear
                );
            }
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, WIDTH, HEIGHT));
        return new PassWithViews((MetalRenderPass) encoder.createRenderPass(descriptor), views);
    }

    private ByteBuffer readback(MetalGpuTexture texture) {
        int size = WIDTH * HEIGHT * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "MRT readback",
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

    private record PassWithViews(
            MetalRenderPass pass,
            List<MetalGpuTextureView> views
    ) implements AutoCloseable {
        @Override
        public void close() {
            for (MetalGpuTextureView view : views) {
                view.close();
            }
        }
    }

    private static void closeTextures(List<MetalGpuTexture> textures) {
        for (MetalGpuTexture texture : textures) {
            if (texture != null) texture.close();
        }
    }

    private static void assertByteNear(byte actualByte, int expected, String label) {
        int actual = Byte.toUnsignedInt(actualByte);
        assertTrue(Math.abs(actual - expected) <= 1, label + ": expected " + expected + ", got " + actual);
    }
}
