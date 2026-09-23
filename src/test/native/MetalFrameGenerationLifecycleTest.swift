import Foundation

private func assertScalerLinkStatusContract() {
    precondition(MetalFxFrameInterpolatorScalerLinkStatus.unavailable.rawValue == 0)
    precondition(MetalFxFrameInterpolatorScalerLinkStatus.metal3Linked.isLinked)
    precondition(MetalFxFrameInterpolatorScalerLinkStatus.metal4Linked.isLinked)
    precondition(!MetalFxFrameInterpolatorScalerLinkStatus.metal3Standalone.isLinked)
    precondition(!MetalFxFrameInterpolatorScalerLinkStatus.metal3LinkRejected.isLinked)
    precondition(!MetalFxFrameInterpolatorScalerLinkStatus.metal4Standalone.isLinked)
    precondition(!MetalFxFrameInterpolatorScalerLinkStatus.metal4LinkRejected.isLinked)
}

private enum TestFailure: Error, CustomStringConvertible {
    case assertion(String)

    var description: String {
        switch self {
        case .assertion(let message): return message
        }
    }
}

private func expect(
    _ condition: @autoclosure () -> Bool,
    _ message: String
) throws {
    if !condition() {
        throw TestFailure.assertion(message)
    }
}

private func testBoundedInputUsesDepthWinnerMotion() throws {
    let candidates = [
        MetalFxBoundedInputCandidate(
            sourceIndex: 0, depth: 0.82, motion: SIMD2(0.10, 0.20)
        ),
        MetalFxBoundedInputCandidate(
            sourceIndex: 1, depth: 0.96, motion: SIMD2(0.30, 0.40)
        ),
        MetalFxBoundedInputCandidate(
            sourceIndex: 2, depth: 0.96, motion: SIMD2(0.50, 0.60)
        ),
        MetalFxBoundedInputCandidate(
            sourceIndex: 3, depth: 0.10, motion: SIMD2(0.70, 0.80)
        )
    ]
    let selected = MetalFxBoundedInputOracle.chooseReversedZ(candidates)
    try expect(selected?.sourceIndex == 1, "reversed-Z max depth must use stable first-winner tie break")
    try expect(
        selected?.motion == SIMD2(0.30, 0.40),
        "motion must come from the selected depth texel"
    )

    let edge = [
        MetalFxBoundedInputCandidate(
            sourceIndex: 0, depth: Float.nan, motion: SIMD2(0.10, 0.20)
        ),
        MetalFxBoundedInputCandidate(
            sourceIndex: 1, depth: 0.25, motion: SIMD2(0.30, 0.40)
        )
    ]
    try expect(
        MetalFxBoundedInputOracle.chooseReversedZ(edge)?.sourceIndex == 1,
        "finite edge depth must beat an invalid source texel"
    )
}

private func testAdmissionTracksDisplayActivity() throws {
    let freshDecision = MetalFrameGenerationAdmissionPolicy.decide(
        now: 10.0,
        lastDisplayUpdateTime: 9.98,
        activityTimeout: 0.05,
        absoluteDeadline: 10.02
    )
    guard case .wait(let deadline) = freshDecision else {
        throw TestFailure.assertion("fresh display activity must preserve the current source")
    }
    try expect(abs(deadline - 10.02) < 0.000_001, "absolute deadline must cap fresh activity")
    try expect(
        MetalFrameGenerationAdmissionPolicy.decide(
            now: 10.04,
            lastDisplayUpdateTime: 9.98,
            activityTimeout: 0.05,
            absoluteDeadline: 10.10
        ) == .supersede,
        "stale display activity must use latest-source-wins"
    )
    try expect(
        MetalFrameGenerationAdmissionPolicy.decide(
            now: 10.0,
            lastDisplayUpdateTime: nil,
            activityTimeout: 0.05,
            absoluteDeadline: 10.02
        ) == .supersede,
        "a display that has never updated must not block the render thread"
    )
    try expect(
        MetalFrameGenerationAdmissionPolicy.decide(
            now: 10.02,
            lastDisplayUpdateTime: 10.019,
            activityTimeout: 0.05,
            absoluteDeadline: 10.02
        ) == .supersede,
        "continuous callbacks must not extend the absolute admission deadline"
    )
    let activityBoundary = 9.98 + 0.05
    try expect(
        MetalFrameGenerationAdmissionPolicy.decide(
            now: activityBoundary,
            lastDisplayUpdateTime: 9.98,
            activityTimeout: 0.05,
            absoluteDeadline: 10.10
        ) == .supersede,
        "the activity timeout boundary must supersede"
    )
    for invalid in [Double.nan, Double.infinity, -Double.infinity] {
        try expect(
            MetalFrameGenerationAdmissionPolicy.decide(
                now: invalid,
                lastDisplayUpdateTime: 9.99,
                activityTimeout: 0.05,
                absoluteDeadline: 10.02
            ) == .supersede,
            "non-finite admission timestamps must supersede"
        )
    }
    try expect(
        MetalFrameGenerationAdmissionPolicy.decide(
            now: 10.0,
            lastDisplayUpdateTime: 10.01,
            activityTimeout: 0.05,
            absoluteDeadline: 10.02
        ) == .supersede,
        "future display timestamps must supersede"
    )
}

private func makeReady(
    sourceFrameID: UInt64,
    interpolation: Bool
) throws -> MetalFrameGenerationLifecycle {
    var state = MetalFrameGenerationLifecycle(sourceFrameID: sourceFrameID)
    _ = state.submitInput()
    _ = state.completeGPUWork(.input, succeeded: true)
    try expect(state.activate(hasInterpolation: interpolation), "source should activate")
    return state
}

private func testGeneratedThenReal() throws {
    var state = try makeReady(sourceFrameID: 1, interpolation: true)
    try expect(state.nextPresentationStep == .generated, "generated must be first")
    _ = state.submitPresentation(.generated)
    try expect(state.nextPresentationStep == .real, "serial queue order permits real submission")
    _ = state.submitPresentation(.real)
    _ = state.recordPresented(.generated, presentedTime: 1.0)
    _ = state.completeGPUWork(.generated, succeeded: true)
    let actions = state.completeGPUWork(.real, succeeded: true)
    try expect(
        state.terminalPhase == .realPresentPending,
        "real GPU completion must not claim a WindowServer presentation"
    )
    try expect(actions == [.releaseOwnership], "real GPU completion releases source ownership")
    try expect(
        state.recordPresented(.real, presentedTime: 2.0).isEmpty,
        "late presented callback cannot release ownership twice"
    )
}

private func testPresentedBeforeGPUCompletion() throws {
    var state = try makeReady(sourceFrameID: 12, interpolation: false)
    _ = state.submitPresentation(.real)
    try expect(
        state.recordPresented(.real, presentedTime: 2.0).isEmpty,
        "presented callback must still wait for GPU completion"
    )
    let actions = state.completeGPUWork(.real, succeeded: true)
    try expect(state.terminalPhase == .presented, "early callback records a real presentation")
    try expect(actions == [.releaseOwnership], "GPU completion releases after early callback")
}

private func testGuiSuspendAndResizeCancel() throws {
    for id in [UInt64(2), UInt64(3)] {
        var state = try makeReady(sourceFrameID: id, interpolation: true)
        let actions = state.cancel(reason: id == 2 ? "GUI suspend" : "resize")
        try expect(state.terminalPhase == .cancelled, "unsubmitted source must cancel")
        try expect(actions.contains(.releaseOwnership), "cancel must release unsubmitted source")
    }
}

private func testEnqueueThenShutdown() throws {
    var state = MetalFrameGenerationLifecycle(sourceFrameID: 4)
    _ = state.submitInput()
    let cancelActions = state.cancel(reason: "shutdown")
    try expect(!cancelActions.contains(.releaseOwnership), "input GPU work must drain before release")
    let completionActions = state.completeGPUWork(.input, succeeded: true)
    try expect(completionActions.contains(.releaseOwnership), "drained cancelled source must release")
}

private func testNewerSourceSupersedesStalledSource() throws {
    var inputInFlight = MetalFrameGenerationLifecycle(sourceFrameID: 13)
    _ = inputInFlight.submitInput()
    let cancelActions = inputInFlight.cancel(reason: "superseded by newer source")
    try expect(
        !cancelActions.contains(.releaseOwnership),
        "supersession must not reuse textures while input GPU work is in flight"
    )
    let completionActions = inputInFlight.completeGPUWork(.input, succeeded: true)
    try expect(
        completionActions.contains(.releaseOwnership),
        "superseded input must release as soon as its GPU work drains"
    )
    try expect(
        inputInFlight.terminalPhase == .cancelled,
        "superseded input must remain a cancellation, not a presentation"
    )

    var waitingForDisplay = try makeReady(sourceFrameID: 14, interpolation: true)
    let displayActions = waitingForDisplay.cancel(reason: "superseded by newer source")
    try expect(
        displayActions.contains(.releaseOwnership),
        "a source with no presentation GPU work must release without a display update"
    )
}

private func testStaleCallbackCannotInvalidateNewerHistory() throws {
    var history = MetalFrameGenerationHistoryOwnership()
    history.recordInterpolator(eventValue: 20)
    history.recordDisplay(eventValue: 20)
    history.recordInterpolator(eventValue: 21)
    history.recordDisplay(eventValue: 21)

    try expect(
        !history.invalidateInterpolator(ifOwnedBy: 20),
        "stale generated callback must not invalidate newer interpolator history"
    )
    try expect(
        !history.invalidateDisplay(ifOwnedBy: 20),
        "stale real callback must not invalidate newer display history"
    )
    try expect(history.interpolatorValid, "newer interpolator history must remain valid")
    try expect(history.displayValid, "newer display history must remain valid")
    try expect(
        history.invalidateInterpolator(ifOwnedBy: 21),
        "owning generated callback must invalidate its history"
    )
    try expect(
        history.invalidateDisplay(ifOwnedBy: 21),
        "owning real callback must invalidate its history"
    )
}

private func testGeneratedSubmittedShutdown() throws {
    var state = try makeReady(sourceFrameID: 5, interpolation: true)
    _ = state.submitPresentation(.generated)
    _ = state.cancel(reason: "shutdown")
    let actions = state.completeGPUWork(.generated, succeeded: true)
    try expect(state.terminalPhase == .cancelled, "submitted generated source must cancel after drain")
    try expect(actions.contains(.releaseOwnership), "generated drain must release")
    try expect(state.nextPresentationStep == nil, "real must not submit after shutdown")
}

private func testRealSubmittedShutdown() throws {
    var state = try makeReady(sourceFrameID: 6, interpolation: false)
    _ = state.submitPresentation(.real)
    _ = state.cancel(reason: "shutdown")
    let actions = state.completeGPUWork(.real, succeeded: true)
    try expect(state.terminalPhase == .cancelled, "shutdown must not wait for presented callback")
    try expect(actions.contains(.releaseOwnership), "real GPU completion must release cancelled source")
}

private func testCommandBufferFailure() throws {
    var generated = try makeReady(sourceFrameID: 7, interpolation: true)
    _ = generated.submitPresentation(.generated)
    let generatedActions = generated.completeGPUWork(.generated, succeeded: false, reason: "GPU error")
    try expect(generated.phase == .failed, "generated GPU error must be visible")
    try expect(generatedActions.contains(.invalidateHistory), "generated error invalidates history")
    try expect(generated.nextPresentationStep == .real, "real source remains recoverable")

    var real = try makeReady(sourceFrameID: 8, interpolation: false)
    _ = real.submitPresentation(.real)
    let realActions = real.completeGPUWork(.real, succeeded: false, reason: "GPU error")
    try expect(real.terminalPhase == .failed, "real GPU error must fail source")
    try expect(realActions.contains(.releaseOwnership), "failed real work must release")
}

private func testStaleDisplayUpdateDoesNotAdvance() throws {
    let state = try makeReady(sourceFrameID: 9, interpolation: true)
    try expect(state.nextPresentationStep == .generated, "stale update must leave generated pending")
    try expect(!state.generatedSubmitted, "stale update must not mark GPU submission")
}

private func testDuplicateCallbackAndIdempotentRelease() throws {
    var state = try makeReady(sourceFrameID: 10, interpolation: false)
    _ = state.submitPresentation(.real)
    let first = state.completeGPUWork(.real, succeeded: true)
    let duplicate = state.recordPresented(.real, presentedTime: 3.0)
    let cancelAfterRelease = state.cancel(reason: "duplicate shutdown")
    try expect(first == [.releaseOwnership], "GPU completion releases")
    try expect(duplicate.isEmpty, "duplicate callback is ignored")
    try expect(cancelAfterRelease.isEmpty, "release is idempotent")
}

private func testPresentedTimeZeroFails() throws {
    var state = try makeReady(sourceFrameID: 11, interpolation: false)
    _ = state.submitPresentation(.real)
    _ = state.recordPresented(.real, presentedTime: 0.0)
    let actions = state.completeGPUWork(.real, succeeded: true)
    try expect(state.terminalPhase == .failed, "presentedTime zero is not success")
    try expect(actions.contains(.releaseOwnership), "non-presented real frame releases")
}

private func testDepthHistoryRejectsStaleCompletions() throws {
    let history = MetalFxDepthHistoryState()
    let first = history.beginWrite(reset: false)
    try expect(!first.previousDepthIsValid, "first source has no previous depth")
    history.complete(first, succeeded: true)
    let second = history.beginWrite(reset: false)
    try expect(second.previousDepthIsValid, "completed immediate predecessor can be read")
    history.complete(first, succeeded: false)
    try expect(!history.isValid, "older failure cannot settle the pending source")
    history.complete(second, succeeded: true)
    history.complete(first, succeeded: false)
    try expect(history.isValid, "older failure cannot invalidate newer successful depth")
    let reset = history.beginWrite(reset: true)
    try expect(!reset.previousDepthIsValid, "reset never reads pre-reset depth")
    history.complete(second, succeeded: true)
    try expect(!history.isValid, "older success cannot authorize a reset generation")
    history.complete(reset, succeeded: false)
    history.complete(reset, succeeded: true)
    try expect(!history.isValid, "duplicate callback cannot reverse a failure")
}

private func testDepthHistoryReplacementAndPendingSource() throws {
    let retired = MetalFxDepthHistoryState()
    let oldWrite = retired.beginWrite(reset: false)
    let replacement = MetalFxDepthHistoryState()
    let newWrite = replacement.beginWrite(reset: false)
    retired.complete(oldWrite, succeeded: true)
    replacement.complete(oldWrite, succeeded: true)
    try expect(!replacement.isValid, "retired resource cannot authorize a replacement at the same key")
    let pending = replacement.beginWrite(reset: false)
    try expect(!pending.previousDepthIsValid, "a pending copy is not a completed immediate predecessor")
    replacement.complete(newWrite, succeeded: true)
    try expect(!replacement.isValid, "out-of-order callback cannot make older depth current")
    replacement.complete(pending, succeeded: true)
    try expect(replacement.isValid, "latest successful copy owns history")
}

private func testDepthHistoryCompletionPermutations() throws {
    for order in [[0, 1, 2], [0, 2, 1], [1, 0, 2], [1, 2, 0], [2, 0, 1], [2, 1, 0]] {
        for latestSucceeded in [false, true] {
            let history = MetalFxDepthHistoryState()
            let writes = [history.beginWrite(reset: false), history.beginWrite(reset: true),
                          history.beginWrite(reset: false)]
            for index in order {
                history.complete(writes[index], succeeded: index == 2 ? latestSucceeded : !latestSucceeded)
            }
            try expect(history.isValid == latestSucceeded,
                       "only the latest write may settle history, regardless of callback order")
        }
    }
}

private func makeParameters(
    depthWidth: Int = 640, depthHeight: Int = 360,
    colorWidth: Int = 1920, colorHeight: Int = 1080,
    jitterX: Float = -0.375, jitterY: Float = 0.25,
    fieldOfView: Float = 67.25, nearPlane: Float = 0.05,
    farPlane: Float = 1536, aspectRatio: Float = 16.0 / 9.0,
    deltaTime: Float = 1.0 / 37.0
) -> MetalFxFrameParameters? {
    MetalFxFrameParameters(depthWidth: depthWidth, depthHeight: depthHeight,
        colorWidth: colorWidth, colorHeight: colorHeight,
        jitterX: jitterX, jitterY: jitterY, fieldOfView: fieldOfView,
        nearPlane: nearPlane, farPlane: farPlane, aspectRatio: aspectRatio,
        deltaTime: deltaTime)
}

private func testInterpolationMotionUsesPreviousColorPixels() throws {
    for size in [(640, 360, 1920, 1080), (853, 479, 1281, 719), (613, 997, 613, 997)] {
        let p = makeParameters(depthWidth: size.0, depthHeight: size.1,
                               colorWidth: size.2, colorHeight: size.3)!
        // Independent forward screen motion: object moved right/down 10px.
        let current = SIMD2<Float>(0.15, -0.31)
        let previous = current - SIMD2<Float>(20.0 / Float(size.2), 20.0 / Float(size.3))
        let pixels = (previous - current) * SIMD2(p.motionScaleX, p.motionScaleY)
        try expect(abs(pixels.x + 10) < 0.0001 && abs(pixels.y + 10) < 0.0001,
                   "NDC motion must address previous COLOR pixels even with lower-resolution depth")
    }
}

private func testInterpolationPreservesRealSourceMetadata() throws {
    for delta: Float in [0.001, 1.0 / 37.0, 0.3, 1.25] {
        let p = makeParameters(deltaTime: delta)!
        try expect(p.deltaTime.bitPattern == delta.bitPattern,
                   "Source time must not be quantized, clamped, or replaced by enqueue spacing")
        try expect(p.jitterX == -0.375 && p.jitterY == 0.25 && p.fieldOfView == 67.25
                   && p.nearPlane == 0.05 && p.farPlane == 1536,
                   "Source jitter/camera metadata must reach the SDK unchanged")
    }
    for bad: Float in [0, -1, .nan, .infinity, -.infinity] {
        try expect(makeParameters(deltaTime: bad) == nil, "Unknown source time must fail closed")
        try expect(makeParameters(nearPlane: bad) == nil, "Invalid near plane must fail closed")
        try expect(makeParameters(aspectRatio: bad) == nil, "Invalid aspect must fail closed")
        try expect(makeParameters(fieldOfView: bad) == nil, "Invalid FOV must fail closed")
    }
    try expect(makeParameters(fieldOfView: 180) == nil, "180-degree perspective is invalid")
    try expect(makeParameters(farPlane: 0.05) == nil, "Far must exceed near")
    try expect(makeParameters(jitterX: .nan) == nil && makeParameters(jitterY: .infinity) == nil,
               "Non-finite jitter must fail closed")
    try expect(makeParameters(depthWidth: 0) == nil && makeParameters(depthHeight: -1) == nil
               && makeParameters(colorWidth: 0) == nil && makeParameters(colorHeight: -1) == nil
               && makeParameters(colorWidth: Int.max) == nil, "All extents are checked")
}

@main
private enum MetalFrameGenerationLifecycleTestMain {
    static func main() {
        let tests: [(String, () throws -> Void)] = [
            ("native scaler-link status", assertScalerLinkStatusContract),
            ("interpolation motion uses previous-color pixels", testInterpolationMotionUsesPreviousColorPixels),
            ("interpolation preserves source time and camera", testInterpolationPreservesRealSourceMetadata),
            ("depth history stale callbacks and reset", testDepthHistoryRejectsStaleCompletions),
            ("depth history replacement and pending source", testDepthHistoryReplacementAndPendingSource),
            ("depth history callback permutations", testDepthHistoryCompletionPermutations),
            ("bounded-input depth/motion pairing", testBoundedInputUsesDepthWinnerMotion),
            ("display-aware source admission", testAdmissionTracksDisplayActivity),
            ("generated then real", testGeneratedThenReal),
            ("GUI suspend and resize", testGuiSuspendAndResizeCancel),
            ("enqueue then shutdown", testEnqueueThenShutdown),
            ("newer source supersedes stalled source", testNewerSourceSupersedesStalledSource),
            ("stale callback preserves newer history", testStaleCallbackCannotInvalidateNewerHistory),
            ("generated submitted shutdown", testGeneratedSubmittedShutdown),
            ("real submitted shutdown", testRealSubmittedShutdown),
            ("command buffer failure", testCommandBufferFailure),
            ("stale display update", testStaleDisplayUpdateDoesNotAdvance),
            ("duplicate callback and idempotent release", testDuplicateCallbackAndIdempotentRelease),
            ("presentedTime zero", testPresentedTimeZeroFails),
            ("presented before GPU completion", testPresentedBeforeGPUCompletion)
        ]
        do {
            for (name, test) in tests {
                try test()
                print("PASS: \(name)")
            }
            print("Metal frame-generation lifecycle tests passed: \(tests.count)")
        } catch {
            fputs("FAIL: \(error)\n", stderr)
            exit(1)
        }
    }
}
