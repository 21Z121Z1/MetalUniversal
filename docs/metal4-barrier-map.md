# Metal 4 屏障映射表（迁移规格 M6 的产出物 = M7e 的施工图）

日期：2026-07-27
上游：`MinecraftMetal_Metal4_Migration_Specs_2026-07-27.md` M6
API 真值源：`docs/mtl4-api-probe.swift`

M6 是纯设计项，不改一行行为代码。本文的作用是把**现存的每一个 fence 调用点**逐个映射到 Metal 4 的屏障对，供 M7e 施工。Metal 4 **没有驱动侧 hazard tracking**，漏一条边就是随机花屏，所以这张表的完整性本身就是验收内容。

---

## 0. 清点结果与规格数字的对账（先读这节）

规格 M6 的验收写的是「**34 处 fence** 一对一映射无遗漏」。实测（字符串锚点 `updateFence` / `waitForFence`，非行号）：

| 类别 | 处数 | 是否需要映射 |
|---|---|---|
| Swift **语义调用点** | **20** | ✅ 需要，逐条列在 §2 |
| Java **语义调用点**（`MetalCommandEncoder`） | **7** | ✅ 需要，逐条列在 §3 |
| Swift `@_cdecl` 导出体（`MTLRenderCommandEncoder_updateFence` / `_waitForFence` / `MTLBlitCommandEncoder_updateFence` / `_waitForFence`） | 4 | ❌ 转发壳，无语义；Java 侧调用点已计入 |
| Swift `device.makeFence()`（`metallum_create_fence`） | 1 | ❌ 只是创建 |
| Java `mtl` 包包装方法（`MTLRenderCommandEncoder` / `MTLBlitCommandEncoder` 各 2） | 4 | ❌ 转发壳 |
| Java Bridge downcall 声明 + 方法体 | 8 | ❌ FFI 管道 |
| **语义调用点合计** | **27** | |
| **含转发壳合计** | **44** | |

**结论：语义调用点是 27 处，不是 34。** 34 落在两个统计之间（27 + 4 导出体 + 1 makeFence + 少量壳 ≈ 32–34），最可能是规格写作时把导出体和部分包装壳一并计入了。**本文按 27 处逐条映射，无遗漏**；上表把被排除的 17 行按类别列清，供评审核对排除是否正当。

> 复核命令（锚点法，不依赖行号）：
> ```bash
> grep -n "updateFence\|waitForFence" src/main/native/MetallumNative.swift | grep -v "@_cdecl"
> ```
> ```bash
> grep -rn "updateFence\|waitForFence" src/main/java/com/metallum/client/metal/render/
> ```

---

## 1. 三种屏障形态与可用阶段（实测拼写）

| 用途 | Swift 签名 | 发在哪 |
|---|---|---|
| 生产者（我写完了，通知后面的 pass） | `barrier(afterStages:beforeQueueStages:visibilityOptions:)` | 写方 encoder 的 `endEncoding()` **之前** |
| 消费者（我要读前面 pass 写的） | `barrier(afterQueueStages:beforeStages:visibilityOptions:)` | 读方 encoder 创建后**立刻** |
| 同 encoder 内 | `barrier(afterEncoderStages:beforeEncoderStages:visibilityOptions:)` | pass 内部先写后读处 |

`MTLStages`：`.vertex` `.fragment` `.tile` `.object` `.mesh` `.resourceState` `.dispatch` `.blit` `.accelerationStructure` `.machineLearning` `.all`
`MTL4VisibilityOptions`：`.none`（只排执行序）、`.device`（刷到 device 一致点）、`.resourceAlias`（别名虚拟地址一致）

**落地规则（来自规格 M6，逐条适用于下表）**
1. **首次落地统一用 `.device`**，连 WAR 行也用 `.device`。收窄到 `.none` 是第二步，必须单独跑一轮金样。
2. **TBDR 约束**：在 render encoder 上，`.fragment` / `.tile` 不得出现在 `barrier(afterEncoderStages:)` 的 after 位置。生产者形态 `barrier(afterStages: .fragment, ...)` 是允许的。
3. 每一条写→读、写→写都必须有 `.device`。Metal 4 不会替你刷缓存。
4. 相邻 render pass 共享 `.load` attachment 时也要显式配对（Metal 3 是隐式的）。S7 的 `deferredStore` / `.unknown` store action 路径尤其要逐 pass 核。
5. 同队列内可继续用 `MTLFence`（Metal 4 保留了 `updateFence(_:afterEncoderStages:)` / `waitForFence(_:beforeEncoderStages:)`），**但跨队列绝对不行**。主队列 ↔ present 队列只能用 `MTLEvent`/`MTLSharedEvent`。

### ★ M4 带出来的语义警告，M7e 必须逐条自问

Metal 3 的 `encodeWaitForEvent` 记录**在命令缓冲里**，丢弃缓冲即撤销；Metal 4 的 `queue.waitForEvent` 是**队列时间线操作，调用即入队**，丢弃缓冲不撤销。M4 的 present 路径已因此踩过一次（详见 `Metal4PresentPath.submit` 的注释）。

**屏障本身是 encoder 上的操作，随 encoder 一起被丢弃，没有这个问题。** 但 M7e 会同时动到队列级操作（M7a 的 commit、M7g 的事件等待），所以每处「失败提前 return」都要问一遍：**这个操作在 Metal 4 语义下，提前 return 会不会留下残留状态？**

---

## 2. Swift 侧 20 处语义调用点 → 屏障对

encoder 类型已逐个核实（`makeComputeCommandEncoder` / `makeRenderCommandEncoder` / `makeBlitCommandEncoder`）。

### 2.1 FG 输入 copy blit（4 处，`MetalFrameGenerationPresenter.encode`）

| # | 锚点 | 现状 | Metal 4 |
|---|---|---|---|
| S1 | `blit.waitForFence(globalFence)` | 消费者：等主队列 render 写完场景/深度/运动 | **compute enc**（blit encoder 已删除，M7h 统一到 compute）：`barrier(afterQueueStages: .fragment, beforeStages: .blit, visibilityOptions: .device)` |
| S2 | `blit.waitForFence(transferFence)` | 同上，split-fence 态（S10） | 与 S1 同一条屏障。**Metal 4 下 `splitFence` 失去意义**：屏障本身按 stage 对表达，双 fence 是 Metal 3 的近似手段 → M7e 只发一条，不再按开关二分 |
| S3 | `blit.updateFence(transferFence)` | 生产者：通知后续 pass 拷贝已完成 | **compute enc**：`barrier(afterStages: .blit, beforeQueueStages: .fragment, visibilityOptions: .device)` |
| S4 | `blit.updateFence(globalFence)` | 同上，非 split 态 | 与 S3 同一条 |

> **注意**：这四处在 **present 线程**上，而 M4 已把 present 线程切到 Metal 4 队列。**Metal 4 的 fence 只能同队列**，所以 S1–S4 在 M4 开启态下**已经不能用 fence 表达**——它们跨的是主队列（Metal 3）到 present 队列（Metal 4）。当前 M4 实现里这段 blit 仍在主队列上、仍走 Metal 3 fence，是正确的；**M7e 动到这里时必须确认这段 encode 挂在哪条队列上**，跨队列的那部分只能是 `MTLSharedEvent`。这是本表最容易出错的一格。

### 2.2 MetalFX compute pass（10 处，全部 compute encoder）

五对，形状完全一致：读上游写的纹理 → 写自己的输出 → 通知下游。

| # | 锚点函数 | 消费者侧 | 生产者侧 |
|---|---|---|---|
| S5/S6 | `metallum_metalfx_apply_cutout_reactive` | `barrier(afterQueueStages: .fragment, beforeStages: .dispatch, visibilityOptions: .device)` | `barrier(afterStages: .dispatch, beforeQueueStages: .fragment, visibilityOptions: .device)` |
| S7/S8 | `metallum_metalfx_encode_hand_overlay` | 同上 | 同上 |
| S9/S10 | `metallum_metalfx_clear_motion_inputs` | 同上 | 同上 |
| S11/S12 | `metallum_metalfx_encode_v2`（cameraEncoder） | 同上 | 同上 |
| S13/S14 | `metallum_metalfx_encode_v2`（mergeEncoder） | **读的是 cameraEncoder 的输出**，同队列同类型：`barrier(afterQueueStages: .dispatch, beforeStages: .dispatch, visibilityOptions: .device)` | `barrier(afterStages: .dispatch, beforeQueueStages: .fragment, visibilityOptions: .device)` |

> S13 是唯一 dispatch→dispatch 的一对，别照抄 `.fragment`。两个 encoder 在同一个命令缓冲里先后创建，**同 encoder 内形态不适用**（是两个 encoder），仍用队列形态。

### 2.3 render encoder（6 处）

| # | 锚点函数 | 现状 | Metal 4 |
|---|---|---|---|
| S15/S16 | `metallum_encode_texture_copy` | `waitForFence(before: .fragment)` / `updateFence(after: .fragment)` | 消费者 `barrier(afterQueueStages: .fragment, beforeStages: .fragment, visibilityOptions: .device)`；生产者 `barrier(afterStages: .fragment, beforeQueueStages: .fragment, visibilityOptions: .device)` |
| S17/S18 | `metallum_MTLCommandBuffer_clearColorDepthTexturesRegion` | 同形 | 同上。**额外注意**：clear 会打断 encoder（P0-2），M7b 之后 store action 走 `setDepthStoreAction`，屏障与 store 决策是两件事，别混 |
| S19/S20 | `metallum_MTLCommandBuffer_encodePresentTextureToDrawable` | 同形 | 同上。这条是 present pass 采样 uiTarget，规格 M6 表里的「present pass 采样 uiTarget」行 = RAW+WAR，首次落地统一 `.device` 即可覆盖两者 |

---

## 3. Java 侧 7 处语义调用点 → 屏障对

全部在 `MetalCommandEncoder`。Java 侧的 `mtl` 包包装类**不需要改**（规格 M7：差异全部吸收在 Swift 侧与 Bridge 新导出里）。M7e 的实际做法是：**这些调用点整体不再发 fence，改为让 Swift 侧在 encoder 创建/结束处发屏障**，Java 只传递「本 encoder 要读什么、写什么」的意图。

| # | 锚点 | 现状 | Metal 4 |
|---|---|---|---|
| J1 | `encoder.waitForFence(fence)`（blit） | 上传 copy 等 render 写完（WAR：copy 读 RT） | compute enc 消费者：`barrier(afterQueueStages: .fragment, beforeStages: .blit, visibilityOptions: .device)` |
| J2 | `encoder.waitForFence(transferFence)`（blit） | 同上，split 态 | 与 J1 同一条（`splitFence` 在 Metal 4 路径上失去意义） |
| J3 | `encoder.waitForFence(transferFence, .Vertex)`（render） | render 在 vertex 前等上传 | 消费者：`barrier(afterQueueStages: .blit, beforeStages: .vertex, visibilityOptions: .device)` |
| J4 | `encoder.waitForFence(fence, .Fragment)`（render） | render 在 fragment 前等上游 RT | 消费者：`barrier(afterQueueStages: .fragment, beforeStages: .fragment, visibilityOptions: .device)` |
| J5 | `encoder.waitForFence(fence, .VertexAndFragment)`（render） | 两阶段都等 | 消费者：`barrier(afterQueueStages: [.blit, .fragment], beforeStages: [.vertex, .fragment], visibilityOptions: .device)`。**`MTLStages` 是 OptionSet，可以合并**——不要拆成两条，两条会各自插一次刷缓存 |
| J6 | `renderEncoder.updateFence(...)`（render） | 生产者 | `barrier(afterStages: .fragment, beforeQueueStages: [.vertex, .fragment, .blit], visibilityOptions: .device)` |
| J7 | `blitEncoder.updateFence(SPLIT_FENCE ? transferFence : fence)`（blit） | 生产者 | compute enc：`barrier(afterStages: .blit, beforeQueueStages: [.vertex, .fragment], visibilityOptions: .device)` |

> J5/J6/J7 的 `beforeQueueStages` 取并集，是因为 Metal 3 的单个 fence 本来就是「对后面所有人可见」的粗粒度语义。**首次落地照抄这个粗粒度**，收窄留到第二步并单独跑金样——否则无法区分「屏障漏了」和「屏障收窄收错了」。

---

## 4. 本工程特有的第 8 类边：资源别名（规格 M6 最后一行，**别漏**）

`MetalTransientMemory.rotate()` 的块回收与 `recycleDynamicBacking` / buffer 池复用会让**同一段虚拟地址换用途**。Metal 3 下靠销毁队列深度（S1）+ 驱动兜底；Metal 4 下必须显式声明：

```swift
barrier(afterQueueStages: .all, beforeStages: .all, visibilityOptions: .resourceAlias)
```

发在哪：**池化 buffer / transient 块被重新分配用途之后、首次被 GPU 访问之前**。现有代码里没有对应的 fence 调用点（这条边在 Metal 3 下是隐式的），所以它**不在上面 27 处之内**，是 M7e 需要**新增**的一条。

**这是整张表里唯一「Metal 3 下无对应调用点」的边，也因此最容易被漏掉。** 症状是读到旧内容——不会报错，只会偶发画面错误。

---

## 5. M7e 施工顺序与自检清单

1. 先把 §2/§3 的 27 条按 encoder 落位：消费者屏障在 encoder 创建后**立刻**发，生产者屏障在 `endEncoding()` **之前**发。
2. 补 §4 的别名边（新增，无 Metal 3 对应）。
3. 全部 `visibilityOptions: .device`，一条都不收窄。
4. 删掉主队列上**跨 encoder** 的 fence；**同 encoder 内**的 fence 用法可以保留（Metal 4 仍支持）。
5. 逐条自检：
   - [ ] 27 条都有对应屏障，且生产者/消费者成对出现
   - [ ] §4 别名边已加
   - [ ] 没有任何跨队列 fence（present 队列已是 Metal 4，见 §2.1 的警告格）
   - [ ] render encoder 上没有把 `.fragment`/`.tile` 放进 `barrier(afterEncoderStages:)` 的 after 位置
   - [ ] `MTLRenderStages`（Metal 3 fence 用）与 `MTLStages`（Metal 4 屏障用）没有混用
   - [ ] 每处失败提前 return 都不会留下队列级残留状态
6. 开 Metal API Validation + GPU Validation 跑 L2/L3——**Metal 4 的屏障错误只有 validation 能抓**。
7. 金样逐字节全等。

---

## 6. 待作者裁决 / 需要复核的两处

1. **§2.1 的四处 FG 输入 blit 落在哪条队列上**。M4 已把 present 线程切到 Metal 4 队列，而这段 blit 目前在主队列。M7e 之后主队列也是 Metal 4 → 两条都是 Metal 4 队列，但**仍是两条不同队列**，所以 S1–S4 仍然只能用 `MTLSharedEvent`，不能用 fence，也不能用队列级屏障（屏障是 encoder 级/单队列时间线的）。**建议 M7e 落地前单独确认一次这段 encode 的归属队列。**
2. **`splitFence`（S10）在 Metal 4 路径上失去意义**（规格 M8 也这么写）。J2/S2 因此不再需要按开关二分。建议：Metal 4 路径直接忽略 `metallum.opt.splitFence`，Metal 3 路径原样保留。这是行为差异，需要确认可以接受。
