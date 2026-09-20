import Foundation
import Metal

struct EncoderCountIdentity {
    let windowId: Int64
    let frameId: Int64
    let submitIndex: Int64
    let backend: Int64
}

// Bounded, identity-based encoder accounting used by the validation harness.
// This intentionally does not use timestamps or completion callbacks: a row is
// made complete by the command-buffer submit path after all encoders have been
// sealed.  The ledger is inactive by default, and all mutating operations take
// the same short lock only while the diagnostic gate is enabled.
final class EncoderCountLedger {
    static let shared = EncoderCountLedger()

    static let rowWidth = 13
    static let metadataWidth = 8
    static let maxRows = 65_536

    private struct RowKey: Hashable {
        let windowId: Int64
        let frameId: Int64
        let submitIndex: Int64
        let backend: Int64
    }

    private struct SubmissionKey: Hashable {
        let windowId: Int64
        let submitIndex: Int64
    }

    private struct Row {
        let key: RowKey
        var attempted: Int64 = 0
        var created: Int64 = 0
        var ended: Int64 = 0
        var renderCreated: Int64 = 0
        var blitCreated: Int64 = 0
        var computeCreated: Int64 = 0
        var createFailures: Int64 = 0
        var unsupportedEncodes: Int64 = 0
        var invalidEvents: Int64 = 0
    }

    private struct Binding {
        let key: ObjectIdentifier
        let rowKey: RowKey
        var sealed = false
        var activeEncoderCount: Int64 = 0
    }

    private enum RowInsertResult {
        case inserted
        case full
        case duplicateSubmission
    }

    private let lock = NSLock()
    private var capacity = 0
    // The gate is intentionally checked before taking the diagnostic lock on
    // the production path.  Reset is coordinated by the measurement owner,
    // before it binds new command buffers; all stateful work remains locked.
    private var enabledFastPath = false
    private var rows: [RowKey: Row] = [:]
    private var submissionKeys: Set<SubmissionKey> = []
    private var bindings: [ObjectIdentifier: Binding] = [:]
    private var encoderBindings: [ObjectIdentifier: ObjectIdentifier] = [:]
    private var droppedRows: Int64 = 0
    private var invalidEvents: Int64 = 0

    private init() {}

    @inline(__always)
    private func saturatingIncrement(_ value: inout Int64) {
        if value < Int64.max {
            value += 1
        } else {
            // Saturation itself is diagnostic evidence: a consumer must reject
            // this ledger as incomplete instead of treating max as exact.
            invalidEvents = Int64.max
        }
    }

    @inline(__always)
    private func invalidLocked(_ rowKey: RowKey? = nil) {
        saturatingIncrement(&invalidEvents)
        if let rowKey, var row = rows[rowKey] {
            saturatingIncrement(&row.invalidEvents)
            rows[rowKey] = row
        }
    }

    @inline(__always)
    private func rowLocked(_ key: RowKey) -> RowInsertResult {
        // submitIndex is the command-buffer submission identity within a
        // measurement window.  A different physical buffer, frame, or
        // backend must not silently merge into the same submission row.
        let submissionKey = SubmissionKey(windowId: key.windowId, submitIndex: key.submitIndex)
        if submissionKeys.contains(submissionKey) {
            invalidLocked()
            return .duplicateSubmission
        }
        guard rows.count < capacity else {
            saturatingIncrement(&droppedRows)
            invalidLocked()
            return .full
        }
        rows[key] = Row(key: key)
        submissionKeys.insert(submissionKey)
        return .inserted
    }

    func reset(_ requestedCapacity: Int32) -> Bool {
        guard requestedCapacity >= 0,
              Int(requestedCapacity) <= Self.maxRows else { return false }
        lock.lock()
        capacity = Int(requestedCapacity)
        enabledFastPath = requestedCapacity != 0
        rows.removeAll(keepingCapacity: requestedCapacity > 0)
        submissionKeys.removeAll(keepingCapacity: requestedCapacity > 0)
        bindings.removeAll(keepingCapacity: requestedCapacity > 0)
        encoderBindings.removeAll(keepingCapacity: requestedCapacity > 0)
        droppedRows = 0
        invalidEvents = 0
        lock.unlock()
        return true
    }

    func enabled() -> Bool {
        lock.lock()
        let result = capacity != 0
        lock.unlock()
        return result
    }

    /// Returns the binding captured for a command buffer without exposing the
    /// ledger's private row or binding types. Attachment diagnostics use this
    /// identity at factory time; they never read the mutable global frame
    /// context or infer identity from a completion callback.
    func bindingIdentity(for object: AnyObject) -> EncoderCountIdentity? {
        guard enabledFastPath else { return nil }
        lock.lock()
        defer { lock.unlock() }
        guard let binding = bindings[ObjectIdentifier(object)], !binding.sealed else { return nil }
        return EncoderCountIdentity(
            windowId: binding.rowKey.windowId,
            frameId: binding.rowKey.frameId,
            submitIndex: binding.rowKey.submitIndex,
            backend: binding.rowKey.backend
        )
    }

    func bind(object: AnyObject, windowId: Int64, frameId: Int64, submitIndex: Int64, backend: Int64) -> Bool {
        guard enabledFastPath else { return true }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return true }
        guard windowId > 0, frameId >= 0, submitIndex >= 0,
              backend == 3 || backend == 4 else {
            invalidLocked()
            return false
        }
        let key = RowKey(windowId: windowId, frameId: frameId, submitIndex: submitIndex, backend: backend)
        let identity = ObjectIdentifier(object)
        if let existing = bindings[identity] {
            // Metal 4 recycles the physical command buffer for a new lease.
            // Rebinding is valid only after the previous lease was sealed and
            // only when the identity tuple actually advances.
            guard existing.sealed, existing.rowKey != key else {
                invalidLocked()
                return false
            }
            bindings.removeValue(forKey: identity)
        }
        // A legal bind beyond the fixed row capacity must not interrupt the
        // renderer.  Leave it untracked and make the bounded snapshot fail
        // closed through droppedRows/invalidEvents.
        switch rowLocked(key) {
        case .inserted:
            bindings[identity] = Binding(key: identity, rowKey: key)
            return true
        case .full:
            // A legal bind beyond the fixed row capacity must not interrupt
            // rendering. It remains untracked and the snapshot is invalid.
            return true
        case .duplicateSubmission:
            return false
        }
    }

    func factoryAttempt(commandBuffer: AnyObject, backend: Int64, kind: Int64) {
        guard enabledFastPath else { return }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return }
        guard kind == 0 || kind == 1 || kind == 2,
              backend == 3 || backend == 4 else {
            invalidLocked()
            return
        }
        let identity = ObjectIdentifier(commandBuffer)
        guard let binding = bindings[identity], !binding.sealed else {
            invalidLocked()
            return
        }
        guard var row = rows[binding.rowKey] else {
            invalidLocked()
            return
        }
        saturatingIncrement(&row.attempted)
        rows[binding.rowKey] = row
    }

    func factoryResult(commandBuffer: AnyObject, encoder: AnyObject?, backend: Int64, kind: Int64) {
        guard enabledFastPath else { return }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return }
        let identity = ObjectIdentifier(commandBuffer)
        guard let binding = bindings[identity], !binding.sealed,
              var row = rows[binding.rowKey] else {
            invalidLocked()
            return
        }
        guard backend == 3 || backend == 4,
              kind == 0 || kind == 1 || kind == 2 else {
            invalidLocked(binding.rowKey)
            return
        }
        guard let encoder else {
            saturatingIncrement(&row.createFailures)
            rows[binding.rowKey] = row
            return
        }
        guard encoderBindings.count < capacity else {
            saturatingIncrement(&row.createFailures)
            saturatingIncrement(&row.unsupportedEncodes)
            rows[binding.rowKey] = row
            invalidLocked(binding.rowKey)
            return
        }
        let encoderIdentity = ObjectIdentifier(encoder)
        guard encoderBindings[encoderIdentity] == nil else {
            invalidLocked(binding.rowKey)
            return
        }
        encoderBindings[encoderIdentity] = identity
        saturatingIncrement(&row.created)
        switch kind {
        case 0: saturatingIncrement(&row.renderCreated)
        case 1: saturatingIncrement(&row.blitCreated)
        default: saturatingIncrement(&row.computeCreated)
        }
        var updatedBinding = binding
        saturatingIncrement(&updatedBinding.activeEncoderCount)
        bindings[identity] = updatedBinding
        rows[binding.rowKey] = row
    }

    func end(encoder: AnyObject) {
        guard enabledFastPath else { return }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return }
        let identity = ObjectIdentifier(encoder)
        guard let commandBufferIdentity = encoderBindings.removeValue(forKey: identity),
              var binding = bindings[commandBufferIdentity],
              var row = rows[binding.rowKey] else {
            invalidLocked()
            return
        }
        guard binding.activeEncoderCount > 0 else {
            invalidLocked(binding.rowKey)
            return
        }
        binding.activeEncoderCount -= 1
        saturatingIncrement(&row.ended)
        bindings[commandBufferIdentity] = binding
        rows[binding.rowKey] = row
    }

    func unsupported(commandBuffer: AnyObject) {
        guard enabledFastPath else { return }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return }
        let identity = ObjectIdentifier(commandBuffer)
        guard let binding = bindings[identity], var row = rows[binding.rowKey] else { return }
        saturatingIncrement(&row.unsupportedEncodes)
        rows[binding.rowKey] = row
        invalidLocked(binding.rowKey)
    }

    func seal(commandBuffer: AnyObject) {
        guard enabledFastPath else { return }
        lock.lock()
        defer { lock.unlock() }
        guard capacity != 0 else { return }
        let identity = ObjectIdentifier(commandBuffer)
        guard var binding = bindings[identity], rows[binding.rowKey] != nil else {
            invalidLocked()
            return
        }
        guard !binding.sealed else {
            invalidLocked(binding.rowKey)
            return
        }
        if binding.activeEncoderCount != 0 {
            invalidLocked(binding.rowKey)
        }
        binding.sealed = true
        bindings[identity] = binding
    }

    func copy(rows output: UnsafeMutablePointer<Int64>?, capacityRows: Int32, metadata: UnsafeMutablePointer<Int64>?) -> Int32 {
        guard capacityRows >= 0 else { return -1 }
        lock.lock()
        defer { lock.unlock() }
        let snapshotRows = Array(rows.values)
        let rowCount = snapshotRows.count
        metadata?[0] = 1
        metadata?[1] = capacity == 0 ? 0 : 1
        metadata?[2] = Int64(capacity)
        metadata?[3] = droppedRows
        metadata?[4] = invalidEvents
        metadata?[5] = Int64(bindings.values.filter { !$0.sealed }.count)
        metadata?[6] = Int64(bindings.values.reduce(into: 0) { $0 += $1.activeEncoderCount })
        metadata?[7] = Int64(rowCount)
        guard let output else { return Int32(rowCount) }
        guard Int(capacityRows) >= rowCount else { return -1 }
        for (index, row) in snapshotRows.enumerated() {
            let offset = index * Self.rowWidth
            output[offset + 0] = row.key.windowId
            output[offset + 1] = row.key.frameId
            output[offset + 2] = row.key.submitIndex
            output[offset + 3] = row.key.backend
            output[offset + 4] = row.attempted
            output[offset + 5] = row.created
            output[offset + 6] = row.ended
            output[offset + 7] = row.renderCreated
            output[offset + 8] = row.blitCreated
            output[offset + 9] = row.computeCreated
            output[offset + 10] = row.createFailures
            output[offset + 11] = row.unsupportedEncodes
            output[offset + 12] = row.invalidEvents
        }
        return Int32(rowCount)
    }
}

@inline(__always)
func encoderCountFactoryAttempt(_ commandBuffer: AnyObject, backend: Int64, kind: Int64) {
    EncoderCountLedger.shared.factoryAttempt(commandBuffer: commandBuffer, backend: backend, kind: kind)
}

@inline(__always)
func encoderCountFactoryResult(_ commandBuffer: AnyObject, _ encoder: AnyObject?, backend: Int64, kind: Int64) {
    EncoderCountLedger.shared.factoryResult(commandBuffer: commandBuffer, encoder: encoder, backend: backend, kind: kind)
}

@inline(__always)
func encoderCountRecordEnd(_ encoder: AnyObject) {
    EncoderCountLedger.shared.end(encoder: encoder)
}

@inline(__always)
func encoderCountUnsupported(_ commandBuffer: AnyObject) {
    AttachmentActionLedger.shared.unsupported(commandBuffer)
    EncoderCountLedger.shared.unsupported(commandBuffer: commandBuffer)
}

@inline(__always)
func encoderCountSeal(_ commandBuffer: AnyObject) {
    EncoderCountLedger.shared.seal(commandBuffer: commandBuffer)
}

@inline(__always)
func encoderCountBindingIdentity(for commandBuffer: AnyObject) -> EncoderCountIdentity? {
    EncoderCountLedger.shared.bindingIdentity(for: commandBuffer)
}

// Keep native encoder construction behind these small wrappers.  They are
// deliberately overloads rather than a closure-based generic so the disabled
// path does not allocate a closure or an accounting token.
@inline(__always)
func encoderCountMakeRender(_ commandBuffer: MTLCommandBuffer, descriptor: MTLRenderPassDescriptor) -> MTLRenderCommandEncoder? {
    let attachmentToken = AttachmentActionLedger.shared.enabledFastPathValue()
            ? AttachmentActionLedger.shared.factoryAttempt(commandBuffer, descriptor)
            : AttachmentActionLedger.FactoryToken(rowIndex: -1, tracked: false)
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 3, kind: 0)
    let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 3, kind: 0)
    AttachmentActionLedger.shared.result(attachmentToken, encoder: encoder as AnyObject?)
    return encoder
}

@inline(__always)
func encoderCountMakeBlit(_ commandBuffer: MTLCommandBuffer, descriptor: MTLBlitPassDescriptor? = nil) -> MTLBlitCommandEncoder? {
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 3, kind: 1)
    let encoder: MTLBlitCommandEncoder?
    if let descriptor {
        encoder = commandBuffer.makeBlitCommandEncoder(descriptor: descriptor)
    } else {
        encoder = commandBuffer.makeBlitCommandEncoder()
    }
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 3, kind: 1)
    return encoder
}

@inline(__always)
func encoderCountMakeCompute(_ commandBuffer: MTLCommandBuffer) -> MTLComputeCommandEncoder? {
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 3, kind: 2)
    let encoder = commandBuffer.makeComputeCommandEncoder()
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 3, kind: 2)
    return encoder
}

// The pilot owns a private validation queue and is deliberately outside the
// measured main-command-buffer ledger.
@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountMakeComputeUntracked(_ commandBuffer: MTL4CommandBuffer) -> MTL4ComputeCommandEncoder? {
    commandBuffer.makeComputeCommandEncoder()
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountMakeRender(_ commandBuffer: MTL4CommandBuffer, descriptor: MTL4RenderPassDescriptor) -> MTL4RenderCommandEncoder? {
    let attachmentToken = AttachmentActionLedger.shared.enabledFastPathValue()
            ? AttachmentActionLedger.shared.factoryAttempt(commandBuffer, descriptor)
            : AttachmentActionLedger.FactoryToken(rowIndex: -1, tracked: false)
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 4, kind: 0)
    let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 4, kind: 0)
    AttachmentActionLedger.shared.result(attachmentToken, encoder: encoder as AnyObject?)
    return encoder
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountMakeRenderUntracked(_ commandBuffer: MTL4CommandBuffer, descriptor: MTL4RenderPassDescriptor) -> MTL4RenderCommandEncoder? {
    commandBuffer.makeRenderCommandEncoder(descriptor: descriptor)
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountMakeCompute(_ commandBuffer: MTL4CommandBuffer) -> MTL4ComputeCommandEncoder? {
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 4, kind: 2)
    let encoder = commandBuffer.makeComputeCommandEncoder()
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 4, kind: 2)
    return encoder
}

// Metal 4's upload ABI is implemented by a compute encoder.  It therefore
// remains a physical compute row even though its Java entry point is named
// makeBlitCommandEncoder.
@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountMakeBlitAsCompute(_ commandBuffer: MTL4CommandBuffer) -> MTL4ComputeCommandEncoder? {
    encoderCountFactoryAttempt(commandBuffer as AnyObject, backend: 4, kind: 2)
    let encoder = commandBuffer.makeComputeCommandEncoder()
    encoderCountFactoryResult(commandBuffer as AnyObject, encoder, backend: 4, kind: 2)
    return encoder
}

@inline(__always)
func encoderCountEnd(_ encoder: MTLCommandEncoder) {
    if AttachmentActionLedger.shared.enabledFastPathValue(),
       let renderEncoder = encoder as? MTLRenderCommandEncoder {
        AttachmentActionLedger.shared.end(renderEncoder)
    }
    encoderCountRecordEnd(encoder)
    encoder.endEncoding()
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountEndUntracked(_ encoder: MTL4ComputeCommandEncoder) {
    encoder.endEncoding()
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountEndUntracked(_ encoder: MTL4RenderCommandEncoder) {
    encoder.endEncoding()
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountEnd(_ encoder: MTL4RenderCommandEncoder) {
    AttachmentActionLedger.shared.end(encoder)
    encoderCountRecordEnd(encoder)
    encoder.endEncoding()
}

@available(macOS 26.0, iOS 26.0, *)
@inline(__always)
func encoderCountEnd(_ encoder: MTL4ComputeCommandEncoder) {
    encoderCountRecordEnd(encoder)
    encoder.endEncoding()
}

@_cdecl("metallum_encoder_counts_reset")
public func metallum_encoder_counts_reset(_ capacityRows: Int32) -> Int32 {
    EncoderCountLedger.shared.reset(capacityRows) ? 1 : 0
}

@_cdecl("metallum_encoder_counts_bind")
public func metallum_encoder_counts_bind(
    _ cbOrLease: UnsafeMutableRawPointer,
    _ windowId: Int64,
    _ frameId: Int64,
    _ submitIndex: Int64
) -> Int32 {
    guard let (object, backend) = encoderCountBindingObject(for: cbOrLease) else { return 0 }
    return EncoderCountLedger.shared.bind(
        object: object,
        windowId: windowId,
        frameId: frameId,
        submitIndex: submitIndex,
        backend: backend
    ) ? 1 : 0
}

@_cdecl("metallum_encoder_counts_copy")
public func metallum_encoder_counts_copy(
    _ rows: UnsafeMutablePointer<Int64>?,
    _ capacityRows: Int32,
    _ metadata: UnsafeMutablePointer<Int64>?
) -> Int32 {
    EncoderCountLedger.shared.copy(rows: rows, capacityRows: capacityRows, metadata: metadata)
}
