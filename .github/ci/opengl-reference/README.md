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
Versions come from the repository's existing properties. No compatibility shim
or renderer fix is applied to upstream mods.

The default loader is Fabric 0.19.5. Loader 0.19.3 does not classify the new
`com.mojang.renderpearl` package as Minecraft code, so it silently skips Iris's
declared class access changes and the stock OpenGL client fails during startup.
The upstream fix shipped in [0.19.4](https://github.com/FabricMC/fabric-loader/releases/tag/0.19.4).
This harness therefore requires at least 0.19.4 and verifies the actual runtime
loader against the staged input. Minecraft, Iris and Sodium versions are unchanged.

This is an explicit **1280×720, 12-distance hosted smoke profile**. Generated
worlds are not snapshot-equivalent across lanes, Iris wall-clock history is not
normalized, and no Metal image comparison or performance decision is made.
It establishes whether an independent reference can execute and preserves its
raw RGBA8 image, PNG, source/harness/pack identity and errors. The physical
native-resolution, distance-32 correctness/performance gates remain separate.
Worlds, Minecraft binaries and downloaded shader packs are excluded from uploads.

Local use (macOS with a real OpenGL context):

```sh
python3 .github/ci/opengl-reference/prepare_reference.py --renderer iris \
  --instance "$PWD/build/reference-instance" --output build/reference-evidence
./gradlew --no-daemon -p .github/ci/opengl-reference \
  -PreferenceRenderer=iris -PreferenceSourceSha="$(git rev-parse HEAD)" \
  -PreferenceRunDir="$PWD/build/reference-instance" \
  -PreferenceEvidence="$PWD/build/reference-evidence" runOpenGlReference
python3 .github/ci/opengl-reference/verify_reference.py \
  --root build/reference-evidence --source-sha "$(git rev-parse HEAD)" \
  --harness .github/ci/opengl-reference/build/libs/metaluniversal-opengl-reference.jar
```
