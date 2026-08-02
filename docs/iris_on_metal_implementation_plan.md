# Iris-on-Metal + MetalFX 详细实现规划

状态:**规划文档**(本文所有条目均为计划,不代表已实现;完成状态只在 `iris_metalfx_acceptance_report.md` 中宣告)
日期:2026-07-26
工作树:`MetalUniversal-iris`,分支 `iris-on-metal`,基线 `ea2dfd4`
配套文档:
- `iris_metalfx_implementation_audit.md` — 工作树核验审计(已建,持续更新)
- `iris_on_metal_architecture.md` — as-built 架构(实现落地后撰写)
- `iris_metalfx_validation.md` — 验证记录(命令、退出码、证据路径)
- `iris_metalfx_acceptance_report.md` — 验收报告(阶段一/二分别判定)

---

## 0. 总原则(任务书纪律的落地口径)

1. **严格两阶段**:阶段一 Iris-on-Metal 未过硬性验收门槛前,不动 MetalFX 功能面(现有 MetalFX 代码只允许"保持可构建"级别的适配性修改)。
2. **先读后写**:所有对 Iris 行为的假设必须以 Iris 1.11.2+26.2-fabric 真实 jar 的反编译审计为准(功能矩阵见 §2),不凭历史版本记忆。
3. **三层验证金字塔**,逐层留证据(命令+退出码+产物路径写入 validation 文档):
   - L1 静态/构建:`compileJava`/`compileTestJava`/`test`/`buildMacNative`;
   - L2 独立 GPU 测试:Java→FFM→Swift 真实链路 + GPU readback(扩展 `MetalMrtBackendIntegrationTest` 模式),接入 `check`;
   - L3 Minecraft 全链路:`MetalValidationClient` 模式的零输入自动化客户端运行(quickPlay 固定世界、定帧场景、attachment readback 断言、自动退出)。
4. **不降标准拿绿**:不删测试、不屏蔽错误、不硬编码样例;根因修复;CPU readback 只用于测试断言,不进正式渲染路径。
5. **资源所有权/线程/生命周期**:新增每个接口都在代码注释与架构文档中写明 owner、线程约束、销毁时机。
6. 构建环境固定为:`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`(Homebrew OpenJDK 25.0.2)+ Gradle 9.4.1 + Loom 1.16.3 + Swift 6.3.3/Xcode 26.6。

## 1. 现状基线(已核验,详见 audit 文档)

- MC 26.2 Blaze3D = 可插拔 `GpuBackend` SPI(官方 GL/Vulkan 双后端);metallum 以 `MetalBackend implements GpuBackend` + `MetalDevice implements GpuDeviceBackend` 接管,shader 链 = Mojang `GlslCompiler`(GLSL→SPIR-V)→ LWJGL Spvc(SPIR-V→MSL)→ 运行时 MSL 编译。
- MRT 后端静态贯通且有 E2E GPU 测试(1/2/3/8 attachment、null slot、逐槽 blend/write mask、三类 fail-closed),本会话复跑通过。
- Java 层 `mtl/` **无 compute encoder/pipeline**;bridge 无 compute/dispatch/storage image/barrier ABI;Iris 所需的通用计算能力三层全缺。
- `MetalValidationClient` + `minecraftMetalFxClientValidation` 已证明本环境可跑真实 Minecraft 自动化验证(今日 17:31 8/8 captures 通过)。
- 仓库零 Iris 代码;Iris 1.11.2+26.2-fabric 在 Modrinth 可得(配套 Sodium 0.9.1;当前 pin 0.9.0,升级决策见 §3.1)。
- MetalFX 现状(阶段二输入):Spatial 可用候选;Temporal 仅相机运动;object motion producer 未接;FG fail-closed;最新 CAMetalDisplayLink presenter 未编译验收且有 P0 缺陷(present(atTime:) 违约、shutdown 死锁)。

## 2. 阶段一:Iris-on-Metal

### 2.1 Iris 功能矩阵(任务书要求的第一步)

方法:对真实 jar 做类/常量池审计(后台已在进行),输出矩阵表填入 audit 文档附录 `iris-matrix`,列:

```
Iris 功能 | OpenGL 调用或抽象 | 当前 Metal 后端状态 | 需改 Java 层 | 需改 bridge 层 | 需改 Swift/Metal 层 | 验证方式
```

至少覆盖任务书列出的:GlFramebuffer、RenderTargets、BufferFlipper、CompositeRenderer、FinalPassRenderer、ShadowRenderer、ShadowCompositeRenderer、ComputeProgram、Program、ProgramSamplers、ProgramImages、ShaderStorageBufferHolder、IrisRenderSystem、ExtendedShader、Sodium shader override、shader pack reload、render target resize。追踪真实调用链与资源所有权,不只看类名。

### 2.2 集成架构决策 —— 已定:形态 B(2026-07-26,依据 `iris-audit/iris-1.11.2-mc26.2-surface.md`)

jar 审计裁定:Iris 26.2 是 **GL 渲染器**(自建 FBO/program、~200 裸 GL 入口、硬转型 `blaze3d.opengl.*`、glsl-transformer→glShaderSource 零 SPIR-V),抽象层只当分配器用。形态 A 不成立。

**采用「Iris 语义层」分步策略**(边界与矩阵见 `iris-audit/feature-matrix.md`):

- **B0 底座(先行,与 Iris 解耦)**:补齐 Metal 后端通用能力——compute pipeline/dispatch(含 indirect)、SSBO(usage+绑定种类+Spvc 反射)、storage image、compare sampler、blit generateMipmaps、MRT 验证矩阵补全(4-attach/非连续/depth+MRT/resize)——全部走 Java→FFM→Swift 真实链路 + GPU 内容级测试。**无论集成走到哪一步,这些都是必要且可独立验收的。**
- **B1 框架层**:`com.metallum.client.iris.*` 实现 Iris 语义等价物:IrisMetalFramebuffer(drawBuffers 映射→RenderPassDescriptor)、IrisMetalRenderTargets + BufferFlipper(main/alt ping-pong、flip 快照、resize/reload 复位)、depthtex/shadowtex 管理、CompositeRenderer 骨架(pass 序列+mipmap+compute 钩子)、IrisMetalProgram(pack GLSL→GlslCompiler→Spvc→PSO,uniform location 语义映射)。每项配内容级 GPU 测试(不依赖 Iris 在场)。
- **B2 接入**:Sodium 0.9.0→0.9.1(先全量回归 metallum 现有 mixin/L1-L3)+ 引入 Iris 依赖;**兼容垫片**让 Iris 在 Metal 上先按「不支持后端」安全停用(等价其 vulkan 分支,游戏可启动可进世界);随后逐步放行:替换 `IrisRenderSystem`/`GlStateManager` 缝合面到 B1 框架、以 `MetalDevice` 管线覆盖钩子等价 `GlDevice.getOrCompilePipeline` 机制、shadow/composite/final 逐段点亮。第一版收敛到单一测试光影包全链路正确。
  - 状态 2026-07-27:垫片+共存已达成(B7/C 冒烟);**B2-2 转译前端已完成并全绿**——`MetalIrisShaderCompiler`(TransformPatcher→std140 收拢→shaderc→Spvc→真机 MTLLibrary)对 BSL/Potato 主世界 96/96 stage 通过(`metalIrisShaderTranslationTest`,validation §L2)。残余=PSO 链接期(varying 按名配对、uniform 供给、绑定表、DRAWBUFFERS 落位)→ 属 B2-1/B2-3。
- **B3 全链路验收**:`minecraftIrisClientValidation`(自制确定性光影包 + 定帧 readback 断言)。

> 诚实边界:B2 的「逐步放行」是长周期工程;每个会话末在验收报告中如实区分「已完成/已验证/未验证/未完成」,阶段一硬门槛(§2.9)未全绿即判「不通过」。

### 2.3 后端能力补齐(两种形态都需要)

按依赖序实施,每项都带 L2 GPU 测试:

1. **MRT 验证矩阵补全**(现有套件缺口):
   - 4 attachment 用例;非连续逻辑 draw buffer 映射(如 0,2,5 → 语义等价 Iris `/* DRAWBUFFERS:025 */`);depth+MRT 组合;resize 后重建(同一逻辑 framebuffer 换尺寸重建并复验内容);clear/load/store 全矩阵。
2. **Depth/stencil 完整性**:
   - depth 格式(DEPTH32_FLOAT、DEPTH24/32+STENCIL 按 GpuFormat 实际枚举)、depth-only pass、depth copy(`copyTextureToTexture` 深度路径)、compare sampler(shadow sampler:`MTLSamplerDescriptor.compareFunction`)、stencil 读写掩码(若 Iris 触发)。
3. **Compute 全链路**(三层新增):
   - Java:`mtl/MTLComputeCommandEncoder`、`mtl/MTLComputePipelineState`;`MetalCommandEncoder.createComputePass()` 或按 Blaze3D 26.2 的 compute 抽象(以 javap 结果对齐 Mojang API 命名);
   - bridge:`metallum_MTLCommandBuffer_makeComputeCommandEncoder`、`metallum_MTLDevice_makeComputePipelineState`、`setComputePipelineState/setBuffer/setTexture/dispatchThreadgroups(+indirect)`;
   - Swift:对应 @_cdecl 实现;
   - SPIR-V→MSL:compute stage 编译路径(GlslCompiler 支持 compute 的话直通;否则走 Spvc compute);workgroup size 从 SPIR-V 反射;
   - 测试:absolute/relative dispatch、write-to-SSBO、write-to-image、compute→render、render→compute、compute→compute。
4. **SSBO**:
   - `MetalGpuBuffer` usage 扩展(storage 读写);binding index 稳定映射(Spvc 资源 rebind 与 render 路径同机制);生命周期/销毁;
   - 测试:SSBO 写后读(compute 写 → fragment 读;fragment 写 → readback)。
5. **Image load/store(storage texture)**:
   - `MTLTextureUsage.shaderWrite` 暴露;view 格式匹配;read/write access 与 stage visibility;
   - 测试:imageStore→sample、imageLoad 校验。
6. **同步/barrier 语义**(设计文档化,不机械翻译 GL barrier bits):
   - 默认策略:同 encoder 内靠 Metal 自动 hazard tracking(现资源默认 tracked;确认 `MTLHazardTrackingMode` 使用);跨 encoder 靠 encoder 边界;必要处 `memoryBarrier(scope:)`/`MTLFence`;
   - 交付一张「GL barrier bit → 本后端语义」表(任务书要求),写入架构文档;
   - 测试:compute 写→draw 读、draw 写→compute 读、SSBO 写后读、mipmap 生成前后。
7. **Mipmap 生成**:blit encoder `generateMipmaps` ABI + Java 封装 + 测试(采样各 mip 层断言)。
8. **纹理/采样杂项**:整数纹理格式按需(矩阵定);`texelFetch`/texture array 若 Iris 用到;sampler LOD/anisotropy 已有,补 compare。

### 2.4 Iris render target 框架(ping-pong / depthtex / shadow)

形态 A 下这些由 Iris 自己管理、我们保证底层原语正确;形态 B 下由 `com.metallum.client.iris` 实现等价物。无论哪种形态,都交付 L2 内容级测试(不依赖屏幕观察):

1. **colortex ping-pong**:main/alt 两套纹理;pass 读 main 写 alt / 读 alt 写 main;explicit flip;pre-flip;`flippedAtLeastOnce`;flip 快照驱动 framebuffer 重建;reload/resize 复位;同 pass 禁止读写同一底层纹理(断言+测试);必要 hazard 处理。
   - 测试:三个连续全屏 pass,各写入可判别常量,逐 pass readback main/alt 断言绑定关系与内容(含 flip 与不 flip 两分支)。
2. **depthtex 语义**:depthtex0(主)、depthtex1(不含半透明前快照)、depthtex2(不含手前快照);复制时机语义(opaque 后/translucent 前/hand 前)与 `preserveWorldDepthBeforeHandInternal` 现有机制对齐复用;
   - 测试:渲染不同深度的两个面,断言三个 depthtex 在复制点后内容互异且符合语义。
3. **shadow targets**:shadowtex0/1(depth,含/不含半透明)、shadowcolor0/1;shadow pass 与主 pass 状态隔离;shadow resize(光影包配置驱动);depth compare sampler 采样路径。
   - 测试:正交投影渲染遮挡体到 shadow depth,compare sampler 在主 pass 侧采样断言阴影判定;shadowcolor 写读断言。

### 2.5 Shader translation(Iris 路径)

- 复核链路:Iris patched GLSL →(GlslCompiler)SPIR-V →(Spvc)MSL → PSO。
- 必须验证:fragment output location 显式化(现有 `EXPLICIT_FRAGMENT_OUTPUT_PATTERN` 机制对 Iris 生成的 GLSL 是否成立)、MRT、uniform block、sampler、image、SSBO、texture array、integer texture、depth texture、shadow sampler(`sampler2DShadow`)、vertex attribute、`gl_FragDepth`、compute、宏/option 注入、include 展开(Iris 侧完成)、reload、编译错误回传(带 Iris program 名与行号)。
- binding 稳定性:资源 binding 由 Spvc rebind 显式分配,禁止依赖声明顺序;为 Iris program 增加「binding 布局快照」调试输出(`METALLUM_MRT_ABI_DEBUG` 同款开关)。
- L2 测试:用代表性 Iris 风格 GLSL(含 DRAWBUFFERS 语义的多输出、shadow sampler、uniform 集)离线编译到 MSL 并 GPU 执行断言。

### 2.6 Iris + Sodium 接入

- 依赖:`modImplementation "maven.modrinth:iris:1.11.2+26.2-fabric"`;Sodium 是否随升 0.9.1 以 Iris 的 fabric.mod.json 依赖区间为准(若区间允许 0.9.0 则不动,减少 mixin 风险;若必须 0.9.1,先跑现有全部 L1-L3 回归再继续)。
- 接入点(以矩阵为准细化):
  - backend 探测/能力门禁:若 Iris 有 "GL only"/backend 白名单检查,用 mixin 放行 Metal 并如实上报能力;
  - Sodium terrain override:确认 Iris 的 chunk shader 替换在 `VK_INDIRECT` 路径上如何挂接(`ShaderChunkRenderer`/`DefaultChunkRenderer` 已有 metallum mixin,注意共存顺序);
  - 覆盖对象:terrain solid/cutout/translucent、entities、block entities、particles、weather、sky、hand、lines、glint、text、shadow variants ——逐项在矩阵中标注「走 Iris program / 走 vanilla program / 未覆盖」。
- **不允许**只让 fullscreen composite 工作而世界几何不走 Iris program。

### 2.7 生命周期

覆盖并测试(L3 场景 + 定向单测):首次进世界、退出、重进、切维度、resize、全屏切换、Retina scale 变化、shader pack reload(F3+R / 屏幕操作等价入口)、开关光影、resource reload、pipeline cache 失效、纹理/缓冲销毁、device 不可用可控失败、fallback 到无光影路径。尺寸/格式变化后禁止复用旧资源(签名校验 + 断言)。

### 2.8 阶段一验证计划

- **L1**:`compileJava compileTestJava test buildMacNative`(每次提交前);
- **L2**(新增/扩展,全部接入 `check` 的 macOS 分支):
  - `metalMrtBackendIntegrationTest`(扩:4-attach、非连续映射、depth+MRT、resize、clear/load/store 矩阵)
  - `metalIrisTargetsIntegrationTest`(新:ping-pong/depthtex/shadow 内容级)
  - `metalComputeSsboImageIntegrationTest`(新:compute/SSBO/image/barrier/mipmap)
  - `metalIrisShaderTranslationTest`(新:Iris 风格 GLSL→MSL→GPU)
- **L3**(新 gradle 任务 `minecraftIrisClientValidation`,复用 `MetalValidationClient` 模式):
  - 固定世界(复用 run/saves/New World)+ 固定相机/时间/天气;
  - 启动时安装**自制确定性测试光影包**(见下),Iris API 激活;
  - 定帧 readback:各 colortex(断言互异、非全黑/非 NaN、ping-pong 关系)、depthtex0/1/2、shadowtex0、composite/final 输出、resize 前后、reload 前后;
  - 结构化日志 + run-state.json,pass/fail 退出码;
  - 二级对照:BSL(关 TAA)与 Potato 各跑一次冒烟(能加载、若干帧非黑、无 crash),不作为验收门槛,结果如实记录。
- **测试光影包** `metallum-iris-validation`(自制,src/test 资源):gbuffers_terrain 写 colortex0/1/2(可判别常量+MRT)、shadow pass、composite 读 shadowtex/colortex 写 colortex0、final 加确定性偏移;含 shadow、MRT、多 composite pass,满足任务书"简单、无 TAA、标准语义"要求,断言值全部可预计算。

### 2.9 阶段一硬性验收门槛(原样承接任务书)

工作树可构建;Java+Swift 可编译;MRT 全链路测试过;ping-pong 内容验证过;depthtex 语义有测试;shadow targets 有测试;compute/image/SSBO 至少后端 smoke;composite/final 可执行;Sodium 世界几何走 Iris shader;reload/resize 不崩溃;≥1 光影包真实/自动化 Minecraft 运行验证;文档记录证据;验收报告不把未验证标成完成。
—— 全部满足才进阶段二;任一不满足,验收报告写「不通过」并停在 Iris 阻塞项。

## 3. 阶段二:MetalFX(仅阶段一通过后)

> 本节为预规划;开工前先对照阶段一实际形态修订。

1. **插入点**(验证实际调用序,不按类名推断):Iris final → 世界场景色彩 → MetalFX Temporal Upscaling → 原生分辨率 GUI → present;GUI/HUD/字体不进 upscaler;色彩空间一致性(Iris final 输出 vs MetalFX 输入)。
2. **TemporalSceneProvider 协议**(显式接口,MetalFxManager 不再猜测目标):sceneColor/sceneDepth/motion/reactive/exposure/jitter/frameTiming/`shaderPackOwnsTemporalAA()`/resetHistory(reason);双帧矩阵、输入输出尺寸、frame index、delta time、target presentation time、GUI 分离状态。
3. **外部 TAA 模式**:选定目标光影包(候选 BSL:TAA 与 jitter 有配置项),记录其 TAA 配置项/宏/pass 归属/历史缓冲依赖;关其 TAA/upscaler/TAA-sharpening/jitter,保留光照/阴影/SSR/volumetrics/tonemap;MetalFX 独占 temporal accumulation 与 projection jitter。不宣称通用支持。
4. **低分辨率 Iris 世界渲染**:0.50/0.67/0.75/1.00 宽高比例;覆盖 gbuffer/screen-space targets/deferred/composite/final/depth/motion/reactive;shadow map 分辨率仍由光影包控制;GUI 原生分辨率。
5. **Jitter 唯一所有权**:序列管理、当前/上帧 jitter、投影注入、motion 去 jitter、reset/resize/传送/FOV 突变重启;禁止双 jitter、GUI jitter、未补偿 motion。
6. **运动向量管线**(相机+terrain+实体+粒子/天气/云/半透明/手/portal/glint/sky/screen-space);无可靠 motion 的像素进 reactive/disocclusion,不得静默零向量;输出约定(单位/方向/jitter/Y 轴/分辨率基准/格式/无效值/clear)数值测试。
7. **Reactive mask**:粒子/水/半透明/云/天气/portal/glint/alpha blend/emissive/SSR/volumetrics/reset 区域,分级强度;不替代 motion。
8. **MetalFX Temporal Upscaling 收尾**:descriptor、exposure、reset、encode 顺序、resize、模式切换(Off/Spatial/Temporal camera-only debug/Temporal full motion,debug 明确标注非完成态)、graceful fallback。
9. **历史重置矩阵**(任务书 16 项场景)+ reset 原因记录。
10. **Frame Interpolation**:启用前置条件(对象 motion 接通、GUI 分离、真实 presentation timeline、presenter P0 修复:去 present(atTime:)、shutdown 状态机 running→draining→stopping→stopped、保存 targetTimestamp deadline、pending update 限 1、去逐帧 NSLog);插值帧不推进 simulation/不改 Iris history/不触发 world render;条件不满足保持 fail-closed 并输出诊断。
11. **显示时间线**:CAMetalDisplayLink、targetPresentationTimestamp/presentedTime、frame pacing、60/120Hz、resize/fullscreen/inactive,不用 CPU 提交时间冒充 presented time。
12. **阶段二验证**:数值(motion 方向/尺度/jitter 补偿/深度重建/无效 motion)、GPU(temporal I/O、reset、格式、顺序、GUI 分离、interpolated output、timeline)、Minecraft 场景矩阵(任务书 21 场景);无法自动判画质的项保存原始输入/输出/相邻帧/插值帧供人工复核,不以"没崩溃"作画质验收。

## 4. 里程碑与执行顺序

```
M0 侦察汇合:功能矩阵 + 后端缺口表 + 运行 runbook(后台审计中)
M1 形态决策 + 矩阵落盘(audit 附录)                    ← 阶段一
M2 后端能力补齐(§2.3)+ L2 测试全绿
M3 target 框架(§2.4)+ L2 内容级测试全绿
M4 Iris+Sodium 接入(§2.6)+ 能启动进世界
M5 L3 自动化全链路(自制包)通过 + 生命周期矩阵
M6 阶段一验收报告 → 判定
M7+ 阶段二(仅 M6 通过):插入点验证 → provider 协议 → 低分辨率 →
    jitter/motion → upscaling 验收 → (最后)FG 前置条件与 presenter 修复
```

提交纪律:每个里程碑至少一个 commit;commit message 记录验证命令与结果;不可构建状态不提交。

## 5. 风险与环境限制(当前已知)

1. **Iris 26.2 内部形态未知**(矩阵进行中)——形态 B 将显著放大工作量,第一版按单包收敛。
2. **Sodium 0.9.0 vs 0.9.1**:升级可能破坏现有 5 个 sodium mixin;以 Iris 依赖区间定,升级则全量回归。
3. **compute/SSBO 的 SPIR-V→MSL 细节**(atomic、shared memory、workgroup 反射)可能踩 Spvc 边角;以测试驱动逐个击破。
4. **L3 依赖有屏客户端**:本机可跑(已证);若失败,按任务书写明环境限制,不降级宣称。
5. **真实光影包兼容性**(BSL/Potato)不作为阶段一验收门槛,防止范围失控;结果如实记录。
6. MetalFX presenter 的 P0 修复属于阶段二;阶段一期间 FG 保持 fail-closed,不受影响。

## 6. 交付物清单(阶段一)

- 代码:后端能力补齐(§2.3)+ target 框架(§2.4)+ Iris 接入(§2.6)+ L2/L3 测试与 gradle 任务
- 测试光影包:`metallum-iris-validation`
- 文档:audit(更新)、本规划(维护)、architecture(as-built)、validation(证据)、acceptance(判定)
- git:iris-on-metal 分支上的里程碑提交序列
