# MetalFX、帧生成与运动矢量：云端验收记录

> 实现代码提交：`ae4d8fdaf9486e9910e475870a1d0a754290da04`。本轮云端可运行验收已完成；Temporal/FG 真实质量和物理显示时序受实际设备能力阻塞。后续报告提交只改变文档，不改变此实现的可执行输入。

> 2026-10-20 后续变更：手部独立 HUD projection / per-draw model-view 已与 staged 顶点接入同一历史事务。本文下方“尚未支持”的手部条目仅描述上述历史提交；现行实现见[时序放大契约](metalfx-temporal-upscaling.md)，新提交的 Actions 证据见 [PR #90](https://github.com/21Z121Z1/MetalUniversal/pull/90)。物理 Temporal/FG 质量仍需支持相应能力的设备验证。

基线为 `97e53a76592963f7ee866e28fd222798abe798a1`，Minecraft 26.3；本轮首先完成[专项研究](metalfx-closure-study.zh-CN.md)，再选定坐标、深度、历史和实际能力回退目标。更广的同类项目搜索及原生批调用研究见[渲染器研究](renderer-reference-study.zh-CN.md)。本轮未复制外部源文件，未合并主线、发布或强推；工作保留在 [PR #90](https://github.com/21Z121Z1/MetalUniversal/pull/90)。

## 研究如何改变实现

Apple 官方示例明确 current→previous、去 jitter、输入像素单位；项目自身使用 NDC 差，因此保持 inputWidth/2、inputHeight/2 的 scale。Godot 提醒明确资源使用和 encoder/fence 边界；MCUpscaler 的独立手部矩阵提示核查 HUD 与世界投影；MetalQuake 的去 jitter 矩阵分离用于交叉检查。MetalDuck 的同名实现实际使用 Vision 光流，不能作为 Apple FrameInterpolator 集成证据。固定 SHA、许可、官方示例 ZIP SHA-256 和不适用部分已列入研究文档。

第一轮 Ciderlight、Vantage、metal189 及第二轮 mcopt、MCMetal 等形成了上一轮六项 CPU 优化；本轮继续保留其默认值及独立回退。其历史 CPU 编码微基准不属于 MetalFX 性能结果。

## 已复现的问题及修复

| 问题 | 修复前证据 | 修复与验收边界 |
| --- | --- | --- |
| 接受相机背后的历史点 | `e296933` 的 [Actions 39282894644](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39282894644)，两种 OS 各 36 例中 2 例 negative clip.w 失败 | CPU 与三个原生相机内核要求 current/previous clip.w 为正；世界齐次缩放允许负值，新增合法负缩放 GPU 对照 |
| 运动与 jitter 的 Y 未对齐实际光栅 | Linux 实际 Shaderc→SPIRV-Cross 输出含 gl_Position.y 翻转；旧语义的三项数值预期失败，真实 MSL 编译预期通过 | 在 Java/MetalFX 边界转换矩阵及 raster jitter；着色器运动公式不重复翻转。随后 8 个实际打包着色器经 FFM/Swift 的 GPU 用例通过 |
| 分离式手部内核把非法精确运动退回相机运动 | `9e864ba` 的 [Actions 39286353717](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39286353717) 中两种尺寸的 split 用例失败，融合式通过 | 非有限或越界的 owned exact motion 写零并强 reactive；与融合路径一致。中间提交曾漏改纹理访问声明，实际 MSL 编译失败后修正，未作为数值通过 |
| 比较了不同相机空间的原始深度 | `76f26e5` 的 [Actions 39289484705](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39289484705) 中，正/反向 Z × 两个尺寸 × 两条内核共 8 例误拒绝有效历史，其余 76 例通过 | 相机像素在前帧空间比较深度，邻域搜索也使用该深度；增加相机深度变化后仍须拒绝真实遮挡物的对照。对象/手部 RG 运动没有前帧 Z，继续保守拒绝，不宣称动态遮挡精确 |
| 第一人称顶点历史被当成完整投影历史 | Minecraft 26.3 GameRenderer.render3dHud 使用独立 hudFov/投影与 model-view；数值对照证明世界矩阵会凭空引入手部像素运动 | 保留 staged 顶点历史，但明确拒绝无独立投影历史的第一人称精确收据；Temporal 使用零运动/reactive，补帧拒绝该源帧。世界重放保留实际 draw 的同帧 Projection slice |
| Spatial 被计为 Temporal 编码 | 原计数在所有 scaler 成功后都增加 temporal 计数 | 分开 spatial/temporal accepted-encode 计数；GPU 完成另查呈现收据，不以计数冒充完成 |
| pause/resume 历史边沿缺少统一处理 | 原有 FG 暂停不等于 Temporal 历史失效 | 在帧提取前观察实际 Minecraft pause 边沿；重复 paused 帧不反复清历史。真实客户端已验证开关、比例和暂停恢复 |
| 资源重载与暂停混淆 | 单人 LoadingOverlay 也会暂停。`0e99984` 的 [Vanilla 对照](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39291356171/job/118192748996) 保持暂停时，重载后 epoch 仍为 42，实际失败 | `ae4d8fd` 在 GameRenderer.onResourceManagerReload 完成后调用统一 surfaceDiscontinuity，停止旧补帧队列并独立清历史；保留原失败断言；最终 Vanilla 的 epoch 为 44→45→46，Sodium 为 43→44→45（暂停→暂停中重载→恢复） |

## 当前坐标与 API 合同

- 颜色、深度与 RG16F motion 采用同一输入分辨率/原生纹理行序；矩阵按列通过 Java→FFM→Swift。
- motion 指向当前像素对应的上一帧像素；X=previousNdc.x-currentNdc.x，Y=currentNdc.y-previousNdc.y，且这里是经过实际编译器 Y 策略后的 Metal clip。
- motionVectorScale=(inputWidth/2,inputHeight/2)；不是输出分辨率，也不是 UV 差的整宽/高 scale。
- Halton 像素 jitter 原样报告给 MetalFX；施加到 Minecraft projection 时补偿编译器翻转；相机重建用当前 jittered VP 的逆矩阵，当前/前帧运动使用 unjittered VP。
- Minecraft Projection 在设备 Z0..1 合同下交换 near/far，使用 reversed Z；GPU 数值检查同时覆盖正向和反向 Z、天空、历史重置及非法输入。
- FrameInterpolator 的 fieldOfView 为角度，deltaTime 为秒；拒绝非有限相机参数、非法 near/far/aspect/jitter。macOS 27 的矩阵属性没有塞入 macOS 26 ABI。
- 当前 UI 链路使用 uiTexture=nil / isUITextureComposited=false，插值场景后在生成帧和真实帧上分别叠加原生分辨率 UI。HUD 不进入时域场景历史；此条是源码/编译合同，当前云端没有执行插值呈现。
- Metal 3/4 的分离式 merge 使用同一个内部 112 字节 Swift 常量结构，与 MSL 的 matrix offset=48 对齐；没有改变导出的 C/FFM 描述符。Metal 4 的相关代码完成编译，未被当前设备执行。

## 实际 GPU 证据

实现提交 `ae4d8fdaf9486e9910e475870a1d0a754290da04` 的 [Actions 39293852150](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39293852150)：

| 检查 | macOS 26 | macOS 15 |
| --- | --- | --- |
| shipping fused/split motion：24 例 × 两尺寸 × 两路径 | 96/96，0 失败 | 96/96，0 失败 |
| 实际 shipping present/copy shader 非对称图案 | 2/2，最大字节误差 0 | 2/2，最大字节误差 0 |
| 打包 entity/posed/cutout/particle/leash GLSL→生产编译器→FFM→Swift | 8/8，0 跳过 | 8/8，0 跳过 |
| 原生 frame 生命周期/相机标量合同 | 16 项通过 | 16 项通过 |
| 不支持 Temporal/FG 时的返回值与未改写输出哨兵 | 通过 | 通过 |
| 实际透明 alpha 合同 | 通过：partial alpha=92、zero=0 | 同左 |
| Temporal/FrameInterpolator 质量 | blocked | blocked |

运动产物包含每例 RG16F/R8/R32 原始数据、motion/depth/reactive PNG、独立数值预期、逐例 JSON 和 command-buffer GPU 时间。时间属于验证负载，不是 Minecraft 性能。Java producer 另存两帧覆盖 mask、RG16F 与实际 raster centroid 对照。没有删除失败断言、靠增加 timeout 或无限重跑覆盖失败。

同一实现提交的[完整 GPU/ABI 工作流](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39293852128)产物含 535 项 JUnit 记录：485 项合同与 25 项成本测量实际执行通过，0 failures/errors；另 25 项为配置门控或缺少可选 fixture 的条件跳过。保留的六项优化与独立回退均参与回归。

原生索引批调用在当前提交的 CPU 复测仍有收益。八个独立 JVM 按 ABBAABBA，只切换 nativeIndexedVertexRebase，其他五项固定开启；每个 JVM 先取八轮 ns/draw 的中位数，再取各四个 baseline/candidate JVM 的中位数。两段 ABBA 均同向改善；计时区间外等待 GPU，计数关闭，各轮 Java bytes/draw 为 0。

| 每批 draw | Java 批处理 µs/draw | 原生批处理 µs/draw | CPU 编码成本降低 |
| --- | ---: | ---: | ---: |
| 2 | 0.925 | 0.538 | 41.8% |
| 16 | 0.646 | 0.136 | 78.9% |
| 128 | 0.646 | 0.113 | 82.5% |

这些是云端编码微基准，不是 Temporal/FG 收益，也不换算为实机 FPS、延迟或功耗。

宏观 PNG 也已查看。E2E 的 metal-framebuffer.png / final-drawable capture 实际取自 **pre-present source texture**，元数据明确标注；它在 PNG 中上下倒置，不能直接当作实际屏幕方向。真正 shipping present shader 的 GPU 对照证明它翻转 Y，内部 copy 不翻转，防止 MetalFX/GUI 被翻两次。这仍不是物理显示器扫描输出或延迟证据。

## 客户端与默认开关

最终实现 `ae4d8fd` 的[客户端 Actions](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39293852389)已独立复核两条 MetalFX 回退产物：

| 客户端 | requested / effective | Spatial / Temporal accepted encodes | epoch：暂停→暂停中重载→恢复 | FG |
| --- | --- | ---: | --- | --- |
| Vanilla | TEMPORAL / SPATIAL | 1,276 / 0 | 44→45→46 | requested=true，enabled=false，queued=0 |
| Sodium | TEMPORAL / SPATIAL | 1,330 / 0 | 43→44→45 | 同左 |

- 显示 854×480，67% 场景为 572×322，关闭后回到 854×480，50% 为 427×240；恢复 67% 后尺寸复原。
- 重载、关闭/重新开启、切换比例、暂停和恢复均推进相应历史 epoch。暂停不变时的重载单独推进一次，不能由 LoadingOverlay 的 pause 边沿冒充。
- 两条任务均完成九帧捕获，无失败/丢弃；三色墙每区 6,528/6,528 像素符合独立预期。重载后 Vanilla 32 次、Sodium 30 次新呈现提交全部完成、失败为 0。这是 GPU 命令/呈现调用完成证据，不是显示节拍或输入延迟证据。
- 构建 source SHA、native source 输入 SHA、实际加载的 JAR/native 与打包身份均独立核对；原生输入与当前源码一致。

最终实现提交的八条客户端已全部通过，并下载逐一复核：

| 客户端 | transport | MetalFX 请求配置 | native 索引批次 | 独立复核 |
| --- | --- | --- | ---: | --- |
| sodium | baseline | off | 0 | 通过 |
| sodium | candidate | off | 3,602 | 通过 |
| sodium | default | off | 3,424 | 通过 |
| sodium | default | requested-temporal | 3,656 | 通过 |
| vanilla | baseline | off | 0 | 通过 |
| vanilla | candidate | off | 0 | 通过 |
| vanilla | default | off | 0 | 通过 |
| vanilla | default | requested-temporal | 0 | 通过 |

八份生产 JAR SHA-256 均为 `692520b0c00d0b8826055b583cf3b9a579cd64e351e7685d22c98232d01c4fdc`，实际加载且与打包一致的 native SHA-256 均为 `d800ce806ed8fde93db9db17f3cf23af177a6145947df2c84a6c08db3d6dece0`。各份 build.sourceSha 都是上述实现提交、dirty=false，native 源输入哈希与源码逐一一致。九帧捕获、独立三色像素、呈现完成、常规重载均通过；两条 FX 另通过开关、比例、暂停/恢复及暂停中重载。没有用关闭整个后端代替候选执行。

本地 Java 25 完整测试：858 tests、0 failures/errors、43 项平台限定跳过；`verify_unified_eval.sh` 通过。当前实现提交的构建、迁移、静态检查和 CodeQL 通过。

六项 CPU 优化保持默认 true：fusedStateDraw、reuseEncoderState、reuseNativeEncoderArguments、dynamicUploadRangeCopy、batchIndexedVertexRebase、nativeIndexedVertexRebase；各自保留 false 回退。Temporal=OFF、FG=false、完整 object-motion producer gate=false；未凭编译或支持查询放开质量开关。

## 明确阻塞与尚未支持

两种 runner 都实际报告 Apple Paravirtual device、Spatial=true、Temporal=false、Metal4FX=false。macOS 26 的 FrameInterpolator API 存在但 supportsDevice=false；macOS 15 则 API 不可用，报告 requires macOS 26。提高 SDK/系统版本不能让这块 GPU 支持 MetalFX 时域/补帧。

已下载的 `0e99984` 环境记录：macOS 26.6.2（25G83）、Xcode 26.6（17F113）、SDK 26.5；macOS 15.7.9（24G830）、显式选择 Xcode 26.3（17C529）、SDK 26.2。原始 OS、SDK、框架头文件和 source SHA-256 随产物上传。

| 项目 | 本轮结论 |
| --- | --- |
| 完整 Temporal/FG 实际图像序列、遮挡/解遮挡/细叶/透明/手部质量 | 需要能通过相应 supportsDevice 的 GPU；当前未执行，不使用历史 M1 Pro 报告冒充 |
| 源帧 FPS、生成帧 FPS、真实 Minecraft CPU/GPU 对照 | 未测 Temporal/FG 收益；不拿 native batch 微基准换算 |
| WindowServer/物理显示时序、生成/真实帧先后、刷新节拍与输入延迟 | 当前云端证据不覆盖，未宣称通过 |
| 第一人称独立 previous HUD projection/model-view | 工程上尚未支持；已有明确质量保护与插值拒绝，不能把它称为纯硬件限制 |
| 任意 mod/material、透明/粒子全生产者的精确运动 | 仅有列出的 staged/着色器/收据合同证据；未证明完整游戏覆盖，保守拒绝仍保留 |
| Metal 4 实际执行 | 编译与能力门控通过，当前 GPU 未执行 |

复核入口是 .github/workflows/metalfx-contracts.yml、.github/workflows/minecraft-client-e2e.yml 与 metal-capabilities；产物保留期为 14 天，源代码与固定提交/运行链接用于后续复现。此报告不授权合并或发布。

