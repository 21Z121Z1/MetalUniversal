# OPAQUE_PARTICLE 像素 ownership / previous-visible 诊断原型

本原型仅在测试源码及独立 CI 入口运行。基于已父审的 `afb17c8c995de8f2adce7537115b96fced7c5db6`，`src/main` tree 固定为 `ac0e01671e4d47cd3ae7259959f126bd8eeb7acf`。生产 renderer、native ABI、shader assets、其他 producer 与 Temporal/FG 门禁均未改变。PR91 继续保留该已验收 head；实验源码在 `codex/particle-pixel-ownership-prototype-20261021`。

## 冻结的失败基线

`9066e43f15b73f46e3aace435505a08e7729afb2` 的[双 macOS 运行](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39591955220)各有一项预期策略失败。真实 Minecraft 26.3 canonical OPAQUE_PARTICLE、固定白 atlas/lightmap、顶点/modulator alpha=1，GREATER_THAN_OR_EQUAL、writeDepth=true、bias0：旧 B 先画，另一 element A 后画，完全同覆盖且 Z=0.5；当前只画 B。

未插桩原版 RGBA 证实，旧目标区域属于绿色 A，B 红贡献为零；当前为红色 B，前后 D32 均为0.5。真实 staged slice 捕获、旧 B 几何和材质仍匹配，现有 motion/V4 对768像素输出 exact/接受。该结果是组件反例，不代表完整 Temporal/FG 门禁已允许整场景。失败源码及失败日志保留；收尾诊断明确观察反例仍存在，不能把它改称生产修复通过。

## 原型路径

`MetalParticlePixelOwnershipPrototype`、`MetalParticlePixelOwnershipRasterTest` 只在 `src/test` 中。使用真实原版 shader/include；只把原 `main` 改名，再由 wrapper 调用，原位置、颜色、采样、discard、depth 计算不重写。新 flat 整数 varying 在调用完成后把输入 identity 输出到 RGBA32_UINT MRT，因此原 main 的 discard 同时取消颜色和 owner 输出。

身份由受控发射对象 ledger 签发为输入，不由 CPU 决定旧像素归属。每个 quad 的身份按真实上传顶点/index 顺序冻结，经128-byte UBO（最多2 quads）进入 vertex/fragment，再由 GPU 的原色 coverage、深度与 draw order 决定最终 winner。不会向 owner 图写入测试期望值或 CPU winner map。测试从独立、未插桩的原色红/绿像素逐像素核对 winner，并对全部 RGBA/D32 字节作相等断言、保留哈希及原始文件。

每像素 owner64 + element generation64，共16 bytes；各32-bit分量含超过2^24的奇数，检查整数精度，不通过浮点颜色编码身份。ledger 检查发射对象、稳定 emission ID 和 generation，冲突/未知/退役 ticket 输出未知0；未知0即使相等也不能有效。这里的 element 粒度是受控的单 quad，尚未证明真实客户端多 quad/自定义发射的稳定身份。

重投影读取现有 shipping motion XY、validity 与 previous/current Z。旧采样点采用当前像素中心加 NDC motion ×输入尺寸/2，检查边界；只有 actual pixel owner、generation 和旧/当前 D32 都匹配时，独立诊断 mask 才有效。深度沿用3e-6预算，不放宽阈值；同 Z 的另一 element 由整数 owner 比较拒绝。该 mask 未接入生产 V4/Temporal/FG。

## 事务与有限控制集

两个实际 GPU slot 各持有 owner 与 source D32。它们不是 CPU 身份表：D32 与 owner 都由同一插桩原色 pass 写出。slot 只在实际 command buffer 成功完成、源一致且原色/深度逐字节相同时成为 accepted。submitted 未退役期间不能重用或销毁；discard、源提交验收失败和失败完成不交换 accepted slot。resize/source generation 变化清空历史并释放旧 slot，迟到的旧 token 不能晋升新 epoch。

专用 GPU 集包含同 Z B→A 遮挡、连续同 B、反向 draw order、generation、X/Y 移动、两种 ID 顺序的分合 batch、discard及移动后重用、验收失败后恢复、resize、实际 backend shader-library 发布/退役、未知与别名身份、不可信 source lease、真实原版 shader 生成的 foreign-depth 负例，以及854×480/1920×1080成本用例。另有实际纹理分配中途抛错的回收控制与7项 CPU 事务/身份测试。

owner、generation、depth各有仅去掉对应 shader 比较的 GPU 对照；必须重新暴露假接受。foreign-depth 负例故意绑定另一实际原版 draw 产生的 D32，验证不能忽略深度；正常入口把 owner/depth 作为同一 slot 管理。

失败注入是应用层分配/源验收异常，以及 CPU 状态机的失败完成回调，不伪造 Metal GPU 故障。reload 用例通过 `MetalDevice.captureShaderSource` 发布新真实 library、清缓存并关闭旧 provider；它不是完整 Minecraft UI/Configs 重载验收。真实客户端稳定 ID 来源、并发 producer hook、所有源状态冻结及全场景生命周期仍需另行接入证明。

## 资源及成本口径

历史 pair 的逻辑字节严格为 `2 × (RGBA32_UINT16 + D32_FLOAT4) × W × H = 40WH`，拒绝超出3840×2160的诊断请求。代表性逻辑预算如下：

| 输入尺寸 | 双缓冲逻辑 bytes |
| --- | ---: |
| 64×48 | 122,880 |
| 854×480 | 16,396,800 |
| 1920×1080 | 82,944,000 |
| 2560×1440 | 147,456,000 |
| 3840×2160 | 331,776,000 |

该数字不是驱动 resident-memory 保证。独立 Swift probe 使用相同 private/untracked、renderTarget/shaderRead 描述符，分别记录 [allocatedSize](https://developer.apple.com/documentation/metal/mtlallocation/allocatedsize)、[heapTextureSizeAndAlign](https://developer.apple.com/documentation/metal/mtldevice/heaptexturesizeandalign(descriptor:))、对齐后的 heap 规划占用及作用域前/中/后的 device allocated bytes。两种回收证据分别是 Java 实际资源的 close 台账，以及 native probe 的实际分配/释放观察，不混称为同一进程的完整 VRAM 验收。

当前诊断额外持有 reference/color/MRT/负例资源，峰值逻辑纹理为 `78WH + 64×48×4`，最大单次 GPU readback buffer 为 `16WH`；源顶点、identity UBO、uniform、小量 pipeline/driver 状态另计。每个 draw 的 identity UBO固定128 bytes。病例结果保留所有 texture/buffer/readback 分配与关闭计数，及分配中断后已创建资源的关闭/重试证据。

原型采用 shadow source pass，与未插桩 reference 并列，因此实际多一个 source raster pass，再加一个全屏 ownership 验证 pass。source identity 写出最多16 bytes/通过片元；验证 shader 最多读取7个 texel，逻辑读写上界54 bytes/像素（包含1-byte输出，不等于实际DRAM流量）。depth直接保留GPU source D32，不经CPU回填。

854×480与1920×1080各做两轮 ABBA source/instrumented 控制，每种4个样本；resolver 单列首次编译/执行与8个缓存后样本。CPU encode、submit/wait 和完成后读取的 [command-buffer GPU timestamps](https://developer.apple.com/documentation/metal/mtlcommandbuffer/gpustarttime)分别保存。缺失时间戳记 null；完整 readback/验证成本另看，不能把它当生产 GPU-only 消费路径。小样本、hosted GPU排队/抖动和首次编译均明确保留，不推出真实FPS、输入延迟或时域画质收益。

## 父审后的接入选项

1. **原色 pass 原位 MRT**：最强同源性，由实际 shader/depth/draw order 一次确定 owner。需要真实 producer 的稳定 element/generation 与源状态绑定、附加 attachment/render-graph 生命周期和平台等价证明；正常 current D32可借用时理论增量约为两个owner图加一张旧D32，即36WH，实际仍须按平台测量。没有完成这些工作前不能接入或准入。
2. **完整冻结的 shadow pass**：复用本诊断方式，额外重放一次 geometry。只有 shader、vertex/index、uniform、材质、scissor、depth及原始顺序全部可冻结且逐像素一致时才有意义；缺失 producer 或状态时拒绝，不能用相近深度代替。
3. **保留 current-only**：不能证明身份、代际、同源性、历史像素或资源寿命时继续保守回退，不以本原型通过抵消未知 producer。

本轮不选择产品化方案，不扩大其他 family、透明/OIT、纹理 cutout 或UI等语义，不启用Temporal/FG，不修改生产ABI。支持硬件上的画质、节奏、延迟与全场景 previous-visible 仍未验收；`doNotPromote` 保持。每个实际候选的完整结果、失败原文、样本和恢复清单以不可变审查 tag 为准，不能用早期候选绿灯替代最终 SHA。

## 独立执行入口

`./gradlew --no-daemon -I .github/ci/HostedMetalGradle.init.gradle hostedMetalParticleOwnershipPrototypeTest -x buildIOSNative -x buildIOSSpvc`

新GPU诊断需专用入口显式开启 `metallum.validation.pixelOwnershipPrototype=true`；普通测试不会无意执行它。专用双 macOS workflow 强制1项基线观察 +7项CPU +22项GPU诊断全部执行、零fail/error/skip，并要求22份互不覆盖的病例结果和 native成本文件。没有“硬件不支持就当通过”的路径。
