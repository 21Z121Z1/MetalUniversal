# Sol 适配接入点地图

> **2026-07-26 status:** 本文是规划/适配地图，不是当前实现状态。已经完成的 MRT、普通实体纵切、三层验证与剩余 producer 缺口见最终验收报告；gate 仍关闭。

本文件是后续实现模型的边界说明，不是实现方案补丁。每个目标都把当前事实、缺失输入、最小接入符号和验证门槛分开。`recommended_symbols` 只表示应先检查的现有边界，不表示已经修改。

## A. 修复 Temporal 相机抖动

```text
目标：
闭合 camera jitter、projection、depth、motion、history 与 display/render/viewport 的同帧契约。

当前路径：
GameRenderer.renderLevel
 -> GameRendererMetalFxMixin.metallum$prepareSceneProjection
 -> MetalFxManager.prepareSceneProjectionInternal
 -> MetalFxMath.pixelJitter/clipJitter/applyProjectionJitter
 -> MetalFxMath.viewProjection mirror / native metallum_motion_reconstruction
 -> MetalFxManager.beforeGuiInternal
 -> MetalFX temporal encode

已确认问题：
- 历史运行有 GUI scissor 1708x524 对 1144x642 render area 的 crash；尺寸契约存在运行时反证。
- jitter 公式、motion 方向和静止/平移/旋转数学单测相互一致；“符号反转”不是当前最强根因。
- previousViewProjection 在成功 frame 后更新；跳帧、invalid matrix、resize 的跨帧时序仍未由 capture 闭合。
- motionVectorScale 在历史日志为输入半尺寸，但 MetalFX 对 RG16_FLOAT 单位的最终解释没有 GPU proof。

建议修改点：
- 第一检查边界：GameRendererMetalFxMixin 的 width/height/projection ModifyArg 与 MetalRenderPass 的 renderArea/scissor 传播。
- 第二检查边界：MetalFxManager.prepareSceneProjectionInternal 的 displayAspect/renderAspect、jittered inverse 与 previousViewProjection update timing。
- 第三检查边界：MetalCommandEncoder.encodeMetalFx/native metallum_metalfx_encode 的 inputContentWidth/Height、motionVectorScale、depthReversed 参数。
- 仅当 capture 证明矩阵/viewport正确而仍抖动时，再看 `MetalFX PresentThread`/present timing；非-FG历史日志不能先归因于 FG。

涉及文件：
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxMath.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalRenderPass.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/native/MetallumNative.swift

涉及符号：
GameRendererMetalFxMixin.metallum$prepareSceneProjection (59-75)
MetalFxManager.prepareSceneProjectionInternal (285-390)
MetalFxManager.beforeGuiInternal (393-516)
MetalFxMath.clipJitter/applyProjectionJitter/reconstructMotion (45-68,120-157)
MetalRenderPass scissor setup (532-548)
metallum_metalfx_encode (1414-1579)

数据输入：
windowRenderState width/height、main target width/height、display/render aspect、final Mojang projection、camera position、partial tick、depth、current/previous VP、jitter、motion scale。

数据输出：
同帧 scene projection/depth、RG16_FLOAT motion、Temporal output、history reset state、runtime dimensions日志。

生命周期：
resize/fullscreen/Retina/FOV/camera mode/teleport/world change/invalid matrix must define reset and previous matrix validity; existing reset paths are partial.

线程：
projection/motion arguments on Minecraft render thread; native encode on same call path; FG pacing is separate and must be isolated in tests.

回归风险：
改变 projection input可能同时影响 vanilla world depth、GUI separation、Sodium terrain和post-processing；宽作用域 width/height redirect可能影响非-MetalFX target。

最小验证：
OFF/SPATIAL/TEMPORAL at 1.0/0.67/0.5; static camera, pure pan, pure rotation; capture bound texture size, render area, viewport/scissor, jitter, projection m20/m21, motion scale and history reset per frame.

完整验证：
Retina/fullscreen/odd sizes, FOV and camera mode changes, teleport/world transition, invalid frame recovery, non-FG and FG separately, Metal GPU capture with actual depth/motion/output.

不要修改：
不要先修改 Sodium settings、GUI renderer、Frame Generation pacing或通过固定零 motion掩盖矩阵问题；不要把旧 rollout 中不存在的 baseProjection 等字段当作当前接口。
```

**当前最可能根因：strong_inference candidate。** 先排除尺寸/viewport 与 previous matrix timing；不能在没有 capture 前宣称单一根因。

## B. 改善树叶/草拖影

```text
目标：
让 alpha-cutout、风动顶点和其深度/颜色在 Temporal history rejection 中有明确、对齐且可验证的覆盖。

当前路径：
Sodium DefaultTerrainRenderPasses.CUTOUT
 -> TerrainRenderPass / ShaderChunkRenderer
 -> DefaultChunkRenderer.render -> MetalRenderPass indexed attachment array（当前 terrain pipeline 只声明一个 color target）
 -> main scene color/depth
 -> MetalFxManager.addTransparencyReactivePassInternal
 -> metallum_metalfx_mark_transparency
 -> Temporal scaler

已确认问题：
- CUTOUT 与 SOLID 共享 main scene，五个 direct reactive targets 只有 translucent/itemEntity/particles/weather/clouds。
- native mask 是 threshold + depth neighborhood heuristic，不知道 material classification、alpha coverage 或 wind vertex motion。
- 当前 CUTOUT/terrain pipeline 只声明 color attachment 0；Java/native backend 已有 indexed attachment path，但不能无声给现有 shader 增加 velocity 输出。

建议修改点：
- 最小保守路线的事实接入点是 Sodium CUTOUT pass identity 与 main color/depth 对齐处，再把 cutout classification 映射到 reactive producer；代价是仍没有真实对象 motion。
- 若需要连续 mask，边界在 native `metallum_metalfx_mark_transparency` 的 R8 producer及其 input binding，而不是 GUI/present worker。
- 若需要 wind/object motion，边界在 Sodium shader vertex data/previous transform与通用 pipeline attachment契约，不能只改 reactive compute。
- mask dispatch 顺序必须在相关 CUTOUT color/depth 写入后、Temporal encode 前；`LevelRendererMetalFxMixin` HEAD handles 是现状证据，不是对未来 pass order 的保证。

涉及文件：
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/LevelRendererMetalFxMixin.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/native/MetallumNative.swift
- /tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass.java
- /tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/ShaderChunkRenderer.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java

涉及符号：
LevelRendererMetalFxMixin injection at addAlwaysOnTopPass HEAD (17-25)
MetalFxManager.addTransparencyReactivePassInternal (518-566)
metallum_metalfx_mark_transparency (1355-1411)
DefaultTerrainRenderPasses.CUTOUT (5-9)
TerrainRenderPass.getTarget (10-43)
MetalCommandEncoder.renderCommandEncoder/createRenderPass (134-180,205-227)
MetalCompiledRenderPipeline color attachment setup (114-125,187-216)

数据输入：
CUTOUT material/pass identity、alpha/coverage、main depth、renderWidth/renderHeight、optional wind/time/object previous state。

数据输出：
R8 reactive mask or future motion attachment, aligned with main color/depth, consumed by Temporal before GUI.

生命周期：
mask must be recreated on render-size change and cleared/rewritten each frame; wind/object previous state must reset on world/teleport/reload.

线程：
Sodium draw and FrameGraph pass on render thread; chunk mesh building may be worker-side but current motion contract is not worker-safe by evidence.

回归风险：
Over-reactive mask can reject history everywhere; under-reactive mask leaves trailing; changing CUTOUT target may alter depth, sort and mod shader compatibility; MRT expands PSO/bridge risk.

最小验证：
Static cutout with static camera; camera pan; fixed camera with wind; material A/B cutout vs translucent; capture color/depth/reactive dimensions and values.

完整验证：
Leaves/grass/water/glass, animated textures/MIP, Sodium on/off, third-party shader/entity, render scales and all MetalFX modes, visual output plus GPU capture.

不要修改：
不要把所有 CUTOUT 直接复制到 translucent target或用全屏白 reactive mask作为完成；不要把 particle/entity direct target当叶片 motion proof；不要改 GUI/pacing来掩盖 cutout缺失。
```

## C. 增加动态实体 motion

```text
目标：
把实体、玩家、手、方块实体、粒子的 current/previous transform 或 vertex motion 转成 Temporal/FG 可消费的 motion，同时保持 depth/reactive 生命周期。

当前路径：
EntityRenderer.extractRenderState (xOld/current + partialTicks)
 -> EntityRenderDispatcher.submit / feature dispatcher
 -> generic MetalRenderPass with indexed attachment array; current entity pipeline declares one color target
 -> MetalFxManager.prepareMotionInputs clears objectMotion/objectValidity
 -> native metallum_metalfx_encode_v2 camera reconstruction + object/camera merge

已确认问题：
- EntityRenderState只有当前 x/y/z，Particle虽然有 xo/yo/zo和velocity，但两者都没有进入 MetalFxManager/native motion input。
- BlockEntityRenderState只有当前 blockPos/blockState/type；renderer-local animation state没有统一 previous contract。
- hand是renderLevel scene-side，不是GUI，当前也没有独立 motion target。
- current entity/block/particle pipeline declarations expose no motion output/validity contract; V2 Java/native resources and merge are connected, but `objectMotionTexture`/`objectValidityTexture` are only cleared. Generic Java/native pass and PSO preserve indexed attachment slots, so the missing boundary is the renderer producer/shader/FrameGraph contract rather than a proven native single-attachment limit。
- `MetalMotionStateStore.observe` has no production caller; `MetalMotionContract.projectVertex` is test-only. The transaction commit/discard/reset hooks are scaffolding, not object-motion implementation (`MetalMotionStateStore.java:31-44,60-82`; `MetalFxManager.java:53,301,510,547,710,776`).

建议修改点：
- depth reconstruction 保留点：`MetalFxManager.prepareSceneProjectionInternal`、native V2 `metallum_motion_camera_v2`/`metallum_motion_merge_v2`（`MetallumNative.swift:1355-1475,1844-2011`）；不要先移除它，它仍覆盖静态几何/相机运动和 disocclusion reactive。
- MRT路线需要保留现有 indexed backend path，同时审查 Minecraft RenderPipeline/RenderPassDescriptor、FrameGraph target creation、MetalCommandEncoder.createRenderPass、MetalRenderPass、MetalCompiledRenderPipeline、MetalCrossShaderCompiler 的 fragment output preservation、MetalNativeBridge descriptor functions、native MTLRenderPipelineDescriptor/encoder，以及所有 shader fragment outputs。当前已确认 backend capacity，不等于当前 shader output/运行 pipeline 已接通。
- velocity replay路线需要审查 EntityRenderer.extractRenderState/EntityRenderState、EntityRenderDispatcher.submit、BlockEntityRenderDispatcher.extract/submit、ParticleEngine.extract、first-person render path，并定义上一帧 object/animation state 的保存和 reset；现有 `MetalMotionStateStore` 可作为事务边界，但没有现成 producer symbol 可直接填充。
- 实体/玩家/掉落物/载具/falling block可先以 EntityRenderer current/previous source 建契约；手需要 GameRenderer.renderLevel/itemInHandRenderer 单独契约；block entity需要每个 renderer state；粒子可利用 Particle.xo/yo/zo但必须对齐 ParticleEngine partialTick。

涉及文件：
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/EntityRenderer.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/state/EntityRenderState.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/EntityRenderDispatcher.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/particle/Particle.java
- /tmp/minecraftmetal-mc26-sources/net/minecraft/client/particle/ParticleEngine.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalRenderPass.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/native/MetallumNative.swift

涉及符号：
EntityRenderer.extractRenderState (154-244)
EntityRenderDispatcher.submit (148-183)
BlockEntityRenderDispatcher.extract/submit (76-108)
Particle.tick / ParticleEngine.extract (94-115,128-133)
native metallum_motion_reconstruction (1211-1264,1473-1506); Java MetalFxMath.reconstructMotion mirror (120-157)
MetalCommandEncoder.renderCommandEncoder/createRenderPass (134-180,205-227)
MetalCompiledRenderPipeline (114-125,187-216)
native `metallum_metalfx_encode` (1414-1579)

数据输入：
stable entity/block/particle identity, current/previous transform, partial tick, local bone/pose/vertex animation, depth, jittered/un-jittered camera matrices, render size.

数据输出：
per-pixel motion attachment or replay-generated motion texture, optionally object/velocity reactive classification, same scene/depth coordinate system as Temporal and FG.

生命周期：
save previous state after successful render, reset on first frame/world change/teleport/resize/camera mode/resource reload; do not retain state across deleted IDs without generation handling.

线程：
state extraction and submit on render thread; entity/chunk/particle simulation may update elsewhere, so snapshot boundary must be explicit.

回归风险：
MRT changes every pipeline and third-party shader; replay may double-render, alter blending/depth, or use stale transforms; Sodium indirect batching complicates per-object state.

最小验证：
runtime pipeline/attachment enumeration plus one moving entity, player hand, item, block entity, particle and falling block; compare motion direction against known translation.

完整验证：
Sodium on/off, mod entities/shaders, bones/wind, translucent/cutout, teleport/world/reload/resize, FG inputs and visual history output.

不要修改：
不要用 zero/random motion、静态截图、 donor transform 或把 camera-only texture重命名为 object motion；不要把 `prepareMotionInputs` 的 clear 当成 object producer；不要在没有现有 indexed attachment 和 shader output contract 对齐前改 fragment MSL 正则。
```

## D. 修正 Frame Generation pacing

```text
目标：
使真实帧/插值帧的顺序、间隔、slot复用和drawable present符合实际刷新率/VRR，并保持输入与资源同步不变量。

当前路径：
MetalCommandEncoder.presentTextureToDrawable
 -> MetalFxManager.frameGenerationInputInternal
 -> metallum_metalfx_frame_generation_encode
 -> native FrameInterpolator slots
 -> MetalFX PresentThread
 -> CAMetalLayer drawable acquire/present

已确认问题：
- 当前 source 将 `OBJECT_MOTION_PRODUCER_CONNECTED` 固定为 `false`，因此 `frameGenerationEnabled` 当前永远不会在 manager 构造时开启（`MetalFxManager.java:29-33,99-116`）；native presenter/pacing 是 dormant conditional path，不是当前每帧实际 present。
- `frameDuration` 在 presenter 创建时采样 `maximumFramesPerSecond`，真实帧使用 `afterMinimumDuration(frameDuration * 0.5)`；没有动态 display timing/VRR query（MetallumNative.swift:149-170,699-728）。
- native有三个private slots、一个 `MetalFX PresentThread` worker、一个 `readyEvent`、`maxOutstandingFrames=1` 和 timeout/drop（MetallumNative.swift:60-195,419-762）。
- Java输入分为pre-GUI scene和post-GUI composed UI；FG继承camera-only motion限制。
- GUI/overlay active时manager会暂停 FG；关闭GUI后 `beginFrameInternal` 会恢复 Java 标志并 reset history，但 native presenter 的重新可用性仍需运行验证。

建议修改点：
- 先检查 `MetalFX PresentThread` 的 drawable acquire/present、`afterMinimumDuration`、`maximumFramesPerSecond` 采样和 `metallum_configure_layer`/CAMetalLayer surface配置；显示时序接入点应在native present worker附近，而不是Minecraft GUI。
- 保留 ready event -> interpolated output -> real composed frame 的所有权顺序；只有GPU completion后才复用slot。
- 将实际 display timing/VRR作为输入契约后，再决定 `frameDuration` 和真实帧间隔；当前不能把初始化时的 `maximumFramesPerSecond` 当成动态 display timing。

涉及文件：
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/native/MetallumNative.swift
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalSurface.java

涉及符号：
MetalCommandEncoder.presentTextureToDrawable (251-287)
MetalFxManager.frameGenerationInputInternal (707-740)
metallum_metalfx_frame_generation_encode (1581-1663)
MetalFX PresentThread / process (188-195,605-728)
metallum_configure_layer (2919-2940)

数据输入：
actual display refresh/present timestamps, drawable availability, GPU completion/shared-event values, real/interpolated frame IDs, resize/hidden state.

数据输出：
timestamped interpolated and real presents, slot ownership state, dropped frame/error state, latency measurements.

生命周期：
first frame previous=self/reset; swap only after ready; resize/shutdown drain; GUI activation pause/resume with reset; hidden/background/VRR behavior must be explicit.

线程：
Minecraft render thread enqueues; native `MetalFX PresentThread` owns present scheduling; `readyEvent`/condition variable are synchronization boundary.

回归风险：
wrong order can show stale GUI, release in-flight texture, deadlock worker, add latency, or duplicate drawable acquisition; initialization-time refresh sampling may become stale on 60/90/144/VRR or display changes.

最小验证：
FG at known 60 and 120 Hz with timestamped real/interpolated presents, resize with one source frame outstanding, first frame, timeout/drop, menu open/close.

完整验证：
60/90/120/144/VRR, windowed/fullscreen/hidden/background, GPU load/drop, resize/Retina, input latency, command-buffer errors and worker shutdown.

不要修改：
不要先调整 `frameDuration` 采样、不要把初始化 refresh rate 当成 VRR、不要把 GUI texture 塞进 Temporal history、不要绕过 `readyEvent` 来“修”顺序。
```

## E. 完成 Sodium 设置

```text
目标：
使 MetalFX mode/scale/reactive/Frame Generation 的 Sodium Config API入口、持久化、能力gate和重启语义与实际 manager行为一致。

当前路径：
fabric.mod.json sodium:config_api_user
 -> MetalFxSodiumConfig
 -> MetalFxConfig / persistent settings + system property overrides
 -> MetalFxManager construction chooseMode/selectMode
 -> native support checks

已确认问题：
- 入口和 option builder存在：`fabric.mod.json:27-29`、`MetalFxSodiumConfig.java:13-119`。
- config读写、system property override、persistent settings在 `MetalFxConfig.java:87-163,244-295`。
- capability gating在 manager construction；mode/effective fields是初始化时决定的，没有完整动态切换契约。
- 历史 Sodium crash `Storage handler must be set`（run/crash-reports/crash-2026-07-26_09.45.27-client.txt:7）证明配置运行时 setup 有独立失败面。

建议修改点：
- 先核对 `MetalFxSodiumConfig` option值与 `MetalFxConfig.persist/override` 的读写键是否一一对应，再核对 manager effective mode的fallback显示。
- capability gating应继续由 MetalFxManager.chooseMode/native supports驱动；UI不能把 unsupported Temporal显示成已生效。
- 明确 mode/scale/frameGeneration/reactive改变需要重启、renderer reset还是安全的下一帧切换；当前代码证据偏向初始化/重建语义。

涉及文件：
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/resources/fabric.mod.json
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxSodiumConfig.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxConfig.java
- /Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java

涉及符号：
MetalFxSodiumConfig option builders (13-119)
MetalFxConfig.load/persistentSettings/override methods (87-163,244-295)
MetalFxManager.chooseMode/selectMode (231-257)
MetalFxManager.initialize (103-105)

数据输入：
Sodium option values, persistent properties, system property overrides, device support booleans, iOS state, current mode/scale.

数据输出：
effective mode/scale, target dimensions, reactive/FG enable state, user-visible option state and restart/reset requirement.

生命周期：
initialization, renderer reset, world change, resize and session disable must preserve config/effective-state consistency; GUI pause/resume is a separate behavior and must preserve native presenter readiness.

线程：
Sodium UI/config operations may occur on GUI/render lifecycle; manager construction and target changes must remain render/device-safe.

回归风险：
stale UI can claim Temporal while manager falls back Spatial/OFF; config API version or storage handler changes can crash before rendering; live toggles can free in-flight targets.

最小验证：
read/write each option, restart, unsupported capability fallback, OFF/SPATIAL/TEMPORAL/AUTO, scale 100/67/50, reactive and FG toggles, no Sodium and Sodium paths.

完整验证：
GUI persistence, config migration, device/iOS gating, resource reload, world/resize/FG transitions, crash-free Storage handler setup and runtime logs matching displayed effective state.

不要修改：
不要在设置页为未知设备硬启用 Temporal、不要用 config fallback制造motion/mask、不要在未定义生命周期前实现无重启的资源切换。
```

## 适配边界总表

| 目标 | 当前最小事实边界 | 首先读取 |
| --- | --- | --- |
| 相机抖动 | size/viewport/projection/previous timing | `GameRendererMetalFxMixin`, `MetalFxManager`, `MetalFxMath`, `MetalRenderPass` |
| 树叶拖影 | CUTOUT identity + reactive alignment + missing vertex motion | Sodium `TerrainRenderPass`, `LevelRendererMetalFxMixin`, native reactive producer |
| 动态 motion | game current/previous state + backend attachment contract | MC entity/particle/block entity extraction, `MetalCompiledRenderPipeline`, native descriptor |
| FG pacing | worker/drawable timing/refresh-rate sampling | `MetallumNative.swift` `MetalFrameGenerationPresenter`, `MetalCommandEncoder.presentTextureToDrawable` |
| Sodium settings | config key/value/capability/restart contract | `MetalFxSodiumConfig`, `MetalFxConfig`, `MetalFxManager.chooseMode` |

完成任何一项前，Sol 仍必须保留本目录中的证据等级和 `14-inconsistencies.md` 约束：当前没有 Git 基线，旧 rollout 不是代码事实，native build 不是 iOS runtime proof，单元测试不是视觉证明。
