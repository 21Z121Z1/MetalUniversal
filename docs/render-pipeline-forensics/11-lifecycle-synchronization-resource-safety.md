# 生命周期、同步与资源安全

> **2026-07-26 live-source correction**
>
> 本文正文是生命周期重写前的风险表。当前 presenter 已采用 reducer-backed 状态机，区分 queued、active、GPU-submitted、real-present-pending、presented、cancelled、failed、released；重复回调/释放幂等，未提交工作可取消，已提交工作 drain，shutdown 不再等待停止后不可能到来的 presented callback。9 项纯状态测试及真实 `CAMetalDisplayLink` 自动 timeline test 已通过。Minecraft 整帧 begin owner 也已唯一化。当前合同见 `../metalfx-frame-generation.md`。

## 总体所有权

当前有三层生命周期：

1. Minecraft/Java target 与 FrameGraph allocator；
2. Java Metal backend 的 command buffer、semaphore、deferred destruction queue；
3. Swift native MetalFX scaler/interpolator cache、Frame Generation slots、worker threads 和 `CAMetalLayer`。

没有一个统一的 lifecycle token 把三层绑定在一起。Java 通过 `MetalNativeBridge` 传 opaque handles；Frame Generation 又在 native 内复制到 private slots。因此 resize/close 时必须同时满足 Java target 不再被 render thread 使用、旧 command buffer 已完成、native slot 不再读旧纹理。

## 事件状态表

| 事件 | 当前代码动作 | history/previous state | GPU wait/release | 线程 | 风险/未证实 |
| --- | --- | --- | --- | --- | --- |
| 启动 / native load | `Metallum.onPreLaunch` 加载 native bridge；`MetalBackend`/`MetalDevice` 后续建 device | manager 尚未有上一帧，`historyReset=true` | native global state 创建 | Fabric/Minecraft startup then render thread | Java 25/Loom 与 native ABI 必须同时匹配 |
| 创建 device | `MetalDevice` 建 device/queue、command encoder、shader/pipeline caches；`MetalFxManager.initialize` 建 active manager | previous valid flags false | device close 时清 cache/queue | render/device setup | close 顺序跨 Minecraft/RenderSystem 未完全 capture |
| 创建 surface | `MetalSurface.configure` 检查 positive width/height，调用 `metallum_configure_layer`（`MetalSurface.java:31-45`） | 不自动 reset history 的证据 | layer 外部拥有 | render thread/surface callback | `isSuboptimal()` 固定 false，Retina/layer resize通知可能被遗漏 |
| 进入世界 | `GameRendererMetalFxMixin.setLevel` TAIL 调 `resetHistory("world change")`（`GameRendererMetalFxMixin.java:94-97`） | `historyReset=true`、previous VP/camera validity false（`MetalFxManager.java:633-643`） | 未见显式 GPU wait | render thread | old world FrameGraph/native slots 的交错需要运行验证 |
| 第一帧 | `GameRenderer.render` HEAD `MetalFxManager.beginFrame`（`GameRendererMetalFxMixin.java:50-57`） | flags/`frameDepthTexture` 清空；initial reset remains | no wait | render thread | no actual capture of first scaler history |
| 每帧开始 | `beginFrameInternal` 清 `reactiveMaskPrepared`, `motionInputsPrepared`, `frameDepthTexture`, `frameUsesUpscaledTarget` 并调用 `motionStateStore.beginFrame()`（`MetalFxManager.java:289-301`） | previous matrix retained until successful update; object motion pending map starts empty | no wait | render thread | `prepareSceneProjectionInternal` 又调用一次 `beginFrameInternal`（`:304-310`）；当前没有 `observe` producer，所以尚未造成已证实的数据丢失，但未来若在 projection 前后采集对象状态，第二次 begin 会清空 pending map |
| projection/camera change | `prepareSceneProjectionInternal` detects FOV/far changes, teleport distance and invalid matrices; calls reset | previous validity false; phase=0 | no wait | render thread | camera mode changes without projection/teleport may not reset explicitly |
| 打开菜单/overlay | `frameGenerationInputInternal` detects `minecraft.gui.screen/overlay` and calls `suspendFrameGenerationForGuiInternal` (`MetalFxManager.java:707-715,748-762`) | FG paused; no immediate Temporal history reset in this branch | native `stop_frame_generation`; `sceneOutputTarget` is deliberately kept alive for the current submitted frame; presenter shutdown waits for worker/outstanding-frame state (`MetallumNative.swift:1733-1742,746-762`) | render thread calls native | visual result and drawable behavior still need runtime verification |
| 关闭菜单 | `beginFrameInternal` clears the GUI-suspension flag, re-enables `frameGenerationEnabled`, and calls `resetHistoryInternal("GUI closed; frame generation resumed")` (`MetalFxManager.java:273-283`) | Temporal reset is explicit; previous matrix validity is cleared by reset | next native encode lazily creates a new presenter when the global presenter is nil (`MetallumNative.swift:1581-1625`) | render thread | Java/native control flow is confirmed; end-to-end timing/output still needs runtime verification |
| resize | Minecraft `GameRenderer.resize` resizes main target and LevelRenderer (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:317-320`); manager `ensureTargets` rebuilds display/scene/aux targets and resets history (`MetalFxManager.java:578-630`) | history reset, phase=0 | Java textures close/recreate; native scaler/interpolator resource resize/drain path exists but exact call order not proven | render thread | historical scissor crash shows at least one bad size transition |
| fullscreen / Retina scale | surface configure receives width/height; manager reacts only when passed dimensions change | dimension reset if observed | no `isSuboptimal` handling; native layer config only | render/surface thread | drawableSize/backing-scale callback and in-flight FG drain unknown |
| render scale change | config `MetalFxConfig` scale maps to target dimensions; effective mode/scale fields are final per manager | target dimension change resets when manager sees it | auxiliary close/recreate | render thread | live setting mutation/restart requirement not fully audited |
| OFF/SPATIAL/TEMPORAL change | selection happens in manager construction; unsupported fallback in `chooseMode/selectMode` (`MetalFxManager.java:231-257`) | new manager/session expected | old targets/native state close only on manager close/disable | startup/render thread | no dynamic mode switch contract |
| Frame Generation toggle | current construction gate includes `OBJECT_MOTION_PRODUCER_CONNECTED=false`, so current Java always starts with FG disabled (`MetalFxManager.java:29-33,99-116`); GUI pause/resume and native presenter are conditional | `frameResetForPresent`/history state carried per frame if gate opens | current path has no worker; conditional GUI pause calls native stop but keeps scene target; permanent disable destroys scene target (`MetalFxManager.java:743-756,830-842`) | render thread + conditional native worker | current no-FG fact confirmed; dormant timing/resource behavior remains unverified |
| FOV change | current frame extracts FOV from camera projection, compares threshold 5 degrees, resets | previous camera projection validity false | no GPU wait | render thread | exact FOV source changes from mods unknown |
| camera mode change | no dedicated hook found; may be caught by projection difference/FOV/teleport | uncertain | no explicit wait | render thread | third-person/first-person transition is a required visual test |
| teleport | camera position delta beyond `SCENE_CUT_DISTANCE` resets | previous position valid cleared | no GPU wait | render thread | threshold semantics only code/math tested |
| resource reload | inspected `Minecraft.reloadResourcePacks` starts async resource reload and finishes `levelExtractor`/reload tracker; no direct `MetalFxManager` call is present in that path (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/Minecraft.java:1009-1050`) | Mixin reset hook exists for `GameRenderer.resetData`, but current reload path does not itself prove it is called | `MetalDevice.clearPipelineCache` waits and clears caches, but production call sites found are device close only (`MetalDevice.java:155-182`) | reload executor + render continuation | old PSO/function vs in-flight command safety and Temporal history continuity unknown |
| shader reload | `MetalDevice.clearPipelineCache` clears compiled pipelines/shader modules/functions after `waitForSubmittedGpuWork` (`MetalDevice.java:163-176`) | no direct resource-reload-to-clear-cache hook found; no explicit Temporal history reset in compiler cache clear | native pipeline handles release is ordered for this method; reload-to-device lifecycle remains unknown | device/render thread | resource reload may not call device cache clear |
| world unload | `Minecraft.setLevel`/`GameRenderer.setLevel` reset hook; final close is separate | reset expected on setLevel | old LevelRenderer resources close later | render thread | native slots may still hold previous scene until drained |
| window hidden/background | Minecraft `pauseIfInactive` can pause game when focus lost (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/Minecraft.java:1359-1363`) | no explicit MetalFX reset/stop hook | no explicit surface/worker pause | render thread/native workers | drawable acquisition behavior unknown |
| command-buffer error | `beforeGuiInternal` can fallback/disable session after encode failure; native failed command buffer signals events to unblock worker (`MetallumNative.swift:577-599`) | reset/disable state depends branch | fallback copy or native shutdown | render thread + native | failure injection and recovery not run in this task |
| close game | `GameRenderer.close` TAIL calls `MetalFxManager.close` (`GameRendererMetalFxMixin.java:104-107`); Minecraft later closes shader/level/resource/window surface (`Minecraft.java:1112-1143`) | manager resources cleared; native shutdown | native worker drain/join path; Java encoder close/semaphore release | render thread then window teardown | exact order vs RenderSystem device close and in-flight command completion needs capture |

## Java command submission and deferred release

`MetalCommandEncoder.submit` ends pass/encoder, commits command buffer with a per-slot completion semaphore, rotates in-flight slots, waits up to five seconds for the submit falling out of the in-flight window, then closes the old buffer and rotates transient/destruction queues (`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:84-131`). `queueForDestroy` adds native release actions to the destruction queue (`:672-674`); `awaitSubmitCompletion` waits on the matching semaphore (`:676-685`).

This is a real synchronization boundary for normal render submissions. It does not by itself prove that a texture passed to native Frame Generation is safe to close, because native copies the input into private slots on its own command buffer and has separate shared events.

`MetalCommandEncoder.close` itself closes in-flight Java command buffer wrappers and releases semaphores, then closes transient/destroy queues (`src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java:768-797`); it does not call `waitForSubmittedGpuWork()` internally. The normal `MetalDevice.close` caller does call `waitForSubmittedGpuWork()` first, and that method waits for the latest submit (`MetalDevice.java:178-190`; `MetalCommandEncoder.java:799-809`). Therefore the ordinary device-close sequence has a Java GPU wait, while a direct encoder close would not. This does not prove every target destruction path is ordered after that wait.

## Native resource/sync facts

- Native texture descriptors use `.private` for MetalFX texture sets (`MetallumNative.swift:245-323`) and one general texture creation path sets `hazardTrackingMode = .untracked` (`:2346-2373`). Untracked resources require the command/event ordering to be correct; no implicit hazard tracking can be assumed.
- Frame Generation has one `readyEvent`, condition variables, `maxOutstandingFrames=1`, one-second ready wait/drop, and explicit event signaling for failed buffers (`MetallumNative.swift:78-125,419-577,623-762`). There is no separate pacing shared event or pacing worker in the current source.
- Native resize/shutdown drains outstanding frames, rebuilds slot textures, stops/joins the worker (`MetallumNative.swift:375-417,738-762,2147-2177`). Native resize is entered from `MetalFrameGenerationPresenter.encode` when input dimensions/formats or layer pixel format differ (`:443-457`); the exact Java call that causes this native resize for every surface/fullscreen/Retina path is not established. Since the current Java FG gate is false, this is conditional rather than observed current-frame behavior.
- Native `metallum_release_object` uses retained-pointer release (`MetallumNative.swift:3064-3070`); Java wrappers therefore cannot assume ARC release occurs at Java GC time.

## Thread model

| State | Owner thread | Shared with | Synchronization evidence |
| --- | --- | --- | --- |
| Minecraft camera/projection/history | render thread | native call arguments only | Java call is synchronous; no Java lock around every manager field shown |
| Java command encoder/current pass | render thread | GPU | Metal command buffer/semaphore/fence and in-flight array |
| native scaler/pipeline cache | native invocation/render command path | native workers for FG only where applicable | Swift state/condition/event; full lock coverage not reconstructed |
| FG slots and request queue | render thread enqueue + `MetalFX PresentThread` | render thread enqueue | condition variable + `readyEvent`, `maxOutstandingFrames=1` |
| CAMetalLayer drawable | native present worker or render thread | Window system | Java configure sets `drawableSize`, `displaySyncEnabled`, and macOS timeout policy; FG presenter overrides `allowsNextDrawableTimeout` to true (`MetallumNative.swift:2968-2999,166-170`) |

## Safety conclusions

1. **Confirmed:** normal Java resource destruction is deferred through submit/fence machinery for resources queued via `MetalCommandEncoder.queueForDestroy`.
2. **Confirmed:** native FG has explicit event/worker drain logic and timeout/drop behavior.
3. **Confirmed:** world/reset/FOV/invalid matrix history resets exist.
4. **Unknown:** whether every resize/fullscreen/Retina path drains both Java in-flight commands and native FG slots before destroying old targets.
5. **Confirmed sequence, unresolved cross-layer risk:** Minecraft calls `GameRenderer.close` before `RenderSystem.shutdownRenderer`; the mixin closes MetalFX auxiliary targets/native caches at `GameRenderer.close` TAIL, while `MetalDevice.close` performs its Java GPU wait later (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/Minecraft.java:1112-1137`; `GameRendererMetalFxMixin.java:104-107`; `MetalFxManager.java:694-705`; `MetalDevice.java:178-190`). Native FG shutdown drains its own presenter, but no evidence shows the Java command buffer has completed before `uiTarget`/auxiliary target destruction. This is a **strong-inference resource-order risk**, not a confirmed use-after-free.
6. **Risk:** `MetalSurface.close()` is empty (`MetalSurface.java:71-73`), so layer ownership/teardown is external; `MetalDevice.close` later clears the Cocoa layer and releases the device, but no runtime teardown trace was captured.
7. **Risk:** native texture sets are `.private`, and the general native texture path can use `.untracked`; cross-layer GPU completion must be captured before changing MRT/target lifetime.
8. **Confirmed scaffold boundary:** V2 camera/disocclusion/merge kernels and object motion textures exist, but `MetalMotionStateStore.observe` has no production caller and `prepareMotionInputs` only clears object motion/validity. The current final motion therefore falls back to camera motion; this is a missing producer, not a proven synchronization failure.
9. **Future接入 risk:** `MetalFxManager.beginFrame()` is called from the `render` HEAD injection and again inside the projection `ModifyArg` path. A future object-state observer inserted between those points could have its pending state cleared by the second transaction start; current source has no observer there, so present impact is unknown.

## Required lifecycle verification

- inject no new code; use existing logs/Metal validation to trace target handle, submit index, semaphore value, native slot value and resize epoch;
- perform resize/fullscreen/Retina changes while a frame is in flight and while FG is enabled;
- open/close GUI and verify pause/resume, native presenter reactivation, and history reset behavior;
- reload resources/shaders and verify old PSO/functions do not outlive their native library or command buffer;
- close the game with pending present/worker requests and confirm no timeout, use-after-free or drawable acquire error.
