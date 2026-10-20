# MetalFX 官方契约与工程边界

本表用于逐项复核实现和独立预期，不把旧提交绿灯或接口存在当作当前运行证据。
运行记录、分支清单、准确 SHA、日志与制品索引保存在忽略的 `build/agent-evidence/`，不作为静态产品事实提交。

## 官方依据

- [Apple Temporal 示例](https://developer.apple.com/documentation/metalfx/applying-temporal-antialiasing-and-upscaling-using-metalfx)：去 jitter 的 previous UV−current UV；UV 向量按输入宽高缩放。本项目保留 NDC 单位，缩放因子为输入宽高的一半。样例固定版本与许可见[专项研究](metalfx-closure-study.zh-CN.md)。
- [WWDC22 10103](https://developer.apple.com/videos/play/wwdc2022/10103/)：采样抖动、颜色／深度／运动及历史输入；scaler 缓存随尺寸变化更新。
- [WWDC25 211](https://developer.apple.com/videos/play/wwdc2025/211/)：Temporal 输入／输出为线性颜色；插值在 tone mapping 之后，UI 可独立合成；生成帧先于相应真实帧呈现。动态输入、reactive 提示、相机参数和源帧间隔分别需要验证。
- [Temporal API](https://developer.apple.com/documentation/metalfx/mtlfxtemporalscalerbase)、[Frame Interpolator API](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase)：以 descriptor 的设备查询、实际 SDK 与纹理 usage 为准，不以 OS 名称推断能力。
- [FG motion scale](https://developer.apple.com/documentation/metalfx/mtlfxframeinterpolatorbase/motionvectorscalex)：实际 SDK `MTLFXFrameInterpolator.h` 将位移换算为 `prevColorTexture` 像素；不能沿用低分辨率 depth/motion 纹理尺寸。SDK 注释还明确 FOV 用角度、deltaTime 用秒；CI 保存头文件 SHA 与相关片段，空收据会失败。
- [CPU/GPU 同步官方样例](https://developer.apple.com/documentation/metal/synchronizing-cpu-and-gpu-work)：资源复用受 GPU 完成约束。
- [Metal Shading Language 规范](https://developer.apple.com/metal/Metal-Shading-Language-Specification.pdf)：窄化浮点转换与纹理写入具有舍入规则，不能直接假定 GPU RG16F 与 CPU nearest-even 相同。严格控制先用已知 R32F 输入同时输出 RG32F／RG16F，逐位识别实际转换模式；未识别或模式不一致即失败。

## 逐项对照

| 项目 | 实现与检查入口 | 工程边界／待验 |
| --- | --- | --- |
| 运动方向、单位、坐标 | `MetalMotionContract`、`MetalFxMath`、`motion_fragment.glsl`、`MetalFxFrameMotionContract`；Java／原生独立数值对照 | current→previous；NDC XY，Y 翻转；Temporal scale=输入颜色宽高/2，FG scale=前帧颜色宽高/2，depth/motion 降采样不改变 FG 位移单位；严格像素对齐控制按独立实测窄化模式及相邻 float32 输入的精确 half 位集合检查，逐像素零／反向负例；既有透视预算另保留 |
| jitter | `prepareSceneProjectionInternal`、源 draw 冻结 projection、V4 surface resolver | 当前反矩阵使用 raster jitter；对应向量不含 jitter；世界历史查找补偿前后 jitter，HUD 独立 |
| 前后帧深度 | RG32F(previous Z,current Z)、V4 world/HUD 双域历史、接受的 raster 状态 | 不用当前深度冒充动态表面 previous Z；前帧 bias 从旧屏幕 Jacobian 还原，D32 常量受限；像素对齐严格控制、独立 GPU 表面点／旧帧 D32 平面及错误当前坡度负例分别检查解析精度与任意姿态。无历史、未知 viewport、更大常量及退化映射保持无效所有权 |
| 动态分辨率 | `ensureTargets`、scaler key、历史 reset | 当前策略重新分配并 reset；尚未采用保留最大分配、逐帧 content size 改变的优化，不宣称无 reset 的动态历史 |
| 独立投影 | `MetalPreviousVertexReplay`、`MetalFirstPersonMotionGpuTest`、`ProjectionEncodingEvidence` | 手部实际 projection/model-view 与世界相机分开；普通及后处理 HUD 深度分别捕获。混合 world/HUD 的原始 Z 尚不能由单一 FG 相机／jitter 描述，准确手部几何也不解除这项准入 |
| 历史重置 | `MetalFxHistoryTransition`、source stamp、提交／失败回调 | 暂停、隐藏、尺寸、世界、传送、FOV、reload、设置与 GPU 失败；退役 epoch 不能覆盖新历史 |
| 遮挡 | V4 surface resolver 与 `MetalMotionContractValidation` | previous 表面 Z、前帧 HUD 遮挡、标准／反向 Z、中心遮挡、无效对应不得回退为相机运动 |
| 透明与特殊材质 | source draw 真实状态、材质 reactive 与 previous validity 分离 | 单一运动不能表示多层混合；未知 pipeline／格式仍拒绝，reactive 不算完整 FG 对应 |
| 粒子 | `MetalParticleBatchMotion`、粒子 ID、独立 Layer 事务；`MetalParticleQuadHistory` | 合并／拆分／重排按粒子 ID 对应；一个当前 draw 只能共享一致的旧投影、模型视图、材质／raster 状态和有效 uniform 输入。出生、发射数量变化及跨 draw 的歧义多发射保留无效顶点；实体／天气仍使用原有完整 manifest，失败提交不推进。任意自定义发射尚须实际入口证明 |
| 天气／环境 | `WeatherEffectRendererMetalFxMixin`、`MetalWeatherMotion`、`MetalWeatherCorrespondence`、`CloudRendererMetalFxMixin` | 雨雪按实际上传 XYZ／UV、随机漂移、列身份和 sampler 重建旧纹理表面点，片元拒绝旧范围外历史；源顶点冻结、雨雪分层并随源提交推进，关闭捕获期间的新上传也使旧准备数据失效。云按真实 signed CloudFaces／CloudInfo 保留原 GPU 程序化表达式，冻结源绑定并以完整 cell／face 身份推进；重建先失效当前 CPU 描述，捕获停用期间替换退役 generation。剩余环境及前帧可见 ownership 仍未证明，不解除完整 FG 准入 |
| 生物／方块实体 | staged CPU posed positions、所有者 generation、完整 draw manifest | 现有可审计模型已接入；未知／剩余渲染器、Iris／模组语义保持未证明 |
| 手部 | 独立 HUD 覆盖、透明手部 current Z、前帧手部表面 | 无源深度的透明手部也不归入世界；缺历史明确反遮挡；真实客户端摆动质量仍需支持硬件 |
| 线性颜色 | `MetalFxColorTransfer`：RGBA8 SDR→RGBA16F linear→Temporal→RGBA8 SDR | 显式 sRGB transfer 保留 alpha；独立 ramp 与 overshoot GPU 读回。未恢复 HDR 辐射、未迁移 tone map，不解除相应颜色门禁 |
| scaler／FG 关联 | `compatibleLinkedTemporalScaler` | 线性 RGBA16F scaler 输出与 SDR RGBA8 FG 输入不兼容时使用已有 standalone 路径；不能伪报 linked |
| 资源生命周期 | `MetalFxColorTransfer`、`closeAuxiliaryTextures`、reload／disable／close；原生 source ring；`RenderContractRuntime` 诊断 owner | 稳态复用；尺寸替换前退役提交；旧纹理、sampler 和 native residency 按已有 owner 释放；opt-in 记录操作与 start／close 共享锁；关闭前撤下 owner，终结写入失败也尝试两者清理、保留异常并维持失败门禁；GameTest 在世界断开之前结束记录 scope。manifest 逐条流式输出、按 UTF-8 计量并原子发布；预算失败保留明确失败的有界摘要，受限堆控制检查完整记录和负例。真实客户端仍需准确提交验证 |
| GPU 同步 | 公共 render pass、encoder fence、Metal4 barrier；present worker completion | 转换 pass 与 native scaler 处于同一公共编码事务；实际 Metal3/4 执行需按对应路径收据区分 |
| 插帧节奏与相机 | `MetalFrameGenerationLifecycle`、`MetalFramePacing`、`Perspective.fromProjection`、present worker | 从实际对称有限 reversed-Z 投影提取 FOV／near／far／aspect，拒绝猜测相机参数；保留真实源帧 deltaTime 秒、源提交身份、generated/real 顺序、丢帧／迟到回退；真实显示节拍和延迟需要物理显示器 |
| UI 合成 | `uiTarget` 与 scene 分离、`isUITextureComposited=false` | 完整 GUI 混合集合尚未证明 premultiplied；保持颜色／生产者准入失败关闭 |
| 能力与回退 | `MetalFxCapabilityProbe`、独立 Temporal／FG offscreen 检查 | raw Metal／Spatial 可用不等于 Temporal／FG；失败回退原始 fullscreen copy，默认 Temporal OFF、FG false |

## 硬件验收

支持的 Apple Silicon GPU 上还需执行：Temporal 收敛、细节／闪烁／拖影、透明／粒子／天气／生物／手部场景，原生分辨率对照；FG 快速转向、遮挡、UI、真实帧与生成帧顺序、显示间隔、输入延迟；源 CPU/GPU 帧时、真实渲染频率、生成呈现频率、功耗与热稳定性分别报告。托管编译、raw Metal 合同、Spatial 和读回都不替代这些结果。
