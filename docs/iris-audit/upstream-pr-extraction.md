# Upstream Iris PR extraction boundary

Snapshot: 2026-07-30.

Target repository: `EternityQwQ/MetalUniversal`, default branch `master`.
The inspected upstream head was `3bf3011`. The published fork branch
`21Z121Z1/MetalUniversal:iris-on-metal` was `dc47f0d`.

## Why the current branch is not a PR head

GitHub's cross-fork comparison reported:

- 78 commits ahead and 5 behind upstream;
- merge base `a549bdf`;
- 202 changed files;
- the local tree comparison contained about 62,563 additions.

Forty-nine pre-Iris commits belong to the fork's independent MetalFX, motion,
frame-generation and Metal 4 development line. Upstream also has a different
`feature/metalfx-upscale-frameinterp` implementation. A direct PR would
therefore make Iris review depend on choosing between two unrelated MetalFX
architectures.

The existing generic-backend commits are not mechanically cherry-pickable onto
upstream. Patch checks for `e41414d` and `a801057` fail because their
`build.gradle`, native bridge and MRT-test contexts come from the fork-only
MetalFX base. Port the resulting contracts, not the historical commits.

The current worktree also contains post-`dc47f0d` BSL and regression-gate work.
Opening from the published branch before those changes are intentionally
captured would submit the older Potato-only release state.

## Supported extraction stack

### PR 1: generic Metal backend capabilities

Base this directly on the then-current upstream `master`. Keep names and tests
backend-generic:

- compute command encoder, pipeline and dispatch;
- SSBO and storage-image resource binding;
- render/compute ordering and barriers;
- mipmap generation and comparison samplers;
- MRT attachment/output validation, unwritten-attachment clear preservation,
  per-target format/blend/write-mask support;
- generic vertex attributes and layouts;
- Java to FFM to Swift to physical-Metal readback tests.

Do not include Iris dependencies, shader-pack logic, MetalFX settings, motion,
frame generation, HUD, Launcher state or captured third-party assets.

### PR 2: Iris 1.11.2 native Metal raster runtime

Start only after PR 1 is merged or rebased into upstream:

- Sodium 0.9.1 and Iris 1.11.2 dependency/runtime dormancy;
- Iris preprocessing and GLSL to SPIR-V to MSL translation;
- generation-owned render targets, ping-pong, depth and shadow resources;
- Sodium/core terrain routing and real vertex ABI;
- built-in/custom uniforms and alpha/depth/render state;
- shadow, deferred, composite and final raster ordering;
- custom/noise textures, reload and resource retirement;
- Potato, BSL and shaders-off non-Iris gate evidence.

Keep `mod_version` from upstream; never downgrade it to the fork release
version. Exclude `logs/latest.log`, compressed runtime logs and shader-pack
archives.

### PR 3: advanced Iris resource semantics

Connect post compute, SSBO, custom images, typed `samplerBuffer`, post blend
overrides and any geometry/tessellation lowering justified by the actual Iris
call surface. Capability flags remain fail-closed until their producer and
consumer tests pass.

### Later: explicit MetalFX integration

The supported local profiles are deliberately separated:

- `runClientIris`: Iris semantic runtime on, MetalFX/FG/HUD off;
- `runClientMetalFx`: MetalFX temporal on, Iris semantic runtime/FG off.

There is no implicit combined task. A future integration must define one owner
for jitter and explicit final-color, depth, motion, reactive-mask, history
reset, GUI and presentation contracts before adding the combined path back.

## Gate before publishing branches

Before creating or pushing the clean PR branches:

1. preserve and intentionally commit the validated current source without
   staging user logs;
2. run the two real shaders-off non-Iris capture lanes and offline exact
   comparison;
3. complete the required Potato regression after the final shared-backend
   change;
4. keep the accepted BSL evidence and do not substitute compilation or pass
   traces for its visible gate;
5. run the focused unit/physical-GPU suites and `git diff --check`.

No Launcher profile, tag or release belongs to this extraction step.
