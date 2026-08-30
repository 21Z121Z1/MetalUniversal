import Foundation
import Metal

// MARK: - Versioned render-state packet ABI
//
// Java writes one fixed-width packet into a persistently allocated off-heap
// segment and submits it immediately before a draw. Structural header failures
// apply no state. Entry validation is intentionally fused with application: if
// a later entry is invalid, the function returns the number already applied and
// Java replays the complete packet through idempotent legacy setters before
// disabling packet use for that encoder.
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

private func packetObject(_ address: UInt64) -> AnyObject? {
    guard address != 0, let pointer = UnsafeMutableRawPointer(bitPattern: UInt(address)) else {
        return nil
    }
    return Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
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

/// Validate and apply one fixed-width render-state entry without constructing
/// intermediate collections or repeating Objective-C protocol casts. A false
/// return means this entry has not mutated the encoder; the caller reports the
/// already-applied prefix length and Java replays the complete packet.
private func validateAndApplyRenderStateEntry(
    _ encoderPointer: UnsafeMutableRawPointer,
    _ entry: RenderStatePacketEntry
) -> Bool {
    guard Int(exactly: entry.index) != nil else { return false }
    let stageMask = Int32(bitPattern: entry.stageMask)

    switch entry.opcode {
    case .pipeline:
        guard let pipeline = packetObject(entry.a) as? MTLRenderPipelineState else {
            return false
        }
        metallum_MTLRenderCommandEncoder_setRenderPipelineState(encoderPointer, pipeline)

    case .depthStencil:
        guard let depthStencil = packetObject(entry.a) as? MTLDepthStencilState else {
            return false
        }
        metallum_MTLRenderCommandEncoder_setDepthStencilState(encoderPointer, depthStencil)

    case .depthBias:
        metallum_MTLRenderCommandEncoder_setDepthBias(
            encoderPointer,
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.a)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.b)),
            Float(bitPattern: UInt32(truncatingIfNeeded: entry.c))
        )

    case .winding:
        guard let winding = MTLWinding(rawValue: UInt(entry.a)) else { return false }
        metallum_MTLRenderCommandEncoder_setFrontFacingWinding(encoderPointer, winding)

    case .cullMode:
        guard let cullMode = MTLCullMode(rawValue: UInt(entry.a)) else { return false }
        metallum_MTLRenderCommandEncoder_setCullMode(encoderPointer, cullMode)

    case .fillMode:
        guard let fillMode = MTLTriangleFillMode(rawValue: UInt(entry.a)) else { return false }
        metallum_MTLRenderCommandEncoder_setTriangleFillMode(encoderPointer, fillMode)

    case .buffer:
        guard validRenderStageMask(entry.stageMask), Int(exactly: entry.b) != nil else {
            return false
        }
        let buffer: MTLBuffer?
        if entry.a == 0 {
            buffer = nil
        } else {
            guard let typedBuffer = packetObject(entry.a) as? MTLBuffer else { return false }
            buffer = typedBuffer
        }
        metallum_MTLRenderCommandEncoder_setBuffer(
            encoderPointer,
            buffer,
            entry.b,
            entry.index,
            stageMask
        )

    case .bufferOffset:
        guard validRenderStageMask(entry.stageMask), Int(exactly: entry.a) != nil else {
            return false
        }
        metallum_MTLRenderCommandEncoder_setBufferOffset(
            encoderPointer,
            entry.a,
            entry.index,
            stageMask
        )

    case .texture:
        guard validRenderStageMask(entry.stageMask) else { return false }
        let texture: MTLTexture?
        if entry.a == 0 {
            texture = nil
        } else {
            guard let typedTexture = packetObject(entry.a) as? MTLTexture else { return false }
            texture = typedTexture
        }
        metallum_MTLRenderCommandEncoder_setTexture(
            encoderPointer,
            texture,
            entry.index,
            stageMask
        )

    case .textureAndSampler:
        guard validRenderStageMask(entry.stageMask),
              let texture = packetObject(entry.a) as? MTLTexture,
              let sampler = packetObject(entry.b) as? MTLSamplerState else {
            return false
        }
        metallum_MTLRenderCommandEncoder_setTextureAndSampler(
            encoderPointer,
            texture,
            sampler,
            entry.index,
            stageMask
        )

    case .scissor:
        guard Int(exactly: entry.a) != nil,
              Int(exactly: entry.b) != nil,
              Int(exactly: entry.c) != nil else {
            return false
        }
        metallum_MTLRenderCommandEncoder_setScissorRect(
            encoderPointer,
            entry.index,
            entry.a,
            entry.b,
            entry.c
        )
    }
    return true
}

/// Returns the number of applied entries. Negative values are reserved for
/// structural packet failures that apply no state. A nonnegative value smaller
/// than entryCount means a validated prefix was applied before an invalid entry;
/// Java's packet contract replays the complete packet through idempotent legacy
/// setters and disables packet use for that encoder.
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

    // Decode each fixed-width entry exactly once. Java intentionally treats a
    // partial positive return as replay-required, so preserving an all-or-nothing
    // native mutation contract would only duplicate decode/type-check work.
    var appliedCount: Int32 = 0
    for index in 0..<entryCount {
        guard let entry = decodeRenderStateEntry(packet, index),
              validateAndApplyRenderStateEntry(encoderPointer, entry) else {
            return appliedCount
        }
        appliedCount += 1
    }
    return appliedCount
}

// MARK: - Versioned native interface

private let interfaceHeaderSize: UInt32 = 32
private let interfaceLock = NSLock()
private var interfaceTables: [UInt64: UnsafeMutableRawPointer] = [:]

enum MetallumInterfaceFeature: Int32 {
    case core = 1
    case metalFX = 2
    case renderStatePacket = 3

    var currentVersion: UInt32 {
        switch self {
        case .core: return 1
        case .metalFX: return 1
        case .renderStatePacket: return 1
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
}

private func buildCapabilities(for feature: MetallumInterfaceFeature) -> UInt64 {
    switch feature {
    case .core:
        return MetallumBuildCapability.core
            | MetallumBuildCapability.raster
            | MetallumBuildCapability.compute
            | MetallumBuildCapability.renderStatePacket
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
    }
}

@_cdecl("metallum_core_device_capabilities")
public func metallum_core_device_capabilities(_ device: MTLDevice) -> UInt64 {
    var bits = MetallumBuildCapability.core
        | MetallumBuildCapability.raster
        | MetallumBuildCapability.compute
        | MetallumBuildCapability.renderStatePacket
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
