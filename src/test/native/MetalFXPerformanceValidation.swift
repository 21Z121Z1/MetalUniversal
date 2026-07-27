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

private struct PresentationCase {
    let name: String
    let sceneWidth: Int
    let sceneHeight: Int
    let inputWidth: Int
    let inputHeight: Int
    let displayWidth: Int
    let displayHeight: Int
}

@available(macOS 26.0, *)
private final class Metal4FrameInterpolatorBenchmark {
    let interpolator: any MTL4FXFrameInterpolator

    private let queue: MTL4CommandQueue
    private let commandBuffer: MTL4CommandBuffer
    private let allocator: MTL4CommandAllocator
    private let residencySet: MTLResidencySet
    private let feedbackQueue: DispatchQueue

    init(device: MTLDevice, item: PerformanceCase) throws {
        let compilerDescriptor = MTL4CompilerDescriptor()
        compilerDescriptor.label = "MetalFX Performance Compiler"
        let compiler = try device.makeCompiler(descriptor: compilerDescriptor)
        let descriptor = MTLFXFrameInterpolatorDescriptor()
        descriptor.colorTextureFormat = .bgra8Unorm
        descriptor.depthTextureFormat = .depth32Float
        descriptor.motionTextureFormat = .rg16Float
        descriptor.outputTextureFormat = .bgra8Unorm
        descriptor.inputWidth = item.inputWidth
        descriptor.inputHeight = item.inputHeight
        descriptor.outputWidth = item.outputWidth
        descriptor.outputHeight = item.outputHeight
        guard let interpolator = descriptor.makeFrameInterpolator(device: device, compiler: compiler),
              let commandBuffer: MTL4CommandBuffer = device.makeCommandBuffer() else {
            throw PerformanceFailure.message("Could not create Metal 4 FrameInterpolator for \(item.name)")
        }
        let queueDescriptor = MTL4CommandQueueDescriptor()
        queueDescriptor.label = "MetalFX Performance Metal 4 Queue"
        let feedbackQueue = DispatchQueue(label: "metallum.performance.metal4-feedback")
        queueDescriptor.feedbackQueue = feedbackQueue
        let allocatorDescriptor = MTL4CommandAllocatorDescriptor()
        allocatorDescriptor.label = "MetalFX Performance Metal 4 Allocator"
        let residencyDescriptor = MTLResidencySetDescriptor()
        residencyDescriptor.label = "MetalFX Performance Metal 4 Residency"
        residencyDescriptor.initialCapacity = 8
        self.interpolator = interpolator
        self.queue = try device.makeMTL4CommandQueue(descriptor: queueDescriptor)
        self.commandBuffer = commandBuffer
        self.allocator = try device.makeCommandAllocator(descriptor: allocatorDescriptor)
        self.residencySet = try device.makeResidencySet(descriptor: residencyDescriptor)
        self.feedbackQueue = feedbackQueue
        self.queue.addResidencySet(self.residencySet)
    }

    func configure(
        color: MTLTexture,
        previousColor: MTLTexture,
        depth: MTLTexture,
        motion: MTLTexture,
        output: MTLTexture,
        item: PerformanceCase
    ) {
        residencySet.removeAllAllocations()
        residencySet.addAllocations([color, previousColor, depth, motion, output])
        residencySet.commit()
        residencySet.requestResidency()
        interpolator.colorTexture = color
        interpolator.prevColorTexture = previousColor
        interpolator.uiTexture = nil
        interpolator.depthTexture = depth
        interpolator.motionTexture = motion
        interpolator.outputTexture = output
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
    }

    func measure(warmupCount: Int, measuredCount: Int) throws -> [Double] {
        var samples: [Double] = []
        for index in 0..<(warmupCount + measuredCount) {
            allocator.reset()
            commandBuffer.beginCommandBuffer(allocator: allocator)
            interpolator.shouldResetHistory = index == 0
            interpolator.encode(commandBuffer: commandBuffer)
            commandBuffer.endCommandBuffer()
            let options = MTL4CommitOptions()
            let completed = DispatchSemaphore(value: 0)
            var feedbackError: Error?
            var gpuStartTime: CFTimeInterval = 0.0
            var gpuEndTime: CFTimeInterval = 0.0
            options.addFeedbackHandler { feedback in
                feedbackError = feedback.error
                gpuStartTime = feedback.gpuStartTime
                gpuEndTime = feedback.gpuEndTime
                completed.signal()
            }
            queue.commit([commandBuffer], options: options)
            guard completed.wait(timeout: .now() + 5.0) == .success else {
                throw PerformanceFailure.message("Metal 4 FrameInterpolator feedback timed out")
            }
            if let feedbackError {
                throw PerformanceFailure.message("Metal 4 FrameInterpolator failed: \(feedbackError)")
            }
            guard gpuEndTime > gpuStartTime else {
                throw PerformanceFailure.message("Metal 4 FrameInterpolator returned invalid GPU timestamps")
            }
            if index >= warmupCount {
                samples.append((gpuEndTime - gpuStartTime) * 1_000.0)
            }
        }
        return samples
    }
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

    private func makePresentPipelines(
        colorFormat: MTLPixelFormat
    ) throws -> (copy: MTLRenderPipelineState, overlay: MTLRenderPipelineState, fused: MTLRenderPipelineState) {
        let source = """
        #include <metal_stdlib>
        using namespace metal;

        struct VertexOut {
          float4 position [[position]];
          float2 uv;
        };

        vertex VertexOut present_vs(uint vertexId [[vertex_id]]) {
          const float2 positions[3] = {
            float2(-1.0,  1.0),
            float2( 3.0,  1.0),
            float2(-1.0, -3.0)
          };
          const float2 uvs[3] = {
            float2(0.0, 0.0),
            float2(2.0, 0.0),
            float2(0.0, 2.0)
          };
          VertexOut out;
          out.position = float4(positions[vertexId], 0.0, 1.0);
          out.uv = uvs[vertexId];
          return out;
        }

        fragment float4 copy_fs(
          VertexOut in [[stage_in]],
          texture2d<float> source [[texture(0)]],
          sampler linearSampler [[sampler(0)]]) {
          return source.sample(linearSampler, in.uv);
        }

        fragment float4 fused_fs(
          VertexOut in [[stage_in]],
          texture2d<float> scene [[texture(0)]],
          texture2d<float> ui [[texture(1)]],
          sampler linearSampler [[sampler(0)]]) {
          float4 sceneValue = scene.sample(linearSampler, in.uv);
          float4 uiValue = ui.sample(linearSampler, in.uv);
          return uiValue + sceneValue * (1.0 - uiValue.a);
        }
        """
        let library = try device.makeLibrary(source: source, options: nil)
        guard let vertex = library.makeFunction(name: "present_vs"),
              let copyFragment = library.makeFunction(name: "copy_fs"),
              let fusedFragment = library.makeFunction(name: "fused_fs") else {
            throw PerformanceFailure.message("Could not create presentation benchmark functions")
        }

        func build(fragment: MTLFunction, blending: Bool) throws -> MTLRenderPipelineState {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = vertex
            descriptor.fragmentFunction = fragment
            let attachment = descriptor.colorAttachments[0]!
            attachment.pixelFormat = colorFormat
            attachment.isBlendingEnabled = blending
            if blending {
                attachment.rgbBlendOperation = .add
                attachment.sourceRGBBlendFactor = .one
                attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
                attachment.alphaBlendOperation = .add
                attachment.sourceAlphaBlendFactor = .one
                attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            }
            return try device.makeRenderPipelineState(descriptor: descriptor)
        }

        return (
            try build(fragment: copyFragment, blending: false),
            try build(fragment: copyFragment, blending: true),
            try build(fragment: fusedFragment, blending: false)
        )
    }

    private func encodeFullscreenPass(
        commandBuffer: MTLCommandBuffer,
        destination: MTLTexture,
        sources: [MTLTexture],
        pipeline: MTLRenderPipelineState,
        sampler: MTLSamplerState,
        loadAction: MTLLoadAction,
        label: String
    ) throws {
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = destination
        pass.colorAttachments[0].loadAction = loadAction
        pass.colorAttachments[0].storeAction = .store
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
            throw PerformanceFailure.message("Could not create \(label) encoder")
        }
        encoder.label = label
        encoder.setRenderPipelineState(pipeline)
        for (index, source) in sources.enumerated() {
            encoder.setFragmentTexture(source, index: index)
        }
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
    }

    private func runPresentationCase(_ item: PresentationCase) throws -> [String: Any] {
        let colorFormat = MTLPixelFormat.bgra8Unorm
        let depthFormat = MTLPixelFormat.depth32Float
        let motionFormat = MTLPixelFormat.rg16Float
        let shaderReadRenderTarget: MTLTextureUsage = [.shaderRead, .renderTarget]
        let scene = try makeTexture(
            format: colorFormat,
            width: item.sceneWidth,
            height: item.sceneHeight,
            usage: shaderReadRenderTarget,
            label: "\(item.name) scene"
        )
        let ui = try makeTexture(
            format: colorFormat,
            width: item.displayWidth,
            height: item.displayHeight,
            usage: shaderReadRenderTarget,
            label: "\(item.name) UI"
        )
        let splitOutput = try makeTexture(
            format: colorFormat,
            width: item.displayWidth,
            height: item.displayHeight,
            usage: shaderReadRenderTarget,
            label: "\(item.name) split output"
        )
        let fusedOutput = try makeTexture(
            format: colorFormat,
            width: item.displayWidth,
            height: item.displayHeight,
            usage: shaderReadRenderTarget,
            label: "\(item.name) fused output"
        )
        let depth = try makeTexture(
            format: depthFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: [.shaderRead, .renderTarget],
            label: "\(item.name) depth"
        )
        let motion = try makeTexture(
            format: motionFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: [.shaderRead, .renderTarget],
            label: "\(item.name) motion"
        )
        let sceneCopy = try makeTexture(
            format: colorFormat,
            width: item.sceneWidth,
            height: item.sceneHeight,
            usage: [.shaderRead],
            label: "\(item.name) scene copy"
        )
        let uiCopy = try makeTexture(
            format: colorFormat,
            width: item.displayWidth,
            height: item.displayHeight,
            usage: [.shaderRead],
            label: "\(item.name) UI copy"
        )
        let depthCopy = try makeTexture(
            format: depthFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: [.shaderRead],
            label: "\(item.name) depth copy"
        )
        let motionCopy = try makeTexture(
            format: motionFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: [.shaderRead],
            label: "\(item.name) motion copy"
        )
        try clearColor(scene, color: MTLClearColor(red: 0.2, green: 0.3, blue: 0.5, alpha: 1.0))
        try clearColor(ui, color: MTLClearColor(red: 0.1, green: 0.04, blue: 0.02, alpha: 0.2))
        try clearDepth(depth)

        let pipelines = try makePresentPipelines(colorFormat: colorFormat)
        let samplerDescriptor = MTLSamplerDescriptor()
        samplerDescriptor.minFilter = .linear
        samplerDescriptor.magFilter = .linear
        samplerDescriptor.sAddressMode = .clampToEdge
        samplerDescriptor.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: samplerDescriptor) else {
            throw PerformanceFailure.message("Could not create presentation benchmark sampler")
        }

        let splitSamples = try measure(label: "\(item.name) split present") { commandBuffer, _ in
            try! self.encodeFullscreenPass(
                commandBuffer: commandBuffer,
                destination: splitOutput,
                sources: [scene],
                pipeline: pipelines.copy,
                sampler: sampler,
                loadAction: .dontCare,
                label: "Frame Generation Scene Scale"
            )
            try! self.encodeFullscreenPass(
                commandBuffer: commandBuffer,
                destination: splitOutput,
                sources: [ui],
                pipeline: pipelines.overlay,
                sampler: sampler,
                loadAction: .load,
                label: "Frame Generation Native UI Overlay"
            )
        }
        let fusedSamples = try measure(label: "\(item.name) fused present") { commandBuffer, _ in
            try! self.encodeFullscreenPass(
                commandBuffer: commandBuffer,
                destination: fusedOutput,
                sources: [scene, ui],
                pipeline: pipelines.fused,
                sampler: sampler,
                loadAction: .dontCare,
                label: "Frame Generation Fused Scene and UI"
            )
        }
        let inputCopySamples = try measure(label: "\(item.name) input copies") { commandBuffer, _ in
            let blit = commandBuffer.makeBlitCommandEncoder()!
            blit.label = "Frame Generation Input Copies"
            for (source, destination) in [
                (scene, sceneCopy),
                (ui, uiCopy),
                (depth, depthCopy),
                (motion, motionCopy)
            ] {
                blit.copy(
                    from: source,
                    sourceSlice: 0,
                    sourceLevel: 0,
                    to: destination,
                    destinationSlice: 0,
                    destinationLevel: 0,
                    sliceCount: 1,
                    levelCount: 1
                )
            }
            blit.endEncoding()
        }

        let split = statistics(splitSamples)
        let fused = statistics(fusedSamples)
        let splitP95 = split["p95Milliseconds"] as? Double ?? 0.0
        let fusedP95 = fused["p95Milliseconds"] as? Double ?? 0.0
        return [
            "name": item.name,
            "sceneWidth": item.sceneWidth,
            "sceneHeight": item.sceneHeight,
            "inputWidth": item.inputWidth,
            "inputHeight": item.inputHeight,
            "displayWidth": item.displayWidth,
            "displayHeight": item.displayHeight,
            "splitPresent": split,
            "fusedPresent": fused,
            "inputCopies": statistics(inputCopySamples),
            "fusedP95SavingsMilliseconds": splitP95 - fusedP95
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
        interpolationDescriptor.scaler = nil
        guard let standaloneInterpolator = interpolationDescriptor.makeFrameInterpolator(device: device) else {
            throw PerformanceFailure.message("Could not create standalone FrameInterpolator for \(item.name)")
        }
        let metal4Benchmark = item.name == "fullscreen-half-scale-3024"
                ? try Metal4FrameInterpolatorBenchmark(device: device, item: item)
                : nil

        let inputColor = try makeTexture(
            format: colorFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: temporal.colorTextureUsage.union(.renderTarget),
            label: "\(item.name) temporal input"
        )
        var depthUsage = temporal.depthTextureUsage
            .union(interpolator.depthTextureUsage)
            .union(standaloneInterpolator.depthTextureUsage)
        if let metal4Benchmark {
            depthUsage.formUnion(metal4Benchmark.interpolator.depthTextureUsage)
        }
        let depth = try makeTexture(
            format: depthFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: depthUsage.union(.renderTarget),
            label: "\(item.name) depth"
        )
        var motionUsage = temporal.motionTextureUsage
            .union(interpolator.motionTextureUsage)
            .union(standaloneInterpolator.motionTextureUsage)
        if let metal4Benchmark {
            motionUsage.formUnion(metal4Benchmark.interpolator.motionTextureUsage)
        }
        let motion = try makeTexture(
            format: motionFormat,
            width: item.inputWidth,
            height: item.inputHeight,
            usage: motionUsage.union(.renderTarget),
            label: "\(item.name) motion"
        )
        let temporalOutput = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: temporal.outputTextureUsage.union(.shaderRead).union(.renderTarget),
            label: "\(item.name) temporal output"
        )
        var colorUsage = interpolator.colorTextureUsage.union(standaloneInterpolator.colorTextureUsage)
        if let metal4Benchmark {
            colorUsage.formUnion(metal4Benchmark.interpolator.colorTextureUsage)
        }
        let previousColor = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: colorUsage.union(.renderTarget),
            label: "\(item.name) previous color"
        )
        var outputUsage = interpolator.outputTextureUsage
            .union(standaloneInterpolator.outputTextureUsage)
        if let metal4Benchmark {
            outputUsage.formUnion(metal4Benchmark.interpolator.outputTextureUsage)
        }
        let interpolationOutput = try makeTexture(
            format: colorFormat,
            width: item.outputWidth,
            height: item.outputHeight,
            usage: outputUsage.union(.shaderRead).union(.renderTarget),
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

        standaloneInterpolator.colorTexture = temporalOutput
        standaloneInterpolator.prevColorTexture = previousColor
        standaloneInterpolator.uiTexture = nil
        standaloneInterpolator.depthTexture = depth
        standaloneInterpolator.motionTexture = motion
        standaloneInterpolator.outputTexture = interpolationOutput
        standaloneInterpolator.isUITextureComposited = false
        standaloneInterpolator.jitterOffsetX = 0.0
        standaloneInterpolator.jitterOffsetY = 0.0
        standaloneInterpolator.motionVectorScaleX = Float(item.inputWidth) * 0.5
        standaloneInterpolator.motionVectorScaleY = Float(item.inputHeight) * 0.5
        standaloneInterpolator.fieldOfView = 70.0
        standaloneInterpolator.nearPlane = 0.05
        standaloneInterpolator.farPlane = 1_000.0
        standaloneInterpolator.aspectRatio = Float(item.outputWidth) / Float(item.outputHeight)
        standaloneInterpolator.deltaTime = 1.0 / 60.0
        standaloneInterpolator.isDepthReversed = true

        let standaloneSamples = try measure(label: "\(item.name) standalone FrameInterpolator") {
            commandBuffer, index in
            standaloneInterpolator.shouldResetHistory = index == 0
            standaloneInterpolator.encode(commandBuffer: commandBuffer)
        }
        var metal4Statistics: [String: Any]?
        if let metal4Benchmark {
            metal4Benchmark.configure(
                color: temporalOutput,
                previousColor: previousColor,
                depth: depth,
                motion: motion,
                output: interpolationOutput,
                item: item
            )
            metal4Statistics = statistics(try metal4Benchmark.measure(
                warmupCount: warmupCount,
                measuredCount: measuredCount
            ))
        }

        var result: [String: Any] = [
            "name": item.name,
            "inputWidth": item.inputWidth,
            "inputHeight": item.inputHeight,
            "outputWidth": item.outputWidth,
            "outputHeight": item.outputHeight,
            "outputMegapixels": Double(item.outputWidth * item.outputHeight) / 1_000_000.0,
            "temporal": statistics(temporalSamples),
            "frameInterpolator": statistics(interpolationSamples),
            "standaloneFrameInterpolator": statistics(standaloneSamples)
        ]
        if let metal4Statistics {
            result["metal4FrameInterpolator"] = metal4Statistics
        }
        return result
    }

    func run() throws {
        let cases = [
            PerformanceCase(name: "headroom-1280", inputWidth: 858, inputHeight: 482, outputWidth: 1280, outputHeight: 720),
            PerformanceCase(name: "bounded-1440", inputWidth: 964, inputHeight: 542, outputWidth: 1440, outputHeight: 808),
            PerformanceCase(name: "qa-1708", inputWidth: 1144, inputHeight: 643, outputWidth: 1708, outputHeight: 960),
            PerformanceCase(name: "retina-3024", inputWidth: 2026, inputHeight: 1119, outputWidth: 3024, outputHeight: 1670),
            PerformanceCase(name: "fullscreen-half-scale-3024", inputWidth: 1512, inputHeight: 839, outputWidth: 3024, outputHeight: 1678)
        ]
        var results: [[String: Any]] = []
        for item in cases {
            print("[performance] \(item.name) \(item.inputWidth)x\(item.inputHeight) -> \(item.outputWidth)x\(item.outputHeight)")
            let result = try runCase(item)
            results.append(result)
            let temporal = result["temporal"] as? [String: Any]
            let frameInterpolator = result["frameInterpolator"] as? [String: Any]
            let standalone = result["standaloneFrameInterpolator"] as? [String: Any]
            let metal4 = result["metal4FrameInterpolator"] as? [String: Any]
            print(String(format: "[performance] %@ Temporal %.2f ms p95; linked FrameInterpolator %.2f ms p95; standalone %.2f ms p95; Metal 4 %.2f ms p95",
                         item.name,
                         temporal?["p95Milliseconds"] as? Double ?? 0.0,
                         frameInterpolator?["p95Milliseconds"] as? Double ?? 0.0,
                         standalone?["p95Milliseconds"] as? Double ?? 0.0,
                         metal4?["p95Milliseconds"] as? Double ?? 0.0))
        }
        let presentationCases = [
            PresentationCase(
                name: "qa-1280-to-1708",
                sceneWidth: 1280,
                sceneHeight: 718,
                inputWidth: 858,
                inputHeight: 482,
                displayWidth: 1708,
                displayHeight: 960
            ),
            PresentationCase(
                name: "native-1708",
                sceneWidth: 1708,
                sceneHeight: 960,
                inputWidth: 1144,
                inputHeight: 643,
                displayWidth: 1708,
                displayHeight: 960
            ),
            PresentationCase(
                name: "fullscreen-direct-3024",
                sceneWidth: 3024,
                sceneHeight: 1678,
                inputWidth: 1512,
                inputHeight: 839,
                displayWidth: 3024,
                displayHeight: 1678
            )
        ]
        var presentationResults: [[String: Any]] = []
        for item in presentationCases {
            print("[performance] \(item.name) presentation overhead")
            let result = try runPresentationCase(item)
            presentationResults.append(result)
            let split = result["splitPresent"] as? [String: Any]
            let fused = result["fusedPresent"] as? [String: Any]
            let copies = result["inputCopies"] as? [String: Any]
            print(String(
                format: "[performance] %@ split %.3f ms p95; fused %.3f ms p95; input copies %.3f ms p95",
                item.name,
                split?["p95Milliseconds"] as? Double ?? 0.0,
                fused?["p95Milliseconds"] as? Double ?? 0.0,
                copies?["p95Milliseconds"] as? Double ?? 0.0
            ))
        }
        let summary: [String: Any] = [
            "status": "passed",
            "device": device.name,
            "warmupCount": warmupCount,
            "measuredCount": measuredCount,
            "usesWindow": false,
            "usedComputerUse": false,
            "cases": results,
            "presentationCases": presentationResults
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
