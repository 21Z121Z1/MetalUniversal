from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)


# ---------------- Swift native scene owner + scene probe ----------------
native_path = Path("src/main/native/MetallumNative.swift")
native = native_path.read_text()
native = once(
    native,
    "    static var terrainVisibilityOnlyPipelines: [TerrainVisibilityComputePipelineKey: MTLComputePipelineState] = [:]\n",
    "    static var terrainVisibilityOnlyPipelines: [TerrainVisibilityComputePipelineKey: MTLComputePipelineState] = [:]\n"
    "    // Persistent-scene visibility uses a distinct entry point but the same device-scoped cache key.\n"
    "    static var terrainVisibilityScenePipelines: [TerrainVisibilityComputePipelineKey: MTLComputePipelineState] = [:]\n",
    "scene pipeline cache",
)
scene_structs = '''
    struct TerrainVisibilitySceneCandidate {
      int4 sectionBlock;
      float4 localMinMaxX;
      float4 localMaxYZRange;
    };

    struct TerrainVisibilitySceneFrame {
      float4x4 clipFromCameraRelative;
      int4 cameraBlock;
      float4 cameraFraction;
    };
'''
native = once(
    native,
    '''    struct TerrainVisibilityCompactionParams {
      uint candidateCount;
      uint blockCount;
      uint groupCount;
    };
''',
    scene_structs + '''
    struct TerrainVisibilityCompactionParams {
      uint candidateCount;
      uint blockCount;
      uint groupCount;
    };
''',
    "scene MSL structs",
)
scene_kernel = r'''
    // Shipping visible-ICB kernel for generation-owned static scene records.
    // Large world coordinates remain integer until after camera subtraction;
    // only the small camera-relative delta is converted to float32.
    kernel void metallum_terrain_gpu_visibility_scene(
      device const TerrainVisibilitySceneCandidate *candidates [[buffer(0)]],
      device const TerrainVisibilitySceneFrame *frame [[buffer(1)]],
      device atomic_uint *visibilityWords [[buffer(2)]],
      device atomic_uint *counters [[buffer(3)]],
      constant TerrainVisibilityCompactionParams &params [[buffer(11)]],
      uint candidateIndex [[thread_position_in_grid]]) {
      if (candidateIndex >= params.candidateCount) {
        return;
      }
      TerrainVisibilitySceneCandidate candidate = candidates[candidateIndex];
      int3 blockDelta = candidate.sectionBlock.xyz - frame->cameraBlock.xyz;
      float3 cameraRelativeBase = float3(blockDelta) - frame->cameraFraction.xyz;
      float3 localMin = candidate.localMinMaxX.xyz;
      float3 localMax = float3(candidate.localMinMaxX.w,
                               candidate.localMaxYZRange.x,
                               candidate.localMaxYZRange.y);
      float3 minBounds = cameraRelativeBase + localMin;
      float3 maxBounds = cameraRelativeBase + localMax;
      float range = candidate.localMaxYZRange.z;
      bool uncertain = false;
      bool visible = true;
      if (!all(isfinite(minBounds)) || !all(isfinite(maxBounds)) || !isfinite(range)
          || any(minBounds > maxBounds) || range < 0.0f
          || !all(isfinite(frame->cameraFraction))) {
        uncertain = true;
      } else {
        float3 corners[8] = {
          float3(minBounds.x, minBounds.y, minBounds.z),
          float3(maxBounds.x, minBounds.y, minBounds.z),
          float3(minBounds.x, maxBounds.y, minBounds.z),
          float3(maxBounds.x, maxBounds.y, minBounds.z),
          float3(minBounds.x, minBounds.y, maxBounds.z),
          float3(maxBounds.x, minBounds.y, maxBounds.z),
          float3(minBounds.x, maxBounds.y, maxBounds.z),
          float3(maxBounds.x, maxBounds.y, maxBounds.z)
        };
        float4 clip[8];
        for (uint corner = 0; corner < 8; ++corner) {
          clip[corner] = frame->clipFromCameraRelative * float4(corners[corner], 1.0f);
          if (!all(isfinite(clip[corner])) || clip[corner].w <= 0.0f) {
            uncertain = true;
            break;
          }
        }
        if (!uncertain) {
          bool outsideLeft = true;
          bool outsideRight = true;
          bool outsideBottom = true;
          bool outsideTop = true;
          bool outsideNear = true;
          bool outsideFar = true;
          for (uint corner = 0; corner < 8; ++corner) {
            outsideLeft = outsideLeft && clip[corner].x < -clip[corner].w;
            outsideRight = outsideRight && clip[corner].x > clip[corner].w;
            outsideBottom = outsideBottom && clip[corner].y < -clip[corner].w;
            outsideTop = outsideTop && clip[corner].y > clip[corner].w;
            outsideNear = outsideNear && clip[corner].z < -clip[corner].w;
            outsideFar = outsideFar && clip[corner].z > clip[corner].w;
          }
          visible = !(outsideLeft || outsideRight || outsideBottom
                      || outsideTop || outsideNear || outsideFar);
        }
      }
      if (uncertain) {
        atomic_fetch_add_explicit(&counters[1], 1u, memory_order_relaxed);
      }
      if (visible || uncertain) {
        uint word = candidateIndex >> 5;
        uint bit = candidateIndex & 31u;
        atomic_fetch_or_explicit(&visibilityWords[word], 1u << bit, memory_order_relaxed);
        atomic_fetch_add_explicit(&counters[0], 1u, memory_order_relaxed);
      }
    }

'''
native = once(
    native,
    "    // One 256-thread group computes an exclusive local rank for each\n",
    scene_kernel + "    // One 256-thread group computes an exclusive local rank for each\n",
    "scene MSL kernel",
)

owner_anchor = '''@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilityProbeOwner {
'''
scene_helpers = '''@available(macOS 26.0, iOS 26.0, *)
private func terrainVisibilityScenePipeline(
    device: MTLDevice,
    source: String
) -> MTLComputePipelineState? {
    let key = TerrainVisibilityComputePipelineKey(deviceAddress: objectAddress(device))
    NativeState.terrainVisibilityPipelineLock.lock()
    defer { NativeState.terrainVisibilityPipelineLock.unlock() }
    if let cached = NativeState.terrainVisibilityScenePipelines[key] {
        return cached
    }
    let pipeline: MTLComputePipelineState? = NativeState.onCompilerThread {
        do {
            let library = try device.makeLibrary(source: source, options: nil)
            guard let visibility = library.makeFunction(name: "metallum_terrain_gpu_visibility_scene") else {
                return nil
            }
            return try device.makeComputePipelineState(function: visibility)
        } catch {
            NSLog("[metallum] persistent terrain visibility pipeline failed: %@", String(describing: error))
            return nil
        }
    }
    if let pipeline {
        NativeState.terrainVisibilityScenePipelines[key] = pipeline
    }
    return pipeline
}

@available(macOS 26.0, iOS 26.0, *)
private final class TerrainGpuVisibilitySceneOwner {
    let sceneGeneration: UInt64
    let candidateCount: Int
    let candidateBuffer: MTLBuffer

    init(sceneGeneration: UInt64, candidateCount: Int, candidateBuffer: MTLBuffer) {
        self.sceneGeneration = sceneGeneration
        self.candidateCount = candidateCount
        self.candidateBuffer = candidateBuffer
        residencyTrackCreated(candidateBuffer)
    }

    deinit {
        residencyTrackReleased(rawPointer(candidateBuffer))
    }
}

'''
native = once(native, owner_anchor, scene_helpers + owner_anchor, "scene owner helpers")
native = once(
    native,
    "    let candidateBuffer: MTLBuffer\n    let matrixBuffer: MTLBuffer\n",
    "    let candidateBuffer: MTLBuffer\n"
    "    // A persistent scene owner keeps the shared candidate allocation live across epochs.\n"
    "    let sceneOwner: AnyObject?\n"
    "    let ownsCandidateBuffer: Bool\n"
    "    let matrixBuffer: MTLBuffer\n",
    "probe owner scene fields",
)
native = once(
    native,
    "        candidateBuffer: MTLBuffer,\n        matrixBuffer: MTLBuffer,\n",
    "        candidateBuffer: MTLBuffer,\n"
    "        sceneOwner: AnyObject? = nil,\n"
    "        ownsCandidateBuffer: Bool = true,\n"
    "        matrixBuffer: MTLBuffer,\n",
    "probe owner scene init args",
)
native = once(
    native,
    "        self.candidateBuffer = candidateBuffer\n        self.matrixBuffer = matrixBuffer\n",
    "        self.candidateBuffer = candidateBuffer\n"
    "        self.sceneOwner = sceneOwner\n"
    "        self.ownsCandidateBuffer = ownsCandidateBuffer\n"
    "        self.matrixBuffer = matrixBuffer\n",
    "probe owner scene init assignment",
)
native = once(
    native,
    "        residencyTrackCreated(candidateBuffer)\n        residencyTrackCreated(matrixBuffer)\n",
    "        if ownsCandidateBuffer { residencyTrackCreated(candidateBuffer) }\n"
    "        residencyTrackCreated(matrixBuffer)\n",
    "probe owner candidate residency",
)
native = once(
    native,
    "        residencyTrackReleased(rawPointer(candidateBuffer))\n        residencyTrackReleased(rawPointer(matrixBuffer))\n",
    "        if ownsCandidateBuffer { residencyTrackReleased(rawPointer(candidateBuffer)) }\n"
    "        residencyTrackReleased(rawPointer(matrixBuffer))\n",
    "probe owner candidate release",
)

probe_anchor = '''/// Dispatches the value-only visibility probe into the current Metal 4 main
/// command buffer.  The returned owner retains all shared buffers until the
/// command-buffer completion callback has made readback safe.
'''
scene_exports = r'''/// Creates one camera-independent terrain visibility scene. Java replaces this
/// owner only when its exact mesh/candidate scene generation changes.
@available(macOS 26.0, iOS 26.0, *)
@_cdecl("metallum_MTLDevice_createTerrainGpuVisibilityScene")
public func metallum_MTLDevice_createTerrainGpuVisibilityScene(
    _ device: MTLDevice,
    _ packedCandidates: UnsafePointer<UInt8>?,
    _ candidateCount: Int32,
    _ sceneGeneration: UInt64
) -> UnsafeMutableRawPointer? {
    guard candidateCount > 0, let packedCandidates,
          device.supportsFamily(.metal4),
          candidateCount <= Int32(terrainVisibilityMaxCandidates) else {
        return nil
    }
    let count = Int(candidateCount)
    guard count <= Int.max / 48 else { return nil }
    let words = UnsafeRawPointer(packedCandidates).assumingMemoryBound(to: UInt32.self)
    for index in 0..<count {
        let base = index * 12
        let minX = Float(bitPattern: words[base + 4])
        let minY = Float(bitPattern: words[base + 5])
        let minZ = Float(bitPattern: words[base + 6])
        let maxX = Float(bitPattern: words[base + 7])
        let maxY = Float(bitPattern: words[base + 8])
        let maxZ = Float(bitPattern: words[base + 9])
        let range = Float(bitPattern: words[base + 10])
        guard minX.isFinite, minY.isFinite, minZ.isFinite,
              maxX.isFinite, maxY.isFinite, maxZ.isFinite, range.isFinite,
              minX <= maxX, minY <= maxY, minZ <= maxZ, range >= 0 else {
            return nil
        }
    }
    guard let buffer = device.makeBuffer(
        bytes: UnsafeRawPointer(packedCandidates),
        length: count * 48,
        options: .storageModeShared
    ) else { return nil }
    buffer.label = "Metallum Persistent Terrain Visibility Scene"
    return retainedPointer(TerrainGpuVisibilitySceneOwner(
        sceneGeneration: sceneGeneration,
        candidateCount: count,
        candidateBuffer: buffer
    ))
}

/// Dispatches visibility against a generation-owned scene. Only the 96-byte
/// frame block and per-frame bitset/counters are allocated for each camera epoch.
@available(macOS 26.0, iOS 26.0, *)
@_cdecl("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe")
public func metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe(
    _ pointer: UnsafeMutableRawPointer,
    _ device: MTLDevice,
    _ scenePointer: UnsafeMutableRawPointer?,
    _ packedFrame: UnsafePointer<UInt8>?,
    _ expectedSceneGeneration: UInt64,
    _ epoch: UInt64
) -> UnsafeMutableRawPointer? {
    guard let scenePointer, let packedFrame,
          let bridge = metal4RenderBridge(pointer),
          device.supportsFamily(.metal4) else { return nil }
    let object = Unmanaged<AnyObject>.fromOpaque(scenePointer).takeUnretainedValue()
    guard let scene = object as? TerrainGpuVisibilitySceneOwner,
          scene.sceneGeneration == expectedSceneGeneration,
          scene.candidateCount > 0 else { return nil }
    let count = scene.candidateCount
    let frameWords = UnsafeRawPointer(packedFrame).assumingMemoryBound(to: UInt32.self)
    for index in 0..<16 {
        guard Float(bitPattern: frameWords[index]).isFinite else { return nil }
    }
    for index in 20..<23 {
        let value = Float(bitPattern: frameWords[index])
        guard value.isFinite, value >= 0.0, value <= 1.0 else { return nil }
    }
    guard count <= Int.max - 31 else { return nil }
    let wordCount = (count + 31) / 32
    let blockWidth = 256
    guard count <= Int.max - (blockWidth - 1) else { return nil }
    let blockCount = (count + blockWidth - 1) / blockWidth
    let groupCount = max(1, (blockCount + blockWidth - 1) / blockWidth)
    guard blockCount > 0,
          wordCount <= Int.max / MemoryLayout<UInt32>.stride else { return nil }

    guard let frameBuffer = device.makeBuffer(
        bytes: UnsafeRawPointer(packedFrame), length: 96, options: .storageModeShared
    ), let visibilityBuffer = device.makeBuffer(
        length: wordCount * MemoryLayout<UInt32>.stride, options: .storageModeShared
    ), let countersBuffer = device.makeBuffer(
        length: 2 * MemoryLayout<UInt32>.stride, options: .storageModeShared
    ), let prefixLocalBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let blockSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let blockOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let groupSumsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let groupOffsetsBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let compactedIndicesBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let compactedCountBuffer = device.makeBuffer(length: 4, options: .storageModeShared),
       let paramsBuffer = device.makeBuffer(length: 12, options: .storageModeShared) else {
        return nil
    }
    visibilityBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: wordCount)
    countersBuffer.contents().assumingMemoryBound(to: UInt32.self)
        .initialize(repeating: 0, count: 2)
    let params = paramsBuffer.contents().assumingMemoryBound(to: UInt32.self)
    params[0] = UInt32(count)
    params[1] = UInt32(blockCount)
    params[2] = UInt32(groupCount)

    let source = terrainVisibilityMslSource()
    guard let pipeline = terrainVisibilityScenePipeline(device: device, source: source),
          pipeline.maxTotalThreadsPerThreadgroup >= blockWidth,
          let computeEncoder = bridge.lease.commandBuffer.makeComputeCommandEncoder() else {
        return nil
    }
    let descriptor = MTL4ArgumentTableDescriptor()
    descriptor.maxBufferBindCount = 12
    descriptor.initializeBindings = true
    descriptor.supportAttributeStrides = false
    guard let arguments = try? device.makeArgumentTable(descriptor: descriptor) else {
        computeEncoder.endEncoding()
        return nil
    }
    arguments.setAddress(scene.candidateBuffer.gpuAddress, index: 0)
    arguments.setAddress(frameBuffer.gpuAddress, index: 1)
    arguments.setAddress(visibilityBuffer.gpuAddress, index: 2)
    arguments.setAddress(countersBuffer.gpuAddress, index: 3)
    arguments.setAddress(prefixLocalBuffer.gpuAddress, index: 4)
    arguments.setAddress(blockSumsBuffer.gpuAddress, index: 5)
    arguments.setAddress(blockOffsetsBuffer.gpuAddress, index: 6)
    arguments.setAddress(groupSumsBuffer.gpuAddress, index: 7)
    arguments.setAddress(groupOffsetsBuffer.gpuAddress, index: 8)
    arguments.setAddress(compactedIndicesBuffer.gpuAddress, index: 9)
    arguments.setAddress(compactedCountBuffer.gpuAddress, index: 10)
    arguments.setAddress(paramsBuffer.gpuAddress, index: 11)
    computeEncoder.setArgumentTable(arguments)
    computeEncoder.setComputePipelineState(pipeline)
    computeEncoder.dispatchThreadgroups(
        threadgroupsPerGrid: MTLSize(width: blockCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: blockWidth, height: 1, depth: 1)
    )
    computeEncoder.barrier(
        afterStages: .dispatch,
        beforeQueueStages: [.vertex, .fragment, .dispatch],
        visibilityOptions: .device
    )
    computeEncoder.endEncoding()

    let owner = TerrainGpuVisibilityProbeOwner(
        leaseIdentity: ObjectIdentifier(bridge.lease),
        epoch: epoch,
        candidateCount: count,
        compactionEnabled: false,
        wordCount: wordCount,
        candidateBuffer: scene.candidateBuffer,
        sceneOwner: scene,
        ownsCandidateBuffer: false,
        matrixBuffer: frameBuffer,
        visibilityBuffer: visibilityBuffer,
        countersBuffer: countersBuffer,
        prefixLocalBuffer: prefixLocalBuffer,
        blockSumsBuffer: blockSumsBuffer,
        blockOffsetsBuffer: blockOffsetsBuffer,
        groupSumsBuffer: groupSumsBuffer,
        groupOffsetsBuffer: groupOffsetsBuffer,
        compactedIndicesBuffer: compactedIndicesBuffer,
        compactedCountBuffer: compactedCountBuffer,
        paramsBuffer: paramsBuffer,
        blockCount: blockCount,
        groupCount: groupCount
    )
    bridge.lease.addCompletionHandler { [owner] error, _, _ in
        owner.complete(error: error)
    }
    return retainedPointer(owner)
}

'''
native = once(native, probe_anchor, scene_exports + probe_anchor, "scene native exports")
native_path.write_text(native)

# ---------------- Java bridge optional ABI ----------------
bridge_path = Path("src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
bridge = bridge_path.read_text()
bridge = once(
    bridge,
    "    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilityProbe;\n",
    "    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilityScene;\n"
    "    @Nullable\n    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilitySceneProbe;\n"
    "    @Nullable\n    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilityProbe;\n",
    "scene bridge handles",
)
lookup_anchor = "            MTLDeviceCreateTerrainGpuVisibilityProbe = optionalDowncallWithoutCritical(\n"
lookup_insert = '''            MTLDeviceCreateTerrainGpuVisibilityScene = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_createTerrainGpuVisibilityScene",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG
                    )
            );
            MTLDeviceCreateTerrainGpuVisibilitySceneProbe = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, LONG, LONG
                    )
            );
'''
bridge = once(bridge, lookup_anchor, lookup_insert + lookup_anchor, "scene bridge lookup")
wrapper_anchor = '''    public static MemorySegment MTLDevice_createTerrainGpuVisibilityProbe(
'''
wrappers = '''    public static MemorySegment MTLDevice_createTerrainGpuVisibilityScene(
            final MemorySegment device,
            final MemorySegment packedCandidates,
            final int candidateCount,
            final long sceneGeneration
    ) {
        if (MTLDeviceCreateTerrainGpuVisibilityScene == null || candidateCount <= 0 || sceneGeneration < 0L) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) MTLDeviceCreateTerrainGpuVisibilityScene.invokeExact(
                    segment(device), segment(packedCandidates), candidateCount, sceneGeneration
            );
        } catch (Throwable throwable) {
            return MemorySegment.NULL;
        }
    }

    public static MemorySegment MTLDevice_createTerrainGpuVisibilitySceneProbe(
            final MemorySegment renderEncoder,
            final MemorySegment device,
            final MemorySegment scene,
            final MemorySegment packedFrame,
            final long expectedSceneGeneration,
            final long epoch
    ) {
        if (MTLDeviceCreateTerrainGpuVisibilitySceneProbe == null
                || isNullHandle(scene) || expectedSceneGeneration < 0L || epoch < 0L) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) MTLDeviceCreateTerrainGpuVisibilitySceneProbe.invokeExact(
                    segment(renderEncoder), segment(device), segment(scene), segment(packedFrame),
                    expectedSceneGeneration, epoch
            );
        } catch (Throwable throwable) {
            return MemorySegment.NULL;
        }
    }

    public static boolean terrainPersistentVisibilitySceneAvailable() {
        return MTLDeviceCreateTerrainGpuVisibilityScene != null
                && MTLDeviceCreateTerrainGpuVisibilitySceneProbe != null
                && TerrainVisibilityProbeStatus != null;
    }

'''
bridge = once(bridge, wrapper_anchor, wrappers + wrapper_anchor, "scene bridge wrappers")
bridge_path.write_text(bridge)

# ---------------- Java runtime scene reuse ----------------
probe_path = Path("src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java")
probe = probe_path.read_text()
probe = once(
    probe,
    "    private static long pendingBytes;\n",
    "    private static long pendingBytes;\n"
    "    /** Java owns one retain on the latest native generation-owned static scene. */\n"
    "    private static MemorySegment persistentSceneOwner = MemorySegment.NULL;\n"
    "    private static long persistentSceneGeneration = -1L;\n"
    "    private static int persistentSceneCandidateCount;\n",
    "runtime scene fields",
)
probe = once(
    probe,
    "            PENDING.clear();\n            pendingBytes = 0L;\n",
    "            PENDING.clear();\n"
    "            if (!MetalNativeBridge.isNullHandle(persistentSceneOwner)) {\n"
    "                releaseQuietly(persistentSceneOwner);\n"
    "            }\n"
    "            persistentSceneOwner = MemorySegment.NULL;\n"
    "            persistentSceneGeneration = -1L;\n"
    "            persistentSceneCandidateCount = 0;\n"
    "            pendingBytes = 0L;\n",
    "runtime scene reset",
)
old_dispatch = '''                    MemorySegment packedCandidates;
                    MemorySegment packedMatrix;
                    Oracle oracle = null;
                    try {
                        packedCandidates = snapshot.packGpuVisibilityCandidates(arena);
                        packedMatrix = snapshot.packGpuVisibilityMatrix(arena);
                        if (ORACLE_ENABLED) {
                            oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);
                        }
                    } catch (RuntimeException invalidInput) {
                        fallbackCount++;
                        if (ORACLE_ENABLED) { compactionFallbackCount++; }
                        return true;
                    }
                    MemorySegment probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                            retainedEncoder,
                            device.metalDeviceHandle(),
                            packedCandidates,
                            packedMatrix,
                            count,
                            snapshot.epoch()
                    );
'''
new_dispatch = '''                    Oracle oracle = null;
                    MemorySegment probe;
                    try {
                        if (!ORACLE_ENABLED && MetalNativeBridge.terrainPersistentVisibilitySceneAvailable()) {
                            if (!ensurePersistentSceneLocked(snapshot, device, arena)) {
                                fallbackCount++;
                                return true;
                            }
                            MemorySegment packedFrame = snapshot.packGpuVisibilitySceneFrame(arena);
                            probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilitySceneProbe(
                                    retainedEncoder,
                                    device.metalDeviceHandle(),
                                    persistentSceneOwner,
                                    packedFrame,
                                    snapshot.sceneGeneration(),
                                    snapshot.epoch()
                            );
                        } else {
                            MemorySegment packedCandidates = snapshot.packGpuVisibilityCandidates(arena);
                            MemorySegment packedMatrix = snapshot.packGpuVisibilityMatrix(arena);
                            if (ORACLE_ENABLED) {
                                oracle = oracleForPackedSnapshot(packedCandidates, packedMatrix, count);
                            }
                            probe = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityProbe(
                                    retainedEncoder,
                                    device.metalDeviceHandle(),
                                    packedCandidates,
                                    packedMatrix,
                                    count,
                                    snapshot.epoch()
                            );
                        }
                    } catch (RuntimeException invalidInput) {
                        fallbackCount++;
                        if (ORACLE_ENABLED) { compactionFallbackCount++; }
                        return true;
                    }
'''
probe = once(probe, old_dispatch, new_dispatch, "runtime persistent scene dispatch")
helper_anchor = '''    static TerrainCandidateRegistry.VisibilityResult latestResult() {
'''
helper = '''    private static boolean ensurePersistentSceneLocked(
            final TerrainCandidateSnapshot snapshot,
            final MetalDevice device,
            final Arena arena
    ) {
        int count = snapshot.candidates().size();
        if (!MetalNativeBridge.isNullHandle(persistentSceneOwner)
                && persistentSceneGeneration == snapshot.sceneGeneration()
                && persistentSceneCandidateCount == count) {
            return true;
        }
        MemorySegment packedScene = snapshot.packGpuVisibilitySceneCandidates(arena);
        MemorySegment replacement = MetalNativeBridge.MTLDevice_createTerrainGpuVisibilityScene(
                device.metalDeviceHandle(),
                packedScene,
                count,
                snapshot.sceneGeneration()
        );
        if (MetalNativeBridge.isNullHandle(replacement)) {
            return false;
        }
        MemorySegment previous = persistentSceneOwner;
        persistentSceneOwner = replacement;
        persistentSceneGeneration = snapshot.sceneGeneration();
        persistentSceneCandidateCount = count;
        if (!MetalNativeBridge.isNullHandle(previous)) {
            releaseQuietly(previous);
        }
        return true;
    }

'''
probe = once(probe, helper_anchor, helper + helper_anchor, "persistent scene ensure helper")
probe_path.write_text(probe)

# ---------------- Contracts ----------------
contract_path = Path("src/test/java/com/metallum/client/metal/render/TerrainPersistentGpuSceneNativeContractTest.java")
contract_path.write_text(r'''package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerrainPersistentGpuSceneNativeContractTest {
    @Test
    void shippingVisibilityReusesGenerationOwnedStaticScene() throws IOException {
        String nativeSource = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"));
        String probe = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java"));

        assertTrue(nativeSource.contains("final class TerrainGpuVisibilitySceneOwner"));
        assertTrue(nativeSource.contains("metallum_MTLDevice_createTerrainGpuVisibilityScene"));
        assertTrue(nativeSource.contains("metallum_MTLDevice_createTerrainGpuVisibilitySceneProbe"));
        assertTrue(nativeSource.contains("int3 blockDelta = candidate.sectionBlock.xyz - frame->cameraBlock.xyz"));
        assertTrue(nativeSource.contains("sceneOwner: scene"));
        assertTrue(nativeSource.contains("ownsCandidateBuffer: false"));
        assertTrue(bridge.contains("terrainPersistentVisibilitySceneAvailable"));
        assertTrue(probe.contains("persistentSceneGeneration == snapshot.sceneGeneration()"));
        assertTrue(probe.contains("snapshot.packGpuVisibilitySceneFrame(arena)"));
        assertTrue(probe.contains("snapshot.packGpuVisibilitySceneCandidates(arena)"));
    }
}
''')

print("persistent terrain GPU scene native ownership staged")
