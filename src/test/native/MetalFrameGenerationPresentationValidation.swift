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
        self.app = NSApplication.shared
        // WindowServer silently drops presents for occluded layers, reporting
        // presentedTime == 0 for the whole run. Center the window on the main
        // screen and float it so back-to-back CI runs and unrelated desktop
        // windows cannot occlude the validation surface.
        let screenFrame = NSScreen.main?.visibleFrame
                ?? NSRect(x: 0, y: 0, width: 1280, height: 800)
        let contentRect = NSRect(
            x: screenFrame.midX - 160,
            y: screenFrame.midY - 120,
            width: 320,
            height: 240
        )
        self.window = NSWindow(
            contentRect: contentRect,
            styleMask: [.titled, .closable, .resizable],
            backing: .buffered,
            defer: false
        )
        self.window.level = .floating
        self.layer = CAMetalLayer()

        try FileManager.default.createDirectory(
            at: outputDirectory,
            withIntermediateDirectories: true
        )
        layer.device = device
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
        layer.drawableSize = CGSize(width: 320, height: 240)
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

    private func makeInputs(width: Int, height: Int) throws -> (
        scene: MTLTexture,
        ui: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture
    ) {
        let colorUsage: MTLTextureUsage = [.renderTarget, .shaderRead, .shaderWrite]
        return (
            try makeTexture(format: .bgra8Unorm, width: width, height: height, usage: colorUsage),
            try makeTexture(format: .bgra8Unorm, width: width, height: height, usage: colorUsage),
            try makeTexture(
                format: .depth32Float,
                width: width,
                height: height,
                usage: [.renderTarget, .shaderRead]
            ),
            try makeTexture(
                format: .rg16Float,
                width: width,
                height: height,
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
        scenePass.depthAttachment.texture = inputs.depth
        scenePass.depthAttachment.loadAction = .clear
        scenePass.depthAttachment.storeAction = .store
        scenePass.depthAttachment.clearDepth = 0.75
        guard let sceneEncoder = commandBuffer.makeRenderCommandEncoder(descriptor: scenePass) else {
            throw PresentationValidationError.failed("Could not encode source clear")
        }
        sceneEncoder.endEncoding()

        let uiPass = MTLRenderPassDescriptor()
        uiPass.colorAttachments[0].texture = inputs.ui
        uiPass.colorAttachments[0].loadAction = .clear
        uiPass.colorAttachments[0].storeAction = .store
        uiPass.colorAttachments[0].clearColor = MTLClearColor(
            red: 0.05,
            green: Double(frame % 2) * 0.1,
            blue: 0.15,
            alpha: 1.0
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
        var width = 320
        var height = 240
        var inputs = try makeInputs(width: width, height: height)
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

        let warmupSourceCount = 3
        let measuredSourceCount = 10
        for sourceIndex in 0..<(warmupSourceCount + measuredSourceCount) {
            let measuredFrame = sourceIndex - warmupSourceCount
            if measuredFrame == 5 {
                width = 400
                height = 300
                inputs = try makeInputs(width: width, height: height)
                DispatchQueue.main.sync {
                    self.window.setContentSize(NSSize(width: width, height: height))
                    self.layer.drawableSize = CGSize(width: width, height: height)
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
                aspectRatio: Float(width) / Float(height),
                sourceDeltaSeconds: 1.0 / 60.0,
                reset: sourceIndex == 0 || measuredFrame == 5,
                globalFence: nil
            )
            guard accepted == 1 else {
                throw PresentationValidationError.failed("Presenter rejected source frame \(sourceIndex)")
            }
            commandBuffer.commit()
            guard presenter.waitUntilIdle(timeout: 3.0) else {
                throw PresentationValidationError.failed(
                    "Source frame \(sourceIndex) did not reach a terminal ownership state"
                )
            }
            Thread.sleep(forTimeInterval: 1.0 / 120.0)
        }

        let timeline = presenter.validationTimelineSnapshot()
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
        guard real.count >= 8 else {
            throw PresentationValidationError.failed("Expected at least 8 presented real frames, found \(real.count)")
        }
        guard generated.count >= 4 else {
            throw PresentationValidationError.failed(
                "Expected at least 4 generated presentations, found \(generated.count)"
            )
        }
        guard shutdownDuration < 2.0 else {
            throw PresentationValidationError.failed("Shutdown took \(shutdownDuration)s")
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

        let records: [[String: Any]] = timeline.map {
            [
                "sourceFrameID": $0.sourceFrameID,
                "frameKind": $0.frameKind,
                "displayUpdateID": $0.displayUpdateID,
                "targetTimestamp": $0.targetTimestamp,
                "targetPresentationTimestamp": $0.targetPresentationTimestamp,
                "cpuCommitTime": $0.cpuCommitTime,
                "gpuCompletionTime": $0.gpuCompletionTime,
                "presentedTime": $0.presentedTime,
                "outcome": $0.outcome
            ]
        }
        let report: [String: Any] = [
            "status": "passed",
            "usedRealCAMetalLayer": true,
            "usedCAMetalDisplayLinkDrawable": true,
            "usedTargetedPresent": false,
            "usedComputerUse": false,
            "usedSystemScreenshot": false,
            "sourceFrames": measuredSourceCount,
            "warmupSourceFrames": warmupSourceCount,
            "realPresented": real.count,
            "generatedPresented": generated.count,
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
