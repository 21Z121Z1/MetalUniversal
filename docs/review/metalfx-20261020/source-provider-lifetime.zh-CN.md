# Shader provider 来源与关闭边界

源码语义必须绑定到实际编译读取及其 library owner；相同 Identifier、后来的 ResourceManager 查询和另一个 generation 的参考匹配均不能替代它。本文不发放完整 producer、Temporal/FG 画质或分支收敛准入。

## 指纹读取更正

LWJGL 的 `ShadercIncludeResult.content()` 按 NUL 扫描；固定 Minecraft 的 `CachedIncludeSource.create` 使用 `memUTF8(text,false)` 并另存 `content_length`。旧诊断可能读过声明内容，其 include 差异不能作为资源替换或 use-after-free 证据。此前“差异仅来自内部回放”和“Minecraft include 已发生变化”的推断均撤回。旧原文保留以供审计，不作为准入依据。

生产读取现在直接读取 CONTENT 指针，先检查声明长度与 1 MiB 上限，不先调用 NUL getter。独立尾部非零哨兵证明旧 getter 会多读，而修正只读声明内容；空、负及超预算长度保留拒绝控制。依据见 [LWJGL 官方实现](https://raw.githubusercontent.com/LWJGL/lwjgl3/master/modules/lwjgl/shaderc/src/generated/java/org/lwjgl/util/shaderc/ShadercIncludeResult.java)，实际固定依赖另有执行证据。

修正后的 `7ddec752` 两条真实 FX 客户端，在主场景及四份云场景的全量程序／变体中均没有 Minecraft 参考差异。主场景 Vanilla 的根／include 读取为 130／496，Sodium 为 118／463；它们是累计编译观测，不是像素数或某帧完整覆盖。未审查的 Metallum／Sodium shader 没有被白名单放行。该 SHA 当时仍有一个原生测试的返回类型错误，不能称为整轮通过。

## 所有权实现

- `preloadUiShader` 的独立 fallback cache 只准备自己的 provider，不替换设备当前完整库。完整库仅在 ShaderManager 成功 apply 后发布。
- 每个真实 `ShaderManager$Configs` 在构造完成时取得一个 lifetime；关闭与原生 include 释放由同一个 owner 管理。生成 shader 借用 fallback lifetime，关闭生成 provider 不关闭 fallback。
- 编译任务持有跨越整个 shaderc 调用的读租约，包括 getInclude 返回之后对指针的使用。close 请求等待租约释放，随后只释放一次；失败与关闭后迟到任务仍必须完成自己的 Future。
- 编译输入、canonical 匹配和 generation 随自己的 compiled pipeline 保存；异步完成不能借用当前库的证明。80 项固定 client 参考检查 raw／精确展开内容及 import 依赖；实际读取不一致仍拒绝。
- 发布新库会退役 backend 自有 lazy pipeline cache；前端与后端继续各自释放自己拥有的编译对象，设备不擅自关闭前端 source owner。

固定字节码的 PipelineCache.close 会先关闭编译对象再关闭 source；Configs.close 释放 include map；CachedIncludeSource.close 释放 source-name、content 和结果 struct，保留 Java record 不保留其原生内存。

## 当前验证范围

`27fa89224d582de2730f635d6ba3bb7269772909` 的双 macOS 合同各 164 项及 hosted/ABBA 已通过，包括 canonical raw library 和实际 native include callback。此前重复编译控制的两个平台各执行 8 次编译、16 次 callback，124 字节内容在消费前后保持相同。它们仍不替代真实 Configs/reload 关闭并发。

该 head 的九条客户端均在独立 harness 编译阶段因局部变量与 lambda 参数重名失败，尚未执行关闭并发。修正 `888694bbaa27691b649ecf4b0002ed2a76bffb88` 已在 [Java 25 独立预检](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39536445433/job/119014085106) 实际通过 harness `compileJava`、`compileTestJava` 和有界 native include 控制；准确 head 的原生与九客户端验收另行进行。E2E 的 Ubuntu 前置编译关卡保留后续全部 macOS 运行断言。

实际客户端控制要求真实 Configs 在 shaderc 借用 include 时请求关闭，释放借用后原 owner 关闭、reload 发布另一 canonical generation，并观察新 generation 的真实 source draw。任何一步失败都不能以先前 raw-library 测试代替。

## 待验证门禁与恢复

源码变更／关闭后的 motion history 失效、Temporal 模式回退及 reload 历史 reset 仍是待验证增量。[适配到 888694bb 的 WIP 原文](https://api.github.com/repos/21Z121Z1/MetalUniversal/git/tags/d0c35adf1755ae0367c1443a163c1d85ae01b263) 已在独立 detached worktree 应用并从远端逐字验证，`doNotPromote=true`。它不再直接套用旧 552a8f17 快照；新增负例要求同 ID 根／include 替换不能借用 motion 语义、一个 owner 的拒绝不污染另一个 owner、退役 source 不能复用已捕获历史。语法检查不等于 Java 25 类型、原生或客户端验收。

[20 个分支头的持久恢复原文](https://api.github.com/repos/21Z121Z1/MetalUniversal/git/tags/98526c910a7b2cab6105b49859c93439e5ec5f03) 包含准确 head、独有／等价提交、持久 tag 映射、bundle 校验和、fresh clone／fsck 命令及原始日志。candidate 为 888694bb，`writersStopped=false`、`deletionReady=false`；main 与原分支继续保留。

完整 producer／前帧可见 ownership、混合 world/HUD 投影、通用 UI 输出范围与完整写覆盖、HDR/tone-map 仍有独立软件门禁。支持硬件的 Temporal/FG 画质、显示节拍及延迟另列验收，不由 hosted Spatial 或 raw Metal 通过代替。
