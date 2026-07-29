import AppKit
import Foundation
import Metal
import MetalFX
import QuartzCore

private enum PresentationValidationError: Error, CustomStringConvertible {
    case failed(String)

    var description: String {
        switch self {
        case .failed(let message):
            return message
        }
    }
}

@available(macOS 26.0, *)
private final class ValidationRunner {
    private let app: NSApplication
    private let window: NSWindow
    private let layer: CAMetalLayer
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let outputDirectory: URL
    private let nominalDisplayUpdatesPerSecond: Double
    private var presenter: MetalFrameGenerationPresenter?
    private var failure: Error?

    init(outputDirectory: URL) throws {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue() else {
            throw PresentationValidationError.failed("Metal device or command queue unavailable")
        }
        self.device = device
        self.queue = queue
        self.outputDirectory = outputDirectory
        self.nominalDisplayUpdatesPerSecond = Double(NSScreen.main?.maximumFramesPerSecond ?? 0)
        self.app = NSApplication.shared
        // WindowServer silently drops presents for occluded layers, reporting
        // presentedTime == 0 for the whole run. Center the window on the main
        // screen and float it so back-to-back CI runs and unrelated desktop
        // windows cannot occlude the validation surface.
        let screenFrame = NSScreen.main?.visibleFrame
                ?? NSRect(x: 0, y: 0, width: 1280, height: 800)
        let contentRect = NSRect(
            x: screenFrame.midX - 427,
            y: screenFrame.midY - 240,
            width: 854,
            height: 480
        )
        self.window = NSWindow(
            contentRect: contentRect,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        self.window.level = .floating
        self.window.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        self.layer = CAMetalLayer()

        try FileManager.default.createDirectory(
            at: outputDirectory,
            withIntermediateDirectories: true
        )
        layer.device = device
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
        // Match the Retina framebuffer used by the Launcher QA profile. The
        // logical window remains 854x480 so the validation surface fits on the
        // built-in display while interpolation runs at the real pixel count.
        layer.drawableSize = CGSize(width: 1708, height: 960)
        let view = NSView(frame: window.contentView?.bounds ?? .zero)
        view.wantsLayer = true
        view.layer = layer
        view.autoresizingMask = [.width, .height]
        window.contentView = view
        window.title = "Metallum CAMetalDisplayLink Validation"
    }

    func run() {
        app.setActivationPolicy(.regular)
        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
        app.activate()

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else {
                return
            }
            do {
                try self.drivePresentation()
            } catch {
                self.failure = error
            }
            DispatchQueue.main.async {
                self.app.stop(nil)
                NSEvent.otherEvent(
                    with: .applicationDefined,
                    location: .zero,
                    modifierFlags: [],
                    timestamp: 0,
                    windowNumber: 0,
                    context: nil,
                    subtype: 0,
                    data1: 0,
                    data2: 0
                ).map { self.app.postEvent($0, atStart: false) }
            }
        }

        app.run()
        window.orderOut(nil)
        if let failure {
            fputs("MetalFrameGenerationPresentationValidation FAILED: \(failure)\n", stderr)
            exit(1)
        }
    }

    private func makeTexture(
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        usage: MTLTextureUsage
    ) throws -> MTLTexture {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.storageMode = .private
        descriptor.usage = usage
        guard let texture = device.makeTexture(descriptor: descriptor) else {
            throw PresentationValidationError.failed("Could not allocate \(format) texture")
        }
        return texture
    }

    private func makeInputs(
        sceneWidth: Int,
        sceneHeight: Int,
        uiWidth: Int,
        uiHeight: Int,
        inputWidth: Int,
        inputHeight: Int
    ) throws -> (
        scene: MTLTexture,
        nativeScene: MTLTexture,
        ui: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) {
        let colorUsage: MTLTextureUsage = [.renderTarget, .shaderRead, .shaderWrite]
        return (
            try makeTexture(
                format: .bgra8Unorm, width: sceneWidth, height: sceneHeight, usage: colorUsage
            ),
            try makeTexture(format: .bgra8Unorm, width: uiWidth, height: uiHeight, usage: colorUsage),
            try makeTexture(format: .bgra8Unorm, width: uiWidth, height: uiHeight, usage: colorUsage),
            try makeTexture(
                format: .depth32Float,
                width: inputWidth,
                height: inputHeight,
                usage: [.renderTarget, .shaderRead]
            ),
            try makeTexture(
                format: .rg16Float,
                width: inputWidth,
                height: inputHeight,
                usage: [.renderTarget, .shaderRead, .shaderWrite]
            )
        )
    }

    private func clearInputs(
        _ inputs: (
            scene: MTLTexture,
            nativeScene: MTLTexture,
            ui: MTLTexture,
            depth: MTLTexture,
            motion: MTLTexture
        ),
        frame: Int,
        commandBuffer: MTLCommandBuffer
    ) throws {
        let scenePass = MTLRenderPassDescriptor()
        scenePass.colorAttachments[0].texture = inputs.scene
        scenePass.colorAttachments[0].loadAction = .clear
        scenePass.colorAttachments[0].storeAction = .store
        scenePass.colorAttachments[0].clearColor = MTLClearColor(
            red: Double(frame % 3) * 0.25 + 0.1,
            green: 0.2,
            blue: 0.6,
            alpha: 1.0
        )
        guard let sceneEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: scenePass) else {
            throw PresentationValidationError.failed("Could not encode source clear")
        }
        sceneEncoder.endEncoding()

        let nativeScenePass = MTLRenderPassDescriptor()
        nativeScenePass.colorAttachments[0].texture = inputs.nativeScene
        nativeScenePass.colorAttachments[0].loadAction = .clear
        nativeScenePass.colorAttachments[0].storeAction = .store
        nativeScenePass.colorAttachments[0].clearColor = scenePass.colorAttachments[0].clearColor
        guard let nativeSceneEncoder = commandBuffer.makeRenderCommandEncoder(
            descriptor: nativeScenePass
        ) else {
            throw PresentationValidationError.failed("Could not encode native source clear")
        }
        nativeSceneEncoder.endEncoding()

        let depthPass = MTLRenderPassDescriptor()
        depthPass.depthAttachment.texture = inputs.depth
        depthPass.depthAttachment.loadAction = .clear
        depthPass.depthAttachment.storeAction = .store
        depthPass.depthAttachment.clearDepth = 0.75
        guard let depthEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: depthPass) else {
            throw PresentationValidationError.failed("Could not encode depth clear")
        }
        depthEncoder.endEncoding()

        let uiPass = MTLRenderPassDescriptor()
        uiPass.colorAttachments[0].texture = inputs.ui
        uiPass.colorAttachments[0].loadAction = .clear
        uiPass.colorAttachments[0].storeAction = .store
        uiPass.colorAttachments[0].clearColor = MTLClearColor(
            red: 0.02,
            green: Double(frame % 2) * 0.02,
            blue: 0.03,
            alpha: 0.2
        )
        guard let uiEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: uiPass) else {
            throw PresentationValidationError.failed("Could not encode UI clear")
        }
        uiEncoder.endEncoding()

        let motionPass = MTLRenderPassDescriptor()
        motionPass.colorAttachments[0].texture = inputs.motion
        motionPass.colorAttachments[0].loadAction = .clear
        motionPass.colorAttachments[0].storeAction = .store
        motionPass.colorAttachments[0].clearColor = MTLClearColor(
            red: frame == 0 ? 0.0 : -0.02,
            green: 0.0,
            blue: 0.0,
            alpha: 0.0
        )
        guard let motionEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: motionPass) else {
            throw PresentationValidationError.failed("Could not encode motion clear")
        }
        motionEncoder.endEncoding()
    }

    private func drivePresentation() throws {
        // Let WindowServer attach the newly ordered window before the first
        // source is submitted.
        Thread.sleep(forTimeInterval: 0.5)
        try primeLayerPresentation()
        var displayWidth = 1708
        var displayHeight = 960
        var sceneWidth = 1280
        var sceneHeight = 718
        var inputWidth = 858
        var inputHeight = 482
        var sourceInputWidth = 1512
        var sourceInputHeight = 867
        var inputs = try makeInputs(
            sceneWidth: sceneWidth,
            sceneHeight: sceneHeight,
            uiWidth: displayWidth,
            uiHeight: displayHeight,
            inputWidth: sourceInputWidth,
            inputHeight: sourceInputHeight
        )
        guard let presenter = MetalFrameGenerationPresenter(
            device: device,
            layer: layer,
            sceneColor: inputs.scene,
            nativeSceneColor: inputs.nativeScene,
            uiColor: inputs.ui,
            depth: inputs.depth,
            motion: inputs.motion,
            inputWidth: inputWidth,
            inputHeight: inputHeight,
            scalerToken: 0,
            stamp: MetalFxFrameStamp(frameID: 1, historyEpoch: 1)
        ) else {
            throw PresentationValidationError.failed("Could not create frame-generation presenter")
        }
        self.presenter = presenter

        let initialWarmupSourceCount = 10
        let steadySourceCountPerPhase = 30
        let resizeWarmupSourceCount = 10
        let resizeSourceIndex = initialWarmupSourceCount + steadySourceCountPerPhase
        let totalSourceCount = initialWarmupSourceCount
                + steadySourceCountPerPhase
                + resizeWarmupSourceCount
                + steadySourceCountPerPhase
        for sourceIndex in 0..<totalSourceCount {
            if sourceIndex == resizeSourceIndex {
                guard presenter.waitUntilIdle(timeout: 3.0) else {
                    throw PresentationValidationError.failed(
                        "Presenter did not drain before resize"
                    )
                }
                // Ownership ends at real-present GPU completion, which can
                // precede WindowServer's presented callback. Do not resize the
                // layer under the last pre-resize drawable: that would turn a
                // valid steady frame into a synthetic presentedTime==0 failure.
                guard waitForPresentationCallbacks(
                    presenter: presenter,
                    throughSourceFrameID: UInt64(sourceIndex),
                    timeout: 1.0
                ) else {
                    throw PresentationValidationError.failed(
                        "Pre-resize presented callbacks did not settle"
                    )
                }
                displayWidth = 1600
                displayHeight = 900
                sceneWidth = 1280
                sceneHeight = 720
                inputWidth = 858
                inputHeight = 482
                sourceInputWidth = 1400
                sourceInputHeight = 788
                inputs = try makeInputs(
                    sceneWidth: sceneWidth,
                    sceneHeight: sceneHeight,
                    uiWidth: displayWidth,
                    uiHeight: displayHeight,
                    inputWidth: sourceInputWidth,
                    inputHeight: sourceInputHeight
                )
                DispatchQueue.main.sync {
                    self.window.setContentSize(NSSize(width: displayWidth / 2, height: displayHeight / 2))
                    // Exercise the same surface reconfigure and deferred layer
                    // policy refresh sequence used by Minecraft. This caught a
                    // production crash where the refresh tried to change
                    // maximumDrawableCount after CAMetalDisplayLink attached.
                    metallum_configure_layer(
                        self.layer,
                        Double(displayWidth),
                        Double(displayHeight),
                        0
                    )
                    presenter.requestLayerPolicyRefresh()
                }
            }
            guard let commandBuffer = queue.makeCommandBuffer() else {
                throw PresentationValidationError.failed("Could not create input command buffer")
            }
            try clearInputs(inputs, frame: sourceIndex, commandBuffer: commandBuffer)
            let commandBufferPointer = UnsafeMutableRawPointer(
                Unmanaged.passUnretained(commandBuffer).toOpaque()
            )
            let accepted = presenter.encode(
                commandBufferPointer: commandBufferPointer,
                sceneColor: inputs.scene,
                nativeSceneColor: inputs.nativeScene,
                uiColor: inputs.ui,
                depth: inputs.depth,
                motion: inputs.motion,
                inputWidth: inputWidth,
                inputHeight: inputHeight,
                jitterX: 0.0,
                jitterY: 0.0,
                fieldOfView: 70.0,
                nearPlane: 0.05,
                farPlane: 1000.0,
                aspectRatio: Float(displayWidth) / Float(displayHeight),
                sourceDeltaSeconds: 1.0 / 60.0,
                reset: sourceIndex == 0 || sourceIndex == resizeSourceIndex,
                globalFence: nil,
                stamp: MetalFxFrameStamp(
                    frameID: UInt64(sourceIndex + 1),
                    historyEpoch: 1
                )
            )
            guard accepted == 1 else {
                throw PresentationValidationError.failed("Presenter rejected source frame \(sourceIndex)")
            }
            commandBuffer.commit()
        }
        guard presenter.waitUntilIdle(timeout: 3.0) else {
            throw PresentationValidationError.failed(
                "Final source frames did not reach terminal ownership states"
            )
        }
        guard waitForPresentationCallbacks(
            presenter: presenter,
            throughSourceFrameID: UInt64(totalSourceCount),
            timeout: 1.0
        ) else {
            throw PresentationValidationError.failed(
                "Final presented callbacks did not settle"
            )
        }

        // Source ownership now ends at real-present GPU completion, while
        // WindowServer's presented callbacks remain intentionally asynchronous.
        // Give those diagnostics a bounded settle window before snapshotting;
        // this wait belongs only to validation and must not re-enter the game's
        // source-frame path.
        Thread.sleep(forTimeInterval: 0.25)
        let timeline = presenter.validationTimelineSnapshot()
        try writeRawTimeline(timeline)
        let shutdownStart = CACurrentMediaTime()
        presenter.shutdown()
        let shutdownDuration = CACurrentMediaTime() - shutdownStart
        guard layer.displaySyncEnabled else {
            throw PresentationValidationError.failed(
                "Frame-generation shutdown did not restore the ordinary VSync present path"
            )
        }
        guard !layer.allowsNextDrawableTimeout else {
            throw PresentationValidationError.failed(
                "Frame-generation shutdown retained presenter-owned drawable policy"
            )
        }
        self.presenter = nil
        try validateAndWrite(
            timeline: timeline,
            initialWarmupSourceCount: initialWarmupSourceCount,
            steadySourceCountPerPhase: steadySourceCountPerPhase,
            resizeWarmupSourceCount: resizeWarmupSourceCount,
            preferredFrameLatency: CFTimeInterval(
                metalFrameGenerationPreferredFrameLatency
            ),
            shutdownDuration: shutdownDuration
        )
    }

    /// Frame generation takes over a CAMetalLayer that Minecraft has already
    /// presented menus and loading screens through. Reproduce that production
    /// precondition and prove the window is actually composited before attaching
    /// CAMetalDisplayLink; otherwise the first few drawables of a never-presented
    /// test layer report presentedTime == 0 independently of the presenter path.
    private func primeLayerPresentation() throws {
        for attempt in 1...12 {
            guard let drawable = layer.nextDrawable(),
                  let commandBuffer = queue.makeCommandBuffer() else {
                Thread.sleep(forTimeInterval: 1.0 / 120.0)
                continue
            }
            let descriptor = MTLRenderPassDescriptor()
            descriptor.colorAttachments[0].texture = drawable.texture
            descriptor.colorAttachments[0].loadAction = .clear
            descriptor.colorAttachments[0].storeAction = .store
            descriptor.colorAttachments[0].clearColor = MTLClearColor(
                red: 0.02,
                green: 0.03,
                blue: 0.05,
                alpha: 1.0
            )
            guard let encoder = commandBuffer.makeRenderCommandEncoder(
                descriptor: descriptor
            ) else {
                throw PresentationValidationError.failed(
                    "Could not encode CAMetalLayer priming frame"
                )
            }
            encoder.endEncoding()
            let presented = DispatchSemaphore(value: 0)
            let resultLock = NSLock()
            var presentedTime: CFTimeInterval = 0.0
            drawable.addPresentedHandler { completedDrawable in
                resultLock.lock()
                presentedTime = completedDrawable.presentedTime
                resultLock.unlock()
                presented.signal()
            }
            commandBuffer.present(drawable)
            commandBuffer.commit()
            guard presented.wait(timeout: .now() + .seconds(1)) == .success else {
                throw PresentationValidationError.failed(
                    "CAMetalLayer priming present \(attempt) did not complete"
                )
            }
            resultLock.lock()
            let visible = presentedTime.isFinite && presentedTime > 0.0
            resultLock.unlock()
            if visible {
                return
            }
            Thread.sleep(forTimeInterval: 1.0 / 120.0)
        }
        throw PresentationValidationError.failed(
            "CAMetalLayer never produced a WindowServer-visible priming frame"
        )
    }

    private func waitForPresentationCallbacks(
        presenter: MetalFrameGenerationPresenter,
        throughSourceFrameID: UInt64,
        timeout: CFTimeInterval
    ) -> Bool {
        let deadline = CACurrentMediaTime() + timeout
        repeat {
            let entries = presenter.validationTimelineSnapshot().filter {
                $0.sourceFrameID == throughSourceFrameID
            }
            if entries.contains(where: { $0.frameKind == "real" })
                    && entries.allSatisfy({ $0.outcome != "submitted" }) {
                return true
            }
            Thread.sleep(forTimeInterval: 0.005)
        } while CACurrentMediaTime() < deadline
        return false
    }

    private func diagnosticRecord(_ item: MetalFrameGenerationDiagnosticSnapshot) -> [String: Any] {
        [
            "presentPath": item.presentPath,
            "sourceFrameID": item.sourceFrameID,
            "frameKind": item.frameKind,
            "displayUpdateID": item.displayUpdateID,
            "targetTimestamp": item.targetTimestamp,
            "targetPresentationTimestamp": item.targetPresentationTimestamp,
            "cpuCommitTime": item.cpuCommitTime,
            "sourceEnqueueTime": item.sourceEnqueueTime,
            "sourceCpuWaitMilliseconds": item.sourceCpuWaitTime * 1_000.0,
            "sourceGpuStartTime": item.sourceGpuStartTime,
            "sourceGpuEndTime": item.sourceGpuEndTime,
            "sourceGpuDurationMilliseconds": item.sourceGpuEndTime > item.sourceGpuStartTime
                ? (item.sourceGpuEndTime - item.sourceGpuStartTime) * 1_000.0
                : 0.0,
            "gpuStartTime": item.gpuStartTime,
            "gpuEndTime": item.gpuEndTime,
            "gpuDurationMilliseconds": item.gpuEndTime > item.gpuStartTime
                ? (item.gpuEndTime - item.gpuStartTime) * 1_000.0
                : 0.0,
            "gpuCompletionTime": item.gpuCompletionTime,
            "presentedTime": item.presentedTime,
            "outcome": item.outcome
        ]
    }

    private func writeRawTimeline(_ timeline: [MetalFrameGenerationDiagnosticSnapshot]) throws {
        let presentPaths = Set(timeline.map(\.presentPath))
        let data = try JSONSerialization.data(
            withJSONObject: [
                "status": "captured",
                "presentPath": presentPaths.count == 1 ? presentPaths.first! : "mixed",
                "timeline": timeline.map(diagnosticRecord)
            ],
            options: [.prettyPrinted, .sortedKeys]
        )
        try data.write(to: outputDirectory.appendingPathComponent("timeline-raw.json"))
    }

    private func validateAndWrite(
        timeline: [MetalFrameGenerationDiagnosticSnapshot],
        initialWarmupSourceCount: Int,
        steadySourceCountPerPhase: Int,
        resizeWarmupSourceCount: Int,
        preferredFrameLatency: CFTimeInterval,
        shutdownDuration: CFTimeInterval
    ) throws {
        let preResizeSteadyRange = UInt64(initialWarmupSourceCount + 1)
                ... UInt64(initialWarmupSourceCount + steadySourceCountPerPhase)
        let postResizeSteadyStart = initialWarmupSourceCount
                + steadySourceCountPerPhase
                + resizeWarmupSourceCount
                + 1
        let postResizeSteadyRange = UInt64(postResizeSteadyStart)
                ... UInt64(postResizeSteadyStart + steadySourceCountPerPhase - 1)
        let steadyRanges = [
            ("pre-resize", preResizeSteadyRange),
            ("post-resize", postResizeSteadyRange)
        ]
        let measuredSourceCount = steadySourceCountPerPhase * steadyRanges.count
        func isMeasuredSource(_ sourceFrameID: UInt64) -> Bool {
            steadyRanges.contains { $0.1.contains(sourceFrameID) }
        }

        let measuredTimeline = timeline.filter { isMeasuredSource($0.sourceFrameID) }
        let nonPresentedMeasured = measuredTimeline.filter { $0.outcome != "presented" }
        guard nonPresentedMeasured.isEmpty else {
            let failures = nonPresentedMeasured.map {
                "\($0.sourceFrameID)/\($0.frameKind)=\($0.outcome)"
            }.joined(separator: ",")
            throw PresentationValidationError.failed(
                "Steady-state presentation failures: \(failures)"
            )
        }
        let presented = timeline.filter {
            $0.outcome == "presented"
                && isMeasuredSource($0.sourceFrameID)
        }
        let real = presented.filter { $0.frameKind == "real" }
        let generated = presented.filter { $0.frameKind == "generated" }
        guard real.count == measuredSourceCount else {
            throw PresentationValidationError.failed(
                "Expected \(measuredSourceCount) steady real frames, found \(real.count)"
            )
        }
        guard generated.count == measuredSourceCount else {
            throw PresentationValidationError.failed(
                "Expected \(measuredSourceCount) steady generated frames, found \(generated.count)"
            )
        }
        for (_, range) in steadyRanges {
            for sourceID in range {
                let source = presented.filter { $0.sourceFrameID == sourceID }
                guard source.filter({ $0.frameKind == "real" }).count == 1,
                      source.filter({ $0.frameKind == "generated" }).count == 1 else {
                    throw PresentationValidationError.failed(
                        "Source \(sourceID) did not present exactly one generated/real pair"
                    )
                }
            }
        }
        guard shutdownDuration < 2.0 else {
            throw PresentationValidationError.failed("Shutdown took \(shutdownDuration)s")
        }
        let presentPaths = Set(timeline.map(\.presentPath))
        guard presentPaths.count == 1, let presentPath = presentPaths.first else {
            throw PresentationValidationError.failed(
                "Expected one presenter path, found \(presentPaths.sorted())"
            )
        }
        let expectedPresentPath = ProcessInfo.processInfo.environment[
            "METALLUM_VALIDATE_METAL4_PRESENT"
        ] == "1" ? "metal4" : "metal3"
        guard presentPath == expectedPresentPath else {
            throw PresentationValidationError.failed(
                "Requested \(expectedPresentPath), but presenter used \(presentPath)"
            )
        }

        func positiveIntervals(_ values: [CFTimeInterval]) -> [CFTimeInterval] {
            let ordered = values.sorted()
            return zip(ordered.dropFirst(), ordered).compactMap { current, previous in
                let delta = current - previous
                return delta.isFinite && delta > 0.0 ? delta : nil
            }
        }

        func averageInterval(_ intervals: [CFTimeInterval]) -> CFTimeInterval {
            return intervals.isEmpty ? 0.0 : intervals.reduce(0.0, +) / Double(intervals.count)
        }

        func percentile(_ values: [Double], _ fraction: Double) -> Double {
            let ordered = values.filter(\.isFinite).sorted()
            guard !ordered.isEmpty else { return 0.0 }
            let index = Int((Double(ordered.count - 1) * fraction).rounded(.up))
            return ordered[min(max(index, 0), ordered.count - 1)]
        }

        let sourceIntervals = steadyRanges.flatMap { _, range in
            positiveIntervals(real.filter {
                range.contains($0.sourceFrameID)
            }.map(\.presentedTime))
        }
        let presentIntervals = steadyRanges.flatMap { _, range in
            positiveIntervals(presented.filter {
                range.contains($0.sourceFrameID)
            }.map(\.presentedTime))
        }
        let sampledUpdateIntervals = steadyRanges.flatMap { _, range in
            positiveIntervals(measuredTimeline.filter {
                range.contains($0.sourceFrameID)
            }.map(\.targetTimestamp))
        }
        let sourceInterval = averageInterval(sourceIntervals)
        let presentInterval = averageInterval(presentIntervals)
        let sourceFramesPerSecond = sourceInterval > 0.0 ? 1.0 / sourceInterval : 0.0
        let presentedFramesPerSecond = presentInterval > 0.0 ? 1.0 / presentInterval : 0.0
        let sampledUpdateInterval = averageInterval(sampledUpdateIntervals)
        let sampledDisplayUpdatesPerSecond = sampledUpdateInterval > 0.0
            ? 1.0 / sampledUpdateInterval
            : 0.0
        let steadyPhaseRates = steadyRanges.map { name, range in
            let phaseReal = real.filter { range.contains($0.sourceFrameID) }
            let phasePresented = presented.filter { range.contains($0.sourceFrameID) }
            let phaseSourceInterval = averageInterval(
                positiveIntervals(phaseReal.map(\.presentedTime))
            )
            let phasePresentInterval = averageInterval(
                positiveIntervals(phasePresented.map(\.presentedTime))
            )
            return (
                name,
                phaseSourceInterval > 0.0 ? 1.0 / phaseSourceInterval : 0.0,
                phasePresentInterval > 0.0 ? 1.0 / phasePresentInterval : 0.0
            )
        }
        if nominalDisplayUpdatesPerSecond >= 100.0 {
            for (phase, phaseSourceFps, phasePresentFps) in steadyPhaseRates {
                guard phaseSourceFps >= 58.0 else {
                    throw PresentationValidationError.failed(
                        "\(phase) 120 Hz source cadence regressed to \(phaseSourceFps) FPS"
                    )
                }
                guard phasePresentFps >= 116.0 else {
                    throw PresentationValidationError.failed(
                        "\(phase) 120 Hz present cadence regressed to \(phasePresentFps) FPS"
                    )
                }
            }
        }

        var updateIDs = Set<UInt64>()
        for item in presented {
            guard item.sourceFrameID > 0,
                  item.displayUpdateID > 0,
                  item.targetTimestamp > 0,
                  item.targetPresentationTimestamp > 0,
                  item.cpuCommitTime > 0,
                  item.cpuCommitTime <= item.targetTimestamp,
                  item.gpuCompletionTime > 0,
                  item.presentedTime > 0,
                  updateIDs.insert(item.displayUpdateID).inserted else {
                throw PresentationValidationError.failed(
                    "Invalid or duplicate presented diagnostic for update \(item.displayUpdateID)"
                )
            }
        }

        for sourceID in Set(presented.map(\.sourceFrameID)) {
            let source = presented.filter { $0.sourceFrameID == sourceID }
            if let generatedItem = source.first(where: { $0.frameKind == "generated" }),
               let realItem = source.first(where: { $0.frameKind == "real" }) {
                guard generatedItem.displayUpdateID < realItem.displayUpdateID,
                      generatedItem.presentedTime <= realItem.presentedTime else {
                    throw PresentationValidationError.failed(
                        "Generated/real order violated for source \(sourceID)"
                    )
                }
            }
        }

        let measuredDiagnostics = timeline.filter {
            isMeasuredSource($0.sourceFrameID)
                && $0.gpuStartTime > 0.0
                && $0.gpuEndTime > $0.gpuStartTime
        }
        let generatedGpuMilliseconds = measuredDiagnostics
            .filter { $0.frameKind == "generated" }
            .map { ($0.gpuEndTime - $0.gpuStartTime) * 1_000.0 }
        let realGpuMilliseconds = measuredDiagnostics
            .filter { $0.frameKind == "real" }
            .map { ($0.gpuEndTime - $0.gpuStartTime) * 1_000.0 }
        var sourceGpuByFrame: [UInt64: Double] = [:]
        var sourceEnqueueByFrame: [UInt64: CFTimeInterval] = [:]
        var sourceCpuWaitByFrame: [UInt64: Double] = [:]
        var presentGpuByFrame: [UInt64: Double] = [:]
        var presentKindsByFrame: [UInt64: Set<String>] = [:]
        for item in measuredDiagnostics {
            if item.sourceGpuEndTime > item.sourceGpuStartTime {
                sourceGpuByFrame[item.sourceFrameID] =
                    (item.sourceGpuEndTime - item.sourceGpuStartTime) * 1_000.0
            }
            if item.sourceEnqueueTime > 0.0 {
                sourceEnqueueByFrame[item.sourceFrameID] = item.sourceEnqueueTime
                sourceCpuWaitByFrame[item.sourceFrameID] = item.sourceCpuWaitTime * 1_000.0
            }
            presentGpuByFrame[item.sourceFrameID, default: 0.0] +=
                (item.gpuEndTime - item.gpuStartTime) * 1_000.0
            presentKindsByFrame[item.sourceFrameID, default: []].insert(item.frameKind)
        }
        var totalGpuMilliseconds: [Double] = []
        for (sourceFrameID, sourceGpu) in sourceGpuByFrame
                where presentKindsByFrame[sourceFrameID] == Set(["generated", "real"]) {
            totalGpuMilliseconds.append(sourceGpu + (presentGpuByFrame[sourceFrameID] ?? 0.0))
        }
        let sourceGpuMilliseconds = Array(sourceGpuByFrame.values)
        let orderedSourceEnqueues = sourceEnqueueByFrame.sorted { $0.key < $1.key }
        let sourceCpuIntervals = zip(
            orderedSourceEnqueues.dropFirst(), orderedSourceEnqueues
        ).compactMap { current, previous -> Double? in
            guard current.key == previous.key + 1 else { return nil }
            let interval = (current.value - previous.value) * 1_000.0
            return interval.isFinite && interval > 0.0 ? interval : nil
        }
        let sourceCpuWaitMilliseconds = Array(sourceCpuWaitByFrame.values)
        let generatedGpuAverage = generatedGpuMilliseconds.isEmpty
            ? 0.0
            : generatedGpuMilliseconds.reduce(0.0, +) / Double(generatedGpuMilliseconds.count)
        let realGpuAverage = realGpuMilliseconds.isEmpty
            ? 0.0
            : realGpuMilliseconds.reduce(0.0, +) / Double(realGpuMilliseconds.count)
        let sourceGpuAverage = sourceGpuMilliseconds.isEmpty
            ? 0.0
            : sourceGpuMilliseconds.reduce(0.0, +) / Double(sourceGpuMilliseconds.count)
        let totalGpuAverage = totalGpuMilliseconds.isEmpty
            ? 0.0
            : totalGpuMilliseconds.reduce(0.0, +) / Double(totalGpuMilliseconds.count)
        let generatedGpuP95 = percentile(generatedGpuMilliseconds, 0.95)
        let realGpuP95 = percentile(realGpuMilliseconds, 0.95)
        let sourceGpuP95 = percentile(sourceGpuMilliseconds, 0.95)
        let totalGpuP95 = percentile(totalGpuMilliseconds, 0.95)
        let sourceCpuIntervalP95 = percentile(sourceCpuIntervals, 0.95)
        let sourceCpuWaitP95 = percentile(sourceCpuWaitMilliseconds, 0.95)
        let transitionTimeline = timeline.filter { !isMeasuredSource($0.sourceFrameID) }
        let transitionNotPresented = transitionTimeline.filter { $0.outcome != "presented" }
        guard transitionNotPresented.isEmpty else {
            let failures = transitionNotPresented.map {
                "\($0.sourceFrameID)/\($0.frameKind)=\($0.outcome)"
            }.joined(separator: ",")
            throw PresentationValidationError.failed(
                "Transition presentation failures: \(failures)"
            )
        }
        let resizeWarmupRange = UInt64(initialWarmupSourceCount + steadySourceCountPerPhase + 1)
                ... UInt64(initialWarmupSourceCount + steadySourceCountPerPhase
                    + resizeWarmupSourceCount)
        let resizeFirstRealPresentedSourceID = resizeWarmupRange.first { sourceID in
            timeline.contains {
                $0.sourceFrameID == sourceID
                    && $0.frameKind == "real"
                    && $0.outcome == "presented"
            }
        }
        let resizeFirstRealPresentedOffset = resizeFirstRealPresentedSourceID.map {
            Int($0 - resizeWarmupRange.lowerBound)
        }
        let steadyPhaseRecords: [[String: Any]] = steadyPhaseRates.map {
            [
                "name": $0.0,
                "sourceFramesPerSecond": $0.1,
                "presentedFramesPerSecond": $0.2
            ]
        }
        let records = timeline.map(diagnosticRecord)
        let report: [String: Any] = [
            "status": "passed",
            "usedRealCAMetalLayer": true,
            "usedCAMetalDisplayLinkDrawable": true,
            "usedTargetedPresent": false,
            "usedComputerUse": false,
            "usedSystemScreenshot": false,
            "presentPath": presentPath,
            "maximumDrawableCount": layer.maximumDrawableCount,
            "preferredFrameLatency": preferredFrameLatency,
            "sourceFrames": measuredSourceCount,
            "initialWarmupSourceFrames": initialWarmupSourceCount,
            "resizeWarmupSourceFrames": resizeWarmupSourceCount,
            "steadySourceFramesPerPhase": steadySourceCountPerPhase,
            "steadyPhases": steadyPhaseRecords,
            "transitionNotPresentedCount": transitionNotPresented.count,
            "resizeFirstRealPresentedOffset": resizeFirstRealPresentedOffset ?? -1,
            "realPresented": real.count,
            "generatedPresented": generated.count,
            "nominalDisplayUpdatesPerSecond": nominalDisplayUpdatesPerSecond,
            "sampledDisplayUpdatesPerSecond": sampledDisplayUpdatesPerSecond,
            "sourceFramesPerSecond": sourceFramesPerSecond,
            "presentedFramesPerSecond": presentedFramesPerSecond,
            "generatedGpuAverageMilliseconds": generatedGpuAverage,
            "generatedGpuP95Milliseconds": generatedGpuP95,
            "generatedGpuP95MarginTo8_33Milliseconds": (1_000.0 / 120.0) - generatedGpuP95,
            "realGpuAverageMilliseconds": realGpuAverage,
            "realGpuP95Milliseconds": realGpuP95,
            "sourceGpuAverageMilliseconds": sourceGpuAverage,
            "sourceGpuP95Milliseconds": sourceGpuP95,
            "sourceCpuIntervalP95Milliseconds": sourceCpuIntervalP95,
            "sourceCpuIntervalP95MarginTo16_67Milliseconds":
                (1_000.0 / 60.0) - sourceCpuIntervalP95,
            "sourceCpuWaitP95Milliseconds": sourceCpuWaitP95,
            "totalGpuAverageMilliseconds": totalGpuAverage,
            "totalGpuP95Milliseconds": totalGpuP95,
            "totalGpuP95MarginTo16_67Milliseconds": (1_000.0 / 60.0) - totalGpuP95,
            "resizeExercised": true,
            "shutdownDurationSeconds": shutdownDuration,
            "timeline": records
        ]
        let data = try JSONSerialization.data(
            withJSONObject: report,
            options: [.prettyPrinted, .sortedKeys]
        )
        try data.write(to: outputDirectory.appendingPathComponent("timeline.json"))
        print(
            "MetalFrameGenerationPresentationValidation PASS "
                + "real=\(real.count) generated=\(generated.count) "
                + "sourceFps=\(String(format: "%.1f", sourceFramesPerSecond)) "
                + "presentFps=\(String(format: "%.1f", presentedFramesPerSecond)) "
                + "sourceCpuWaitP95=\(String(format: "%.2f", sourceCpuWaitP95))ms "
                + "totalGpuP95=\(String(format: "%.2f", totalGpuP95))ms "
                + "generatedGpuP95=\(String(format: "%.2f", generatedGpuP95))ms "
                + "shutdown=\(String(format: "%.4f", shutdownDuration))s"
        )
    }
}

@main
private struct PresentationValidationMain {
    static func main() {
        if #available(macOS 26.0, *) {
            let output = CommandLine.arguments.count > 1
                ? URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
                : URL(fileURLWithPath: "build/metal-validation/presentation-current", isDirectory: true)
            // Metal 4 migration M4: opt in to the MTL4 present path so this same
            // harness measures both branches. In the game this switch comes from
            // metallum.opt.metal4Present via Java; here it is an env var because
            // the harness builds the presenter directly. Unset means the Metal 3
            // path, i.e. the default behaviour of this task is unchanged.
            if ProcessInfo.processInfo.environment["METALLUM_VALIDATE_METAL4_PRESENT"] == "1" {
                metallum_set_metal4_present_enabled(1)
                print("MetalFrameGenerationPresentationValidation: Metal 4 present path requested")
            }
            do {
                let runner = try ValidationRunner(outputDirectory: output)
                runner.run()
            } catch {
                fputs("MetalFrameGenerationPresentationValidation FAILED: \(error)\n", stderr)
                exit(1)
            }
        } else {
            fputs("MetalFrameGenerationPresentationValidation SKIPPED: macOS 26 is required\n", stderr)
            exit(77)
        }
    }
}
