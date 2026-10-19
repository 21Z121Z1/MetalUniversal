# 优化默认值与 Actions 验证

默认值属于运行策略，不能通过把全部实验开关设为 `true` 来代替执行路径验证。诊断开关、可选画面功能和硬件后端也不是同一类优化。

## 常规 CPU 优化

已默认开启的路径包括 encoder 状态缓存、状态包、绑定缓存、编译后的绑定计划、延迟附件 store、blit 合批和无 trace 绘制快路径。

以下四项默认开启。Actions 对其执行独立像素读回、组合激活、逐项关闭与真实客户端检查；默认值集中在 `MetalBackendOptimizations`，执行和诊断读取同一个进程快照。

| 属性（前缀 `metallum.opt.`） | 减少的工作 | 保留的兼容路径 |
| --- | --- | --- |
| `fusedStateDraw` | 原生状态包与直接／索引绘制合并为一次 FFM 调用 | 单项状态变化保留原 critical setter；无 v2 接口、无待应用状态或明确拒绝时使用原调用；未知绘制结果禁止重放 |
| `reuseEncoderState` | 重复分配 CPU 状态缓存与堆外状态包 | 活跃 encoder 独占租约；同线程最多保存一份空闲存储 |
| `reuseNativeEncoderArguments` | 反复创建原生附件参数 arena 与数组 | 重入、过长标签或超出固定容量时使用 scoped arena |
| `dynamicUploadRangeCopy` | 跳过被上传数据覆盖的旧字节复制 | 仅用于覆盖至少半个 buffer 且不少于 4 KiB 的部分上传；小块、窄范围更新保持单次整块复制，先前 GPU consumer 保留原 backing |

每项都保留显式 `-Dmetallum.opt.<name>=false` 回退。

## 验证入口

`metal-capabilities` 先运行 raw Metal GPU 探针与 shipping dylib ABI 检查，再运行：

- 原路径：显式关闭四项，执行核心 GPU 合同。
- 候选组合：显式开启四项，执行 MRT、偏移／实例化、上传与资源寿命测试，并要求激活计数增加。
- 生产默认：不注入这四个属性，使用相同 GPU 预期验证默认行为。
- 逐项关闭：其余三项开启，验证关闭一项后读回、resize 和 fallback 行为。
- 编码成本：同一 runner 上八个独立 JVM，顺序为 ABBAABBA；固定单／多状态变化加 draw 与重复 encoder 工作负载，记录每次操作时间和 Java 分配量。GPU 等待在计时区间外，timing JVM 不开启诊断计数。
- 上传成本：64 B、64 KiB、1 MiB 的 direct buffer，四个 ABBA 块，按独立字节预期检查结果。

`Minecraft 26.3 production client E2E` 对 Vanilla、Sodium 分别运行原路径、候选组合、生产默认。生产 JAR 必须与精确提交一致；检查有内容的 Metal framebuffer、呈现完成、资源重载和优化激活计数。客户端不再强制关闭状态包。

这些测量只能说明托管设备上的编码／复制成本；不将它们换算为实机 FPS、输入延迟或功耗收益。历史 gameplay 性能协议仍显式固定原候选默认值，避免升级默认策略后悄悄改变其比较对象。

## 仍需独立验证的实验

| 范围 | 默认策略与具体缺口 |
| --- | --- |
| GPU 提交预算 | 默认 3，1／2／3 已有读回和完成回调验证；更小预算改变延迟与吞吐取舍，需要对应呈现场景证据 |
| 异步预编译、原生 multi-draw | 保持候选；需各自的加载／冷暖缓存／重载、真实批绘制激活与成本比较，本轮四项测试不能代替 |
| Iris pass fusion、compute grouping、depth liveness、final color fusion | 保持候选；需实际 shader pack、资源代际、危险关系、resize／reload 的激活与视觉等价证据 |
| Iris attachment liveness、argument tables、indirect submission | 当前稳定开关仅供配置与诊断读取，尚无执行消费者；启用前需要实现接线与准入 |
| Metal 4、显式 residency、terrain GPU／ICB 替代路径 | 保持设备能力与 generation 准入；Metal 4 原生测试在不支持的 hosted GPU 上跳过，不能据此宣称执行已验证 |
| pacing、MetalFX、帧生成与 motion producer | 涉及节拍或画面功能，按各自能力、运动／深度输入和质量合同验证 |
| telemetry、GPU timing、frame evidence | 诊断仪器保持默认关闭，避免给日常渲染增加采样成本 |
