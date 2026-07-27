import Foundation
import Metal
import MetalFX

private enum PerformanceFailure: Error, CustomStringConvertible {
    case message(String)

    var description: String {
        switch self {
        case .message(let message): return message
        }
    }
}

private struct PerformanceCase {
    let name: String
    let inputWidth: Int
    let inputHeight: Int
    let outputWidth: Int
    let outputHeight: Int
}

@available(macOS 26.0, *)
private final class PerformanceRunner {
    private let device: MTLDevice
    private let queue: MTLCommandQueue
    private let outputDirectory: URL
    private let warmupCount = 5
    private let measuredCount = 30

    init(outputDirectory: URL) throws {
        guard let device = MTLCreateSystemDefaultDevice(),
              let queue = device.makeCommandQueue() else {
            throw PerformanceFailure.message("Metal device or command queue unavailable")
        }
        self.device = device
        self.queue = queue
        self.outputDirectory = outputDirectory
        try FileManager.default.createDirectory(
            at: outputDirectory,
            withIntermediateDirectories: true
        )
    }

    private func makeTexture(
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        usage: MTLTextureUsage,
        label: String
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
            throw PerformanceFailure.message("Could not allocate \(label)")
        }
        texture.label = label
        return texture
    }

    private func clearColor(_ texture: MTLTexture, color: MTLClearColor) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw PerformanceFailure.message("Could not create clear command buffer")
        }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = color
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw PerformanceFailure.message("Could not create color clear encoder")
        }
        encoder.endEncoding()
        try commitAndWait(commandBuffer, label: "clear \(texture.label ?? "color")")
    }

    private func clearDepth(_ texture: MTLTexture) throws {
        guard let commandBuffer = queue.makeCommandBuffer() else {
            throw PerformanceFailure.message("Could not create depth clear command buffer")
        }
        let pass = MTLRenderPassDescriptor()
        pass.depthAttachment.texture = texture
        pass.depthAttachment.loadAction = .clear
        pass.depthAttachment.storeAction = .store
        pass.depthAttachment.clearDepth = 0.75
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw PerformanceFailure.message("Could not create depth clear encoder")
        }
        encoder.endEncoding()
        try commitAndWait(commandBuffer, label: "clear depth")
    }

    private func commitAndWait(_ commandBuffer: MTLCommandBuffer, label: String) throws {
        commandBuffer.label = label
        commandBuffer.commit()
        commandBuffer.waitUntilCompleted()
        guard commandBuffer.status == .completed else {
            throw PerformanceFailure.message(
                "\(label) failed: \(String(describing: commandBuffer.error))"
            )
        }
    }

    private func measure(
        label: String,
        encode: (MTLCommandBuffer, Int) -> Void
    ) throws -> [Double] {
        var samples: [Double] = []
        for index in 0..<(warmupCount + measuredCount) {
            guard let commandBuffer = queue.makeCommandBuffer() else {
                throw PerformanceFailure.message("Could not create \(label) command buffer")
            }
            encode(commandBuffer, index)
            try commitAndWait(commandBuffer, label: "\(label) \(index)")
            guard commandBuffer.gpuEndTime > commandBuffer.gpuStartTime else {
                throw PerformanceFailure.message("\(label) returned invalid GPU timestamps")
            }
            if index >= warmupCount {
                samples.append((commandBuffer.gpuEndTime - commandBuffer.gpuStartTime) * 1_000.0)
            }
        }
        return samples
    }

    private func statistics(_ samples: [Double]) -> [String: Any] {
        let ordered = samples.sorted()
        let average = ordered.reduce(0.0, +) / Double(max(ordered.count, 1))
        let p95Index = Int((Double(max(ordered.count - 1, 0)) * 0.95).rounded(.up))
        let p95 = ordered.isEmpty ? 0.0 : ordered[min(p95Index, ordered.count - 1)]
        return [
            "sampleCount": ordered.count,
            "averageMilliseconds": average,
            "p95Milliseconds": p95,
            "minimumMilliseconds": ordered.first ?? 0.0,
            "maximumMilliseconds": ordered.last ?? 0.0,
            "p95MarginTo8_33Milliseconds": (1_000.0 / 120.0) - p95,
            "p95ShareOf16_67MillisecondSourceBudget": p95 / (1_000.0 / 60.0)
        ]
    }

    private func runCase(_ item: PerformanceCase) throws -> [String: Any] {
        let colorFormat = MTLPixelFormat.bgra8Unorm
        let depthFormat = MTLPixelFormat.depth32Float
        let motionFormat = MTLPixelFormat.rg16Float

        let temporalDescriptor = MTLFXTemporalScalerDescriptor()
        temporalDescriptor.colorTextureFormat = colorFormat
        temporalDescriptor.depthTextureFormat = depthFormat
        temporalDescriptor.motionTextureFormat = motionFormat
        temporalDescriptor.outputTextureFormat = colorFormat
        temporalDescriptor.inputWidth = item.inputWidth
        temporalDescriptor.inputHeight = item.inputHeight
        temporalDescriptor.outputWidth = item.outputWidth
        temporalDescriptor.outputHeight = item.outputHeight
        temporalDescriptor.isAutoExposureEnabled = false
        temporalDescriptor.requiresSynchronousInitialization = true
        guard let temporal = temporalDescriptor.makeTemporalScaler(device: device) else {
            throw PerformanceFailure.message("Could not create Temporal scaler for \(item.name)")
        }

        let interpolationDescriptor = MTLFXFrameInterpolatorDescriptor()
        interpolationDescriptor.colorTextureFormat = colorFormat
        interpolationDescriptor.depthTextureFormat = depthFormat
        interpolationDescriptor.motionTextureFormat = motionFormat
        interpolationDescriptor.outputTextureFormat = colorFormat
        interpolationDescriptor.inputWidth = item.inputWidth
        interpolationDescriptor.inputHeight = item.inputHeight
        interpolationDescriptor.outputWidth = item.outputWidth
        interpolationDescriptor.outputHeight = item.outputHeight
        interpolationDescriptor.scaler = temporal
        guard let interpolator = interpolationDescriptor.makeFrameInterpolator(device: device) else {
            throw PerformanceFailure.message("Could not create FrameInterpolator for \(item.name)")
        }

        let inputColor = try makeTexture(
            format: colorFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: temporal.colorTextureUsage.union(.renderTarget),
            label: "\(item.name) temporal input"
        )
        let depth = try makeTexture(
            format: depthFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: temporal.depthTextureUsage.union(interpolator.depthTextureUsage).union(.renderTarget),
            label: "\(item.name) depth"
        )
        let motion = try makeTexture(
            format: motionFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: temporal.motionTextureUsage.union(interpolator.motionTextureUsage).union(.renderTarget),
            label: "\(item.name) motion"
        )
        let temporalOutput = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: temporal.outputTextureUsage.union(.shaderRead).union(.renderTarget),
            label: "\(item.name) temporal output"
        )
        let previousColor = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: interpolator.colorTextureUsage.union(.renderTarget),
            label: "\(item.name) previous color"
        )
        let interpolationOutput = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: interpolator.outputTextureUsage.union(.shaderRead).union(.renderTarget),
            label: "\(item.name) interpolation output"
        )

        try clearColor(inputColor, color: MTLClearColor(red: 0.2, green: 0.3, blue: 0.5, alpha: 1.0))
        try clearColor(previousColor, color: MTLClearColor(red: 0.18, green: 0.3, blue: 0.52, alpha: 1.0))
        try clearDepth(depth)
        try clearColor(motion, color: MTLClearColor(red: -0.01, green: 0.0, blue: 0.0, alpha: 0.0))

        temporal.colorTexture = inputColor
        temporal.depthTexture = depth
        temporal.motionTexture = motion
        temporal.outputTexture = temporalOutput
        temporal.inputContentWidth = item.inputWidth
        temporal.inputContentHeight = item.inputHeight
        temporal.jitterOffsetX = 0.0
        temporal.jitterOffsetY = 0.0
        temporal.motionVectorScaleX = Float(item.inputWidth) * 0.5
        temporal.motionVectorScaleY = Float(item.inputHeight) * 0.5
        temporal.isDepthReversed = true

        let temporalSamples = try measure(label: "\(item.name) Temporal") { commandBuffer, index in
            temporal.reset = index == 0
            temporal.encode(commandBuffer: commandBuffer)
        }

        interpolator.colorTexture = temporalOutput
        interpolator.prevColorTexture = previousColor
        interpolator.uiTexture = nil
        interpolator.depthTexture = depth
        interpolator.motionTexture = motion
        interpolator.outputTexture = interpolationOutput
        interpolator.isUITextureComposited = false
        interpolator.jitterOffsetX = 0.0
        interpolator.jitterOffsetY = 0.0
        interpolator.motionVectorScaleX = Float(item.inputWidth) * 0.5
        interpolator.motionVectorScaleY = Float(item.inputHeight) * 0.5
        interpolator.fieldOfView = 70.0
        interpolator.nearPlane = 0.05
        interpolator.farPlane = 1_000.0
        interpolator.aspectRatio = Float(item.outputWidth) / Float(item.outputHeight)
        interpolator.deltaTime = 1.0 / 60.0
        interpolator.isDepthReversed = true

        let interpolationSamples = try measure(label: "\(item.name) FrameInterpolator") {
            commandBuffer, index in
            interpolator.shouldResetHistory = index == 0
            interpolator.encode(commandBuffer: commandBuffer)
        }

        return [
            "name": item.name,
            "inputWidth": item.inputWidth,
            "inputHeight": item.inputHeight,
            "outputWidth": item.outputWidth,
            "outputHeight": item.outputHeight,
            "outputMegapixels": Double(item.outputWidth * item.outputHeight) / 1_000_000.0,
            "temporal": statistics(temporalSamples),
            "frameInterpolator": statistics(interpolationSamples)
        ]
    }

    func run() throws {
        let cases = [
            PerformanceCase(name: "headroom-1280", inputWidth: 858, inputHeight: 482, outputWidth: 1280, outputHeight: 720),
            PerformanceCase(name: "bounded-1440", inputWidth: 964, inputHeight: 542, outputWidth: 1440, outputHeight: 808),
            PerformanceCase(name: "qa-1708", inputWidth: 1144, inputHeight: 643, outputWidth: 1708, outputHeight: 960),
            PerformanceCase(name: "retina-3024", inputWidth: 2026, inputHeight: 1119, outputWidth: 3024, outputHeight: 1670)
        ]
        var results: [[String: Any]] = []
        for item in cases {
            print("[performance] \(item.name) \(item.inputWidth)x\(item.inputHeight) -> \(item.outputWidth)x\(item.outputHeight)")
            let result = try runCase(item)
            results.append(result)
            let temporal = result["temporal"] as? [String: Any]
            let frameInterpolator = result["frameInterpolator"] as? [String: Any]
            print(String(format: "[performance] %@ Temporal %.2f ms p95; FrameInterpolator %.2f ms p95",
                         item.name,
                         temporal?["p95Milliseconds"] as? Double ?? 0.0,
                         frameInterpolator?["p95Milliseconds"] as? Double ?? 0.0))
        }
        let summary: [String: Any] = [
            "status": "passed",
            "device": device.name,
            "warmupCount": warmupCount,
            "measuredCount": measuredCount,
            "usesWindow": false,
            "usedComputerUse": false,
            "cases": results
        ]
        let data = try JSONSerialization.data(
            withJSONObject: summary,
            options: [.prettyPrinted, .sortedKeys]
        )
        try data.write(to: outputDirectory.appendingPathComponent("summary.json"), options: .atomic)
    }
}

@main
private enum MetalFXPerformanceValidationMain {
    static func main() {
        guard #available(macOS 26.0, *) else {
            fputs("MetalFX performance validation SKIPPED: macOS 26 is required\n", stderr)
            exit(77)
        }
        let output = URL(
            fileURLWithPath: CommandLine.arguments.dropFirst().first
                ?? "build/metal-validation/performance-current",
            isDirectory: true
        )
        do {
            try PerformanceRunner(outputDirectory: output).run()
            print("MetalFX performance validation passed; artifacts: \(output.path)")
        } catch {
            fputs("MetalFX performance validation failed: \(error)\n", stderr)
            exit(1)
        }
    }
}
