from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}\n--- anchor ---\n{old}")
    p.write_text(text.replace(old, new, 1))


swift = "src/main/native/MetallumNative.swift"
replace_once(
    swift,
    '''    func complete(error: Error?) {
        lock.lock()
        completed = true
        succeeded = error == nil
        lock.unlock()
    }

    /// Returns 0 while the command buffer is in flight, 1 for a copied result,
''',
    '''    func complete(error: Error?) {
        lock.lock()
        completed = true
        succeeded = error == nil
        lock.unlock()
    }

    /// Completion-only query for the shipping visible-ICB lane. It deliberately
    /// exposes no GPU-authored visibility bytes to the CPU.
    func status() -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        guard completed else { return 0 }
        return succeeded ? 1 : -1
    }

    /// Returns 0 while the command buffer is in flight, 1 for a copied result,
''',
)
replace_once(
    swift,
    '''/// Non-blocking completion/readback for a decision-only terrain visibility
/// probe.  The caller owns the returned probe pointer and must release it
/// after a successful or failed poll.
@_cdecl("metallum_terrain_visibility_probe_poll_v2")
''',
    '''/// Completion-only query for a terrain visibility owner. Visible ICB
/// submission uses this instead of allocating CPU readback buffers after GPU
/// completion; the explicit diagnostic probe keeps using poll_v2 below.
@_cdecl("metallum_terrain_visibility_probe_status")
public func metallum_terrain_visibility_probe_status(
    _ pointer: UnsafeMutableRawPointer
) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *) else { return -1 }
    let object = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
    guard let owner = object as? TerrainGpuVisibilityProbeOwner else { return -1 }
    return owner.status()
}

/// Non-blocking completion/readback for a decision-only terrain visibility
/// probe.  The caller owns the returned probe pointer and must release it
/// after a successful or failed poll.
@_cdecl("metallum_terrain_visibility_probe_poll_v2")
''',
)

bridge = "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
replace_once(
    bridge,
    '''    @Nullable
    private static final MethodHandle TerrainVisibilityProbePoll;
    @Nullable
    private static final MethodHandle MTLRenderCommandEncoderExecuteTerrainIcb;
''',
    '''    @Nullable
    private static final MethodHandle TerrainVisibilityProbePoll;
    @Nullable
    private static final MethodHandle TerrainVisibilityProbeStatus;
    @Nullable
    private static final MethodHandle MTLRenderCommandEncoderExecuteTerrainIcb;
''',
)
replace_once(
    bridge,
    '''            TerrainVisibilityProbePoll = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_terrain_visibility_probe_poll_v2",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT
                    )
            );
            MTLRenderCommandEncoderExecuteTerrainIcb = optionalDowncallWithoutCritical(
''',
    '''            TerrainVisibilityProbePoll = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_terrain_visibility_probe_poll_v2",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT
                    )
            );
            TerrainVisibilityProbeStatus = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_terrain_visibility_probe_status",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            MTLRenderCommandEncoderExecuteTerrainIcb = optionalDowncallWithoutCritical(
''',
)
replace_once(
    bridge,
    '''    public static boolean terrainVisibilityProbeAvailable() {
        return MTLDeviceCreateTerrainGpuVisibilityProbe != null
                && TerrainVisibilityProbePoll != null;
    }

    /** Non-blocking completion/readback poll for one terrain visibility probe. */
''',
    '''    public static boolean terrainVisibilityProbeAvailable() {
        return MTLDeviceCreateTerrainGpuVisibilityProbe != null
                && TerrainVisibilityProbePoll != null;
    }

    /** True when visible-only terrain completion can avoid GPU-result readback. */
    public static boolean terrainVisibilityProbeStatusAvailable() {
        return TerrainVisibilityProbeStatus != null;
    }

    /** Non-blocking completion-only query; returns 0 in-flight, 1 success, -1 failure. */
    public static int terrainVisibilityProbeStatus(final MemorySegment probe) {
        if (TerrainVisibilityProbeStatus == null || isNullHandle(probe)) {
            return -1;
        }
        try {
            return (int) TerrainVisibilityProbeStatus.invokeExact(segment(probe));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Non-blocking completion/readback poll for one terrain visibility probe. */
''',
)

metal_device = "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
replace_once(
    metal_device,
    '''    private static final boolean VISIBLE_GPU_ICB_METAL4 =
            TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED
                    && MetalNativeBridge.terrainVisibilityProbeAvailable()
                    && MetalNativeBridge.terrainVisibleGpuIcbAvailable();
''',
    '''    private static final boolean VISIBLE_GPU_ICB_METAL4 =
            TerrainCandidateSnapshot.VISIBLE_GPU_ICB_ENABLED
                    && MetalNativeBridge.terrainVisibilityProbeAvailable()
                    && MetalNativeBridge.terrainVisibilityProbeStatusAvailable()
                    && MetalNativeBridge.terrainVisibleGpuIcbAvailable();
''',
)

probe = "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"
replace_once(
    probe,
    '''        for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            boolean remove = false;
            try (Arena arena = Arena.ofConfined()) {
''',
    '''        for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            // The visible-ICB lane needs this owner only until native ICB
            // authoring retains it. After GPU completion, query one status word
            // and release the Java retain: do not allocate an Arena or copy the
            // visibility bitset/compacted list back to the CPU.
            if (!pending.oracleEnabled()) {
                int status = MetalNativeBridge.terrainVisibilityProbeStatus(pending.probe());
                if (status == 0) {
                    continue;
                }
                if (status < 0) {
                    fallbackCount++;
                }
                lastCompletedEpoch = Math.max(lastCompletedEpoch, pending.epoch());
                pendingBytes = Math.max(0L, pendingBytes - pendingAllocationBytes(pending.candidateCount()));
                releaseQuietly(pending.probe());
                iterator.remove();
                continue;
            }
            boolean remove = false;
            try (Arena arena = Arena.ofConfined()) {
''',
)

test = "src/test/java/com/metallum/client/metal/render/Metal4TerrainVisibleIcbContractTest.java"
replace_once(
    test,
    '''        assertTrue(source.contains("MetalNativeBridge.terrainVisibilityProbeAvailable()"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibleGpuIcbAvailable()"));
''',
    '''        assertTrue(source.contains("MetalNativeBridge.terrainVisibilityProbeAvailable()"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibilityProbeStatusAvailable()"));
        assertTrue(source.contains("MetalNativeBridge.terrainVisibleGpuIcbAvailable()"));
''',
)
replace_once(
    test,
    '''    @Test
    void visibleFeatureRetainsNativeCapabilityGate() throws IOException {
''',
    '''    @Test
    void visibleOnlyCompletionAvoidsCpuVisibilityReadback() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"
        ));
        int loop = source.indexOf("for (Iterator<Pending> iterator = PENDING.iterator()");
        int completionOnly = source.indexOf("if (!pending.oracleEnabled())", loop);
        int status = source.indexOf("terrainVisibilityProbeStatus(pending.probe())", completionOnly);
        int readbackArena = source.indexOf("try (Arena arena = Arena.ofConfined())", completionOnly);
        assertTrue(loop > 0 && completionOnly > loop && status > completionOnly);
        assertTrue(readbackArena > status);
        String noReadback = source.substring(completionOnly, readbackArena);
        assertTrue(!noReadback.contains("terrainVisibilityProbePoll("));
        assertTrue(!noReadback.contains("actualWords"));
        assertTrue(!noReadback.contains("actualCompacted"));
    }

    @Test
    void visibleFeatureRetainsNativeCapabilityGate() throws IOException {
''',
)

print("terrain visible no-readback patch applied")
