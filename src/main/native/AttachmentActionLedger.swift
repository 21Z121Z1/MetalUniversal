import Foundation
import Metal

/// Bounded raw attachment facts.  This ledger intentionally does not estimate
/// bytes; consumers decide whether a format/storage/resolve tuple is supported
/// and fail closed when it is not.
final class AttachmentActionLedger {
    static let shared = AttachmentActionLedger()
    static let rowWidth = 40
    static let metadataWidth = 8
    static let maxRows = 65_536

    // 0 windowId, 1 frameId, 2 submitIndex, 3 backend, 4 encoderSequence,
    // 5 aspect (-1 marker, 0 color, 1 depth, 2 stencil), 6 slot
    // (marker: descriptor attachment mask, color bits 0...7/depth 8/stencil 9),
    // 7 pixelFormat, 8 width, 9 height, 10 depth, 11 arrayLength,
    // 12 textureType, 13 sampleCount, 14 storageMode, 15 level, 16 slice,
    // 17 depthPlane, 18 renderTargetWidth, 19 renderTargetHeight,
    // 20 renderTargetArrayLength, 21 loadAction, 22 initialStoreAction,
    // 23 finalStoreAction, 24 resolvePixelFormat, 25 resolveWidth,
    // 26 resolveHeight, 27 resolveDepth, 28 resolveArrayLength,
    // 29 resolveTextureType, 30 resolveSampleCount, 31 resolveStorageMode,
    // 32 resolveLevel, 33 resolveSlice, 34 resolveDepthPlane,
    // 35 resolveFilter, 36 storeActionOptions, 37 ended, 38 errorBits,
    // 39 reserved.
    private enum C {
        static let window = 0, frame = 1, submit = 2, backend = 3
        static let sequence = 4, aspect = 5, slot = 6
        static let pixelFormat = 7, width = 8, height = 9, depth = 10
        static let arrayLength = 11, textureType = 12, sampleCount = 13
        static let storageMode = 14, level = 15, slice = 16, depthPlane = 17
        static let targetWidth = 18, targetHeight = 19, targetArrayLength = 20
        static let load = 21, initialStore = 22, finalStore = 23
        static let resolvePixelFormat = 24, resolveWidth = 25
        static let resolveHeight = 26, resolveDepth = 27
        static let resolveArrayLength = 28, resolveTextureType = 29
        static let resolveSampleCount = 30, resolveStorageMode = 31
        static let resolveLevel = 32, resolveSlice = 33
        static let resolveDepthPlane = 34, resolveFilter = 35
        static let storeOptions = 36, ended = 37, errors = 38, reserved = 39
    }

    // Error bits are facts about coverage, not replacement action values.
    private static let createFailed: Int64 = 1 << 0
    private static let unsupported: Int64 = 1 << 1
    private static let unresolvedFinalStore: Int64 = 1 << 2

    private struct Row { var words: [Int64] }
    struct FactoryToken { let rowIndex: Int; let tracked: Bool }

    private let lock = NSLock()
    private var capacity = 0
    private var enabledFastPath = false
    private var nextSequence: Int64 = 1
    private var rows: [Row] = []
    private var encoderRows: [ObjectIdentifier: Int] = [:]
    private var droppedRows: Int64 = 0
    private var invalidEvents: Int64 = 0

    private init() {}

    @inline(__always)
    func enabledFastPathValue() -> Bool { enabledFastPath }

    @inline(__always)
    private func increment(_ value: inout Int64) {
        if value < Int64.max { value += 1 } else { invalidEvents = Int64.max }
    }

    @inline(__always)
    private func add(_ value: inout Int64, _ amount: Int64) {
        guard amount > 0 else { return }
        if value > Int64.max - amount {
            value = Int64.max
            invalidEvents = Int64.max
        } else { value += amount }
    }

    private func invalidLocked() { increment(&invalidEvents) }

    func reset(_ requestedCapacity: Int32) -> Bool {
        guard requestedCapacity >= 0, Int(requestedCapacity) <= Self.maxRows else { return false }
        lock.lock()
        capacity = Int(requestedCapacity)
        enabledFastPath = requestedCapacity != 0
        nextSequence = 1
        rows.removeAll(keepingCapacity: requestedCapacity > 0)
        encoderRows.removeAll(keepingCapacity: requestedCapacity > 0)
        droppedRows = 0
        invalidEvents = 0
        lock.unlock()
        return true
    }

    @inline(__always)
    private func value(_ x: UInt) -> Int64? {
        x <= UInt(Int64.max) ? Int64(x) : nil
    }

    @inline(__always)
    private func value(_ x: Int) -> Int64? {
        x >= 0 && UInt(x) <= UInt(Int64.max) ? Int64(x) : nil
    }

    private func writeTexture(_ texture: MTLTexture?, base: Int, into words: inout [Int64]) -> Bool {
        guard let texture else { return true }
        guard let pixel = value(texture.pixelFormat.rawValue),
              let width = value(texture.width), let height = value(texture.height),
              let depth = value(texture.depth), let array = value(texture.arrayLength),
              let type = value(texture.textureType.rawValue),
              let samples = value(texture.sampleCount),
              let storage = value(texture.storageMode.rawValue) else { return false }
        words[base + 0] = pixel
        words[base + 1] = width
        words[base + 2] = height
        words[base + 3] = depth
        words[base + 4] = array
        words[base + 5] = type
        words[base + 6] = samples
        words[base + 7] = storage
        return true
    }

    private func row(
        identity: EncoderCountIdentity,
        sequence: Int64,
        aspect: Int64,
        slot: Int64,
        targetWidth: Int64,
        targetHeight: Int64,
        targetArrayLength: Int64,
        attachment: MTLRenderPassAttachmentDescriptor?,
        resolveFilter: Int64
    ) -> ([Int64], Bool) {
        var words = Array(repeating: Int64(0), count: Self.rowWidth)
        words[C.window] = identity.windowId; words[C.frame] = identity.frameId
        words[C.submit] = identity.submitIndex; words[C.backend] = identity.backend
        words[C.sequence] = sequence; words[C.aspect] = aspect; words[C.slot] = slot
        words[C.targetWidth] = targetWidth; words[C.targetHeight] = targetHeight
        words[C.targetArrayLength] = targetArrayLength
        guard let attachment else { return (words, true) }
        guard let load = value(attachment.loadAction.rawValue),
              let initialStore = value(attachment.storeAction.rawValue),
              let level = value(attachment.level), let slice = value(attachment.slice),
              let depthPlane = value(attachment.depthPlane),
              let resolveLevel = value(attachment.resolveLevel),
              let resolveSlice = value(attachment.resolveSlice),
              let resolveDepthPlane = value(attachment.resolveDepthPlane),
              let options = value(attachment.storeActionOptions.rawValue),
              writeTexture(attachment.texture, base: C.pixelFormat, into: &words),
              writeTexture(attachment.resolveTexture, base: C.resolvePixelFormat, into: &words)
        else { return (words, false) }
        words[C.level] = level; words[C.slice] = slice; words[C.depthPlane] = depthPlane
        words[C.load] = load; words[C.initialStore] = initialStore
        words[C.finalStore] = initialStore
        words[C.resolveLevel] = resolveLevel; words[C.resolveSlice] = resolveSlice
        words[C.resolveDepthPlane] = resolveDepthPlane
        words[C.resolveFilter] = resolveFilter; words[C.storeOptions] = options
        if attachment.resolveTexture == nil {
            for index in C.resolvePixelFormat...C.resolveFilter { words[index] = 0 }
        }
        let knownLoad = load >= 0 && load <= 2
        let knownStore = initialStore >= 0 && initialStore <= 5
        if !knownLoad || !knownStore { words[C.errors] |= Self.unsupported }
        return (words, true)
    }

    private func descriptorRows(
        identity: EncoderCountIdentity,
        targetWidth: Int64,
        targetHeight: Int64,
        targetArrayLength: Int64,
        colors: MTLRenderPassColorAttachmentDescriptorArray,
        depth: MTLRenderPassDepthAttachmentDescriptor,
        stencil: MTLRenderPassStencilAttachmentDescriptor,
        depthResolveFilter: Int64,
        stencilResolveFilter: Int64
    ) -> (rows: [[Int64]], valid: Bool) {
        let sequence = nextSequence
        let marker = row(identity: identity, sequence: sequence, aspect: -1, slot: 0,
                         targetWidth: targetWidth, targetHeight: targetHeight,
                         targetArrayLength: targetArrayLength, attachment: nil,
                         resolveFilter: 0)
        var result = [marker.0]
        var valid = marker.1
        for index in 0..<8 {
            guard let attachment = colors[index], attachment.texture != nil else { continue }
            result[0][C.slot] |= Int64(1) << index
            let built = row(identity: identity, sequence: sequence, aspect: 0, slot: Int64(index),
                            targetWidth: targetWidth, targetHeight: targetHeight,
                            targetArrayLength: targetArrayLength, attachment: attachment,
                            resolveFilter: 0)
            result.append(built.0); valid = valid && built.1
        }
        if depth.texture != nil {
            result[0][C.slot] |= Int64(1) << 8
            let built = row(identity: identity, sequence: sequence, aspect: 1, slot: 0,
                            targetWidth: targetWidth, targetHeight: targetHeight,
                            targetArrayLength: targetArrayLength, attachment: depth,
                            resolveFilter: depthResolveFilter)
            result.append(built.0); valid = valid && built.1
        }
        if stencil.texture != nil {
            result[0][C.slot] |= Int64(1) << 9
            let built = row(identity: identity, sequence: sequence, aspect: 2, slot: 0,
                            targetWidth: targetWidth, targetHeight: targetHeight,
                            targetArrayLength: targetArrayLength, attachment: stencil,
                            resolveFilter: stencilResolveFilter)
            result.append(built.0); valid = valid && built.1
        }
        return (result, valid)
    }

    private func attempt(
        commandBuffer: AnyObject, backend: Int64,
        targetWidth: Int64, targetHeight: Int64, targetArrayLength: Int64,
        colors: MTLRenderPassColorAttachmentDescriptorArray,
        depth: MTLRenderPassDepthAttachmentDescriptor,
        stencil: MTLRenderPassStencilAttachmentDescriptor,
        depthResolveFilter: Int64, stencilResolveFilter: Int64
    ) -> FactoryToken {
        guard enabledFastPath else { return FactoryToken(rowIndex: -1, tracked: false) }
        lock.lock(); defer { lock.unlock() }
        guard capacity != 0 else { return FactoryToken(rowIndex: -1, tracked: false) }
        guard let identity = encoderCountBindingIdentity(for: commandBuffer), identity.backend == backend else {
            invalidLocked(); return FactoryToken(rowIndex: -1, tracked: false)
        }
        let built = descriptorRows(identity: identity, targetWidth: targetWidth,
                                   targetHeight: targetHeight, targetArrayLength: targetArrayLength,
                                   colors: colors, depth: depth, stencil: stencil,
                                   depthResolveFilter: depthResolveFilter,
                                   stencilResolveFilter: stencilResolveFilter)
        guard built.valid else { invalidLocked(); return FactoryToken(rowIndex: -1, tracked: false) }
        let needed = built.rows.count
        guard needed <= capacity - rows.count else {
            add(&droppedRows, Int64(needed)); invalidLocked()
            return FactoryToken(rowIndex: -1, tracked: false)
        }
        let first = rows.count
        rows.append(contentsOf: built.rows.map { Row(words: $0) })
        nextSequence = nextSequence == Int64.max ? Int64.max : nextSequence + 1
        return FactoryToken(rowIndex: first, tracked: true)
    }

    func result(_ token: FactoryToken, encoder: AnyObject?) {
        guard token.tracked, enabledFastPath else { return }
        lock.lock(); defer { lock.unlock() }
        guard token.rowIndex >= 0, token.rowIndex < rows.count else { invalidLocked(); return }
        let sequence = rows[token.rowIndex].words[C.sequence]
        if let encoder {
            let key = ObjectIdentifier(encoder)
            guard encoderRows[key] == nil else { invalidLocked(); return }
            encoderRows[key] = token.rowIndex
        } else {
            for index in token.rowIndex..<rows.count where rows[index].words[C.sequence] == sequence {
                rows[index].words[C.errors] |= Self.createFailed
                rows[index].words[C.ended] = 1
            }
        }
    }

    private func setStoreLocked(encoder: AnyObject, aspect: Int64, slot: Int64, store: Int64) {
        guard let marker = encoderRows[ObjectIdentifier(encoder)] else { invalidLocked(); return }
        let sequence = rows[marker].words[C.sequence]
        for index in marker..<rows.count where rows[index].words[C.sequence] == sequence {
            if rows[index].words[C.aspect] == aspect && rows[index].words[C.slot] == slot {
                rows[index].words[C.finalStore] = store
                if store < 0 || store > 5 { rows[index].words[C.errors] |= Self.unsupported }
                return
            }
        }
        invalidLocked()
    }

    func setDepthStoreAction(_ encoder: AnyObject, _ store: Int64) {
        guard enabledFastPath else { return }
        lock.lock(); setStoreLocked(encoder: encoder, aspect: 1, slot: 0, store: store); lock.unlock()
    }

    func setColorStoreAction(_ encoder: AnyObject, index: Int64, store: Int64) {
        guard enabledFastPath else { return }
        lock.lock(); setStoreLocked(encoder: encoder, aspect: 0, slot: index, store: store); lock.unlock()
    }

    /// Opaque MetalFX encoders are not created through a descriptor visible to
    /// this module. Mark the bound submission unsupported so a consumer cannot
    /// mistake the visible render rows for complete encoder coverage.
    func unsupported(_ commandBuffer: AnyObject) {
        guard enabledFastPath else { return }
        lock.lock(); defer { lock.unlock() }
        guard let identity = encoderCountBindingIdentity(for: commandBuffer) else {
            invalidLocked(); return
        }
        var marked = false
        for index in rows.indices where rows[index].words[C.window] == identity.windowId
                && rows[index].words[C.submit] == identity.submitIndex
                && rows[index].words[C.backend] == identity.backend {
            rows[index].words[C.errors] |= Self.unsupported
            marked = true
        }
        if !marked { invalidLocked() }
    }

    func end(_ encoder: AnyObject) {
        guard enabledFastPath else { return }
        lock.lock(); defer { lock.unlock() }
        guard let marker = encoderRows.removeValue(forKey: ObjectIdentifier(encoder)) else {
            invalidLocked(); return
        }
        let sequence = rows[marker].words[C.sequence]
        for index in marker..<rows.count where rows[index].words[C.sequence] == sequence {
            if rows[index].words[C.aspect] != -1 && rows[index].words[C.finalStore] == MTLStoreAction.unknown.rawValue {
                rows[index].words[C.errors] |= Self.unresolvedFinalStore
            }
            rows[index].words[C.ended] = 1
        }
    }

    func copy(rows output: UnsafeMutablePointer<Int64>?, capacityRows: Int32, metadata: UnsafeMutablePointer<Int64>?) -> Int32 {
        guard capacityRows >= 0 else { return -1 }
        lock.lock(); defer { lock.unlock() }
        metadata?[0] = 1; metadata?[1] = capacity == 0 ? 0 : 1
        metadata?[2] = Int64(capacity); metadata?[3] = droppedRows; metadata?[4] = invalidEvents
        metadata?[5] = Int64(encoderRows.count)
        metadata?[6] = Int64(rows.reduce(into: 0) { count, row in
            if row.words[C.aspect] == -1 && row.words[C.errors] & Self.createFailed == 0 { count += 1 }
        })
        metadata?[7] = Int64(self.rows.count)
        guard let output else { return Int32(self.rows.count) }
        guard Int(capacityRows) >= self.rows.count else { return -1 }
        for (rowIndex, row) in self.rows.enumerated() {
            let offset = rowIndex * Self.rowWidth
            for column in 0..<Self.rowWidth { output[offset + column] = row.words[column] }
        }
        return Int32(self.rows.count)
    }

    func factoryAttempt(_ commandBuffer: MTLCommandBuffer, _ descriptor: MTLRenderPassDescriptor) -> FactoryToken {
        attempt(commandBuffer: commandBuffer as AnyObject, backend: 3,
                targetWidth: Int64(descriptor.renderTargetWidth), targetHeight: Int64(descriptor.renderTargetHeight),
                targetArrayLength: Int64(descriptor.renderTargetArrayLength), colors: descriptor.colorAttachments,
                depth: descriptor.depthAttachment, stencil: descriptor.stencilAttachment,
                depthResolveFilter: Int64(descriptor.depthAttachment.depthResolveFilter.rawValue),
                stencilResolveFilter: Int64(descriptor.stencilAttachment.stencilResolveFilter.rawValue))
    }

    @available(macOS 26.0, iOS 26.0, *)
    func factoryAttempt(_ commandBuffer: MTL4CommandBuffer, _ descriptor: MTL4RenderPassDescriptor) -> FactoryToken {
        attempt(commandBuffer: commandBuffer as AnyObject, backend: 4,
                targetWidth: Int64(descriptor.renderTargetWidth), targetHeight: Int64(descriptor.renderTargetHeight),
                targetArrayLength: Int64(descriptor.renderTargetArrayLength), colors: descriptor.colorAttachments,
                depth: descriptor.depthAttachment, stencil: descriptor.stencilAttachment,
                depthResolveFilter: Int64(descriptor.depthAttachment.depthResolveFilter.rawValue),
                stencilResolveFilter: Int64(descriptor.stencilAttachment.stencilResolveFilter.rawValue))
    }
}

@_cdecl("metallum_attachment_actions_reset")
public func metallum_attachment_actions_reset(_ capacityRows: Int32) -> Int32 {
    AttachmentActionLedger.shared.reset(capacityRows) ? 1 : 0
}

@_cdecl("metallum_attachment_actions_copy")
public func metallum_attachment_actions_copy(
    _ rows: UnsafeMutablePointer<Int64>?, _ capacityRows: Int32,
    _ metadata: UnsafeMutablePointer<Int64>?
) -> Int32 {
    AttachmentActionLedger.shared.copy(rows: rows, capacityRows: capacityRows, metadata: metadata)
}
