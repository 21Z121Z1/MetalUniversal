# MetalFX 当前实现取证

> **2026-07-26 live-source correction**
>
> 本文正文保留为实现前取证记录。其中“只有 camera motion、没有 object validity/disocclusion、没有 GPU capture”等结论已经过时。当前工作树已连接普通实体 object motion/validity MRT，使用保存的 world depth 进行 camera/object merge 和 disocclusion，并具有 offscreen 与 Minecraft client GPU readback。对象覆盖仍不完整，因此 gate 仍为 `false`。请以 `../metalfx-motion-pipeline-implementation.md` 和 `../metalfx-final-acceptance-2026-07-26.md` 为当前状态。

## mode 选择与能力检测

`MetalFxManager` 在初始化时加载 `MetalFxConfig`，Temporal 的可用性是 `metallum_metalfx_supports_temporal && metallum_metalfx_supports_motion_v2`（`MetalFxManager.java:99-116,249-257`）。当前 Swift source 导出 spatial/temporal/frame-generation、V2 support/clear 和 V2 encode（`MetallumNative.swift:1529-1567,1569-1615,1844-2011`）；当前 macOS/iOS bundled dylib 的 `nm -gU` 也有 `supports_motion_v2`、`clear_motion_inputs`、`encode_v2`。Temporal 不可用时选择 Spatial，再不可用才 OFF；请求 OFF 不被自动改写。

历史运行日志交叉证据：`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/run/logs/latest.log:29` 记录 `requested=TEMPORAL, effective=TEMPORAL, scale=0.67, phases=18, frameGeneration=false`。当前持久化文件 `/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/run/metallum-metalfx.properties` 后来为 OFF/50，因此不能用它代表历史 Temporal 运行。

## target 与辅助纹理创建

`sceneWidthInternal/sceneHeightInternal` 在 active mode 下调用 `MetalFxConfig.scaledDimension`（`MetalFxManager.java:263-270`）。`ensureTargets` 使用 display width/height 建立 native-resolution `uiTarget`，Frame Generation 打开时另建 `sceneOutputTarget`，并调用 `ensureAuxiliaryTextures`（`MetalFxManager.java:578-630`）。当前已确认的格式/用途为：

| 资源 | 格式 | 用途 | 证据状态 |
| --- | --- | --- | --- |
| scene color/depth | `RGBA8_UNORM` / `D32_FLOAT` | Minecraft main target；Temporal/Spatial input | confirmed by target construction and runtime encode |
| camera motion | `RG16_FLOAT` | V2 camera reconstruction output | confirmed allocation/producer; camera-only |
| object motion | `RG16_FLOAT` | intended renderer MRT input; cleared before world | confirmed allocation/clear; no current producer |
| object validity | `R8_UNORM` | selects object motion in V2 merge | confirmed allocation/clear; current validity remains zero |
| disocclusion | `R8_UNORM` | V2 camera/disocclusion input to merge/reactive | confirmed allocation/producer; visual rejection unknown |
| motion | `RG16_FLOAT` | V2 merge output -> Temporal/conditional FG | confirmed allocation/producer; current output camera-only because object validity has no producer |
| reactive | `R8_UNORM` | transparency compute output -> Temporal | confirmed allocation; mask coverage limited |
| UI/output color | `RGBA8_UNORM` | MetalFX output then GUI | confirmed target role |

这些 auxiliary textures 使用 texture binding 与 shader-write usage（`MetalFxManager.java:609-630`）。关闭路径是 `closeAuxiliaryTextures`（`MetalFxManager.java:686-692`），`closeInternal` 在 manager shutdown 时调用（`MetalFxManager.java:694-705`）。

## Spatial 路径

`beforeGuiInternal` 的 Spatial 行为是：

1. 取得 native display dimensions并调用 `ensureTargets`（`MetalFxManager.java:393-403`）。
2. 将低分辨率 `mainRenderTarget` color 作为 scaler input，目标为 native `uiTarget`（`:396-424,442-460`）。
3. native `metallum_metalfx_encode` 创建/缓存 `MTLFXSpatialScalerDescriptor` 并 encode（`MetallumNative.swift:1676-1842`）。
4. 成功后标记 `frameUsesUpscaledTarget`，GUI 继续使用 ui target（`MetalFxManager.java:468-516`）。

**判断：** 输入是真低分辨率，不是每帧先全分辨率再缩小；scaler 对象由 native cache 按输入/输出尺寸/format 复用，当前代码没有“每帧必建”证据。输出具有 shader-write usage 的 target allocation，但 MetalFX driver 对 usage 的最终接受仍由 native encode/runtime 日志证明，不能只靠 API 名称。

## Temporal 每帧输入

| 输入 | 当前来源 | 状态 | 交叉证据/限制 |
| --- | --- | --- | --- |
| color | Minecraft scaled main color/depth target | real | `MetalFxManager.java:421-448` + runtime log `latest.log:110` |
| depth | main depth texture；reversed clear 0.0 | real but depth validity needs capture | mapped clear calls + `latest.log:111` `depthReversed=true` |
| camera/object/final motion | V2 camera kernel + object validity merge; object input is cleared and has no producer | real texture, current final content camera-only | `MetallumNative.swift:1355-1475,1844-2011`; `MetalFxManager.java:642-700` |
| reactive mask | `reactiveTexture`; direct five transparency targets plus 3x3 depth heuristic | conservative/partial | Java handles `MetalFxManager.java:518-566`; native `MetallumNative.swift:1098-1126,1175-1209,1211-1265,1346-1398` |
| output | native-resolution `uiTarget` when no FG, `sceneOutputTarget` with FG | real target | `MetalFxManager.java:420-472` |
| jitter | Halton pixel/clip jitter applied to scene projection | real | `MetalFxManager.java:360-369`; `MetalFxMath.java:16-68` |
| motionVectorScale | Java/native call and runtime log `(572,321)` for input `(1144,642)` | logged contract; internal normalization not GPU-proven | `run/logs/latest.log:110-111` |
| reset | `historyReset` plus reset reasons for resize/projection/teleport/invalid matrix/explicit reset | real control path; event coverage incomplete | `MetalFxManager.java:285-390,633-643`; runtime `latest.log:79-110` |

## Temporal encode ordering

`beforeGuiInternal` first validates scene frame/targets, prepares V2 motion inputs, chooses depth/output and calls `encodeMetalFxV2` when all V2 resources exist (`MetalFxManager.java:409-479`). Native V2 runs camera reconstruction, object/camera merge, then the Temporal scaler (`MetallumNative.swift:1844-2011`). On successful encode it may copy/seed the pre-GUI output for the conditional Frame Generation path, clears UI depth, stores previous VP, advances phase and commits the motion-state transaction (`MetalFxManager.java:499-549`). On encode failure it falls back to fullscreen copy for the frame (`MetalFxManager.java:509-528`).

## Reactive mask actual coverage

`LevelRendererMetalFxMixin` injects at `addAlwaysOnTopPass` HEAD and passes `targets.translucent`, `itemEntity`, `particles`, `weather`, `clouds` into `MetalFxManager.addTransparencyReactivePassInternal` (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/LevelRendererMetalFxMixin.java:17-25`；`MetalFxManager.java:518-566`). Native direct-mask logic marks a pixel as exactly `0.0` or `1.0` when the maximum of alpha/red/green/blue is above `0.001`; the later motion kernel preserves that value and maxes it with depth-edge reactivity (`MetallumNative.swift:1098-1126,1175-1209,1211-1265`).

## Exact reactive coverage boundary

**Confirmed direct coverage:** the Java frame-graph pass only registers five optional color handles and reads them into the native compute pass (`MetalFxManager.java:518-566`; `MetalCommandEncoder.java:339-368`; `MetallumNative.swift:1346-1398`). The native kernel reads the same pixel coordinate from each non-null texture and writes a binary `R8_UNORM`-compatible value to `reactiveTexture` (`MetallumNative.swift:1098-1126`). The current log confirms all five handles were non-null in one successful frame (`run/logs/latest.log:108`), but that log does not prove their pixel contents were nonzero.

**Confirmed absence of direct CUTOUT coverage:** Minecraft groups `SOLID` and `CUTOUT` together as `ChunkSectionLayerGroup.OPAQUE` and maps that group to `mainRenderTarget` (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/chunk/ChunkSectionLayerGroup.java:10-37`). Sodium's `SodiumWorldRenderer.drawChunkLayer` renders `DefaultTerrainRenderPasses.SOLID` and `.CUTOUT` in that opaque group, while only `.TRANSLUCENT` is selected for the translucent group (`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.java:220-226`; `DefaultTerrainRenderPasses.java:5-9`). The Sodium terrain pass also sets `fragmentDiscard=true` for CUTOUT and `isTranslucent=false` (`TerrainRenderPass.java:10-40`). Thus leaves/grass are not read by the direct five-target mask.

**Confirmed indirect CUTOUT handling:** after the direct mask pass, `metallum_motion_reconstruction` samples the main scene depth and examines an 8-neighbor 3x3 window. A valid/invalid depth boundary becomes reactive `1.0`; a valid-valid depth gradient becomes `clamp(gradient * 4.0, 0, 1)` (`MetallumNative.swift:1175-1209,1255-1265`). This is a depth discontinuity heuristic, not a leaf/material/alpha classification. It can mark a cutout edge, but source cannot establish its true pixel recall or false-positive rate.

**Ordering:** the reactive frame-graph pass is added at the HEAD of `LevelRenderer.addAlwaysOnTopPass`, after the vanilla frame graph has already added main, clouds, weather, transparency-chain, and other passes (`LevelRenderer.java:184-244`; `LevelRendererMetalFxMixin.java:20-27`). It reads the current target handles and disables pass culling (`MetalFxManager.java:531-538`). This proves the pass is intentionally placed after those handle-producing passes and before the always-on-top pass is added; a GPU capture is still needed to prove the final scheduled execution order and whether any later pass changes the relevant target before Temporal encode.

**Confidence:** direct handle set, binary threshold, CUTOUT target classification, and depth heuristic are `confirmed` by source. Actual mask pixel occupancy, alignment with the main depth/color target, and usefulness for wind/alpha coverage remain `unknown` without a texture capture/readback.

因此：

- direct coverage = translucent, item entities, particles, weather, clouds;
- indirect coverage = depth boundaries in main target;
- alpha-cutout leaves/grass are not direct handles;
- object transform/vertex wind motion is not represented;
- mask is binary/near-binary rather than a measured continuous material strength.

## 当前实现成熟度分类

### 正确或强证据支持

- active modes have real scaled scene target and native-resolution output;
- Temporal has non-null depth, motion and reactive resources on the successful path;
- Halton jitter and current/previous camera reconstruction are connected;
- GUI draw is after `beforeGui` and redirected to native UI target;
- history reset has explicit frame-local reasons;
- native scaler/interpolator is cached rather than an always-new Java wrapper.

### 近似/临时处理

- motion is camera/screen-space reconstruction only;
- reactive is five-target plus depth-edge heuristic;
- alpha-cutout and dynamic entity coverage is indirect;
- Frame Generation pacing samples `NSScreen.maximumFramesPerSecond` when the native presenter is created; no dynamic display-timing/VRR callback is present;
- fullscreen copy fallback is used after encode failure.

### 仅由单测证明

`MetalFxMathTest` covers Halton/jitter, field-of-view extraction, static/translation/rotation motion, invalid matrix, scale/phase and mode fallback (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/test/java/com/metallum/client/metal/render/MetalFxMathTest.java:11-158`). It does not render a Minecraft frame, sample an actual depth texture, or inspect a MetalFX history result.

### 仅由日志证明

`run/logs/latest.log:29,106,110-111` proves one historical successful configuration/encode observation: Temporal, 0.67, five transparency targets present, input/output sizes, jitter, motion scale, reversed depth and convention. It does not prove all frames, all windows, object motion, current loaded dylib, or visual quality.

## 直接回答关键问题

1. 场景颜色包含正常 world FrameGraph output；first-person hand/screen effects/3D crosshair are pre-MetalFX scene-side. GUI is post-MetalFX.
2. Depth is available at encode time on successful path; lifetime across every post/GUI branch remains runtime concern.
3. The current final motion is camera/screen-space reconstruction because object validity is cleared and no renderer writes object motion; V2 merge topology exists but object motion is not connected.
4. Jitter is applied to scene projection; current/previous projection used for motion is unjittered, but actual depth/jitter alignment needs capture.
5. Reactive sources are five transparency targets plus depth neighborhood heuristic; no direct cutout/entity velocity mask.
6. Menu/chat/HUD normal GUI are after Temporal; hand is before Temporal because it is part of `renderLevel`.
7. Temporal output can receive an extra copy/seed and then GUI before present; no additional confirmed post-GUI temporal pass exists. Frame Generation after this point is gated off by `OBJECT_MOTION_PRODUCER_CONNECTED=false` in the current source.

## 性能与颜色限制

The current source proves extra color/auxiliary allocations, motion compute, reactive compute, MetalFX encode and optional fullscreen copies. It does not prove a performance win, exact color-space/alpha conversion, or whether `CAMetalLayer` drawable can be directly used by every MetalFX mode. Those require runtime counters/GPU capture and are intentionally left unknown.
