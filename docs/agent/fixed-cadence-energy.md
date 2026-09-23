# Fixed cadence energy observations

`fixed-cadence-energy-v1` extends the existing frame trial runner with measured power integration. It preserves the source frame recorder, trial identities, native output, Fabulous quality, render distance 32 and simulation behavior. Generated or presented frame counts never divide energy. A CPU/GPU/SoC measurement remains that domain; only `wall-system` is whole-system energy.

The registered profile lives in `unified-evaluation-acceptance.json`. `fixed_cadence_energy.py` implements it and `test_frame_energy.py` checks the registered and executable thresholds agree. No experiment is enabled by default.

## Policy and candidates

The existing Minecraft `FramerateLimiter.limitDisplayFPS` remains the pacing actuator. Set `-Dmetallum.pacing.enabled=true` and one or more of `metallum.pacing.targetFps`, `backgroundFps`, `minimizedFps`, `idleFps`, `batteryFps` (each prefixed `metallum.pacing.`). Values are 0 to inherit or 1–259. `idleAfterSeconds` defaults to 60. Configured states can lower a target but never raise an existing lower Minecraft cap. Battery rules read SDL only when explicitly configured; unknown power state does not invent a battery transition. Dynamic FPS delegates policy ownership. These settings never change simulation ticks.

Frame context records the requested/effective target, state and owner. Window visibility/size and explicit surface reconfiguration reset temporal history and advance the frame epoch. Native presentation remains under the existing ordinary/MetalFX ownership contract.

The workload/trial runners expose independent `--dynamic-upload-range-copy`, `--native-multi-draw-batch`, `--async-precompile` and `--pacing-policy` flags, alongside existing encoder reuse and terrain slice cache. Campaign flags use `--a-`/`--b-` prefixes. Every campaign may change one artifact, backend or actuator dimension. Startup-to-gameplay counters prove activation only; they do not measure sample-window CPU time or energy.

## Instrument input

Use an actual instrument/exporter producing [the v1 schema](fixed-cadence-power-trace.schema.json). The runner does not start a privileged sampler or estimate watts from utilization/battery percentage. Preserve the raw instrument export and clock-correlation evidence alongside the trial.

- `trialId` and `artifact` equal `trial-manifest.json`; `sourceWindow` copies only `clock`, `startNs`, `endNs` from `observation.sourceSampleWindow`.
- Declare instrument identity/version/method, measurement domain, watts, and measured authority.
- Correlate instrument time to Java `System.nanoTime` with documented maximum error at most 1 ms. A wall-clock anchor alone does not prove this bound.
- Export interval-mean watts over contiguous intervals at most one second long, covering the exact half-open source window. Gaps, overlap, NaN, negative power, guessed endpoint fractions and extrapolation are rejected. If the source cannot provide exact window boundary energy, retain `unavailable`.
- Supply `--power-trace /absolute/export.json` to a single workload. For a campaign, `--power-trace-dir` contains `trial-0001-A.json`, `trial-0002-B.json`, etc.; the completed export must exist when that trial exits. The imported bytes and normalized `energy.json` join the immutable trial inventory. Missing exports produce explicit unavailable observations.

The same machine/display/toolchain/power policy, binary/flag declaration and workload rules apply to both arms. Run matched A/A before A/B. At least 30 seconds warmup, 120 seconds sample and four ABBA/BAAB paired blocks are required for a directional A/B result. Both arms must sustain 98% of target; source FPS and P99 have 2% relative regression limits. Zero change is not improvement; the paired median must improve and at least 75% of blocks must improve.

`energy-direction-passed` reports measured-domain direction only. It cannot promote a candidate: render correctness, real activation, matched A/A noise, and all existing CPU/GPU/memory/stutter guardrails remain mandatory. Missing power or incomplete presentation evidence reports `unavailable`; no failed trial may be filtered or repeated until it passes.

## Commands

Use a clean exact packaged JAR and an immutable world snapshot created by the existing bootstrap runner. The paths below are caller-supplied inputs, not bundled fixtures.

```bash
python3 scripts/agent/run_frame_trials.py --protocol aa --blocks 2 \
  --a-jar "$CANDIDATE_JAR" --initial-world "$WORLD_SNAPSHOT" --workload P0 \
  --a-backend metal3 --energy-profile fixed-cadence-energy-v1 \
  --power-trace-dir "$INSTRUMENT_EXPORTS" --target-fps 60 \
  --output build/agent-runs/efficiency-energy-aa

python3 scripts/agent/run_frame_trials.py --protocol abba --blocks 4 \
  --a-jar "$CANDIDATE_JAR" --initial-world "$WORLD_SNAPSHOT" --workload G0 \
  --a-backend metal3 --b-dynamic-upload-range-copy \
  --energy-profile fixed-cadence-energy-v1 --power-trace-dir "$INSTRUMENT_EXPORTS" \
  --target-fps 60 --output build/agent-runs/efficiency-upload-energy-abba

python3 scripts/agent/run_frame_trials.py --verify build/agent-runs/efficiency-upload-energy-abba
```

If the workload produces no partial dynamic upload, admission fails; choose a workload from observed producer evidence before declaring another campaign. Do not reinterpret zero counter values as success.

For correctness/readback only, use `renderContractMetal3NativeTest` / `renderContractMetal4NativeTest` with `-Pmetallum.efficiencyProfile=baseline`, `reuse-encoder-state`, `dynamic-upload-range-copy`, `async-precompile` or `native-multi-draw-batch`. These runs enable Metal validation and are separate from performance trials.
