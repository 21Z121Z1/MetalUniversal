# MobileGL-inspired hot path: current status

Branch: `feature/mobilegl-inspired-hotpath`

Base: `integration/iris-metal-next`

This file overrides the phase-status language in older planning sections. The
architecture document remains useful for rationale and prior layers, but the
current implementation/debug authority is:

- `mobilegl-inspired-hotpath-redesign.md` — original architecture and completed
  state-shadow/token/frame-arena work;
- `mobilegl-command-packets-terrain-icb-agent-guide.md` — authoritative guide for
  render command packets, compute command packets, debugging, validation, and
  agent handoff;
- `mobilegl-terrain-icb-resource-contract-addendum.md` — authoritative override
  for terrain-ICB enablement, direct texture/sampler risk, and corrected ICB
  validation lanes.

## Implemented but not yet validated

- ordered render state + draw command packet, feature ID 4;
- ordered compute state + dispatch command packet, feature ID 5;
- Metal 3 Sodium terrain indexed-draw ICB pilot, feature ID 6;
- full-packet native prevalidation;
- fail-stop behavior for ambiguous draw/dispatch execution;
- legacy replay only for proven zero-execution rejection;
- render validation/debug boundaries;
- compute fence/end boundaries;
- packet/ICB telemetry and periodic log reports;
- Java packet-layout and terrain-scope tests;
- default-off Mixin gating for render packet and terrain scope;
- a second default-off `terrainIcbDirectResourceProbe` gate, required before the
  current direct texture/sampler terrain path may execute ICB commands.

## Local validation snapshot (2026-08-04)

The latest remote implementation tip tested for this snapshot was
`9c772878f54438d816c2e97021299b155fcb88d2` after the branch's fast-forwarded
Metal 4 merge. The following evidence was collected in an isolated worktree
on an Apple M1 Pro with the repository's native validation and lifecycle gates
enabled:

- `./gradlew clean test --stacktrace` passed (383 tests), including the packet,
  native-interface, terrain-scope, state-shadow, destruction-queue, and Mixin
  registration tests.
- `./gradlew buildMacNative --stacktrace` passed. `buildIOSNative` also passed
  as part of the build graph. The required packet/ICB C exports are present in
  `libmetallum.dylib`: interface table, render-state packet, render-command
  packet, compute-command packet, and terrain-ICB encoder.
- `./gradlew clean buildMacNative build --stacktrace` passed at the latest tip
  (`29 actionable tasks`), including `compileJava`, `buildIOSNative`,
  `metal4ApiProbe`, `metalComputeBackendIntegrationTest`, frame-generation
  lifecycle/presentation, offscreen/readback, MetalFX performance validation,
  `metalIrisTargetsIntegrationTest`, `metalMrtBackendIntegrationTest`,
  `metalMrtSmokeTest`, `test`, and the production build checks. The real
  presentation gate reported `real=60 generated=60` with no shutdown failure.
- The full clean test no longer loads a second shipped native image after the
  production bridge has initialized; the earlier Objective-C duplicate-class
  warning was isolated to test-side `libraryLookup` calls and is absent after
  the loader tests reuse the existing image.
- The local ignored runtime fixture contains BSL (`SHA-256
  185774628b5259c36255183fc1adeb0f64f89235f7ea2c826fa327d1112687a8`) and
  Potato (`SHA-256
  55aa21562dbc2860fd466719908437a8bc22ad358a673fb3c119e4bcdf1616af`)
  shaderpacks. `./gradlew metalIrisShaderTranslationTest --stacktrace`
  translated BSL 52/52 and Potato 44/44 stages successfully, and both packs
  produced SOLID/CUTOUT/TRANSLUCENT Sodium terrain PSOs.
- Default-off, R0/R1/R2/R3, C0/C1, and I0/I1 `runClientIris` smoke lanes reached
  the copied `New World` and produced hotpath telemetry without hotpath Mixin,
  native-bridge, partial-execution, or command-buffer errors. R2 and R3
  produced render-command packet calls and operations with zero command-packet
  replays; R2 produced zero state-only packet calls, preserving packet
  mutual-exclusion.
- With BSL selected, C0 and C1 both reached the semantic terrain path, but
  both reported `computeForwarded=0` and
  `computeCommandPacketCalls=0`; this workload did not create a compute
  encoder/dispatch, so C1 compute correctness remains unexercised rather than
  being treated as a failure.
- `./gradlew --rerun-tasks metalComputeBackendIntegrationTest --stacktrace
  -Dmetallum.opt.computeCommandPacket=true
  -Dmetallum.opt.computeCommandPacketMinOperations=1` passed with Metal API and
  GPU validation enabled. The real-M1-Pro suite ran 11 tests covering absolute,
  relative, and indirect dispatch, compute-to-compute ordering, storage-image
  readback, and render-to-compute visibility while the compute-packet gate was
  requested. This validates the backend workload path; it does not turn the
  BSL client smoke lane's absent-dispatch result into a shaderpack telemetry
  claim.
- `./gradlew --rerun-tasks metalIrisTargetsIntegrationTest --stacktrace`
  passed 17 real-M1-Pro offscreen/readback tests with Metal API and GPU
  validation enabled, including shadow targets, ping-pong/depth/resize, and
  shadow-composite compute publication. Both
  `metalMrtBackendIntegrationTest` baseline (packet switches off) and candidate
  (`renderStatePacket=true`, `renderCommandPacket=true`, minimum operations 1)
  passed 16 readback tests. The candidate log's uint4/RGBA8 pipeline messages
  came from the intentional `fragmentOutputFormatMismatchFailsClosed` test;
  the XML result had zero failures and errors.
- With BSL selected, I0 and I1 reached the semantic terrain path and reported
  `multiDrawBatches=0`, `terrainIcbAttempts=0`, `terrainIcbAccepted=0`, and
  `terrainIcbFallbacks=0`. I1 therefore verified the closed direct-resource
  gate, but did not admit an ICB because this scene emitted no qualifying
  ordinary indexed multi-draw.
- On the latest tip, the explicit compute backend gate with
  `-Dmetallum.opt.computeCommandPacket=true` and the packet-enabled MRT
  readback gate both passed with Metal API and GPU validation enabled.
- The MetalFX-OFF native fullscreen validation entry produced real 3024x1734
  runs for R0 and R2. Both 256x256 native main-render readbacks passed their
  non-zero/varying-pixel validity checks and both reported zero packet replay;
  R2 reported `renderCommandPacketCalls=5593` and
  `renderCommandOperations=278888` in its final telemetry report. The R0
  readback checksum was `8c356fb2ef5582d1`, R2 was `1b1a07e0b5eb56fb`, and a
  repeated R0 was `a7b1aa59de5fc6a8`; the repeated-R0 difference means these
  single readbacks are not a deterministic framebuffer-equivalence oracle.
- The dedicated `renderContractMinecraftValidation` task reached the real
  Metal world and completed all 17 GPU readbacks, but fail-closed on the
  pre-existing `hand_translucent_motion_series` MetalFX gate (`passed=false`,
  two failures). That task currently overwrites an explicitly requested
  MetalFX-OFF mode with TEMPORAL, so it is not usable as a packet-only
  render-contract oracle in this snapshot. No packet or ICB error caused this
  failure.

The earlier locked-console presentation attempt is retained in the local
terminal history as an environment-blocked attempt; after the console was
unlocked, the same full build completed successfully. No validation switch
was weakened to obtain that pass.

## Still unproven

No claim is made yet for:

- deterministic framebuffer/image hash equivalence or Iris semantic
  equivalence for the R lanes; the native readback is a validity signal, not a
  stable baseline/candidate comparison;
- compute dispatch correctness: the BSL semantic workload created no compute
  encoder/dispatch, so C1 did not exercise packet execution;
- terrain ICB correctness: I2 was not run because the direct texture/sampler
  resource contract is incomplete and the BSL scene emitted no qualifying
  ordinary indexed multi-draw;
- physical-GPU ICB validation, sustained visual acceptance, or the prescribed
  three-run 30-second/120-second interleaved hotpath performance protocol;
- FPS, CPU encode, GPU time, frame-time tails, allocation, GC, or FFM crossing
  improvements attributable to the packet path. The short R0/R2 native
  validation samples are diagnostic only; they are not a performance
  admission.

Terrain ICB and command-packet experiments remain default-off. Follow the guide
and addendum for any future I2 or performance work; do not treat this snapshot
as feature-completion evidence.
