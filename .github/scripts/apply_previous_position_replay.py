from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


capture_path = Path("src/main/java/com/metallum/client/metal/render/MetalEntityMotionCapture.java")
capture = capture_path.read_text()
capture = replace_once(
    capture,
    "    private static final Map<StagedVertexBuffer.ExecuteInfo, Sample> EXECUTES = new IdentityHashMap<>();\n",
    "    private static final Map<StagedVertexBuffer.ExecuteInfo, Sample> EXECUTES = new IdentityHashMap<>();\n"
    "    private static final Map<StagedVertexBuffer.ExecuteInfo, MetalPreviousVertexHistory.DrawToken> EXECUTE_VERTEX_TOKENS =\n"
    "            new IdentityHashMap<>();\n",
    "execute token map",
)
capture = replace_once(
    capture,
    "        EXECUTES.clear();\n        statesAttached = 0;\n",
    "        EXECUTES.clear();\n        EXECUTE_VERTEX_TOKENS.clear();\n        statesAttached = 0;\n",
    "clear execute tokens",
)
capture = replace_once(
    capture,
    "        if (capture != null && executeInfo != null) {\n"
    "            EXECUTES.put(executeInfo, capture.sample());\n"
    "            executesTransferred++;\n"
    "        }\n",
    "        if (capture != null && executeInfo != null) {\n"
    "            EXECUTES.put(executeInfo, capture.sample());\n"
    "            if (capture.previousVertexToken() != null) {\n"
    "                EXECUTE_VERTEX_TOKENS.put(executeInfo, capture.previousVertexToken());\n"
    "            }\n"
    "            executesTransferred++;\n"
    "        }\n",
    "transfer execute token",
)
take_execute = '''    public static Sample takeExecute(final StagedVertexBuffer.ExecuteInfo executeInfo) {
        if (!enabled) {
            return null;
        }
        Sample sample = EXECUTES.remove(executeInfo);
        if (sample != null) {
            executesConsumed++;
        }
        return sample;
    }
'''
capture = replace_once(
    capture,
    take_execute,
    take_execute
    + '''
    /** Consumes the staged previous-position identity paired with this exact ExecuteInfo. */
    static MetalPreviousVertexHistory.DrawToken takePreviousVertexToken(
            final StagedVertexBuffer.ExecuteInfo executeInfo
    ) {
        return enabled && executeInfo != null ? EXECUTE_VERTEX_TOKENS.remove(executeInfo) : null;
    }
''',
    "take previous vertex token",
)
capture_path.write_text(capture)

manager_path = Path("src/main/java/com/metallum/client/metal/render/MetalFxManager.java")
manager = manager_path.read_text()
manager = replace_once(
    manager,
    "    private final Matrix4f previousViewProjection = new Matrix4f();\n"
    "    private final Matrix4f currentViewProjection = new Matrix4f();\n",
    "    private final Matrix4f previousViewProjection = new Matrix4f();\n"
    "    private final Matrix4f currentViewProjection = new Matrix4f();\n"
    "    // ENTITY staged Position already contains the camera-relative root and CPU model pose.\n"
    "    // Keep Projection * viewRotation transactionally with the previous successful source frame\n"
    "    // so exact replay never rebuilds large world coordinates by adding the camera back.\n"
    "    private final Matrix4f previousCameraRelativeViewProjection = new Matrix4f();\n"
    "    private final Matrix4f currentCameraRelativeViewProjection = new Matrix4f();\n",
    "camera-relative fields",
)
old_records = '''    private record ObjectMotionReplay(
            PreparedRenderType prepared,
            StagedVertexBuffer.ExecuteInfo executeInfo,
            GpuBufferSlice dynamicTransforms,
            GpuBufferSlice motionUniform
    ) {
    }
'''
new_records = '''    private record ObjectMotionReplay(
            PreparedRenderType prepared,
            StagedVertexBuffer.ExecuteInfo executeInfo,
            GpuBufferSlice dynamicTransforms,
            MetalEntityMotionCapture.Sample sample,
            @Nullable MetalPreviousVertexHistory.DrawToken previousVertexToken
    ) {
    }

    private record PreparedObjectMotionReplay(
            PreparedRenderType prepared,
            StagedVertexBuffer.ExecuteInfo executeInfo,
            GpuBufferSlice dynamicTransforms,
            GpuBufferSlice motionUniform,
            GpuBufferSlice currentVertexBuffer,
            @Nullable GpuBufferSlice previousPositionBuffer,
            int replayBaseVertex
    ) {
    }
'''
manager = replace_once(manager, old_records, new_records, "replay records")

start = manager.index("    private void drawEntityMotionInternal(\n")
end = manager.index("    private void flushEntityMotionReplaysInternal(", start)
new_draw = '''    private void drawEntityMotionInternal(
            final PreparedRenderType prepared,
            final StagedVertexBuffer.ExecuteInfo executeInfo,
            final MetalEntityMotionCapture.Sample sample
    ) {
        // Consume the sidecar with the ExecuteInfo lifetime even when this source frame fails closed.
        MetalPreviousVertexHistory.DrawToken previousVertexToken =
                MetalEntityMotionCapture.takePreviousVertexToken(executeInfo);
        if (!sceneFrame) {
            MetalEntityMotionCapture.recordMotionDrawSkip("scene-frame-inactive");
            return;
        }
        if (!motionInputsPrepared) {
            MetalEntityMotionCapture.recordMotionDrawSkip("motion-inputs-unprepared");
            return;
        }
        if (historyReset) {
            MetalEntityMotionCapture.recordMotionDrawSkip("history-reset");
            return;
        }
        if (!sample.hasPrevious()) {
            MetalEntityMotionCapture.recordMotionDrawSkip("no-previous-object-state");
            return;
        }
        if (objectMotionView == null || objectValidityView == null) {
            MetalEntityMotionCapture.recordMotionDrawSkip("attachments-unavailable");
            return;
        }
        if (!MetalEntityMotionPipeline.supports(prepared.pipeline())) {
            MetalEntityMotionCapture.recordMotionDrawSkip("pipeline-unsupported");
            return;
        }

        // Exact previous-position selection is intentionally deferred until flush. At that point all
        // feature draws have registered, so objectManifestMatches cannot accept a transient prefix.
        objectMotionReplays.add(new ObjectMotionReplay(
                prepared,
                executeInfo,
                prepared.dynamicTransforms(),
                sample,
                previousVertexToken
        ));
    }

'''
manager = manager[:start] + new_draw + manager[end:]

start = manager.index("    private void flushEntityMotionReplaysInternal(")
end = manager.index("    private Matrix4f prepareSceneProjectionInternal(", start)
new_flush = '''    private void flushEntityMotionReplaysInternal(final GameRenderer renderer) {
        if (!motionInputsPrepared || (objectMotionReplays.isEmpty() && objectMotionInputsCleared)) {
            return;
        }
        List<ObjectMotionReplay> replays = List.copyOf(objectMotionReplays);
        objectMotionReplays.clear();

        RenderTarget mainTarget = renderer.mainRenderTarget();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (depthView == null || objectMotionView == null || objectValidityView == null) {
            replays.forEach(ignored -> MetalEntityMotionCapture.recordMotionDrawSkip("flush-attachments-unavailable"));
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        Matrix4f currentUnjitteredFromRaster =
                new Matrix4f(currentViewProjection).mul(inverseCurrentViewProjection);
        if (!MetalFxMath.isFinite(currentUnjitteredFromRaster)) {
            replays.forEach(ignored -> MetalEntityMotionCapture.recordMotionDrawSkip("non-finite-current-transform"));
            return;
        }

        List<PreparedObjectMotionReplay> preparedReplays = new ArrayList<>(replays.size());
        for (ObjectMotionReplay replay : replays) {
            PreparedRenderType prepared = replay.prepared();
            StagedVertexBuffer.ExecuteInfo executeInfo = replay.executeInfo();
            MetalPreviousVertexReplay.Plan exactPlan = MetalFxMath.isFinite(previousCameraRelativeViewProjection)
                    ? MetalPreviousVertexReplay.plan(
                            prepared.pipeline(), executeInfo, replay.previousVertexToken())
                    : null;
            boolean exactPreviousPositions = exactPlan != null;
            Matrix4f previousFromRaster = exactPreviousPositions
                    ? new Matrix4f(previousCameraRelativeViewProjection)
                    : new Matrix4f(previousViewProjection)
                            .mul(MetalEntityMotionCapture.objectCurrentToPrevious(replay.sample()))
                            .mul(inverseCurrentViewProjection);
            if (!MetalFxMath.isFinite(previousFromRaster)) {
                MetalEntityMotionCapture.recordMotionDrawSkip("non-finite-previous-transform");
                continue;
            }

            GpuBufferSlice currentVertexBuffer = exactPreviousPositions
                    ? exactPlan.currentVertexBuffer()
                    : executeInfo.vertexBuffer().slice();
            int replayBaseVertex = exactPreviousPositions
                    ? exactPlan.replayBaseVertex()
                    : executeInfo.baseVertex();
            GpuBufferSlice previousPositionBuffer = null;
            if (exactPreviousPositions) {
                float[] previousPositions = exactPlan.previousPositions();
                long previousByteCount = Math.multiplyExact((long) previousPositions.length, Float.BYTES);
                try (GpuBufferSlice.MappedView mapped = encoder.transientMemory()
                        .allocateGpuMapped(previousByteCount, 16L, GpuBuffer.USAGE_VERTEX)) {
                    ByteBuffer bytes = mapped.data().order(ByteOrder.nativeOrder());
                    for (float value : previousPositions) {
                        bytes.putFloat(value);
                    }
                    previousPositionBuffer = mapped.slice();
                }
            }

            GpuBufferSlice motionUniform;
            try (GpuBufferSlice.MappedView mapped = encoder.transientMemory()
                    .allocateGpuMapped(128L, 256L, GpuBuffer.USAGE_UNIFORM)) {
                ByteBuffer bytes = mapped.data().order(ByteOrder.nativeOrder());
                currentUnjitteredFromRaster.get(0, bytes);
                previousFromRaster.get(64, bytes);
                motionUniform = mapped.slice();
            }
            preparedReplays.add(new PreparedObjectMotionReplay(
                    prepared,
                    executeInfo,
                    replay.dynamicTransforms(),
                    motionUniform,
                    currentVertexBuffer,
                    previousPositionBuffer,
                    replayBaseVertex
            ));
        }

        RenderPassDescriptor descriptor = RenderPassDescriptor
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
                MetalEntityMotionCapture.recordMotionDrawEncoded(prepared.pipeline());
            }
        }
        objectMotionInputsCleared = true;
    }

'''
manager = manager[:start] + new_flush + manager[end:]

manager = replace_once(
    manager,
    "        MetalFxMath.adjustPerspectiveAspect(this.currentProjection, displayAspect, renderAspect);\n",
    "        MetalFxMath.adjustPerspectiveAspect(this.currentProjection, displayAspect, renderAspect);\n"
    "        MetalFxMath.viewProjection(\n"
    "                this.currentCameraRelativeViewProjection,\n"
    "                this.currentProjection,\n"
    "                cameraState.viewRotationMatrix\n"
    "        );\n",
    "current camera-relative vp",
)
manager = replace_once(
    manager,
    "        if (!MetalFxMath.isFinite(this.currentViewProjection)) {\n",
    "        if (!MetalFxMath.isFinite(this.currentViewProjection)\n"
    "                || !MetalFxMath.isFinite(this.currentCameraRelativeViewProjection)) {\n",
    "camera-relative finite check",
)
manager = replace_once(
    manager,
    "            if (!previousMatrixValid) {\n"
    "                previousViewProjection.set(currentViewProjection);\n"
    "                previousMatrixValid = true;\n"
    "                historyReset = true;\n"
    "            }\n",
    "            if (!previousMatrixValid) {\n"
    "                previousViewProjection.set(currentViewProjection);\n"
    "                previousCameraRelativeViewProjection.set(currentCameraRelativeViewProjection);\n"
    "                previousMatrixValid = true;\n"
    "                historyReset = true;\n"
    "            }\n",
    "initial previous camera-relative vp",
)
manager = replace_once(
    manager,
    "            Matrix4f submittedViewProjection = new Matrix4f(this.currentViewProjection);\n"
    "            int submittedNextPhase = (phase + 1) % phaseCount;\n",
    "            Matrix4f submittedViewProjection = new Matrix4f(this.currentViewProjection);\n"
    "            Matrix4f submittedCameraRelativeViewProjection =\n"
    "                    new Matrix4f(this.currentCameraRelativeViewProjection);\n"
    "            int submittedNextPhase = (phase + 1) % phaseCount;\n",
    "submitted camera-relative vp",
)
manager = replace_once(
    manager,
    "                        this.previousViewProjection.set(submittedViewProjection);\n"
    "                        this.previousMatrixValid = true;\n",
    "                        this.previousViewProjection.set(submittedViewProjection);\n"
    "                        this.previousCameraRelativeViewProjection.set(submittedCameraRelativeViewProjection);\n"
    "                        this.previousMatrixValid = true;\n",
    "commit camera-relative vp",
)
manager_path.write_text(manager)

test_path = Path("src/test/java/com/metallum/client/metal/render/MetalPreviousVertexHistoryTest.java")
test = test_path.read_text()
anchor = '''    @Test
    void historyAdvancesOnlyOnSuccessfulCommit() {
'''
addition = '''    @Test
    void compactPreviousPositionBindingMatchesConfirmedMinecraftAbi() {
        VertexFormat format = MetalEntityMotionPipeline.previousPositionFormat();
        assertTrue(format.getStepRate() == 0);
        assertTrue(format.getVertexSize() == 12);
        assertTrue(format.getElements().size() == 1);
        assertTrue(format.contains("PreviousPosition"));
    }

'''
test = replace_once(test, anchor, addition + anchor, "previous position ABI test")
test_path.write_text(test)
