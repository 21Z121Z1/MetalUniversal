import Foundation
import Metal

enum MetalFrameGenerationAdmissionDecision: Equatable {
    case wait(until: CFTimeInterval)
    case supersede
}

struct MetalFrameGenerationAdmissionPolicy {
    static func decide(
        now: CFTimeInterval,
        lastDisplayUpdateTime: CFTimeInterval?,
        activityTimeout: CFTimeInterval,
        absoluteDeadline: CFTimeInterval
    ) -> MetalFrameGenerationAdmissionDecision {
        guard let lastDisplayUpdateTime,
              now.isFinite,
              lastDisplayUpdateTime.isFinite,
              activityTimeout > 0.0,
              absoluteDeadline.isFinite,
              now >= lastDisplayUpdateTime else {
            return .supersede
        }
        let activityDeadline = lastDisplayUpdateTime + activityTimeout
        guard activityDeadline.isFinite,
              now < activityDeadline,
              now < absoluteDeadline else {
            return .supersede
        }
        return .wait(until: min(activityDeadline, absoluteDeadline))
    }
}

enum MetalFrameGenerationSourcePhase: String, Equatable {
    case queued
    case active
    case gpuSubmitted = "GPU-submitted"
    case realPresentPending = "real-present-pending"
    case presented
    case cancelled
    case failed
    case released
}

enum MetalFrameGenerationGPUWork: Equatable {
    case input
    case generated
    case real
}

enum MetalFrameGenerationPresentationStep: Equatable {
    case generated
    case real
}

enum MetalFrameGenerationLifecycleAction: Equatable {
    case releaseOwnership
    case invalidateHistory
}

struct MetalFrameGenerationHistoryOwnership {
    private(set) var interpolatorEventValue: UInt64?
    private(set) var displayEventValue: UInt64?

    var interpolatorValid: Bool { interpolatorEventValue != nil }
    var displayValid: Bool { displayEventValue != nil }

    mutating func recordInterpolator(eventValue: UInt64) {
        interpolatorEventValue = eventValue
    }

    mutating func recordDisplay(eventValue: UInt64) {
        displayEventValue = eventValue
    }

    @discardableResult
    mutating func invalidateInterpolator(ifOwnedBy eventValue: UInt64) -> Bool {
        guard interpolatorEventValue == eventValue else { return false }
        interpolatorEventValue = nil
        return true
    }

    @discardableResult
    mutating func invalidateDisplay(ifOwnedBy eventValue: UInt64) -> Bool {
        guard displayEventValue == eventValue else { return false }
        displayEventValue = nil
        return true
    }

    mutating func invalidateAll() {
        interpolatorEventValue = nil
        displayEventValue = nil
    }
}

struct MetalFrameGenerationLifecycle {
    let sourceFrameID: UInt64

    private(set) var phase: MetalFrameGenerationSourcePhase = .queued
    private(set) var terminalPhase: MetalFrameGenerationSourcePhase?
    private(set) var ownershipReleased = false
    private(set) var cancellationRequested = false
    private(set) var failureReason: String?

    private(set) var inputSubmitted = false
    private(set) var inputCompleted = false
    private(set) var inputSucceeded = false
    private(set) var hasInterpolation = false
    private(set) var activated = false
    private(set) var generatedSubmitted = false
    private(set) var generatedCompleted = false
    private(set) var generatedSucceeded = false
    private(set) var realSubmitted = false
    private(set) var realCompleted = false
    private(set) var realSucceeded = false
    private(set) var generatedPresentedCallbackReceived = false
    private(set) var generatedPresentedSuccessfully = false
    private(set) var realPresentedCallbackReceived = false
    private(set) var realPresentedSuccessfully = false
    private(set) var gpuWorkInFlight = 0

    init(sourceFrameID: UInt64) {
        self.sourceFrameID = sourceFrameID
    }

    var nextPresentationStep: MetalFrameGenerationPresentationStep? {
        guard !ownershipReleased, !cancellationRequested, inputCompleted, inputSucceeded, activated else {
            return nil
        }
        if hasInterpolation && !generatedSubmitted {
            return .generated
        }
        if (!hasInterpolation || generatedSubmitted) && !realSubmitted {
            return .real
        }
        return nil
    }

    mutating func submitInput() -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased, !inputSubmitted else {
            return []
        }
        inputSubmitted = true
        gpuWorkInFlight += 1
        phase = .gpuSubmitted
        return []
    }

    mutating func activate(hasInterpolation: Bool) -> Bool {
        guard !ownershipReleased, !cancellationRequested,
              inputCompleted, inputSucceeded, !activated else {
            return false
        }
        self.hasInterpolation = hasInterpolation
        activated = true
        phase = .active
        return true
    }

    mutating func submitPresentation(
        _ step: MetalFrameGenerationPresentationStep
    ) -> [MetalFrameGenerationLifecycleAction] {
        guard nextPresentationStep == step else {
            return []
        }
        switch step {
        case .generated:
            generatedSubmitted = true
        case .real:
            realSubmitted = true
        }
        gpuWorkInFlight += 1
        phase = .gpuSubmitted
        return []
    }

    mutating func failBeforeSubmission(
        _ step: MetalFrameGenerationPresentationStep,
        reason: String
    ) -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased, nextPresentationStep == step else {
            return []
        }
        failureReason = reason
        switch step {
        case .generated:
            generatedSubmitted = true
            generatedCompleted = true
            generatedSucceeded = false
            phase = .failed
            return [.invalidateHistory]
        case .real:
            realSubmitted = true
            realCompleted = true
            realSucceeded = false
            phase = .failed
            return terminalActions()
        }
    }

    mutating func completeGPUWork(
        _ work: MetalFrameGenerationGPUWork,
        succeeded: Bool,
        reason: String? = nil
    ) -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased else {
            return []
        }

        let wasPending: Bool
        switch work {
        case .input:
            wasPending = inputSubmitted && !inputCompleted
            guard wasPending else { return [] }
            inputCompleted = true
            inputSucceeded = succeeded
        case .generated:
            wasPending = generatedSubmitted && !generatedCompleted
            guard wasPending else { return [] }
            generatedCompleted = true
            generatedSucceeded = succeeded
        case .real:
            wasPending = realSubmitted && !realCompleted
            guard wasPending else { return [] }
            realCompleted = true
            realSucceeded = succeeded
        }

        gpuWorkInFlight = max(0, gpuWorkInFlight - 1)

        if cancellationRequested {
            phase = .cancelled
            return terminalActions()
        }

        guard succeeded else {
            failureReason = reason ?? "\(work) command buffer failed"
            phase = .failed
            if work == .generated {
                return [.invalidateHistory]
            }
            return [.invalidateHistory] + terminalActions()
        }

        switch work {
        case .input:
            phase = .queued
        case .generated:
            phase = .active
        case .real:
            if realPresentedCallbackReceived {
                if realPresentedSuccessfully {
                    phase = .presented
                } else {
                    phase = .failed
                }
                return terminalActions()
            }
            phase = .realPresentPending
            terminalPhase = .realPresentPending
            ownershipReleased = true
            phase = .released
            return [.releaseOwnership]
        }
        return []
    }

    mutating func recordPresented(
        _ step: MetalFrameGenerationPresentationStep,
        presentedTime: CFTimeInterval
    ) -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased else {
            return []
        }
        let actuallyPresented = presentedTime.isFinite && presentedTime > 0.0
        switch step {
        case .generated:
            guard generatedSubmitted, !generatedPresentedCallbackReceived else {
                return []
            }
            generatedPresentedCallbackReceived = true
            generatedPresentedSuccessfully = actuallyPresented
            if !actuallyPresented {
                failureReason = "generated drawable was not presented"
                return [.invalidateHistory]
            }
            return []
        case .real:
            guard realSubmitted, !realPresentedCallbackReceived else {
                return []
            }
            realPresentedCallbackReceived = true
            realPresentedSuccessfully = actuallyPresented
            guard realCompleted else {
                return actuallyPresented ? [] : [.invalidateHistory]
            }
            if actuallyPresented && realSucceeded && !cancellationRequested {
                phase = .presented
            } else if cancellationRequested {
                phase = .cancelled
            } else {
                failureReason = "real drawable was not presented"
                phase = .failed
            }
            return terminalActions()
        }
    }

    mutating func cancel(reason: String) -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased else {
            return []
        }
        cancellationRequested = true
        failureReason = reason
        phase = .cancelled
        return [.invalidateHistory] + terminalActions()
    }

    mutating func failPendingPresentation(reason: String) -> [MetalFrameGenerationLifecycleAction] {
        guard !ownershipReleased, realSubmitted, realCompleted, !realPresentedCallbackReceived else {
            return []
        }
        failureReason = reason
        phase = .failed
        return [.invalidateHistory] + terminalActions()
    }

    private mutating func terminalActions() -> [MetalFrameGenerationLifecycleAction] {
        guard gpuWorkInFlight == 0, !ownershipReleased else {
            return []
        }
        guard phase == .presented || phase == .cancelled || phase == .failed else {
            return []
        }
        terminalPhase = phase
        ownershipReleased = true
        phase = .released
        return [.releaseOwnership]
    }
}

// MARK: - Raw compute packet adapters
//
// The generic compute ABI returns a retained MTLComputeCommandEncoder as an
// opaque pointer. MetallumInterface.swift owns only that pointer, while the
// established native functions accept the typed protocol object. These local
// overloads restore the object and call Metal directly. They deliberately have
// no @_cdecl symbol and therefore do not change the public ABI.

@inline(__always)
private func metalComputePacketEncoder(
    _ pointer: UnsafeMutableRawPointer
) -> MTLComputeCommandEncoder {
    Unmanaged<AnyObject>.fromOpaque(pointer).takeUnretainedValue()
        as! MTLComputeCommandEncoder
}

func metallum_MTLComputeCommandEncoder_setComputePipelineState(
    _ pointer: UnsafeMutableRawPointer,
    _ pipelineState: MTLComputePipelineState
) {
    metalComputePacketEncoder(pointer).setComputePipelineState(pipelineState)
}

func metallum_MTLComputeCommandEncoder_setBuffer(
    _ pointer: UnsafeMutableRawPointer,
    _ buffer: MTLBuffer?,
    _ offset: UInt64,
    _ index: Int32
) {
    metalComputePacketEncoder(pointer).setBuffer(
        buffer,
        offset: Int(offset),
        index: Int(index)
    )
}

func metallum_MTLComputeCommandEncoder_setTexture(
    _ pointer: UnsafeMutableRawPointer,
    _ texture: MTLTexture?,
    _ index: Int32
) {
    metalComputePacketEncoder(pointer).setTexture(texture, index: Int(index))
}

func metallum_MTLComputeCommandEncoder_setSamplerState(
    _ pointer: UnsafeMutableRawPointer,
    _ sampler: MTLSamplerState?,
    _ index: Int32
) {
    metalComputePacketEncoder(pointer).setSamplerState(sampler, index: Int(index))
}

func metallum_MTLComputeCommandEncoder_dispatchThreadgroups(
    _ pointer: UnsafeMutableRawPointer,
    _ groupsX: Int32,
    _ groupsY: Int32,
    _ groupsZ: Int32,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    metalComputePacketEncoder(pointer).dispatchThreadgroups(
        MTLSize(width: Int(groupsX), height: Int(groupsY), depth: Int(groupsZ)),
        threadsPerThreadgroup: MTLSize(
            width: Int(threadsPerGroupX),
            height: Int(threadsPerGroupY),
            depth: Int(threadsPerGroupZ)
        )
    )
}

func metallum_MTLComputeCommandEncoder_dispatchThreadgroupsIndirect(
    _ pointer: UnsafeMutableRawPointer,
    _ indirectBuffer: MTLBuffer,
    _ indirectOffset: UInt64,
    _ threadsPerGroupX: Int32,
    _ threadsPerGroupY: Int32,
    _ threadsPerGroupZ: Int32
) {
    metalComputePacketEncoder(pointer).dispatchThreadgroups(
        indirectBuffer: indirectBuffer,
        indirectBufferOffset: Int(indirectOffset),
        threadsPerThreadgroup: MTLSize(
            width: Int(threadsPerGroupX),
            height: Int(threadsPerGroupY),
            depth: Int(threadsPerGroupZ)
        )
    )
}
