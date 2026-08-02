# 分辨率、坐标与 viewport 传播

> **2026-07-26 status:** 本文坐标取证仍可作历史参考，但当前 motion/validity/merge 与 validation 尺寸合同以 `../metalfx-motion-pipeline-implementation.md` 和 current-run JSON 为准。

## 当前尺寸层级

```text
Window / CAMetalLayer drawable pixel size
  -> GameRenderer windowRenderState.width,height
     -> Minecraft mainRenderTarget width,height
        -> MetalFxManager.displayWidth,height
           -> MetalFxConfig.scaledDimension
              -> renderWidth,height
                 -> main scene color/depth, motion, reactive
           -> uiTarget native display width,height
              -> GUI and final compose
```

**代码交叉证据：** `GameRenderer.render` 比较 `windowRenderState` 和 `mainRenderTarget`（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:419-430`）；`MetalFxManager` 在 projection preparation/target ensure 中维护 `displayWidth/displayHeight` 与 `renderWidth/renderHeight`（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxManager.java:46-49,285-299,578-630`）；历史 runtime log 是 `input=1144x642, output=1708x960, scale=0.67`。

## 传播表

| 尺寸 | 来源 | 传播调用点 | 资源/消费者 | 证据状态 |
| --- | --- | --- | --- | --- |
| logical GUI size | `Window.getGuiScaledWidth/Height` | `Minecraft` screen resize，例如 `/tmp/minecraftmetal-mc26-sources/net/minecraft/client/Minecraft.java:1421` | screen layout/GUI logical coordinates | confirmed source; exact backing scale per run unknown |
| drawable pixel size | `Window`/`CAMetalLayer` surface path | `GameRenderer.windowRenderState` -> `GameRenderer.resize`; native `metallum_configure_layer` | main target and drawable | source path confirmed; direct layer `drawableSize` capture missing |
| display width/height | `MetalFxManager.beforeGuiInternal`/`ensureTargets` arguments | `prepareSceneProjectionInternal(...displayWidth,displayHeight)` and `ensureTargets(width,height)` (`MetalFxManager.java:285-299,393-403,578-606`) | UI target, output size, display aspect | confirmed code; exact source value must be logged on resize |
| render width/height | `sceneWidthInternal/sceneHeightInternal` | `MetalFxManager.java:263-270,285-289,578-585` | main scene target, motion/reactive, Temporal input | confirmed |
| UI target size | `ensureTargets` uses `width,height` rather than scaled dimensions | `MetalFxManager.java:578-606` | GUI render and post-GUI compose | confirmed native-resolution intent |
| MetalFX inputContentWidth/Height | Java `renderWidth/renderHeight` passed to `encodeMetalFx` | `MetalFxManager.java:421-464` | scaler descriptor/Temporal input | confirmed by historical log and call arguments |
| MetalFX output size | native display width/height; target selected `uiTarget` or `sceneOutputTarget` | `MetalFxManager.java:393-429` | MetalFX output | confirmed |
| motion texture size | `ensureAuxiliaryTextures` checks width/height against `renderWidth/renderHeight` | `MetalFxManager.java:609-630` | motion reconstruction + Temporal/FG | confirmed |
| reactive mask size | same | `MetalFxManager.java:609-630` | transparency compute + Temporal | confirmed |
| FG interpolator size | native `makeTextureSet` with output scene dimensions and depth/motion input dimensions; interpolator descriptor uses depth as input and sceneColor as output | `MetallumNative.swift:201-218,243-323,375-417` | slot textures | source relationship confirmed; actual runtime dimensions still need log/capture |

## Scale and rounding

`MetalFxConfig.scaledDimension` is the single Java scaling helper (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxConfig.java:171-190`). Existing tests prove current expected values: 1920 at 1.0, 1286 at 0.67, 960 at 0.5, and phase counts 8/18/32 (`src/test/java/com/metallum/client/metal/render/MetalFxMathTest.java:103-110`). The exact odd-size rounding and alignment behavior is not reproduced here; it must be tested with odd display dimensions and 0.67/0.5. No source evidence in this first pass proves an 8-pixel alignment constraint.

## Aspect ratio and projection

`MetalFxManager.prepareSceneProjectionInternal` saves the final Mojang projection and calls `MetalFxMath.adjustPerspectiveAspect` with display aspect and render aspect before jitter (`MetalFxManager.java:303-363`; `MetalFxMath.java:71-90`). This is intended to preserve the display camera while rendering a lower-resolution target. The projection input is therefore not simply “display projection”; its exact matrix also includes Mojang camera effects and the current render-state path.

**Potential mismatch:** Minecraft `GameRenderer.mainRenderTarget.width/height`, GUI scissor, surface drawable size and MetalFX output can be different logical layers. Historical crash `crash-2026-07-26_02.17.39-client.txt` recorded GUI scissor `1708x524` against `1144x642`, which is direct runtime evidence that at least one path mixed native and scene dimensions. This is not resolved by the existence of `sceneWidthInternal` alone.

## Viewport/scissor

`MetalRenderPass` defaults scissor to the first non-null color texture dimensions and also accepts render area values (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:33-80,532-548`). Native v2 render encoders bind the indexed color array and set the viewport (`MetallumNative.swift:2484-2580`). The current code does not provide a first-round proof that every caller's render area is in the same coordinate space as the bound texture; the historical GUI crash is the counter-evidence. This is a size/scissor issue, not evidence that the backend collapses MRT to attachment 0.

## Resize/fullscreen/Retina order

1. `GameRenderer.render` observes window render-state mismatch and calls `GameRenderer.resize` (`GameRenderer.java:423-430`).
2. `GameRenderer.resize` resizes main target and calls `LevelRenderer.resize` (`GameRenderer.java:317-320`).
3. Mixin redirects target construction/resize and reports width/height to `MetalFxManager` (`GameRendererMetalFxMixin.java:16-77`).
4. `MetalFxManager.ensureTargets` updates scene/UI dimensions, recreates targets/aux textures, and resets history on dimension change (`MetalFxManager.java:578-630`).

The exact order of `CAMetalLayer.drawableSize`, Java window state, GUI logical scale and in-flight native Frame Generation drain is not proven in this first round. A Retina change while a native FG slot is outstanding is therefore an explicit unknown, not a completed lifecycle guarantee.

## Coordinate conventions

- JOML/NDC matrix path uses the Java `Matrix4f.get(float[])` -> native scratch -> Swift `makeMatrix` path for Temporal motion; no separate row-major transpose is present in the inspected bridge. Frame Generation input itself carries textures and scalar camera parameters rather than VP matrices (`MetalCommandEncoder.java:293-335`; `MetalNativeBridge.java:803-835`; `MetallumNative.swift:1270-1277,1473-1493`; `MetalFxManager.java:707-740`).
- Runtime motion reconstruction converts pixel center to top-left screen/NDC with `currentNDC.y = 1 - 2 * pixelY/height` in native `metallum_motion_reconstruction` (`MetallumNative.swift:1211-1264`); `MetalFxMath.reconstructMotion` is the Java mathematical mirror used by tests (`MetalFxMath.java:120-157`).
- Native MSL/present has an explicit vertical-orientation helper/comment for `CAMetalLayer` (`MetallumNative.swift:947-1000`); the exact copy shader transform should be captured before any implementation change.

## Narrowed evidence: pixel size, logical GUI size, and scissor contract

**Confirmed:** the Minecraft value passed into `GameRenderer`'s `WindowRenderState.width/height` is framebuffer pixel size, not window-point size. `Window.getWidth()` and `getHeight()` return `framebufferWidth/framebufferHeight` (`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/platform/Window.java:462-468`); `GameRenderer.extractWindow()` copies those values into `WindowRenderState` (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:612-620`). GUI logical size is a separate `guiScaledWidth/guiScaledHeight`, derived from framebuffer size and the GUI scale (`Window.java:433-440,486-503`; `Minecraft.java:1410-1422`). Retina backing scale is therefore already folded into the width/height values before MetalUniversal receives them; no separate Java-side backing-scale field enters the MetalFX manager in the inspected path.

**Confirmed:** in an active scaled mode, the same framebuffer pixel dimensions feed three different contracts:

1. `GameRendererMetalFxMixin` redirects `MainTarget` construction and `RenderTarget.resize` to `sceneWidth/sceneHeight`, so the main scene target is lower resolution (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java:17-31`).
2. `GameRenderer.render`'s width/height field reads are redirected to `MetalFxManager.reportedWidth/reportedHeight`, which return the stored display dimensions rather than the low-resolution target dimensions (`GameRendererMetalFxMixin.java:34-48`; `MetalFxManager.java:125-135`; mapped `GameRenderer.java:419-444`). This is a deliberate compatibility shim, but the redirect is method-wide and has no ordinal/local distinction.
3. `prepareSceneProjectionInternal` receives those display dimensions, derives render dimensions, adjusts the projection aspect from display to render aspect, then applies jitter (`MetalFxManager.java:285-364`). The hand projection separately uses `WindowRenderState.width/height`, i.e. display pixel dimensions (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:588-594`).

**Confirmed:** the generic render-pass default is texture-sized, while explicit scissor values are not rescaled by the Metal backend. Mojang's `CommandEncoder.createRenderPass` creates a full-texture `RenderArea` from the bound color texture (`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/systems/CommandEncoder.java:59-85`; `RenderPass.java:322-327`). `MetalRenderPass.pushEffectiveScissor` intersects the caller's `ScissorState` with that area using raw integer coordinates (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalRenderPass.java:527-549`). There is no scale conversion in that function. Therefore a display-sized scissor submitted while the active color target is the scaled main target can exceed or collapse against the target; the historical GUI validation failure is consistent with this, but the source alone does not prove that every caller supplies display-sized coordinates.

**Confirmed:** native surface configuration uses the width/height supplied by Java surface configuration as `CAMetalLayer.drawableSize`, sets the layer pixel format to `.bgra8Unorm`, and does not query or derive the size from `contentsScale` (`MetalSurface.java:31-49`; `MetallumNative.swift:2859-2871`). On iOS the existing host layer's `contentsScale` is intentionally left under launcher control, while `drawableSize` remains the renderable size (`MetallumNative.swift:1978-2005`). This separates layer drawable size from Minecraft GUI logical size. Exact runtime equality between drawable pixels and the Java framebuffer values still requires a live log or capture.

**Confidence boundary:** the size propagation and absence of backend scissor rescaling are `confirmed` by source. The claim that a particular GUI or world pass is wrong is `strong_inference`, supported by the recorded `1708x524` versus `1144x642` validation failure (`crash-2026-07-26_02.17.39-client.txt` and `run/logs/latest.log`), but needs a capture naming the pass and its bound texture. The exact odd-size behavior is source-confirmed for `Math.round` followed by clearing the low bit (`MetalFxConfig.java:182-194`), but its effect on a particular projection/viewport pair remains unverified.
