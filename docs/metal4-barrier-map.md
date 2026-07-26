# Metal 4 屏障映射表（迁移规格 M6 的产出物 = M7e 的施工图）

日期：2026-07-27（**第二版，方法论已从「逐 fence」改为「逐 encoder」**）
上游：`MinecraftMetal_Metal4_Migration_Specs_2026-07-27.md` M6
API 真值源：`docs/mtl4-api-probe.swift`

M6 是纯设计项，不改一行行为代码。本文是 M7e 的施工图。Metal 4 **没有驱动侧 hazard tracking**，漏一条边就是随机花屏，所以完整性本身就是验收内容。

---

## 0. ★ 第一版的方法论错误（先读这节）

第一版按规格字面做法「**枚举 fence 调用点，逐条翻译成屏障**」。**这个方法有结构性漏洞：没有 fence 的 encoder 它永远找不到。**

实测（枚举全部 `makeRenderCommandEncoder(` / `makeComputeCommandEncoder()` / `makeBlitCommandEncoder()`）确实存在**两个零 fence 的 encoder**：

| 锚点 | encoder | 读 | 写 | fence |
|---|---|---|---|---|
| `metallum_metalfx_mark_transparency` | compute | translucent / itemEntity / particles / weather / clouds（均为前面 render pass 的输出） | `reactive` | **函数体 0 处；ABI 签名里连 fence 参数都没有** |
| `metallum_metalfx_encode_motion_v2` 内 `historyBlit`（label `MetalFX Previous Depth Update`） | blit | `depthTexture` | `previousDepthTexture` | **0 处** |

两者的纹理都是 `hazardTrackingMode = .untracked`，**驱动不兜底**。紧邻的 `encodeCutoutReactiveMask` 是传了 fence 的 —— 说明这是遗漏而非设计。

**结论**：
1. 这在 **Metal 3 下已经是既有的潜在竞态**，靠 Apple GPU 的实际调度侥幸没暴露；Metal 4 下是确定性 bug。
2. 按 **M0.10「改动范围外的代码一行都不要动」**：本文只记录，**不在此修 Metal 3 路径**。已单开审计项（见审计 §3 的 P4-3 状态块）。
3. **清点必须逐 encoder，不能逐 fence**。下表因此以 encoder 为行，`wait` / `update` 空白格就是缺口。

> 复核命令：
> ```bash
> grep -n "makeRenderCommandEncoder(\|makeComputeCommandEncoder()\|makeBlitCommandEncoder()" src/main/native/MetallumNative.swift
> ```

---

## 1. 逐 encoder 清点（Swift 17 处 + Java 2 个工厂）

队列列：**main** = Java 驱动的主队列；**present** = FG present 线程（M4 已切 Metal 4）。

| # | 锚点函数 | encoder | 队列 | 现有 wait | 现有 update | M7e 处置 |
|---|---|---|---|---|---|---|
| E1 | `MetalFrameGenerationPresenter.encodeCopy`（:351 组） | render | present | — | — | 无需屏障：present 队列的次序由 `readyEvent` 保证（见 §3） |
| E2 | FG 输入拷贝（:1013） | blit | **main→present 跨界** | `globalFence` / `transferFence` | `transferFence` / `globalFence` | **只能 `MTLSharedEvent`**，见 §3。**不是 fence、也不是屏障** |
| E3 | `MetalFrameGenerationPresenter.encodeCopy`（:1159） | render | present | — | — | 同 E1 |
| E4 | `metallum_metalfx_apply_cutout_reactive` | compute | main | ✓ | ✓ | 消费者屏障（§2） |
| E5 | `metallum_metalfx_encode_hand_overlay` | compute | main | ✓ | ✓ | 消费者屏障 |
| E6 | `metallum_metalfx_clear_motion_inputs` | compute | main | ✓ | ✓ | 消费者屏障 |
| **E7** | **`metallum_metalfx_mark_transparency`** | compute | main | **缺** | **缺** | **新增消费者屏障；Metal 3 竞态另记审计项** |
| E8 | `metallum_metalfx_encode_v2` cameraEncoder | compute | main | ✓ | ✓ | 消费者屏障 |
| E9 | `metallum_metalfx_encode_v2` mergeEncoder | compute | main | ✓ | ✓ | 消费者屏障，**dispatch→dispatch**（读 E8 的输出，别照抄 `.fragment`） |
| **E10** | **`metallum_metalfx_encode_v2` historyBlit** | blit | main | **缺** | **缺** | **新增消费者屏障；同上另记** |
| E11 | `metallum_encode_texture_copy` | render | main | ✓ | ✓ | 消费者屏障 |
| E12 | `metallum_MTLCommandBuffer_makeBlitCommandEncoder`（导出，:4409） | blit→**compute**（M7h） | main | Java J1/J2 | Java J7 | 消费者屏障，由 Swift 在 encoder 创建处发 |
| E13 | `metallum_MTLCommandBuffer_makeRenderCommandEncoder`（导出，:4709） | render | main | Java J3–J5 | Java J6 | 同上 |
| E14 | `metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2`（导出，:4767/:4857） | render | main | Java J3–J5 | Java J6 | 同上 |
| E15 | `metallum_MTLCommandBuffer_clearColorDepthTexturesRegion` | render | main | ✓ | ✓ | 消费者屏障 |
| E16 | `metallum_MTLCommandBuffer_encodePresentTextureToDrawable` | render | main | ✓ | ✓ | 消费者屏障 |
| E17 | MetalFX scaler 内部（`scaler.fence = fence`，:3409 / :3640） | MetalFX 内部 | main | 由 MetalFX 自行 wait/update | 同 | **保留 fence，见 §4.2** |

Java 侧 2 个工厂（`MTLCommandBuffer.makeRenderCommandEncoder` / `makeBlitCommandEncoder` 的包装）不持有语义，语义在 `MetalCommandEncoder` 的 7 处调用点上，已并入 E12–E14。

### fence 处数对账

规格验收写「34 处」。实测：Java **7** 处语义调用点（其中 3 处受 `SPLIT_FENCE` 互斥，**运行期实际生效 4 或 6 处**）+ Swift **20** 处 + 4 处 ABI 透传导出体 + 1 处 `makeFence`。34 落在「27 语义」与「44 含壳」之间，最可能把导出体与部分包装壳计入了。**本文不再以 fence 数为验收口径，改用 §1 的 17 个 encoder 全覆盖。**

---

## 2. 屏障形态：**单侧即完整，不要成对**

规格逐行给出「生产者侧 + 消费者侧」两条。**这是过同步。** SDK 头（`MTL4CommandEncoder.h`）的定义：

- 消费者形态 `barrier(afterQueueStages:beforeStages:visibilityOptions:)`：`beforeStages` 作用于**当前 encoder** 的工作，`afterQueueStages` 覆盖**当前 encoder 之前提交到同队列的全部匹配阶段**。
- 生产者形态 `barrier(afterStages:beforeQueueStages:visibilityOptions:)`：保证**后续 encoder** 中匹配 `beforeQueueStages` 的工作，不早于当前及之前 encoder 中匹配 `afterStages` 的工作完成。

**任一形态单独使用就是一条完整的跨 encoder 边** —— 与 fence 不同，屏障**不需要成对**。规格「逐行两侧 + 首次统一 `.device`」叠加，会让 Metal 4 的同步点**多于**现有 fence 链，而 M7 验收里有一条是「System Trace 确认时间线与 Metal 3 相当或更好」——照规格写会自相矛盾地把自己卡红。

**施工规则（取代规格的双侧写法）**：
1. **每条边只发一条屏障，统一用消费者形态**，发在读方 encoder 创建后**立刻**。理由：读方最清楚自己要读什么，且消费者形态天然覆盖「之前所有匹配阶段」，与 Metal 3 单 fence 的粗粒度语义最接近。
2. **只有一种情况需要生产者形态**：写方之后没有任何读方 encoder 会再创建（例如帧尾写入、跨命令缓冲的边）。本工程目前无此情形。
3 . 首次落地统一 `visibilityOptions: .device`。收窄到 `.none` 是第二步，必须单独跑一轮金样。
4. **TBDR 约束**：render encoder 上，`.fragment` / `.tile` 不得出现在 `barrier(afterEncoderStages:)` 的 after 位置（同 encoder 内形态）。队列形态不受此限。
5. `MTLStages` 是 **OptionSet**（已 typecheck），多阶段合并成一条屏障，不要拆成多条 —— 每条都会各自刷一次缓存。

### 各 encoder 的消费者屏障（`.device`，逐条可抄）

| encoder | 消费者屏障 |
|---|---|
| E4/E5/E6/**E7** compute，读 render 输出 | `barrier(afterQueueStages: .fragment, beforeStages: .dispatch, visibilityOptions: .device)` |
| E9 compute，读 E8 的 compute 输出 | `barrier(afterQueueStages: .dispatch, beforeStages: .dispatch, visibilityOptions: .device)` |
| **E10** compute（M7h 后 blit 折叠进 compute），读 render 输出的 depth | `barrier(afterQueueStages: .fragment, beforeStages: .blit, visibilityOptions: .device)` |
| E12 compute（上传拷贝），读 render 写过的 RT（WAR） | `barrier(afterQueueStages: .fragment, beforeStages: .blit, visibilityOptions: .device)` |
| E11/E15/E16 render，读上游 RT | `barrier(afterQueueStages: .fragment, beforeStages: .fragment, visibilityOptions: .device)` |
| E13/E14 render（Java 驱动，J3–J5 合并） | `barrier(afterQueueStages: [.blit, .fragment], beforeStages: [.vertex, .fragment], visibilityOptions: .device)` |

---

## 3. ★ R6-1：`signalEvent` 的位置，会在 M7 开启时破坏已落地的 M4

**已核 SDK 头**：`MTL4CommandBuffer` **没有** `encodeSignalEvent` / `encodeWaitForEvent`。唯一的是 `MTL4CommandQueue.signalEvent(_:value:)`，文档原文：

> Schedules an operation to signal a GPU event with a specific value **after all GPU work prior to this point is complete.**

「prior to this point」指的是**队列时间线上此刻之前的工作，即已经 commit 的工作**。

现状：FG 输入拷贝在 `blit.endEncoding()` 之后紧接一行 `commandBuffer.encodeSignalEvent(readyEvent, value:)` —— 这是**命令缓冲级**的，信号的位置就是它在缓冲里的位置。

**若按现有位置直译成 `queue.signalEvent`（在 commit 之前调用）**：事件会排在这个命令缓冲的 commit **之前** → present 线程在拷贝尚未执行时就被放行 → 读到上一帧或半写的输入 → **随机撕裂/花屏，且不报任何错**。

这与 M4 已修掉的 `queue.waitForEvent` 悬挂是**同构问题的镜像版**：Metal 3 里「记录在缓冲内、位置即语义」，Metal 4 里「队列时间线操作、调用即生效」。

**处置（M7e 必须选一个，推荐 A）**

- **A（推荐）：把 FG 输入拷贝拆成独立的 MTL4 命令缓冲**，`queue.commit([copyBuffer])` 之后**立刻** `queue.signalEvent(readyEvent, value:)`。语义最接近现状、延迟不变，代价是多一个命令缓冲 + 一个 allocator。
- B：把 `signalEvent` 挪到整帧 commit 之后。实现最简，但 present 线程要多等整帧主队列工作，吃掉 FG 的 deadline 预算 —— 与 M4 验收「deadline miss 不升」直接冲突。

**这是唯一一处 M7 会反向影响 M4 已落地代码的地方。** M7a 落地时必须同时处理，否则 M7 开关一开 FG 就坏。

---

## 4. 三条规格错误（会让人白做工或做错）

### 4.1 `.resourceAlias` 不适用 —— 删掉，用 `.device`

规格 M6 表最后一行给了别名边，还特意标「别漏」，理由是「`MetalTransientMemory.rotate()` 的块回收与 buffer 池复用会让同一段虚拟地址换用途」。**这个理由不成立**：

- 本工程 **`MTLHeap` 用量为 0**（已 grep 确认，规格自己的迁移面表也写 `useHeap` = 0）。
- `recycleDynamicBacking` 推回的是**整个 `MTLBuffer` 句柄**，`MetalTransientMemory` 回收的是**整块对象** —— 换用途的是「同一个资源对象」，不是「两个资源对象映射同一物理页」。这不是 aliasing。
- 唯一真正的别名是 `buffer.makeTexture(descriptor:offset:bytesPerRow:)`（:4638，buffer 背衬纹理视图，与其 buffer 共享物理内存）。**即便这一处，Apple 的 `managing-metal4-synchronization` 指引也是用 `.device`**；`.resourceAlias` 的文档写明「可能刷到系统内存一致点 —— 比 Device 更重」。

**处置：删除别名边这一行，改为对 buffer 背衬纹理视图与其 buffer 之间的依赖使用普通 `.device` 屏障。** 若将来引入 `MTLHeap` 或 placement sparse，再重新评估。

### 4.2 `scaler.fence` 必须保留 —— 不要按「全量换屏障」删掉

`fence` 属性在 `MTLFXTemporalScalerBase` / `MTLFXFrameInterpolatorBase` 上，`MTL4FX*` 继承之 → **Metal 4 下依然存在且合法**。本工程**在用**：`scaler.fence = fence`（:3409、:3640）。

它是 MetalFX **内部**用于跨自己 encoder 同步的 fence。规格 M6 把它归入「全量换成屏障」、M7e 又写「删掉跨 encoder 的 fence」——**它恰恰是跨 encoder 且必须保留**。

**处置：M7e 的「删 fence」范围明确排除 `scaler.fence` / `interpolator.fence`。** 只删 §1 表里 main 队列上我们自己发的跨 encoder fence。

### 4.3 fence 数不是验收口径

见 §1 末尾。改用 17 个 encoder 全覆盖。

---

## 5. Java ABI 可以原样复用（利好）

已核：项目 `mtl/MTLRenderStages.java` 的值与 SDK `MTLStages` **低位逐位相同**。

| 名称 | 项目值 | SDK `MTLStages` |
|---|---|---|
| Vertex | 1 | `1 << 0` = 1 ✓ |
| Fragment | 2 | `1 << 1` = 2 ✓ |
| VertexAndFragment | 3 | 1\|2 = 3 ✓ |
| Tile | 4 | `1 << 2` = 4 ✓ |
| （新增）Dispatch | — | `1 << 27` |
| （新增）Blit | — | `1 << 28` |

**⇒ 现有 `long stages` 的 Bridge ABI 可以原样复用给 Metal 4 屏障**，只需给枚举补 `Dispatch(1L << 27)` / `Blit(1L << 28)`。直接支撑 M7 的「尽量不改 Java ABI」。

### ★ 一个编译器不会提醒你的雷

`setArgumentTable(_:stages:)` 的 stages 是 **`MTLRenderStages`**；屏障的是 **`MTLStages`**。两个类型都有 `.vertex` / `.fragment`，**语境推断、拼写完全一样、写错了编译器不报错**。规格附录 A 缺这条。

M7c 与 M7e 会在同一段代码里交替用到这两个类型 —— **建议在这两处显式写全类型名**（`MTLStages.fragment` / `MTLRenderStages.Fragment`）而不是依赖 `.fragment` 推断。

---

## 6. ★ M7 的另一个隐患：MetalFX 的 6 个 `@_cdecl` 首参类型

6 个 MetalFX 导出的首参是 `_ commandBuffer: MTLCommandBuffer`，由 Java 传进来。**M7 开关一开，Java 传的是 `MTL4CommandBuffer`**，而 `MTLCommandBuffer` 与 `MTL4CommandBuffer` 是**两个独立协议，不互通**。`@_cdecl` 的 ObjC 桥接是**无检查转换**，紧接的 `makeComputeCommandEncoder()` 会打到不存在的 selector → **崩溃**。

**处置：M7a/M7c 必须同时给这 6 个入口加 MTL4 孪生形态**（或让入口内部按开关取正确类型），否则 M7 开关一开就 crash。列入 M7 的前置检查清单。

---

## 7. M7e 施工顺序与自检清单

1. 按 §2 给 §1 表里 main 队列上的 encoder 逐个发**消费者**屏障（单侧），全部 `.device`。
2. 补 **E7 / E10** 两个原本无 fence 的 encoder（§0）。
3. 按 §3 选定并实现 `signalEvent` 方案（推荐 A：拆独立命令缓冲）。
4. 删掉 main 队列上我们自己发的跨 encoder fence；**保留** `scaler.fence`（§4.2）与同 encoder 内的 fence 用法。
5. **不要**加别名边（§4.1）。
6. 逐条自检：
   - [ ] §1 的 17 个 encoder 每个都有明确处置（含「无需屏障」的 E1/E3）
   - [ ] E7/E10 已补
   - [ ] 每条边只有一条屏障，没有双侧叠加（§2）
   - [ ] 没有任何跨队列 fence；E2 走 `MTLSharedEvent`
   - [ ] `signalEvent` 在 commit **之后**（§3）
   - [ ] `scaler.fence` 未被删
   - [ ] render encoder 上没把 `.fragment`/`.tile` 放进 `barrier(afterEncoderStages:)` 的 after 位置
   - [ ] `MTLStages` 与 `MTLRenderStages` 均显式写全类型名（§5）
   - [ ] MetalFX 6 个入口已有 MTL4 孪生（§6）
   - [ ] 每处失败提前 return 都不会留下队列级残留状态
7. 开 Metal API Validation + GPU Validation 跑 L2/L3 —— Metal 4 的屏障错误**只有 validation 能抓**。
8. 金样逐字节全等；System Trace 时间线不退化（§2 的单侧规则是这一条能过的前提）。

---

## 8. 待作者裁决

1. **§3 的 signalEvent 方案 A / B**（推荐 A）。
2. **E7 / E10 的 Metal 3 竞态**：按 M0.10 本文只记录、未修。是否单开一个 P0 级修复项？（Metal 3 下靠调度侥幸，Metal 4 下确定性错。）
3. **`splitFence`（S10）在 Metal 4 路径上失去意义**：屏障按 stage 对表达，双 fence 是 Metal 3 的近似手段。建议 Metal 4 路径忽略 `metallum.opt.splitFence`，Metal 3 原样保留。
