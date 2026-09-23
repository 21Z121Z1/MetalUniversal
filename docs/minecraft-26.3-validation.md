# Minecraft 26.3 validation

This is the operational runbook for the RenderPearl transplant onto
`EternityQwQ/MetalUniversal:26.3-fabric-dev` (lower-case). It is not a release
readiness claim. **流程已迁移、实机未验证 — physical procedures migrated;
physical Apple Silicon acceptance has not been performed for this PR.**
Use the PR's current **40-character head SHA**, not a branch name alone, for
all acceptance evidence. A subsequent commit invalidates previous evidence.

## Runtime profiles and dependency boundary

The installed production JAR does **not** require Sodium or Iris in Fabric
metadata. Both remain compile-only adapter APIs. The common RenderPearl backend
and the validation client must link without either mod; optional mixins are
selected by actual Fabric mod presence. Runtime profiles select exact mod sets:

| Gradle argument | Runtime renderer mods | Iris semantic layer |
| --- | --- | --- |
| `-Pmetallum.renderer=vanilla` | Neither Sodium nor Iris | Off |
| `-Pmetallum.renderer=sodium` | Sodium only | Off |
| `-Pmetallum.renderer=iris` | Sodium and Iris | On in the test harness |

`-Pmetallum.noOptionalMods=true` remains a legacy alias for Vanilla. Conflicting
arguments fail instead of silently adding a mod. The development default remains
Iris for existing adapter tests; all acceptance lanes specify their profile.
`verifyRendererRuntimeProfile` resolves the actual root runtime dependency graph.
The client fixture also records and checks the actual loaded Fabric mod list.

The upstream Loader, Loom, Minecraft, Sodium, Gradle wrapper and mod version
settings are retained. Only the previously absent optional `iris_version` is
added. The independent E2E build checks its matching pins against the root build.
The upstream empty 26.3 access widener is retained; old Vulkan mappings are not
reintroduced. Engine `TextureViewAndSampler` and defensive foreign-texture checks
remain covered by texture-binding tests and the migration contract check.

## GitHub-hosted checks

Three workflows run on pushes to `upstream/renderpearl-26.3` (fork) or
`26.3-fabric-dev`, and on pull requests targeting **`26.3-fabric-dev`**. They use
read-only repository permissions and explicitly check out the PR head SHA rather
than presenting a synthetic merge SHA as candidate evidence. An upstream
maintainer may need to approve a first-time contributor's workflow run.

| Workflow | Runner and scope | Artifacts |
| --- | --- | --- |
| `minecraft-26.3-migration.yml` | Ubuntu, Java 25: compile/unit tests, production isolation, Vanilla boundary, resolved Vanilla/Sodium/Iris runtime graphs, physical script syntax and negative identity tests | `minecraft-26.3-contracts-<sha>-<attempt>` |
| `metal-capabilities.yml` | Ubuntu static contracts; hosted `macos-26`: native build, raw Metal compute/readback, ABI and hosted shipping contracts | `metal-capabilities-<sha>-<attempt>` |
| `minecraft-client-e2e.yml` | Three independent hosted `macos-26` jobs: Vanilla, Sodium-only, Iris; build a production JAR and launch the real client GameTest | `minecraft-26.3-e2e-<renderer>-<sha>-<attempt>` |

The macOS jobs set `METALLUM_HOSTED_METAL_OFFSCREEN=true`. They provide offscreen
GPU/readback and client-lifecycle evidence, **not physical WindowServer/display
presentation, visual parity, VRR, input-to-photon latency, power, thermals or
stable physical performance**. A hosted macOS job must never close the physical
gate. The ordinary hosted client lane deliberately disables the Metal 4 main
renderer/residency path unavailable on the paravirtual GPU. Dedicated native
probes have their own narrowly stated scope.

When these workflow files are available for manual dispatch in a repository:

```sh
REPO=21Z121Z1/MetalUniversal
REF=upstream/renderpearl-26.3
for workflow in minecraft-26.3-migration.yml metal-capabilities.yml minecraft-client-e2e.yml; do
  gh workflow run "$workflow" --repo "$REPO" --ref "$REF"
done
gh run list --repo "$REPO" --branch "$REF"
# Inspect a selected run and download its exact-head artifacts:
gh run view RUN_ID --repo "$REPO" --log-failed
gh run download RUN_ID --repo "$REPO" --dir evidence/RUN_ID
```

GitHub requires a workflow to be registered on the default branch before some
manual-dispatch routes are available. The PR/push triggers do not require moving
this PR to a different base or merging it merely to validate it.

The E2E artifacts contain `build/libs/`, `build/agent-evidence/`, and the client's
`build/evidence/`, logs and reports under `.github/ci/minecraft-e2e/`. Required
outputs include `artifact-identity.json`, `artifact-decision.json`,
`readback-control/suite.json`, `metal-framebuffer.png`, framebuffer samples,
runtime, presentation and reload JSON. A process that exits without these
outputs fails, even if it prints a successful launch message.

## Trusted physical machine prerequisites

These are instructions for an assigned physical-test operator/agent, **not a
request for the PR author to build or run Minecraft locally**. There is no
self-hosted workflow in this PR. Do not wire these scripts to `pull_request`,
`pull_request_target` or an external PR-controlled self-hosted job. A future
manual workflow must run only reviewed, immutable SHAs on a trusted runner with
explicit authorization; no arbitrary external PR ref or secret-bearing checkout.

Use a physical Apple Silicon Mac, macOS 26+, Xcode 26+ with the selected SDK and
accepted developer-tool setup, a JDK 25 toolchain, Python 3, Git, and network access
to the normal Gradle/Fabric/Minecraft dependencies. A valid Minecraft entitlement
and lawful world/shader inputs remain the operator's responsibility. Run as the
active console user in an **unlocked, logged-in WindowServer GUI session**, not a
background system daemon. Keep the display connected and awake. For the P1
performance protocol, the display/settings must admit the fixed **1708×960**
drawable; mismatched resolution fails rather than adjusting the acceptance bar.

`physical_preflight.sh` rejects non-Apple/non-arm64 hosts, hosted/offscreen mode,
paravirtual Metal devices, unavailable/locked console sessions, and inherited
`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` or `_JAVA_OPTIONS`. Unset these variables;
do not override the offscreen setting to label a hosted run physical. Metal 4
capability and residency/main-renderer activation still have to pass runtime
checks; a successful environment probe is not GPU correctness evidence.

Check out the exact candidate with a clean worktree. Use fresh output directories
for every run; an existing output directory is rejected to prevent stale PASS
files. Generated builds and evidence live below ignored `build/`/`run/` paths.

```sh
EXPECTED_SHA=<PR-head-40-hex-SHA>
git checkout --detach "$EXPECTED_SHA"
test "$(git rev-parse HEAD)" = "$EXPECTED_SHA"
test -z "$(git status --porcelain)"
unset METALLUM_HOSTED_METAL_OFFSCREEN JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS
```

### Physical correctness: no Sodium, with Sodium, optional Iris

These client GameTests generate their own deterministic test world; **no `WORLD`
or external shader pack is required for correctness**. The Iris lane verifies the
optional runtime/semantic integration, not visual quality of a third-party pack.
Each invocation builds the production bits once, then uses the **same JAR and
native binary** in its baseline/candidate Metal 4 main-renderer pair.

```sh
for renderer in vanilla sodium iris; do
  RENDERER="$renderer" \
  METALLUM_P1_CORRECTNESS_OUT="$PWD/build/physical-$renderer-correctness" \
    bash scripts/agent/run_metal4_main_p1_physical_correctness.sh
done
```

Vanilla must prove both optional mods absent; Sodium must prove Sodium present
and Iris absent; Iris must prove both present. The tests check Metal activation,
world/chunk rendering, exact readback controls, capture completion, presentation
completion and resource reload. `pair-decision.json` is published only **after**
all guards and actual artifact identity checks pass. Each lane preserves its
client log, exit status, raw evidence and artifact decision. Exceptions, missing
captures, dropped captures, GPU failure, failed reload, absent native identity or
incorrect renderer presence are failures, not “unsupported = pass”.

### Physical performance and shader matrix

Only start after the matching renderer's physical correctness pair passes at the
**same source/JAR/native identity**. Supply a validation world under
`run/saves/<WORLD>` (use a disposable MC26.3 test save). The runner snapshots it,
resets a separate evaluation copy per trial and records a deterministic world
hash excluding `session.lock`. It restores prior options/Iris configuration when
finished; do not share this game directory with an active interactive client.

For a single no-shader profile, choose Vanilla or Sodium explicitly:

```sh
WORLD='ValidationWorld' PROFILE_ID=V1 RENDERER=vanilla \
P1_CORRECTNESS_GATE="$PWD/build/physical-vanilla-correctness/pair-decision.json" \
METALLUM_P1_PERFORMANCE_OUT="$PWD/build/physical-vanilla-performance" \
  bash scripts/agent/run_metal4_main_p1_physical_performance.sh

WORLD='ValidationWorld' PROFILE_ID=V1 RENDERER=sodium \
P1_CORRECTNESS_GATE="$PWD/build/physical-sodium-correctness/pair-decision.json" \
METALLUM_P1_PERFORMANCE_OUT="$PWD/build/physical-sodium-performance" \
  bash scripts/agent/run_metal4_main_p1_physical_performance.sh
```

`V1` disables shaders. `I0` uses an explicitly supplied Potato archive, and `I1`
uses an explicitly supplied BSL archive. No shader pack is downloaded or
redistributed by these scripts. Supply the exact archive and its version string;
optional `.txt` settings can be supplied as `POTATO_SHADER_OPTIONS` and
`BSL_SHADER_OPTIONS`. Archive/options hashes are recorded and each shader trial
must log activation of the exact staged pack. The matrix needs **both** Vanilla
and Iris correctness gates; an Iris run cannot reuse a Vanilla-only gate.

```sh
WORLD='ValidationWorld' \
P1_CORRECTNESS_GATE="$PWD/build/physical-vanilla-correctness/pair-decision.json" \
P1_IRIS_CORRECTNESS_GATE="$PWD/build/physical-iris-correctness/pair-decision.json" \
POTATO_SHADER_PACK='/trusted/inputs/Potato.zip' POTATO_SHADER_PACK_VERSION='<exact version>' \
BSL_SHADER_PACK='/trusted/inputs/BSL.zip' BSL_SHADER_PACK_VERSION='<exact version>' \
METALLUM_P1_MATRIX_OUT="$PWD/build/physical-shader-matrix" \
  bash scripts/agent/run_metal4_main_p1_physical_matrix.sh
```

The paired protocol requires at least four interleaved blocks, 30 seconds warmup
and 120 seconds sampling per trial (`BLOCKS`, `WARMUP_SECONDS`, `SAMPLE_SECONDS`),
fixed world/camera/UI/render distance and framebuffer identity. Metal API/shader
validation and HUD overlays are off for timing. The only intended baseline vs
candidate change is `metallum.opt.metal4MainRenderer`; Metal 4 compilation,
presentation and residency stay equal. Missing candidate engagement or residency
is failure. Performance executes `runProductionPhysicalPerformance`, **not the
Loom development `runClient` classpath**. Its observer records actual production
JAR/native identity before measuring.

Outputs default to timestamped `build/agent-runs/p1-metal4-main-*` directories.
`environment.json` captures machine/input identity; `trials/` contains arguments,
client logs, exit status, observed artifact identity, readbacks, raw timing and
normalized results. `decision.json` and the profile-matrix decision are authoritative
only if the process exits successfully and every required profile reports the
required accepted state. A neutral/regressing/incomplete result is not acceptance.
Visual inspection of physical captures and display/power/thermal acceptance remain
separate from automated numerical checks.

## Exact artifact verification and failure boundaries

`metallum-build-identity.json` inside the production JAR records actual Git
`sourceSha`, `treeSha`, clean-worktree state, MC version and SHA256 of `build.gradle`
and every shipping Swift input. The bundled native build manifest binds those
inputs to its compiled dylib hash. `artifact-identity.json` records the actual
backend CodeSource JAR, the successfully loaded native file and SHA256, and loaded
Fabric mod versions. Requested hashes alone are never accepted as observations.

The shared verifier checks the source tree, native source input hashes, native
build manifest, archive contents, actually loaded JAR/native hashes and exact
runtime mod set. Both cloud E2E and physical scripts call it:

```sh
python3 scripts/agent/check_production_identity.py \
  /path/to/evidence/artifact-identity.json \
  --jar /path/to/the-tested-production.jar \
  --source "$EXPECTED_SHA" --renderer vanilla \
  --output /path/to/evidence/artifact-decision.json
```

Run this in the matching source checkout while the recorded loaded JAR path is
still present; retain the original evidence and JAR together. A missing native
observation, stale/dirty source, mismatching binary, wrong profile, nonzero exit,
failed readback/reload/presentation, or absent output means **FAIL**. A skipped
physical test remains **NOT RUN**, regardless of hosted CI status.
