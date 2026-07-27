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
        _ inputs: (scene: MTLTexture, ui: MTLTexture, depth: MTLTexture, motion: MTLTexture),
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
        // source is submitted. Drawables received during this startup edge can
        // legitimately call their handler with presentedTime == 0 and must
        // remain failures rather than being counted as warm-up successes.
        Thread.sleep(forTimeInterval: 0.5)
        var displayWidth = 1708
        var displayHeight = 960
        var sceneWidth = 1280
        var sceneHeight = 718
        var inputWidth = 858
        var inputHeight = 482
        var inputs = try makeInputs(
            sceneWidth: sceneWidth,
            sceneHeight: sceneHeight,
            uiWidth: displayWidth,
            uiHeight: displayHeight,
            inputWidth: inputWidth,
            inputHeight: inputHeight
        )
        guard let presenter = MetalFrameGenerationPresenter(
            device: device,
            layer: layer,
            sceneColor: inputs.scene,
            uiColor: inputs.ui,
            depth: inputs.depth,
            motion: inputs.motion
        ) else {
            throw PresentationValidationError.failed("Could not create frame-generation presenter")
        }
        self.presenter = presenter

        let warmupSourceCount = 10
        let measuredSourceCount = 60
        for sourceIndex in 0..<(warmupSourceCount + measuredSourceCount) {
            let measuredFrame = sourceIndex - warmupSourceCount
            if measuredFrame == measuredSourceCount / 2 {
                guard presenter.waitUntilIdle(timeout: 3.0) else {
                    throw PresentationValidationError.failed(
                        "Presenter did not drain before resize"
                    )
                }
                displayWidth = 1600
                displayHeight = 900
                sceneWidth = 1280
                sceneHeight = 720
                inputWidth = 858
                inputHeight = 482
                inputs = try makeInputs(
                    sceneWidth: sceneWidth,
                    sceneHeight: sceneHeight,
                    uiWidth: displayWidth,
                    uiHeight: displayHeight,
                    inputWidth: inputWidth,
                    inputHeight: inputHeight
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
            let accepted = presenter.encode(
                commandBuffer: commandBuffer,
                sceneColor: inputs.scene,
                uiColor: inputs.ui,
                depth: inputs.depth,
                motion: inputs.motion,
                jitterX: 0.0,
                jitterY: 0.0,
                fieldOfView: 70.0,
                nearPlane: 0.05,
                farPlane: 1000.0,
                aspectRatio: Float(displayWidth) / Float(displayHeight),
                sourceDeltaSeconds: 1.0 / 60.0,
                reset: sourceIndex == 0 || measuredFrame == 5,
                globalFence: nil
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
        self.presenter = nil
        try validateAndWrite(
            timeline: timeline,
            warmupSourceCount: warmupSourceCount,
            measuredSourceCount: measuredSourceCount,
            shutdownDuration: shutdownDuration
        )
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
        warmupSourceCount: Int,
        measuredSourceCount: Int,
        shutdownDuration: CFTimeInterval
    ) throws {
        let presented = timeline.filter {
            $0.outcome == "presented"
                && $0.sourceFrameID > UInt64(warmupSourceCount)
        }
        let real = presented.filter { $0.frameKind == "real" }
        let generated = presented.filter { $0.frameKind == "generated" }
        let minimumPresentedCount = Int(Double(measuredSourceCount) * 0.8)
        guard real.count >= minimumPresentedCount else {
            throw PresentationValidationError.failed(
                "Expected at least \(minimumPresentedCount) presented real frames, found \(real.count)"
            )
        }
        guard generated.count >= minimumPresentedCount else {
            throw PresentationValidationError.failed(
                "Expected at least \(minimumPresentedCount) generated presentations, found \(generated.count)"
            )
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

        func averagePositiveInterval(_ values: [CFTimeInterval]) -> CFTimeInterval {
            let ordered = values.sorted()
            let intervals = zip(ordered.dropFirst(), ordered).compactMap { current, previous in
                let delta = current - previous
                return delta.isFinite && delta > 0.0 ? delta : nil
            }
            return intervals.isEmpty ? 0.0 : intervals.reduce(0.0, +) / Double(intervals.count)
        }

        func percentile(_ values: [Double], _ fraction: Double) -> Double {
            let ordered = values.filter(\.isFinite).sorted()
            guard !ordered.isEmpty else { return 0.0 }
            let index = Int((Double(ordered.count - 1) * fraction).rounded(.up))
            return ordered[min(max(index, 0), ordered.count - 1)]
        }

        let sourceInterval = averagePositiveInterval(real.map(\.presentedTime))
        let presentInterval = averagePositiveInterval(presented.map(\.presentedTime))
        let sourceFramesPerSecond = sourceInterval > 0.0 ? 1.0 / sourceInterval : 0.0
        let presentedFramesPerSecond = presentInterval > 0.0 ? 1.0 / presentInterval : 0.0
        let sampledUpdateInterval = averagePositiveInterval(timeline.map(\.targetTimestamp))
        let sampledDisplayUpdatesPerSecond = sampledUpdateInterval > 0.0
            ? 1.0 / sampledUpdateInterval
            : 0.0
        if nominalDisplayUpdatesPerSecond >= 100.0 {
            guard sourceFramesPerSecond >= 55.0 else {
                throw PresentationValidationError.failed(
                    "120 Hz source cadence regressed to \(sourceFramesPerSecond) FPS"
                )
            }
            guard presentedFramesPerSecond >= 110.0 else {
                throw PresentationValidationError.failed(
                    "120 Hz present cadence regressed to \(presentedFramesPerSecond) FPS"
                )
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
            $0.sourceFrameID > UInt64(warmupSourceCount)
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
        let records = timeline.map(diagnosticRecord)
        let report: [String: Any] = [
            "status": "passed",
            "usedRealCAMetalLayer": true,
            "usedCAMetalDisplayLinkDrawable": true,
            "usedTargetedPresent": false,
            "usedComputerUse": false,
            "usedSystemScreenshot": false,
            "presentPath": presentPath,
            "sourceFrames": measuredSourceCount,
            "warmupSourceFrames": warmupSourceCount,
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
