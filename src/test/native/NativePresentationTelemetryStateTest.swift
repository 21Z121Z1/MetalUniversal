import Foundation

private func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    guard condition() else {
        fputs("FAIL: \(message)\n", stderr)
        exit(1)
    }
}

@main
struct NativePresentationTelemetryStateTest {
    static func main() {
        var state = NativePresentationTelemetryState()
        state.recordDrawableWait(nanos: -1)
        check(state.latestDrawableWaitNanos == -1, "negative drawable wait remains unavailable")
        state.recordDrawableWait(nanos: 0)
        check(state.latestDrawableWaitNanos == 0, "zero drawable wait is a valid immediate observation")

        let metal3ID = state.schedulePresentation()
        check(state.framesInFlight == 1, "Metal 3 schedule increments drawable in-flight count")
        state.recordPresented(metal3ID, presentedTime: 10.0)
        check(state.latestPresentIntervalNanos == -1, "first presented timestamp has no interval")
        check(state.framesInFlight == 0, "Metal 3 presented callback resolves the count")

        let metal4ID = state.schedulePresentation()
        check(state.framesInFlight == 1, "Metal 4 uses the same presentation accounting entry")
        state.recordPresented(metal4ID, presentedTime: 10.016666667)
        check(state.latestPresentIntervalNanos > 16_000_000
                && state.latestPresentIntervalNanos < 17_000_000,
              "second monotonic presented timestamp produces a nanosecond interval")
        check(state.framesInFlight == 0, "Metal 4 presented callback resolves the count")

        let invalidID = state.schedulePresentation()
        let intervalBeforeInvalid = state.latestPresentIntervalNanos
        state.recordPresented(invalidID, presentedTime: 0.0)
        check(state.latestPresentIntervalNanos == intervalBeforeInvalid,
              "zero presented timestamp does not overwrite the latest interval")
        check(state.framesInFlight == 0, "zero timestamp callback still resolves the drawable")

        let outOfOrderID = state.schedulePresentation()
        state.recordPresented(outOfOrderID, presentedTime: 10.008)
        check(state.latestPresentIntervalNanos == intervalBeforeInvalid,
              "out-of-order presented timestamp does not overwrite the latest interval")
        check(state.framesInFlight == 0, "out-of-order callback still resolves the drawable")

        let encodeThenCloseID = state.schedulePresentation()
        check(state.framesInFlight == 1, "encode reserves one pending drawable")
        check(state.resolvePresentation(encodeThenCloseID), "close without commit cancels the pending drawable")
        check(!state.resolvePresentation(encodeThenCloseID), "close cancellation is idempotent with a later callback")
        check(state.framesInFlight == 0, "failed/cancelled drawable cannot make count negative")

        let observed = state.schedulePresentation(recordEvidence: true)
        let cancelled = state.schedulePresentation(recordEvidence: true)
        check(state.presentedTimeEvidence(observed) == 0, "scheduled does not mean presented")
        _ = state.resolvePresentation(cancelled)
        state.recordPresented(cancelled, presentedTime: 20)
        check(state.presentedTimeEvidence(cancelled) == -1, "late callback cannot turn cancellation into presentation")
        state.recordPresented(observed, presentedTime: 19)
        check(state.presentedTimeEvidence(observed) == 19, "callback retains its exact native ticket")
        check(state.presentedTimeEvidence(metal3ID) == -3, "disabled evidence is absent, not zero")
        let invalidObserved = state.schedulePresentation(recordEvidence: true)
        state.recordPresented(invalidObserved, presentedTime: .nan)
        check(state.presentedTimeEvidence(invalidObserved) == -2, "non-finite callback is explicitly unavailable")

        let notPresented = state.schedulePresentation(recordEvidence: true)
        state.recordPresented(notPresented, presentedTime: 0)
        check(state.presentedTimeEvidence(notPresented) == -4,
              "zero callback reports not-presented, not an invalid clock")
        state.recordPresented(notPresented, presentedTime: 21)
        check(state.presentedTimeEvidence(notPresented) == -4,
              "a terminal callback cannot be replaced by a duplicate callback")
        let negativeTime = state.schedulePresentation(recordEvidence: true)
        state.recordPresented(negativeTime, presentedTime: -0.5)
        check(state.presentedTimeEvidence(negativeTime) == -2,
              "negative callback remains an invalid timestamp")
        let infiniteTime = state.schedulePresentation(recordEvidence: true)
        state.recordPresented(infiniteTime, presentedTime: .infinity)
        check(state.presentedTimeEvidence(infiniteTime) == -2,
              "infinite callback remains an invalid timestamp")

        var rolling = NativePresentationTelemetryState()
        let oldestPending = rolling.schedulePresentation(recordEvidence: true)
        let oldestResolved = rolling.schedulePresentation(recordEvidence: true)
        rolling.recordPresented(oldestResolved, presentedTime: 30)
        for index in 2..<NativePresentationTelemetryState.evidenceCapacity {
            let ticket = rolling.schedulePresentation(recordEvidence: true)
            rolling.recordPresented(ticket, presentedTime: 30 + Double(index))
        }
        check(rolling.presentedTimeEvidence(oldestPending) == 0, "capacity boundary retains oldest pending ticket")
        check(rolling.presentedTimeEvidence(oldestResolved) == 30, "capacity boundary retains completed evidence")
        let newestPending = rolling.schedulePresentation(recordEvidence: true)
        check(newestPending > oldestResolved, "rolling retention preserves monotonic ticket identity")
        check(rolling.presentedTimeEvidence(oldestPending) == -3, "overflow evicts oldest pending without claiming presentation")
        check(rolling.presentedTimeEvidence(oldestResolved) == 30, "one overflow evicts exactly one ticket")
        rolling.recordPresented(oldestPending, presentedTime: 29)
        check(rolling.presentedTimeEvidence(oldestPending) == -3, "late evicted callback cannot resurrect evidence")
        check(rolling.framesInFlight == -1, "expired pending identity makes occupancy unavailable, not a fabricated count")
        let newestCancelled = rolling.schedulePresentation(recordEvidence: true)
        check(rolling.presentedTimeEvidence(oldestResolved) == -3, "second overflow evicts second-oldest evidence")
        _ = rolling.resolvePresentation(newestCancelled)
        rolling.recordPresented(newestCancelled, presentedTime: 40)
        rolling.recordPresented(newestPending, presentedTime: 39)
        check(rolling.presentedTimeEvidence(newestCancelled) == -1, "cancelled recent ticket stays cancelled after late callback")
        check(rolling.presentedTimeEvidence(newestPending) == 39, "out-of-order recent callback retains its actual timestamp")
        check(rolling.framesInFlight == -1, "later completion and cancellation cannot recover unknown occupancy")
        // Exercise a complete ring wrap, not just the first eviction boundary.
        for _ in 0..<NativePresentationTelemetryState.evidenceCapacity {
            let ticket = rolling.schedulePresentation(recordEvidence: true)
            _ = rolling.resolvePresentation(ticket)
        }
        check(rolling.presentedTimeEvidence(newestPending) == -3, "second ring cycle expires previously retained receipt")
        let lateSession = rolling.schedulePresentation(recordEvidence: true)
        rolling.recordPresented(lateSession, presentedTime: 100_000)
        check(rolling.presentedTimeEvidence(lateSession) == 100_000, "late-session windows still receive actual callbacks")

        var missingCallbacks = NativePresentationTelemetryState()
        let expired = missingCallbacks.schedulePresentation()
        for _ in 1..<NativePresentationTelemetryState.pendingIdentityHorizon {
            _ = missingCallbacks.schedulePresentation()
        }
        check(missingCallbacks.framesInFlight == Int64(NativePresentationTelemetryState.pendingIdentityHorizon),
              "pending horizon boundary retains exact occupancy")
        let retained = missingCallbacks.schedulePresentation(recordEvidence: true)
        check(missingCallbacks.framesInFlight == -1, "missing callbacks exhaust bounded identity even with observer off")
        check(!missingCallbacks.resolvePresentation(expired), "expired pending identity was actually removed")
        missingCallbacks.recordPresented(expired, presentedTime: 5)
        check(missingCallbacks.latestPresentIntervalNanos == -1, "forgotten callback cannot create interval authority")
        missingCallbacks.recordPresented(retained, presentedTime: 10)
        check(missingCallbacks.presentedTimeEvidence(retained) == 10, "recent actual receipts survive unknown aggregate occupancy")
        let cancelAfterOverflow = missingCallbacks.schedulePresentation()
        _ = missingCallbacks.resolvePresentation(cancelAfterOverflow)
        check(missingCallbacks.framesInFlight == -1, "future schedule/cancel preserves unavailable occupancy")

        print("NativePresentationTelemetryStateTest: PASS")
    }
}
