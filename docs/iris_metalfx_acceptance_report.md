# Iris + MetalFX 验收报告

日期:2026-07-26/27(本会话)
分支:`iris-on-metal`(worktree `MetalUniversal-iris`;基线 `ea2dfd4` = 原始工作树快照)
判定口径:任务书阶段一/阶段二硬性门槛;未验证一律不标完成。

---

## 阶段一:Iris-on-Metal —— **不通过**(基础设施验收通过,集成未完成)

### 已完成且已验证(GPU/运行时证据)

| 项 | 证据 |
|---|---|
| 工作树可构建;Java+Swift 可编译 | L1 全绿(validation 文档 §L1) |
| 通用 MRT 全链路 | `metalMrtBackendIntegrationTest` **14/14**:1/2/3/4/8 attachment、非连续 0/2/5、null 槽、逐槽 clear/load/store/blend/writeMask、depth+MRT 内容、resize 重建、3 类 fail-closed |
| compute/image/SSBO 后端(超出 smoke 要求) | `metalComputeBackendIntegrationTest` **10/10**:absolute/relative/indirect dispatch、SSBO 链、imageLoad/Store、render↔compute 顺序、GPU mipmap 内容、compare-sampler shadow 语义 |
| ping-pong 内容验证 | `metalIrisTargetsIntegrationTest` **6/6**:三 pass 双侧内容、snapshot/restore、feedback 守卫 |
| depthtex 语义 | 同套件:depthtex0/1/2 三元组 0.75/0.25/0.5 内容断言 |
| shadow targets | 同套件:shadowtex0/1 + shadowcolor + 主目标隔离 + resize |
| 同步/barrier 语义 | encoder-fence 链有序性测试(render→compute→render、compute→compute、indirect args);GL barrier bit 映射表见 architecture §2.4 |
| Sodium 0.9.1 升级 | L1+单测+**真实客户端冒烟 A**:Metal 后端进世界渲染 ~4 分钟无渲染异常(SIGTERM 收尾;唯一异常为已知离线鉴权 401 噪声) |
| Iris 1.11.2 引入+休眠垫片 | **冒烟 B7 通过**(2026-07-27):Metal 后端 + Sodium 0.9.1 + Iris 共存,28s 进世界,90s 持续渲染存活,0 崩溃标记。休眠面=7 处取消(onRenderSystemInit/duringRenderSystemInit/loadShaderpack/IrisRenderSystem.initRenderer+supportsSSBO/GLDebug×4/IrisSamplers.initRenderer/VanillaRenderingPipeline.beginLevelRendering)+ `_getInteger` 常量假接 + `iris$getGlId` 合成 id 覆写。迭代过程与三个 `<clinit>`/纹理钩子陷阱见 validation 文档 |

### 仅完成接口/静态代码、未运行验证

- `IrisMetal*` 框架与 Iris 本体的对接(B2 缝合面替换)——**未开始编码**,仅休眠垫片。
- render 阶段的 SSBO/storage-image 绑定(compute 侧已验证;render 侧属 B2)。

### 未完成(阶段一硬门槛缺口)

1. **Iris composite/final pass 执行**:未实现(Iris 在 Metal 上处于休眠模式,自身 GL 渲染链未被语义层替换)。
2. **Sodium 世界几何走 Iris shader**:未实现(同上;当前世界几何走 metallum 原生管线)。
3. **shader pack reload / 开关光影生命周期**:Iris 层未点亮,无从验证(后端层 resize/rebuild 有 L2 覆盖)。
4. **≥1 光影包真实 Minecraft 运行验证**:未达成(BSL/Potato 已预取,自制确定性验证包未编写)。
5. Iris 风格 shader 转译(DRAWBUFFERS 多输出、shadow sampler、uniform 集的 pack GLSL→MSL)专项测试未编写(通用 MRT/输出位置校验已有)。

### 环境限制(非实现问题)

- 无(本环境可跑真实客户端;上述缺口均为实现进度,不是环境不可为)。

### 已知问题(本分支如实记录,非本分支引入)

- `minecraftMetalFxClientValidation` 红:基线内 15:26 会话的 CUTOUT 验证改造半成品(captures 9/8、moving-entity 运动指标收紧)。属 MetalFX 线,master 树并行会话在修;阶段一不动其语义。其中一处硬崩溃(reactiveTexture 缺 RENDER_ATTACHMENT usage → Metal 校验中止)已在本分支根因修复。

### 结论

阶段一硬门槛 12 项中 8 项达成、4 项未达成(上表)。**判定:不通过。** 按任务书纪律,阶段二不启动;后续工作聚焦 B2 缝合面(见下一步清单)。

---

## 阶段二:MetalFX —— **未启动**(受阶段一门禁约束,符合任务书顺序)

- Temporal Upscaling:维持基线状态(相机运动候选;本分支零改动)。
- 运动向量覆盖:相机重建 + 实体捕获管线部分接线(基线状态);对象运动 producer 未接,`OBJECT_MOTION_PRODUCER_CONNECTED=false` 维持。
- Frame Interpolation:fail-closed 维持;presenter P0(present(atTime:) 违约、shutdown 死锁)未修(阶段二工作)。
- 显示时间线:基线状态(最新 CAMetalDisplayLink 源码未验收)。
- 默认启用策略:FG 关闭,Temporal 需显式 -D 属性,不变。

---

## 附:本分支提交序列

```
ea2dfd4 Baseline: MetalUniversal working tree snapshot (pre-Iris)
a3e9cf9 docs: audit + feature matrix + implementation plan
e41414d iris-b0: compute/SSBO/image/mipmap/compare-sampler backend (10/10)
a801057 iris-b0: MRT validation matrix gaps (14/14)
3535788 iris-b1: ping-pong/depthtex/shadow framework (6/6)
(进行中) iris-b2: Sodium 0.9.1 + Iris dep + dormancy shims + smokes
```

## 下一步(优先级序)

1. **B2-1 世界几何**:`MetalDevice` 管线覆盖钩子(等价 `GlDevice.getOrCompilePipeline` mixin 机制)+ Iris `ShaderMap/IrisPipelines` 查表接通,先让 gbuffers_terrain 单程序点亮(Sodium terrain solid)。
2. **B2-2 pack 装载**:Iris pack 解析结果(ProgramSource)→ GlslCompiler→Spvc→PSO 编译路径 + `metalIrisShaderTranslationTest`(DRAWBUFFERS/shadow sampler/uniform 集)。
3. **B2-3 composite/final**:`CompositeRenderer` 语义(IrisMetalCompositeRenderer 骨架已在 plan §2.4)挂到 `IrisMetalRenderTargets`,自制确定性验证包 + `minecraftIrisClientValidation` L3 任务。
4. **B2-4 生命周期**:reload/开关光影/维度切换在 Iris 层的资源重建。
5. (阶段一通过后)阶段二按 plan §3:插入点验证 → TemporalSceneProvider → 低分辨率 → jitter/motion → FG 前置。
