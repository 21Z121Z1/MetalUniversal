# Independent hosted OpenGL reference

`opengl-reference.yml` first creates an actual macOS OpenGL Core context,
compiles GLSL, draws into RGBA8 and checks every readback byte. It permits any
Core renderer, matching the client's API requirement, and records actual
acceleration. Software OpenGL can serve as an independent API reference and provides
no hardware-performance evidence. At least OpenGL 3.3 Core is mandatory. A missing
context emits `blocked-environment` and fails the job; downstream clients cannot
turn missing capability into a passing reference.
Legacy contexts are diagnostic only. A deliberate wrong-color GPU readback must
also fail the probe's negative self-test. The client jobs run independently of
this diagnostic: SDL's windowed context selection can differ from direct CGL.
Each client must prove its own actual OpenGL backend, GPU control and image;
a successful client does not change a failed probe into a pass.

The separate Vanilla, Sodium and Iris clients load **no MetalUniversal JAR or
native module**. They force `OPENGL`, use a fixed username/UUID, normal seed-1
terrain, camera, noon and clear weather, then freeze simulation before capture.
The Iris lane downloads BSL 10.1.8 from its pinned official Modrinth file and
checks its SHA-256. A dormant pack, fixed-function pipeline, backend fallback,
readback error, wrong profile, incomplete image or substituted harness fails.
World preparation uses Iris's normal shader toggle with shaders disabled. After
all server-declared chunks arrive and simulation freezes, the harness enables
the same BSL pack, reloads Iris and repeats the terrain-render readiness gate.
All chunks must still be present after activation, followed by the existing
40-tick settling period and active-pipeline/image checks. This avoids rendering
BSL during slow software-GL chunk delivery; it covers enabling the pack in a
loaded world, not loading the world with shaders already enabled. Image settings,
pack bytes, chunk coverage and pixel checks are unchanged. Each completed
settling tick is recorded, and all 40 are mandatory. An independent 25-minute
harness deadline leaves setup/upload headroom within the 30-minute CI job.
Versions come from the repository's existing properties. No compatibility shim
or renderer fix is applied to upstream mods.

The default loader is Fabric 0.19.5. Loader 0.19.3 does not classify the new
`com.mojang.renderpearl` package as Minecraft code, so it silently skips Iris's
declared class access changes and the stock OpenGL client fails during startup.
The upstream fix shipped in [0.19.4](https://github.com/FabricMC/fabric-loader/releases/tag/0.19.4).
This harness therefore requires at least 0.19.4 and verifies the actual runtime
loader against the staged input. Minecraft, Iris and Sodium versions are unchanged.

The default is an explicit **1280×720, 12-distance hosted smoke profile**. Generated
worlds are not snapshot-equivalent across lanes, Iris wall-clock history is not
normalized, and no Metal image comparison or performance decision is made.
It establishes whether an independent reference can execute and preserves its
raw RGBA8 image, PNG, source/harness/pack identity and errors. The physical
native-resolution, distance-32 correctness/performance gates remain separate.
Worlds, Minecraft binaries and downloaded shader packs are excluded from uploads.

For local saved-world input checks, stage an existing seed-1 `WorldSnapshot`
directory and its adjacent `-manifest.json`. Pass all dimensions explicitly:

```sh
python3 .github/ci/opengl-reference/prepare_reference.py --renderer iris \
  --instance "$PWD/build/saved-reference-instance" --output build/saved-reference-evidence \
  --world-snapshot /absolute/path/to/initial-world --width 3024 --height 1964 --distance 32
```

Use those instance/evidence paths in the Gradle and verifier commands below.
Gradle reads the staged dimensions and distance; conflicting overrides fail.
The separate `saved-world-opengl-reference-v1` scenario opens only a verified
copy under the fresh instance's `saves/reference-snapshot`. Both Python staging
and the shared Java `WorldSnapshot.verify` check the canonical manifest before
Minecraft opens the copy. The original snapshot is never opened or changed.
The pinned Fabric 6.0.7 saved-world implementation owns connection and cleanup.
Receipts must match the observed snapshot and manifest hashes, file count and
bytes. This binds initial content; simulation history, Iris clocks and shader
preprocessing still need matching before an image-equivalence conclusion.

Local use (macOS with a real OpenGL context):

```sh
python3 .github/ci/opengl-reference/prepare_reference.py --renderer iris \
  --instance "$PWD/build/reference-instance" --output build/reference-evidence
reference_source_sha="$(git rev-parse HEAD)"
set +e
./gradlew --no-daemon -p .github/ci/opengl-reference \
  -PreferenceRenderer=iris -PreferenceSourceSha="$reference_source_sha" \
  -PreferenceRunDir="$PWD/build/reference-instance" \
  -PreferenceEvidence="$PWD/build/reference-evidence" runOpenGlReference
reference_exit=$?
set -e
REFERENCE_EXIT="$reference_exit" REFERENCE_SOURCE_SHA="$reference_source_sha" python3 - <<'PY'
import json, os
from pathlib import Path
Path('build/reference-evidence/execution.json').write_text(json.dumps({
    'exitCode': int(os.environ['REFERENCE_EXIT']),
    'sourceSha': os.environ['REFERENCE_SOURCE_SHA'], 'renderer': 'iris'}) + '\n')
PY
test "$reference_exit" -eq 0
python3 .github/ci/opengl-reference/verify_reference.py \
  --root build/reference-evidence --source-sha "$reference_source_sha" \
  --harness .github/ci/opengl-reference/build/libs/metaluniversal-opengl-reference.jar
```
