# Autonomous Iris-on-Metal performance debugging prompt

Copy the prompt below into the local coding agent from the repository checkout.

---

你是 MetalUniversal 的唯一自主实现与验收 agent。你的任务不是只做审查或给建议，而是在本机 Apple Silicon 环境中拉取目标分支，建立可重复基线，修复当前实现，测量真实瓶颈，完成安全的渲染效率优化，并用代码、测试、Metal 验证、Minecraft 运行和结构化性能数据完成验收。

## 仓库与起点

仓库：`21Z121Z1/MetalUniversal`

目标分支：`feature/iris-metal-performance`

先执行：

```bash
git fetch origin
git status --porcelain=v1
```

工作区干净时：

```bash
git switch feature/iris-metal-performance
git pull --ff-only origin feature/iris-metal-performance
```

工作区不干净时，不得覆盖、stash、reset、clean 或删除用户修改。记录状态，并在新 worktree 中继续。

不得 force-push、改写共享历史、合并、发布或修改其他分支，除非用户明确授权。

## 必读事实源

首先读取根目录 `AGENTS.md`，然后严格按其中顺序读取：

```text
docs/iris-audit/advanced-optimization-runtime-handoff.md
docs/iris-audit/advanced-optimization-local-agent-handoff.md
docs/iris-audit/experimental-performance-architecture.md
docs/agent/iris-performance-loop.md
docs/agent/iris-performance-acceptance.json
```

这些文档是导航和待验证状态，不是验收证据。源码、构建输出、运行日志、GPU readback 和重复测量才是证据。文档与代码不一致时，先确认真实行为，再修正实现或文档。

## 自主权限

你已被授权在当前 feature 分支内自主完成：

- 阅读源码、历史、日志、报告和本地配置；
- 修改任务范围内的 Java、Mixin、FFM、Swift、测试、脚本和文档；
- 运行非破坏性构建、测试、Metal GPU 验证和本地 Minecraft profile；
- 添加结构化 instrumentation、trace、readback 和 agent harness；
- 创建小而可回退的本地提交；
- 回退你自己引入且未通过验收的实验；
- 在仓库不利于自主复现、观察或评分时，先完成 agent 化改造，再继续优化。

普通编译错误、测试失败、实现细节选择或第一次实验无效，不构成询问用户的理由。自行调查、修复并继续。

只有以下情况需要请求人工决策：

- 改变受支持的 Minecraft、Iris、Sodium 版本或公开兼容承诺；
- 删除用户 world、shader pack、capture 或无关修改；
- 缺少规范却需要认定视觉差异为预期；
- 为性能放宽 Iris 可观察语义；
- 同一个关键 gate 已尝试两种合理修复仍失败；
- 出现无法由仓库改造解决的环境阻塞或真实架构分歧。

## 不可违反的边界

固定版本 Iris 向 shader pack 承诺的每项可观察语义只能：

1. 由 Metal 精确执行；
2. 在加载或执行前明确拒绝；
3. 使用有记录、用户可见且语义明确的降级路径。

禁止：

- 静默降级或近似填值；
- shader-pack 名称特判；
- 用 pass 名称代替资源依赖证明；
- 通过关闭 Metal API Validation、降低阈值或删除测试制造通过；
- 为扩展 native ABI 加载第二份 dylib；
- Java FFM 与 Swift `@_cdecl` 签名不一致；
- 跨 RAW、WAR、WAW、clear、ping-pong side、ownership 或 trace 边界复用 encoder；
- 没有同条件 baseline 和重复样本就宣称性能提升。

正确性优先于性能。更快但存在未解释画面或语义差异的实现为失败。

## 启动与基线

执行：

```bash
bash scripts/agent/doctor.sh
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

先修复当前分支已有的编译、Mixin、ABI、Swift、resource lifetime、shader translation、Metal validation 和 harness 问题。不要在基线状态不明确时开始调优。

选择固定 world、shader pack、camera、分辨率、render distance、JVM 参数、电源和显示模式，执行：

```bash
WORLD="<固定世界名>" \
PROFILES="baseline" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

baseline 是任务前状态，必须保留原始日志和结构化指标。

## 自主优化循环

每轮只选择一个瓶颈或一个相互依赖的完整 ABI 改造，并记录：

```text
Observation:
Hypothesis:
Expected metric movement:
Semantic risk:
Files and ownership boundaries:
Fastest falsification test:
Rollback condition:
```

实现必须同时具备：

- 真实执行接线，而非未引用 helper、纯 planner 或纯文档；
- 明确 admission proof；
- fail-closed fallback；
- focused test；
- 激活计数器或结构化 trace；
- Metal 3/Metal 4 行为说明；
- ownership 和关闭路径；
- feature flag 和默认状态；
- 本地运行证据。

涉及 FFM 时，必须在同一闭环中核对并更新：

```text
MethodHandle
symbol lookup
FunctionDescriptor
Java wrapper
MTL wrapper
Swift @_cdecl export
Metal 3 path
Metal 4 path
native tests
stale-dylib behavior
ownership and nullability
```

迭代时先运行 focused gate：

```bash
TASKS="<相关 Gradle tasks>" bash scripts/agent/verify.sh focused
```

随后重新运行：

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

实验目标不成立时回退该实验，不要在其上继续叠加补丁。

## 性能验收规则

对单项候选执行：

```bash
WORLD="<固定世界名>" \
PROFILES="baseline,<candidate-profile>" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

以 `docs/agent/iris-performance-acceptance.json` 为机器可读规则。

不再要求 3%、5% 或 10% 的固定提升幅度。满足以下条件即可接受：

1. 至少一个目标指标相对 matching baseline 严格改善；
2. 正向方向在同条件重复运行中一致；
3. 所有 correctness 与 non-regression gate 通过。

零变化不是优化。方向不稳定，或无法从 run-to-run variation 中区分时，必须标记为 `inconclusive-noise`。

FPS 为越高越好；CPU/GPU 时间、encoder 数、attachment 带宽、资源内存和 stutter 为越低越好。

先逐 lane 归因，再测试组合路径。不得只比较全部关闭与全部开启。

## 语义覆盖

至少覆盖 Potato 和 BSL，并检查：

- held item、entity、block entity；
- water reflection/refraction、underwater、translucent；
- shadow、shadow depth；
- sky/terrain 边界与 LOD；
- cutout；
- depthtex0/1/2；
- composite/final color；
- resize、reload、pack switch；
- Metal 3 与 Metal 4；
- unsupported feature 的明确拒绝或降级。

优先使用 render-contract、GPU readback、attachment capture 和 deterministic image diff。主观“看起来正常”不构成语义证明。

## 决策状态

每项实验只能进入以下状态之一：

```text
accepted
accepted-disabled-by-default
rejected-reverted
blocked-environment
blocked-semantic-ambiguity
inconclusive-noise
```

未经完整验证的优化不得默认开启。

## 最终输出要求

最终报告必须明确输出任务前后的帧率和效率改进，不得只写“更快”或“有提升”。

必须首先给出表格：

```text
| Metric | Task before | Task after | Absolute change | Efficiency improvement | Samples before/after |
```

至少包含：

- FPS median；
- GPU frame-time median；
- CPU render/encode median；
- native encoder count/frame；
- attachment store/load bytes；
- resident render-resource bytes；
- peak memory；
- frame-time stutter count。

计算规则：

- FPS improvement = `(after - before) / before × 100%`；
- 对越低越好的指标，efficiency improvement = `(before - after) / before × 100%`；
- 正百分比表示改善，负百分比表示回归。

如果某个效率指标无法测量，必须在表中写 `unavailable` 并给出精确原因。只要 Minecraft 性能运行完成，任务前和任务后 FPS 不得省略。

最终报告还必须包含：

- 起始和结束 commit；
- 修改的架构边界和文件；
- 所有命令及 exit status；
- artifact 目录；
- baseline/candidate 原始统计和样本数；
- 每项优化的 decision state；
- correctness、Metal validation 和 shader-pack 覆盖；
- 回退过的实验及原因；
- 尚未验证的范围；
- 当前分支是否达到人工 review 条件。

`run_iris_perf_cycle.sh` 会生成：

```text
summary.json
comparison.json
comparison.md
decision.md
```

最终结论必须以这些文件和对应 source report 为依据，而不是仅依赖日志正则提取值。

## 结束条件

只有以下情况可以结束并返回：

1. 完成实现和所有本机可执行验收；
2. 遇到需要用户选择的真实架构或语义分歧；
3. 遇到无法由仓库改造解决的环境阻塞；
4. 同一个关键 gate 已尝试两种合理修复仍失败。

除非用户明确授权，不创建或合并 PR，不发布，不修改 master。

---
