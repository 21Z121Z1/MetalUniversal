# MetalFX frame-synthesis contract v2

This branch extracts only the fail-closed input semantics from the pre-Iris Frame Generation contract work. It does not copy the old manager, texture-view, native presenter, or resource-lifetime implementation.

## Current contract

A source frame is identified by `(frameId, historyEpoch)`. Every finalized motion input must provide exactly one receipt for each observable producer domain:

- camera and depth;
- dynamic content;
- first-person content;
- transparency;
- particles and weather;
- modded renderers.

Each receipt is classified as real motion, explicit reactive fallback, or unsupported. Temporal is ineligible when any domain is unsupported. Frame Generation additionally requires real camera/depth and dynamic-content motion.

The contract validates camera parameters, input dimensions, texture formats, single-layer/single-mip ownership, finite jitter, MetalFX motion scale, and agreement between the scene and motion reset state.

## Deliberately not implemented yet

- frame-stamp production in `MetalFxManager`;
- history-epoch advancement on every reset cause;
- producer receipt collection from the existing motion paths;
- replacement of the current unvalidated `FrameGenerationInput` record;
- exact-stamp native enqueue/completion/fallback transitions;
- per-slot native presenter resources and argument-table lifetime;
- sRGB texture-view reinterpretation from the old branch.

The current backend stores pixel format on `MetalGpuTexture`; `MetalGpuTextureView` describes mip range and swizzle only. The old branch's view-owned `mtlPixelFormat()` assumptions must not be restored without an explicit current-architecture design and native validation.

## Required sequence

1. Land and test the pure contract.
2. Add frame-id/history-epoch production without changing Frame Generation admission.
3. Emit coverage receipts from existing camera, entity, first-person, transparency, particle/weather, and modded-renderer paths.
4. Replace the Java presenter handoff and keep the shipped object-motion gate false.
5. Add exact-stamp native lifecycle and fallback state.
6. Run offscreen, lifecycle, presentation, frame-pacing, and attended visual gates before enabling anything by default.
