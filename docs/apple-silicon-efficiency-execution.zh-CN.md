# Apple Silicon 效率实施记录

起点：`40b9b9bc97f005c8613683a31324d88f2501657f`，Minecraft 26.3；分支 `codex/apple-silicon-efficiency-20260923`。

## 实施范围与验证入口

按先完成全部可实施工作、再集中验证的顺序实施。以下记录源码责任和候选边界，不代表渲染正确性或物理收益；每次验证的精确提交、退出码和原始结果保留在 ignored `build/agent-runs/`。

| 工作包 | 已有且复用 | 本次候选／缺口 | 状态 |
|---|---|---|---|
| E1 | packet/shadow 独占线程 scratch、generation-aware 动态 backing、上传去重、原生有序 multi-draw | encoder flush/end 失败仍释放；范围检查在 dedup 之前；OOM 明确失败；默认关闭的 partial upload 仅复制未覆盖区间；独立候选接线与计数 | 实现及行为测试入口齐备；独立候选默认关闭 |
| E2 | 完整 SPIR-V/布局/编译选项 MSL key、PSO archive、加载 executor 编译、reload generation、同步 fallback | 长度前缀 key、缓存身份和内容摘要、并发独立临时文件；同步/异步 pending 单次消费与失效；预热队列有界且拒绝后按需创建 | 缓存合同及候选接线齐备；预编译默认关闭 |
| E3 | generation-aware 附件 lifetime、deferred store、copy 去重、M3/M4 completion-owned allocator | 补 deferred store/fence 失败时的终结清理；保留已有同资源全 clear 的死 store 证明；不扩大不明消费者的消除范围 | 清理修复及现有生命周期复用 |
| E4 | persistent terrain scene、GPU frustum/compact/ICB、publication guard、slice cache | 已发出或不确定发出 draw 时禁止重复 fallback；执行序号保护 cleanup 异常；显式 cadence 接入原 terrain 预算安全界限 | 提交保护及预算接线齐备；producer 替代须实际激活证据 |
| E5 | 普通 SDL surface、frame receipts、Sodium pacing controller | 纯策略函数、前台/后台/最小化/空闲/可选电池限制、Dynamic FPS 委托、真实 limiter 接线；固定节奏功率导入、积分、ABBA 比较、合同/schema/测试/CLI | 策略和仪器合同齐备；默认关闭 |
| E6 | temporal/motion/coverage、source-pair receipts、独立 UI 合成、设备能力准入 | 后端 surface lease 单次消费、重配与隐藏/恢复时停旧 presenter、失效 history、frame epoch；复用现有 producer 准入 | 生命周期及转换诊断接线齐备；FG 物理质量独立验收 |

### 实际边界和前置条件

- E1 的 scratch 只在同步 packet 消费后归还；动态 GPU backing 沿既有 completion-safe destruction queue 回收。本次不改提交粒度、透明顺序或 producer 画质。
- E2 使用实际 RenderPearl 加载 executor 的首次使用序列；没有引入猜测 shader 的全量预热。加载/冷缓存/热缓存/资源重载的时间收益仍需要各自实测。
- E3 的现有 native Metal 4 allocator slot 在 GPU 完成后复用；SDK 说明 argument table 在 draw/dispatch/execute 时快照。本次不另造 allocator 或 argument-table pool。没有已测热点和完整消费者证明的新 memoryless/pass 合并候选。
- E4 的 GPU frustum/compact/ICB 路径已有准入；软件测试与 source mapping 不证明其在 Vanilla/Sodium/Iris 每个组合实际替代 CPU 工作。26.3 reference 合同使用收敛后的原版 declarations 核查。
- E5 仅降低实际 limiter cap，保留 tick/联网/模拟。未知电池状态不触发电池规则。功耗必须有可信仪器、准确 source 时钟相关和完整窗口；没有该输入时输出 `unavailable`。新 profile 只给出能耗方向，不能绕过原正确性、admission、CPU/GPU/内存/stutter 和 A/A 门槛。
- E6 保留现有 temporal/FG 的设备和 motion/depth/coverage 准入。mesh shader 缺少剩余几何瓶颈证据；LOD/Voxy 缺少独立产品画质合同；Metal I/O/稀疏/光追缺少具体可替代 workload。它们未被伪装成已完成或默认候选。

### 接线与可执行入口

- 开关、仪器输入 schema、A/A 和 ABBA 命令见 [固定节奏能耗](agent/fixed-cadence-energy.md)。
- `metallum.opt.dynamicUploadRangeCopy` 与既有 `reuseEncoderState`、`nativeMultiDrawBatch`、`asyncPrecompile` 独立，均默认 false。
- `metallum.pacing.enabled` 默认 false，其余 cadence 默认 0（继承）。每个 source frame 附带当帧实际策略；启动累计计数只作 activation，不称样本窗口性能。
- Java 行为测试覆盖 CPU byte copy、越界、encoder 异常终结、缓存损坏/并发、策略切换、surface lease、ICB 不确定提交、scheduler 目标来源和嵌套 frame metadata。
- 原生 MRT 集成测试新增未完成 GPU consumer 与 partial dynamic upload 的读回，补 pending 重复消费。用 `-Pmetallum.efficiencyProfile=...` 选择单个候选；M3/M4 分别验证。
- Python 合同测试覆盖仪器域/身份/窗口/时钟、积分、缺项、cadence 回归、四组配对方向和 profile 阈值一致性。
- `X0` 独立检查实际 SDL 窗口像素尺寸、资源重载和恢复全屏。Fabric 的虚拟 framebuffer 不作为物理窗口变化的证明；转换窗口不用于稳定帧率或能耗比较。

集中验证先固定本地提交和收敛来源，再运行合同、原生读回与真实客户端探针。失败记录原样保留；修复后的新运行使用新的证据目录。累计 activation 计数只能证明路径进入，不能替代样本窗口内的配对性能证据。

## 预先记录的可证伪假设

- E1：scratch 复用减少状态对象/arena 分配，不改变 encoder 初始未知状态；异常仍终结唯一 native encoder，未提交 draw 不得重放。
- E2：带身份和摘要的原子缓存不接受错键／损坏内容，后台结果跨 reload 不发布；热缓存只跳过已证明相同的翻译工作。
- E3/E4：只有有消费者／generation 证明的路径可以替代旧工作；已发出 draw 后不允许回退重复执行。
- E5：显式目标通过既有 limiter 执行，后台降频不改变 tick/simulation；固定节奏收益只由同窗口实际功率积分建立。
- E6：surface 模式／尺寸／恢复失效旧 temporal history 和呈现 receipt，不新增 present 所有者。

## 证据边界

工程、render correctness、物理性能和真实功耗分别报告。既有源码、历史 CI、分配计数或 FFM wall time 不证明本次候选有 FPS 或功耗收益。原始历史证据不改写；新运行留在 ignored `build/agent-runs/`。
