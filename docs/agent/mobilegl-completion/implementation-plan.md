# Implementation plan

This is an execution order, not a list of deferred ideas. Each stage closes the
smallest dependency-complete slice and adds its own tests.

## 1. Evidence and semantic foundations

1. Preserve exact remote/MobileGL/MoltenVK/toolchain identities.
2. Repair evaluation flags, exit-code checks, sample windows and missing metric
   handling before using the harness as evidence.
3. Count every Java-to-native downcall at the MethodHandle boundary with a
   disabled zero-overhead path.
4. Add sample-window pipeline creation telemetry including bounded late
   identities and require `runtimePipelineCompiles=0` for candidates.

Acceptance: unit/self-tests pass; an intentionally missing metric or wrong
profile flag fails admission; measured counters start after warmup.

## 2. Generation-aware semantic resources

1. Add backend generation to buffers and any replaceable texture/program
   backing that lacks it.
2. Replace raw handle caching with immutable resource snapshots containing
   object id, backing generation, handle, range and access.
3. Invalidate compiled binding/ICB entries on backing, program, device or world
   generation change.
4. Tie retirement to the command buffer completion serial.

Acceptance: same-submit orphan/copy/draw, delayed completion, rebuild and unload
tests prove no stale access and no early reuse.

## 3. Typed compiled binding and argument execution

1. Remove reflective layout discovery from the production runtime; use
   `MetalCompiledBindingPlanProvider` and typed slots.
2. Define one versioned native argument snapshot ABI with explicit buffer
   offset, texture, sampler, access and stage fields.
3. Back snapshots with per-submit-slot native storage; apply compact deltas and
   bind the encoded table/buffer once per stage/layout generation.
4. Declare writable and indirect resources resident. Fail before draw when a
   required slot is absent or stale.
5. Retain fine-grained setters only for unsupported/non-admitted paths, with a
   structured reason and counter.

Acceptance: Metal 3 and Metal 4 physical readbacks; no reflection/string lookup
inside admitted draws; argument updates > 0 and dominant native setters fall.

## 4. Pipeline and shader completion

1. Audit pipeline keys against every fixed render state and attachment variant.
2. Track actual render/compute PSO creation identity and phase.
3. Prewarm Sodium terrain, Iris gbuffers/shadow/deferred/composite/final/compute,
   MRT and attachment variants after pack compilation and before sampling.
4. Bind archive identity to shader MSL, layout, device/OS/Metal mode and reject
   stale archives.
5. Extend BSL/Potato/conformance fixtures for any translation or interface gap
   found in runtime.

Acceptance: compilation/key tests, physical MRT/render-compute lanes,
translation counts and zero sampled runtime PSO creation.

## 5. Ordered operations and frame graph

1. Normalize render, compute, clear, copy, blit, mipmap, readback, barrier,
   query/sync and presentation resource uses.
2. Make buffer and attachment identities generation-aware.
3. Compile RAW/WAR/WAW dependencies, load/store, ping-pong and transient
   liveness; fuse only proven-safe passes.
4. Ensure helper encoders reset execution shadows and cannot pollute the next
   pass.

Acceptance: synthetic graph tests plus physical attachment/intermediate
readbacks for every producer transition; first divergent pass is reported.

## 6. Packet completion

1. Keep render/state/compute packet layouts bounded, aligned and ABI versioned.
2. Validate all operations/handles/ranges before executing operation zero.
3. Flush at capacity and semantic encoder boundary.
4. Prove render and compute packet activation on physical GPU, with replay zero.

Acceptance: negative packet corpus, ABI symmetry/exported symbols, render MRT,
render->compute and compute->render readbacks, exact FFM/packet counters.

## 7. Sodium terrain ICB

1. Feed the completed argument layout into terrain command eligibility.
2. Build bounded per-submit-slot ICB storage keyed by pipeline and all resource
   generations; reuse capacity rather than allocating an ICB per batch.
3. Encode SOLID, CUTOUT, CUTOUT_MIPPED and safe TRANSLUCENT ranges with exact
   index type/offset/base vertex/transforms/material/sampler state.
4. Reject incomplete/stale/out-of-order batches atomically and run the compact
   direct packet fallback without skipping work.

Acceptance: physical ICB integration and real-client attempts/accepted/draws >
0 with fallback 0 for qualifying ranges; rebuild, unload and visual correctness.

## 8. Build and client correctness loop

1. Run clean Java/native build, unit/static/GPU/render-contract lanes.
2. Run Sodium-only, Iris+BSL and Iris+Potato fixed-world automated client lanes.
3. Diagnose the first semantic divergent producer via intermediate readback,
   repair, and rerun all affected lanes.
4. Cover resize/fullscreen, shader reload/off/on, world reload, dimension switch
   and normal exit with exact process exit status.

Acceptance: no missing evidence, Metal API/GPU validation clean, all listed
scene components and lifecycle actions pass. Human-only scanout judgments remain
explicit until actually observed; internal readback is not mislabeled as
WindowServer proof.

## 9. Differential and performance acceptance

1. Build/run the exact MobileGL commit against the exact local MoltenVK binary.
2. Freeze identical O/V/M versions, world, camera, display, render/simulation
   distance, shader settings, JVM, power and quality configuration.
3. Compare starting MetalUniversal B and candidate C in at least four
   interleaved 30-second-warmup/120-second-sample blocks; then run V.
4. Reject any candidate with correctness/quality/activation regression or
   runtime pipeline compile.

Acceptance: one target metric improves in at least 75% of paired blocks and its
paired median improves; p99/long-frame/memory/GC/correctness guardrails do not
regress.

## 10. Delivery

1. Convert evidence to stable Markdown/JSON fixtures only; exclude runtime
   caches, worlds, packs, dylibs, captures and traces.
2. Commit logical source/test/document slices.
3. Fetch target remote; rebase if it advanced; rerun affected and final gates.
4. Push fast-forward only to `feature/mobilegl-inspired-hotpath` without force.
5. Verify local/remote HEAD equality and a clean task worktree; stop
   `caffeinate`.

No stage is considered complete merely because its code compiles or a switch
exists. If an external gate remains impossible after all code-side work, the
final state is `PARTIAL — external environment gate missing`, not `PASS`.
