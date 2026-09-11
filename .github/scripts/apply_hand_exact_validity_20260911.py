from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact anchor, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex anchor, found {count}")
    return text


# ---------------------------------------------------------------------------
# Java resource ownership + replay routing.
# ---------------------------------------------------------------------------
manager_path = "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"
manager = read(manager_path)

manager = replace_once(
    manager,
    """    @Nullable
    private MetalGpuTexture objectValidityTexture;
    @Nullable
    private GpuTextureView objectMotionView;
    @Nullable
    private GpuTextureView objectValidityView;
""",
    """    @Nullable
    private MetalGpuTexture objectValidityTexture;
    @Nullable
    private MetalGpuTexture handExactValidityTexture;
    @Nullable
    private GpuTextureView objectMotionView;
    @Nullable
    private GpuTextureView objectValidityView;
    @Nullable
    private GpuTextureView handExactValidityView;
""",
    "manager hand validity fields",
)

manager = replace_once(
    manager,
    """        if (objectMotionView == null || objectValidityView == null) {
            MetalEntityMotionCapture.recordMotionDrawSkip("attachments-unavailable");
            return;
        }
""",
    """        if (objectMotionView == null || objectValidityView == null || handExactValidityView == null) {
            MetalEntityMotionCapture.recordMotionDrawSkip("attachments-unavailable");
            return;
        }
""",
    "draw replay attachment gate",
)

manager = replace_once(
    manager,
    """        if (depthView == null || objectMotionView == null || objectValidityView == null) {
            replays.forEach(ignored -> MetalEntityMotionCapture.recordMotionDrawSkip("flush-attachments-unavailable"));
            return;
        }
""",
    """        if (depthView == null || objectMotionView == null || objectValidityView == null
                || handExactValidityView == null) {
            replays.forEach(ignored -> MetalEntityMotionCapture.recordMotionDrawSkip("flush-attachments-unavailable"));
            return;
        }
""",
    "flush replay attachment gate",
)

old_render_block = """        RenderPassDescriptor descriptor = RenderPassDescriptor
                .create(() -> "Metallum batched ordinary entity object motion");
        if (objectMotionInputsCleared) {
            descriptor = descriptor
                    .withColorAttachment(objectMotionView)
                    .withColorAttachment(objectValidityView);
        } else {
            descriptor = descriptor
                    .withColorAttachment(objectMotionView, Optional.of(UI_CLEAR))
                    .withColorAttachment(objectValidityView, Optional.of(UI_CLEAR));
        }
        descriptor = descriptor
                .withDepthAttachment(depthView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
        try (RenderPass pass = encoder.createRenderPass(descriptor)) {
            for (PreparedObjectMotionReplay replay : preparedReplays) {
                PreparedRenderType prepared = replay.prepared();
                StagedVertexBuffer.ExecuteInfo executeInfo = replay.executeInfo();
                boolean exactPreviousPositions = replay.previousPositionBuffer() != null;
                pass.setPipeline(exactPreviousPositions
                        ? MetalEntityMotionPipeline.forPreviousPositions(prepared.pipeline())
                        : MetalEntityMotionPipeline.forSource(prepared.pipeline()));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", replay.dynamicTransforms());
                pass.setUniform("MetallumMotion", replay.motionUniform());
                pass.setVertexBuffer(0, replay.currentVertexBuffer());
                if (exactPreviousPositions) {
                    pass.setVertexBuffer(1, replay.previousPositionBuffer());
                }
                for (PreparedRenderType.Texture texture : prepared.textures()) {
                    pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
                }
                pass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType());
                pass.drawIndexed(
                        executeInfo.indexCount(),
                        1,
                        executeInfo.firstIndex(),
                        replay.replayBaseVertex(),
                        0
                );
                if (exactPreviousPositions) {
                    MetalEntityMotionCapture.recordExactReplayEncoded(replay.exactPreviousVertexToken());
                }
                recordMotionProducerEncoded(replay.sample());
                MetalEntityMotionCapture.recordMotionDrawEncoded(prepared.pipeline());
            }
        }
        objectMotionInputsCleared = true;
"""
new_render_block = """        List<PreparedObjectMotionReplay> worldReplays = new ArrayList<>(preparedReplays.size());
        List<PreparedObjectMotionReplay> firstPersonReplays = new ArrayList<>(2);
        for (PreparedObjectMotionReplay replay : preparedReplays) {
            if (replay.sample().domain() == FrameSynthesisContract.ProducerDomain.FIRST_PERSON) {
                firstPersonReplays.add(replay);
            } else {
                worldReplays.add(replay);
            }
        }

        // World validity and first-person validity are deliberately different namespaces.
        // A world entity can be directly behind the hand at the same pixel; reusing its
        // validity bit would make the hand consume unrelated world motion. Always run the
        // world pass (even with no draws) so object motion/validity are deterministically
        // cleared for this source frame, then append first-person exact motion into the
        // shared RG16F motion field while writing a dedicated R8 validity plane.
        RenderPassDescriptor worldDescriptor = RenderPassDescriptor
                .create(() -> "Metallum batched world object motion");
        if (objectMotionInputsCleared) {
            worldDescriptor = worldDescriptor
                    .withColorAttachment(objectMotionView)
                    .withColorAttachment(objectValidityView);
        } else {
            worldDescriptor = worldDescriptor
                    .withColorAttachment(objectMotionView, Optional.of(UI_CLEAR))
                    .withColorAttachment(objectValidityView, Optional.of(UI_CLEAR));
        }
        worldDescriptor = worldDescriptor
                .withDepthAttachment(depthView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
        try (RenderPass pass = encoder.createRenderPass(worldDescriptor)) {
            encodePreparedMotionReplays(pass, worldReplays);
        }
        objectMotionInputsCleared = true;

        if (!firstPersonReplays.isEmpty()) {
            RenderPassDescriptor handDescriptor = RenderPassDescriptor
                    .create(() -> "Metallum batched first-person exact motion")
                    .withColorAttachment(objectMotionView)
                    .withColorAttachment(handExactValidityView)
                    .withDepthAttachment(depthView)
                    .withRenderArea(new RenderPass.RenderArea(0, 0, renderWidth, renderHeight));
            try (RenderPass pass = encoder.createRenderPass(handDescriptor)) {
                encodePreparedMotionReplays(pass, firstPersonReplays);
            }
        }
    }

    private void encodePreparedMotionReplays(
            final RenderPass pass,
            final List<PreparedObjectMotionReplay> replays
    ) {
        for (PreparedObjectMotionReplay replay : replays) {
            PreparedRenderType prepared = replay.prepared();
            StagedVertexBuffer.ExecuteInfo executeInfo = replay.executeInfo();
            boolean exactPreviousPositions = replay.previousPositionBuffer() != null;
            pass.setPipeline(exactPreviousPositions
                    ? MetalEntityMotionPipeline.forPreviousPositions(prepared.pipeline())
                    : MetalEntityMotionPipeline.forSource(prepared.pipeline()));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", replay.dynamicTransforms());
            pass.setUniform("MetallumMotion", replay.motionUniform());
            pass.setVertexBuffer(0, replay.currentVertexBuffer());
            if (exactPreviousPositions) {
                pass.setVertexBuffer(1, replay.previousPositionBuffer());
            }
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
            }
            pass.setIndexBuffer(executeInfo.indexBuffer(), executeInfo.indexType());
            pass.drawIndexed(
                    executeInfo.indexCount(),
                    1,
                    executeInfo.firstIndex(),
                    replay.replayBaseVertex(),
                    0
            );
            if (exactPreviousPositions) {
                MetalEntityMotionCapture.recordExactReplayEncoded(replay.exactPreviousVertexToken());
            }
            recordMotionProducerEncoded(replay.sample());
            MetalEntityMotionCapture.recordMotionDrawEncoded(prepared.pipeline());
        }
"""
manager = replace_once(manager, old_render_block, new_render_block, "split world/first-person replay passes")

manager = replace_once(
    manager,
    """                && objectValidityTexture != null && objectValidityTexture.getWidth(0) == renderWidth
                && objectValidityTexture.getHeight(0) == renderHeight
                && disocclusionTexture != null && disocclusionTexture.getWidth(0) == renderWidth
""",
    """                && objectValidityTexture != null && objectValidityTexture.getWidth(0) == renderWidth
                && objectValidityTexture.getHeight(0) == renderHeight
                && handExactValidityTexture != null && handExactValidityTexture.getWidth(0) == renderWidth
                && handExactValidityTexture.getHeight(0) == renderHeight
                && disocclusionTexture != null && disocclusionTexture.getWidth(0) == renderWidth
""",
    "auxiliary texture dimension gate",
)

manager = replace_once(
    manager,
    """        objectValidityTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Object Motion Validity R8", objectUsage, GpuFormat.R8_UNORM, renderWidth, renderHeight, 1, 1
        );
        objectMotionView = RenderSystem.getDevice().createTextureView(objectMotionTexture);
        objectValidityView = RenderSystem.getDevice().createTextureView(objectValidityTexture);
""",
    """        objectValidityTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX Object Motion Validity R8", objectUsage, GpuFormat.R8_UNORM, renderWidth, renderHeight, 1, 1
        );
        handExactValidityTexture = (MetalGpuTexture) RenderSystem.getDevice().createTexture(
                "MetalFX First-Person Exact Motion Validity R8",
                objectUsage,
                GpuFormat.R8_UNORM,
                renderWidth,
                renderHeight,
                1,
                1
        );
        objectMotionView = RenderSystem.getDevice().createTextureView(objectMotionTexture);
        objectValidityView = RenderSystem.getDevice().createTextureView(objectValidityTexture);
        handExactValidityView = RenderSystem.getDevice().createTextureView(handExactValidityTexture);
""",
    "create hand validity texture",
)

manager = replace_once(
    manager,
    """        if (objectMotionTexture == null || objectValidityTexture == null
                || reactiveTexture == null || cutoutReactiveTexture == null
""",
    """        if (objectMotionTexture == null || objectValidityTexture == null || handExactValidityTexture == null
                || reactiveTexture == null || cutoutReactiveTexture == null
""",
    "prepare motion input gate",
)

manager = replace_once(
    manager,
    """        device.commandEncoder().clearColorTexture(reactiveTexture, UI_CLEAR);
        device.commandEncoder().clearColorTexture(cutoutReactiveTexture, UI_CLEAR);
""",
    """        device.commandEncoder().clearColorTexture(reactiveTexture, UI_CLEAR);
        device.commandEncoder().clearColorTexture(cutoutReactiveTexture, UI_CLEAR);
        // This clear is consumed either by the first-person replay render pass or by
        // the later Temporal encode. It prevents a hand that disappears for one
        // submitted source frame from inheriting exact validity from an older frame.
        device.commandEncoder().clearColorTexture(handExactValidityTexture, UI_CLEAR);
""",
    "clear hand validity each source frame",
)

manager = replace_once(
    manager,
    """        if (objectMotionView != null) objectMotionView.close();
        if (objectValidityView != null) objectValidityView.close();
        if (cutoutReactiveView != null) cutoutReactiveView.close();
        objectMotionView = null;
        objectValidityView = null;
        cutoutReactiveView = null;
""",
    """        if (objectMotionView != null) objectMotionView.close();
        if (objectValidityView != null) objectValidityView.close();
        if (handExactValidityView != null) handExactValidityView.close();
        if (cutoutReactiveView != null) cutoutReactiveView.close();
        objectMotionView = null;
        objectValidityView = null;
        handExactValidityView = null;
        cutoutReactiveView = null;
""",
    "close hand validity view",
)

manager = replace_once(
    manager,
    """        if (objectMotionTexture != null) objectMotionTexture.close();
        if (objectValidityTexture != null) objectValidityTexture.close();
        if (disocclusionTexture != null) disocclusionTexture.close();
""",
    """        if (objectMotionTexture != null) objectMotionTexture.close();
        if (objectValidityTexture != null) objectValidityTexture.close();
        if (handExactValidityTexture != null) handExactValidityTexture.close();
        if (disocclusionTexture != null) disocclusionTexture.close();
""",
    "close hand validity texture",
)

manager = replace_once(
    manager,
    """        objectMotionTexture = null;
        objectValidityTexture = null;
        disocclusionTexture = null;
""",
    """        objectMotionTexture = null;
        objectValidityTexture = null;
        handExactValidityTexture = null;
        disocclusionTexture = null;
""",
    "null hand validity texture",
)

manager = replace_once(
    manager,
    """                + (objectMotionTexture == null ? 0 : 1)
                + (objectValidityTexture == null ? 0 : 1)
                + (disocclusionTexture == null ? 0 : 1)
""",
    """                + (objectMotionTexture == null ? 0 : 1)
                + (objectValidityTexture == null ? 0 : 1)
                + (handExactValidityTexture == null ? 0 : 1)
                + (disocclusionTexture == null ? 0 : 1)
""",
    "count hand validity texture",
)

manager = replace_once(
    manager,
    """                && objectMotionTexture != null && objectValidityTexture != null
                && reactiveTexture != null
""",
    """                && objectMotionTexture != null && objectValidityTexture != null
                && handExactValidityTexture != null && reactiveTexture != null
""",
    "hand overlay resource gate",
)

manager = replace_once(
    manager,
    """                        objectMotionTexture,
                        objectValidityTexture,
                        reactiveTexture,
""",
    """                        objectMotionTexture,
                        objectValidityTexture,
                        handExactValidityTexture,
                        reactiveTexture,
""",
    "hand overlay encoder arguments",
)

manager = replace_once(
    manager,
    """                    && cameraMotionTexture != null && objectMotionTexture != null
                    && objectValidityTexture != null && disocclusionTexture != null
""",
    """                    && cameraMotionTexture != null && objectMotionTexture != null
                    && objectValidityTexture != null && handExactValidityTexture != null
                    && disocclusionTexture != null
""",
    "temporal v3 resource gate",
)

manager = replace_once(
    manager,
    """                        depth,
                        handDepth,
                        HAND_OVERLAY_REACTIVE_BOOST,
                        cameraMotionTexture,
""",
    """                        depth,
                        handDepth,
                        handExactValidityTexture,
                        HAND_OVERLAY_REACTIVE_BOOST,
                        cameraMotionTexture,
""",
    "temporal v3 encoder arguments",
)

# Exact first-person owners participate in the receipt transaction even though
# FIRST_PERSON remains a hard motion-eligibility veto in this increment.
manager = replace_once(
    manager,
    """    static void markExactParticleProducerCandidate(
            final MetalEntityMotionCapture.Sample sample
    ) {
""",
    """    static void markExactFirstPersonProducerCandidate(
            final MetalEntityMotionCapture.Sample sample
    ) {
        MetalFxManager manager = active;
        if (manager != null && sample != null && sample.hasPrevious()) {
            manager.markExactProducerCandidate(
                    FrameSynthesisContract.ProducerDomain.FIRST_PERSON,
                    sample
            );
        }
    }

    static void markExactParticleProducerCandidate(
            final MetalEntityMotionCapture.Sample sample
    ) {
""",
    "first-person exact receipt candidate",
)

write(manager_path, manager)


synthetic_path = "src/main/java/com/metallum/client/metal/render/MetalSyntheticExactMotion.java"
synthetic = read(synthetic_path)
synthetic = replace_once(
    synthetic,
    """        MetalEntityMotionCapture.attachState(state, sample);
        MetalEntityMotionCapture.requireExactState(state);
        MetalEntityMotionCapture.beginEntitySubmission(state);
""",
    """        MetalEntityMotionCapture.attachState(state, sample);
        MetalEntityMotionCapture.requireExactState(state);
        MetalFxManager.markExactFirstPersonProducerCandidate(sample);
        MetalEntityMotionCapture.beginEntitySubmission(state);
""",
    "mark synthetic hand exact candidate",
)
# The pending stack is already an owned copy and is cleared immediately after
# commit. Move it into committed history instead of allocating a second copy.
synthetic = replace_once(
    synthetic,
    """            previousMainHandStack = pendingMainHandStack == null ? null : pendingMainHandStack.copy();
""",
    """            previousMainHandStack = pendingMainHandStack;
""",
    "avoid duplicate main-hand ItemStack copy",
)
synthetic = replace_once(
    synthetic,
    """            previousOffHandStack = pendingOffHandStack == null ? null : pendingOffHandStack.copy();
""",
    """            previousOffHandStack = pendingOffHandStack;
""",
    "avoid duplicate off-hand ItemStack copy",
)
write(synthetic_path, synthetic)


# ---------------------------------------------------------------------------
# Java encoder and FFM bridge. Preserve the shipped V2 ABI: the exact-validity
# extension is V3, and stale dylibs fall back to V2/zero-hand-motion safely.
# ---------------------------------------------------------------------------
encoder_path = "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"
encoder = read(encoder_path)
encoder = replace_once(
    encoder,
    """            @Nullable final MetalGpuTexture handDepth,
            final float handReactiveBoost,
            final MetalGpuTexture cameraMotion,
""",
    """            @Nullable final MetalGpuTexture handDepth,
            final MetalGpuTexture handExactValidity,
            final float handReactiveBoost,
            final MetalGpuTexture cameraMotion,
""",
    "encoder temporal hand validity parameter",
)
encoder = replace_once(
    encoder,
    """        if (handDepth != null) flushPendingClear(handDepth);
        flushPendingClear(cameraMotion);
""",
    """        if (handDepth != null) flushPendingClear(handDepth);
        flushPendingClear(handExactValidity);
        flushPendingClear(cameraMotion);
""",
    "flush hand exact validity before temporal",
)
old_v2_call = """        return MetalNativeBridge.metallum_metalfx_encode_v2(
                commandBuffer().nativeHandle(),
                device.metalDeviceHandle(),
                color.nativeHandle(),
                depth.nativeHandle(),
                handDepth == null ? MemorySegment.NULL : handDepth.nativeHandle(),
                cameraMotion.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                disocclusion.nativeHandle(),
                motion.nativeHandle(),
                reactive.nativeHandle(),
                output.nativeHandle(),
                currentViewProjection.get(currentViewProjectionBuffer),
                inverseCurrentViewProjection.get(inverseViewProjectionBuffer),
                previousViewProjection.get(previousViewProjectionBuffer),
                pixelJitter.x,
                pixelJitter.y,
                handReactiveBoost,
                inputWidth,
                inputHeight,
                reset,
                depthReversed,
                preserveReactiveMask,
                emitMotionDiagnostics,
                fence
        );
"""
new_v2_call = """        if (MetalNativeBridge.metallum_metalfx_encode_v3_available()) {
            return MetalNativeBridge.metallum_metalfx_encode_v3(
                    commandBuffer().nativeHandle(),
                    device.metalDeviceHandle(),
                    color.nativeHandle(),
                    depth.nativeHandle(),
                    handDepth == null ? MemorySegment.NULL : handDepth.nativeHandle(),
                    handExactValidity.nativeHandle(),
                    cameraMotion.nativeHandle(),
                    objectMotion.nativeHandle(),
                    objectValidity.nativeHandle(),
                    disocclusion.nativeHandle(),
                    motion.nativeHandle(),
                    reactive.nativeHandle(),
                    output.nativeHandle(),
                    currentViewProjection.get(currentViewProjectionBuffer),
                    inverseCurrentViewProjection.get(inverseViewProjectionBuffer),
                    previousViewProjection.get(previousViewProjectionBuffer),
                    pixelJitter.x,
                    pixelJitter.y,
                    handReactiveBoost,
                    inputWidth,
                    inputHeight,
                    reset,
                    depthReversed,
                    preserveReactiveMask,
                    emitMotionDiagnostics,
                    fence
            );
        }
        return MetalNativeBridge.metallum_metalfx_encode_v2(
                commandBuffer().nativeHandle(),
                device.metalDeviceHandle(),
                color.nativeHandle(),
                depth.nativeHandle(),
                handDepth == null ? MemorySegment.NULL : handDepth.nativeHandle(),
                cameraMotion.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                disocclusion.nativeHandle(),
                motion.nativeHandle(),
                reactive.nativeHandle(),
                output.nativeHandle(),
                currentViewProjection.get(currentViewProjectionBuffer),
                inverseCurrentViewProjection.get(inverseViewProjectionBuffer),
                previousViewProjection.get(previousViewProjectionBuffer),
                pixelJitter.x,
                pixelJitter.y,
                handReactiveBoost,
                inputWidth,
                inputHeight,
                reset,
                depthReversed,
                preserveReactiveMask,
                emitMotionDiagnostics,
                fence
        );
"""
encoder = replace_once(encoder, old_v2_call, new_v2_call, "encoder V3/V2 native dispatch")

encoder = replace_once(
    encoder,
    """            final MetalGpuTexture objectMotion,
            final MetalGpuTexture objectValidity,
            final MetalGpuTexture reactive,
""",
    """            final MetalGpuTexture objectMotion,
            final MetalGpuTexture objectValidity,
            final MetalGpuTexture handExactValidity,
            final MetalGpuTexture reactive,
""",
    "hand overlay exact validity parameter",
)
encoder = replace_once(
    encoder,
    """        flushPendingClear(objectMotion);
        flushPendingClear(objectValidity);
        flushPendingClear(reactive);
""",
    """        flushPendingClear(objectMotion);
        flushPendingClear(objectValidity);
        flushPendingClear(handExactValidity);
        flushPendingClear(reactive);
""",
    "hand overlay exact validity clear",
)
old_hand_call = """        return MetalNativeBridge.metallum_metalfx_encode_hand_overlay(
                commandBuffer().nativeHandle(),
                handDepth.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                reactive.nativeHandle(),
                inputWidth,
                inputHeight,
                reactiveBoost,
                fence
        );
"""
new_hand_call = """        if (MetalNativeBridge.metallum_metalfx_encode_hand_overlay_v2_available()) {
            return MetalNativeBridge.metallum_metalfx_encode_hand_overlay_v2(
                    commandBuffer().nativeHandle(),
                    handDepth.nativeHandle(),
                    objectMotion.nativeHandle(),
                    objectValidity.nativeHandle(),
                    handExactValidity.nativeHandle(),
                    reactive.nativeHandle(),
                    inputWidth,
                    inputHeight,
                    reactiveBoost,
                    fence
            );
        }
        return MetalNativeBridge.metallum_metalfx_encode_hand_overlay(
                commandBuffer().nativeHandle(),
                handDepth.nativeHandle(),
                objectMotion.nativeHandle(),
                objectValidity.nativeHandle(),
                reactive.nativeHandle(),
                inputWidth,
                inputHeight,
                reactiveBoost,
                fence
        );
"""
encoder = replace_once(encoder, old_hand_call, new_hand_call, "hand overlay V2/V1 native dispatch")
write(encoder_path, encoder)


bridge_path = "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
bridge = read(bridge_path)
bridge = replace_once(
    bridge,
    """            metalfxEncodeHandOverlay = optionalDowncall(
                    lookup,
                    "metallum_metalfx_encode_hand_overlay",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            FLOAT,
                            ValueLayout.ADDRESS
                    )
            );
            metalfxEncodeV2 = optionalDowncall(lookup, "metallum_metalfx_encode_v2", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    FLOAT, FLOAT, FLOAT, INT, INT, INT, INT, INT, INT
            ));
""",
    """            metalfxEncodeHandOverlay = optionalDowncall(
                    lookup,
                    "metallum_metalfx_encode_hand_overlay",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            FLOAT,
                            ValueLayout.ADDRESS
                    )
            );
            metalfxEncodeHandOverlayV2 = optionalDowncall(
                    lookup,
                    "metallum_metalfx_encode_hand_overlay_v2",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            FLOAT,
                            ValueLayout.ADDRESS
                    )
            );
            metalfxEncodeV2 = optionalDowncall(lookup, "metallum_metalfx_encode_v2", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    FLOAT, FLOAT, FLOAT, INT, INT, INT, INT, INT, INT
            ));
            metalfxEncodeV3 = optionalDowncall(lookup, "metallum_metalfx_encode_v3", FunctionDescriptor.of(
                    INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    FLOAT, FLOAT, FLOAT, INT, INT, INT, INT, INT, INT
            ));
""",
    "FFM V3 descriptors",
)
bridge = replace_once(
    bridge,
    """    @Nullable
    private static final MethodHandle metalfxEncodeHandOverlay;
    @Nullable
    private static final MethodHandle metalfxEncodeV2;
""",
    """    @Nullable
    private static final MethodHandle metalfxEncodeHandOverlay;
    @Nullable
    private static final MethodHandle metalfxEncodeHandOverlayV2;
    @Nullable
    private static final MethodHandle metalfxEncodeV2;
    @Nullable
    private static final MethodHandle metalfxEncodeV3;
""",
    "FFM V3 handles",
)

bridge = replace_once(
    bridge,
    """    public static boolean metallum_metalfx_encode_hand_overlay(
""",
    """    public static boolean metallum_metalfx_encode_hand_overlay_v2_available() {
        return metalfxEncodeHandOverlayV2 != null;
    }

    public static boolean metallum_metalfx_encode_hand_overlay(
""",
    "hand overlay v2 availability",
)

hand_v2_method = """
    public static boolean metallum_metalfx_encode_hand_overlay_v2(
            final MemorySegment commandBuffer,
            final MemorySegment handDepth,
            final MemorySegment objectMotion,
            final MemorySegment objectValidity,
            final MemorySegment handExactValidity,
            final MemorySegment reactive,
            final int inputWidth,
            final int inputHeight,
            final float reactiveBoost,
            final MemorySegment fence
    ) {
        if (metalfxEncodeHandOverlayV2 == null) {
            return false;
        }
        try {
            return (int) metalfxEncodeHandOverlayV2.invokeExact(
                    segment(commandBuffer),
                    segment(handDepth),
                    segment(objectMotion),
                    segment(objectValidity),
                    segment(handExactValidity),
                    segment(reactive),
                    inputWidth,
                    inputHeight,
                    reactiveBoost,
                    segment(fence)
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_encode_hand_overlay_v2", throwable);
        }
    }

"""
bridge = replace_once(
    bridge,
    """    public static boolean metallum_metalfx_mark_transparency(
""",
    hand_v2_method + "    public static boolean metallum_metalfx_mark_transparency(\n",
    "insert hand overlay v2 wrapper",
)

bridge = replace_once(
    bridge,
    """    public static boolean metallum_metalfx_encode_v2(
""",
    """    public static boolean metallum_metalfx_encode_v3_available() {
        return metalfxEncodeV3 != null;
    }

    public static boolean metallum_metalfx_encode_v2(
""",
    "V3 availability",
)

v3_method = """
    public static boolean metallum_metalfx_encode_v3(
            final MemorySegment commandBuffer,
            final MemorySegment device,
            final MemorySegment color,
            final MemorySegment depth,
            @Nullable final MemorySegment handDepth,
            final MemorySegment handExactValidity,
            final MemorySegment cameraMotion,
            final MemorySegment objectMotion,
            final MemorySegment objectValidity,
            final MemorySegment disocclusion,
            final MemorySegment motion,
            final MemorySegment reactive,
            final MemorySegment output,
            @Nullable final float[] currentViewProjection,
            @Nullable final float[] inverseCurrentViewProjection,
            @Nullable final float[] previousViewProjection,
            final float jitterX,
            final float jitterY,
            final float handReactiveBoost,
            final int inputWidth,
            final int inputHeight,
            final boolean reset,
            final boolean depthReversed,
            final boolean preserveReactiveMask,
            final boolean emitMotionDiagnostics,
            final MemorySegment fence
    ) {
        if (metalfxEncodeV3 == null) {
            return false;
        }
        try {
            MetalFxMatrixScratch scratch = METALFX_MATRIX_SCRATCH.get();
            MemorySegment current = scratch.copy(currentViewProjection, scratch.current);
            MemorySegment inverse = scratch.copy(inverseCurrentViewProjection, scratch.inverse);
            MemorySegment previous = scratch.copy(previousViewProjection, scratch.previous);
            return (int) metalfxEncodeV3.invokeExact(
                    segment(commandBuffer), segment(device), segment(color), segment(depth),
                    segment(handDepth), segment(handExactValidity), segment(cameraMotion),
                    segment(objectMotion), segment(objectValidity), segment(disocclusion),
                    segment(motion), segment(reactive), segment(output), current, inverse, previous,
                    segment(fence), jitterX, jitterY, handReactiveBoost, inputWidth, inputHeight,
                    reset ? 1 : 0, depthReversed ? 1 : 0, preserveReactiveMask ? 1 : 0,
                    emitMotionDiagnostics ? 1 : 0
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_metalfx_encode_v3", throwable);
        }
    }

"""
bridge = replace_once(
    bridge,
    """    public static boolean metallum_metalfx_frame_generation_encode(
""",
    v3_method + "    public static boolean metallum_metalfx_frame_generation_encode(\n",
    "insert V3 wrapper",
)
write(bridge_path, bridge)


# ---------------------------------------------------------------------------
# Swift: keep old C ABIs intact, add V3/V2 symbols, and make the internal
# kernels consume the dedicated first-person exact-validity plane.
# ---------------------------------------------------------------------------
swift_path = "src/main/native/MetallumNative.swift"
swift = read(swift_path)

swift = replace_once(
    swift,
    """      texture2d<half, access::write> objectValidityTexture [[texture(2)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(3)]],
""",
    """      texture2d<half, access::write> objectValidityTexture [[texture(2)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(3)]],
      texture2d<float, access::read> handExactValidityTexture [[texture(4)]],
""",
    "hand overlay kernel validity texture",
)
swift = replace_once(
    swift,
    """      objectMotionTexture.write(half4(half(0.0)), pixel);
      objectValidityTexture.write(
""",
    """      float handExactValid = u.reserved > 0.5 ? handExactValidityTexture.read(pixel).r : 0.0;
      if (!(isfinite(handExactValid) && handExactValid > 0.5)) {
        objectMotionTexture.write(half4(half(0.0)), pixel);
      }
      // Legacy merge has no hand-depth branch. Force it to select the shared
      // object-motion field at hand pixels; V2 keeps exact motion when the
      // dedicated mask proves ownership and otherwise writes the safe zero fallback.
      objectValidityTexture.write(
""",
    "hand overlay preserve exact motion",
)

# Internal Metal 3 hand-overlay encoder accepts an optional exact mask. V1 uses nil.
swift = replace_once(
    swift,
    """    _ objectValidityTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
""",
    """    _ objectValidityTexture: MTLTexture,
    _ handExactValidityTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture,
""",
    "metal3 hand overlay parameter",
)
swift = replace_once(
    swift,
    """              objectValidityTexture.width == Int(inputWidth),
              objectValidityTexture.height == Int(inputHeight),
              reactiveTexture.width == Int(inputWidth),
""",
    """              objectValidityTexture.width == Int(inputWidth),
              objectValidityTexture.height == Int(inputHeight),
              handExactValidityTexture == nil || (handExactValidityTexture?.width == Int(inputWidth)
                  && handExactValidityTexture?.height == Int(inputHeight)
                  && handExactValidityTexture?.pixelFormat == .r8Unorm),
              reactiveTexture.width == Int(inputWidth),
""",
    "metal3 hand overlay validity guard",
)
swift = replace_once(
    swift,
    """            reactiveBoost: reactiveBoost,
            reserved: 0.0
""",
    """            reactiveBoost: reactiveBoost,
            reserved: handExactValidityTexture != nil ? 1.0 : 0.0
""",
    "metal3 hand overlay validity flag",
)
swift = replace_once(
    swift,
    """        encoder.setTexture(objectValidityTexture, index: 2)
        encoder.setTexture(reactiveTexture, index: 3)
""",
    """        encoder.setTexture(objectValidityTexture, index: 2)
        encoder.setTexture(reactiveTexture, index: 3)
        encoder.setTexture(handExactValidityTexture, index: 4)
""",
    "metal3 hand overlay validity binding",
)

# Replace the C hand-overlay entry with a shared implementation plus a V2 symbol.
hand_entry_pattern = r'''@_cdecl\("metallum_metalfx_encode_hand_overlay"\)\npublic func metallumMetalFxEncodeHandOverlayEntry\(.*?\n}\n\nprivate func metal3MetalFxClearMotionInputs\('''
hand_entry_replacement = r'''private func metalFxEncodeHandOverlayEntryImpl(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ handDepthTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ handExactValidityTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reactiveBoost: Float,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer), inputWidth > 0, inputHeight > 0,
       handDepthTexture.width == Int(inputWidth), handDepthTexture.height == Int(inputHeight),
       objectMotionTexture.width == Int(inputWidth), objectMotionTexture.height == Int(inputHeight),
       objectValidityTexture.width == Int(inputWidth), objectValidityTexture.height == Int(inputHeight),
       handExactValidityTexture == nil || (handExactValidityTexture?.width == Int(inputWidth)
           && handExactValidityTexture?.height == Int(inputHeight)
           && handExactValidityTexture?.pixelFormat == .r8Unorm),
       reactiveTexture.width == Int(inputWidth), reactiveTexture.height == Int(inputHeight),
       objectMotionTexture.pixelFormat == .rg16Float,
       objectValidityTexture.pixelFormat == .r8Unorm, reactiveTexture.pixelFormat == .r8Unorm,
       let pipeline = ensureHandOverlayPipeline(handDepthTexture.device) {
        let uniforms = HandOverlayUniforms(
            width: UInt32(inputWidth), height: UInt32(inputHeight),
            reactiveBoost: reactiveBoost, reserved: handExactValidityTexture != nil ? 1.0 : 0.0
        )
        return encodeMetal4Compute(
            lease: lease, label: "MetalFX Hand Overlay Motion (Metal 4)",
            pipeline: pipeline, uniforms: uniforms,
            textures: [(0, handDepthTexture), (1, objectMotionTexture),
                       (2, objectValidityTexture), (3, reactiveTexture),
                       (4, handExactValidityTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) ? 1 : 0
    }
    #endif
    return metal3MetalFxEncodeHandOverlay(
        metal3CommandBuffer(commandBufferPointer), handDepthTexture, objectMotionTexture,
        objectValidityTexture, handExactValidityTexture, reactiveTexture,
        inputWidth, inputHeight, reactiveBoost, fence
    )
}

@_cdecl("metallum_metalfx_encode_hand_overlay")
public func metallumMetalFxEncodeHandOverlayEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ handDepthTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reactiveBoost: Float,
    _ fence: MTLFence?
) -> Int32 {
    metalFxEncodeHandOverlayEntryImpl(
        commandBufferPointer, handDepthTexture, objectMotionTexture, objectValidityTexture,
        nil, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
    )
}

@_cdecl("metallum_metalfx_encode_hand_overlay_v2")
public func metallumMetalFxEncodeHandOverlayV2Entry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ handDepthTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ handExactValidityTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reactiveBoost: Float,
    _ fence: MTLFence?
) -> Int32 {
    metalFxEncodeHandOverlayEntryImpl(
        commandBufferPointer, handDepthTexture, objectMotionTexture, objectValidityTexture,
        handExactValidityTexture, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
    )
}

private func metal3MetalFxClearMotionInputs('''
swift = replace_regex_once(swift, hand_entry_pattern, hand_entry_replacement, "hand overlay C ABI V2")

# Public V1 Swift wrapper calls the old C entry and therefore remains ABI-compatible.
# The internal Metal 3 call introduced above needs V1 to pass nil.
swift = replace_once(
    swift,
    """        metal3CommandBuffer(commandBufferPointer), handDepthTexture, objectMotionTexture,
        objectValidityTexture, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
""",
    """        metal3CommandBuffer(commandBufferPointer), handDepthTexture, objectMotionTexture,
        objectValidityTexture, nil, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
""",
    "legacy hand-overlay fallback nil mask",
)

# Fused shader consumes a dedicated validity texture. World validity is never
# consulted for covered first-person pixels.
swift = replace_once(
    swift,
    """      texture2d<float, access::read> handDepthTexture [[texture(8)]],
      constant FusedMotionUniforms& u [[buffer(0)]],
""",
    """      texture2d<float, access::read> handDepthTexture [[texture(8)]],
      texture2d<float, access::read> handExactValidityTexture [[texture(9)]],
      constant FusedMotionUniforms& u [[buffer(0)]],
""",
    "fused hand validity texture",
)
old_hand_branch = """      if (u.options.z != 0u) {
        float handDepth = handDepthTexture.read(pixel).r;
        if (isfinite(handDepth) && handDepth > 0.0000001) {
          // The hand target is cleared immediately before first-person
          // rendering. Covered pixels are camera-locked, so zero motion is the
          // exact camera component; swing/bob remains protected by reactivity.
          selected = float2(0.0);
          reactive = max(reactive, quantizeUnorm8(u.params.z));
        }
      }
"""
new_hand_branch = """      if (u.options.z != 0u) {
        float handDepth = handDepthTexture.read(pixel).r;
        if (isfinite(handDepth) && handDepth > 0.0000001) {
          // Hand pixels have their own ownership proof. A world entity directly
          // behind the hand may set objectValidityTexture at the same pixel, so
          // that plane is intentionally ignored here. Exact first-person motion
          // is consumed only when the dedicated mask says the shared RG16F value
          // was produced by this hand replay; otherwise zero remains the safe
          // camera-locked fallback.
          bool exactHand = false;
          if (u.options.w != 0u) {
            float handValid = handExactValidityTexture.read(pixel).r;
            if (isfinite(handValid) && handValid > 0.5) {
              float2 handMotion = float2(objectMotionTexture.read(pixel).rg);
              if (all(isfinite(handMotion)) && all(abs(handMotion) <= float2(32.0))) {
                selected = handMotion;
                exactHand = true;
              } else {
                reactive = 1.0;
              }
            }
          }
          if (!exactHand) {
            selected = float2(0.0);
          }
          reactive = max(reactive, quantizeUnorm8(u.params.z));
        }
      }
"""
swift = replace_once(swift, old_hand_branch, new_hand_branch, "fused hand ownership selection")

# Internal Metal 4/3 temporal functions accept an optional V3 mask. V2 passes nil.
swift = replace_once(
    swift,
    """    colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
    cameraMotionTexture: MTLTexture, objectMotionTexture: MTLTexture,
""",
    """    colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
    handExactValidityTexture: MTLTexture?,
    cameraMotionTexture: MTLTexture, objectMotionTexture: MTLTexture,
""",
    "metal4 temporal exact validity parameter",
)
swift = replace_once(
    swift,
    """          handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
              && handDepthTexture?.height == Int(inputHeight)),
          cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
""",
    """          handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
              && handDepthTexture?.height == Int(inputHeight)),
          handExactValidityTexture == nil || (handExactValidityTexture?.width == Int(inputWidth)
              && handExactValidityTexture?.height == Int(inputHeight)
              && handExactValidityTexture?.pixelFormat == .r8Unorm),
          cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
""",
    "metal4 temporal exact validity guard",
)
swift = replace_once(
    swift,
    """            options: SIMD4(NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                           emitMotionDiagnostics != 0 ? 1 : 0,
                           handDepthTexture != nil ? 1 : 0, 0),
""",
    """            options: SIMD4(NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                           emitMotionDiagnostics != 0 ? 1 : 0,
                           handDepthTexture != nil ? 1 : 0,
                           handExactValidityTexture != nil ? 1 : 0),
""",
    "metal4 fused validity option",
)
swift = replace_once(
    swift,
    """                       (6, cameraMotionTexture), (7, disocclusionTexture),
                       (8, handDepthTexture)],
""",
    """                       (6, cameraMotionTexture), (7, disocclusionTexture),
                       (8, handDepthTexture), (9, handExactValidityTexture)],
""",
    "metal4 fused validity binding",
)

swift = replace_once(
    swift,
    """    _ depthTexture: MTLTexture,
    _ handDepthTexture: MTLTexture?,
    _ cameraMotionTexture: MTLTexture,
""",
    """    _ depthTexture: MTLTexture,
    _ handDepthTexture: MTLTexture?,
    _ handExactValidityTexture: MTLTexture?,
    _ cameraMotionTexture: MTLTexture,
""",
    "metal3 temporal exact validity parameter",
)
swift = replace_once(
    swift,
    """                  handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
                      && handDepthTexture?.height == Int(inputHeight)),
                  cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
""",
    """                  handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
                      && handDepthTexture?.height == Int(inputHeight)),
                  handExactValidityTexture == nil || (handExactValidityTexture?.width == Int(inputWidth)
                      && handExactValidityTexture?.height == Int(inputHeight)
                      && handExactValidityTexture?.pixelFormat == .r8Unorm),
                  cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
""",
    "metal3 temporal exact validity guard",
)
swift = replace_once(
    swift,
    """                    options: SIMD4<UInt32>(
                        NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                        emitMotionDiagnostics != 0 ? 1 : 0,
                        handDepthTexture != nil ? 1 : 0,
                        0
                    ),
""",
    """                    options: SIMD4<UInt32>(
                        NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                        emitMotionDiagnostics != 0 ? 1 : 0,
                        handDepthTexture != nil ? 1 : 0,
                        handExactValidityTexture != nil ? 1 : 0
                    ),
""",
    "metal3 fused validity option",
)
swift = replace_once(
    swift,
    """                fusedEncoder.setTexture(disocclusionTexture, index: 7)
                fusedEncoder.setTexture(handDepthTexture, index: 8)
""",
    """                fusedEncoder.setTexture(disocclusionTexture, index: 7)
                fusedEncoder.setTexture(handDepthTexture, index: 8)
                fusedEncoder.setTexture(handExactValidityTexture, index: 9)
""",
    "metal3 fused validity binding",
)

# Replace the V2 C entry with a shared implementation and a V3 symbol. V2 ABI
# remains byte-for-byte the same and supplies nil for the new mask.
v2_entry_pattern = r'''@_cdecl\("metallum_metalfx_encode_v2"\)\npublic func metallumMetalFxEncodeV2Entry\(.*?\n}\n\n@_cdecl\("metallum_metalfx_frame_generation_encode"\)'''
v2_entry_replacement = r'''private func metalFxEncodeV2EntryImpl(
    _ commandBufferPointer: UnsafeMutableRawPointer, _ device: MTLDevice,
    _ colorTexture: MTLTexture, _ depthTexture: MTLTexture, _ handDepthTexture: MTLTexture?,
    _ handExactValidityTexture: MTLTexture?,
    _ cameraMotionTexture: MTLTexture, _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture, _ disocclusionTexture: MTLTexture,
    _ motionTexture: MTLTexture, _ reactiveTexture: MTLTexture, _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?, _ fence: MTLFence?,
    _ jitterX: Float, _ jitterY: Float, _ handReactiveBoost: Float,
    _ inputWidth: Int32, _ inputHeight: Int32, _ reset: Int32, _ depthReversed: Int32,
    _ preserveReactiveMask: Int32, _ emitMotionDiagnostics: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer) {
        return metal4MetalFxEncodeV2(
            lease: lease, device: device, colorTexture: colorTexture, depthTexture: depthTexture,
            handDepthTexture: handDepthTexture, handExactValidityTexture: handExactValidityTexture,
            cameraMotionTexture: cameraMotionTexture, objectMotionTexture: objectMotionTexture,
            objectValidityTexture: objectValidityTexture, disocclusionTexture: disocclusionTexture,
            motionTexture: motionTexture, reactiveTexture: reactiveTexture, outputTexture: outputTexture,
            currentViewProjection: currentViewProjection,
            inverseCurrentViewProjection: inverseCurrentViewProjection,
            previousViewProjection: previousViewProjection, fence: fence,
            jitterX: jitterX, jitterY: jitterY, handReactiveBoost: handReactiveBoost,
            inputWidth: inputWidth, inputHeight: inputHeight, reset: reset,
            depthReversed: depthReversed, preserveReactiveMask: preserveReactiveMask,
            emitMotionDiagnostics: emitMotionDiagnostics
        )
    }
    #endif
    return metal3MetalFxEncodeV2(
        metal3CommandBuffer(commandBufferPointer), device, colorTexture, depthTexture, handDepthTexture,
        handExactValidityTexture, cameraMotionTexture, objectMotionTexture, objectValidityTexture,
        disocclusionTexture, motionTexture, reactiveTexture, outputTexture, currentViewProjection,
        inverseCurrentViewProjection, previousViewProjection, fence, jitterX, jitterY,
        handReactiveBoost, inputWidth, inputHeight, reset, depthReversed,
        preserveReactiveMask, emitMotionDiagnostics
    )
}

@_cdecl("metallum_metalfx_encode_v2")
public func metallumMetalFxEncodeV2Entry(
    _ commandBufferPointer: UnsafeMutableRawPointer, _ device: MTLDevice,
    _ colorTexture: MTLTexture, _ depthTexture: MTLTexture, _ handDepthTexture: MTLTexture?,
    _ cameraMotionTexture: MTLTexture, _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture, _ disocclusionTexture: MTLTexture,
    _ motionTexture: MTLTexture, _ reactiveTexture: MTLTexture, _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?, _ fence: MTLFence?,
    _ jitterX: Float, _ jitterY: Float, _ handReactiveBoost: Float,
    _ inputWidth: Int32, _ inputHeight: Int32, _ reset: Int32, _ depthReversed: Int32,
    _ preserveReactiveMask: Int32, _ emitMotionDiagnostics: Int32
) -> Int32 {
    metalFxEncodeV2EntryImpl(
        commandBufferPointer, device, colorTexture, depthTexture, handDepthTexture, nil,
        cameraMotionTexture, objectMotionTexture, objectValidityTexture, disocclusionTexture,
        motionTexture, reactiveTexture, outputTexture, currentViewProjection,
        inverseCurrentViewProjection, previousViewProjection, fence, jitterX, jitterY,
        handReactiveBoost, inputWidth, inputHeight, reset, depthReversed,
        preserveReactiveMask, emitMotionDiagnostics
    )
}

@_cdecl("metallum_metalfx_encode_v3")
public func metallumMetalFxEncodeV3Entry(
    _ commandBufferPointer: UnsafeMutableRawPointer, _ device: MTLDevice,
    _ colorTexture: MTLTexture, _ depthTexture: MTLTexture, _ handDepthTexture: MTLTexture?,
    _ handExactValidityTexture: MTLTexture,
    _ cameraMotionTexture: MTLTexture, _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture, _ disocclusionTexture: MTLTexture,
    _ motionTexture: MTLTexture, _ reactiveTexture: MTLTexture, _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?, _ fence: MTLFence?,
    _ jitterX: Float, _ jitterY: Float, _ handReactiveBoost: Float,
    _ inputWidth: Int32, _ inputHeight: Int32, _ reset: Int32, _ depthReversed: Int32,
    _ preserveReactiveMask: Int32, _ emitMotionDiagnostics: Int32
) -> Int32 {
    metalFxEncodeV2EntryImpl(
        commandBufferPointer, device, colorTexture, depthTexture, handDepthTexture,
        handExactValidityTexture, cameraMotionTexture, objectMotionTexture, objectValidityTexture,
        disocclusionTexture, motionTexture, reactiveTexture, outputTexture,
        currentViewProjection, inverseCurrentViewProjection, previousViewProjection, fence,
        jitterX, jitterY, handReactiveBoost, inputWidth, inputHeight, reset, depthReversed,
        preserveReactiveMask, emitMotionDiagnostics
    )
}

@_cdecl("metallum_metalfx_frame_generation_encode")'''
swift = replace_regex_once(swift, v2_entry_pattern, v2_entry_replacement, "temporal C ABI V3")

write(swift_path, swift)


# ---------------------------------------------------------------------------
# Source-contract regression test. Native compilation in the macOS workflow is
# the executable proof; this test makes the semantic ownership split explicit.
# ---------------------------------------------------------------------------
test_path = Path("src/test/java/com/metallum/client/metal/render/MetalFxFirstPersonValidityContractTest.java")
test_path.write_text('''package com.metallum.client.metal.render;\n\nimport org.junit.jupiter.api.Test;\n\nimport java.nio.file.Files;\nimport java.nio.file.Path;\n\nimport static org.junit.jupiter.api.Assertions.assertFalse;\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nfinal class MetalFxFirstPersonValidityContractTest {\n    @Test\n    void handPixelsUseDedicatedExactValidityAndKeepProductionGatesClosed() throws Exception {\n        String manager = Files.readString(Path.of(\n                "src/main/java/com/metallum/client/metal/render/MetalFxManager.java"));\n        String encoder = Files.readString(Path.of(\n                "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java"));\n        String bridge = Files.readString(Path.of(\n                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"));\n        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));\n\n        assertTrue(manager.contains("MetalFX First-Person Exact Motion Validity R8"));\n        assertTrue(manager.contains("replay.sample().domain() == FrameSynthesisContract.ProducerDomain.FIRST_PERSON"));\n        assertTrue(manager.contains(".withColorAttachment(handExactValidityView)"));\n        assertTrue(manager.contains("private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;"));\n        assertTrue(manager.contains("motionEligibility.reject(MetalFxMotionEligibility.FIRST_PERSON);"));\n\n        assertTrue(encoder.contains("metallum_metalfx_encode_v3_available()"));\n        assertTrue(bridge.contains("metallum_metalfx_encode_v3"));\n        assertTrue(bridge.contains("metallum_metalfx_encode_hand_overlay_v2"));\n        assertTrue(nativeSource.contains("handExactValidityTexture [[texture(9)]]"));\n        assertTrue(nativeSource.contains("float handValid = handExactValidityTexture.read(pixel).r;"));\n        assertTrue(nativeSource.contains("if (!exactHand)"));\n        assertTrue(nativeSource.contains("selected = float2(0.0);"));\n        assertTrue(nativeSource.contains("@_cdecl(\\"metallum_metalfx_encode_v2\\")"));\n        assertTrue(nativeSource.contains("@_cdecl(\\"metallum_metalfx_encode_v3\\")"));\n        assertFalse(nativeSource.contains("hand exact validity is inferred from objectValidity"));\n    }\n}\n''')

print("first-person exact validity patch applied")
