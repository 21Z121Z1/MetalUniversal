# MobileGL Comprehensive Completion Report

## Identity

- Repository: `21Z121Z1/MetalUniversal`
- Starting remote branch: `origin/feature/mobilegl-inspired-hotpath`
- Starting SHA: `e52d84356b7edb7ff4ca2f147fcedb4f7a54b421`
- Worktree branch: `codex/mobilegl-comprehensive-completion`
- Worktree: `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/.codex/worktrees/mobilegl-completion-20260804`
- Current implementation SHA before final report commit: `ea629a181104e47ace041bc4b38f9f90049f90b7`
- Required push branch: `feature/mobilegl-inspired-hotpath`
- MobileGL: `dev`, SHA `598c5497b06c57e1ab2586a560aaaf5cc957b772`
- MoltenVK: 1.4.2, arm64 dylib SHA-256 `33ffaf11e8d042fd078f1ca4daf44a1f75697f80c6f0ad35e3b10ac4994bee32`
- Host: MacBookPro18,3, Apple M1 Pro (10 CPU cores, 16 GPU cores, 16 GB unified memory)
- OS/toolchain: macOS 26.5.1 (25F80), Xcode 26.6 (17F113), Swift 6.3.3, OpenJDK 25.0.2
- Client: Minecraft 26.2, Fabric Loader 0.19.3, Sodium 0.9.1, Iris 1.11.2
- Shader archives: `bsl-shaders.zip` and `potato-shaders.zip` were the fixed local packs used by the correctness lanes. Their run manifests and hashes are retained under `build/agent-runs/final-bsl` and `build/agent-runs/final-potato`.
- Hardware power/display for the formal run: AC, 3024x1734 drawable (1708x960 logical validation surface), Retina scale 2.

## Goal

The task goal is recorded in [goal.md](goal.md): make OpenGL-observable semantics single-owner and validated, separate compatibility/state/execution layers, complete the Java -> FFM -> Swift -> Metal 3/4 path, preserve Sodium/Iris/shader-pack behavior, prove the result in Minecraft and against MobileGL/MoltenVK, and deliver a clean fast-forward push.

Implementation and native correctness work reached the current Metal 4 candidate. The goal as a whole was not reached because the required MobileGL runtime differential was not available on this host, the fourth performance block was rejected by the locked WindowServer gate, and the completed blocks did not satisfy every performance guardrail. The correct final status is therefore `PARTIAL — external environment gate missing`.

## MobileGL Full Analysis

The source map and semantic matrix are in [source-map.md](source-map.md) and [semantic-matrix.md](semantic-matrix.md). The analysis covered the implementation rather than only the hot path:

| Area | MobileGL evidence | MetalUniversal adoption |
| --- | --- | --- |
| API semantics and validation | `MG_Impl/GLImpl/Exporting/Definitions.cpp`, `GetProcAddress.cpp`, buffer/texture/VAO/FBO validators, `MG_State/GLState/ErrorState` | Compatibility adapters feed one validated semantic owner. Invalid packet operations are rejected before operation zero; compatibility, ABI and Metal failures remain distinct. |
| Context/state machine | `MG_State/GLState/Core.*`, `RenderState`, `TextureUnit`, indexed bindings and generation/version counters | Render state, compiled binding snapshots, frame/pass state and resource generations are explicit and reset at encoder/pass boundaries. |
| Object model/lifetime | `BufferState`, `TextureState`, `SamplerState`, `ProgramState`, `VertexArrayState`, `FramebufferState`, `RenderbufferState` | API-visible identity is separate from replaceable native backing. Generation-aware bindings and completion-driven release prevent stale handles and GC-dependent lifetime. |
| Shader/program/link/reflection | `ShaderTranspiler`, `ProgramFactory`, `UniformManager`, `SpvcSession`, `MG_Test/Program`, shader lowering passes | Iris GLSL is translated and reflected into typed Metal layouts. Pipeline identity includes shader, vertex input, attachment, blend/depth/stencil, resource layout and Metal mode. Runtime admitted draws use compiled slots, not string reflection. |
| Render and compute execution | `MG_Impl/GLImpl/Drawing/GL_Drawing.cpp`, `Renderer/VulkanRenderer.*` | Render, compute, clear, transfer and presentation operations are normalized before Java FFM submission. Render and compute packet ABI validation is atomic and fail-closed. |
| Multi/indirect draw and terrain | MobileGL ordered renderer and bulk draw tests | Sodium terrain scope preserves index type, first index, base vertex, transforms, material and resource generations. Real candidate runs admitted terrain ICB work with zero fallback and zero replay. |
| Clear/copy/blit/mipmap/readback | `GL_Buffer.cpp`, `GL_Texture.cpp`, `GL_Framebuffer.cpp`, `VkClearManager`, pixel-store processors | Metal pass/load-store, transfer ordering, row alignment and GPU readback are represented in the native validation lanes. Helper encoders cannot leak state shadow. |
| Barriers/sync/query/presentation | `DirectVulkanResourceState.h`, `GL_Query.cpp`, `GL_Sync.cpp`, `SwapchainObject.*` | RAW/WAR/WAW dependencies, command-buffer boundaries, residency, drawable generation and deferred release are explicit. Missing presentation timing fails closed instead of becoming zero. |
| Frames and memory | `Renderer/FrameContext.*`, `BufferArena.*`, `BufferSlice.h`, VMA-backed resource managers | Three reusable Metal 4 command buffers, per-submit ownership, bounded backing pools and completion serials are used. Vulkan descriptor pools/VMA/image layouts were not copied. |
| Compatibility/workarounds | MobileGL latest workaround history and DirectVulkan paths | OpenGL semantics were adopted; Vulkan/MoltenVK-specific workarounds and MobileGL stubs were classified and not blindly ported. |
| Tests/benchmarks | `MG_Test/*`, `MG_Benchmark/*` | Semantic cases became Java/native packet, shader, MRT, render-compute, terrain and physical-GPU suites. Benchmark numbers are only compared when the same Minecraft protocol is used. |

The implementation deliberately follows MobileGL's front-end -> semantic state -> backend operation boundary. Swift/Metal consumes typed handles and plans; it does not rediscover GL semantics from Java strings.

## Implementation

### Java and FFM

- Render and compute packets are bounded, aligned and versioned; validation rejects malformed operations without partial execution.
- Render and compute command packets are now enabled by default for validated paths, with explicit `false` properties required to disable them (`c87ad88`).
- Packet telemetry records calls, operations and replay count. Candidate Minecraft runs show render packet calls and operations with `renderCommandPacketReplays=0`; the physical compute suite shows real dispatch packet execution with replay zero.
- The FFM bridge carries compact off-heap submissions and generation-aware resource identities. Formal candidate samples reduced Java-to-native calls per frame from about 1,849.6 to 1,636.3 (three complete pairs).
- The compute backend integration test now forwards packet telemetry and asserts the actual Metal 4 renderer identity (`31ff6fa`).

### Swift, Metal 3 and Metal 4

- Metal 4 device/session, compiler, reusable command-buffer leases, explicit residency and upload barrier contracts are engaged on the M1 Pro.
- Metal 3 remains the capability fallback and was exercised by the native Metal 3 validation task; semantic packet and binding contracts are shared between the adapters.
- Argument tables/buffers, compiled binding plans, pipeline archive/prewarm identity, frame-graph hazard ordering, deferred release and terrain ICB paths are present in the baseline implementation and exercised by the current tests.
- Metal API Validation and GPU Validation report zero legacy encoder violations in the BSL and Potato real-client correctness artifacts.

### Iris, Sodium and shader translation

- Iris shader translation, reflection and typed resource layout were exercised for BSL and Potato terrain classes. No shader-pack-name special case was added.
- Sodium terrain classes SOLID, CUTOUT and TRANSLUCENT compile to device pipelines in the shader translation suite; the runtime ICB path admits qualifying terrain ranges and rejects unsafe ranges with bounded budget reasons.
- The BSL run logs include the `armor_cutout_no_cull` pipeline and the distant-LOD/cutout scene producers. These are semantic/readback evidence, not an attended scanout claim.

## Validation

Passed before the final report:

- `./gradlew clean test --stacktrace` (pass)
- `./gradlew buildMacNative build --stacktrace` (pass after packet test property fixes)
- `./gradlew --no-daemon verifyIsolatedClientProfiles` (pass; validates the isolated Metal 4 + Iris profile)
- `./gradlew --no-daemon tasks --all` (pass; exposes `runClientMetal4Iris`)
- `bash -n scripts/launcher/run_metal4_iris.sh` (pass)
- `bash scripts/agent/doctor.sh` (0 failures; expected worktree/branch warnings)
- `bash scripts/agent/verify.sh static` (pass)
- `bash scripts/agent/verify.sh gpu` (pass)
- `bash scripts/agent/verify_unified_eval.sh` (pass)
- Unified-evaluation Python self-tests (pass)
- Metal API/GPU Validation native MRT, compute, Iris-target and shader-translation suites (pass)
- Physical compute packet execution: packet calls/operations > 0, replay 0

The complete formal run is `build/agent-runs/unified-eval-20260804T083907Z`. It contains the exact commands, properties, binary identity and every trial artifact. Its correctness gate passed and its admission probe was admitted. The probe recorded non-zero/varying GPU readback, render packet activation, terrain ICB activation, runtime pipeline compilation zero and replay zero.

The formal run exit status was 2. The fourth block did not fabricate metrics: `runClient` was rejected at [build.gradle:561](../../../build.gradle:561) with:

```text
Visible MetalFX presentation validation cannot run: the macOS console is locked;
WindowServer cannot provide nonzero presentedTime callbacks.
```

The analyzer therefore reported `state=rejected-regression`, with missing candidate runtime-pipeline evidence for the incomplete block and observed long-frame guardrail differences in the completed data. This is an external presentation gate plus an unaccepted performance result, not a pass.

## Minecraft Acceptance

| Profile/evidence | Result | Artifact |
| --- | --- | --- |
| Sodium-only profile A | Not rerun in this final worktree evidence set | No final-A artifact; do not infer pass from shader runs |
| Iris + BSL profile B | 17/17 render-contract captures, 14,942 pass records, 0 failed/dropped/forced-close/invalid refs, Metal 4 engaged, 0 legacy encoder violations | `build/agent-runs/final-bsl` |
| Iris + Potato profile C | 17/17 render-contract captures, 11,258 pass records, 0 failed/dropped/forced-close/invalid refs, Metal 4 engaged, 0 legacy encoder violations | `build/agent-runs/final-potato` |
| Distant terrain/LOD scene | Automated scene installed and captured through semantic pass manifests; no first-producer divergence recorded | BSL/Potato `render-contract/pass-manifest.json` |
| Cutout foliage and translucent scene | Automated cutout leaves/grass/sky and hand/translucent scenes executed; resource and generation manifests passed | BSL/Potato artifacts |
| Armor stand/entity cutout | `minecraft:pipeline/armor_cutout_no_cull` was compiled and cached in the real BSL client log; no invalid resource reference or render-contract failure | `final-bsl` client log and pass manifest |
| GUI, world lifecycle and normal exit | Automated clients exited normally after validation scenes and saved all dimensions | BSL/Potato `run-state.json` and client logs |
| Attended resize/fullscreen/scanout visual judgment | Not claimable while WindowServer console remained locked | Formal-run failure log |

The structured evidence supports the fixes for the reported distant rendering and transparent armor-stand paths, but it does not replace a human attended screenshot when the console is locked.

## Performance

The formal harness used the required 30-second warmup and 120-second sample. Three paired blocks completed; block four was fail-closed by WindowServer. The harness baseline is the final binary with the candidate flags disabled, not a checkout of the starting SHA, so this table is diagnostic and not a valid start-SHA acceptance claim.

| Metric | Baseline mean (3 blocks) | Candidate mean (3 blocks) | Directional result | MobileGL/MoltenVK |
| --- | ---: | ---: | --- | --- |
| Average FPS | 18.926 | 19.074 | +0.78%; not consistent across 3/3 pairs | unavailable |
| Median FPS | 19.239 | 19.163 | -0.40% | unavailable |
| 1% low FPS | 14.122 | 14.129 | +0.04% | unavailable |
| 0.1% low FPS | 7.202 | 6.082 | -15.55% | unavailable |
| Frame p95 (ms) | 63.524 | 62.974 | -0.86% | unavailable |
| Frame p99 (ms) | 70.810 | 70.783 | -0.04% | unavailable |
| Frame p99.9 (ms) | 158.767 | 188.036 | regression | unavailable |
| Frames >33.3 ms | 2,256 | 2,270 | regression in mean | unavailable |
| Frames >50 ms | 1,724 | 1,675 | improvement in mean | unavailable |
| Frames >100 ms | 3.0 | 5.0 | guardrail regression | unavailable |
| CPU render/encode median (ms) | 50.597 | 50.742 | +0.29% | unavailable |
| GPU frame median (ms) | 51.850 | 52.226 | +0.72% | unavailable |
| FFM calls/frame | 1,849.601 | 1,636.323 | -11.53% | unavailable |
| Native setter ops/frame | 1,883.497 | 1,806.605 | -4.08% | unavailable |
| Render packet calls/frame | 0 | 672.827 | activated; replay 0 | unavailable |
| Argument table updates/frame | 0 | 433.029 | activated | unavailable |
| Terrain ICB accepted | 0 | 2,289/block | activated; fallback 0 | unavailable |
| Runtime pipeline compiles | 0 | 0 in 3 complete blocks | block 4 missing due lock | unavailable |
| Peak memory, GC, store/load bytes | unavailable | unavailable | bridge does not expose trustworthy values | unavailable |

The candidate reduced bridge/setter overhead and activated packet/argument/ICB work, but the formal acceptance rule was not met: the fourth pair is incomplete, one low-FPS/long-frame guardrail regressed, and no MobileGL comparison exists. No quality or render-work reduction was used to obtain the observed changes.

## Git

New commits on top of the fetched task baseline:

- `c87ad88 perf: enable validated command packets by default`
- `31ff6fa test: wire compute packet validation into native suite`
- `ea629a1 perf: add Metal 4 Iris launcher profile`
- `5e6d52a docs: record MobileGL completion evidence`

The task branch was fast-forwardable from `bb02de095da4f00ee373a0475eae54f9b2e67bf9`. The delivery command was:

```text
git push origin HEAD:feature/mobilegl-inspired-hotpath
```

It completed without force (`bb02de0..5e6d52a`). Post-push verification reported local and remote HEAD as `5e6d52af02ca6c1a9a5cf539b44d30d231587d67`, with a clean worktree. This final-report status update is itself a docs-only follow-up commit; the same equality check is rerun after it is pushed. Runtime artifacts, logs, worlds, shader archives, dylibs, captures and `.codex-run` files are excluded from Git.

## Final Status

`PARTIAL — external environment gate missing`

The implementation and native/real-client correctness evidence are substantial, but the required MobileGL/MoltenVK differential, attended WindowServer presentation gate, Sodium-only final lane, and accepted four-block performance decision are not all available. The report intentionally does not label those missing gates as passed.
