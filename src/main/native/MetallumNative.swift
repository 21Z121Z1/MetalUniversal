import Foundation
#if os(macOS)
import AppKit
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

private enum NativeState {
    static var debugLabelsEnabled = false
    static var depthStencilStates: [DepthStencilKey: MTLDepthStencilState] = [:]
    static var clearPipelines: [PipelineVariantKey: MTLRenderPipelineState] = [:]
    static var presentPipeline: MTLRenderPipelineState!
    static var presentNearestSampler: MTLSamplerState!
    static var presentLinearSampler: MTLSamplerState!
    static var copyPipelines: [Int: MTLRenderPipelineState] = [:]
    #if os(macOS) && canImport(MetalFX)
    static var metalFxScalers: [String: AnyObject] = [:]
    static var metalFxPreviousDepthTextures: [String: MTLTexture] = [:]
    static var metalFxPreviousDepthValid: Set<String> = []
    static let metalFxHistoryLock = NSLock()
    static var motionPipeline: MTLComputePipelineState?
    static var motionV2Pipeline: MTLComputePipelineState?
    static var motionMergePipeline: MTLComputePipelineState?
    static var motionClearPipeline: MTLComputePipelineState?
    static var transparencyMaskPipeline: MTLComputePipelineState?
    static var cutoutReactivePipeline: MTLComputePipelineState?
    static var metalFxFailureKeys: Set<String> = []
    static var frameGenerationLogged = false
    @available(macOS 26.0, *)
    static var frameGenerationPresenter: MetalFrameGenerationPresenter?
    #endif
}

#if os(macOS) && canImport(MetalFX)
@available(macOS 26.0, *)
struct MetalFrameGenerationDiagnosticSnapshot {
    let sourceFrameID: UInt64
    let frameKind: String
    let displayUpdateID: UInt64
    let targetTimestamp: CFTimeInterval
    let targetPresentationTimestamp: CFTimeInterval
    let cpuCommitTime: CFTimeInterval
    let gpuCompletionTime: CFTimeInterval
    let presentedTime: CFTimeInterval
    let outcome: String
}

@available(macOS 26.0, *)
final class MetalFrameGenerationPresenter: NSObject, CAMetalDisplayLinkDelegate {
    private struct PendingFrame {
        let sourceFrameID: UInt64
        let index: Int
        let eventValue: UInt64
        let timestamp: CFTimeInterval
        let inputWidth: Int
        let inputHeight: Int
        let jitterX: Float
        let jitterY: Float
        let fieldOfView: Float
        let nearPlane: Float
        let farPlane: Float
        let aspectRatio: Float
        let reset: Bool
    }

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
        var gpuCompletionTime: CFTimeInterval
        var presentedTime: CFTimeInterval
        var outcome: String
    }

    private struct TextureSet {
        let scene: [MTLTexture]
        let composed: [MTLTexture]
        let depth: [MTLTexture]
        let motion: [MTLTexture]
        let interpolation: [MTLTexture]
    }

    private static let bufferCount = 3
    // Source frames remain pinned until the real drawable reports its presented
    // boundary. Keeping one source frame in flight also prevents a later frame
    // from overtaking the real/interpolated pair in WindowServer.
    private static let maxOutstandingFrames = 1
    private static let diagnosticCapacity = 256
    private static let presentationCallbackTimeout: CFTimeInterval = 0.25
    private static let displayUpdateStarvationTimeout: CFTimeInterval = 0.75

    private let device: MTLDevice
    private let layer: CAMetalLayer
    private let presentQueue: MTLCommandQueue
    private let readyEvent: MTLSharedEvent
    private var frameInterpolator: any MTLFXFrameInterpolator
    private var copyPipeline: MTLRenderPipelineState
    private var copySampler: MTLSamplerState
    private var copyFormat: MTLPixelFormat

    private var sceneBuffers: [MTLTexture] = []
    private var composedBuffers: [MTLTexture] = []
    private var depthBuffers: [MTLTexture] = []
    private var motionBuffers: [MTLTexture] = []
    private var interpolationOutputs: [MTLTexture] = []

    private var outputWidth: Int
    private var outputHeight: Int
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
    private var pendingDisplayUpdate: DisplayUpdate?
    private var currentFrame: PendingFrame?
    private var currentLifecycle: MetalFrameGenerationLifecycle?
    private var activePreviousIndex: Int?
    private var activeShouldResetHistory = true
    private var activeDeltaTime: Float = 1.0 / 60.0
    private var interpolatorEncodeHistoryValid = false
    private var displayHistoryValid = false
    private var realPresentationTimeoutAt: CFTimeInterval?
    private var displayUpdateStarvationTimeoutAt: CFTimeInterval?
    private var diagnostics: [FrameDiagnostic] = []
    private var diagnosticsDumped = false
    private var droppedDisplayUpdates = 0
    private var presentationDeadlineMisses = 0

    private let condition = NSCondition()
    private var outstandingFrames = 0
    private var stopping = false
    private var workerExited = false
    private var worker: Thread?

    init?(
        device: MTLDevice,
        layer: CAMetalLayer,
        sceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) {
        guard let presentQueue = device.makeCommandQueue(),
              let readyEvent = device.makeSharedEvent(),
              let copyPipeline = buildPresentPipeline(device: device, colorFormat: layer.pixelFormat),
              let copySampler = buildPresentSampler(device: device, filter: .linear),
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
        self.copySampler = copySampler
        self.copyFormat = layer.pixelFormat
        self.outputWidth = sceneColor.width
        self.outputHeight = sceneColor.height
        self.outputFormat = sceneColor.pixelFormat
        self.depthFormat = depth.pixelFormat
        self.motionFormat = motion.pixelFormat
        layer.maximumDrawableCount = 3
        // A hidden or minimized window may not recycle drawables promptly.
        // Let the present thread time out and fall back to the rendered frame
        // instead of blocking shutdown or the next resize forever.
        layer.allowsNextDrawableTimeout = true
        presentQueue.label = "MetalFX Frame Generation Present"
        readyEvent.label = "MetalFX Frame Generation Ready"
        super.init()

        guard rebuildTextures(
            outputWidth: sceneColor.width,
            outputHeight: sceneColor.height,
            outputFormat: sceneColor.pixelFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat,
            depthWidth: depth.width,
            depthHeight: depth.height,
            motionWidth: motion.width,
            motionHeight: motion.height
        ) else {
            return nil
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
        descriptor.uiTextureFormat = uiColor.pixelFormat
        descriptor.inputWidth = depth.width
        descriptor.inputHeight = depth.height
        descriptor.outputWidth = sceneColor.width
        descriptor.outputHeight = sceneColor.height
        return descriptor.makeFrameInterpolator(device: device)
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
        outputFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        motionFormat: MTLPixelFormat,
        depthWidth: Int,
        depthHeight: Int,
        motionWidth: Int,
        motionHeight: Int
    ) -> TextureSet? {
        guard outputWidth > 0, outputHeight > 0 else {
            return nil
        }

        let colorUsage: MTLTextureUsage = [.shaderRead, .shaderWrite, .renderTarget]
        let depthUsage: MTLTextureUsage = [.shaderRead, .renderTarget]
        let motionUsage: MTLTextureUsage = [.shaderRead, .shaderWrite, .renderTarget]
        var newScene: [MTLTexture] = []
        var newComposed: [MTLTexture] = []
        var newDepth: [MTLTexture] = []
        var newMotion: [MTLTexture] = []
        var newInterpolation: [MTLTexture] = []

        for index in 0..<Self.bufferCount {
            guard let scene = makeTexture(
                pixelFormat: outputFormat,
                width: outputWidth,
                height: outputHeight,
                usage: colorUsage,
                label: "Frame Generation Scene \(index)"
            ), let composed = makeTexture(
                pixelFormat: outputFormat,
                width: outputWidth,
                height: outputHeight,
                usage: colorUsage,
                label: "Frame Generation UI \(index)"
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
            newComposed.append(composed)
            newDepth.append(depth)
            newMotion.append(motion)
            guard let interpolation = makeTexture(
                pixelFormat: outputFormat,
                width: outputWidth,
                height: outputHeight,
                usage: colorUsage,
                label: "Frame Generation Interpolation \(index)"
            ) else {
                return nil
            }
            newInterpolation.append(interpolation)
        }

        return TextureSet(
            scene: newScene,
            composed: newComposed,
            depth: newDepth,
            motion: newMotion,
            interpolation: newInterpolation
        )
    }

    private func installTextureSet(
        _ textureSet: TextureSet,
        outputWidth: Int,
        outputHeight: Int,
        outputFormat: MTLPixelFormat,
        depthFormat: MTLPixelFormat,
        motionFormat: MTLPixelFormat
    ) {
        self.outputWidth = outputWidth
        self.outputHeight = outputHeight
        self.outputFormat = outputFormat
        self.depthFormat = depthFormat
        self.motionFormat = motionFormat
        self.sceneBuffers = textureSet.scene
        self.composedBuffers = textureSet.composed
        self.depthBuffers = textureSet.depth
        self.motionBuffers = textureSet.motion
        self.interpolationOutputs = textureSet.interpolation
    }

    private func rebuildTextures(
        outputWidth: Int,
        outputHeight: Int,
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
            outputFormat: outputFormat,
            depthFormat: depthFormat,
            motionFormat: motionFormat
        )
        return true
    }

    private func resizeResources(
        outputWidth: Int,
        outputHeight: Int,
        outputFormat: MTLPixelFormat,
        depth: MTLTexture,
        motion: MTLTexture
    ) -> Bool {
        cancelAndDrain(reason: "resize")
        guard let textureSet = makeTextureSet(
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            outputFormat: outputFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat,
            depthWidth: depth.width,
            depthHeight: depth.height,
            motionWidth: motion.width,
            motionHeight: motion.height
        ), let newInterpolator = Self.makeFrameInterpolator(
            device: device,
            sceneColor: textureSet.scene[0],
            uiColor: textureSet.composed[0],
            depth: textureSet.depth[0],
            motion: textureSet.motion[0]
        ), let newCopyPipeline = buildPresentPipeline(device: device, colorFormat: layer.pixelFormat) else {
            return false
        }
        installTextureSet(
            textureSet,
            outputWidth: outputWidth,
            outputHeight: outputHeight,
            outputFormat: outputFormat,
            depthFormat: depth.pixelFormat,
            motionFormat: motion.pixelFormat
        )
        self.frameInterpolator = newInterpolator
        self.copyPipeline = newCopyPipeline
        self.copyFormat = layer.pixelFormat
        self.nextBufferIndex = 0
        self.lastPresentedIndex = nil
        self.lastPresentedTimestamp = nil
        self.interpolatorEncodeHistoryValid = false
        self.displayHistoryValid = false
        return true
    }

    func encode(
        commandBuffer: MTLCommandBuffer,
        sceneColor: MTLTexture,
        uiColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture,
        jitterX: Float,
        jitterY: Float,
        fieldOfView: Float,
        nearPlane: Float,
        farPlane: Float,
        aspectRatio: Float,
        reset: Bool,
        globalFence: MTLFence?
    ) -> Int32 {
        _ = globalFence
        guard sceneColor.width > 0, sceneColor.height > 0,
              depth.width > 0, depth.height > 0,
              sceneColor.width == uiColor.width, sceneColor.height == uiColor.height,
              sceneColor.pixelFormat == uiColor.pixelFormat,
              depth.width == motion.width, depth.height == motion.height else {
            return 0
        }

        if sceneColor.width != outputWidth || sceneColor.height != outputHeight
                || sceneColor.pixelFormat != outputFormat
                || depth.pixelFormat != depthFormat || motion.pixelFormat != motionFormat
                || depthBuffers.first?.width != depth.width || depthBuffers.first?.height != depth.height
                || motionBuffers.first?.width != motion.width || motionBuffers.first?.height != motion.height
                || layer.pixelFormat != copyFormat {
            guard resizeResources(
                outputWidth: sceneColor.width,
                outputHeight: sceneColor.height,
                outputFormat: sceneColor.pixelFormat,
                depth: depth,
                motion: motion
            ) else {
                return 0
            }
        }

        condition.lock()
        while outstandingFrames >= Self.maxOutstandingFrames && !stopping {
            condition.wait()
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
        outstandingFrames += 1
        condition.unlock()

        guard let blit = commandBuffer.makeBlitCommandEncoder() else {
            completeFrame()
            return 0
        }
        blit.label = "Frame Generation Input Copies"
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
            from: uiColor,
            sourceSlice: 0,
            sourceLevel: 0,
            to: composedBuffers[index],
            destinationSlice: 0,
            destinationLevel: 0,
            sliceCount: 1,
            levelCount: 1
        )
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
        blit.endEncoding()
        commandBuffer.encodeSignalEvent(readyEvent, value: eventValue)

        let frame = PendingFrame(
            sourceFrameID: sourceFrameID,
            index: index,
            eventValue: eventValue,
            timestamp: timestamp,
            inputWidth: depth.width,
            inputHeight: depth.height,
            jitterX: jitterX,
            jitterY: jitterY,
            fieldOfView: fieldOfView,
            nearPlane: nearPlane,
            farPlane: farPlane,
            aspectRatio: aspectRatio,
            reset: reset
        )

        condition.lock()
        var lifecycle = MetalFrameGenerationLifecycle(sourceFrameID: sourceFrameID)
        _ = lifecycle.submitInput()
        currentFrame = frame
        currentLifecycle = lifecycle
        displayUpdateStarvationTimeoutAt = timestamp + Self.displayUpdateStarvationTimeout
        condition.signal()
        condition.unlock()

        commandBuffer.addCompletedHandler { [weak self] completed in
            self?.handleInputCommandBufferCompletion(
                eventValue: eventValue,
                succeeded: completed.status == .completed,
                error: completed.error
            )
        }
        return 1
    }

    private func handleInputCommandBufferCompletion(
        eventValue: UInt64,
        succeeded: Bool,
        error: Error?
    ) {
        condition.lock()
        guard currentFrame?.eventValue == eventValue, var lifecycle = currentLifecycle else {
            condition.unlock()
            return
        }
        let actions = lifecycle.completeGPUWork(
            .input,
            succeeded: succeeded,
            reason: succeeded ? nil : "input command buffer failed: \(String(describing: error))"
        )
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: eventValue)
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

    private func encodeCopy(commandBuffer: MTLCommandBuffer, source: MTLTexture, destination: MTLTexture, label: String) -> Bool {
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
        encoder.setRenderPipelineState(copyPipeline)
        encoder.setFragmentTexture(source, index: 0)
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
        link.preferredFrameLatency = 1.0
        link.add(to: RunLoop.current, forMode: .default)
        displayLink = link
        return true
    }

    func metalDisplayLink(_ link: CAMetalDisplayLink, needsUpdate update: CAMetalDisplayLink.Update) {
        let targetTimestamp = update.targetTimestamp
        let targetPresentationTimestamp = update.targetPresentationTimestamp
        condition.lock()
        defer {
            condition.unlock()
        }
        guard !stopping,
              targetTimestamp.isFinite, targetTimestamp > 0.0,
              targetPresentationTimestamp.isFinite, targetPresentationTimestamp > 0.0 else {
            return
        }
        let updateID = nextDisplayUpdateID
        nextDisplayUpdateID += 1
        if let superseded = pendingDisplayUpdate {
            droppedDisplayUpdates += 1
            appendDiagnosticLocked(
                sourceFrameID: currentFrame?.sourceFrameID ?? 0,
                frameKind: "unassigned",
                update: superseded,
                outcome: "dropped:superseded"
            )
        }
        pendingDisplayUpdate = DisplayUpdate(
            updateID: updateID,
            drawable: update.drawable,
            targetTimestamp: targetTimestamp,
            targetPresentationTimestamp: targetPresentationTimestamp
        )
        condition.signal()
    }

    private func nextPresentationWork() -> PresentationWork? {
        condition.lock()
        defer {
            condition.unlock()
        }
        guard !stopping else {
            return nil
        }

        let now = CACurrentMediaTime()
        expireRealPresentationLocked(now: now)
        expireDisplayUpdateStarvationLocked(now: now)
        if let update = pendingDisplayUpdate, update.targetTimestamp <= now {
            pendingDisplayUpdate = nil
            droppedDisplayUpdates += 1
            presentationDeadlineMisses += 1
            appendDiagnosticLocked(
                sourceFrameID: currentFrame?.sourceFrameID ?? 0,
                frameKind: "unassigned",
                update: update,
                outcome: "dropped:stale-deadline"
            )
        }

        guard let frame = currentFrame, var lifecycle = currentLifecycle else {
            return nil
        }
        if !lifecycle.activated {
            let hasInterpolation = !frame.reset && displayHistoryValid && lastPresentedIndex != nil
            guard lifecycle.activate(hasInterpolation: hasInterpolation) else {
                return nil
            }
            activePreviousIndex = lastPresentedIndex
            activeShouldResetHistory = frame.reset
                    || !interpolatorEncodeHistoryValid
                    || !displayHistoryValid
            activeDeltaTime = {
                guard !activeShouldResetHistory, let previousTimestamp = lastPresentedTimestamp else {
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

        guard let step = lifecycle.nextPresentationStep,
              let update = pendingDisplayUpdate else {
            return nil
        }
        pendingDisplayUpdate = nil
        let previousIndex = activePreviousIndex ?? frame.index
        return PresentationWork(
            frame: frame,
            update: update,
            step: step,
            previousIndex: previousIndex,
            shouldResetHistory: activeShouldResetHistory,
            deltaTime: activeDeltaTime
        )
    }

    private func runWorker() {
        guard installDisplayLink() else {
            condition.lock()
            cancelCurrentSourceLocked(reason: "display link installation failed")
            workerExited = true
            condition.broadcast()
            condition.unlock()
            logMetalFxFailureOnce(
                "frame-generation-display-link",
                "CAMetalDisplayLink is unavailable; frame generation is disabled"
            )
            return
        }

        let runLoop = RunLoop.current
        while true {
            if let work = nextPresentationWork() {
                present(work)
                continue
            }
            condition.lock()
            let shouldStop = stopping
            let canExit = shouldStop && outstandingFrames == 0
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
            frameInterpolator.uiTexture = composedBuffers[frame.index]
            frameInterpolator.outputTexture = interpolationOutputs[frame.index]
            frameInterpolator.isUITextureComposited = true
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
            guard encodeCopy(
                commandBuffer: commandBuffer,
                source: interpolationOutputs[frame.index],
                destination: work.update.drawable.texture,
                label: "Frame Generation Interpolation Copy"
            ) else {
                failPresentationBeforeSubmission(work, reason: "interpolated copy encoder unavailable")
                return
            }
        } else {
            guard encodeCopy(
                commandBuffer: commandBuffer,
                source: composedBuffers[frame.index],
                destination: work.update.drawable.texture,
                label: "Frame Generation Rendered Copy"
            ) else {
                failPresentationBeforeSubmission(work, reason: "rendered copy encoder unavailable")
                return
            }
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
                error: completed.error
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
        error: Error?
    ) {
        let completionTime = CACurrentMediaTime()
        condition.lock()
        updateDiagnosticLocked(displayUpdateID: displayUpdateID) { diagnostic in
            diagnostic.gpuCompletionTime = completionTime
            if !succeeded {
                diagnostic.outcome = "failed:gpu-command-buffer"
            }
        }
        guard currentFrame?.eventValue == eventValue, var lifecycle = currentLifecycle else {
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
            interpolatorEncodeHistoryValid = succeeded && !lifecycle.cancellationRequested
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
        updateDiagnosticLocked(displayUpdateID: displayUpdateID) { diagnostic in
            diagnostic.presentedTime = presentedTime
            diagnostic.outcome = presentedTime.isFinite && presentedTime > 0.0
                    ? "presented"
                    : "failed:not-presented"
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
                displayHistoryValid = true
                realPresentationTimeoutAt = nil
            } else {
                displayHistoryValid = false
            }
        } else if !(presentedTime.isFinite && presentedTime > 0.0) {
            interpolatorEncodeHistoryValid = false
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
            interpolatorEncodeHistoryValid = false
            displayHistoryValid = false
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
    }

    private func cancelCurrentSourceLocked(reason: String) {
        guard let frame = currentFrame, var lifecycle = currentLifecycle else {
            return
        }
        let actions = lifecycle.cancel(reason: reason)
        currentLifecycle = lifecycle
        applyLifecycleActionsLocked(actions, eventValue: frame.eventValue)
    }

    private func cancelAndDrain(reason: String) {
        condition.lock()
        if let update = pendingDisplayUpdate {
            appendDiagnosticLocked(
                sourceFrameID: currentFrame?.sourceFrameID ?? 0,
                frameKind: "unassigned",
                update: update,
                outcome: "cancelled:\(reason)"
            )
            pendingDisplayUpdate = nil
        }
        cancelCurrentSourceLocked(reason: reason)
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
        diagnostics.append(FrameDiagnostic(
            sourceFrameID: sourceFrameID,
            frameKind: frameKind,
            displayUpdateID: update.updateID,
            targetTimestamp: update.targetTimestamp,
            targetPresentationTimestamp: update.targetPresentationTimestamp,
            cpuCommitTime: cpuCommitTime,
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

    private func dumpDiagnosticsIfEnabled(_ snapshot: [FrameDiagnostic]) {
        guard ProcessInfo.processInfo.environment["METALLUM_METALFX_PRESENT_DIAGNOSTICS"] == "1" else {
            return
        }
        for diagnostic in snapshot {
            NSLog(
                "[Metallum] MetalFX timeline source=%llu kind=%@ update=%llu target=%.6f presentationTarget=%.6f commit=%.6f gpu=%.6f presented=%.6f outcome=%@",
                diagnostic.sourceFrameID,
                diagnostic.frameKind,
                diagnostic.displayUpdateID,
                diagnostic.targetTimestamp,
                diagnostic.targetPresentationTimestamp,
                diagnostic.cpuCommitTime,
                diagnostic.gpuCompletionTime,
                diagnostic.presentedTime,
                diagnostic.outcome
            )
        }
    }

    func validationTimelineSnapshot() -> [MetalFrameGenerationDiagnosticSnapshot] {
        condition.lock()
        let snapshot = diagnostics.map {
            MetalFrameGenerationDiagnosticSnapshot(
                sourceFrameID: $0.sourceFrameID,
                frameKind: $0.frameKind,
                displayUpdateID: $0.displayUpdateID,
                targetTimestamp: $0.targetTimestamp,
                targetPresentationTimestamp: $0.targetPresentationTimestamp,
                cpuCommitTime: $0.cpuCommitTime,
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
            // The callback checks `stopping` before retaining a drawable. From
            // this point forward, no new DisplayUpdate is accepted.
            stopping = true
            if let update = pendingDisplayUpdate {
                appendDiagnosticLocked(
                    sourceFrameID: currentFrame?.sourceFrameID ?? 0,
                    frameKind: "unassigned",
                    update: update,
                    outcome: "cancelled:shutdown"
                )
                pendingDisplayUpdate = nil
            }
            cancelCurrentSourceLocked(reason: "shutdown")
            condition.broadcast()
        }
        while !workerExited || outstandingFrames > 0 {
            condition.wait()
        }
        let shouldDumpDiagnostics = !diagnosticsDumped
        diagnosticsDumped = true
        let diagnosticSnapshot = shouldDumpDiagnostics ? diagnostics : []
        condition.unlock()
        worker = nil
        if shouldDumpDiagnostics {
            dumpDiagnosticsIfEnabled(diagnosticSnapshot)
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
}

private func transparencyMaskMslSource() -> String {
    """
    #include <metal_stdlib>
    using namespace metal;

    struct TransparencyMaskUniforms {
      uint4 viewport;
      uint4 flags;
    };

    inline float targetActivity(texture2d<float, access::read> texture, uint2 pixel) {
      if (pixel.x >= texture.get_width() || pixel.y >= texture.get_height()) return 0.0;
      float4 value = texture.read(pixel);
      float coverage = max(value.a, max(value.r, max(value.g, value.b)));
      return coverage > 0.001 ? 1.0 : 0.0;
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
      float reactive = 0.0;
      if ((flags & 1u) != 0u) reactive = max(reactive, targetActivity(translucentTexture, pixel));
      if ((flags & 2u) != 0u) reactive = max(reactive, targetActivity(itemEntityTexture, pixel));
      if ((flags & 4u) != 0u) reactive = max(reactive, targetActivity(particlesTexture, pixel));
      if ((flags & 8u) != 0u) reactive = max(reactive, targetActivity(weatherTexture, pixel));
      if ((flags & 16u) != 0u) reactive = max(reactive, targetActivity(cloudsTexture, pixel));
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
      uint width;
      uint height;
      uint radius;
      uint reserved;
    };

    kernel void metallum_cutout_reactive_dilate(
      texture2d<float, access::read> cutoutCoverage [[texture(0)]],
      texture2d<half, access::read_write> reactiveTexture [[texture(1)]],
      constant CutoutReactiveUniforms& u [[buffer(0)]],
      uint2 pixel [[thread_position_in_grid]]) {
      if (pixel.x >= u.width || pixel.y >= u.height) return;

      float reactive = float(reactiveTexture.read(pixel).r);
      int radius = int(min(u.radius, 3u));
      for (int y = -radius; y <= radius; ++y) {
        for (int x = -radius; x <= radius; ++x) {
          int2 samplePosition = int2(pixel) + int2(x, y);
          if (samplePosition.x < 0 || samplePosition.y < 0
              || samplePosition.x >= int(u.width)
              || samplePosition.y >= int(u.height)) {
            continue;
          }
          reactive = max(
            reactive,
            clamp(cutoutCoverage.read(uint2(samplePosition)).r, 0.0, 1.0)
          );
        }
      }
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

private struct MotionUniforms {
    var currentViewProjection: simd_float4x4
    var inverseCurrentViewProjection: simd_float4x4
    var previousViewProjection: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
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
    };

    inline bool metallum_valid_depth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    inline float metallum_depth_edge_reactive(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth
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

      return validityBoundary ? 1.0 : clamp(gradient * 4.0, 0.0, 1.0);
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
      reactive = max(reactive, metallum_depth_edge_reactive(depthTexture, pixel, width, height, depth));

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
    };

    inline bool validDepth(float depth) {
      return isfinite(depth) && depth > 0.00001 && depth <= 1.00001;
    }

    inline float depthBoundary(
      texture2d<float, access::read> depthTexture,
      uint2 pixel,
      uint width,
      uint height,
      float depth
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
      return validityBoundary ? 1.0 : clamp(gradient * 4.0, 0.0, 1.0);
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
      if (!validDepth(depth)) {
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

      reactive = max(reactive, depthBoundary(depthTexture, pixel, width, height, depth));
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
          float previousDepth = previousDepthTexture.read(samplePixel).r;
          float threshold = max(0.0025, abs(currentDepth) * 0.01);
          bool wasOccluded = u.viewport.w != 0u
              ? previousDepth > currentDepth + threshold
              : previousDepth < currentDepth - threshold;
          if (!validDepth(previousDepth) || wasOccluded) {
            disocclusion = 1.0;
          }
        }
      }
      if (!isfinite(disocclusion) || disocclusion > 0.5) reactive = 1.0;
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
    clear: MTLComputePipelineState
)? {
    if let camera = NativeState.motionV2Pipeline,
       let merge = NativeState.motionMergePipeline,
       let clear = NativeState.motionClearPipeline {
        return (camera, merge, clear)
    }
    do {
        let cameraLibrary = try device.makeLibrary(source: motionCameraV2MslSource(), options: nil)
        let mergeLibrary = try device.makeLibrary(source: motionMergeV2MslSource(), options: nil)
        let clearLibrary = try device.makeLibrary(source: motionClearV2MslSource(), options: nil)
        guard let cameraFunction = cameraLibrary.makeFunction(name: "metallum_motion_camera_v2"),
              let mergeFunction = mergeLibrary.makeFunction(name: "metallum_motion_merge_v2"),
              let clearFunction = clearLibrary.makeFunction(name: "metallum_motion_clear_v2") else {
            NSLog("[Metallum] MetalFX v2 motion compute function missing")
            return nil
        }
        let camera = try device.makeComputePipelineState(function: cameraFunction)
        let merge = try device.makeComputePipelineState(function: mergeFunction)
        let clear = try device.makeComputePipelineState(function: clearFunction)
        NativeState.motionV2Pipeline = camera
        NativeState.motionMergePipeline = merge
        NativeState.motionClearPipeline = clear
        return (camera, merge, clear)
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

@_cdecl("metallum_metalfx_supports_cutout_reactive")
public func metallum_metalfx_supports_cutout_reactive(_ device: MTLDevice) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return ensureCutoutReactivePipeline(device) != nil ? 1 : 0
    }
    #endif
    return 0
}

@_cdecl("metallum_metalfx_apply_cutout_reactive")
public func metallum_metalfx_apply_cutout_reactive(
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
            if let fence {
                encoder.waitForFence(fence)
            }
            var uniforms = SIMD4<UInt32>(
                UInt32(inputWidth),
                UInt32(inputHeight),
                UInt32(radius),
                0
            )
            encoder.setComputePipelineState(pipeline)
            encoder.setBytes(
                &uniforms,
                length: MemoryLayout<SIMD4<UInt32>>.stride,
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

@_cdecl("metallum_metalfx_clear_motion_inputs")
public func metallum_metalfx_clear_motion_inputs(
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

@_cdecl("metallum_metalfx_mark_transparency")
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
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  let pipeline = ensureTransparencyMaskPipeline(device),
                  let encoder = commandBuffer.makeComputeCommandEncoder() else {
                logMetalFxFailureOnce("transparency-mask-encode", "could not create transparency mask pipeline or encoder")
                return 0
            }

            var flags: UInt32 = 0
            if translucentTexture != nil { flags |= 1 << 0 }
            if itemEntityTexture != nil { flags |= 1 << 1 }
            if particlesTexture != nil { flags |= 1 << 2 }
            if weatherTexture != nil { flags |= 1 << 3 }
            if cloudsTexture != nil { flags |= 1 << 4 }
            var uniforms = TransparencyMaskUniforms(
                viewport: SIMD4<UInt32>(UInt32(inputWidth), UInt32(inputHeight), 0, 0),
                flags: SIMD4<UInt32>(flags, 0, 0, 0)
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

@_cdecl("metallum_metalfx_encode")
public func metallum_metalfx_encode(
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
                if let cached = NativeState.metalFxScalers[key] {
                    scalerObject = cached
                } else {
                    let descriptor = MTLFXTemporalScalerDescriptor()
                    descriptor.colorTextureFormat = colorTexture.pixelFormat
                    descriptor.depthTextureFormat = depthTexture!.pixelFormat
                    descriptor.motionTextureFormat = motionTexture!.pixelFormat
                    descriptor.outputTextureFormat = outputTexture.pixelFormat
                    descriptor.inputWidth = colorTexture.width
                    descriptor.inputHeight = colorTexture.height
                    descriptor.outputWidth = outputTexture.width
                    descriptor.outputHeight = outputTexture.height
                    // Minecraft's render target is already SDR-tonemapped.
                    // MetalFX auto exposure is intended for HDR content and
                    // can make a static sky oscillate as temporal history is
                    // updated.
                    descriptor.isAutoExposureEnabled = false
                    descriptor.requiresSynchronousInitialization = true
                    if #available(macOS 14.4, *), reactiveTexture != nil {
                        descriptor.isReactiveMaskTextureEnabled = true
                        descriptor.reactiveMaskTextureFormat = reactiveTexture!.pixelFormat
                    }
                    guard let scaler = descriptor.makeTemporalScaler(device: device) else {
                        logMetalFxFailureOnce(
                            "temporal-create",
                            "descriptor rejected color=\(colorTexture.pixelFormat.rawValue) depth=\(depthTexture!.pixelFormat.rawValue) motion=\(motionTexture!.pixelFormat.rawValue) output=\(outputTexture.pixelFormat.rawValue) input=\(colorTexture.width)x\(colorTexture.height) output=\(outputTexture.width)x\(outputTexture.height)"
                        )
                        return 0
                    }
                    scalerObject = scaler as AnyObject
                    NativeState.metalFxScalers[key] = scaler as AnyObject
                }
                guard let scaler = scalerObject as? any MTLFXTemporalScaler,
                      let depthTexture,
                      let motionTexture,
                      let reactiveTexture else {
                    logMetalFxFailureOnce("temporal-cast", "cached scaler did not conform to MTLFXTemporalScaler")
                    return 0
                }

                if let currentViewProjection, let inverseCurrentViewProjection, let previousViewProjection {
                    guard let pipeline = ensureMotionPipeline(device),
                          let encoder = commandBuffer.makeComputeCommandEncoder() else {
                        logMetalFxFailureOnce("motion-encode", "could not create motion reconstruction pipeline or encoder")
                        return 0
                    }
                    if let fence {
                        encoder.waitForFence(fence)
                    }
                    var uniforms = MotionUniforms(
                        currentViewProjection: makeMatrix(currentViewProjection),
                        inverseCurrentViewProjection: makeMatrix(inverseCurrentViewProjection),
                        previousViewProjection: makeMatrix(previousViewProjection),
                        viewport: SIMD4<Float>(Float(inputWidth), Float(inputHeight), 1.0 / Float(max(inputWidth, 1)), 1.0 / Float(max(inputHeight, 1))),
                        flags: SIMD4<UInt32>(preserveReactiveMask != 0 ? 1 : 0, 0, 0, 0)
                    )
                    encoder.setComputePipelineState(pipeline)
                    encoder.setBytes(&uniforms, length: MemoryLayout<MotionUniforms>.stride, index: 0)
                    encoder.setTexture(depthTexture, index: 0)
                    encoder.setTexture(motionTexture, index: 1)
                    encoder.setTexture(reactiveTexture, index: 2)
                    // See the transparency mask pass above: cap the reported
                    // width so validation instrumentation cannot create an
                    // illegal threadgroup.
                    let threadWidth = max(1, min(pipeline.threadExecutionWidth, 64))
                    let threadHeight = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / threadWidth))
                    encoder.dispatchThreads(
                        MTLSize(width: Int(inputWidth), height: Int(inputHeight), depth: 1),
                        threadsPerThreadgroup: MTLSize(width: threadWidth, height: threadHeight, depth: 1)
                    )
                    if let fence {
                        encoder.updateFence(fence)
                    }
                    encoder.endEncoding()
                }

                scaler.colorTexture = colorTexture
                scaler.depthTexture = depthTexture
                scaler.motionTexture = motionTexture
                scaler.outputTexture = outputTexture
                scaler.inputContentWidth = Int(inputWidth)
                scaler.inputContentHeight = Int(inputHeight)
                scaler.jitterOffsetX = jitterX
                scaler.jitterOffsetY = jitterY
                // Motion is emitted as NDC delta; convert to input-resolution
                // pixels using the half-resolution NDC range.
                scaler.motionVectorScaleX = Float(inputWidth) * 0.5
                scaler.motionVectorScaleY = Float(inputHeight) * 0.5
                scaler.reset = reset != 0
                scaler.isDepthReversed = depthReversed != 0
                if #available(macOS 14.4, *) {
                    scaler.reactiveMaskTexture = reactiveTexture
                }
                scaler.fence = fence
                commandBuffer.pushDebugGroup("MetalFX Temporal Upscale")
                scaler.encode(commandBuffer: commandBuffer)
                commandBuffer.popDebugGroup()
                return 1
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
                    descriptor.colorProcessingMode = .linear
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

/// Versioned temporal entry point. It keeps the legacy camera-only symbol
/// intact while making the producer/merge boundary explicit: camera motion is
/// reconstructed separately, valid object motion overrides it, and
/// disocclusion/invalid data forces reactive history rejection.
@_cdecl("metallum_metalfx_encode_v2")
public func metallum_metalfx_encode_v2(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ colorTexture: MTLTexture,
    _ depthTexture: MTLTexture,
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
    _ inputWidth: Int32,
    _ inputHeight: Int32,
    _ reset: Int32,
    _ depthReversed: Int32,
    _ preserveReactiveMask: Int32
) -> Int32 {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 13.0, *) {
        return autoreleasepool {
            guard inputWidth > 0, inputHeight > 0,
                  colorTexture.width == Int(inputWidth), colorTexture.height == Int(inputHeight),
                  depthTexture.width == Int(inputWidth), depthTexture.height == Int(inputHeight),
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

            guard let scaler = scalerObject as? any MTLFXTemporalScaler,
                  let cameraEncoder = commandBuffer.makeComputeCommandEncoder() else {
                logMetalFxFailureOnce("temporal-v2-cast", "cached scaler or camera compute encoder unavailable")
                return 0
            }
            cameraEncoder.label = "MetalFX Camera Motion Reconstruction"
            if let fence {
                cameraEncoder.waitForFence(fence)
            }
            var motionUniforms = MotionUniforms(
                currentViewProjection: makeMatrix(currentViewProjection),
                inverseCurrentViewProjection: makeMatrix(inverseCurrentViewProjection),
                previousViewProjection: makeMatrix(previousViewProjection),
                viewport: SIMD4<Float>(
                    Float(inputWidth), Float(inputHeight),
                    1.0 / Float(max(inputWidth, 1)), 1.0 / Float(max(inputHeight, 1))
                ),
                flags: SIMD4<UInt32>(preserveReactiveMask != 0 ? 1 : 0, 0, 0, 0)
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
            if let fence {
                mergeEncoder.waitForFence(fence)
            }
            var mergeUniforms = SIMD4<UInt32>(
                UInt32(inputWidth),
                UInt32(inputHeight),
                previousDepthIsValid ? 1 : 0,
                depthReversed != 0 ? 1 : 0
            )
            mergeEncoder.setComputePipelineState(pipelines.merge)
            mergeEncoder.setBytes(&mergeUniforms, length: MemoryLayout<SIMD4<UInt32>>.stride, index: 0)
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

@_cdecl("metallum_metalfx_frame_generation_encode")
public func metallum_metalfx_frame_generation_encode(
    _ commandBuffer: MTLCommandBuffer,
    _ device: MTLDevice,
    _ layer: CAMetalLayer,
    _ sceneColor: MTLTexture,
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
                    uiColor: uiColor,
                    depth: depthTexture,
                    motion: motionTexture
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

            commandBuffer.pushDebugGroup("MetalFX Frame Generation Inputs")
            let result = presenter.encode(
                commandBuffer: commandBuffer,
                sceneColor: sceneColor,
                uiColor: uiColor,
                depth: depthTexture,
                motion: motionTexture,
                jitterX: jitterX,
                jitterY: jitterY,
                fieldOfView: fieldOfView,
                nearPlane: nearPlane,
                farPlane: farPlane,
                aspectRatio: aspectRatio,
                reset: reset != 0,
                globalFence: globalFence
            )
            commandBuffer.popDebugGroup()
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

@_cdecl("metallum_encode_texture_copy")
public func metallum_encode_texture_copy(
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

@_cdecl("metallum_metalfx_shutdown")
public func metallum_metalfx_shutdown() {
    #if os(macOS) && canImport(MetalFX)
    if #available(macOS 26.0, *) {
        NativeState.frameGenerationPresenter?.shutdown()
    NativeState.frameGenerationPresenter = nil
    }
    NativeState.metalFxScalers.removeAll()
    NativeState.metalFxHistoryLock.lock()
    NativeState.metalFxPreviousDepthTextures.removeAll()
    NativeState.metalFxPreviousDepthValid.removeAll()
    NativeState.metalFxHistoryLock.unlock()
    NativeState.motionPipeline = nil
    NativeState.motionV2Pipeline = nil
    NativeState.motionMergePipeline = nil
    NativeState.motionClearPipeline = nil
    NativeState.transparencyMaskPipeline = nil
    NativeState.cutoutReactivePipeline = nil
    NativeState.frameGenerationLogged = false
    #endif
    NativeState.copyPipelines.removeAll()
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
        retainedPointer(MTLCreateSystemDefaultDevice())
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
        newLayer.frame = view.bounds
        view.layer.sublayers = [newLayer]
        return retainedPointer(newLayer)
    }
    NSLog("[Metallum] Using existing view.layer as CAMetalLayer (frame=\(layer.frame), contentsScale=\(layer.contentsScale), drawsAsynchronously=\(layer.drawsAsynchronously ? "YES" : "NO"))")
    layer.device = device
    layer.framebufferOnly = true
    layer.isOpaque = true
    // Do NOT override contentsScale: Amethyst sets it to
    // screenScale * resolutionScale and re-syncs it on rotation; let the
    // launcher own that property. The renderable size is governed by
    // `drawableSize`, which we set in metallum_configure_layer.
    return unretainedPointer(layer)
}
#endif

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
public func metallum_MTLCommandBuffer_commit(_ commandBuffer: MTLCommandBuffer) {
    commandBuffer.commit()
}

@_cdecl("metallum_create_semaphore")
public func metallum_create_semaphore() -> UnsafeMutableRawPointer? {
    retainedPointer(DispatchSemaphore(value: 0))
}

@_cdecl("metallum_MTLCommandBuffer_commitWithSignal")
public func metallum_MTLCommandBuffer_commitWithSignal(_ commandBuffer: MTLCommandBuffer, _ semaphore: DispatchSemaphore) {
    while semaphore.wait(timeout: .now()) == .success {}
    commandBuffer.addCompletedHandler { _ in
        semaphore.signal()
    }
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
public func metallum_MTLCommandBuffer_isCompleted(_ commandBuffer: MTLCommandBuffer) -> Int32 {
    commandBuffer.status == .completed || commandBuffer.status == .error ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_completedSuccessfully")
public func metallum_MTLCommandBuffer_completedSuccessfully(_ commandBuffer: MTLCommandBuffer) -> Int32 {
    commandBuffer.status == .completed && commandBuffer.error == nil ? 1 : 0
}

@_cdecl("metallum_MTLCommandBuffer_waitUntilCompleted")
public func metallum_MTLCommandBuffer_waitUntilCompleted(_ commandBuffer: MTLCommandBuffer, _ timeoutMs: UInt64) -> Int32 {
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
    _ commandBuffer: MTLCommandBuffer,
    _ labelPtr: UnsafePointer<CChar>?
) {
    autoreleasepool {
        commandBuffer.pushDebugGroup(stringFromOptionalCString(labelPtr) ?? "")
    }
}

@_cdecl("metallum_MTLCommandBuffer_popDebugGroup")
public func metallum_MTLCommandBuffer_popDebugGroup(_ commandBuffer: MTLCommandBuffer) {
    commandBuffer.popDebugGroup()
}

@_cdecl("metallum_MTLCommandBuffer_makeBlitCommandEncoder")
public func metallum_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBuffer: MTLCommandBuffer
) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(commandBuffer.makeBlitCommandEncoder())
    }
}

@_cdecl("metallum_MTLCommandEncoder_endEncoding")
public func metallum_MTLCommandEncoder_endEncoding(_ encoder: MTLCommandEncoder) {
    encoder.endEncoding()
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer(
    _ blit: MTLBlitCommandEncoder,
    _ sourceBuffer: MTLBuffer,
    _ sourceOffset: UInt64,
    _ destinationBuffer: MTLBuffer,
    _ destinationOffset: UInt64,
    _ length: UInt64
) {
    blit.copy(from: sourceBuffer, sourceOffset: Int(sourceOffset), to: destinationBuffer, destinationOffset: Int(destinationOffset), size: Int(length))
}

@_cdecl("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture")
public func metallum_MTLBlitCommandEncoder_copyFromBufferToTexture(
    _ blit: MTLBlitCommandEncoder,
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
    _ blit: MTLBlitCommandEncoder,
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
    _ blit: MTLBlitCommandEncoder,
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
        retainedPointer(device.makeBuffer(length: length, options: options))
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
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = minFilter
        descriptor.magFilter = magFilter
        descriptor.mipFilter = mipFilter
        descriptor.sAddressMode = addressModeU
        descriptor.tAddressMode = addressModeV
        descriptor.maxAnisotropy = max(Int(maxAnisotropy), 1)
        descriptor.lodMinClamp = 0.0
        descriptor.lodMaxClamp = lodMaxClamp >= 0.0 && lodMaxClamp.isFinite ? Float(lodMaxClamp) : Float.greatestFiniteMagnitude
        return retainedPointer(device.makeSamplerState(descriptor: descriptor))
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
    _ commandBuffer: MTLCommandBuffer,
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
    _ commandBuffer: MTLCommandBuffer,
    _ colorTexturePointers: UnsafePointer<UnsafeMutableRawPointer?>?,
    _ colorCount: Int32,
    _ depthTexture: MTLTexture?,
    _ viewportWidth: Double,
    _ viewportHeight: Double,
    _ clearColors: UnsafePointer<Float>?,
    _ clearColorEnabled: UnsafePointer<Int32>?,
    _ clearDepthEnabled: Int32,
    _ clearDepth: Double
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
                renderPass.depthAttachment.storeAction = .store
            }
            if stencilFormat != .invalid || depthFormat == .stencil8 {
                renderPass.stencilAttachment.texture = depthTexture
                renderPass.stencilAttachment.loadAction = .dontCare
                renderPass.stencilAttachment.storeAction = .store
            }
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return nil
        }
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
public func metallum_MTLRenderCommandEncoder_setRenderPipelineState(_ encoder: MTLRenderCommandEncoder, _ pipeline: MTLRenderPipelineState) {
    encoder.setRenderPipelineState(pipeline)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthStencilState")
public func metallum_MTLRenderCommandEncoder_setDepthStencilState(_ encoder: MTLRenderCommandEncoder, _ state: MTLDepthStencilState?) {
    encoder.setDepthStencilState(state)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setDepthBias")
public func metallum_MTLRenderCommandEncoder_setDepthBias(
    _ encoder: MTLRenderCommandEncoder,
    _ depthBias: Float,
    _ slopeScale: Float,
    _ clamp: Float
) {
    encoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setFrontFacingWinding")
public func metallum_MTLRenderCommandEncoder_setFrontFacingWinding(_ encoder: MTLRenderCommandEncoder, _ winding: MTLWinding) {
    encoder.setFrontFacing(winding)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setCullMode")
public func metallum_MTLRenderCommandEncoder_setCullMode(_ encoder: MTLRenderCommandEncoder, _ cullMode: MTLCullMode) {
    encoder.setCullMode(cullMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTriangleFillMode")
public func metallum_MTLRenderCommandEncoder_setTriangleFillMode(_ encoder: MTLRenderCommandEncoder, _ fillMode: MTLTriangleFillMode) {
    encoder.setTriangleFillMode(fillMode)
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBuffer")
public func metallum_MTLRenderCommandEncoder_setBuffer(_ encoder: MTLRenderCommandEncoder, _ buffer: MTLBuffer?, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBuffer(buffer, offset: Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBuffer(buffer, offset: Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setBufferOffset")
public func metallum_MTLRenderCommandEncoder_setBufferOffset(_ encoder: MTLRenderCommandEncoder, _ offset: UInt64, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexBufferOffset(Int(offset), index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentBufferOffset(Int(offset), index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTexture")
public func metallum_MTLRenderCommandEncoder_setTexture(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ index: UInt64, _ stageMask: Int32) {
    if (stageMask & 1) != 0 {
        encoder.setVertexTexture(texture, index: Int(index))
    }
    if (stageMask & 2) != 0 {
        encoder.setFragmentTexture(texture, index: Int(index))
    }
}

@_cdecl("metallum_MTLRenderCommandEncoder_setTextureAndSampler")
public func metallum_MTLRenderCommandEncoder_setTextureAndSampler(_ encoder: MTLRenderCommandEncoder, _ texture: MTLTexture?, _ sampler: MTLSamplerState?, _ index: UInt64, _ stageMask: Int32) {
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
    _ encoder: MTLRenderCommandEncoder,
    _ x: UInt64,
    _ y: UInt64,
    _ width: UInt64,
    _ height: UInt64
) {
    encoder.setScissorRect(MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height)))
}

@_cdecl("metallum_MTLRenderCommandEncoder_drawPrimitives")
public func metallum_MTLRenderCommandEncoder_drawPrimitives(
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ firstVertex: Int,
    _ vertexCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
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
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexCount: Int,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseVertex: Int,
    _ baseInstance: Int
) {
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
    _ encoder: MTLRenderCommandEncoder,
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
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indexType: MTLIndexType,
    _ indexBuffer: MTLBuffer,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
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
    _ encoder: MTLRenderCommandEncoder,
    _ primitiveType: MTLPrimitiveType,
    _ indirectBuffer: MTLBuffer,
    _ indirectBufferOffset: UInt64,
    _ drawCount: Int,
    _ stride: UInt64
) {
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
    _ encoder: MTLRenderCommandEncoder,
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
    _ commandBuffer: MTLCommandBuffer,
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
    _ encoder: MTLRenderCommandEncoder,
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
    layer.allowsNextDrawableTimeout = false
    layer.displaySyncEnabled = immediatePresentMode == 0
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
    _ commandBuffer: MTLCommandBuffer,
    _ layer: CAMetalLayer,
    _ sourceTexture: MTLTexture,
    _ globalFence: MTLFence?
) {
    return autoreleasepool {
        guard let drawable: CAMetalDrawable = layer.nextDrawable() else {
            NSLog("[Metallum] WARNING: nextDrawable() returned nil (drawableSize=\(layer.drawableSize), frame=\(layer.frame), isOpaque=\(layer.isOpaque), device=\(layer.device != nil ? "set" : "nil"))")
            return
        }

        let renderPass = MTLRenderPassDescriptor()
        renderPass.colorAttachments[0].texture = drawable.texture
        renderPass.colorAttachments[0].loadAction = .dontCare
        renderPass.colorAttachments[0].storeAction = .store

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPass) else {
            return
        }

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

        encoder.endEncoding()
        commandBuffer.present(drawable)
        #if os(iOS)
        CATransaction.flush()
        #endif
    }
}

@_cdecl("metallum_create_fence")
public func metallum_create_fence(_ device: MTLDevice) -> UnsafeMutableRawPointer? {
    return autoreleasepool {
        retainedPointer(device.makeFence())
    }
}

@_cdecl("MTLRenderCommandEncoder_updateFence")
public func MTLRenderCommandEncoder_updateFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.updateFence(fence, after: stages)
}

@_cdecl("MTLRenderCommandEncoder_waitForFence")
public func MTLRenderCommandEncoder_waitForFence(
    _ encoder: MTLRenderCommandEncoder,
    _ fence: MTLFence,
    _ stages: MTLRenderStages
) {
    encoder.waitForFence(fence, before: stages)
}

@_cdecl("MTLBlitCommandEncoder_updateFence")
public func MTLBlitCommandEncoder_updateFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
    encoder.updateFence(fence)
}

@_cdecl("MTLBlitCommandEncoder_waitForFence")
public func MTLBlitCommandEncoder_waitForFence(
    _ encoder: MTLBlitCommandEncoder,
    _ fence: MTLFence
) {
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

@_cdecl("metallum_release_object")
public func metallum_release_object(_ obj: UnsafeMutableRawPointer?) {
    autoreleasepool {
        guard let obj else { return }
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
        do {
            return retainedPointer(try device.makeRenderPipelineState(descriptor: descriptor))
        } catch {
            NSLog("[metallum] Failed to create render pipeline state: %@", String(describing: error))
            return nil
        }
    }
}
