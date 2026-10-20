# Minecraft 26.3 运动生产者覆盖补齐

起点为 `04c4bd63b85fcb8fbcdefe7dbfdd6d8fa905807a`。本清单记录工程验收范围；不能用已有 16 个着色器 GPU 用例代替真实 Minecraft 入口验收。

## 参考与约束

沿用[专项研究](metalfx-closure-study.zh-CN.md)中固定的 Apple Temporal 示例、WWDC25 211、Godot `81ab961f`、MCUpscaler `defa601f`，以及本地 26.3 客户端源码。采用源帧事务、真实绘制资源绑定、独立手部投影、去抖动 current→previous 运动及明确的设备支持判断。外部项目的包围盒运动、光流和不同单位不替代逐顶点历史；本轮没有复制外部代码。

## 优先目标：真实粒子和 OIT 执行

- `QuadParticleFeatureRenderer.drawLayers` 的真实 `drawIndexed` 返回后进入公共 staged-motion 路径。无需伪造 `PreparedRenderType`。
- `PreparedRenderType.drawFromBuffer` 和 `drawFromBufferOit` 共用同一路径，保留实际绑定的颜色阶段管线、纹理、投影、动态矩阵和 scissor。
- staged Draw 持有所有者直到其自身缓冲的 `endDraw`；DEPTH_BOUNDS、TRANSMITTANCE 不消费颜色回放，ACCUMULATE 最多消费一次。清理一个缓冲不影响其他 PreparedFrame。
- 透明运动使用独立的场景／手部深度副本解析最近表面；运动不得改写游戏深度。混合实体采用逐顶点路径，刚体回退继续拒绝混合材质。保留透明 reactive 保护，单一 XY 不能表达多层混合。
- 粒子 alpha 插值、实体 TextureMat 和零透明度可见性需与颜色阶段一致。DISSOLVE、其他顶点格式和未知着色器仍需对应契约。

验收分三层：Java 身份／生命周期；生产 GLSL→FFM→Metal 的运动、透明重叠与源深度不变性；实际 Vanilla/Sodium 客户端的粒子及 OIT source draw。

## 云端真实绘制清单

诊断属性 `metallum.metalfx.captureMotionValidation=true` 在 Spatial 回退时也捕获真实 staged 所有者／历史，按成功 source submit 提交，并执行公共 Metal 运动回放及纹理读回。它不创建 Temporal 支持，不运行补帧，不把运动回放等同于 MetalFX 时域／插值质量。生产默认不启用此诊断。`574a4a7` 的首轮仅验证捕获；后续读回证据必须来自实现增强后的提交。

客户端导出 `motion-source-draw-coverage.json`：按真实管线列出后端绘制调用与 staged hook 调用，并分别保留三阶段／所有者／排队／编码计数。`programs` 另按原始 shader ID、defines、顶点语义／步长、混合／深度状态区分同名程序变体，避免 GLINT_SPECIAL、裁剪或 OIT 的漏接被汇总计数掩盖。未覆盖管线和没有来源的后端绘制保留在清单中，不能记为 NOT_PRESENT。绘制调用数不是 fragment 数；indirect 的调用数也不证明其中每条命令非空。

## 模型、材质和实际回放

- 每个真实颜色 draw 在同一历史事务中保存实际 projection/model-view、去抖动投影及材质资源 allocation/generation。纹理更换、采样语义或管线改变时，不能仅凭顶点数量继续复用。OIT 的每帧系数目标不当作材质身份。
- 世界逐顶点回放使用前一 draw 的实际 MVP；手部继续使用独立 HUD 历史。所有 exact draw 都复制当前源投影，避免后续全局 UBO 覆写。
- 不可变顶点快照在附加矩阵、材质和提交时共享数据，保留对外防御性复制，避免每个 draw 重复复制整份姿态顶点。
- `CustomFeatureRenderer` 必须在取得 vertex builder 前激活所有者。`574a4a7` 的 Sodium 实机客户端清单显示 OIT entity 有 10 次 hook、0 次所有者，暴露了旧 callback 包围位置过晚；后续 CI 必须要求所有者、完整历史和实际运动编码，不能只验 hook 次数。
- 新审计模型路径为附魔台、讲台书本、饰纹陶罐、潮涌核心的 shell/cage/wind/eye、头颅，以及水晶及其 ENTITY beam、经验球、自身模型与透明覆盖的潜影贝导弹、两种风弹。每个对象仍需完整 manifest，空讲台不算几何，未知状态／管线继续拒绝。
- 增加实际 Minecraft `core/entity` 颜色着色器的独立光栅参考，检查世界 draw 的不同 model-view、源抖动及源投影覆写。客户端读回 `raw-source-motion/` 明确记录源提交、历史 epoch、有效像素、有限向量和当前 scaler 模式。
- 原始 RenderPipeline 随各自的异步 pending compile 保存，在完成后附到 Metal 编译对象。SPIR-V 重建的后端布局只有 `attributeN` 属性且没有 defines，不能代替源管线。未关联来源的后端绘制保留在 `backend-only:` 清单中，并拒绝运动准入。GPU 回归覆盖同名不同 defines 的管线逆序完成，客户端验证真正的 PipelineCache 入口。
- 手部覆盖深度从 `render3dHud` 实际清除的纹理复制，在该方法结束后保存为渲染尺寸的独立纹理。启用后处理时，主深度已混合世界／HUD，不能再当作手部遮罩。GPU 对照覆盖同尺寸和较大 HUD 目标、世界运动不被归零、源深度不变及手部消失；客户端导出世界／手部深度和分离像素统计。

运动着色器改为插值齐次 current/previous clip，再在片元阶段除以 W、计算有效性。独立 GPU 对照使用原版颜色着色器的覆盖和 CPU 透视重心坐标，检查倾斜三角形、姿态与抖动、前帧部分越过相机的三种情况；同时要求旧的顶点 NDC 插值产生可检测误差。误差预算逐像素计入 RG16F 量化，另留 0.02 像素的运算误差，不对近相机的大向量套用无法满足的固定半精度阈值。新增用例需由对应提交的 Actions GPU 结果验收。

`b484d67` 的 macOS 15 对照发现：前帧 W 接近零时，允许离开前帧视锥的对应点被错误标为有效；最大偏差发生在 previous NDC XY/Z 均超出前帧可采样范围的位置。现在把前帧视锥之外的对应点标为无效，并继续对可采样对应点使用原误差预算。另一失败为新增 pending-compile 测试漏设 primitive topology，已经补上；这轮不能记为全通过。

粒子按持久粒子 ID 重排每个四顶点 quad 的前帧位置。出生、死亡和排序变化不再清空仍存活粒子的历史；同一粒子的多次 quad 发射只有数量不变时才按该粒子的发射顺序对应。新增或发射数变化的 quad 明确输出无效历史，并阻止该源帧声称完整补帧运动覆盖。旧 group 计划曾使无关层连带重置，下文独立 Layer 事务消除此依赖。事务继续只在成功源提交后前移。

`68161df` 的 GPU 回归进一步发现，NaN 前帧顶点经过 GPU 后不能可靠维持“无效”标记。生产回放改用 16 字节 XYZ＋PreviousValidity 流：无历史顶点上传有限零坐标和独立有效性 0。保留 12 字节旧测试／兼容变体；原生导出符号未变。手部独立参考改为原版 ENTITY/ITEM 颜色着色器，避免参考也使用运动有效性裁剪。

运动 MRT 新增 RG32F(previous Z, current raster Z)，与 XY／覆盖选择同一表面。无历史但有当前几何的像素写 previous=-1、有效性约 0.25；没有几何的背景为 Z=(-1,-1)、有效性 0，不能混淆二者。客户端读回导出深度对及有效／无效所有权统计，透视 GPU 对照检查前帧变形表面的 Z 和当前光栅 Z。此步接通了生产者输出；原生 merge／反遮挡和 FG 输入消费仍需后续版本化 ABI，不能宣称 previous Z 已端到端完成。

`68161df` 的真实 Vanilla 清单已证明普通粒子 256 次 draw／256 次 hook／254 次编码，OIT 粒子 39／39／38。但报告仍未验收：诊断回放被混入源 draw 分母；水晶光束 46 次 draw 均无所有者，经验球也只记录到 extraction。后续分别用显式 replay 作用域单列 `internalMotionReplays`，以及让提交列表的检查遍历保留帧内身份。HEAD 观察器遍历列表不再消费真正 builder 随后所需的 owner。原断言继续要求光束与经验球有真实所有者和运动编码。

`0b14794` 的两条真实 FX 客户端均通过粒子／OIT、光束、经验球及新增模型的断言，并读回约三万个有限有效运动／深度像素；最终断言失败是测试仍处于旁观模式，手部源深度为零。后续场景明确切换第一人称创造模式、显示 HUD、持剑和盾，并验证普通／invert 后处理两种 HUD 深度入口；结束时恢复原状态。HUD 深度目标从构造起使用场景尺寸，避免首次启用后处理时依赖先发生窗口 resize。

同一提交的 GPU 为 24/25：持久 ID 有效性、真实手部颜色参考、独立 HUD 深度均通过，部分越过前相机的用例有 8 个像素的 Z 超过数学参考 0.00003（最大约 0.00004962）。对照增加独立的 GPU 当前表面位置输出，再由 CPU 变形／前帧 MVP 计算该表面点的 Z；继续保留原数学 XY 负对照、0.00003 深度预算及独立位置投影回源像素的检查。尚须新提交的 GPU 结果验收。

`eb7a376` 的五条工作流均通过：macOS 15／26 各 25/25 GPU 用例，Vanilla／Sodium 的 FX 场景和 baseline/default/candidate 客户端均成功。实际 FX 附件确认持物手部、独立后处理 HUD 深度、粒子/OIT 及新增模型的真实 source draw；Sodium 两种 HUD 入口均读回 41,808 个有效手部像素。此证据仍是生产者和 Spatial 验证，不代表托管设备已运行 Temporal／FG。

## 表面深度消费与公共原生合成

V4 将 RG32F 前帧／当前 Z 接到独立的原生运动合成入口。它不依赖 Temporal 支持，Spatial 诊断也执行相同的 Metal 3／4 compute；跳过时域放大的 native-direct FG 必须先成功合成运动。Temporal 的 prepared 入口消费合成结果，避免再次经过旧的仅当前深度合成。

世界与 HUD 分别使用两个 R32F 深度历史纹理，随接受的源提交与顶点历史一起交换；reset、尺寸变化、提交失败会使历史失效。同一 epoch 中延迟返回的旧 GPU 失败也使后续依赖历史失效，已经退役的世界／尺寸 epoch 不覆盖新历史。合成输出的 presentation depth 按实际 HUD 覆盖选择 HUD Z，否则选择场景／透明运动最近表面 Z，供 Temporal 和 FG 共用。

遮挡检查使用表面自身 previous Z，不把动态物体或手部当作世界相机重投影。世界对应点还检查前帧 HUD 覆盖；中心更近的遮挡物不能被邻域 dilation 抹掉。世界深度查找补偿前后 raster jitter，HUD 保持独立无抖动域。已拥有但无效的像素输出零运动、完整 reactive 和反遮挡，不能回退成看似有效的世界运动。有限性检查使用禁用 fast-math 的内核。

新增 GPU 对照覆盖接近相机的物体、前帧／当前遮挡、无前帧表面、明确无效所有权、NaN／越界 Z、非有限运动、手部独立深度、手部移开、reset、相机运动、标准／反向 Z、jitter 域、dilation 和透明最近表面。真实客户端读回增加合成 XY／reactive／disocclusion、下帧双域深度和 presentation depth，逐像素验证有限性与 HUD／世界选择。`6aa3448` 的 macOS 15／26 附件均为 47/47 GPU 通过，22 项新增 V4 场景的运动与遮挡预期全部匹配；Swift／MSL 已经实际编译和执行。真实客户端的 V4 合成读回仍待该提交的运行完成。

## 首次出现与历史不连续

已知所有者、当前几何和源矩阵完整时，reset、首次出现、几何／材质 manifest 改变不再直接跳过整笔运动覆盖。当前可见表面继续使用真实颜色可见性回放，写 current Z、previous Z=-1 和 R8≈0.25；前帧位置上传有限零坐标与有效性 0，刚体路径使用明确的零 previous clip W。没有捕获到当前几何／矩阵、未知管线或所有者的绘制仍拒绝准入。

该回放不伪造完整历史：清单的 `encodedDisocclusionDraws` 与 `encodedMotionDraws` 分开，receipt 维持 reactive-only，完整补帧门禁不因此解除。新增 GPU 场景检查实体、alpha discard、粒子和绳索的初次覆盖／深度；CPU 契约检查当前投影、有限性以及 discard 后不保留已退役的当前数据。下一轮客户端同时要求实际出现无效历史绘制记录。

粒子层进一步使用与原版 `IdentityHashMap` 一致的实际 Layer 身份，分别拥有 object ID、generation 和源提交历史。添加／删除无关层、改变 feature group 顺序不会重置仍存在的层。appendDraw 钩子按该层的 DrawPlan 绑定所有者，结束后立即清除；空 Storage 不创建所有者。同值但不同实例的 Layer 不互用历史，提交失败不推进，提交过一次缺席的层重新出现时重新建历史。未知／损坏 group 使其参与层失效，其他层继续保持。相同层的多个 group 仍由完整 draw manifest 保守约束；跨 group 分区变化还需要进一步按粒子 ID 对应并验证前帧变换一致性。

`6aa3448` 的 8 个客户端全部通过，Vanilla/Sodium 的普通和后处理两种入口均读回 184,184/184,184 个有限合成像素和 41,808 个 HUD presentation depth 像素；Java/native 制品身份一致。GPU 47/47 为无呈现层的 Metal 3 执行，Metal 4 分支已编译，仍需专门执行证据。

后续仍需特殊格式／材质、其余实体和方块实体、天气环境、粒子跨 group 对应、明确反遮挡在 FG 覆盖契约中的独立准入、Metal 4 合成执行、Iris／自定义渲染契约及完整清单验收；上述模型扩展不代表这些项目已完成。

## 后续目标与完成条件

| 目标 | 需要的证据 |
| --- | --- |
| 特殊材质 | DISSOLVE、GLINT_SPECIAL、发光／能量／光束／线的实际顶点 ABI、可见性、前帧参数及 GPU 遮挡读回 |
| 原版实体和方块实体 | 按具体渲染器审计全部输出，接通准确所有者，完整 draw manifest 及颜色／运动覆盖；不只扩大状态白名单 |
| 天气与环境 | 云、雨雪、日月、边界自身前帧参数与直接 draw；明确处理色彩／透明动画 |
| 粒子连续性 | 依持久粒子 ID 重排前帧顶点，新增粒子不继承旧粒子历史，提交／失败／消失事务正确 |
| Iris／模组 | 显式 previous deformation/time/clip/discard 适配契约及实际 shader-pack 测试，未知输出继续拒绝 |
| 遮挡和最终覆盖 | 动态 previous Z／对应性、反遮挡与 hand/world 隔离；真实 draw 清单逐项核销 |
| Temporal／FG 最终验收 | 独立 supportsDevice 为真；实际时域／插值图像、呈现节拍与延迟。托管 GPU 不支持时只阻塞这一验收层 |

Temporal 默认 OFF、FG 默认 false、完整 producer 总门禁保持 false，直到上述证据完整。原有六项 CPU 优化、Metal 3 回退和可选 Metal 4 路径不改变。
