# MetalFX Temporal Upscaling

The 2026-10-19 [specialty audit](metalfx-closure-study.zh-CN.md) supersedes
older acceptance statements below for the current branch. Hosted raw Metal
execution is available, but the probed hosted GPUs report Temporal and frame
interpolation unsupported. Historical M1 Pro results are not current-head proof.

The temporal input uses the render-resolution color and depth textures, an
`RG16_FLOAT` motion texture, and an `R8_UNORM` reactive mask. The native
MetalFX descriptor receives the actual texture formats and dimensions rather
than assuming a fixed swapchain format.

Per frame, the Java side keeps the camera's unjittered view-projection matrix
for history and applies a Halton pixel jitter to the projection used for the
current depth buffer. The inverse of that jittered matrix reconstructs the
current world position. The motion pass projects that position through the
current and previous unjittered view-projection matrices, so camera jitter is
not interpreted as object motion.

Motion is emitted in MetalFX's top-left screen convention: X is
`previousNdc.x - currentNdc.x`, while Y is `currentNdc.y - previousNdc.y`
because Metal clip-space Y points up and framebuffer Y points down. The scaler
receives `motionVectorScaleX/Y = renderWidth/2, renderHeight/2`. A static
camera must therefore produce zero motion even while the Halton phase changes.

These matrices describe the final Metal raster coordinate space. The shipping
SPIRV-Cross policy flips Minecraft's `gl_Position.y`; `MetalMotionContract`
applies that same flip to motion varyings and camera matrices, and compensates
the projection jitter before translation. Pixel jitter reported to MetalFX
stays unchanged. Applying another Y flip in the motion kernel would be wrong.

Object-motion replays retain the actual source draw's same-frame projection
slice rather than binding whichever projection a later HUD pass left global.
First-person draws capture their actual bound HUD projection and ModelViewMat
from live CPU-written Metal uniforms. These immutable matrices share the draw
token, complete material manifest, and successful source-frame commit with the
posed vertex history. The hand replay uses the previous HUD projection times
the previous per-draw model-view; its current correction is only the compiler's
clip-Y conversion, since HUD rendering is unjittered. It retains the source HUD
depth attachment and copies the captured current projection into frame-owned
memory, so later screen effects cannot replace it. Both hands retain independent
content-sensitive generations.

Missing geometry or matrices, changed item/topology, first appearance, reload,
and reset keep the hand at zero motion with reactive protection. A complete
candidate becomes real motion only after every matching replay was encoded.
This connects the hand producer; hosted shader/readback tests do not establish
physical MetalFX reconstruction or frame-interpolation visual quality.

The motion compute pass combines:

- reconstructed screen-space motion;
- conservative 3x3 depth-boundary response for both sides of cutout foliage
  and geometry edges; and
- the current-frame transparency mask.

The cleared-depth side of a boundary is included deliberately. Leaves and
grass are rendered by Mojang's `CUTOUT` layer together with `SOLID`, so the
background pixels in their holes have no independent target or object motion.
Rejecting history on that side prevents a moving leaf from being reconstructed
into its newly exposed background during view rotation. This is a conservative
edge policy; it does not claim that the Java renderer exposes a separate
foliage buffer.

Camera motion itself is not used as a reactive value. The motion vector already
describes valid camera motion; marking every moving pixel reactive would reject
the whole frame's history and reduce temporal quality. Reactive values are
reserved for transparency, depth discontinuities, and invalid reconstruction.

For camera-only pixels, occlusion compares the previous depth texture to the
reconstructed point's **previous-camera-space depth**, including during the
neighborhood search. Comparing it to current raw depth falsely rejected camera
forward/backward motion. Fused motion reuses its previous clip Z; the split path
passes `previousVP * inverseCurrentJitteredVP` through one shared internal Swift
uniform layout for both Metal 3 and Metal 4. RG-only object/hand samples lack
previous Z and retain conservative depth rejection; they do not gain a claim of
exact dynamic occlusion from this correction.

After a resize, world change, renderer reset, invalid matrix, projection/FOV
change, large camera displacement (teleport), or first frame, history is reset.
These resets are event-driven and do not remain active after the next successful
scene frame. If the transparency frame-graph pass is unavailable, the motion
pass starts the reactive value at zero instead of retaining stale mask data from
the previous frame.

The motion and transparency compute passes use the device-reported thread
execution width (capped at a portable 64-wide upper bound) and keep their
pipelines cached. The cap also protects validation runs, where instrumentation
can report an inflated width, while retaining a small height for balanced 2D
occupancy.

Negative mip bias is applied after SPIR-V-to-MSL translation to plain fragment
texture samples. The bias is `log2(renderScale) - 1`, matching the Game Porting
Toolkit guidance when `renderScale` is the render/display ratio. The rewriter
leaves explicit `level`, `bias`, `gradient2d`, `min_lod_clamp`, and offset forms
unchanged. Single-mip resources cannot select a lower mip, so GUI, font, and
lightmap sampling remains exact. The bias bits are part of the MSL disk-cache
key, preventing OFF, 0.67, and 0.5 variants from aliasing.

The configured scale is the render/display ratio. The phase count follows the
MetalFX guidance used by this project: `ceil(8 / scale^2)`, yielding 8 phases
at 1.0, 18 at 0.67, and 32 at 0.5.

Frame generation consumes the temporal result only after this encode has
completed. It uses the full-resolution scene output, a native-resolution UI
composition, and the same render-resolution depth/motion inputs. The current
presenter sets `uiTexture=nil` and `isUITextureComposited=false`, interpolates
scene color, then overlays the native-resolution UI onto both generated and
real outputs. HUD pixels do not enter Temporal history or scene interpolation.
See `metalfx-frame-generation.md` for the
separate PresentThread and synchronization contract.

## Game Porting Toolkit contract audit (2026-07-27)

The implementation was re-audited against
`using-metalfx-temporal-upscaler/SKILL.md` and its integration guide after the
earlier review session stopped before producing this result.

- Halton samples are one-based, centered to `[-0.5, 0.5)`, and cycle through
  `ceil(8 / scale^2)` phases. Unit tests cover the sequence and phase counts.
- Pixel jitter is passed unchanged to MetalFX. Shader clip jitter uses
  `(2*x/renderWidth, -2*y/renderHeight)`. `applyProjectionJitter` accounts for
  JOML's right-handed projection (`w = -z_view`) rather than copying the
  left-handed matrix-column edit verbatim.
- Depth is reconstructed with the inverse jittered view-projection matrix, but
  current and previous screen positions use unjittered matrices. A static scene
  therefore emits zero motion while the jitter phase advances.
- Motion is previous-screen minus current-screen in a top-left framebuffer.
  The Y subtraction is reversed relative to Metal clip space, and the scaler
  receives `(inputWidth/2, inputHeight/2)` motion-vector scales.
- The Temporal descriptor mirrors the real color, depth, motion, reactive, and
  output formats. Every frame sets input content dimensions, pixel jitter,
  motion-vector scales, reset, reversed depth, and the reactive texture when
  the OS exposes that API. The output texture includes shader-write usage.
- Resize, render/display size changes, projection/FOV changes, teleport,
  invalid matrices, world transitions, command-buffer failure, and explicit
  lifecycle events reset history and restart the jitter sequence.
- GUI/HUD is rendered after Temporal at native resolution. The current Frame
  Generation presenter composites UI after interpolating scene color. It does
  not put GUI pixels into Temporal history; the older precomposited-UI account
  below predates the current presenter and is not its execution contract.

Verification on Apple M1 Pro with Metal API Validation enabled:

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test buildMacNative metalFxOffscreenValidation --no-daemon

BUILD SUCCESSFUL
MetalFX offscreen validation passed: 8/8 scenarios
```

The eight GPU scenarios are static, translation, rotation, occlusion/reveal,
alpha test, scene cut, illegal motion, and history reset.

The real Minecraft renderer gate also passed on the same Apple M1 Pro with
Metal API Validation enabled:

```text
./gradlew minecraftMetalFxClientValidation --no-daemon

BUILD SUCCESSFUL
MetalFX client validation: PASS (16/16 GPU readbacks, 0 failed)
```

The final validation pass also closed three harness defects found while running
the gate: the room is reinstalled after integrated-server startup chunk sync;
first-person overlay validity is separated from world-object occlusion/reset
assertions; and the old-minecart rail samples use a deterministic scripted
direction instead of the wall-clock-dependent hurt animation. CUTOUT policy
checks ignore coverage hidden behind nearer object-validity pixels.
