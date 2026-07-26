# 动态内容与透明内容

> **2026-07-26 live-source correction**
>
> 本文正文是 producer 接入前的覆盖审计。普通实体当前已有真实 current/previous transform、motion + validity MRT、camera/object merge 和自动客户端数值读回；block entity、first-person hand/item、CPU/vertex animation 及部分透明内容仍只有 fallback/reactive 策略。完整的当前覆盖矩阵见 `../metalfx-motion-pipeline-implementation.md`，未覆盖项仍是工程缺口，不能记为环境限制。

## 证据边界

当前 Temporal producer 是 native V2 `metallum_metalfx_encode_v2`：camera kernel 写 camera motion/disocclusion，merge kernel 从 object validity 非零的像素选择 object motion（`MetallumNative.swift:1355-1475,1844-2011`）。但 `MetalFxManager.prepareMotionInputs` 只在世界绘制前清零 object motion/validity，当前生产代码没有 renderer/MRT/velocity replay 写回（`MetalFxManager.java:687-700`；`rg` 未发现生产 `observe(...)` 调用）。Java `MetalMotionStateStore` 和 `MetalMotionContract.projectVertex` 是状态/数学 scaffold，`projectVertex` 只有测试调用（`MetalMotionStateStore.java:31-44,60-75`；`MetalMotionContract.java:64-98`；`MetalFxMathTest.java:77-169`）。因此下面区分两件事：Minecraft 是否拥有某类内容的 current/previous 状态；这些状态是否已经接入 motion/reactive/pipeline。前者存在不代表后者存在。

## Java motion scaffold 的实际边界

`MetalFxManager` 持有 `motionStateStore`，每帧开始调用 `beginFrame()`，成功的 MetalFX encode 调 `commitSubmittedFrame()`，失败调 `discardFrame()`，reset/close 清空 previous/pending（`MetalFxManager.java:53,290-301,509-510,542-549,703-715,775-777`）。但是 `MetalMotionStateStore.observe(ObjectKey, Matrix4fc)` 没有生产调用，且没有公开给实体、方块实体、粒子或 Sodium renderer 的 bridge。`MetalMotionContract.projectVertex(...)` 能计算 current raster clip 与 current/previous unjittered NDC motion，但也没有生产调用；它只由 `MetalFxMathTest` 调用。**结论：事务生命周期已搭出，producer 接入未发生。confidence=confirmed absence in inspected source; complete third-party renderer search remains runtime-unknown.**

V2 object resources 也不能反推 producer 已存在：`objectMotionTexture`/`objectValidityTexture` 具有 render-attachment usage，但 `prepareMotionInputs()` 的唯一生产调用是清零；V2 merge 只有 validity > 0.5 才覆盖 camera motion（`MetallumNative.swift:1425-1450,1844-1985`）。

## Minecraft 26.2 动态状态来源

### 普通实体、玩家、模组实体

`EntityRenderDispatcher.extractEntity` 把同一个 partial tick 传给 renderer（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/EntityRenderDispatcher.java:133-145`）；`EntityRenderer.extractRenderState` 再用 `Mth.lerp(partialTicks, entity.xOld, entity.getX())` 等产生 state.x/y/z，并保存 `ageInTicks = tickCount + partialTicks`（`EntityRenderer.java:154-171`）。`EntityRenderState` 只有当前 x/y/z、age、bounds、pose/renderer-specific state 等字段，没有 previous transform（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/state/EntityRenderState.java:16-43`）。`EntityRenderDispatcher.submit` 再把当前 render state 平移到 PoseStack 并调用 renderer submit（`EntityRenderDispatcher.java:148-183`）。

**结论：** 游戏对象有旧位置和当前位置，partial tick current render state 可得；但当前 MetalFx bridge 不读取这些 state，也没有 object ID/previous transform texture。玩家和模组实体若走 vanilla `EntityRenderer` 继承该事实；若模组走自定义 renderer/shader，是否进入同一 backend 未在首轮枚举。

### 静态区块

Sodium 将区块 draw 分为 SOLID/CUTOUT/TRANSLUCENT，`ChunkSectionsToRenderMixin.renderGroup` 取消 vanilla group draw 并调用 `SodiumWorldRenderer.drawChunkLayer`（`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/mixin/core/render/world/ChunkSectionsToRenderMixin.java:28-47`；`SodiumWorldRenderer.java:220-246`）。静态区块顶点位置已经在 chunk mesh 中，当前 motion 只会把它解释成相机运动。

**动态区块/区块重建：** 当前证据能确认 mesh/pass 被重新构建，但没有发现一个“上一帧区块顶点位置”交给 `MetalFxManager` 的接口。区块内容改变属于 geometry/disocclusion，而非对象 motion。**confidence=strong_inference; 完整 chunk rebuild 生命周期需 runtime capture。**

### Item entity、掉落物、经验球、载具、falling block

这些对象属于 entity renderer 通路。`ItemEntityRenderer.extractRenderState` 只在通用 state 上追加 `bobOffset`，`submit` 再根据 `state.ageInTicks`/`bobOffset` 计算 bob、spin 和多 item offset（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/entity/ItemEntityRenderer.java:34-55,69-107`），但没有上一帧 bob/spin transform 进入 MetalFX。`LevelRenderer` 在 shader transparency 下创建 `item_entity` target，并在 main pass/always-on-top pass 中读写它（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/LevelRenderer.java:190-198,365-399,490-500`）；它随后只是 reactive input，不会自动提供 velocity。

Falling block 和载具的实体位移有 Entity `xOld/current` 插值；车辆 passenger offset 甚至在 EntityRenderer 中单独用 partial tick 计算（`EntityRenderer.java:173-183`），但没有 motion attachment。**confidence=confirmed current-state path, confirmed missing bridge field。**

### Block entity、活塞、方块实体动画

`BlockEntityRenderDispatcher` 以 partialTicks 调用 renderer `extractRenderState`，之后 submit 当前 state（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher.java:76-108`）。基础 `BlockEntityRenderState` 只有 `blockPos`、`blockState`、`blockEntityType`、light 和 break overlay，没有 previous position/transform（`BlockEntityRenderState.java:17-33`）。具体 renderer 可以在自有 state 中保存动画参数；当前 MetalFxManager 不接收这些 state，因此活塞、箱子、告示牌、模组 block entity 的局部动画没有对象 motion。

`BlockEntityRenderDispatcher.onResourceManagerReload` 会重建 renderer map（`:111-124`），这也使 renderer-local previous state 的生命周期需要单独设计；当前没有与 MetalFX history 的连接证据。

### 第一人称手与持有物

第一人称 hand/item 由 `GameRenderer.renderLevel` 内的 scene-side path 提交：LevelRenderer world pass 后，`renderItemInHand` 使用 `cameraEntityPartialTicks`，向 hand node storage 提交 hands/items/features；之后才离开 renderLevel，进入外层 `GameRenderer.render` 的 `beforeGui` 注入点（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:547-605`；MetalFX injection `GameRendererMetalFxMixin.java:78-84`）。`ItemInHandRenderer.submitHandsWithItems` 对 attack/view bob、hand height、use/swing animation 仍使用当前 `frameInterp`（`ItemInHandRenderer.java:346-383`）。它不是 `GuiRenderer` 的 HUD，但也没有独立 hand motion/reactive target；相机抖动和手部动画都进入同一低分辨率 scene color/depth，motion 仍是相机重建。

### 粒子

`Particle` 明确保存上一 tick `xo/yo/zo` 与 current `x/y/z`，还保存 velocity `xd/yd/zd` 和 age；tick 首先将 current 复制到 old 再移动（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/particle/Particle.java:19-40,94-115`）。`ParticleEngine.extract` 以 partialTickTime 调用每个 particle group 的 extraction（`ParticleEngine.java:128-133`），`SingleQuadParticle.extractRotatedQuad` 实际用 `Mth.lerp(partialTickTime, xo/x)` 等生成当前 quad 位置（`SingleQuadParticle.java:47-85`）。这是比 entity bridge 更明确的粒子 current/previous source，但当前 MetalFX motion shader 仍只读 scene depth/camera matrices。`LevelRenderer` 在 shader transparency 下创建/读写 `particles` target（`LevelRenderer.java:190-198,365-399`），所以 reactive direct coverage 可用；velocity replay 未接入。

### 雨雪、云、世界边界

`LevelRenderer` 将 clouds/weather 建成单独 FrameGraph passes；cloud pass 使用 cameraPosition/gameTime/partialTicks，weather pass 调用 `WeatherEffectRenderer.render`（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/LevelRenderer.java:450-488`）。`WeatherEffectRenderer.extractRenderState` 按 partial tick 生成雨雪 columns，render 使用 weather target（`WeatherEffectRenderer.java:66-98,119-145`）；CloudRenderer 根据 gameTime、partialTicks 和 camera position 计算 cloud offset，并按 cell/camera 状态重建 mesh（`CloudRenderer.java:149-207`）。对应 targets 是 `targets.clouds` 与 `targets.weather`（`LevelRenderer.java:190-198,450-488`），并由 manager reactive pass 读取。它们没有对象 previous transform 传给 motion；动态时间参数只影响颜色/顶点执行。

### 水、玻璃、alpha-cutout 树叶和草

Sodium `DefaultTerrainRenderPasses` 定义 SOLID、CUTOUT、TRANSLUCENT（`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/chunk/terrain/DefaultTerrainRenderPasses.java:5-9`），`TerrainRenderPass.getTarget()` 只在 `isTranslucent && useShaderTransparency()` 时选择 Minecraft `translucentTarget()`（`TerrainRenderPass.java:10-43`）。因此：

- 水/玻璃等 translucent material 可写 `translucent` target，直接进入 reactive mask；
- alpha-cutout leaves/grass 归 CUTOUT，和 SOLID 一样写 main scene target，不能被五个 transparency handles 直接识别；
- cutout 的 alpha discard 是 pipeline/material 语义，不等于 reactive mask；当前 native depth-edge heuristic 只能间接覆盖边界；
- 材质/纹理的 MIP alpha coverage、风动顶点动画和 history rejection 没有直接输入。

## 每类内容能力表

| 内容 | 稳定 ID | current/previous 状态 | partial/局部动画 | 当前 pipeline 能力 | velocity replay | reactive 能力 | 当前最佳事实接入边界 | 主要风险 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 静态区块 | chunk/section 游戏标识存在，但未进入 motion | current mesh；previous mesh未交给 MetalFX | chunk rebuild 非 partial object motion | Sodium SOLID/CUTOUT 当前 pipeline 单 color；backend 可按 index 绑定多附件 | 无 | depth heuristic only | `DefaultChunkRenderer.render` / backend attachment boundary | geometry change/disocclusion |
| 动态区块 | 未形成 motion ID contract | mesh rebuild state 未接入 | 未确认 | 当前 pipeline 单 color；backend indexed MRT 能力未被 terrain contract 使用 | 无 | depth heuristic | Sodium chunk render + history rejection boundary | rebuilt mesh与history错配 |
| 普通实体 | Entity ID 在游戏层存在 | `xOld/current` 插值；`EntityRenderState` 只保留 current x/y/z | `partialTicks`；renderer-local pose | generic entity pipeline 当前单 color；backend attachment array 已存在 | 未接入 | 仅若落入透明 target或depth heuristic | `EntityRenderer.extractRenderState` / feature submit 与 motion数据桥 | 模组 renderer/骨骼动画 |
| 玩家 | Entity path；player renderer state | current position/pose；无 MetalFX previous | player body/animation state local | 当前 pipeline 单 color | 无 | indirect | player renderer submit boundary | hand/body分层 |
| 第一人称手 | 无独立 MetalFX ID | current camera/player render state | hand animation partial | main scene 当前单 color；backend indexed path未被 hand pipeline 使用 | 无 | depth heuristic | `GameRenderer.renderLevel` before `beforeGui` | UI/scene误分离、jitter |
| 持有物/掉落物/经验球 | entity ID 游戏层 | entity current interpolated | item bob/spin current state | item/entity 当前单 color; item target may be reactive | 无 | itemEntity direct if routed | `ItemEntityRenderer.submit` / item target | bob/spin trailing |
| 载具 | entity/passenger state | xOld/current + passenger offset | partial tick | entity 当前单 color | 无 | indirect | EntityRenderer extraction | compound transform |
| 方块实体 | BlockPos/type | BlockEntityRenderState current pos/state only | renderer-specific partial state | generic block entity 当前单 color | 无 | indirect unless target path | `BlockEntityRenderDispatcher.extract/submit` | local animation/reload |
| falling block | entity path | xOld/current | partial tick | 当前单 color | 无 | indirect | entity renderer | movement trailing |
| 粒子 | particle object identity in engine, not motion texture | `xo/yo/zo`, current x/y/z, velocity | `ParticleEngine.extract(partialTickTime)` | particle target color; current pipeline no MRT | 无 | particles direct | `ParticleEngine.extract` + particles target | fast transient particles |
| 雨雪 | no stable per-drop motion handoff | weather render state/current camera | weather state/partial call | weather target | 无 | weather direct | `LevelRenderer.addWeatherPass` | density/alpha |
| 云 | renderer/time input | cameraPosition/gameTime; no previous motion | partialTicks | clouds target | 无 | clouds direct | `LevelRenderer.addCloudsPass` | temporal cloud drift |
| 水/玻璃 | block material, no motion ID | static/translucent mesh | fluid animation shader possible | TRANSLUCENT blend target | 无 | translucent direct | Sodium TRANSLUCENT target | sort/alpha |
| alpha-cutout 树叶/草 | block material, no motion ID | main scene current depth/color | wind vertex shader possible | CUTOUT discard, single color | 无 | depth heuristic only | Sodium CUTOUT pipeline + mask boundary | wind and alpha coverage |
| 模组实体 | unknown | only if vanilla state path used | mod-defined | backend dependent | unknown | backend dependent | runtime pipeline/entity hook enumeration | bypass/compatibility |
| 模组 shader | unknown shader key | no generic previous transform | arbitrary | SPIR-V/MSL generic backend is indexed-attachment capable; current shader/pipeline motion output unconfirmed | no confirmed | no confirmed | `MetalCrossShaderCompiler`/PSO boundary | reflection/attachment assumptions |

## 直接结论

1. **有可利用的 Minecraft previous state，但当前未接入。** Entity old/current and Particle old/current are concrete facts; missing part is the bridge into velocity or reactive resources.
2. **透明 target 与 motion 不是同一能力。** Five targets let native mark pixels; they do not tell the interpolator where those pixels moved.
3. **CUTOUT 不是 TRANSLUCENT。** 叶片拖影不能靠当前五个 transparency handles 直接覆盖；它需要 cutout classification、depth-aligned rejection/mask 或真实 vertex/object motion。
4. **当前缺口是 producer/contract，不是 backend 的单附件能力。** Java/Swift backend 和 RenderPipeline 保留 indexed attachment，但当前 inspected Minecraft/Sodium pipeline 没有 motion output contract；V2 object textures 只被清零，没有 renderer 写入。
5. **首轮未发现 velocity replay。** 当前 V2 camera/merge writer 是真实 native producer，但 object validity 永远没有 inspected producer；不得把 entity/particle old state 的存在描述成已实现 motion。

## Sodium 0.9 cutout/translucent boundary

The local Sodium 0.9 decompilation gives an exact routing fact that matters for motion coverage: `SodiumWorldRenderer.drawChunkLayer` sends the Minecraft `OPAQUE` group to the SOLID and CUTOUT terrain passes, and sends the `TRANSLUCENT` group only to the TRANSLUCENT terrain pass (`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.java:220-226`). `DefaultTerrainRenderPasses` defines CUTOUT as non-translucent but fragment-discard capable, while TRANSLUCENT is both translucent and discard capable (`DefaultTerrainRenderPasses.java:5-9`; `TerrainRenderPass.java:10-40`). The target selection maps non-translucent terrain to `GameRenderer.mainRenderTarget()` and translucent terrain to `LevelRenderer.translucentTarget()` when shader transparency is enabled (`TerrainRenderPass.java:36-40`).

This makes the existing artifact boundary concrete:

- CUTOUT leaves/grass are in the main color/depth path and are not among the five direct reactive inputs.
- Terrain translucent water/glass can reach the direct `translucent` target when the transparency chain is active.
- Entity cutout/translucent classification is renderer-specific; the five target names alone do not prove every entity feature is routed to `itemEntity` or `translucent`.
- Sodium's `ChunkSectionsToRenderMixin` cancels vanilla `renderGroup` and calls `SodiumWorldRenderer.drawChunkLayer` when its renderer is installed (`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/mixin/core/render/world/ChunkSectionsToRenderMixin.java:28-37`). Therefore the CUTOUT routing above is the actual Sodium path, not just a theoretical vanilla fallback.

**Confidence:** CUTOUT versus TRANSLUCENT routing is `confirmed` for the inspected Sodium 0.9 artifact. Full modded entity/particle routing and actual per-pixel output still require runtime pipeline/target enumeration.

## 后续验证所需的最小证据

- runtime capture 标出实体、粒子、cutout、translucent 的 bound color/depth target 和 pipeline key；
- 对同一相机的静止实体、平移实体、风动 cutout、粒子、透明水各录两帧，比较 motion/reactive 实际纹理；
- 记录 partial tick、current render state 和 GPU pass 的时间关系；
- 验证第三方 renderer 是否经过 `EntityRenderer`/`MetalCrossShaderCompiler`，不要由 vanilla path 推断模组兼容性。
