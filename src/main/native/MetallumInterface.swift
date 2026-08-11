import Foundation
import Metal

// MARK: - Shared packet helpers

private func packetLoad<T>(
    _ pointer: UnsafeRawPointer,
    _ offset: Int,
    as type: T.Type
) -> T {
    pointer.loadUnaligned(fromByteOffset: offset, as: type)
}

private func packetObject(_ address: UInt64) -> AnyObject? {
    guard address != 0, let pointer = UnsafeMutableRawPointer(bitPattern: UInt(address)) else {
        return nil
    }
    return Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
}

private let renderStageVertex: UInt32 = 1
private let renderStageFragment: UInt32 = 2
private let renderStageAll: UInt32 = renderStageVertex | renderStageFragment

private func validRenderStageMask(_ mask: UInt32) -> Bool {
    mask != 0 && (mask & ~renderStageAll) == 0
}

// MARK: - Versioned render-state packet ABI

private let renderStatePacketMagic: UInt32 = 0x4D525350
private let renderStatePacketVersion: UInt32 = 1
private let renderStatePacketHeaderSize = 16
private let renderStatePacketEntrySize = 48

private enum RenderStatePacketOpcode: UInt32 {
    case pipeline = 1
    case depthStencil = 2
    case depthBias = 3
    case winding = 4
    case cullMode = 5
    case fillMode = 6
    case buffer = 7
    case bufferOffset = 8
    case texture = 9
    case textureAndSampler = 10
    case scissor = 11
}

private struct RenderStatePacketEntry {
    let opcode: RenderStatePacketOpcode
    let stageMask: UInt32
    let index: UInt64
    let a: UInt64
    let b: UInt64
    let c: UInt64
    let d: UInt64
}

private func decodeRenderStateEntry(
    _ packet: UnsafeRawPointer,
    _ entryIndex: Int
) -> RenderStatePacketEntry? {
    let base = renderStatePacketHeaderSize + entryIndex * renderStatePacketEntrySize
    guard let opcode = RenderStatePacketOpcode(
        rawValue: packetLoad(packet, base, as: UInt32.self)
    ) else {
        return nil
    }
    return RenderStatePacketEntry(
        opcode: opcode,
        stageMask: packetLoad(packet, base + 4, as: UInt32.self),
        index: packetLoad(packet, base + 8, as: UInt64.self),
        a: packetLoad(packet, base + 16, as: UInt64.self),
        b: packetLoad(packet, base + 24, as: UInt64.self),
        c: packetLoad(packet, base + 32, as: UInt64.self),
        d: packetLoad(packet, base + 40, as: UInt64.self)
    )
}

private func validateRenderStateEntry(_ entry: RenderStatePacketEntry) -> Bool {
    guard Int(exactly: entry.index) != nil else { return false }
    switch entry.opcode {
    case .pipeline:
        return packetObject(entry.a) as? MTLRenderPipelineState != nil
    case .depthStencil:
        return entry.a == 0 || packetObject(entry.a) as? MTLDepthStencilState != nil
    case .depthBias, .scissor:
        return true
    case .winding:
        return MTLWinding(rawValue: UInt(entry.a)) != nil
    case .cullMode:
        return MTLCullMode(rawValue: UInt(entry.a)) != nil
    case .fillMode:
        return MTLTriangleFillMode(rawValue: UInt(entry.a)) != nil
    case .buffer:
        return validRenderStageMask(entry.stageMask)
            && (entry.a == 0 || packetObject(entry.a) as? MTLBuffer != nil)
    case .bufferOffset:
        return validRenderStageMask(entry.stageMask)
    case .texture:
        return validRenderStageMask(entry.stageMask)
            && (entry.a == 0 || packetObject(entry.a) as? MTLTexture != nil)
    case .textureAndSampler:
        return validRenderStageMask(entry.stageMask)
            && (entry.a == 0 || packetObject(entry.a) as? MTLTexture != nil)
            && (entry.b == 0 || packetObject(entry.b) as? MTLSamplerState != nil)
    }
}

private func applyRenderStateEntry(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ entry: RenderStatePacketEntry
) {
    let stageMask = Int32(bitPattern: entry.stageMask)
    switch entry.opcode {
    case .pipeline:
        metallum_MTLRenderCommandEncoder_setRenderPipelineState(
            encoderPointer,
            packetObject(entry.a) as! MTLRenderPipelineState
        )
    case .depthStencil:
        metallum_MTLRenderCommandEncoder_setDepthStencilState(
            encoderPointer,
            packetObject(entry.a) as? MTLDepthStencilState
        )
    case .depthBias:
        metallum_MTLRenderCommandEncoder_setDepthBias(
            encoderPointer,
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.a)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.b)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.c))
        )
    case .winding:
        metallum_MTLRenderCommandEncoder_setFrontFacingWinding(
            encoderPointer,
            MTLWinding(rawValue: UInt(entry.a))!
        )
    case .cullMode:
        metallum_MTLRenderCommandEncoder_setCullMode(
            encoderPointer,
            MTLCullMode(rawValue: UInt(entry.a))!
        )
    case .fillMode:
        metallum_MTLRenderCommandEncoder_setTriangleFillMode(
            encoderPointer,
            MTLTriangleFillMode(rawValue: UInt(entry.a))!
        )
    case .buffer:
        metallum_MTLRenderCommandEncoder_setBuffer(
            encoderPointer,
            packetObject(entry.a) as? MTLBuffer,
            entry.b,
            entry.index,
            stageMask
        )
    case .bufferOffset:
        metallum_MTLRenderCommandEncoder_setBufferOffset(
            encoderPointer,
            entry.a,
            entry.index,
            stageMask
        )
    case .texture:
        metallum_MTLRenderCommandEncoder_setTexture(
            encoderPointer,
            packetObject(entry.a) as? MTLTexture,
            entry.index,
            stageMask
        )
    case .textureAndSampler:
        metallum_MTLRenderCommandEncoder_setTextureAndSampler(
            encoderPointer,
            packetObject(entry.a) as? MTLTexture,
            packetObject(entry.b) as? MTLSamplerState,
            entry.index,
            stageMask
        )
    case .scissor:
        metallum_MTLRenderCommandEncoder_setScissorRect(
            encoderPointer,
            entry.index,
            entry.a,
            entry.b,
            entry.c
        )
    }
}

@_cdecl("metallum_render_state_packet_apply_v1")
public func metallum_render_state_packet_apply_v1(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ rawPacket: UnsafeRawPointer?,
    _ rawByteCount: Int64
) -> Int32 {
    guard let packet = rawPacket,
          let byteCount = Int(exactly: rawByteCount),
          byteCount >= renderStatePacketHeaderSize else {
        return -1
    }
    guard packetLoad(packet, 0, as: UInt32.self) == renderStatePacketMagic,
          packetLoad(packet, 4, as: UInt32.self) == renderStatePacketVersion else {
        return -2
    }
    let declaredByteCount = Int(packetLoad(packet, 8, as: UInt32.self))
    let entryCount = Int(packetLoad(packet, 12, as: UInt32.self))
    guard entryCount <= (Int.max - renderStatePacketHeaderSize) / renderStatePacketEntrySize else {
        return -3
    }
    let expectedByteCount = renderStatePacketHeaderSize + entryCount * renderStatePacketEntrySize
    guard declaredByteCount == expectedByteCount, byteCount >= expectedByteCount else {
        return -4
    }
    for index in 0..<entryCount {
        guard let entry = decodeRenderStateEntry(packet, index),
              validateRenderStateEntry(entry) else {
            return -5
        }
    }
    for index in 0..<entryCount {
        applyRenderStateEntry(encoderPointer, decodeRenderStateEntry(packet, index)!)
    }
    return Int32(entryCount)
}

// MARK: - Ordered render command packet ABI

private let renderCommandPacketMagic: UInt32 = 0x4D524350
private let renderCommandPacketVersion: UInt32 = 1
private let renderCommandPacketHeaderSize = 24
private let renderCommandPacketEntrySize = 64

private enum RenderCommandPacketOpcode: UInt32 {
    case pipeline = 1
    case depthStencil = 2
    case depthBias = 3
    case winding = 4
    case cullMode = 5
    case fillMode = 6
    case buffer = 7
    case bufferOffset = 8
    case texture = 9
    case textureAndSampler = 10
    case scissor = 11
    case drawPrimitives = 32
    case drawIndexed = 33
    case drawPrimitivesIndirect = 34
    case drawIndexedIndirect = 35
}

private struct RenderCommandPacketEntry {
    let opcode: RenderCommandPacketOpcode
    let flags: UInt32
    let a: UInt64
    let b: UInt64
    let c: UInt64
    let d: UInt64
    let e: UInt64
    let f: UInt64
    let g: UInt64
}

private func decodeRenderCommandEntry(
    _ packet: UnsafeRawPointer,
    _ operationIndex: Int
) -> RenderCommandPacketEntry? {
    let base = renderCommandPacketHeaderSize + operationIndex * renderCommandPacketEntrySize
    guard let opcode = RenderCommandPacketOpcode(
        rawValue: packetLoad(packet, base, as: UInt32.self)
    ) else {
        return nil
    }
    return RenderCommandPacketEntry(
        opcode: opcode,
        flags: packetLoad(packet, base + 4, as: UInt32.self),
        a: packetLoad(packet, base + 8, as: UInt64.self),
        b: packetLoad(packet, base + 16, as: UInt64.self),
        c: packetLoad(packet, base + 24, as: UInt64.self),
        d: packetLoad(packet, base + 32, as: UInt64.self),
        e: packetLoad(packet, base + 40, as: UInt64.self),
        f: packetLoad(packet, base + 48, as: UInt64.self),
        g: packetLoad(packet, base + 56, as: UInt64.self)
    )
}

private func exactInt(_ value: UInt64) -> Int? {
    Int(exactly: value)
}

private func renderCommandPackedHigh(_ value: UInt64) -> Int {
    Int(Int32(bitPattern: UInt32(truncatingIfNeeded: value >> 32)))
}

private func renderCommandPackedLow(_ value: UInt64) -> Int {
    Int(Int32(bitPattern: UInt32(truncatingIfNeeded: value)))
}

private func validateRenderCommandEntry(_ entry: RenderCommandPacketEntry) -> Bool {
    switch entry.opcode {
    case .pipeline:
        return packetObject(entry.a) as? MTLRenderPipelineState != nil
    case .depthStencil:
        return entry.a == 0 || packetObject(entry.a) as? MTLDepthStencilState != nil
    case .depthBias:
        return true
    case .winding:
        return MTLWinding(rawValue: UInt(entry.a)) != nil
    case .cullMode:
        return MTLCullMode(rawValue: UInt(entry.a)) != nil
    case .fillMode:
        return MTLTriangleFillMode(rawValue: UInt(entry.a)) != nil
    case .buffer:
        return validRenderStageMask(entry.flags)
            && (entry.a == 0 || packetObject(entry.a) as? MTLBuffer != nil)
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
    case .bufferOffset:
        return validRenderStageMask(entry.flags)
            && exactInt(entry.a) != nil
            && exactInt(entry.b) != nil
    case .texture:
        return validRenderStageMask(entry.flags)
            && (entry.a == 0 || packetObject(entry.a) as? MTLTexture != nil)
            && exactInt(entry.b) != nil
    case .textureAndSampler:
        return validRenderStageMask(entry.flags)
            && (entry.a == 0 || packetObject(entry.a) as? MTLTexture != nil)
            && (entry.b == 0 || packetObject(entry.b) as? MTLSamplerState != nil)
            && exactInt(entry.c) != nil
    case .scissor:
        return exactInt(entry.a) != nil
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
            && exactInt(entry.d) != nil
    case .drawPrimitives:
        return MTLPrimitiveType(rawValue: UInt(entry.a)) != nil
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
            && exactInt(entry.d) != nil
            && exactInt(entry.e) != nil
    case .drawIndexed:
        return MTLPrimitiveType(rawValue: UInt(entry.a)) != nil
            && exactInt(entry.b) != nil
            && MTLIndexType(rawValue: UInt(entry.c)) != nil
            && packetObject(entry.d) as? MTLBuffer != nil
            && exactInt(entry.e) != nil
            && exactInt(entry.f) != nil
    case .drawPrimitivesIndirect:
        return MTLPrimitiveType(rawValue: UInt(entry.a)) != nil
            && packetObject(entry.b) as? MTLBuffer != nil
            && exactInt(entry.c) != nil
            && exactInt(entry.d) != nil
            && exactInt(entry.e) != nil
    case .drawIndexedIndirect:
        return MTLPrimitiveType(rawValue: UInt(entry.a)) != nil
            && MTLIndexType(rawValue: UInt(entry.b)) != nil
            && packetObject(entry.c) as? MTLBuffer != nil
            && packetObject(entry.d) as? MTLBuffer != nil
            && exactInt(entry.e) != nil
            && exactInt(entry.f) != nil
            && exactInt(entry.g) != nil
    }
}

private func applyRenderCommandEntry(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ entry: RenderCommandPacketEntry
) {
    let stageMask = Int32(bitPattern: entry.flags)
    switch entry.opcode {
    case .pipeline:
        metallum_MTLRenderCommandEncoder_setRenderPipelineState(
            encoderPointer,
            packetObject(entry.a) as! MTLRenderPipelineState
        )
    case .depthStencil:
        metallum_MTLRenderCommandEncoder_setDepthStencilState(
            encoderPointer,
            packetObject(entry.a) as? MTLDepthStencilState
        )
    case .depthBias:
        metallum_MTLRenderCommandEncoder_setDepthBias(
            encoderPointer,
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.a)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.b)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.c))
        )
    case .winding:
        metallum_MTLRenderCommandEncoder_setFrontFacingWinding(
            encoderPointer,
            MTLWinding(rawValue: UInt(entry.a))!
        )
    case .cullMode:
        metallum_MTLRenderCommandEncoder_setCullMode(
            encoderPointer,
            MTLCullMode(rawValue: UInt(entry.a))!
        )
    case .fillMode:
        metallum_MTLRenderCommandEncoder_setTriangleFillMode(
            encoderPointer,
            MTLTriangleFillMode(rawValue: UInt(entry.a))!
        )
    case .buffer:
        metallum_MTLRenderCommandEncoder_setBuffer(
            encoderPointer,
            packetObject(entry.a) as? MTLBuffer,
            entry.b,
            entry.c,
            stageMask
        )
    case .bufferOffset:
        metallum_MTLRenderCommandEncoder_setBufferOffset(
            encoderPointer,
            entry.a,
            entry.b,
            stageMask
        )
    case .texture:
        metallum_MTLRenderCommandEncoder_setTexture(
            encoderPointer,
            packetObject(entry.a) as? MTLTexture,
            entry.b,
            stageMask
        )
    case .textureAndSampler:
        metallum_MTLRenderCommandEncoder_setTextureAndSampler(
            encoderPointer,
            packetObject(entry.a) as? MTLTexture,
            packetObject(entry.b) as? MTLSamplerState,
            entry.c,
            stageMask
        )
    case .scissor:
        metallum_MTLRenderCommandEncoder_setScissorRect(
            encoderPointer,
            entry.a,
            entry.b,
            entry.c,
            entry.d
        )
    case .drawPrimitives:
        metallum_MTLRenderCommandEncoder_drawPrimitives(
            encoderPointer,
            MTLPrimitiveType(rawValue: UInt(entry.a))!,
            exactInt(entry.b)!,
            exactInt(entry.c)!,
            exactInt(entry.d)!,
            exactInt(entry.e)!
        )
    case .drawIndexed:
        metallum_MTLRenderCommandEncoder_drawIndexedPrimitives(
            encoderPointer,
            MTLPrimitiveType(rawValue: UInt(entry.a))!,
            exactInt(entry.b)!,
            MTLIndexType(rawValue: UInt(entry.c))!,
            packetObject(entry.d) as! MTLBuffer,
            exactInt(entry.e)!,
            exactInt(entry.f)!,
            renderCommandPackedHigh(entry.g),
            renderCommandPackedLow(entry.g)
        )
    case .drawPrimitivesIndirect:
        metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect(
            encoderPointer,
            MTLPrimitiveType(rawValue: UInt(entry.a))!,
            packetObject(entry.b) as! MTLBuffer,
            entry.c,
            exactInt(entry.d)!,
            entry.e
        )
    case .drawIndexedIndirect:
        metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
            encoderPointer,
            MTLPrimitiveType(rawValue: UInt(entry.a))!,
            MTLIndexType(rawValue: UInt(entry.b))!,
            packetObject(entry.c) as! MTLBuffer,
            packetObject(entry.d) as! MTLBuffer,
            entry.e,
            exactInt(entry.f)!,
            entry.g
        )
    }
}

@_cdecl("metallum_render_command_packet_apply_v1")
public func metallum_render_command_packet_apply_v1(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ rawPacket: UnsafeRawPointer?,
    _ rawByteCount: Int64
) -> Int32 {
    guard let packet = rawPacket,
          let byteCount = Int(exactly: rawByteCount),
          byteCount >= renderCommandPacketHeaderSize else {
        return -1
    }
    guard packetLoad(packet, 0, as: UInt32.self) == renderCommandPacketMagic,
          packetLoad(packet, 4, as: UInt32.self) == renderCommandPacketVersion,
          packetLoad(packet, 16, as: UInt32.self) == UInt32(renderCommandPacketEntrySize),
          packetLoad(packet, 20, as: UInt32.self) == 0 else {
        return -2
    }
    let declaredByteCount = Int(packetLoad(packet, 8, as: UInt32.self))
    let operationCount = Int(packetLoad(packet, 12, as: UInt32.self))
    guard operationCount <= Int(Int32.max),
          operationCount <= (Int.max - renderCommandPacketHeaderSize) / renderCommandPacketEntrySize else {
        return -3
    }
    let expected = renderCommandPacketHeaderSize + operationCount * renderCommandPacketEntrySize
    guard declaredByteCount == expected, byteCount >= expected else {
        return -4
    }
    for index in 0..<operationCount {
        guard let entry = decodeRenderCommandEntry(packet, index),
              validateRenderCommandEntry(entry) else {
            return -5
        }
    }
    // No negative result is returned after this point. Java may replay only
    // negative results without risking duplicate draw execution.
    for index in 0..<operationCount {
        applyRenderCommandEntry(encoderPointer, decodeRenderCommandEntry(packet, index)!)
    }
    return Int32(operationCount)
}

// MARK: - Ordered compute command packet ABI

private let computeCommandPacketMagic: UInt32 = 0x4D434350
private let computeCommandPacketVersion: UInt32 = 1
private let computeCommandPacketHeaderSize = 24
private let computeCommandPacketEntrySize = 64

private enum ComputeCommandPacketOpcode: UInt32 {
    case pipeline = 1
    case buffer = 2
    case texture = 3
    case sampler = 4
    case dispatch = 32
    case dispatchIndirect = 33
}

private struct ComputeCommandPacketEntry {
    let opcode: ComputeCommandPacketOpcode
    let a: UInt64
    let b: UInt64
    let c: UInt64
    let d: UInt64
    let e: UInt64
    let f: UInt64
    let g: UInt64
}

private func decodeComputeCommandEntry(
    _ packet: UnsafeRawPointer,
    _ operationIndex: Int
) -> ComputeCommandPacketEntry? {
    let base = computeCommandPacketHeaderSize + operationIndex * computeCommandPacketEntrySize
    guard packetLoad(packet, base + 4, as: UInt32.self) == 0,
          let opcode = ComputeCommandPacketOpcode(
            rawValue: packetLoad(packet, base, as: UInt32.self)
          ) else {
        return nil
    }
    return ComputeCommandPacketEntry(
        opcode: opcode,
        a: packetLoad(packet, base + 8, as: UInt64.self),
        b: packetLoad(packet, base + 16, as: UInt64.self),
        c: packetLoad(packet, base + 24, as: UInt64.self),
        d: packetLoad(packet, base + 32, as: UInt64.self),
        e: packetLoad(packet, base + 40, as: UInt64.self),
        f: packetLoad(packet, base + 48, as: UInt64.self),
        g: packetLoad(packet, base + 56, as: UInt64.self)
    )
}

private func validateComputeCommandEntry(_ entry: ComputeCommandPacketEntry) -> Bool {
    switch entry.opcode {
    case .pipeline:
        return packetObject(entry.a) as? MTLComputePipelineState != nil
    case .buffer:
        return (entry.a == 0 || packetObject(entry.a) as? MTLBuffer != nil)
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
    case .texture:
        return (entry.a == 0 || packetObject(entry.a) as? MTLTexture != nil)
            && exactInt(entry.b) != nil
    case .sampler:
        return (entry.a == 0 || packetObject(entry.a) as? MTLSamplerState != nil)
            && exactInt(entry.b) != nil
    case .dispatch:
        return exactInt(entry.a) != nil
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
            && exactInt(entry.d) != nil
            && exactInt(entry.e) != nil
            && exactInt(entry.f) != nil
    case .dispatchIndirect:
        return packetObject(entry.a) as? MTLBuffer != nil
            && exactInt(entry.b) != nil
            && exactInt(entry.c) != nil
            && exactInt(entry.d) != nil
            && exactInt(entry.e) != nil
    }
}

private func applyComputeCommandEntry(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ entry: ComputeCommandPacketEntry
) {
    switch entry.opcode {
    case .pipeline:
        metallum_MTLComputeCommandEncoder_setComputePipelineState(
            encoderPointer,
            packetObject(entry.a) as! MTLComputePipelineState
        )
    case .buffer:
        metallum_MTLComputeCommandEncoder_setBuffer(
            encoderPointer,
            packetObject(entry.a) as? MTLBuffer,
            entry.b,
            Int32(exactInt(entry.c)!)
        )
    case .texture:
        metallum_MTLComputeCommandEncoder_setTexture(
            encoderPointer,
            packetObject(entry.a) as? MTLTexture,
            Int32(exactInt(entry.b)!)
        )
    case .sampler:
        metallum_MTLComputeCommandEncoder_setSamplerState(
            encoderPointer,
            packetObject(entry.a) as? MTLSamplerState,
            Int32(exactInt(entry.b)!)
        )
    case .dispatch:
        metallum_MTLComputeCommandEncoder_dispatchThreadgroups(
            encoderPointer,
            Int32(exactInt(entry.a)!),
            Int32(exactInt(entry.b)!),
            Int32(exactInt(entry.c)!),
            Int32(exactInt(entry.d)!),
            Int32(exactInt(entry.e)!),
            Int32(exactInt(entry.f)!)
        )
    case .dispatchIndirect:
        metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
            encoderPointer,
            packetObject(entry.a) as! MTLBuffer,
            entry.b,
            Int32(exactInt(entry.c)!),
            Int32(exactInt(entry.d)!),
            Int32(exactInt(entry.e)!)
        )
    }
}

@_cdecl("metallum_compute_command_packet_apply_v1")
public func metallum_compute_command_packet_apply_v1(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ rawPacket: UnsafeRawPointer?,
    _ rawByteCount: Int64
) -> Int32 {
    guard let packet = rawPacket,
          let byteCount = Int(exactly: rawByteCount),
          byteCount >= computeCommandPacketHeaderSize else {
        return -1
    }
    guard packetLoad(packet, 0, as: UInt32.self) == computeCommandPacketMagic,
          packetLoad(packet, 4, as: UInt32.self) == computeCommandPacketVersion,
          packetLoad(packet, 16, as: UInt32.self) == UInt32(computeCommandPacketEntrySize),
          packetLoad(packet, 20, as: UInt32.self) == 0 else {
        return -2
    }
    let declaredByteCount = Int(packetLoad(packet, 8, as: UInt32.self))
    let operationCount = Int(packetLoad(packet, 12, as: UInt32.self))
    guard operationCount <= Int(Int32.max),
          operationCount <= (Int.max - computeCommandPacketHeaderSize) / computeCommandPacketEntrySize else {
        return -3
    }
    let expected = computeCommandPacketHeaderSize + operationCount * computeCommandPacketEntrySize
    guard declaredByteCount == expected, byteCount >= expected else {
        return -4
    }
    for index in 0..<operationCount {
        guard let entry = decodeComputeCommandEntry(packet, index),
              validateComputeCommandEntry(entry) else {
            return -5
        }
    }
    for index in 0..<operationCount {
        applyComputeCommandEntry(encoderPointer, decodeComputeCommandEntry(packet, index)!)
    }
    return Int32(operationCount)
}

// MARK: - Compiled render argument-buffer ABI

private let renderArgumentPacketMagic: UInt32 = 0x4D_41_42_47 // "MABG"
private let renderArgumentPacketVersion: UInt32 = 1
private let renderArgumentPacketHeaderSize = 24
private let renderArgumentPacketEntrySize = 48
private let renderArgumentPacketMaxEntries = 256

private enum RenderArgumentKind: UInt32 {
    case buffer = 1
    case texture = 2
    case sampler = 3
}

private struct RenderArgumentEntry {
    let kind: RenderArgumentKind
    let stageMask: UInt32
    let vertexIndex: Int32
    let fragmentIndex: Int32
    let objectAddress: UInt64
    let offset: UInt64
    let flags: UInt32
}

private final class MetallumRenderArgumentLayout {
    let vertex: MTLArgumentEncoder?
    let fragment: MTLArgumentEncoder?

    init?(
        vertexFunction: MTLFunction?,
        fragmentFunction: MTLFunction?,
        hasVertexArguments: Bool,
        hasFragmentArguments: Bool
    ) {
        if hasVertexArguments {
            guard let vertexFunction else { return nil }
            self.vertex = vertexFunction.makeArgumentEncoder(bufferIndex: 0)
        } else {
            self.vertex = nil
        }
        if hasFragmentArguments {
            guard let fragmentFunction else { return nil }
            self.fragment = fragmentFunction.makeArgumentEncoder(bufferIndex: 0)
        } else {
            self.fragment = nil
        }
        guard self.vertex != nil || self.fragment != nil else { return nil }
    }
}

private func decodeRenderArgumentEntry(
    _ packet: UnsafeRawPointer,
    _ entryIndex: Int
) -> RenderArgumentEntry? {
    let base = renderArgumentPacketHeaderSize + entryIndex * renderArgumentPacketEntrySize
    guard let kind = RenderArgumentKind(rawValue: packetLoad(packet, base, as: UInt32.self)) else {
        return nil
    }
    return RenderArgumentEntry(
        kind: kind,
        stageMask: packetLoad(packet, base + 4, as: UInt32.self),
        vertexIndex: packetLoad(packet, base + 8, as: Int32.self),
        fragmentIndex: packetLoad(packet, base + 12, as: Int32.self),
        objectAddress: packetLoad(packet, base + 16, as: UInt64.self),
        offset: packetLoad(packet, base + 24, as: UInt64.self),
        flags: packetLoad(packet, base + 32, as: UInt32.self)
    )
}

@_cdecl("metallum_render_argument_layout_create_v1")
public func metallum_render_argument_layout_create_v1(
    _ vertexFunction: MTLFunction?,
    _ fragmentFunction: MTLFunction?,
    _ hasVertexArguments: Int32,
    _ hasFragmentArguments: Int32
) -> UnsafeMutableRawPointer? {
    guard (hasVertexArguments == 0 || hasVertexArguments == 1),
          (hasFragmentArguments == 0 || hasFragmentArguments == 1),
          let layout = MetallumRenderArgumentLayout(
              vertexFunction: vertexFunction,
              fragmentFunction: fragmentFunction,
              hasVertexArguments: hasVertexArguments != 0,
              hasFragmentArguments: hasFragmentArguments != 0
          ) else {
        return nil
    }
    return Unmanaged.passRetained(layout).toOpaque()
}

/// Low 32 bits are the vertex encoded length, high 32 bits the fragment length.
@_cdecl("metallum_render_argument_layout_sizes_v1")
public func metallum_render_argument_layout_sizes_v1(
    _ layoutPointer: UnsafeMutableRawPointer
) -> UInt64 {
    let layout = Unmanaged<MetallumRenderArgumentLayout>
        .fromOpaque(layoutPointer).takeUnretainedValue()
    let vertex = UInt64(UInt32(exactly: layout.vertex?.encodedLength ?? 0) ?? UInt32.max)
    let fragment = UInt64(UInt32(exactly: layout.fragment?.encodedLength ?? 0) ?? UInt32.max)
    return vertex | (fragment << 32)
}

@_cdecl("metallum_render_argument_packet_apply_v1")
public func metallum_render_argument_packet_apply_v1(
    _ layoutPointer: UnsafeMutableRawPointer,
    _ encoderPointer: UnsafeMutableRawPointer,
    _ vertexArgumentBuffer: MTLBuffer?,
    _ vertexArgumentOffset: UInt64,
    _ fragmentArgumentBuffer: MTLBuffer?,
    _ fragmentArgumentOffset: UInt64,
    _ rawPacket: UnsafeRawPointer?,
    _ rawByteCount: Int64
) -> Int32 {
    guard let packet = rawPacket,
          let byteCount = Int(exactly: rawByteCount),
          byteCount >= renderArgumentPacketHeaderSize else {
        return -1
    }
    guard packetLoad(packet, 0, as: UInt32.self) == renderArgumentPacketMagic,
          packetLoad(packet, 4, as: UInt32.self) == renderArgumentPacketVersion,
          packetLoad(packet, 16, as: UInt32.self) == UInt32(renderArgumentPacketEntrySize),
          packetLoad(packet, 20, as: UInt32.self) == 0 else {
        return -2
    }
    let declaredByteCount = Int(packetLoad(packet, 8, as: UInt32.self))
    let entryCount = Int(packetLoad(packet, 12, as: UInt32.self))
    guard entryCount > 0,
          entryCount <= renderArgumentPacketMaxEntries,
          entryCount <= (Int.max - renderArgumentPacketHeaderSize) / renderArgumentPacketEntrySize else {
        return -3
    }
    let expectedByteCount = renderArgumentPacketHeaderSize
        + entryCount * renderArgumentPacketEntrySize
    guard declaredByteCount == expectedByteCount, byteCount >= expectedByteCount else {
        return -4
    }

    let layout = Unmanaged<MetallumRenderArgumentLayout>
        .fromOpaque(layoutPointer).takeUnretainedValue()
    guard let vertexOffset = Int(exactly: vertexArgumentOffset),
          let fragmentOffset = Int(exactly: fragmentArgumentOffset),
          vertexOffset >= 0,
          fragmentOffset >= 0 else {
        return -5
    }
    if let vertex = layout.vertex {
        guard let vertexArgumentBuffer,
              vertexOffset <= vertexArgumentBuffer.length,
              vertex.encodedLength <= vertexArgumentBuffer.length - vertexOffset else {
            return -6
        }
    } else if vertexArgumentBuffer != nil {
        return -6
    }
    if let fragment = layout.fragment {
        guard let fragmentArgumentBuffer,
              fragmentOffset <= fragmentArgumentBuffer.length,
              fragment.encodedLength <= fragmentArgumentBuffer.length - fragmentOffset else {
            return -7
        }
    } else if fragmentArgumentBuffer != nil {
        return -7
    }

    // Validate every entry and the complete destination-key set before
    // mutating either argument buffer. A negative result is therefore always
    // zero execution and safe for a caller to diagnose or fail closed.
    var entries: [RenderArgumentEntry] = []
    entries.reserveCapacity(entryCount)
    var destinationKeys = Set<UInt64>()
    for index in 0..<entryCount {
        guard let entry = decodeRenderArgumentEntry(packet, index),
              validRenderStageMask(entry.stageMask),
              (entry.flags & ~UInt32(1)) == 0,
              entry.objectAddress != 0 else {
            return -8
        }
        let object = packetObject(entry.objectAddress)
        switch entry.kind {
        case .buffer:
            guard let buffer = object as? MTLBuffer,
                  let offset = Int(exactly: entry.offset),
                  offset >= 0, offset <= buffer.length else { return -9 }
        case .texture:
            guard object is MTLTexture, entry.offset == 0 else { return -9 }
        case .sampler:
            guard object is MTLSamplerState, entry.offset == 0, entry.flags == 0 else { return -9 }
        }
        if (entry.stageMask & renderStageVertex) != 0 {
            guard layout.vertex != nil, entry.vertexIndex >= 0 else { return -10 }
            let key = (UInt64(renderStageVertex) << 48)
                | UInt64(UInt32(bitPattern: entry.vertexIndex))
            guard destinationKeys.insert(key).inserted else { return -11 }
        } else if entry.vertexIndex != -1 {
            return -10
        }
        if (entry.stageMask & renderStageFragment) != 0 {
            guard layout.fragment != nil, entry.fragmentIndex >= 0 else { return -10 }
            let key = (UInt64(renderStageFragment) << 48)
                | UInt64(UInt32(bitPattern: entry.fragmentIndex))
            guard destinationKeys.insert(key).inserted else { return -11 }
        } else if entry.fragmentIndex != -1 {
            return -10
        }
        entries.append(entry)
    }

    if let vertex = layout.vertex, let vertexArgumentBuffer {
        vertex.setArgumentBuffer(vertexArgumentBuffer, offset: vertexOffset)
    }
    if let fragment = layout.fragment, let fragmentArgumentBuffer {
        fragment.setArgumentBuffer(fragmentArgumentBuffer, offset: fragmentOffset)
    }

    let metal3Encoder = Unmanaged<AnyObject>.fromOpaque(encoderPointer)
        .takeUnretainedValue() as? MTLRenderCommandEncoder
    for entry in entries {
        let object = packetObject(entry.objectAddress)
        if (entry.stageMask & renderStageVertex) != 0, let vertex = layout.vertex {
            switch entry.kind {
            case .buffer:
                vertex.setBuffer(object as? MTLBuffer, offset: Int(entry.offset), index: Int(entry.vertexIndex))
            case .texture:
                vertex.setTexture(object as? MTLTexture, index: Int(entry.vertexIndex))
            case .sampler:
                vertex.setSamplerState(object as? MTLSamplerState, index: Int(entry.vertexIndex))
            }
        }
        if (entry.stageMask & renderStageFragment) != 0, let fragment = layout.fragment {
            switch entry.kind {
            case .buffer:
                fragment.setBuffer(object as? MTLBuffer, offset: Int(entry.offset), index: Int(entry.fragmentIndex))
            case .texture:
                fragment.setTexture(object as? MTLTexture, index: Int(entry.fragmentIndex))
            case .sampler:
                fragment.setSamplerState(object as? MTLSamplerState, index: Int(entry.fragmentIndex))
            }
        }
        if let metal3Encoder, let resource = object as? MTLResource {
            var usage: MTLResourceUsage = .read
            if (entry.flags & 1) != 0 { usage.insert(.write) }
            var stages: MTLRenderStages = []
            if (entry.stageMask & renderStageVertex) != 0 { stages.insert(.vertex) }
            if (entry.stageMask & renderStageFragment) != 0 { stages.insert(.fragment) }
            metal3Encoder.useResource(resource, usage: usage, stages: stages)
        }
    }

    // Do not bind the completed tables here. The Java encoder owns the
    // ordered state packet and its per-encoder shadow; bypassing it would make
    // buffer(0) appear unchanged after an argument-buffer pipeline and could
    // suppress the vertex-buffer restore for the next legacy pipeline. The
    // caller binds both tables only after this function has accepted and
    // encoded the complete snapshot.
    return Int32(entryCount)
}

// MARK: - Sodium terrain ICB (Metal 3 and Metal 4)

@_cdecl("metallum_terrain_icb_encode_indexed_v1")
public func metallum_terrain_icb_encode_indexed_v1(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ primitiveRaw: UInt64,
    _ indexTypeRaw: UInt64,
    _ indexBuffer: MTLBuffer,
    _ firstIndexOffsets: UnsafePointer<Int64>?,
    _ indexCounts: UnsafePointer<Int32>?,
    _ vertexOffsets: UnsafePointer<Int32>?,
    _ rawDrawCount: Int32,
    _ rawInstanceCount: Int32,
    _ rawBaseInstance: Int32
) -> Int32 {
    guard rawDrawCount > 0,
          rawDrawCount <= 16_384,
          rawInstanceCount > 0,
          let firstIndexOffsets,
          let indexCounts,
          let vertexOffsets,
          let primitive = MTLPrimitiveType(rawValue: UInt(primitiveRaw)),
          let indexType = MTLIndexType(rawValue: UInt(indexTypeRaw)) else {
        return 0
    }

    let object = Unmanaged<AnyObject>.fromOpaque(encoderPointer).takeUnretainedValue()
    let metal3Encoder = object as? MTLRenderCommandEncoder
    let isMetal4Encoder: Bool
    if #available(macOS 26.0, iOS 26.0, *) {
        isMetal4Encoder = object is Metal4MainRenderEncoderBridge
    } else {
        isMetal4Encoder = false
    }
    guard metal3Encoder != nil || isMetal4Encoder else {
        return 0
    }

    let drawCount = Int(rawDrawCount)
    let instanceCount = Int(rawInstanceCount)
    let baseInstance = Int(rawBaseInstance)
    let indexSize: Int
    switch indexType {
    case .uint16: indexSize = 2
    case .uint32: indexSize = 4
    @unknown default: return 0
    }

    // Validate the complete batch before creating or executing an ICB. A zero
    // return therefore guarantees that Java can use ordinary multi-draw.
    for draw in 0..<drawCount {
        let offset = Int(firstIndexOffsets[draw])
        let count = Int(indexCounts[draw])
        guard offset >= 0, offset % indexSize == 0, count > 0 else { return 0 }
        let (bytes, mulOverflow) = count.multipliedReportingOverflow(by: indexSize)
        let (end, addOverflow) = offset.addingReportingOverflow(bytes)
        guard !mulOverflow, !addOverflow, end <= indexBuffer.length else { return 0 }
    }

    let descriptor = MTLIndirectCommandBufferDescriptor()
    descriptor.commandTypes = .drawIndexed
    descriptor.inheritPipelineState = true
    descriptor.inheritBuffers = true
    descriptor.maxVertexBufferBindCount = 0
    descriptor.maxFragmentBufferBindCount = 0
    let icb: MTLIndirectCommandBuffer
    if isMetal4Encoder {
        guard #available(macOS 26.0, iOS 26.0, *),
              let transient = metallumMetal4AcquireTerrainIcb(
                  encoderPointer: encoderPointer,
                  descriptor: descriptor,
                  device: indexBuffer.device,
                  commandCount: drawCount
              ) else {
            return 0
        }
        icb = transient
    } else {
        guard let transient = indexBuffer.device.makeIndirectCommandBuffer(
                  descriptor: descriptor,
                  maxCommandCount: drawCount,
                  options: []
              ),
              transient.size > 0 else {
            metallumRecordTerrainIcbZeroAllocation(
                drawCount: drawCount,
                indexBytes: indexBuffer.length
            )
            return 0
        }
        transient.label = "Metallum Sodium Terrain ICB"
        icb = transient
    }

    for draw in 0..<drawCount {
        let command = icb.indirectRenderCommandAt(draw)
        command.drawIndexedPrimitives(
            primitive,
            indexCount: Int(indexCounts[draw]),
            indexType: indexType,
            indexBuffer: indexBuffer,
            indexBufferOffset: Int(firstIndexOffsets[draw]),
            instanceCount: instanceCount,
            baseVertex: Int(vertexOffsets[draw]),
            baseInstance: baseInstance
        )
    }
    if let metal3Encoder {
        metal3Encoder.useResource(indexBuffer, usage: .read, stages: .vertex)
        metal3Encoder.useResource(icb, usage: .read, stages: .vertex)
        metal3Encoder.executeCommandsInBuffer(icb, range: NSRange(location: 0, length: drawCount))
        return 1
    }
    if #available(macOS 26.0, iOS 26.0, *),
       metallumMetal4ExecuteTerrainIcb(
           encoderPointer: encoderPointer,
           indirectCommandBuffer: icb,
           commandCount: drawCount
       ) {
        return 1
    }
    return 0
}

@_cdecl("metallum_terrain_icb_stats_v1")
public func metallum_terrain_icb_stats_v1(
    _ allocations: UnsafeMutablePointer<UInt64>?,
    _ completionReleases: UnsafeMutablePointer<UInt64>?,
    _ budgetFallbacks: UnsafeMutablePointer<UInt64>?,
    _ zeroAllocationFallbacks: UnsafeMutablePointer<UInt64>?
) -> Int32 {
    guard let allocations,
          let completionReleases,
          let budgetFallbacks,
          let zeroAllocationFallbacks else {
        return -1
    }
    let stats = metallumTerrainIcbStatsSnapshot()
    allocations.pointee = stats.0
    completionReleases.pointee = stats.1
    budgetFallbacks.pointee = stats.2
    zeroAllocationFallbacks.pointee = stats.3
    return 0
}

@_cdecl("metallum_terrain_icb_submission_budget_v1")
public func metallum_terrain_icb_submission_budget_v1() -> Int32 {
    Int32(metallumMetal4TerrainIcbSubmissionBudget)
}

// MARK: - Versioned native interface

private let interfaceHeaderSize: UInt32 = 32
private let interfaceLock = NSLock()
private var interfaceTables: [UInt64: UnsafeMutableRawPointer] = [:]

enum MetallumInterfaceFeature: Int32 {
    case core = 1
    case metalFX = 2
    case renderStatePacket = 3
    case renderCommandPacket = 4
    case computeCommandPacket = 5
    case terrainIcb = 6
    case renderArgumentBindings = 7

    var currentVersion: UInt32 {
        switch self {
        case .core, .metalFX, .renderStatePacket,
             .renderCommandPacket, .computeCommandPacket, .terrainIcb,
             .renderArgumentBindings:
            return 1
        }
    }
}

enum MetallumInterfaceStatus: Int32 {
    case ok = 0
    case unknownFeature = 1
    case versionTooNew = 2
}

struct MetallumBuildCapability {
    static let core: UInt64 = 1 << 0
    static let raster: UInt64 = 1 << 1
    static let compute: UInt64 = 1 << 2
    static let metalFXSpatial: UInt64 = 1 << 3
    static let metalFXTemporal: UInt64 = 1 << 4
    static let frameGeneration: UInt64 = 1 << 5
    static let motionV2: UInt64 = 1 << 6
    static let cutoutReactive: UInt64 = 1 << 7
    static let handOverlay: UInt64 = 1 << 8
    static let presentationTimeline: UInt64 = 1 << 9
    static let renderStatePacket: UInt64 = 1 << 10
    static let renderCommandPacket: UInt64 = 1 << 11
    static let computeCommandPacket: UInt64 = 1 << 12
    static let terrainIcb: UInt64 = 1 << 13
    static let renderArgumentBindings: UInt64 = 1 << 14
}

private func buildCapabilities(for feature: MetallumInterfaceFeature) -> UInt64 {
    switch feature {
    case .core:
        return MetallumBuildCapability.core
            | MetallumBuildCapability.raster
            | MetallumBuildCapability.compute
            | MetallumBuildCapability.renderStatePacket
            | MetallumBuildCapability.renderCommandPacket
            | MetallumBuildCapability.computeCommandPacket
            | MetallumBuildCapability.terrainIcb
            | MetallumBuildCapability.renderArgumentBindings
    case .metalFX:
        #if canImport(MetalFX)
        return MetallumBuildCapability.metalFXSpatial
            | MetallumBuildCapability.metalFXTemporal
            | MetallumBuildCapability.frameGeneration
            | MetallumBuildCapability.motionV2
            | MetallumBuildCapability.cutoutReactive
            | MetallumBuildCapability.handOverlay
            | MetallumBuildCapability.presentationTimeline
        #else
        return 0
        #endif
    case .renderStatePacket:
        return MetallumBuildCapability.renderStatePacket
    case .renderCommandPacket:
        return MetallumBuildCapability.renderCommandPacket
    case .computeCommandPacket:
        return MetallumBuildCapability.computeCommandPacket
    case .terrainIcb:
        return MetallumBuildCapability.terrainIcb
    case .renderArgumentBindings:
        return MetallumBuildCapability.renderArgumentBindings
    }
}

@_cdecl("metallum_core_device_capabilities")
public func metallum_core_device_capabilities(_ device: MTLDevice) -> UInt64 {
    var bits = MetallumBuildCapability.core
        | MetallumBuildCapability.raster
        | MetallumBuildCapability.compute
        | MetallumBuildCapability.renderStatePacket
        | MetallumBuildCapability.renderCommandPacket
        | MetallumBuildCapability.computeCommandPacket
        | MetallumBuildCapability.terrainIcb
        | MetallumBuildCapability.renderArgumentBindings
    if metallum_metalfx_supports_spatial(device) != 0 {
        bits |= MetallumBuildCapability.metalFXSpatial
    }
    if metallum_metalfx_supports_temporal(device) != 0 {
        bits |= MetallumBuildCapability.metalFXTemporal
    }
    if metallum_metalfx_supports_frame_generation(device) != 0 {
        bits |= MetallumBuildCapability.frameGeneration
            | MetallumBuildCapability.presentationTimeline
    }
    if metallum_metalfx_supports_motion_v2(device) != 0 {
        bits |= MetallumBuildCapability.motionV2
    }
    if metallum_metalfx_supports_cutout_reactive(device) != 0 {
        bits |= MetallumBuildCapability.cutoutReactive
    }
    if metallum_metalfx_supports_hand_overlay(device) != 0 {
        bits |= MetallumBuildCapability.handOverlay
    }
    return bits
}

@_cdecl("metallum_core_build_capabilities")
public func metallum_core_build_capabilities(_ featureId: Int32) -> UInt64 {
    guard let feature = MetallumInterfaceFeature(rawValue: featureId) else { return 0 }
    return buildCapabilities(for: feature)
}

private typealias RawFunction = UnsafeRawPointer

private func functionPointer<T>(_ function: T) -> RawFunction {
    unsafeBitCast(function, to: RawFunction.self)
}

private func entries(for feature: MetallumInterfaceFeature, version: UInt32) -> [RawFunction] {
    switch feature {
    case .core:
        return [
            functionPointer(metallum_core_build_capabilities as @convention(c) (Int32) -> UInt64),
            functionPointer(metallum_core_device_capabilities as @convention(c) (MTLDevice) -> UInt64)
        ]
    case .metalFX:
        return [
            functionPointer(metallum_metalfx_supports_spatial as @convention(c) (MTLDevice) -> Int32),
            functionPointer(metallum_metalfx_supports_temporal as @convention(c) (MTLDevice) -> Int32),
            functionPointer(metallum_metalfx_supports_frame_generation as @convention(c) (MTLDevice) -> Int32),
            functionPointer(metallum_metalfx_supports_motion_v2 as @convention(c) (MTLDevice) -> Int32),
            functionPointer(metallum_metalfx_supports_cutout_reactive as @convention(c) (MTLDevice) -> Int32),
            functionPointer(metallum_metalfx_supports_hand_overlay as @convention(c) (MTLDevice) -> Int32)
        ]
    case .renderStatePacket:
        return [
            functionPointer(
                metallum_render_state_packet_apply_v1
                    as @convention(c) (UnsafeMutableRawPointer, UnsafeRawPointer?, Int64) -> Int32
            )
        ]
    case .renderCommandPacket:
        return [
            functionPointer(
                metallum_render_command_packet_apply_v1
                    as @convention(c) (UnsafeMutableRawPointer, UnsafeRawPointer?, Int64) -> Int32
            )
        ]
    case .computeCommandPacket:
        return [
            functionPointer(
                metallum_compute_command_packet_apply_v1
                    as @convention(c) (UnsafeMutableRawPointer, UnsafeRawPointer?, Int64) -> Int32
            )
        ]
    case .terrainIcb:
        return [
            functionPointer(
                metallum_terrain_icb_encode_indexed_v1
                    as @convention(c) (
                        UnsafeMutableRawPointer,
                        UInt64,
                        UInt64,
                        MTLBuffer,
                        UnsafePointer<Int64>?,
                        UnsafePointer<Int32>?,
                        UnsafePointer<Int32>?,
                        Int32,
                        Int32,
                        Int32
                    ) -> Int32
            ),
            functionPointer(
                metallum_terrain_icb_stats_v1
                    as @convention(c) (
                        UnsafeMutablePointer<UInt64>?,
                        UnsafeMutablePointer<UInt64>?,
                        UnsafeMutablePointer<UInt64>?,
                        UnsafeMutablePointer<UInt64>?
                    ) -> Int32
            ),
            functionPointer(
                metallum_terrain_icb_submission_budget_v1
                    as @convention(c) () -> Int32
            )
        ]
    case .renderArgumentBindings:
        return [
            functionPointer(
                metallum_render_argument_layout_create_v1
                    as @convention(c) (MTLFunction?, MTLFunction?, Int32, Int32) -> UnsafeMutableRawPointer?
            ),
            functionPointer(
                metallum_render_argument_layout_sizes_v1
                    as @convention(c) (UnsafeMutableRawPointer) -> UInt64
            ),
            functionPointer(
                metallum_render_argument_packet_apply_v1
                    as @convention(c) (
                        UnsafeMutableRawPointer,
                        UnsafeMutableRawPointer,
                        MTLBuffer?,
                        UInt64,
                        MTLBuffer?,
                        UInt64,
                        UnsafeRawPointer?,
                        Int64
                    ) -> Int32
            )
        ]
    }
}

private func interfaceTable(feature: MetallumInterfaceFeature, version: UInt32) -> UnsafeMutableRawPointer {
    let key = (UInt64(UInt32(bitPattern: feature.rawValue)) << 32) | UInt64(version)
    interfaceLock.lock()
    defer { interfaceLock.unlock() }
    if let existing = interfaceTables[key] { return existing }

    let functions = entries(for: feature, version: version)
    let stride = MemoryLayout<RawFunction>.stride
    let byteCount = Int(interfaceHeaderSize) + functions.count * stride
    let table = UnsafeMutableRawPointer.allocate(byteCount: byteCount, alignment: 8)
    table.initializeMemory(as: UInt8.self, repeating: 0, count: byteCount)
    table.storeBytes(of: interfaceHeaderSize, toByteOffset: 0, as: UInt32.self)
    table.storeBytes(of: UInt32(byteCount), toByteOffset: 4, as: UInt32.self)
    table.storeBytes(of: version, toByteOffset: 8, as: UInt32.self)
    table.storeBytes(of: feature.rawValue, toByteOffset: 12, as: Int32.self)
    table.storeBytes(of: UInt32(functions.count), toByteOffset: 16, as: UInt32.self)
    table.storeBytes(of: UInt32(0), toByteOffset: 20, as: UInt32.self)
    table.storeBytes(of: buildCapabilities(for: feature), toByteOffset: 24, as: UInt64.self)
    for (index, function) in functions.enumerated() {
        table.storeBytes(
            of: function,
            toByteOffset: Int(interfaceHeaderSize) + index * stride,
            as: RawFunction.self
        )
    }
    interfaceTables[key] = table
    return table
}

@_cdecl("metallum_get_interface")
public func metallum_get_interface(
    _ featureId: Int32,
    _ minVersion: UInt32,
    _ outFunctionTable: UnsafeMutablePointer<UnsafeRawPointer?>?
) -> Int32 {
    guard let feature = MetallumInterfaceFeature(rawValue: featureId), let outFunctionTable else {
        return MetallumInterfaceStatus.unknownFeature.rawValue
    }
    let available = feature.currentVersion
    guard minVersion <= available else {
        return MetallumInterfaceStatus.versionTooNew.rawValue
    }
    outFunctionTable.pointee = UnsafeRawPointer(interfaceTable(feature: feature, version: available))
    return MetallumInterfaceStatus.ok.rawValue
}
