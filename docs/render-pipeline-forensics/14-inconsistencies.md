# 取证冲突与证据不一致

> **2026-07-26 live-source correction**
>
> 本文正文主要记录实现前冲突。此后 `MetalUniversal-master` 已初始化 Git（尚无 HEAD commit，全部文件仍是未跟踪 baseline），当前 Swift 已重新构建为新 dylib，Gradle 的 Java/native/MRT/offscreen/Minecraft client/real-display 验收均已针对当前源码运行。旧 rollout、旧 dylib、旧 `/tmp` trace 和本文早期行号均不能替代 `../metalfx-final-acceptance-2026-07-26.md` 中的 current-source receipt。

本文件专门记录摘要、rollout、文档、构建输出和当前工作树之间的差异。优先级仍是当前工作树 > 映射源码 > 本地依赖 > 构建/运行日志 > rollout > 旧文档。

## 1. rollout 声称存在 `baseProjection` 等字段，但当前源码没有

**冲突：** 历史 rollout/摘要曾提到 `baseProjection`、`previousProjection`、`PROJECTION_CHANGE_EPSILON` 等字段或 projection-change 状态。当前 `MetalFxManager` 的字段区只有 `previousViewProjection`、`currentViewProjection`、`inverseCurrentViewProjection`、`viewMatrix`、`currentProjection`、`jitteredViewProjection`（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java:38-44`），`rg` 未找到上述三个名称。当前 projection difference helper 是 `MetalFxMath.maxAbsDifference`，单测在 `MetalFxMathTest.java:140-145` 使用硬编码 epsilon。

**判定：** 当前源码优先；旧 rollout 结论 stale/不一致。**置信度：confirmed。**

## 2. 报告目录位置

**冲突：** 用户规范给出 `docs/render-pipeline-forensics/`，工作区实际项目目录是嵌套的 `MetalUniversal-master`。父目录为 `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal`。

**判定：** 本轮唯一写入目录是 `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/docs/render-pipeline-forensics/`，因为实现代码、Gradle project 和 `src` 都位于该目录。没有在父目录另建报告目录。**置信度：confirmed path choice；限制：无 Git baseline，不能用仓库根元数据自动证明根目录。**

## 3. Loom 属性版本与实际解析版本

**冲突：** `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/gradle.properties:12` 是 `loom_version=1.16-SNAPSHOT`；本地 Gradle/Loom 配置解析记录为 1.16.3。

**判定：** 报告同时记录两者，不把 1.16.3 写回属性，也不声称 snapshot 与 resolved version 相同。**置信度：confirmed from property and prior Gradle resolution output；限制：当前没有重新执行 dependency insight。**

## 4. native build 成功不等于 iOS 产物可运行

**事实：** 历史构建记录 `./gradlew buildMacNative` 成功，`./gradlew buildIOSNative` 也完成，但 iOS target/sysroot 有 warning；build task 只是编译 Swift dylib，不是 iOS app/device launch 或 MetalFX runtime validation。

**判定：** “iOS native build passed”只能写成 build artifact 生成/编译成功；不能写成 iOS 产物可加载、可运行或功能正确。**置信度：confirmed interpretation。**

## 5. build task 是否重写现有 dylib

**事实：** `build.gradle:53-74` 的 macOS task 和 `:109-132` 的 iOS task 直接以 `-o src/main/resources/natives/.../libmetallum.dylib` 输出。当前资源目录已有 macOS/iOS dylib。

**判定：** 历史 build 可能已经重写这些二进制；没有 Git 元数据，也没有构建前 SHA/mtime 证据，不能声称二进制未变化，也不能把它描述成 Java/Swift 源码实现修改。后续只应把它列为构建副作用风险。**置信度：confirmed path, unknown byte delta。**

## 6. 当前持久化配置与历史 Temporal 日志不一致

**事实：** `run/metallum-metalfx.properties` 当前是 `mode=OFF`、`scalePercent=50`；`run/logs/latest.log:29` 记录过 `requested=TEMPORAL, effective=TEMPORAL, scale=0.67`，`:110-111` 记录过成功 Temporal encode。

**判定：** 运行日志证明历史运行，不证明当前配置仍为 Temporal。报告中所有尺寸/jitter/runtime 结论都标注历史观察。**置信度：confirmed。**

## 7. mapped `GuiRenderer` 路径

**冲突：** 早期摘要曾把 `GuiRenderer.java` 路径写在 `net/minecraft/client/renderer`；当前映射文件实际位于 `/tmp/minecraftmetal-mc26-sources/net/minecraft/client/gui/render/GuiRenderer.java`。

**判定：** 以后以 `client/gui/render/GuiRenderer.java:62,120,180-217` 为准。**置信度：confirmed by `rg --files`。**

## 8. build/运行失败与当前代码事实

| 证据 | 内容 | 判定 |
| --- | --- | --- |
| `run/crash-reports/crash-2026-07-26_02.17.39-client.txt:7,119-120` | scissor 1708x524 超出 1144x642 render area；window/surface 1708x960 | 尺寸混用的运行时反证，不能被单纯代码意图覆盖 |
| `run/crash-reports/crash-2026-07-26_03.18.43-client.txt:23` | heap `MemorySegment` rejected | Java/native bridge 的历史 ABI/segment 错误，不能归因到 MetalFX 数学 |
| `run/crash-reports/crash-2026-07-26_09.45.27-client.txt:7` | Sodium `Storage handler must be set` | config API/runtime setup failure，不能用来证明 pipeline/shader 错误 |
| `run/logs/latest.log:104-105` | invalid camera matrix 与 reset | 当前代码确实有 invalid-matrix guard，但历史运行遇到过 invalid matrix |

## 9. rollout 与当前工作树的证据等级

旧 rollout 中有 requirements-only 记录，明确没有 implementation/runtime proof；本轮把 rollout 仅作为导航/冲突来源。当前结论必须回到源文件和本地日志。尤其不能因 rollout 文字出现“Temporal 完成”“Frame Generation 完成”就把动态 motion、pacing、视觉质量标成 confirmed。

## 10. 本轮没有声称的事项

- 没有声称 Git branch/HEAD 或工作树完全干净。
- 没有声称 iOS dylib 可在真机运行。
- 没有声称 GUI/scissor 尺寸混用已经修复。
- 没有声称所有 runtime pipelines 已枚举。
- 没有声称 Temporal 或 Frame Generation 画质正确。

## 11. GUI 激活时 Frame Generation 是暂停/恢复，不是单向 disable

**冲突：** 早期报告文本把 GUI/overlay 路径写成调用 `disableFrameGenerationInternal`、销毁 `sceneOutputTarget`，并据此推断关闭菜单后没有恢复路径。当前工作树实际由 `frameGenerationInputInternal` 调用 `suspendFrameGenerationForGuiInternal`（`MetalFxManager.java:707-715,748-760`）；该函数只停止 native presenter 并保留 `sceneOutputTarget`。下一帧 `beginFrameInternal` 在 GUI 消失后设置 `frameGenerationEnabled=true` 并调用 `resetHistoryInternal("GUI closed; frame generation resumed")`（`MetalFxManager.java:273-283`）。永久 disable 是另一条 `disableFrameGenerationInternal` 路径，会销毁 `sceneOutputTarget`（`MetalFxManager.java:671-684`）。

**判定：** 以后以当前源码为准：GUI 是 Java 侧 pause/resume；`stop_frame_generation` 的 shutdown 会等待 worker/outstanding-frame 状态，下一次 native encode 在 presenter 为空时懒创建新 presenter（`MetallumNative.swift:746-762,1581-1625,1715-1742`）。因此 Java/native 控制流和 drain/recreate 结构已确认，但真实 drawable timing、视觉输出和设备级完成关系仍需要运行验证。**置信度：confirmed control flow/topology; runtime output unknown。**

## 12. JOML 到 Swift simd 矩阵链曾被错误地标成不存在

**冲突：** 早期 `05-matrices-jitter-motion-conventions.md` 文本把矩阵描述成只在 Java 侧使用，并声称 bridge 没有 JOML -> Swift 转换。当前工作树实际中，`MetalCommandEncoder.encodeMetalFx` 把三组 JOML `Matrix4f` 写入 float arrays（`MetalCommandEncoder.java:293-335`），`MetalNativeBridge.metallum_metalfx_encode` 复制到 native scratch segments（`MetalNativeBridge.java:803-835`），Swift `makeMatrix` 再组装 `simd_float4x4`，供 `metallum_motion_reconstruction` 使用（`MetallumNative.swift:1270-1277,1473-1493`）。

**判定：** Temporal motion 的 JOML -> float buffer -> Swift simd 链已确认；只有 Frame Generation 的 `FrameGenerationInput` 不携带 VP，而是携带 texture handles 和标量参数。报告和 handoff 已按当前源码更正。**置信度：confirmed data path；JOML/Swift 数学语义仍需 GPU capture 验证。**

## 13. Java `MetalFxMath.reconstructMotion` 不是运行时 motion producer

**冲突：** 早期章节把 `MetalFxMath.reconstructMotion` 写成当前每帧 motion producer，并只描述 legacy `metallum_motion_reconstruction`。当前源码的 manager 走 `encodeMetalFxV2`（`MetalFxManager.java:456-479`）；V2 native export 依次执行 camera kernel、object/camera merge 和 Temporal scaler（`MetallumNative.swift:1355-1475,1844-2011`）。Java helper 和 `MetalMotionContract.projectVertex` 仍没有生产调用，只有测试/定义路径（`MetalMotionStateStore.java:31-44`; `MetalMotionContract.java:64-98`; `MetalFxMathTest.java:77-169`）。

**判定：** 运行时 final motion producer 以 V2 native MSL 为准；由于 `prepareMotionInputs` 只清零 object motion/validity 且没有 renderer producer，V2 merge 当前选 camera motion。Java helper 只能证明 mirror/候选对象数学，不单独证明 GPU object motion texture 内容。**置信度：confirmed call topology and missing producer; actual GPU output still requires capture.**

## 14. motion scale 曾被过度保留为未知

**冲突：** 早期报告只引用日志中的 `motionVectorScale=(572,321)`，因此把 MetalFX 对 motion 单位的解释保留为 unknown。当前进一步核对了本地 Xcode 26.5 SDK：`MTLFXTemporalScaler.h` 明确规定 scale 将 motion texture 值乘为 fragment pixels，并规定向右/向下移动 10 像素的 current-to-previous vector 为 `(-10,-10)`（`/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk/System/Library/Frameworks/MetalFX.framework/Headers/MTLFXTemporalScaler.h:266-286`）。native producer 输出 NDC 差值，随后设置 `inputWidth * 0.5` / `inputHeight * 0.5`（`MetallumNative.swift:1211-1265,1517-1522`）。

**判定：** 当前 V2 motion 的方向和像素 scale 与已安装 SDK 契约一致，属于 `confirmed`；V2 camera/merge 都在 `MetallumNative.swift:1844-2011` 设置 `inputWidth * 0.5` / `inputHeight * 0.5`。仍未知的是驱动实际采样后的画面结果、jitter 的最终符号响应和动态对象覆盖，不是 scale 公式本身。第 05、07、09、00 章与 handoff 已更新。**置信度：confirmed contract; GPU output unknown。**

## 15. Frame Generation 刷新率与 worker 数量曾被写错

**冲突：** 旧摘要和多个章节把 Frame Generation 描述成固定 `1/120`、`realFramePaceFraction=31/64`、两个 worker、两个 shared event、最多两个 outstanding frame。当前 `MetallumNative.swift` 中没有这些符号：`rg` 只找到一个 `readyEvent`、一个 `MetalFX PresentThread`、`bufferCount=3` 和 `maxOutstandingFrames=1`（`src/main/native/MetallumNative.swift:78-125,188-194`）。`frameDuration` 实际在 presenter 初始化时按 `NSScreen.maximumFramesPerSecond` 采样，缺省 60、下限 30（`:149-170`）；真实 present 使用 `afterMinimumDuration: frameDuration * 0.5`（`:699-728`）。

**判定：** 以当前 Swift 源码为准：不是固定 120 Hz，也没有独立 Frame Pacing worker/pacing shared event；存在一个 render-thread enqueue + 一个 `MetalFX PresentThread` worker 和一个 ready shared event。旧的固定 120/双 worker/双 event 描述已降级为 stale 文本。**置信度：confirmed source correction；限制：实际 WindowServer/VRR present timing 仍未知。**

## 16. Frame Generation native export 行号和失败语义曾被过度扩大

**冲突：** 旧章节把 `metallum_metalfx_frame_generation_encode` 引用为旧行号，并把 `readyEvent` timeout 写成只丢插值帧。当前 export 是 `MetallumNative.swift:2013-2095`；Java bridge/present caller 是 `MetalCommandEncoder.java:349-389` 和 `MetalNativeBridge.java:1001-1050`。worker `process` 仍在 `MetallumNative.swift:623-728`；ready event 一秒等待失败或 `failedInputEvents` 命中时直接 `completeFrame()` 返回，不调用 `presentRealFrame`，因此可以丢弃整个 source frame。只有 `nextDrawable`/插值 command 创建失败时才进入 `presentRealFrame` 退化路径。

**判定：** 以后使用 `10-frame-generation-and-presentation.md` 的窄行号；把 event timeout 描述为“source frame 被丢弃”，不能描述为“必然保留真实帧”。另外，当前 `MetalFxManager.java:29-33,99-116` 把 FG gate 固定为关闭，所以以上是 dormant conditional topology，当前运行没有 worker present 证据。**置信度：confirmed control flow and gate; device drop frequency unknown.**

## 17. MRT 边界曾被错误地写成 native 单附件限制

**冲突：** 旧报告把 `MetalCommandEncoder.createRenderPass`、`MetalRenderPass`、`MetalCompiledRenderPipeline` 和 Swift native descriptor/encoder 描述成只消费 `color attachment 0`，并据此把通用 backend 标成无法绑定 MRT。当前工作树的窄读与 Minecraft 26.2 mapped source 直接反证该表述：`RenderPipeline.Builder` 保留 8 个 color slots，`RenderPass.setPipeline` 按数量校验；Java encoder 逐槽建立 `MemorySegment[]` 并调用 `makeRenderCommandEncoderV2`；`MetalRenderPass` 保存 `GpuTextureView[]`；PSO 逐槽设置 format/blend；Swift v2 render pass 与 descriptor setters 都按 index 支持 0..<8（`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/pipeline/RenderPipeline.java:147-159,241-255`；`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/systems/RenderPass.java:82-98`；`MetalCommandEncoder.java:134-180,205-227`；`MetalRenderPass.java:33-80,382-409`；`MetalCompiledRenderPipeline.java:114-125,187-216`；`MetallumNative.swift:2484-2580,3214-3275`）。

**判定：** 以当前源码为准：通用 backend 的 indexed MRT binding capacity 已确认；当前已枚举 Minecraft 26.2 `RenderPipelines` 和 Sodium 0.9 `ShaderChunkRenderer` 仍只声明 slot 0，fragment output 是否在所有运行时/第三方 shader 中产生第二个 color location 尚未完整枚举。因此后续报告把“native 单附件”降级为 stale，把“当前 motion MRT contract 缺失”保留为 confirmed inspected-source boundary。**限制：** 没有 runtime pipeline log 或 GPU capture，不能把静态枚举推广成所有第三方 pipeline。

## 18. indexed MRT 源码、bundled dylib 与实际加载库不是同一证据

**事实：** 当前 Swift 源码和 Java bridge 都提供 v2/indexed symbols；对工作树现有二进制执行 `nm -gU` 也能看到 macOS 与 iOS `libmetallum.dylib` 中的 `metallum_MTLCommandBuffer_makeRenderCommandEncoder_v2`、`metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat` 和 `...setColorAttachmentBlendState`。这是对 bundled artifacts 的静态交叉证据。Java bridge 的 `optionalDowncall` 允许 symbol 缺失；缺失时单附件使用 legacy path，多附件 render encoder、非零 attachment format/blend 会抛出 `IllegalStateException`（`MetalNativeBridge.java:696-700,1341-1371,1820-1842,1861-1890`）。

**判定：** 当前报告可以确认“源码 + 当前 bundled dylib 具备 indexed MRT symbols”，但不能仅凭 `nm` 证明 Minecraft 进程实际加载的是这两个 bundled 文件；macOS/iOS loader 还有 `System.loadLibrary`、Frameworks 和 iOS temporary extraction 分支（`MetalNativeBridge.java:529-568`）。因此 active loaded dylib、实际 `SymbolLookup` 结果和运行时多附件 render pass 仍标为 unknown/需要运行验证。此前 build task 可能重写 bundled dylib 的风险仍独立保留在第 5 节。

## 19. Frame Generation 的 Java input scalar 与 native texture 尺寸有两套来源

**事实：** `MetalFxManager.frameGenerationInputInternal` 把 `renderWidth/renderHeight` 放入 `FrameGenerationInput`（`MetalFxManager.java:790-822`），Java export 也接收这两个参数；但 Swift `metallum_metalfx_frame_generation_encode` 创建 `PendingFrame` 时从实际 `depth.width/height` 写入 input dimensions，`makeFrameInterpolator` 同样用 depth dimensions 作为 input、scene color dimensions 作为 output，motion scale 从 native frame input dimensions计算（`MetallumNative.swift:2013-2095,204-221,528-547,667-679`）。

**判定：** 当前目标资源设计应使两者相等，因为 main depth/motion 是 render size，scene output 是 display/native size；但没有跨层 assert 或 runtime log 同时打印 Java scalar 与 depth texture dimensions。若两者分离，Java 日志和 native interpolator scale 可能描述不同输入尺寸。**置信度：producer/consumer source path=confirmed；实际运行时 equality=unknown，需要 resize/Retina/odd-size capture。**

## 20. V2 object motion 是资源/merge scaffold，不是已接入对象 producer

**事实：** 当前 `MetalFxManager.ensureAuxiliaryTextures` 创建 `cameraMotionTexture`、`objectMotionTexture`、`objectValidityTexture`、`disocclusionTexture`、`motionTexture` 和 `reactiveTexture`；`prepareMotionInputs` 只调用 `clearMotionInputs(objectMotionTexture, objectValidityTexture, ...)`（`MetalFxManager.java:642-700`; `MetalCommandEncoder.java:391-408`）。native V2 camera kernel 写 camera/disocclusion，merge kernel 仅当 validity > 0.5 时选择 object motion（`MetallumNative.swift:1355-1475`）。

**交叉核验：** Java `MetalMotionStateStore.observe` 只有定义，`rg -n "observe\\(" src/main/java` 没有生产 caller；`MetalMotionContract.projectVertex` 的调用只出现在 `MetalFxMathTest`。`EntityRenderer`、`Particle` 等旧/current state 证据因此不能被升级成 MetalFX object motion producer。

**判定：** 当前 final motion 的静态/相机部分是 connected；object motion/velocity replay 未接入。**置信度：confirmed source boundary；实际 attachment 全帧值仍需 GPU capture。**

## 21. Frame Generation gate 与 native presenter 是两种状态

**事实：** `OBJECT_MOTION_PRODUCER_CONNECTED` 在当前 Java source 固定为 `false`，`frameGenerationEnabled` 初始化要求它为真（`MetalFxManager.java:29-33,99-116`）。native presenter、slots、worker、shared event 和 export 仍存在（`MetallumNative.swift:65-221,2013-2095`），但 `frameGenerationInputInternal` 只有 gate 打开才返回输入（`MetalFxManager.java:790-822`）。

**判定：** 报告可以描述 Frame Generation 的控制流和潜在 pacing，但不能把它描述成当前每帧真实 present。需要运行验证的是 active gate、loaded symbols、实际 queued frame 和 drawable timestamps；静态 native worker 代码不等于已经启动 worker。**置信度：Java gate=confirmed；current runtime worker activation=needs runtime verification.**

## 22. V2 symbols 与构建副作用的边界

**事实：** Swift source 有 `metallum_metalfx_supports_motion_v2`、`metallum_metalfx_clear_motion_inputs` 和 `metallum_metalfx_encode_v2`（`MetallumNative.swift:1559-1615,1844-2011`）；对现有 macOS/iOS bundled dylib 的精确 `nm -gU` 查询也分别看到这三个 symbols。该结果比只查 indexed render symbols 更完整。当前 bundled artifact 与 source 的 symbol presence 已静态对齐。

**限制：** `MetalNativeBridge.createSymbolLookup` 仍有系统库、Frameworks 和临时 extraction 分支，`nm` 不能证明游戏进程实际加载哪一个文件（`MetalNativeBridge.java:529-568`）。同时 `build.gradle:53-74,109-132` 直接把 native build 输出写入 `src/main/resources/natives/{macos,ios}/libmetallum.dylib`；没有 Git 基线、构建前 SHA 或 capture，不能声称 build 前后 bytes 未变，也不把该二进制副作用描述成源码实现修改。**置信度：source/bundled symbol presence=confirmed；active loader identity and byte delta=unknown.**
