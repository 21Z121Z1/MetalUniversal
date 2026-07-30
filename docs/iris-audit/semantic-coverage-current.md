# Iris 1.11.2 + Minecraft 26.2 native Metal semantic coverage

This is the current, concise coverage matrix. Older architecture matrices in
this directory describe the initial backend state and are not current
completion evidence.

Status vocabulary:

- **Closed**: current source plus content/runtime evidence covers the stated
  contract.
- **Connected**: the real Iris call path reaches Metal, but the whole semantic
  family is not yet closed.
- **Gap**: a real Iris call surface is rejected or lacks an executor.

| Semantic family | Status | Current evidence / earliest gap |
|---|---|---|
| Pack selection, profiles, boolean/slider options | Connected | Exact Iris bytecode shows option queue → `Iris.reload()` → rebuilt `ShaderPack/ProgramSet`; BSL HIGH generation 1→2 observed. Add a synthetic option-change conformance fixture so changed source/directives are asserted directly. |
| Dimension `ProgramSet`, fallback and program selection | Connected | Metal receives Iris's exact dimension `ProgramSet` and uses `ProgramFallbackResolver`. Nether/End live dimension transitions are not yet a gate. |
| Reload, disable-enable, resize and resource retirement | Connected | Potato reload Gate 2 and BSL reload are accepted; generation-scoped cache/target/uniform teardown is implemented. Full disable-enable and live resize/dimension recreation still need a generic lifecycle receipt. |
| Sodium/core vertex ABI and generic attributes | Connected | Potato/BSL terrain and core PSOs compile and render; serializer and generic-attribute tests exist. The full Iris `ShaderKey`/RenderType catalog has not yet been exercised by one synthetic fixture. |
| GLSL preprocessing, patching, linking, varyings and fragment outputs | Connected | All active Potato/BSL vertex/fragment stages translate and create physical Metal PSOs; fragment outputs/MRT fail closed. Geometry and tessellation are gaps. |
| MRT, formats, depth/cull/viewport, blend/write masks | Connected | MRT and unwritten attachments have GPU readback; gbuffer/core per-target state is mapped. Post global/per-buffer blend overrides remain a gap. |
| Built-in/custom uniforms, matrices, previous state, time, camera, alpha test | Connected | Real Iris `CommonUniforms`, pack custom-uniform graph and per-program alpha metadata feed std140 blocks. A complete exact-Iris uniform catalog/value A/B is still missing. |
| Sampled textures, aliases, noise/custom textures, filtering/wrap/mipmap | Connected | Render targets, depth, comparison samplers, PNG custom textures, noise and mipmaps have focused GPU coverage. `samplerBuffer`, Iris custom images and non-PNG custom texture data are gaps. |
| Colortex ping-pong, clear/format/flip and depthtex0/1/2 | Closed for raster fixtures | Content-level target tests plus Potato/BSL runtime traces cover the active contracts. Broader format and lifecycle permutations remain regression work, not a known BSL/Potato failure. |
| Shadow raster, matrices, color/depth targets and compare sampling | Closed for BSL HIGH | BSL HIGH shadow terrain/entities/block entities and post sampling render visibly. Shadowcolor mipmaps and compute-driven shadow/shadowcomp variants remain gaps. |
| Deferred/composite/final raster ordering and visible contribution | Closed for Potato and BSL HIGH | Both accepted fixtures execute their active chains with real resources and visible output. Post compute and post blend variants remain gaps. |
| Compute, SSBO, storage image and barriers | Gap at Iris integration | Native Metal backend primitives and GPU readbacks exist, but Iris post-chain resource construction/execution is not connected; capability negotiation intentionally reports unsupported. |
| Sky/cloud/horizon/weather/particles/entities/block entities/hand/water/glint/text routing | Connected | Potato and BSL close multiple real paths, including water/translucent MRT and direct core routing. A catalog-driven synthetic stage fixture is still needed for exhaustive coverage. |
| Pack directives, feature flags and capability queries | Connected | Common renderer/target/shadow directives are consumed. Advanced flags are fail-closed while their executors are absent; they must be enabled only after semantic tests pass. |
| MetalFX temporal scaler and frame generation handoff | Isolated; integration gap by design | Supported launch profiles are now separate: `runClientIris` forces MetalFX/FG/HUD off and `runClientMetalFx` keeps Iris semantic rendering dormant. The implicit combined `runClientAll` profile is removed and an offline task enforces those defaults. Preserve one jitter owner and add motion/reactive sidebands without replacing pack shaders before restoring a combined path. |
| Shaders-off vanilla/Sodium regression | Partial; exact final-frame difference remains | The deterministic real-client lanes now prove distinct physical game/log directories, identical world snapshots, fixed player identity and entity state, `VanillaRenderingPipeline`, no active pack/generation, and MetalFX/FG/HUD off. Exact comparison still fails: frame 160 differs at 5,253 of 6,558,720 bytes and frame 220 at 3,605 bytes (maximum channel delta 205, stable first offset 20,236). Do not loosen the gate; isolate the semantic bootstrap boundary after this preview release. See `non-iris-regression-gate.md` and `build/iris-runtime/non-iris-gate-20260730-deterministic-player`. |

## Ordered framework work after BSL

1. Wire the non-Iris regression gate before making another cross-framework
   semantic change.
2. Add one redistributable conformance pack covering option mutation, stage
   routing, formats, blend, flip, history and lifecycle.
3. Close post blend overrides and typed `samplerBuffer`.
4. Connect Iris compute/SSBO/custom-image resource graphs to the already tested
   Metal primitives, one producer-consumer ordering at a time.
5. Expand dimension and lifecycle runtime receipts.
6. Consider geometry/tessellation only from the actual Iris pack corpus.
7. Connect MetalFX/Frame Generation through explicit motion/reactive/jitter
   contracts after the Iris-native path remains green.
