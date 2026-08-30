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

        print("NativePresentationTelemetryStateTest: PASS")
    }
}
