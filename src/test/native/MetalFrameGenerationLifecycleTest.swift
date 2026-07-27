import Foundation

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
    _ = state.recordPresented(.generated, presentedTime: 1.0)
    _ = state.completeGPUWork(.generated, succeeded: true)
    try expect(state.nextPresentationStep == .real, "real must follow generated completion")
    _ = state.submitPresentation(.real)
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

@main
private enum MetalFrameGenerationLifecycleTestMain {
    static func main() {
        let tests: [(String, () throws -> Void)] = [
            ("generated then real", testGeneratedThenReal),
            ("GUI suspend and resize", testGuiSuspendAndResizeCancel),
            ("enqueue then shutdown", testEnqueueThenShutdown),
            ("newer source supersedes stalled source", testNewerSourceSupersedesStalledSource),
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
