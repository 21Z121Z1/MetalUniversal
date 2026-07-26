# 已知画面伪影候选根因图

> **2026-07-26 live-source correction**：本文为旧 presenter 时期的 forensic 快照。文中 `afterMinimumDuration`、`maximumFramesPerSecond` 采样、PresentThread 自行 `nextDrawable()` 的描述已不适用——当前实现基于 `CAMetalDisplayLink`，present 在 `needsUpdate` 回调内同步提交，显式 source-frame 状态机管理 drop/failure/shutdown，真实窗口 timeline 验收已通过（见 `../metalfx-frame-generation.md` 与仓库上级 `MinecraftMetal_MetalFX_Audit_2026-07-26.md` 第 17 节）。保留原文仅作历史证据链。

> **2026-07-26 status:** 本文是风险假设地图，不是当前缺陷清单。offscreen difference、Minecraft attachment capture 已建立；尚未覆盖的 attended 画面项见最终验收报告。

本文不修复任何问题。它把“历史运行中确实出现的现象”和“从当前代码可推导的候选原因”分开。除非写明 `confirmed artifact`，候选都需要 Sol 做控制变量视觉验证。

## 观察到的运行事实

1. 历史日志有成功 Temporal encode：`run/logs/latest.log:110-111`，输入 1144x642，输出 1708x960，jitter `(0,-0.16666666)`，motion scale `(572,321)`，depth reversed，direction `previousScreen-currentScreen`；这是历史运行证据，不是本轮新 capture。
2. 历史 crash 有 GUI scissor 1708x524 应用于 1144x642 render area：`run/crash-reports/crash-2026-07-26_02.17.39-client.txt:7,119-120`。
3. 代码使用 scaled scene target、native UI target，Temporal encode 位于 GUI 前（`MetalFxManager.java:393-516`；`GameRendererMetalFxMixin.java:78-84`）。
4. 当前 V2 motion writer 接受 depth/相机矩阵并额外消费 object motion/validity/disocclusion；但 object attachments 在 Java 世界绘制前只被清零，没有生产写入。V2 camera/merge 是 `MetallumNative.swift:1355-1475,1844-2011`，资源/clear 是 `MetalFxManager.java:642-700`；reactive direct writer 仍只接受五个透明目标，随后由 V2 camera/depth heuristic 叠加（`MetalFxManager.java:551-600`；`MetallumNative.swift:1098-1126,1175-1219`）。

## 镜头抖动候选树

| 候选 | 支持证据 | 反对/尚未证明 | 验证方法 | 具体符号 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| clip jitter 符号错误 | `clipJitter.y = -2*pixelJitter.y/renderHeight`，Y 方向特意取负（`MetalFxMath.java:51-63`） | 单测只证明内部约定，不证明 Metal viewport/texture Y 与该约定一致；没有 GPU capture | 固定相机、单独记录 jittered depth 和 screen-space sample，比较上下半像素方向 | `MetalFxMath.clipJitter`, `applyProjectionJitter` | weak_inference |
| V2 object motion producer 未接入 | V2 merge 只有 object validity > 0.5 才选择对象 motion；Java 只清零 object motion/validity，`MetalMotionStateStore.observe` 没有生产调用（`MetalFxManager.java:687-700`; `MetalMotionStateStore.java:31-44`; `MetallumNative.swift:1425-1450`） | 当前 camera motion/disocclusion 仍能完整生成，不能单独解释所有相机抖动；它更直接解释动态内容拖影 | controlled moving entity/particle/cutout scene，capture object validity/motion and final merge output | `prepareMotionInputs`, `MetalCommandEncoder.encodeMetalFxV2`, `metallum_metalfx_encode_v2` | confirmed absence; artifact relevance strong |
| pixel jitter 与 clip jitter 比例错误 | 公式分母使用 renderWidth/renderHeight；display/render 混用是已观察风险 | 对当前单元测试和历史日志而言比例内部自洽 | 运行 1.0/0.67/0.5，读 projection、depth sample、motion scale 和实际 viewport | `MetalFxMath.clipJitter`, `MetalFxManager.prepareSceneProjectionInternal` | strong_inference candidate |
| display size 替代 render size | 历史 scissor `1708x524` 对 `1144x642` 是直接反证；native output 为 display size | 成功 Temporal 日志的 input/output尺寸符合预期，不能指出具体调用者 | capture 每个 render pass bound texture/render area/scissor，特别是 GUI draw | `GameRendererMetalFxMixin` width/height redirects, `MetalRenderPass.begin` | strong_inference |
| projection aspect 使用了错误尺寸 | manager 同时计算 displayAspect/renderAspect 并修改 projection（`MetalFxManager.java:306-319`） | 该逻辑的设计目标是保持显示相机，未见直接错误数值 | 同一 FOV 下对 display/render aspect 读回 projection m00/m11 与实际 image | `MetalFxMath.adjustPerspectiveAspect`, `prepareSceneProjectionInternal` | weak_inference |
| FOV 来源或提取错误 | 历史日志 `fieldOfView=76.75938`；FOV 从 `cameraState.projectionMatrix` 提取（`MetalFxManager.java:316-318`） | FOV 数值可能是当前 camera state 的真实值；单测只覆盖标准 70 度 | 记录游戏 FOV、projection m11、MetalFX FOV 三者同帧值 | `MetalFxMath.verticalFieldOfViewDegrees`, `frameFieldOfView` | weak_inference |
| previous matrix 保存时机错误 | `previousViewProjection` 在成功 encode 后更新（`MetalFxManager.java:511-515`），跳帧/失败时可能改变节奏 | 显式 invalid/reset 分支存在，静止相机单测通过 | 连续记录 frame index、historyReset、current/previous hash、encode success | `previousViewProjection`, `beforeGuiInternal` | strong_inference candidate |
| motion current/previous 方向反转 | native V2 camera/merge 和 Java mirror 都写 current-to-previous；Xcode 26.5 MetalFX header 对同一契约的例子是向右/向下 10 像素写 `(-10,-10)`（`MetallumNative.swift:1355-1450`; `MetalFxMath.java:120-157`; `MTLFXTemporalScaler.h:266-286`; `latest.log:111`） | 没有 GPU 输出箭头 capture，但没有当前代码证据支持“方向反转” | 用已知相机平移和纹理箭头验证最终输出方向 | `metallum_motion_camera_v2`, `metallum_motion_merge_v2` | confirmed contract; not a leading root cause |
| motionVectorScale 错误 | 当前值是输入半尺寸 `(572,321)` | SDK 明确说 scale 把 motion 值转换为 fragment pixels；NDC delta 乘半宽/半高正是当前 V2 producer 的单位转换（`MTLFXTemporalScaler.h:266-286`; `MetallumNative.swift:1992-1996`） | 仍可用 GPU capture 验证实际 sampled displacement，但不能再把 scale 本身列为未知 | `MetalCommandEncoder.encodeMetalFxV2`, `metallum_metalfx_encode_v2` | confirmed contract |
| UI 或手部错误 jitter | GUI 明确在 beforeGui 后；手在 renderLevel 内 | GUI 分离代码反驳 HUD 被 scene jitter；手是 scene-side，可能合理地随相机 jitter | 分别 capture hand/main target 与 GUI target projection/viewport | `GameRendererMetalFxMixin.beforeGui`, `GuiRendererMetalFxMixin.draw` | GUI weak; hand unknown |
| history reset 时序错误 | 日志在 resize/invalid matrix/renderer reset 触发 reset（`latest.log:79-110`） | reset 机制和 `historyReset` 初值存在；没有证据它长期 true | 连续帧统计 reset flag、成功 encode 和 previous matrix valid | `resetHistoryInternal`, `frameResetForPresent` | strong_inference candidate |
| FG present pacing 造成抖动 | native presenter 创建时采样 `maximumFramesPerSecond`，真实帧使用 `afterMinimumDuration(frameDuration * 0.5)`（`MetallumNative.swift:149-170,699-728`） | 历史成功日志 `frameGeneration=false`，所以不能解释该次非-FG现象；没有 VRR/presentation timestamp 证据 | 关闭/开启 FG 对比，按实际 refresh timestamp 画 present 间隔，并覆盖 presenter 创建后切屏/刷新率变化 | `MetalFrameGenerationPresenter`, `process`, `presentRealFrame` | strong only when FG enabled; final timing unknown |
| drawable timing/VRR | native没有refresh/VRR query | 当前日志没有 present timestamps | Metal capture + display link/present timestamp at 60/120/VRR | `CAMetalLayer`, native present worker | unknown |
| partial tick不一致 | Minecraft camera/entity uses partial tick; MetalFxManager只接收最终 projection/camera state | 当前代码没有直接显示不同 partial tick 的两套值 | 记录 DeltaTracker partial、camera state、projection modify arg、encode frame id | `GameRenderer.update/extract/render`, `prepareSceneProjectionInternal` | weak_inference |

### 当前排序

对历史 GUI/scissor 反证，display/render/viewport 混用是证据最强的尺寸候选。对纯 Temporal 相机抖动，当前最值得先排除的是 projection/depth/viewport 对齐与 previous matrix/skip-frame 时序；方向反转不是当前最可信根因，因为代码、日志、单测互相支持现行方向。历史成功帧记录 `frameGeneration=false`，且当前 source gate 也为 false，所以 FG pacing 不能解释该次非-FG现象；V2 object producer 缺失更直接对应动态内容拖影。

## 树叶/草拖影候选树

| 候选 | 支持证据 | 反对/尚未证明 | 验证方法 | 具体符号 | 置信度 |
| --- | --- | --- | --- | --- | --- |
| alpha-cutout 没进入 direct reactive | Sodium CUTOUT 是 alpha discard 的独立 pass；manager direct mask 只有五个 transparency targets（`DefaultTerrainRenderPasses.java:5-9`; `MetalFxManager.java:518-524`） | depth-edge heuristic 可能间接覆盖 cutout 边界 | 单独录树叶/草 CUTOUT 的 reactive texture 和 main depth | `TerrainRenderPass`, `addTransparencyReactivePassInternal` | strong_inference |
| cutout 被当 opaque 处理 | CUTOUT 与 SOLID 都不在 translucent target；共享 main color/depth | cutout 的 discard 和 depth 仍可让 heuristic 工作 | 对同一树叶用 cutout/translucent材质对比 | Sodium `CUTOUT`, `ShaderChunkRenderer` | strong_inference |
| depth neighborhood heuristic 覆盖不足或过强 | native 使用 3x3 depth validity/gradient，目标不是材质语义（`MetallumNative.swift:1175-1209,1255-1265`） | 该 heuristic 设计上可覆盖静态边界 | capture depth gradient、mask value、edge rejection 逐像素对照 | native motion/reactive MSL producer | strong_inference |
| reactive 与 color/depth 没对齐 | motion/reactive尺寸按 renderWidth/renderHeight创建；历史 GUI/scissor 证明存在尺寸混用风险 | manager 的 auxiliary size guard 明确检查相同 renderWidth/renderHeight（`MetalFxManager.java:609-630`） | capture texture dimensions, viewport, dispatch threads and bound main depth | `ensureAuxiliaryTextures`, native dispatch | strong_inference candidate |
| motion 只含相机、不含叶片风动顶点 | runtime motion kernel 只用 depth/相机；没有 vertex previous state（`MetallumNative.swift:1211-1264,1473-1506`） | 若风动只改变少量 alpha/depth，reactive可能缓解 | static wind-off vs wind-on，固定相机比较 motion/history | `metallum_motion_reconstruction`, Sodium CUTOUT shader path | strong_inference |
| wind/模组 vertex shader 动画不可得 | generic compiler/PSO没有 velocity output，MRT缺失 | 具体 shader 可能有 time uniform，但无当前/上一时刻位置记录 | enumerate shader key/defines and inspect vertex outputs; no regex patch in this task | `MetalCrossShaderCompiler`, `MetalCompiledRenderPipeline` | strong_inference |
| alpha coverage/MIP变化 | CUTOUT alpha discard 与纹理 MIP 可能改变 coverage | 当前没有纹理/MIP capture或代码级 MetalFX proof | 同一叶片锁定 mip/anisotropy，对比 pre/post color and reactive | Sodium/MC shader resource path | unknown |
| mask生成顺序太晚或只覆盖 always-on-top | 注入点在 `LevelRenderer.addAlwaysOnTopPass` HEAD；直接 source handles由FrameGraph提供 | handles在 graph 中可读且日志五个 target 都存在 | GPU capture pass order，确认 mask dispatch在最终 cutout/transparency写入之后 | `LevelRendererMetalFxMixin`, `LevelRenderer.addAlwaysOnTopPass` | strong_inference candidate |
| translucent合成改变了 cutout邻域 | LevelRenderer有 sorting/transparency post chain，main/translucent depth会复制/合成（`LevelRenderer.java:396-431,835-837`） | 树叶本身通常 CUTOUT，不等于 translucent | capture main/translucent copyDepth/composite前后 | `LevelRenderer` transparency chain | weak_inference |
| jittered depth 与 motion重建不一致 | Java明确用 jittered inverse reconstruct，并用 unjittered VP计算 motion（`MetalFxManager.java:360-371,348-349`） | 数学单测专门验证 jitter不变成motion | read actual depth generated by bound projection and compare inverse reconstruction | `jitteredViewProjection`, `inverseCurrentViewProjection` | weak_inference |
| history rejection/disocclusion不合适 | MetalFX driver 内部历史拒绝不可由当前 Java 直接观察 | 没有 driver rejection trace | controlled static/camera/wind scene with Metal capture and output diff | `metallum_metalfx_encode` | unknown |
| mask强度近二值 | native 用 max channel threshold，非连续 material strength | binary mask可能足够保守，但不适用于全部树叶 | readback/Metal capture mask histogram | `metallum_metalfx_mark_transparency` | confirmed implementation, artifact relevance strong |
| MetalFX input format/alpha semantics | target 是 RGBA8_UNORM；color space/alpha转换没有 capture | runtime encode succeeds | capture texture pixel format/color space/alpha and compare output | `ensureTargets`, native scaler descriptor | unknown |

## 支持与反证汇总

- 最强的叶片候选是“CUTOUT 不在 direct reactive + V2 object producer 未接入”。两者都由当前路径直接支持；camera/disocclusion reactive 只能间接缓解。
- “reactive 完全为空”与代码/日志相矛盾：`R8_UNORM` 有 allocation，五个 target 在日志中均为 true。
- “所有透明内容都在同一个 target”与 LevelRenderer 的五个 target/透明 chain 相矛盾。
- “只要加 reactive 就有真实 object motion”没有证据；mask 与 velocity 是不同资源。

## 不在本文件中完成的验证

本文件没有改 shader、没有插入日志、没有执行 GPU capture，也没有声称树叶拖影或镜头抖动的单一根因已经闭合。Sol 应先使用现有日志/捕获接口确认尺寸和 projection，再选 CUTOUT mask、object motion 或 history policy 的实现边界。
