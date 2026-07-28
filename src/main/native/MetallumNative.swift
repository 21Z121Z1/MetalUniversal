import Foundation
#if os(macOS)
import AppKit
import ObjectiveC
#elseif os(iOS)
import UIKit
#endif
import Metal
import QuartzCore
import simd
#if os(macOS)
import Darwin
#endif
#if os(macOS) && canImport(MetalFX)
import MetalFX
#endif

// On iOS, AppKit types (NSView/NSWindow) are unavailable. We expose platform-
// neutral type aliases so the rest of the file can reference the same names
// without littering every signature with #if branches.
#if os(macOS)
public typealias MetallumView = NSView
public typealias MetallumWindow = NSWindow
#elseif os(iOS)
public typealias MetallumView = UIView
public typealias MetallumWindow = UIWindow
#endif

private struct DepthStencilKey: Hashable {
    let deviceAddress: UInt
    let compareOp: MTLCompareFunction
    let writeDepth: Bool
}

private struct PipelineVariantKey: Hashable {
    let deviceAddress: UInt
    let colorFormat: MTLPixelFormat
    let depthFormat: MTLPixelFormat
    let writeColor: Bool
}

private struct SamplerKey: Hashable {
    let deviceAddress: UInt
    let addressModeU: UInt
    let addressModeV: UInt
    let minFilter: UInt
    let magFilter: UInt
    let mipFilter: UInt
    let maxAnisotropy: Int
    let lodMaxClampBits: UInt32
}

private enum NativeState {
    static var debugLabelsEnabled = false
    // When true, makeRenderCommandEncoder_v2 leaves the depth attachment with
    // storeAction=.unknown and the Java side resolves it (setDepthStoreAction)
    // before endEncoding. Toggled once at device init from
    // metallum_set_deferred_depth_store; must match the Java flag exactly.
    static var deferredDepthStore = false
    // Split-fence mode (metallum.opt.splitFence): non-nil while the Java
    // encoder runs a separate transfer fence for blit work. The only Swift
    // encoder on the transfer chain is the frame-generation input copy blit;
    // every other native encoder stays on the render fence it receives as a
    // parameter. Set at device init, cleared before the fence is released.
    static var transferFence: MTLFence?
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var samplerStates: [SamplerKey: MTLSamplerState] = [:]
    // Disk-backed PSO cache: descriptors compiled through
    // metallum_MTLDevice_makeRenderPipelineState look up this archive first
    // and harvest into it after a successful compile. Serialized to disk via
    // metallum_pso_archive_flush. The lock guards harvest/serialize because
    // pipeline creation may move off the render thread later.
    static var binaryArchive: MTLBinaryArchive?
    static let binaryArchiveLock = NSLock()
    // True when the archive was loaded from an existing file. Re-serializing
    // an archive that contains loaded entries fails on current macOS
    // ("expecting 'fragment' stage in pipeline no. N", entry number varies),
    // so a loaded archive is used strictly read-only: PSO creation still hits
    // it via descriptor.binaryArchives, but harvest and flush are skipped.
    // Fresh archives (first launch or after deletion) harvest and serialize
    // normally.
    static var binaryArchiveReadOnly = false
    // Metal 4 (migration spec M2). Enabled from Java once the capability gate
    // and metallum.opt.metal4Compiler both hold; false means every PSO takes
    // the Metal 3 path below, unchanged.
    static var metal4CompilerEnabled = false
    // Metal 4 frame-generation present pilot (spec M4). Read once when the
    // presenter is constructed; flipping it later has no effect, which matches how
    // the presenter is started.
    static var metal4PresentEnabled = false
    // Appends the barrier map's consumer barriers to the existing Metal 3 encoders
    // (spec M6-B). Independent of the metal4 master gate: the API is gated on
    // macOS 26, not on Metal 4 family support.
    static var metal4BarrierEnabled = false
    static var gpuEncoderTimingEnabled = false
    // Validation-only A/B for MTL4 argument-table lifetime. This is outside the
    // MetalFX conditional block because the MTL4 main queue is shared by the
    // platform native builds.
    static let freshComputeArgumentTables = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_FRESH_COMPUTE_ARGUMENT_TABLE"
    ] == "1"
    // MTL4LibraryFunctionDescriptor requires the MTLLibrary a function came
    // from, and MTLFunction does not expose it, so the association is kept
    // beside it. Weak keys: the entry disappears when the function is released,
    // so a library is held exactly as long as some function of it is alive.
    static let functionLibraries = NSMapTable<AnyObject, AnyObject>.weakToStrongObjects()
    static let functionLibrariesLock = NSLock()
    // Typed as AnyObject? deliberately: MTL4Compiler and
    // MTL4PipelineDataSetSerializer are macOS 26 / iOS 26 symbols and cannot
    // appear in the signature of an unversioned type, and putting @available on
    // all of NativeState is not an option. Stored erased, recovered with `as?`
    // inside an #available block.
    static var metal4CompilerStorage: AnyObject?
    static var metal4Serializer: AnyObject?
    // MTL4Archive loaded from the previous launch, fed to pipeline creation as
    // MTL4CompilerTaskOptions.lookupArchives. Erased for the same reason as the
    // compiler above.
    static var metal4LookupArchive: AnyObject?
    static var metal4MainQueuePilotStorage: AnyObject?
    static var metal4MainQueueStorage: AnyObject?
    static var metal4AuxiliaryComputeEncodeCount: UInt64 = 0
    static var metal4SpatialEncodeCount: UInt64 = 0
    static var metal4TemporalEncodeCount: UInt64 = 0
    static var metal4FrameGenerationInputCount: UInt64 = 0
    static let metal4CompilerLock = NSLock()
    // Residency set (migration spec M3), enabled by metallum.opt.residencySet.
    // MTLResidencySet is macOS 15 / iOS 18 and needs no Metal 4, so the table of
    // "what the GPU may touch" is built on the existing Metal 3 queue first;
    // under Metal 4 residency becomes mandatory and this is already wired.
    // Erased as AnyObject? for the same versioning reason as the compiler.
    // MTLResidencySet is NOT thread safe: every addAllocation / removeAllocation
    // / commit must hold residencyLock. This project has the render thread, the
    // frame-generation present thread and the async precompile thread all able to
    // create and destroy resources, so the lock is not optional.
    static var residencySetStorage: AnyObject?
    static let residencyLock = NSLock()
    static var residencyDirty = false
    static var residencyRequested = false
    // One-shot logging so a run can tell "the Metal 4 pipeline path worked" from
    // "every pipeline silently fell back to Metal 3" — the two are otherwise
    // indistinguishable, since falling back is by design never an error. Racing
    // on these only ever costs a duplicate log line.
    static var metal4PipelineLogged = false
    static var metal4PipelineFallbackLogged = false

    static func logMetal4PipelineFallback(_ reason: String) {
        guard !metal4PipelineFallbackLogged else { return }
        metal4PipelineFallbackLogged = true
        NSLog("[metallum] Metal 4 pipeline path unavailable, using Metal 3: %@", reason)
    }

    static func register(function: MTLFunction, library: MTLLibrary) {
        functionLibrariesLock.lock()
        functionLibraries.setObject(library, forKey: function as AnyObject)
        functionLibrariesLock.unlock()
    }

    static func library(for function: MTLFunction) -> MTLLibrary? {
        functionLibrariesLock.lock()
        defer { functionLibrariesLock.unlock() }
        return functionLibraries.object(forKey: function as AnyObject) as? MTLLibrary
    }

    /// Process-wide compiler, built on first use. Nil means Metal 4 pipeline
    /// creation is unavailable and callers must fall back to the Metal 3 path.
    @available(macOS 26.0, iOS 26.0, *)
    static func metal4Compiler(_ device: MTLDevice) -> MTL4Compiler? {
        metal4CompilerLock.lock()
        defer { metal4CompilerLock.unlock() }
        if let existing = metal4CompilerStorage as? MTL4Compiler { return existing }
        let descriptor = MTL4CompilerDescriptor()
        descriptor.label = "metallum-compiler"
        if let serializer = metal4Serializer as? MTL4PipelineDataSetSerializer {
            descriptor.pipelineDataSetSerializer = serializer
        }
        guard let compiler = try? device.makeCompiler(descriptor: descriptor) else {
            NSLog("[metallum] MTL4Compiler creation failed; Metal 4 pipeline path disabled")
            return nil
        }
        metal4CompilerStorage = compiler
        return compiler
    }
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipeline: MTLRenderPipelineState!
    static var presentNearestSampler: MTLSamplerState!
    static var presentLinearSampler: MTLSamplerState!
    static var copyPipelines: [Int: MTLRenderPipelineState] = [:]
    #if os(macOS)
    // Present mode the game last asked for, so stopping the frame-generation
    // presenter can hand the layer back in the state Minecraft expects instead
    // of the vsync-on state frame generation requires.
    static var immediatePresentModeRequested = false
    #endif
    #if os(macOS) && canImport(MetalFX)
    static var metalFxScalers: [String: AnyObject] = [:]
    static var metalFxPreviousDepthTextures: [String: MTLTexture] = [:]
    static var metalFxValidationReactiveTextures: [String: MTLTexture] = [:]
    static var metalFxPreviousDepthValid: Set<String> = []
    static let metalFxHistoryLock = NSLock()
    static var motionPipeline: MTLComputePipelineState?
    static var motionV2Pipeline: MTLComputePipelineState?
    static var motionMergePipeline: MTLComputePipelineState?
    static var motionFusedPipeline: MTLComputePipelineState?
    static var motionClearPipeline: MTLComputePipelineState?
    // QA-only A/B escape hatch. Production uses the fused motion path; setting
    // this before launch restores the two-dispatch camera/merge implementation.
    static let legacyMotionPasses = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_LEGACY_MOTION_PASSES"
    ] == "1"
    // Validation-only A/B switch for the Metal 4 reactive preservation copy.
    // The default keeps the snapshot/restore evidence enabled; setting this
    // to 0 isolates the producer and Temporal path from that diagnostic copy.
    static let reactiveValidationSnapshotEnabled = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_REACTIVE_SNAPSHOT"
    ] != "0"
    // Validation-only producer isolation. These are intentionally opt-in and
    // leave the default Metal 4 producer chain unchanged.
    static let skipMetal4TransparencyReactive = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_SKIP_TRANSPARENCY_REACTIVE"
    ] == "1"
    static let skipMetal4CutoutReactive = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_SKIP_CUTOUT_REACTIVE"
    ] == "1"
    // Production follows the actual alpha used by transparent compositing.
    // Looking at max(alpha, RGB) marks colored texels even when they contribute
    // no visible transparency and suppresses useful temporal history. This
    // validation-only escape hatch restores that legacy behavior for A/B.
    static let transparencyAlphaOnly = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_TRANSPARENCY_RGB_ACTIVITY"
    ] != "1"
    // Validation-only source attribution. Each active transparency attachment
    // writes a distinct reactive value so a single GPU readback can identify
    // which producer polluted an otherwise static CUTOUT interior.
    static let transparencySourceTags = ProcessInfo.processInfo.environment[
        "METALLUM_METALFX_TRANSPARENCY_SOURCE_TAGS"
    ] == "1"
    static var transparencyMaskPipeline: MTLComputePipelineState?
    static var cutoutReactivePipeline: MTLComputePipelineState?
    static var handOverlayPipeline: MTLComputePipelineState?
    static var metalFxFailureKeys: Set<String> = []
    static var frameGenerationLogged = false
    // Most recent temporal scaler from the v2 encode path. The frame
    // interpolator links against it (descriptor.scaler) so MetalFX can share
    // internal resources between upscaling and interpolation (WWDC25).
    static var lastTemporalScalerForInterpolation: AnyObject?
    @available(macOS 26.0, *)
    static var frameGenerationPresenter: MetalFrameGenerationPresenter?
    // Reactive-policy tuning, set once from Java before the first frame.
    // Order: (cutoutEdgeWeight, cutoutInteriorWeight, depthEdgeCap,
    // transparencyValue). Defaults mirror MetalFxConfig defaults so a missing
    // Java call keeps the shipped policy.
    static var reactiveTuning = SIMD4<Float>(0.0, 0.0, 0.0, 0.9)
    // Sky (cleared reversed-Z far plane) reconstructs camera-rotation motion
    // at a far-plane depth instead of being fully reactive+disoccluded every
    // frame. 1.0 = on (default), 0.0 = legacy sky suppression.
    static var skyFarPlaneMotion: Float = 1.0
    // Reactive value written for a disoccluded pixel. FSR2 guidance is that
    // 1.0 never produces good results; a hard 1.0 here is what kept
    // foliage/sky silhouettes strobing, because sub-pixel jitter re-flags them
    // as disoccluded on alternating frames.
    static var disocclusionReactiveCap: Float = 0.85
    // 3x3 depth dilation on the reprojected sample. Without it a silhouette
    // that jitters sub-pixel reads the far side of the edge every other frame
    // and is called a disocclusion. 1.0 = on (default), 0.0 = legacy probe.
    static var mergeDepthDilation: Float = 1.0
    #endif
}

private struct CompletedGpuEncoderTiming {
    let label: String
    let kind: Int32
    let milliseconds: Double
}

private final class GpuEncoderTimingContext {
    struct Record {
        let label: String
        let kind: Int32
        let startIndex: Int
        let endIndex: Int
    }

    static let sampleCapacity = 512
    let sampleBuffer: MTLCounterSampleBuffer
    var nextSample = 0
    var records: [Record] = []

    init?(_ device: MTLDevice) {
        guard device.supportsCounterSampling(.atStageBoundary),
              let timestampSet = device.counterSets?.first(where: { $0.name == "timestamp" }) else {
            return nil
        }
        let descriptor = MTLCounterSampleBufferDescriptor()
        descriptor.label = "Metallum encoder timings"
        descriptor.counterSet = timestampSet
        descriptor.storageMode = .shared
        descriptor.sampleCount = Self.sampleCapacity
        guard let sampleBuffer = try? device.makeCounterSampleBuffer(descriptor: descriptor) else {
            return nil
        }
        self.sampleBuffer = sampleBuffer
    }

    func reserve(label: String, kind: Int32) -> (Int, Int)? {
        guard nextSample + 2 <= Self.sampleCapacity else { return nil }
        let start = nextSample
        let end = start + 1
        nextSample += 2
        records.append(Record(label: label, kind: kind, startIndex: start, endIndex: end))
        return (start, end)
    }

    func resolve() -> [CompletedGpuEncoderTiming] {
        guard nextSample > 0,
              let data = try? sampleBuffer.resolveCounterRange(0..<nextSample) else {
            return []
        }
        return data.withUnsafeBytes { rawBuffer in
            let values = rawBuffer.bindMemory(to: MTLCounterResultTimestamp.self)
            return records.compactMap { record in
                guard record.startIndex < values.count, record.endIndex < values.count else {
                    return nil
                }
                let start = values[record.startIndex].timestamp
                let end = values[record.endIndex].timestamp
                guard start != MTLCounterErrorValue, end != MTLCounterErrorValue, end > start else {
                    return nil
                }
                return CompletedGpuEncoderTiming(
                    label: record.label,
                    kind: record.kind,
                    milliseconds: Double(end - start) / 1_000_000.0
                )
            }
        }
    }
}

private let gpuEncoderTimingLock = NSLock()
private var gpuEncoderTimingContexts: [ObjectIdentifier: GpuEncoderTimingContext] = [:]
private var completedGpuEncoderTimings: [CompletedGpuEncoderTiming] = []

private func gpuEncoderTimingContext(_ commandBuffer: MTLCommandBuffer) -> GpuEncoderTimingContext? {
    guard NativeState.gpuEncoderTimingEnabled else { return nil }
    let key = ObjectIdentifier(commandBuffer)
    gpuEncoderTimingLock.lock()
    defer { gpuEncoderTimingLock.unlock() }
    if let existing = gpuEncoderTimingContexts[key] {
        return existing
    }
    guard let created = GpuEncoderTimingContext(commandBuffer.device) else { return nil }
    gpuEncoderTimingContexts[key] = created
    return created
}

private func finishGpuEncoderTimings(_ commandBuffer: MTLCommandBuffer) {
    let key = ObjectIdentifier(commandBuffer)
    gpuEncoderTimingLock.lock()
    let context = gpuEncoderTimingContexts.removeValue(forKey: key)
    gpuEncoderTimingLock.unlock()
    guard let context else { return }
    commandBuffer.addCompletedHandler { _ in
        let resolved = context.resolve()
        guard !resolved.isEmpty else { return }
        gpuEncoderTimingLock.lock()
        completedGpuEncoderTimings.append(contentsOf: resolved)
        if completedGpuEncoderTimings.count > 32_768 {
            completedGpuEncoderTimings.removeFirst(completedGpuEncoderTimings.count - 32_768)
        }
        gpuEncoderTimingLock.unlock()
    }
}

@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainQueuePilot {
    private static let validationByteCount = 256

    private struct Slot {
        let commandBuffer: MTL4CommandBuffer
        let allocator: MTL4CommandAllocator
    }

    private let queue: MTL4CommandQueue
    private let slots: [Slot]
    private let sourceBuffer: MTLBuffer
    private let destinationBuffer: MTLBuffer
    private let residencySet: MTLResidencySet
    private var nextSlot = 0

    init?(_ device: MTLDevice) {
        let queueDescriptor = MTL4CommandQueueDescriptor()
        queueDescriptor.label = "Metallum Main Queue Pilot"
        guard let queue = try? device.makeMTL4CommandQueue(descriptor: queueDescriptor) else {
            return nil
        }
        var slots: [Slot] = []
        for index in 0..<3 {
            let allocatorDescriptor = MTL4CommandAllocatorDescriptor()
            allocatorDescriptor.label = "Metallum Main Queue Pilot Allocator \(index)"
            guard let allocator = try? device.makeCommandAllocator(descriptor: allocatorDescriptor),
                  let commandBuffer = device.makeCommandBuffer() else {
                return nil
            }
            commandBuffer.label = "Metallum Main Queue Pilot Buffer \(index)"
            slots.append(Slot(commandBuffer: commandBuffer, allocator: allocator))
        }
        guard let sourceBuffer = device.makeBuffer(
                  length: Self.validationByteCount,
                  options: .storageModeShared
              ),
              let destinationBuffer = device.makeBuffer(
                  length: Self.validationByteCount,
                  options: .storageModeShared
              ) else {
            return nil
        }
        let residencyDescriptor = MTLResidencySetDescriptor()
        residencyDescriptor.label = "Metallum Main Queue Pilot Residency"
        residencyDescriptor.initialCapacity = 2
        guard let residencySet = try? device.makeResidencySet(descriptor: residencyDescriptor) else {
            return nil
        }
        let sourceWords = sourceBuffer.contents().assumingMemoryBound(to: UInt32.self)
        let destinationWords = destinationBuffer.contents().assumingMemoryBound(to: UInt32.self)
        for index in 0..<(Self.validationByteCount / MemoryLayout<UInt32>.stride) {
            sourceWords[index] = 0x9e37_79b9 ^ UInt32(index)
            destinationWords[index] = 0
        }
        residencySet.addAllocations([sourceBuffer, destinationBuffer])
        residencySet.commit()
        residencySet.requestResidency()
        queue.addResidencySet(residencySet)
        self.queue = queue
        self.slots = slots
        self.sourceBuffer = sourceBuffer
        self.destinationBuffer = destinationBuffer
        self.residencySet = residencySet
    }

    func submitAndWait() -> Bool {
        let slot = slots[nextSlot]
        nextSlot = (nextSlot + 1) % slots.count
        slot.allocator.reset()
        slot.commandBuffer.beginCommandBuffer(allocator: slot.allocator)
        guard let encoder = slot.commandBuffer.makeComputeCommandEncoder() else {
            slot.commandBuffer.endCommandBuffer()
            return false
        }
        encoder.label = "Metallum Main Queue Pilot Copy"
        encoder.copy(
            sourceBuffer: sourceBuffer,
            sourceOffset: 0,
            destinationBuffer: destinationBuffer,
            destinationOffset: 0,
            size: Self.validationByteCount
        )
        encoder.endEncoding()
        slot.commandBuffer.endCommandBuffer()
        let completed = DispatchSemaphore(value: 0)
        var succeeded = false
        let options = MTL4CommitOptions()
        options.addFeedbackHandler { feedback in
            succeeded = feedback.error == nil
            completed.signal()
        }
        queue.commit([slot.commandBuffer], options: options)
        guard completed.wait(timeout: .now() + .seconds(5)) == .success, succeeded else {
            return false
        }
        let sourceWords = sourceBuffer.contents().assumingMemoryBound(to: UInt32.self)
        let destinationWords = destinationBuffer.contents().assumingMemoryBound(to: UInt32.self)
        for index in 0..<(Self.validationByteCount / MemoryLayout<UInt32>.stride) {
            if sourceWords[index] != destinationWords[index] {
                return false
            }
        }
        return true
    }
}

@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainCommandBufferLease {
    fileprivate let owner: Metal4MainQueueContext
    fileprivate let slotIndex: Int
    private let condition = NSCondition()
    private var submitted = false
    private var completed = false
    private var completionError: Error?
    private var startTime = 0.0
    private var endTime = 0.0
    fileprivate var presentDrawable: CAMetalDrawable?
    private var completionHandlers: [(Error?, CFTimeInterval, CFTimeInterval) -> Void] = []
    fileprivate var postCommitSignals: [(MTLSharedEvent, UInt64)] = []

    init(owner: Metal4MainQueueContext, slotIndex: Int) {
        self.owner = owner
        self.slotIndex = slotIndex
    }

    var commandBuffer: MTL4CommandBuffer { owner.commandBuffer(at: slotIndex) }

    func markSubmitted() {
        condition.lock()
        submitted = true
        condition.unlock()
    }

    func markCompleted(
        error: Error?,
        gpuStartTime: CFTimeInterval,
        gpuEndTime: CFTimeInterval
    ) -> [(Error?, CFTimeInterval, CFTimeInterval) -> Void] {
        condition.lock()
        completionError = error
        startTime = gpuStartTime
        endTime = gpuEndTime
        completed = true
        let handlers = completionHandlers
        completionHandlers.removeAll()
        condition.broadcast()
        condition.unlock()
        return handlers
    }

    func addCompletionHandler(_ handler: @escaping (Error?, CFTimeInterval, CFTimeInterval) -> Void) {
        condition.lock()
        if completed {
            let error = completionError
            let gpuStartTime = startTime
            let gpuEndTime = endTime
            condition.unlock()
            handler(error, gpuStartTime, gpuEndTime)
            return
        }
        completionHandlers.append(handler)
        condition.unlock()
    }

    func signalAfterCommit(_ event: MTLSharedEvent, value: UInt64) {
        postCommitSignals.append((event, value))
    }

    func isCompleted() -> Bool {
        condition.lock()
        defer { condition.unlock() }
        return completed
    }

    func completedSuccessfully() -> Bool {
        condition.lock()
        defer { condition.unlock() }
        return completed && completionError == nil
    }

    func gpuTimes() -> (Double, Double) {
        condition.lock()
        defer { condition.unlock() }
        return (startTime, endTime)
    }

    func waitUntilCompleted(timeoutMs: UInt64) -> Bool {
        condition.lock()
        defer { condition.unlock() }
        if completed { return true }
        guard submitted, timeoutMs > 0 else { return false }
        let seconds = min(Double(timeoutMs) / 1000.0, Double(Int.max))
        let deadline = Date(timeIntervalSinceNow: seconds)
        while !completed {
            if !condition.wait(until: deadline) { return completed }
        }
        return true
    }
}

@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainQueueContext {
    // Each compute dispatch needs an argument-table snapshot that will not be
    // mutated by a later dispatch before the command buffer executes. Eight
    // tables cover the current clear/transparency/CUTOUT/hand/fused chain plus
    // the legacy camera+merge probe, while remaining bounded per slot.
    private static let computeArgumentTableCount = 8

    private enum SlotState {
        case free
        case recording
        case submitted
    }

    private final class Slot {
        let commandBuffer: MTL4CommandBuffer
        let allocator: MTL4CommandAllocator
        let vertexArguments: MTL4ArgumentTable
        let fragmentArguments: MTL4ArgumentTable
        let computeArgumentTables: [MTL4ArgumentTable]
        var renderArgumentTables: [(vertex: MTL4ArgumentTable, fragment: MTL4ArgumentTable)] = []
        let uniformBuffer: MTLBuffer
        var uniformOffset = 0
        var nextComputeArgumentTable = 0
        var nextRenderArgumentTable = 0
        var state: SlotState = .free

        init(
            commandBuffer: MTL4CommandBuffer,
            allocator: MTL4CommandAllocator,
            vertexArguments: MTL4ArgumentTable,
            fragmentArguments: MTL4ArgumentTable,
            computeArgumentTables: [MTL4ArgumentTable],
            uniformBuffer: MTLBuffer
        ) {
            self.commandBuffer = commandBuffer
            self.allocator = allocator
            self.vertexArguments = vertexArguments
            self.fragmentArguments = fragmentArguments
            self.computeArgumentTables = computeArgumentTables
            self.uniformBuffer = uniformBuffer
        }
    }

    private let device: MTLDevice
    private let queue: MTL4CommandQueue
    private let slots: [Slot]
    // The Java renderer bounds submissions to the same three-frame depth, but
    // it cannot wait for the oldest frame until submit(), which happens after
    // it has acquired the next command buffer. If all three GPU submissions
    // are still running, a fail-fast fourth acquire crashes resource reload.
    // Wait here for completion feedback instead, exactly where slot ownership
    // is transferred. The timeout preserves a bounded failure for a genuine
    // recording-without-submit bug.
    private let slotCondition = NSCondition()
    private static let slotAcquireTimeout: TimeInterval = 5.0
    private var nextSlot = 0
    private var begunCount: UInt64 = 0
    private var submittedCount: UInt64 = 0

    init?(_ device: MTLDevice, layer: CAMetalLayer?) {
        guard let residencySet = NativeState.residencySetStorage as? MTLResidencySet else {
            NSLog("[metallum] Metal 4 main renderer requires the global residency set")
            return nil
        }
        let queueDescriptor = MTL4CommandQueueDescriptor()
        queueDescriptor.label = "Metallum Main Queue (Metal 4)"
        guard let queue = try? device.makeMTL4CommandQueue(descriptor: queueDescriptor) else {
            return nil
        }
        var created: [Slot] = []
        for index in 0..<3 {
            let allocatorDescriptor = MTL4CommandAllocatorDescriptor()
            allocatorDescriptor.label = "Metallum Main Allocator \(index) (Metal 4)"
            let tableDescriptor = MTL4ArgumentTableDescriptor()
            tableDescriptor.maxBufferBindCount = 31
            tableDescriptor.maxTextureBindCount = 128
            tableDescriptor.maxSamplerStateBindCount = 16
            tableDescriptor.initializeBindings = true
            tableDescriptor.supportAttributeStrides = true
            tableDescriptor.label = "Metallum Main Arguments \(index) (Metal 4)"
            var computeArgumentTables: [MTL4ArgumentTable] = []
            for _ in 0..<Self.computeArgumentTableCount {
                guard let table = try? device.makeArgumentTable(descriptor: tableDescriptor) else {
                    return nil
                }
                computeArgumentTables.append(table)
            }
            guard let allocator = try? device.makeCommandAllocator(descriptor: allocatorDescriptor),
                  let commandBuffer = device.makeCommandBuffer(),
                  let vertexArguments = try? device.makeArgumentTable(descriptor: tableDescriptor),
                  let fragmentArguments = try? device.makeArgumentTable(descriptor: tableDescriptor),
                  let uniformBuffer = device.makeBuffer(length: 65_536, options: .storageModeShared) else {
                return nil
            }
            uniformBuffer.label = "Metallum Uniforms \(index) (Metal 4)"
            residencyTrackCreated(uniformBuffer)
            commandBuffer.label = "Metallum Main Buffer \(index) (Metal 4)"
            created.append(Slot(
                commandBuffer: commandBuffer,
                allocator: allocator,
                vertexArguments: vertexArguments,
                fragmentArguments: fragmentArguments,
                computeArgumentTables: computeArgumentTables,
                uniformBuffer: uniformBuffer
            ))
        }
        self.device = device
        self.queue = queue
        self.slots = created
        queue.addResidencySet(residencySet)
        if let layer {
            queue.addResidencySet(layer.residencySet)
        }
    }

    func commandBuffer(at index: Int) -> MTL4CommandBuffer { slots[index].commandBuffer }

    func argumentTables(at index: Int) -> (MTL4ArgumentTable, MTL4ArgumentTable) {
        let slot = slots[index]
        if slot.nextRenderArgumentTable >= slot.renderArgumentTables.count {
            let descriptor = MTL4ArgumentTableDescriptor()
            descriptor.maxBufferBindCount = 31
            descriptor.maxTextureBindCount = 128
            descriptor.maxSamplerStateBindCount = 16
            descriptor.initializeBindings = true
            descriptor.supportAttributeStrides = true
            descriptor.label = "Metallum Render Arguments (index) #(slot.nextRenderArgumentTable) (Metal 4)"
            if let vertex = try? device.makeArgumentTable(descriptor: descriptor),
               let fragment = try? device.makeArgumentTable(descriptor: descriptor) {
                slot.renderArgumentTables.append((vertex: vertex, fragment: fragment))
            } else {
                // Keep the renderer fail-soft if the driver refuses a table
                // allocation. The shared pair is still valid for the legacy
                // path, while normal MTL4 devices use one pair per encoder.
                return (slot.vertexArguments, slot.fragmentArguments)
            }
        }
        let tables = slot.renderArgumentTables[slot.nextRenderArgumentTable]
        slot.nextRenderArgumentTable += 1
        return (tables.vertex, tables.fragment)
    }

    func computeArgumentTable(at index: Int) -> MTL4ArgumentTable {
        if NativeState.freshComputeArgumentTables {
            let descriptor = MTL4ArgumentTableDescriptor()
            descriptor.maxBufferBindCount = 31
            descriptor.maxTextureBindCount = 128
            descriptor.maxSamplerStateBindCount = 16
            descriptor.initializeBindings = true
            descriptor.supportAttributeStrides = true
            descriptor.label = "Metallum Compute Dispatch Arguments (fresh)"
            if let table = try? device.makeArgumentTable(descriptor: descriptor) {
                return table
            }
        }
        let slot = slots[index]
        if slot.nextComputeArgumentTable < slot.computeArgumentTables.count {
            let table = slot.computeArgumentTables[slot.nextComputeArgumentTable]
            slot.nextComputeArgumentTable += 1
            return table
        }
        // Keep correctness if a future producer adds another dispatch before
        // this bounded pool is resized. Never silently alias an in-flight table.
        NSLog("[metallum] Metal 4 compute argument-table pool exhausted; allocating a fallback table")
        let descriptor = MTL4ArgumentTableDescriptor()
        descriptor.maxBufferBindCount = 31
        descriptor.maxTextureBindCount = 128
        descriptor.maxSamplerStateBindCount = 16
        descriptor.initializeBindings = true
        descriptor.supportAttributeStrides = true
        descriptor.label = "Metallum Compute Dispatch Arguments (overflow)"
        if let table = try? device.makeArgumentTable(descriptor: descriptor) {
            return table
        }
        return slot.computeArgumentTables[slot.computeArgumentTables.count - 1]
    }

    func writeClearUniforms(_ uniforms: MetallumClearUniforms, at slotIndex: Int) -> (MTLBuffer, Int)? {
        writeUniform(uniforms, at: slotIndex, alignment: 256)
    }

    func writeUniform<T>(_ value: T, at slotIndex: Int, alignment: Int = 16) -> (MTLBuffer, Int)? {
        let slot = slots[slotIndex]
        let effectiveAlignment = max(16, alignment)
        let aligned = (slot.uniformOffset + effectiveAlignment - 1) & ~(effectiveAlignment - 1)
        guard aligned + MemoryLayout<T>.stride <= slot.uniformBuffer.length else {
            return nil
        }
        var mutableValue = value
        withUnsafeBytes(of: &mutableValue) { bytes in
            slot.uniformBuffer.contents().advanced(by: aligned).copyMemory(
                from: bytes.baseAddress!,
                byteCount: bytes.count
            )
        }
        slot.uniformOffset = aligned + MemoryLayout<T>.stride
        return (slot.uniformBuffer, aligned)
    }

    func beginLease(label: String?) -> Metal4MainCommandBufferLease? {
        slotCondition.lock()
        var chosen: Int?
        let deadline = Date(timeIntervalSinceNow: Self.slotAcquireTimeout)
        repeat {
            for offset in 0..<slots.count {
                let index = (nextSlot + offset) % slots.count
                if slots[index].state == .free {
                    chosen = index
                    nextSlot = (index + 1) % slots.count
                    slots[index].state = .recording
                    begunCount += 1
                    break
                }
            }
            if chosen == nil && !slotCondition.wait(until: deadline) {
                break
            }
        } while chosen == nil
        guard let index = chosen else {
            let recording = slots.filter { $0.state == .recording }.count
            let submitted = slots.filter { $0.state == .submitted }.count
            slotCondition.unlock()
            NSLog(
                "[metallum] timed out waiting for Metal 4 main command-buffer slot "
                    + "(recording=\(recording), submitted=\(submitted))"
            )
            return nil
        }
        slotCondition.unlock()
        let slot = slots[index]
        slot.uniformOffset = 0
        slot.nextComputeArgumentTable = 0
        slot.nextRenderArgumentTable = 0
        slot.allocator.reset()
        slot.commandBuffer.beginCommandBuffer(allocator: slot.allocator)
        if NativeState.debugLabelsEnabled {
            slot.commandBuffer.label = label
        }
        return Metal4MainCommandBufferLease(owner: self, slotIndex: index)
    }

    func submit(_ lease: Metal4MainCommandBufferLease, signal semaphore: DispatchSemaphore?) {
        slotCondition.lock()
        guard slots[lease.slotIndex].state == .recording else {
            slotCondition.unlock()
            return
        }
        slots[lease.slotIndex].state = .submitted
        submittedCount += 1
        slotCondition.unlock()

        residencyFlushBeforeSubmit()
        let commandBuffer = slots[lease.slotIndex].commandBuffer
        commandBuffer.endCommandBuffer()
        lease.markSubmitted()
        let options = MTL4CommitOptions()
        options.addFeedbackHandler { [self, lease] feedback in
            let completionHandlers = lease.markCompleted(
                error: feedback.error,
                gpuStartTime: feedback.gpuStartTime,
                gpuEndTime: feedback.gpuEndTime
            )
            self.slotCondition.lock()
            self.slots[lease.slotIndex].state = .free
            self.slotCondition.broadcast()
            self.slotCondition.unlock()
            semaphore?.signal()
            // A completion callback may start encoding the next unit of work.
            // Run it only after the completed slot is visible to acquire.
            for handler in completionHandlers {
                handler(feedback.error, feedback.gpuStartTime, feedback.gpuEndTime)
            }
        }
        if let drawable = lease.presentDrawable {
            queue.waitForDrawable(drawable)
        }
        queue.commit([commandBuffer], options: options)
        for (event, value) in lease.postCommitSignals {
            queue.signalEvent(event, value: value)
        }
        lease.postCommitSignals.removeAll()
        if let drawable = lease.presentDrawable {
            queue.signalDrawable(drawable)
            drawable.present()
            lease.presentDrawable = nil
        }
    }

    func stats() -> (UInt64, UInt64, UInt64) {
        slotCondition.lock()
        defer { slotCondition.unlock() }
        return (begunCount, submittedCount, begunCount > 3 ? begunCount - 3 : 0)
    }
}

@available(macOS 26.0, iOS 26.0, *)
private final class Metal4MainRenderEncoderBridge {
    let encoder: MTL4RenderCommandEncoder
    let lease: Metal4MainCommandBufferLease
    private let vertexArguments: MTL4ArgumentTable
    private let fragmentArguments: MTL4ArgumentTable
    private var vertexBuffers = Array<MTLBuffer?>(repeating: nil, count: 31)
    private var fragmentBuffers = Array<MTLBuffer?>(repeating: nil, count: 31)

    init(
        encoder: MTL4RenderCommandEncoder,
        lease: Metal4MainCommandBufferLease,
        vertexArguments: MTL4ArgumentTable,
        fragmentArguments: MTL4ArgumentTable
    ) {
        self.encoder = encoder
        self.lease = lease
        self.vertexArguments = vertexArguments
        self.fragmentArguments = fragmentArguments
        encoder.setArgumentTable(vertexArguments, stages: MTLRenderStages.vertex)
        encoder.setArgumentTable(fragmentArguments, stages: MTLRenderStages.fragment)
    }

    func setBuffer(_ buffer: MTLBuffer?, offset: Int, index: Int, stageMask: Int32) {
        guard index >= 0, index < 31 else {
            NSLog("[metallum] Metal 4 rejected buffer binding index %d (maximum 30)", index)
            return
        }
        guard let buffer else {
            NSLog("[metallum] Metal 4 rejected null buffer binding at index %d", index)
            return
        }
        if (stageMask & 1) != 0 {
            vertexBuffers[index] = buffer
            vertexArguments.setAddress(buffer.gpuAddress + UInt64(offset), index: index)
        }
        if (stageMask & 2) != 0 {
            fragmentBuffers[index] = buffer
            fragmentArguments.setAddress(buffer.gpuAddress + UInt64(offset), index: index)
        }
    }

    func setBufferOffset(_ offset: Int, index: Int, stageMask: Int32) {
        guard index >= 0, index < 31 else { return }
        if (stageMask & 1) != 0, let buffer = vertexBuffers[index] {
            vertexArguments.setAddress(buffer.gpuAddress + UInt64(offset), index: index)
        }
        if (stageMask & 2) != 0, let buffer = fragmentBuffers[index] {
            fragmentArguments.setAddress(buffer.gpuAddress + UInt64(offset), index: index)
        }
    }

    func setTexture(_ texture: MTLTexture?, index: Int, stageMask: Int32) {
        guard index >= 0, index < 128 else { return }
        let resourceID = texture?.gpuResourceID ?? MTLResourceID()
        if (stageMask & 1) != 0 { vertexArguments.setTexture(resourceID, index: index) }
        if (stageMask & 2) != 0 { fragmentArguments.setTexture(resourceID, index: index) }
    }

    func setTextureAndSampler(
        _ texture: MTLTexture?,
        sampler: MTLSamplerState?,
        index: Int,
        stageMask: Int32
    ) {
        guard index >= 0, index < 16 else { return }
        let textureID = texture?.gpuResourceID ?? MTLResourceID()
        let samplerID = sampler?.gpuResourceID ?? MTLResourceID()
        if (stageMask & 1) != 0 {
            vertexArguments.setTexture(textureID, index: index)
            vertexArguments.setSamplerState(samplerID, index: index)
        }
        if (stageMask & 2) != 0 {
            fragmentArguments.setTexture(textureID, index: index)
            fragmentArguments.setSamplerState(samplerID, index: index)
        }
    }
}

@available(macOS 26.0, iOS 26.0, *)
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

@available(macOS 26.0, iOS 26.0, *)
private func metal4MainLease(_ pointer: UnsafeMutableRawPointer) -> Metal4MainCommandBufferLease? {
    return Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? Metal4MainCommandBufferLease
}

private func metal3CommandBuffer(_ pointer: UnsafeMutableRawPointer) -> MTLCommandBuffer {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLCommandBuffer
}

private func commandBufferPointer(_ commandBuffer: MTLCommandBuffer) -> UnsafeMutableRawPointer {
    UnsafeMutableRawPointer(Unmanaged.passUnretained(commandBuffer).toOpaque())
}

@available(macOS 26.0, iOS 26.0, *)
private func encodeMetal4Compute<T>(
    lease: Metal4MainCommandBufferLease,
    label: String,
    pipeline: MTLComputePipelineState,
    uniforms: T,
    textures: [(Int, MTLTexture?)],
    width: Int,
    height: Int,
    afterStages: MTLStages = [.vertex, .fragment, .dispatch, .blit],
    producerBarrierBeforeStages: MTLStages = []
) -> Bool {
    guard let encoder = lease.commandBuffer.makeComputeCommandEncoder(),
          let (uniformBuffer, uniformOffset) = lease.owner.writeUniform(
              uniforms,
              at: lease.slotIndex,
              alignment: 256
          ) else {
        return false
    }
    encoder.label = label
    encoder.barrier(
        afterQueueStages: afterStages,
        beforeStages: .dispatch,
        visibilityOptions: .device
    )
    let arguments = lease.owner.computeArgumentTable(at: lease.slotIndex)
    arguments.setAddress(uniformBuffer.gpuAddress + UInt64(uniformOffset), index: 0)
    for (index, texture) in textures {
        arguments.setTexture(texture?.gpuResourceID ?? MTLResourceID(), index: index)
    }
    encoder.setArgumentTable(arguments)
    encoder.setComputePipelineState(pipeline)
    let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
    let threadHeight = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth))
    encoder.dispatchThreads(
        threadsPerGrid: MTLSize(width: width, height: height, depth: 1),
        threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
    )
    if !producerBarrierBeforeStages.isEmpty {
        // MetalFX owns the encoders it appends, so its consumer pass is not
        // available to this code for a consumer barrier. Publish this dispatch
        // to every stage its opaque implementation may use instead.
        encoder.barrier(
            afterStages: .dispatch,
            beforeQueueStages: producerBarrierBeforeStages,
            visibilityOptions: .device
        )
    }
    encoder.endEncoding()
    NativeState.metal4AuxiliaryComputeEncodeCount &+= 1
    return true
}

#if os(macOS) && canImport(MetalFX)
@available(macOS 26.0, *)
struct MetalFrameGenerationDiagnosticSnapshot {
    let presentPath: String
    let sourceFrameID: UInt64
    let frameKind: String
    let displayUpdateID: UInt64
    let targetTimestamp: CFTimeInterval
    let targetPresentationTimestamp: CFTimeInterval
    let cpuCommitTime: CFTimeInterval
    let sourceEnqueueTime: CFTimeInterval
    let sourceCpuWaitTime: CFTimeInterval
    let sourceGpuStartTime: CFTimeInterval
    let sourceGpuEndTime: CFTimeInterval
    let gpuStartTime: CFTimeInterval
    let gpuEndTime: CFTimeInterval
    let gpuCompletionTime: CFTimeInterval
    let presentedTime: CFTimeInterval
    let outcome: String
}

/// Metal 4 side of the frame-generation present path (migration spec M4).
///
/// This is the migration's first MTL4 queue, and the present thread is the pilot
/// because it is the smallest self-contained surface: no Java ABI crosses it, one
/// command buffer carries at most an interpolator encode plus a three-vertex copy
/// pass, the binding surface is one texture and one sampler, and — decisively —
/// it touches no MTLFence. Metal 4 fences are same-queue only, so a pilot that
/// used fences would collide with the main queue's fence chain immediately. The
/// cross-queue ordering here is already an MTLSharedEvent, and shared events work
/// between a Metal 3 and a Metal 4 queue: the main Metal 3 queue keeps signalling
/// exactly as before and only the wait side moves.
///
/// The presenter keeps owning all lifecycle, deadline and diagnostic state; this
/// type owns only the Metal 4 mechanics.
private let metalFrameGenerationDrawableCount: Int = {
    guard let value = ProcessInfo.processInfo.environment[
        "METALLUM_FRAME_GENERATION_DRAWABLE_COUNT"
    ], let parsed = Int(value) else {
        return 2
    }
    return min(max(parsed, 2), 3)
}()

let metalFrameGenerationPreferredFrameLatency: Float = {
    guard let value = ProcessInfo.processInfo.environment[
        "METALLUM_FRAME_GENERATION_PREFERRED_LATENCY"
    ], let parsed = Float(value), parsed.isFinite else {
        // CAMetalDisplayLink's documented default and Apple's current game
        // porting reference both use two frames. A forced value of one left
        // full-resolution interpolation with no scheduling margin at 120 Hz.
        return 2.0
    }
    // CAMetalDisplayLink accepts only the documented discrete values 1 or 2.
    // Treat every other override as invalid instead of forwarding a clamped
    // fractional value (or the previously accepted but unsupported value 3).
    return parsed == 1.0 || parsed == 2.0 ? parsed : 2.0
}()

@available(macOS 26.0, *)
final class Metal4PresentPath {
    private enum SlotState: Equatable {
        case free
        case recording
        case submitted
    }

    private final class FrameSlot {
        let commandBuffer: MTL4CommandBuffer
        let allocator: MTL4CommandAllocator
        var state: SlotState = .free

        init(commandBuffer: MTL4CommandBuffer, allocator: MTL4CommandAllocator) {
            self.commandBuffer = commandBuffer
            self.allocator = allocator
        }
    }

    /// Matches the layer drawable pool exactly. The default remains two for
    /// minimum latency; the bounded 2/3-drawable A/B override lets validation
    /// prove whether triple buffering recovers display updates on a given GPU.
    static let inFlightSlotCount = metalFrameGenerationDrawableCount

    private let queue: MTL4CommandQueue
    private let slots: [FrameSlot]
    private let argumentTable: MTL4ArgumentTable
    private let residencySet: MTLResidencySet
    private let slotLock = NSLock()
    /// Only the display-link callback records commands, so at most one slot is
    /// recording. Completion feedback can release submitted slots concurrently.
    private var recordingSlotIndex: Int?

    init?(device: MTLDevice, layer: CAMetalLayer) {
        let queueDescriptor = MTL4CommandQueueDescriptor()
        // MTL4CommandQueue.label is get-only, unlike MTLCommandQueue's: the label
        // has to come from the descriptor.
        queueDescriptor.label = "MetalFX Frame Generation Present (Metal 4)"
        guard let queue = try? device.makeMTL4CommandQueue(descriptor: queueDescriptor) else {
            return nil
        }
        var slots: [FrameSlot] = []
        for index in 0..<Self.inFlightSlotCount {
            let allocatorDescriptor = MTL4CommandAllocatorDescriptor()
            allocatorDescriptor.label = "MetalFX Frame Generation Allocator \(index)"
            guard let allocator = try? device.makeCommandAllocator(descriptor: allocatorDescriptor),
                  let commandBuffer = device.makeCommandBuffer() else {
                return nil
            }
            commandBuffer.label = "MetalFX Frame Generation Present \(index) (Metal 4)"
            slots.append(FrameSlot(commandBuffer: commandBuffer, allocator: allocator))
        }
        let tableDescriptor = MTL4ArgumentTableDescriptor()
        tableDescriptor.maxTextureBindCount = 2
        tableDescriptor.maxSamplerStateBindCount = 1
        // Unbound slots must read as a defined empty value; without this they are
        // undefined behaviour.
        tableDescriptor.initializeBindings = true
        let residencyDescriptor = MTLResidencySetDescriptor()
        residencyDescriptor.label = "MetalFX Frame Generation Residency"
        residencyDescriptor.initialCapacity = 32
        guard let argumentTable = try? device.makeArgumentTable(descriptor: tableDescriptor),
              let residencySet = try? device.makeResidencySet(descriptor: residencyDescriptor) else {
            return nil
        }
        self.queue = queue
        self.slots = slots
        self.argumentTable = argumentTable
        self.residencySet = residencySet
        queue.addResidencySet(residencySet)
        // Read-only and drawable-tracking: never add anything to it by hand.
        queue.addResidencySet(layer.residencySet)
    }

    /// Republishes the presenter's texture set after every rebuild. Metal 4 has no
    /// automatic residency, so a texture missing here is read as unmapped memory.
    /// Memoryless textures are excluded: they have no backing allocation.
    func adopt(textures: [MTLTexture]) {
        residencySet.removeAllAllocations()
        residencySet.addAllocations(textures.filter { $0.storageMode != .memoryless })
        residencySet.commit()
        residencySet.requestResidency()
    }

    /// Starts a frame without waiting. A slot remains unavailable until Metal's
    /// commit feedback proves its previous GPU submission complete, which is the
    /// precondition for allocator.reset(). If both drawable-backed submissions
    /// are still in flight, the display-link update is dropped by the caller.
    func beginFrame() -> MTL4CommandBuffer? {
        slotLock.lock()
        guard recordingSlotIndex == nil,
              let index = slots.firstIndex(where: { $0.state == .free }) else {
            slotLock.unlock()
            return nil
        }
        let slot = slots[index]
        slot.state = .recording
        recordingSlotIndex = index
        slotLock.unlock()

        slot.allocator.reset()
        slot.commandBuffer.beginCommandBuffer(allocator: slot.allocator)
        return slot.commandBuffer
    }

    private func endRecordingForSubmission() -> (Int, FrameSlot)? {
        slotLock.lock()
        guard let index = recordingSlotIndex else {
            slotLock.unlock()
            return nil
        }
        let slot = slots[index]
        recordingSlotIndex = nil
        slot.state = .submitted
        slotLock.unlock()
        slot.commandBuffer.endCommandBuffer()
        return (index, slot)
    }

    private func releaseSubmittedSlot(_ index: Int) {
        slotLock.lock()
        if slots[index].state == .submitted {
            slots[index].state = .free
        }
        slotLock.unlock()
    }

    var availableFrameSlotCount: Int {
        slotLock.lock()
        defer { slotLock.unlock() }
        return slots.reduce(0) { $0 + ($1.state == .free ? 1 : 0) }
    }

    /// The full-screen copy, with the texture and sampler routed through the
    /// argument table instead of setFragmentTexture / setFragmentSamplerState.
    /// The pipeline is the presenter's ordinary Metal 3 copy PSO; Metal 3 and
    /// Metal 4 pipeline states interoperate (checked by metal4PipelineSmokeTest).
    func encodeCopy(
        commandBuffer: MTL4CommandBuffer,
        source: MTLTexture,
        destination: MTLTexture,
        pipeline: MTLRenderPipelineState,
        sampler: MTLSamplerState,
        loadAction: MTLLoadAction = .dontCare,
        label: String
    ) -> Bool {
        let descriptor = MTL4RenderPassDescriptor()
        descriptor.colorAttachments[0].texture = destination
        descriptor.colorAttachments[0].loadAction = loadAction
        descriptor.colorAttachments[0].storeAction = .store
        // MTL4RenderPassDescriptor carries no attachment size implicitly.
        descriptor.renderTargetWidth = destination.width
        descriptor.renderTargetHeight = destination.height
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return false
        }
        encoder.label = label
        argumentTable.setTexture(source.gpuResourceID, index: 0)
        argumentTable.setSamplerState(sampler.gpuResourceID, index: 0)
        encoder.setArgumentTable(argumentTable, stages: .fragment)
        encoder.setRenderPipelineState(pipeline)
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destination.width),
            height: Double(destination.height),
            znear: 0.0,
            zfar: 1.0
        ))
        encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return true
    }

    func encodeComposite(
        commandBuffer: MTL4CommandBuffer,
        scene: MTLTexture,
        ui: MTLTexture,
        destination: MTLTexture,
        pipeline: MTLRenderPipelineState,
        sampler: MTLSamplerState,
        synchronizePreviousWrites: Bool,
        label: String
    ) -> Bool {
        let descriptor = MTL4RenderPassDescriptor()
        descriptor.colorAttachments[0].texture = destination
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.renderTargetWidth = destination.width
        descriptor.renderTargetHeight = destination.height
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return false
        }
        encoder.label = label
        if synchronizePreviousWrites {
            // MTL4FXFrameInterpolator may produce its output through several
            // queue stages. Metal 4 resources are untracked, so the following
            // fragment read needs an explicit consumer barrier; command order
            // alone is not a memory dependency.
            encoder.barrier(
                afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
                beforeStages: .fragment,
                visibilityOptions: .device
            )
        }
        argumentTable.setTexture(scene.gpuResourceID, index: 0)
        argumentTable.setTexture(ui.gpuResourceID, index: 1)
        argumentTable.setSamplerState(sampler.gpuResourceID, index: 0)
        encoder.setArgumentTable(argumentTable, stages: .fragment)
        encoder.setRenderPipelineState(pipeline)
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destination.width),
            height: Double(destination.height),
            znear: 0.0,
            zfar: 1.0
        ))
        encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return true
    }

    /// Closes the command buffer and presents.
    ///
    /// The readyEvent wait lives here, deliberately, and taking it is the whole
    /// reason this method owns it rather than exposing a separate wait call.
    /// Metal 3 recorded the wait *into* the command buffer
    /// (encodeWaitForEvent), so dropping an unsubmitted buffer dropped the wait
    /// with it — which is what lets present(_:) return from four places after
    /// encoding. Metal 4's queue.waitForEvent is a queue-timeline operation that
    /// takes effect when called: issued before those early returns it would leave
    /// an orphan wait that nothing ever satisfies (the deadline-miss return is hit
    /// in normal operation), and every later commit would queue behind it — a
    /// permanently wedged present queue with the display-link callback blocked in
    /// commit. Issuing it here means it is only ever reached once the frame is
    /// certain to be committed. Queue operations take effect in call order, so
    /// waiting immediately before commit on the same thread is equivalent.
    ///
    /// The four present steps are ordered and not interchangeable: waitForDrawable
    /// before commit, signalDrawable after it, then the drawable's own present.
    /// It is an ordinary present because CAMetalDisplayLink owns the drawable's
    /// scheduling, which makes targeted present illegal here, and it is
    /// synchronous so the commit still lands inside the needsUpdate callback — a
    /// present committed in a later run-loop pass reports presentedTime == 0.
    func submit(
        drawable: CAMetalDrawable,
        readyEvent: MTLSharedEvent,
        eventValue: UInt64,
        onCompleted: @escaping (Error?, CFTimeInterval, CFTimeInterval) -> Void
    ) {
        guard let (slotIndex, slot) = endRecordingForSubmission() else {
            return
        }
        let options = MTL4CommitOptions()
        // MTL4CommandBufferFeedback has no status, only error: succeeded is
        // error == nil.
        options.addFeedbackHandler { [weak self] feedback in
            self?.releaseSubmittedSlot(slotIndex)
            onCompleted(feedback.error, feedback.gpuStartTime, feedback.gpuEndTime)
        }
        queue.waitForEvent(readyEvent, value: eventValue)
        queue.waitForDrawable(drawable)
        queue.commit([slot.commandBuffer], options: options)
        queue.signalDrawable(drawable)
        drawable.present()
    }

    /// Abandons a frame that will not be submitted, so the reusable command buffer
    /// is not left open across frames. Idempotent, so every early return can call
    /// it without tracking whether an earlier one already did.
    func abandonFrame() {
        slotLock.lock()
        guard let index = recordingSlotIndex else {
            slotLock.unlock()
            return
        }
        let slot = slots[index]
        recordingSlotIndex = nil
        slotLock.unlock()

        slot.commandBuffer.endCommandBuffer()
        slotLock.lock()
        slot.state = .free
        slotLock.unlock()
    }
}

@available(macOS 26.0, *)
final class MetalFrameGenerationPresenter: NSObject, CAMetalDisplayLinkDelegate {
    private struct PendingFrame {
        let sourceFrameID: UInt64
        let index: Int
        let eventValue: UInt64
        let timestamp: CFTimeInterval
        let cpuWaitDuration: CFTimeInterval
        let inputWidth: Int
        let inputHeight: Int
        let frameGenerationWidth: Int
        let frameGenerationHeight: Int
        let nativeWidth: Int
        let nativeHeight: Int
        let jitterX: Float
        let jitterY: Float
        let fieldOfView: Float
        let nearPlane: Float
        let farPlane: Float
        let aspectRatio: Float
        // Render-timeline interval between this source frame and the previous
        // one, measured by the game at scene-frame start. 0 or non-finite
        // means "unknown"; the presenter then falls back to enqueue spacing.
        let sourceDelta: Float
        let reset: Bool
    }

    // Value carrier for one display-link update. It is only ever passed down
    // the synchronous callback -> present call chain; the drawable must never
    // be retained past the delegate callback. WindowServer drops presents that
    // are committed after metalDisplayLink(_:needsUpdate:) returns
    // (drawable.presentedTime == 0), so deferring the drawable to another
    // thread or a later run-loop pass silently blanks every frame.
    private struct DisplayUpdate {
        let updateID: UInt64
        let drawable: CAMetalDrawable
        let targetTimestamp: CFTimeInterval
        let targetPresentationTimestamp: CFTimeInterval
    }

    private struct PresentationWork {
        let frame: PendingFrame
        let update: DisplayUpdate
        let step: MetalFrameGenerationPresentationStep
        let previousIndex: Int
        let shouldResetHistory: Bool
        let deltaTime: Float
    }

    private struct FrameDiagnostic {
        let sourceFrameID: UInt64
        let frameKind: String
        let displayUpdateID: UInt64
        let targetTimestamp: CFTimeInterval
        let targetPresentationTimestamp: CFTimeInterval
        var cpuCommitTime: CFTimeInterval
        let sourceEnqueueTime: CFTimeInterval
        let sourceCpuWaitTime: CFTimeInterval
        let inputWidth: Int
        let inputHeight: Int
        let frameGenerationWidth: Int
        let frameGenerationHeight: Int
        let nativeWidth: Int
        let nativeHeight: Int
        var sourceGpuStartTime: CFTimeInterval
        var sourceGpuEndTime: CFTimeInterval
        var gpuStartTime: CFTimeInterval
        var gpuEndTime: CFTimeInterval
        var gpuCompletionTime: CFTimeInterval
        var presentedTime: CFTimeInterval
        var outcome: String
    }

    private struct SourceAdmissionDiagnostic {
        let sourceFrameID: UInt64
        let enqueueTime: CFTimeInterval
        let cpuWaitDuration: CFTimeInterval
    }

    private struct TextureSet {
        let scene: [MTLTexture]
        let nativeScene: [MTLTexture]
        let uiOverlay: [MTLTexture]
        let depth: [MTLTexture]
        let motion: [MTLTexture]
        let interpolation: [MTLTexture]
    }

    private static let bufferCount = 3
    // Keep the active source plus one ready successor. Without the successor,
    // the render thread starts the next source after real-present completion and
    // regularly misses the immediately following 120 Hz display update.
    private static let maxOutstandingFrames = 2
    private static let diagnosticCapacity = 256
    private static let sourceAdmissionCapacity = 1024
    private static let presentationCallbackTimeout: CFTimeInterval = 0.25
    private static let displayUpdateStarvationTimeout: CFTimeInterval = 0.75
    // Six 120 Hz refresh periods distinguish a briefly busy presenter from an
    // occluded/locked display. A foreground source waits for ownership; once
    // updates go stale, later sources immediately switch to latest-source-wins.
    private static let displayUpdateActivityTimeout: CFTimeInterval = 0.05
    // Bound foreground admission independently of callback activity. This still
    // allows several refreshes for a genuine GPU spike, while a wedged presenter
    // cannot hold Minecraft's render thread indefinitely.
    private static let maxActiveAdmissionWait: CFTimeInterval = 0.05

    private let device: MTLDevice
    private let layer: CAMetalLayer
    private let presentQueue: MTLCommandQueue
    private let readyEvent: MTLSharedEvent
    private var frameInterpolator: any MTLFXFrameInterpolator
    private var copyPipeline: MTLRenderPipelineState
    private var fusedPresentPipeline: MTLRenderPipelineState
    private var motionResamplePipeline: MTLRenderPipelineState
    private var depthResamplePipeline: MTLRenderPipelineState
    private var depthResampleState: MTLDepthStencilState
    private var copySampler: MTLSamplerState
    private var inputResampleSampler: MTLSamplerState
    private var copyFormat: MTLPixelFormat
    // Metal 4 present path (spec M4), non-nil only when metallum.opt.metal4Present
    // and the capability gate both hold and construction succeeded. Nil means
    // present() takes the unchanged Metal 3 branch.
    private var metal4Path: Metal4PresentPath?
    // The MTL4 interpolator encodes into an MTL4CommandBuffer, so it cannot be the
    // same object as frameInterpolator. Both exist while the switch is on: keeping
    // the Metal 3 one lets the Metal 3 branch stay untouched, at the cost of a
    // second set of MetalFX internal resources on an experimental path.
    private var metal4Interpolator: (any MTL4FXFrameInterpolator)?

    private var sceneBuffers: [MTLTexture] = []
    private var nativeSceneBuffers: [MTLTexture] = []
    private var uiOverlayBuffers: [MTLTexture] = []
    private var depthBuffers: [MTLTexture] = []
    private var motionBuffers: [MTLTexture] = []
    private var interpolationOutputs: [MTLTexture] = []

    private var outputWidth: Int
    private var outputHeight: Int
    private var uiWidth: Int
    private var uiHeight: Int
    private var outputFormat: MTLPixelFormat
    private var depthFormat: MTLPixelFormat
    private var motionFormat: MTLPixelFormat
    private var nextBufferIndex = 0
    private var nextEventValue: UInt64 = 1
    private var nextSourceFrameID: UInt64 = 1
    private var nextDisplayUpdateID: UInt64 = 1
    private var lastPresentedIndex: Int?
    private var lastPresentedTimestamp: CFTimeInterval?
    private var displayLink: CAMetalDisplayLink?
    private var displayLinkInstallationTime: CFTimeInterval?
    private var lastDisplayUpdateTime: CFTimeInterval?
    private var currentFrame: PendingFrame?
    private var currentLifecycle: MetalFrameGenerationLifecycle?
    private var queuedFrame: PendingFrame?
    private var queuedLifecycle: MetalFrameGenerationLifecycle?
    private var activePreviousIndex: Int?
    private var activeShouldResetHistory = true
    private var activeDeltaTime: Float = 1.0 / 60.0
    private var historyOwnership = MetalFrameGenerationHistoryOwnership()
    private var realPresentationTimeoutAt: CFTimeInterval?
    private var displayUpdateStarvationTimeoutAt: CFTimeInterval?
    private var diagnostics: [FrameDiagnostic] = []
    private var sourceAdmissions: [SourceAdmissionDiagnostic] = []
    private var sourceGpuTimings: [UInt64: (start: CFTimeInterval, end: CFTimeInterval)] = [:]
    private var diagnosticsDumped = false
    private var droppedDisplayUpdates = 0
    private var presentationDeadlineMisses = 0
    private var supersededSourceFrames = 0

    private let condition = NSCondition()
    private var outstandingFrames = 0
    private var stopping = false
    private var workerExited = false
    private var worker: Thread?
    // Set when the render thread reconfigures the surface. CAMetalLayer
    // properties may only be changed after a present, so the presenter restates
    // the ones it owns from inside the display-link callback instead of letting
    // the render thread race the present it is about to commit.
    private var pendingLayerPolicyRefresh = false

    init?(
        device: MTLDevice,
        layer: CAMetalLayer,
        sceneColor: MTLTexture,
        nativeSceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture,
        inputWidth: Int,
        inputHeight: Int
    ) {
        guard nativeSceneColor.width == uiColor.width,
              nativeSceneColor.height == uiColor.height,
              nativeSceneColor.pixelFormat == uiColor.pixelFormat else {
            return nil
        }
        guard let presentQueue = device.makeCommandQueue(),
              let readyEvent = device.makeSharedEvent(),
              let copyPipeline = buildPresentPipeline(device: device, colorFormat: layer.pixelFormat),
              let fusedPresentPipeline = buildFusedPresentPipeline(
                  device: device,
                  colorFormat: layer.pixelFormat
              ),
              let motionResamplePipeline = buildPresentPipeline(
                  device: device,
                  colorFormat: motion.pixelFormat
              ),
              let depthResamplePipeline = buildDepthResamplePipeline(
                  device: device,
                  depthFormat: depth.pixelFormat
              ),
              let depthResampleState = buildDepthResampleState(device: device),
              let copySampler = buildPresentSampler(device: device, filter: .linear),
              let inputResampleSampler = buildPresentSampler(device: device, filter: .nearest),
              let frameInterpolator = Self.makeFrameInterpolator(
                  device: device,
                  sceneColor: sceneColor,
                  uiColor: uiColor,
                  depth: depth,
                  motion: motion
              ) else {
            return nil
        }

        self.device = device
        self.layer = layer
        self.presentQueue = presentQueue
        self.readyEvent = readyEvent
        self.frameInterpolator = frameInterpolator
        self.copyPipeline = copyPipeline
        self.fusedPresentPipeline = fusedPresentPipeline
        self.motionResamplePipeline = motionResamplePipeline
        self.depthResamplePipeline = depthResamplePipeline
        self.depthResampleState = depthResampleState
        self.copySampler = copySampler
        self.inputResampleSampler = inputResampleSampler
        self.copyFormat = layer.pixelFormat
        self.outputWidth = sceneColor.width
        self.outputHeight = sceneColor.height
        self.uiWidth = uiColor.width
        self.uiHeight = uiColor.height
        self.outputFormat = sceneColor.pixelFormat
        self.depthFormat = depth.pixelFormat
        self.motionFormat = motion.pixelFormat
        layer.maximumDrawableCount = metalFrameGenerationDrawableCount
        // A hidden or minimized window may not recycle drawables promptly.
        // Let the present thread time out and fall back to the rendered frame
        // instead of blocking shutdown or the next resize forever.
        layer.allowsNextDrawableTimeout = true
        // CAMetalDisplayLink only schedules updates on the display's refresh
        // boundary, so the presenter is a vsync-on loop by construction. Java
        // gates frame generation off in the immediate present mode, but a
        // surface reconfigure lands on the render thread and can arrive before
        // that gate takes effect for the frame already in flight.
        layer.displaySyncEnabled = true
        presentQueue.label = "MetalFX Frame Generation Present"
        readyEvent.label = "MetalFX Frame Generation Ready"
        // Metal 4 pilot (spec M4). Built only when asked for and supported; any
        // failure leaves metal4Path nil and the Metal 3 path runs unchanged. The
        // Metal 3 presentQueue above is still created either way, because the
        // render thread's own submissions and the readyEvent signalling side stay
        // on Metal 3 regardless.
        if NativeState.metal4PresentEnabled, device.supportsFamily(.metal4) {
            if let path = Metal4PresentPath(device: device, layer: layer),
               let interpolator = Self.makeMetal4FrameInterpolator(
                   device: device,
                   sceneColor: sceneColor,
                   uiColor: uiColor,
                   depth: depth,
                   motion: motion
               ) {
                self.metal4Path = path
                self.metal4Interpolator = interpolator
                NSLog("[metallum] frame generation present path: Metal 4")
            } else {
                NSLog("[metallum] Metal 4 present path unavailable; using Metal 3")
            }
        }
        super.init()

        guard rebuildTextures(
            outputWidth: sceneColor.width,
            outputHeight: sceneColor.height,
            uiWidth: uiColor.width,
            uiHeight: uiColor.height,
            outputFormat: sceneColor.pixelFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat,
            depthWidth: inputWidth,
            depthHeight: inputHeight,
            motionWidth: inputWidth,
            motionHeight: inputHeight
        ) else {
            return nil
        }
        guard let workInterpolator = Self.makeFrameInterpolator(
            device: device,
            sceneColor: sceneBuffers[0],
            uiColor: uiOverlayBuffers[0],
            depth: depthBuffers[0],
            motion: motionBuffers[0]
        ) else {
            return nil
        }
        self.frameInterpolator = workInterpolator
        if metal4Path != nil {
            self.metal4Interpolator = Self.makeMetal4FrameInterpolator(
                device: device,
                sceneColor: sceneBuffers[0],
                uiColor: uiOverlayBuffers[0],
                depth: depthBuffers[0],
                motion: motionBuffers[0]
            )
            if metal4Interpolator == nil {
                self.metal4Path = nil
            }
        }

        let worker = Thread { [weak self] in
            self?.runWorker()
        }
        worker.name = "MetalFX PresentThread"
        worker.qualityOfService = .userInteractive
        self.worker = worker
        worker.start()
    }

    deinit {
        shutdown()
    }

    private static func compatibleLinkedTemporalScaler(
        sceneColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) -> (any MTLFXFrameInterpolatableScaler)? {
        guard let scaler = NativeState.lastTemporalScalerForInterpolation
                    as? (any MTLFXTemporalScalerBase),
              scaler.inputWidth == depth.width,
              scaler.inputHeight == depth.height,
              scaler.outputWidth == sceneColor.width,
              scaler.outputHeight == sceneColor.height,
              scaler.outputTextureFormat == sceneColor.pixelFormat,
              scaler.depthTextureFormat == depth.pixelFormat,
              scaler.motionTextureFormat == motion.pixelFormat else {
            return nil
        }
        return scaler
    }

    private static func makeFrameInterpolator(
        device: MTLDevice,
        sceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) -> (any MTLFXFrameInterpolator)? {
        let descriptor = MTLFXFrameInterpolatorDescriptor()
        descriptor.colorTextureFormat = sceneColor.pixelFormat
        descriptor.outputTextureFormat = sceneColor.pixelFormat
        descriptor.depthTextureFormat = depth.pixelFormat
        descriptor.motionTextureFormat = motion.pixelFormat
        descriptor.inputWidth = depth.width
        descriptor.inputHeight = depth.height
        descriptor.outputWidth = sceneColor.width
        descriptor.outputHeight = sceneColor.height
        // Link the active temporal scaler so MetalFX shares internal state
        // between upscaling and interpolation (WWDC25 guidance). If linking
        // is dimensionally incompatible with the bounded FrameGen work
        // resolution, or rejected on this device/SDK, use a standalone
        // interpolator. MetalFX can accept an incompatible scaler at creation
        // and then assert on the first color texture assignment, so the size
        // and format contract has to be checked here.
        if let linked = compatibleLinkedTemporalScaler(
            sceneColor: sceneColor,
            depth: depth,
            motion: motion
        ) {
            descriptor.scaler = linked
            if let interpolator = descriptor.makeFrameInterpolator(device: device) {
                return interpolator
            }
            descriptor.scaler = nil
        }
        return descriptor.makeFrameInterpolator(device: device)
    }

    /// MTL4 twin of makeFrameInterpolator. The descriptor fields are identical —
    /// MTLFXFrameInterpolator and MTL4FXFrameInterpolator share
    /// MTLFXFrameInterpolatorBase — only the factory differs, taking an
    /// MTL4Compiler. Scaler linking is attempted and abandoned on failure exactly
    /// as on the Metal 3 path; the recorded scaler is a Metal 3 one while the
    /// upscaling path is still Metal 3, so the link is expected to be refused more
    /// often here.
    @available(macOS 26.0, *)
    private static func makeMetal4FrameInterpolator(
        device: MTLDevice,
        sceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) -> (any MTL4FXFrameInterpolator)? {
        guard let compiler = NativeState.metal4Compiler(device) else {
            return nil
        }
        let descriptor = MTLFXFrameInterpolatorDescriptor()
        descriptor.colorTextureFormat = sceneColor.pixelFormat
        descriptor.outputTextureFormat = sceneColor.pixelFormat
        descriptor.depthTextureFormat = depth.pixelFormat
        descriptor.motionTextureFormat = motion.pixelFormat
        descriptor.inputWidth = depth.width
        descriptor.inputHeight = depth.height
        descriptor.outputWidth = sceneColor.width
        descriptor.outputHeight = sceneColor.height
        if let linked = compatibleLinkedTemporalScaler(
            sceneColor: sceneColor,
            depth: depth,
            motion: motion
        ) {
            descriptor.scaler = linked
            if let interpolator = descriptor.makeFrameInterpolator(device: device, compiler: compiler) {
                return interpolator
            }
            descriptor.scaler = nil
        }
        return descriptor.makeFrameInterpolator(device: device, compiler: compiler)
    }

    private func makeTexture(
        pixelFormat: MTLPixelFormat,
        width: Int,
        height: Int,
        usage: MTLTextureUsage,
        label: String
    ) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = label
        return texture
    }

    private func makeTextureSet(
        outputWidth: Int,
        outputHeight: Int,
        uiWidth: Int,
        uiHeight: Int,
        outputFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        motionFormat: MTLPixelFormat,
        depthWidth: Int,
        depthHeight: Int,
        motionWidth: Int,
        motionHeight: Int
    ) -> TextureSet? {
        guard outputWidth > 0, outputHeight > 0, uiWidth > 0, uiHeight > 0 else {
            return nil
        }

        // Use the exact MetalFX requirements plus the present shader read.
        // On current Apple GPUs the inputs remain read-only, preserving lossless
        // compression, while this stays correct if a future implementation
        // advertises a stricter minimum usage.
        var sceneUsage = frameInterpolator.colorTextureUsage.union(.shaderRead)
        var uiUsage = frameInterpolator.uiTextureUsage.union(.shaderRead)
        var depthUsage = frameInterpolator.depthTextureUsage.union([.shaderRead, .renderTarget])
        var motionUsage = frameInterpolator.motionTextureUsage.union([.shaderRead, .renderTarget])
        var interpolationUsage = frameInterpolator.outputTextureUsage.union(.shaderRead)
        if let metal4Interpolator {
            sceneUsage.formUnion(metal4Interpolator.colorTextureUsage)
            uiUsage.formUnion(metal4Interpolator.uiTextureUsage)
            depthUsage.formUnion(metal4Interpolator.depthTextureUsage)
            motionUsage.formUnion(metal4Interpolator.motionTextureUsage)
            interpolationUsage.formUnion(metal4Interpolator.outputTextureUsage)
        }
        var newScene: [MTLTexture] = []
        var newNativeScene: [MTLTexture] = []
        var newComposed: [MTLTexture] = []
        var newDepth: [MTLTexture] = []
        var newMotion: [MTLTexture] = []
        var newInterpolation: [MTLTexture] = []

        for index in 0..<Self.bufferCount {
            guard let scene = makeTexture(
                pixelFormat: outputFormat,
                width: outputWidth,
                height: outputHeight,
                usage: sceneUsage,
                label: "Frame Generation Scene \(index)"
            ), let nativeScene = makeTexture(
                pixelFormat: outputFormat,
                width: uiWidth,
                height: uiHeight,
                usage: .shaderRead,
                label: "Frame Generation Native Scene \(index)"
            ), let uiOverlay = makeTexture(
                pixelFormat: outputFormat,
                width: uiWidth,
                height: uiHeight,
                usage: uiUsage,
                label: "Frame Generation UI Overlay \(index)"
            ), let depth = makeTexture(
                pixelFormat: depthFormat,
                width: depthWidth,
                height: depthHeight,
                usage: depthUsage,
                label: "Frame Generation Depth \(index)"
            ), let motion = makeTexture(
                pixelFormat: motionFormat,
                width: motionWidth,
                height: motionHeight,
                usage: motionUsage,
                label: "Frame Generation Motion \(index)"
            ) else {
                return nil
            }
            newScene.append(scene)
            newNativeScene.append(nativeScene)
            newComposed.append(uiOverlay)
            newDepth.append(depth)
            newMotion.append(motion)
            guard let interpolation = makeTexture(
                pixelFormat: outputFormat,
                width: outputWidth,
                height: outputHeight,
                usage: interpolationUsage,
                label: "Frame Generation Interpolation \(index)"
            ) else {
                return nil
            }
            newInterpolation.append(interpolation)
        }

        return TextureSet(
            scene: newScene,
            nativeScene: newNativeScene,
            uiOverlay: newComposed,
            depth: newDepth,
            motion: newMotion,
            interpolation: newInterpolation
        )
    }

    private func installTextureSet(
        _ textureSet: TextureSet,
        outputWidth: Int,
        outputHeight: Int,
        uiWidth: Int,
        uiHeight: Int,
        outputFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        motionFormat: MTLPixelFormat
    ) {
        self.outputWidth = outputWidth
        self.outputHeight = outputHeight
        self.uiWidth = uiWidth
        self.uiHeight = uiHeight
        self.outputFormat = outputFormat
        self.depthFormat = depthFormat
        self.motionFormat = motionFormat
        self.sceneBuffers = textureSet.scene
        self.nativeSceneBuffers = textureSet.nativeScene
        self.uiOverlayBuffers = textureSet.uiOverlay
        self.depthBuffers = textureSet.depth
        self.motionBuffers = textureSet.motion
        self.interpolationOutputs = textureSet.interpolation
        // Every rebuild path funnels through here, so this is the one place the
        // Metal 4 residency set has to be republished. Missing a texture here
        // means the GPU reads unmapped memory, since Metal 4 does not track
        // residency automatically.
        metal4Path?.adopt(
            textures: textureSet.scene
                + textureSet.nativeScene
                + textureSet.uiOverlay
                + textureSet.depth
                + textureSet.motion
                + textureSet.interpolation
        )
    }

    private func rebuildTextures(
        outputWidth: Int,
        outputHeight: Int,
        uiWidth: Int,
        uiHeight: Int,
        outputFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        motionFormat: MTLPixelFormat,
        depthWidth: Int,
        depthHeight: Int,
        motionWidth: Int,
        motionHeight: Int
    ) -> Bool {
        guard let textureSet = makeTextureSet(
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            uiWidth: uiWidth,
            uiHeight: uiHeight,
            outputFormat: outputFormat,
            depthFormat: depthFormat,
            motionFormat: motionFormat,
            depthWidth: depthWidth,
            depthHeight: depthHeight,
            motionWidth: motionWidth,
            motionHeight: motionHeight
        ) else {
            return false
        }
        installTextureSet(
            textureSet,
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            uiWidth: uiWidth,
            uiHeight: uiHeight,
            outputFormat: outputFormat,
            depthFormat: depthFormat,
            motionFormat: motionFormat
        )
        return true
    }

    private func resizeResources(
        outputWidth: Int,
        outputHeight: Int,
        uiWidth: Int,
        uiHeight: Int,
        outputFormat: MTLPixelFormat,
        depth: MTLTexture,
        motion: MTLTexture,
        inputWidth: Int,
        inputHeight: Int
    ) -> Bool {
        cancelAndDrain(reason: "resize")
        guard let textureSet = makeTextureSet(
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            uiWidth: uiWidth,
            uiHeight: uiHeight,
            outputFormat: outputFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat,
            depthWidth: inputWidth,
            depthHeight: inputHeight,
            motionWidth: inputWidth,
            motionHeight: inputHeight
        ), let newInterpolator = Self.makeFrameInterpolator(
            device: device,
            sceneColor: textureSet.scene[0],
            uiColor: textureSet.uiOverlay[0],
            depth: textureSet.depth[0],
            motion: textureSet.motion[0]
        ), let newCopyPipeline = buildPresentPipeline(device: device, colorFormat: layer.pixelFormat),
           let newFusedPresentPipeline = buildFusedPresentPipeline(
               device: device,
               colorFormat: layer.pixelFormat
           ) else {
            return false
        }
        installTextureSet(
            textureSet,
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            uiWidth: uiWidth,
            uiHeight: uiHeight,
            outputFormat: outputFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat
        )
        self.frameInterpolator = newInterpolator
        // The MTL4 interpolator is format-bound the same way, so a resize has to
        // rebuild it too. Failing here disables the Metal 4 present path for the
        // rest of the session rather than failing the resize: the Metal 3 branch
        // is always a valid fallback, and metal4Path is what present() dispatches
        // on, so both must be cleared together.
        if metal4Path != nil {
            if let rebuilt = Self.makeMetal4FrameInterpolator(
                device: device,
                sceneColor: textureSet.scene[0],
                uiColor: textureSet.uiOverlay[0],
                depth: textureSet.depth[0],
                motion: textureSet.motion[0]
            ) {
                self.metal4Interpolator = rebuilt
            } else {
                NSLog("[metallum] Metal 4 interpolator rebuild failed after resize; reverting to Metal 3 present")
                self.metal4Interpolator = nil
                self.metal4Path = nil
            }
        }
        self.copyPipeline = newCopyPipeline
        self.fusedPresentPipeline = newFusedPresentPipeline
        self.copyFormat = layer.pixelFormat
        self.nextBufferIndex = 0
        self.lastPresentedIndex = nil
        self.lastPresentedTimestamp = nil
        self.historyOwnership.invalidateAll()
        return true
    }

    private func encodeResampledFrameGenerationInputs(
        commandBuffer: MTLCommandBuffer,
        sourceDepth: MTLTexture,
        sourceMotion: MTLTexture,
        destinationDepth: MTLTexture,
        destinationMotion: MTLTexture
    ) -> Bool {
        let motionPass = MTLRenderPassDescriptor()
        motionPass.colorAttachments[0].texture = destinationMotion
        motionPass.colorAttachments[0].loadAction = .dontCare
        motionPass.colorAttachments[0].storeAction = .store
        guard let motionEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: motionPass) else {
            return false
        }
        motionEncoder.label = "Frame Generation Motion Downsample"
        motionEncoder.setRenderPipelineState(motionResamplePipeline)
        motionEncoder.setFragmentTexture(sourceMotion, index: 0)
        motionEncoder.setFragmentSamplerState(inputResampleSampler, index: 0)
        motionEncoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destinationMotion.width),
            height: Double(destinationMotion.height),
            znear: 0.0,
            zfar: 1.0
        ))
        motionEncoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        motionEncoder.endEncoding()

        let depthPass = MTLRenderPassDescriptor()
        depthPass.depthAttachment.texture = destinationDepth
        depthPass.depthAttachment.loadAction = .dontCare
        depthPass.depthAttachment.storeAction = .store
        guard let depthEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: depthPass) else {
            return false
        }
        depthEncoder.label = "Frame Generation Reversed-Z Depth Downsample"
        depthEncoder.setRenderPipelineState(depthResamplePipeline)
        depthEncoder.setDepthStencilState(depthResampleState)
        depthEncoder.setFragmentTexture(sourceDepth, index: 0)
        depthEncoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destinationDepth.width),
            height: Double(destinationDepth.height),
            znear: 0.0,
            zfar: 1.0
        ))
        depthEncoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        depthEncoder.endEncoding()
        return true
    }

    @available(macOS 26.0, *)
    private func encodeResampledFrameGenerationInputsMetal4(
        lease: Metal4MainCommandBufferLease,
        sourceDepth: MTLTexture,
        sourceMotion: MTLTexture,
        destinationDepth: MTLTexture,
        destinationMotion: MTLTexture
    ) -> Bool {
        let motionTables = lease.owner.argumentTables(at: lease.slotIndex)

        let motionPass = MTL4RenderPassDescriptor()
        motionPass.colorAttachments[0].texture = destinationMotion
        motionPass.colorAttachments[0].loadAction = .dontCare
        motionPass.colorAttachments[0].storeAction = .store
        motionPass.renderTargetWidth = destinationMotion.width
        motionPass.renderTargetHeight = destinationMotion.height
        guard let motionEncoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: motionPass) else {
            return false
        }
        motionEncoder.label = "Frame Generation Motion Downsample (Metal 4)"
        motionEncoder.barrier(
            afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
            beforeStages: .fragment,
            visibilityOptions: .device
        )
        motionTables.1.setTexture(sourceMotion.gpuResourceID, index: 0)
        motionTables.1.setSamplerState(inputResampleSampler.gpuResourceID, index: 0)
        motionEncoder.setArgumentTable(motionTables.1, stages: .fragment)
        motionEncoder.setRenderPipelineState(motionResamplePipeline)
        motionEncoder.setViewport(MTLViewport(
            originX: 0, originY: 0,
            width: Double(destinationMotion.width), height: Double(destinationMotion.height),
            znear: 0, zfar: 1
        ))
        motionEncoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        motionEncoder.endEncoding()

        let depthPass = MTL4RenderPassDescriptor()
        depthPass.depthAttachment.texture = destinationDepth
        depthPass.depthAttachment.loadAction = .dontCare
        depthPass.depthAttachment.storeAction = .store
        depthPass.renderTargetWidth = destinationDepth.width
        depthPass.renderTargetHeight = destinationDepth.height
        guard let depthEncoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: depthPass) else {
            return false
        }
        let depthTables = lease.owner.argumentTables(at: lease.slotIndex)
        depthEncoder.label = "Frame Generation Reversed-Z Depth Downsample (Metal 4)"
        depthEncoder.barrier(
            afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
            beforeStages: .fragment,
            visibilityOptions: .device
        )
        depthTables.1.setTexture(sourceDepth.gpuResourceID, index: 0)
        depthEncoder.setArgumentTable(depthTables.1, stages: .fragment)
        depthEncoder.setRenderPipelineState(depthResamplePipeline)
        depthEncoder.setDepthStencilState(depthResampleState)
        depthEncoder.setViewport(MTLViewport(
            originX: 0, originY: 0,
            width: Double(destinationDepth.width), height: Double(destinationDepth.height),
            znear: 0, zfar: 1
        ))
        depthEncoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        depthEncoder.endEncoding()
        return true
    }

    func encode(
        commandBufferPointer: UnsafeMutableRawPointer,
        sceneColor: MTLTexture,
        nativeSceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture,
        inputWidth: Int,
        inputHeight: Int,
        jitterX: Float,
        jitterY: Float,
        fieldOfView: Float,
        nearPlane: Float,
        farPlane: Float,
        aspectRatio: Float,
        sourceDeltaSeconds: Float = 0.0,
        reset: Bool,
        globalFence: MTLFence?
    ) -> Int32 {
        guard sceneColor.width > 0, sceneColor.height > 0,
              nativeSceneColor.width > 0, nativeSceneColor.height > 0,
              uiColor.width > 0, uiColor.height > 0,
              depth.width > 0, depth.height > 0,
              sceneColor.pixelFormat == uiColor.pixelFormat,
              nativeSceneColor.pixelFormat == uiColor.pixelFormat,
              nativeSceneColor.width == uiColor.width,
              nativeSceneColor.height == uiColor.height,
              depth.width == motion.width, depth.height == motion.height,
              inputWidth > 0, inputHeight > 0,
              inputWidth <= depth.width, inputHeight <= depth.height else {
            return 0
        }

        if sceneColor.width != outputWidth || sceneColor.height != outputHeight
                || uiColor.width != uiWidth || uiColor.height != uiHeight
                || sceneColor.pixelFormat != outputFormat
                || depth.pixelFormat != depthFormat || motion.pixelFormat != motionFormat
                || depthBuffers.first?.width != inputWidth || depthBuffers.first?.height != inputHeight
                || motionBuffers.first?.width != inputWidth || motionBuffers.first?.height != inputHeight
                || layer.pixelFormat != copyFormat {
            guard resizeResources(
                outputWidth: sceneColor.width,
                outputHeight: sceneColor.height,
                uiWidth: uiColor.width,
                uiHeight: uiColor.height,
                outputFormat: sceneColor.pixelFormat,
                depth: depth,
                motion: motion,
                inputWidth: inputWidth,
                inputHeight: inputHeight
            ) else {
                return 0
            }
        }

        let waitStart = CACurrentMediaTime()
        let absoluteAdmissionDeadline = waitStart + Self.maxActiveAdmissionWait
        var cancelledForAdmission = false
        condition.lock()
        while outstandingFrames >= Self.maxOutstandingFrames && !stopping {
            if cancelledForAdmission {
                // Cancellation cannot release a slot that still has GPU work in
                // flight. Wait for its completion handler before reusing it.
                condition.wait()
                continue
            }
            let admissionNow = CACurrentMediaTime()
            if lastDisplayUpdateTime == nil {
                let installationTime = displayLinkInstallationTime ?? waitStart
                let initialDeadline = min(
                    installationTime + Self.displayUpdateActivityTimeout,
                    absoluteAdmissionDeadline
                )
                if admissionNow < initialDeadline {
                    _ = condition.wait(until: Date(
                        timeIntervalSinceNow: initialDeadline - admissionNow
                    ))
                    continue
                }
            }
            switch MetalFrameGenerationAdmissionPolicy.decide(
                now: admissionNow,
                lastDisplayUpdateTime: lastDisplayUpdateTime,
                activityTimeout: Self.displayUpdateActivityTimeout,
                absoluteDeadline: absoluteAdmissionDeadline
            ) {
            case .wait(let activityDeadline):
                let remaining = max(0.0, activityDeadline - CACurrentMediaTime())
                if remaining > 0.0 {
                    _ = condition.wait(until: Date(timeIntervalSinceNow: remaining))
                }
            case .supersede:
                // Hidden, occluded and locked windows stop receiving display
                // updates. In that state an unpresented source is obsolete;
                // cancel it and wait only for submitted GPU work to drain.
                supersededSourceFrames += 1
                cancelAllSourcesLocked(reason: "superseded after display became inactive")
                cancelledForAdmission = true
                condition.broadcast()
            }
        }
        guard !stopping else {
            condition.unlock()
            return 0
        }
        let index = nextBufferIndex
        nextBufferIndex = (nextBufferIndex + 1) % Self.bufferCount
        let eventValue = nextEventValue
        nextEventValue += 1
        let sourceFrameID = nextSourceFrameID
        nextSourceFrameID += 1
        let timestamp = CACurrentMediaTime()
        let cpuWaitDuration = max(0.0, timestamp - waitStart)
        sourceAdmissions.append(SourceAdmissionDiagnostic(
            sourceFrameID: sourceFrameID,
            enqueueTime: timestamp,
            cpuWaitDuration: cpuWaitDuration
        ))
        if sourceAdmissions.count > Self.sourceAdmissionCapacity {
            sourceAdmissions.removeFirst(sourceAdmissions.count - Self.sourceAdmissionCapacity)
        }
        outstandingFrames += 1
        condition.unlock()

        let metal4Lease: Metal4MainCommandBufferLease? = {
            if #available(macOS 26.0, *) { return metal4MainLease(commandBufferPointer) }
            return nil
        }()
        if let lease = metal4Lease {
            guard #available(macOS 26.0, *),
                  let copies = lease.commandBuffer.makeComputeCommandEncoder() else {
                completeFrame()
                return 0
            }
            copies.label = "Frame Generation Input Copies (Metal 4)"
            copies.barrier(
                afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
                beforeStages: .blit,
                visibilityOptions: .device
            )
            copies.copy(sourceTexture: sceneColor, destinationTexture: sceneBuffers[index])
            copies.copy(sourceTexture: nativeSceneColor, destinationTexture: nativeSceneBuffers[index])
            copies.copy(sourceTexture: uiColor, destinationTexture: uiOverlayBuffers[index])
            let resampleInputs = depth.width != inputWidth || depth.height != inputHeight
            if !resampleInputs {
                copies.copy(sourceTexture: depth, destinationTexture: depthBuffers[index])
                copies.copy(sourceTexture: motion, destinationTexture: motionBuffers[index])
            }
            copies.endEncoding()
            if resampleInputs && !encodeResampledFrameGenerationInputsMetal4(
                lease: lease,
                sourceDepth: depth, sourceMotion: motion,
                destinationDepth: depthBuffers[index], destinationMotion: motionBuffers[index]
            ) {
                completeFrame()
                return 0
            }
            lease.signalAfterCommit(readyEvent, value: eventValue)
            NativeState.metal4FrameGenerationInputCount &+= 1
        } else {
            let commandBuffer = metal3CommandBuffer(commandBufferPointer)
            guard let blit = commandBuffer.makeBlitCommandEncoder() else {
                completeFrame()
                return 0
            }
            blit.label = "Frame Generation Input Copies"
        // The copy sources (scene/ui/depth/motion) are untracked render
        // outputs of earlier encoders in this command buffer; the global
        // fence chain is the only ordering guarantee.
        if let globalFence {
            blit.waitForFence(globalFence)
        }
        // Split-fence mode: this blit also joins the transfer chain so the
        // write-after-write edge to the next frame's input copy (and to any
        // Java-side blit touching these textures) survives without the
        // render fence detour.
        if let transferFence = NativeState.transferFence {
            blit.waitForFence(transferFence)
        }
        blit.copy(
            from: sceneColor,
            sourceSlice: 0,
            sourceLevel: 0,
            to: sceneBuffers[index],
            destinationSlice: 0,
            destinationLevel: 0,
            sliceCount: 1,
            levelCount: 1
        )
        blit.copy(
            from: nativeSceneColor,
            sourceSlice: 0,
            sourceLevel: 0,
            to: nativeSceneBuffers[index],
            destinationSlice: 0,
            destinationLevel: 0,
            sliceCount: 1,
            levelCount: 1
        )
        blit.copy(
            from: uiColor,
            sourceSlice: 0,
            sourceLevel: 0,
            to: uiOverlayBuffers[index],
            destinationSlice: 0,
            destinationLevel: 0,
            sliceCount: 1,
            levelCount: 1
        )
        let resampleInputs = depth.width != inputWidth || depth.height != inputHeight
        if !resampleInputs {
            blit.copy(
                from: depth,
                sourceSlice: 0,
                sourceLevel: 0,
                to: depthBuffers[index],
                destinationSlice: 0,
                destinationLevel: 0,
                sliceCount: 1,
                levelCount: 1
            )
            blit.copy(
                from: motion,
                sourceSlice: 0,
                sourceLevel: 0,
                to: motionBuffers[index],
                destinationSlice: 0,
                destinationLevel: 0,
                sliceCount: 1,
                levelCount: 1
            )
        }
        // Later encoders in the game command buffer wait on this fence; the
        // present-queue consumer is ordered by the shared event instead.
        // Split-fence mode: signal the transfer chain instead — blits are
        // transfer-chain producers there, and the render chain must not gain
        // a false edge on this copy.
        if let transferFence = NativeState.transferFence {
            blit.updateFence(transferFence)
        } else if let globalFence {
            blit.updateFence(globalFence)
            }
            blit.endEncoding()
            if resampleInputs && !encodeResampledFrameGenerationInputs(
                commandBuffer: commandBuffer,
                sourceDepth: depth,
                sourceMotion: motion,
                destinationDepth: depthBuffers[index],
                destinationMotion: motionBuffers[index]
            ) {
                completeFrame()
                return 0
            }
            commandBuffer.encodeSignalEvent(readyEvent, value: eventValue)
        }

        let frame = PendingFrame(
            sourceFrameID: sourceFrameID,
            index: index,
            eventValue: eventValue,
            timestamp: timestamp,
            cpuWaitDuration: cpuWaitDuration,
            inputWidth: inputWidth,
            inputHeight: inputHeight,
            frameGenerationWidth: sceneColor.width,
            frameGenerationHeight: sceneColor.height,
            nativeWidth: nativeSceneColor.width,
            nativeHeight: nativeSceneColor.height,
            jitterX: jitterX,
            jitterY: jitterY,
            fieldOfView: fieldOfView,
            nearPlane: nearPlane,
            farPlane: farPlane,
            aspectRatio: aspectRatio,
            sourceDelta: sourceDeltaSeconds,
            reset: reset
        )

        condition.lock()
        var lifecycle = MetalFrameGenerationLifecycle(sourceFrameID: sourceFrameID)
        _ = lifecycle.submitInput()
        if currentFrame == nil {
            currentFrame = frame
            currentLifecycle = lifecycle
            displayUpdateStarvationTimeoutAt = timestamp + Self.displayUpdateStarvationTimeout
        } else {
            queuedFrame = frame
            queuedLifecycle = lifecycle
        }
        condition.signal()
        condition.unlock()

        if let lease = metal4Lease {
            lease.addCompletionHandler { [weak self] error, gpuStartTime, gpuEndTime in
                self?.handleInputCommandBufferCompletion(
                    eventValue: eventValue, succeeded: error == nil, error: error,
                    gpuStartTime: gpuStartTime, gpuEndTime: gpuEndTime
                )
            }
        } else {
            let commandBuffer = metal3CommandBuffer(commandBufferPointer)
            commandBuffer.addCompletedHandler { [weak self] completed in
                self?.handleInputCommandBufferCompletion(
                    eventValue: eventValue,
                    succeeded: completed.status == .completed,
                    error: completed.error,
                    gpuStartTime: completed.gpuStartTime,
                    gpuEndTime: completed.gpuEndTime
                )
            }
        }
        return 1
    }

    private func handleInputCommandBufferCompletion(
        eventValue: UInt64,
        succeeded: Bool,
        error: Error?,
        gpuStartTime: CFTimeInterval,
        gpuEndTime: CFTimeInterval
    ) {
        condition.lock()
        let isCurrent = currentFrame?.eventValue == eventValue
        guard let frame = isCurrent ? currentFrame : queuedFrame,
              frame.eventValue == eventValue,
              var lifecycle = isCurrent ? currentLifecycle : queuedLifecycle else {
            condition.unlock()
            return
        }
        if gpuStartTime > 0.0, gpuEndTime > gpuStartTime {
            sourceGpuTimings[frame.sourceFrameID] = (gpuStartTime, gpuEndTime)
            for index in diagnostics.indices where diagnostics[index].sourceFrameID == frame.sourceFrameID {
                diagnostics[index].sourceGpuStartTime = gpuStartTime
                diagnostics[index].sourceGpuEndTime = gpuEndTime
            }
            if frame.sourceFrameID > UInt64(Self.diagnosticCapacity) {
                sourceGpuTimings.removeValue(
                    forKey: frame.sourceFrameID - UInt64(Self.diagnosticCapacity)
                )
            }
        }
        let actions = lifecycle.completeGPUWork(
            .input,
            succeeded: succeeded,
            reason: succeeded ? nil : "input command buffer failed: \(String(describing: error))"
        )
        if isCurrent {
            currentLifecycle = lifecycle
            applyLifecycleActionsLocked(actions, eventValue: eventValue)
        } else {
            queuedLifecycle = lifecycle
            applyQueuedLifecycleActionsLocked(actions, eventValue: eventValue)
        }
        condition.broadcast()
        condition.unlock()

        if !succeeded {
            // A failed command buffer does not execute its encoded signal
            // event. Advance it on the CPU only to prevent stale waits from
            // surviving a failure path; no presentation work is submitted.
            if readyEvent.signaledValue < eventValue {
                readyEvent.signaledValue = eventValue
            }
            logMetalFxFailureOnce(
                "frame-generation-input",
                "input command buffer failed: \(String(describing: error))"
            )
        }
    }

    private func encodeCopy(
        commandBuffer: MTLCommandBuffer,
        source: MTLTexture,
        destination: MTLTexture,
        pipeline: MTLRenderPipelineState? = nil,
        loadAction: MTLLoadAction = .dontCare,
        label: String
    ) -> Bool {
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = destination
        descriptor.colorAttachments[0].loadAction = loadAction
        descriptor.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return false
        }
        encoder.label = label
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destination.width),
            height: Double(destination.height),
            znear: 0.0,
            zfar: 1.0
        ))
        encoder.setRenderPipelineState(pipeline ?? copyPipeline)
        encoder.setFragmentTexture(source, index: 0)
        encoder.setFragmentSamplerState(copySampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return true
    }

    private func encodeComposite(
        commandBuffer: MTLCommandBuffer,
        scene: MTLTexture,
        ui: MTLTexture,
        destination: MTLTexture,
        label: String
    ) -> Bool {
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = destination
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            return false
        }
        encoder.label = label
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(destination.width),
            height: Double(destination.height),
            znear: 0.0,
            zfar: 1.0
        ))
        encoder.setRenderPipelineState(fusedPresentPipeline)
        encoder.setFragmentTexture(scene, index: 0)
        encoder.setFragmentTexture(ui, index: 1)
        encoder.setFragmentSamplerState(copySampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return true
    }

    private func installDisplayLink() -> Bool {
        let link = CAMetalDisplayLink(metalLayer: layer)
        link.delegate = self
        // Keep display-link cadence controlled by the attached display. Do not
        // copy NSScreen.maximumFramesPerSecond into a fixed pacing interval;
        // that breaks VRR and display migration.
        link.preferredFrameLatency = metalFrameGenerationPreferredFrameLatency
        link.add(to: RunLoop.current, forMode: .default)
        displayLink = link
        condition.lock()
        displayLinkInstallationTime = CACurrentMediaTime()
        condition.broadcast()
        condition.unlock()
        return true
    }

    func metalDisplayLink(_ link: CAMetalDisplayLink, needsUpdate update: CAMetalDisplayLink.Update) {
        // The drawable's present must be committed before this callback
        // returns; WindowServer reports presentedTime == 0 for drawables whose
        // present is committed from a later run-loop pass, even on the same
        // thread. All work selection and the full encode/commit therefore run
        // synchronously here.
        if let work = claimPresentationWork(update) {
            present(work)
        }
        applyLayerPolicyIfNeeded()
    }

    /// Marks the presenter-owned CAMetalLayer properties as needing to be
    /// restated. Called from the render thread on every surface reconfigure;
    /// the work itself happens after the next present.
    func requestLayerPolicyRefresh() {
        condition.lock()
        pendingLayerPolicyRefresh = true
        condition.unlock()
    }

    /// Restates the layer properties the presenter depends on. A resize routes
    /// through `metallum_configure_layer`, which would otherwise leave
    /// `allowsNextDrawableTimeout` off — the presenter would then block forever
    /// on a hidden or minimized window — and could drop vsync underneath a
    /// display link that only ever schedules on the refresh boundary.
    ///
    /// `maximumDrawableCount` is intentionally absent. QuartzCore forbids
    /// changing it after a CAMetalDisplayLink has attached to the layer and
    /// throws CAMetalLayerInvalidOperation during a live resize. The presenter
    /// sets the drawable count once, before installing its display link.
    private func applyLayerPolicyIfNeeded() {
        condition.lock()
        let refresh = pendingLayerPolicyRefresh
        pendingLayerPolicyRefresh = false
        condition.unlock()
        guard refresh else {
            return
        }
        layer.allowsNextDrawableTimeout = true
        layer.displaySyncEnabled = true
    }

    private func claimPresentationWork(_ update: CAMetalDisplayLink.Update) -> PresentationWork? {
        let targetTimestamp = update.targetTimestamp
        let targetPresentationTimestamp = update.targetPresentationTimestamp
        condition.lock()
        defer {
            condition.unlock()
        }
        guard !stopping,
              targetTimestamp.isFinite, targetTimestamp > 0.0,
              targetPresentationTimestamp.isFinite, targetPresentationTimestamp > 0.0 else {
            return nil
        }
        let updateID = nextDisplayUpdateID
        nextDisplayUpdateID += 1

        let now = CACurrentMediaTime()
        lastDisplayUpdateTime = now
        condition.broadcast()
        expireRealPresentationLocked(now: now)
        expireDisplayUpdateStarvationLocked(now: now)

        guard let frame = currentFrame, var lifecycle = currentLifecycle else {
            return nil
        }
        if !lifecycle.activated {
            let hasInterpolation = !frame.reset && historyOwnership.displayValid
                    && lastPresentedIndex != nil
            guard lifecycle.activate(hasInterpolation: hasInterpolation) else {
                return nil
            }
            activePreviousIndex = lastPresentedIndex
            activeShouldResetHistory = frame.reset
                    || !historyOwnership.interpolatorValid
                    || !historyOwnership.displayValid
            activeDeltaTime = {
                guard !activeShouldResetHistory else {
                    return 1.0 / 60.0
                }
                // Prefer the game-provided render-timeline interval; the
                // enqueue spacing below is only a proxy that inherits CPU
                // scheduling jitter from the encode path.
                if frame.sourceDelta.isFinite && frame.sourceDelta > 0.0 {
                    return min(max(frame.sourceDelta, 1.0 / 240.0), 0.25)
                }
                guard let previousTimestamp = lastPresentedTimestamp else {
                    return 1.0 / 60.0
                }
                let delta = frame.timestamp - previousTimestamp
                guard delta.isFinite, delta > 0.0 else {
                    return 1.0 / 60.0
                }
                return Float(min(max(delta, 1.0 / 240.0), 0.25))
            }()
            currentLifecycle = lifecycle
        }

        guard let step = lifecycle.nextPresentationStep else {
            return nil
        }
        let previousIndex = activePreviousIndex ?? frame.index
        return PresentationWork(
            frame: frame,
            update: DisplayUpdate(
                updateID: updateID,
                drawable: update.drawable,
                targetTimestamp: targetTimestamp,
                targetPresentationTimestamp: targetPresentationTimestamp
            ),
            step: step,
            previousIndex: previousIndex,
            shouldResetHistory: activeShouldResetHistory,
            deltaTime: activeDeltaTime
        )
    }

    private func runWorker() {
        guard installDisplayLink() else {
            condition.lock()
            stopping = true
            cancelAllSourcesLocked(reason: "display link installation failed")
            workerExited = true
            condition.broadcast()
            condition.unlock()
            logMetalFxFailureOnce(
                "frame-generation-display-link",
                "CAMetalDisplayLink is unavailable; frame generation is disabled"
            )
            return
        }

        // Presentation happens synchronously inside the display-link callback.
        // This loop only services that callback's run loop and expires sources
        // that stopped receiving display updates (hidden window, display sleep)
        // or whose presented callback never arrived.
        let runLoop = RunLoop.current
        while true {
            condition.lock()
            let now = CACurrentMediaTime()
            expireRealPresentationLocked(now: now)
            expireDisplayUpdateStarvationLocked(now: now)
            let canExit = stopping && outstandingFrames == 0
            condition.unlock()
            if canExit {
                break
            }
            _ = runLoop.run(mode: .default, before: Date(timeIntervalSinceNow: 0.005))
        }

        displayLink?.delegate = nil
        displayLink?.invalidate()
        displayLink = nil
        condition.lock()
        workerExited = true
        condition.broadcast()
        condition.unlock()
    }

    private func present(_ work: PresentationWork) {
        // Metal 4 pilot (spec M4). Two-way dispatch on the switch; everything
        // below this point is the original Metal 3 branch, unmodified.
        if let metal4Path, let metal4Interpolator {
            presentMetal4(work, path: metal4Path, interpolator: metal4Interpolator)
            return
        }
        let frame = work.frame
        guard let commandBuffer = presentQueue.makeCommandBuffer() else {
            failPresentationBeforeSubmission(work, reason: "present command buffer unavailable")
            return
        }
        commandBuffer.label = work.step == .generated
                ? "MetalFX Interpolated Present"
                : "MetalFX Rendered Present"
        commandBuffer.encodeWaitForEvent(readyEvent, value: frame.eventValue)

        if work.step == .generated {
            frameInterpolator.colorTexture = sceneBuffers[frame.index]
            frameInterpolator.prevColorTexture = sceneBuffers[work.previousIndex]
            frameInterpolator.depthTexture = depthBuffers[frame.index]
            frameInterpolator.motionTexture = motionBuffers[frame.index]
            frameInterpolator.uiTexture = nil
            frameInterpolator.outputTexture = interpolationOutputs[frame.index]
            frameInterpolator.isUITextureComposited = false
            frameInterpolator.jitterOffsetX = frame.jitterX
            frameInterpolator.jitterOffsetY = frame.jitterY
            frameInterpolator.motionVectorScaleX = Float(frame.inputWidth) * 0.5
            frameInterpolator.motionVectorScaleY = Float(frame.inputHeight) * 0.5
            frameInterpolator.fieldOfView = frame.fieldOfView
            frameInterpolator.nearPlane = frame.nearPlane
            frameInterpolator.farPlane = frame.farPlane
            frameInterpolator.aspectRatio = frame.aspectRatio
            frameInterpolator.deltaTime = work.deltaTime
            frameInterpolator.isDepthReversed = true
            frameInterpolator.shouldResetHistory = work.shouldResetHistory
            frameInterpolator.encode(commandBuffer: commandBuffer)
            MetalFxNativeHudMetrics.updateFrameInterpolator(deltaTime: work.deltaTime)
        }
        let presentScene = work.step == .generated
                ? interpolationOutputs[frame.index]
                : nativeSceneBuffers[frame.index]
        guard encodeComposite(
            commandBuffer: commandBuffer,
            scene: presentScene,
            ui: uiOverlayBuffers[frame.index],
            destination: work.update.drawable.texture,
            label: "Frame Generation Fused Scene and UI"
        ) else {
            failPresentationBeforeSubmission(work, reason: "fused present encoder unavailable")
            return
        }

        let commitTime = CACurrentMediaTime()
        guard commitTime <= work.update.targetTimestamp else {
            condition.lock()
            presentationDeadlineMisses += 1
            droppedDisplayUpdates += 1
            appendDiagnosticLocked(
                sourceFrameID: frame.sourceFrameID,
                frameKind: diagnosticKind(work.step),
                update: work.update,
                outcome: "dropped:deadline-missed-before-commit"
            )
            condition.unlock()
            return
        }

        let eventValue = frame.eventValue
        let drawable = work.update.drawable
        let updateID = work.update.updateID
        drawable.addPresentedHandler { [weak self] drawable in
            self?.handlePresented(
                eventValue: eventValue,
                step: work.step,
                displayUpdateID: updateID,
                presentedTime: drawable.presentedTime
            )
        }
        commandBuffer.addCompletedHandler { [weak self] completed in
            self?.handlePresentGPUCompletion(
                eventValue: eventValue,
                step: work.step,
                displayUpdateID: updateID,
                succeeded: completed.status == .completed,
                error: completed.error,
                gpuStartTime: completed.gpuStartTime,
                gpuEndTime: completed.gpuEndTime
            )
        }

        condition.lock()
        guard !stopping,
              currentFrame?.eventValue == eventValue,
              var lifecycle = currentLifecycle,
              lifecycle.nextPresentationStep == work.step else {
            cancelCurrentSourceLocked(reason: "presentation cancelled before commit")
            condition.unlock()
            return
        }
        let actions = lifecycle.submitPresentation(work.step)
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: eventValue)
        if work.step == .real {
            realPresentationTimeoutAt = work.update.targetPresentationTimestamp
                    + Self.presentationCallbackTimeout
            displayUpdateStarvationTimeoutAt = nil
        } else {
            displayUpdateStarvationTimeoutAt = commitTime
                    + Self.displayUpdateStarvationTimeout
        }
        appendDiagnosticLocked(
            sourceFrameID: frame.sourceFrameID,
            frameKind: diagnosticKind(work.step),
            update: work.update,
            cpuCommitTime: commitTime,
            outcome: "submitted"
        )
        condition.unlock()

        // CAMetalDisplayLink owns this drawable and its pacing decision. Its
        // drawable must use ordinary present; targeted present APIs are invalid
        // on this path.
        commandBuffer.present(drawable)
        commandBuffer.commit()
    }

    /// Metal 4 twin of present(_:) (spec M4). Same lifecycle, deadline and
    /// diagnostic bookkeeping — deliberately duplicated rather than factored out,
    /// so the Metal 3 branch stays exactly as it was.
    ///
    /// Two orderings differ from Metal 3 and both matter:
    ///   - the event wait is a queue operation, not a command-buffer one, so it is
    ///     issued inside submit() rather than up front. Every early return below
    ///     happens before any wait has been placed on the queue timeline; see
    ///     Metal4PresentPath.submit for what issuing it early would wedge.
    ///   - the selected Metal 4 slot must be closed on every path out of here,
    ///     which is what abandonFrame() is for. All early returns after beginFrame
    ///     call it, and it is idempotent. Slot exhaustion returns before encoding.
    @available(macOS 26.0, *)
    private func presentMetal4(
        _ work: PresentationWork,
        path: Metal4PresentPath,
        interpolator: any MTL4FXFrameInterpolator
    ) {
        let frame = work.frame
        guard let commandBuffer = path.beginFrame() else {
            condition.lock()
            droppedDisplayUpdates += 1
            appendDiagnosticLocked(
                sourceFrameID: frame.sourceFrameID,
                frameKind: diagnosticKind(work.step),
                update: work.update,
                outcome: "dropped:metal4-in-flight-saturated"
            )
            condition.unlock()
            return
        }

        if work.step == .generated {
            interpolator.colorTexture = sceneBuffers[frame.index]
            interpolator.prevColorTexture = sceneBuffers[work.previousIndex]
            interpolator.depthTexture = depthBuffers[frame.index]
            interpolator.motionTexture = motionBuffers[frame.index]
            interpolator.uiTexture = nil
            interpolator.outputTexture = interpolationOutputs[frame.index]
            interpolator.isUITextureComposited = false
            interpolator.jitterOffsetX = frame.jitterX
            interpolator.jitterOffsetY = frame.jitterY
            interpolator.motionVectorScaleX = Float(frame.inputWidth) * 0.5
            interpolator.motionVectorScaleY = Float(frame.inputHeight) * 0.5
            interpolator.fieldOfView = frame.fieldOfView
            interpolator.nearPlane = frame.nearPlane
            interpolator.farPlane = frame.farPlane
            interpolator.aspectRatio = frame.aspectRatio
            interpolator.deltaTime = work.deltaTime
            interpolator.isDepthReversed = true
            interpolator.shouldResetHistory = work.shouldResetHistory
            interpolator.encode(commandBuffer: commandBuffer)
            MetalFxNativeHudMetrics.updateFrameInterpolator(deltaTime: work.deltaTime)
        }
        let presentScene = work.step == .generated
                ? interpolationOutputs[frame.index]
                : nativeSceneBuffers[frame.index]
        guard path.encodeComposite(
            commandBuffer: commandBuffer,
            scene: presentScene,
            ui: uiOverlayBuffers[frame.index],
            destination: work.update.drawable.texture,
            pipeline: fusedPresentPipeline,
            sampler: copySampler,
            synchronizePreviousWrites: work.step == .generated,
            label: "Frame Generation Fused Scene and UI"
        ) else {
            path.abandonFrame()
            failPresentationBeforeSubmission(work, reason: "fused present encoder unavailable")
            return
        }

        let commitTime = CACurrentMediaTime()
        guard commitTime <= work.update.targetTimestamp else {
            path.abandonFrame()
            condition.lock()
            presentationDeadlineMisses += 1
            droppedDisplayUpdates += 1
            appendDiagnosticLocked(
                sourceFrameID: frame.sourceFrameID,
                frameKind: diagnosticKind(work.step),
                update: work.update,
                outcome: "dropped:deadline-missed-before-commit"
            )
            condition.unlock()
            return
        }

        let eventValue = frame.eventValue
        let drawable = work.update.drawable
        let updateID = work.update.updateID
        // Unchanged from Metal 3: addPresentedHandler is CAMetalDrawable API and
        // has no Metal 4 equivalent to move to.
        drawable.addPresentedHandler { [weak self] drawable in
            self?.handlePresented(
                eventValue: eventValue,
                step: work.step,
                displayUpdateID: updateID,
                presentedTime: drawable.presentedTime
            )
        }

        condition.lock()
        guard !stopping,
              currentFrame?.eventValue == eventValue,
              var lifecycle = currentLifecycle,
              lifecycle.nextPresentationStep == work.step else {
            cancelCurrentSourceLocked(reason: "presentation cancelled before commit")
            condition.unlock()
            path.abandonFrame()
            return
        }
        let actions = lifecycle.submitPresentation(work.step)
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: eventValue)
        if work.step == .real {
            realPresentationTimeoutAt = work.update.targetPresentationTimestamp
                    + Self.presentationCallbackTimeout
            displayUpdateStarvationTimeoutAt = nil
        } else {
            displayUpdateStarvationTimeoutAt = commitTime
                    + Self.displayUpdateStarvationTimeout
        }
        appendDiagnosticLocked(
            sourceFrameID: frame.sourceFrameID,
            frameKind: diagnosticKind(work.step),
            update: work.update,
            cpuCommitTime: commitTime,
            outcome: "submitted"
        )
        condition.unlock()

        // The main Metal 3 queue signals readyEvent; this Metal 4 queue waits on
        // it. Shared events cross the Metal 3 / Metal 4 boundary, which is what
        // makes this pilot possible without touching the main queue at all. The
        // wait is issued inside submit(), past every path that can still abandon
        // the frame — see its documentation for why that placement is load-bearing.
        path.submit(drawable: drawable, readyEvent: readyEvent, eventValue: eventValue) {
            [weak self] error, gpuStartTime, gpuEndTime in
            // MTL4CommandBufferFeedback carries no status, so error == nil is the
            // only success signal. Routing into the same handler as Metal 3 keeps
            // the failure path — which advances readyEvent so the present thread
            // cannot hang on a stale wait — identical.
            self?.handlePresentGPUCompletion(
                eventValue: eventValue,
                step: work.step,
                displayUpdateID: updateID,
                succeeded: error == nil,
                error: error,
                gpuStartTime: gpuStartTime,
                gpuEndTime: gpuEndTime
            )
        }
    }

    private func failPresentationBeforeSubmission(_ work: PresentationWork, reason: String) {
        condition.lock()
        guard currentFrame?.eventValue == work.frame.eventValue,
              var lifecycle = currentLifecycle else {
            condition.unlock()
            return
        }
        let actions = lifecycle.failBeforeSubmission(work.step, reason: reason)
        currentLifecycle = lifecycle
        appendDiagnosticLocked(
            sourceFrameID: work.frame.sourceFrameID,
            frameKind: diagnosticKind(work.step),
            update: work.update,
            outcome: "failed:\(reason)"
        )
        applyLifecycleActionsLocked(actions, eventValue: work.frame.eventValue)
        condition.broadcast()
        condition.unlock()
        logMetalFxFailureOnce("frame-generation-present", reason)
    }

    private func handlePresentGPUCompletion(
        eventValue: UInt64,
        step: MetalFrameGenerationPresentationStep,
        displayUpdateID: UInt64,
        succeeded: Bool,
        error: Error?,
        gpuStartTime: CFTimeInterval,
        gpuEndTime: CFTimeInterval
    ) {
        let completionTime = CACurrentMediaTime()
        condition.lock()
        updateDiagnosticLocked(displayUpdateID: displayUpdateID) { diagnostic in
            diagnostic.gpuStartTime = gpuStartTime
            diagnostic.gpuEndTime = gpuEndTime
            diagnostic.gpuCompletionTime = completionTime
            if !succeeded {
                diagnostic.outcome = "failed:gpu-command-buffer"
            }
        }
        guard let frame = currentFrame, frame.eventValue == eventValue,
              var lifecycle = currentLifecycle else {
            condition.unlock()
            return
        }
        let work: MetalFrameGenerationGPUWork = step == .generated ? .generated : .real
        let actions = lifecycle.completeGPUWork(
            work,
            succeeded: succeeded,
            reason: succeeded ? nil : "present command buffer failed: \(String(describing: error))"
        )
        if step == .generated {
            if succeeded && !lifecycle.cancellationRequested {
                historyOwnership.recordInterpolator(eventValue: eventValue)
            } else {
                historyOwnership.invalidateInterpolator(ifOwnedBy: eventValue)
            }
        } else if succeeded && !lifecycle.cancellationRequested {
            // The present queue is serial and the real drawable has consumed
            // this slot. Use it as interpolation history immediately instead
            // of stalling the render thread on WindowServer scanout latency.
            lastPresentedIndex = frame.index
            lastPresentedTimestamp = frame.timestamp
            historyOwnership.recordDisplay(eventValue: eventValue)
            realPresentationTimeoutAt = nil
        } else if historyOwnership.invalidateDisplay(ifOwnedBy: eventValue) {
            lastPresentedIndex = nil
            lastPresentedTimestamp = nil
        }
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: eventValue)
        condition.broadcast()
        condition.unlock()

        if !succeeded {
            logMetalFxFailureOnce(
                "frame-generation-present-command",
                "present command buffer failed: \(String(describing: error))"
            )
        }
    }

    private func handlePresented(
        eventValue: UInt64,
        step: MetalFrameGenerationPresentationStep,
        displayUpdateID: UInt64,
        presentedTime: CFTimeInterval
    ) {
        condition.lock()
        let actuallyPresented = presentedTime.isFinite && presentedTime > 0.0
        updateDiagnosticLocked(displayUpdateID: displayUpdateID) { diagnostic in
            diagnostic.presentedTime = presentedTime
            diagnostic.outcome = actuallyPresented
                    ? "presented"
                    : "failed:not-presented"
        }
        if !actuallyPresented {
            if step == .generated {
                historyOwnership.invalidateInterpolator(ifOwnedBy: eventValue)
            } else if historyOwnership.invalidateDisplay(ifOwnedBy: eventValue) {
                lastPresentedIndex = nil
                lastPresentedTimestamp = nil
            }
        }
        guard let frame = currentFrame, frame.eventValue == eventValue,
              var lifecycle = currentLifecycle else {
            condition.unlock()
            return
        }
        let actions = lifecycle.recordPresented(step, presentedTime: presentedTime)
        if step == .real {
            if presentedTime.isFinite && presentedTime > 0.0 && !lifecycle.cancellationRequested {
                lastPresentedIndex = frame.index
                lastPresentedTimestamp = frame.timestamp
                historyOwnership.recordDisplay(eventValue: eventValue)
                realPresentationTimeoutAt = nil
            }
        }
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: eventValue)
        condition.broadcast()
        condition.unlock()
    }

    private func completeFrame() {
        condition.lock()
        completeFrameLocked()
        condition.unlock()
    }

    private func completeFrameLocked() {
        outstandingFrames = max(0, outstandingFrames - 1)
        condition.broadcast()
    }

    private func applyLifecycleActionsLocked(
        _ actions: [MetalFrameGenerationLifecycleAction],
        eventValue: UInt64
    ) {
        if actions.contains(.invalidateHistory) {
            historyOwnership.invalidateAll()
            lastPresentedIndex = nil
            lastPresentedTimestamp = nil
        }
        guard actions.contains(.releaseOwnership),
              currentFrame?.eventValue == eventValue else {
            return
        }
        currentFrame = nil
        currentLifecycle = nil
        activePreviousIndex = nil
        activeShouldResetHistory = true
        activeDeltaTime = 1.0 / 60.0
        realPresentationTimeoutAt = nil
        displayUpdateStarvationTimeoutAt = nil
        completeFrameLocked()
        promoteQueuedSourceLocked()
    }

    private func applyQueuedLifecycleActionsLocked(
        _ actions: [MetalFrameGenerationLifecycleAction],
        eventValue: UInt64
    ) {
        if actions.contains(.invalidateHistory) {
            historyOwnership.invalidateAll()
            lastPresentedIndex = nil
            lastPresentedTimestamp = nil
        }
        guard actions.contains(.releaseOwnership),
              queuedFrame?.eventValue == eventValue else {
            return
        }
        queuedFrame = nil
        queuedLifecycle = nil
        completeFrameLocked()
    }

    private func promoteQueuedSourceLocked() {
        guard currentFrame == nil,
              let frame = queuedFrame,
              let lifecycle = queuedLifecycle else {
            return
        }
        currentFrame = frame
        currentLifecycle = lifecycle
        queuedFrame = nil
        queuedLifecycle = nil
        activePreviousIndex = nil
        activeShouldResetHistory = true
        activeDeltaTime = 1.0 / 60.0
        displayUpdateStarvationTimeoutAt = CACurrentMediaTime()
                + Self.displayUpdateStarvationTimeout
        condition.broadcast()
    }

    private func cancelCurrentSourceLocked(reason: String) {
        guard let frame = currentFrame, var lifecycle = currentLifecycle else {
            return
        }
        for index in diagnostics.indices where diagnostics[index].sourceFrameID == frame.sourceFrameID
                && diagnostics[index].outcome == "submitted" {
            diagnostics[index].outcome = "cancelled:\(reason.replacingOccurrences(of: " ", with: "-"))"
        }
        let actions = lifecycle.cancel(reason: reason)
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: frame.eventValue)
    }

    private func cancelQueuedSourceLocked(reason: String) {
        guard let frame = queuedFrame, var lifecycle = queuedLifecycle else {
            return
        }
        let actions = lifecycle.cancel(reason: reason)
        queuedLifecycle = lifecycle
        applyQueuedLifecycleActionsLocked(actions, eventValue: frame.eventValue)
    }

    private func cancelAllSourcesLocked(reason: String) {
        // Cancel the successor first so releasing the active source cannot
        // promote uncancelled work during resize, shutdown or display loss.
        cancelQueuedSourceLocked(reason: reason)
        cancelCurrentSourceLocked(reason: reason)
    }

    private func cancelAndDrain(reason: String) {
        condition.lock()
        cancelAllSourcesLocked(reason: reason)
        condition.broadcast()
        while outstandingFrames > 0 {
            condition.wait()
        }
        condition.unlock()
    }

    private func expireRealPresentationLocked(now: CFTimeInterval) {
        guard let timeout = realPresentationTimeoutAt, now >= timeout,
              let frame = currentFrame, var lifecycle = currentLifecycle else {
            return
        }
        let actions = lifecycle.failPendingPresentation(
            reason: "presented callback timeout"
        )
        guard !actions.isEmpty else {
            return
        }
        currentLifecycle = lifecycle
        if let diagnosticIndex = diagnostics.lastIndex(where: {
            $0.sourceFrameID == frame.sourceFrameID && $0.frameKind == "real"
        }) {
            diagnostics[diagnosticIndex].outcome = "failed:presented-callback-timeout"
        }
        applyLifecycleActionsLocked(actions, eventValue: frame.eventValue)
    }

    private func expireDisplayUpdateStarvationLocked(now: CFTimeInterval) {
        guard let timeout = displayUpdateStarvationTimeoutAt,
              now >= timeout,
              let frame = currentFrame,
              let lifecycle = currentLifecycle,
              !lifecycle.realSubmitted else {
            return
        }
        if let diagnosticIndex = diagnostics.lastIndex(where: {
            $0.sourceFrameID == frame.sourceFrameID
        }) {
            diagnostics[diagnosticIndex].outcome = "cancelled:display-update-starvation"
        }
        cancelCurrentSourceLocked(reason: "display update starvation")
    }

    private func diagnosticKind(_ step: MetalFrameGenerationPresentationStep) -> String {
        step == .generated ? "generated" : "real"
    }

    private func appendDiagnosticLocked(
        sourceFrameID: UInt64,
        frameKind: String,
        update: DisplayUpdate,
        cpuCommitTime: CFTimeInterval = 0.0,
        outcome: String
    ) {
        let sourceTiming = sourceGpuTimings[sourceFrameID]
        let sourceFrame = currentFrame?.sourceFrameID == sourceFrameID ? currentFrame : nil
        diagnostics.append(FrameDiagnostic(
            sourceFrameID: sourceFrameID,
            frameKind: frameKind,
            displayUpdateID: update.updateID,
            targetTimestamp: update.targetTimestamp,
            targetPresentationTimestamp: update.targetPresentationTimestamp,
            cpuCommitTime: cpuCommitTime,
            sourceEnqueueTime: sourceFrame?.timestamp ?? 0.0,
            sourceCpuWaitTime: sourceFrame?.cpuWaitDuration ?? 0.0,
            inputWidth: sourceFrame?.inputWidth ?? 0,
            inputHeight: sourceFrame?.inputHeight ?? 0,
            frameGenerationWidth: sourceFrame?.frameGenerationWidth ?? 0,
            frameGenerationHeight: sourceFrame?.frameGenerationHeight ?? 0,
            nativeWidth: sourceFrame?.nativeWidth ?? 0,
            nativeHeight: sourceFrame?.nativeHeight ?? 0,
            sourceGpuStartTime: sourceTiming?.start ?? 0.0,
            sourceGpuEndTime: sourceTiming?.end ?? 0.0,
            gpuStartTime: 0.0,
            gpuEndTime: 0.0,
            gpuCompletionTime: 0.0,
            presentedTime: 0.0,
            outcome: outcome
        ))
        if diagnostics.count > Self.diagnosticCapacity {
            diagnostics.removeFirst(diagnostics.count - Self.diagnosticCapacity)
        }
    }

    private func updateDiagnosticLocked(
        displayUpdateID: UInt64,
        update: (inout FrameDiagnostic) -> Void
    ) {
        guard let index = diagnostics.lastIndex(where: {
            $0.displayUpdateID == displayUpdateID
        }) else {
            return
        }
        update(&diagnostics[index])
    }

    private func dumpDiagnosticsIfEnabled(
        _ snapshot: [FrameDiagnostic],
        sourceAdmissionSnapshot: [SourceAdmissionDiagnostic],
        supersededSourceSnapshot: Int,
        droppedDisplayUpdateSnapshot: Int,
        presentationDeadlineMissSnapshot: Int
    ) {
        let process = ProcessInfo.processInfo
        let outputPath = process.environment["METALLUM_METALFX_PRESENT_DIAGNOSTICS_PATH"]
        let enabled = process.environment["METALLUM_METALFX_PRESENT_DIAGNOSTICS"] == "1"
                || process.arguments.contains("-Dmetallum.metalfx.debug=true")
                || outputPath != nil
        guard enabled else {
            return
        }
        if let outputPath {
            let presentPath = metal4Path != nil && metal4Interpolator != nil ? "metal4" : "metal3"
            let records: [[String: Any]] = snapshot.map { diagnostic in
                [
                    "presentPath": presentPath,
                    "sourceFrameID": diagnostic.sourceFrameID,
                    "frameKind": diagnostic.frameKind,
                    "displayUpdateID": diagnostic.displayUpdateID,
                    "targetTimestamp": diagnostic.targetTimestamp,
                    "targetPresentationTimestamp": diagnostic.targetPresentationTimestamp,
                    "cpuCommitTime": diagnostic.cpuCommitTime,
                    "sourceEnqueueTime": diagnostic.sourceEnqueueTime,
                    "sourceCpuWaitTime": diagnostic.sourceCpuWaitTime,
                    "inputWidth": diagnostic.inputWidth,
                    "inputHeight": diagnostic.inputHeight,
                    "frameGenerationWidth": diagnostic.frameGenerationWidth,
                    "frameGenerationHeight": diagnostic.frameGenerationHeight,
                    "nativeWidth": diagnostic.nativeWidth,
                    "nativeHeight": diagnostic.nativeHeight,
                    "sourceGpuStartTime": diagnostic.sourceGpuStartTime,
                    "sourceGpuEndTime": diagnostic.sourceGpuEndTime,
                    "gpuStartTime": diagnostic.gpuStartTime,
                    "gpuEndTime": diagnostic.gpuEndTime,
                    "gpuCompletionTime": diagnostic.gpuCompletionTime,
                    "presentedTime": diagnostic.presentedTime,
                    "outcome": diagnostic.outcome,
                ]
            }
            do {
                let url = URL(fileURLWithPath: outputPath)
                try FileManager.default.createDirectory(
                    at: url.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                // GUI/focus transitions can stop one presenter and briefly
                // create another during shutdown. Preserve the longest session
                // from this validation run so a one-frame tail cannot overwrite
                // the steady-state timeline that preceded it.
                let existingRecordCount: Int? = {
                    guard let existingData = try? Data(contentsOf: url),
                          let existingRecords = try? JSONSerialization.jsonObject(with: existingData)
                                as? [[String: Any]] else {
                        return nil
                    }
                    return existingRecords.count
                }()
                if let existingRecordCount, existingRecordCount >= records.count {
                    NSLog(
                        "[Metallum] MetalFX timeline retained longer session: %d records at %@ (discarded %d)",
                        existingRecordCount,
                        outputPath,
                        records.count
                    )
                } else {
                    let data = try JSONSerialization.data(
                        withJSONObject: records,
                        options: [.prettyPrinted, .sortedKeys]
                    )
                    try data.write(to: url, options: .atomic)
                    NSLog("[Metallum] MetalFX timeline written: %@", outputPath)
                }

                let admissionURL = url.deletingLastPathComponent()
                    .appendingPathComponent("frame-generation-source-admission.json")
                let sourceRecords: [[String: Any]] = sourceAdmissionSnapshot.map { admission in
                    [
                        "sourceFrameID": admission.sourceFrameID,
                        "enqueueTime": admission.enqueueTime,
                        "cpuWaitMilliseconds": admission.cpuWaitDuration * 1_000.0,
                    ]
                }
                let admissionReport: [String: Any] = [
                    "status": "captured",
                    "sourceFrames": sourceRecords.count,
                    "supersededSources": supersededSourceSnapshot,
                    "droppedDisplayUpdates": droppedDisplayUpdateSnapshot,
                    "deadlineMisses": presentationDeadlineMissSnapshot,
                    "sources": sourceRecords,
                ]
                let existingAdmissionCount: Int? = {
                    guard let existingData = try? Data(contentsOf: admissionURL),
                          let existingReport = try? JSONSerialization.jsonObject(with: existingData)
                                as? [String: Any],
                          let existingSources = existingReport["sources"] as? [[String: Any]] else {
                        return nil
                    }
                    return existingSources.count
                }()
                if let existingAdmissionCount,
                   existingAdmissionCount >= sourceAdmissionSnapshot.count {
                    NSLog(
                        "[Metallum] MetalFX source admission retained longer session: %d records at %@ (discarded %d)",
                        existingAdmissionCount,
                        admissionURL.path,
                        sourceAdmissionSnapshot.count
                    )
                } else {
                    let admissionData = try JSONSerialization.data(
                        withJSONObject: admissionReport,
                        options: [.prettyPrinted, .sortedKeys]
                    )
                    try admissionData.write(to: admissionURL, options: .atomic)
                    NSLog("[Metallum] MetalFX source admission written: %@", admissionURL.path)
                }
            } catch {
                NSLog("[Metallum] MetalFX timeline write failed for %@: %@", outputPath, String(describing: error))
            }
        }
        NSLog(
            "[Metallum] MetalFX presenter counters: supersededSources=%d droppedDisplayUpdates=%d deadlineMisses=%d",
            supersededSourceSnapshot,
            droppedDisplayUpdateSnapshot,
            presentationDeadlineMissSnapshot
        )
        for diagnostic in snapshot {
            NSLog(
                "[Metallum] MetalFX timeline source=%llu kind=%@ update=%llu target=%.6f presentationTarget=%.6f commit=%.6f sourceEnqueue=%.6f sourceCpuWait=%.6f sourceGpuStart=%.6f sourceGpuEnd=%.6f gpuStart=%.6f gpuEnd=%.6f gpuComplete=%.6f presented=%.6f outcome=%@",
                diagnostic.sourceFrameID,
                diagnostic.frameKind,
                diagnostic.displayUpdateID,
                diagnostic.targetTimestamp,
                diagnostic.targetPresentationTimestamp,
                diagnostic.cpuCommitTime,
                diagnostic.sourceEnqueueTime,
                diagnostic.sourceCpuWaitTime,
                diagnostic.sourceGpuStartTime,
                diagnostic.sourceGpuEndTime,
                diagnostic.gpuStartTime,
                diagnostic.gpuEndTime,
                diagnostic.gpuCompletionTime,
                diagnostic.presentedTime,
                diagnostic.outcome
            )
        }
    }

    func validationTimelineSnapshot() -> [MetalFrameGenerationDiagnosticSnapshot] {
        condition.lock()
        let presentPath = metal4Path != nil && metal4Interpolator != nil ? "metal4" : "metal3"
        let snapshot = diagnostics.map {
            MetalFrameGenerationDiagnosticSnapshot(
                presentPath: presentPath,
                sourceFrameID: $0.sourceFrameID,
                frameKind: $0.frameKind,
                displayUpdateID: $0.displayUpdateID,
                targetTimestamp: $0.targetTimestamp,
                targetPresentationTimestamp: $0.targetPresentationTimestamp,
                cpuCommitTime: $0.cpuCommitTime,
                sourceEnqueueTime: $0.sourceEnqueueTime,
                sourceCpuWaitTime: $0.sourceCpuWaitTime,
                sourceGpuStartTime: $0.sourceGpuStartTime,
                sourceGpuEndTime: $0.sourceGpuEndTime,
                gpuStartTime: $0.gpuStartTime,
                gpuEndTime: $0.gpuEndTime,
                gpuCompletionTime: $0.gpuCompletionTime,
                presentedTime: $0.presentedTime,
                outcome: $0.outcome
            )
        }
        condition.unlock()
        return snapshot
    }

    func waitUntilIdle(timeout: TimeInterval) -> Bool {
        let deadline = Date(timeIntervalSinceNow: timeout)
        condition.lock()
        while outstandingFrames > 0 && !workerExited {
            if !condition.wait(until: deadline) {
                condition.unlock()
                return false
            }
        }
        let idle = outstandingFrames == 0
        condition.unlock()
        return idle
    }

    func shutdown() {
        condition.lock()
        if !stopping {
            // The callback checks `stopping` before claiming work, so no new
            // presentation is committed from this point forward.
            stopping = true
            cancelAllSourcesLocked(reason: "shutdown")
            condition.broadcast()
        }
        while !workerExited || outstandingFrames > 0 {
            condition.wait()
        }
        let shouldDumpDiagnostics = !diagnosticsDumped
        diagnosticsDumped = true
        let diagnosticSnapshot = shouldDumpDiagnostics ? diagnostics : []
        let sourceAdmissionSnapshot = shouldDumpDiagnostics ? sourceAdmissions : []
        let supersededSourceSnapshot = supersededSourceFrames
        let droppedDisplayUpdateSnapshot = droppedDisplayUpdates
        let presentationDeadlineMissSnapshot = presentationDeadlineMisses
        condition.unlock()
        worker = nil
        // The worker has exited and no further present can be committed, so the
        // apply-after-present rule is satisfied and the layer can be handed back
        // to the ordinary present path in the state the game asked for.
        layer.allowsNextDrawableTimeout = false
        layer.displaySyncEnabled = !NativeState.immediatePresentModeRequested
        if shouldDumpDiagnostics {
            dumpDiagnosticsIfEnabled(
                diagnosticSnapshot,
                sourceAdmissionSnapshot: sourceAdmissionSnapshot,
                supersededSourceSnapshot: supersededSourceSnapshot,
                droppedDisplayUpdateSnapshot: droppedDisplayUpdateSnapshot,
                presentationDeadlineMissSnapshot: presentationDeadlineMissSnapshot
            )
        }
    }
}
#endif

#if os(macOS) && canImport(MetalFX)
private func logMetalFxFailureOnce(_ key: String, _ message: String) {
    if NativeState.metalFxFailureKeys.insert(key).inserted {
        NSLog("[Metallum] MetalFX failure (%@): %@", key, message)
        print("[Metallum] MetalFX failure (\(key)): \(message)")
    }
}
#endif

@inline(__always)
private func retainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passRetained(object).toOpaque())
}

@inline(__always)
private func unretainedPointer(_ object: AnyObject?) -> UnsafeMutableRawPointer? {
    guard let object else {
        return nil
    }
    return UnsafeMutableRawPointer(Unmanaged.passUnretained(object).toOpaque())
}

@inline(__always)
private func textureFromUnretainedPointer(_ pointer: UnsafeMutableRawPointer?) -> MTLTexture? {
    guard let pointer else {
        return nil
    }
    return Unmanaged<MTLTexture>.fromOpaque(pointer).takeUnretainedValue()
}

@inline(__always)
private func objectAddress(_ object: AnyObject) -> UInt {
    UInt(bitPattern: Unmanaged.passUnretained(object).toOpaque())
}

private func textureSliceCount(_ texture: MTLTexture) -> Int {
    switch texture.textureType {
    case .type2DArray:
        return max(texture.arrayLength, 1)
    case .typeCube:
        return 6
    case .typeCubeArray:
        return max(texture.arrayLength, 1) * 6
    default:
        return 1
    }
}

private func stencilPixelFormat(for depthFormat: MTLPixelFormat) -> MTLPixelFormat {
    let isStencil: Bool = {
        #if os(macOS)
        return depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8
        #else
        return depthFormat == .depth32Float_stencil8
        #endif
    }()
    return isStencil ? depthFormat : .invalid
}

private func makeClearColor(red: Float, green: Float, blue: Float, alpha: Float) -> MTLClearColor {
    MTLClearColor(red: Double(red), green: Double(green), blue: Double(blue), alpha: Double(alpha))
}

private func stringFromOptionalCString(_ pointer: UnsafePointer<CChar>?) -> String? {
    guard let pointer else {
        return nil
    }
    let value = String(cString: pointer)
    return value.isEmpty ? nil : value
}

private func fullscreenMslSource(flipY: Bool) -> String {
    let topY = flipY ? "1.0" : "0.0"
    let bottomY = flipY ? "-1.0" : "2.0"
    return """
    #include <metal_stdlib>
    using namespace metal;

    struct PresentVertexOut {
      float4 position [[position]];
      float2 uv;
    };

    vertex PresentVertexOut metallum_present_vs(uint vertexId [[vertex_id]]) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      const float2 uvs[3] = {
        float2(0.0,  \(topY)),
        float2(2.0,  \(topY)),
        float2(0.0,  \(bottomY))
      };

      PresentVertexOut out;
      out.position = float4(positions[vertexId], 0.0, 1.0);
      out.uv = uvs[vertexId];
      return out;
    }

    fragment float4 metallum_present_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> tex [[texture(0)]],
      sampler smp [[sampler(0)]]
    ) {
      return tex.sample(smp, in.uv);
    }

    struct DepthResampleOut {
      float depth [[depth(any)]];
    };

    fragment DepthResampleOut metallum_depth_resample_fs(
      PresentVertexOut in [[stage_in]],
      depth2d<float, access::read> tex [[texture(0)]]
    ) {
      uint2 size = uint2(tex.get_width(), tex.get_height());
      float2 sourcePosition = in.uv * float2(size) - 0.5;
      uint2 base = uint2(clamp(floor(sourcePosition), float2(0.0), float2(size - 1)));
      uint2 next = min(base + 1, size - 1);
      DepthResampleOut out;
      // Reversed Z: retain the nearest covered surface in the source footprint.
      out.depth = max(
        max(tex.read(base), tex.read(uint2(next.x, base.y))),
        max(tex.read(uint2(base.x, next.y)), tex.read(next))
      );
      return out;
    }

    fragment float4 metallum_present_composite_fs(
      PresentVertexOut in [[stage_in]],
      texture2d<float> scene [[texture(0)]],
      texture2d<float> ui [[texture(1)]],
      sampler smp [[sampler(0)]]
    ) {
      float4 sceneValue = scene.sample(smp, in.uv);
      float widthRatio = float(ui.get_width()) / float(max(scene.get_width(), 1u));
      float sharpenStrength = clamp((widthRatio - 1.0) * 0.55, 0.0, 0.22);
      if (sharpenStrength > 0.0) {
        float2 texel = 1.0 / float2(scene.get_width(), scene.get_height());
        float3 north = scene.sample(smp, in.uv + float2(0.0, -texel.y)).rgb;
        float3 south = scene.sample(smp, in.uv + float2(0.0, texel.y)).rgb;
        float3 west = scene.sample(smp, in.uv + float2(-texel.x, 0.0)).rgb;
        float3 east = scene.sample(smp, in.uv + float2(texel.x, 0.0)).rgb;
        float3 neighborhoodMin = min(sceneValue.rgb, min(min(north, south), min(west, east)));
        float3 neighborhoodMax = max(sceneValue.rgb, max(max(north, south), max(west, east)));
        float3 laplacian = 4.0 * sceneValue.rgb - north - south - west - east;
        sceneValue.rgb = clamp(
          sceneValue.rgb + sharpenStrength * laplacian,
          neighborhoodMin,
          neighborhoodMax
        );
      }
      float4 uiValue = ui.sample(smp, in.uv);
      return uiValue + sceneValue * (1.0 - uiValue.a);
    }
    """
}

private func presentMslSource() -> String {
    // CAMetalLayer presents with the opposite vertical orientation from the
    // framebuffer convention used by the original Metallum backend.
    return fullscreenMslSource(flipY: true)
}

private func copyMslSource() -> String {
    // Texture-to-texture copies stay within the same Metal coordinate space;
    // applying the drawable flip here would make the later present double
    // flip MetalFX output and the GUI seed texture.
    return fullscreenMslSource(flipY: false)
}

private struct MetallumClearUniforms {
    var z: Float
    var _padding0: SIMD3<Float>
    var color: SIMD4<Float>
}

private func clearMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearUniforms {
      float z;
      float3 _padding0;
      float4 color;
    };

    struct ClearVertexOut {
      float4 position [[position]];
      float4 color;
    };

    vertex ClearVertexOut metallum_clear_vs(
      uint vertexId [[vertex_id]],
      constant ClearUniforms& u [[buffer(1)]]
    ) {
      const float2 positions[3] = {
        float2(-1.0,  1.0),
        float2( 3.0,  1.0),
        float2(-1.0, -3.0)
      };

      ClearVertexOut out;
      out.position = float4(positions[vertexId], u.z, 1.0);
      out.color = u.color;
      return out;
    }

    fragment float4 metallum_clear_fs(ClearVertexOut in [[stage_in]]) {
      return in.color;
    }
    """
}

private func encodeClearDraw(
    encoder: MTLRenderCommandEncoder,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) {
    encoder.setViewport(MTLViewport(
        originX: 0.0,
        originY: 0.0,
        width: Double(textureWidth),
        height: Double(textureHeight),
        znear: 0.0,
        zfar: 1.0
    ))

    encoder.setScissorRect(scissorRect)
    encoder.setRenderPipelineState(pipeline)

    if let depthState {
        encoder.setDepthStencilState(depthState)
    }

    var uniforms = MetallumClearUniforms(
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: SIMD3<Float>(0.0, 0.0, 0.0),
        color: clearColor
    )

    withUnsafeBytes(of: &uniforms) { bytes in
        encoder.setVertexBytes(bytes.baseAddress!, length: bytes.count, index: 1)
    }

    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
}

@available(macOS 26.0, iOS 26.0, *)
private func encodeClearDrawMetal4(
    bridge: Metal4MainRenderEncoderBridge,
    lease: Metal4MainCommandBufferLease,
    pipeline: MTLRenderPipelineState,
    textureWidth: Int,
    textureHeight: Int,
    clearColor: SIMD4<Float>,
    scissorRect: MTLScissorRect,
    depthState: MTLDepthStencilState? = nil,
    clearDepth: Double = 0.0
) -> Bool {
    bridge.encoder.setViewport(MTLViewport(
        originX: 0.0, originY: 0.0,
        width: Double(textureWidth), height: Double(textureHeight),
        znear: 0.0, zfar: 1.0
    ))
    bridge.encoder.setScissorRect(scissorRect)
    bridge.encoder.setRenderPipelineState(pipeline)
    if let depthState { bridge.encoder.setDepthStencilState(depthState) }
    let uniforms = MetallumClearUniforms(
        z: depthState == nil ? 0.0 : Float(max(0.0, min(clearDepth, 1.0))),
        _padding0: SIMD3<Float>(0.0, 0.0, 0.0),
        color: clearColor
    )
    guard let allocation = lease.owner.writeClearUniforms(uniforms, at: lease.slotIndex) else {
        return false
    }
    bridge.setBuffer(allocation.0, offset: allocation.1, index: 1, stageMask: 1)
    bridge.encoder.drawPrimitives(
        primitiveType: .triangle,
        vertexStart: 0,
        vertexCount: 3
    )
    return true
}

private func buildClearPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat,
    depthFormat: MTLPixelFormat = .invalid,
    writeColor: Bool = true
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: clearMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_clear_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_clear_fs")
        else {
            NSLog("[metallum] Failed to create clear shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.depthAttachmentPixelFormat = depthFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false
        descriptor.colorAttachments[0].writeMask = writeColor ? .all : []

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create clear pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)

        guard
            let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
            let fragmentFunction = library.makeFunction(name: "metallum_present_fs")
        else {
            NSLog("[metallum] Failed to create present shader functions")
            return nil
        }

        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false

        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create present render pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildDepthResamplePipeline(
    device: MTLDevice,
    depthFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)
        guard let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
              let fragmentFunction = library.makeFunction(name: "metallum_depth_resample_fs") else {
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.depthAttachmentPixelFormat = depthFormat
        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create depth-resample pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildDepthResampleState(device: MTLDevice) -> MTLDepthStencilState? {
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = .always
    descriptor.isDepthWriteEnabled = true
    return device.makeDepthStencilState(descriptor: descriptor)
}

private func buildOverlayPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)
        guard let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
              let fragmentFunction = library.makeFunction(name: "metallum_present_fs") else {
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        let attachment = descriptor.colorAttachments[0]!
        attachment.pixelFormat = colorFormat
        attachment.isBlendingEnabled = true
        // Minecraft's GUI is rendered onto a transparent target first, so its
        // stored RGB is premultiplied by alpha. Preserve native-resolution edge
        // coverage when compositing it over the upscaled scene.
        attachment.rgbBlendOperation = .add
        attachment.sourceRGBBlendFactor = .one
        attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
        attachment.alphaBlendOperation = .add
        attachment.sourceAlphaBlendFactor = .one
        attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create native UI overlay pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildFusedPresentPipeline(
    device: MTLDevice,
    colorFormat: MTLPixelFormat
) -> MTLRenderPipelineState? {
    do {
        let library = try device.makeLibrary(source: presentMslSource(), options: nil)
        guard let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
              let fragmentFunction = library.makeFunction(name: "metallum_present_composite_fs") else {
            return nil
        }
        let descriptor = MTLRenderPipelineDescriptor()
        descriptor.vertexFunction = vertexFunction
        descriptor.fragmentFunction = fragmentFunction
        descriptor.colorAttachments[0].pixelFormat = colorFormat
        descriptor.colorAttachments[0].isBlendingEnabled = false
        return try device.makeRenderPipelineState(descriptor: descriptor)
    } catch {
        NSLog("[metallum] Failed to create fused present pipeline: %@", String(describing: error))
        return nil
    }
}

private func buildPresentSampler(device: MTLDevice, filter: MTLSamplerMinMagFilter) -> MTLSamplerState? {
    let descriptor = MTLSamplerDescriptor()
    descriptor.minFilter = filter
    descriptor.magFilter = filter
    descriptor.mipFilter = .notMipmapped
    descriptor.sAddressMode = .clampToEdge
    descriptor.tAddressMode = .clampToEdge
    return device.makeSamplerState(descriptor: descriptor)
}

private func ensureCopyPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
    let key = Int(colorFormat.rawValue)
    if let pipeline = NativeState.copyPipelines[key] {
        return pipeline
    }
    guard let library = try? device.makeLibrary(source: copyMslSource(), options: nil) else {
        NSLog("[metallum] Failed to compile texture-copy shader library")
        return nil
    }

    guard
        let vertexFunction = library.makeFunction(name: "metallum_present_vs"),
        let fragmentFunction = library.makeFunction(name: "metallum_present_fs")
    else {
        NSLog("[metallum] Failed to create texture-copy shader functions")
        return nil
    }

    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.vertexFunction = vertexFunction
    descriptor.fragmentFunction = fragmentFunction
    descriptor.colorAttachments[0].pixelFormat = colorFormat
    descriptor.colorAttachments[0].isBlendingEnabled = false
    guard let pipeline = try? device.makeRenderPipelineState(descriptor: descriptor) else {
        NSLog("[metallum] Failed to create texture-copy render pipeline")
        return nil
    }
    NativeState.copyPipelines[key] = pipeline
    return pipeline
}

private func ensureClearColorDepthPipeline(_ device: MTLDevice, _ colorFormat: MTLPixelFormat, _ depthFormat: MTLPixelFormat, _ writeColor: Bool = true) -> MTLRenderPipelineState? {
    let key = PipelineVariantKey(deviceAddress: objectAddress(device), colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let cached = NativeState.clearPipelines[key] {
        return cached
    }
    let pipeline = buildClearPipeline(device: device, colorFormat: colorFormat, depthFormat: depthFormat, writeColor: writeColor)
    if let pipeline {
        NativeState.clearPipelines[key] = pipeline
    }
    return pipeline
}

#if os(macOS) && canImport(MetalFX)
private struct TransparencyMaskUniforms {
    var viewport: SIMD4<UInt32>
    var flags: SIMD4<UInt32>
    var params: SIMD4<Float>
}

private func transparencyMaskMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct TransparencyMaskUniforms {
      uint4 viewport;
      uint4 flags;
      float4 params;  // x = transparency reactive value
    };

    inline float targetActivity(
      texture2d<float, access::read> texture,
      uint2 pixel,
      bool alphaOnly
    ) {
      if (pixel.x >= texture.get_width() || pixel.y >= texture.get_height()) return 0.0;
      float4 value = texture.read(pixel);
      float coverage = alphaOnly
        ? value.a
        : max(value.a, max(value.r, max(value.g, value.b)));
      // FSR2 guidance: write the compositing strength, not a binary presence
      // bit, so faint content (thin rain streaks, cloud wisps) only mildly
      // biases toward the current frame while solid water/glass stays
      // protected at the full configured value.
      return coverage > 0.001 ? clamp(coverage, 0.0, 1.0) : 0.0;
    }

    kernel void metallum_transparency_mask(
      texture2d<float, access::read> translucentTexture [[texture(0)]],
      texture2d<float, access::read> itemEntityTexture [[texture(1)]],
      texture2d<float, access::read> particlesTexture [[texture(2)]],
      texture2d<float, access::read> weatherTexture [[texture(3)]],
      texture2d<float, access::read> cloudsTexture [[texture(4)]],
      texture2d<half, access::write> reactiveTexture [[texture(5)]],
      constant TransparencyMaskUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      uint width = u.viewport.x;
      uint height = u.viewport.y;
      if (pixel.x >= width || pixel.y >= height) return;

      uint flags = u.flags.x;
      // Transparency layers lack depth/motion and need a current-frame bias,
      // but full suppression (1.0) reintroduces shimmer; FSR2 guidance caps
      // reactive values around 0.9.
      float reactive = 0.0;
      bool alphaOnly = (flags & 32u) != 0u;
      bool sourceTags = (flags & 64u) != 0u;
      float translucent = (flags & 1u) != 0u ? targetActivity(translucentTexture, pixel, alphaOnly) : 0.0;
      float itemEntity = (flags & 2u) != 0u ? targetActivity(itemEntityTexture, pixel, alphaOnly) : 0.0;
      float particles = (flags & 4u) != 0u ? targetActivity(particlesTexture, pixel, alphaOnly) : 0.0;
      float weather = (flags & 8u) != 0u ? targetActivity(weatherTexture, pixel, alphaOnly) : 0.0;
      float clouds = (flags & 16u) != 0u ? targetActivity(cloudsTexture, pixel, alphaOnly) : 0.0;
      if (sourceTags) {
        if (translucent > 0.001) reactive = max(reactive, 0.125);
        if (itemEntity > 0.001) reactive = max(reactive, 0.250);
        if (particles > 0.001) reactive = max(reactive, 0.375);
        if (weather > 0.001) reactive = max(reactive, 0.500);
        if (clouds > 0.001) reactive = max(reactive, 0.625);
      } else {
        reactive = max(reactive, translucent * u.params.x);
        reactive = max(reactive, itemEntity * u.params.x);
        reactive = max(reactive, particles * u.params.x);
        reactive = max(reactive, weather * u.params.x);
        reactive = max(reactive, clouds * u.params.x);
      }
      reactiveTexture.write(half4(half(reactive), half(0.0), half(0.0), half(0.0)), pixel);
    }
    """
}

private func ensureTransparencyMaskPipeline(_ device: MTLDevice) -> MTLComputePipelineState? {
    if let pipeline = NativeState.transparencyMaskPipeline {
        return pipeline
    }
    do {
        let library = try device.makeLibrary(source: transparencyMaskMslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_transparency_mask") else {
            NSLog("[Metallum] MetalFX transparency mask function missing")
            return nil
        }
        function.label = "Transparency Mask"
        let pipeline = try device.makeComputePipelineState(function: function)
        NativeState.transparencyMaskPipeline = pipeline
        return pipeline
    } catch {
        NSLog("[Metallum] Failed to build MetalFX transparency mask pipeline: %@", String(describing: error))
        return nil
    }
}

private func cutoutReactiveDilationMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct CutoutReactiveUniforms {
      uint4 dims;      // x = width, y = height, z = radius, w = unused
      float4 weights;  // x = edge-band weight, y = interior weight
    };

    kernel void metallum_cutout_reactive_dilate(
      texture2d<float, access::read> cutoutCoverage [[texture(0)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(1)]],
      constant CutoutReactiveUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.dims.x || pixel.y >= u.dims.y) return;

      // Radius floors at 1: the edge band needs at least one neighbor to
      // detect a coverage transition, and it must span the jitter/upscale
      // reconstruction footprint on both sides of the alpha-test boundary.
      int radius = int(clamp(u.dims.z, 1u, 3u));
      float coverageMin = 1.0;
      float coverageMax = 0.0;
      bool windowInBounds = true;
      for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
          int2 samplePosition = int2(pixel) + int2(x, y);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(u.dims.x)
              || samplePosition.y >= int(u.dims.y)) {
            windowInBounds = false;
            continue;
          }
          float coverage = clamp(cutoutCoverage.read(uint2(samplePosition)).r, 0.0, 1.0);
          coverageMin = min(coverageMin, coverage);
          coverageMax = max(coverageMax, coverage);
        }
      }

      // Interior (window fully covered): history stays valid, accumulation
      // is what resolves jittered subpixel coverage — keep reactivity low.
      // Edge band (window mixed): the alpha-test decision can flip with
      // jitter, and history can smear a leaf into the hole during motion —
      // bias to the current frame, but far below full suppression
      // (FSR2 guidance: reactive near 1.0 never produces good results).
      float contribution = 0.0;
      if (coverageMax >= 0.5) {
        // A cutout touching the framebuffer boundary has unknown coverage
        // outside the drawable. Keep it in the protective edge band instead
        // of treating the clipped window as a fully covered interior.
        if (!windowInBounds) coverageMin = 0.0;
        contribution = coverageMin < 0.5 ? u.weights.x : u.weights.y;
      }
      float reactive = max(
        float(reactiveTexture.read(pixel).r),
        clamp(contribution, 0.0, 1.0)
      );
      reactiveTexture.write(
        half4(half(clamp(reactive, 0.0, 1.0)), half(0.0), half(0.0), half(0.0)),
        pixel
      );
    }
    """
}

private func ensureCutoutReactivePipeline(_ device: MTLDevice) -> MTLComputePipelineState? {
    if let pipeline = NativeState.cutoutReactivePipeline {
        return pipeline
    }
    do {
        let library = try device.makeLibrary(source: cutoutReactiveDilationMslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_cutout_reactive_dilate") else {
            NSLog("[Metallum] CUTOUT reactive dilation function missing")
            return nil
        }
        function.label = "CUTOUT Reactive Dilation"
        let pipeline = try device.makeComputePipelineState(function: function)
        NativeState.cutoutReactivePipeline = pipeline
        return pipeline
    } catch {
        NSLog("[Metallum] Failed to build CUTOUT reactive dilation pipeline: %@", String(describing: error))
        return nil
    }
}

private func handOverlayMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct HandOverlayUniforms {
      uint width;
      uint height;
      float reactiveBoost;
      float reserved;
    };

    kernel void metallum_hand_overlay_motion(
      texture2d<float, access::read> handDepthTexture [[texture(0)]],
      texture2d<half, access::write> objectMotionTexture [[texture(1)]],
      texture2d<half, access::write> objectValidityTexture [[texture(2)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(3)]],
      constant HandOverlayUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.width || pixel.y >= u.height) return;

      // Vanilla clears the reversed-Z depth buffer (to 0.0) right before the
      // first-person hand pass, so at upscale time any covered depth pixel is
      // camera-locked first-person content: hand, held item, and screen
      // effects. Their correct screen-space motion under camera movement is
      // zero; camera reprojection through the world depth behind them would
      // smear them during rotation. The residual swing/bob animation is
      // handled with a moderate reactive boost instead of motion vectors.
      float depth = handDepthTexture.read(pixel).r;
      if (!(isfinite(depth) && depth > 0.0000001)) return;

      objectMotionTexture.write(half4(half(0.0)), pixel);
      objectValidityTexture.write(
        half4(half(1.0), half(0.0), half(0.0), half(0.0)),
        pixel
      );
      float reactive = float(reactiveTexture.read(pixel).r);
      reactiveTexture.write(
        half4(
          half(clamp(max(reactive, u.reactiveBoost), 0.0, 1.0)),
          half(0.0), half(0.0), half(0.0)
        ),
        pixel
      );
    }
    """
}

private struct HandOverlayUniforms {
    var width: UInt32
    var height: UInt32
    var reactiveBoost: Float
    var reserved: Float
}

private func ensureHandOverlayPipeline(_ device: MTLDevice) -> MTLComputePipelineState? {
    if let pipeline = NativeState.handOverlayPipeline {
        return pipeline
    }
    do {
        let library = try device.makeLibrary(source: handOverlayMslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_hand_overlay_motion") else {
            NSLog("[Metallum] hand overlay motion function missing")
            return nil
        }
        function.label = "Hand Overlay Motion"
        let pipeline = try device.makeComputePipelineState(function: function)
        NativeState.handOverlayPipeline = pipeline
        return pipeline
    } catch {
        NSLog("[Metallum] Failed to build hand overlay motion pipeline: %@", String(describing: error))
        return nil
    }
}

private struct MotionUniforms {
    var currentViewProjection: simd_float4x4
    var inverseCurrentViewProjection: simd_float4x4
    var previousViewProjection: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
    var params: SIMD4<Float>
}

private func motionReconstructionMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct MotionUniforms {
      float4x4 currentViewProjection;
      float4x4 inverseCurrentViewProjection;
      float4x4 previousViewProjection;
      float4 viewport;
      uint4 flags;
      float4 params;  // x = depth-edge reactive cap
    };

    inline bool metallum_valid_depth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    inline float metallum_depth_edge_reactive(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth,
      float cap
    ) {
      bool centerValid = metallum_valid_depth(depth);
      float gradient = 0.0;
      bool validityBoundary = false;

      // CUTOUT terrain (leaves and grass) shares Minecraft's opaque target.
      // Inspecting both valid and cleared depth pixels catches the background
      // side of an alpha-cutout edge, where history would otherwise smear a
      // leaf into the hole during camera motion.
      for (int offsetY = -1; offsetY <= 1; ++offsetY) {
        for (int offsetX = -1; offsetX <= 1; ++offsetX) {
          if (offsetX == 0 && offsetY == 0) continue;
          int2 samplePosition = int2(pixel) + int2(offsetX, offsetY);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(width) || samplePosition.y >= int(height)) {
            continue;
          }
          float neighborDepth = depthTexture.read(uint2(samplePosition)).r;
          bool neighborValid = metallum_valid_depth(neighborDepth);
          if (centerValid != neighborValid) {
            validityBoundary = true;
          } else if (centerValid) {
            gradient = max(gradient, abs(depth - neighborDepth));
          }
        }
      }

      // Depth boundaries have valid depth and correct camera motion on the
      // covered side; they need a history bias against edge smear, not full
      // suppression. The cap keeps accumulation alive on foliage silhouettes.
      return validityBoundary ? cap : min(cap, clamp(gradient * 4.0, 0.0, 1.0));
    }

    kernel void metallum_motion_reconstruction(
      texture2d<float, access::read> depthTexture [[texture(0)]],
      texture2d<half, access::write> motionTexture [[texture(1)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(2)]],
      constant MotionUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      uint width = uint(u.viewport.x);
      uint height = uint(u.viewport.y);
      if (pixel.x >= width || pixel.y >= height) return;

      float depth = depthTexture.read(pixel).r;
      bool validDepth = metallum_valid_depth(depth);
      if (!validDepth && u.flags.y != 0u
          && isfinite(depth) && depth >= 0.0 && depth <= 0.00001) {
        // Cleared reversed-Z far plane (sky): reconstruct at a far-plane
        // depth so camera rotation produces correct flow (see the v2 kernel).
        depth = 0.00002;
        validDepth = true;
      }
      float2 uv = (float2(pixel) + 0.5) / float2(width, height);
      float2 motion = float2(0.0);
      float reactive = u.flags.x != 0u ? float(reactiveTexture.read(pixel).r) : 0.0;

      if (validDepth) {
        float4 currentNdc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
        float4 world = u.inverseCurrentViewProjection * currentNdc;
        if (isfinite(world.w) && abs(world.w) > 0.000001) {
          world /= world.w;
          float4 currentClip = u.currentViewProjection * world;
          float4 previousClip = u.previousViewProjection * world;
          if (isfinite(currentClip.w) && abs(currentClip.w) > 0.000001
              && isfinite(previousClip.w) && abs(previousClip.w) > 0.000001) {
            currentClip /= currentClip.w;
            previousClip /= previousClip.w;
            // Both projections are unjittered. The depth reconstruction uses
            // the jittered inverse, but jitter must not become object motion.
            // MetalFX motion vectors point from the current top-left screen
            // pixel to its previous-frame location. Clip-space Y points up,
            // while screen-space Y points down, so the Y subtraction is
            // intentionally opposite to X.
            motion.x = previousClip.x - currentClip.x;
            motion.y = currentClip.y - previousClip.y;
          } else {
            reactive = 1.0;
          }
        } else {
          reactive = 1.0;
        }

      }

      // Run this for both sides of a depth boundary. The cleared side is
      // invalid for reconstruction but still needs history rejection when a
      // cutout pixel can move into it.
      reactive = max(reactive, metallum_depth_edge_reactive(depthTexture, pixel, width, height, depth, u.params.x));

      if (!isfinite(motion.x) || !isfinite(motion.y)) {
        motion = float2(0.0);
        reactive = 1.0;
      }
      motionTexture.write(half4(half(motion.x), half(motion.y), half(0.0), half(0.0)), pixel);
      reactiveTexture.write(half4(half(reactive), half(0.0), half(0.0), half(0.0)), pixel);
    }
    """
}

private func makeMatrix(_ pointer: UnsafePointer<Float>) -> simd_float4x4 {
    simd_float4x4(
        SIMD4<Float>(pointer[0], pointer[1], pointer[2], pointer[3]),
        SIMD4<Float>(pointer[4], pointer[5], pointer[6], pointer[7]),
        SIMD4<Float>(pointer[8], pointer[9], pointer[10], pointer[11]),
        SIMD4<Float>(pointer[12], pointer[13], pointer[14], pointer[15])
    )
}

private func ensureMotionPipeline(_ device: MTLDevice) -> MTLComputePipelineState? {
    if let pipeline = NativeState.motionPipeline {
        return pipeline
    }
    do {
        let library = try device.makeLibrary(source: motionReconstructionMslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_motion_reconstruction") else {
            NSLog("[Metallum] MetalFX motion reconstruction function missing")
            return nil
        }
        function.label = "Motion Reconstruction"
        let pipeline = try device.makeComputePipelineState(function: function)
        NativeState.motionPipeline = pipeline
        return pipeline
    } catch {
        NSLog("[Metallum] Failed to build MetalFX motion reconstruction pipeline: %@", String(describing: error))
        return nil
    }
}

private func motionCameraV2MslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct MotionUniforms {
      float4x4 currentViewProjection;
      float4x4 inverseCurrentViewProjection;
      float4x4 previousViewProjection;
      float4 viewport;
      uint4 flags;
      float4 params;  // x = depth-edge reactive cap
    };

    inline bool validDepth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    inline float depthBoundary(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth,
      float cap
    ) {
      bool centerValid = validDepth(depth);
      float gradient = 0.0;
      bool validityBoundary = false;
      for (int offsetY = -1; offsetY <= 1; ++offsetY) {
        for (int offsetX = -1; offsetX <= 1; ++offsetX) {
          if (offsetX == 0 && offsetY == 0) continue;
          int2 samplePosition = int2(pixel) + int2(offsetX, offsetY);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(width) || samplePosition.y >= int(height)) continue;
          float neighborDepth = depthTexture.read(uint2(samplePosition)).r;
          bool neighborValid = validDepth(neighborDepth);
          if (centerValid != neighborValid) {
            validityBoundary = true;
          } else if (centerValid) {
            gradient = max(gradient, abs(depth - neighborDepth));
          }
        }
      }
      // Depth boundaries have valid depth and correct camera motion on the
      // covered side; they need a history bias against edge smear, not full
      // suppression. The cap keeps accumulation alive on foliage silhouettes.
      return validityBoundary ? cap : min(cap, clamp(gradient * 4.0, 0.0, 1.0));
    }

    kernel void metallum_motion_camera_v2(
      texture2d<float, access::read> depthTexture [[texture(0)]],
      texture2d<half, access::write> cameraMotionTexture [[texture(1)]],
      texture2d<half, access::write> disocclusionTexture [[texture(2)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(3)]],
      constant MotionUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      uint width = uint(u.viewport.x);
      uint height = uint(u.viewport.y);
      if (pixel.x >= width || pixel.y >= height) return;

      float depth = depthTexture.read(pixel).r;
      float2 motion = float2(0.0);
      float reactive = u.flags.x != 0u ? float(reactiveTexture.read(pixel).r) : 0.0;
      float disocclusion = 0.0;
      bool reconstruct = validDepth(depth);
      if (!reconstruct && u.flags.y != 0u
          && isfinite(depth) && depth >= 0.0 && depth <= 0.00001) {
        // Cleared reversed-Z far plane: the sky. Reconstruct at a far-plane
        // depth so camera rotation produces correct flow and the sky keeps
        // temporal accumulation on both sides of geometry silhouettes;
        // translation is negligible at the far plane. Without this the sky
        // is fully reactive every frame and silhouettes against it strobe.
        depth = 0.00002;
        reconstruct = true;
      }
      if (!reconstruct) {
        disocclusion = 1.0;
        reactive = 1.0;
      } else {
        float2 uv = (float2(pixel) + 0.5) / float2(width, height);
        float4 currentNdc = float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
        float4 world = u.inverseCurrentViewProjection * currentNdc;
        if (!isfinite(world.w) || abs(world.w) <= 0.000001) {
          disocclusion = 1.0;
          reactive = 1.0;
        } else {
          world /= world.w;
          float4 currentClip = u.currentViewProjection * world;
          float4 previousClip = u.previousViewProjection * world;
          if (!isfinite(currentClip.w) || abs(currentClip.w) <= 0.000001
              || !isfinite(previousClip.w) || abs(previousClip.w) <= 0.000001) {
            disocclusion = 1.0;
            reactive = 1.0;
          } else {
            currentClip /= currentClip.w;
            previousClip /= previousClip.w;
            motion = float2(previousClip.x - currentClip.x, currentClip.y - previousClip.y);
            if (previousClip.x < -1.0 || previousClip.x > 1.0
                || previousClip.y < -1.0 || previousClip.y > 1.0
                || !all(isfinite(motion)) || any(abs(motion) > float2(32.0))) {
              disocclusion = 1.0;
              reactive = 1.0;
              motion = float2(0.0);
            }
          }
        }
      }

      reactive = max(reactive, depthBoundary(depthTexture, pixel, width, height, depth, u.params.x));
      if (!isfinite(motion.x) || !isfinite(motion.y)) {
        motion = float2(0.0);
        disocclusion = 1.0;
        reactive = 1.0;
      }
      cameraMotionTexture.write(half4(half(motion.x), half(motion.y), half(0.0), half(0.0)), pixel);
      disocclusionTexture.write(half4(half(disocclusion), half(0.0), half(0.0), half(0.0)), pixel);
      reactiveTexture.write(half4(half(reactive), half(0.0), half(0.0), half(0.0)), pixel);
    }
    """
}

private func motionMergeV2MslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct MergeUniforms {
      uint4 viewport;
      uint4 flags;   // x = sky far-plane motion, y = reprojection depth dilation
      float4 params; // x = disocclusion reactive cap
    };

    inline bool validDepth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    kernel void metallum_motion_merge_v2(
      texture2d<half, access::read> cameraMotionTexture [[texture(0)]],
      texture2d<half, access::read> objectMotionTexture [[texture(1)]],
      texture2d<float, access::read> objectValidityTexture [[texture(2)]],
      texture2d<float, access::read_write> disocclusionTexture [[texture(3)]],
      texture2d<half, access::write> motionTexture [[texture(4)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(5)]],
      texture2d<float, access::read> previousDepthTexture [[texture(6)]],
      texture2d<float, access::read> currentDepthTexture [[texture(7)]],
      constant MergeUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.viewport.x || pixel.y >= u.viewport.y) return;
      float2 selected = float2(cameraMotionTexture.read(pixel).rg);
      float reactive = float(reactiveTexture.read(pixel).r);
      float objectValid = objectValidityTexture.read(pixel).r;
      if (isfinite(objectValid) && objectValid > 0.5) {
        float2 objectMotion = float2(objectMotionTexture.read(pixel).rg);
        if (all(isfinite(objectMotion)) && all(abs(objectMotion) <= float2(32.0))) {
          selected = objectMotion;
        } else {
          reactive = 1.0;
        }
      }
      float disocclusion = disocclusionTexture.read(pixel).r;
      if (u.viewport.z != 0u) {
        float currentDepth = currentDepthTexture.read(pixel).r;
        // Far-plane substitution mirrors the camera pass: cleared reversed-Z
        // sky participates in reprojection so sky-onto-sky is valid history
        // instead of a permanent per-frame disocclusion.
        bool skyCurrent = u.flags.x != 0u && isfinite(currentDepth)
            && currentDepth >= 0.0 && currentDepth <= 0.00001;
        if (skyCurrent) {
          currentDepth = 0.00002;
        }
        float2 previousPixel = float2(pixel) + 0.5
            + selected * float2(u.viewport.xy) * 0.5;
        if (!validDepth(currentDepth)
            || !all(isfinite(previousPixel))
            || previousPixel.x < 0.0 || previousPixel.y < 0.0
            || previousPixel.x >= float(u.viewport.x)
            || previousPixel.y >= float(u.viewport.y)) {
          disocclusion = 1.0;
        } else {
          uint2 samplePixel = uint2(previousPixel);
          // Depth dilation. A silhouette that jitters sub-pixel puts the
          // nearest-neighbour probe on the far side of the edge on alternating
          // frames, and leaf-vs-sky always clears the threshold below, so the
          // whole foliage/sky border was re-flagged as disoccluded every other
          // frame. Take the neighbourhood sample closest to the current depth
          // instead; radius 0 reproduces the legacy single probe exactly.
          int radius = u.flags.y != 0u ? 1 : 0;
          float previousDepth = 0.0;
          bool skyPrevious = false;
          float bestDelta = -1.0;
          for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
              int2 probe = int2(samplePixel) + int2(dx, dy);
              if (probe.x < 0 || probe.y < 0
                  || probe.x >= int(u.viewport.x) || probe.y >= int(u.viewport.y)) {
                continue;
              }
              float probeDepth = previousDepthTexture.read(uint2(probe)).r;
              bool probeSky = u.flags.x != 0u && isfinite(probeDepth)
                  && probeDepth >= 0.0 && probeDepth <= 0.00001;
              if (probeSky) {
                probeDepth = 0.00002;
              }
              float delta = isfinite(probeDepth)
                  ? abs(probeDepth - currentDepth)
                  : 1.0e30;
              if (bestDelta < 0.0 || delta < bestDelta) {
                bestDelta = delta;
                previousDepth = probeDepth;
                skyPrevious = probeSky;
              }
            }
          }
          if (skyPrevious && !skyCurrent) {
            // Geometry reprojecting onto previous-frame sky, with nothing
            // closer in the neighbourhood: newly revealed, and the
            // sky-colored history is invalid for it.
            disocclusion = 1.0;
          } else {
            float threshold = max(0.0025, abs(currentDepth) * 0.01);
            bool wasOccluded = u.viewport.w != 0u
                ? previousDepth > currentDepth + threshold
                : previousDepth < currentDepth - threshold;
            if (!validDepth(previousDepth) || wasOccluded) {
              disocclusion = 1.0;
            }
          }
        }
      }
      // FSR2 guidance: a reactive value at or near 1.0 never produces good
      // results. A disoccluded pixel has no usable history, but writing full
      // suppression is exactly what made jittered silhouettes strobe, so bias
      // strongly toward the current frame while leaving the accumulator a
      // share. Same policy as the CUTOUT edge band and the transparency mask.
      if (!isfinite(disocclusion) || disocclusion > 0.5) {
        reactive = max(reactive, u.params.x);
      }
      if (!all(isfinite(selected)) || any(abs(selected) > float2(32.0))) {
        selected = float2(0.0);
        reactive = 1.0;
      }
      motionTexture.write(half4(half(selected.x), half(selected.y), half(0.0), half(0.0)), pixel);
      disocclusionTexture.write(float4(clamp(disocclusion, 0.0, 1.0), 0.0, 0.0, 0.0), pixel);
      reactiveTexture.write(half4(half(clamp(reactive, 0.0, 1.0)), half(0.0), half(0.0), half(0.0)), pixel);
    }
    """
}

private func motionFusedV2MslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct FusedMotionUniforms {
      float4x4 currentViewProjection;
      float4x4 inverseCurrentViewProjection;
      float4x4 previousViewProjection;
      float4 viewport;
      // x = preserve reactive, y = sky far-plane motion,
      // z = previous depth valid, w = reversed depth.
      uint4 flags;
      // x = reprojection depth dilation, y = emit diagnostic textures,
      // z = first-person hand depth is bound.
      uint4 options;
      // x = depth-edge reactive cap, y = disocclusion reactive cap,
      // z = first-person reactive boost.
      float4 params;
    };

    inline bool fusedValidDepth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    inline float fusedDepthBoundary(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth,
      float cap
    ) {
      bool centerValid = fusedValidDepth(depth);
      float gradient = 0.0;
      bool validityBoundary = false;
      for (int offsetY = -1; offsetY <= 1; ++offsetY) {
        for (int offsetX = -1; offsetX <= 1; ++offsetX) {
          if (offsetX == 0 && offsetY == 0) continue;
          int2 samplePosition = int2(pixel) + int2(offsetX, offsetY);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(width) || samplePosition.y >= int(height)) continue;
          float neighborDepth = depthTexture.read(uint2(samplePosition)).r;
          bool neighborValid = fusedValidDepth(neighborDepth);
          if (centerValid != neighborValid) {
            validityBoundary = true;
          } else if (centerValid) {
            gradient = max(gradient, abs(depth - neighborDepth));
          }
        }
      }
      return validityBoundary ? cap : min(cap, clamp(gradient * 4.0, 0.0, 1.0));
    }

    inline float quantizeUnorm8(float value) {
      return rint(clamp(value, 0.0, 1.0) * 255.0) / 255.0;
    }

    kernel void metallum_motion_fused_v2(
      texture2d<float, access::read> depthTexture [[texture(0)]],
      texture2d<half, access::read> objectMotionTexture [[texture(1)]],
      texture2d<float, access::read> objectValidityTexture [[texture(2)]],
      texture2d<float, access::read> previousDepthTexture [[texture(3)]],
      texture2d<half, access::write> motionTexture [[texture(4)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(5)]],
      texture2d<half, access::write> cameraDiagnosticTexture [[texture(6)]],
      texture2d<half, access::write> disocclusionDiagnosticTexture [[texture(7)]],
      texture2d<float, access::read> handDepthTexture [[texture(8)]],
      constant FusedMotionUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      uint width = uint(u.viewport.x);
      uint height = uint(u.viewport.y);
      if (pixel.x >= width || pixel.y >= height) return;

      float currentDepth = depthTexture.read(pixel).r;
      float reconstructionDepth = currentDepth;
      float2 cameraMotion = float2(0.0);
      float reactive = u.flags.x != 0u ? float(reactiveTexture.read(pixel).r) : 0.0;
      float disocclusion = 0.0;
      bool reconstruct = fusedValidDepth(reconstructionDepth);
      if (!reconstruct && u.flags.y != 0u
          && isfinite(reconstructionDepth)
          && reconstructionDepth >= 0.0 && reconstructionDepth <= 0.00001) {
        reconstructionDepth = 0.00002;
        reconstruct = true;
      }
      if (!reconstruct) {
        disocclusion = 1.0;
        reactive = 1.0;
      } else {
        float2 uv = (float2(pixel) + 0.5) / float2(width, height);
        float4 currentNdc = float4(
          uv.x * 2.0 - 1.0,
          1.0 - uv.y * 2.0,
          reconstructionDepth,
          1.0
        );
        float4 world = u.inverseCurrentViewProjection * currentNdc;
        if (!isfinite(world.w) || abs(world.w) <= 0.000001) {
          disocclusion = 1.0;
          reactive = 1.0;
        } else {
          world /= world.w;
          float4 currentClip = u.currentViewProjection * world;
          float4 previousClip = u.previousViewProjection * world;
          if (!isfinite(currentClip.w) || abs(currentClip.w) <= 0.000001
              || !isfinite(previousClip.w) || abs(previousClip.w) <= 0.000001) {
            disocclusion = 1.0;
            reactive = 1.0;
          } else {
            currentClip /= currentClip.w;
            previousClip /= previousClip.w;
            cameraMotion = float2(
              previousClip.x - currentClip.x,
              currentClip.y - previousClip.y
            );
            if (previousClip.x < -1.0 || previousClip.x > 1.0
                || previousClip.y < -1.0 || previousClip.y > 1.0
                || !all(isfinite(cameraMotion))
                || any(abs(cameraMotion) > float2(32.0))) {
              disocclusion = 1.0;
              reactive = 1.0;
              cameraMotion = float2(0.0);
            }
          }
        }
      }

      reactive = max(
        reactive,
        fusedDepthBoundary(
          depthTexture,
          pixel,
          width,
          height,
          reconstructionDepth,
          u.params.x
        )
      );
      if (!all(isfinite(cameraMotion))) {
        cameraMotion = float2(0.0);
        disocclusion = 1.0;
        reactive = 1.0;
      }

      // The legacy path stores camera motion in RG16F and reactive in R8 before
      // the merge dispatch reads them. Preserve those quantization points so
      // fused/legacy validation compares semantics rather than precision drift.
      half2 storedCameraMotion = half2(cameraMotion);
      float2 selected = float2(storedCameraMotion);
      reactive = quantizeUnorm8(reactive);

      float objectValid = objectValidityTexture.read(pixel).r;
      if (isfinite(objectValid) && objectValid > 0.5) {
        float2 objectMotion = float2(objectMotionTexture.read(pixel).rg);
        if (all(isfinite(objectMotion)) && all(abs(objectMotion) <= float2(32.0))) {
          selected = objectMotion;
        } else {
          reactive = 1.0;
        }
      }

      if (u.options.z != 0u) {
        float handDepth = handDepthTexture.read(pixel).r;
        if (isfinite(handDepth) && handDepth > 0.0000001) {
          // The hand target is cleared immediately before first-person
          // rendering. Covered pixels are camera-locked, so zero motion is the
          // exact camera component; swing/bob remains protected by reactivity.
          selected = float2(0.0);
          reactive = max(reactive, quantizeUnorm8(u.params.z));
        }
      }

      if (u.flags.z != 0u) {
        float reprojectedCurrentDepth = currentDepth;
        bool skyCurrent = u.flags.y != 0u && isfinite(reprojectedCurrentDepth)
            && reprojectedCurrentDepth >= 0.0 && reprojectedCurrentDepth <= 0.00001;
        if (skyCurrent) {
          reprojectedCurrentDepth = 0.00002;
        }
        float2 previousPixel = float2(pixel) + 0.5
            + selected * float2(width, height) * 0.5;
        if (!fusedValidDepth(reprojectedCurrentDepth)
            || !all(isfinite(previousPixel))
            || previousPixel.x < 0.0 || previousPixel.y < 0.0
            || previousPixel.x >= float(width) || previousPixel.y >= float(height)) {
          disocclusion = 1.0;
        } else {
          uint2 samplePixel = uint2(previousPixel);
          int radius = u.options.x != 0u ? 1 : 0;
          float previousDepth = 0.0;
          bool skyPrevious = false;
          float bestDelta = -1.0;
          for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
              int2 probe = int2(samplePixel) + int2(dx, dy);
              if (probe.x < 0 || probe.y < 0
                  || probe.x >= int(width) || probe.y >= int(height)) continue;
              float probeDepth = previousDepthTexture.read(uint2(probe)).r;
              bool probeSky = u.flags.y != 0u && isfinite(probeDepth)
                  && probeDepth >= 0.0 && probeDepth <= 0.00001;
              if (probeSky) {
                probeDepth = 0.00002;
              }
              float delta = isfinite(probeDepth)
                  ? abs(probeDepth - reprojectedCurrentDepth)
                  : 1.0e30;
              if (bestDelta < 0.0 || delta < bestDelta) {
                bestDelta = delta;
                previousDepth = probeDepth;
                skyPrevious = probeSky;
              }
            }
          }
          if (skyPrevious && !skyCurrent) {
            disocclusion = 1.0;
          } else {
            float threshold = max(0.0025, abs(reprojectedCurrentDepth) * 0.01);
            bool wasOccluded = u.flags.w != 0u
                ? previousDepth > reprojectedCurrentDepth + threshold
                : previousDepth < reprojectedCurrentDepth - threshold;
            if (!fusedValidDepth(previousDepth) || wasOccluded) {
              disocclusion = 1.0;
            }
          }
        }
      }

      if (!isfinite(disocclusion) || disocclusion > 0.5) {
        reactive = max(reactive, u.params.y);
      }
      if (!all(isfinite(selected)) || any(abs(selected) > float2(32.0))) {
        selected = float2(0.0);
        reactive = 1.0;
      }

      motionTexture.write(
        half4(half(selected.x), half(selected.y), half(0.0), half(0.0)),
        pixel
      );
      reactiveTexture.write(
        half4(half(clamp(reactive, 0.0, 1.0)), half(0.0), half(0.0), half(0.0)),
        pixel
      );
      if (u.options.y != 0u) {
        cameraDiagnosticTexture.write(
          half4(storedCameraMotion.x, storedCameraMotion.y, half(0.0), half(0.0)),
          pixel
        );
        disocclusionDiagnosticTexture.write(
          half4(half(clamp(disocclusion, 0.0, 1.0)), half(0.0), half(0.0), half(0.0)),
          pixel
        );
      }
    }
    """
}

private func motionClearV2MslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct ClearUniforms {
      uint2 viewport;
    };

    kernel void metallum_motion_clear_v2(
      texture2d<half, access::write> objectMotionTexture [[texture(0)]],
      texture2d<half, access::write> objectValidityTexture [[texture(1)]],
      constant ClearUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.viewport.x || pixel.y >= u.viewport.y) return;
      objectMotionTexture.write(half4(half(0.0)), pixel);
      objectValidityTexture.write(half4(half(0.0)), pixel);
    }
    """
}

private func ensureMotionV2Pipelines(_ device: MTLDevice) -> (
    camera: MTLComputePipelineState,
    merge: MTLComputePipelineState,
    fused: MTLComputePipelineState,
    clear: MTLComputePipelineState
)? {
    if let camera = NativeState.motionV2Pipeline,
       let merge = NativeState.motionMergePipeline,
       let fused = NativeState.motionFusedPipeline,
       let clear = NativeState.motionClearPipeline {
        return (camera, merge, fused, clear)
    }
    do {
        let cameraLibrary = try device.makeLibrary(source: motionCameraV2MslSource(), options: nil)
        let mergeLibrary = try device.makeLibrary(source: motionMergeV2MslSource(), options: nil)
        let fusedLibrary = try device.makeLibrary(source: motionFusedV2MslSource(), options: nil)
        let clearLibrary = try device.makeLibrary(source: motionClearV2MslSource(), options: nil)
        guard let cameraFunction = cameraLibrary.makeFunction(name: "metallum_motion_camera_v2"),
              let mergeFunction = mergeLibrary.makeFunction(name: "metallum_motion_merge_v2"),
              let fusedFunction = fusedLibrary.makeFunction(name: "metallum_motion_fused_v2"),
              let clearFunction = clearLibrary.makeFunction(name: "metallum_motion_clear_v2") else {
            NSLog("[Metallum] MetalFX v2 motion compute function missing")
            return nil
        }
        let camera = try device.makeComputePipelineState(function: cameraFunction)
        let merge = try device.makeComputePipelineState(function: mergeFunction)
        let fused = try device.makeComputePipelineState(function: fusedFunction)
        let clear = try device.makeComputePipelineState(function: clearFunction)
        NativeState.motionV2Pipeline = camera
        NativeState.motionMergePipeline = merge
        NativeState.motionFusedPipeline = fused
        NativeState.motionClearPipeline = clear
        return (camera, merge, fused, clear)
    } catch {
        NSLog("[Metallum] Failed to build MetalFX v2 motion pipelines: %@", String(describing: error))
        return nil
    }
}

private func metalFxScalerKey(_ device: MTLDevice, _ temporal: Bool, _ color: MTLTexture, _ output: MTLTexture) -> String {
    "\(objectAddress(device))-\(temporal ? 1 : 0)-\(color.pixelFormat.rawValue)-\(output.pixelFormat.rawValue)-\(color.width)x\(color.height)-\(output.width)x\(output.height)"
}
#endif

@_cdecl("metallum_init_pipelines")
public func metallum_init_pipelines(_ device: MTLDevice) {
    autoreleasepool {
        NativeState.presentPipeline = buildPresentPipeline(device: device, colorFormat: .bgra8Unorm)
        NativeState.presentLinearSampler = buildPresentSampler(device: device, filter: .linear)
        NativeState.presentNearestSampler = buildPresentSampler(device: device, filter: .nearest)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .rgba8Unorm, .depth32Float)
        _ = ensureClearColorDepthPipeline(device, .bgra8Unorm, .invalid)
    }
}

@_cdecl("metallum_metalfx_supports_spatial")
public func metallum_metalfx_supports_spatial(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return MTLFXSpatialScalerDescriptor.supportsDevice(device) ? 1 : 0
    }
    #endif
    return 0
}

@_cdecl("metallum_metalfx_supports_temporal")
public func metallum_metalfx_supports_temporal(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return MTLFXTemporalScalerDescriptor.supportsDevice(device) ? 1 : 0
    }
    #endif
    return 0
}

@_cdecl("metallum_metalfx_supports_frame_generation")
public func metallum_metalfx_supports_frame_generation(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        return MTLFXFrameInterpolatorDescriptor.supportsDevice(device) ? 1 : 0
    }
    #endif
    return 0
}

@_cdecl("metallum_metalfx_supports_motion_v2")
public func metallum_metalfx_supports_motion_v2(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return ensureMotionV2Pipelines(device) != nil ? 1 : 0
    }
    #endif
    return 0
}

@_cdecl("metallum_metalfx_set_reactive_tuning")
public func metallum_metalfx_set_reactive_tuning(
    _ cutoutEdgeWeight: Float,
    _ cutoutInteriorWeight: Float,
    _ depthEdgeCap: Float,
    _ transparencyValue: Float,
    _ skyFarPlaneMotion: Float,
    _ disocclusionReactiveCap: Float,
    _ mergeDepthDilation: Float
) {
    #if os(macOS) && canImport(MetalFX)
    func clamped(_ value: Float, _ fallback: Float) -> Float {
        value.isFinite ? min(max(value, 0.0), 1.0) : fallback
    }
    NativeState.reactiveTuning = SIMD4<Float>(
        clamped(cutoutEdgeWeight, 0.35),
        clamped(cutoutInteriorWeight, 0.0),
        clamped(depthEdgeCap, 0.5),
        clamped(transparencyValue, 0.9)
    )
    NativeState.skyFarPlaneMotion = skyFarPlaneMotion.isFinite && skyFarPlaneMotion > 0.5 ? 1.0 : 0.0
    NativeState.disocclusionReactiveCap = clamped(disocclusionReactiveCap, 0.85)
    NativeState.mergeDepthDilation = mergeDepthDilation.isFinite && mergeDepthDilation > 0.5 ? 1.0 : 0.0
    NSLog(
        "[Metallum] MetalFX reactive tuning: cutoutEdge=%.3f cutoutInterior=%.3f depthEdgeCap=%.3f transparency=%.3f skyFarPlaneMotion=%.0f disocclusionCap=%.3f depthDilation=%.0f",
        NativeState.reactiveTuning.x,
        NativeState.reactiveTuning.y,
        NativeState.reactiveTuning.z,
        NativeState.reactiveTuning.w,
        NativeState.skyFarPlaneMotion,
        NativeState.disocclusionReactiveCap,
        NativeState.mergeDepthDilation
    )
    #endif
}

@_cdecl("metallum_metalfx_supports_cutout_reactive")
public func metallum_metalfx_supports_cutout_reactive(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return ensureCutoutReactivePipeline(device) != nil ? 1 : 0
    }
    #endif
    return 0
}

private func metal3MetalFxApplyCutoutReactive(
    _ commandBuffer: MTLCommandBuffer,
    _ cutoutCoverageTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ radius: Int32,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  radius >= 0, radius <= 3,
                  cutoutCoverageTexture.width == Int(inputWidth),
                  cutoutCoverageTexture.height == Int(inputHeight),
                  reactiveTexture.width == Int(inputWidth),
                  reactiveTexture.height == Int(inputHeight),
                  cutoutCoverageTexture.pixelFormat == .r8Unorm,
                  reactiveTexture.pixelFormat == .r8Unorm,
                  let pipeline = ensureCutoutReactivePipeline(commandBuffer.device),
                  let encoder = commandBuffer.makeComputeCommandEncoder() else {
                logMetalFxFailureOnce(
                    "cutout-reactive",
                    "invalid CUTOUT coverage resources or missing dilation pipeline"
                )
                return 0
            }
            encoder.label = "MetalFX CUTOUT Coverage Reactive Dilation"
            metal4BarrierComputeAfterRender(encoder)
            if let fence {
                encoder.waitForFence(fence)
            }
            struct CutoutReactiveUniforms {
                var dims: SIMD4<UInt32>
                var weights: SIMD4<Float>
            }
            var uniforms = CutoutReactiveUniforms(
                dims: SIMD4<UInt32>(
                    UInt32(inputWidth),
                    UInt32(inputHeight),
                    UInt32(radius),
                    0
                ),
                weights: SIMD4<Float>(
                    NativeState.reactiveTuning.x,
                    NativeState.reactiveTuning.y,
                    0.0,
                    0.0
                )
            )
            encoder.setComputePipelineState(pipeline)
            encoder.setBytes(
                &uniforms,
                length: MemoryLayout<CutoutReactiveUniforms>.stride,
                index: 0
            )
            encoder.setTexture(cutoutCoverageTexture, index: 0)
            encoder.setTexture(reactiveTexture, index: 1)
            let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
            let threadHeight = max(
                1,
                min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth)
            )
            encoder.dispatchThreads(
                MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                threadsPerThreadgroup: MTLSize(
                    width: threadWidth,
                    height: threadHeight,
                    depth: 1
                )
            )
            if let fence {
                encoder.updateFence(fence)
            }
            encoder.endEncoding()
            return 1
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_apply_cutout_reactive(
    _ commandBuffer: MTLCommandBuffer,
    _ cutoutCoverageTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ radius: Int32,
    _ fence: MTLFence?
) -> Int32 {
    metallumMetalFxApplyCutoutReactiveEntry(
        commandBufferPointer(commandBuffer), cutoutCoverageTexture, reactiveTexture,
        inputWidth, inputHeight, radius, fence
    )
}

@_cdecl("metallum_metalfx_apply_cutout_reactive")
public func metallumMetalFxApplyCutoutReactiveEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ cutoutCoverageTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ radius: Int32,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if NativeState.skipMetal4CutoutReactive,
       #available(macOS 26.0, iOS 26.0, *),
       metal4MainLease(commandBufferPointer) != nil {
        return 1
    }
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer),
       inputWidth > 0, inputHeight > 0, radius >= 0, radius <= 3,
       cutoutCoverageTexture.width == Int(inputWidth),
       cutoutCoverageTexture.height == Int(inputHeight),
       reactiveTexture.width == Int(inputWidth), reactiveTexture.height == Int(inputHeight),
       cutoutCoverageTexture.pixelFormat == .r8Unorm,
       reactiveTexture.pixelFormat == .r8Unorm,
       let pipeline = ensureCutoutReactivePipeline(cutoutCoverageTexture.device) {
        struct Uniforms {
            var dims: SIMD4<UInt32>
            var weights: SIMD4<Float>
        }
        let uniforms = Uniforms(
            dims: SIMD4(UInt32(inputWidth), UInt32(inputHeight), UInt32(radius), 0),
            weights: SIMD4(NativeState.reactiveTuning.x, NativeState.reactiveTuning.y, 0, 0)
        )
        return encodeMetal4Compute(
            lease: lease,
            label: "MetalFX CUTOUT Coverage Reactive Dilation (Metal 4)",
            pipeline: pipeline,
            uniforms: uniforms,
            textures: [(0, cutoutCoverageTexture), (1, reactiveTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) ? 1 : 0
    }
    #endif
    return metal3MetalFxApplyCutoutReactive(
        metal3CommandBuffer(commandBufferPointer), cutoutCoverageTexture, reactiveTexture,
        inputWidth, inputHeight, radius, fence
    )
}

@_cdecl("metallum_metalfx_supports_hand_overlay")
public func metallum_metalfx_supports_hand_overlay(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    return ensureHandOverlayPipeline(device) != nil ? 1 : 0
    #else
    return 0
    #endif
}

private func metal3MetalFxEncodeHandOverlay(
    _ commandBuffer: MTLCommandBuffer,
    _ handDepthTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reactiveBoost: Float,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    return autoreleasepool {
        guard inputWidth > 0, inputHeight > 0,
              handDepthTexture.width == Int(inputWidth),
              handDepthTexture.height == Int(inputHeight),
              objectMotionTexture.width == Int(inputWidth),
              objectMotionTexture.height == Int(inputHeight),
              objectValidityTexture.width == Int(inputWidth),
              objectValidityTexture.height == Int(inputHeight),
              reactiveTexture.width == Int(inputWidth),
              reactiveTexture.height == Int(inputHeight),
              objectMotionTexture.pixelFormat == .rg16Float,
              objectValidityTexture.pixelFormat == .r8Unorm,
              reactiveTexture.pixelFormat == .r8Unorm,
              let pipeline = ensureHandOverlayPipeline(commandBuffer.device),
              let encoder = commandBuffer.makeComputeCommandEncoder() else {
            logMetalFxFailureOnce(
                "hand-overlay",
                "invalid hand overlay resources or missing pipeline"
            )
            return 0
        }
        encoder.label = "MetalFX Hand Overlay Motion"
        metal4BarrierComputeAfterRender(encoder)
        if let fence {
            encoder.waitForFence(fence)
        }
        var uniforms = HandOverlayUniforms(
            width: UInt32(inputWidth),
            height: UInt32(inputHeight),
            reactiveBoost: reactiveBoost,
            reserved: 0.0
        )
        encoder.setComputePipelineState(pipeline)
        encoder.setBytes(
            &uniforms,
            length: MemoryLayout<HandOverlayUniforms>.stride,
            index: 0
        )
        encoder.setTexture(handDepthTexture, index: 0)
        encoder.setTexture(objectMotionTexture, index: 1)
        encoder.setTexture(objectValidityTexture, index: 2)
        encoder.setTexture(reactiveTexture, index: 3)
        let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
        let threadHeight = max(
            1,
            min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth)
        )
        encoder.dispatchThreads(
            MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
            threadsPerThreadgroup: MTLSize(
                width: threadWidth,
                height: threadHeight,
                depth: 1
            )
        )
        if let fence {
            encoder.updateFence(fence)
        }
        encoder.endEncoding()
        return 1
    }
    #else
    return 0
    #endif
}

public func metallum_metalfx_encode_hand_overlay(
    _ commandBuffer: MTLCommandBuffer,
    _ handDepthTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reactiveBoost: Float,
    _ fence: MTLFence?
) -> Int32 {
    metallumMetalFxEncodeHandOverlayEntry(
        commandBufferPointer(commandBuffer), handDepthTexture, objectMotionTexture,
        objectValidityTexture, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
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
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer), inputWidth > 0, inputHeight > 0,
       handDepthTexture.width == Int(inputWidth), handDepthTexture.height == Int(inputHeight),
       objectMotionTexture.width == Int(inputWidth), objectMotionTexture.height == Int(inputHeight),
       objectValidityTexture.width == Int(inputWidth), objectValidityTexture.height == Int(inputHeight),
       reactiveTexture.width == Int(inputWidth), reactiveTexture.height == Int(inputHeight),
       objectMotionTexture.pixelFormat == .rg16Float,
       objectValidityTexture.pixelFormat == .r8Unorm, reactiveTexture.pixelFormat == .r8Unorm,
       let pipeline = ensureHandOverlayPipeline(handDepthTexture.device) {
        let uniforms = HandOverlayUniforms(
            width: UInt32(inputWidth), height: UInt32(inputHeight),
            reactiveBoost: reactiveBoost, reserved: 0
        )
        return encodeMetal4Compute(
            lease: lease, label: "MetalFX Hand Overlay Motion (Metal 4)",
            pipeline: pipeline, uniforms: uniforms,
            textures: [(0, handDepthTexture), (1, objectMotionTexture),
                       (2, objectValidityTexture), (3, reactiveTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) ? 1 : 0
    }
    #endif
    return metal3MetalFxEncodeHandOverlay(
        metal3CommandBuffer(commandBufferPointer), handDepthTexture, objectMotionTexture,
        objectValidityTexture, reactiveTexture, inputWidth, inputHeight, reactiveBoost, fence
    )
}

private func metal3MetalFxClearMotionInputs(
    _ commandBuffer: MTLCommandBuffer,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  objectMotionTexture.width == Int(inputWidth),
                  objectMotionTexture.height == Int(inputHeight),
                  objectValidityTexture.width == Int(inputWidth),
                  objectValidityTexture.height == Int(inputHeight),
                  let pipelines = ensureMotionV2Pipelines(commandBuffer.device),
                  let encoder = commandBuffer.makeComputeCommandEncoder() else {
                logMetalFxFailureOnce("motion-clear", "invalid object motion resources or missing v2 clear pipeline")
                return 0
            }
            encoder.label = "MetalFX Clear Object Motion Inputs"
            metal4BarrierComputeAfterRender(encoder)
            if let fence {
                encoder.waitForFence(fence)
            }
            var uniforms = SIMD2<UInt32>(UInt32(inputWidth), UInt32(inputHeight))
            encoder.setComputePipelineState(pipelines.clear)
            encoder.setBytes(&uniforms, length: MemoryLayout<SIMD2<UInt32>>.stride, index: 0)
            encoder.setTexture(objectMotionTexture, index: 0)
            encoder.setTexture(objectValidityTexture, index: 1)
            let threadWidth = max(1, min(pipelines.clear.threadExecutionWidth, 64))
            let threadHeight = max(1, min(8, pipelines.clear.maxTotalThreadsPerThreadgroup / threadWidth))
            encoder.dispatchThreads(
                MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
            )
            if let fence {
                encoder.updateFence(fence)
            }
            encoder.endEncoding()
            return 1
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_clear_motion_inputs(
    _ commandBuffer: MTLCommandBuffer,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ fence: MTLFence?
) -> Int32 {
    metallumMetalFxClearMotionInputsEntry(
        commandBufferPointer(commandBuffer), objectMotionTexture, objectValidityTexture,
        inputWidth, inputHeight, fence
    )
}

@_cdecl("metallum_metalfx_clear_motion_inputs")
public func metallumMetalFxClearMotionInputsEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ fence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer), inputWidth > 0, inputHeight > 0,
       objectMotionTexture.width == Int(inputWidth), objectMotionTexture.height == Int(inputHeight),
       objectValidityTexture.width == Int(inputWidth), objectValidityTexture.height == Int(inputHeight),
       let pipelines = ensureMotionV2Pipelines(objectMotionTexture.device) {
        let uniforms = SIMD2<UInt32>(UInt32(inputWidth), UInt32(inputHeight))
        return encodeMetal4Compute(
            lease: lease, label: "MetalFX Clear Object Motion Inputs (Metal 4)",
            pipeline: pipelines.clear, uniforms: uniforms,
            textures: [(0, objectMotionTexture), (1, objectValidityTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) ? 1 : 0
    }
    #endif
    return metal3MetalFxClearMotionInputs(
        metal3CommandBuffer(commandBufferPointer), objectMotionTexture, objectValidityTexture,
        inputWidth, inputHeight, fence
    )
}

private func metal3MetalFxMarkTransparency(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ translucentTexture: MTLTexture?,
    _ itemEntityTexture: MTLTexture?,
    _ particlesTexture: MTLTexture?,
    _ weatherTexture: MTLTexture?,
    _ cloudsTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  let pipeline = ensureTransparencyMaskPipeline(device),
                  let encoder = commandBuffer.makeComputeCommandEncoder() else {
                logMetalFxFailureOnce("transparency-mask-encode", "could not create transparency mask pipeline or encoder")
                return 0
            }
            // E7: this encoder has no fence at all under Metal 3 (barrier map section 0).
            metal4BarrierComputeAfterRender(encoder)

            var flags: UInt32 = 0
            if translucentTexture != nil { flags |= 1 << 0 }
            if itemEntityTexture != nil { flags |= 1 << 1 }
            if particlesTexture != nil { flags |= 1 << 2 }
            if weatherTexture != nil { flags |= 1 << 3 }
            if cloudsTexture != nil { flags |= 1 << 4 }
            if NativeState.transparencyAlphaOnly { flags |= 1 << 5 }
            if NativeState.transparencySourceTags { flags |= 1 << 6 }
            var uniforms = TransparencyMaskUniforms(
                viewport: SIMD4<UInt32>(UInt32(inputWidth), UInt32(inputHeight), 0, 0),
                flags: SIMD4<UInt32>(flags, 0, 0, 0),
                params: SIMD4<Float>(NativeState.reactiveTuning.w, 0.0, 0.0, 0.0)
            )

            encoder.setComputePipelineState(pipeline)
            encoder.setBytes(&uniforms, length: MemoryLayout<TransparencyMaskUniforms>.stride, index: 0)
            encoder.setTexture(translucentTexture, index: 0)
            encoder.setTexture(itemEntityTexture, index: 1)
            encoder.setTexture(particlesTexture, index: 2)
            encoder.setTexture(weatherTexture, index: 3)
            encoder.setTexture(cloudsTexture, index: 4)
            encoder.setTexture(reactiveTexture, index: 5)
            // Validation instrumentation can report an inflated execution
            // width. Keep the group within a portable Apple GPU width while
            // still using the device-reported SIMD width on normal runs.
            let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
            let threadHeight = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth))
            encoder.dispatchThreads(
                MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
            )
            encoder.endEncoding()
            return 1
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_mark_transparency(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ translucentTexture: MTLTexture?,
    _ itemEntityTexture: MTLTexture?,
    _ particlesTexture: MTLTexture?,
    _ weatherTexture: MTLTexture?,
    _ cloudsTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32
) -> Int32 {
    metallumMetalFxMarkTransparencyEntry(
        commandBufferPointer(commandBuffer), device, translucentTexture, itemEntityTexture,
        particlesTexture, weatherTexture, cloudsTexture, reactiveTexture, inputWidth, inputHeight
    )
}

@_cdecl("metallum_metalfx_mark_transparency")
public func metallumMetalFxMarkTransparencyEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ device: MTLDevice,
    _ translucentTexture: MTLTexture?,
    _ itemEntityTexture: MTLTexture?,
    _ particlesTexture: MTLTexture?,
    _ weatherTexture: MTLTexture?,
    _ cloudsTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if NativeState.skipMetal4TransparencyReactive,
       #available(macOS 26.0, iOS 26.0, *),
       metal4MainLease(commandBufferPointer) != nil {
        return 1
    }
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer), inputWidth > 0, inputHeight > 0,
       let pipeline = ensureTransparencyMaskPipeline(device) {
        var flags: UInt32 = 0
        if translucentTexture != nil { flags |= 1 << 0 }
        if itemEntityTexture != nil { flags |= 1 << 1 }
        if particlesTexture != nil { flags |= 1 << 2 }
        if weatherTexture != nil { flags |= 1 << 3 }
        if cloudsTexture != nil { flags |= 1 << 4 }
        if NativeState.transparencyAlphaOnly { flags |= 1 << 5 }
        if NativeState.transparencySourceTags { flags |= 1 << 6 }
        let uniforms = TransparencyMaskUniforms(
            viewport: SIMD4(UInt32(inputWidth), UInt32(inputHeight), 0, 0),
            flags: SIMD4(flags, 0, 0, 0),
            params: SIMD4(NativeState.reactiveTuning.w, 0, 0, 0)
        )
        return encodeMetal4Compute(
            lease: lease, label: "MetalFX Transparency Reactive Mask (Metal 4)",
            pipeline: pipeline, uniforms: uniforms,
            textures: [(0, translucentTexture), (1, itemEntityTexture), (2, particlesTexture),
                       (3, weatherTexture), (4, cloudsTexture), (5, reactiveTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) ? 1 : 0
    }
    #endif
    return metal3MetalFxMarkTransparency(
        metal3CommandBuffer(commandBufferPointer), device, translucentTexture, itemEntityTexture,
        particlesTexture, weatherTexture, cloudsTexture, reactiveTexture, inputWidth, inputHeight
    )
}

private func metal3MetalFxEncode(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ colorTexture: MTLTexture,
    _ depthTexture: MTLTexture?,
    _ motionTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture?,
    _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?,
    _ fence: MTLFence?,
    _ jitterX: Float,
    _ jitterY: Float,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reset: Int32,
    _ depthReversed: Int32,
    _ preserveReactiveMask: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            let temporal = motionTexture != nil && depthTexture != nil
            let key = metalFxScalerKey(device, temporal, colorTexture, outputTexture)
            let scalerObject: AnyObject?
            if temporal {
                // Temporal upscaling lives in metallum_metalfx_encode_v2, which
                // owns the camera/object motion merge, the disocclusion signal
                // and the previous-depth history. The removed path here also
                // carried a latent cache hazard: it enabled the reactive mask
                // on the descriptor only when a reactive texture was supplied,
                // while metalFxScalerKey encodes neither that flag nor the
                // depth/motion formats, so one nil-reactive call could cache a
                // non-reactive scaler under the key the reactive path reuses.
                logMetalFxFailureOnce(
                    "temporal-v1-removed",
                    "metallum_metalfx_encode is spatial-only; temporal upscaling must use metallum_metalfx_encode_v2"
                )
                return 0
            } else {
                if let cached = NativeState.metalFxScalers[key] {
                    scalerObject = cached
                } else {
                    let descriptor = MTLFXSpatialScalerDescriptor()
                    descriptor.colorTextureFormat = colorTexture.pixelFormat
                    descriptor.outputTextureFormat = outputTexture.pixelFormat
                    descriptor.inputWidth = colorTexture.width
                    descriptor.inputHeight = colorTexture.height
                    descriptor.outputWidth = outputTexture.width
                    descriptor.outputHeight = outputTexture.height
                    // Minecraft's scene target is a plain (non-_srgb) UNORM
                    // texture holding already-tonemapped, gamma-encoded values,
                    // and the layer is .bgra8Unorm, so Metal performs no
                    // decode on read. Declaring .linear would make the spatial
                    // scaler interpolate gamma values as if they were linear
                    // and halo high-contrast edges.
                    descriptor.colorProcessingMode = .perceptual
                    guard let scaler = descriptor.makeSpatialScaler(device: device) else {
                        logMetalFxFailureOnce(
                            "spatial-create",
                            "descriptor rejected color=\(colorTexture.pixelFormat.rawValue) output=\(outputTexture.pixelFormat.rawValue) input=\(colorTexture.width)x\(colorTexture.height) output=\(outputTexture.width)x\(outputTexture.height) colorUsage=\(colorTexture.usage.rawValue) outputUsage=\(outputTexture.usage.rawValue) colorStorage=\(colorTexture.storageMode.rawValue) outputStorage=\(outputTexture.storageMode.rawValue)"
                        )
                        return 0
                    }
                    scalerObject = scaler as AnyObject
                    NativeState.metalFxScalers[key] = scaler as AnyObject
                }
                guard let scaler = scalerObject as? any MTLFXSpatialScaler else {
                    logMetalFxFailureOnce("spatial-cast", "cached scaler did not conform to MTLFXSpatialScaler")
                    return 0
                }
                scaler.colorTexture = colorTexture
                scaler.outputTexture = outputTexture
                scaler.inputContentWidth = Int(inputWidth)
                scaler.inputContentHeight = Int(inputHeight)
                scaler.fence = fence
                commandBuffer.pushDebugGroup("MetalFX Spatial Upscale")
                scaler.encode(commandBuffer: commandBuffer)
                commandBuffer.popDebugGroup()
                return 1
            }
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_encode(
    _ commandBuffer: MTLCommandBuffer, _ device: MTLDevice,
    _ colorTexture: MTLTexture, _ depthTexture: MTLTexture?, _ motionTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture?, _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?, _ fence: MTLFence?,
    _ jitterX: Float, _ jitterY: Float, _ inputWidth: Int32, _ inputHeight: Int32,
    _ reset: Int32, _ depthReversed: Int32, _ preserveReactiveMask: Int32
) -> Int32 {
    metallumMetalFxEncodeEntry(
        commandBufferPointer(commandBuffer), device, colorTexture, depthTexture, motionTexture,
        reactiveTexture, outputTexture, currentViewProjection, inverseCurrentViewProjection,
        previousViewProjection, fence, jitterX, jitterY, inputWidth, inputHeight,
        reset, depthReversed, preserveReactiveMask
    )
}

@_cdecl("metallum_metalfx_encode")
public func metallumMetalFxEncodeEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer, _ device: MTLDevice,
    _ colorTexture: MTLTexture, _ depthTexture: MTLTexture?, _ motionTexture: MTLTexture?,
    _ reactiveTexture: MTLTexture?, _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?, _ fence: MTLFence?,
    _ jitterX: Float, _ jitterY: Float, _ inputWidth: Int32, _ inputHeight: Int32,
    _ reset: Int32, _ depthReversed: Int32, _ preserveReactiveMask: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer) {
        guard let fence else {
            logMetalFxFailureOnce(
                "spatial-metal4-fence",
                "Metal 4 Spatial requires a synchronization fence"
            )
            return 0
        }
        guard depthTexture == nil, motionTexture == nil,
              inputWidth > 0, inputHeight > 0,
              let compiler = NativeState.metal4Compiler(device) else { return 0 }
        let key = "m4-spatial-" + metalFxScalerKey(device, false, colorTexture, outputTexture)
        let scaler: any MTL4FXSpatialScaler
        if let cached = NativeState.metalFxScalers[key] as? any MTL4FXSpatialScaler {
            scaler = cached
        } else {
            let descriptor = MTLFXSpatialScalerDescriptor()
            descriptor.colorTextureFormat = colorTexture.pixelFormat
            descriptor.outputTextureFormat = outputTexture.pixelFormat
            descriptor.inputWidth = colorTexture.width
            descriptor.inputHeight = colorTexture.height
            descriptor.outputWidth = outputTexture.width
            descriptor.outputHeight = outputTexture.height
            descriptor.colorProcessingMode = .perceptual
            guard let created = descriptor.makeSpatialScaler(device: device, compiler: compiler) else {
                logMetalFxFailureOnce("spatial-metal4-create", "Metal 4 spatial scaler creation failed")
                return 0
            }
            scaler = created
            NativeState.metalFxScalers[key] = created as AnyObject
        }
        scaler.colorTexture = colorTexture
        scaler.outputTexture = outputTexture
        scaler.inputContentWidth = Int(inputWidth)
        scaler.inputContentHeight = Int(inputHeight)
        scaler.fence = fence
        lease.commandBuffer.pushDebugGroup("MetalFX Spatial Upscale (Metal 4)")
        scaler.encode(commandBuffer: lease.commandBuffer)
        lease.commandBuffer.popDebugGroup()
        MetalFxNativeHudMetrics.updateScaling(
            mode: "Spatial",
            inputWidth: Int(inputWidth),
            inputHeight: Int(inputHeight),
            targetWidth: outputTexture.width,
            targetHeight: outputTexture.height,
            exposure: 1.0
        )
        NativeState.metal4SpatialEncodeCount &+= 1
        return 1
    }
    #endif
    return metal3MetalFxEncode(
        metal3CommandBuffer(commandBufferPointer), device, colorTexture, depthTexture, motionTexture,
        reactiveTexture, outputTexture, currentViewProjection, inverseCurrentViewProjection,
        previousViewProjection, fence, jitterX, jitterY, inputWidth, inputHeight,
        reset, depthReversed, preserveReactiveMask
    )
}

/// Versioned temporal entry point. It keeps the legacy camera-only symbol
/// intact while making the producer/merge boundary explicit: camera motion is
/// reconstructed separately, valid object motion overrides it, and
/// disocclusion/invalid data forces reactive history rejection.
#if os(macOS) && canImport(MetalFX)
@available(macOS 26.0, iOS 26.0, *)
private func metal4MetalFxEncodeV2(
    lease: Metal4MainCommandBufferLease, device: MTLDevice,
    colorTexture: MTLTexture, depthTexture: MTLTexture, handDepthTexture: MTLTexture?,
    cameraMotionTexture: MTLTexture, objectMotionTexture: MTLTexture,
    objectValidityTexture: MTLTexture, disocclusionTexture: MTLTexture,
    motionTexture: MTLTexture, reactiveTexture: MTLTexture, outputTexture: MTLTexture,
    currentViewProjection: UnsafePointer<Float>?,
    inverseCurrentViewProjection: UnsafePointer<Float>?,
    previousViewProjection: UnsafePointer<Float>?, fence: MTLFence?,
    jitterX: Float, jitterY: Float, handReactiveBoost: Float,
    inputWidth: Int32, inputHeight: Int32, reset: Int32, depthReversed: Int32,
    preserveReactiveMask: Int32, emitMotionDiagnostics: Int32
) -> Int32 {
    guard inputWidth > 0, inputHeight > 0,
          colorTexture.width == Int(inputWidth), colorTexture.height == Int(inputHeight),
          depthTexture.width == Int(inputWidth), depthTexture.height == Int(inputHeight),
          handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
              && handDepthTexture?.height == Int(inputHeight)),
          cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
          objectMotionTexture.width == Int(inputWidth), objectMotionTexture.height == Int(inputHeight),
          objectValidityTexture.width == Int(inputWidth), objectValidityTexture.height == Int(inputHeight),
          disocclusionTexture.width == Int(inputWidth), disocclusionTexture.height == Int(inputHeight),
          motionTexture.width == Int(inputWidth), motionTexture.height == Int(inputHeight),
          let currentViewProjection, let inverseCurrentViewProjection, let previousViewProjection,
          let pipelines = ensureMotionV2Pipelines(device),
          let compiler = NativeState.metal4Compiler(device) else {
        logMetalFxFailureOnce("temporal-v2-metal4-resources", "invalid resources, matrices, pipelines, or compiler")
        return 0
    }

    let baseKey = metalFxScalerKey(device, true, colorTexture, outputTexture)
    let key = "m4-temporal-" + baseKey
    let previousDepthTexture: MTLTexture
    let previousDepthIsValid: Bool
    NativeState.metalFxHistoryLock.lock()
    if let cached = NativeState.metalFxPreviousDepthTextures[key],
       cached.width == depthTexture.width, cached.height == depthTexture.height,
       cached.pixelFormat == depthTexture.pixelFormat {
        previousDepthTexture = cached
    } else {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: depthTexture.pixelFormat,
            width: depthTexture.width,
            height: depthTexture.height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = [.shaderRead]
        guard let created = device.makeTexture(descriptor: descriptor) else {
            NativeState.metalFxHistoryLock.unlock()
            return 0
        }
        created.label = "MetalFX Previous Depth (Metal 4)"
        residencyTrackCreated(created)
        NativeState.metalFxPreviousDepthTextures[key] = created
        NativeState.metalFxPreviousDepthValid.remove(key)
        previousDepthTexture = created
    }
    if reset != 0 { NativeState.metalFxPreviousDepthValid.remove(key) }
    previousDepthIsValid = NativeState.metalFxPreviousDepthValid.contains(key)
    NativeState.metalFxHistoryLock.unlock()

    let scaler: any MTL4FXTemporalScaler
    if let cached = NativeState.metalFxScalers[key] as? any MTL4FXTemporalScaler {
        scaler = cached
    } else {
        let descriptor = MTLFXTemporalScalerDescriptor()
        descriptor.colorTextureFormat = colorTexture.pixelFormat
        descriptor.depthTextureFormat = depthTexture.pixelFormat
        descriptor.motionTextureFormat = motionTexture.pixelFormat
        descriptor.outputTextureFormat = outputTexture.pixelFormat
        descriptor.inputWidth = colorTexture.width
        descriptor.inputHeight = colorTexture.height
        descriptor.outputWidth = outputTexture.width
        descriptor.outputHeight = outputTexture.height
        descriptor.isAutoExposureEnabled = false
        descriptor.requiresSynchronousInitialization = true
        if #available(macOS 14.4, *) {
            descriptor.isReactiveMaskTextureEnabled = true
            descriptor.reactiveMaskTextureFormat = reactiveTexture.pixelFormat
        }
        guard let created = descriptor.makeTemporalScaler(device: device, compiler: compiler) else {
            logMetalFxFailureOnce("temporal-v2-metal4-create", "Metal 4 temporal scaler creation failed")
            return 0
        }
        scaler = created
        NativeState.metalFxScalers[key] = created as AnyObject
    }
    NativeState.lastTemporalScalerForInterpolation = scaler as AnyObject

    let currentMatrix = makeMatrix(currentViewProjection)
    let inverseMatrix = makeMatrix(inverseCurrentViewProjection)
    let previousMatrix = makeMatrix(previousViewProjection)
    var validationReactiveSnapshot: MTLTexture?
    if emitMotionDiagnostics != 0 && NativeState.reactiveValidationSnapshotEnabled {
        NativeState.metalFxHistoryLock.lock()
        if let cached = NativeState.metalFxValidationReactiveTextures[key],
           cached.width == reactiveTexture.width, cached.height == reactiveTexture.height,
           cached.pixelFormat == reactiveTexture.pixelFormat {
            validationReactiveSnapshot = cached
        } else {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: reactiveTexture.pixelFormat,
                width: reactiveTexture.width,
                height: reactiveTexture.height,
                mipmapped: false
            )
            descriptor.storageMode = .private
            descriptor.usage = [.shaderRead, .shaderWrite]
            if let created = device.makeTexture(descriptor: descriptor) {
                created.label = "MetalFX Pre-Motion Reactive Validation Snapshot"
                residencyTrackCreated(created)
                NativeState.metalFxValidationReactiveTextures[key] = created
                validationReactiveSnapshot = created
            }
        }
        NativeState.metalFxHistoryLock.unlock()
        guard let validationReactiveSnapshot,
              let snapshotCopy = lease.commandBuffer.makeComputeCommandEncoder() else { return 0 }
        snapshotCopy.label = "MetalFX Pre-Motion Reactive Validation Snapshot"
        snapshotCopy.barrier(
            afterQueueStages: .dispatch,
            beforeStages: .blit,
            visibilityOptions: .device
        )
        snapshotCopy.copy(sourceTexture: reactiveTexture, destinationTexture: validationReactiveSnapshot)
        snapshotCopy.endEncoding()
    }
    if NativeState.legacyMotionPasses {
        var cameraUniforms = MotionUniforms(
            currentViewProjection: currentMatrix,
            inverseCurrentViewProjection: inverseMatrix,
            previousViewProjection: previousMatrix,
            viewport: SIMD4(Float(inputWidth), Float(inputHeight),
                            1 / Float(inputWidth), 1 / Float(inputHeight)),
            flags: SIMD4(preserveReactiveMask != 0 ? 1 : 0,
                         NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0, 0, 0),
            params: SIMD4(NativeState.reactiveTuning.z, 0, 0, 0)
        )
        guard encodeMetal4Compute(
            lease: lease, label: "MetalFX Camera Motion Reconstruction (Metal 4)",
            pipeline: pipelines.camera, uniforms: cameraUniforms,
            textures: [(0, depthTexture), (1, cameraMotionTexture),
                       (2, disocclusionTexture), (3, reactiveTexture)],
            width: Int(inputWidth), height: Int(inputHeight)
        ) else { return 0 }
        struct MergeUniforms {
            var viewport: SIMD4<UInt32>
            var flags: SIMD4<UInt32>
            var params: SIMD4<Float>
        }
        let mergeUniforms = MergeUniforms(
            viewport: SIMD4(UInt32(inputWidth), UInt32(inputHeight),
                            previousDepthIsValid ? 1 : 0, depthReversed != 0 ? 1 : 0),
            flags: SIMD4(NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0,
                         NativeState.mergeDepthDilation > 0.5 ? 1 : 0, 0, 0),
            params: SIMD4(NativeState.disocclusionReactiveCap, 0, 0, 0)
        )
        guard encodeMetal4Compute(
            lease: lease, label: "MetalFX Object and Camera Motion Merge (Metal 4)",
            pipeline: pipelines.merge, uniforms: mergeUniforms,
            textures: [(0, cameraMotionTexture), (1, objectMotionTexture),
                       (2, objectValidityTexture), (3, disocclusionTexture),
                       (4, motionTexture), (5, reactiveTexture),
                       (6, previousDepthTexture), (7, depthTexture)],
            width: Int(inputWidth), height: Int(inputHeight),
            afterStages: .dispatch,
            producerBarrierBeforeStages: [.vertex, .fragment, .dispatch, .blit]
        ) else { return 0 }
    } else {
        struct FusedMotionUniforms {
            var currentViewProjection: simd_float4x4
            var inverseCurrentViewProjection: simd_float4x4
            var previousViewProjection: simd_float4x4
            var viewport: SIMD4<Float>
            var flags: SIMD4<UInt32>
            var options: SIMD4<UInt32>
            var params: SIMD4<Float>
        }
        let uniforms = FusedMotionUniforms(
            currentViewProjection: currentMatrix,
            inverseCurrentViewProjection: inverseMatrix,
            previousViewProjection: previousMatrix,
            viewport: SIMD4(Float(inputWidth), Float(inputHeight), 0, 0),
            flags: SIMD4(preserveReactiveMask != 0 ? 1 : 0,
                         NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0,
                         previousDepthIsValid ? 1 : 0, depthReversed != 0 ? 1 : 0),
            options: SIMD4(NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                           emitMotionDiagnostics != 0 ? 1 : 0,
                           handDepthTexture != nil ? 1 : 0, 0),
            params: SIMD4(NativeState.reactiveTuning.z,
                          NativeState.disocclusionReactiveCap, handReactiveBoost, 0)
        )
        guard encodeMetal4Compute(
            lease: lease, label: "MetalFX Fused Camera and Object Motion (Metal 4)",
            pipeline: pipelines.fused, uniforms: uniforms,
            textures: [(0, depthTexture), (1, objectMotionTexture),
                       (2, objectValidityTexture), (3, previousDepthTexture),
                       (4, motionTexture), (5, reactiveTexture),
                       (6, cameraMotionTexture), (7, disocclusionTexture),
                       (8, handDepthTexture)],
            width: Int(inputWidth), height: Int(inputHeight),
            producerBarrierBeforeStages: [.vertex, .fragment, .dispatch, .blit]
        ) else { return 0 }
    }

    scaler.colorTexture = colorTexture
    scaler.depthTexture = depthTexture
    scaler.motionTexture = motionTexture
    scaler.outputTexture = outputTexture
    scaler.inputContentWidth = Int(inputWidth)
    scaler.inputContentHeight = Int(inputHeight)
    scaler.jitterOffsetX = jitterX
    scaler.jitterOffsetY = jitterY
    scaler.motionVectorScaleX = Float(inputWidth) * 0.5
    scaler.motionVectorScaleY = Float(inputHeight) * 0.5
    scaler.reset = reset != 0
    scaler.isDepthReversed = depthReversed != 0
    if #available(macOS 14.4, *) { scaler.reactiveMaskTexture = reactiveTexture }
    scaler.fence = fence
    lease.commandBuffer.pushDebugGroup("MetalFX Temporal Upscale V2 (Metal 4)")
    scaler.encode(commandBuffer: lease.commandBuffer)
    lease.commandBuffer.popDebugGroup()
    MetalFxNativeHudMetrics.updateScaling(
        mode: "Temporal",
        inputWidth: Int(inputWidth),
        inputHeight: Int(inputHeight),
        targetWidth: outputTexture.width,
        targetHeight: outputTexture.height,
        exposure: 1.0
    )

    if let validationReactiveSnapshot {
        guard let snapshotRestore = lease.commandBuffer.makeComputeCommandEncoder() else { return 0 }
        snapshotRestore.label = "MetalFX Pre-Motion Reactive Validation Restore"
        snapshotRestore.barrier(
            afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
            beforeStages: .blit,
            visibilityOptions: .device
        )
        snapshotRestore.copy(sourceTexture: validationReactiveSnapshot, destinationTexture: reactiveTexture)
        snapshotRestore.endEncoding()
    }

    guard let historyCopy = lease.commandBuffer.makeComputeCommandEncoder() else { return 0 }
    historyCopy.label = "MetalFX Previous Depth Update (Metal 4)"
    historyCopy.barrier(
        afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
        beforeStages: .blit,
        visibilityOptions: .device
    )
    historyCopy.copy(sourceTexture: depthTexture, destinationTexture: previousDepthTexture)
    historyCopy.endEncoding()
    lease.addCompletionHandler { error, _, _ in
        NativeState.metalFxHistoryLock.lock()
        if error == nil {
            NativeState.metalFxPreviousDepthValid.insert(key)
        } else {
            NativeState.metalFxPreviousDepthValid.remove(key)
        }
        NativeState.metalFxHistoryLock.unlock()
    }
    NativeState.metal4TemporalEncodeCount &+= 1
    return 1
}
#endif

private func metal3MetalFxEncodeV2(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ colorTexture: MTLTexture,
    _ depthTexture: MTLTexture,
    _ handDepthTexture: MTLTexture?,
    _ cameraMotionTexture: MTLTexture,
    _ objectMotionTexture: MTLTexture,
    _ objectValidityTexture: MTLTexture,
    _ disocclusionTexture: MTLTexture,
    _ motionTexture: MTLTexture,
    _ reactiveTexture: MTLTexture,
    _ outputTexture: MTLTexture,
    _ currentViewProjection: UnsafePointer<Float>?,
    _ inverseCurrentViewProjection: UnsafePointer<Float>?,
    _ previousViewProjection: UnsafePointer<Float>?,
    _ fence: MTLFence?,
    _ jitterX: Float,
    _ jitterY: Float,
    _ handReactiveBoost: Float,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reset: Int32,
    _ depthReversed: Int32,
    _ preserveReactiveMask: Int32,
    _ emitMotionDiagnostics: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  colorTexture.width == Int(inputWidth), colorTexture.height == Int(inputHeight),
                  depthTexture.width == Int(inputWidth), depthTexture.height == Int(inputHeight),
                  handDepthTexture == nil || (handDepthTexture?.width == Int(inputWidth)
                      && handDepthTexture?.height == Int(inputHeight)),
                  cameraMotionTexture.width == Int(inputWidth), cameraMotionTexture.height == Int(inputHeight),
                  objectMotionTexture.width == Int(inputWidth), objectMotionTexture.height == Int(inputHeight),
                  objectValidityTexture.width == Int(inputWidth), objectValidityTexture.height == Int(inputHeight),
                  disocclusionTexture.width == Int(inputWidth), disocclusionTexture.height == Int(inputHeight),
                  motionTexture.width == Int(inputWidth), motionTexture.height == Int(inputHeight),
                  let currentViewProjection,
                  let inverseCurrentViewProjection,
                  let previousViewProjection,
                  let pipelines = ensureMotionV2Pipelines(device) else {
                logMetalFxFailureOnce("motion-v2-resources", "invalid v2 motion dimensions, matrices, or compute pipeline")
                return 0
            }

            let key = metalFxScalerKey(device, true, colorTexture, outputTexture)
            let previousDepthTexture: MTLTexture
            let previousDepthIsValid: Bool
            NativeState.metalFxHistoryLock.lock()
            if let cachedDepth = NativeState.metalFxPreviousDepthTextures[key],
               cachedDepth.width == depthTexture.width,
               cachedDepth.height == depthTexture.height,
               cachedDepth.pixelFormat == depthTexture.pixelFormat {
                previousDepthTexture = cachedDepth
            } else {
                let previousDepthDescriptor = MTLTextureDescriptor.texture2DDescriptor(
                    pixelFormat: depthTexture.pixelFormat,
                    width: depthTexture.width,
                    height: depthTexture.height,
                    mipmapped: false
                )
                previousDepthDescriptor.storageMode = .private
                previousDepthDescriptor.usage = [.shaderRead]
                guard let createdDepth = device.makeTexture(descriptor: previousDepthDescriptor) else {
                    NativeState.metalFxHistoryLock.unlock()
                    logMetalFxFailureOnce("motion-v2-previous-depth", "could not allocate previous depth history")
                    return 0
                }
                createdDepth.label = "MetalFX Previous Depth"
                NativeState.metalFxPreviousDepthTextures[key] = createdDepth
                NativeState.metalFxPreviousDepthValid.remove(key)
                previousDepthTexture = createdDepth
            }
            if reset != 0 {
                NativeState.metalFxPreviousDepthValid.remove(key)
            }
            previousDepthIsValid = NativeState.metalFxPreviousDepthValid.contains(key)
            NativeState.metalFxHistoryLock.unlock()

            let scalerObject: AnyObject?
            if let cached = NativeState.metalFxScalers[key] {
                scalerObject = cached
            } else {
                let descriptor = MTLFXTemporalScalerDescriptor()
                descriptor.colorTextureFormat = colorTexture.pixelFormat
                descriptor.depthTextureFormat = depthTexture.pixelFormat
                descriptor.motionTextureFormat = motionTexture.pixelFormat
                descriptor.outputTextureFormat = outputTexture.pixelFormat
                descriptor.inputWidth = colorTexture.width
                descriptor.inputHeight = colorTexture.height
                descriptor.outputWidth = outputTexture.width
                descriptor.outputHeight = outputTexture.height
                descriptor.isAutoExposureEnabled = false
                descriptor.requiresSynchronousInitialization = true
                if #available(macOS 14.4, *) {
                    descriptor.isReactiveMaskTextureEnabled = true
                    descriptor.reactiveMaskTextureFormat = reactiveTexture.pixelFormat
                }
                guard let scaler = descriptor.makeTemporalScaler(device: device) else {
                    logMetalFxFailureOnce(
                        "temporal-v2-create",
                        "descriptor rejected v2 color=\(colorTexture.pixelFormat.rawValue) depth=\(depthTexture.pixelFormat.rawValue) motion=\(motionTexture.pixelFormat.rawValue) output=\(outputTexture.pixelFormat.rawValue)"
                    )
                    return 0
                }
                scalerObject = scaler as AnyObject
                NativeState.metalFxScalers[key] = scaler as AnyObject
            }

            guard let scaler = scalerObject as? any MTLFXTemporalScaler else {
                logMetalFxFailureOnce("temporal-v2-cast", "cached temporal scaler unavailable")
                return 0
            }
            NativeState.lastTemporalScalerForInterpolation = scalerObject
            struct MergeUniforms {
                var viewport: SIMD4<UInt32>
                var flags: SIMD4<UInt32>
                var params: SIMD4<Float>
            }
            let currentMatrix = makeMatrix(currentViewProjection)
            let inverseMatrix = makeMatrix(inverseCurrentViewProjection)
            let previousMatrix = makeMatrix(previousViewProjection)
            let mergeUniforms = MergeUniforms(
                viewport: SIMD4<UInt32>(
                    UInt32(inputWidth),
                    UInt32(inputHeight),
                    previousDepthIsValid ? 1 : 0,
                    depthReversed != 0 ? 1 : 0
                ),
                flags: SIMD4<UInt32>(
                    NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0,
                    NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                    0,
                    0
                ),
                params: SIMD4<Float>(NativeState.disocclusionReactiveCap, 0.0, 0.0, 0.0)
            )
            if NativeState.legacyMotionPasses {
                guard let cameraEncoder = commandBuffer.makeComputeCommandEncoder() else {
                    logMetalFxFailureOnce("motion-v2-camera-encoder", "could not create v2 camera compute encoder")
                    return 0
                }
                cameraEncoder.label = "MetalFX Camera Motion Reconstruction"
                metal4BarrierComputeAfterRender(cameraEncoder)
                if let fence {
                    cameraEncoder.waitForFence(fence)
                }
                var motionUniforms = MotionUniforms(
                    currentViewProjection: currentMatrix,
                    inverseCurrentViewProjection: inverseMatrix,
                    previousViewProjection: previousMatrix,
                    viewport: SIMD4<Float>(
                        Float(inputWidth), Float(inputHeight),
                        1.0 / Float(max(inputWidth, 1)), 1.0 / Float(max(inputHeight, 1))
                    ),
                    flags: SIMD4<UInt32>(
                        preserveReactiveMask != 0 ? 1 : 0,
                        NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0,
                        0,
                        0
                    ),
                    params: SIMD4<Float>(NativeState.reactiveTuning.z, 0.0, 0.0, 0.0)
                )
                cameraEncoder.setComputePipelineState(pipelines.camera)
                cameraEncoder.setBytes(&motionUniforms, length: MemoryLayout<MotionUniforms>.stride, index: 0)
                cameraEncoder.setTexture(depthTexture, index: 0)
                cameraEncoder.setTexture(cameraMotionTexture, index: 1)
                cameraEncoder.setTexture(disocclusionTexture, index: 2)
                cameraEncoder.setTexture(reactiveTexture, index: 3)
                let cameraWidth = max(1, min(pipelines.camera.threadExecutionWidth, 64))
                let cameraHeight = max(1, min(8, pipelines.camera.maxTotalThreadsPerThreadgroup / cameraWidth))
                cameraEncoder.dispatchThreads(
                    MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                    threadsPerThreadgroup: MTLSize(width: cameraWidth, height: cameraHeight, depth: 1)
                )
                if let fence {
                    cameraEncoder.updateFence(fence)
                }
                cameraEncoder.endEncoding()

                guard let mergeEncoder = commandBuffer.makeComputeCommandEncoder() else {
                    logMetalFxFailureOnce("motion-v2-merge-encoder", "could not create v2 merge compute encoder")
                    return 0
                }
                mergeEncoder.label = "MetalFX Object and Camera Motion Merge"
                metal4BarrierComputeAfterCompute(mergeEncoder)
                if let fence {
                    mergeEncoder.waitForFence(fence)
                }
                var mutableMergeUniforms = mergeUniforms
                mergeEncoder.setComputePipelineState(pipelines.merge)
                mergeEncoder.setBytes(
                    &mutableMergeUniforms,
                    length: MemoryLayout<MergeUniforms>.stride,
                    index: 0
                )
                mergeEncoder.setTexture(cameraMotionTexture, index: 0)
                mergeEncoder.setTexture(objectMotionTexture, index: 1)
                mergeEncoder.setTexture(objectValidityTexture, index: 2)
                mergeEncoder.setTexture(disocclusionTexture, index: 3)
                mergeEncoder.setTexture(motionTexture, index: 4)
                mergeEncoder.setTexture(reactiveTexture, index: 5)
                mergeEncoder.setTexture(previousDepthTexture, index: 6)
                mergeEncoder.setTexture(depthTexture, index: 7)
                let mergeWidth = max(1, min(pipelines.merge.threadExecutionWidth, 64))
                let mergeHeight = max(1, min(8, pipelines.merge.maxTotalThreadsPerThreadgroup / mergeWidth))
                mergeEncoder.dispatchThreads(
                    MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                    threadsPerThreadgroup: MTLSize(width: mergeWidth, height: mergeHeight, depth: 1)
                )
                if let fence {
                    mergeEncoder.updateFence(fence)
                }
                mergeEncoder.endEncoding()
            } else {
                guard let fusedEncoder = commandBuffer.makeComputeCommandEncoder() else {
                    logMetalFxFailureOnce("motion-v2-fused-encoder", "could not create fused v2 motion encoder")
                    return 0
                }
                fusedEncoder.label = "MetalFX Fused Camera and Object Motion"
                metal4BarrierComputeAfterRender(fusedEncoder)
                if let fence {
                    fusedEncoder.waitForFence(fence)
                }
                struct FusedMotionUniforms {
                    var currentViewProjection: simd_float4x4
                    var inverseCurrentViewProjection: simd_float4x4
                    var previousViewProjection: simd_float4x4
                    var viewport: SIMD4<Float>
                    var flags: SIMD4<UInt32>
                    var options: SIMD4<UInt32>
                    var params: SIMD4<Float>
                }
                var fusedUniforms = FusedMotionUniforms(
                    currentViewProjection: currentMatrix,
                    inverseCurrentViewProjection: inverseMatrix,
                    previousViewProjection: previousMatrix,
                    viewport: SIMD4<Float>(Float(inputWidth), Float(inputHeight), 0.0, 0.0),
                    flags: SIMD4<UInt32>(
                        preserveReactiveMask != 0 ? 1 : 0,
                        NativeState.skyFarPlaneMotion > 0.5 ? 1 : 0,
                        previousDepthIsValid ? 1 : 0,
                        depthReversed != 0 ? 1 : 0
                    ),
                    options: SIMD4<UInt32>(
                        NativeState.mergeDepthDilation > 0.5 ? 1 : 0,
                        emitMotionDiagnostics != 0 ? 1 : 0,
                        handDepthTexture != nil ? 1 : 0,
                        0
                    ),
                    params: SIMD4<Float>(
                        NativeState.reactiveTuning.z,
                        NativeState.disocclusionReactiveCap,
                        handReactiveBoost,
                        0.0
                    )
                )
                fusedEncoder.setComputePipelineState(pipelines.fused)
                fusedEncoder.setBytes(
                    &fusedUniforms,
                    length: MemoryLayout<FusedMotionUniforms>.stride,
                    index: 0
                )
                fusedEncoder.setTexture(depthTexture, index: 0)
                fusedEncoder.setTexture(objectMotionTexture, index: 1)
                fusedEncoder.setTexture(objectValidityTexture, index: 2)
                fusedEncoder.setTexture(previousDepthTexture, index: 3)
                fusedEncoder.setTexture(motionTexture, index: 4)
                fusedEncoder.setTexture(reactiveTexture, index: 5)
                fusedEncoder.setTexture(cameraMotionTexture, index: 6)
                fusedEncoder.setTexture(disocclusionTexture, index: 7)
                fusedEncoder.setTexture(handDepthTexture, index: 8)
                let fusedWidth = max(1, min(pipelines.fused.threadExecutionWidth, 64))
                let fusedHeight = max(1, min(8, pipelines.fused.maxTotalThreadsPerThreadgroup / fusedWidth))
                fusedEncoder.dispatchThreads(
                    MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                    threadsPerThreadgroup: MTLSize(width: fusedWidth, height: fusedHeight, depth: 1)
                )
                if let fence {
                    fusedEncoder.updateFence(fence)
                }
                fusedEncoder.endEncoding()
            }

            scaler.colorTexture = colorTexture
            scaler.depthTexture = depthTexture
            scaler.motionTexture = motionTexture
            scaler.outputTexture = outputTexture
            scaler.inputContentWidth = Int(inputWidth)
            scaler.inputContentHeight = Int(inputHeight)
            scaler.jitterOffsetX = jitterX
            scaler.jitterOffsetY = jitterY
            scaler.motionVectorScaleX = Float(inputWidth) * 0.5
            scaler.motionVectorScaleY = Float(inputHeight) * 0.5
            scaler.reset = reset != 0
            scaler.isDepthReversed = depthReversed != 0
            if #available(macOS 14.4, *) {
                scaler.reactiveMaskTexture = reactiveTexture
            }
            scaler.fence = fence
            commandBuffer.pushDebugGroup("MetalFX Temporal Upscale V2")
            scaler.encode(commandBuffer: commandBuffer)
            commandBuffer.popDebugGroup()

            guard let historyBlit = commandBuffer.makeBlitCommandEncoder() else {
                logMetalFxFailureOnce("motion-v2-history-copy", "could not create previous-depth history blit")
                return 0
            }
            historyBlit.label = "MetalFX Previous Depth Update"
            // E10: this encoder has no fence at all under Metal 3 (barrier map section 0).
            metal4BarrierBlitAfterRender(historyBlit)
            historyBlit.copy(
                from: depthTexture,
                sourceSlice: 0,
                sourceLevel: 0,
                to: previousDepthTexture,
                destinationSlice: 0,
                destinationLevel: 0,
                sliceCount: 1,
                levelCount: 1
            )
            historyBlit.endEncoding()
            commandBuffer.addCompletedHandler { completed in
                NativeState.metalFxHistoryLock.lock()
                if completed.status == .completed {
                    NativeState.metalFxPreviousDepthValid.insert(key)
                } else {
                    NativeState.metalFxPreviousDepthValid.remove(key)
                }
                NativeState.metalFxHistoryLock.unlock()
            }
            return 1
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_encode_v2(
    _ commandBuffer: MTLCommandBuffer, _ device: MTLDevice,
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
    metallumMetalFxEncodeV2Entry(
        commandBufferPointer(commandBuffer), device, colorTexture, depthTexture, handDepthTexture,
        cameraMotionTexture, objectMotionTexture, objectValidityTexture, disocclusionTexture,
        motionTexture, reactiveTexture, outputTexture, currentViewProjection,
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
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer) {
        return metal4MetalFxEncodeV2(
            lease: lease, device: device, colorTexture: colorTexture, depthTexture: depthTexture,
            handDepthTexture: handDepthTexture, cameraMotionTexture: cameraMotionTexture,
            objectMotionTexture: objectMotionTexture, objectValidityTexture: objectValidityTexture,
            disocclusionTexture: disocclusionTexture, motionTexture: motionTexture,
            reactiveTexture: reactiveTexture, outputTexture: outputTexture,
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
        cameraMotionTexture, objectMotionTexture, objectValidityTexture, disocclusionTexture,
        motionTexture, reactiveTexture, outputTexture, currentViewProjection,
        inverseCurrentViewProjection, previousViewProjection, fence, jitterX, jitterY,
        handReactiveBoost, inputWidth, inputHeight, reset, depthReversed,
        preserveReactiveMask, emitMotionDiagnostics
    )
}

@_cdecl("metallum_metalfx_frame_generation_encode")
public func metallumMetalFxFrameGenerationEncodeEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ device: MTLDevice,
    _ layer: CAMetalLayer,
    _ sceneColor: MTLTexture,
    _ nativeSceneColor: MTLTexture,
    _ uiColor: MTLTexture,
    _ depthTexture: MTLTexture,
    _ motionTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ jitterX: Float,
    _ jitterY: Float,
    _ fieldOfView: Float,
    _ nearPlane: Float,
    _ farPlane: Float,
    _ aspectRatio: Float,
    _ sourceDeltaSeconds: Float,
    _ reset: Int32,
    _ globalFence: MTLFence?
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        return autoreleasepool {
            let presenter: MetalFrameGenerationPresenter
            if let existing = NativeState.frameGenerationPresenter {
                presenter = existing
            } else {
                guard let created = MetalFrameGenerationPresenter(
                    device: device,
                    layer: layer,
                    sceneColor: sceneColor,
                    nativeSceneColor: nativeSceneColor,
                    uiColor: uiColor,
                    depth: depthTexture,
                    motion: motionTexture,
                    inputWidth: Int(inputWidth),
                    inputHeight: Int(inputHeight)
                ) else {
                    logMetalFxFailureOnce(
                        "frame-generation-create",
                        "could not create the macOS 26 MetalFX frame interpolator or present thread"
                    )
                    return 0
                }
                NativeState.frameGenerationPresenter = created
                presenter = created
            }

            if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
                lease.commandBuffer.pushDebugGroup("MetalFX Frame Generation Inputs (Metal 4)")
            } else {
                metal3CommandBuffer(commandBufferPointer).pushDebugGroup("MetalFX Frame Generation Inputs")
            }
            let result = presenter.encode(
                commandBufferPointer: commandBufferPointer,
                sceneColor: sceneColor,
                nativeSceneColor: nativeSceneColor,
                uiColor: uiColor,
                depth: depthTexture,
                motion: motionTexture,
                inputWidth: Int(inputWidth),
                inputHeight: Int(inputHeight),
                jitterX: jitterX,
                jitterY: jitterY,
                fieldOfView: fieldOfView,
                nearPlane: nearPlane,
                farPlane: farPlane,
                aspectRatio: aspectRatio,
                sourceDeltaSeconds: sourceDeltaSeconds,
                reset: reset != 0,
                globalFence: globalFence
            )
            if #available(macOS 26.0, *), let lease = metal4MainLease(commandBufferPointer) {
                lease.commandBuffer.popDebugGroup()
            } else {
                metal3CommandBuffer(commandBufferPointer).popDebugGroup()
            }
            // Do not emit an NSLog for every rendered frame. Besides making
            // diagnostics unusable, that adds measurable CPU work to the
            // present path. Keep the first accepted frame and explicit reset
            // events observable instead.
            if result != 0 && (reset != 0 || !NativeState.frameGenerationLogged) {
                NSLog(
                    "[Metallum] MetalFX frame generation queued: input=%dx%d output=%dx%d reset=%@",
                    inputWidth,
                    inputHeight,
                    sceneColor.width,
                    sceneColor.height,
                    reset != 0 ? "YES" : "NO"
                )
                NativeState.frameGenerationLogged = true
            }
            return result
        }
    }
    #endif
    return 0
}

public func metallum_metalfx_frame_generation_encode(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ layer: CAMetalLayer,
    _ sceneColor: MTLTexture,
    _ nativeSceneColor: MTLTexture,
    _ uiColor: MTLTexture,
    _ depthTexture: MTLTexture,
    _ motionTexture: MTLTexture,
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ jitterX: Float,
    _ jitterY: Float,
    _ fieldOfView: Float,
    _ nearPlane: Float,
    _ farPlane: Float,
    _ aspectRatio: Float,
    _ sourceDeltaSeconds: Float,
    _ reset: Int32,
    _ globalFence: MTLFence?
) -> Int32 {
    metallumMetalFxFrameGenerationEncodeEntry(
        commandBufferPointer(commandBuffer), device, layer, sceneColor, nativeSceneColor,
        uiColor, depthTexture, motionTexture, inputWidth, inputHeight, jitterX, jitterY,
        fieldOfView, nearPlane, farPlane, aspectRatio, sourceDeltaSeconds, reset, globalFence
    )
}

/// Headless validation entry point for the actual MetalFX frame interpolator.
/// This deliberately accepts only textures and a command buffer: no
/// CAMetalLayer, CAMetalDrawable, display link, window, or screenshot path is
/// involved. The caller supplies the directly rendered previous/current
/// frames and owns GPU completion/readback.
@_cdecl("metallum_metalfx_frame_interpolator_encode_offscreen")
public func metallum_metalfx_frame_interpolator_encode_offscreen(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ currentColorTexture: MTLTexture,
    _ previousColorTexture: MTLTexture,
    _ uiTexture: MTLTexture,
    _ depthTexture: MTLTexture,
    _ motionTexture: MTLTexture,
    _ outputTexture: MTLTexture,
    _ jitterX: Float,
    _ jitterY: Float,
    _ fieldOfView: Float,
    _ nearPlane: Float,
    _ farPlane: Float,
    _ aspectRatio: Float,
    _ deltaTime: Float,
    _ uiComposited: Int32,
    _ reset: Int32,
    _ depthReversed: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        return autoreleasepool {
            guard currentColorTexture.width > 0,
                  currentColorTexture.height > 0,
                  currentColorTexture.width == previousColorTexture.width,
                  currentColorTexture.height == previousColorTexture.height,
                  currentColorTexture.pixelFormat == previousColorTexture.pixelFormat,
                  currentColorTexture.width == uiTexture.width,
                  currentColorTexture.height == uiTexture.height,
                  currentColorTexture.pixelFormat == uiTexture.pixelFormat,
                  currentColorTexture.width == outputTexture.width,
                  currentColorTexture.height == outputTexture.height,
                  currentColorTexture.pixelFormat == outputTexture.pixelFormat,
                  depthTexture.width == motionTexture.width,
                  depthTexture.height == motionTexture.height,
                  fieldOfView.isFinite,
                  nearPlane.isFinite,
                  farPlane.isFinite,
                  aspectRatio.isFinite,
                  deltaTime.isFinite,
                  fieldOfView > 0.0,
                  nearPlane > 0.0,
                  farPlane > nearPlane,
                  aspectRatio > 0.0,
                  deltaTime > 0.0 else {
                return 0
            }

            let descriptor = MTLFXFrameInterpolatorDescriptor()
            descriptor.colorTextureFormat = currentColorTexture.pixelFormat
            descriptor.outputTextureFormat = outputTexture.pixelFormat
            descriptor.depthTextureFormat = depthTexture.pixelFormat
            descriptor.motionTextureFormat = motionTexture.pixelFormat
            descriptor.uiTextureFormat = uiTexture.pixelFormat
            descriptor.inputWidth = depthTexture.width
            descriptor.inputHeight = depthTexture.height
            descriptor.outputWidth = outputTexture.width
            descriptor.outputHeight = outputTexture.height
            guard let interpolator = descriptor.makeFrameInterpolator(device: device) else {
                logMetalFxFailureOnce(
                    "frame-interpolator-offscreen-create",
                    "offscreen descriptor rejected color=\(currentColorTexture.pixelFormat.rawValue) depth=\(depthTexture.pixelFormat.rawValue) motion=\(motionTexture.pixelFormat.rawValue)"
                )
                return 0
            }

            interpolator.colorTexture = currentColorTexture
            interpolator.prevColorTexture = previousColorTexture
            interpolator.uiTexture = uiTexture
            interpolator.depthTexture = depthTexture
            interpolator.motionTexture = motionTexture
            interpolator.outputTexture = outputTexture
            interpolator.isUITextureComposited = uiComposited != 0
            interpolator.jitterOffsetX = jitterX
            interpolator.jitterOffsetY = jitterY
            interpolator.motionVectorScaleX = Float(motionTexture.width) * 0.5
            interpolator.motionVectorScaleY = Float(motionTexture.height) * 0.5
            interpolator.fieldOfView = fieldOfView
            interpolator.nearPlane = nearPlane
            interpolator.farPlane = farPlane
            interpolator.aspectRatio = aspectRatio
            interpolator.deltaTime = deltaTime
            interpolator.isDepthReversed = depthReversed != 0
            interpolator.shouldResetHistory = reset != 0
            commandBuffer.pushDebugGroup("MetalFX Frame Interpolator Offscreen")
            interpolator.encode(commandBuffer: commandBuffer)
            commandBuffer.popDebugGroup()
            return 1
        }
    }
    #endif
    return 0
}

private func metal3EncodeTextureCopy(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ linear: Int32,
    _ fence: MTLFence?
) -> Int32 {
    autoreleasepool {
        guard let pipeline = ensureCopyPipeline(commandBuffer.device, destinationTexture.pixelFormat) else {
            #if os(macOS) && canImport(MetalFX)
            logMetalFxFailureOnce("copy-pipeline", "could not create copy pipeline for output format \(destinationTexture.pixelFormat.rawValue)")
            #endif
            return 0
        }
        guard let sampler = linear != 0 ? NativeState.presentLinearSampler : NativeState.presentNearestSampler else {
            #if os(macOS) && canImport(MetalFX)
            logMetalFxFailureOnce("copy-sampler", "present sampler was not initialized")
            #endif
            return 0
        }
        let descriptor = MTLRenderPassDescriptor()
        descriptor.colorAttachments[0].texture = destinationTexture
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            #if os(macOS) && canImport(MetalFX)
            logMetalFxFailureOnce(
                "copy-encoder",
                "could not create render encoder source=\(sourceTexture.width)x\(sourceTexture.height)/\(sourceTexture.pixelFormat.rawValue) destination=\(destinationTexture.width)x\(destinationTexture.height)/\(destinationTexture.pixelFormat.rawValue)"
            )
            #endif
            return 0
        }
        metal4BarrierRenderAfterRender(encoder)
        if let fence {
            encoder.waitForFence(fence, before: .fragment)
        }
        encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: Double(destinationTexture.width), height: Double(destinationTexture.height), znear: 0.0, zfar: 1.0))
        encoder.setRenderPipelineState(pipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        if let fence {
            encoder.updateFence(fence, after: .fragment)
        }
        encoder.endEncoding()
        return 1
    }
}

public func metallum_encode_texture_copy(
    _ commandBuffer: MTLCommandBuffer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ linear: Int32,
    _ fence: MTLFence?
) -> Int32 {
    metallumEncodeTextureCopyEntry(
        commandBufferPointer(commandBuffer), sourceTexture, destinationTexture, linear, fence
    )
}

@_cdecl("metallum_encode_texture_copy")
public func metallumEncodeTextureCopyEntry(
    _ commandBufferPointer: UnsafeMutableRawPointer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ linear: Int32,
    _ fence: MTLFence?
) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *),
       let lease = metal4MainLease(commandBufferPointer) {
        guard let pipeline = ensureCopyPipeline(sourceTexture.device, destinationTexture.pixelFormat),
              let sampler = linear != 0 ? NativeState.presentLinearSampler : NativeState.presentNearestSampler else {
            return 0
        }
        let pass = MTL4RenderPassDescriptor()
        pass.colorAttachments[0].texture = destinationTexture
        pass.colorAttachments[0].loadAction = .dontCare
        pass.colorAttachments[0].storeAction = .store
        pass.renderTargetWidth = destinationTexture.width
        pass.renderTargetHeight = destinationTexture.height
        guard let encoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return 0 }
        encoder.label = "MetalFX Texture Copy (Metal 4)"
        encoder.barrier(
            afterQueueStages: [.vertex, .fragment, .dispatch, .blit],
            beforeStages: .fragment,
            visibilityOptions: .device
        )
        encoder.setViewport(MTLViewport(
            originX: 0, originY: 0,
            width: Double(destinationTexture.width), height: Double(destinationTexture.height),
            znear: 0, zfar: 1
        ))
        encoder.setRenderPipelineState(pipeline)
        let arguments = lease.owner.argumentTables(at: lease.slotIndex).1
        arguments.setTexture(sourceTexture.gpuResourceID, index: 0)
        arguments.setSamplerState(sampler.gpuResourceID, index: 0)
        encoder.setArgumentTable(arguments, stages: .fragment)
        encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        return 1
    }
    return metal3EncodeTextureCopy(
        metal3CommandBuffer(commandBufferPointer), sourceTexture, destinationTexture, linear, fence
    )
}

/// Releases every MetalFX object whose cache identity depends on the current
/// render/display dimensions.
///
/// `metalFxScalerKey` encodes both the input and the output size, so without
/// this each resize strands a fully initialized `MTLFXTemporalScaler` — plus
/// its previous-depth history texture — in the cache for the rest of the
/// session. Because the descriptors also set
/// `requiresSynchronousInitialization`, a drag-resize pays that initialization
/// on the render thread once per intermediate size and never reclaims any of
/// it. The compute pipelines and the frame-generation presenter are dimension
/// independent and deliberately survive.
@_cdecl("metallum_metalfx_release_scalers")
public func metallum_metalfx_release_scalers() {
    #if os(macOS) && canImport(MetalFX)
    NativeState.metalFxScalers.removeAll()
    // The presenter links this scaler into freshly built interpolators through
    // MTLFXFrameInterpolatorDescriptor.scaler, so a stale entry would be sized
    // for the previous surface. The next v2 encode republishes it before the
    // presenter rebuilds its interpolator.
    NativeState.lastTemporalScalerForInterpolation = nil
    NativeState.metalFxHistoryLock.lock()
    NativeState.metalFxPreviousDepthTextures.removeAll()
    NativeState.metalFxPreviousDepthValid.removeAll()
    NativeState.metalFxHistoryLock.unlock()
    #endif
}

@_cdecl("metallum_metalfx_shutdown")
public func metallum_metalfx_shutdown() {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        NativeState.frameGenerationPresenter?.shutdown()
    NativeState.frameGenerationPresenter = nil
    }
    metallum_metalfx_release_scalers()
    NativeState.motionPipeline = nil
    NativeState.motionV2Pipeline = nil
    NativeState.motionMergePipeline = nil
    NativeState.motionFusedPipeline = nil
    NativeState.motionClearPipeline = nil
    NativeState.transparencyMaskPipeline = nil
    NativeState.cutoutReactivePipeline = nil
    NativeState.frameGenerationLogged = false
    #endif
    NativeState.copyPipelines.removeAll()
    #if os(macOS)
    MetalFxNativeHudMetrics.resetMetalFx()
    #endif
}

/// Stops only the asynchronous frame-generation presenter. MetalFX temporal
/// and spatial scaler caches remain valid, so switching back to the ordinary
/// present path does not invalidate an already encoded upscaling command.
@_cdecl("metallum_metalfx_stop_frame_generation")
public func metallum_metalfx_stop_frame_generation() {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        NativeState.frameGenerationPresenter?.shutdown()
        NativeState.frameGenerationPresenter = nil
        NativeState.frameGenerationLogged = false
        MetalFxNativeHudMetrics.frameInterpolatorDisabled()
    }
    #endif
}

private func ensureDepthStencilState(device: MTLDevice, compareOp: MTLCompareFunction, writeDepth: Bool) -> MTLDepthStencilState? {
    let key = DepthStencilKey(deviceAddress: objectAddress(device), compareOp: compareOp, writeDepth: writeDepth)
    if let cached = NativeState.depthStencilStates[key] {
        return cached
    }
    let descriptor = MTLDepthStencilDescriptor()
    descriptor.depthCompareFunction = compareOp
    descriptor.isDepthWriteEnabled = writeDepth
    let state = device.makeDepthStencilState(descriptor: descriptor)
    if let state {
        NativeState.depthStencilStates[key] = state
    }
    return state
}

private func triangleFanOutputIndexCount(sourceCount: Int, buffer: MTLBuffer, offset: Int) -> Int? {
    let triangleCount = sourceCount - 2
    guard triangleCount <= Int.max / 3 else {
        return nil
    }

    let indexCount = triangleCount * 3
    let bufferIndexCapacity = UInt64((buffer.length - offset) / MemoryLayout<UInt32>.stride)
    guard indexCount <= UInt64(Int.max), indexCount <= bufferIndexCapacity else {
        return nil
    }
    return Int(indexCount)
}

private func readIndex(_ indexBuffer: MTLBuffer, byteOffset: Int, index: Int, indexType: Int) -> UInt32 {
    let base = indexBuffer.contents().advanced(by: Int(byteOffset))
    if indexType == 0 {
        return UInt32(base.assumingMemoryBound(to: UInt16.self)[Int(index)])
    }
    return base.assumingMemoryBound(to: UInt32.self)[Int(index)]
}

private func writeIndexedTriangleFanIndices(
    sourceIndexBuffer: MTLBuffer,
    destinationIndexBuffer: MTLBuffer,
    destinationOffset: Int,
    indexType: Int,
    indexOffsetBytes: Int,
    indexCount: Int
) -> Int? {
    guard indexCount >= 3, let generatedIndexCount = triangleFanOutputIndexCount(sourceCount: indexCount, buffer: destinationIndexBuffer, offset: destinationOffset) else {
        return nil
    }
    let triangleCount = indexCount - 2
    let center = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: 0, indexType: indexType)
    let indices = (destinationIndexBuffer.contents() + destinationOffset).assumingMemoryBound(to: UInt32.self)
    var writeIndex = 0
    for triangle in 0..<triangleCount {
        indices[writeIndex] = center
        indices[writeIndex + 1] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 1, indexType: indexType)
        indices[writeIndex + 2] = readIndex(sourceIndexBuffer, byteOffset: indexOffsetBytes, index: triangle + 2, indexType: indexType)
        writeIndex += 3
    }
    return generatedIndexCount
}

@_cdecl("metallum_create_system_default_device")
public func metallum_create_system_default_device() -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        #if os(macOS)
        // CAMetalLayer.developerHUDProperties can show and hide the HUD at
        // runtime only after Metal's HUD subsystem was enabled when the device
        // was created. A mod cannot add MetalHUDEnabled to the host launcher's
        // Info.plist, so prime the equivalent documented environment switch
        // before the first MTLDevice exists. Every layer starts hidden below.
        setenv("MTL_HUD_ENABLED", "1", 1)
        // MetalFX registers its Temporal and Frame Interpolator sections only
        // when this separate switch is present before the effects are built.
        setenv("MTLFX_HUD_ENABLED", "1", 1)
        #endif
        return retainedPointer(MTLCreateSystemDefaultDevice())
    }
}

#if os(iOS)
/// Locates the host launcher's game surface UIView on iOS without requiring
/// the host to publish a pointer via a system property.
///
/// Strategy 1: call `+[SurfaceViewController surface]` directly. This class
/// method just returns a static variable (`pojavWindow`) — it does NOT touch
/// UIKit, so it's safe to call from any thread (including the JVM render
/// thread) without dispatching to main. This is the preferred path because
/// the main thread may be blocked inside `launchJVM`, making
/// `DispatchQueue.main.sync` deadlock.
///
/// Strategy 2 (fallback): dispatch to the main thread with a timeout and
/// walk the view hierarchy for a `GameSurfaceView`. This handles launchers
/// that don't expose `+surface` but requires the main thread to be runnable.
@_cdecl("metallum_ios_find_surface_view")
public func metallum_ios_find_surface_view() -> UnsafeMutableRawPointer? {
    // Strategy 1: +[SurfaceViewController surface] — no UIKit, any thread.
    if let view = callSurfaceViewControllerSurface() {
        return view
    }

    // Strategy 2: dispatch to main with a timeout and walk the view hierarchy.
    // If the main thread is blocked (e.g. inside launchJVM), the timeout
    // fires and we return nil rather than deadlocking forever.
    if Thread.isMainThread {
        return findViewInHierarchy()
    }
    let semaphore = DispatchSemaphore(value: 0)
    var hierarchyResult: UnsafeMutableRawPointer? = nil
    DispatchQueue.main.async {
        hierarchyResult = findViewInHierarchy()
        semaphore.signal()
    }
    let timeout: DispatchTime = .now() + .seconds(3)
    if semaphore.wait(timeout: timeout) == .timedOut {
        NSLog("[Metallum] WARNING: main thread did not respond within 3s; view-hierarchy lookup skipped")
        return nil
    }
    return hierarchyResult
}

/// Calls `+[SurfaceViewController surface]` via the ObjC runtime. This method
/// only returns a static variable, so it's thread-safe without main-thread
/// dispatch.
private func callSurfaceViewControllerSurface() -> UnsafeMutableRawPointer? {
    guard let cls = NSClassFromString("SurfaceViewController") as? NSObject.Type else {
        NSLog("[Metallum] SurfaceViewController class not found")
        return nil
    }
    let sel = NSSelectorFromString("surface")
    if !cls.responds(to: sel) {
        NSLog("[Metallum] SurfaceViewController does not respond to 'surface'")
        return nil
    }
    guard let result = cls.perform(sel) else {
        NSLog("[Metallum] +[SurfaceViewController surface] returned nil")
        return nil
    }
    let view = result.takeUnretainedValue()
    NSLog("[Metallum] +[SurfaceViewController surface] returned \(view)")
    return Unmanaged.passUnretained(view as AnyObject).toOpaque()
}

private func findViewInHierarchy() -> UnsafeMutableRawPointer? {
    let gameSurfaceClass = objc_getClass("GameSurfaceView") as? NSObject.Type
    let windows = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .flatMap({ $0.windows })
    NSLog("[Metallum] view hierarchy walk: \(windows.count) window(s); GameSurfaceView class found: \(gameSurfaceClass != nil)")
    for window in windows {
        if let found = findViewInView(window, targetClass: gameSurfaceClass) {
            return found
        }
    }
    let keyWindow = windows.first(where: { $0.isKeyWindow }) ?? windows.first
    if let root = keyWindow?.rootViewController?.view {
        return findLargestSubview(root)
    }
    return nil
}

/// Recursively searches a view hierarchy for a view of the given class.
private func findViewInView(_ view: UIView, targetClass: NSObject.Type?) -> UnsafeMutableRawPointer? {
    if let targetClass = targetClass, view.isKind(of: targetClass) {
        return Unmanaged.passUnretained(view).toOpaque()
    }
    for sub in view.subviews {
        if let found = findViewInView(sub, targetClass: targetClass) {
            return found
        }
    }
    return nil
}

private func findLargestSubview(_ view: UIView) -> UnsafeMutableRawPointer {
    var largest = view
    var largestArea = view.bounds.width * view.bounds.height
    for sub in view.subviews {
        let area = sub.bounds.width * sub.bounds.height
        if area > largestArea {
            largestArea = area
            largest = sub
        }
    }
    if largest !== view && !largest.subviews.isEmpty {
        return findLargestSubview(largest)
    }
    return Unmanaged.passUnretained(largest).toOpaque()
}
#endif

@_cdecl("metallum_copy_device_name")
public func metallum_copy_device_name(
    _ device: MTLDevice,
    _ output: UnsafeMutablePointer<CChar>?,
    _ capacity: Int64
) -> Int32 {
    return autoreleasepool {
        guard let output, capacity > 0 else {
            return 1
        }
        let maxLength = Int(capacity - 1)
        let bytes = Array(device.name.utf8.prefix(maxLength))
        for i in 0..<bytes.count {
            output[i] = CChar(bitPattern: bytes[i])
        }
        output[bytes.count] = 0
        return 0
    }
}

@_cdecl("metallum_NSWindow_backingScaleFactor")
public func metallum_NSWindow_backingScaleFactor(_ window: MetallumWindow) -> Double {
    #if os(macOS)
    return Double(window.backingScaleFactor)
    #elseif os(iOS)
    // UIWindow on iOS does not expose backingScaleFactor directly; the
    // on-screen scale is determined by the window's UIScreen.
    return Double(window.screen.scale)
    #endif
}

private func setMetalHudProperties(_ layer: CAMetalLayer, enabled: Bool) {
    if #available(macOS 13.0, iOS 16.0, *) {
        layer.developerHUDProperties = enabled ? ["mode": "default"] : [:]
    }
}

#if os(macOS)
/// MetalFX's Metal 3 effects register these metrics themselves. The macOS 26
/// Metal 4 effects update no HUD state, so register the same system metric IDs
/// and feed them only from successful M4 encodes.
private final class MetalFxHudFrameEnd: NSObject {
    @objc dynamic let deltaTime: Double

    init(deltaTime: Double) {
        self.deltaTime = deltaTime
    }
}

private enum MetalFxNativeHudMetrics {
    private typealias AddMetricImplementation = @convention(c) (
        AnyObject, Selector, NSString, NSString, NSString,
        UInt32, UInt32, UInt32, UInt64
    ) -> Bool
    private typealias UpdateLabelMetricImplementation = @convention(c) (
        AnyObject, Selector, NSString, NSString
    ) -> Void
    private typealias FrameInterpolatorEndImplementation = @convention(c) (
        AnyObject, Selector, AnyObject
    ) -> Void
    private typealias NoArgumentImplementation = @convention(c) (
        AnyObject, Selector
    ) -> Void
    private typealias RemoveMetricImplementation = @convention(c) (
        AnyObject, Selector, NSString
    ) -> Void

    private static let lock = NSLock()
    private static let instanceSelector = NSSelectorFromString("instance")
    private static let addMetricSelector = NSSelectorFromString(
        "addMetric:name:unit:nameColor:valueColor:visualType:options:"
    )
    private static let updateLabelMetricSelector = NSSelectorFromString("updateLabelMetric:label:")
    private static let getMetricSelector = NSSelectorFromString("getMetric:")
    private static let removeMetricSelector = NSSelectorFromString("removeMetric:")
    private static let frameInterpolatorEndSelector = NSSelectorFromString(
        "metalFXFrameInterpolatorEncodingEnd:"
    )
    private static let frameInterpolatorDisableSelector = NSSelectorFromString(
        "metalFXFrameInterpolatorDisable"
    )
    private static let scalingMetrics: [(identifier: NSString, name: NSString)] = [
        ("com.apple.hud-label.metalfx.v2.scaling", "Scaling"),
        ("com.apple.hud-label.metalfx.v2.input_resolution", "Scaling Input Res"),
        ("com.apple.hud-label.metalfx.v2.target_resolution", "Scaling Target Res"),
        ("com.apple.hud-label.metalfx.v2.exposure", "Exposure")
    ]
    private static let interpolatorMetrics: [NSString] = [
        "com.apple.hud-label.metalfx.v2.interpolator",
        "com.apple.hud-label.metalfx.v2.interpolator.deltaTime"
    ]

    private static var enabled = false
    private static var scalingInstalled = false
    private static var interpolatorInstalled = false
    private static var loggedScaling = false
    private static var loggedInterpolator = false
    private static var properties: NSObject?

    static func setEnabled(_ newValue: Bool) {
        lock.lock()
        defer { lock.unlock() }
        enabled = newValue
        if !newValue {
            removeScalingLocked()
            disableFrameInterpolatorLocked()
            properties = nil
        }
    }

    static func updateScaling(
        mode: String,
        inputWidth: Int,
        inputHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        exposure: Float
    ) {
        lock.lock()
        defer { lock.unlock() }
        guard enabled,
              inputWidth > 0, inputHeight > 0,
              targetWidth > 0, targetHeight > 0,
              let hudProperties = resolvePropertiesLocked(),
              installScalingLocked(hudProperties),
              let updateMethod = class_getInstanceMethod(
                  type(of: hudProperties), updateLabelMetricSelector
              ) else {
            return
        }
        let updateLabelMetric = unsafeBitCast(
            method_getImplementation(updateMethod),
            to: UpdateLabelMetricImplementation.self
        )
        updateLabelMetric(
            hudProperties, updateLabelMetricSelector,
            scalingMetrics[0].identifier, mode as NSString
        )
        updateLabelMetric(
            hudProperties, updateLabelMetricSelector,
            scalingMetrics[1].identifier, "\(inputWidth)x\(inputHeight)" as NSString
        )
        updateLabelMetric(
            hudProperties, updateLabelMetricSelector,
            scalingMetrics[2].identifier, "\(targetWidth)x\(targetHeight)" as NSString
        )
        updateLabelMetric(
            hudProperties, updateLabelMetricSelector,
            scalingMetrics[3].identifier, String(format: "%.6f", exposure) as NSString
        )
        if !loggedScaling {
            loggedScaling = true
            NSLog(
                "[metallum] Apple MetalFX HUD scaling metrics active (\(mode) "
                    + "\(inputWidth)x\(inputHeight) -> \(targetWidth)x\(targetHeight))"
            )
        }
    }

    static func updateFrameInterpolator(deltaTime: Float) {
        guard deltaTime.isFinite, deltaTime > 0 else { return }
        lock.lock()
        defer { lock.unlock() }
        guard enabled,
              let hudProperties = resolvePropertiesLocked(),
              let method = class_getInstanceMethod(
                  type(of: hudProperties), frameInterpolatorEndSelector
              ) else {
            return
        }
        let update = unsafeBitCast(
            method_getImplementation(method),
            to: FrameInterpolatorEndImplementation.self
        )
        update(
            hudProperties,
            frameInterpolatorEndSelector,
            MetalFxHudFrameEnd(deltaTime: Double(deltaTime))
        )
        interpolatorInstalled = metricsExistLocked(interpolatorMetrics, in: hudProperties)
        if interpolatorInstalled && !loggedInterpolator {
            loggedInterpolator = true
            NSLog("[metallum] Apple MetalFX HUD frame-interpolator metrics active")
        }
    }

    static func frameInterpolatorDisabled() {
        lock.lock()
        defer { lock.unlock() }
        disableFrameInterpolatorLocked()
    }

    static func resetMetalFx() {
        lock.lock()
        defer { lock.unlock() }
        removeScalingLocked()
        disableFrameInterpolatorLocked()
    }

    private static func resolvePropertiesLocked() -> NSObject? {
        if let properties { return properties }
        guard let hudClass = NSClassFromString("_CADeveloperHUDProperties") as? NSObject.Type,
              hudClass.responds(to: instanceSelector),
              let instance = hudClass.perform(instanceSelector)?.takeUnretainedValue() as? NSObject,
              instance.responds(to: addMetricSelector),
              instance.responds(to: updateLabelMetricSelector),
              instance.responds(to: getMetricSelector),
              instance.responds(to: removeMetricSelector) else {
            return nil
        }
        properties = instance
        return instance
    }

    private static func installScalingLocked(_ hudProperties: NSObject) -> Bool {
        if scalingInstalled { return true }
        guard let method = class_getInstanceMethod(type(of: hudProperties), addMetricSelector) else {
            return false
        }
        let addMetric = unsafeBitCast(
            method_getImplementation(method),
            to: AddMetricImplementation.self
        )
        for metric in scalingMetrics {
            _ = addMetric(
                hudProperties,
                addMetricSelector,
                metric.identifier,
                metric.name,
                "",
                UInt32.max,
                UInt32.max,
                2048,
                8
            )
        }
        scalingInstalled = metricsExistLocked(
            scalingMetrics.map(\.identifier),
            in: hudProperties
        )
        return scalingInstalled
    }

    private static func metricsExistLocked(
        _ identifiers: [NSString],
        in hudProperties: NSObject
    ) -> Bool {
        identifiers.allSatisfy { identifier in
            hudProperties.perform(getMetricSelector, with: identifier)?.takeUnretainedValue() != nil
        }
    }

    private static func removeScalingLocked() {
        guard scalingInstalled, let hudProperties = properties,
              let method = class_getInstanceMethod(type(of: hudProperties), removeMetricSelector) else {
            scalingInstalled = false
            return
        }
        let removeMetric = unsafeBitCast(
            method_getImplementation(method),
            to: RemoveMetricImplementation.self
        )
        for metric in scalingMetrics.reversed() {
            removeMetric(hudProperties, removeMetricSelector, metric.identifier)
        }
        scalingInstalled = false
        loggedScaling = false
    }

    private static func disableFrameInterpolatorLocked() {
        guard interpolatorInstalled, let hudProperties = properties else {
            interpolatorInstalled = false
            return
        }
        if let method = class_getInstanceMethod(
            type(of: hudProperties), frameInterpolatorDisableSelector
        ) {
            let disable = unsafeBitCast(
                method_getImplementation(method),
                to: NoArgumentImplementation.self
            )
            disable(hudProperties, frameInterpolatorDisableSelector)
        }
        interpolatorInstalled = false
        loggedInterpolator = false
    }
}

#endif

@_cdecl("metallum_create_metal_layer")
public func metallum_create_metal_layer(
    _ device: MTLDevice,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    let layer = CAMetalLayer()
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    layer.contentsScale = CGFloat(contentsScale)
    setMetalHudProperties(layer, enabled: false)
    return retainedPointer(layer)
}

#if os(iOS)
/// Returns the host launcher's existing CAMetalLayer for the given UIView.
///
/// On Amethyst / PojavLauncher_iOS, `GameSurfaceView` overrides `+layerClass`
/// to return `CAMetalLayer.class`, so `view.layer` IS already a CAMetalLayer.
/// Amethyst's own Vulkan path (`pojavCreateContext` in `egl_bridge.m`) returns
/// `SurfaceViewController.surface.layer` directly to MoltenVK — it does NOT
/// create a new CAMetalLayer or attach a sublayer. We must follow the same
/// pattern: use `view.layer` itself as the render target.
///
/// Previously we created a new CAMetalLayer and added it as a sublayer of
/// `view.layer`. That does NOT work reliably: CAMetalLayer has special
/// compositing semantics, and a CAMetalLayer sublayer hosted inside another
/// CAMetalLayer (the view's backing layer) is not guaranteed to be displayed.
/// The result was a black screen with audio playing normally.
///
/// This function configures the existing layer's device (and a few other
/// render-target properties) and returns an *unretained* pointer — the view
/// owns the layer, so we must not retain it (would leak).
@_cdecl("metallum_ios_get_view_metal_layer")
public func metallum_ios_get_view_metal_layer(
    _ view: UIView,
    _ device: MTLDevice,
    _ contentsScale: Double
) -> UnsafeMutableRawPointer? {
    guard let layer = view.layer as? CAMetalLayer else {
        NSLog("[Metallum] view.layer is not a CAMetalLayer (got %@); falling back to sublayer attachment", String(describing: type(of: view.layer)))
        // Fallback for launchers that do not override +layerClass. Create a
        // new CAMetalLayer and add it as a sublayer, matching the macOS path.
        let newLayer = CAMetalLayer()
        newLayer.device = device
        newLayer.framebufferOnly = true
        newLayer.isOpaque = true
        newLayer.contentsScale = CGFloat(contentsScale)
        setMetalHudProperties(newLayer, enabled: false)
        newLayer.frame = view.bounds
        view.layer.sublayers = [newLayer]
        return retainedPointer(newLayer)
    }
    NSLog("[Metallum] Using existing view.layer as CAMetalLayer (frame=\(layer.frame), contentsScale=\(layer.contentsScale), drawsAsynchronously=\(layer.drawsAsynchronously ? "YES" : "NO"))")
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    setMetalHudProperties(layer, enabled: false)
    // Do NOT override contentsScale: Amethyst sets it to
    // screenScale * resolutionScale and re-syncs it on rotation; let the
    // launcher own that property. The renderable size is governed by
    // `drawableSize`, which we set in metallum_configure_layer.
    return unretainedPointer(layer)
}

#endif

/// Shows or hides Apple's Metal Performance HUD without recreating the layer.
/// The HUD subsystem is primed before the MTLDevice is created; clearing the
/// documented `mode` key keeps it hidden without stopping the game.
@_cdecl("metallum_set_metal_hud")
public func metallum_set_metal_hud(_ layer: CAMetalLayer, _ enabled: Int32) {
    let isEnabled = enabled != 0
    setMetalHudProperties(layer, enabled: isEnabled)
    #if os(macOS)
    MetalFxNativeHudMetrics.setEnabled(isEnabled)
    #endif
}

@_cdecl("metallum_NSView_setMetalLayer")
public func metallum_NSView_setMetalLayer(
    _ view: MetallumView,
    _ layer: CAMetalLayer
) {
    #if os(macOS)
    view.wantsLayer = true
    view.layer = layer
    #elseif os(iOS)
    // On iOS the Java side uses metallum_ios_get_view_metal_layer, which
    // returns view.layer directly (GameSurfaceView already overrides
    // +layerClass to CAMetalLayer.class). This function is therefore a no-op
    // on iOS — the layer is already attached to the view. We keep the symbol
    // so the macOS/Java code path that calls it unconditionally does not
    // need an #if guard.
    _ = view
    _ = layer
    #endif
}

@_cdecl("metallum_NSView_clearLayer")
public func metallum_NSView_clearLayer(_ view: MetallumView) {
    #if os(macOS)
    view.layer = nil
    view.wantsLayer = false
    #endif
}

@_cdecl("metallum_set_debug_labels_enabled")
public func metallum_set_debug_labels_enabled(_ enabled: Int32) {
    NativeState.debugLabelsEnabled = enabled != 0
}

@_cdecl("metallum_MTLDevice_maxMemoryAllocationSize")
public func metallum_MTLDevice_maxMemoryAllocationSize(_ device: MTLDevice) -> UInt64 {
    let maxBuffer = UInt64(device.maxBufferLength)
    #if os(iOS)
    if #available(iOS 16.0, *) {
        return min(maxBuffer, device.recommendedMaxWorkingSetSize)
    }
    return maxBuffer
    #else
    return min(maxBuffer, device.recommendedMaxWorkingSetSize)
    #endif
}

/// 1 when both the SDK this dylib was built against and the running device
/// support Metal 4. Both capability gates (compile-time #available, run-time
/// supportsFamily) are collected here so Java only sees a single answer; the
/// Metal 4 kill switches on the Java side AND this must both be true before any
/// MTL4 path is taken. Metal 4 exists only on macOS 26 / iOS 26, while
/// build.gradle still targets macosx14.0 / ios14.0, so MTLGPUFamily.metal4 must
/// stay inside #available.
@_cdecl("metallum_metal4_supported")
public func metallum_metal4_supported(_ device: MTLDevice) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *) {
        return device.supportsFamily(.metal4) ? 1 : 0
    }
    return 0
}

@_cdecl("metallum_metal4_main_queue_pilot_validate")
public func metallum_metal4_main_queue_pilot_validate(_ device: MTLDevice) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *), device.supportsFamily(.metal4) else {
        return 0
    }
    let pilot: Metal4MainQueuePilot
    if let existing = NativeState.metal4MainQueuePilotStorage as? Metal4MainQueuePilot {
        pilot = existing
    } else {
        guard let created = Metal4MainQueuePilot(device) else { return 0 }
        NativeState.metal4MainQueuePilotStorage = created
        pilot = created
    }
    for _ in 0..<6 {
        guard pilot.submitAndWait() else { return 0 }
    }
    NSLog("[metallum] Metal 4 main-queue pilot validated: 3 reusable buffers, 6 compute copies, explicit residency")
    return 1
}

@_cdecl("metallum_metal4_main_renderer_enable")
public func metallum_metal4_main_renderer_enable(
    _ device: MTLDevice,
    _ layer: CAMetalLayer?
) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *), device.supportsFamily(.metal4) else {
        return 0
    }
    if NativeState.metal4MainQueueStorage is Metal4MainQueueContext {
        return 1
    }
    guard let context = Metal4MainQueueContext(device, layer: layer) else {
        return 0
    }
    NativeState.metal4MainQueueStorage = context
    NSLog("[metallum] Metal 4 main renderer enabled: 3 reusable command buffers, explicit residency")
    return 1
}

@_cdecl("metallum_metal4_main_renderer_stats")
public func metallum_metal4_main_renderer_stats(
    _ begun: UnsafeMutablePointer<UInt64>?,
    _ submitted: UnsafeMutablePointer<UInt64>?,
    _ reused: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *),
          let context = NativeState.metal4MainQueueStorage as? Metal4MainQueueContext else {
        return 0
    }
    let values = context.stats()
    begun?.pointee = values.0
    submitted?.pointee = values.1
    reused?.pointee = values.2
    return 1
}

@_cdecl("metallum_metal4_metalfx_stats")
public func metallum_metal4_metalfx_stats(
    _ auxiliaryCompute: UnsafeMutablePointer<UInt64>?,
    _ spatial: UnsafeMutablePointer<UInt64>?,
    _ temporal: UnsafeMutablePointer<UInt64>?,
    _ frameGenerationInput: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard #available(macOS 26.0, iOS 26.0, *),
          NativeState.metal4MainQueueStorage is Metal4MainQueueContext else {
        return 0
    }
    auxiliaryCompute?.pointee = NativeState.metal4AuxiliaryComputeEncodeCount
    spatial?.pointee = NativeState.metal4SpatialEncodeCount
    temporal?.pointee = NativeState.metal4TemporalEncodeCount
    frameGenerationInput?.pointee = NativeState.metal4FrameGenerationInputCount
    return 1
}

@_cdecl("metallum_MTLDevice_makeCommandQueue")
public func metallum_MTLDevice_makeCommandQueue(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeCommandQueue())
    }
}

@_cdecl("metallum_MTLCommandQueue_makeCommandBuffer")
public func metallum_MTLCommandQueue_makeCommandBuffer(
    _ queue: MTLCommandQueue,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool { () -> UnsafeMutableRawPointer? in
        if #available(macOS 26.0, iOS 26.0, *),
           let context = NativeState.metal4MainQueueStorage as? Metal4MainQueueContext {
            return retainedPointer(context.beginLease(label: stringFromOptionalCString(labelPtr)))
        }
        guard let commandBuffer = queue.makeCommandBuffer() else {
            return nil
        }
        if NativeState.debugLabelsEnabled {
            commandBuffer.label = stringFromOptionalCString(labelPtr)
        }
        return retainedPointer(commandBuffer)
    }
}

@_cdecl("metallum_MTLCommandBuffer_commit")
public func metallum_MTLCommandBuffer_commit(_ pointer: UnsafeMutableRawPointer) {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        lease.owner.submit(lease, signal: nil)
        return
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    finishGpuEncoderTimings(commandBuffer)
    residencyFlushBeforeSubmit()
    commandBuffer.commit()
}

@_cdecl("metallum_create_semaphore")
public func metallum_create_semaphore() -> UnsafeMutableRawPointer? {
    retainedPointer(DispatchSemaphore(value: 0))
}

@_cdecl("metallum_MTLCommandBuffer_commitWithSignal")
public func metallum_MTLCommandBuffer_commitWithSignal(_ pointer: UnsafeMutableRawPointer, _ semaphore: DispatchSemaphore) {
    while semaphore.wait(timeout: .now()) == .success {}
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        lease.owner.submit(lease, signal: semaphore)
        return
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    finishGpuEncoderTimings(commandBuffer)
    commandBuffer.addCompletedHandler { _ in
        semaphore.signal()
    }
    residencyFlushBeforeSubmit()
    commandBuffer.commit()
}

@_cdecl("metallum_semaphore_wait")
public func metallum_semaphore_wait(_ semaphore: DispatchSemaphore, _ timeoutMs: UInt64) -> Int32 {
    let result: DispatchTimeoutResult
    if timeoutMs >= UInt64(Int.max) {
        result = semaphore.wait(timeout: .distantFuture)
    } else {
        result = semaphore.wait(timeout: .now() + .milliseconds(Int(timeoutMs)))
    }
    guard result == .success else {
        return 1
    }
    semaphore.signal()
    return 0
}

@_cdecl("metallum_MTLCommandBuffer_isCompleted")
public func metallum_MTLCommandBuffer_isCompleted(_ pointer: UnsafeMutableRawPointer) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        return lease.isCompleted() ? 1 : 0
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_completedSuccessfully")
public func metallum_MTLCommandBuffer_completedSuccessfully(_ pointer: UnsafeMutableRawPointer) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        return lease.completedSuccessfully() ? 1 : 0
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    return commandBuffer.status == .completed && commandBuffer.error == nil ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_gpuStartTime")
public func metallum_MTLCommandBuffer_gpuStartTime(_ pointer: UnsafeMutableRawPointer) -> Double {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        return lease.gpuTimes().0
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    return commandBuffer.gpuStartTime
}

@_cdecl("metallum_MTLCommandBuffer_gpuEndTime")
public func metallum_MTLCommandBuffer_gpuEndTime(_ pointer: UnsafeMutableRawPointer) -> Double {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        return lease.gpuTimes().1
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    return commandBuffer.gpuEndTime
}

@_cdecl("metallum_MTLCommandBuffer_waitUntilCompleted")
public func metallum_MTLCommandBuffer_waitUntilCompleted(_ pointer: UnsafeMutableRawPointer, _ timeoutMs: UInt64) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        return lease.waitUntilCompleted(timeoutMs: timeoutMs) ? 0 : 1
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    if commandBuffer.status == .completed || commandBuffer.status == .error {
        return 0
    }
    if timeoutMs == 0 {
        return 1
    }
    commandBuffer.waitUntilCompleted()
    return commandBuffer.status == .completed || commandBuffer.status == .error ? 0 : 1
}

@_cdecl("metallum_MTLCommandBuffer_pushDebugGroup")
public func metallum_MTLCommandBuffer_pushDebugGroup(
    _ pointer: UnsafeMutableRawPointer,
    _ labelPtr: UnsafePointer<CChar>?
) {
    autoreleasepool {
        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            lease.commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
            return
        }
        let commandBuffer = metal3CommandBuffer(pointer)
        commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
    }
}

@_cdecl("metallum_MTLCommandBuffer_popDebugGroup")
public func metallum_MTLCommandBuffer_popDebugGroup(_ pointer: UnsafeMutableRawPointer) {
    if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
        lease.commandBuffer.popDebugGroup()
        return
    }
    let commandBuffer = metal3CommandBuffer(pointer)
    commandBuffer.popDebugGroup()
}

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ pointer: UnsafeMutableRawPointer,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let label = stringFromOptionalCString(labelPtr) ?? "blit"
        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            guard let encoder = lease.commandBuffer.makeComputeCommandEncoder() else { return nil }
            encoder.label = label
            encoder.barrier(
                afterQueueStages: [.fragment, .dispatch, .blit],
                beforeStages: .blit,
                visibilityOptions: .device
            )
            return retainedPointer(Metal4MainBlitEncoderBridge(encoder))
        }
        let commandBuffer = metal3CommandBuffer(pointer)
        let timing = gpuEncoderTimingContext(commandBuffer)
        let indices = timing?.reserve(label: label, kind: 1)
        let descriptor = MTLBlitPassDescriptor()
        if let timing, let indices, let attachment = descriptor.sampleBufferAttachments[0] {
            attachment.sampleBuffer = timing.sampleBuffer
            attachment.startOfEncoderSampleIndex = indices.0
            attachment.endOfEncoderSampleIndex = indices.1
        }
        guard let encoder = commandBuffer.makeBlitCommandEncoder(descriptor: descriptor) else {
            return nil
        }
        encoder.label = label
        metal4BarrierBlitAfterRender(encoder)
        return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ pointer: UnsafeMutableRawPointer) {
    if #available(macOS 26.0, iOS 26.0, *), let render = metal4RenderBridge(pointer) {
        render.encoder.endEncoding()
        return
    }
    if #available(macOS 26.0, iOS 26.0, *), let blit = metal4BlitBridge(pointer) {
        blit.encoder.endEncoding()
        return
    }
    let encoder = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as! MTLCommandEncoder
    encoder.endEncoding()
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(
    _ pointer: UnsafeMutableRawPointer,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ length: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4BlitBridge(pointer) {
        bridge.encoder.copy(
            sourceBuffer: sourceBuffer,
            sourceOffset: Int(sourceOffset),
            destinationBuffer: destinationBuffer,
            destinationOffset: Int(destinationOffset),
            size: Int(length)
        )
        return
    }
    let blit = metal3BlitEncoder(pointer)
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(
    _ pointer: UnsafeMutableRawPointer,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ texture: MTLTexture,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4BlitBridge(pointer) {
        bridge.encoder.copy(
            sourceBuffer: sourceBuffer,
            sourceOffset: Int(sourceOffset),
            sourceBytesPerRow: Int(bytesPerRow),
            sourceBytesPerImage: Int(bytesPerImage),
            sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            destinationTexture: texture,
            destinationSlice: Int(slice),
            destinationLevel: Int(mipLevel),
            destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
        )
        return
    }
    let blit = metal3BlitEncoder(pointer)
    blit.copy(
        from: sourceBuffer,
        sourceOffset: Int(sourceOffset),
        sourceBytesPerRow: Int(bytesPerRow),
        sourceBytesPerImage: Int(bytesPerImage),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: texture,
        destinationSlice: Int(slice),
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToTexture(
    _ pointer: UnsafeMutableRawPointer,
    _ sourceTexture: MTLTexture,
    _ destinationTexture: MTLTexture,
    _ mipLevel: UInt64,
    _ sourceX: UInt64,
    _ sourceY: UInt64,
    _ destX: UInt64,
    _ destY: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4BlitBridge(pointer) {
        bridge.encoder.copy(
            sourceTexture: sourceTexture,
            sourceSlice: 0,
            sourceLevel: Int(mipLevel),
            sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
            sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            destinationTexture: destinationTexture,
            destinationSlice: 0,
            destinationLevel: Int(mipLevel),
            destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
        )
        return
    }
    let blit = metal3BlitEncoder(pointer)
    blit.copy(
        from: sourceTexture,
        sourceSlice: 0,
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(sourceX), y: Int(sourceY), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationTexture,
        destinationSlice: 0,
        destinationLevel: Int(mipLevel),
        destinationOrigin: MTLOrigin(x: Int(destX), y: Int(destY), z: 0)
    )
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer(
    _ pointer: UnsafeMutableRawPointer,
    _ sourceTexture: MTLTexture,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ mipLevel: UInt64,
    _ slice: UInt64,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64,
    _ bytesPerImage: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4BlitBridge(pointer) {
        bridge.encoder.copy(
            sourceTexture: sourceTexture,
            sourceSlice: Int(slice),
            sourceLevel: Int(mipLevel),
            sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
            sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
            destinationBuffer: destinationBuffer,
            destinationOffset: Int(destinationOffset),
            destinationBytesPerRow: Int(bytesPerRow),
            destinationBytesPerImage: Int(bytesPerImage)
        )
        return
    }
    let blit = metal3BlitEncoder(pointer)
    blit.copy(
        from: sourceTexture,
        sourceSlice: Int(slice),
        sourceLevel: Int(mipLevel),
        sourceOrigin: MTLOrigin(x: Int(x), y: Int(y), z: 0),
        sourceSize: MTLSize(width: Int(width), height: Int(height), depth: 1),
        to: destinationBuffer,
        destinationOffset: Int(destinationOffset),
        destinationBytesPerRow: Int(bytesPerRow),
        destinationBytesPerImage: Int(bytesPerImage)
    )
}

@_cdecl("metallum_create_buffer")
public func metallum_create_buffer(
    _ device: MTLDevice,
    _ length: Int,
    _ options: MTLResourceOptions
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let buffer = device.makeBuffer(length: length, options: options) else {
            return nil
        }
        residencyTrackCreated(buffer)
        return retainedPointer(buffer)
    }
}

@_cdecl("metallum_create_texture_2d")
public func metallum_create_texture_2d(
    _ device: MTLDevice,
    _ pixelFormat: MTLPixelFormat,
    _ width: UInt64,
    _ height: UInt64,
    _ depthOrLayers: UInt64,
    _ mipLevels: UInt64,
    _ cubeCompatible: UInt64,
    _ usage: MTLTextureUsage,
    _ storageMode: MTLStorageMode,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: pixelFormat,
            width: Int(width),
            height: Int(height),
            mipmapped: mipLevels > 1
        )

        if cubeCompatible != 0 {
            if depthOrLayers > 6 {
                descriptor.textureType = MTLTextureType.typeCubeArray
                descriptor.arrayLength = Int(depthOrLayers) / 6
            } else {
                descriptor.textureType = MTLTextureType.typeCube
                descriptor.arrayLength = 1
            }
        } else if depthOrLayers > 1 {
            descriptor.textureType = MTLTextureType.type2DArray
            descriptor.arrayLength = Int(depthOrLayers)
        }

        descriptor.mipmapLevelCount = max(Int(mipLevels), 1)
        descriptor.usage = usage
        descriptor.storageMode = storageMode
        descriptor.hazardTrackingMode = .untracked
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }
        texture.label = stringFromOptionalCString(labelPtr)
        residencyTrackCreated(texture)
        return retainedPointer(texture)
    }
}

@_cdecl("metallum_create_texture_view")
public func metallum_create_texture_view(_ texture: MTLTexture, _ baseMipLevel: UInt64, _ mipLevelCount: UInt64) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard mipLevelCount > 0 else {
            return nil
        }

        let baseLevel = Int(baseMipLevel)
        let levelCount = Int(mipLevelCount)
        guard baseLevel < texture.mipmapLevelCount, baseLevel + levelCount <= texture.mipmapLevelCount else {
            return nil
        }

        let view = texture.__newTextureView(
            with: texture.pixelFormat,
            textureType: texture.textureType,
            levels: NSRange(location: baseLevel, length: levelCount),
            slices: NSRange(location: 0, length: textureSliceCount(texture))
        )

        return retainedPointer(view)
    }
}

@_cdecl("metallum_create_buffer_texture_view")
public func metallum_create_buffer_texture_view(
    _ buffer: MTLBuffer,
    _ pixelFormat: MTLPixelFormat,
    _ offset: UInt64,
    _ width: UInt64,
    _ height: UInt64,
    _ bytesPerRow: UInt64
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard
            pixelFormat != .invalid,
            width > 0,
            bytesPerRow > 0
        else {
            return nil
        }

        let nativeOffset = Int(offset)
        let nativeWidth = Int(width)
        let nativeBytesPerRow = Int(bytesPerRow)
        guard nativeOffset >= 0, nativeWidth > 0, nativeBytesPerRow > 0, nativeOffset <= buffer.length, nativeBytesPerRow <= buffer.length - nativeOffset else {
            return nil
        }

        let alignment = buffer.device.minimumLinearTextureAlignment(for: pixelFormat)
        guard alignment > 0, nativeOffset % alignment == 0 else {
            return nil
        }

        let alignedBytesPerRow = roundUp(nativeBytesPerRow, alignment: alignment)
        let descriptor = MTLTextureDescriptor.textureBufferDescriptor(
            with: pixelFormat,
            width: nativeWidth,
            resourceOptions: [],
            usage: MTLTextureUsage.shaderRead
        )
        descriptor.storageMode = buffer.storageMode
        descriptor.hazardTrackingMode = .untracked

        return retainedPointer(buffer.makeTexture(descriptor: descriptor, offset: nativeOffset, bytesPerRow: alignedBytesPerRow))
    }
}

private func roundUp(_ value: Int, alignment: Int) -> Int {
    let remainder = value % alignment
    return remainder == 0 ? value : value + alignment - remainder
}

@_cdecl("metallum_create_sampler")
public func metallum_create_sampler(
    _ device: MTLDevice,
    _ addressModeU: MTLSamplerAddressMode,
    _ addressModeV: MTLSamplerAddressMode,
    _ minFilter: MTLSamplerMinMagFilter,
    _ magFilter: MTLSamplerMinMagFilter,
    _ mipFilter: MTLSamplerMipFilter,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let clampedAnisotropy = max(Int(maxAnisotropy), 1)
        let clamp: Float = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
        // Sampler states are immutable device objects with a hard device
        // limit; identical descriptors share one cached instance. Ownership
        // protocol is unchanged: every call returns +1 (passRetained) and the
        // Java close() releases exactly once; the cache keeps its own strong
        // reference for the process lifetime. Render thread only, like
        // depthStencilStates.
        let key = SamplerKey(
            deviceAddress: objectAddress(device),
            addressModeU: addressModeU.rawValue,
            addressModeV: addressModeV.rawValue,
            minFilter: minFilter.rawValue,
            magFilter: magFilter.rawValue,
            mipFilter: mipFilter.rawValue,
            maxAnisotropy: clampedAnisotropy,
            lodMaxClampBits: clamp.bitPattern
        )
        if let cached = NativeState.samplerStates[key] {
            return Unmanaged.passRetained(cached).toOpaque()
        }
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
        descriptor.sAddressMode = addressModeU
        descriptor.tAddressMode = addressModeV
        descriptor.maxAnisotropy = clampedAnisotropy
        descriptor.lodMinClamp = 0.0
        descriptor.lodMaxClamp = clamp
        guard let state = device.makeSamplerState(descriptor: descriptor) else {
            return nil
        }
        NativeState.samplerStates[key] = state
        return Unmanaged.passRetained(state).toOpaque()
    }
}

@_cdecl("metallum_MTLDevice_makeDepthStencilState")
public func metallum_MTLDevice_makeDepthStencilState(
    _ device: MTLDevice,
    _ depthCompareOp: MTLCompareFunction,
    _ writeDepth: Int32
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        unretainedPointer(ensureDepthStencilState(device: device, compareOp: depthCompareOp, writeDepth: writeDepth != 0))
    }
}

@_cdecl("metallum_MTLCommandBuffer_makeRenderCommandEncoder")
public func metallum_MTLCommandBuffer_makeRenderCommandEncoder(
    _ pointer: UnsafeMutableRawPointer,
    _ colorTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard colorTexture != nil || depthTexture != nil else {
            return nil
        }
        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            let renderPass = MTL4RenderPassDescriptor()
            if let colorTexture {
                renderPass.colorAttachments[0].texture = colorTexture
                renderPass.colorAttachments[0].loadAction = clearColorEnabled != 0 ? .clear : .load
                renderPass.colorAttachments[0].clearColor = makeClearColor(
                    red: clearColorRed,
                    green: clearColorGreen,
                    blue: clearColorBlue,
                    alpha: clearColorAlpha
                )
                renderPass.colorAttachments[0].storeAction = .store
            }
            if let depthTexture {
                renderPass.depthAttachment.texture = depthTexture
                renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
                renderPass.depthAttachment.clearDepth = clearDepth
                renderPass.depthAttachment.storeAction = .store
                if stencilPixelFormat(for: depthTexture.pixelFormat) != .invalid {
                    renderPass.stencilAttachment.texture = depthTexture
                    renderPass.stencilAttachment.loadAction = .dontCare
                    renderPass.stencilAttachment.storeAction = .dontCare
                }
            }
            renderPass.renderTargetWidth = Int(viewportWidth)
            renderPass.renderTargetHeight = Int(viewportHeight)
            guard let encoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
                return nil
            }
            encoder.barrier(
                afterQueueStages: [.blit, .fragment, .dispatch],
                beforeStages: [.vertex, .fragment],
                visibilityOptions: .device
            )
            encoder.setViewport(MTLViewport(
                originX: 0.0, originY: 0.0,
                width: viewportWidth, height: viewportHeight,
                znear: 0.0, zfar: 1.0
            ))
            let tables = lease.owner.argumentTables(at: lease.slotIndex)
            return retainedPointer(Metal4MainRenderEncoderBridge(
                encoder: encoder,
                lease: lease,
                vertexArguments: tables.0,
                fragmentArguments: tables.1
            ))
        }
        let commandBuffer = metal3CommandBuffer(pointer)
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let stencilFormat = stencilPixelFormat(for: depthFormat)

        let renderPass = MTLRenderPassDescriptor()
        if let colorTexture {
            renderPass.colorAttachments[0].texture = colorTexture
            if clearColorEnabled != 0 {
                renderPass.colorAttachments[0].loadAction = .clear
                renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
            } else {
                renderPass.colorAttachments[0].loadAction = .load
            }
            renderPass.colorAttachments[0].storeAction = .store
        }

        if let depthTexture {
            renderPass.depthAttachment.texture = depthTexture
            renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
            renderPass.depthAttachment.clearDepth = clearDepth
            renderPass.depthAttachment.storeAction = .store
            if stencilFormat != .invalid {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                renderPass.stencilAttachment.storeAction = .dontCare
            }
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return nil
        }
        metal4BarrierRenderAfterUploadAndRender(encoder)
        encoder.setViewport(MTLViewport(originX: 0.0, originY: 0.0, width: viewportWidth, height: viewportHeight, znear: 0.0, zfar: 1.0))
        return retainedPointer(encoder)
    }
}

/// Array-preserving render-pass entry point. The pointer array contains
/// unretained Objective-C texture pointers for each Java color slot; it is
/// only dereferenced while this call is active. Null entries remain null so
/// slot N is never compacted into another Metal attachment.
@_cdecl("metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2")
public func metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2(
    _ pointer: UnsafeMutableRawPointer,
    _ colorTexturePointers: UnsafePointer<UnsafeMutableRawPointer?>?,
    _ colorCount: Int32,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColors: UnsafePointer<Float>?,
    _ clearColorEnabled: UnsafePointer<Int32>?,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double,
    _ labelPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool { () -> UnsafeMutableRawPointer? in
        let count = Int(colorCount)
        guard count >= 0 && count <= 8 else {
            NSLog("[Metallum] rejected render pass with %d color slots; Metal backend supports at most 8", colorCount)
            return nil
        }
        guard count == 0 || colorTexturePointers != nil else {
            NSLog("[Metallum] render pass color slot count is non-zero but the texture array is null")
            return nil
        }
        guard count == 0 || (clearColors != nil && clearColorEnabled != nil) else {
            NSLog("[Metallum] render pass color slot count is non-zero but clear arrays are null")
            return nil
        }
        guard count > 0 || depthTexture != nil else {
            NSLog("[Metallum] rejected render pass with no color or depth attachment")
            return nil
        }

        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let stencilFormat = stencilPixelFormat(for: depthFormat)
        let label = stringFromOptionalCString(labelPtr) ?? "render"

        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            let renderPass = MTL4RenderPassDescriptor()
            for index in 0..<count {
                guard let attachment = renderPass.colorAttachments[index] else { return nil }
                guard let texture = textureFromUnretainedPointer(colorTexturePointers?[index]) else {
                    attachment.loadAction = .dontCare
                    attachment.storeAction = .dontCare
                    continue
                }
                attachment.texture = texture
                if clearColorEnabled?[index] != 0 {
                    let base = index * 4
                    let colors = clearColors!
                    attachment.loadAction = .clear
                    attachment.clearColor = makeClearColor(
                        red: colors[base], green: colors[base + 1],
                        blue: colors[base + 2], alpha: colors[base + 3]
                    )
                } else {
                    attachment.loadAction = .load
                }
                attachment.storeAction = .store
            }
            if let depthTexture {
                if depthFormat != .stencil8 {
                    renderPass.depthAttachment.texture = depthTexture
                    renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
                    renderPass.depthAttachment.clearDepth = clearDepth
                    renderPass.depthAttachment.storeAction = NativeState.deferredDepthStore ? .unknown : .store
                }
                if stencilFormat != .invalid || depthFormat == .stencil8 {
                    renderPass.stencilAttachment.texture = depthTexture
                    renderPass.stencilAttachment.loadAction = .dontCare
                    renderPass.stencilAttachment.storeAction = .dontCare
                }
            }
            renderPass.renderTargetWidth = Int(viewportWidth)
            renderPass.renderTargetHeight = Int(viewportHeight)
            guard let encoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
                return nil
            }
            encoder.label = label
            encoder.barrier(
                afterQueueStages: [.blit, .fragment, .dispatch],
                beforeStages: [.vertex, .fragment],
                visibilityOptions: .device
            )
            encoder.setViewport(MTLViewport(
                originX: 0.0, originY: 0.0,
                width: viewportWidth, height: viewportHeight,
                znear: 0.0, zfar: 1.0
            ))
            let tables = lease.owner.argumentTables(at: lease.slotIndex)
            return retainedPointer(Metal4MainRenderEncoderBridge(
                encoder: encoder,
                lease: lease,
                vertexArguments: tables.0,
                fragmentArguments: tables.1
            ))
        }

        let commandBuffer = metal3CommandBuffer(pointer)
        let renderPass = MTLRenderPassDescriptor()

        for index in 0..<count {
            let rawTexture: UnsafeMutableRawPointer?
            if let colorTexturePointers {
                rawTexture = colorTexturePointers[index]
            } else {
                rawTexture = nil
            }
            guard let attachment = renderPass.colorAttachments[index] else {
                NSLog("[Metallum] color attachment descriptor %d is unavailable", index)
                return nil
            }
            guard let texture = textureFromUnretainedPointer(rawTexture) else {
                attachment.loadAction = .dontCare
                attachment.storeAction = .dontCare
                continue
            }

            attachment.texture = texture
            let enabled = clearColorEnabled?[index] ?? 0
            if enabled != 0 {
                let base = index * 4
                let colors = clearColors!
                attachment.loadAction = .clear
                attachment.clearColor = makeClearColor(
                    red: colors[base],
                    green: colors[base + 1],
                    blue: colors[base + 2],
                    alpha: colors[base + 3]
                )
            } else {
                attachment.loadAction = .load
            }
            attachment.storeAction = .store
        }

        if let depthTexture {
            if depthFormat != .stencil8 {
                renderPass.depthAttachment.texture = depthTexture
                renderPass.depthAttachment.loadAction = clearDepthEnabled != 0 ? .clear : .load
                renderPass.depthAttachment.clearDepth = clearDepth
                // Deferred mode: the Java encoder owns the store decision and
                // must call metallum_MTLRenderCommandEncoder_setDepthStoreAction
                // before endEncoding (Metal requires resolving .unknown).
                renderPass.depthAttachment.storeAction = NativeState.deferredDepthStore ? .unknown : .store
            }
            if stencilFormat != .invalid || depthFormat == .stencil8 {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                // Every pass loads stencil as .dontCare, so no pass can ever
                // observe a stored stencil value: storing it is provably dead
                // bandwidth. Revisit if stencil load semantics ever change.
                renderPass.stencilAttachment.storeAction = .dontCare
            }
        }

        let timing = gpuEncoderTimingContext(commandBuffer)
        if let timing,
           let indices = timing.reserve(label: label, kind: 0),
           let attachment = renderPass.sampleBufferAttachments[0] {
            attachment.sampleBuffer = timing.sampleBuffer
            attachment.startOfVertexSampleIndex = indices.0
            attachment.endOfFragmentSampleIndex = indices.1
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return nil
        }
        encoder.label = label
        metal4BarrierRenderAfterUploadAndRender(encoder)
        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: viewportWidth,
            height: viewportHeight,
            znear: 0.0,
            zfar: 1.0
        ))
        return retainedPointer(encoder)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setRenderPipelineState")
public func metallum_MTLRenderCommandEncoder_setRenderPipelineState(_ pointer: UnsafeMutableRawPointer, _ pipeline: MTLRenderPipelineState) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setRenderPipelineState(pipeline)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setRenderPipelineState(pipeline)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStencilState")
public func metallum_MTLRenderCommandEncoder_setDepthStencilState(_ pointer: UnsafeMutableRawPointer, _ state: MTLDepthStencilState?) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setDepthStencilState(state)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setDepthStencilState(state)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthBias")
public func metallum_MTLRenderCommandEncoder_setDepthBias(
    _ pointer: UnsafeMutableRawPointer,
    _ depthBias: Float,
    _ slopeScale: Float,
    _ clamp: Float
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFrontFacingWinding")
public func metallum_MTLRenderCommandEncoder_setFrontFacingWinding(_ pointer: UnsafeMutableRawPointer, _ winding: MTLWinding) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setFrontFacing(winding)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setFrontFacing(winding)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setCullMode")
public func metallum_MTLRenderCommandEncoder_setCullMode(_ pointer: UnsafeMutableRawPointer, _ cullMode: MTLCullMode) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setCullMode(cullMode)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setCullMode(cullMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTriangleFillMode")
public func metallum_MTLRenderCommandEncoder_setTriangleFillMode(_ pointer: UnsafeMutableRawPointer, _ fillMode: MTLTriangleFillMode) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setTriangleFillMode(fillMode)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setTriangleFillMode(fillMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBuffer")
public func metallum_MTLRenderCommandEncoder_setBuffer(_ pointer: UnsafeMutableRawPointer, _ buffer: MTLBuffer?, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.setBuffer(buffer, offset: Int(offset), index: Int(index), stageMask: stageMask)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    if (stageMask & 1) != 0 {
        encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBufferOffset")
public func metallum_MTLRenderCommandEncoder_setBufferOffset(_ pointer: UnsafeMutableRawPointer, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.setBufferOffset(Int(offset), index: Int(index), stageMask: stageMask)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    if (stageMask & 1) != 0 {
        encoder.setVertexBufferOffset(Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBufferOffset(Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTexture")
public func metallum_MTLRenderCommandEncoder_setTexture(_ pointer: UnsafeMutableRawPointer, _ texture: MTLTexture?, _ index: UInt64, _ stageMask: Int32) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.setTexture(texture, index: Int(index), stageMask: stageMask)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTextureAndSampler")
public func metallum_MTLRenderCommandEncoder_setTextureAndSampler(_ pointer: UnsafeMutableRawPointer, _ texture: MTLTexture?, _ sampler: MTLSamplerState?, _ index: UInt64, _ stageMask: Int32) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.setTextureAndSampler(texture, sampler: sampler, index: Int(index), stageMask: stageMask)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
        encoder.setVertexSamplerState(sampler, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
        encoder.setFragmentSamplerState(sampler, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setScissorRect")
public func metallum_MTLRenderCommandEncoder_setScissorRect(
    _ pointer: UnsafeMutableRawPointer,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitives")
public func metallum_MTLRenderCommandEncoder_drawPrimitives(
    _ pointer: UnsafeMutableRawPointer,
    _ primitiveType: MTLPrimitiveType,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.drawPrimitives(
            primitiveType: primitiveType,
            vertexStart: firstVertex,
            vertexCount: vertexCount,
            instanceCount: instanceCount,
            baseInstance: baseInstance
        )
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.drawPrimitives(
        type: primitiveType,
        vertexStart: firstVertex,
        vertexCount: vertexCount,
        instanceCount: instanceCount,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(
    _ pointer: UnsafeMutableRawPointer,
    _ primitiveType: MTLPrimitiveType,
    _ indexCount: Int,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseVertex: Int,
    _ baseInstance: Int
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        let offset = max(indexBufferOffset, 0)
        bridge.encoder.drawIndexedPrimitives(
            primitiveType: primitiveType,
            indexCount: indexCount,
            indexType: indexType,
            indexBuffer: indexBuffer.gpuAddress + UInt64(offset),
            indexBufferLength: max(indexBuffer.length - offset, 0),
            instanceCount: instanceCount,
            baseVertex: baseVertex,
            baseInstance: baseInstance
        )
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.drawIndexedPrimitives(
        type: primitiveType,
        indexCount: indexCount,
        indexType: indexType,
        indexBuffer: indexBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLRenderCommandEncoder_multiDrawIndexed")
public func metallum_MTLRenderCommandEncoder_multiDrawIndexed(
    _ pointer: UnsafeMutableRawPointer,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ firstIndexOffsets: UnsafePointer<Int>,
    _ indexCounts: UnsafePointer<Int32>,
    _ vertexOffsets: UnsafePointer<Int32>,
    _ drawCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        for i in 0..<drawCount {
            let indexCount = Int(indexCounts[i])
            let offset = max(firstIndexOffsets[i], 0)
            if indexCount > 0 {
                bridge.encoder.drawIndexedPrimitives(
                    primitiveType: primitiveType,
                    indexCount: indexCount,
                    indexType: indexType,
                    indexBuffer: indexBuffer.gpuAddress + UInt64(offset),
                    indexBufferLength: max(indexBuffer.length - offset, 0),
                    instanceCount: instanceCount,
                    baseVertex: Int(vertexOffsets[i]),
                    baseInstance: baseInstance
                )
            }
        }
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    for i in 0..<drawCount {
        let indexCount = Int(indexCounts[i])
        if indexCount > 0 {
            encoder.drawIndexedPrimitives(
                type: primitiveType,
                indexCount: indexCount,
                indexType: indexType,
                indexBuffer: indexBuffer,
                indexBufferOffset: firstIndexOffsets[i],
                instanceCount: instanceCount,
                baseVertex: Int(vertexOffsets[i]),
                baseInstance: baseInstance
            )
        }
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
    _ pointer: UnsafeMutableRawPointer,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        var offset = Int(indirectBufferOffset)
        for _ in 0..<drawCount {
            bridge.encoder.drawIndexedPrimitives(
                primitiveType: primitiveType,
                indexType: indexType,
                indexBuffer: indexBuffer.gpuAddress,
                indexBufferLength: indexBuffer.length,
                indirectBuffer: indirectBuffer.gpuAddress + UInt64(offset)
            )
            offset += Int(stride)
        }
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawIndexedPrimitives(
            type: primitiveType,
            indexType: indexType,
            indexBuffer: indexBuffer,
            indexBufferOffset: 0,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect")
public func metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect(
    _ pointer: UnsafeMutableRawPointer,
    _ primitiveType: MTLPrimitiveType,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        var offset = Int(indirectBufferOffset)
        for _ in 0..<drawCount {
            bridge.encoder.drawPrimitives(
                primitiveType: primitiveType,
                indirectBuffer: indirectBuffer.gpuAddress + UInt64(offset)
            )
            offset += Int(stride)
        }
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    var offset = Int(indirectBufferOffset)
    for _ in 0..<drawCount {
        encoder.drawPrimitives(
            type: primitiveType,
            indirectBuffer: indirectBuffer,
            indirectBufferOffset: offset
        )
        offset += Int(stride)
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan")
public func metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
    _ pointer: UnsafeMutableRawPointer,
    _ indexBuffer: MTLBuffer,
    _ fanIndexBuffer: MTLBuffer,
    _ fanIndexBufferOffset: Int,
    _ indexType: Int,
    _ indexOffsetBytes: Int,
    _ indexCount: Int,
    _ baseVertex: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    guard let generatedIndexCount = writeIndexedTriangleFanIndices(
        sourceIndexBuffer: indexBuffer,
        destinationIndexBuffer: fanIndexBuffer,
        destinationOffset: fanIndexBufferOffset,
        indexType: indexType,
        indexOffsetBytes: indexOffsetBytes,
        indexCount: indexCount
    ) else {
        return
    }
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.drawIndexedPrimitives(
            primitiveType: .triangle,
            indexCount: generatedIndexCount,
            indexType: .uint32,
            indexBuffer: fanIndexBuffer.gpuAddress + UInt64(fanIndexBufferOffset),
            indexBufferLength: max(fanIndexBuffer.length - fanIndexBufferOffset, 0),
            instanceCount: instanceCount,
            baseVertex: baseVertex,
            baseInstance: baseInstance
        )
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.drawIndexedPrimitives(
        type: .triangle,
        indexCount: generatedIndexCount,
        indexType: .uint32,
        indexBuffer: fanIndexBuffer,
        indexBufferOffset: fanIndexBufferOffset,
        instanceCount: instanceCount,
        baseVertex: baseVertex,
        baseInstance: baseInstance
    )
}

@_cdecl("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion")
public func metallum_MTLCommandBuffer_clearColorDepthTexturesRegion(
    _ pointer: UnsafeMutableRawPointer,
    _ colorTexture: MTLTexture,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ depthTexture: MTLTexture,
    _ clearDepth: Double,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard width > 0, height > 0 else {
            return
        }

        let textureWidth = min(colorTexture.width, depthTexture.width)
        let textureHeight = min(colorTexture.height, depthTexture.height)
        let clampedX = max(Int(x), 0)
        let clampedY = max(Int(y), 0)
        let clampedMaxX = min(Int(x) + Int(width), textureWidth)
        let clampedMaxY = min(Int(y) + Int(height), textureHeight)
        if clampedX >= clampedMaxX || clampedY >= clampedMaxY {
            return
        }
        let scissorRect = MTLScissorRect(x: clampedX, y: clampedY, width: clampedMaxX - clampedX, height: clampedMaxY - clampedY)
        let fullRegion = clampedX == 0 && clampedY == 0 && clampedMaxX == textureWidth && clampedMaxY == textureHeight

        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            let renderPass = MTL4RenderPassDescriptor()
            renderPass.colorAttachments[0].texture = colorTexture
            renderPass.colorAttachments[0].loadAction = fullRegion ? .clear : .load
            renderPass.colorAttachments[0].clearColor = makeClearColor(
                red: clearColorRed, green: clearColorGreen,
                blue: clearColorBlue, alpha: clearColorAlpha
            )
            renderPass.colorAttachments[0].storeAction = .store
            renderPass.depthAttachment.texture = depthTexture
            renderPass.depthAttachment.loadAction = fullRegion ? .clear : .load
            renderPass.depthAttachment.clearDepth = clearDepth
            renderPass.depthAttachment.storeAction = .store
            if stencilPixelFormat(for: depthTexture.pixelFormat) != .invalid {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                renderPass.stencilAttachment.storeAction = .dontCare
            }
            renderPass.renderTargetWidth = textureWidth
            renderPass.renderTargetHeight = textureHeight
            guard let encoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
                return
            }
            encoder.barrier(
                afterQueueStages: [.blit, .fragment, .dispatch],
                beforeStages: [.vertex, .fragment],
                visibilityOptions: .device
            )
            let tables = lease.owner.argumentTables(at: lease.slotIndex)
            let bridge = Metal4MainRenderEncoderBridge(
                encoder: encoder,
                lease: lease,
                vertexArguments: tables.0,
                fragmentArguments: tables.1
            )
            if !fullRegion {
                guard let pipeline = ensureClearColorDepthPipeline(
                    colorTexture.device,
                    colorTexture.pixelFormat,
                    depthTexture.pixelFormat
                ), let depthState = ensureDepthStencilState(
                    device: colorTexture.device,
                    compareOp: .always,
                    writeDepth: true
                ), encodeClearDrawMetal4(
                    bridge: bridge,
                    lease: lease,
                    pipeline: pipeline,
                    textureWidth: textureWidth,
                    textureHeight: textureHeight,
                    clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
                    scissorRect: scissorRect,
                    depthState: depthState,
                    clearDepth: clearDepth
                ) else {
                    encoder.endEncoding()
                    return
                }
            }
            encoder.endEncoding()
            return
        }

        let commandBuffer = metal3CommandBuffer(pointer)

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = colorTexture
        renderPass.colorAttachments[0].loadAction = fullRegion ? .clear : .load
        renderPass.colorAttachments[0].clearColor = makeClearColor(red: clearColorRed, green: clearColorGreen, blue: clearColorBlue, alpha: clearColorAlpha)
        renderPass.colorAttachments[0].storeAction = .store

        renderPass.depthAttachment.texture = depthTexture
        renderPass.depthAttachment.loadAction = fullRegion ? .clear : .load
        renderPass.depthAttachment.clearDepth = clearDepth
        renderPass.depthAttachment.storeAction = .store

        let depthFormat = depthTexture.pixelFormat
        let isStencilFormat: Bool = {
            #if os(macOS)
            return depthFormat == .depth24Unorm_stencil8 || depthFormat == .depth32Float_stencil8
            #else
            return depthFormat == .depth32Float_stencil8
            #endif
        }()
        if isStencilFormat {
            renderPass.stencilAttachment.texture = depthTexture
            renderPass.stencilAttachment.loadAction = .dontCare
            renderPass.stencilAttachment.storeAction = .dontCare
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

        metal4BarrierRenderAfterRender(encoder)
        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        if !fullRegion {
            guard
                let pipeline = ensureClearColorDepthPipeline(commandBuffer.device, colorTexture.pixelFormat, depthTexture.pixelFormat),
                let depthState = ensureDepthStencilState(device: commandBuffer.device, compareOp: MTLCompareFunction.always, writeDepth: true)
            else {
                encoder.endEncoding()
                return
            }
            encodeClearDraw(
                encoder: encoder,
                pipeline: pipeline,
                textureWidth: textureWidth,
                textureHeight: textureHeight,
                clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
                scissorRect: scissorRect,
                depthState: depthState,
                clearDepth: clearDepth
            )
        }

        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }

        encoder.endEncoding()
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_clearDraw")
public func metallum_MTLRenderCommandEncoder_clearDraw(
    _ pointer: UnsafeMutableRawPointer,
    _ colorTexture: MTLTexture?,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColorEnabled: Int32,
    _ clearColorRed: Float,
    _ clearColorGreen: Float,
    _ clearColorBlue: Float,
    _ clearColorAlpha: Float,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
) {
    autoreleasepool {
        guard let device = colorTexture?.device ?? depthTexture?.device else {
            return
        }
        let colorFormat = colorTexture?.pixelFormat ?? .invalid
        let depthFormat = depthTexture?.pixelFormat ?? .invalid
        let writeColor = clearColorEnabled != 0

        guard let pipeline = ensureClearColorDepthPipeline(device, colorFormat, depthFormat, writeColor) else {
            return
        }

        let depthState: MTLDepthStencilState?
        if depthFormat != .invalid {
            depthState = ensureDepthStencilState(device: device, compareOp: .always, writeDepth: clearDepthEnabled != 0)
        } else {
            depthState = nil
        }

        let width = colorTexture?.width ?? depthTexture?.width ?? 0
        let height = colorTexture?.height ?? depthTexture?.height ?? 0
        guard width > 0, height > 0 else {
            return
        }

        if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
            _ = encodeClearDrawMetal4(
                bridge: bridge,
                lease: bridge.lease,
                pipeline: pipeline,
                textureWidth: Int(viewportWidth),
                textureHeight: Int(viewportHeight),
                clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
                scissorRect: MTLScissorRect(x: 0, y: 0, width: width, height: height),
                depthState: depthState,
                clearDepth: clearDepth
            )
            return
        }
        let encoder = metal3RenderEncoder(pointer)
        encodeClearDraw(
            encoder: encoder,
            pipeline: pipeline,
            textureWidth: Int(viewportWidth),
            textureHeight: Int(viewportHeight),
            clearColor: SIMD4<Float>(clearColorRed, clearColorGreen, clearColorBlue, clearColorAlpha),
            scissorRect: MTLScissorRect(x: 0, y: 0, width: width, height: height),
            depthState: depthState,
            clearDepth: clearDepth
        )
    }
}

@_cdecl("metallum_configure_layer")
public func metallum_configure_layer(_ layer: CAMetalLayer, _ width: Double, _ height: Double, _ immediatePresentMode: Int32) {
    layer.pixelFormat = .bgra8Unorm
    layer.drawableSize = CGSize(width: width, height: height)
    // Present command buffers directly through CAMetalLayer. Leaving this at
    // the default makes presentation depend on an unrelated Core Animation
    // transaction boundary, which can add an extra frame of latency and make
    // the drawable appear to alternate during resize or focus changes.
    layer.presentsWithTransaction = false
    #if os(macOS)
    NativeState.immediatePresentModeRequested = immediatePresentMode != 0
    var presenterOwnsLayerPolicy = false
    #if canImport(MetalFX)
    if #available(macOS 26.0, *), let presenter = NativeState.frameGenerationPresenter {
        // While the frame-generation presenter owns the layer it also owns
        // allowsNextDrawableTimeout and displaySyncEnabled: writing them from the
        // render thread here races the present the display link is committing,
        // and this function historically undid the presenter's own timeout
        // setting on every resize. Defer to the presenter, which restates them
        // after its next present.
        presenter.requestLayerPolicyRefresh()
        presenterOwnsLayerPolicy = true
    }
    #endif
    if !presenterOwnsLayerPolicy {
        layer.allowsNextDrawableTimeout = false
        layer.displaySyncEnabled = immediatePresentMode == 0
    }
    #elseif os(iOS)
    // iOS: use allowsNextDrawableTimeout = true to prevent silent frame
    // drops when all drawables are in-flight. The host UIView owns the
    // CAMetalLayer (it IS view.layer), and the drawable pool is small
    // (3 drawables). With MAX_SUBMITS_IN_FLIGHT=3, racing between
    // command-buffer completions and drawable recycling can exhaust the
    // pool, causing nextDrawable() to return nil (black frame).
    layer.allowsNextDrawableTimeout = true
    // The CAMetalLayer IS view.layer (see metallum_ios_get_view_metal_layer):
    // the host UIView owns the layer's frame and updates it on layout /
    // rotation. We must NOT touch layer.frame here — doing so would fight
    // the view's layout pass and could leave the layer with the wrong frame.
    // The renderable size is governed by `drawableSize` above, which is what
    // Metal actually cares about.
    //
    // (The legacy sublayer fallback in metallum_ios_get_view_metal_layer sets
    // newLayer.frame = view.bounds at attach time; we accept that it will not
    // auto-resize if the view is later laid out larger.)
    #endif
}

@_cdecl("metallum_MTLCommandBuffer_encodePresentTextureToDrawable")
public func metallum_MTLCommandBuffer_encodePresentTextureToDrawable(
    _ pointer: UnsafeMutableRawPointer,
    _ layer: CAMetalLayer,
    _ sourceTexture: MTLTexture,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard let drawable: CAMetalDrawable = layer.nextDrawable() else {
            NSLog("[Metallum] WARNING: nextDrawable() returned nil (drawableSize=\(layer.drawableSize), frame=\(layer.frame), isOpaque=\(layer.isOpaque), device=\(layer.device != nil ? "set" : "nil"))")
            return
        }

        if #available(macOS 26.0, iOS 26.0, *), let lease = metal4MainLease(pointer) {
            let renderPass = MTL4RenderPassDescriptor()
            renderPass.colorAttachments[0].texture = drawable.texture
            renderPass.colorAttachments[0].loadAction = .dontCare
            renderPass.colorAttachments[0].storeAction = .store
            renderPass.renderTargetWidth = drawable.texture.width
            renderPass.renderTargetHeight = drawable.texture.height
            guard let encoder = lease.commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
                return
            }
            encoder.barrier(
                afterQueueStages: [.fragment, .dispatch, .blit],
                beforeStages: .fragment,
                visibilityOptions: .device
            )
            encoder.setViewport(MTLViewport(
                originX: 0.0, originY: 0.0,
                width: Double(drawable.texture.width),
                height: Double(drawable.texture.height),
                znear: 0.0, zfar: 1.0
            ))
            encoder.setRenderPipelineState(NativeState.presentPipeline)
            let tables = lease.owner.argumentTables(at: lease.slotIndex)
            tables.1.setTexture(sourceTexture.gpuResourceID, index: 0)
            let requiresScaling = sourceTexture.width != drawable.texture.width ||
                                  sourceTexture.height != drawable.texture.height
            guard let sampler = requiresScaling
                    ? NativeState.presentLinearSampler
                    : NativeState.presentNearestSampler else {
                encoder.endEncoding()
                return
            }
            tables.1.setSamplerState(sampler.gpuResourceID, index: 0)
            encoder.setArgumentTable(tables.1, stages: MTLRenderStages.fragment)
            encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
            encoder.endEncoding()
            lease.presentDrawable = drawable
            return
        }

        let commandBuffer = metal3CommandBuffer(pointer)

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

        metal4BarrierRenderAfterRender(encoder)
        if let globalFence {
            encoder.waitForFence(globalFence, before: .fragment)
        }

        encoder.setViewport(MTLViewport(
            originX: 0.0,
            originY: 0.0,
            width: Double(drawable.texture.width),
            height: Double(drawable.texture.height),
            znear: 0.0,
            zfar: 1.0
        ))

        encoder.setRenderPipelineState(NativeState.presentPipeline)
        encoder.setFragmentTexture(sourceTexture, index: 0)

        let requiresScaling = sourceTexture.width != drawable.texture.width ||
                              sourceTexture.height != drawable.texture.height

        let sampler = requiresScaling ? NativeState.presentLinearSampler : NativeState.presentNearestSampler
        encoder.setFragmentSamplerState(sampler, index: 0)

        encoder.drawPrimitives(
            type: .triangle,
            vertexStart: 0,
            vertexCount: 3
        )

        // Without this update the next frame's first writer of the sampled
        // texture has no GPU edge to this read: fence waits only order
        // against encoders that signaled the fence, and cross-command-buffer
        // WAR hazards on untracked resources are otherwise unordered.
        if let globalFence {
            encoder.updateFence(globalFence, after: .fragment)
        }
        encoder.endEncoding()
        commandBuffer.present(drawable)
        #if os(iOS)
        CATransaction.flush()
        #endif
    }
}

@_cdecl("metallum_set_transfer_fence")
public func metallum_set_transfer_fence(_ fence: MTLFence?) {
    NativeState.transferFence = fence
}

@_cdecl("metallum_create_fence")
public func metallum_create_fence(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeFence())
    }
}

@_cdecl("MTLRenderCommandEncoder_updateFence")
public func MTLRenderCommandEncoder_updateFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    if #available(macOS 26.0, iOS 26.0, *), metal4RenderBridge(pointer) != nil { return }
    let encoder = metal3RenderEncoder(pointer)
    encoder.updateFence(fence, after: stages)
}

@_cdecl("MTLRenderCommandEncoder_waitForFence")
public func MTLRenderCommandEncoder_waitForFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    if #available(macOS 26.0, iOS 26.0, *), metal4RenderBridge(pointer) != nil { return }
    let encoder = metal3RenderEncoder(pointer)
    encoder.waitForFence(fence, before: stages)
}

@_cdecl("MTLBlitCommandEncoder_updateFence")
public func MTLBlitCommandEncoder_updateFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence
) {
    if #available(macOS 26.0, iOS 26.0, *), metal4BlitBridge(pointer) != nil { return }
    let encoder = metal3BlitEncoder(pointer)
    encoder.updateFence(fence)
}

@_cdecl("MTLBlitCommandEncoder_waitForFence")
public func MTLBlitCommandEncoder_waitForFence(
    _ pointer: UnsafeMutableRawPointer,
    _ fence: MTLFence
) {
    if #available(macOS 26.0, iOS 26.0, *), metal4BlitBridge(pointer) != nil { return }
    let encoder = metal3BlitEncoder(pointer)
    encoder.waitForFence(fence)
}

// MARK: - Generic compute / mipmap / compare-sampler ABI (Iris backend B0)
//
// Vanilla Blaze3D 26.2 has no compute, storage-resource, mipmap-generation or
// depth-compare-sampler concepts, so these exports are mod-private extensions
// consumed by the Java layer through optional FFM downcalls. Compute encoders
// participate in the same single-MTLFence hazard chain as render/blit encoders
// (resources are allocated untracked): the Java owner must waitForFence on
// begin and updateFence on end, exactly like MetalCommandEncoder does for the
// other encoder kinds.

@_cdecl("metallum_MTLCommandBuffer_makeComputeCommandEncoder")
public func metallum_MTLCommandBuffer_makeComputeCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(commandBuffer.makeComputeCommandEncoder())
    }
}

@_cdecl("metallum_MTLComputeCommandEncoder_setComputePipelineState")
public func metallum_MTLComputeCommandEncoder_setComputePipelineState(
    _ encoder: MTLComputeCommandEncoder,
    _ pipelineState: MTLComputePipelineState
) {
    encoder.setComputePipelineState(pipelineState)
}

@_cdecl("metallum_MTLComputeCommandEncoder_setBuffer")
public func metallum_MTLComputeCommandEncoder_setBuffer(
    _ encoder: MTLComputeCommandEncoder,
    _ buffer: MTLBuffer?,
    _ offset: Int,
    _ index: Int32
) {
    encoder.setBuffer(buffer, offset: offset, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_setTexture")
public func metallum_MTLComputeCommandEncoder_setTexture(
    _ encoder: MTLComputeCommandEncoder,
    _ texture: MTLTexture?,
    _ index: Int32
) {
    encoder.setTexture(texture, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_setSamplerState")
public func metallum_MTLComputeCommandEncoder_setSamplerState(
    _ encoder: MTLComputeCommandEncoder,
    _ sampler: MTLSamplerState?,
    _ index: Int32
) {
    encoder.setSamplerState(sampler, index: Int(index))
}

@_cdecl("metallum_MTLComputeCommandEncoder_dispatchThreadgroups")
public func metallum_MTLComputeCommandEncoder_dispatchThreadgroups(
    _ encoder: MTLComputeCommandEncoder,
    _ groupsX: Int32,
    _ groupsY: Int32,
    _ groupsZ: Int32,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    encoder.dispatchThreadgroups(
        MTLSize(width: Int(groupsX), height: Int(groupsY), depth: Int(groupsZ)),
        threadsPerThreadgroup: MTLSize(
            width: Int(threadsPerGroupX),
            height: Int(threadsPerGroupY),
            depth: Int(threadsPerGroupZ)
        )
    )
}

@_cdecl("metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect")
public func metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
    _ encoder: MTLComputeCommandEncoder,
    _ indirectBuffer: MTLBuffer,
    _ indirectOffset: Int,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    encoder.dispatchThreadgroups(
        indirectBuffer: indirectBuffer,
        indirectBufferOffset: indirectOffset,
        threadsPerThreadgroup: MTLSize(
            width: Int(threadsPerGroupX),
            height: Int(threadsPerGroupY),
            depth: Int(threadsPerGroupZ)
        )
    )
}

@_cdecl("metallum_MTLComputeCommandEncoder_updateFence")
public func metallum_MTLComputeCommandEncoder_updateFence(
    _ encoder: MTLComputeCommandEncoder,
    _ fence: MTLFence
) {
    encoder.updateFence(fence)
}

@_cdecl("metallum_MTLComputeCommandEncoder_waitForFence")
public func metallum_MTLComputeCommandEncoder_waitForFence(
    _ encoder: MTLComputeCommandEncoder,
    _ fence: MTLFence
) {
    encoder.waitForFence(fence)
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
    _ encoder: MTLBlitCommandEncoder,
    _ texture: MTLTexture
) {
    encoder.generateMipmaps(for: texture)
}

// Sampler creation with an optional depth-compare function. compareFunction
// receives the MTLCompareFunction raw value, or -1 for an ordinary sampler.
// Compare samplers additionally force normalized coordinates and are intended
// for shadow2D-style lookups (MSL sample_compare).
@_cdecl("metallum_create_sampler_v2")
public func metallum_create_sampler_v2(
    _ device: MTLDevice,
    _ addressModeU: MTLSamplerAddressMode,
    _ addressModeV: MTLSamplerAddressMode,
    _ minFilter: MTLSamplerMinMagFilter,
    _ magFilter: MTLSamplerMinMagFilter,
    _ mipFilter: MTLSamplerMipFilter,
    _ maxAnisotropy: Int32,
    _ lodMaxClamp: Double,
    _ compareFunction: Int32
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
        descriptor.sAddressMode = addressModeU
        descriptor.tAddressMode = addressModeV
        descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
        descriptor.lodMinClamp = 0.0
        descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
        if compareFunction >= 0, let compare = MTLCompareFunction(rawValue: UInt(compareFunction)) {
            descriptor.compareFunction = compare
        }
        return retainedPointer(device.makeSamplerState(descriptor: descriptor))
    }
}

/// Resolves a depth attachment that was created with storeAction=.unknown
/// (deferred store mode). Only legal on encoders whose descriptor deferred
/// the decision; the Java side tracks that invariant.
@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStoreAction")
public func metallum_MTLRenderCommandEncoder_setDepthStoreAction(
    _ pointer: UnsafeMutableRawPointer,
    _ store: Int32
) {
    if #available(macOS 26.0, iOS 26.0, *), let bridge = metal4RenderBridge(pointer) {
        bridge.encoder.setDepthStoreAction(store != 0 ? .store : .dontCare)
        return
    }
    let encoder = metal3RenderEncoder(pointer)
    encoder.setDepthStoreAction(store != 0 ? .store : .dontCare)
}

@_cdecl("metallum_set_deferred_depth_store")
public func metallum_set_deferred_depth_store(_ enabled: Int32) {
    NativeState.deferredDepthStore = enabled != 0
}

/// Routes render pipeline creation through MTL4Compiler (migration spec M2).
/// Java only calls this with 1 when the capability gate and
/// metallum.opt.metal4Compiler both hold; 0 (the default) leaves every PSO on
/// the Metal 3 path.
@_cdecl("metallum_set_metal4_compiler_enabled")
public func metallum_set_metal4_compiler_enabled(_ enabled: Int32) {
    NativeState.metal4CompilerEnabled = enabled != 0
}

/// Routes the frame-generation present thread onto a Metal 4 queue (spec M4).
/// Java only passes 1 when the capability gate, metallum.opt.metal4Compiler and
/// metallum.opt.metal4Present all hold; the presenter still falls back to Metal 3
/// on its own if any Metal 4 object cannot be built.
/// Appends the barrier map's consumer barriers to the existing Metal 3 encoders
/// (spec M6-B). Independent of the metal4 master gate, like the residency set:
/// barrierAfterQueueStages:beforeStages: is gated on macOS 26, not on Metal 4
/// family support. Appending can only strengthen ordering, so with this on the
/// output must stay byte-identical.
@_cdecl("metallum_set_metal4_barrier_enabled")
public func metallum_set_metal4_barrier_enabled(_ enabled: Int32) {
    NativeState.metal4BarrierEnabled = enabled != 0
}

@_cdecl("metallum_set_gpu_encoder_timing_enabled")
public func metallum_set_gpu_encoder_timing_enabled(_ enabled: Int32) {
    NativeState.gpuEncoderTimingEnabled = enabled != 0
}

@_cdecl("metallum_gpu_encoder_timing_reset")
public func metallum_gpu_encoder_timing_reset() {
    gpuEncoderTimingLock.lock()
    completedGpuEncoderTimings.removeAll(keepingCapacity: true)
    gpuEncoderTimingLock.unlock()
}

@_cdecl("metallum_gpu_encoder_timing_count")
public func metallum_gpu_encoder_timing_count() -> Int32 {
    gpuEncoderTimingLock.lock()
    defer { gpuEncoderTimingLock.unlock() }
    return Int32(min(completedGpuEncoderTimings.count, Int(Int32.max)))
}

@_cdecl("metallum_gpu_encoder_timing_milliseconds")
public func metallum_gpu_encoder_timing_milliseconds(_ index: Int32) -> Double {
    gpuEncoderTimingLock.lock()
    defer { gpuEncoderTimingLock.unlock() }
    let offset = Int(index)
    guard offset >= 0, offset < completedGpuEncoderTimings.count else { return 0.0 }
    return completedGpuEncoderTimings[offset].milliseconds
}

@_cdecl("metallum_gpu_encoder_timing_kind")
public func metallum_gpu_encoder_timing_kind(_ index: Int32) -> Int32 {
    gpuEncoderTimingLock.lock()
    defer { gpuEncoderTimingLock.unlock() }
    let offset = Int(index)
    guard offset >= 0, offset < completedGpuEncoderTimings.count else { return -1 }
    return completedGpuEncoderTimings[offset].kind
}

@_cdecl("metallum_gpu_encoder_timing_copy_label")
public func metallum_gpu_encoder_timing_copy_label(
    _ index: Int32,
    _ output: UnsafeMutablePointer<CChar>?,
    _ capacity: Int64
) -> Int32 {
    guard let output, capacity > 0 else { return 1 }
    gpuEncoderTimingLock.lock()
    let offset = Int(index)
    guard offset >= 0, offset < completedGpuEncoderTimings.count else {
        gpuEncoderTimingLock.unlock()
        output[0] = 0
        return 1
    }
    let label = completedGpuEncoderTimings[offset].label
    gpuEncoderTimingLock.unlock()
    let bytes = Array(label.utf8.prefix(Int(capacity - 1)))
    for byteIndex in 0..<bytes.count {
        output[byteIndex] = CChar(bitPattern: bytes[byteIndex])
    }
    output[bytes.count] = 0
    return 0
}

@_cdecl("metallum_set_metal4_present_enabled")
public func metallum_set_metal4_present_enabled(_ enabled: Int32) {
    NativeState.metal4PresentEnabled = enabled != 0
}

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    autoreleasepool {
        guard let obj else { return }
        // Residency bookkeeping happens here rather than at destruction-queue
        // enqueue time: this is the point the Java side has already deferred past
        // every submit that could still be reading the resource (S1 made the
        // queue depth in-flight+1), so the set never loses an allocation the GPU
        // is still using.
        residencyTrackReleased(obj)
        Unmanaged<AnyObject>.fromOpaque(obj).release()
    }
}

@_cdecl("metallum_get_buffer_contents")
public func metallum_get_buffer_contents(_ buffer: MTLBuffer) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        buffer.contents()
    }
}

@_cdecl("metallum_MTLVertexDescriptor_create")
public func metallum_MTLVertexDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLVertexDescriptor())
}

@_cdecl("metallum_MTLVertexDescriptor_setAttribute")
public func metallum_MTLVertexDescriptor_setAttribute(
    _ desc: MTLVertexDescriptor,
    _ index: Int,
    _ format: MTLVertexFormat,
    _ offset: Int,
    _ bufferIndex: Int
) {
    autoreleasepool {
        desc.attributes[index].format = format
        desc.attributes[index].offset = offset
        desc.attributes[index].bufferIndex = bufferIndex
    }
}

@_cdecl("metallum_MTLVertexDescriptor_setLayout")
public func metallum_MTLVertexDescriptor_setLayout(
    _ desc: MTLVertexDescriptor,
    _ bufferIndex: Int,
    _ stride: Int,
    _ stepFunction: MTLVertexStepFunction,
    _ stepRate: Int
) {
    autoreleasepool {
        desc.layouts[bufferIndex].stride = stride
        desc.layouts[bufferIndex].stepFunction = stepFunction
        desc.layouts[bufferIndex].stepRate = stepRate
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_create")
public func metallum_MTLRenderPipelineDescriptor_create() -> UnsafeMutableRawPointer? {
    retainedPointer(MTLRenderPipelineDescriptor())
}

@_cdecl("metallum_create_shader_function")
public func metallum_create_shader_function(
    _ device: MTLDevice,
    _ sourcePtr: UnsafePointer<CChar>?,
    _ entryPtr: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        guard let sourcePtr, let entryPtr else {
            return nil
        }
        do {
            let library = try device.makeLibrary(source: String(cString: sourcePtr), options: nil)
            guard let function = library.makeFunction(name: String(cString: entryPtr)) else {
                NSLog("[metallum] Failed to resolve MSL entry point '%s'", entryPtr)
                return nil
            }
            // Metal 4 needs the library back when it builds a pipeline from this
            // function (MTL4LibraryFunctionDescriptor), and MTLFunction does not
            // carry it. Registering unconditionally keeps the Metal 3 and Metal 4
            // paths from disagreeing when the switch is flipped mid-session; the
            // table is weak-keyed, so the cost is one entry per live function.
            NativeState.register(function: function, library: library)
            return retainedPointer(function)
        } catch {
            NSLog("[metallum] Failed to compile MSL: %@", String(describing: error))
            return nil
        }
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions")
public func metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexFunction: MTLFunction,
    _ fragmentFunction: MTLFunction
) {
    desc.vertexFunction = vertexFunction
    desc.fragmentFunction = fragmentFunction
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor")
public func metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
    _ desc: MTLRenderPipelineDescriptor,
    _ vertexDesc: MTLVertexDescriptor
) {
    desc.vertexDescriptor = vertexDesc
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats")
public func metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
    _ desc: MTLRenderPipelineDescriptor,
    _ colorFormat: MTLPixelFormat,
    _ depthFormat: MTLPixelFormat,
    _ stencilFormat: MTLPixelFormat
) {
    autoreleasepool {
        desc.colorAttachments[0].pixelFormat = colorFormat
        if depthFormat != .invalid {
            desc.depthAttachmentPixelFormat = depthFormat
        }
        if stencilFormat != .invalid {
            desc.stencilAttachmentPixelFormat = stencilFormat
        }
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat")
public func metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat(
    _ desc: MTLRenderPipelineDescriptor,
    _ index: Int32,
    _ format: MTLPixelFormat
) -> Int32 {
    guard index >= 0 && index < 8 else {
        NSLog("[Metallum] rejected color attachment format index %d", index)
        return 0
    }
    guard let attachment = desc.colorAttachments[Int(index)] else {
        NSLog("[Metallum] color attachment descriptor %d is unavailable", index)
        return 0
    }
    attachment.pixelFormat = format
    return 1
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats")
public func metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats(
    _ desc: MTLRenderPipelineDescriptor,
    _ depthFormat: MTLPixelFormat,
    _ stencilFormat: MTLPixelFormat
) {
    // A fresh descriptor already represents "no attachment". Explicitly
    // assigning MTLPixelFormat.invalid trips Metal GPU Validation on current
    // macOS SDKs even though the resulting value is otherwise identical.
    if depthFormat != .invalid {
        desc.depthAttachmentPixelFormat = depthFormat
    }
    if stencilFormat != .invalid {
        desc.stencilAttachmentPixelFormat = stencilFormat
    }
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState")
public func metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState(
    _ desc: MTLRenderPipelineDescriptor,
    _ index: Int32,
    _ enabled: Int32,
    _ srcRgb: MTLBlendFactor,
    _ dstRgb: MTLBlendFactor,
    _ opRgb: MTLBlendOperation,
    _ srcAlpha: MTLBlendFactor,
    _ dstAlpha: MTLBlendFactor,
    _ opAlpha: MTLBlendOperation,
    _ writeMask: MTLColorWriteMask
) -> Int32 {
    guard index >= 0 && index < 8 else {
        NSLog("[Metallum] rejected color attachment blend-state index %d", index)
        return 0
    }

    guard let attachment = desc.colorAttachments[Int(index)] else {
        NSLog("[Metallum] color attachment descriptor %d is unavailable", index)
        return 0
    }
    attachment.writeMask = writeMask
    attachment.isBlendingEnabled = enabled != 0
    if enabled != 0 {
        attachment.sourceRGBBlendFactor = srcRgb
        attachment.destinationRGBBlendFactor = dstRgb
        attachment.rgbBlendOperation = opRgb
        attachment.sourceAlphaBlendFactor = srcAlpha
        attachment.destinationAlphaBlendFactor = dstAlpha
        attachment.alphaBlendOperation = opAlpha
    }
    return 1
}

@_cdecl("metallum_MTLRenderPipelineDescriptor_setBlendState")
public func metallum_MTLRenderPipelineDescriptor_setBlendState(
    _ desc: MTLRenderPipelineDescriptor,
    _ enabled: Int32,
    _ srcRgb: MTLBlendFactor,
    _ dstRgb: MTLBlendFactor,
    _ opRgb: MTLBlendOperation,
    _ srcAlpha: MTLBlendFactor,
    _ dstAlpha: MTLBlendFactor,
    _ opAlpha: MTLBlendOperation,
    _ writeMask: MTLColorWriteMask
) {
    autoreleasepool {
        desc.colorAttachments[0].writeMask = writeMask
        if enabled != 0 {
            desc.colorAttachments[0].isBlendingEnabled = true
            desc.colorAttachments[0].sourceRGBBlendFactor = srcRgb
            desc.colorAttachments[0].destinationRGBBlendFactor = dstRgb
            desc.colorAttachments[0].rgbBlendOperation = opRgb
            desc.colorAttachments[0].sourceAlphaBlendFactor = srcAlpha
            desc.colorAttachments[0].destinationAlphaBlendFactor = dstAlpha
            desc.colorAttachments[0].alphaBlendOperation = opAlpha
        } else {
            desc.colorAttachments[0].isBlendingEnabled = false
        }
    }
}

private func descriptorHasLiveColorWrite(_ descriptor: MTLRenderPipelineDescriptor) -> Bool {
    for index in 0..<8 {
        guard let attachment = descriptor.colorAttachments[index] else { continue }
        if attachment.pixelFormat != .invalid && !attachment.writeMask.isEmpty {
            return true
        }
    }
    return false
}

// MARK: - Appended queue barriers (migration spec M6-B)

/// Raw MTLStages bits. Kept as plain integers so the call sites below need no
/// #available: MTLStages itself is a macOS 26 symbol and cannot appear in an
/// unversioned signature. Values verified bit-for-bit against MTLCommandEncoder.h,
/// and identical to the low bits of the project's existing mtl/MTLRenderStages
/// values, which is what lets the Java `long stages` ABI be reused later.
private enum Metal4Stage {
    static let vertex: UInt = 1 << 0
    static let fragment: UInt = 1 << 1
    static let tile: UInt = 1 << 2
    static let dispatch: UInt = 1 << 27
    static let blit: UInt = 1 << 28
}

/// Appends the consumer barrier from docs/metal4-barrier-map.md to an ordinary
/// *Metal 3* encoder.
///
/// This is the M6-B validation vehicle, and it works because macOS 26 added
/// `barrierAfterQueueStages:beforeStages:` to the Metal 3 base protocol
/// MTLCommandEncoder (no visibilityOptions — that is MTL4-only). Appending is
/// strictly stronger than the existing fence chain: it can only add ordering, never
/// remove any. So with the switch on, rendering must be byte-identical. If it is
/// not, the barrier map's stage pairs are wrong, and finding that out here is far
/// cheaper than finding it out inside M7e where the fences are gone and there is
/// nothing left to compare against.
///
/// Deliberately independent of the metal4 master gate: this API is gated on the OS
/// version, not on MTLGPUFamily.metal4, exactly like the residency set in M3.
private func metal4AppendConsumerBarrier(_ encoder: MTLCommandEncoder, after: UInt, before: UInt) {
    guard NativeState.metal4BarrierEnabled else { return }
    if #available(macOS 26.0, iOS 26.0, *) {
        encoder.barrier(
            afterQueueStages: MTLStages(rawValue: after),
            beforeStages: MTLStages(rawValue: before)
        )
    }
}

/// Consumer barrier for a compute pass that reads what render passes wrote.
/// Covers E4/E5/E6/E7 in the barrier map.
private func metal4BarrierComputeAfterRender(_ encoder: MTLComputeCommandEncoder) {
    metal4AppendConsumerBarrier(encoder, after: Metal4Stage.fragment, before: Metal4Stage.dispatch)
}

/// Consumer barrier for a compute pass that reads another compute pass's output.
/// The only such edge is E9, the merge encoder reading the camera encoder.
private func metal4BarrierComputeAfterCompute(_ encoder: MTLComputeCommandEncoder) {
    metal4AppendConsumerBarrier(encoder, after: Metal4Stage.dispatch, before: Metal4Stage.dispatch)
}

/// Consumer barrier for a copy that reads what render passes wrote (E10/E12).
private func metal4BarrierBlitAfterRender(_ encoder: MTLBlitCommandEncoder) {
    metal4AppendConsumerBarrier(encoder, after: Metal4Stage.fragment, before: Metal4Stage.blit)
}

/// Consumer barrier for a render pass that samples or loads an upstream target
/// (E11/E15/E16).
private func metal4BarrierRenderAfterRender(_ encoder: MTLRenderCommandEncoder) {
    metal4AppendConsumerBarrier(encoder, after: Metal4Stage.fragment, before: Metal4Stage.fragment)
}

/// Consumer barrier for the Java-driven render encoders, whose single Metal 3 fence
/// covers both uploads and upstream targets, so the stage masks are the union
/// (E13/E14). One barrier with combined masks, not two — each barrier is its own
/// cache flush.
private func metal4BarrierRenderAfterUploadAndRender(_ encoder: MTLRenderCommandEncoder) {
    metal4AppendConsumerBarrier(
        encoder,
        after: Metal4Stage.blit | Metal4Stage.fragment,
        before: Metal4Stage.vertex | Metal4Stage.fragment
    )
}

// MARK: - CPU wait for GPU completion (migration spec M7g)

/// Metal 4 replacement for MTLCommandBuffer.waitUntilCompleted, which neither
/// MTL4CommandQueue nor MTL4CommandBuffer has.
///
/// The queue signals `event` to `value` after everything already committed, so
/// waiting for that value is waiting for those submits. Returns 1 when the value
/// was reached, 0 on timeout — the same contract as the Metal 3 export, whose
/// caller (Encoder.awaitSubmitCompletion) implements glClientWaitSync semantics
/// and must be able to distinguish a timeout from completion. The GL semantics
/// and the implicit flush that S10 corrected live on the Java side and are not
/// touched here; only the waiting mechanism changes.
///
/// Each submit needs its own increasing value, paired one-to-one with the
/// existing submitIndex, or a later wait would be satisfied by an earlier submit.
@available(macOS 26.0, iOS 26.0, *)
func metal4WaitForCompletion(
    queue: MTL4CommandQueue,
    event: MTLSharedEvent,
    value: UInt64,
    timeoutMs: UInt64
) -> Int32 {
    queue.signalEvent(event, value: value)
    return event.wait(untilSignaledValue: value, timeoutMS: timeoutMs) ? 1 : 0
}

// MARK: - Bump allocator (migration spec M5)

/// Per-frame linear allocator that replaces set*Bytes, which Metal 4 removed
/// entirely. Uniforms are copied into a shared-storage buffer and bound by GPU
/// address through an argument table (`setAddress(_:index:)`) instead of being
/// handed to an encoder.
///
/// Sizing is measured, not guessed. The seven remaining set*Bytes sites push:
///   ClearUniforms 48 B (the only vertex one, and the only one that can repeat
///   many times per frame — once per clear), CutoutReactiveUniforms 32 B,
///   HandOverlayUniforms 16 B, SIMD2<UInt32> 8 B, TransparencyMaskUniforms 48 B,
///   MergeUniforms 48 B, and MotionUniforms 240 B, the largest (three 4x4
///   matrices plus three vectors).
/// Every one of those has an alignment requirement of at most 16 B, so the 16 B
/// default below covers them; `allocate` still takes an alignment so a future
/// uniform with a stricter requirement cannot silently be under-aligned.
///
/// Metal 3's 4 KB set*Bytes ceiling does not apply here. That is a side effect,
/// not an invitation: the uniform-caching invariants from S11 still hold.
/// Running out of room cannot fall back to a Metal 3 binding: there is no
/// set*Bytes anywhere on the MTL4 encoders (the whole family is absent from the
/// SDK headers), so a nil here would mean the draw runs with no uniform at all.
/// The arena therefore grows instead, by chaining another chunk.
///
/// Growth is a plain `makeBuffer` rather than a transient block put through the
/// destruction queue, specifically to protect M3's one rule: a new chunk only
/// marks the residency set dirty, and the single batched `commit()` still happens
/// once per submit. Routing growth through transient blocks would force a
/// residency `commit()` per overflow, and `commit()` is the one expensive
/// operation in the residency design.
@available(macOS 26.0, iOS 26.0, *)
final class Metal4BumpAllocator {
    private let device: MTLDevice
    private let chunkCapacity: Int
    private let label: String
    /// Chunk 0 is allocated up front; later chunks appear only if a frame overflows
    /// and are then kept for reuse, so a frame that overflows once pays for the
    /// allocation once rather than every frame.
    private var chunks: [MTLBuffer] = []
    private var bases: [UnsafeMutableRawPointer] = []
    private var chunkIndex = 0
    private var cursor: Int = 0

    init?(device: MTLDevice, capacity: Int, label: String) {
        self.device = device
        self.chunkCapacity = capacity
        self.label = label
        guard appendChunk() else { return nil }
    }

    @discardableResult
    private func appendChunk() -> Bool {
        guard let buffer = device.makeBuffer(length: chunkCapacity, options: [.storageModeShared]) else {
            return false
        }
        buffer.label = "\(label)-chunk\(chunks.count)"
        chunks.append(buffer)
        bases.append(buffer.contents())
        // The GPU reads this by address, so it has to be resident: Metal 4 does no
        // automatic residency and an address into a non-resident buffer is a read
        // of unmapped memory. This only sets the dirty flag; the commit stays
        // batched to once per submit.
        residencyTrackCreated(buffer)
        return true
    }

    /// Chunk 0. Allocation after a reset always starts here.
    var primaryBacking: MTLBuffer { chunks[0] }

    /// Every chunk, for callers that must make the whole arena resident on a queue
    /// of their own.
    var allBackings: [MTLBuffer] { chunks }

    /// Bytes handed out since the last reset, across chunks. Capacity tuning input:
    /// if this regularly exceeds one chunk, raise the chunk size instead of paying
    /// for chaining every frame.
    private(set) var peakUsage: Int = 0

    /// Called at frame start, and only for the allocator belonging to a frame that
    /// is no longer in flight — the ring is what guarantees that. Resetting an
    /// allocator whose frame the GPU is still reading would let the next frame
    /// overwrite live uniform data. Chunks are kept, only the cursor rewinds.
    func reset() {
        chunkIndex = 0
        cursor = 0
        usedThisFrame = 0
    }

    private var usedThisFrame = 0

    /// Copies `length` bytes in and returns the GPU address to bind.
    ///
    /// Nil is returned only when `length` exceeds a whole chunk, which no uniform
    /// in this project comes close to (the largest is MotionUniforms at 240 B) and
    /// which chaining cannot fix. Ordinary exhaustion grows the arena instead.
    func allocate(bytes: UnsafeRawPointer, length: Int, alignment: Int = 16) -> MTLGPUAddress? {
        let effectiveAlignment = max(16, alignment)
        guard length <= chunkCapacity else { return nil }
        var aligned = (cursor + effectiveAlignment - 1) & ~(effectiveAlignment - 1)
        if aligned + length > chunkCapacity {
            // Current chunk is full: move to the next one, allocating it if this is
            // the first frame to need it.
            if chunkIndex + 1 >= chunks.count {
                guard appendChunk() else { return nil }
            }
            chunkIndex += 1
            cursor = 0
            aligned = 0
        }
        bases[chunkIndex].advanced(by: aligned).copyMemory(from: bytes, byteCount: length)
        cursor = aligned + length
        usedThisFrame += length
        peakUsage = max(peakUsage, usedThisFrame)
        return chunks[chunkIndex].gpuAddress + UInt64(aligned)
    }

    /// Chunks currently held, for diagnostics: more than one means some frame
    /// overflowed the primary chunk.
    var chunkCount: Int { chunks.count }
}

/// One bump allocator per in-flight frame, rotated at frame start.
///
/// The depth is MAX_SUBMITS_IN_FLIGHT + 1 = 4, matching the destruction queue
/// depth S1 established, and for the same reason: an allocator may only be reset
/// once every submit that could still be reading it has completed. A single
/// shared allocator would overwrite uniforms the GPU is still fetching, and the
/// symptom would be intermittently wrong uniform values rather than a crash.
@available(macOS 26.0, iOS 26.0, *)
final class Metal4BumpAllocatorRing {
    /// MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT (3) + 1.
    static let depth = 4
    /// 240 B largest uniform, and the clear path can allocate once per clear; 64 KiB
    /// leaves room for ~270 largest-case allocations per frame, far above any
    /// observed frame, at a total cost of 256 KiB across the ring. Exceeding it
    /// chains another chunk rather than failing, so this is a "how often do we pay
    /// for a second chunk" knob, not a correctness bound.
    static let capacityPerFrame = 64 * 1024

    private var allocators: [Metal4BumpAllocator] = []
    private var frameIndex = 0
    private var overflowLogged = false

    init?(device: MTLDevice) {
        for index in 0..<Self.depth {
            guard let allocator = Metal4BumpAllocator(
                device: device,
                capacity: Self.capacityPerFrame,
                label: "metallum-uniform-bump-\(index)"
            ) else {
                return nil
            }
            allocators.append(allocator)
        }
    }

    /// Rotates to the next frame's allocator and clears it. Call once per frame,
    /// before any allocation for that frame.
    func beginFrame() -> Metal4BumpAllocator {
        let allocator = allocators[frameIndex % allocators.count]
        frameIndex += 1
        allocator.reset()
        return allocator
    }

    var current: Metal4BumpAllocator {
        allocators[(frameIndex + allocators.count - 1) % allocators.count]
    }

    /// Reports the first chunk chain only. Not an error — the arena grew and the
    /// frame is correct — but it means the chunk size is undersized for real
    /// frames, which is worth knowing because every such frame allocates.
    func logGrowthOnce(chunkCount: Int) {
        guard !overflowLogged else { return }
        overflowLogged = true
        NSLog(
            "[metallum] uniform bump allocator chained a chunk (now %ld x %ld B); consider raising capacityPerFrame",
            chunkCount,
            Self.capacityPerFrame
        )
    }

    /// A single allocation larger than one whole chunk cannot be served by chaining.
    /// No uniform in this project is close (largest is 240 B), so this is a
    /// programming error rather than a capacity problem.
    func logOversizedOnce(_ length: Int) {
        guard !overflowLogged else { return }
        overflowLogged = true
        NSLog(
            "[metallum] uniform of %ld B exceeds the %ld B bump chunk; binding skipped",
            length,
            Self.capacityPerFrame
        )
    }

    var peakUsage: Int {
        allocators.reduce(0) { max($0, $1.peakUsage) }
    }
}

// MARK: - Residency set (migration spec M3)

/// Adds a freshly created resource to the residency set, if one is active.
/// Memoryless textures are excluded: they have no backing allocation, so adding
/// them is invalid.
@available(macOS 15.0, iOS 18.0, *)
private func residencyAdd(_ resource: MTLResource) {
    NativeState.residencyLock.lock()
    defer { NativeState.residencyLock.unlock() }
    guard let set = NativeState.residencySetStorage as? MTLResidencySet else { return }
    if let texture = resource as? MTLTexture, texture.storageMode == .memoryless {
        return
    }
    set.addAllocation(resource)
    NativeState.residencyDirty = true
}

/// Drops a resource from the residency set. Called from the release path, which
/// the Java destruction queue already defers past the frames still in flight.
@available(macOS 15.0, iOS 18.0, *)
private func residencyRemove(_ resource: MTLResource) {
    NativeState.residencyLock.lock()
    defer { NativeState.residencyLock.unlock() }
    guard let set = NativeState.residencySetStorage as? MTLResidencySet else { return }
    set.removeAllocation(resource)
    NativeState.residencyDirty = true
}

/// Publishes pending additions and removals. commit() is expensive, so it runs
/// at most once per submit — this is the one performance trap of residency sets.
/// requestResidency() is persistent and only needs the first commit.
@available(macOS 15.0, iOS 18.0, *)
private func residencyCommitIfDirty() {
    NativeState.residencyLock.lock()
    defer { NativeState.residencyLock.unlock() }
    guard NativeState.residencyDirty,
          let set = NativeState.residencySetStorage as? MTLResidencySet else { return }
    set.commit()
    NativeState.residencyDirty = false
    if !NativeState.residencyRequested {
        set.requestResidency()
        NativeState.residencyRequested = true
    }
}

/// Version-erased entry points so the call sites stay free of #available noise.
private func residencyTrackCreated(_ resource: MTLResource?) {
    guard let resource, NativeState.residencySetStorage != nil else { return }
    if #available(macOS 15.0, iOS 18.0, *) {
        residencyAdd(resource)
    }
}

/// Takes the raw pointer rather than the object: this runs for every native
/// object release, and with no residency set active it must cost one nil check
/// and nothing else — materializing an AnyObject here would add an ARC
/// retain/release per release on a per-frame-hot path.
private func residencyTrackReleased(_ pointer: UnsafeMutableRawPointer) {
    guard NativeState.residencySetStorage != nil else { return }
    if #available(macOS 15.0, iOS 18.0, *) {
        guard let resource = Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? MTLResource else {
            return
        }
        residencyRemove(resource)
    }
}

private func residencyFlushBeforeSubmit() {
    guard NativeState.residencySetStorage != nil else { return }
    if #available(macOS 15.0, iOS 18.0, *) {
        residencyCommitIfDirty()
    }
}

/// Creates the residency set and attaches it to `queue`, plus the layer's own
/// read-only set when a layer is available (it tracks drawables automatically,
/// so nothing is ever added to it by hand). Returns 1 on success.
@_cdecl("metallum_residency_set_enable")
public func metallum_residency_set_enable(_ device: MTLDevice, _ queue: MTLCommandQueue) -> Int32 {
    return autoreleasepool {
        guard #available(macOS 15.0, iOS 18.0, *) else { return 0 }
        NativeState.residencyLock.lock()
        defer { NativeState.residencyLock.unlock() }
        if NativeState.residencySetStorage != nil { return 1 }
        let descriptor = MTLResidencySetDescriptor()
        descriptor.label = "metallum-residency"
        descriptor.initialCapacity = 1024
        guard let set = try? device.makeResidencySet(descriptor: descriptor) else {
            NSLog("[metallum] residency set creation failed; staying on automatic residency")
            return 0
        }
        NativeState.residencySetStorage = set
        NativeState.residencyDirty = false
        NativeState.residencyRequested = false
        queue.addResidencySet(set)
        NSLog("[metallum] residency set attached to the main command queue")
        return 1
    }
}

/// Reports how much the residency set currently pins: the number of tracked
/// allocations and their total size in bytes. Returns 0 when no set is active.
/// This is the measurement M3 is accepted against (resident footprint must not
/// move materially versus automatic residency), and it is how a run can tell an
/// empty set from a populated one.
@_cdecl("metallum_residency_set_stats")
public func metallum_residency_set_stats(
    _ outAllocations: UnsafeMutablePointer<UInt32>?,
    _ outBytes: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    return autoreleasepool {
        guard #available(macOS 15.0, iOS 18.0, *) else { return 0 }
        NativeState.residencyLock.lock()
        defer { NativeState.residencyLock.unlock() }
        guard let set = NativeState.residencySetStorage as? MTLResidencySet else { return 0 }
        outAllocations?.pointee = UInt32(set.allAllocations.count)
        outBytes?.pointee = UInt64(set.allocatedSize)
        return 1
    }
}

/// The Metal 4 pipeline data set lives beside the Metal 3 binary archive rather
/// than in it. Java passes one path and its ABI does not change; the two caches
/// are simply different formats written by different APIs
/// (MTLBinaryArchive.serialize vs MTL4PipelineDataSetSerializer), so sharing one
/// file would mean each launch that flips metallum.opt.metal4Compiler discards
/// the other mode's cache. Separate files keep both warm.
private func metal4ArchiveURL(forBinaryArchivePath path: String) -> URL {
    URL(fileURLWithPath: path).deletingPathExtension().appendingPathExtension("mtl4archive")
}

/// Translates the Metal 3 pipeline descriptor the Java side has already filled
/// in into its Metal 4 equivalent, or nil when the translation cannot be made
/// (in which case the caller keeps the Metal 3 path).
///
/// Two fields deliberately have no counterpart: depth/stencil attachment
/// formats do not exist on MTL4RenderPipelineDescriptor at all — the render pass
/// supplies them — so the depth dimension of the variant matrix disappears here.
/// binaryArchives has no counterpart either; MTL4 uses
/// MTL4CompilerTaskOptions.lookupArchives instead.
@available(macOS 26.0, iOS 26.0, *)
private func makeMetal4Descriptor(_ src: MTLRenderPipelineDescriptor) -> MTL4RenderPipelineDescriptor? {
    guard let vertexFunction = src.vertexFunction,
          let vertexLibrary = NativeState.library(for: vertexFunction) else {
        return nil
    }
    let dst = MTL4RenderPipelineDescriptor()
    dst.label = src.label
    let vfd = MTL4LibraryFunctionDescriptor()
    vfd.library = vertexLibrary
    vfd.name = vertexFunction.name
    dst.vertexFunctionDescriptor = vfd
    if let fragmentFunction = src.fragmentFunction,
       let fragmentLibrary = NativeState.library(for: fragmentFunction) {
        let ffd = MTL4LibraryFunctionDescriptor()
        ffd.library = fragmentLibrary
        ffd.name = fragmentFunction.name
        dst.fragmentFunctionDescriptor = ffd
    } else if src.fragmentFunction != nil {
        // A fragment function whose library is not in the side table: give up on
        // the Metal 4 path rather than compile a pipeline missing a stage.
        return nil
    }
    dst.vertexDescriptor = src.vertexDescriptor
    dst.rasterSampleCount = src.rasterSampleCount
    dst.inputPrimitiveTopology = src.inputPrimitiveTopology
    dst.alphaToCoverageState = src.isAlphaToCoverageEnabled ? .enabled : .disabled
    dst.alphaToOneState = src.isAlphaToOneEnabled ? .enabled : .disabled
    dst.isRasterizationEnabled = src.isRasterizationEnabled
    dst.maxVertexAmplificationCount = src.maxVertexAmplificationCount
    for index in 0..<8 {
        guard let s = src.colorAttachments[index], let d = dst.colorAttachments[index] else { continue }
        d.pixelFormat = s.pixelFormat
        d.writeMask = s.writeMask
        d.blendingState = s.isBlendingEnabled ? .enabled : .disabled
        if s.isBlendingEnabled {
            d.sourceRGBBlendFactor = s.sourceRGBBlendFactor
            d.destinationRGBBlendFactor = s.destinationRGBBlendFactor
            d.rgbBlendOperation = s.rgbBlendOperation
            d.sourceAlphaBlendFactor = s.sourceAlphaBlendFactor
            d.destinationAlphaBlendFactor = s.destinationAlphaBlendFactor
            d.alphaBlendOperation = s.alphaBlendOperation
        }
    }
    return dst
}

/// Opens (or creates) the on-disk PSO binary archive. Existing file is loaded
/// so previously harvested pipelines skip the Metal compiler; a corrupt file
/// is deleted and replaced with an empty archive.
@_cdecl("metallum_pso_archive_open")
public func metallum_pso_archive_open(
    _ device: MTLDevice,
    _ pathPtr: UnsafePointer<CChar>?
) -> Int32 {
    return autoreleasepool {
        guard let pathPtr else { return 0 }
        // Metal 4 path (migration spec M2c). MTL4PipelineDataSetSerializer has no
        // equivalent of MTLBinaryArchive's "an archive loaded from disk can never
        // be re-serialized" defect, so there is no read-only mode here: every
        // flush writes, including the ones triggered by resource reloads.
        if NativeState.metal4CompilerEnabled, #available(macOS 26.0, iOS 26.0, *) {
            let url = metal4ArchiveURL(forBinaryArchivePath: String(cString: pathPtr))
            let device = device
            NativeState.metal4CompilerLock.lock()
            let serializerDescriptor = MTL4PipelineDataSetSerializerDescriptor()
            // .captureBinaries, not .captureDescriptors: the configuration is an
            // options mask that selects which serializer method is usable, and
            // serializeAsArchiveAndFlush(url:) needs binaries. With
            // .captureDescriptors only, the flush throws (nilError) and the
            // pipeline cache silently never lands — .captureDescriptors pairs with
            // serializeAsPipelinesScript(), which is for offline metal-tt builds.
            serializerDescriptor.configuration = .captureBinaries
            NativeState.metal4Serializer = device.makePipelineDataSetSerializer(descriptor: serializerDescriptor)
            // The serializer is only collected through the compiler it was
            // attached to at creation. Drop any compiler built before this point
            // so it is rebuilt with the serializer; otherwise a single pipeline
            // created ahead of the archive opening would silently disable
            // archiving for the whole session.
            NativeState.metal4CompilerStorage = nil
            // Previous launch's archive, if any, becomes the compiler lookup set.
            // Absent or unreadable simply means a cold start.
            if FileManager.default.fileExists(atPath: url.path) {
                if let archive = try? device.makeArchive(url: url) {
                    NativeState.metal4LookupArchive = archive
                } else {
                    NSLog("[metallum] Metal 4 pipeline archive unreadable, rebuilding")
                    try? FileManager.default.removeItem(at: url)
                }
            }
            let loaded = NativeState.metal4LookupArchive != nil
            NativeState.metal4CompilerLock.unlock()
            NSLog("[metallum] Metal 4 pipeline data set opened (lookup archive: %@)", loaded ? "yes" : "cold")
            return 1
        }
        let url = URL(fileURLWithPath: String(cString: pathPtr))
        let descriptor = MTLBinaryArchiveDescriptor()
        let loadedFromDisk = FileManager.default.fileExists(atPath: url.path)
        if loadedFromDisk {
            descriptor.url = url
        }
        do {
            NativeState.binaryArchive = try device.makeBinaryArchive(descriptor: descriptor)
            NativeState.binaryArchiveReadOnly = loadedFromDisk
            if loadedFromDisk {
                NSLog("[metallum] PSO binary archive loaded (read-only lookup mode)")
            }
            return 1
        } catch {
            NSLog("[metallum] PSO binary archive open failed, rebuilding: %@", String(describing: error))
            try? FileManager.default.removeItem(at: url)
            descriptor.url = nil
            NativeState.binaryArchive = try? device.makeBinaryArchive(descriptor: descriptor)
            NativeState.binaryArchiveReadOnly = false
            return NativeState.binaryArchive != nil ? 1 : 0
        }
    }
}

@_cdecl("metallum_pso_archive_flush")
public func metallum_pso_archive_flush(_ pathPtr: UnsafePointer<CChar>?) -> Int32 {
    return autoreleasepool {
        guard let pathPtr else { return 0 }
        if #available(macOS 26.0, iOS 26.0, *),
           let serializer = NativeState.metal4Serializer as? MTL4PipelineDataSetSerializer {
            let url = metal4ArchiveURL(forBinaryArchivePath: String(cString: pathPtr))
            NativeState.metal4CompilerLock.lock()
            defer { NativeState.metal4CompilerLock.unlock() }
            do {
                try serializer.serializeAsArchiveAndFlush(url: url)
                return 1
            } catch {
                NSLog("[metallum] Metal 4 pipeline data set flush failed: %@", String(describing: error))
                return 0
            }
        }
        guard let archive = NativeState.binaryArchive else { return 0 }
        if NativeState.binaryArchiveReadOnly {
            // Loaded archives cannot be re-serialized on current macOS; the
            // on-disk file from the launch that built it stays authoritative.
            return 1
        }
        NativeState.binaryArchiveLock.lock()
        defer { NativeState.binaryArchiveLock.unlock() }
        do {
            try archive.serialize(to: URL(fileURLWithPath: String(cString: pathPtr)))
            return 1
        } catch {
            // Known failure mode: the AOT pack step can reject individual
            // harvested pipelines (e.g. "expecting 'fragment' stage in
            // pipeline no. N"). Serialization is all-or-nothing, so disable
            // the archive for the rest of the session instead of failing the
            // same way on every later flush (resource reloads flush too).
            NSLog("[metallum] PSO binary archive flush failed; disabling archive for this session: %@", String(describing: error))
            NativeState.binaryArchive = nil
            return 0
        }
    }
}

@_cdecl("metallum_MTLDevice_makeRenderPipelineState")
public func metallum_MTLDevice_makeRenderPipelineState(
    _ device: MTLDevice,
    _ descriptor: MTLRenderPipelineDescriptor
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        if ProcessInfo.processInfo.environment["METALLUM_MRT_ABI_DEBUG"] == "1" {
            let colorFormats = (0..<8)
                .map { String(descriptor.colorAttachments[$0].pixelFormat.rawValue) }
                .joined(separator: ",")
            NSLog(
                "[Metallum] MRT PSO descriptor colors=[%@] depth=%lu stencil=%lu",
                colorFormats,
                descriptor.depthAttachmentPixelFormat.rawValue,
                descriptor.stencilAttachmentPixelFormat.rawValue
            )
        }
        #if os(macOS)
        if (descriptor.depthAttachmentPixelFormat == .depth24Unorm_stencil8
            || descriptor.stencilAttachmentPixelFormat == .depth24Unorm_stencil8)
            && !device.isDepth24Stencil8PixelFormatSupported {
                return nil
            }
        #endif
        // Metal 4 path (migration spec M2b). MTL4Compiler returns an ordinary
        // MTLRenderPipelineState that binds to the existing Metal 3 encoders
        // (proved by metal4PipelineSmokeTest), so this needs no encoder changes.
        // Any failure — no compiler, an untranslatable descriptor, a compile
        // error — falls through to the unchanged Metal 3 path below.
        if NativeState.metal4CompilerEnabled, #available(macOS 26.0, iOS 26.0, *) {
            if let compiler = NativeState.metal4Compiler(device),
               let metal4Descriptor = makeMetal4Descriptor(descriptor) {
                do {
                    // lookupArchives is Metal 4's replacement for
                    // descriptor.binaryArchives: last launch's compiled pipelines
                    // are found here instead of being recompiled. The serializer
                    // attached to the compiler collects this launch's, and
                    // metallum_pso_archive_flush writes them back.
                    let state: MTLRenderPipelineState
                    if let archive = NativeState.metal4LookupArchive as? MTL4Archive {
                        let options = MTL4CompilerTaskOptions()
                        options.lookupArchives = [archive]
                        state = try compiler.makeRenderPipelineState(
                            descriptor: metal4Descriptor,
                            compilerTaskOptions: options
                        )
                    } else {
                        state = try compiler.makeRenderPipelineState(descriptor: metal4Descriptor)
                    }
                    if !NativeState.metal4PipelineLogged {
                        NativeState.metal4PipelineLogged = true
                        NSLog("[metallum] Metal 4 pipeline path engaged (MTL4Compiler)")
                    }
                    return retainedPointer(state)
                } catch {
                    NativeState.logMetal4PipelineFallback(
                        "MTL4Compiler rejected the descriptor: \(String(describing: error))"
                    )
                }
            } else {
                NativeState.logMetal4PipelineFallback("no compiler, or descriptor not translatable")
            }
        }
        if let archive = NativeState.binaryArchive {
            descriptor.binaryArchives = [archive]
        }
        do {
            let state = try device.makeRenderPipelineState(descriptor: descriptor)
            // Harvest for the next launch; failure only means this PSO is
            // not archived, never a pipeline creation failure. Serialize()
            // rejects entries whose fragment stage the AOT packer stripped
            // ("expecting 'fragment' stage in pipeline no. N"), and one bad
            // entry poisons the whole archive, so only harvest pipelines
            // with a fragment function and at least one live color write.
            if let archive = NativeState.binaryArchive,
               !NativeState.binaryArchiveReadOnly,
               descriptor.fragmentFunction != nil,
               descriptorHasLiveColorWrite(descriptor) {
                NativeState.binaryArchiveLock.lock()
                try? archive.addRenderPipelineFunctions(descriptor: descriptor)
                NativeState.binaryArchiveLock.unlock()
            }
            return retainedPointer(state)
        } catch {
            NSLog("[metallum] Failed to create render pipeline state: %@", String(describing: error))
            return nil
        }
    }
}
