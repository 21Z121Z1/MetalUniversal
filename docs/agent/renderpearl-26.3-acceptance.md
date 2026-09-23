# RenderPearl 26.3 native Metal acceptance matrix

This matrix is fixed before implementation on `codex/renderpearl-native-263-20260923`, starting at `74e0bbc94e7b2b9e041a7dcd4aeee0dd0f3bd75d`. The reference is the Mojang 26.3 client, SHA-1 `e877b6a07acd633fb3bb475002175cec036e7b87`. Its generated source is local evidence and must not be committed. A line passes only with the stated behavior test or production observation on the final source and binary identity. `unavailable` and compilation alone are not passes.

The engineering target is at least 19 of these 20 lines passing, including every **critical** line. An unverified line remains unverified even when an adjacent test passes. The target does not imply performance improvement or hardware acceptance.

| # | Contract and minimum evidence | Class | Status |
|---|---|---|---|
| 1 | Exact 26.3 RenderPearl backend API/descriptor and Java–Swift ABI compile on final HEAD | critical | pending |
| 2 | Base backend classloads and enters a Vanilla world without Iris or Sodium | critical | pending |
| 3 | Base backend renders a deterministic world with no unexplained render-contract difference from Vulkan | critical | pending |
| 4 | Resource reload and resize recreate usable pipelines, textures and surface | critical | pending |
| 5 | Texture format, dimensions, layers, mip counts and view ranges obey frontend and native limits | critical | pending |
| 6 | Color/depth clear and regional clear preserve independent expected attachment values | critical | pending |
| 7 | Buffer/texture copy and readback preserve bytes, offsets, rows and callback completion | critical | pending |
| 8 | Render/transfer RAW, WAR and WAW ordering and resource retirement pass native readback tests | critical | pending |
| 9 | Direct and indirect draw offsets, base vertex and first instance pass independent expected-pixel tests | critical | pending |
| 10 | MRT blend, depth, stencil and write masks pass independent expected-pixel tests | critical | pending |
| 11 | Shader inputs, outputs, bindings, push constants and coordinate/depth conventions match RenderPearl SPIR-V semantics | critical | pending |
| 12 | Dynamic shader source and user resource-pack shader changes reach the Metal pipeline | critical | pending |
| 13 | Metal 3 pipeline creation/cache and reload produce correct native PSOs | critical | pending |
| 14 | Metal 4 pipeline route has an explicit capability gate and correct native PSO behavior on actual Metal 4 | critical | pending |
| 15 | GPU timestamp queries use GPU samples, report pending/invalid as unavailable, and calibrate to host time without fabrication | critical | pending |
| 16 | Device limits and feature bits match exercised native capability and fail closed otherwise | critical | pending |
| 17 | Mipmap generation handles one-level textures without a Metal validation assertion | critical | pending |
| 18 | Surface acquire, blit, present, iconify and error lifecycle pass production-client observation | critical | pending |
| 19 | Native Metal 3 automated suite passes with validation enabled on final binary | critical | pending |
| 20 | Native Metal 4 automated suite passes on true Metal 4 hardware, separately from hosted/Paravirtual results | critical | pending |

Statuses will be changed only alongside the exact final artifact, command, exit status and independent observation. A hosted runner with `metallum_metal4_supported=false` cannot pass line 20. Encoder-level GPU telemetry is not line 15's arbitrary RenderPearl query contract. The existing `RenderTraceRecorder` and unified evaluation loop own cross-backend semantic identity; this document only records acceptance, not another trace format.

## Shader and native pipeline boundary

The exact 26.3 `PipelineBuilder` loads `ShaderSource` through RenderPearl, preprocesses and compiles it to SPIR-V, then passes `BackendRenderPipeline.CreateInfo` to `GpuDeviceBackend.compilePipeline`. `MetalCrossShaderCompiler` copies those modules before rebinding Metal resources, translates them with SPIRV-Cross to MSL, and keys its MSL cache on both SPIR-V digests, layout, LOD policy, SPIRV-Cross version and cache salt. `MetalDevice` and the existing Swift bridge create native `MTLRenderPipelineState`; optional `MTL4Compiler` and archive lookup are already separate paths. `clearPipelineCache` invalidates generation-bound pending pipelines and retained functions on reload. These are distinct phases: a native pipeline can execute the translated shader, but it cannot directly execute SPIR-V or replace RenderPearl's source/IR semantics. Handwritten MSL can replace a dynamic shader only after a bounded equivalence proof for its inputs, bindings, output and resource-pack variants. No such replacement is part of this task.

The native PSO path is already in place. Additional default-on precompilation or caching is not justified here without its own correctness and paired performance evidence; the concurrent Apple Silicon efficiency task owns its experiments. Existing resource-pack shader changes must continue to flow through `ShaderSource`, SPIR-V and the cache key. Relevant primary sources: [Apple `MTLRenderPipelineState`](https://developer.apple.com/documentation/metal/mtlrenderpipelinestate), [Apple Metal 4 compiler](https://developer.apple.com/documentation/metal/mtl4compiler), [Khronos SPIRV-Cross README](https://github.com/KhronosGroup/SPIRV-Cross/blob/main/README.md).

## Timestamp capability boundary

Apple documents that counter samples must be resolved after GPU completion and GPU timestamps require calibration against the CPU clock: [sampling](https://developer.apple.com/documentation/metal/sampling-gpu-data-into-counter-sample-buffers), [resolution](https://developer.apple.com/documentation/metal/converting-a-gpus-counter-data-into-a-readable-format), [calibration](https://developer.apple.com/documentation/metal/converting-gpu-timestamps-into-cpu-time). Metal 3 command-stream queries use a stage-boundary blit sample after both renderer fences. Metal 3 render-pass queries require actual draw-boundary support; the backend reports unsupported if the device lacks it. Metal 4 uses `MTL4CounterHeap` and native command/render-encoder timestamp operations. Query slots stay pending until their owning submission succeeds. Native resolve failures remain empty and never become host timestamps. The optional whole-encoder GPU timer cannot satisfy a particular RenderPearl query position.
