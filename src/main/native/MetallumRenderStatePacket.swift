import Foundation
import Metal

// MARK: - Versioned render-state packet ABI
//
// Java writes one fixed-width packet into a persistently allocated off-heap
// segment and submits it immediately before a draw. The decoder validates the
// whole packet before mutating the encoder, so Java can replay the entries
// through the legacy per-symbol bridge if negotiation or validation fails.
//
// Header (16 bytes, little-endian):
//   0  UInt32 magic     = 'MRSP'
//   4  UInt32 version   = 1
//   8  UInt32 byteCount
//  12  UInt32 entryCount
//
// Entry (48 bytes):
//   0  UInt32 opcode
//   4  UInt32 stageMask (vertex=1, fragment=2)
//   8  UInt64 index
//  16  UInt64 a
//  24  UInt64 b
//  32  UInt64 c
//  40  UInt64 d

private let renderStatePacketMagic: UInt32 = 0x4D525350
private let renderStatePacketVersion: UInt32 = 1
private let renderStatePacketHeaderSize = 16
private let renderStatePacketEntrySize = 48
private let renderStageVertex: UInt32 = 1
private let renderStageFragment: UInt32 = 2
private let renderStageAll: UInt32 = renderStageVertex | renderStageFragment

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

private func packetLoad<T>(
    _ pointer: UnsafeRawPointer,
    _ offset: Int,
    as type: T.Type
) -> T {
    pointer.loadUnaligned(fromByteOffset: offset, as: type)
}

private func packetObject<T: AnyObject>(_ address: UInt64, as type: T.Type) -> T? {
    guard address != 0, let pointer = UnsafeMutableRawPointer(bitPattern: UInt(address)) else {
        return nil
    }
    return Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue() as? T
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

private func validRenderStageMask(_ mask: UInt32) -> Bool {
    mask != 0 && (mask & ~renderStageAll) == 0
}

private func validateRenderStateEntry(_ entry: RenderStatePacketEntry) -> Bool {
    guard Int(exactly: entry.index) != nil else { return false }
    switch entry.opcode {
    case .pipeline:
        return packetObject(entry.a, as: MTLRenderPipelineState.self) != nil
    case .depthStencil:
        return packetObject(entry.a, as: MTLDepthStencilState.self) != nil
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
            && (entry.a == 0 || packetObject(entry.a, as: MTLBuffer.self) != nil)
    case .bufferOffset:
        return validRenderStageMask(entry.stageMask)
    case .texture:
        return validRenderStageMask(entry.stageMask)
            && (entry.a == 0 || packetObject(entry.a, as: MTLTexture.self) != nil)
    case .textureAndSampler:
        return validRenderStageMask(entry.stageMask)
            && packetObject(entry.a, as: MTLTexture.self) != nil
            && packetObject(entry.b, as: MTLSamplerState.self) != nil
    }
}

private func applyRenderBuffer(
    _ encoder: MTLRenderCommandEncoder,
    _ buffer: MTLBuffer?,
    _ offset: Int,
    _ index: Int,
    _ stageMask: UInt32
) {
    if (stageMask & renderStageVertex) != 0 {
        encoder.setVertexBuffer(buffer, offset: offset, index: index)
    }
    if (stageMask & renderStageFragment) != 0 {
        encoder.setFragmentBuffer(buffer, offset: offset, index: index)
    }
}

private func applyRenderBufferOffset(
    _ encoder: MTLRenderCommandEncoder,
    _ offset: Int,
    _ index: Int,
    _ stageMask: UInt32
) {
    if (stageMask & renderStageVertex) != 0 {
        encoder.setVertexBufferOffset(offset, index: index)
    }
    if (stageMask & renderStageFragment) != 0 {
        encoder.setFragmentBufferOffset(offset, index: index)
    }
}

private func applyRenderTexture(
    _ encoder: MTLRenderCommandEncoder,
    _ texture: MTLTexture?,
    _ index: Int,
    _ stageMask: UInt32
) {
    if (stageMask & renderStageVertex) != 0 {
        encoder.setVertexTexture(texture, index: index)
    }
    if (stageMask & renderStageFragment) != 0 {
        encoder.setFragmentTexture(texture, index: index)
    }
}

private func applyRenderSampler(
    _ encoder: MTLRenderCommandEncoder,
    _ sampler: MTLSamplerState?,
    _ index: Int,
    _ stageMask: UInt32
) {
    if (stageMask & renderStageVertex) != 0 {
        encoder.setVertexSamplerState(sampler, index: index)
    }
    if (stageMask & renderStageFragment) != 0 {
        encoder.setFragmentSamplerState(sampler, index: index)
    }
}

private func applyRenderStateEntry(
    _ encoder: MTLRenderCommandEncoder,
    _ entry: RenderStatePacketEntry
) {
    let index = Int(entry.index)
    switch entry.opcode {
    case .pipeline:
        encoder.setRenderPipelineState(
            packetObject(entry.a, as: MTLRenderPipelineState.self)!
        )
    case .depthStencil:
        encoder.setDepthStencilState(
            packetObject(entry.a, as: MTLDepthStencilState.self)
        )
    case .depthBias:
        encoder.setDepthBias(
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.a)),
            slopeScale: Float(bitPattern: UInt32(truncatingIfNeeded: entry.b)),
            clamp: Float(bitPattern: UInt32(truncatingIfNeeded: entry.c))
        )
    case .winding:
        encoder.setFrontFacing(MTLWinding(rawValue: UInt(entry.a))!)
    case .cullMode:
        encoder.setCullMode(MTLCullMode(rawValue: UInt(entry.a))!)
    case .fillMode:
        encoder.setTriangleFillMode(MTLTriangleFillMode(rawValue: UInt(entry.a))!)
    case .buffer:
        applyRenderBuffer(
            encoder,
            packetObject(entry.a, as: MTLBuffer.self),
            Int(bitPattern: UInt(entry.b)),
            index,
            entry.stageMask
        )
    case .bufferOffset:
        applyRenderBufferOffset(
            encoder,
            Int(bitPattern: UInt(entry.a)),
            index,
            entry.stageMask
        )
    case .texture:
        applyRenderTexture(
            encoder,
            packetObject(entry.a, as: MTLTexture.self),
            index,
            entry.stageMask
        )
    case .textureAndSampler:
        applyRenderTexture(
            encoder,
            packetObject(entry.a, as: MTLTexture.self),
            index,
            entry.stageMask
        )
        applyRenderSampler(
            encoder,
            packetObject(entry.b, as: MTLSamplerState.self),
            index,
            entry.stageMask
        )
    case .scissor:
        encoder.setScissorRect(MTLScissorRect(
            x: Int(entry.index),
            y: Int(entry.a),
            width: Int(entry.b),
            height: Int(entry.c)
        ))
    }
}

/// Returns the number of applied entries. Negative values indicate that no
/// state was applied and Java may safely replay the packet through legacy calls.
@_cdecl("metallum_render_state_packet_apply_v1")
public func metallum_render_state_packet_apply_v1(
    _ encoder: MTLRenderCommandEncoder,
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

    // Validate the complete packet first. This makes failure atomic from the
    // Java caller's perspective and permits exact legacy replay.
    for index in 0..<entryCount {
        guard let entry = decodeRenderStateEntry(packet, index),
              validateRenderStateEntry(entry) else {
            return -5
        }
    }
    for index in 0..<entryCount {
        applyRenderStateEntry(encoder, decodeRenderStateEntry(packet, index)!)
    }
    return Int32(entryCount)
}
