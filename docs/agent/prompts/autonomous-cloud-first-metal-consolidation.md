# MetalUniversal Cloud-First Canonical Consolidation Prompt

@GitHub

请立即调用 GitHub 插件，对 `21Z121Z1/MetalUniversal` 做实时检查，然后按照本 Prompt 的阶段门禁实际开始实现。不要只给建议。

## 1. 总目标

目标不是从零重写 Metal renderer，而是把仓库里已经存在、已经部分验证甚至经过 physical Apple GPU 验证的实现逐步收敛进唯一 canonical architecture。

核心原则：

**consolidation first, invention second**

并采用新的验证顺序：

**cloud implementation + GitHub Actions 尽量验证完 → 最后统一 physical Apple Silicon acceptance**

这里的“先云端”不代表降低真机标准。Physical acceptance 是 deferred，不是 waived。

---

## 2. 当前 canonical 状态

当前 canonical branch：

`integration/iris-metal-next`

本 Prompt 创建时 canonical HEAD：

`31853a506b08e7666016497f8732c0dca3f347c8`

它已经包含：

- P0 real Minecraft 26.2 E2E；
- PR #28 / P1 Metal 4 main renderer productionization code；
- three-slot MTL4 allocator/command-buffer lifetime；
- one persistent vertex/fragment/compute argument table per in-flight slot；
- no argument-table allocation in the P1 encoding hot path；
- P1 telemetry；
- physical correctness/performance harnesses。

PR #28 已经 merge。

因此：

**不要继续在 `feature/metal4-main-production` 上开发。**

**不要重新实现 P1。**

P1 的 physical acceptance contract 仍保留在：

`docs/agent/metal4-main-production-acceptance.json`

它仍要求最终在 physical Apple Silicon Mac 上验证：

- real Metal 4 support；
- `MTLResidencySet`；
- candidate main renderer activation；
- framebuffer/readback/present/reload/lifetime；
- Metal API Validation；
- V1 / I0 / I1 paired performance。

这些要求不得删除或降低。

但是从现在开始，后续代码阶段可以在各自 **Cloud Gate 100% 完成** 后继续，最终再做统一 physical acceptance。

---

## 3. 当前 active cloud stage

当前 cloud program branch：

`agent/metal-eval-v3`

当前 PR：

`#29 C0: establish Mac-only Agent Metal Lab cloud validation`

第一步必须重新读取：

- canonical HEAD；
- `agent/metal-eval-v3` HEAD；
- PR #29；
- exact-head workflow runs；
- changed files；
- `docs/agent/cloud-first-metal-program.json`；
- `docs/agent/branch-migration-matrix.json`。

实时 GitHub 数据优先于本 Prompt 里的 SHA 快照。

如果 PR #29 仍未完成：

**只修 C0。禁止开始 P2。**

---

## 4. “100% 完成”的定义

用户要求每一个阶段只有在百分之百完成、对实现百分之百有信心后，才能进入下一阶段。

不要把它解释成主观自信。

每一个 cloud stage 的 `100% COMPLETE` 必须意味着：

1. stage scope 全部实现；
2. focused unit/contract tests PASS；
3. shipping native ABI 编译 PASS；
4. required GitHub Actions 对 current exact SHA 全部 PASS；
5. required hosted Metal path 在 runner capability 存在时真实执行，而不是 skip；
6. machine-readable evidence 已产生；
7. activation 被证明；
8. 没有 silent fallback 被当成目标路径成功；
9. 没有 cancelled / skipped / queued / in-progress required gate；
10. 没有引用旧 SHA 的 green evidence；
11. 没有 unresolved cloud-testable correctness blocker；
12. branch 与 current canonical ancestry 明确；
13. migration source SHA 被记录；
14. tests 与 implementation 一起迁移；
15. stage decision 明确输出。

Cloud stage 只允许：

- `CLOUD_INCOMPLETE`
- `CLOUD_COMPLETE_FINAL_PHYSICAL_PENDING`

最终 integrated program 才允许：

- `PHYSICAL_ACCEPTED`

`CLOUD_COMPLETE_FINAL_PHYSICAL_PENDING` 绝不等于 production physical acceptance。

---

## 5. Fail-closed 规则

以下情况一律不能算 PASS：

- required job cancelled；
- required job skipped；
- capability unsupported 被记成 pass；
- feature requested 但实际 fallback 到 legacy；
- telemetry 缺失；
- malformed JSON evidence；
- source SHA 不匹配；
- JAR/native identity 不匹配；
- mutable shader/world input 未固定；
- framebuffer/readback 缺失；
- current SHA 没有 evidence，却引用旧 run；
- compile success 冒充 runtime correctness；
- GitHub hosted success 冒充 final physical acceptance。

---

## 6. 平台范围

主要平台：

**macOS + Apple Silicon + Metal**

明确禁止新增：

- Amethyst-specific compatibility；
- Amethyst environment shim；
- iOS-specific runtime adaptation；
- iPhoneOS build path 作为当前主线要求；
- iOS Simulator job 作为当前主线要求；
- 为移动环境改变主 renderer architecture。

现有跨平台代码不需要无理由删除，但不要扩大 iOS/Amethyst scope。

---

## 7. 全仓库 branch migration map

完整 machine-readable inventory：

`docs/agent/branch-migration-matrix.json`

当前 24 个 branch 的用途必须按下面规则理解。

### Canonical

`integration/iris-metal-next`

唯一长期 integration base。

### 已被 canonical 吸收

`feature/metal4-main-production`

P1 已通过 PR #28 merge。只作为历史 evidence source，不再开发。

`ci/minecraft-client-e2e-p0-20260819`

P0 已被 canonical 吸收。

### P3 migration sources

`feature/token-native-private-bindings`

已有：

- private token producer route；
- dense binding slots；
- pass-local token session；
- focused tests。

不要重写 token infrastructure。

未来只 selective replay/port 到新的 canonical branch。

`codex/p1-binding-shadow-safe`

只迁 state/binding safety contracts/tests。

`codex/p1-uniform-token-ci`

只迁 uniform token contract/tests。

### P2 semantic source

`feature/iris-semantic-completion`

大型 alternate architecture，包含：

- `IrisMetalExecutionGraph`
- `IrisMetalRenderPassMetadata`
- ping-pong semantics
- render targets
- shadow semantics
- dynamic uniforms
- runtime receipts
- semantic tests

**绝对禁止 wholesale merge。**

用途：挖 semantic contracts、metadata 与 tests，迁入新的 canonical RenderPass V3/TBDR planner。

`research/iris-semantic-contracts-alt`

仅二级研究 source。

### P4 source

`fix/metal4-arena-lifetime`

已有：

- `TerrainMeshGeneration`
- generation identity
- arena lifetime correctness
- Sodium generation hooks
- focused tests

未来 TerrainGpuScene 必须复用 generation semantic。

### MobileGL / ICB / hosted CI source

`master`

不要被 ancestry 数字误导。它包含 PR #23 的大型已验证 lineage：

- command packets；
- state shadow；
- bounded real terrain ICB；
- residency improvements；
- hosted Metal capability CI；
- pipeline prewarm；
- FFM telemetry。

其中 `MetalTerrainIcbBridge` 是未来 P5 的迁移 source。

不要重新从 `IrisMetalIndirectCommandStream` scaffold 造第二套 CPU ICB。

`codex/autonomous-metal-next-20260813`

更晚的 migration source，包含：

- hosted performance experiment；
- `HostedMetalStateSubmissionBenchmark`；
- `HostedMetalStatePathPairedBenchmark`；
- hosted performance analyzer；
- command packets；
- terrain ICB。

### P12 source

`codex/bsl-metalfx-framegen-cutout-20260813`

只在 P12 使用：

- cutout coverage；
- hand coverage；
- BSL/MetalFX handoff；
- reactive path。

### Historical / archive

- `archive/metal4-geometry-handoff-2026-07-28`
- `archive/metal-iris-beta-2026-08-02`
- `archive/metalfx-v1-prototype-2026-08-02`
- `ci/minecraft-client-e2e-20260815`
- `feature/metalfx-framegen-contracts`
- `codex/autonomous-metal-next`

仅在优先 migration source 缺失信息时研究，不作为开发 base。

### No unique implementation

- `perf/swift-performance-by-design-20260819`
- `perf/swift-performance-by-design-final-20260819`
- `perf/swift-performance-by-design-final-validation-20260819`
- `perf/swift-performance-by-design-ready-20260819`

不要把它们误判成新的架构 source。

### Out of scope platform lineage

- `feature/ios-amethyst-runtime`
- `codex/amethyst-ios-runtime-262`

不迁 iOS/Amethyst-specific code。

若里面有真正 platform-neutral、且 preferred migration source 完全没有的算法或 benchmark，可以读设计，但迁移时不得携带平台适配。

---

## 8. Migration discipline

对 diverged branch 禁止：

```text
git merge master
git merge feature/iris-semantic-completion
git merge codex/autonomous-metal-next-20260813
git merge feature/token-native-private-bindings
```

采用：

```text
inspect exact source commit
→ identify bounded component
→ compare current canonical equivalent
→ port smallest coherent change
→ port focused tests
→ run cloud gates
→ record evidence
```

每次迁移记录：

- source branch；
- source SHA；
- destination stage；
- files migrated；
- canonical equivalent reviewed；
- omitted files；
- tests；
- runtime/cloud evidence。

持续更新：

`docs/agent/branch-migration-matrix.json`

---

## 9. C0 — Agent Metal Lab

当前必须先完成这个阶段。

目标：让后续 P2-P12 都能由云端 Agent 得到尽可能高质量的 correctness/performance feedback。

当前 PR #29 已开始添加：

- `.github/ci/HostedMetalCapabilityProbe.swift`
- `.github/workflows/metal-capabilities.yml`
- `docs/agent/cloud-first-metal-program.json`
- `docs/agent/branch-migration-matrix.json`
- `scripts/agent/verify_cloud_program.py`

C0 最终应包含：

```text
agent-evidence/
  environment.json
  capabilities.json
  correctness.json
  activation.json
  performance.json
  terrain.json
  memory.json
  rendergraph.json
  comparison.json
  decision.json
  first-divergence/
```

不要求一次创建没有数据的假文件；只有产生真实 evidence 时再生成。

### C0.1 raw hosted Metal signal

必须在 GitHub `macos-26` Apple Silicon 上真实：

```text
MTLCreateSystemDefaultDevice
→ command queue
→ MSL compile
→ compute dispatch
→ GPU completion
→ shared-buffer exact readback
```

不能只 compile。

### C0.2 shipping native cloud route

逐步把旧 MobileGL capability model selective port 到 canonical：

- hosted offscreen capability；
- physical-only capability reasons；
- reject blanket `if CI: skip Metal`；
- shipping Java/FFM/Swift GPU integration tests 在 hosted capability 存在时实际执行。

注意：

不要直接复制旧 `hosted-metal-gpu-probe.yml`，因为旧 workflow 含 iPhoneOS/Simulator jobs。

只迁 macOS 通用部分。

### C0.3 structured evidence

Agent 第一入口必须逐步变成：

```text
decision
→ activation
→ first divergence
→ metrics
→ trace
→ source
```

而不是先 grep 巨型 log。

### C0 exit

只有 PR exact HEAD 上：

- new `metal-capabilities` PASS；
- existing Minecraft production E2E PASS；
- Unified Eval PASS；
- merge check PASS；
- no required cancelled/skipped job；
- structured evidence PASS；

才允许把 C0 标记：

`CLOUD_COMPLETE_FINAL_PHYSICAL_PENDING`

并 merge 到 canonical。

否则只修 C0。

---

## 10. Cloud phase sequence after C0

严格顺序：

```text
C0 Agent Metal Lab
↓
P2 Iris RenderPass V3 / TBDR graph
↓
P3 token-native + bulk argument patch
↓
P4 TerrainGpuScene
↓
P5 canonical CPU terrain ICB
↓
P6 GPU visibility + GPU ICB generation
↓
P7 TTFV predictive streaming
↓
P8 streaming residency
↓
P9 Placement Sparse backend
↓
P10 GPU-ready terrain cache + MetalIO
↓
P11 shader / PSO pipeline
↓
P12 MetalFX frame pacing
↓
F0 final physical acceptance
```

当前 stage 未 cloud-complete 时，不允许写下一阶段 implementation。

---

# P2 — Iris → Apple TBDR RenderPass V3

从 C0 merge 后的 latest canonical 创建新 branch。

不要从 semantic-completion branch 开发。

目标 ABI：

```text
RenderPassDescriptorV3

colorTextures[]
colorLoadActions[]
colorStoreActions[]
colorClearValues[]

depthTexture
depthLoadAction
depthStoreAction
clearDepth

renderTargetWidth
renderTargetHeight
attachmentMapping
```

planner 对 attachment generation 分析：

- first access
- last access
- full overwrite
- partial viewport
- scissor
- blend
- sampled later
- copied later
- history consumer
- readback consumer
- present consumer

保守规则：

```text
LOAD = any previous content may be needed
CLEAR = explicit semantic clear
DONT_CARE load = guaranteed full overwrite before read
STORE = any later consumer
DONT_CARE store = content provably dead
```

不确定就选择 correctness-conservative action。

允许减少 native encoder/pass，但不允许减少 Iris semantic identity。

比如：

```text
semantic passes = 14
native encoders = 6
physical tile passes = 4
```

CI 仍必须看到 14 个 semantic identities。

重点从 `feature/iris-semantic-completion` 迁 contracts/tests，不迁旧 renderer architecture。

---

# P3 — Token-native + bulk Argument Table patch

不要重写 token infrastructure。

从 `feature/token-native-private-bindings` selective replay 当前仍适用的：

- token layout；
- dense slots；
- pass-local session；
- tests。

最终路径：

```text
private Iris/Sodium token / dense slot
→ BindingSnapshot
→ dirty dense ranges
→ one/bounded FFM crossing
→ MTL4ArgumentTable patch
```

例如：

```text
BindingSnapshot {
  dirtyBufferSlots[]
  gpuAddresses[]
  dirtyTextureSlots[]
  textureResourceIDs[]
  dirtySamplerSlots[]
  samplerResourceIDs[]
}
```

目标 native ABI 可类似：

`metallum_argument_table_patch(...)`

必须测：

- FFM calls / terrain draw；
- FFM calls / Iris fullscreen pass；
- native setters/frame；
- unchanged binding suppression；
- Java allocation bytes/frame；
- CPU encode p50/p95/p99。

---

# P4 — TerrainGpuScene

复用 `fix/metal4-arena-lifetime` 的 generation semantic。

目标：

```text
TerrainGpuScene {
  sectionId
  generation
  bounds
  vertexOffset
  vertexBytes
  indexOffset
  indexCount
  materialRange
  renderPass
  flags
}
```

资源：

```text
persistent vertex arena
persistent index arena
dense section metadata buffer
```

第一代用 ordinary slab/buddy allocator。

不要先做 sparse。

更新模型：

```text
allocate new range
→ upload
→ atomically publish generation
→ defer old range retirement
→ recycle after GPU completion
```

---

# P5 — Canonical CPU Terrain ICB

不是绿地。

优先迁：

- `master` / PR #23；
- `MetalTerrainIcbBridge`；
- late MobileGL branch 的相关 tests/telemetry。

第一阶段不改变 visibility。

CPU Sodium 认为需要 N 个 draws，ICB 必须表达 exact same N draws。

先：

- opaque；
- cutout。

不动 translucent。

oracle：

- draw ID
- draw count
- indexCount
- firstIndex
- baseVertex
- material
- pipeline
- generation
- framebuffer

---

# P6 — GPU visibility

只有 CPU ICB 等价后。

第一步只有 frustum：

```text
SectionMetadata[] + CameraFrustum
→ compute
→ visibility bitset
→ compact commands
→ ICB
```

CPU visible set 是 exact oracle。

之后才 conservative Hi-Z。

原则：

- false positive 可以；
- false negative 不允许；
- 不确定则 visible=true。

不要此时做 mesh shader rewrite。

---

# P7 — TTFV predictive streaming

新增生命周期：

```text
section requested
build queued
build started
mesh complete
GPU range allocated
upload submitted
upload GPU complete
section visible
```

核心指标：

`TTFV = visible timestamp - first requested timestamp`

同时记录：

- meshBuildNs
- meshBytes
- uploadQueueBytes
- uploadedBytes/frame
- arenaAllocatedBytes
- fragmentation
- residentTerrainBytes
- visible/pending sections
- cancelled builds
- wasted build bytes

优先优化 render-section → first-visible，不扩 scope 到 `.mca`/NBT/worldgen，除非 profiling 证明它们是主因。

---

# P8 — Streaming residency

拆：

```text
StaticResidencySet
TerrainResidencySet
FrameResidencySet
```

terrain changes：

```text
residency delta queue
→ batch add/remove
→ commit
→ request ahead of first use
```

不要一 section 一 commit。

---

# P9 — Placement Sparse

只在 ordinary persistent arena 稳定后。

capability-gated：

```text
supported → sparse backend
unsupported → ordinary arena backend
```

不要为了 sparse 破坏常规 Apple Silicon fallback。

---

# P10 — MetalIO GPU-ready terrain cache

禁止：

```text
region.mca → MetalIO → GPU
```

正确：

```text
Minecraft section
→ normal meshing
→ packed GPU-ready mesh
→ disk cache

revisit:
cache hit
→ MetalIO
→ terrain arena
→ visible
```

cache key 至少包含：

- world/dimension；
- section content/generation hash；
- Minecraft version；
- resource-pack/model identity；
- terrain vertex format；
- MetalUniversal mesh format。

---

# P11 — Shader / PSO pipeline

已有 binary archive / telemetry / prewarm source。

不要直接删 `COMPILE_CHAIN_LOCK`。

逐层证明 thread safety：

```text
GLSL preprocess
→ glslang
→ SPIR-V
→ SPIRV-Cross
→ MSL
→ MTLLibrary / MTL4Compiler
→ PSO
```

再引入：

- worker-local contexts；
- parallel compilation；
- pack-specific harvesting；
- per-pack cache identity；
- warm reload。

测：

- cold pack load；
- warm pack load；
- total compile ms；
- longest sync compile；
- archive hits；
- frames >33/50/100 ms attributable to compile。

---

# P12 — MetalFX frame pacing

advanced source：

`codex/bsl-metalfx-framegen-cutout-20260813`

重点不是继续重写 MetalFX，而是拆开：

- source FPS；
- source GPU ms；
- temporal upscale ms；
- interpolation ms；
- presented FPS；
- drawable wait；
- present latency；
- dropped display updates。

不要用 displayed FPS 掩盖 source renderer throughput。

---

## 11. Cloud synthetic benchmark roadmap

逐阶段建立，但 harness 必须尽可能调用 shipping native ABI，而不是另写 fake renderer。

### TerrainSubmitBench

固定 10k section records / visibility / geometry / pipeline groups。

输出：

- CPU encode
- FFM calls
- native calls
- draws
- ICB count
- command buffers
- encoder count
- GPU time（runner capability允许时）

### TerrainStreamingBench

持续 load/rebuild/unload。

输出：

- arena bytes
- fragmentation
- resource count
- residency changes
- upload bytes
- memory slope

### IrisGraphBench

覆盖：

- MRT
- ping-pong
- partial viewport
- blend
- compute RAW
- history
- depth
- capture
- dead attachment

验证 load/store/fusion/barrier exact planner result。

### GpuCullingBench

CPU oracle vs GPU visibility vs ICB commands machine-readable exact diff。

---

## 12. Cloud paired performance

能在 hosted runner 上稳定测的 microbench 必须 base/head 同 runner：

```text
git worktree BASE
git worktree CANDIDATE
build both Release
warm both
BASE → CANDIDATE
CANDIDATE → BASE
repeat >= 4 paired blocks
```

不能跨 runner 比绝对数。

保留：

- ABBA/alternating order；
- correctness first；
- structured evidence；
- positive block fraction；
- guardrails。

hosted performance 只用于相对 microbenchmark signal。

最终 Minecraft performance acceptance 仍由 physical runner 完成。

---

## 13. Final Physical Acceptance — F0

只有所有 cloud stages 完成后，才做统一最终真机签收。

至少包括：

### P1 carried gate

- real Metal 4；
- `MTLResidencySet`；
- V1 / I0 / I1 exact-bit correctness；
- V1 / I0 / I1 paired performance。

### integrated renderer correctness

- real Minecraft world；
- Sodium；
- Iris Potato；
- Iris BSL；
- framebuffer/readback；
- present；
- reload；
- clean shutdown；
- Metal API Validation；
- no lifetime errors。

### terrain

- T0 static high render distance；
- T1 fixed high-speed streaming path；
- ICB activation；
- GPU visibility if implemented；
- no false-negative culling；
- TTFV；
- arena lifetime；
- residency correctness；
- long-run memory slope。

### performance

- same-machine base/head；
- fixed world/camera；
- immutable shader pack identity；
- ABBA；
- p50/p95/p99；
- source FPS/GPU ms；
- CPU encode；
- FFM/native crossings；
- commitCallsPerFrame；
- stutter guardrails。

只有 F0 全部 PASS：

`PHYSICAL_ACCEPTED`

才允许做最终 default-enable / release readiness decision。

---

## 14. 明确禁止事项

不要：

1. 重建 P1；
2. 在 C0 未完成时开始 P2；
3. wholesale merge master；
4. wholesale merge semantic-completion；
5. wholesale merge token branch；
6. 重写已有 CPU ICB；
7. 重写已有 token infrastructure；
8. GPU culling 先于 CPU ICB exact equivalence；
9. mesh-shader terrain rewrite 提前；
10. MetalIO 直接读取 `.mca`；
11. Placement Sparse 提前；
12. 全局 async compute 提前；
13. 一次打开所有实验 flags；
14. 新增 Amethyst compatibility；
15. 新增 iOS-specific adaptation；
16. 因 hosted runner capability 不足而降低最终 physical gate；
17. 用旧 SHA green run 当 current SHA evidence；
18. 把 skipped/cancelled 当 success；
19. 用 test renderer 替代 shipping ABI 作为主要 correctness proof。

---

## 15. 每个 stage 的工作方式

每一个 stage：

```text
read current GitHub state
→ read migration matrix
→ inspect source branch exact commits
→ define bounded stage contract
→ implement smallest coherent change
→ focused tests
→ cloud runtime/hosted Metal where supported
→ structured evidence
→ exact-head GitHub Actions
→ stage decision
```

如果失败：

只修当前 stage。

不要顺手写下一个 stage。

---

## 16. 每阶段输出格式

未完成：

```text
Stage:
Status: CLOUD_INCOMPLETE
Current SHA:
Branch:
PR:
Completed gates:
Missing/failed gates:
Evidence:
Next cloud stage allowed: NO
```

云端完成：

```text
Stage:
Status: CLOUD_COMPLETE_FINAL_PHYSICAL_PENDING
Source SHA:
Merged canonical SHA:
Branch:
PR:
Implemented:
Migrated from:
Tests:
Hosted Metal activation:
GitHub Actions:
Structured evidence:
Physical-only obligations preserved:
Known cloud blockers: NONE
Next cloud stage allowed: YES
```

最终真机：

```text
Program status: PHYSICAL_ACCEPTED
Exact canonical SHA:
Production JAR SHA256:
Native dylib SHA256:
Physical correctness:
Physical paired performance:
Presentation/reload/lifetime:
Terrain/streaming:
Metal validation:
Known blockers: NONE
```

---

## 17. 现在立即执行

现在不要重新规划整个项目后停下来。

请实际使用 GitHub 插件：

1. 读取 PR #29 current exact HEAD；
2. 读取它所有 workflow runs；
3. 如果有 queued/in-progress，保持 C0 `CLOUD_INCOMPLETE`；
4. 如果有 failure，读取 job/log/patch 并只修 C0；
5. 检查 Mac-only hosted Metal probe 是否真实执行；
6. 检查 structured evidence 是否生成；
7. 检查 existing Minecraft E2E / Unified Eval / merge check；
8. 检查新 workflow 没有 iOS/Amethyst-specific path；
9. 逐步迁移旧 PR #22 的 capability-driven **macOS-only** test routing，但不要直接复制其移动平台 jobs；
10. 只有 PR #29 exact HEAD 所有 C0 required gates PASS，才将其标记 cloud-complete、合入 canonical；
11. 然后从新的 canonical HEAD 创建 P2 branch；
12. P2 未 cloud-complete 前不得开始 P3。

最终原则：

**在云端把所有能证明的代码、ABI、semantic contract、synthetic workload、hosted Metal execution、结构化 evidence 和相对 microbenchmark 尽可能验证完；最后再把同一个 content-addressed canonical 产品拿到真实 Apple Silicon Mac 做统一 physical acceptance。**
