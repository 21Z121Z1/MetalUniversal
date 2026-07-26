# 一帧 CPU 调用时间线

> **2026-07-26 status:** 本文保留实现前时间线。当前唯一 whole-frame begin owner、GPU-success history commit、depth-before-hand copy 和 display-link presenter timeline 见最终验收报告；正文旧行号不可作为当前验收。

## 符号级主链

```text
Minecraft.runTick(boolean)
  -> Minecraft.renderFrame(...)
     -> surface/window configure and acquire path
     -> GameRenderer.update(DeltaTracker)
     -> GameRenderer.extract(DeltaTracker, advanceGameTime)
     -> GameRenderer.render(DeltaTracker, advanceGameTime)
        -> GameRenderer.renderLevel(...)
           -> LevelRenderer.render(...)
              -> FrameGraphBuilder passes
           -> first-person hand / screen effects / feature rendering / 3D crosshair
        -> post effect / depth clear
        -> GameRendererMetalFxMixin.beforeGui
           -> MetalFxManager.beforeGuiInternal
           -> MetalFX spatial or temporal encode
        -> GuiRenderer.render()
           -> GuiRenderer.draw()
              -> GuiRendererMetalFxMixin redirects GameRenderer.mainRenderTarget()
                 to MetalFxManager.guiTarget()
     -> MinecraftMetalFxMixin redirects final presentation target
     -> GpuSurface.blitFromTexture(...)
     -> MetalSurface.blitFromTexture(...)
     -> MetalCommandEncoder.presentTextureToDrawable(...)
        -> native present or Frame Generation enqueue
     -> MetalCommandEncoder.submit()
     -> MetalSurface.present()
```

**证据：** Minecraft 26.2 mapped `Minecraft.runTick`/`renderFrame`（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/Minecraft.java:1148,1226`）；`GameRenderer.update/extract/render`（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:395,402,419`）；`LevelRenderer.render`（`LevelRenderer.java:163`）；`GuiRenderer.render/draw`（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/gui/render/GuiRenderer.java:120,180`）；MetalUniversal redirect/present（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/MinecraftMetalFxMixin.java:29-45`、`src/main/java/com/metallum/client/metal/render/MetalSurface.java:62-68`、`MetalCommandEncoder.java:251-287`）。

## 阶段表

| 阶段 | 实际类/方法 | 调用方 -> 被调用方 | 线程判断 | target / depth / 尺寸 | jitter/history | Sodium 替代 |
| --- | --- | --- | --- | --- | --- | --- |
| tick/render 边界 | `Minecraft.runTick` -> `Minecraft.renderFrame` | Minecraft 主循环 | render thread；当前映射未在本报告中证明线程名 | window render state 进入 `GameRenderer` | 未进入 MetalFX 前 frame state | 否 |
| camera/update | `GameRenderer.update`、`extract` | `Minecraft.renderFrame` -> renderer | render thread | camera/render state；具体 partial tick 数据由 `DeltaTracker` 和 `CameraRenderState` 传入 | `GameRendererMetalFxMixin.render` HEAD 调用 `MetalFxManager.beginFrame`（`GameRendererMetalFxMixin.java:50-57`） | 否 |
| projection | `GameRenderer.render` -> `GameRenderer.renderLevel`；`GameRendererMetalFxMixin` `@ModifyArg` | renderer -> MetalFxManager.prepareSceneProjection | render thread | 最终 projection 以 main target/window dimensions 调整 aspect；scene render size 在 manager 计算 | Temporal 时对 JOML projection 写 `m20/m21`（`MetalFxManager.java:360-369`） | 否 |
| main target/resize | `GameRenderer.render` 比较 `windowRenderState.width/height` 与 `mainRenderTarget.width/height` | `GameRenderer.render` -> `GameRenderer.resize` | render thread | `GameRenderer.mainRenderTarget`；resize 同时 `LevelRenderer.resize`（`GameRenderer.java:317-320,423-430`） | resize 会使 manager reset history（`MetalFxManager.java:578-606`） | Sodium terrain target 仍由 Minecraft target bundle 决定 |
| world graph | `LevelRenderer.render` | `GameRenderer.renderLevel` -> FrameGraph | render thread | main color/depth；可选 transparency targets；尺寸等于当前 main target | 当前 projection 已可能 jitter | 区块 group 由 Sodium mixin 绕过 vanilla renderer |
| sky | `LevelRenderer.addSkyPass` | FrameGraph -> sky render pass | render thread | main target；depth clear/load 由 pass descriptor | 使用场景 projection | Sodium 不替代天空 |
| opaque/cutout chunks | `ChunkSectionsToRenderMixin.renderGroup` -> `SodiumWorldRenderer.drawChunkLayer`；默认 pass SOLID/CUTOUT | LevelRenderer/Sodium -> `DefaultChunkRenderer.render` -> generic RenderPass | render thread; chunk build workers not part of draw call | main target；SOLID no discard；CUTOUT alpha cutoff | 无对象 motion attachment | 是，Sodium 替代区块 draw |
| entities / block entities / item entities | `LevelRenderer` feature dispatcher and always-on-top/feature passes | LevelRenderer -> feature dispatcher | render thread | main or item-entity transparency target depending graph | 没有对象 previous transform 入 MetalFx motion | 不由 Sodium terrain path 覆盖 |
| particles | `LevelRenderer` transparency pass | LevelRenderer -> particle render | render thread | `particles` target when shader transparency enabled | reactive direct input; motion remains camera-only | 不由 Sodium terrain path覆盖 |
| weather/clouds | `LevelRenderer.addWeatherPass` / cloud pass | LevelRenderer -> FrameGraph | render thread | `weather` / `clouds` auxiliary targets if enabled | reactive direct input; no object motion | 否 |
| translucent | transparency chain | LevelRenderer -> `translucent` target | render thread | `translucent` target, then transparency composite | reactive direct input | Sodium TRANSLUCENT chunk pass can write this selected target |
| post process | `GameRenderer.render` post-chain branch | GameRenderer -> post chain | render thread | current main target and resource pool; no separate MetalFX post texture proven | occurs before `beforeGui` | Sodium 不替代 |
| MetalFX | `GameRendererMetalFxMixin.beforeGui` -> `MetalFxManager.beforeGuiInternal` -> `MetalCommandEncoder.encodeMetalFxV2` -> native `metallum_metalfx_encode_v2` for Temporal; legacy `encodeMetalFx` for Spatial/fallback | render thread into native encoder | Java call on render thread; native Metal command encoding synchronous to call | input scene render size; output native `uiTarget` or conditional `sceneOutputTarget`; V2 camera/object/disocclusion/motion/reactive as applicable | Temporal history and phase advance in manager; object attachment currently cleared | 不由 Sodium 替代 |
| GUI begin/draw | `GuiRenderer.render` -> `GuiRenderer.draw` | GameRenderer -> GUI renderer | render thread | native `uiTarget`; GUI depth cleared before draw (`GuiRenderer.java:193`) | GUI is after Temporal; no jitter injection to GUI proven | 否 |
| final blit/present | `MinecraftMetalFxMixin` -> `GpuSurface.blitFromTexture` -> `MetalSurface` -> encoder | Minecraft -> Metal backend -> native | render thread; Frame Generation workers are conditional on `OBJECT_MOTION_PRODUCER_CONNECTED` | drawable texture; final copy pipeline color attachment 0 | current source gate keeps ordinary present; conditional FG may enqueue interpolated and real outputs | 否 |
| submit | `MetalCommandEncoder.submitRenderPass/submit` | surface/present -> native command buffer commit and semaphore | render thread; native worker commits its own buffers for FG | command buffer resources retained until completion semaphore | resource destruction queued by submit completion | 否 |

## 关键顺序结论

1. 第一人称手、screen effects、feature rendering、3D crosshair 在 `renderLevel` 中，先于 `beforeGui`，所以它们不是 GUI 排除项。
2. 普通 HUD、chat、menu 通过 `GuiRenderer` 在 MetalFX 之后绘制；这是代码上的 GUI 分离。
3. `LevelRenderer` 的 FrameGraph 是真实场景 pass graph；MetalFX reactive pass 在 `addAlwaysOnTopPass` HEAD 插入，但它读取之前建立的 transparency handles，而不是新增场景颜色 pass。
4. 主链中的 CPU 线程切换只在 Frame Generation 条件分支发生；当前 `MetalFxManager.java:29-33,99-116` 将其 gate 关闭，所以 current source path 的 present 是普通 render-thread present。普通 OFF/SPATIAL/TEMPORAL encode 没有 native worker 的证据。

## 尚未由运行验证的部分

- `Minecraft.runTick` 到 `renderFrame` 的每个 lambda/Profiler 区段实际 GPU submit 边界。
- 具体 entity/block entity/particle pass 是否在当前运行配置启用，以及每个 pass 的 render target 别名。
- GUI scissor/viewport 每个 draw range 的运行时值；历史 crash 证明存在过尺寸混用，但未证明现行工作树已消失。
- draw buffer 的实际 command buffer commit 次数；只有日志/代码调用关系，没有 GPU capture。
