# Minecraft 26.3 validation

This branch targets **`26.3-fabric-dev`**. Its source baseline is upstream
`f8294b2fb6ce2edc56d418510294111c9276091e`. The renderer and validation
components were ported from fork snapshot
`790f9cb2a4c7195158e7b2f71a030fcbf8db43e1`, tracing the RenderPearl migration
starting at `825226f2e747ec557629866232e5b55c99d34a2e` and its native/adapter
prerequisites. This is not a merge of the fork's branch history or documentation.
The upstream texture type guard, access widener, Loader 0.19.5, Loom 1.18.2,
Gradle 9.7.0 and mod version 1.0.4 are retained. Iris 1.11.6+26.3-fabric is an
optional adapter, not a mandatory runtime dependency.

**Physical status at migration: 流程已迁移、实机未验证.** A green hosted job
must not be substituted for the physical gates below. Current run outcomes
belong in the PR/Actions receipts, not in a permanently green status document.

## Runtime profiles

`-Pmetallum.renderer=vanilla` removes both Sodium and Iris from the launch
classpath. `sodium` installs Sodium without Iris. `iris` installs both, since
Iris uses Sodium. The root development default remains Sodium, as upstream did;
Iris now requires an explicit choice. `-Pmetallum.noOptionalMods=true` remains
an alias for Vanilla; conflicting arguments fail configuration. Optional API
references are compile-only, their mixins are gated by actual Fabric mod
presence, and `fabric.mod.json` does not require either optional renderer.
Unit tests deliberately have both adapter APIs except for the isolated Vanilla
boundary task. A unit-test classpath is not evidence of a client launch profile.

## Hosted cloud checks

All three workflows support `workflow_dispatch`. Pushes on the migration
branch and PRs targeting `26.3-fabric-dev` run exact-head checks. Maintainers can
also dispatch the workflows from an explicitly trusted branch:

```sh
gh workflow run minecraft-26.3-migration.yml --repo OWNER/MetalUniversal --ref BRANCH
gh workflow run metal-capabilities.yml --repo OWNER/MetalUniversal --ref BRANCH
gh workflow run minecraft-client-e2e.yml --repo OWNER/MetalUniversal --ref BRANCH
```

The migration workflow resolves each of the three runtime inventories, runs
the no-optional-mod RenderPearl boundary, Java contracts, JAR isolation, and
Python/shell evidence checks. The capability workflow builds the shipping
native library and runs raw Metal compute/readback plus selected JVM/native
integration tests. The client workflow runs three separate production-JAR
GameTests: Vanilla, Sodium-only, and Iris. Each creates a disposable normal
Overworld with seed 1; no supplied WORLD or shader pack is needed. Iris in this
workflow proves the optional adapter without an externally supplied shader pack.

The macOS jobs use GitHub-hosted `macos-26` Apple Silicon and
`METALLUM_HOSTED_METAL_OFFSCREEN=true`. Their evidence is **hosted offscreen
execution**, not proof of physical WindowServer scan-out, visual parity, VRR,
input latency, power/thermals, MetalFX quality, or an improvement in frame pacing.
The client job verifies loaded mod inventory, actual source/JAR/native identity,
non-vacuous framebuffer readback, world/chunk rendering, presentation lifecycle
and successful resource reload. Native capabilities are not inferred from a
successful compilation alone.

Artifacts are attached to each workflow run, named with the exact source SHA,
mode (for client E2E), and attempt. They contain `build/agent-evidence`, test
reports, and the E2E `build/evidence` and client logs. The client artifacts also
include the built production JAR. In a checkout, E2E evidence is under
`.github/ci/minecraft-e2e/build/evidence/`; client logs are under its
`build/run/clientGameTest/logs/`. Downloaded artifacts should be kept outside the
tracked source tree.

## Physical prerequisites and trust boundary

These scripts are for a maintainer-operated **physical Apple Silicon Mac**, not
for the contributor to build or test on their own computer. No self-hosted
workflow is installed by this PR. Do not run unreviewed external PR code on a
persistent/self-hosted machine or add an automatic `pull_request` trigger for
these scripts. A future self-hosted integration must be trusted manual dispatch
only and check out an explicitly reviewed exact commit.

Use macOS 26 or newer, a compatible Xcode/Metal SDK and command-line tools,
Java 25, Python 3.11+, Git, and Bash (the shipped scripts support macOS Bash 3).
The operator must be the logged-in desktop user with an unlocked WindowServer
GUI session, a real connected display and a functioning Metal 4/residency path.
The wrapper retrieves Gradle; dependency/assets downloads require network
access. The runner checks Darwin/arm64, macOS, WindowServer, the active console
user and GUI launch domain. The explicit declaration below is an operator's
attestation of real hardware, not a hardware proof produced by hosted CI.

Check out the reviewed **exact SHA** in a clean worktree, with all changes
committed. Keep build/evidence files in ignored `build/` directories. Clear
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and
`METALLUM_HOSTED_METAL_OFFSCREEN`; inherited JVM overrides are rejected. Set:

```sh
export METALLUM_PHYSICAL_MACHINE=1
```

Keep the desktop unlocked throughout. Correctness uses Metal API Validation;
performance disables that instrumentation. The physical route uses ordinary
SDL/CAMetalLayer presentation and rejects the hosted offscreen override.
Capability failure, locked/unavailable GUI, or a missing input is a failed or
blocked physical attempt, never a passing physical check.

## Physical correctness, separately without and with Sodium

No WORLD or shader-pack input is required for these generated-world GameTests:

```sh
P1_RENDERER_MODE=vanilla bash scripts/agent/run_metal4_main_p1_physical_correctness.sh
P1_RENDERER_MODE=sodium  bash scripts/agent/run_metal4_main_p1_physical_correctness.sh
P1_RENDERER_MODE=iris    bash scripts/agent/run_metal4_main_p1_physical_correctness.sh
```

Each invocation automatically builds the production JAR/native at that exact
checkout, verifies JAR isolation, and runs baseline/candidate on **the same
binary pair**. Only the intended `metal4MainRenderer` switch differs; compiler,
present and explicit residency remain enabled in both lanes. The legacy pilot
queue is disabled. Baseline is not a different source revision.

Default output:
`build/agent-runs/p1-metal4-main-correctness-MODE-UTC_TIMESTAMP/`.
`METALLUM_P1_CORRECTNESS_OUT` can specify an absolute output under `build/`.
The directory contains `build.log`, `environment.json`, each lane's logs and
`evidence/`, and `pair-decision.json`. Failed client evidence is retained as
`evidence-failed/` where available. A successful decision is written with the
actual renderer mode; a Vanilla receipt cannot authorize a Sodium or Iris trial.

## Physical performance and full matrix

Prepare a disposable, known validation world under `run/saves/WORLD_NAME/`.
`WORLD` is that **directory name**, not an arbitrary path. The script snapshots
it, excludes `session.lock` from its content digest, restores an independent
copy for each trial, and restores client options and Iris configuration on exit.
Do not supply an important active world. The fixed-camera driver snaps the
saved player pose to a deterministic position/yaw. The protocol requires
1708x960 framebuffer pixels, UI scale 3, render distance 16, at least 30 seconds
warm-up, 120 seconds sampling, and four paired blocks in alternating AB/BA order.
A display/scaling mismatch fails rather than silently changing the workload.

Profiles are now version 2: **V1 is genuinely Vanilla**, **S1 is Sodium-only**,
I0 uses Iris with Potato, and I1 uses Iris with BSL. Historical V1 receipts from
the fork's Sodium+Iris-loaded baseline are not accepted as Vanilla evidence.
For an individual no-shader trial, use its matching correctness receipt:

```sh
WORLD='Validation World' PROFILE_ID=V1 \
P1_CORRECTNESS_GATE="$PWD/build/agent-runs/CORRECTNESS_VANILLA/pair-decision.json" \
  bash scripts/agent/run_metal4_main_p1_physical_performance.sh

WORLD='Validation World' PROFILE_ID=S1 \
P1_CORRECTNESS_GATE="$PWD/build/agent-runs/CORRECTNESS_SODIUM/pair-decision.json" \
  bash scripts/agent/run_metal4_main_p1_physical_performance.sh
```

For I0/I1, supply the corresponding actual shader ZIP and its version:
`POTATO_SHADER_PACK`, `POTATO_SHADER_PACK_VERSION`, `BSL_SHADER_PACK`,
`BSL_SHADER_PACK_VERSION`. Optional `POTATO_SHADER_OPTIONS` and
`BSL_SHADER_OPTIONS` are paths to preset/options files. Packs are not downloaded,
redistributed or silently upgraded by these scripts. Both pack and options bytes
are hashed; the logs must prove the content-addressed staged pack was enabled.

To run all correctness modes followed by V1/S1/I0/I1 performance:

```sh
WORLD='Validation World' \
POTATO_SHADER_PACK='/fixtures/Potato.zip' POTATO_SHADER_PACK_VERSION='EXACT_VERSION' \
BSL_SHADER_PACK='/fixtures/BSL.zip' BSL_SHADER_PACK_VERSION='EXACT_VERSION' \
  bash scripts/agent/run_metal4_main_p1_physical_matrix.sh
```

The full matrix writes `build/agent-runs/p1-metal4-main-matrix-UTC_TIMESTAMP/`,
with `correctness/{vanilla,sodium,iris}/`, `profiles/{V1,S1,I0,I1}/` and a final
`decision.json`. Individual performance runs use
`build/agent-runs/p1-metal4-main-PROFILE-performance-UTC_TIMESTAMP/` and contain
`environment.json`, `trials/block-NN/{baseline,candidate}/`, original
`native-fullscreen-baseline.json`, observed `artifact-identity.json`, normalized
metrics, admission results, logs and final decision. Output override variables
are `METALLUM_P1_MATRIX_OUT` and `METALLUM_P1_PERFORMANCE_OUT`.

The timing runner calls Loom's `runProductionClientValidation` with the shipping
JAR, **not** `runClient` or development classes. It uses the same actual JAR and
native hashes approved by the matching correctness run. Hashing occurs before
the sample window, never in the per-frame hot path.

## Identity and failure criteria

`metallum-build-identity.json` inside the JAR records actual Git source SHA,
tree, dirty state and native source inputs. Runtime identity observes the code
source that defined `MetalDevice` and the successfully loaded native file,
then checks their SHA-256 values against the packaged native and any supplied
correctness-approved hashes. A requested SHA alone is not evidence. The
runtime mode must match the actual Fabric mod inventory.

A saved receipt can be checked against the exact shipping artifacts with:

```sh
python3 scripts/agent/verify_loaded_artifact.py \
  --identity /evidence/artifact-identity.json --mode vanilla --source EXACT_40_HEX_SHA \
  --jar build/libs/metallum-1.0.4.jar \
  --native src/main/resources/natives/macos/libmetallum.dylib
```

Missing evidence, dirty/wrong source, a development class directory, absent
native load identity, JAR/native mismatch, unexpected optional mods, failed or
dropped captures, wrong backend, missing chunks, unsuccessful command buffers,
empty framebuffer, lost world or failed reload reject correctness. Process exit
zero alone does not pass. Physical checks additionally require real presentation
and the P1 activation/residency/readback contracts. Timing trials reject wrong
resolution, missing measurements, incorrect lane activation or shader input.
Performance acceptance requires the existing paired statistical and regression
guardrails and an `accepted-candidate` decision for every profile; an inconclusive
or slower candidate is not promoted merely because Minecraft can run. None of
these synthetic/automated comparisons establishes attended visual quality,
power/thermal behavior or input-to-photon latency.
