# Vanilla 26.3 world-stage telemetry

This is bounded diagnostic instrumentation for the vanilla Minecraft 26.3
client and its integrated server. It records where packet, chunk, light, and
server work occurs during an opted-in client run. It is not a performance
measurement source and it does not prove complete world-generation or light
work coverage.

The emitted recorder report uses schema 2. A schema-1 report is an older
warmup-inclusive process observation and must not be interpreted as schema-2
window evidence.

## Activation and output

The instrumentation is disabled by default. Set the JVM property
`metallum.validation.worldStages=true` for a client run. The mixin config lists
the five world mixins in its `client` section, and
`MetallumMixinConfigPlugin.shouldApplyMixin` additionally requires macOS. With
the property absent, these mixins are not applied and the validation client
does not write a stage report.

The report is written at the end of the validation run as
`world-stages.json` below `metallum.validation.output`. Its report envelope is
`WorldStageRecorder.Report`:

```text
schemaVersion=2
evidenceClass=diagnostic
performanceEligible=false
scope=measurement-window | process-observation-including-warmup
clock=System.nanoTime
minecraftVersion=26.3
sourceSha, trialId, status
measurementLimits
snapshot
```

The schema-2 `measurementLimits` value is
`bounded begin/end observations; active calls retained at snapshot; nested
wall-clock intervals are inclusive`.

The schema-2 `Snapshot` fields are:
`schemaVersion`, `eventCapacity`, `eventCount`, `droppedEvents`,
`invalidEvents`, `contextOverflowEvents`, `liveContextCount`,
`startedInvocations`, `finishedInvocations`, `activeOverflowEvents`,
`activeInvocations`, `events`, `window`, and `timestampOffsetNanos`.

Each `Event` contains `sequence`, `invocationId`, `stage`, `contextId`,
`threadId`, `startOffsetNanos`, `endOffsetNanos`, `completed`, `queueBefore`,
`queueAfter`, `workCount`, and `resultCode`. Each `ActiveInvocation` contains
`invocationId`, `stage`, `contextId`, `threadId`, `startOffsetNanos`, and
`queueBefore`. `Window` contains `id`, `startFrameInclusive`,
`endFrameExclusive`, `startOffsetNanos`, `endOffsetNanos`, `activeAtStart`,
and `closed`.

The window and active-call invariants are:

- A `START`/`COMPLETE` begin/end token pair defines one fixed baseline window.
  The window is fixed under the recorder lock and is not inferred from report
  serialization time.
- Completed warmup rows are cleared at `START`. An invocation that crosses the
  boundary remains represented as an active/pending call and is clipped by the
  oracle to the intersection with the fixed window.
- After `endWindow` completes, the report snapshot is frozen. Calls still
  active at that close are represented in `activeInvocations` as pending/right-
  censored state and are not silently counted as finished rows. Before
  `endWindow`, `snapshot()` is a live snapshot.
- For valid closed-window accounting, the conservation identities are
  `activeAtStart + started = finished + active` and
  `finished = rows + dropped`.

The serialized raw offsets and `timestampOffsetNanos` remain available for audit.
Loss or invalid-event counters reject diagnostic integrity. An unfinished call
is retained explicitly; it does not invalidate the accounting, but its full
duration and eventual result remain unavailable. A five-second sample may legitimately contain no
`CHUNK_INSTALL` event when chunk installation finished during warmup; that
stage must be reported as unobserved rather than treated as zero work.

A repository client task can enable it with the same output and run identity
used by the existing automation, for example:

```bash
./gradlew --no-daemon minecraftNativeRenderEfficiencyValidation \
  -Pworld="$WORLD" \
  -Dmetallum.validation.worldStages=true \
  -Dmetallum.validation.output="$OUT" \
  -Dmetallum.renderContract.runId="$RUN_ID" \
  -Dmetallum.validation.sourceCommit="$(git rev-parse HEAD)"
```

`metallum.validation.worldStageCapacity` optionally selects the bounded event
capacity from 1 through 65,536. The default is 16,384. Explicit blank, non-integer, or out-of-range values fail initialization;
only an absent property selects the default.

## Instrumented stages

The five mixins cover eight stage kinds:

| Stage | Hook and scope | `workCount` / `resultCode` |
| --- | --- | --- |
| `CLIENT_PACKETS` | `Minecraft.runTick` around its one `PacketProcessor.processQueuedPackets()` call | both unavailable (`-1`) |
| `CHUNK_INSTALL` | `ClientChunkCache.replaceWithPacketData(int,int,ClientboundLevelChunkPacketData)` | attempt is `1` even on failure; result is `1` for a non-null chunk, `0` for a completed null return, `-1` on failure |
| `LIGHT_ENQUEUE` | `ClientLevel.queueLightUpdate(Runnable)` | completed enqueue is `1`, failed enqueue is `0`; result unavailable |
| `LIGHT_POLL` | complete `ClientLevel.pollLightUpdates()` invocation | work and result unavailable |
| `LIGHT_TASK` | each `Runnable.run()` invoked by `pollLightUpdates` | task attempt is `1` even on failure; result unavailable |
| `LIGHT_UPDATE` | complete `ClientLevel.update()` invocation | work and result unavailable |
| `SERVER_PACKETS` | the packet-processing call in `MinecraftServer.processPacketsAndTick`; recorded only when the receiver is an `IntegratedServer` | both unavailable |
| `SERVER_TICK` | `IntegratedServer.tickServer(BooleanSupplier)` | tick attempt is `1`; `resultCode` is the observed paused flag (`1` paused, `0` running), while `completed` separately reports normal return |

`queueBefore` and `queueAfter` are light-queue sizes where the hook has that
queue. They are `-1` otherwise. `LIGHT_POLL` deliberately does not report a
task count: callbacks can enqueue work reentrantly, so a queue-size delta is
not a task count.

The packet and server wrappers call their original operation exactly once.
The chunk, enqueue, poll, task, update, and server-tick wrappers all record in
`finally`; an exception is rethrown unchanged after a failed-exit event is
recorded. A call still in progress when a snapshot is taken has not reached
its post-invocation `finally` record and may be absent from that snapshot.
The begin/end bookkeeping is admission under the recorder lock only; it does
not claim that a strict physical CPU interval was executing while the lock was
held. Enabling the recorder must not alter vanilla queue policy, packet policy,
light policy, or server tick behavior.

## Intervals and identity

Each event uses raw `System.nanoTime` offsets from the recorder origin and
includes `sequence`, `contextId`, `threadId`, begin/end offsets, completion,
queue gauges, `workCount`, and `resultCode`. The timestamp is captured before
recorder-lock bookkeeping can wait; it therefore describes the supplied
operation timestamp, not a strict physical CPU-execution interval.

The stages are inclusive and can nest. For example, `LIGHT_UPDATE` contains
the vanilla `pollLightUpdates` call, and `LIGHT_POLL` can contain multiple
`LIGHT_TASK` events. Client packet processing can contain chunk installation
and light enqueue work. Do not add durations across stage kinds or interpret
their sum as main-thread time.

`contextId` is a weak-identity ID for the receiver object (`ClientLevel`,
`ClientChunkCache.level`, `Minecraft`, or the server). It uses object identity,
not a world epoch, dimension generation, chunk generation, or task identity.
ID zero means null or context-registry overflow. The fixed context registry
has capacity 128; overflow increments `contextOverflowEvents` and
`invalidEvents`.

## Capacity and evidence limits

The recorder uses fixed primitive arrays and does not grow its event buffer.
The active-call table has 256 primitive slots. When row or active capacity is
reached, later observations are dropped and the corresponding loss counter is
retained; the conservation identities expose the loss. Invalid timestamps,
gauges, sequence state, or overflowed arithmetic increment `invalidEvents`. A
consumer must treat any nonzero dropped, invalid, or unresolved-active count as
incomplete evidence and fail closed for a claim requiring a complete trace;
the runtime stage wrappers continue to preserve vanilla control flow.

Window duration is computed by the oracle from the intersection of each raw
begin/end interval with the fixed measurement window. It must distinguish a
fully observed interval from one clipped at `START`, `COMPLETE`, or snapshot
drain. Nested intervals remain inclusive: intersected durations across stage
kinds still must not be added together.

Outside the baseline window this telemetry is a process observation including
warmup. Both modes are diagnostic only. It does not identify
world epochs or generations, does not establish that every packet/light task
was observed after truncation, and does not establish atomicity or ownership
between world work and renderer publication. `performanceEligible` is always
false. It must not be used as CPU/GPU frame time, as a complete light backlog
metric, or as a P0 performance-acceptance gate.


## Independent validation

Run `python3 scripts/agent/verify_world_stages.py "$OUT/world-stages.json"`.
The checker rejects malformed stage-specific gauges, missing fields, invalid
identities, nonzero loss counters, and failed validation status. Optional
`--require-stage LIGHT_TASK` checks explicit activation; absent stages remain
unobserved. Its `observedWorkCount` sums declared observations/attempts, not
successful tasks. Per-stage timings are inclusive wall-clock durations, not
CPU utilization; observer overhead has not been isolated. `SERVER_TICK`
includes paused invocations, which must be separated using the raw result
flag before interpreting server simulation cost. Accepted diagnostic integrity
does not close whole P0 or any paired performance gate.

The baseline numeric window ID is serialized as its decimal string in this
report; frame bounds must also match the baseline measurement manifest.
