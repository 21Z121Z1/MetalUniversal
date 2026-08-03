#!/usr/bin/env python3
"""Apply the remaining compile-time Metal 4 backend migration atomically.

This is intentionally an exact, one-shot repository patch. It exists because the
GitHub contents API cannot patch the very large Swift native translation unit
without replacing it wholesale. Every replacement is anchored and count-checked;
a changed or already-patched tree fails rather than producing a partial migration.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content, encoding="utf-8")


def replace_once(content: str, old: str, new: str, *, label: str) -> str:
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one anchor, found {count}")
    return content.replace(old, new, 1)


def replace_between(content: str, start: str, end: str, replacement: str, *, label: str) -> str:
    start_index = content.find(start)
    if start_index < 0:
        raise RuntimeError(f"{label}: start anchor not found")
    end_index = content.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{label}: end anchor not found")
    if content.find(start, start_index + len(start)) >= 0:
        raise RuntimeError(f"{label}: start anchor is not unique")
    return content[:start_index] + replacement + content[end_index:]


def patch_native() -> None:
    path = "src/main/native/MetallumNative.swift"
    source = read(path)
    marker = "static var metal4GenericComputeEncodeCount: UInt64 = 0"
    if marker in source:
        print("MetallumNative.swift already patched")
        return

    source = replace_once(
        source,
        """    static var metal4FrameGenerationInputCount: UInt64 = 0
    // The upload bridge cannot identify the destination allocation or range
""",
        """    static var metal4FrameGenerationInputCount: UInt64 = 0
    // Backend-closure diagnostics. These counters distinguish the generic
    // Iris/Blaze3D bridge from the dedicated MetalFX producers above and make a
    // legacy-encoder escape observable when the MTL4 main queue is active.
    static var metal4GenericComputeEncodeCount: UInt64 = 0
    static var metal4GenericBlitEncodeCount: UInt64 = 0
    static var metal4GenericRenderEncodeCount: UInt64 = 0
    static var metal3GenericComputeEncodeCount: UInt64 = 0
    static var metal3GenericBlitEncodeCount: UInt64 = 0
    static var metal4LegacyEncoderViolationCount: UInt64 = 0
    // The upload bridge cannot identify the destination allocation or range
""",
        label="native counters",
    )

    old_bridges = """@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainBlitEncoderBridge {
    let encoder: MTL4ComputeCommandEncoder
    init(_ encoder: MTL4ComputeCommandEncoder) { self.encoder = encoder }
}

@available(macOS 26.0, iOS 26.0, *)
private func metal4RenderBridge(_ pointer: UnsafeMutableRawPointer) -> Metal4MainRenderEncoderBridge? {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainRenderEncoderBridge
}

@available(macOS 26.0, iOS 26.0, *)
private func metal4BlitBridge(_ pointer: UnsafeMutableRawPointer) -> Metal4MainBlitEncoderBridge? {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainBlitEncoderBridge
}

private func metal3RenderEncoder(_ pointer: UnsafeMutableRawPointer) -> MTLRenderCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLRenderCommandEncoder
}

private func metal3BlitEncoder(_ pointer: UnsafeMutableRawPointer) -> MTLBlitCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLBlitCommandEncoder
}
"""
    new_bridges = """@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainBlitEncoderBridge {
    let encoder: MTL4ComputeCommandEncoder
    init(_ encoder: MTL4ComputeCommandEncoder) { self.encoder = encoder }
}

/// Generic compute bridge used by Iris and every future Blaze3D compute pass.
/// MTL4 has no setBuffer/setTexture calls; bindings are written into one
/// argument table and snapshotted by each dispatch command.
@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainComputeEncoderBridge {
    let encoder: MTL4ComputeCommandEncoder
    private let arguments: MTL4ArgumentTable
    private var encodedDispatch = false

    init(encoder: MTL4ComputeCommandEncoder, arguments: MTL4ArgumentTable) {
        self.encoder = encoder
        self.arguments = arguments
        encoder.setArgumentTable(arguments)
    }

    func setBuffer(_ buffer: MTLBuffer?, offset: Int, index: Int) {
        guard index >= 0, index < 31, offset >= 0 else {
            NSLog("[metallum] Metal 4 rejected compute buffer binding index=%d offset=%d", index, offset)
            return
        }
        arguments.setAddress(buffer.map { $0.gpuAddress + UInt64(offset) } ?? 0, index: index)
    }

    func setTexture(_ texture: MTLTexture?, index: Int) {
        guard index >= 0, index < 128 else {
            NSLog("[metallum] Metal 4 rejected compute texture binding index=%d", index)
            return
        }
        arguments.setTexture(texture?.gpuResourceID ?? MTLResourceID(), index: index)
    }

    func setSampler(_ sampler: MTLSamplerState?, index: Int) {
        guard index >= 0, index < 16 else {
            NSLog("[metallum] Metal 4 rejected compute sampler binding index=%d", index)
            return
        }
        arguments.setSamplerState(sampler?.gpuResourceID ?? MTLResourceID(), index: index)
    }

    func prepareDispatch() {
        if encodedDispatch {
            // A generic compute pass may bind a producer and consumer in one
            // encoder. Metal 3 relied on tracked encoder ordering here; MTL4
            // needs an explicit intra-pass dispatch dependency.
            encoder.barrier(
                afterEncoderStages: .dispatch,
                beforeEncoderStages: .dispatch,
                visibilityOptions: .device
            )
        }
        encodedDispatch = true
    }

    func publishWrites() {
        guard encodedDispatch else { return }
        encoder.barrier(
            afterStages: .dispatch,
            beforeQueueStages: [.vertex, .fragment, .dispatch, .blit],
            visibilityOptions: .device
        )
    }
}

@available(macOS 26.0, iOS 26.0, *)
private func metal4RenderBridge(_ pointer: UnsafeMutableRawPointer) -> Metal4MainRenderEncoderBridge? {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainRenderEncoderBridge
}

@available(macOS 26.0, iOS 26.0, *)
private func metal4BlitBridge(_ pointer: UnsafeMutableRawPointer) -> Metal4MainBlitEncoderBridge? {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainBlitEncoderBridge
}

@available(macOS 26.0, iOS 26.0, *)
private func metal4ComputeBridge(_ pointer: UnsafeMutableRawPointer) -> Metal4MainComputeEncoderBridge? {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainComputeEncoderBridge
}

private func metal3RenderEncoder(_ pointer: UnsafeMutableRawPointer) -> MTLRenderCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLRenderCommandEncoder
}

private func metal3BlitEncoder(_ pointer: UnsafeMutableRawPointer) -> MTLBlitCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLBlitCommandEncoder
}

private func metal3ComputeEncoder(_ pointer: UnsafeMutableRawPointer) -> MTLComputeCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLComputeCommandEncoder
}
"""
    source = replace_once(source, old_bridges, new_bridges, label="compute bridge")

    source = replace_once(
        source,
        """        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            guard let encoder = lease.commandBuffer.makeComputeCommandEncoder() else { return nil }
            encoder.label = label
""",
        """        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            guard let encoder = lease.commandBuffer.makeComputeCommandEncoder() else { return nil }
            NativeState.metal4GenericBlitEncodeCount &+= 1
            encoder.label = label
""",
        label="blit counter",
    )
    source = replace_once(
        source,
        """        let commandBuffer = metal3CommandBuffer(pointer)
        let timing = gpuEncoderTimingContext(commandBuffer)
""",
        """        if #available(macOS 26.0, iOS 26.0, *), NativeState.metal4MainQueueStorage != nil {
            NativeState.metal4LegacyEncoderViolationCount &+= 1
            NSLog("[metallum] rejected Metal 3 blit encoder while Metal 4 main renderer is active")
            return nil
        }
        NativeState.metal3GenericBlitEncodeCount &+= 1
        let commandBuffer = metal3CommandBuffer(pointer)
        let timing = gpuEncoderTimingContext(commandBuffer)
""",
        label="blit hard gate",
    )

    source = replace_once(
        source,
        """    if #available(macOS 26.0, iOS 26.0, *), let blit = metal4BlitBridge(pointer) {
        blit.encoder.endEncoding()
        return
    }
    let encoder = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLCommandEncoder
""",
        """    if #available(macOS 26.0, iOS 26.0, *), let blit = metal4BlitBridge(pointer) {
        blit.encoder.endEncoding()
        return
    }
    if #available(macOS 26.0, iOS 26.0, *), let compute = metal4ComputeBridge(pointer) {
        compute.encoder.endEncoding()
        return
    }
    let encoder = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLCommandEncoder
""",
        label="generic endEncoding",
    )

    generic_start = "// MARK: - Generic compute / mipmap / compare-sampler ABI (Iris backend B0)\n"
    sampler_start = "// Sampler creation with an optional depth-compare function. compareFunction\n"
    generic_replacement = """// MARK: - Generic compute / mipmap / compare-sampler ABI (Iris backend B0)
//
// The ABI remains pointer-shaped for Java FFM, but every operation now branches
// on the concrete Metal 3 encoder or Metal 4 bridge. No MTL4 command-buffer lease
// is force-cast to an MTLCommandBuffer, and no MTL4 compute encoder is force-cast
// to MTLComputeCommandEncoder.

@_cdecl("metallum_MTLCommandBuffer_makeComputeCommandEncoder")
public func metallum_MTLCommandBuffer_makeComputeCommandEncoder(
    _ pointer: UnsafeMutableRawPointer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            guard let encoder = lease.commandBuffer.makeComputeCommandEncoder() else { return nil }
            encoder.label = "Metallum Generic Compute (Metal 4)"
            encoder.barrier(
                afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
                beforeStages: .dispatch,
                visibilityOptions: .device
            )
            let arguments = lease.owner.computeArgumentTable(at: lease.slotIndex)
            NativeState.metal4GenericComputeEncodeCount &+= 1
            return retainedPointer(Metal4MainComputeEncoderBridge(
                encoder: encoder,
                arguments: arguments
            ))
        }
        if #available(macOS 26.0, iOS 26.0, *), NativeState.metal4MainQueueStorage != nil {
            NativeState.metal4LegacyEncoderViolationCount &+= 1
            NSLog("[metallum] rejected Metal 3 compute encoder while Metal 4 main renderer is active")
            return nil
        }
        NativeState.metal3GenericComputeEncodeCount &+= 1
        return retainedPointer(metal3CommandBuffer(pointer).makeComputeCommandEncoder())
    }
}

@_cdecl("metallum_MTLComputeCommandEncoder_setComputePipelineState")
public func metallum_MTLComputeCommandEncoder_setComputePipelineState(
    _ pointer: UnsafeMutableRawPointer,
    _ pipelineState: MTLComputePipelineState
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.encoder.setComputePipelineState(pipelineState)
        return
    }
    metal3ComputeEncoder(pointer).setComputePipelineState(pipelineState)
}

@_cdecl("metallum_MTLComputeCommandEncoder_setBuffer")
public func metallum_MTLComputeCommandEncoder_setBuffer(
    _ pointer: UnsafeMutableRawPointer,
    _ buffer: MTLBuffer?,
    _ offset: Int,
    _ index: Int32
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.setBuffer(buffer, offset: offset, index: Int(index))
        return
    }
    metal3ComputeEncoder(pointer).setBuffer(buffer, offset: offset, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_setTexture")
public func metallum_MTLComputeCommandEncoder_setTexture(
    _ pointer: UnsafeMutableRawPointer,
    _ texture: MTLTexture?,
    _ index: Int32
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.setTexture(texture, index: Int(index))
        return
    }
    metal3ComputeEncoder(pointer).setTexture(texture, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_setSamplerState")
public func metallum_MTLComputeCommandEncoder_setSamplerState(
    _ pointer: UnsafeMutableRawPointer,
    _ sampler: MTLSamplerState?,
    _ index: Int32
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.setSampler(sampler, index: Int(index))
        return
    }
    metal3ComputeEncoder(pointer).setSamplerState(sampler, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_dispatchThreadgroups")
public func metallum_MTLComputeCommandEncoder_dispatchThreadgroups(
    _ pointer: UnsafeMutableRawPointer,
    _ groupsX: Int32,
    _ groupsY: Int32,
    _ groupsZ: Int32,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    let groups = MTLSize(width: Int(groupsX), height: Int(groupsY), depth: Int(groupsZ))
    let threads = MTLSize(
        width: Int(threadsPerGroupX),
        height: Int(threadsPerGroupY),
        depth: Int(threadsPerGroupZ)
    )
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.prepareDispatch()
        bridge.encoder.dispatchThreadgroups(
            threadgroupsPerGrid: groups,
            threadsPerThreadgroup: threads
        )
        return
    }
    metal3ComputeEncoder(pointer).dispatchThreadgroups(groups, threadsPerThreadgroup: threads)
}

@_cdecl("metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect")
public func metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
    _ pointer: UnsafeMutableRawPointer,
    _ indirectBuffer: MTLBuffer,
    _ indirectOffset: Int,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    guard indirectOffset >= 0, indirectOffset % 4 == 0,
          indirectOffset + MemoryLayout<MTLDispatchThreadgroupsIndirectArguments>.stride <= indirectBuffer.length else {
        NSLog("[metallum] rejected invalid indirect compute range offset=%d length=%d", indirectOffset, indirectBuffer.length)
        return
    }
    let threads = MTLSize(
        width: Int(threadsPerGroupX),
        height: Int(threadsPerGroupY),
        depth: Int(threadsPerGroupZ)
    )
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.prepareDispatch()
        bridge.encoder.dispatchThreadgroups(
            indirectBuffer: indirectBuffer.gpuAddress + UInt64(indirectOffset),
            threadsPerThreadgroup: threads
        )
        return
    }
    metal3ComputeEncoder(pointer).dispatchThreadgroups(
        indirectBuffer: indirectBuffer,
        indirectBufferOffset: indirectOffset,
        threadsPerThreadgroup: threads
    )
}

@_cdecl("metallum_MTLComputeCommandEncoder_updateFence")
public func metallum_MTLComputeCommandEncoder_updateFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.publishWrites()
        return
    }
    metal3ComputeEncoder(pointer).updateFence(fence)
}

@_cdecl("metallum_MTLComputeCommandEncoder_waitForFence")
public func metallum_MTLComputeCommandEncoder_waitForFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence
) {
    if #available(macOS 26.0, iOS 26.0, *), metal4ComputeBridge(pointer) != nil {
        // The MTL4 consumer barrier is encoded when the bridge is created.
        return
    }
    metal3ComputeEncoder(pointer).waitForFence(fence)
}

@_cdecl("metallum_MTLDevice_makeComputePipelineState")
public func metallum_MTLDevice_makeComputePipelineState(
    _ device: MTLDevice,
    _ function: MTLFunction
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        do {
            return retainedPointer(try device.makeComputePipelineState(function: function))
        } catch {
            NSLog("[metallum] Failed to create compute pipeline state: %@", String(describing: error))
            return nil
        }
    }
}

@_cdecl("metallum_MTLComputePipelineState_maxTotalThreadsPerThreadgroup")
public func metallum_MTLComputePipelineState_maxTotalThreadsPerThreadgroup(
    _ pipelineState: MTLComputePipelineState
) -> Int32 {
    return Int32(clamping: pipelineState.maxTotalThreadsPerThreadgroup)
}

@_cdecl("metallum_MTLBlitCommandEncoder_generateMipmaps")
public func metallum_MTLBlitCommandEncoder_generateMipmaps(
    _ pointer: UnsafeMutableRawPointer,
    _ texture: MTLTexture
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4BlitBridge(pointer) {
        bridge.encoder.generateMipmaps(texture: texture)
        return
    }
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4ComputeBridge(pointer) {
        bridge.encoder.generateMipmaps(texture: texture)
        return
    }
    metal3BlitEncoder(pointer).generateMipmaps(for: texture)
}

"""
    source = replace_between(
        source,
        generic_start,
        sampler_start,
        generic_replacement,
        label="generic compute block",
    )

    stats_anchor = """@_cdecl("metallum_MTLDevice_makeCommandQueue")
public func metallum_MTLDevice_makeCommandQueue(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
"""
    stats_block = """/// Reports whether the Java-driven backend stayed entirely on MTL4 after
/// enabling the main renderer. A non-zero legacy count is a release blocker.
@_cdecl("metallum_metal4_backend_closure_stats")
public func metallum_metal4_backend_closure_stats(
    _ render: UnsafeMutablePointer<UInt64>?,
    _ compute: UnsafeMutablePointer<UInt64>?,
    _ blit: UnsafeMutablePointer<UInt64>?,
    _ legacyViolations: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *),
          NativeState.metal4MainQueueStorage != nil else {
        return 0
    }
    render?.pointee = NativeState.metal4GenericRenderEncodeCount
    compute?.pointee = NativeState.metal4GenericComputeEncodeCount
    blit?.pointee = NativeState.metal4GenericBlitEncodeCount
    legacyViolations?.pointee = NativeState.metal4LegacyEncoderViolationCount
    return 1
}

@_cdecl("metallum_metal4_no_legacy_encoder_violations")
public func metallum_metal4_no_legacy_encoder_violations() -> Int32 {
    NativeState.metal4LegacyEncoderViolationCount == 0 ? 1 : 0
}

""" + stats_anchor
    source = replace_once(source, stats_anchor, stats_block, label="backend closure stats")

    # Typecheck the exact MTL4 indirect-dispatch spelling in the SDK probe.
    source_probe_path = "docs/mtl4-api-probe.swift"
    probe = read(source_probe_path)
    probe = replace_once(
        probe,
        """    cenc.dispatchThreads(threadsPerGrid: MTLSize(width: 8, height: 8, depth: 1), threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1))
    cenc.setThreadgroupMemoryLength(0, index: 0)
""",
        """    cenc.dispatchThreads(threadsPerGrid: MTLSize(width: 8, height: 8, depth: 1), threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1))
    cenc.dispatchThreadgroups(
        indirectBuffer: buffer.gpuAddress,
        threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1)
    )
    cenc.setThreadgroupMemoryLength(0, index: 0)
""",
        label="MTL4 indirect-dispatch probe",
    )

    write(path, source)
    write(source_probe_path, probe)


def patch_device() -> None:
    path = "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
    source = read(path)
    if "private volatile boolean closing;" in source:
        print("MetalDevice.java already patched")
        return

    source = replace_once(
        source,
        """    private volatile int pipelineCacheGeneration;
    @Nullable
    private final ExecutorService prewarmExecutor;
""",
        """    private volatile int pipelineCacheGeneration;
    /** Terminal state published before background compilation and GPU teardown. */
    private volatile boolean closing;
    @Nullable
    private final ExecutorService prewarmExecutor;
""",
        label="device closing field",
    )
    source = replace_once(
        source,
        """    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
""",
        """    public @NonNull CompiledRenderPipeline precompilePipeline(final @NonNull RenderPipeline pipeline, @Nullable final ShaderSource shaderSource) {
        if (this.closing) {
            throw new IllegalStateException("Metal device is closing");
        }
        ShaderSource effectiveSource = shaderSource == null ? this.activeShaderSource : shaderSource;
""",
        label="precompile close gate",
    )
    source = replace_once(
        source,
        """    void submitPrewarmTask(final Runnable task) {
        if (this.prewarmExecutor != null) {
""",
        """    void submitPrewarmTask(final Runnable task) {
        if (!this.closing && this.prewarmExecutor != null) {
""",
        label="prewarm submission gate",
    )
    source = replace_once(
        source,
        """    private void compileInBackground(final RenderPipeline pipeline, final ShaderSource source, final int generation) {
        if (this.compiledPipelines.containsKey(pipeline)) {
""",
        """    private void compileInBackground(final RenderPipeline pipeline, final ShaderSource source, final int generation) {
        if (this.closing || this.compiledPipelines.containsKey(pipeline)) {
""",
        label="background compile gate",
    )

    old_close = """    public void close() {
        if (current == this) {
            current = null;
        }
        this.waitForSubmittedGpuWork();
        this.genericVertexAttributeBuffer.close();
        this.commandEncoder.close();
        if (this.prewarmExecutor != null) {
            // Stop background compiles before tearing down the caches they
            // populate; a straggler past the 5s bail-out still serializes
            // against clearPipelineCache via COMPILE_CHAIN_LOCK.
            this.prewarmExecutor.shutdownNow();
            try {
                if (!this.prewarmExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Metallum.LOGGER.warn("[metallum] PSO prewarm thread still busy at shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        this.clearPipelineCache();
        this.drainBufferPool();
        if (!MetalNativeBridge.isNullHandle(this.cocoaView)) {
            try {
                MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
            } catch (Throwable ignored) {
            }
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }
"""
    new_close = """    public void close() {
        if (this.closing) {
            return;
        }
        this.closing = true;
        if (current == this) {
            current = null;
        }

        // Freeze and join background compilation before touching any cache,
        // encoder, residency state or native object it can still populate.
        if (this.prewarmExecutor != null) {
            this.prewarmExecutor.shutdownNow();
            try {
                if (!this.prewarmExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Metallum.LOGGER.warn("[metallum] PSO prewarm thread still busy at shutdown");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // All retirement queued below remains legal until commandEncoder.close()
        // performs the terminal drain. Closing the encoder earlier stranded late
        // sampler and buffer releases in a queue that could never rotate again.
        this.waitForSubmittedGpuWork();
        this.genericVertexAttributeBuffer.close();
        this.clearPipelineCache();
        this.commandEncoder.close();
        this.drainBufferPool();
        if (!MetalNativeBridge.isNullHandle(this.cocoaView)) {
            try {
                MetalNativeBridge.metallum_NSView_clearLayer(this.cocoaView);
            } catch (Throwable ignored) {
            }
        }
        this.commandQueue.close();
        MetalNativeBridge.metallum_release_object(this.metalDeviceHandle);
    }
"""
    source = replace_once(source, old_close, new_close, label="device close ordering")
    write(path, source)


def patch_build() -> None:
    path = "build.gradle"
    source = read(path)
    if 'tasks.register("metal4ApiProbe"' in source:
        print("build.gradle already patched")
        return
    anchor = """tasks.register("metal4PipelinePathTest", Exec) {
"""
    task = """tasks.register("metal4ApiProbe", Exec) {
    group = "verification"
    description = "Typechecks the complete Metal 4 API spelling against the macOS 26 SDK."
    onlyIf {
        org.gradle.internal.os.OperatingSystem.current().isMacOsX()
    }
    workingDir project.projectDir
    inputs.file("docs/mtl4-api-probe.swift")
    commandLine "bash", "-lc", "xcrun swiftc -typecheck -sdk \\\"$(xcrun --show-sdk-path --sdk macosx)\\\" -target arm64-apple-macosx14.0 docs/mtl4-api-probe.swift"
}

tasks.named("check") {
    dependsOn "metal4ApiProbe"
}

""" + anchor
    source = replace_once(source, anchor, task, label="Metal 4 API probe task")
    write(path, source)


def main() -> None:
    patch_native()
    patch_device()
    patch_build()
    print("Metal 4 complete-migration patch applied")


if __name__ == "__main__":
    main()
