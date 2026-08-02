# Mixin 风险与版本耦合

> **2026-07-26 status:** 本文保留实现前耦合审计。当前 Minecraft 26.2 entity producer、depth-before-hand hook 和 automated client validation 的实际接入点以源码及最终验收报告为准。

## 当前配置边界

`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/resources/metallum.mixins.json:8-24` 将全部 render/Sodium mixins 放在 client 列表，`defaultRequire=1`，兼容级别 `JAVA_25`。`fabric.mod.json:34-38` 又要求 Fabric Loader >=0.19.2、Minecraft `~26.2-`、Java >=25。`MetallumMixinConfigPlugin.shouldApplyMixin` 首先要求 `os.name` 包含 `mac`；当前源码条件可以确认非 macOS（包括 iOS JVM 环境）不应用本配置 mixin。对 macOS，`.mixin.sodium.` 走 Sodium presence 条件，`PreferredGraphicsApiMixin` 总是保留，而其它 render mixin 只有 `preferredGraphicsBackend` 读为 `"default"` 时应用（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java:20-45,63-79`）。

## MetalUniversal Mixin 表

| Mixin | Target | Injection point | Local capture | 功能 | 版本风险 | Sodium/其他 Mod 影响 | 失败结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `render.PreferredGraphicsApiMixin` | `PreferredGraphicsApi` | `getBackendsToTry` HEAD cancellable；`caption` HEAD cancellable | 无 | 插入 `MetalBackend` 并改 caption | method 名/返回数组类型变化会硬失败 | 其他 graphics backend mixin 可能同点竞争；直接改变 backend 选择 | backend 不选 Metal 或 required mixin fail |
| `render.GameRendererMetalFxMixin` | `GameRenderer` | `<init>` NEW `MainTarget`；`resize` `RenderTarget.resize(II)`；`render` width/height FIELD；`render` HEAD；`renderLevel` `ProjectionMatrixBuffer.getBuffer(Matrix4f)` `ModifyArg(index=0)`；`render` GUI render INVOKE BEFORE；blur main target FIELD；`setLevel`/`resetData`/`close` TAIL | 无 | 缩放 main target、projection jitter、before-GUI encode、reset/close | Minecraft 26.2 的 exact invoke/field order、argument type、number of generic resize calls | Sodium 有 `GameRenderer` workaround mixins；其他 mods 可改变 render/blur/projection body | target not found/required mixin fail；或错误 redirect 造成尺寸/时序错误 |
| `render.GameRenderStateMetalFxMixin` | `GameRenderState` | `useShaderTransparency` RETURN cancellable | 无 | 在 MetalFX reactive mask需要时影响 transparency target创建 | method return/owner变化 | Sodium config/render state 可同时改变 transparency | transparency targets 缺失或无条件启用 |
| `render.LevelRendererMetalFxMixin` | `LevelRenderer` | `addAlwaysOnTopPass` HEAD | 无 | 向 FrameGraph 插入 reactive mask pass | 方法改名/参数签名变化；HEAD 的 target state 时序敏感 | Sodium `LevelRendererMixin`/sky/cloud mixins 与同一 render frame 叠加 | reactive pass 不插入或读到尚未完成/错误 handles |
| `render.GuiRendererMetalFxMixin` | `GuiRenderer` | `draw` 中 invoke `GameRenderer.mainRenderTarget()` redirect | 无 | GUI render target 改为 `MetalFxManager.guiTarget` | GUI package/name/signature或调用次数变化 | 其他 GUI/postprocess mixin 可能重排 draw | GUI进入低分辨率/错误 scissor；历史 crash 是尺寸风险证据 |
| `render.MinecraftMetalFxMixin` | `Minecraft` | `<init>` width/height FIELD redirect；`renderFrame` mainRenderTarget INVOKE redirect | 无 | 启动报告尺寸、final present target改为native target | `<init>`/`renderFrame`调用点变化；FIELD redirect作用域广 | Sodium core Minecraft/window mixins；其他 surface mods | present低分辨率或构造期间尺寸异常 |
| `sodium.DrawBackendMixin` | Sodium `DrawBackend` | `chooseBackend` HEAD cancellable, `remap=false` | 无 | 返回 `VK_INDIRECT` 作为 Metal backend选择 | Sodium enum/chooseBackend method变化；`remap=false` 强绑定 intermediary/class name | Sodium自身 backend selection 是直接竞争点 | Sodium backend init失败或回到其他 backend |
| `sodium.DrawContextMixin` | Sodium `DrawContext` | `create` HEAD cancellable, `remap=false` | 无 | 返回 `new MetalDrawContext()` | factory return/class hierarchy变化 | 依赖 `DrawBackend.BACKEND` 已被前一个 mixin改写 | Sodium draw context创建失败 |
| `sodium.SodiumPreferredGraphicsApiMixin` | `CyclingControl$CyclingControlElement` | `extractRenderState` 中 redirect `EnumOption.getElementName(Enum)`，`remap=false` | 无 | 只改 Sodium graphics API option 显示名 | inner class 名/enum option method变化；非语义 mapping | Sodium GUI版本变化；可能影响其它 enum label | 设置页 label错误或 mixin fail |

## 作用域风险重点

### `GameRenderer` width/height redirect

`GameRendererMetalFxMixin` 对 `GameRenderer.render` 中的所有目标字段读取做 `FIELD` redirect（`GameRendererMetalFxMixin.java:34-48`），不是以 field read 的 ordinal/local context 区分。`MinecraftMetalFxMixin` 对 `<init>` 中的 `RenderTarget.width/height` 同样是宽作用域（`:13-27`）。这能解释为什么尺寸传播必须用 runtime capture 验证：一个 redirect 可能影响 comparison、GUI/scissor、post effect 或其它同方法 target，而不是只影响 MetalFX main target。

### 通用 `RenderTarget.resize(II)` redirect

`GameRendererMetalFxMixin.java:25-32` 对 `GameRenderer.resize` 中的 `RenderTarget.resize(II)` 做 redirect，没有 `ordinal`。当前映射的 `GameRenderer.resize` 至少 resize main target 和 LevelRenderer（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/GameRenderer.java:317-320`）；如果未来同方法增加更多 RenderTarget，redirect 作用域可能扩大。历史 scissor crash 证明这类尺寸边界不是理论问题。

### Projection `ModifyArg`

`renderLevel` 的 `ProjectionMatrixBuffer.getBuffer(Matrix4f)` 被 `ModifyArg(index=0)` 替换（`GameRendererMetalFxMixin.java:59-75`）。没有 locals capture，优点是局部布局变化影响较小；风险是只要同一方法出现多个相同 invoke 或 Mojang 改调用顺序，注入点可能命中错误 projection。当前没有 ordinal，因此后续版本必须重新确认 target 数量。

该注入还有一个当前 manager 生命周期耦合：`render` HEAD 的 `GameRendererMetalFxMixin.metallum$beginFrame` 先调用 `MetalFxManager.beginFrame()`，随后 projection `ModifyArg` 进入 `prepareSceneProjectionInternal`，后者再次调用 `beginFrameInternal`（`GameRendererMetalFxMixin.java:50-75`; `MetalFxManager.java:153-169,289-310`）。当前没有生产 `MetalMotionStateStore.observe`，所以尚未证明实际帧数据被清掉；未来若在两处之间采集对象状态，第二次 begin 会清空 pending transaction。**confidence=confirmed call topology; future data-loss impact=unknown.**

### GUI 与 always-on-top 注入

GUI redirect 是 `GuiRenderer.draw` 内具体 `GameRenderer.mainRenderTarget()` invoke，作用域比 `GameRenderer` field redirect 窄（`GuiRendererMetalFxMixin.java:12-22`）。Reactive injection 在 `addAlwaysOnTopPass` HEAD，但它使用 FrameGraph `targets` handles；若 Minecraft 改变 target lifecycle或把 cutout/feature submit移到另一个 pass，mask可能缺内容。没有 capture 证明该 injection 覆盖所有透明内容。

## Sodium 叠加与绕行

Sodium 0.9 在当前解包源码中将 draw backend 选为 `VK_INDIRECT`，`DrawContext.create` 在该 backend 创建 `VKIndirectContext`；MetalUniversal 用 `MetalDrawContext extends VKIndirectContext` 接管 context（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalDrawContext.java:13`；`/tmp/minecraftmetal-sodium-decomp/.../DrawBackend.java:8-23`、`DrawContext.java:9-23`）。`ChunkSectionsToRenderMixin.renderGroup` 又取消 vanilla group draw，改走 `SodiumWorldRenderer.drawChunkLayer`。因此：

- Sodium terrain SOLID/CUTOUT/TRANSLUCENT 共享 Metal backend；当前 terrain pipeline 只声明一个 color target，indexed attachment backend 能力本身已存在；
- Sodium 自身 `GameRenderer`/window workaround Mixins 可能同时重定向 resize/minimized state；
- entity/block entity/particle 不会因为 Sodium terrain backend 而自动获得 motion MRT；
- 第三方 renderer 如果绕过 Mojang `RenderPipeline` 或另建 native path，当前 MetalFX mask/motion 不会自动覆盖。

## 无 Sodium、iOS 与其它环境

- 无 Sodium：config plugin 对 Sodium mixins 有条件筛选，render mixins 理论上仍可成立；但 `GameRenderer`/`LevelRenderer` 的 transparency target是否存在、`useShaderTransparency` 返回值和非-Sodium pipeline集必须单独验证。
- iOS：native/Java mode gating 对 iOS 返回 OFF（`MetalFxManager.java:231-233`），且 `MetallumMixinConfigPlugin.shouldApplyMixin` 在 `os.name` 不含 `mac` 时直接返回 false（`MetallumMixinConfigPlugin.java:36-39`）。所以“iOS 不应用本配置 mixin”是当前插件逻辑的 confirmed 结论；iOS native dylib 是否能被宿主加载、以及是否存在非 Minecraft 的 iOS Java 启动环境，仍需构建/运行验证，不能从该 gating 推出。
- 其它渲染 Mod：当前没有 mixin priority 或冲突处理证据。`defaultRequire=1` 意味着 target 变化通常是硬失败，不是静默降级。

## 版本耦合等级

| 耦合项 | 当前状态 | 等级 |
| --- | --- | --- |
| Minecraft 26.2 mapped names/signatures | 已按当前 `/tmp/minecraftmetal-mc26-sources` 对齐 | current baseline |
| Loom | property `1.16-SNAPSHOT` vs resolved 1.16.3 不一致 | medium; must pin/record at implementation time |
| Java | source/mixin compatibility JAVA_25，环境 Java 24 | hard build blocker |
| Sodium 0.9 | `remap=false` DrawBackend/DrawContext and inner GUI class names | high |
| Mixin order | no explicit priority/compatibility matrix | unknown/high with other mods |
| runtime pipeline | no complete enumeration | unknown |

## 失败结果分类

- **硬失败：** target/name/signature变化、required injection not found、Java 25 class/compile mismatch。
- **静默行为错误：** width/height redirect 命中错误 read、GUI target错误、transparency target未创建、Sodium backend显示名/选择不一致。
- **性能/画质回归：** injection仍成功但 projection、scissor、pass order与原版语义不同。

## 后续实现模型必须先保留的边界

1. 不要把 `GameRenderer` 的宽作用域 FIELD/resize redirect 当成稳定 API；每次版本升级要确认所有命中点。
2. 不要把 Sodium terrain Mixin 当成 entity/particle path 的统一入口。
3. 不要通过 `remap=false` target 名称推断跨 Sodium 版本稳定。
4. 不要通过 Mixin 应用成功推断 GUI/Temporal/Frame Generation 语义正确；需要 runtime dimensions/target capture。
