# Iris Metal frame-stability foundation

Target base: `integration/iris-metal-next`

This batch implements the first executable part of the advanced optimization plan: unified stutter attribution and deadline-oriented admission for non-critical terrain work. All new runtime behavior is opt-in. It does not re-enable pass fusion, compute grouping, depth allocation pruning, or argument snapshot tracking.

## Why this precedes more graph optimization

The supplied Codex rollout showed that the existing advanced lanes did not establish a performance win:

- BSL render-pass fusion produced thousands of candidates but no admitted merge;
- compute grouping produced no admitted group;
- argument snapshot tracking was active but added work instead of replacing native setters;
- depth liveness skipped captures but did not prove allocation pruning;
- the short BSL comparison regressed FPS, CPU render time, GPU time, encoder counts, and stutter;
- no candidate completed the formal 30 second warmup, 120 second sample, and repeated paired acceptance run.

The correct next step is therefore to identify the source of each long frame before changing shader-visible execution.

## Implemented

### Unified frame recorder

`FrameStutterRecorder` joins these sources into one bounded frame sample:

- client frame begin/end;
- latest completed Metal command-buffer GPU duration;
- full `MetalCommandEncoder.submit` duration, including any in-flight-slot wait;
- exact invocation count and duration of `MetalDevice.compileWithIrisOverride`;
- background PSO prewarm invocation count;
- Java GC collection-time delta;
- Sodium chunk build submission duration/count;
- Sodium chunk upload duration/result count;
- display target/deadline source and miss status.

The summary emits:

- frame-time p50, p95, p99, and p99.9;
- approximate 1% and 0.1% low FPS from p99 and p99.9;
- frames above two times the rolling median;
- frames above 33.3 ms, 50 ms, and 100 ms;
- missed display deadlines and maximum consecutive misses;
- pipeline compile count, total duration, longest duration, and background count;
- command-submit count and total duration.

Every frame receives one primary cause. The classifier is intentionally conservative and does not invent unavailable evidence.

### Structured report

Set both properties:

```text
-Dmetallum.validation.frameStutter=true
-Dmetallum.validation.frameStutterReport=/absolute/path/frame-stutter.json
```

The report explicitly identifies unavailable metrics. In this batch those include exact drawable acquisition time, exact semaphore/queue wait separated from the rest of submit, FFM call count, allocation count/bytes, process/Metal resident memory, attachment store/load bytes, and translucent-sort duration.

### Frame-budget controller

Enable with:

```text
-Dmetallum.opt.frameBudget=true
-Dmetallum.opt.frameBudget.mode=STABLE
-Dmetallum.opt.frameBudget.targetHz=60
```

Available modes:

```text
LOW_LATENCY
STABLE
```

The controller predicts core render cost with an EWMA, subtracts it and a safety margin from the current display opportunity, and clamps Sodium build/upload duration budgets. It reserves the admitted build budget before calculating upload allowance so both stages cannot independently consume the same slack.

The previous terrain controller used the previous CPU frame duration as the next frame's budget basis. A slow frame therefore increased the nominal chunk budget. When the new controller is enabled, the configured display cadence is used instead; overload drives background work toward zero.

### Deferred work queue

`DeferredRenderWorkQueue` is a bounded priority queue for later pipeline warmup, maintenance, or validation work. It is implemented and unit-tested but is not yet connected to PSO creation. Connecting it before the required/optional pipeline manifest exists would risk delaying a required pipeline until first visible use.

## Important deadline limitation

The current ordinary Iris presentation path does not own a `CAMetalDisplayLink`. The repository's existing `CAMetalDisplayLink` belongs exclusively to the MetalFX frame-generation presenter and owns its drawable/present sequence. Attaching a second passive display link to the same layer would compete for drawables and is not an acceptable shortcut.

Consequently this batch records `ESTIMATED_CADENCE` for ordinary Iris frames. The JSON report labels that evidence `estimated-cadence-not-display-link`. `DisplayDeadlineSnapshot.Source.METAL_DISPLAY_LINK` and `observeDisplayDeadline` are present for the later presentation-owner refactor, but no estimated sample is reported as genuine display-link evidence.

The next P2 step must make one presentation owner provide `targetTimestamp` and `targetPresentationTimestamp` to Java, rather than starting a second display link.

## Existing PSO archive status

This branch already has:

- Metal 3 `MTLBinaryArchive` open, descriptor lookup, harvesting, and serialization;
- Metal 4 lookup archive and pipeline-data serializer;
- a single-thread async prewarm executor behind `metallum.opt.asyncPrecompile`;
- one compile funnel used by native, Iris override, synchronous, and background paths.

This batch instruments that funnel instead of adding a duplicate archive. It does not yet claim the full P1 contract: the archive path is still process-global rather than keyed by shader-pack content hash and complete pipeline ABI dimensions, required/optional Iris manifests are not generated, and archive hits/misses are not independently proven with fail-on-miss validation.

## Test commands

Headless/unit gate:

```bash
./gradlew --no-daemon test
bash scripts/agent/verify_unified_eval.sh
```

Local Apple Silicon runtime gate:

```bash
./gradlew --no-daemon runClientIris \
  -Dmetallum.validation.frameStutter=true \
  -Dmetallum.validation.frameStutterReport="$PWD/build/agent-runs/frame-stutter.json" \
  -Dmetallum.opt.frameBudget=true \
  -Dmetallum.opt.frameBudget.mode=STABLE \
  -Dmetallum.opt.frameBudget.targetHz=60 \
  -Pworld="<world>"
```

Formal acceptance still requires the repository's conformance gate and repeated interleaved baseline/candidate trials. Compilation and unit tests do not establish FPS or stutter improvement.

## Next implementation order

1. Add exact queue-wait and drawable-acquire native timestamps plus FFM/allocation counters.
2. Integrate recorder JSON into unified evaluation normalization and admission.
3. Generate an Iris pipeline manifest and distinguish required from optional PSOs.
4. Key archives by shader-pack content and complete pipeline ABI; validate required hits.
5. Refactor ordinary presentation to one `CAMetalDisplayLink` owner and feed real deadlines to the controller.
6. Only after those gates, compile the immutable Iris frame graph and remove runtime reflection analysis.
