# MetalFX production-gate handoff - 2026-07-27

## 1. Start here

The complete advanced implementation is already on GitHub `master`:

```text
repository: https://github.com/21Z121Z1/MetalUniversal
remote master: 11bf3964485860ea9b12c804990511b7085307a2
implementation commit: e6e74359122504716c1b5253d7957f44cea28f64
local worktree: repository root
local branch: claude/framegen-comparison
```

`11bf396` has two parents: the advanced implementation line and the previous
GitHub `master`. It was pushed as a normal fast-forward, not a force push. The
local and remote tree IDs were both verified as
`97444f2af759b8c15fa5a81cd50a1592065bfd6d`.

Do not replace this tree with the smaller upstream experiment at `6de5bd9`.
That branch fixes a Spatial no-op but does not contain this line's complete
Temporal inputs, native-resolution GUI composition, motion/reactive/history
pipeline, presenter, or validation infrastructure. `MetalUniversal-iris` is a
separate integration line and is not part of this handoff.

## 2. What is already complete

The live tree contains:

- real low-resolution scene color/depth and full-resolution MetalFX output;
- Temporal Halton jitter, unjittered current-to-previous motion, reversed-Z,
  history reset, camera/object motion, disocclusion and reactive masks;
- GUI/HUD composition after Temporal at native resolution;
- `MTLFXFrameInterpolator`, display-link presentation, lifecycle reducer,
  suspension/resume behavior and runtime kill switch;
- ordinary entity/item/block motion families plus category-specific root
  transforms for living entities, dropped items, boats, arrows and both
  minecart render behaviors;
- Java, MRT, native offscreen, presentation timeline and real Minecraft GPU
  readback validation.

Latest local evidence on Apple M1 Pro with Metal API Validation enabled:

```text
./gradlew test buildMacNative metalFxOffscreenValidation --no-daemon
BUILD SUCCESSFUL
MetalFX offscreen validation: 8/8 scenarios passed

./gradlew minecraftMetalFxClientValidation --no-daemon
BUILD SUCCESSFUL
MetalFX client validation: PASS (16/16 GPU readbacks, 0 failed)
```

The second command produced
`build/metal-validation/minecraft-client-current/run-state.json` with
`completedGpuCaptures=16`, `failedGpuCaptures=0`, and `status=passed`.

The final Temporal contract audit and both validation receipts are recorded in
`docs/metalfx-temporal-upscaling.md`.

## 3. Remaining gate A: GitHub Actions has not started

At handoff time:

```text
workflow: .github/workflows/build.yml
workflow id: 321158492
workflow state: active
Actions permissions: enabled, allowed_actions=all
repository Actions runs: 0
```

The push to `master` succeeded, but no push run appeared. First refresh rather
than assuming failure:

```bash
gh run list --repo 21Z121Z1/MetalUniversal --branch master --limit 10
gh api repos/21Z121Z1/MetalUniversal/actions/runs \
  --jq '{total_count, runs: [.workflow_runs[] | {id,status,conclusion,head_sha,event,html_url}]}'
```

If it is still empty, explicitly dispatch the active workflow and wait for it:

```bash
gh workflow run build.yml --repo 21Z121Z1/MetalUniversal --ref master
gh run list --repo 21Z121Z1/MetalUniversal --workflow build.yml --limit 5
gh run watch --repo 21Z121Z1/MetalUniversal <run-id> --exit-status
```

If the run fails, inspect its logs and fix the actual CI issue on top of
`master`; do not weaken or remove the local Metal validation gates to make CI
green.

## 4. Remaining gate B: production Frame Generation stays closed

The current source intentionally contains:

```java
private static final boolean OBJECT_MOTION_PRODUCER_CONNECTED = false;
```

Location:
`src/main/java/com/metallum/client/metal/render/MetalFxManager.java`.

This is the explicit source-level production gate; runtime activation also
requires Temporal mode and device support. Do not flip it merely because the
presenter exists or automated tests pass. The override exists specifically for
attended acceptance without changing shipped behavior:

```bash
./gradlew minecraftMetalFxClientValidation \
  -Dmetallum.metalfx.objectMotionProducer=true \
  -Dmetallum.metalfx.frameGeneration=true
```

Before the attended run, use Java 25 and confirm the client is on Metal:

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home
grep -E 'startedCleanly|preferredGraphicsBackend' run/options.txt
```

`preferredGraphicsBackend` must be `default`. Remove the old `latest.log` and
enable presentation diagnostics for low-source-rate cases:

```bash
rm run/logs/latest.log
export METALLUM_METALFX_PRESENT_DIAGNOSTICS=1
```

Use the authoritative attended checklist at:

```text
MinecraftMetal_FrameGen_Attended_QA_Checklist_2026-07-27.md (workspace-level, not committed here)
```

The required matrix is 60 Hz, 120 Hz and VRR against 30/40/60 FPS source
rates. For each applicable cell inspect camera motion, covered object classes,
foliage/water/glass/particles/weather/clouds, GUI open/close, resize,
fullscreen, Retina backing and cross-display migration. The four facts that
automation cannot supply are perceived smoothness, scanout tearing, VRR
behavior and display migration.

Judge the production gate strictly for the covered root-motion classes:
living entities, dropped items, old/new minecarts, boats, arrows and tridents.
Known gaps such as first-person articulated motion, block-entity rotation and
model-internal limb animation must be recorded accurately; do not relabel them
as covered, copy unrelated motion, or hide them with a silent fallback.

## 5. Completion sequence

Only after the attended matrix passes:

1. Change `OBJECT_MOTION_PRODUCER_CONNECTED` to `true`.
2. Keep `metallum.metalfx.frameGeneration` as the runtime kill switch.
3. Rerun unit/native/offscreen validation and the real Minecraft client gate
   with Frame Generation enabled.
4. Update the status at the top of `docs/metalfx-frame-generation.md` and the
   final decision in `docs/metalfx-final-acceptance-2026-07-26.md` with exact
   display/source-rate evidence.
5. Commit and push the result to `21Z121Z1/MetalUniversal` `master`, then verify
   the remote SHA and GitHub Actions conclusion.

If any attended cell fails, leave the constant `false`. Record the exact
display refresh, source FPS, content, visible defect, present diagnostic
pattern and whether disabling Frame Generation removes it. `PARTIAL
ACCEPTANCE` remains the correct status until those failures are resolved.

## 6. Scope and ownership

At the start of this handoff, the worktree is clean and GitHub `master` points
to the same tree. No further commit containing this handoff has been pushed.
The next window owns any edits made after this file. Avoid changing the outer
worktree at the main `MetalUniversal-master` checkout,
which is on the independent upstream comparison branch.
