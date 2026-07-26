import Foundation
import Metal

// MARK: - Versioned native interface
//
// The Java side reaches the dylib through per-symbol FFM downcalls, which makes
// the jar and the dylib a matched pair: a jar that looks up a symbol an older
// dylib does not export fails at bridge initialisation, and there is no way to
// ask a dylib what it actually implements before calling into it.
//
// `metallum_get_interface` is that question. It hands back a table whose header
// states its own size, the interface version for one feature, and the set of
// capabilities this dylib was built with. A jar can then negotiate: use the
// feature at the version both sides understand, or degrade cleanly.
//
// ABI rules, which exist so a version number stays meaningful:
//
//   1. The entry order of a table is frozen once released. Append only.
//   2. Appending entries bumps that feature's interface version.
//   3. Changing an existing entry's signature or meaning is a new feature id,
//      never a version bump.
//   4. The header only grows. Readers must use `headerSize` to find the first
//      entry rather than assuming 32.
//
// Header layout, little-endian, total 32 bytes today:
//
//   offset  0  UInt32  headerSize          bytes before the first entry
//   offset  4  UInt32  byteCount           total table size
//   offset  8  UInt32  abiVersion          interface version of this feature
//   offset 12  Int32   featureId
//   offset 16  UInt32  entryCount
//   offset 20  UInt32  reserved            zero
//   offset 24  UInt64  buildCapabilities   what this dylib implements
//
// Tables are immutable process-lifetime data and never retain a Metal object.
// Device-dependent support is a separate question answered by
// `metallum_core_device_capabilities`, because the answer differs per device
// and a process-level table must not pretend otherwise.

private let interfaceHeaderSize: UInt32 = 32
private let interfaceLock = NSLock()
private var interfaceTables: [UInt64: UnsafeMutableRawPointer] = [:]

enum MetallumInterfaceFeature: Int32 {
    case core = 1
    case metalFX = 2

    /// Highest interface version this dylib provides for the feature.
    var currentVersion: UInt32 {
        switch self {
        case .core: return 1
        case .metalFX: return 1
        }
    }
}

enum MetallumInterfaceStatus: Int32 {
    case ok = 0
    case unknownFeature = 1
    case versionTooNew = 2
}

/// What this dylib implements, independent of any device.
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
}

private func buildCapabilities(for feature: MetallumInterfaceFeature) -> UInt64 {
    switch feature {
    case .core:
        return MetallumBuildCapability.core
            | MetallumBuildCapability.raster
            | MetallumBuildCapability.compute
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
    }
}

/// Everything this device actually supports, by asking the same probes the
/// renderer asks before it enables a stage.
@_cdecl("metallum_core_device_capabilities")
public func metallum_core_device_capabilities(_ device: MTLDevice) -> UInt64 {
    var bits = MetallumBuildCapability.core
        | MetallumBuildCapability.raster
        | MetallumBuildCapability.compute
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

/// Build-time capability bits for one feature, callable without a device.
@_cdecl("metallum_core_build_capabilities")
public func metallum_core_build_capabilities(_ featureId: Int32) -> UInt64 {
    guard let feature = MetallumInterfaceFeature(rawValue: featureId) else { return 0 }
    return buildCapabilities(for: feature)
}

private typealias RawFunction = UnsafeRawPointer

private func functionPointer<T>(_ function: T) -> RawFunction {
    unsafeBitCast(function, to: RawFunction.self)
}

/// Frozen entry order. Append only, and bump the feature's version when you do.
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

/// Negotiates one feature's interface.
///
/// - Parameters:
///   - featureId: a `MetallumInterfaceFeature` raw value.
///   - minVersion: the lowest version the caller can work with.
///   - outFunctionTable: receives the table pointer on success.
/// - Returns: a `MetallumInterfaceStatus` raw value. On failure the out
///   parameter is left untouched, so a caller that ignores the status still
///   cannot read a partially written table.
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
