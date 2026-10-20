# MetalFX 时域、帧生成与运动向量专项审计

审计起点：`97e53a76592963f7ee866e28fd222798abe798a1`，Minecraft 26.3；主线核查为 `9930a2e3ae9d54756f79693c1e8c60704975372c`。本轮先完成下列官方资料、源码和现状核查，再实现修复。上一轮原生索引批调用及六项独立回退不属于本轮修改对象。

## 现状与证据边界

| 链路 | 起点源码现状 | 本轮需要验证的缺口 |
| --- | --- | --- |
| 相机运动 | `MetalFxManager.prepareSceneProjectionInternal` 保存未抖动的当前／前帧 VP，使用实际抖动 VP 的逆矩阵重建深度；Swift 三个 camera/fused kernel 输出 current→previous | camera kernel 对 clip.w 使用绝对值，可能接受位于上一相机后方的点；用独立数值预期和实际 GPU 复现 |
| 单位与坐标 | RG16F 存 NDC 差，X=prev.x-current.x，Y=current.y-prev.y；Temporal scale=输入颜色宽高/2，FG scale=前帧颜色宽高/2（不是 depth/motion 分配尺寸）；JOML→FFM→simd 均按列 | 非方形和奇数分辨率、X/Y、jitter-only、旋转、非法矩阵与大运动；不能拿 UV 差的 scale=width 直接替换 |
| 深度 | Minecraft reversed Z，清零表示远端；world depth 在第一人称清深度前保存；融合 kernel 做深度边界和前帧深度拒绝 | 同时覆盖 standard/reversed Z 的遮挡判断、无历史、天空、反遮挡；深度重投影比较仍需区别相机空间变化与真实遮挡 |
| 动态／蒙皮 | `MetalPreviousVertexHistory` 按成功提交源帧保存实际 CPU posed position，匹配对象 generation、pipeline、格式、拓扑、顶点数及完整 draw manifest；并非只有刚体根矩阵 | 单测和着色器编译已有覆盖，旧报告“完全没有逐顶点 producer”已过时；当前提交没有完整真实游戏 GPU 收据矩阵 |
| 粒子／透明／自定义 | 有 staged particle、shared、shadow、piston 与辅助 draw carrier；不匹配历史时通过 eligibility／receipt 拒绝插值，Temporal 使用 reactive 或 fail closed | 不能将 reactive-only 记为精确运动；对未观察到与不支持必须分别报告 |
| 第一人称 | 已有上一帧 staged position 与独立 R8 exact-validity；缺少手部有效历史时零相机锁定向量只作为 Temporal 保护，不能证明摆动已支持 | GPU 验证手部遮住世界对象时不误用世界 validity；exact／fallback／非法 hand motion 分别验收 |
| 历史重置 | resize、render/display 尺寸、FOV、teleport、world/resetData、设置变化、命令失败等已有入口；FG GUI 暂停／恢复已有状态 | Temporal 暂停／恢复与窗口隐藏转换需要明确重置，不应依赖 FG 已开启；需要边沿事件测试 |
| Temporal | color/depth/motion/reactive→native output；GUI 后绘制；普通配置默认 OFF | `metalFxOffscreenValidation` 禁止 hosted CI，且先要求 FG 支持，Temporal-only GPU 无法通过此入口单独验收 |
| 帧生成 | macOS 26 MetalFX interpolator，source stamp、ownership reducer、原生呈现线程及 scaler link；同一源帧的 jitter/depth/motion/UI | 独立判断设备支持；合成与呈现、生成帧与真实帧、CPU 与 GPU 时间分别报告；显示器节拍不能由离屏读回证明 |
| 现有 CI | PR #90 上一轮全部检查现在已成功；hosted raw Metal、GPU 合同与六组游戏客户端是旧目标的证据 | 现有工作流仅编译 MetalFX offscreen/performance/presentation，不能写成当前 MetalFX 已运行通过 |

旧报告所述 M1 Pro 8/8、16/16 和插值 PSNR 属于历史运行，不用于本轮精确提交的验收。

## Apple 官方专项参考

读取日期：2026-10-19。官方网页／DocC JSON 的实际声明与 runner SDK 头文件共同约束实现；不将“Apple Silicon”或系统版本当成设备支持结论。

| 官方来源 | 核查结论／采用方式 |
| --- | --- |
| [MetalFX 概览](https://developer.apple.com/documentation/metalfx)、[Temporal descriptor](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerdescriptor) | Temporal macOS 13+；通过 supportsDevice 和支持的 content scale 查询，不能根据 import 成功决定运行 |
| [WWDC22 10103：Boost performance with MetalFX Upscaling](https://developer.apple.com/videos/play/wwdc2022/10103/) | 抖动颜色、深度、运动、历史输入；使用固定序列和独立图像预期验证，不能只看一张锐化截图 |
| [官方 Temporal 示例](https://developer.apple.com/documentation/metalfx/applying-temporal-antialiasing-and-upscaling-using-metalfx) | `Renderer/Shaders.metal` 先去 jitter，再 `prevUV-UV`；`AAPLRenderer.swift` 的 scale=renderWidth/Height 对应 UV 单位。本项目 NDC 单位应继续除以二。官方 ZIP SHA-256：`009d527d228f2c18135350f17bbe2f62c7fc43ff3b1dd43ea40c0f19e8fcc6f0`，内含 Apple 2022 MIT 式许可；只做设计对照 |
| [WWDC25 211：Go further with Metal 4 games](https://developer.apple.com/videos/play/wwdc2025/211/) | Interpolate 在 tone map 之后；两张真实颜色、depth、motion，descriptor 可关联 upscaler；单独合成 UI 并正确调度生成／真实帧。不能用光流或相机近似代替完整生产者 |
| [FrameInterpolator descriptor](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatordescriptor)、[base](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase) | macOS 26+，独立 supportsDevice；deltaTime 单位是秒；前帧颜色来自上一 encode；OS 和 SDK 存在接口不等于当前 GPU 支持 |
| [isUITextureComposited](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase/isuitexturecomposited) | true 表示 uiTexture 是 UI 与 scene 的预合成；不是“任意透明 UI texture”。需按实际 UI 纹理内容核查，不能只看属性名字 |
| [worldToViewMatrix](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase/worldtoviewmatrix) | 当前官方声明从 macOS 27 起；本轮 macOS 26 ABI 不添加此字段，不用文档最新页冒充 runner SDK 可用 API |
| [WWDC26 359：Build real-time neural rendering pipelines with Metal](https://developer.apple.com/videos/play/wwdc2026/359/) | motion 是当前像素到前帧的屏幕位移，须去除 jitter。该 session 的 denoising／材质输入部分不是本项目 FrameInterpolator 的输入要求 |

官方 DocC 的个别继承属性 availability 显示基础 MTLTexture 的版本，因此 API 支持以所属 descriptor、SDK 头文件和运行时 supportsDevice 为准。`isUISupported` 查询返回 404，未将其虚构为 API。

## GitHub 专项源码对照

本轮另行执行 MetalFX temporal／frame interpolation／Minecraft MetalFX／motion／sample 仓库查询及 MTLFXFrameInterpolatorDescriptor、motionVectorScaleX/jitterOffsetX 等代码查询。以下固定到实际读取提交，未引入外部源文件或执行外部项目。

| 固定源码 | 许可及可借鉴设计 | 不适用部分 |
| --- | --- | --- |
| [Godot 81ab961f](https://github.com/godotengine/godot/blob/81ab961f91066a63bcbf3ec340962bb1a1ada558/servers/rendering/renderer_rd/effects/metal_fx.cpp) | MIT；Temporal 作为 renderer callback，明确 texture 读写用途、结束当前 encoder、设置 fence、保留 scaler 到命令生命周期结束 | 独立引擎的 motion 单位／资源图不能直接替换 RenderPearl 合同 |
| [MCUpscaler defa601f](https://github.com/ConnRaus/MCUpscaler/blob/defa601f4f482f338e467dd87aed6499957fb828/src/native/mac/metalfx_bridge.m)、[HandMotion](https://github.com/ConnRaus/MCUpscaler/blob/defa601f4f482f338e467dd87aed6499957fb828/src/main/java/dev/mcupscaler/HandMotion.java)、[MotionBoxes](https://github.com/ConnRaus/MCUpscaler/blob/defa601f4f482f338e467dd87aed6499957fb828/src/main/java/dev/mcupscaler/MotionBoxes.java) | MIT；手部用自己的矩阵与历史，CPU frame block 和 native 时序可对照 | 实体 bounding box 和刚体矩阵近似不能证明蒙皮／粒子精确位移，不替代我们的 staged previous positions |
| [MetalQuake 732e2d69](https://github.com/aloysha976/MetalQuake/blob/732e2d6914649929bb9d922146bf0394fbe381a1/MetalEngine/mq_metalfx.m) | GPL-2.0；明确分开 jittered inverse、unjittered current/previous，nearest 深度及 reset | 使用像素单位／scale=1，不能直接套到本项目 NDC motion；不复制源码 |
| [SuperResolution ede68fac](https://github.com/IReallyWantToSleep/superresolution/blob/ede68fac7f7e18255a8caa1a33763b61f5c9b0ea/modules/common/src/main/resources/shader/interop/motion_vector_preprocess.comp.glsl) | GPL-3.0；预处理明确 texture 输入、坐标转换，适合作为方向和单位检查的对照 | 其余跨 API／FSR 后端不引入 native Metal 执行链 |
| [MetalDuck a72960d8](https://github.com/Nycolazs/MetalDuck/blob/a72960d87937df1aa34e1ce21a13c78dd1eceb74/Sources/MetalDuck/FrameGeneration/MetalFXFrameGenerationEngine.swift) | MIT；前帧尺寸、flow 历史 reset 与捕获帧对应可参考 | 名称含 MetalFX，但实际是 Vision optical flow + 自定义 warp／compose，不作为 Apple MTLFXFrameInterpolator 已集成的证据 |
| [MCMetal d771db0a](https://github.com/Goodbuilder34/MCMetal/blob/d771db0ad80cffe40be2dc7b41236f5bcb1ab420/src/native/pass.mm) | MIT；资源与 render-pass 边界对照 | 本次树检索未见 MetalFX／motion 实现，不以 README 泛称作时域参考 |
| [mc-metalfx d5b6051a](https://github.com/ruomox/mc-metalfx-1.20.1/tree/d5b6051ad6bc7eb09aea22cec5e174f5c61509f2) | MPL-2.0 | 当前 client 入口为空，不能当成完整时域或帧生成实现 |

另核查 Ciderlight、Vantage 的固定 HEAD 与上一轮相同，本轮树查询没有发现可直接复核的 MetalFX 专项实现；WickedEngine 的 MetalFX 搜索主要命中 C++ API 包装头，不能用包装声明证明运行集成。

## 搜索后选择的实施目标与验收清单

1. 增加 exact-head hosted MetalFX 能力和 SDK 合同证据，分别记录 raw Metal、spatial、Temporal、FrameInterpolator、Metal4FX；不支持项写明 blocked，而非质量通过。
2. 在无需 MetalFX 支持的 GPU 上实际执行 shipping motion kernel。先复现再修复；独立 CPU 数值预期覆盖静态、平移／旋转、Y、去 jitter、clip.w、非法向量、物体覆盖、hand exact／fallback、遮挡、reversed Z、透明 reactive、首次历史及多尺寸。导出原始纹理、可视化与逐例 JSON。
3. Temporal 和 FrameInterpolator 分开执行。有能力时运行原生离屏时域／插值对照；无 FG 不能阻止 Temporal 验收。保留严格完整验收入口，不能把 unsupported 写成 PASS。
4. 检查并补齐 pause/resume 和可见性边沿的历史失效；保留 world、传送、resize／scale、reload、首次启用、异常路径的已有 fail-closed 合同。按实际执行边界报告动态／蒙皮／粒子／透明／手部覆盖。
5. 运行当前提交的相关 Java 测试、生命周期 reducer、shipping 原生编译、GPU 数值合同与生产客户端回归；下载 Actions artifact 并查看图像。测试失败先定位，不删断言、不靠增加 timeout 或无限重跑。
6. 仅在相应硬件上测 Temporal／FG 质量和性能组合。CPU/GPU source frame time、真实渲染频率、生成呈现频率和延迟分开；没有显示器／相应 GPU 的项目保持未运行，不能用编译、API 或单帧代替。

运行命令、exact commit、Actions 链接、原始输出与剩余缺口由本轮最终验收报告补充；此研究文档本身不声称上述新验收已通过。默认 Temporal=OFF、FG=false、完整 producer gate=false；本轮没有证据前不晋升这些默认值。
