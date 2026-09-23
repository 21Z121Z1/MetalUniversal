# RenderPearl 26.3 native Metal acceptance matrix

This matrix is fixed before implementation on `codex/renderpearl-native-263-20260923`, starting at `74e0bbc94e7b2b9e041a7dcd4aeee0dd0f3bd75d`. The reference is the Mojang 26.3 client, SHA-1 `e877b6a07acd633fb3bb475002175cec036e7b87`. Its generated source is local evidence and must not be committed. A line passes only with the stated behavior test or production observation on the final source and binary identity. `unavailable` and compilation alone are not passes.

The engineering target is at least 19 of these 20 lines passing, including every **critical** line. An unverified line remains unverified even when an adjacent test passes. The target does not imply performance improvement or hardware acceptance.

| # | Contract and minimum evidence | Class | Status |
|---|---|---|---|
| 1 | Exact 26.3 RenderPearl backend API/descriptor and Java–Swift ABI compile on final HEAD | critical | pass |
| 2 | Base backend classloads and enters a Vanilla world without Iris or Sodium | critical | pass |
| 3 | Base backend renders a deterministic world with no unexplained render-contract difference from Vulkan | critical | failed |
| 4 | Resource reload and resize recreate usable pipelines, textures and surface | critical | partial |
| 5 | Texture format, dimensions, layers, mip counts and view ranges obey frontend and native limits | critical | partial |
| 6 | Color/depth clear and regional clear preserve independent expected attachment values | critical | pass |
| 7 | Buffer/texture copy and readback preserve bytes, offsets, rows and callback completion | critical | pass |
| 8 | Render/transfer RAW, WAR and WAW ordering and resource retirement pass native readback tests | critical | partial |
| 9 | Direct and indirect draw offsets, base vertex and first instance pass independent expected-pixel tests | critical | pass |
| 10 | MRT blend, depth and write masks pass independent expected-pixel tests; packed depth/stencil attachment formats obey the supported-format policy | critical | partial |
| 11 | Shader inputs, outputs, bindings, push constants and coordinate/depth conventions match RenderPearl SPIR-V semantics | critical | partial |
| 12 | Dynamic shader source and user resource-pack shader changes reach the Metal pipeline | critical | partial |
| 13 | Metal 3 pipeline creation/cache and reload produce correct native PSOs | critical | pass |
| 14 | Metal 4 pipeline route has an explicit capability gate and correct native PSO behavior on actual Metal 4 | critical | pass |
| 15 | GPU timestamp queries use GPU samples, report pending/invalid as unavailable, and calibrate to host time without fabrication | critical | pass |
| 16 | Device limits and feature bits match exercised native capability and fail closed otherwise | critical | partial |
| 17 | Mipmap generation handles one-level textures without a Metal validation assertion | critical | pass |
| 18 | Surface acquire, blit, present, iconify and error lifecycle pass production-client observation | critical | partial |
| 19 | Native Metal 3 automated suite passes with validation enabled on final binary | critical | pass |
| 20 | Native Metal 4 automated suite passes on true Metal 4 hardware, separately from hosted/Paravirtual results | critical | pass |

Statuses will be changed only alongside the exact final artifact, command, exit status and independent observation. A hosted runner with `metallum_metal4_supported=false` cannot pass line 20. Encoder-level GPU telemetry is not line 15's arbitrary RenderPearl query contract. The existing `RenderTraceRecorder` and unified evaluation loop own cross-backend semantic identity; this document only records acceptance, not another trace format.

The current result is **11/20 pass**, eight partial and one failed. The 95% target is not met. The production client independently verifies the clean, exact-source JAR and loaded native-library hash; the Vanilla 26.3 run produces eight nonblank 854x480 GPU readbacks, successful presentation and resource reload receipts. The Metal 3 and Metal 4 native suites pass separately with Metal API validation enabled on a physical Apple M1 Pro. `renderContractSyntheticValidation`, the Vanilla boundary test and `verify_unified_eval.sh` pass. Local evidence is in ignored `build/` and `.github/ci/minecraft-e2e/build/`, and must not be committed.

The failed line 3 is a forced Metal/Vulkan comparison of the same copied world and recorded camera, time, weather, entity poses, lightmap inputs and terrain inputs. The comparison report is `build/renderpearl-backend-compare/comparison.json`: both requested frames differ in 308,364 of 1,639,680 pixels, with maximum channel delta 170. Most differences have magnitude one, but a smaller concentrated region has larger deltas; Metal reports 586 visible chunks and Vulkan 587. These differences are not accepted as harmless. Line 4 lacks a production window-resize receipt despite native texture resize and production resource reload passing. Lines 5, 8, 10, 11, 12 and 16 lack complete coverage of their listed subcontracts. Line 18 has successful acquire/present and reload receipts, but no production iconify/error-lifecycle observation. One isolated production run closed during the fifth sample while Gradle returned success; a fresh isolated rerun completed and its required receipts were checked independently. The Gradle exit code alone is not a GameTest pass.

The 26.3 `DepthStencilState` contains depth compare, depth write and depth bias; it does not expose stencil compare or stencil write controls. Line 10 checks only the depth/stencil attachment formats that RenderPearl can describe, including fail-closed rejection of unsupported combinations. This corrects the initial wording without removing a matrix line.

For line 3, `minecraftBackendCapture` must force the requested Minecraft backend and the comparison must verify each receipt's actual device backend. The two captures must share the world snapshot, player, camera, clock, weather, simulation state, extent, format, lightmap and recorded entity poses before a pixel result is eligible for acceptance. Copied worlds can assign new mob UUIDs, so compare their recorded types and poses rather than UUID-derived hashes; keep the player's requested UUID exact. An unmatched scene or unexplained pixel difference is not a pass.

## Shader and native pipeline boundary

The exact 26.3 `PipelineBuilder` loads `ShaderSource` through RenderPearl, preprocesses and compiles it to SPIR-V, then passes `BackendRenderPipeline.CreateInfo` to `GpuDeviceBackend.compilePipeline`. `MetalCrossShaderCompiler` copies those modules before rebinding Metal resources, translates them with SPIRV-Cross to MSL, and keys its MSL cache on both SPIR-V digests, layout, LOD policy, SPIRV-Cross version and cache salt. `MetalDevice` and the existing Swift bridge create native `MTLRenderPipelineState`; optional `MTL4Compiler` and archive lookup are already separate paths. `clearPipelineCache` invalidates generation-bound pending pipelines and retained functions on reload. These are distinct phases: a native pipeline can execute the translated shader, but it cannot directly execute SPIR-V or replace RenderPearl's source/IR semantics. Handwritten MSL can replace a dynamic shader only after a bounded equivalence proof for its inputs, bindings, output and resource-pack variants. No such replacement is part of this task.

The native PSO path is already in place. Additional default-on precompilation or caching is not justified here without its own correctness and paired performance evidence; the concurrent Apple Silicon efficiency task owns its experiments. Existing resource-pack shader changes must continue to flow through `ShaderSource`, SPIR-V and the cache key. Relevant primary sources: [Apple `MTLRenderPipelineState`](https://developer.apple.com/documentation/metal/mtlrenderpipelinestate), [Apple Metal 4 compiler](https://developer.apple.com/documentation/metal/mtl4compiler), [Khronos SPIRV-Cross README](https://github.com/KhronosGroup/SPIRV-Cross/blob/main/README.md).

## Timestamp capability boundary

Apple documents that counter samples must be resolved after GPU completion and GPU timestamps require calibration against the CPU clock: [sampling](https://developer.apple.com/documentation/metal/sampling-gpu-data-into-counter-sample-buffers), [resolution](https://developer.apple.com/documentation/metal/converting-a-gpus-counter-data-into-a-readable-format), [calibration](https://developer.apple.com/documentation/metal/converting-gpu-timestamps-into-cpu-time). Metal 3 command-stream queries use a stage-boundary blit sample after both renderer fences. Metal 3 render-pass queries require actual draw-boundary support; the backend reports unsupported if the device lacks it. Metal 4 uses `MTL4CounterHeap` and native command/render-encoder timestamp operations. Query slots stay pending until their owning submission succeeds. Native resolve failures remain empty and never become host timestamps. The optional whole-encoder GPU timer cannot satisfy a particular RenderPearl query position.
