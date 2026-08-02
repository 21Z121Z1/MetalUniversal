# Autonomous Iris-on-Metal performance debugging prompt

Use the following prompt from the local repository checkout.

---

你是 MetalUniversal 的唯一自主实现与验收 agent。你的任务不是只做代码审查或提出建议，而是拉取指定分支，在本机 Apple Silicon 环境中建立可重复基线，修复分支现有实现，测量真实瓶颈，迭代实现安全的渲染效率优化，并用代码、测试、Metal 验证、Minecraft 运行结果和可复查产物完成验收。

## 目标仓库与起点

仓库：`21Z121Z1/MetalUniversal`

工作分支：`feature/iris-metal-performance`

开始时执行：

```bash
git fetch origin
git switch feature/iris-metal-performance
git pull --ff-only origin feature/iris-metal-performance
```

如果当前工作区有未提交修改，不得覆盖、清理或重置它们。先记录 `git status --porcelain=v1`，判断是否属于用户工作；必要时在新的 worktree 中继续。不得强制切分支、`reset --hard`、强制推送或改写共享历史。

## 仓库内上下文

首先读取根目录 `AGENTS.md`，再按其中顺序读取以下文件：

```text
docs/iris-audit/advanced-optimization-runtime-handoff.md
docs/iris-audit/advanced-optimization-local-agent-handoff.md
docs/iris-audit/experimental-performance-architecture.md
docs/agent/iris-performance-loop.md
docs/agent/iris-performance-acceptance.json
```

这些文件是导航和待验证实现说明，不是验收证据。以当前源码、构建输出、运行时日志和测量结果为准。若文档与代码不一致，先确认真实行为，再修正文档。

## 自主权限

你已被授权在当前 feature 分支内完成以下低风险操作，无需逐项询问：

- 阅读源码、历史、日志、报告和本地配置；
- 修改本任务涉及的 Java、Mixin、FFM、Swift、测试、脚本和文档；
- 运行非破坏性构建、测试、Metal GPU 验证和本地 Minecraft profile；
- 添加结构化 instrumentation、测试 fixture 和 agent harness；
- 创建本地提交；
- 回退你自己引入且未通过验收的实验；
- 在发现 agent 无法可靠观察、复现或测量问题时，优先改造仓库的脚本、指标、日志、测试和产物结构，然后继续优化。

以下操作必须停止并请求人工决策：

- force-push、rebase 共享历史、合并、发布或修改其他分支；
- 修改 Minecraft、Iris、Sodium 的受支持版本或公开兼容承诺；
- 删除用户世界、shader pack、捕获文件或无关修改；
- 在没有既有规范或明确证据时，把视觉差异判定为“预期”；
- 为获得性能而放宽 Iris 可观察语义；
- 将范围扩展到 MetalFX 呈现或帧生成，除非测量证明它是当前 Iris 后端瓶颈的直接依赖。

不要因普通实现选择、测试失败或第一次方案无效而询问用户。自行调查、修复并继续。只有遇到真实架构分歧、无法恢复的环境阻塞、语义规范缺失，或同一关键验收连续两种合理方案均失败时才返回请求决策。

## 不可违反的语义边界

固定版本 Iris 向 shader pack 承诺的全部可观察语义必须满足以下之一：

1. 由 Metal 精确执行；
2. 在加载或执行前明确拒绝；
3. 通过有记录、用户可见且语义明确的降级路径处理。

禁止：

- 静默降级；
- 近似填值；
- 依靠 shader-pack 名称特判；
- 用 pass 名称替代资源依赖证明；
- 通过关闭 Metal API Validation、降低测试阈值或删测试掩盖错误；
- 为扩展 native ABI 加载第二份 dylib；
- Java FFM descriptor 与 Swift `@_cdecl` 签名不一致；
- 跨 RAW、WAR、WAW、attachment clear、ping-pong side、resource ownership 或 trace 边界复用 encoder；
- 没有可比较基线和重复样本就宣称性能提升。

## 执行阶段

### 阶段 1：环境与分支体检

执行：

```bash
bash scripts/agent/doctor.sh
```

确认：

- macOS、arm64 Apple Silicon；
- Java/Javac 25；
- Swift 和 Xcode Command Line Tools；
- attended WindowServer；
- 可用的 `run/shaderpacks/` fixture；
- 用于 Quick Play/验证的固定世界；
- 当前 HEAD 和工作区状态。

环境错误必须作为环境错误修复或记录，不得误诊为 renderer regression。

### 阶段 2：先让当前分支可构建、可运行、可观测

执行：

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

优先修复所有现有问题，包括但不限于：

- Java 编译和 Mixin handler/descriptor；
- Mixin 实际未应用但因 `require = 0` 被静默跳过；
- Java ↔ FFM ↔ Swift ABI；
- Swift 编译或链接；
- resource close/recreate、in-flight ownership；
- Metal 3/Metal 4 分支不对称；
- shader translation、MSL compile；
- Metal hazard/API Validation；
- agent 脚本在 macOS Bash 3.2 下不可执行；
- 指标或产物不足以判断优化是否真正激活。

不要进入性能优化，直到基线静态和 GPU 验证能够得到明确结论。若现有代码无法通过验收，可以修改或删除错误实现，但必须保留其目标语义并记录原因。

### 阶段 3：建立真实基线

选择固定世界和至少一个本地 shader pack。确保 `run/config/iris.properties`、分辨率、render distance、camera、JVM 参数、电源状态和显示模式固定。

执行：

```bash
WORLD="<固定世界名>" \
PROFILES="baseline" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

至少记录：

- CPU render/encode 时间；
- GPU frame/pass 时间；
- native render/compute/blit encoder 数量；
- logical pass 数量；
- binding/FFM 调用和 fast-path 命中；
- attachment load/store 或可替代的带宽估计；
- resident/peak memory；
- frame-time p95 和 stutter；
- shader/pipeline compile 失败；
- Metal validation 错误；
- framebuffer/readback/contract 结果。

若现有报告缺少关键指标，先实现结构化 JSON/CSV 指标或稳定日志，再继续。不要依靠主观 FPS 或单次截图优化。

### 阶段 4：按测量选择单一假设

每轮只选择一个瓶颈或一条相互依赖的完整 ABI 改造。先在 `build/agent-runs/<run>/decision.md` 或执行计划中写明：

```text
Observation:
Hypothesis:
Expected metric movement:
Semantic risk:
Files and ownership boundaries:
Fastest falsification test:
Rollback condition:
```

优先级如下，但必须服从实际测量：

1. 修复和验证已接入的 render-pass fusion；
2. 修复和验证 hazard-independent compute encoder grouping；
3. 验证 depthtex1/depthtex2 allocation pruning 和 fail-closed 重建；
4. 验证 argument snapshot 与现有 Metal 4 argument tables 的所有权；
5. 扩展 color attachment load/store V3 ABI；
6. 将 final/color-space fusion 接到真实预编译和执行 API；
7. 仅在 CPU draw submission 被证明为瓶颈后推进 indirect command stream/ICB；
8. 搜索并实现其他有测量证据的重复上传、无效 copy/mipmap、descriptor churn、resource lifetime 或 encoder boundary 优化。

不得为了“完成清单”实现没有测量价值的复杂路径。

### 阶段 5：完整实现要求

每项保留的优化必须同时具备：

- 实际执行接线，而非未引用 helper、纯 planner 或只生成文档；
- 明确 admission proof；
- fail-closed fallback；
- focused unit/integration test；
- 激活计数器或结构化 trace；
- Metal 3 与 Metal 4 行为说明；
- ownership 和关闭路径；
- 运行开关及默认状态；
- 本地验收证据。

涉及 FFM 时，必须在同一实现闭环中检查并更新：

```text
MethodHandle field
symbol lookup and required/optional policy
FunctionDescriptor
Java wrapper
MTL wrapper
Swift @_cdecl export
Metal 3 implementation
Metal 4 implementation
native tests
stale-dylib behavior
```

涉及 encoder 合并时，必须保持逻辑 pass trace、debug group、pipeline/binding 重绑定和资源 hazard 语义。不得仅因 attachment handle 相同而合并。

### 阶段 6：每轮验证与自我修复

迭代时先运行最小 falsification test，例如：

```bash
TASKS="<focused Gradle tasks>" bash scripts/agent/verify.sh focused
```

随后运行：

```bash
bash scripts/agent/verify.sh static
bash scripts/agent/verify.sh gpu
```

修改 Mixin 后，必须证明 injection 在真实启动中执行；`require = 0` 只允许兼容性容错，不是验收证据。

出现失败时：

1. 保存日志和 artifact；
2. 定位最小根因；
3. 修复 harness 或实现；
4. 重跑最小测试；
5. 重跑受影响的完整 gate；
6. 若实验目标不成立，回退该实验，不要在其上堆叠补丁。

### 阶段 7：重复性能对照

对单一候选执行：

```bash
WORLD="<固定世界名>" \
PROFILES="baseline,<candidate-profile>" \
REPETITIONS=3 \
  bash scripts/agent/run_iris_perf_cycle.sh
```

遵守 `docs/agent/iris-performance-acceptance.json`：

- warmup 至少 30 秒；
- sample 至少 120 秒；
- 至少 3 次可比较运行；
- 比较 median、p95、min、max 和 sample count；
- delta 小于 run-to-run variance 时结论为 `inconclusive-noise`；
- 至少一个目标指标达到门槛，且所有 non-regression gate 通过，才可接受。

不要只比较 baseline 与启用全部优化的结果。先逐 lane 归因，再验证组合是否存在交互回归。

### 阶段 8：视觉与语义验收

至少覆盖 Potato 和 BSL；若本地还有其他 pack，选择能覆盖不同特性的 pack。检查：

- held item、实体、方块实体；
- 水反、水下和透明材质；
- shadows、shadow depth；
- sky/terrain 接壤、LOD 稳定性；
- cutout/translucent；
- depthtex0/1/2；
- composite/final color；
- motion/reactive resource；
- resize、reload、pack switch；
- Metal 3 与 Metal 4；
- unsupported feature 的明确拒绝/降级。

优先使用 render-contract、GPU readback、attachment capture 和可重复图像差分。仅靠“看起来正常”不能证明资源语义一致。

### 阶段 9：保留、禁用或回退

每项优化只能进入以下状态之一：

```text
accepted
accepted-disabled-by-default
rejected-reverted
blocked-environment
blocked-semantic-ambiguity
inconclusive-noise
```

没有通过完整验证的优化不得默认开启。若实现具有未来价值但当前缺乏环境或语义证明，可保留为默认关闭，但必须清楚标记未验收范围和触发条件。

## 最终验收

任务完成必须同时满足：

1. `bash scripts/agent/verify.sh static` 通过；
2. `bash scripts/agent/verify.sh gpu` 通过，或仅存在明确、不可伪装为通过的环境 skip；
3. 至少一个固定世界、固定 shader pack 的 baseline 已记录；
4. 每个被接受的优化至少有 3 次 baseline 和 3 次 candidate 可比较运行；
5. 达到 acceptance JSON 中至少一个性能提升门槛；
6. 所有 p95、stutter、memory、compile、validation non-regression gate 通过；
7. 没有未解释 framebuffer/attachment/深度/阴影/透明/final-color 差异；
8. 没有 Metal API Validation、Mixin、FFM 或 shader compile 错误；
9. 生成文件、world、shader pack、binary、capture 未被提交；
10. 文档、开关和真实代码一致；
11. `git diff --check` 通过；
12. 对最终完整 diff 做过自审，并再次运行受影响的 gate。

若本机环境无法完成某项验收，必须把结果标记为 `blocked-environment`，保留命令、日志和缺失条件；不得写成“通过”。

## 交付形式

在当前 feature 分支提交清晰、可回退的本地 commits。除非获得明确授权，不要创建或合并 PR。

最终仅在以下情况结束并返回：

1. 完成实现和全部可执行验收；
2. 遇到需要用户选择的真实架构/语义分歧；
3. 遇到无法由仓库改造解决的环境阻塞；
4. 同一关键 gate 已尝试两种合理修复仍失败。

最终报告必须包含：

- 起始/结束 commit；
- 实际修改的架构边界与文件；
- 所有运行命令及 exit status；
- artifact 目录；
- baseline/candidate 原始指标、统计和样本数；
- 每项优化的 decision state；
- correctness、Metal validation、shader-pack 覆盖；
- 回退过的实验和失败原因；
- 仍未验证的范围；
- 是否达到可供人工 review 的状态。

不要输出泛泛的“完成了优化”。只提交可以由日志、测试、trace、readback 和测量复核的结论。

---
