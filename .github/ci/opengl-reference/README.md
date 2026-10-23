# Independent hosted OpenGL reference

`opengl-reference.yml` first creates an actual accelerated macOS OpenGL core
context, compiles GLSL, draws into RGBA8 and checks every readback byte. A missing
context emits `blocked-environment` and fails the job; downstream clients cannot
turn missing capability into a passing reference.

The separate Vanilla, Sodium and Iris clients load **no MetalUniversal JAR or
native module**. They force `OPENGL`, use a fixed username/UUID, normal seed-1
terrain, camera, noon and clear weather, then freeze simulation before capture.
The Iris lane downloads BSL 10.1.8 from its pinned official Modrinth file and
checks its SHA-256. A dormant pack, fixed-function pipeline, backend fallback,
readback error, wrong profile, incomplete image or substituted harness fails.
Versions come from the repository's existing properties. No compatibility shim
or renderer fix is applied to upstream mods.

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
