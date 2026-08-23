# Presentation pacing evidence

`PresentationPacingSnapshot` is the Java-side immutable evidence carrier for
future terrain pacing work.  It is sampled alongside the existing terrain
`FrameInputs` and serialized by `PresentationPacingEvidenceAdapter` into the
existing `native-fullscreen-baseline.json` structured report under
`presentationPacing`.  Structured JSON is authoritative; no log line or regex
is used as a source.

The current Java boundary has these sources:

- `targetPresentIntervalNanos` is derived from the Java-visible
  `Minecraft.getWindow().getRefreshRate()` when positive.  The interval uses
  rounded nanoseconds (`60 Hz = 16,666,667`, `90 Hz = 11,111,111`, and
  `120 Hz = 8,333,333`).  Its `...Measured` flag is false and its `...Derived`
  flag is true: a configured refresh rate is not an observed presented
  interval.
- `cpuFrameTimeNanos` is the latest existing `Minecraft.renderFrame` interval.
- `gpuFrameTimeNanos` is the latest completed Metal command-buffer service
  time from `MetalGpuTimingRecorder`.

The native presenter does not currently export a presented timestamp, drawable
wait duration, or dynamic in-flight count through the Java bridge.  Therefore
`measuredPresentIntervalNanos`, `drawableWaitNanos`, and `framesInFlight` are
serialized as JSON `null` with `available=false` and a precise provenance and
fallback reason.  A missing display refresh source uses the legacy
`16,666,667 ns` value only as `conservative-60hz-fallback`; it is never marked
as measured display timing.

This slice does not read the pacing snapshot in any scheduling decision and
does not alter rendering.  Snapshot objects are refreshed at most once every
`TerrainSchedulingController.PACING_SNAPSHOT_CADENCE_FRAMES` observed frames
(or immediately when refresh rate changes), so active observation does not
allocate a snapshot and six value objects every frame.  The snapshot is available through
`TerrainSchedulingController.FrameInputs.presentationPacing()` for a later,
separately reviewed scheduler change.  Snapshot creation occurs only when the
existing opt-in terrain observation path is active; the default disabled path
remains a predictable no-op.
