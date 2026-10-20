import Foundation
import Metal
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import simd

private struct ContractError: Error, CustomStringConvertible {
    var description: String
    init(_ message: String) { description = message }
}

// This matches the actual constant layout consumed by the shipping fused kernel.
private struct FusedUniforms {
    var current: simd_float4x4
    var inverse: simd_float4x4
    var previous: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
    var options: SIMD4<UInt32>
    var params: SIMD4<Float>
}

private struct CameraUniforms {
    var current: simd_float4x4
    var inverse: simd_float4x4
    var previous: simd_float4x4
    var viewport: SIMD4<Float>
    var flags: SIMD4<UInt32>
    var params: SIMD4<Float>
}

private struct HandUniforms {
    var width: UInt32
    var height: UInt32
    var boost: Float
    var exact: Float
}

private struct Case {
    var name: String
    var previous = matrix_identity_float4x4
    var inverse = matrix_identity_float4x4
    var object = SIMD2<Float>(0, 0)
    var valid: Float = 0
    var hand: Float = 0
    var handExact: Float = 0
    var reactive: Float = 0
    var depth: Float = 0.5
    var previousDepth: Float = 0.5
    var history = false
    var reversed = true
    var sky = false
    var expectedMotion: SIMD2<Float>? = nil
    var expectedRejection = false
    var reactiveMinimum: Float = 0
}

private func cases(width: Int, height: Int) -> [Case] {
    var x = matrix_identity_float4x4; x.columns.3.x = 0.125
    var y = matrix_identity_float4x4; y.columns.3.y = 0.25
    var forward = matrix_identity_float4x4; forward.columns.3.z = 0.1
    var backward = matrix_identity_float4x4; backward.columns.3.z = -0.1
    let angle: Float = 0.05
    var rotation = matrix_identity_float4x4
    rotation.columns.0 = SIMD4(cos(angle), sin(angle), 0, 0)
    rotation.columns.1 = SIMD4(-sin(angle), cos(angle), 0, 0)
    var jitter = matrix_identity_float4x4
    jitter.columns.3.x = -0.5 / Float(width)
    jitter.columns.3.y = 1.0 / Float(height)
    var behind = matrix_identity_float4x4; behind.columns.3.w = -1
    var singular = matrix_identity_float4x4; singular.columns.3.w = 0
    let negativeHomogeneousScale = simd_float4x4(diagonal: SIMD4<Float>(repeating: -1))
    return [
        Case(name: "static", expectedMotion: .zero),
        Case(name: "jitter_only", inverse: jitter, expectedMotion: .zero),
        Case(name: "camera_x_ndc", previous: x, expectedMotion: SIMD2(0.125, 0)),
        Case(name: "camera_y_top_left", previous: y, expectedMotion: SIMD2(0, -0.25)),
        Case(name: "camera_rotation", previous: rotation),
        Case(name: "previous_behind_camera", previous: behind, expectedMotion: .zero,
             expectedRejection: true, reactiveMinimum: 0.99),
        Case(name: "singular_unprojection", inverse: singular, expectedMotion: .zero,
             expectedRejection: true, reactiveMinimum: 0.99),
        Case(name: "negative_world_homogeneous_scale_is_valid", inverse: negativeHomogeneousScale,
             history: true, expectedMotion: .zero),
        Case(name: "object_overrides_camera", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, expectedMotion: SIMD2(-0.25, 0.125)),
        Case(name: "uncovered_object_ignored", object: SIMD2(8, 8), expectedMotion: .zero),
        Case(name: "invalid_object", previous: x, object: SIMD2(.nan, .infinity),
             valid: 1, expectedMotion: SIMD2(0.125, 0), reactiveMinimum: 0.99),
        Case(name: "hand_covers_world_validity", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, hand: 0.9, expectedMotion: .zero, reactiveMinimum: 0.49),
        Case(name: "exact_hand", previous: x, object: SIMD2(-0.25, 0.125),
             valid: 1, hand: 0.9, handExact: 1,
             expectedMotion: SIMD2(-0.25, 0.125), reactiveMinimum: 0.49),
        Case(name: "invalid_exact_hand", object: SIMD2(.nan, .infinity),
             valid: 1, hand: 0.9, handExact: 1, expectedMotion: .zero, reactiveMinimum: 0.99),
        Case(name: "invalid_exact_hand_moving_camera", previous: x, object: SIMD2(.nan, .infinity),
             valid: 1, hand: 0.9, handExact: 1, expectedMotion: .zero, reactiveMinimum: 0.99),
        Case(name: "reversed_z_occlusion", previousDepth: 0.8, history: true,
             expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "standard_z_occlusion", previousDepth: 0.2, history: true, reversed: false,
             expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "history_reset_ignores_old_depth", previousDepth: 0.8,
             expectedMotion: .zero),
        Case(name: "camera_depth_reprojection_reversed_z", previous: forward, previousDepth: 0.6,
             history: true, expectedMotion: .zero),
        Case(name: "camera_depth_reprojection_standard_z", previous: backward, previousDepth: 0.4,
             history: true, reversed: false, expectedMotion: .zero),
        Case(name: "camera_depth_reprojection_still_rejects_occluder_reversed_z", previous: forward, previousDepth: 0.8,
             history: true, expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "camera_depth_reprojection_still_rejects_occluder_standard_z", previous: backward, previousDepth: 0.2,
             history: true, reversed: false, expectedMotion: .zero, expectedRejection: true, reactiveMinimum: 0.84),
        Case(name: "transparent_reactive_max_merge", reactive: 0.9,
             expectedMotion: .zero, reactiveMinimum: 0.89),
        Case(name: "reversed_z_sky", depth: 0, previousDepth: 0, history: true,
             sky: true, expectedMotion: .zero)
    ]
}

@available(macOS 13.0, *)
private final class Harness {
    let device: MTLDevice
    let queue: MTLCommandQueue
    let pipeline: MTLComputePipelineState
    let cameraPipeline: MTLComputePipelineState
    let mergePipeline: MTLComputePipelineState
    let handPipeline: MTLComputePipelineState

    init() throws {
        guard MemoryLayout<MetalFxMotionMergeUniforms>.stride == 112,
              MemoryLayout<MetalFxMotionMergeUniforms>.offset(of: \.previousFromCurrentClip) == 48 else {
            throw ContractError("Shipping Swift/MSL merge constant layout mismatch")
        }
        guard let device = MTLCreateSystemDefaultDevice(), let queue = device.makeCommandQueue() else {
            throw ContractError("No Metal device/queue")
        }
        self.device = device; self.queue = queue
        // Use exactly the same source and compile options as ensureMotionV2Pipelines.
        let library = try device.makeLibrary(source: motionFusedV2MslSource(), options: nil)
        guard let function = library.makeFunction(name: "metallum_motion_fused_v2") else {
            throw ContractError("Shipping fused kernel missing")
        }
        pipeline = try device.makeComputePipelineState(function: function)
        func make(_ source: String, _ name: String) throws -> MTLComputePipelineState {
            let library = try device.makeLibrary(source: source, options: nil)
            guard let f = library.makeFunction(name: name) else { throw ContractError("Missing shipping kernel \(name)") }
            return try device.makeComputePipelineState(function: f)
        }
        cameraPipeline = try make(motionCameraV2MslSource(), "metallum_motion_camera_v2")
        mergePipeline = try make(motionMergeV2MslSource(), "metallum_motion_merge_v2")
        handPipeline = try make(handOverlayMslSource(), "metallum_hand_overlay_motion")
    }

    func presentationOrientation(root: URL) throws -> [[String: Any]] {
        let width = 13, height = 9
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm, width: width, height: height, mipmapped: false)
        descriptor.storageMode = .shared; descriptor.usage = [.shaderRead, .renderTarget]
        guard let source = device.makeTexture(descriptor: descriptor) else { throw ContractError("Orientation source allocation") }
        var bytes = [UInt8](repeating: 255, count: width*height*4)
        for y in 0..<height { for x in 0..<width {
            let p = (y*width+x)*4
            bytes[p] = UInt8(x*17); bytes[p+1] = UInt8(y*23); bytes[p+2] = UInt8((x+3*y)*7%256)
        }}
        bytes.withUnsafeBytes { source.replace(region: MTLRegionMake2D(0,0,width,height), mipmapLevel: 0,
                                               withBytes: $0.baseAddress!, bytesPerRow: width*4) }
        try png(bytes, width: width, height: height, to: root.appendingPathComponent("orientation-source.png"))
        let samplerDescriptor = MTLSamplerDescriptor()
        samplerDescriptor.minFilter = .linear; samplerDescriptor.magFilter = .linear
        samplerDescriptor.sAddressMode = .clampToEdge; samplerDescriptor.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: samplerDescriptor) else { throw ContractError("Orientation sampler") }
        var results = [[String: Any]]()
        for flip in [false, true] {
            let name = flip ? "shipping_present_flips_y" : "shipping_internal_copy_preserves_y"
            let library = try device.makeLibrary(source: flip ? presentMslSource() : copyMslSource(), options: nil)
            let pso = MTLRenderPipelineDescriptor()
            pso.vertexFunction = library.makeFunction(name: "metallum_present_vs")
            pso.fragmentFunction = library.makeFunction(name: "metallum_present_fs")
            pso.colorAttachments[0].pixelFormat = .rgba8Unorm
            let pipeline = try device.makeRenderPipelineState(descriptor: pso)
            guard let output = device.makeTexture(descriptor: descriptor), let cb = queue.makeCommandBuffer() else {
                throw ContractError("Orientation output/command allocation")
            }
            let pass = MTLRenderPassDescriptor()
            pass.colorAttachments[0].texture = output
            pass.colorAttachments[0].loadAction = .clear; pass.colorAttachments[0].storeAction = .store
            guard let encoder = cb.makeRenderCommandEncoder(descriptor: pass) else { throw ContractError("Orientation encoder") }
            encoder.setRenderPipelineState(pipeline)
            encoder.setFragmentTexture(source, index: 0); encoder.setFragmentSamplerState(sampler, index: 0)
            encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
            encoder.endEncoding(); cb.commit(); cb.waitUntilCompleted()
            guard cb.status == .completed else { throw ContractError("Orientation GPU command failed: \(String(describing:cb.error))") }
            let actual = [UInt8](read(output, bytesPerPixel: 4))
            var violations = 0, maxError = 0
            for y in 0..<height { for x in 0..<width { for channel in 0..<4 {
                let expected = bytes[((flip ? height-1-y : y)*width+x)*4+channel]
                let error = abs(Int(actual[(y*width+x)*4+channel])-Int(expected))
                maxError = max(maxError,error); if error > 1 { violations += 1 }
            }}}
            try png(actual, width: width, height: height, to: root.appendingPathComponent(name+".png"))
            results.append(["name":name,"passed":violations == 0,"violations":violations,"maxByteError":maxError,
                            "width":width,"height":height,"scope":"actual shipping shader offscreen; not scanout/pacing"])
            print("PRESENT_ORIENTATION \(violations == 0 ? "PASS" : "FAIL") \(name) violations=\(violations)")
        }
        return results
    }

    // Runs on raw Metal even when both MetalFX supportsDevice queries are false.
    // Construct the same format-bound PSO the presenter creates unconditionally,
    // then exercise its copied and bounded input paths with independent texel IDs.
    func frameGenerationDepthInputs(root: URL) throws -> [[String: Any]] {
        let width = 13, height = 9
        var results = [[String: Any]]()
        for format in [MTLPixelFormat.r32Float, .depth32Float] {
            guard let pipeline = buildMotionDepthResamplePipeline(device: device, motionFormat: .rg16Float,
                                                                   depthFormat: format) else {
                throw ContractError("Presenter resampler rejected depth format \(format)")
            }
            func make(_ f: MTLPixelFormat, _ w: Int, _ h: Int) throws -> MTLTexture {
                let d = MTLTextureDescriptor.texture2DDescriptor(pixelFormat:f,width:w,height:h,mipmapped:false)
                // Depth32Float cannot be a shared texture. Use private textures
                // and staging blits for both formats so the oracle is identical.
                d.storageMode = .private; d.usage = [.shaderRead,.renderTarget]
                guard let t = device.makeTexture(descriptor:d) else { throw ContractError("Resample allocation") }
                return t
            }
            let depth = try make(format,width,height), motion = try make(.rg16Float,width,height)
            var z = [Float](repeating:0,count:width*height), xy = [UInt16](repeating:0,count:width*height*2)
            for p in 0..<z.count {
                z[p] = Float((p*17)%97)/100
                xy[p*2] = Float16(Float(p)/128).bitPattern
                xy[p*2+1] = Float16(-Float(p)/256).bitPattern
            }
            // Stable ties must take motion from the same winning depth texel.
            z[1] = z[0]
            let rowBytes = 256
            guard let depthUpload = device.makeBuffer(length:rowBytes*height),
                  let motionUpload = device.makeBuffer(length:rowBytes*height),
                  let upload = queue.makeCommandBuffer(), let blit = upload.makeBlitCommandEncoder() else {
                throw ContractError("Resample upload")
            }
            for y in 0..<height { for x in 0..<width {
                depthUpload.contents().storeBytes(of:z[y*width+x],toByteOffset:y*rowBytes+x*4,as:Float.self)
                for c in 0..<2 { motionUpload.contents().storeBytes(of:xy[(y*width+x)*2+c],
                    toByteOffset:y*rowBytes+x*4+c*2,as:UInt16.self) }
            }}
            for (buffer,texture) in [(depthUpload,depth),(motionUpload,motion)] {
                blit.copy(from:buffer,sourceOffset:0,sourceBytesPerRow:rowBytes,sourceBytesPerImage:rowBytes*height,
                    sourceSize:MTLSize(width:width,height:height,depth:1),to:texture,destinationSlice:0,
                    destinationLevel:0,destinationOrigin:MTLOrigin(x:0,y:0,z:0))
            }
            blit.endEncoding();upload.commit();upload.waitUntilCompleted()
            guard upload.status == .completed else { throw ContractError("Resample upload GPU error") }
            for (w,h) in [(width,height),(7,5)] {
                let name = "fg_depth_\(format == .r32Float ? "r32f" : "d32f")_\(w)x\(h)"
                let outZ = try make(format,w,h), outXY = try make(.rg16Float,w,h)
                guard let cb = queue.makeCommandBuffer() else { throw ContractError("Resample command") }
                if w == width && h == height {
                    guard let copy = cb.makeBlitCommandEncoder() else { throw ContractError("Resample copy") }
                    for (source,dest) in [(depth,outZ),(motion,outXY)] {
                        copy.copy(from:source,sourceSlice:0,sourceLevel:0,to:dest,destinationSlice:0,
                            destinationLevel:0,sliceCount:1,levelCount:1)
                    }
                    copy.endEncoding()
                } else {
                    let pass = MTLRenderPassDescriptor()
                    pass.colorAttachments[0].texture = outXY
                    pass.colorAttachments[0].loadAction = .dontCare;pass.colorAttachments[0].storeAction = .store
                    if format == .r32Float {
                        pass.colorAttachments[1].texture = outZ
                        pass.colorAttachments[1].loadAction = .dontCare;pass.colorAttachments[1].storeAction = .store
                    } else {
                        pass.depthAttachment.texture = outZ
                        pass.depthAttachment.loadAction = .dontCare;pass.depthAttachment.storeAction = .store
                    }
                    guard let e = cb.makeRenderCommandEncoder(descriptor:pass) else { throw ContractError("Resample render encoder") }
                    e.setRenderPipelineState(pipeline)
                    if format == .depth32Float {
                        let state = MTLDepthStencilDescriptor();state.depthCompareFunction = .always;state.isDepthWriteEnabled = true
                        e.setDepthStencilState(device.makeDepthStencilState(descriptor:state))
                    }
                    e.setFragmentTexture(depth,index:0);e.setFragmentTexture(motion,index:1)
                    var extent = SIMD2<UInt32>(UInt32(w),UInt32(h))
                    e.setFragmentBytes(&extent,length:MemoryLayout<SIMD2<UInt32>>.stride,index:0)
                    e.setViewport(MTLViewport(originX:0,originY:0,width:Double(w),height:Double(h),znear:0,zfar:1))
                    e.drawPrimitives(type:.triangle,vertexStart:0,vertexCount:3);e.endEncoding()
                }
                guard let zRead = device.makeBuffer(length:rowBytes*h), let xyRead = device.makeBuffer(length:rowBytes*h),
                      let copy = cb.makeBlitCommandEncoder() else { throw ContractError("Resample readback") }
                for (texture,buffer) in [(outZ,zRead),(outXY,xyRead)] {
                    copy.copy(from:texture,sourceSlice:0,sourceLevel:0,sourceOrigin:MTLOrigin(x:0,y:0,z:0),
                        sourceSize:MTLSize(width:w,height:h,depth:1),to:buffer,destinationOffset:0,
                        destinationBytesPerRow:rowBytes,destinationBytesPerImage:rowBytes*h)
                }
                copy.endEncoding();cb.commit();cb.waitUntilCompleted()
                guard cb.status == .completed else { throw ContractError("Resample GPU error: \(String(describing:cb.error))") }
                var violations = 0
                for y in 0..<h { for x in 0..<w {
                    var selected = y*width+x
                    if w != width || h != height {
                        let bx = max(0,min(width-1,Int(floor((Double(x)+0.5)*Double(width)/Double(w)-0.5))))
                        let by = max(0,min(height-1,Int(floor((Double(y)+0.5)*Double(height)/Double(h)-0.5))))
                        let nx = min(bx+1,width-1), ny = min(by+1,height-1)
                        let candidates = [by*width+bx,by*width+nx,ny*width+bx,ny*width+nx]
                        selected = candidates[0]
                        for p in candidates.dropFirst() where z[p] > z[selected] { selected = p }
                    }
                    let actual = zRead.contents().load(fromByteOffset:y*rowBytes+x*4,as:Float.self)
                    if abs(actual-z[selected]) > 0.000001 { violations += 1 }
                    for c in 0..<2 {
                        let value = xyRead.contents().load(fromByteOffset:y*rowBytes+x*4+c*2,as:UInt16.self)
                        if value != xy[selected*2+c] { violations += 1 }
                    }
                }}
                results.append(["name":name,"passed":violations == 0,"violations":violations,"width":w,"height":h,
                    "metalfxRequired":false,"scope":"shipping presenter PSO and depth/motion texel pairing; not presentation"])
                print("FG_DEPTH_INPUT \(violations == 0 ? "PASS" : "FAIL") \(name) violations=\(violations)")
            }
        }
        return results
    }

    func texture(_ format: MTLPixelFormat, _ width: Int, _ height: Int,
                 _ value: SIMD2<Float>) throws -> MTLTexture {
        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: format, width: width,
                                                          height: height, mipmapped: false)
        desc.storageMode = .shared; desc.usage = [.shaderRead, .shaderWrite]
        guard let result = device.makeTexture(descriptor: desc) else { throw ContractError("Texture allocation failed") }
        let count = width * height
        switch format {
        case .rg16Float:
            var values = [UInt16](repeating: 0, count: count * 2)
            for i in 0..<count { values[i*2] = Float16(value.x).bitPattern; values[i*2+1] = Float16(value.y).bitPattern }
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width * 4) }
        case .r32Float:
            let values = [Float](repeating: value.x, count: count)
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width * 4) }
        case .r8Unorm:
            let values = [UInt8](repeating: UInt8((max(0, min(1, value.x)) * 255).rounded()), count: count)
            values.withUnsafeBytes { result.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0,
                                                   withBytes: $0.baseAddress!, bytesPerRow: width) }
        default: throw ContractError("Unexpected format")
        }
        return result
    }

    func read(_ texture: MTLTexture, bytesPerPixel: Int) -> Data {
        var data = Data(count: texture.width * texture.height * bytesPerPixel)
        data.withUnsafeMutableBytes { texture.getBytes($0.baseAddress!, bytesPerRow: texture.width * bytesPerPixel,
                                                     from: MTLRegionMake2D(0, 0, texture.width, texture.height), mipmapLevel: 0) }
        return data
    }

    func encode<U>(_ cb: MTLCommandBuffer, _ pipeline: MTLComputePipelineState,
                   _ uniforms: inout U, _ textures: [MTLTexture], _ label: String) throws {
        guard let encoder = cb.makeComputeCommandEncoder() else { throw ContractError("Compute encoder failed") }
        encoder.label = label
        encoder.setComputePipelineState(pipeline)
        encoder.setBytes(&uniforms, length: MemoryLayout<U>.stride, index: 0)
        for (index, texture) in textures.enumerated() { encoder.setTexture(texture, index: index) }
        let w = max(1, min(pipeline.threadExecutionWidth, 64))
        let h = max(1, min(8, pipeline.maxTotalThreadsPerThreadgroup / w))
        encoder.dispatchThreads(MTLSize(width: textures[0].width, height: textures[0].height, depth: 1),
                                threadsPerThreadgroup: MTLSize(width: w, height: h, depth: 1))
        encoder.endEncoding()
    }

    func run(_ c: Case, width: Int, height: Int, root: URL, fused: Bool) throws -> [String: Any] {
        let backend = fused ? "fused" : "split"
        let dir = root.appendingPathComponent("\(backend)-\(width)x\(height)-\(c.name)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let depth = try texture(.r32Float, width, height, SIMD2(c.depth, 0))
        let object = try texture(.rg16Float, width, height, c.object)
        let valid = try texture(.r8Unorm, width, height, SIMD2(c.valid, 0))
        let previous = try texture(.r32Float, width, height, SIMD2(c.previousDepth, 0))
        let motion = try texture(.rg16Float, width, height, SIMD2(30, 30))
        let reactive = try texture(.r8Unorm, width, height, SIMD2(c.reactive, 0))
        let camera = try texture(.rg16Float, width, height, SIMD2(30, 30))
        let rejection = try texture(.r8Unorm, width, height, SIMD2(0.5, 0))
        let hand = try texture(.r32Float, width, height, SIMD2(c.hand, 0))
        let handExact = try texture(.r8Unorm, width, height, SIMD2(c.handExact, 0))
        var u = FusedUniforms(current: matrix_identity_float4x4, inverse: c.inverse,
                              previous: c.previous, viewport: SIMD4(Float(width), Float(height), 0, 0),
                              flags: SIMD4(1, c.sky ? 1 : 0, c.history ? 1 : 0, c.reversed ? 1 : 0),
                              options: SIMD4(0, 1, c.hand > 0 ? 1 : 0, c.handExact > 0 ? 1 : 0),
                              params: SIMD4(0.5, 0.85, 0.5, 0))
        guard let cb = queue.makeCommandBuffer() else {
            throw ContractError("Command buffer allocation failed")
        }
        let encodeStart = ProcessInfo.processInfo.systemUptime
        if fused {
            try encode(cb, pipeline, &u, [depth, object, valid, previous, motion, reactive, camera, rejection, hand, handExact], c.name)
        } else {
            if c.hand > 0 {
                var hu = HandUniforms(width: UInt32(width), height: UInt32(height), boost: 0.5, exact: c.handExact > 0 ? 1 : 0)
                try encode(cb, handPipeline, &hu, [hand, object, valid, reactive, handExact], c.name + " hand")
            }
            var cu = CameraUniforms(current: u.current, inverse: u.inverse, previous: u.previous,
                                    viewport: u.viewport, flags: SIMD4(1, c.sky ? 1 : 0, 0, 0), params: SIMD4(0.5, 0, 0, 0))
            try encode(cb, cameraPipeline, &cu, [depth, camera, rejection, reactive], c.name + " camera")
            var mu = MetalFxMotionMergeUniforms(
                viewport: SIMD4(UInt32(width), UInt32(height), c.history ? 1 : 0, c.reversed ? 1 : 0),
                flags: SIMD4(c.sky ? 1 : 0, 0, 0, 0), params: SIMD4(0.85, 0, 0, 0),
                previousFromCurrentClip: c.previous * c.inverse)
            try encode(cb, mergePipeline, &mu, [camera, object, valid, rejection, motion, reactive, previous, depth], c.name + " merge")
        }
        let encodeMilliseconds = (ProcessInfo.processInfo.systemUptime - encodeStart) * 1000
        cb.commit(); cb.waitUntilCompleted()
        guard cb.status == .completed, cb.error == nil else { throw ContractError("GPU failed: \(String(describing: cb.error))") }
        let motionData = read(motion, bytesPerPixel: 4), reactiveData = read(reactive, bytesPerPixel: 1)
        let rejectionData = read(rejection, bytesPerPixel: 1)
        try motionData.write(to: dir.appendingPathComponent("motion.rg16f"))
        try reactiveData.write(to: dir.appendingPathComponent("reactive.r8"))
        try rejectionData.write(to: dir.appendingPathComponent("disocclusion.r8"))
        try read(depth, bytesPerPixel: 4).write(to: dir.appendingPathComponent("depth.r32f"))
        var violations = 0, checked = 0, maximumError: Float = 0
        var visualization = [UInt8](repeating: 255, count: width * height * 4)
        for y in 0..<height { for x in 0..<width {
            let p = y*width+x, offset = p*4
            let vx = Float(Float16(bitPattern: UInt16(motionData[offset]) | UInt16(motionData[offset+1]) << 8))
            let vy = Float(Float16(bitPattern: UInt16(motionData[offset+2]) | UInt16(motionData[offset+3]) << 8))
            if !vx.isFinite || !vy.isFinite { violations += 1 }
            visualization[p*4] = UInt8(max(0, min(255, vx.isFinite ? 128 + vx*128 : 255)))
            visualization[p*4+1] = UInt8(max(0, min(255, vy.isFinite ? 128 + vy*128 : 0)))
            visualization[p*4+2] = rejectionData[p]
            // Use an interior region to keep border visibility changes separate from the oracle.
            guard x >= width/3, x < width*2/3, y >= height/3, y < height*2/3 else { continue }
            checked += 1
            let expected: SIMD2<Float>
            if let constant = c.expectedMotion { expected = constant }
            else {
                // Independent affine rotation of a top-left pixel center; no production math helper.
                let px = 2 * (Float(x)+0.5)/Float(width)-1
                let py = 1-2 * (Float(y)+0.5)/Float(height)
                let a: Float = 0.05
                expected = SIMD2(cos(a)*px - sin(a)*py - px, py - sin(a)*px - cos(a)*py)
            }
            let error = max(abs(vx-expected.x), abs(vy-expected.y))
            maximumError = max(maximumError, error)
            if !error.isFinite || error > 0.001 { violations += 1 }
            if (rejectionData[p] >= 128) != c.expectedRejection { violations += 1 }
            if Float(reactiveData[p])/255 < c.reactiveMinimum { violations += 1 }
        }}
        try png(visualization, width: width, height: height, to: dir.appendingPathComponent("motion.png"))
        var scalar = [UInt8](repeating: 255, count: width*height*4)
        for p in 0..<width*height {
            for channel in 0..<3 { scalar[p*4+channel] = reactiveData[p] }
        }
        try png(scalar, width: width, height: height, to: dir.appendingPathComponent("reactive.png"))
        let depthValue = UInt8(max(0,min(255,c.depth*255)))
        for p in 0..<width*height { for channel in 0..<3 { scalar[p*4+channel] = depthValue } }
        try png(scalar, width: width, height: height, to: dir.appendingPathComponent("depth.png"))
        let result: [String: Any] = ["name":c.name,"width":width,"height":height,
                                   "backend":backend,"cpuEncodingMilliseconds":encodeMilliseconds,
                                   "passed":violations == 0,"violations":violations,"checkedPixels":checked,
                                   "maxNdcError":maximumError.isFinite ? maximumError : -1,
                                   "motionUnit":"unjittered current-to-previous NDC, top-left Y",
                                   "motionVectorScale":[Float(width)/2,Float(height)/2],
                                   "gpuMilliseconds":max(0,(cb.gpuEndTime-cb.gpuStartTime)*1000)]
        try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted,.sortedKeys])
            .write(to: dir.appendingPathComponent("result.json"))
        print("MOTION_CONTRACT \(violations == 0 ? "PASS" : "FAIL") \(backend) \(width)x\(height) \(c.name) violations=\(violations) error=\(maximumError)")
        return result
    }
}

private func png(_ rgba: [UInt8], width: Int, height: Int, to url: URL) throws {
    let data = Data(rgba)
    guard let provider = CGDataProvider(data: data as CFData),
          let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                              bytesPerRow: width*4, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                              provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent),
          let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
        throw ContractError("PNG creation failed")
    }
    CGImageDestinationAddImage(destination, image, nil)
    if !CGImageDestinationFinalize(destination) { throw ContractError("PNG export failed") }
}

@main
private enum MetalMotionContractValidation {
    static func main() {
        do {
            guard #available(macOS 13.0, *) else { throw ContractError("macOS 13 required") }
            let root = URL(fileURLWithPath: CommandLine.arguments.dropFirst().first ?? "build/metal-validation/motion-current")
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let h = try Harness()
            var results = [[String: Any]]()
            for (width,height) in [(32,24),(37,19)] {
                for fused in [true, false] {
                    for c in cases(width:width,height:height) {
                        results.append(try h.run(c,width:width,height:height,root:root,fused:fused))
                    }
                }
            }
            let orientation = try h.presentationOrientation(root: root)
            let fgDepthInputs = try h.frameGenerationDepthInputs(root: root)
            let failed = results.filter { ($0["passed"] as? Bool) != true }.count
                + orientation.filter { ($0["passed"] as? Bool) != true }.count
                + fgDepthInputs.filter { ($0["passed"] as? Bool) != true }.count
            let result: [String: Any] = ["sourceCommit":ProcessInfo.processInfo.environment["METALLUM_SOURCE_SHA"] ?? "unknown",
                                       "device":h.device.name,"passed":failed == 0,"cases":results,"failedCases":failed,
                                       "kernel":"shipping fused, camera, merge and hand kernels","metalfxQualityValidated":false,
                                       "presentationShaderOrientation":orientation,
                                       "frameGenerationDepthInputs":fgDepthInputs,
                                       "presentationValidated":false]
            try JSONSerialization.data(withJSONObject: result, options:[.prettyPrinted,.sortedKeys])
                .write(to:root.appendingPathComponent("summary.json"),options:.atomic)
            if failed != 0 { throw ContractError("\(failed) motion cases failed") }
            print("METAL_MOTION_CONTRACT_PASS cases=\(results.count)")
        } catch { fputs("METAL_MOTION_CONTRACT_FAIL: \(error)\n",stderr); exit(1) }
    }
}
