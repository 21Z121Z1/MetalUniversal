import Foundation
import CoreFoundation

/// Immutable source values consumed by both Metal 3 and Metal 4 interpolation.
/// Motion is stored as a top-left oriented NDC displacement (previous-current).
/// The SDK scales it into PREVIOUS COLOR pixels, not depth/motion texel units.
/// Input allocation dimensions must therefore never determine these scales.
struct MetalFxFrameParameters: Equatable {
    let depthWidth: Int
    let depthHeight: Int
    let colorWidth: Int
    let colorHeight: Int
    let jitterX: Float
    let jitterY: Float
    let fieldOfView: Float
    let nearPlane: Float
    let farPlane: Float
    let aspectRatio: Float
    let deltaTime: Float

    var motionScaleX: Float { Float(colorWidth) * 0.5 }
    var motionScaleY: Float { Float(colorHeight) * 0.5 }

    init?(depthWidth: Int, depthHeight: Int, colorWidth: Int, colorHeight: Int,
          jitterX: Float, jitterY: Float, fieldOfView: Float, nearPlane: Float,
          farPlane: Float, aspectRatio: Float, deltaTime: Float) {
        guard depthWidth > 0, depthHeight > 0, colorWidth > 0, colorHeight > 0,
              depthWidth <= Int(Int32.max), depthHeight <= Int(Int32.max),
              colorWidth <= Int(Int32.max), colorHeight <= Int(Int32.max),
              jitterX.isFinite, jitterY.isFinite,
              fieldOfView.isFinite, fieldOfView > 0, fieldOfView < 180,
              nearPlane.isFinite, nearPlane > 0,
              farPlane.isFinite, farPlane > nearPlane,
              aspectRatio.isFinite, aspectRatio > 0,
              deltaTime.isFinite, deltaTime > 0 else { return nil }
        self.depthWidth = depthWidth
        self.depthHeight = depthHeight
        self.colorWidth = colorWidth
        self.colorHeight = colorHeight
        self.jitterX = jitterX
        self.jitterY = jitterY
        self.fieldOfView = fieldOfView
        self.nearPlane = nearPlane
        self.farPlane = farPlane
        self.aspectRatio = aspectRatio
        self.deltaTime = deltaTime
    }
}

/// CPU reference for bounded Frame Generation input resampling.
///
/// The GPU pass uses the same candidate order (base, +x, +y, diagonal) and
/// strict-greater comparison. This keeps depth and motion paired even when a
/// footprint straddles an occlusion edge, and makes tie-breaking independently
/// testable without relying on Metal readback.
struct MetalFxBoundedInputCandidate: Equatable {
    let sourceIndex: Int
    let depth: Float
    let motion: SIMD2<Float>
}

enum MetalFxBoundedInputOracle {
    static func chooseReversedZ(
        _ candidates: [MetalFxBoundedInputCandidate]
    ) -> MetalFxBoundedInputCandidate? {
        guard var best = candidates.first else { return nil }
        for candidate in candidates.dropFirst() {
            guard candidate.depth.isFinite else { continue }
            if !best.depth.isFinite || candidate.depth > best.depth {
                best = candidate
            }
        }
        return best
    }
}


/// Telemetry state for the scaler passed to a FrameInterpolator descriptor.
/// A linked state is only reported after the factory accepted descriptor.scaler.
enum MetalFxFrameInterpolatorScalerLinkStatus: Int32, Equatable {
    case unavailable = 0
    case metal3Linked = 1
    case metal4Linked = 2
    case metal3Standalone = 3
    case metal3LinkRejected = 4
    case metal4Standalone = 5
    case metal4LinkRejected = 6

    var isLinked: Bool {
        self == .metal3Linked || self == .metal4Linked
    }
}

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

struct MetalFrameGenerationLifecycleAction: OptionSet, Equatable {
    let rawValue: UInt8

    static let releaseOwnership = MetalFrameGenerationLifecycleAction(rawValue: 1 << 0)
    static let invalidateHistory = MetalFrameGenerationLifecycleAction(rawValue: 1 << 1)
}

/// Identifies which source most recently established each presenter history.
/// Drawable callbacks can arrive after source ownership has moved on; a stale
/// failure must not invalidate history produced by a newer source.
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

/// Metal-independent reducer for one source frame.
///
/// All calls are expected to be serialized by the presenter. The reducer owns
/// no Metal objects; it only decides whether work may advance and when the
/// presenter's source ownership token can be released.
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
        // Generated and real command buffers share one serial presenter queue.
        // Submission order is therefore sufficient; waiting for the generated
        // completion handler here can unnecessarily skip the next display update.
        if (!hasInterpolation || generatedSubmitted) && !realSubmitted {
            return .real
        }
        return nil
    }

    mutating func submitInput() -> MetalFrameGenerationLifecycleAction {
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
    ) -> MetalFrameGenerationLifecycleAction {
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
    ) -> MetalFrameGenerationLifecycleAction {
        guard !ownershipReleased, nextPresentationStep == step else {
            return []
        }
        failureReason = reason
        switch step {
        case .generated:
            // A generated-frame failure invalidates interpolation history, but
            // the real source frame may still be presented on a later update.
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
    ) -> MetalFrameGenerationLifecycleAction {
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
                // Preserve the source long enough to try its real frame.
                return [.invalidateHistory]
            }
            return MetalFrameGenerationLifecycleAction.invalidateHistory.union(terminalActions())
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
            // The present command buffer has finished reading the source slot
            // and writing the CAMetalDrawable, so the slot is safe to reuse.
            // WindowServer may report the actual scanout several refreshes
            // later in windowed mode; retaining ownership until that callback
            // serializes this latency into the game's source-frame rate.
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
    ) -> MetalFrameGenerationLifecycleAction {
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

    mutating func cancel(reason: String) -> MetalFrameGenerationLifecycleAction {
        guard !ownershipReleased else {
            return []
        }
        cancellationRequested = true
        failureReason = reason
        phase = .cancelled
        return MetalFrameGenerationLifecycleAction.invalidateHistory.union(terminalActions())
    }

    mutating func failPendingPresentation(reason: String) -> MetalFrameGenerationLifecycleAction {
        guard !ownershipReleased, realSubmitted, realCompleted, !realPresentedCallbackReceived else {
            return []
        }
        failureReason = reason
        phase = .failed
        return MetalFrameGenerationLifecycleAction.invalidateHistory.union(terminalActions())
    }

    private mutating func terminalActions() -> MetalFrameGenerationLifecycleAction {
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

/// Ownership of the depth copied by a Temporal encode. Callers serialize this
/// reducer with their existing history lock. A format/size cache key is not a
/// history identity: reset, eviction and A -> B -> A must allocate a new epoch.
struct MetalFxDepthHistoryOwnership<Key: Hashable> {
    struct Ticket: Equatable {
        let epoch: UInt64
        let submission: UInt64
    }
    struct Submission {
        let ticket: Ticket
        let previousDepthIsValid: Bool
        // Reset is a property of actual native ownership, not just the Java
        // flag: queue changes, failures and cache recreation also reset MetalFX.
        var shouldResetHistory: Bool { !previousDepthIsValid }
    }
    private struct Entry {
        let epoch: UInt64
        var issued: UInt64 = 0
        // Publication means a successfully encoded copy has actually been
        // committed to this history's queue. It is not GPU-completion evidence.
        var published: UInt64 = 0
        var completed: UInt64 = 0
        var poisoned = false
    }
    private var nextEpoch: UInt64 = 0
    private var entries: [Key: Entry] = [:]
    private var activeKey: Key?

    mutating func begin(_ key: Key, reset: Bool) -> Submission {
        // Returning to a cached format/extent is NOT a continuation of its old
        // source stream. Invalidate even when an upstream reset flag was lost.
        if activeKey != key {
            invalidateAll()
            activeKey = key
        }
        if reset || entries[key] == nil {
            precondition(nextEpoch < UInt64.max, "Depth history epoch exhausted")
            nextEpoch += 1
            entries[key] = Entry(epoch: nextEpoch)
        }
        var entry = entries[key]!
        // The GPU reads the immediately preceding committed copy, ordered by
        // tracked hazards (Metal 3) or an explicit queue barrier (Metal 4).
        // Requiring its CPU completion callback here would starve history when
        // the CPU consistently submits two or more source frames ahead.
        let valid = entry.issued > 0 && entry.published == entry.issued && !entry.poisoned
        precondition(entry.issued < UInt64.max, "Depth history submission exhausted")
        entry.issued += 1
        entries[key] = entry
        return Submission(ticket: Ticket(epoch: entry.epoch, submission: entry.issued),
                          previousDepthIsValid: valid)
    }

    /// Called only after committing the command buffer containing this copy.
    /// Merely allocating or encoding a texture must never initialize history.
    mutating func publish(_ key: Key, ticket: Ticket) {
        guard var entry = entries[key], entry.epoch == ticket.epoch,
              ticket.submission <= entry.issued, ticket.submission > entry.published else { return }
        entry.published = ticket.submission
        // Do not clear a GPU failure, including completion racing with commit.
        entries[key] = entry
    }

    mutating func complete(_ key: Key, ticket: Ticket, succeeded: Bool) {
        guard var entry = entries[key], entry.epoch == ticket.epoch,
              ticket.submission > entry.completed, ticket.submission <= entry.issued else { return }
        entry.completed = ticket.submission
        entry.poisoned = !succeeded
        // Completion also proves submission for native clients that commit a
        // command buffer directly instead of using the production bridge.
        entry.published = max(entry.published, ticket.submission)
        entries[key] = entry
    }

    mutating func invalidate(_ key: Key) { entries.removeValue(forKey: key) }

    mutating func invalidate(_ key: Key, ifOwnedBy ticket: Ticket) {
        guard let entry = entries[key], entry.epoch == ticket.epoch,
              entry.issued == ticket.submission else { return }
        invalidate(key)
    }

    mutating func invalidateAll() {
        entries.removeAll()
        activeKey = nil
        // Deliberately retain nextEpoch: outstanding callbacks may still exist.
    }
}

/// Owned by the real command buffer / Metal 4 lease, not by a global pointer
/// registry. Only the real commit path can publish an encoded depth copy.
/// Releasing an unsubmitted owner cancels its tickets without GPU callbacks.
/// Encoding and commit run on the command-buffer owner thread; callbacks use
/// the existing native history lock inside the supplied closures.
final class MetalFxSubmissionPublication {
    private var submitted = false
    private var actions: [(publish: () -> Void, cancel: () -> Void)] = []

    func append(publish: @escaping () -> Void, cancel: @escaping () -> Void) {
        precondition(!submitted, "Cannot append work to a submitted command buffer")
        actions.append((publish, cancel))
    }

    func didSubmit() {
        guard !submitted else { return }
        submitted = true
        let submittedActions = actions
        actions.removeAll()
        for action in submittedActions { action.publish() }
    }

    deinit {
        if !submitted {
            for action in actions { action.cancel() }
        }
    }
}
