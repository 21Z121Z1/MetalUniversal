# Iris + MetalFX 实现审计(工作树核验版)

日期:2026-07-26(本会话)
工作树:`MetalUniversal-iris`(git worktree,分支 `iris-on-metal`,基线 commit `ea2dfd4`)
基线来源:`MetalUniversal-master` 工作树快照(原压缩包解包内容;`/mnt/data/MinecraftMetal(1).zip` 在本机不存在,实际内容已解包于 `~/Documents/Projects/Active/MinecraftMetal/`)。

> 本文档只记录**对当前工作树重新核验过**的结论。rollout 与旧文档中的历史结论一律标注来源,不作为当前事实。
> 前次(今天更早)的 MetalFX 专项审查见仓库外的 `MinecraftMetal_MetalFX_Audit_2026-07-26.md`;其中与本工作树仍一致的结论在下文引用时标注〔前审计〕。

## 0. 版本与 git 状态

- `MetalUniversal-master/.git` 原本存在但 **0 commit**(全部 untracked)。本会话创建了基线提交 `ea2dfd4`(快照全部源码;`build/`、`run/`、natives dylib 按 `.gitignore` 排除),并建立 worktree `MetalUniversal-iris` + 分支 `iris-on-metal`。
- `metallum-master/`(基线对照)与 `game-porting-toolkit-main/`(Apple GPTK 4 示例/技能)无 git 元数据。
- 5 份 Codex rollout JSONL 在项目根目录,时间跨度 2026-07-26 01:25 → 12:56。

## 1. 可构建性(已核验)

- JDK:`build.gradle` 要求 `options.release = 25`;PATH 上的 `java` 是 Oracle 24,但 **Homebrew `openjdk@25`(25.0.2)存在**,先前构建产物 classfile major=69(Java 25)证明历史构建即用它。
- 核验命令(基线树,缓存热):
  ```
  JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
    ./gradlew compileJava compileTestJava test buildMacNative
  → BUILD SUCCESSFUL(6s;:test 执行通过;buildMacNative UP-TO-DATE)
  ```
- Gradle 9.4.1,Fabric Loom 1.16.3,Minecraft 26.2,fabric-loader 0.19.3,Sodium `mc26.2-0.9.0`(modrinth maven)。网络可用(modrinth API 可达)。
- Swift 工具链:Swift 6.3.3 / Xcode 26.6(macOS 26.5)。`glslangValidator`/`spirv-cross` CLI 未安装——**不需要**:GLSL→SPIR-V 走 MC 26.2 自带 `com.mojang.blaze3d.vulkan.glsl.GlslCompiler`(shaderc),SPIR-V→MSL 走 LWJGL Spvc(`MetalCrossShaderCompiler.java`)。
- 结论:**当前工作树可构建,Java 单测通过。**

## 2. 架构与桥接(已核验要点)

- MC 26.2 Blaze3D 已抽象为 `GpuDevice/GpuDeviceBackend + CommandEncoder + RenderPass`(Vulkan 取向,自带 GLSL→SPIR-V)。metallum 用 `MetalDevice implements GpuDeviceBackend`(`MetalDevice.java:33`)接管整个设备:
  - shader 链:`GlslCompiler.createIntermediary`(SPIR-V)→ `MetalCrossShaderCompiler.compile` → LWJGL `Spvc`(SPIR-V→MSL,显式 fragment output location、资源 rebind)→ `metallum_create_shader_function`(运行时 MSL 编译)。
  - 桥接:Java FFM(`MetalNativeBridge.java`)→ `libmetallum.dylib`(`MetallumNative.swift`,4642 行,`buildMacNative` 用 swiftc 编译)。
  - 后端选择:`PreferredGraphicsApiMixin` / `SodiumPreferredGraphicsApiMixin`(细节见 §backend-coverage 附录)。
- 设备信息:`MetalDevice.buildDeviceInfo` 宣告 `maxColorAttachments = ColorTargetState.MAX_COLOR_TARGETS`(8);`DeviceFeatures(false,false,true,true,true,false,true)` 各位含义待附录确认(与 Iris 能力门禁直接相关)。

## 3. Minecraft 全链路验证基础设施(已核验,可复用于 Iris)

- `MetalValidationClient`(`com.metallum.client.validation`)是**零输入**验证驱动:`metallum.validation.enabled` 开启后,以帧计数驱动 8 个场景(静止/动实体/动相机/遮挡/揭示/GUI/reset),控制 ArmorStand 与相机,经 `MetalFxManager.setValidationFrame` 请求 GPU attachment readback,74 帧内完成 8 次 capture 校验后 `minecraft.stop()`,写 `run-state.json`/`frame-state.json`。
- Gradle 任务 `minecraftMetalFxClientValidation` = `runClient` + `--quickPlaySingleplayer "New World"` + validation 系统属性。
- `run/logs/latest.log`(今天 17:31)证明:**该验证真实跑通过 8/8 GPU captures 并自动退出**。世界存档 `run/saves/New World` 已存在(`run/` 不进 git;worktree 中需要时从 master 复制)。
- 结论:本环境**具备真实 Minecraft 客户端自动化运行验证能力**——阶段一的 Iris 全链路验证按同一模式构建(加载测试光影包 → 定帧 readback Iris attachment → 断言非全黑/非 NaN/target 互异/resize 行为 → 自动退出)。

## 4. 与 Iris 相关的后端现状(初判,附录核验中)

- **仓库内(src/docs/build.gradle)没有任何 Iris 相关代码或依赖**(grep "iris" 零命中)。Iris 支持完全从零开始。
- Modrinth 存在 **Iris 1.11.2+26.2-fabric**(version id `oaD6KQls`,project `YL57xq9U`),changelog 注明"updated to Sodium 0.9.1"(当前项目 pin Sodium 0.9.0——兼容范围以 Iris jar 的 fabric.mod.json 为准,见附录 iris-matrix)。
- MRT:Java `MetalCommandEncoder`/`MetalCompiledRenderPipeline`/`MetalRenderPass` + FFM v2 ABI(≤8 indexed slots)静态贯通〔前审计,本树待复验〕;`metalMrtBackendIntegrationTest` 是 Java→FFM→Swift 的**真实 E2E** GPU readback 套件(与前审计所述"smoke 绕过后端"不同,该任务在 build.gradle 中依赖 buildMacNative 并跑真实链路——本会话将复跑确认)。
- Compute/image/SSBO:Java 层 `mtl/` 包**没有** compute encoder/pipeline 类;"Compute" 仅出现在 `MetallumNative.swift`(MetalFX 内部 motion compute)。Iris 所需的通用 compute/dispatch/storage/barrier 能力在 Java↔bridge↔Swift 三层均缺失(附录 backend-coverage 逐条核验)。
- MetalFX / 帧生成现状〔前审计,与本树一致性待复验〕:Spatial 可用候选;Temporal 仅相机运动;object motion producer 未接;FG fail-closed(`OBJECT_MOTION_PRODUCER_CONNECTED=false`);最新 `CAMetalDisplayLink` presenter 含 P0 缺陷且未编译验收。**阶段一期间不动 MetalFX 功能面**(任务书纪律)。

## 4.1 后端选择与 Sodium 路径(已核验)

- MC 26.2 官方内置 `GlBackend`(`com.mojang.blaze3d.opengl`)与 `VulkanBackend`(`com.mojang.blaze3d.vulkan`),按 `PreferredGraphicsApi.getBackendsToTry` 选择。`PreferredGraphicsApiMixin` 把 DEFAULT 改为 `[Metal, Vulkan, GL]`(`PreferredGraphicsApiMixin.java:22`)。
- Sodium 0.9.0 的 `DrawBackend.chooseBackend` 被 `DrawBackendMixin` 拦截:backend 名为 "Metal" 时强制 `VK_INDIRECT` —— Sodium 地形绘制走设备无关的 indirect 路径,metallum 已支撑。
- **推论(待 Iris jar 证实)**:Iris 26.2 若能跑在官方 Vulkan 后端上,则其为后端无关实现,Metal 适配=补齐后端能力缺口;若 GL-only,则需要 GL 语义层。以 jar 审计为准。

## 4.2 现有 MRT E2E 回执(本会话已复跑)

- `./gradlew metalMrtBackendIntegrationTest` → **BUILD SUCCESSFUL**(2026-07-26 18:36,本机 AGX G13X)。
- 该套件从 Mojang `RenderPassDescriptor` 出发,穿过生产 `MetalCommandEncoder`/pipeline metadata/FFM arrays/Swift indexed ABI,GPU readback 断言。覆盖:1/2 attachment、混合格式 3 attachment(RGBA8+RG16F+R8)、null 中间槽、8 attachment、逐槽 clear/load/store + blend + write mask、legacy 单attachment ABI、pipeline/render-pass 签名错配 fail-closed、fragment location/format 错配 fail-closed、5 连续提交回调。
- 规格矩阵仍缺:**4 attachment 用例、depth+MRT、resize 后重建、非连续逻辑 draw buffer 映射**(现 null-slot 用例是其子集)——阶段一补齐。
- 日志中 3 条 `uint4 ... not compatible` 是 fail-closed 用例的**预期**诊断输出,非错误。

## 4.3 Iris 1.11.2+26.2 真实调用面(已核验,详见附录 iris-1.11.2-mc26.2-surface.md)

- **形态 B 确认**:Iris 26.2 是 OpenGL 渲染器 —— 自建 GL FBO(`GlFramebuffer`)与 GL program(jcpp+glsl-transformer→`glShaderSource`,**零 SPIR-V**);24 个类调用 ~200 个裸 GL 入口(DSA/compute/SSBO/image/`glClipControl`/per-buffer blend);硬转型/子类化 `com.mojang.blaze3d.opengl.*`(`GlDevice.getOrCompilePipeline` mixin、`ExtendedShader extends GlProgram`、`GlTexture.glId`);抽象层仅用于资源分配(`GpuDevice.createTexture` 等)后经注入的 `iris$getGlId()` 取回 GL id。
- **后端 gating**:`IrisMixinPlugin` 只识别 options.txt 里的 "vulkan" 子串(命中则整体自禁并提示);对 "metal"/default 无感知 → GL mixin 全量应用,在 Metal 后端上必然崩溃。接入初期需要兼容垫片让其安全停用,语义层就绪后逐步放行。
- **Sodium**:声明 `0.9.x`,**二进制要求 0.9.1**(`MixinUniformData` shadow 字段、`DefaultChunkRenderer.render` 参数 `GpuBuffer→GpuBufferSlice`、`MultiDrawBatch` API 变化);升级需回归 metallum 的 5 个 sodium mixin。
- 功能矩阵(任务书要求的 20 项逐条:调用面→现状→需改层→验证方式)已落盘:`docs/iris-audit/feature-matrix.md`。

## 5. rollout 记录(已完成,详见附录 runbook.md)

- 实际存在 **6 个**会话:项目目录内 5 份 + `~/.codex/sessions/2026/07/26/rollout-2026-07-26T15-26-02-*.jsonl`(最后会话,15:26–18:24)。最后会话完成了 MRT E2E/offscreen/presentation/Minecraft 客户端验证 harness(17:31 8/8 PASS),随后 CUTOUT reactive 修复做到一半按用户要求停手,交接文档:`docs/handoffs/metalfx-cutout-reactive-handoff-2026-07-26.md`。
- 交接警告「最后一次 MetalFxManager 编辑未编译」——本会话已核验:基线树 compileJava/test **通过**,风险解除;CUTOUT 后续(帧 74/82 场景、captures 8→10)属 MetalFX 线,阶段一不动。
- 12:56 会话因本地代理 503 连环中断(presenter 改造中);无 Iris 实现尝试(全部 rollout 仅 1 处 breaks 声明命中)。
- 历史 JDK 为 /tmp 下 Temurin 25(易失);本会话改用 Homebrew openjdk@25(已验证等效)。全部命令/环境陷阱(含 `MTL_SHADER_VALIDATION=1` 全局开启会让 Apple MetalFX kernel 在本机中止)见 runbook。

## 6. 审计问题速答(任务书 9 问)

1. 工作树可构建?——**是**(§1,已核验)。
2. 桥接方式?——Java FFM downcall → `libmetallum.dylib`(Swift)。无 JNI。iOS 路径另有 spvc dylib 打包逻辑(`buildIOSSpvc`)。
3. RenderPass/PSO 数据流?——`RenderPipeline`(Blaze3D)→ SPIR-V → MSL + `MetalCompiledRenderPipeline`(per-slot format/blend/writeMask)→ PSO 缓存;`MetalCommandEncoder.createRenderPass(textureViews[], clears[])` → FFM v2 → `MTLRenderPassDescriptor`。细节与逐槽核验见附录。
4. MRT 是否贯通?——静态贯通 + `metalMrtBackendIntegrationTest` E2E(本会话将复跑给出回执);Minecraft 内实际使用面(哪些 pass 用 >1 attachment)待查。
5. compute/image/SSBO/barrier/mipmap/depth/sampler/framebuffer 支持?——compute/image/SSBO/barrier:**缺失**(Java/bridge 层无 API);mipmap/depth/sampler/copy:部分存在,逐项见附录。
6. MetalFX 位置/输入/输出/生命周期/缺口?——见〔前审计〕§4-§9;阶段一不改动。
7. 运动向量覆盖?——仅相机重建;对象运动 producer 缺失〔前审计,与源码一致〕。
8. display link / presentation 状态?——最新 presenter 未编译验收、含 P0(present(atTime:) 违约、shutdown 死锁)〔前审计〕;FG fail-closed,阶段一不触碰。
9. rollout 未完成/失败/重复实现?——见 §5 与附录 rollout-mining。

## 7. 阶段一执行基线(本会话决定)

1. 以真实 **Iris 1.11.2+26.2-fabric jar 反编译审计**为唯一功能矩阵依据(不凭记忆假设其 GL/Blaze3D 使用面)。
2. 后端能力补齐顺序:MRT 验证矩阵扩展 → ping-pong/depthtex/shadow 框架 → compute/image/SSBO/barrier → shader 转译 Iris 特有需求(MRT 输出 location、binding 稳定性)。全部走 Java→FFM→Swift 真实链路 + GPU readback 测试,并接入 `check`。
3. 全链路验证:扩展 `MetalValidationClient` 模式,Iris + 测试光影包 + 固定世界/相机,readback colortex/depthtex/shadowtex 断言。
4. MetalFX(阶段二)在阶段一验收通过前**不动**;现有 fail-closed 门禁保持。

---

## 附录(已落盘)

- `iris-audit/backend-coverage.md` —— Blaze3D 26.2 抽象面全量枚举 × metallum 实现覆盖逐条核验(vanilla 无 compute/SSBO/image/barrier/GPU mipmap/compare sampler;metallum 除 2 个 multiDraw 变体外全覆盖;hazard=untracked+全局 MTLFence 链;Iris-ready 缺口总表)
- `iris-audit/iris-1.11.2-mc26.2-surface.md` —— Iris 真实 jar 调用面(三层使用、关键类 API、七问答案、Sodium 0.9.1 证据、程序集清单)
- `iris-audit/feature-matrix.md` —— 任务书要求的 20 项功能矩阵(功能→调用→现状→需改层→验证)
- `iris-audit/runbook.md` —— 构建/运行命令、JDK、Metal 验证环境陷阱、L3 机制、交接要点、会话时间线
