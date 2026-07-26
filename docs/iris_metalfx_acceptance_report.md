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
| **B2-2 转译前端:真实光影包全程序转译矩阵** | `metalIrisShaderTranslationTest` **96/96 stage 全过**(2026-07-27):BSL 10.1.3(24 程序 52 stage,含 shadowcomp compute)+ Potato(22 程序 44 stage),链路=Iris ShaderPack 装载器→TransformPatcher→`MetalIrisShaderCompiler`(loose-uniform std140 收拢+敌意标识符重命名)→shaderc→SPIRV-Cross MSL→**真机 MTLLibrary 编译**。矩阵与迭代记录见 validation §L2 |
| **B2-1 地形编译链 + 唤醒线(离线 GPU + 真机客户端装载)** | 离线:`metalIrisShaderTranslationTest` 新增 `MetalIrisSodiumTerrainTest`,BSL+Potato × solid/cutout/translucent **6/6 创建出有效 PSO**(`isValid()`),链路=patchSodium→pair-link→合成 RenderPipeline→**库存编译链**(vanilla `GlslCompiler`→`IntermediaryShaderModule.rebind`→SPIRV-Cross)→真机 PSO;并断言整张绑定表每个资源都能被解析、`gbufferModelView` 真的写进了 std140 块的正确偏移。真机客户端(2026-07-27,BSL 10.1.3 `enableShaders=true`):语义层激活→`Profile: HIGH` 解析→`Using shaderpack: bsl-shaders.zip`→三个 kind 全部转译→`semantic pipeline generation 1 online`,到标题画面 0 崩溃、管线创建后无 ERROR |
| pack 安装+启用共存 | **冒烟 C 通过**(2026-07-27):BSL 入 shaderpacks + iris.properties 启用,Metal 29s 进世界、90s 存活、0 崩溃、dormant 正常、哨兵健康 |

### 仅完成接口/静态代码、未运行验证

- `IrisMetal*` 框架与 Iris 本体的对接(B2 缝合面替换)——**未开始编码**,仅休眠垫片。
- render 阶段的 SSBO/storage-image 绑定(compute 侧已验证;render 侧属 B2)。

### 未完成(阶段一硬门槛缺口)

1. **Iris composite/final pass 执行**:未实现。属 B2-3;B2-1 的显示语义是 colortex0 直落主帧缓冲、画面=原始 gbuffer0。**无进展。**
2. **Sodium 世界几何走 Iris shader**:**部分达成,未验证执行**。编译路径与供给路径均已落地并有离线 GPU 证据(见下表 B2-1 行),但**地形绘制期是否真的命中覆盖 PSO 未验证**——需要进世界看 `compiling terrain override` 日志。判定维持未达成。
3. **shader pack reload / 开关光影生命周期**:**部分达成**。注册表 teardown 已清 `MetalDevice` 管线缓存(否则 reload 后仍用旧 pack 的 PSO),`MetalWorldRenderingPipeline.destroy()` 走通;**但没做 reload 实测**(F3+R / 切换光影包 / 关光影)。判定维持未达成。
4. **≥1 光影包真实 Minecraft 运行验证(渲染语义)**:**部分达成**。2026-07-27 真实客户端已验证到「装载→解析→转译→合成管线上线」全绿(见下表 B2-1 行),这比冒烟 C 的「共存」前进了一整段;但**没有进世界,渲染语义仍未验证**。判定维持未达成。
5. ~~Iris 风格 shader 转译专项测试未编写~~ → **已完成并全绿**(2026-07-27,`metalIrisShaderTranslationTest` 96/96,见上表)。残余边界(转译≠执行):stage 间 varying location 按名配对与显式注入、uniform 值供给、采样器绑定表、DRAWBUFFERS→MRT 落位,均属 B2-3 PSO 链接/执行期工作。

### 环境限制(非实现问题)

- 无(本环境可跑真实客户端;上述缺口均为实现进度,不是环境不可为)。

### 已知问题(本分支如实记录,非本分支引入)

- `minecraftMetalFxClientValidation` 红:基线内 15:26 会话的 CUTOUT 验证改造半成品(captures 9/8、moving-entity 运动指标收紧)。属 MetalFX 线,master 树并行会话在修;阶段一不动其语义。其中一处硬崩溃(reactiveTexture 缺 RENDER_ATTACHMENT usage → Metal 校验中止)已在本分支根因修复。

### 结论

阶段一硬门槛 12 项中 **8 项达成、4 项未达成**(上表)。2026-07-27 增量:B2-1 把缺口 2/3/4 各推进到**部分达成**——编译链与 uniform/采样器供给已落地并有离线 GPU 证据,真机客户端已验证到 pack 装载与转译上线。**但计数不变,4 项仍全部未达成**:三项都卡在同一件事——**没有进世界**,因此地形绘制是否命中覆盖、画面是否出现 pack 着色、reload 生命周期是否正确,全部未验证;缺口 1(composite/final)无进展。**判定:不通过。** 按任务书纪律,阶段二不启动;后续工作聚焦 B2 缝合面(见下一步清单)。

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
69f75cb iris-b2: Sodium 0.9.1 + Iris dep + dormancy shims + smokes A/B7
(已提交) iris-b2-2: real-pack translation front-end (96/96) + smoke C + perf audit
933a1ab B2-1: sodium 地形编译链(6/6 PSO)+ Iris 唤醒线(默认关)
abe5ba8 B2-1 S4+S6a: uniform 供给 + pass 资源 fallback;语义层默认打开
9538341 B2-1: 打通游戏内 pack 装载线;BSL 在真实客户端被解析并转译
8eaab09 docs: 与集成分支的冲突面 + 同步层边界
```

## 下一步(优先级序)

1. **B2-1 世界几何**:`MetalDevice` 管线覆盖钩子(等价 `GlDevice.getOrCompilePipeline` mixin 机制)+ Iris `ShaderMap/IrisPipelines` 查表接通,先让 gbuffers_terrain 单程序点亮(Sodium terrain solid;转译前端已就绪,缺 PSO 链接期:varying 按名配对+显式 location、uniform 供给、绑定表)。
2. **B2-3 composite/final**:`CompositeRenderer` 语义挂到 `IrisMetalRenderTargets`(转译产物→PSO→全屏 pass 执行),自制确定性验证包 + `minecraftIrisClientValidation` L3 任务;同步落地性能审计 §1.1 的按管线 fragment-stage fence 精化(composite 链的前置性能项)。
3. **B2-4 生命周期**:reload/开关光影/维度切换在 Iris 层的资源重建。
4. 性能:先落 `metal_performance_audit.md` §5 计数器,再按测量结果实施 §1.2(blit encoder 合并)/§2.2(draw 循环去字符串键)。
5. (阶段一通过后)阶段二按 plan §3:插入点验证 → TemporalSceneProvider → 低分辨率 → jitter/motion → FG 前置。
