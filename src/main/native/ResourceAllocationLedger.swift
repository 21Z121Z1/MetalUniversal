import Foundation
import Metal

/// Bounded diagnostic ownership ledger for module-created Metal resources.
///
/// This reports the live allocation footprint of resources owned by the
/// Metallum module. It deliberately excludes residency, device-global
/// allocation, pipelines, drawables, and opaque MetalFX allocations.
final class ResourceAllocationLedger {
    static let maxRows = 65_536
    static let metadataWidth = 8
    static let rowWidth = 8

    /// Read once from the process environment. There is no runtime enable API;
    /// this keeps the production path to one predictable branch.
    static let enabledAtStartup = ProcessInfo.processInfo.environment[
        "METALLUM_RESOURCE_ALLOCATION_TRACE"
    ] == "1"

    static let shared = ResourceAllocationLedger()

    enum Kind: Int64 {
        case buffer = 0
        case texture = 1
        case indirectCommandBuffer = 2
    }

    private final class WeakEntry {
        weak var object: AnyObject?
        let id: Int64
        let kind: Kind

        init(object: AnyObject, id: Int64, kind: Kind) {
            self.object = object
            self.id = id
            self.kind = kind
        }
    }

    private let lock = NSLock()
    private var entries: [ObjectIdentifier: WeakEntry] = [:]
    private var nextId: Int64 = 1
    private var droppedRows: Int64 = 0
    private var invalidEvents: Int64 = 0
    private var createdResources: Int64 = 0

    private init() {}

    @inline(__always)
    @discardableResult
    private func saturatingIncrement(_ value: inout Int64) -> Bool {
        if value < Int64.max {
            value += 1
            return true
        }
        return false
    }

    @inline(__always)
    private func saturatingAdd(_ value: inout Int64, _ amount: Int64) {
        guard amount >= 0 else {
            invalidEvents = invalidEvents == Int64.max ? Int64.max : invalidEvents + 1
            return
        }
        if value > Int64.max - amount {
            value = Int64.max
            invalidEvents = Int64.max
        } else {
            value += amount
        }
    }

    @inline(__always)
    private func int64(_ value: UInt) -> Int64? {
        value <= UInt(Int64.max) ? Int64(value) : nil
    }

    @inline(__always)
    private func int64(_ value: Int) -> Int64? {
        value >= 0 ? Int64(value) : nil
    }

    private func pruneDeadLocked() {
        entries = entries.filter { $0.value.object != nil }
    }

    private func markInvalidLocked() {
        saturatingIncrement(&invalidEvents)
    }

    /// Registers a newly created root resource. Views and heap-backed
    /// resources are rejected so this ledger cannot silently double count
    /// shared storage or aliasing.
    func register(_ resource: any MTLResource, kind: Kind) {
        guard Self.enabledAtStartup else { return }

        lock.lock()
        defer { lock.unlock() }

        let object = resource as AnyObject
        let identity = ObjectIdentifier(object)
        if let existing = entries[identity] {
            if existing.object != nil { return }
            // A dead weak entry at a reused address is a different allocation.
            entries.removeValue(forKey: identity)
        }

        if resource.heap != nil {
            markInvalidLocked()
            return
        }
        if let texture = resource as? MTLTexture,
           texture.parent != nil || texture.buffer != nil {
            markInvalidLocked()
            return
        }

        if !saturatingIncrement(&createdResources) { invalidEvents = Int64.max }
        if entries.count >= Self.maxRows { pruneDeadLocked() }
        guard entries.count < Self.maxRows else {
            if !saturatingIncrement(&droppedRows) { invalidEvents = Int64.max }
            return
        }
        guard nextId > 0 else {
            markInvalidLocked()
            return
        }
        entries[identity] = WeakEntry(object: object, id: nextId, kind: kind)
        if nextId < Int64.max {
            nextId += 1
        } else {
            // The current row is still valid, but future identities cannot be
            // represented without violating the positive-id contract.
            invalidEvents = Int64.max
        }
    }

    private struct SnapshotRow {
        let id: Int64
        let kind: Int64
        let allocatedBytes: Int64
        let storageMode: Int64
        let memoryless: Int64
    }

    private func snapshotLocked() -> ([SnapshotRow], Int64) {
        pruneDeadLocked()
        var rows: [SnapshotRow] = []
        rows.reserveCapacity(entries.count)
        var total: Int64 = 0

        for entry in entries.values.sorted(by: { $0.id < $1.id }) {
            // Weak lifetime can end after pruning, independently of our lock.
            guard let object = entry.object else { continue }
            guard let resource = object as? any MTLResource,
                  let allocatedBytes = int64(resource.allocatedSize),
                  let storageMode = int64(resource.storageMode.rawValue) else {
                markInvalidLocked()
                continue
            }
            let memoryless: Int64
            if let texture = resource as? MTLTexture {
                let isMemoryless = texture.storageMode == .memoryless
                memoryless = isMemoryless ? 1 : 0
                if isMemoryless && allocatedBytes != 0 {
                    // Keep the raw allocation observation, but make the
                    // snapshot diagnostically incomplete: memoryless
                    // resources are expected to report no persistent backing.
                    markInvalidLocked()
                }
            } else {
                memoryless = 0
            }
            rows.append(SnapshotRow(
                id: entry.id,
                kind: entry.kind.rawValue,
                allocatedBytes: allocatedBytes,
                storageMode: storageMode,
                memoryless: memoryless
            ))
            saturatingAdd(&total, allocatedBytes)
        }
        return (rows, total)
    }

    /// Copies one locked snapshot. A zero-capacity call with a null rows
    /// pointer is a query-only form that returns the current row count.
    func copy(
        rows output: UnsafeMutablePointer<Int64>?,
        capacityRows: Int32,
        metadata outputMetadata: UnsafeMutablePointer<Int64>?,
        metadataCapacityWords: Int32
    ) -> Int32 {
        guard capacityRows >= 0,
              capacityRows <= Int32(Self.maxRows),
              metadataCapacityWords >= Int32(Self.metadataWidth),
              let outputMetadata else {
            return -1
        }
        let queryOnly = output == nil && capacityRows == 0
        if capacityRows == 0 {
            guard queryOnly else { return -1 }
        } else {
            guard output != nil else { return -1 }
        }

        guard Self.enabledAtStartup else {
            for index in 0..<Self.metadataWidth {
                outputMetadata[index] = 0
            }
            outputMetadata[0] = 1
            outputMetadata[1] = 0
            outputMetadata[2] = Int64(Self.maxRows)
            return 0
        }

        lock.lock()
        let (snapshot, total) = snapshotLocked()
        guard queryOnly || snapshot.count <= Int(capacityRows) else {
            lock.unlock()
            return -1
        }

        var metadata = Array(repeating: Int64(0), count: Self.metadataWidth)
        metadata[0] = 1
        metadata[1] = 1
        metadata[2] = Int64(Self.maxRows)
        metadata[3] = droppedRows
        metadata[4] = invalidEvents
        metadata[5] = createdResources
        metadata[6] = total
        metadata[7] = Int64(snapshot.count)
        for index in 0..<metadata.count {
            outputMetadata[index] = metadata[index]
        }
        if let output {
            for (rowIndex, row) in snapshot.enumerated() {
                let base = rowIndex * Self.rowWidth
                output[base + 0] = row.id
                output[base + 1] = row.kind
                output[base + 2] = row.allocatedBytes
                output[base + 3] = row.storageMode
                output[base + 4] = row.memoryless
                output[base + 5] = 0
                output[base + 6] = 0
                output[base + 7] = 0
            }
        }
        lock.unlock()
        return Int32(snapshot.count)
    }
}

extension MTLDevice {
    @inline(__always)
    func makeTrackedBuffer(length: Int, options: MTLResourceOptions) -> MTLBuffer? {
        guard let resource = makeBuffer(length: length, options: options) else { return nil }
        if ResourceAllocationLedger.enabledAtStartup {
            ResourceAllocationLedger.shared.register(resource, kind: .buffer)
        }
        return resource
    }

    @inline(__always)
    func makeTrackedBuffer(
        bytes: UnsafeRawPointer,
        length: Int,
        options: MTLResourceOptions
    ) -> MTLBuffer? {
        guard let resource = makeBuffer(bytes: bytes, length: length, options: options) else {
            return nil
        }
        if ResourceAllocationLedger.enabledAtStartup {
            ResourceAllocationLedger.shared.register(resource, kind: .buffer)
        }
        return resource
    }

    @inline(__always)
    func makeTrackedTexture(descriptor: MTLTextureDescriptor) -> MTLTexture? {
        guard let resource = makeTexture(descriptor: descriptor) else { return nil }
        if ResourceAllocationLedger.enabledAtStartup {
            ResourceAllocationLedger.shared.register(resource, kind: .texture)
        }
        return resource
    }

    @inline(__always)
    func makeTrackedIndirectCommandBuffer(
        descriptor: MTLIndirectCommandBufferDescriptor,
        maxCommandCount: Int,
        options: MTLResourceOptions
    ) -> MTLIndirectCommandBuffer? {
        guard let resource = makeIndirectCommandBuffer(
            descriptor: descriptor,
            maxCommandCount: maxCommandCount,
            options: options
        ) else { return nil }
        if ResourceAllocationLedger.enabledAtStartup {
            ResourceAllocationLedger.shared.register(resource, kind: .indirectCommandBuffer)
        }
        return resource
    }
}

@_cdecl("metallum_resource_allocations_copy")
public func metallum_resource_allocations_copy(
    _ output: UnsafeMutablePointer<Int64>?,
    _ capacityRows: Int32,
    _ metadata: UnsafeMutablePointer<Int64>?,
    _ metadataCapacityWords: Int32
) -> Int32 {
    ResourceAllocationLedger.shared.copy(
        rows: output,
        capacityRows: capacityRows,
        metadata: metadata,
        metadataCapacityWords: metadataCapacityWords
    )
}
