from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def patch(path: str, fn) -> None:
    p = Path(path)
    old = p.read_text()
    new = fn(old)
    if new == old:
        raise SystemExit(f"{path}: patch produced no change")
    p.write_text(new)


def patch_candidate(text: str) -> str:
    old = '''    public static final boolean VISIBLE_GPU_ICB_ENABLED = Boolean.parseBoolean(
            System.getProperty(VISIBLE_GPU_ICB_PROPERTY, "false")
    );
'''
    new = old + '''    /** Fuses persistent-scene visibility and sparse ICB authoring into one compute pass. */
    public static final String FUSED_VISIBLE_GPU_ICB_PROPERTY =
            "metallum.opt.terrainFusedVisibleIcb";
    public static final boolean FUSED_VISIBLE_GPU_ICB_ENABLED = VISIBLE_GPU_ICB_ENABLED
            && Boolean.parseBoolean(System.getProperty(FUSED_VISIBLE_GPU_ICB_PROPERTY, "false"));
'''
    return replace_once(text, old, new, "candidate fused flag")


def patch_probe(text: str) -> str:
    old = '''    static boolean beforeTerrainDraw(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder
    ) {
        if (!ENABLED) {
            return false;
        }
'''
    new = '''    static boolean beforeTerrainDraw(
            final MetalDevice device,
            final MetalCommandEncoder commandEncoder
    ) {
        // The fused shipping lane performs visibility inside ICB authoring and
        // therefore must not create an intermediate bitset/probe encoder. Keep
        // the explicit diagnostic oracle untouched so it can still falsify the
        // fused decision on hardware when both switches are enabled.
        if (!ENABLED || (TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED && !ORACLE_ENABLED)) {
            return false;
        }
'''
    text = replace_once(text, old, new, "probe fused bypass")
    anchor = '''    private static boolean ensurePersistentSceneLocked(
            final TerrainCandidateSnapshot snapshot,
            final MetalDevice device,
            final Arena arena
    ) {
'''
    addition = '''    /**
     * Borrows the exact generation-owned scene for fused ICB authoring.
     * The returned pointer remains Java-retained by this runtime; the native
     * ICB owner takes a strong reference to the scene before this call returns.
     */
    static MemorySegment persistentSceneForFused(
            final TerrainCandidateSnapshot snapshot,
            final MetalDevice device
    ) {
        if (!TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED
                || snapshot == null || device == null
                || !MetalNativeBridge.terrainFusedVisibleGpuIcbAvailable()) {
            return MemorySegment.NULL;
        }
        synchronized (LOCK) {
            try (Arena arena = Arena.ofConfined()) {
                if (!ensurePersistentSceneLocked(snapshot, device, arena)) {
                    return MemorySegment.NULL;
                }
                return persistentSceneGeneration == snapshot.sceneGeneration()
                        && persistentSceneCandidateCount == snapshot.candidates().size()
                        ? persistentSceneOwner : MemorySegment.NULL;
            } catch (RuntimeException failure) {
                return MemorySegment.NULL;
            }
        }
    }

'''
    return replace_once(text, anchor, addition + anchor, "probe scene borrow")


def patch_icb_owner(text: str) -> str:
    anchor = '''    void invalidateVisibilityAuthored() {
'''
    method = '''    boolean encodeFusedVisibleGpu(
            final MetalDevice currentDevice,
            final MemorySegment previousEncoder,
            final MTLPrimitiveType currentPrimitiveType,
            final MTLIndexType indexType,
            final MemorySegment indexBuffer,
            final MemorySegment pipeline,
            final TerrainSceneSnapshot snapshot,
            final TerrainCandidateSnapshot candidates,
            final TerrainVisibleDrawPlan plan,
            final MemorySegment persistentSceneOwner,
            final int drawCount
    ) {
        if (closed || currentDevice == null || previousEncoder == null || snapshot == null
                || candidates == null || plan == null || drawCount <= 0
                || drawCount != snapshot.draws().size() || drawCount != plan.drawCount()
                || plan.candidateCount() != candidates.candidates().size()
                || plan.candidateEpoch() != candidates.epoch()
                || indexBuffer == null || pipeline == null
                || MetalNativeBridge.isNullHandle(indexBuffer)
                || MetalNativeBridge.isNullHandle(pipeline)
                || MetalNativeBridge.isNullHandle(persistentSceneOwner)
                || !MetalNativeBridge.terrainFusedVisibleGpuIcbAvailable()) {
            return false;
        }
        if (device != null && device != currentDevice) {
            retire();
            content = null;
        }
        device = currentDevice;
        // Like the two-stage visible path, fused visibility is camera/epoch
        // state and can never be reused through the immutable content key.
        retire();
        content = null;
        primitiveType = currentPrimitiveType;
        gpuAuthored = false;
        visibilityEpoch = -1L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packed = snapshot.packIndexedCommands(arena);
            MemorySegment mapping = plan.packCandidateIndices(arena);
            MemorySegment frame = candidates.packGpuVisibilitySceneFrame(arena);
            indirectCommandBuffer = MetalNativeBridge.MTLDevice_createTerrainFusedVisibleGpuIndexedIcb(
                    previousEncoder,
                    currentDevice.metalDeviceHandle(),
                    currentPrimitiveType.value,
                    indexType.value,
                    indexBuffer,
                    pipeline,
                    packed,
                    mapping,
                    drawCount,
                    persistentSceneOwner,
                    frame,
                    candidates.sceneGeneration(),
                    plan.candidateCount()
            );
        } catch (RuntimeException exception) {
            indirectCommandBuffer = MemorySegment.NULL;
            return false;
        }
        if (MetalNativeBridge.isNullHandle(indirectCommandBuffer)) {
            return false;
        }
        content = snapshot.icbContent();
        gpuAuthored = true;
        visibilityEpoch = plan.candidateEpoch();
        return true;
    }

'''
    return replace_once(text, anchor, method + anchor, "fused owner method")


def patch_render_pass(text: str) -> str:
    anchor = '''                if (visiblePlan != null) {
                    MemorySegment visibilityOwner = TerrainGpuVisibilityProbe.ownerForEpoch(
'''
    replacement = '''                if (visiblePlan != null) {
                    // Fast shipping lane: evaluate the persistent scene and
                    // author source-ordinal ICB slots in the same compute pass.
                    // This removes the intermediate visibility bitset and one
                    // render->compute->render transition. Failure is non-terminal.
                    if (TerrainCandidateSnapshot.FUSED_VISIBLE_GPU_ICB_ENABLED) {
                        MemorySegment sceneOwner = TerrainGpuVisibilityProbe.persistentSceneForFused(
                                candidates, device
                        );
                        if (!MetalNativeBridge.isNullHandle(sceneOwner)) {
                            MemorySegment retainedEncoder = commandEncoder.endEncoderForTerrainGpuAuthoring();
                            try {
                                if (!MetalNativeBridge.isNullHandle(retainedEncoder)
                                        && owner.encodeFusedVisibleGpu(
                                        device,
                                        retainedEncoder,
                                        primitiveType,
                                        indexType,
                                        indexHandle,
                                        pipelineHandle,
                                        snapshot,
                                        candidates,
                                        visiblePlan,
                                        sceneOwner,
                                        drawCount
                                )) {
                                    MTLRenderCommandEncoder reopened = renderEncoder();
                                    bindDrawState(reopened);
                                    if (owner.execute(
                                            device,
                                            reopened,
                                            primitiveType,
                                            indexType,
                                            indexHandle,
                                            pipelineHandle,
                                            snapshot,
                                            drawCount
                                    )) {
                                        return true;
                                    }
                                }
                            } finally {
                                if (!MetalNativeBridge.isNullHandle(retainedEncoder)) {
                                    MetalNativeBridge.metallum_release_object(retainedEncoder);
                                }
                            }
                            owner.invalidateVisibilityAuthored();
                            enc = renderEncoder();
                            bindDrawState(enc);
                        }
                    }

                    // Diagnostic/two-stage lane remains available. In fused-only
                    // shipping mode no probe owner exists, so this naturally
                    // skips without allocating or reading an intermediate bitset.
                    MemorySegment visibilityOwner = TerrainGpuVisibilityProbe.ownerForEpoch(
'''
    return replace_once(text, anchor, replacement, "render pass fused path")


def patch_bridge(text: str) -> str:
    init_anchor = '''            MTLDeviceCreateTerrainGpuVisibilityScene = optionalDowncallWithoutCritical(
'''
    init_block = '''            MTLDeviceCreateTerrainFusedVisibleGpuIndexedIcb = optionalDowncallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_createTerrainFusedVisibleGpuIndexedIcb",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            INT
                    )
            );
'''
    text = replace_once(text, init_anchor, init_block + init_anchor, "bridge fused downcall")

    decl_anchor = '''    @Nullable
    private static final MethodHandle MTLDeviceCreateTerrainGpuVisibilityScene;
'''
    decl = '''    @Nullable
    private static final MethodHandle MTLDeviceCreateTerrainFusedVisibleGpuIndexedIcb;
'''
    text = replace_once(text, decl_anchor, decl + decl_anchor, "bridge fused handle")

    method_anchor = '''    public static boolean terrainPersistentVisibilitySceneAvailable() {
'''
    method = '''    public static boolean terrainFusedVisibleGpuIcbAvailable() {
        return MTLDeviceCreateTerrainFusedVisibleGpuIndexedIcb != null
                && MTLDeviceCreateTerrainGpuVisibilityScene != null;
    }

    /** Fuses persistent-scene frustum testing and source-ordinal ICB authoring. */
    public static MemorySegment MTLDevice_createTerrainFusedVisibleGpuIndexedIcb(
            final MemorySegment renderEncoder,
            final MemorySegment device,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment pipeline,
            final MemorySegment packedCommands,
            final MemorySegment packedCandidateIndices,
            final int drawCount,
            final MemorySegment persistentSceneOwner,
            final MemorySegment packedFrame,
            final long expectedSceneGeneration,
            final int expectedCandidateCount
    ) {
        MethodHandle handle = MTLDeviceCreateTerrainFusedVisibleGpuIndexedIcb;
        if (handle == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) handle.invokeExact(
                    segment(renderEncoder), segment(device), primitiveType, indexType,
                    segment(indexBuffer), segment(pipeline), segment(packedCommands),
                    segment(packedCandidateIndices), drawCount, segment(persistentSceneOwner),
                    segment(packedFrame), expectedSceneGeneration, expectedCandidateCount
            );
        } catch (Throwable throwable) {
            return MemorySegment.NULL;
        }
    }

'''
    return replace_once(text, method_anchor, method + method_anchor, "bridge fused methods")


def patch_native(text: str) -> str:
    struct_anchor = '''    struct TerrainIcbContainer {
      command_buffer commandBuffer [[id(0)]];
    };
'''
    structs = struct_anchor + '''

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
    text = replace_once(text, struct_anchor, structs, "native fused MSL structs")

    msl_tail = '''    }
    """
}

/// Decision-only terrain visibility and compaction kernels.'''
    fused_kernel = '''    }

    kernel void metallum_terrain_gpu_encode_fused_visible(
      device const TerrainDrawRecord *records [[buffer(0)]],
      device TerrainIcbContainer *container [[buffer(1)]],
      device \\(indexPointer) *indices [[buffer(2)]],
      device const TerrainVisibilitySceneCandidate *candidates [[buffer(3)]],
      device const TerrainVisibilitySceneFrame *frame [[buffer(4)]],
      device const uint *candidateBySourceOrdinal [[buffer(5)]],
      uint drawIndex [[thread_position_in_grid]]) {
      uint candidateIndex = candidateBySourceOrdinal[drawIndex];
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
      if (!visible && !uncertain) {
        return;
      }
      TerrainDrawRecord record = records[drawIndex];
      if (record.indexCount < 0 || record.instanceCount < 0
          || record.firstIndex < 0 || record.firstInstance < 0) {
        return;
      }
      render_command command(container->commandBuffer, drawIndex);
      command.draw_indexed_primitives(primitive_type::\\(primitive),
          uint(record.indexCount), indices + uint(record.firstIndex),
          uint(record.instanceCount), as_type<uint>(record.baseVertex),
          uint(record.firstInstance));
    }
    """
}

/// Decision-only terrain visibility and compaction kernels.'''
    text = replace_once(text, msl_tail, fused_kernel, "native fused MSL kernel")

    function_anchor = '''/// Executes one already encoded terrain ICB. No command records are decoded or
'''
    function = r'''/// Fuses persistent-scene frustum testing and source-ordinal ICB authoring in
/// one compute encoder. No intermediate visibility bitset is produced.
@_cdecl("metallum_MTLDevice_createTerrainFusedVisibleGpuIndexedIcb")
public func metallum_MTLDevice_createTerrainFusedVisibleGpuIndexedIcb(
    _ pointer: UnsafeMutableRawPointer,
    _ device: MTLDevice,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ pipeline: MTLRenderPipelineState,
    _ packedCommands: UnsafePointer<Int32>?,
    _ packedCandidateIndices: UnsafePointer<Int32>?,
    _ drawCount: Int32,
    _ scenePointer: UnsafeMutableRawPointer?,
    _ packedFrame: UnsafePointer<UInt8>?,
    _ expectedSceneGeneration: UInt64,
    _ expectedCandidateCount: Int32
) -> UnsafeMutableRawPointer? {
    guard NativeState.terrainIcbEnabled, NativeState.terrainGpuEncodeEnabled,
          drawCount > 0, expectedCandidateCount > 0,
          let packedCommands, let packedCandidateIndices, let scenePointer, let packedFrame,
          pipeline.supportIndirectCommandBuffers,
          #available(macOS 26.0, iOS 26.0, *),
          device.supportsFamily(.metal4),
          let bridge = metal4RenderBridge(pointer),
          let source = terrainGpuIcbMslSource(primitiveType: primitiveType, indexType: indexType) else {
        return nil
    }
    let object = Unmanaged<AnyObject>.fromOpaque(scenePointer).takeUnretainedValue()
    guard let scene = object as? TerrainGpuVisibilitySceneOwner,
          scene.sceneGeneration == expectedSceneGeneration,
          scene.candidateCount == Int(expectedCandidateCount),
          let slot = scene.frameSlot(at: bridge.lease.slotIndex) else {
        return nil
    }
    let frameWords = UnsafeRawPointer(packedFrame).assumingMemoryBound(to: UInt32.self)
    for index in 0..<16 {
        guard Float(bitPattern: frameWords[index]).isFinite else { return nil }
    }
    for index in 20..<23 {
        let value = Float(bitPattern: frameWords[index])
        guard value.isFinite, value >= 0.0, value <= 1.0 else { return nil }
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
              candidate >= 0, candidate < scene.candidateCount else {
            return nil
        }
    }

    slot.frameBuffer.contents().copyMemory(from: UnsafeRawPointer(packedFrame), byteCount: 96)
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
        functionName: "metallum_terrain_gpu_encode_fused_visible",
        variant: 2
    ), let computeEncoder = bridge.lease.commandBuffer.makeComputeCommandEncoder() else {
        return nil
    }

    let argumentDescriptor = MTL4ArgumentTableDescriptor()
    argumentDescriptor.maxBufferBindCount = 6
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
    arguments.setAddress(scene.candidateBuffer.gpuAddress, index: 3)
    arguments.setAddress(slot.frameBuffer.gpuAddress, index: 4)
    arguments.setAddress(mappingBuffer.gpuAddress, index: 5)
    computeEncoder.setArgumentTable(arguments)
    computeEncoder.setComputePipelineState(computePipeline.state)
    computeEncoder.resetCommands(buffer: commandBuffer, range: 0..<commandCount)
    computeEncoder.dispatchThreads(
        threadsPerGrid: MTLSize(width: commandCount, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(
            width: max(1, min(computePipeline.state.threadExecutionWidth, 64)),
            height: 1,
            depth: 1
        )
    )
    if NativeState.terrainVisibleIcbOptimizeEnabled {
        computeEncoder.optimizeCommands(buffer: commandBuffer, range: 0..<commandCount)
    }
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
        visibilityOwner: scene
    )
    NativeState.terrainIcbEncodedCount &+= 1
    NativeState.terrainIcbGpuEncodedCount &+= 1
    NativeState.terrainIcbGpuDispatchCount &+= 1
    return retainedPointer(owner)
}

'''
    return replace_once(text, function_anchor, function + function_anchor, "native fused entry")


patch("src/main/java/com/metallum/client/metal/render/TerrainCandidateSnapshot.java", patch_candidate)
patch("src/main/java/com/metallum/client/metal/render/TerrainGpuVisibilityProbe.java", patch_probe)
patch("src/main/java/com/metallum/client/metal/render/TerrainIcbOwner.java", patch_icb_owner)
patch("src/main/java/com/metallum/client/metal/render/MetalRenderPass.java", patch_render_pass)
patch("src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java", patch_bridge)
patch("src/main/native/MetallumNative.swift", patch_native)
print("fused terrain visibility->ICB patch applied")
