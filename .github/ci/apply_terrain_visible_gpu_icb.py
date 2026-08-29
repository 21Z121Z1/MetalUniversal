from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}\n--- anchor ---\n{old[:500]}")
    file.write_text(text.replace(old, new, 1))


def insert_before(path: str, anchor: str, addition: str) -> None:
    replace_once(path, anchor, addition + anchor)


# ---------------------------------------------------------------------------
# Feature request vs native capability: keep the new path opt-in and fail-closed.
# ---------------------------------------------------------------------------
replace_once(
    "src/main/java/com/metallum/client/metal/render/TerrainCandidateSnapshot.java",
    '''    public static final boolean GPU_VISIBILITY_PROBE_ENABLED = Boolean.parseBoolean(\n            System.getProperty(GPU_VISIBILITY_PROBE_PROPERTY, "false")\n    );\n''',
    '''    public static final boolean GPU_VISIBILITY_PROBE_ENABLED = Boolean.parseBoolean(\n            System.getProperty(GPU_VISIBILITY_PROBE_PROPERTY, "false")\n    );\n    /** GPU visibility directly masks source-ordinal terrain ICB slots. Default off. */\n    public static final String VISIBLE_GPU_ICB_PROPERTY =\n            "metallum.opt.terrainVisibleGpuIcb";\n    public static final boolean VISIBLE_GPU_ICB_ENABLED = Boolean.parseBoolean(\n            System.getProperty(VISIBLE_GPU_ICB_PROPERTY, "false")\n    );\n'''
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/TerrainCandidateRegistry.java",
    '''    public static final boolean ENABLED = Boolean.parseBoolean(\n            System.getProperty(PROPERTY, "false")\n    ) || TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED;\n''',
    '''    public static final boolean ENABLED = Boolean.parseBoolean(\n            System.getProperty(PROPERTY, "false")\n    ) || TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n            || TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED;\n'''
)

replace_once(
    "src/main/java/com/metallum/client/metal/render/TerrainSceneSnapshot.java",
    '''    public static final boolean GPU_ICB_ENABLED = Boolean.parseBoolean(\n            System.getProperty("metallum.opt.terrainGpuEncode", "false")\n    );\n\n    /**\n     * Producer-side Sodium draw metadata.  This is deliberately independent\n''',
    '''    public static final boolean GPU_ICB_ENABLED = Boolean.parseBoolean(\n            System.getProperty("metallum.opt.terrainGpuEncode", "false")\n    );\n\n    /**\n     * Strict opt-in for GPU visibility-masked, source-ordinal ICB authoring.\n     * Candidate capture and draw metadata become required producer inputs, but\n     * unsupported native/Metal 4 paths still fall through to the existing ICB\n     * or indirect submission.\n     */\n    public static final boolean VISIBLE_GPU_ICB_ENABLED =\n            TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED;\n\n    /**\n     * Producer-side Sodium draw metadata.  This is deliberately independent\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/TerrainSceneSnapshot.java",
    '''    public static boolean captureEnabled() {\n        return ENABLED || ICB_ENABLED || GPU_ICB_ENABLED || DRAW_METADATA_ENABLED;\n    }\n''',
    '''    public static boolean captureEnabled() {\n        return ENABLED || ICB_ENABLED || GPU_ICB_ENABLED\n                || VISIBLE_GPU_ICB_ENABLED || DRAW_METADATA_ENABLED;\n    }\n\n    public static boolean drawMetadataRequired() {\n        return DRAW_METADATA_ENABLED || VISIBLE_GPU_ICB_ENABLED;\n    }\n'''
)
replace_once(
    "src/main/java/com/metallum/client/metal/render/TerrainDrawMetadataCapture.java",
    '''    public static boolean enabled() {\n        return TerrainSceneSnapshot.DRAW_METADATA_ENABLED;\n    }\n''',
    '''    public static boolean enabled() {\n        return TerrainSceneSnapshot.drawMetadataRequired();\n    }\n'''
)
replace_once(
    "src/main/java/com/metallum/mixin/sodium/VKIndirectDrawBatchTerrainSceneMixin.java",
    '''                && (TerrainSceneSnapshot.ICB_ENABLED || TerrainSceneSnapshot.GPU_ICB_ENABLED)) {\n''',
    '''                && (TerrainSceneSnapshot.ICB_ENABLED\n                || TerrainSceneSnapshot.GPU_ICB_ENABLED\n                || TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED)) {\n'''
)

# ---------------------------------------------------------------------------
# Visibility probe: visible-ICB mode reuses the in-flight native owner without
# a CPU readback/oracle on the draw-authority path. Explicit diagnostic probe
# mode keeps its existing CPU differential oracle unchanged.
# ---------------------------------------------------------------------------
probe = "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"
replace_once(
    probe,
    '''    public static final boolean ENABLED = TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED;\n''',
    '''    public static final boolean ENABLED = TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n            || TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED;\n    private static final boolean ORACLE_ENABLED =\n            TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED;\n'''
)
replace_once(
    probe,
    '''                    Oracle oracle;\n                    try {\n                        packedCandidates = snapshot.packGpuVisibilityCandidates(arena);\n                        packedMatrix = snapshot.packGpuVisibilityMatrix(arena);\n                        oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);\n                    } catch (RuntimeException invalidInput) {\n''',
    '''                    Oracle oracle = null;\n                    try {\n                        packedCandidates = snapshot.packGpuVisibilityCandidates(arena);\n                        packedMatrix = snapshot.packGpuVisibilityMatrix(arena);\n                        if (ORACLE_ENABLED) {\n                            oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);\n                        }\n                    } catch (RuntimeException invalidInput) {\n'''
)
replace_once(
    probe,
    '''                    PENDING.add(new Pending(\n                            probe,\n                            snapshot.epoch(),\n                            count,\n                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count),\n                            oracle.expectedWords(),\n                            oracle.visibleCount(),\n                            oracle.uncertainCount(),\n                            oracle.compactedIndices()\n                    ));\n''',
    '''                    PENDING.add(oracle != null\n                            ? new Pending(\n                            probe,\n                            snapshot.epoch(),\n                            count,\n                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count),\n                            oracle.expectedWords(),\n                            oracle.visibleCount(),\n                            oracle.uncertainCount(),\n                            oracle.compactedIndices(),\n                            true\n                    )\n                            : Pending.withoutOracle(\n                            probe, snapshot.epoch(), count,\n                            TerrainCandidateSnapshot.gpuVisibilityWordCount(count)\n                    ));\n'''
)
insert_before(
    probe,
    '''    static TerrainCandidateRegistry.VisibilityResult latestResult() {\n''',
    '''    /**\n     * Returns the unique in-flight native probe owner for this exact producer\n     * epoch. This never polls or copies GPU results to the CPU; the returned\n     * pointer is borrowed while PENDING owns the Java-side retain.\n     */\n    static MemorySegment ownerForEpoch(final long epoch, final int expectedCandidateCount) {\n        if (!ENABLED || epoch < 0L || expectedCandidateCount <= 0) {\n            return MemorySegment.NULL;\n        }\n        synchronized (LOCK) {\n            MemorySegment found = MemorySegment.NULL;\n            for (Pending pending : PENDING) {\n                if (pending.epoch() != epoch\n                        || pending.candidateCount() != expectedCandidateCount) {\n                    continue;\n                }\n                if (!MetalNativeBridge.isNullHandle(found)) {\n                    return MemorySegment.NULL;\n                }\n                found = pending.probe();\n            }\n            return found;\n        }\n    }\n\n'''
)
replace_once(
    probe,
    '''                    boolean falseNegative = missingExpectedBits(\n                            pending.expectedWords(), actualWords\n                    );\n''',
    '''                    boolean falseNegative = pending.oracleEnabled() && missingExpectedBits(\n                            pending.expectedWords(), actualWords\n                    );\n'''
)
replace_once(
    probe,
    '''                    boolean compactionMismatch = !compactionMatches(\n                            pending.expectedCompactedIndices(), completedCompacted, actualCompacted\n                    );\n''',
    '''                    boolean compactionMismatch = pending.oracleEnabled() && !compactionMatches(\n                            pending.expectedCompactedIndices(), completedCompacted, actualCompacted\n                    );\n'''
)
replace_once(
    probe,
    '''        boolean countersMatch = Integer.toUnsignedLong(completedVisible)\n                == pending.expectedVisibleCount()\n                && Integer.toUnsignedLong(completedUncertain)\n                == pending.expectedUncertainCount();\n        boolean valid = completedEpoch == pending.epoch()\n                && completedWordCount == pending.wordCount()\n                && !missingExpectedBits(pending.expectedWords(), actualWords)\n                && countersMatch\n                && compactionMatches(\n                        pending.expectedCompactedIndices(), completedCompacted, actualCompacted\n                );\n''',
    '''        boolean valid = completedEpoch == pending.epoch()\n                && completedWordCount == pending.wordCount()\n                && completedCompacted >= 0\n                && completedCompacted <= pending.candidateCount()\n                && actualCompacted != null\n                && actualCompacted.length == completedCompacted\n                && Integer.toUnsignedLong(completedVisible) <= pending.candidateCount()\n                && Integer.toUnsignedLong(completedUncertain) <= pending.candidateCount();\n        if (valid && pending.oracleEnabled()) {\n            boolean countersMatch = Integer.toUnsignedLong(completedVisible)\n                    == pending.expectedVisibleCount()\n                    && Integer.toUnsignedLong(completedUncertain)\n                    == pending.expectedUncertainCount();\n            valid = !missingExpectedBits(pending.expectedWords(), actualWords)\n                    && countersMatch\n                    && compactionMatches(\n                    pending.expectedCompactedIndices(), completedCompacted, actualCompacted\n            );\n        }\n'''
)
replace_once(
    probe,
    '''    record Pending(\n            MemorySegment probe,\n            long epoch,\n            int candidateCount,\n            int wordCount,\n            int[] expectedWords,\n            int expectedVisibleCount,\n            int expectedUncertainCount,\n            int[] expectedCompactedIndices\n    ) {\n        Pending {\n            expectedWords = expectedWords.clone();\n            expectedCompactedIndices = expectedCompactedIndices.clone();\n        }\n\n        Pending(\n                final MemorySegment probe,\n                final long epoch,\n                final int candidateCount,\n                final int wordCount,\n                final int[] expectedWords,\n                final int expectedVisibleCount,\n                final int expectedUncertainCount\n        ) {\n            this(\n                    probe,\n                    epoch,\n                    candidateCount,\n                    wordCount,\n                    expectedWords,\n                    expectedVisibleCount,\n                    expectedUncertainCount,\n                    compactIndicesForWords(candidateCount, expectedWords)\n            );\n        }\n''',
    '''    record Pending(\n            MemorySegment probe,\n            long epoch,\n            int candidateCount,\n            int wordCount,\n            int[] expectedWords,\n            int expectedVisibleCount,\n            int expectedUncertainCount,\n            int[] expectedCompactedIndices,\n            boolean oracleEnabled\n    ) {\n        Pending {\n            expectedWords = expectedWords.clone();\n            expectedCompactedIndices = expectedCompactedIndices.clone();\n        }\n\n        Pending(\n                final MemorySegment probe,\n                final long epoch,\n                final int candidateCount,\n                final int wordCount,\n                final int[] expectedWords,\n                final int expectedVisibleCount,\n                final int expectedUncertainCount,\n                final int[] expectedCompactedIndices\n        ) {\n            this(probe, epoch, candidateCount, wordCount, expectedWords,\n                    expectedVisibleCount, expectedUncertainCount, expectedCompactedIndices, true);\n        }\n\n        Pending(\n                final MemorySegment probe,\n                final long epoch,\n                final int candidateCount,\n                final int wordCount,\n                final int[] expectedWords,\n                final int expectedVisibleCount,\n                final int expectedUncertainCount\n        ) {\n            this(\n                    probe,\n                    epoch,\n                    candidateCount,\n                    wordCount,\n                    expectedWords,\n                    expectedVisibleCount,\n                    expectedUncertainCount,\n                    compactIndicesForWords(candidateCount, expectedWords),\n                    true\n            );\n        }\n\n        static Pending withoutOracle(\n                final MemorySegment probe,\n                final long epoch,\n                final int candidateCount,\n                final int wordCount\n        ) {\n            return new Pending(\n                    probe, epoch, candidateCount, wordCount,\n                    new int[0], -1, -1, new int[0], false\n            );\n        }\n'''
)

# ---------------------------------------------------------------------------
# Native bridge: add the optional visible-ICB symbol without making it part of
# the required ABI. A missing new symbol therefore cannot break old dylibs.
# ---------------------------------------------------------------------------
bridge = "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
insert_before(
    bridge,
    '''    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilityProbe;\n''',
    '''    @Nullable\n    private static final MethodHandle MTLDeviceCreateTerrainVisibleGpuIndexedIcb;\n'''
)
insert_before(
    bridge,
    '''            MTLDeviceCreateTerrainGpuVisibilityProbe = optionalDowncallWithoutCritical(\n''',
    '''            MTLDeviceCreateTerrainVisibleGpuIndexedIcb = optionalDowncallWithoutCritical(\n                    lookup,\n                    "metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb",\n                    FunctionDescriptor.of(\n                            ValueLayout.ADDRESS,\n                            ValueLayout.ADDRESS,\n                            ValueLayout.ADDRESS,\n                            LONG,\n                            LONG,\n                            ValueLayout.ADDRESS,\n                            ValueLayout.ADDRESS,\n                            ValueLayout.ADDRESS,\n                            ValueLayout.ADDRESS,\n                            INT,\n                            ValueLayout.ADDRESS,\n                            LONG\n                    )\n            );\n'''
)
insert_before(
    bridge,
    '''    public static MemorySegment MTLDevice_createTerrainGpuVisibilityProbe(\n''',
    '''    public static boolean terrainVisibleGpuIcbAvailable() {\n        return MTLDeviceCreateTerrainVisibleGpuIndexedIcb != null;\n    }\n\n    /** Creates a source-ordinal terrain ICB whose slots are masked by an in-flight GPU visibility bitset. */\n    public static MemorySegment MTLDevice_createTerrainVisibleGpuIndexedIcb(\n            final MemorySegment renderEncoder,\n            final MemorySegment device,\n            final long primitiveType,\n            final long indexType,\n            final MemorySegment indexBuffer,\n            final MemorySegment pipeline,\n            final MemorySegment packedCommands,\n            final MemorySegment candidateBySourceOrdinal,\n            final int drawCount,\n            final MemorySegment visibilityProbeOwner,\n            final long expectedEpoch\n    ) {\n        if (MTLDeviceCreateTerrainVisibleGpuIndexedIcb == null\n                || drawCount <= 0 || expectedEpoch < 0L\n                || isNullHandle(visibilityProbeOwner)) {\n            return MemorySegment.NULL;\n        }\n        try {\n            return (MemorySegment) MTLDeviceCreateTerrainVisibleGpuIndexedIcb.invokeExact(\n                    segment(renderEncoder),\n                    segment(device),\n                    primitiveType,\n                    indexType,\n                    segment(indexBuffer),\n                    segment(pipeline),\n                    segment(packedCommands),\n                    segment(candidateBySourceOrdinal),\n                    drawCount,\n                    segment(visibilityProbeOwner),\n                    expectedEpoch\n            );\n        } catch (Throwable throwable) {\n            return MemorySegment.NULL;\n        }\n    }\n\n'''
)

# ---------------------------------------------------------------------------
# Metal 4 request gates: visible ICB only requests Metal 4 when both optional
# native capabilities exist. Do not restore the rollout's capability-dropping OR.
# ---------------------------------------------------------------------------
device = "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
replace_once(
    device,
    '''    /** Optional probe symbols are a capability gate, never a required bridge ABI. */\n    private static final boolean GPU_VISIBILITY_PROBE_METAL4 =\n            TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n                    && MetalNativeBridge.terrainVisibilityProbeAvailable();\n''',
    '''    /** Optional symbols are capability gates, never required bridge ABI. */\n    private static final boolean EXPLICIT_GPU_VISIBILITY_PROBE_METAL4 =\n            TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n                    && MetalNativeBridge.terrainVisibilityProbeAvailable();\n    private static final boolean VISIBLE_GPU_ICB_METAL4 =\n            TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED\n                    && MetalNativeBridge.terrainVisibilityProbeAvailable()\n                    && MetalNativeBridge.terrainVisibleGpuIcbAvailable();\n    private static final boolean GPU_VISIBILITY_PROBE_METAL4 =\n            EXPLICIT_GPU_VISIBILITY_PROBE_METAL4 || VISIBLE_GPU_ICB_METAL4;\n'''
)
replace_once(
    device,
    '''    private static final boolean VISIBILITY_PROBE_FALLBACK_ALLOWED =\n            TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n                    && !TerrainSceneSnapshot.ICB_ENABLED\n''',
    '''    private static final boolean VISIBILITY_PROBE_FALLBACK_ALLOWED =\n            (TerrainCandidateSnapshot.GPU_VISIBILITY_PROBE_ENABLED\n                    || TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED)\n                    && !TerrainSceneSnapshot.ICB_ENABLED\n'''
)
replace_once(
    device,
    '''        MetalNativeBridge.metallum_set_terrain_icb_enabled(\n                (TerrainSceneSnapshot.ICB_ENABLED || TerrainSceneSnapshot.GPU_ICB_ENABLED)\n                        && metal4Compiler ? 1 : 0\n        );\n        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(\n                TerrainSceneSnapshot.GPU_ICB_ENABLED && metal4Compiler ? 1 : 0\n        );\n''',
    '''        MetalNativeBridge.metallum_set_terrain_icb_enabled(\n                (TerrainSceneSnapshot.ICB_ENABLED\n                        || TerrainSceneSnapshot.GPU_ICB_ENABLED\n                        || VISIBLE_GPU_ICB_METAL4)\n                        && metal4Compiler ? 1 : 0\n        );\n        MetalNativeBridge.metallum_set_terrain_gpu_encode_enabled(\n                (TerrainSceneSnapshot.GPU_ICB_ENABLED || VISIBLE_GPU_ICB_METAL4)\n                        && metal4Compiler ? 1 : 0\n        );\n'''
)

# ---------------------------------------------------------------------------
# ICB owner: visible content is epoch-scoped and never content-only reusable.
# ---------------------------------------------------------------------------
icb = "src/main/java/com/metallum/client/metal/render/TerrainIcbOwner.java"
replace_once(
    icb,
    '''    private boolean gpuAuthored;\n    private boolean closed;\n''',
    '''    private boolean gpuAuthored;\n    /** -1 for CPU/all-visible ICB; otherwise the visibility producer epoch. */\n    private long visibilityEpoch = -1L;\n    private boolean closed;\n'''
)
replace_once(
    icb,
    '''                && gpuAuthored\n                && primitiveType == currentPrimitiveType\n''',
    '''                && gpuAuthored\n                && visibilityEpoch < 0L\n                && primitiveType == currentPrimitiveType\n'''
)
# Existing all-visible encode remains reusable but explicitly clears visibility epoch on every reset/success.
replace_once(
    icb,
    '''            content = null;\n            gpuAuthored = false;\n        }\n        device = currentDevice;\n''',
    '''            content = null;\n            gpuAuthored = false;\n            visibilityEpoch = -1L;\n        }\n        device = currentDevice;\n'''
)
replace_once(
    icb,
    '''        primitiveType = currentPrimitiveType;\n        gpuAuthored = false;\n        try (Arena arena = Arena.ofConfined()) {\n''',
    '''        primitiveType = currentPrimitiveType;\n        gpuAuthored = false;\n        visibilityEpoch = -1L;\n        try (Arena arena = Arena.ofConfined()) {\n'''
)
replace_once(
    icb,
    '''        content = snapshot.icbContent();\n        gpuAuthored = true;\n        return true;\n    }\n\n    boolean execute(\n''',
    '''        content = snapshot.icbContent();\n        gpuAuthored = true;\n        visibilityEpoch = -1L;\n        return true;\n    }\n\n    boolean encodeVisibleGpu(\n            final MetalDevice currentDevice,\n            final MemorySegment previousEncoder,\n            final MTLPrimitiveType currentPrimitiveType,\n            final MTLIndexType indexType,\n            final MemorySegment indexBuffer,\n            final MemorySegment pipeline,\n            final TerrainSceneSnapshot snapshot,\n            final TerrainVisibleDrawPlan plan,\n            final MemorySegment visibilityProbeOwner,\n            final int drawCount\n    ) {\n        if (closed || currentDevice == null || previousEncoder == null || snapshot == null\n                || plan == null || drawCount <= 0 || drawCount != snapshot.draws().size()\n                || drawCount != plan.drawCount() || indexBuffer == null || pipeline == null\n                || MetalNativeBridge.isNullHandle(indexBuffer)\n                || MetalNativeBridge.isNullHandle(pipeline)\n                || MetalNativeBridge.isNullHandle(visibilityProbeOwner)\n                || !MetalNativeBridge.terrainVisibleGpuIcbAvailable()) {\n            return false;\n        }\n        if (device != null && device != currentDevice) {\n            retire();\n            content = null;\n        }\n        device = currentDevice;\n        // Visibility is frame/epoch state, not immutable draw content. Never\n        // reuse a visibility-masked ICB solely because the command records match.\n        retire();\n        content = null;\n        primitiveType = currentPrimitiveType;\n        gpuAuthored = false;\n        visibilityEpoch = -1L;\n        try (Arena arena = Arena.ofConfined()) {\n            MemorySegment packed = snapshot.packIndexedCommands(arena);\n            MemorySegment mapping = plan.packCandidateIndices(arena);\n            indirectCommandBuffer = MetalNativeBridge.MTLDevice_createTerrainVisibleGpuIndexedIcb(\n                    previousEncoder, currentDevice.metalDeviceHandle(),\n                    currentPrimitiveType.value, indexType.value, indexBuffer, pipeline,\n                    packed, mapping, drawCount, visibilityProbeOwner, plan.candidateEpoch()\n            );\n        } catch (RuntimeException exception) {\n            indirectCommandBuffer = MemorySegment.NULL;\n            return false;\n        }\n        if (MetalNativeBridge.isNullHandle(indirectCommandBuffer)) {\n            return false;\n        }\n        content = snapshot.icbContent();\n        gpuAuthored = true;\n        visibilityEpoch = plan.candidateEpoch();\n        return true;\n    }\n\n    void invalidateVisibilityAuthored() {\n        if (visibilityEpoch < 0L) {\n            return;\n        }\n        retire();\n        content = null;\n        primitiveType = null;\n        gpuAuthored = false;\n        visibilityEpoch = -1L;\n    }\n\n    boolean execute(\n'''
)
replace_once(
    icb,
    '''            content = null;\n            primitiveType = currentPrimitiveType;\n            gpuAuthored = false;\n            try (Arena arena = Arena.ofConfined()) {\n''',
    '''            content = null;\n            primitiveType = currentPrimitiveType;\n            gpuAuthored = false;\n            visibilityEpoch = -1L;\n            try (Arena arena = Arena.ofConfined()) {\n'''
)
replace_once(
    icb,
    '''        primitiveType = null;\n        gpuAuthored = false;\n        return false;\n''',
    '''        primitiveType = null;\n        gpuAuthored = false;\n        visibilityEpoch = -1L;\n        return false;\n'''
)
replace_once(
    icb,
    '''        content = null;\n        primitiveType = null;\n    }\n''',
    '''        content = null;\n        primitiveType = null;\n        gpuAuthored = false;\n        visibilityEpoch = -1L;\n    }\n'''
)

# ---------------------------------------------------------------------------
# Render submission: visible mode gets first refusal. Any missing/stale input
# invalidates a previous visible ICB before CPU ICB / original indirect fallback.
# ---------------------------------------------------------------------------
render = "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
replace_once(
    render,
    '''        if (!TerrainSceneSnapshot.ICB_ENABLED && !TerrainSceneSnapshot.GPU_ICB_ENABLED) {\n''',
    '''        if (!TerrainSceneSnapshot.ICB_ENABLED\n                && !TerrainSceneSnapshot.GPU_ICB_ENABLED\n                && !TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED) {\n'''
)
insert_before(
    render,
    '''            if (TerrainSceneSnapshot.GPU_ICB_ENABLED\n                    && owner.hasReusableGpuIcb(device, primitiveType, snapshot)) {\n''',
    '''            if (TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED) {\n                TerrainCandidateSnapshot candidates = TerrainCandidateRegistry.latestSnapshot();\n                TerrainVisibleDrawPlan visiblePlan = candidates == null\n                        ? null : TerrainVisibleDrawPlan.tryBuild(snapshot, candidates);\n                MemorySegment visibilityOwner = visiblePlan == null\n                        ? MemorySegment.NULL\n                        : TerrainGpuVisibilityProbe.ownerForEpoch(\n                        visiblePlan.candidateEpoch(), visiblePlan.candidateCount()\n                );\n                if (visiblePlan != null\n                        && !MetalNativeBridge.isNullHandle(visibilityOwner)\n                        && MetalNativeBridge.terrainVisibleGpuIcbAvailable()) {\n                    MemorySegment retainedEncoder = commandEncoder.endEncoderForTerrainGpuAuthoring();\n                    try {\n                        if (owner.encodeVisibleGpu(\n                                device, retainedEncoder, primitiveType, indexType, indexHandle,\n                                pipelineHandle, snapshot, visiblePlan, visibilityOwner, drawCount\n                        )) {\n                            MTLRenderCommandEncoder reopened = renderEncoder();\n                            bindDrawState(reopened);\n                            if (owner.execute(\n                                    device, reopened, primitiveType, indexType, indexHandle,\n                                    pipelineHandle, snapshot, drawCount\n                            )) {\n                                return true;\n                            }\n                        }\n                    } finally {\n                        if (!MetalNativeBridge.isNullHandle(retainedEncoder)) {\n                            MetalNativeBridge.metallum_release_object(retainedEncoder);\n                        }\n                    }\n                }\n                owner.invalidateVisibilityAuthored();\n                MTLRenderCommandEncoder fallbackEncoder = renderEncoder();\n                bindDrawState(fallbackEncoder);\n                return owner.execute(\n                        device, fallbackEncoder, primitiveType, indexType, indexHandle,\n                        pipelineHandle, snapshot, drawCount\n                );\n            }\n'''
)
replace_once(
    render,
    '''            if (TerrainSceneSnapshot.ICB_ENABLED || TerrainSceneSnapshot.GPU_ICB_ENABLED) {\n''',
    '''            if (TerrainSceneSnapshot.ICB_ENABLED\n                    || TerrainSceneSnapshot.GPU_ICB_ENABLED\n                    || TerrainSceneSnapshot.VISIBLE_GPU_ICB_ENABLED) {\n'''
)

# ---------------------------------------------------------------------------
# Native Metal 4: compile a separate cached visible kernel variant, consume the
# prior visibility dispatch with a queue barrier, write source-ordinal slots,
# and retain both the mapping buffer and visibility owner until ICB retirement.
# ---------------------------------------------------------------------------
swift = "src/main/native/MetallumNative.swift"
replace_once(
    swift,
    '''private struct TerrainGpuComputePipelineKey: Hashable {\n    let deviceAddress: UInt\n    let primitiveType: UInt\n    let indexType: UInt\n}\n''',
    '''private struct TerrainGpuComputePipelineKey: Hashable {\n    let deviceAddress: UInt\n    let primitiveType: UInt\n    let indexType: UInt\n    let variant: UInt8\n}\n'''
)
replace_once(
    swift,
    '''    let pipeline: MTLRenderPipelineState\n\n    init(\n        commandBuffer: MTLIndirectCommandBuffer,\n        packedCommands: MTLBuffer,\n        argumentBuffer: MTLBuffer,\n        indexBuffer: MTLBuffer,\n        pipeline: MTLRenderPipelineState\n    ) {\n''',
    '''    let pipeline: MTLRenderPipelineState\n    let candidateIndices: MTLBuffer?\n    let visibilityOwner: AnyObject?\n\n    init(\n        commandBuffer: MTLIndirectCommandBuffer,\n        packedCommands: MTLBuffer,\n        argumentBuffer: MTLBuffer,\n        indexBuffer: MTLBuffer,\n        pipeline: MTLRenderPipelineState,\n        candidateIndices: MTLBuffer? = nil,\n        visibilityOwner: AnyObject? = nil\n    ) {\n'''
)
replace_once(
    swift,
    '''        self.indexBuffer = indexBuffer\n        self.pipeline = pipeline\n        residencyTrackCreated(commandBuffer)\n''',
    '''        self.indexBuffer = indexBuffer\n        self.pipeline = pipeline\n        self.candidateIndices = candidateIndices\n        self.visibilityOwner = visibilityOwner\n        residencyTrackCreated(commandBuffer)\n'''
)
replace_once(
    swift,
    '''        residencyTrackCreated(argumentBuffer)\n    }\n\n    deinit {\n''',
    '''        residencyTrackCreated(argumentBuffer)\n        if let candidateIndices { residencyTrackCreated(candidateIndices) }\n    }\n\n    deinit {\n'''
)
replace_once(
    swift,
    '''        residencyTrackReleased(rawPointer(argumentBuffer))\n    }\n}\n''',
    '''        residencyTrackReleased(rawPointer(argumentBuffer))\n        if let candidateIndices { residencyTrackReleased(rawPointer(candidateIndices)) }\n    }\n}\n'''
)
replace_once(
    swift,
    '''      command.draw_indexed_primitives(primitive_type::\\(primitive),\n          uint(record.indexCount), indices + uint(record.firstIndex),\n          uint(record.instanceCount), as_type<uint>(record.baseVertex),\n          uint(record.firstInstance));\n    }\n    """\n}\n''',
    '''      command.draw_indexed_primitives(primitive_type::\\(primitive),\n          uint(record.indexCount), indices + uint(record.firstIndex),\n          uint(record.instanceCount), as_type<uint>(record.baseVertex),\n          uint(record.firstInstance));\n    }\n\n    kernel void metallum_terrain_gpu_encode_visible(\n      device const TerrainDrawRecord *records [[buffer(0)]],\n      device TerrainIcbContainer *container [[buffer(1)]],\n      device \\(indexPointer) *indices [[buffer(2)]],\n      device atomic_uint *visibilityWords [[buffer(3)]],\n      device const uint *candidateBySourceOrdinal [[buffer(4)]],\n      uint drawIndex [[thread_position_in_grid]]) {\n      uint candidateIndex = candidateBySourceOrdinal[drawIndex];\n      uint word = atomic_load_explicit(&visibilityWords[candidateIndex >> 5], memory_order_relaxed);\n      if ((word & (1u << (candidateIndex & 31))) == 0u) {\n        return;\n      }\n      TerrainDrawRecord record = records[drawIndex];\n      if (record.indexCount < 0 || record.instanceCount < 0\n          || record.firstIndex < 0 || record.firstInstance < 0) {\n        return;\n      }\n      render_command command(container->commandBuffer, drawIndex);\n      command.draw_indexed_primitives(primitive_type::\\(primitive),\n          uint(record.indexCount), indices + uint(record.firstIndex),\n          uint(record.instanceCount), as_type<uint>(record.baseVertex),\n          uint(record.firstInstance));\n    }\n    """\n}\n'''
)
replace_once(
    swift,
    '''private func terrainGpuComputePipeline(\n    device: MTLDevice,\n    primitiveType: MTLPrimitiveType,\n    indexType: MTLIndexType,\n    source: String\n) -> TerrainGpuComputePipeline? {\n''',
    '''private func terrainGpuComputePipeline(\n    device: MTLDevice,\n    primitiveType: MTLPrimitiveType,\n    indexType: MTLIndexType,\n    source: String,\n    functionName: String,\n    variant: UInt8\n) -> TerrainGpuComputePipeline? {\n'''
)
replace_once(
    swift,
    '''        primitiveType: primitiveType.rawValue,\n        indexType: indexType.rawValue\n    )\n''',
    '''        primitiveType: primitiveType.rawValue,\n        indexType: indexType.rawValue,\n        variant: variant\n    )\n'''
)
replace_once(
    swift,
    '''            guard let function = library.makeFunction(name: "metallum_terrain_gpu_encode") else {\n''',
    '''            guard let function = library.makeFunction(name: functionName) else {\n'''
)
replace_once(
    swift,
    '''        indexType: indexType,\n        source: source\n    ) else {\n''',
    '''        indexType: indexType,\n        source: source,\n        functionName: "metallum_terrain_gpu_encode",\n        variant: 0\n    ) else {\n'''
)

visible_native = r'''
/// GPU-authors a sparse source-ordinal terrain ICB directly from the visibility
/// bitset produced earlier in the same Metal 4 command-buffer lease. Invisible
/// source slots remain reset/no-op; the render pass executes the complete source
/// range and never reads visibility back to the CPU.
@_cdecl("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb")
public func metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb(
    _ pointer: UnsafeMutableRawPointer,
    _ device: MTLDevice,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ pipeline: MTLRenderPipelineState,
    _ packedCommands: UnsafePointer<Int32>?,
    _ packedCandidateIndices: UnsafePointer<Int32>?,
    _ drawCount: Int32,
    _ visibilityProbePointer: UnsafeMutableRawPointer?,
    _ expectedEpoch: UInt64
) -> UnsafeMutableRawPointer? {
    guard NativeState.terrainIcbEnabled, NativeState.terrainGpuEncodeEnabled,
          drawCount > 0, let packedCommands, let packedCandidateIndices,
          let visibilityProbePointer,
          pipeline.supportIndirectCommandBuffers,
          #available(macOS 26.0, iOS 26.0, *),
          device.supportsFamily(.metal4),
          let bridge = metal4RenderBridge(pointer),
          let source = terrainGpuIcbMslSource(primitiveType: primitiveType, indexType: indexType) else {
        return nil
    }
    let retained = Unmanaged<AnyObject>.fromOpaque(visibilityProbePointer).takeUnretainedValue()
    guard let visibilityOwner = retained as? TerrainGpuVisibilityProbeOwner,
          visibilityOwner.epoch == expectedEpoch,
          visibilityOwner.candidateCount > 0 else {
        return nil
    }
    let commandCount = Int(drawCount)
    guard commandCount <= Int.max / 5,
          commandCount <= Int.max / (5 * MemoryLayout<Int32>.stride),
          commandCount <= Int.max / MemoryLayout<Int32>.stride else {
        return nil
    }

    let indexBytes = indexType == .uint16 ? 2 : 4
    for index in 0..<commandCount {
        let base = index * 5
        let indexCount = Int(packedCommands[base])
        let instanceCount = Int(packedCommands[base + 1])
        let firstIndex = Int(packedCommands[base + 2])
        let firstInstance = Int(packedCommands[base + 4])
        let candidate = Int(packedCandidateIndices[index])
        guard indexCount >= 0, instanceCount >= 0,
              firstIndex >= 0, firstInstance >= 0,
              firstIndex <= Int.max / indexBytes,
              candidate >= 0, candidate < visibilityOwner.candidateCount else {
            return nil
        }
    }

    let recordBytes = commandCount * 5 * MemoryLayout<Int32>.stride
    let mappingBytes = commandCount * MemoryLayout<Int32>.stride
    guard let packedBuffer = device.makeBuffer(
        bytes: UnsafeRawPointer(packedCommands), length: recordBytes, options: .storageModeShared
    ), let mappingBuffer = device.makeBuffer(
        bytes: UnsafeRawPointer(packedCandidateIndices), length: mappingBytes, options: .storageModeShared
    ) else {
        return nil
    }

    let descriptor = MTLIndirectCommandBufferDescriptor()
    descriptor.commandTypes = .drawIndexed
    descriptor.inheritPipelineState = true
    descriptor.inheritBuffers = true
    descriptor.maxVertexBufferBindCount = 0
    descriptor.maxFragmentBufferBindCount = 0
    descriptor.inheritDepthStencilState = true
    descriptor.inheritDepthBias = true
    descriptor.inheritDepthClipMode = true
    descriptor.inheritCullMode = true
    descriptor.inheritFrontFacingWinding = true
    descriptor.inheritTriangleFillMode = true
    guard let commandBuffer = device.makeIndirectCommandBuffer(
        descriptor: descriptor, maxCommandCount: commandCount, options: .storageModeShared
    ), let computePipeline = terrainGpuComputePipeline(
        device: device,
        primitiveType: primitiveType,
        indexType: indexType,
        source: source,
        functionName: "metallum_terrain_gpu_encode_visible",
        variant: 1
    ), let computeEncoder = bridge.lease.commandBuffer.makeComputeCommandEncoder() else {
        return nil
    }

    let argumentDescriptor = MTL4ArgumentTableDescriptor()
    argumentDescriptor.maxBufferBindCount = 5
    argumentDescriptor.initializeBindings = true
    argumentDescriptor.supportAttributeStrides = false
    guard let arguments = try? device.makeArgumentTable(descriptor: argumentDescriptor) else {
        computeEncoder.endEncoding()
        return nil
    }
    let argumentEncoder = computePipeline.function.makeArgumentEncoder(bufferIndex: 1)
    guard let argumentBuffer = device.makeBuffer(
        length: argumentEncoder.encodedLength, options: .storageModeShared
    ) else {
        computeEncoder.endEncoding()
        return nil
    }
    argumentEncoder.setArgumentBuffer(argumentBuffer, offset: 0)
    argumentEncoder.setIndirectCommandBuffer(commandBuffer, index: 0)
    arguments.setAddress(packedBuffer.gpuAddress, index: 0)
    arguments.setAddress(argumentBuffer.gpuAddress, index: 1)
    arguments.setAddress(indexBuffer.gpuAddress, index: 2)
    arguments.setAddress(visibilityOwner.visibilityBuffer.gpuAddress, index: 3)
    arguments.setAddress(mappingBuffer.gpuAddress, index: 4)
    computeEncoder.setArgumentTable(arguments)
    computeEncoder.setComputePipelineState(computePipeline.state)
    computeEncoder.resetCommands(buffer: commandBuffer, range: 0..<commandCount)
    // The visibility producer is a previous compute encoder on this same queue.
    // Pair its producer queue barrier with the precise dispatch consumer edge.
    computeEncoder.barrier(
        afterQueueStages: .dispatch,
        beforeStages: .dispatch,
        visibilityOptions: .device
    )
    computeEncoder.dispatchThreads(
        threadsPerGrid: MTLSize(width: commandCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(
            width: max(1, min(computePipeline.state.threadExecutionWidth, 64)),
            height: 1,
            depth: 1
        )
    )
    computeEncoder.barrier(
        afterStages: .dispatch,
        beforeQueueStages: [.vertex, .fragment],
        visibilityOptions: .device
    )
    computeEncoder.endEncoding()

    let owner = TerrainGpuIcbOwner(
        commandBuffer: commandBuffer,
        packedCommands: packedBuffer,
        argumentBuffer: argumentBuffer,
        indexBuffer: indexBuffer,
        pipeline: pipeline,
        candidateIndices: mappingBuffer,
        visibilityOwner: visibilityOwner
    )
    NativeState.terrainIcbEncodedCount &+= 1
    NativeState.terrainIcbGpuEncodedCount &+= 1
    NativeState.terrainIcbGpuDispatchCount &+= 1
    return retainedPointer(owner)
}

'''
insert_before(
    swift,
    '''/// Executes one already encoded terrain ICB. No command records are decoded or\n''',
    visible_native
)

# Source-level guardrails for the exact modernized contract.
test_path = Path("src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java")
test_path.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class Metal4TerrainVisibleIcbContractTest {
    @Test
    void visibleIcbKeepsSourceOrdinalsAndCrossEncoderQueueDependency() throws IOException {
        String source = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        int kernel = source.indexOf("kernel void metallum_terrain_gpu_encode_visible(");
        int nativeEntry = source.indexOf("metallum_MTLDevice_createTerrainVisibleGpuIndexedIcb");
        assertTrue(kernel > 0 && nativeEntry > kernel);
        String visible = source.substring(kernel, source.indexOf("/// Executes one already encoded terrain ICB", nativeEntry));
        assertTrue(visible.contains("candidateBySourceOrdinal[drawIndex]"));
        assertTrue(visible.contains("render_command command(container->commandBuffer, drawIndex)"));
        assertTrue(visible.contains("resetCommands(buffer: commandBuffer, range: 0..<commandCount)"));
        assertTrue(visible.contains("afterQueueStages: .dispatch"));
        assertTrue(visible.contains("beforeStages: .dispatch"));
        assertTrue(visible.contains("visibilityOwner.epoch == expectedEpoch"));
    }

    @Test
    void visibleFeatureRetainsNativeCapabilityGate() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/metallum/client/metal/render/MetalDevice.java"));
        assertTrue(source.contains("TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibilityProbeAvailable()"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibleGpuIcbAvailable()"));
        assertTrue(source.contains("EXPLICIT_GPU_VISIBILITY_PROBE_METAL4 || VISIBLE_GPU_ICB_METAL4"));
    }
}
''')

print("terrain visible GPU ICB modernization patch applied")
