# 矩阵、jitter 与 motion 约定

> **2026-07-26 status:** motion 方向、top-left Y 和 jitter exclusion 仍沿用本文合同；普通实体 producer 与数值 readback 已接入，当前证据见 `../metalfx-motion-pipeline-implementation.md`。

## 矩阵来源与更新时间

| 矩阵/状态 | 来源与更新时间 | 当前用途 | 置信度/限制 |
| --- | --- | --- | --- |
| Mojang final projection | `GameRendererMetalFxMixin` 在 `GameRenderer.renderLevel` projection 参数处调用 `MetalFxManager.prepareSceneProjection`；manager 保存 `projectionMatrix` 到 `currentProjection`（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java:59-77`；`MetalFxManager.java:285-312`） | 场景投影，含 Mojang 的 bob/hurt/screen effects 结果 | confirmed path; exact call ordinal/local layout is version-coupled |
| view matrix | `MetalFxMath.viewMatrix` 将 camera rotation 后施加 `(-cameraX,-cameraY,-cameraZ)`（`MetalFxMath.java:94-106`）；manager 在 projection prepare 期间构造（`MetalFxManager.java:334-342`） | current VP 和 jittered VP | confirmed |
| current unjittered VP | `MetalFxMath.viewProjection(currentViewProjection,currentProjection,viewMatrix)`（`MetalFxManager.java:348-349`；`MetalFxMath.java:108-118`） | previous/current motion projection | confirmed |
| jittered VP | 对同一 projection 写入 clip jitter 后再组合（`MetalFxManager.java:360-369`） | inverse reconstruction from current depth | confirmed |
| inverse current jittered VP | `jitteredViewProjection.invert(inverseCurrentViewProjection)`（`MetalFxManager.java:369-371`） | reconstruct world position | confirmed |
| previous VP | field `previousViewProjection`（`MetalFxManager.java:38`），成功准备/encode 后更新到 current（`MetalFxManager.java:511-515`） | current-to-previous camera motion | confirmed update point; scene-cut clearing semantics still need lifecycle audit |
| object current/previous transform | 未进入 `MetalFxManager` field、Java/native encode argument 或 `MetalFxMath.reconstructMotion`；这里的 encode argument 仅包含相机 VP，不包含对象 transform | 无 | confirmed absence in inspected path; complete entity source audit deferred |
| camera/FOV/near/far/aspect | camera state and projection; manager stores `frameFieldOfView`, `frameFarPlane` and adjusts display/render aspect (`MetalFxManager.java:55-67,306-319`) | Temporal/FG scalar input | confirmed; FOV extraction fallback is unit-tested |

## Jitter 数学

`MetalFxMath.pixelJitter` 使用 Halton base 2/3，取 `Halton(index)-0.5`（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalFxMath.java:16-43`）。`clipJitter` 是：

```text
clipJitter.x =  2 * pixelJitter.x / renderWidth
clipJitter.y = -2 * pixelJitter.y / renderHeight
```

然后 `applyProjectionJitter` 将 x/y 加到 JOML projection `m20/m21`（`MetalFxMath.java:45-68`）。相位在成功 frame 后 `phase = (phase + 1) % phaseCount`（`MetalFxManager.java:511-515`）；0.67 的现有测试期望 18 phases，0.5 期望 32（`MetalFxMathTest.java:103-110`）。

**静止相机验证：** 单测通过 jittered inverse 与 unjittered current/previous 组合，静止相机 motion 为零（`MetalFxMathTest.java:74-83`）。这只证明函数级 jitter isolation，不证明实际 depth 是由同一个 jittered projection 产生。

## Motion 重建公式

当前实现可还原为：

```text
currentNDC.x = 2 * (pixelX + 0.5) / width  - 1
currentNDC.y = 1 - 2 * (pixelY + 0.5) / height
currentNDC.z = depth

world       = inverse(currentJitteredVP) * currentNDC
currentClip = currentUnjitteredVP * world
previousClip= previousUnjitteredVP * world

motion.x = previousClip.x - currentClip.x
motion.y = currentClip.y - previousClip.y
```

运行时实现位置是 native V2 `metallum_motion_camera_v2` 与 `metallum_motion_merge_v2`（`MetallumNative.swift:1355-1475`），由 `metallum_metalfx_encode_v2` dispatch（`:1844-2011`）。Java `MetalFxMath.reconstructMotion`（`MetalFxMath.java:120-157`）是 legacy/数学 mirror；单元测试证明该 mirror 静止为 0（`MetalFxMathTest.java:49-54`）、x 平移方向输出正 20（`:57-63`）、top-left y 方向为 -10（`:65-72`）、旋转有方向性（`:85-93`）。当前 V2 还接受 object motion/validity，但 inspected production path 只清零这些输入。

**方向：confirmed。** 运行日志也记录 `motion=previousScreen-currentScreen`。**单位：strong_inference/partially unknown。** Java/native 日志记录 `motionVectorScale=(572.0,321.0)`，对应 `input=1144x642` 的半尺寸；但没有本轮 GPU capture 证明 MetalFX 内部对 `RG16_FLOAT` motion 的最终归一化方式。

## Depth convention

场景 depth clear 使用 0.0（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:430,462,593`；native v2 clear path `MetallumNative.swift:2484-2580`），Java 日志和 native encode 传 `depthReversed=true`。因此当前契约把 valid depth 视为大于 clear value 的 reversed-Z 方向。

**限制：** 映射源码证明 clear/调用，运行日志证明参数；没有 GPU readback 证明一个实际 fragment 的 depth 数值和 shader depth compare。

## JOML 与 simd/Metal 的传递

当前存在两条不同的 native 输入路径，不能混为一谈。Temporal V2 的 `MetalCommandEncoder.encodeMetalFxV2` 接收 `currentViewProjection`、`inverseCurrentViewProjection`、`previousViewProjection`，调用 `Matrix4f.get(float[])` 写入 Java scratch 数组（`MetalCommandEncoder.java:457-517`）。`MetalNativeBridge.metallum_metalfx_encode_v2` 再把数组复制到 thread-local native segments（`MetalNativeBridge.java:951-999`）；Swift 的 `makeMatrix` 将连续四元组组装为 `simd_float4x4`，并在 V2 camera compute 的 `MotionUniforms` 中使用（`MetallumNative.swift:1281-1287,1844-1957`）。这是已确认的 JOML -> float buffer -> Swift simd 矩阵链。legacy `encodeMetalFx`/`metallum_metalfx_encode` 仍存在，不能把它的旧 kernel 路径当成当前 manager 的主 Temporal call。

另一方面，`frameGenerationInputInternal` 传入 Frame Generation 的是 texture handles 和标量 `jitterX/Y, fieldOfView, near/far, aspect, reset`，不再重复传 VP 矩阵（`MetalFxManager.java:790-822`）。因此“Frame Generation input record 没有 VP 字段”是 confirmed，但“当前没有 JOML -> Swift 矩阵转换链”是错误表述；后续若修改 Temporal 矩阵布局，必须同时保持 `Matrix4f.get`、bridge scratch 和 `makeMatrix` 的列向量分组契约。

## History reset

当前代码触发 reset 的分支包括：

- display/render dimensions changed（`MetalFxManager.java:285-299,578-606`）；
- FOV/far projection change（`MetalFxManager.java:316-323`）；
- camera teleport distance（`:319-324`）；
- invalid current/jittered matrix（`MetalFxManager.java:349-376`）；
- explicit `MetalFxManager.resetHistory(String)`（`MetalFxManager.java:198-201,633-643`）；
- frame-generation/encode failure path may disable or set present reset (`:461-489`）。

`previousViewProjection` 是否在 world unload、pause、window hidden、resource reload 和 camera mode change 时清零，在当前首轮未完成生命周期审计；不要把上述 frame-local reset 列表当成完整 reset contract。

## 结论与验证边界

1. **相机 motion 约定已确认。** 公式、方向、静止/平移/旋转单测和日志互相支持。
2. **jitter 公式已确认，实际场景接入仍需 capture。** 当前 projection 注入点明确，但历史 scissor 尺寸反证说明坐标契约不能只由数学测试闭合。
3. **对象 motion 未接入已确认于 inspected bridge。** 这不是“motion texture 为空”；texture 有真实 reconstruction 写入，但输入信息只含相机/深度。
4. **previous matrix 生命周期尚未完全确认。** 已记录成功 frame 更新点，跨世界/窗口事件需后续文件补齐。

## Narrowed evidence: motion sign and scale against the installed MetalFX SDK

**Confirmed:** the runtime native V2 producer emits a current-to-previous motion vector in normalized clip units, with a top-left screen-space Y conversion. For each valid depth pixel, `metallum_motion_camera_v2` reconstructs world position through `inverseCurrentViewProjection`, projects it with the unjittered current and previous VP matrices, and writes:

```text
motion.x = previousClip.x - currentClip.x
motion.y = currentClip.y - previousClip.y
```

(`MetallumNative.swift:1355-1412`). The Java mirror has the same pixel-space conversion (`MetalFxMath.java:120-155`), and the matrices passed to the V2 camera kernel are assembled from the three Java float arrays (`MetallumNative.swift:1281-1287,1928-1957`; `MetalCommandEncoder.java:457-517`).

**Confirmed by cross-source contract:** the installed Xcode 26.5 MetalFX header says each motion value is multiplied by `motionVectorScaleX/Y` to become fragment pixels, and defines a vector as pointing from the current pixel to its previous-frame location. Its example says an object moving down/right by 10 pixels uses `(-10,-10)` (`/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX26.5.sdk/System/Library/Frameworks/MetalFX.framework/Headers/MTLFXTemporalScaler.h:266-286`; the same contract is repeated for the frame interpolator at `MTLFXFrameInterpolator.h:197-217`). Current V2 native assignment uses `inputWidth * 0.5` and `inputHeight * 0.5` (`MetallumNative.swift:1992-1996`), which converts NDC delta to input-pixel delta. Therefore the current camera-motion sign and scale are `confirmed` against both producer math and the local SDK contract; the prior report's “internal MetalFX normalization unknown” wording was too weak and is corrected by this evidence.

**Confirmed:** depth convention matches the SDK contract. Minecraft clears depth to `0.0`, the native validity test treats `(0,1]` as valid, and the scaler is assigned `depthReversed = true` (`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/LevelRenderer.java:206-212`; `MetallumNative.swift:1171-1173,1519-1524`). The SDK defines `depthReversed` as zero representing farthest distance (`MTLFXTemporalScaler.h:288-292`; `MTLFXFrameInterpolator.h:293-296`). This proves the declared convention; it does not prove every third-party shader writes the same depth encoding into the bound target.

**Confirmed:** camera jitter is excluded from the motion projection pair but is used for depth unprojection. `currentViewProjection` is assembled before jitter; `jitteredViewProjection` is assembled after the jittered projection is applied; the inverse passed to native is the latter, while current/previous projections used for output motion are unjittered (`MetalFxManager.java:341-384`; `MetalCommandEncoder.java:300-328`). This is the intended invariant: reconstruct the world point using the projection that produced the depth, then compare unjittered screen positions.

**Still unverified:** the Java jitter convention itself is source-confirmed but not GPU-confirmed. It uses a Halton phase, maps pixel jitter to `(2*x/width, -2*y/height)`, adds it to `m20/m21`, and passes the unmodified pixel jitter to MetalFX (`MetalFxMath.java:31-69`; `MetalFxManager.java:360-369`; `MetallumNative.swift:1513-1516`). The SDK only describes the jitter property as the pixel offset used to return to the reference frame, without a sign example (`MTLFXTemporalScaler.h:256-264`). A static camera capture must therefore still verify whether the projection injection and `jitterOffsetY` use the same sign expected by the driver. The Java unit tests prove the internal convention and the mirror formula, not the rendered sample displacement (`MetalFxMathTest.java:12-26,48-94`).

**Matrix layout evidence:** the cached JOML 1.10.8 `Matrix4f.get(float[])` delegates to `MemUtil.copy(Matrix4fc,float[],int)`, whose bytecode stores `m00,m01,m02,m03`, then `m10...` in sequence (`/Users/retriedstormtrooper/.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8/fc0a71dad90a2cf41d82a76156a0e700af8e4f8d/joml-1.10.8.jar`, `org.joml.Matrix4f.get(float[])`, `org.joml.MemUtil$MemUtilNIO.copy(...)`). Swift consumes each four-float group as one `simd_float4x4` column (`MetallumNative.swift:1270-1277`). This is strong static evidence for a column-grouped transfer; an on-GPU identity/known-transform capture is still the final check for the complete Java/JOML/Swift/Metal multiplication path.
