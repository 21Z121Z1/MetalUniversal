# Metal 后端性能审计(iris-on-metal 分支)

日期:2026-07-27。方法:**热路径静态分析**(本会话未做游戏内 profiling;每项标注证据等级)。
范围:`MetalCommandEncoder` / `MetalRenderPass` / `MetalTransientMemory` / `MetalDevice` / `MetalGpuBuffer` / Swift present 路径。
归属边界:presenter/display-link/FG/MetalFX 管理属 **master 树 MetalFX 线**(并行会话在改 `MetalFxManager` 与 Swift presenter);本文对其只记录、不建议本分支改动。`MetalCommandEncoder`/`MetalRenderPass` 为两线共享文件,实施前需与 master 线协调合并窗口。

优先级 = 预估收益 × 置信度 ÷ 风险。所有项在实施前应先按 §5 建立测量基线,避免盲改。

## 1. P1(高价值)

### 1.1 全局单 MTLFence 链把所有 pass 完全串行化
- 证据:[MetalCommandEncoder.java:95](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:95)(每个 encoder 结束 `updateFence`)+ 每次开 encoder `waitForFence(fence, VertexAndFragment)`([:260](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:260))。
- 问题:render→render 在 **Vertex 阶段前**就等待上一 pass 的 Fragment 完成 → GPU 上相邻 pass 零重叠,即使无资源冲突。这是 GL barrier 语义的保守实现(architecture §2.4 有意为之),但代价是 pass 越多损失越大——**Iris 点亮后 composite 链是 8–16 个全屏 pass,该成本会线性放大**。
- 方向(按侵入度递增):
  1. render→render 消费方仅在 **Fragment 阶段**等 fence(`waitForFence(fence, before:.fragment)`):上一 pass 的输出只被下一 pass 的 fragment 采样时,vertex/光栅化可与上一 pass 尾部重叠。前提:下一 pass 的 **vertex 不采样纹理**。我们的 PSO 反射(`MslShader.activeResources` + stageMask,[MetalCrossShaderCompiler.java:246](../src/main/java/com/metallum/client/metal/render/MetalCrossShaderCompiler.java:246))已能逐管线判定 vertex 是否有纹理读取(光影包的 waving 顶点动画会读 noisetex——正好被反射捕获),可做成精确的按管线条件降级。
  2. 读写集追踪跳过无冲突 pass 之间的 fence(更大改动,后置)。
- 风险:hazard 漏判 → 用 MTL_DEBUG_LAYER + 现有 GPU 内容级套件回归;先在 metalIrisTargets/MRT 套件里加"vertex 采样上一 pass 输出"的对抗用例再实施。
- 证据等级:静态分析(收益未测量)。

### 1.2 每次 blit 拷贝各开一个 encoder(含两次 fence 跳)
- 证据:[MetalCommandEncoder.java:79](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:79) `blitCommandEncoder()` 无条件 `endEncoder()`;`writeToBuffer`/`writeToTexture`/`copyToBuffer`/`copyTextureToTexture` 每调用一次 = 新 blit encoder + waitFence + updateFence + endEncoding([:748](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:748) 等 6 处)。
- 问题:连续上传(区块网格、图集/字体更新、多次 buffer 写)造成 encoder/fence 风暴;render encoder 已有同附件复用([:242](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:242)),blit 没有对应机制。
- 方向:`blitCommandEncoder()` 当 `currentEncoder` 已是 blit 时直接复用(blit encoder 内命令按编码顺序执行,GL 顺序语义不变——实施前以 Metal 文档/API validation 复核该保证)。
- 风险:低;一处集中改动。证据等级:静态分析。

### 1.3 `nextDrawable()` 在 render 线程内阻塞(present 编码期)
- 证据:[MetallumNative.swift:4307](../src/main/native/MetallumNative.swift:4307)(present 编码内取 drawable),macOS `allowsNextDrawableTimeout = false`([:4276](../src/main/native/MetallumNative.swift:4276));叠加 [MetalCommandEncoder.java:180](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:180) 的 3-in-flight 信号量等待,GPU 落后时 render 线程可能双重停顿。
- 方向:present 拆分为独立小 command buffer——主帧工作先 commit(GPU 立即开跑),然后才 `nextDrawable()`+blit+present。drawable 饥饿时 CPU 等待与 GPU 执行重叠,吞吐/延迟双收益。
- **归属**:present 节奏与 CAMetalDisplayLink 契约是 master 线 Phase-2 工作(见记忆 cametaldisplaylink-present-contract);本分支不动,此处仅记录。
- 证据等级:静态分析 + 既有 presenter 审计结论。

## 2. P2(中等)

### 2.1 dynamic buffer 部分写触发全量 orphan 拷贝
- 证据:[MetalCommandEncoder.java:766](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:766)——offset≠0 或长度≠全量时,把**整个旧 backing** memcpy 进新 backing 再覆写目标区间。
- 方向:仅拷贝未覆盖区间;或用 submit-index 判定"GPU 未在读"时原地写(免 orphan)。先测量每帧 orphan 次数×buffer 大小再决定。
- 证据等级:静态分析;频率未测量(取决于 vanilla/Sodium 对 dynamic buffer 的部分写频率)。

### 2.2 drawMultipleIndexed 每 draw 的字符串键 HashMap 往返
- 证据:[MetalRenderPass.java:275-292](../src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:275)(每 draw `setUniform`→`uniforms.put`+`markDescriptorDirty` 字符串查找;dirty 后 [:556](../src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:556) 全资源表扫描)。
- 方向:按管线预解析 uniform 名→binding index(编译期已知),draw 循环走 int 索引数组;dirty 扫描改按位遍历。收益集中在 vanilla 实体/文字批(Sodium 地形走 multiDraw/indirect,不受影响)。
- 证据等级:静态分析。

### 2.3 transient 分配的对象churn
- 证据:[MetalTransientMemory.java:95](../src/main/java/com/metallum/client/metal/render/MetalTransientMemory.java:95) 每次 `allocateGpu*` new 一个 `TransientGpuBuffer` + `GpuBufferSlice` + `MappedView`。
- 方向:先用 alloc-profiler 测量每帧分配量;若显著,做 per-frame flyweight 池。
- 证据等级:静态分析;GC 压力未测量。

### 2.4 延迟 clear 逐纹理各开 render encoder
- 证据:[MetalCommandEncoder.java:1060-1087](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:1060)。被后续 pass 用作附件的 clear 已能吸收进 loadAction([:344](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:344)),此项只影响"clear 后未被 pass 引用先被采样/拷贝"的纹理。
- 方向:把可合并的颜色 clear 合并进一个 MRT clear encoder(≤8 attachment)。频率主要来自 MetalFX 目标(master 线域),本分支收益有限——低优先。

## 3. P3(小/记录性)

- `submit()` 每帧 `List.copyOf`([MetalCommandEncoder.java:187](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:187));label 拼接仅在 useLabels 时发生(debug-only)——不动。
- `setPipeline` 每次调用重建 `colorAttachmentFormats()` 数组做校验([MetalRenderPass.java:108](../src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:108))——可缓存于 pass;微小。
- `getTimestampNow()` 用 `System.nanoTime` 充当 GPU 时间戳([MetalDevice.java:201](../src/main/java/com/metallum/client/metal/render/MetalDevice.java:201))——F3 的 GPU 计时是 CPU 时间,**保真度问题**而非性能问题,记录待办(MTLCounterSampleBuffer)。
- MSL function 缓存以完整源码字符串为键([MetalDevice.java:286](../src/main/java/com/metallum/client/metal/render/MetalDevice.java:286))——仅编译期路径,频率低,不动。
- 三角扇 draw 每次生成索引缓冲([MetalRenderPass.java:445](../src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:445))——GUI 低频路径,不动。

## 4. 对 Iris 点亮(B2-3+)的前瞻性性能要求

1. composite/deferred 链把 §1.1 的 fence 串行化成本放大 8–16 倍——**建议 B2-3 落地时同步实现按管线的 fragment-stage 等待**(ping-pong 设计保证读写分离,正是该优化的安全适用面)。
2. Iris 逐 pass 全屏绘制应复用同一 render encoder 的同附件合并路径:同一 flip 周期内连续写同侧目标的 pass 天然同附件,现有 [:242](../src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:242) 复用可生效;跨 flip 必然换附件,是 fence 优化的主战场。
3. pack uniform 供给(B2-3 uniform provider)应走 transient 环 + 单 UBO 布局(转译层已把 loose uniform 收进 `MetallumIrisUniforms` std140 块,天然一次 setBuffer 全量绑定,避免 GL 式逐 uniform 提交)。

## 5. 测量计划(实施任何优化前)

1. `-Dmetallum.debug.perfCounters`:每 5s 输出 encoders/frame(按类型)、fence waits/frame、transient bytes/frame、orphan copies/frame、submit 阻塞时长。(待实现,~1 处 MetalCommandEncoder 埋点。)
2. Xcode Metal System Trace / GPU capture:pass 重叠度可视化,验证 §1.1 收益上限。
3. async-profiler alloc 模式:验证 §2.3。
4. 基线场景:固定种子旁观者存档(与 L3 冒烟同一存档),MetalFX OFF,60s 平均。

## 6. 结论

后端的正确性架构(untracked + 全局 fence 链)换来了简单可证的 GL 语义,代价是 GPU 并行度;在 vanilla 场景 pass 数少,损失有限,但 **Iris composite 链会把该代价乘上一个数量级——fence 精化(§1.1)是 Iris 性能达标的前置项**,建议排进 B2-3。CPU 侧(blit 合并 §1.2、draw 循环 §2.2)是低风险的独立收益。present/节奏类问题(§1.3)归 master 线 Phase-2。本文全部为静态分析结论,实施顺序:先 §5 计数器,再按测量结果动刀。
