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

Ordinary `CAMetalLayer` presentation telemetry is exported through the Java
bridge when native samples are available: `measuredPresentIntervalNanos`,
`drawableWaitNanos`, and `framesInFlight` retain explicit provenance.  Before
a sample exists, or on an unsupported path, those fields remain JSON `null`
with `available=false` and a precise fallback reason.  This evidence covers
the ordinary CAMetalLayer render/present timeline and intentionally excludes
the separate MetalFX generated-frame timeline.  A missing display refresh
source uses the legacy `16,666,667 ns` value only as
`conservative-60hz-fallback`; it is never marked as measured display timing.

When `metallum.opt.terrainAdaptiveScheduling=true` and warmup has completed,
`TerrainSchedulingController` uses `targetPresentInterval` as the single
frame-budget and CPU/GPU pressure-threshold target.  It never uses the
observed CPU frame duration or measured-present field to expand that target.
An unavailable or malformed target falls back to `16,666,667 ns`, and each
`FrameDecision` plus the optional terrain CSV records the target and source
(`display-derived`, `conservative-fallback`, or `unavailable-fallback`).
The default-disabled and warmup paths keep Sodium's original budget arguments.
This policy wiring does not alter rendering or claim physical VRR/ProMotion
performance. Snapshot objects are refreshed at most once every
`TerrainSchedulingController.PACING_SNAPSHOT_CADENCE_FRAMES` observed frames
(or immediately when refresh rate changes), so active observation does not
allocate a snapshot and six value objects every frame.  The snapshot is available through
`TerrainSchedulingController.FrameInputs.presentationPacing()`. Snapshot
creation occurs only when the existing opt-in terrain observation path is
active; the default disabled path remains a predictable no-op.
