# MetalFX Temporal Upscaling

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

Negative mip bias remains intentionally unenabled. The current Minecraft
sampler abstraction exposes max-LOD but not a per-sample bias, and the generated
SPIR-V-to-MSL path does not provide a safe material-only hook. Applying a
global MSL text replacement would affect GUI, explicit-LOD, depth, and shadow
samples, so it is not used.

The configured scale is the render/display ratio. The phase count follows the
MetalFX guidance used by this project: `ceil(8 / scale^2)`, yielding 8 phases
at 1.0, 18 at 0.67, and 32 at 0.5.

Frame generation consumes the temporal result only after this encode has
completed. It uses the full-resolution scene output, a native-resolution UI
composition, and the same render-resolution depth/motion inputs. The UI is
marked as precomposited for `MTLFXFrameInterpolator`, so HUD pixels are not
treated as moving scene content. See `metalfx-frame-generation.md` for the
separate PresentThread and synchronization contract.
