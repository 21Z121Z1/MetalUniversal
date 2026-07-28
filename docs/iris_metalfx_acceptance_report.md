# Iris + MetalFX 验收报告

日期:2026-07-26–28(持续审计)
分支:`iris-on-metal`(worktree `MetalUniversal-iris`;已合入 `fork/master` 2026-07-28 最新基线)
判定口径:任务书阶段一/阶段二硬性门槛;未验证一律不标完成。

---

## 阶段一:Iris-on-Metal —— **不通过**(基础设施验收通过,集成未完成)

### 2026-07-28 主线合入就绪审计

- `fork/master` 已合入 `iris-on-metal`,Git 无文本冲突;合后在 Homebrew OpenJDK 25.0.2 上运行
  `test metalIrisShaderTranslationTest metalIrisTargetsIntegrationTest metalMrtBackendIntegrationTest
  metalComputeBackendIntegrationTest buildMacNative --no-daemon`,**BUILD SUCCESSFUL**。
- 真实 pack 离线门继续全绿:BSL 52/52 stage + Potato 44/44 stage;terrain
  solid/cutout/translucent 6/6 PSO 创建成功。
- 真实客户端证据已证明 BSL solid/cutout 的 terrain override 会在进世界后编译并绑定;
  但该轮最终以 SIGABRT(134) 退出,且没有截图/持续帧证据,不能等价为渲染语义验收。
- S6b 只完成了「扩展附件决策按 generation 冻结」的预编译竞态修复;生产 terrain pass
  尚未连接多 DRAWBUFFERS 附件,扩展槽错序保护也未实现。
- composite/final 执行仍未实现;reload GUI 矩阵(退世界、重进、关/开光影、切维度、换 pack)
  仍无真实客户端验收。
- 为保证主线安全,`metallum.iris.semantic` 改为**默认 false**;完整实验路径仍可通过
  `-Dmetallum.iris.semantic=true` 或 `runClientAll` 显式开启。

**合入判定**:可作为「默认休眠、显式 opt-in 的实验性 Iris 基础」合入主线;
不可对外声称「Iris 光影完整支持」,也不可默认开启语义层。

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

### 仅完成接口/框架、未连入完整运行链

- `IrisMetalRenderTargets` / ping-pong / depthtex / shadow 框架有内容级 GPU 测试,但尚未被
  `MetalWorldRenderingPipeline` 的真实 terrain/composite/final 阶段持有并调度。
- render 阶段的 SSBO/storage-image 绑定(compute 侧已验证;render 侧属 B2)。

### 未完成(阶段一硬门槛缺口)

1. **Iris composite/final pass 执行**:未实现。属 B2-3;B2-1 的显示语义是 colortex0 直落主帧缓冲、画面=原始 gbuffer0。**无进展。**
2. **Sodium 世界几何走 Iris shader**:**路由已证明**。真实客户端进世界后出现
   `compiling terrain override SOLID/CUTOUT`,placeholder/uniform 供给也实际执行,无 missing binding。
   但该证据只覆盖 BSL 的单附件 solid/cutout;S6b 未完成的多附件 kind 仍 fail-open 走原生管线。
3. **shader pack reload / 开关光影生命周期**:**部分达成**。teardown、cache generation、重复 activate
   均有自动化回归;**真实 GUI/reload 矩阵未跑**。
4. **≥1 光影包真实 Minecraft 运行验证(渲染语义)**:**部分达成**。BSL 已真实装载、转译、
   进世界并命中 terrain override;但无截图对照/持续帧证据,该轮最终 SIGABRT(134),因此渲染语义仍未通过。
5. ~~Iris 风格 shader 转译专项测试未编写~~ → **已完成并全绿**(2026-07-27,`metalIrisShaderTranslationTest` 96/96,见上表)。残余边界(转译≠执行):stage 间 varying location 按名配对与显式注入、uniform 值供给、采样器绑定表、DRAWBUFFERS→MRT 落位,均属 B2-3 PSO 链接/执行期工作。

### 环境限制(非实现问题)

- 无(本环境可跑真实客户端;上述缺口均为实现进度,不是环境不可为)。

### 已知问题(本分支如实记录,非本分支引入)

- `minecraftMetalFxClientValidation` 红:基线内 15:26 会话的 CUTOUT 验证改造半成品(captures 9/8、moving-entity 运动指标收紧)。属 MetalFX 线,master 树并行会话在修;阶段一不动其语义。其中一处硬崩溃(reactiveTexture 缺 RENDER_ATTACHMENT usage → Metal 校验中止)已在本分支根因修复。

### 结论

阶段一硬门槛 12 项中 **9 项达成、3 项未达成**。Sodium 几何路由已由真实客户端日志证明;
仍缺 composite/final 执行、真实 reload/resize GUI 生命周期、以及至少一个 pack 的稳定可见渲染语义验收。
**判定:不通过。** 当前只具备实验性、默认休眠形态的主线合入条件。

---

## 阶段二:Iris × MetalFX 集成 —— **未启动**

- `fork/master` 已含完整 MetalFX/Metal 4 产品路径;本节指 Iris final 输出与 Temporal/FG 的组合集成。
- 当前 TEMPORAL 会用 `metallum:pipeline/terrain_cutout_reactive` 替换 Sodium CUTOUT,使 Iris CUTOUT override 被绕过;
  目前只有一次性告警,没有共存实现。
- 在 Iris 阶段一通过前,`runClientAll` 仅用于手动诊断,不构成产品验收。

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

1. **S6b terrain 多附件**:实际创建 DRAWBUFFERS 附件、扩展槽顺序自检、与 MetalFX cutout
   coverage 的 per-generation 互斥决策;保持不在 draw 期分配资源。
2. **B2-3 composite/final**:`CompositeRenderer` 语义挂到 `IrisMetalRenderTargets`,加确定性
   `minecraftIrisClientValidation` readback 门。
3. **B2-4 真实生命周期**:进世界→退标题→重进→关/开光影→切维度→换 pack;
   验证 generation 递增、PSO 重编、旧 GPU 资源退休。
4. **真实可见验收**:固定相机下 pack on/off 截图与 GPU readback,至少 90s 持续帧无 abort。
5. 上述全绿后才将 `metallum.iris.semantic` 默认值改为 true,再开始 Iris final → MetalFX 组合验收。
