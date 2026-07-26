// Metal 4 API probe — the ground truth for MinecraftMetal_Metal4_Migration_Specs_2026-07-27.md.
//
// This file is never compiled into the dylib. It exists so every Metal 4 call
// the migration needs has a *typechecked* Swift spelling: the Swift importer
// renames or relabels a large fraction of the MTL4 selectors, and guessing from
// the Objective-C headers or from WWDC prose produces code that does not build.
// Appendix A of the spec is derived from the errors this file produced.
//
// Re-verify after any Xcode/SDK update. All three must exit 0 — the macOS 14 and
// iOS 14 runs are what prove the @available(macOS 26.0, iOS 26.0, *) dual-path
// strategy compiles against build.gradle's existing deployment targets, so the
// migration never has to raise them:
//
//   xcrun swiftc -typecheck -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
//       -target arm64-apple-macosx26.0 docs/mtl4-api-probe.swift
//   xcrun swiftc -typecheck -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
//       -target arm64-apple-macosx14.0 docs/mtl4-api-probe.swift
//   xcrun swiftc -typecheck -sdk "$(xcrun --show-sdk-path --sdk iphoneos)" \
//       -target arm64-apple-ios14.0 docs/mtl4-api-probe.swift
//
// Last verified: 2026-07-27, Xcode SDK MacOSX26.5 / iPhoneOS26.5, all three EXIT=0.

import Metal
import MetalFX
import QuartzCore
import Foundation

@available(macOS 26.0, iOS 26.0, *)
func probe(device: MTLDevice, layer: CAMetalLayer, buffer: MTLBuffer, texture: MTLTexture, sampler: MTLSamplerState, drawable: CAMetalDrawable, url: URL, dsState: MTLDepthStencilState) throws {
    // --- feature detection ---
    _ = device.supportsFamily(.metal4)

    // --- queue / command buffer / allocator ---
    let queue: MTL4CommandQueue = device.makeMTL4CommandQueue()!
    // MTL4CommandQueue.label is GET-ONLY, unlike MTLCommandQueue.label:
    // `queue.label = "x"` fails with "cannot assign to property: 'label' is a
    // get-only property". The label must come from the descriptor, and that
    // overload throws while the no-argument one does not.
    let qd = MTL4CommandQueueDescriptor()
    qd.label = "metallum-m4"
    _ = try device.makeMTL4CommandQueue(descriptor: qd)
    let cmd: MTL4CommandBuffer = device.makeCommandBuffer()!
    let alloc: MTL4CommandAllocator = device.makeCommandAllocator()!
    alloc.reset()
    cmd.beginCommandBuffer(allocator: alloc)

    // --- compiler / library / pipeline ---
    let compDesc = MTL4CompilerDescriptor()
    let compiler = try device.makeCompiler(descriptor: compDesc)
    let libDesc = MTL4LibraryDescriptor()
    libDesc.source = "kernel void k() {}"
    let lib = try compiler.makeLibrary(descriptor: libDesc)
    let vfn = MTL4LibraryFunctionDescriptor()
    vfn.library = lib
    vfn.name = "vertexMain"
    let ffn = MTL4LibraryFunctionDescriptor()
    ffn.library = lib
    ffn.name = "fragmentMain"
    let rp = MTL4RenderPipelineDescriptor()
    rp.vertexFunctionDescriptor = vfn
    rp.fragmentFunctionDescriptor = ffn
    rp.colorAttachments[0].pixelFormat = .bgra8Unorm
    rp.colorAttachments[0].blendingState = .enabled
    rp.colorAttachments[0].sourceRGBBlendFactor = .sourceAlpha
    rp.vertexDescriptor = MTLVertexDescriptor()
    rp.rasterSampleCount = 1
    let pso: MTLRenderPipelineState = try compiler.makeRenderPipelineState(descriptor: rp)
    // async variant probed separately in probeAsync()
    let taskOptions = MTL4CompilerTaskOptions()
    // A *synchronous* overload taking compilerTaskOptions exists as well, which is
    // what lets lookupArchives be used without moving pipeline creation onto
    // Swift concurrency (M2c depends on this).
    _ = try compiler.makeRenderPipelineState(descriptor: rp, compilerTaskOptions: taskOptions)
    // unspecialized / flexible
    rp.colorAttachments[0].pixelFormat = .unspecialized
    rp.colorAttachments[0].blendingState = .unspecialized
    _ = try compiler.makeRenderPipelineStateBySpecialization(descriptor: rp, pipeline: pso)

    // --- archive / serializer ---
    // configuration is an NS_OPTIONS mask that selects which serializer method is
    // usable, and the pairing is not interchangeable:
    //   .captureDescriptors -> serializeAsPipelinesScript()   (offline metal-tt)
    //   .captureBinaries    -> serializeAsArchiveAndFlush(url:)
    // Typechecking cannot catch a wrong pairing: .captureDescriptors +
    // serializeAsArchiveAndFlush compiles and then throws `nilError` at run time,
    // so the pipeline cache silently never lands. Found by running it (M2c).
    let serDesc = MTL4PipelineDataSetSerializerDescriptor()
    serDesc.configuration = .captureBinaries
    let serializer = device.makePipelineDataSetSerializer(descriptor: serDesc)
    try serializer.serializeAsArchiveAndFlush(url: url)
    let archive = try device.makeArchive(url: url)
    taskOptions.lookupArchives = [archive]

    // --- render pass / encoder ---
    let rpd = MTL4RenderPassDescriptor()
    rpd.colorAttachments[0].texture = texture
    rpd.colorAttachments[0].loadAction = .clear
    rpd.colorAttachments[0].storeAction = .store
    rpd.depthAttachment.texture = texture
    rpd.renderTargetWidth = 16
    rpd.renderTargetHeight = 16
    let enc = cmd.makeRenderCommandEncoder(descriptor: rpd)!
    // Pipeline states are interchangeable in BOTH directions, and both directions
    // were confirmed by running them, not just by typechecking (M2 step 0 and the
    // M4 precondition): an MTL4Compiler pipeline binds to a Metal 3 encoder, and a
    // pipeline from the ordinary device.makeRenderPipelineState binds here, on an
    // MTL4 encoder, reading its texture and sampler from the argument table below.
    enc.setRenderPipelineState(pso)
    enc.setDepthStencilState(dsState)
    enc.setViewport(MTLViewport(originX: 0, originY: 0, width: 16, height: 16, znear: 0, zfar: 1))
    enc.setScissorRect(MTLScissorRect(x: 0, y: 0, width: 16, height: 16))
    enc.setCullMode(.back)

    // --- argument table ---
    let atd = MTL4ArgumentTableDescriptor()
    atd.maxBufferBindCount = 8
    atd.maxTextureBindCount = 8
    atd.maxSamplerStateBindCount = 8
    atd.initializeBindings = true
    atd.supportAttributeStrides = true
    let at = try device.makeArgumentTable(descriptor: atd)
    at.setAddress(buffer.gpuAddress, index: 0)
    at.setAddress(buffer.gpuAddress + 64, attributeStride: 32, index: 1)
    at.setTexture(texture.gpuResourceID, index: 0)
    at.setSamplerState(sampler.gpuResourceID, index: 0)
    enc.setArgumentTable(at, stages: [.vertex, .fragment])

    // --- draws (GPU address based) ---
    enc.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
    enc.drawIndexedPrimitives(primitiveType: .triangle, indexCount: 3, indexType: .uint32, indexBuffer: buffer.gpuAddress, indexBufferLength: buffer.length)

    // --- barriers + fences on render encoder ---
    enc.barrier(afterQueueStages: .blit, beforeStages: .vertex, visibilityOptions: .device)
    enc.barrier(afterStages: .fragment, beforeQueueStages: .fragment, visibilityOptions: .device)
    let fence = device.makeFence()!
    enc.updateFence(fence, afterEncoderStages: .fragment)
    enc.waitForFence(fence, beforeEncoderStages: .vertex)
    enc.endEncoding()

    // --- compute encoder (unified blit) ---
    let cenc = cmd.makeComputeCommandEncoder()!
    cenc.copy(sourceBuffer: buffer, sourceOffset: 0, destinationBuffer: buffer, destinationOffset: 64, size: 16)
    cenc.copy(sourceBuffer: buffer, sourceOffset: 0, sourceBytesPerRow: 64, sourceBytesPerImage: 64 * 16, sourceSize: MTLSize(width: 16, height: 16, depth: 1), destinationTexture: texture, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
    cenc.copy(sourceTexture: texture, sourceSlice: 0, sourceLevel: 0, destinationTexture: texture, destinationSlice: 0, destinationLevel: 0, sliceCount: 1, levelCount: 1)
    cenc.generateMipmaps(texture: texture)
    cenc.fill(buffer: buffer, range: 0..<16, value: 0)
    cenc.setComputePipelineState(try compiler.makeComputePipelineState(descriptor: MTL4ComputePipelineDescriptor()))
    cenc.setArgumentTable(at)
    cenc.dispatchThreadgroups(threadgroupsPerGrid: MTLSize(width: 1, height: 1, depth: 1), threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1))
    cenc.barrier(afterEncoderStages: .blit, beforeEncoderStages: .dispatch, visibilityOptions: .device)
    cenc.updateFence(fence, afterEncoderStages: .blit)
    cenc.waitForFence(fence, beforeEncoderStages: .blit)
    cenc.endEncoding()

    cmd.endCommandBuffer()

    // --- residency ---
    let rsd = MTLResidencySetDescriptor()
    rsd.initialCapacity = 128
    let rs = try device.makeResidencySet(descriptor: rsd)
    rs.addAllocation(buffer)
    rs.addAllocations([texture])
    rs.commit()
    rs.requestResidency()
    rs.removeAllocation(buffer)
    queue.addResidencySet(rs)
    queue.addResidencySet(layer.residencySet)
    cmd.useResidencySet(rs)

    // --- commit / feedback / events ---
    queue.commit([cmd])
    let commitOptions = MTL4CommitOptions()
    commitOptions.addFeedbackHandler { feedback in
        _ = feedback.gpuStartTime
        _ = feedback.gpuEndTime
        _ = feedback.error
    }
    queue.commit([cmd], options: commitOptions)
    let sharedEvent = device.makeSharedEvent()!
    queue.signalEvent(sharedEvent, value: 42)
    queue.waitForEvent(sharedEvent, value: 42)
    _ = sharedEvent.wait(untilSignaledValue: 42, timeoutMS: 1000)

    // --- present ---
    queue.waitForDrawable(drawable)
    queue.signalDrawable(drawable)
    drawable.present()

    // --- MetalFX MTL4 ---
    let fxDesc = MTLFXTemporalScalerDescriptor()
    fxDesc.colorTextureFormat = .rgba16Float
    let scaler = fxDesc.makeTemporalScaler(device: device, compiler: compiler)
    scaler?.encode(commandBuffer: cmd)
    let sfxDesc = MTLFXSpatialScalerDescriptor()
    let sscaler = sfxDesc.makeSpatialScaler(device: device, compiler: compiler)
    _ = sscaler
}

@available(macOS 26.0, iOS 26.0, *)
func probeAsync(compiler: MTL4Compiler, rp: MTL4RenderPipelineDescriptor) async throws {
    let opts = MTL4CompilerTaskOptions()
    let pso = try await compiler.makeRenderPipelineState(descriptor: rp, compilerTaskOptions: opts)
    _ = pso
}

// ============================================================
// Probe #2: the API surface that the main render path (Java-driven bridge) and
// the frame-generation present thread need. Complements mtl4probe.swift.

@available(macOS 26.0, iOS 26.0, *)
func probeRenderState(enc: MTL4RenderCommandEncoder, buffer: MTLBuffer, dss: MTLDepthStencilState) {
    enc.setViewport(MTLViewport(originX: 0, originY: 0, width: 8, height: 8, znear: 0, zfar: 1))
    enc.setViewports([MTLViewport(originX: 0, originY: 0, width: 8, height: 8, znear: 0, zfar: 1)])
    enc.setScissorRect(MTLScissorRect(x: 0, y: 0, width: 8, height: 8))
    enc.setCullMode(.back)
    enc.setFrontFacing(.counterClockwise)
    enc.setTriangleFillMode(.fill)
    enc.setDepthBias(0.0, slopeScale: 0.0, clamp: 0.0)
    enc.setDepthStencilState(dss)
    enc.setStencilReferenceValue(0)
    enc.setStencilReferenceValue(front: 0, back: 0)
    enc.setBlendColor(red: 0, green: 0, blue: 0, alpha: 0)
    enc.setDepthClipMode(.clip)
    enc.setColorStoreAction(.store, index: 0)
    enc.setVisibilityResultMode(.disabled, offset: 0)

    // draw families used by the Java bridge
    enc.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3, instanceCount: 1, baseInstance: 0)
    enc.drawIndexedPrimitives(
        primitiveType: .triangle,
        indexCount: 3,
        indexType: .uint32,
        indexBuffer: buffer.gpuAddress,
        indexBufferLength: buffer.length,
        instanceCount: 2,
        baseVertex: 0,
        baseInstance: 0
    )
    enc.drawPrimitives(primitiveType: .triangle, indirectBuffer: buffer.gpuAddress)
    enc.drawIndexedPrimitives(
        primitiveType: .triangle,
        indexType: .uint32,
        indexBuffer: buffer.gpuAddress,
        indexBufferLength: buffer.length,
        indirectBuffer: buffer.gpuAddress
    )
}

// S7 deferred-store: does the Metal 4 render encoder still allow late store-action
// decisions, and what is the Metal 4 spelling of MTLStoreActionOptions?
@available(macOS 26.0, iOS 26.0, *)
func probeStoreActions(rpd: MTL4RenderPassDescriptor, texture: MTLTexture) {
    rpd.colorAttachments[0].storeAction = .store
    rpd.depthAttachment.storeAction = .dontCare
    rpd.stencilAttachment.storeAction = .dontCare
    rpd.colorAttachments[0].resolveTexture = texture
    rpd.depthAttachment.clearDepth = 1.0
    rpd.stencilAttachment.clearStencil = 0
    rpd.defaultRasterSampleCount = 1
    rpd.renderTargetArrayLength = 1
    rpd.tileWidth = 0
    rpd.tileHeight = 0
    rpd.imageblockSampleLength = 0
    rpd.threadgroupMemoryLength = 0
    rpd.supportColorAttachmentMapping = false
}

@available(macOS 26.0, iOS 26.0, *)
func probeCompute(cenc: MTL4ComputeCommandEncoder, compiler: MTL4Compiler, lib: MTLLibrary) throws {
    let cfn = MTL4LibraryFunctionDescriptor()
    cfn.library = lib
    cfn.name = "k"
    let cpd = MTL4ComputePipelineDescriptor()
    cpd.computeFunctionDescriptor = cfn
    cpd.threadGroupSizeIsMultipleOfThreadExecutionWidth = true
    let cps = try compiler.makeComputePipelineState(descriptor: cpd)
    cenc.setComputePipelineState(cps)
    cenc.dispatchThreads(threadsPerGrid: MTLSize(width: 8, height: 8, depth: 1), threadsPerThreadgroup: MTLSize(width: 8, height: 8, depth: 1))
    cenc.setThreadgroupMemoryLength(0, index: 0)
}

// Compiler descriptor: serializer attachment + task options.
@available(macOS 26.0, iOS 26.0, *)
func probeCompilerDescriptor(device: MTLDevice, url: URL) throws {
    let serDesc = MTL4PipelineDataSetSerializerDescriptor()
    serDesc.configuration = .captureDescriptors
    let serializer = device.makePipelineDataSetSerializer(descriptor: serDesc)
    let cd = MTL4CompilerDescriptor()
    cd.pipelineDataSetSerializer = serializer
    cd.label = "metallum-compiler"
    let compiler = try device.makeCompiler(descriptor: cd)
    _ = compiler.device
    _ = compiler.label
    let opts = MTL4CompilerTaskOptions()
    opts.lookupArchives = [try device.makeArchive(url: url)]
    _ = try serializer.serializeAsPipelinesScript()
}

// Frame generation present thread.
@available(macOS 26.0, iOS 26.0, *)
func probeFrameInterpolator(device: MTLDevice, compiler: MTL4Compiler, cmd: MTL4CommandBuffer) {
    let d = MTLFXFrameInterpolatorDescriptor()
    d.colorTextureFormat = .rgba16Float
    d.outputTextureFormat = .bgra8Unorm
    d.depthTextureFormat = .depth32Float
    d.motionTextureFormat = .rg16Float
    d.uiTextureFormat = .bgra8Unorm
    d.inputWidth = 16
    d.inputHeight = 16
    d.outputWidth = 16
    d.outputHeight = 16
    let interp: (any MTL4FXFrameInterpolator)? = d.makeFrameInterpolator(device: device, compiler: compiler)
    guard let interp else { return }
    interp.colorTexture = nil
    interp.prevColorTexture = nil
    interp.depthTexture = nil
    interp.motionTexture = nil
    interp.uiTexture = nil
    interp.outputTexture = nil
    interp.isUITextureComposited = true
    interp.jitterOffsetX = 0
    interp.jitterOffsetY = 0
    interp.motionVectorScaleX = 1
    interp.motionVectorScaleY = 1
    interp.fieldOfView = 1
    interp.nearPlane = 0.1
    interp.farPlane = 100
    interp.aspectRatio = 1.7
    interp.deltaTime = 0.016
    interp.isDepthReversed = true
    interp.shouldResetHistory = false
    interp.encode(commandBuffer: cmd)
    _ = interp.fence
}

// Temporal scaler MTL4 property surface (S3/FxManager path).
@available(macOS 26.0, iOS 26.0, *)
func probeTemporalScaler(device: MTLDevice, compiler: MTL4Compiler, cmd: MTL4CommandBuffer) {
    let d = MTLFXTemporalScalerDescriptor()
    d.colorTextureFormat = .rgba16Float
    d.depthTextureFormat = .depth32Float
    d.motionTextureFormat = .rg16Float
    d.outputTextureFormat = .rgba16Float
    d.inputWidth = 8
    d.inputHeight = 8
    d.outputWidth = 16
    d.outputHeight = 16
    d.isAutoExposureEnabled = false
    d.isInputContentPropertiesEnabled = false
    d.requiresSynchronousInitialization = false
    d.isReactiveMaskTextureEnabled = true
    d.reactiveMaskTextureFormat = .r8Unorm
    guard let s: any MTL4FXTemporalScaler = d.makeTemporalScaler(device: device, compiler: compiler) else { return }
    s.colorTexture = nil
    s.depthTexture = nil
    s.motionTexture = nil
    s.outputTexture = nil
    s.reactiveMaskTexture = nil
    s.jitterOffsetX = 0
    s.jitterOffsetY = 0
    s.motionVectorScaleX = 1
    s.motionVectorScaleY = 1
    s.isDepthReversed = true
    s.reset = false
    s.inputContentWidth = 8
    s.inputContentHeight = 8
    s.encode(commandBuffer: cmd)
    _ = s.fence
}

// Residency set attached to a *Metal 3* queue (staging step before any MTL4 queue).
@available(macOS 15.0, iOS 18.0, *)
func probeResidencyOnMetal3(device: MTLDevice, queue: MTLCommandQueue, buffer: MTLBuffer) throws {
    let rsd = MTLResidencySetDescriptor()
    rsd.label = "metallum-rs"
    rsd.initialCapacity = 64
    let rs = try device.makeResidencySet(descriptor: rsd)
    rs.addAllocation(buffer)
    rs.commit()
    rs.requestResidency()
    queue.addResidencySet(rs)
    queue.removeResidencySet(rs)
    _ = rs.allocatedSize
    _ = rs.allAllocations
}

// Command buffer / allocator lifecycle detail.
@available(macOS 26.0, iOS 26.0, *)
func probeLifecycle(device: MTLDevice, queue: MTL4CommandQueue) throws {
    let ad = MTL4CommandAllocatorDescriptor()
    ad.label = "metallum-alloc-0"
    let alloc = try device.makeCommandAllocator(descriptor: ad)
    _ = alloc.allocatedSize
    let cmd = device.makeCommandBuffer()!
    cmd.label = "metallum-cb-relabel"
    cmd.beginCommandBuffer(allocator: alloc)
    cmd.pushDebugGroup("frame")
    cmd.popDebugGroup()
    cmd.endCommandBuffer()
    queue.commit([cmd])
    _ = queue.label
}

// ============================================================
// Probe #3: field-for-field mapping of MTLRenderPipelineDescriptor (what the
// existing metallum_MTLRenderPipelineDescriptor_* exports set) onto
// MTL4RenderPipelineDescriptor, plus MTLFunction -> MTL4LibraryFunctionDescriptor
// recovery without an ABI change.

@available(macOS 26.0, iOS 26.0, *)
func probeDescriptorMapping(function: MTLFunction, library: MTLLibrary, vertexDesc: MTLVertexDescriptor) {
    // MTLFunction does NOT expose its library, so the library must be carried
    // alongside; only `name` is recoverable from the function object.
    let fd = MTL4LibraryFunctionDescriptor()
    fd.library = library
    fd.name = function.name

    let d = MTL4RenderPipelineDescriptor()
    d.label = "metallum-pso"
    d.vertexFunctionDescriptor = fd
    d.fragmentFunctionDescriptor = fd
    d.vertexDescriptor = vertexDesc
    d.rasterSampleCount = 1
    d.inputPrimitiveTopology = .triangle
    d.alphaToCoverageState = .disabled
    d.alphaToOneState = .disabled
    d.isRasterizationEnabled = true
    d.maxVertexAmplificationCount = 1
    d.supportIndirectCommandBuffers = .disabled
    d.colorAttachmentMappingState = .identity
    d.supportVertexBinaryLinking = false
    d.supportFragmentBinaryLinking = false

    let ca = d.colorAttachments[0]!
    ca.pixelFormat = .bgra8Unorm
    ca.writeMask = [.red, .green, .blue, .alpha]
    ca.blendingState = .enabled
    ca.sourceRGBBlendFactor = .sourceAlpha
    ca.destinationRGBBlendFactor = .oneMinusSourceAlpha
    ca.rgbBlendOperation = .add
    ca.sourceAlphaBlendFactor = .one
    ca.destinationAlphaBlendFactor = .oneMinusSourceAlpha
    ca.alphaBlendOperation = .add

    // "no attachment" spelling and the unspecialized (flexible) spelling
    d.colorAttachments[1]!.pixelFormat = .invalid
    d.colorAttachments[0]!.pixelFormat = .unspecialized
    d.colorAttachments[0]!.blendingState = .unspecialized

    // reset + reuse, so one descriptor object can serve many specializations
    d.reset()
}

@available(macOS 26.0, iOS 26.0, *)
func probeStaticLinking(lib: MTLLibrary) {
    let sld = MTL4StaticLinkingDescriptor()
    sld.functionDescriptors = []
    sld.privateFunctionDescriptors = []
    let d = MTL4RenderPipelineDescriptor()
    d.vertexStaticLinkingDescriptor = sld
    d.fragmentStaticLinkingDescriptor = sld
    _ = lib
}

// ============================================================
// Probe #4: the *implementation* snippets the migration spec hands to the
// executor, typechecked verbatim so they can be copied without editing.

// ---------------------------------------------------------------- M1

enum Probe4State {
    static let functionLibraries = NSMapTable<AnyObject, AnyObject>.weakToStrongObjects()
    static let functionLibrariesLock = NSLock()

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

    static var metal4Enabled = false
}

@_cdecl("probe4_metal4_supported")
public func probe4_metal4_supported(_ device: MTLDevice) -> Int32 {
    if #available(macOS 26.0, iOS 26.0, *) {
        return device.supportsFamily(.metal4) ? 1 : 0
    }
    return 0
}

// ---------------------------------------------------------------- M2

@available(macOS 26.0, iOS 26.0, *)
func probe4MakeMetal4Descriptor(_ src: MTLRenderPipelineDescriptor) -> MTL4RenderPipelineDescriptor? {
    guard let vertexFunction = src.vertexFunction,
          let vertexLibrary = Probe4State.library(for: vertexFunction) else {
        return nil
    }
    let dst = MTL4RenderPipelineDescriptor()
    dst.label = src.label
    let vfd = MTL4LibraryFunctionDescriptor()
    vfd.library = vertexLibrary
    vfd.name = vertexFunction.name
    dst.vertexFunctionDescriptor = vfd
    if let fragmentFunction = src.fragmentFunction,
       let fragmentLibrary = Probe4State.library(for: fragmentFunction) {
        let ffd = MTL4LibraryFunctionDescriptor()
        ffd.library = fragmentLibrary
        ffd.name = fragmentFunction.name
        dst.fragmentFunctionDescriptor = ffd
    } else if src.fragmentFunction != nil {
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

// ---------------------------------------------------------------- M5

@available(macOS 26.0, iOS 26.0, *)
final class Probe4BumpAllocator {
    private let buffer: MTLBuffer
    private let capacity: Int
    private var cursor: Int = 0
    private let base: UnsafeMutableRawPointer

    init?(device: MTLDevice, capacity: Int, label: String) {
        guard let buffer = device.makeBuffer(length: capacity, options: [.storageModeShared]) else {
            return nil
        }
        buffer.label = label
        self.buffer = buffer
        self.capacity = capacity
        self.base = buffer.contents()
    }

    var backing: MTLBuffer { buffer }

    func reset() { cursor = 0 }

    /// 16-byte aligned sub-allocation; returns the GPU address to bind.
    func allocate(bytes: UnsafeRawPointer, length: Int) -> MTLGPUAddress? {
        let aligned = (cursor + 15) & ~15
        guard aligned + length <= capacity else { return nil }
        base.advanced(by: aligned).copyMemory(from: bytes, byteCount: length)
        cursor = aligned + length
        return buffer.gpuAddress + UInt64(aligned)
    }
}

// ---------------------------------------------------------------- M4

@available(macOS 26.0, iOS 26.0, *)
final class Probe4Presenter {
    private let device: MTLDevice
    private let queue: MTL4CommandQueue
    private let commandBuffer: MTL4CommandBuffer
    private let allocators: [MTL4CommandAllocator]
    private let argumentTable: MTL4ArgumentTable
    private let residencySet: MTLResidencySet
    private var frameIndex = 0

    init?(device: MTLDevice, layer: CAMetalLayer) {
        guard let queue = device.makeMTL4CommandQueue(),
              let commandBuffer = device.makeCommandBuffer() else { return nil }
        var allocators: [MTL4CommandAllocator] = []
        for _ in 0..<2 {
            guard let a = device.makeCommandAllocator() else { return nil }
            allocators.append(a)
        }
        let atd = MTL4ArgumentTableDescriptor()
        atd.maxTextureBindCount = 1
        atd.maxSamplerStateBindCount = 1
        atd.initializeBindings = true
        let rsd = MTLResidencySetDescriptor()
        rsd.initialCapacity = 32
        guard let argumentTable = try? device.makeArgumentTable(descriptor: atd),
              let residencySet = try? device.makeResidencySet(descriptor: rsd) else { return nil }
        self.device = device
        self.queue = queue
        self.commandBuffer = commandBuffer
        self.allocators = allocators
        self.argumentTable = argumentTable
        self.residencySet = residencySet
        queue.addResidencySet(residencySet)
        queue.addResidencySet(layer.residencySet)
    }

    func present(
        drawable: CAMetalDrawable,
        source: MTLTexture,
        sampler: MTLSamplerState,
        pipeline: MTLRenderPipelineState,
        readyEvent: MTLSharedEvent,
        eventValue: UInt64,
        onCompleted: @escaping (Error?) -> Void
    ) {
        let allocator = allocators[frameIndex % allocators.count]
        frameIndex += 1
        allocator.reset()

        queue.waitForEvent(readyEvent, value: eventValue)

        commandBuffer.beginCommandBuffer(allocator: allocator)
        let descriptor = MTL4RenderPassDescriptor()
        descriptor.colorAttachments[0].texture = drawable.texture
        descriptor.colorAttachments[0].loadAction = .dontCare
        descriptor.colorAttachments[0].storeAction = .store
        descriptor.renderTargetWidth = drawable.texture.width
        descriptor.renderTargetHeight = drawable.texture.height
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor) else {
            commandBuffer.endCommandBuffer()
            return
        }
        argumentTable.setTexture(source.gpuResourceID, index: 0)
        argumentTable.setSamplerState(sampler.gpuResourceID, index: 0)
        encoder.setArgumentTable(argumentTable, stages: .fragment)
        encoder.setRenderPipelineState(pipeline)
        encoder.setViewport(MTLViewport(
            originX: 0, originY: 0,
            width: Double(drawable.texture.width),
            height: Double(drawable.texture.height),
            znear: 0, zfar: 1
        ))
        encoder.drawPrimitives(primitiveType: .triangle, vertexStart: 0, vertexCount: 3)
        encoder.endEncoding()
        commandBuffer.endCommandBuffer()

        let options = MTL4CommitOptions()
        options.addFeedbackHandler { feedback in onCompleted(feedback.error) }
        queue.waitForDrawable(drawable)
        queue.commit([commandBuffer], options: options)
        queue.signalDrawable(drawable)
        drawable.present()
    }

    func adopt(textures: [MTLTexture]) {
        residencySet.addAllocations(textures)
        residencySet.commit()
        residencySet.requestResidency()
    }
}

// ---------------------------------------------------------------- M7g

@available(macOS 26.0, iOS 26.0, *)
func probe4WaitForCompletion(
    queue: MTL4CommandQueue,
    event: MTLSharedEvent,
    value: UInt64,
    timeoutMs: UInt64
) -> Int32 {
    queue.signalEvent(event, value: value)
    return event.wait(untilSignaledValue: value, timeoutMS: timeoutMs) ? 1 : 0
}
