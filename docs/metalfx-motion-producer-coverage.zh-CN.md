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
- 每笔 source draw 在同一 Snapshot 中冻结原版 std140 DynamicTransforms 的 160 字节和实际绑定 Globals 的 48 字节。保留 TextureMat、ColorModulator、ModelOffset、GlintAlpha/GameTime 和原始整数位；回放将冻结值上传到帧内资源，刚体及无效历史路径也不再引用可变源缓冲。前帧参数随接受的几何事务一起提交／丢弃，供后续材质和 ModelOffset 适配读取，不等同于这些适配已经完成。
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

透明手部可能只写颜色而不写原版 HUD 深度。V4 合成同时使用真实回放的手部所有权和 current Z：独立 HUD 深度有值时取较近表面，否则保留该手部自己的 Z，不能归入世界域。无效历史也保留当前 HUD 覆盖。R8≈0.75 表示几何对应准确、材质颜色需要 reactive；GLINT、APPLY_TEXTURE_MATRIX 和之后准入的 DISSOLVE 变体保持 XY/Z，但合成输出完整 reactive。它不等同于无效几何的 R8≈0.25。原生能力返回按位声明这两项语义，缺少能力的旧模块不能进入 prepared Temporal/FG。新增五项 GPU 场景覆盖无源 HUD 深度、无效历史、标准 Z、真实 HUD 遮挡和材质 reactive；尚待这一提交的 Actions 执行。

新增 GPU 对照覆盖接近相机的物体、前帧／当前遮挡、无前帧表面、明确无效所有权、NaN／越界 Z、非有限运动、手部独立深度、手部移开、reset、相机运动、标准／反向 Z、jitter 域、dilation 和透明最近表面。真实客户端读回增加合成 XY／reactive／disocclusion、下帧双域深度和 presentation depth，逐像素验证有限性与 HUD／世界选择。`6aa3448` 的 macOS 15／26 附件均为 47/47 GPU 通过，22 项新增 V4 场景的运动与遮挡预期全部匹配；Swift／MSL 已经实际编译和执行。真实客户端的 V4 合成读回仍待该提交的运行完成。

## 首次出现与历史不连续

已知所有者、当前几何和源矩阵完整时，reset、首次出现、几何／材质 manifest 改变不再直接跳过整笔运动覆盖。当前可见表面继续使用真实颜色可见性回放，写 current Z、previous Z=-1 和 R8≈0.25；前帧位置上传有限零坐标与有效性 0，刚体路径使用明确的零 previous clip W。没有捕获到当前几何／矩阵、未知管线或所有者的绘制仍拒绝准入。

该回放不伪造完整历史：清单的 `encodedDisocclusionDraws` 与 `encodedMotionDraws` 分开，receipt 维持 reactive-only，完整补帧门禁不因此解除。新增 GPU 场景检查实体、alpha discard、粒子和绳索的初次覆盖／深度；CPU 契约检查当前投影、有限性以及 discard 后不保留已退役的当前数据。下一轮客户端同时要求实际出现无效历史绘制记录。

`dd8eee8` 的 macOS 15/26 均通过 51/51 GPU，8 个客户端全部成功。真实 Vanilla/Sodium 清单分别记录 190/174 笔独立的无效历史覆盖绘制，并通过持物、OIT、粒子及 V4 合成读回；这部分已按对应制品身份验收。新增 source-uniform 回归进一步刻意覆写原始 Projection/DynamicTransforms 后使用冻结参数重放，并检查 Globals 改写／资源关闭、整数位模式、截断和非有限字段；这些增强用例还需要后续提交的 GPU 验收。

粒子层进一步使用与原版 `IdentityHashMap` 一致的实际 Layer 身份，分别拥有 object ID、generation 和源提交历史。添加／删除无关层、改变 feature group 顺序不会重置仍存在的层。appendDraw 钩子按该层的 DrawPlan 绑定所有者，结束后立即清除；空 Storage 不创建所有者。同值但不同实例的 Layer 不互用历史，提交失败不推进，提交过一次缺席的层重新出现时重新建历史。未知／损坏 group 使其参与层失效，其他层继续保持。相同层的多个 group 仍由完整 draw manifest 保守约束；跨 group 分区变化还需要进一步按粒子 ID 对应并验证前帧变换一致性。

`6aa3448` 的 8 个客户端全部通过，Vanilla/Sodium 的普通和后处理两种入口均读回 184,184/184,184 个有限合成像素和 41,808 个 HUD presentation depth 像素；Java/native 制品身份一致。GPU 47/47 为无呈现层的 Metal 3 执行，Metal 4 分支已编译，仍需专门执行证据。

后续仍需特殊格式／材质、其余实体和方块实体、天气环境、粒子跨 group 对应、明确反遮挡在 FG 覆盖契约中的独立准入、Metal 4 合成执行、Iris／自定义渲染契约及完整清单验收；上述模型扩展不代表这些项目已完成。

## 后续目标与完成条件

特殊材质的 exact 回放新增 GLINT_SPECIAL 的七属性 ENTITY_GLINT_SPECIAL ABI，previous position/validity 分别使用属性 7/8。DISSOLVE 按原版顺序执行纹理 alpha cutout、smooth 顶点 alpha 与遮罩比较、替换顶点透明度，再乘 ColorModulator；刚体回退仍拒绝 DISSOLVE。GLINT 在 cutout 后使用实际 Globals.GlintAlpha 提高 alpha，避免错误丢弃零 base-alpha 的闪光贡献。ENTITY 的 OIT_ADDITIVE alpha clamp 不套用到 ITEM，ITEM 也不错误应用 ENTITY 专有的基础纹理矩阵语义。根运动与 exact 的格式准入共同拒绝不匹配布局。

新增 17 项 GPU 对照使用原版 ENTITY/ITEM 颜色着色器的 RGBA32F 和独立 D32 片元存活作为参考，分别验证 alpha、裁剪／透明覆盖、reactive 所有权标签、零运动和 previous/current Z；失败时保存原始纹理。客户端新增真实闪光剑与特殊闪光指南针，并要求按原始 shader defines 区分的实际 draw 存在所有者及 exact 编码。`a6d30cd` 的五条工作流和 8 个客户端均成功，macOS 15/26 的完成日志分别确认 80/80，17 项材质对照全部通过；已下载的 macOS 26 原始附件中，五类像素差异计数全部为零。

源深度语义继续补齐：SourceState／surface history 记录实际 pass 是否有深度附件，cache 区分同一管线在有／无附件时的变体。R8 明确编码普通 invalid/reactive/exact=64/191/255，以及覆盖世界深度选择的 96/159/223。深度自由的覆盖使用 private current Z；普通回放同时检查独立复制的实际源深度，避免前一 overlay 改写 private depth 后让被场景挡住的后续物体写入运动。独立快照按实际源附件分组、保持 draw 顺序并随目标 resize／关闭释放。EQUAL／NOT_EQUAL 的来源测试与 private nearest depth 分开；自写深度且无法从最终附件重建前置测试的严格／非等比较，以及带偏移的等值比较继续拒绝，等待明确的执行前深度证据。

V4 能力位新增源深度策略，Java 要求完整能力 15；旧模块保守回退。V4 诊断不再调用会覆盖 world ownership 的旧手部写入。QA 增加 private world depth 读回并逐像素核对源策略、双域历史及 presentation depth。新增 7 项原生合成、8 项实际源绘制顺序、3 项自身深度偏移／透视对照，另要求真实 see-through 标签有所有者和 exact 编码；这些新增场景仍待对应 Actions 执行，不能先宣称深度偏移与完整 producer 已经验收。

`8a41e77` 的 macOS 26 为 70/98。34 项 V4 合成均通过；20 项材质用例因参考深度纹理遗漏 TEXTURE_BINDING 而提前失败，需补齐测试资源声明后重跑。顺序对照中七项的表面选择和 Z 差异均为零，XY 差异是 RG16F 在边界的半精度量化（约 0.002／0.004 像素），新对照改为沿用既有透视 oracle 的 binary16 预算，并先用独立 CPU 校准及错误方向／零运动负对照验证该预算。剩下一项揭示真实后端问题：pass 没有深度附件时仍绑定管线的深度测试状态，源颜色没有画出，运动回放却认为覆盖存在。无深度附件时现在绑定显式 ALWAYS／不写深度的设备缓存状态，并清除 depth bias；不传入原生状态包不接受的空对象。原颜色覆盖断言继续保留，另用同一管线交替有／无深度附件，核对颜色绘制与真实 NEVER 深度测试的恢复。

BLOCK 新增精确 previous-position 变体：四个源属性之后使用 previous position/validity=4/5；前帧位置加上前一接受提交的 ModelOffset，再乘前帧真实 MVP，当前顶点继续使用冻结的当前 ModelOffset。失败提交不会推进偏移，补偿不会改写保留的原始顶点。BLOCK 的 alpha cutout 按原版在纹理、smooth 顶点 alpha 与 ColorModulator 相乘之后执行，零贡献透明层保留独立裁剪语义。新增五项材质覆盖、两帧原版 BLOCK／前帧偏移 GPU 对照，以及真实移动 falling-block 的 source/history/encode 断言。新增 GPU 场景仍待对应提交验收。

原始斜面附件还揭示后续闭环目标：`f78a995` 的 current Z 已包含真实 raster depth bias，previous Z 仍是几何 clip Z。斜面样本相差约 0.00625；它可能使没有遮挡的表面被判为历史不匹配。必须进一步补齐前帧偏移并用原版前帧深度独立对照，不能只以“自身偏移用例通过”宣称 previous raster Z 已完整。

`95890b8` 的 macOS 26 原始 JUnit 记录为 91 producer + 13 first-person，104/104 通过；macOS 15 对应任务成功。五项 BLOCK alpha／偏移场景和两帧 BLOCK 历史均无覆盖、深度错误。实际客户端的 vanilla candidate 已成功，完整矩阵仍需等同一提交的各 lane 完成。

前帧 raster bias 的后续实现从实际 compiled pipeline 捕获硬件方向的 slope／constant 和源 viewport，纳入接受提交的 surface identity，使用独立 16 字节帧内 uniform。片元在任何 discard／历史分支前取得前帧 NDC 的导数，通过前帧屏幕映射 Jacobian 还原旧深度坡度；不能直接采用当前画面坡度。previous Z 加旧偏移并按 [0,1] 裁定，current Z 保持真实 gl_FragCoord.z。D32 constant 的片元／primitive 指数误差按原有 3e-6 预算约束，仅准入绝对值不超过 16 的常量；未知 viewport、非有限参数、更大自定义常量和退化映射保留 current-only ownership，等待 primitive 证据提供者。

自身偏移测试改为逐像素对照实际原版 D32，加入负偏移和透视斜面；容差保持 3e-6。静止斜面还经过生产 V4 历史消费，并关闭 dilation、用故意遗漏偏移的独立几何平面作负例，要求分别接受／拒绝。以上新增实现仍待新提交 Actions 验证，不能预先扩大 producer 门禁。

`cc4c413` 两个 macOS GPU job 均成功；已下载的 macOS 15 原始 JUnit 为 93 producer + 13 first-person，106/106 通过。前帧 Z 对原版实际 D32 的最大误差：常量偏移 0，正坡度 4.18e-7，负坡度 4.48e-7，透视坡度 3.58e-7；各材质覆盖／alpha／tag／运动／深度错误为零。正负斜面实际历史拒绝值均为 0，故意遗漏偏移的反例均为 255，且 dilation=false。该结果关闭静止自身偏移回归；旧、新斜面姿态改变的独立对照仍需补充。

附件自由颜色路径还需区分 raster writeDepth 与实际片元 FragDepth 输出。编译层从真实 SPIR-V 反射 active FragDepth，包含 MSL 缓存命中路径；前者不应禁止无附件 PSO，后者仍必须有有效深度格式。CPU 用例覆盖颜色、深度读取、直接／条件写出及未调用的写深度函数；GPU 新增声明 writeDepth 的无附件颜色绘制、带未生效 bias 的无附件绘制，以及 writeDepth=false 仍有实际片元深度输出的反例。新增验证仍待对应提交 Actions。

`45a4bdb` 的两个 macOS GPU job 均成功。下一轮增加 POSITION_COLOR 精确 ABI：源属性 0/1，前帧位置／有效性 2/3，供原版闪电与龙死亡光芒共用。回放复现 smooth 顶点 alpha、源 ColorModulator 和原版雾的透明贡献；只在这类可见性依赖 Fog 的源 draw 捕获并冻结真实 40 字节 Fog，其他路径不增加该上传。闪电 seed 或状态类变化会分配新几何 generation，不能把重新随机生成的分支对应成连续变形；龙随机光芒按固定种子及完整 draw／顶点数量清单匹配，数量变化仍为 current-only。

`45a4bdb` 的 macOS 26 原始 JUnit 为 96 producer + 13 first-person，109/109；新增无附件 writeDepth 与 writeDepth+bias 各 520 个原色覆盖像素，运动表面／XY／Z 错误均为零。该提交 Vanilla MetalFX fallback 客户端也已成功。新加的前帧偏移动态对照用原版实际旧帧 D32／颜色拟合屏幕深度平面，再以独立 GPU 当前表面点映射到旧平面；覆盖不同旋转／缩放的正交与透视姿态，沿用已有 3e-5 透视误差预算，并要求错误使用当前坡度的负例误差超过 3e-4。拟合器有独立 CPU 数值及退化／空覆盖反例；动态 GPU 对照仍待对应提交执行。

原版 additive OIT 的 sampleColorForAccumulation 也以 alpha 预乘 RGB，所以所有 OIT_ACCUMULATE 的零 alpha 均不应覆盖运动。新增原版 OIT shader／真实零 absorbance 纹理对照，用实际 RGB 贡献判断 additive 可见性（其输出 alpha 固定为 0），验证实体与 POSITION_COLOR 的正、零贡献。真实客户端加入视觉闪电、普通／死亡龙及死亡 rays，继续要求实际 source、owner、匹配历史和编码证据；新增路径仍待对应 Actions 验收。

天气路径复用 core/particle；审计该原版着色器时发现 reduced particle VS 曾假定 lightmap alpha=1。运动 VS 现在读取真实 UV2／Sampler2 的 alpha，并按原版与顶点 alpha 一起插值，fragment 再乘纹理与 ColorModulator 后执行 0.1 裁剪。Sampler2 的实际分配／采样身份加入粒子 surface history。新增三项 GPU 对照直接渲染原版 particle shader，覆盖光照纹理 alpha=0、低于 cutout 和可见；旧粒子输入／重排／首帧回放测试也显式绑定真实采样资源。新增行为仍待对应 Actions，不能据此宣称天气入口已接通。

`924dd31` macOS 15 为 120/121。新增雾、实际 additive OIT 正／零贡献、Fog 重写／关闭快照和透视换姿态均通过。唯一失败是正交换姿态参考的整四边形单平面自检：GPU 光栅取整使两个源三角形不再共用同一 D32 平面，拟合残差 6.61e-6；运动 Z 对照最大误差 3.60e-6，原有 3e-5 预算内且错误像素为零。参考现在给原版两三角形分别写独立颜色标记，以各自实际 D32 拟合平面，继续要求每个平面残差 <3e-6，保持运动 Z 与负例阈值。新增 CPU 反例证明合并两个不同的 primitive 平面不能通过该自检；修正仍待新 Actions，不提高任何旧容差。

`ea95dae` 的五条工作流和 8 个真实客户端任务均成功，两个 macOS GPU 任务也均成功。已下载的 macOS 26 原始 JUnit 为 111 producer + 13 first-person，124/124。逐三角形旧深度平面的最大残差为正交 5.78e-8、透视 7.64e-9；运动 previous Z 的最大误差分别为 9.13e-6、5.57e-7。错误采用当前坡度的独立负例分别偏差 0.00198、0.000368。三项 particle lightmap alpha 与原版颜色覆盖对照均无错误；闪电、普通／死亡龙和 rays 的实际 source/history/encode 客户端断言同时通过。该轮不包含下一段的天气接入。

天气现在在真实 `renderWeather -> drawIndexed` 之后读取当前 pipeline、绑定和普通／OIT stage，只有颜色阶段捕获一次雨／雪历史，其他 OIT 阶段只计数。按实际上传的四顶点 XYZ／UV 和列坐标建立持久身份；捕获原版实际采样的雨速、雪花随机漂移与时间，不能重新运行随机数发生器。雨雪各有独立所有者，历史只随接受的源提交推进；失败、缺席、reset、关闭与不完整的新上传不会复用错误旧数据。

对应点按当前纹理图案在旧四边形 UV 基中的位置重建，包含雨水下落、雪花横向／纵向漂移、实际 sampler repeat 和时间包装。额外 24 字节 previous-position／validity／old-local-UV 流在片元逐像素检查旧柱面边界；超出旧源范围继续写当前 Z 和无效历史标签。源当前顶点冻结到 GPU 帧内内存，并在源 pass 关闭后、手部改写世界深度前回放，雪的原始非零 firstIndex 也重基到独立快照。原版 camera-cell 的 0/0 方向只替换为有限的零宽四边形，保持空覆盖。新增五项原版颜色 GPU 对照及普通／OIT 混合雨雪真实客户端清单；本地完整 1175 项、零失败／错误、43 项条件跳过，另有畸形新上传的针对性回归。新增天气行为仍待对应 Actions 验收，完整 producer 门禁保持关闭。

`f4afd59` 的 Vanilla FX 原始客户端附件确认天气清单 `verified=true`：普通 weather 为 118 次 draw／118 次 hook，116 次完整运动与 2 次无历史覆盖；OIT accumulate 为 164／164，162 次完整运动与 2 次无历史覆盖；雨／雪所有者各 139 次完整历史编码。两个原始运动读回均为 184,184 个有限合成像素、41,808 个手部像素，普通与后处理 HUD 深度隔离通过。任务随后在 GameTest 线程关闭 recorder 与渲染线程更新资源身份竞争时失败，不能把该任务标为成功。采集器关闭改为在实际渲染线程执行，原清单／图像断言保留；需后续完整任务重验。

lightmap alpha 的审计继续覆盖 BLOCK／ENTITY／ITEM。BLOCK 按原版在 VS 中先乘顶点 alpha 再插值并参与 cutout；ENTITY 独立插值 lightmap alpha，在纹理 cutout、DISSOLVE 替换及 GLINT alpha floor 之后相乘。EMISSIVE 实体不采样，ITEM 包括 GLINT_SPECIAL 始终采样，UV2 仍是原版物理属性 4；阴影不错误继承实体光照语义。Sampler2 资源／采样身份也纳入这些实际依赖光照可见性的表面历史。新增十项原版颜色 GPU 对照涵盖顺序负例、零 lightmap、cutout 前后区别及 emissive 特例，手部 ITEM 与 BLOCK 回放对照补齐真实绑定。本地完整 1273 项、零失败／错误、43 项条件跳过；新增材质行为仍需对应 Actions 验收。

`ec63dec` 的 macOS 15／26 GPU job 均成功；macOS 26 完成日志逐项确认 126 producer + 13 first-person，139/139，包含五项雨雪及十项 lightmap 场景。原始 GPU 附件下载遇到存储超时，尚未复核其中逐像素统计。真实 Vanilla 客户端仍在关闭 recorder 后报错，渲染线程调度本身没有解决。关闭入口补充异常安全：最终 IO 前先退役全局入口，以 Java 资源关闭语义尝试关闭采集服务和 trace，并保留首错／suppressed 错误；不会让已关闭 recorder 留给之后的资源失效事件。两个独立文件系统故障用例先建立可通过的捕获，再分别破坏最终结果／manifest 路径，要求保留失败、关闭两个所有者并拒绝完成门禁。GameTest 同时保存和打印原始失败，以便下一轮日志定位最初异常；不减少断言或提高容差。

`14cc12a` 的 Sodium 真实 FX fallback 客户端任务成功，包含此前的雨雪与特殊材质清单、普通／后处理手部深度和原生合成断言；下载后的 9 项捕获与实际 draw census 均通过，但磁盘 trace 仍为 active／manifestComplete=false，不能把任务成功视为完整 trace 验收。新增关闭后终态 snapshot 与磁盘 manifest 的双重检查，以及工作流上传前的独立复核；独立成功关闭和两个 IO 故障测试保留严格完成门禁。该提交 Vanilla FX 未完成，不能据此宣称整个客户端矩阵通过。

云的后续接入读取真实上传的有符号三字节 CloudFaces 和实际绑定的 44 字节 CloudInfo，并核对源 GPU face backing。按原版准备阶段实际选出的中心单元、完整时间周期和相机纹理 tile 建立完整坐标／朝向／内外面 tuple 身份，避免在 torus 边界重新除以 12 产生不同的单元；tuple 按接受的源提交分配持久 ID，无坐标截断或 hash 碰撞。CPU 解码固定原版 12／4／12 网格用于前帧对应与身份；当前 GPU 栅格保留原版 CloudFaces 程序化顶点表达式，冻结实际 CloudInfo／CloudFaces 到同次提交拥有的缓冲切片，复用真实 source MVP 与 Fog。新增／改变朝向的面保持当前覆盖但不伪造前帧。

普通／OIT、FAST／FANCY 共用实际 indexed draw 入口，只在颜色阶段推进一次历史。reload、消失、reset、失败源提交与 renderer 关闭保持独立事务。新增 17 项原版程序化 cloud shader／真实 texel-buffer 对照覆盖六面、内外 winding、负／奇坐标、Fog、零 alpha、出生和自身 XY/Z；CPU 使用独立六面顶点表及故障／连续性回归。真实客户端新增四个云层向上视角，要求各自读回有效世界 ownership、有限 XY／双 Z 和 reactive 标签，并核对四条实际管线 source/history/encode 清单。本地完整 1293 项、零失败／错误、43 项条件跳过，客户端编译和统一契约通过；`dcb7c0e` 的 macOS 15／26 均在新增云 GPU 对照中失败。已下载 macOS 26 原始产物：原有 139 项通过，17 项新增云对照中 12 项存在覆盖缺失，错误 XY／Z／标签均随缺失像素出现；未提高容差或降低覆盖期望。后续修复保留 GPU 原版程序化顶点运算，另加改写并关闭原始 CloudInfo／CloudFaces 后仍使用冻结切片的回归，共 18 项云场景。修复后的运行行为仍待对应 Actions，不能提前视为全量 producer 完成。

后续环境审计还需保持背景绘制顺序：日月／星辰原版没有深度测试，但发生在地形之前，不能因无 depth state 就按前景 overlay 回放。接入需明确背景深度／后续遮挡语义。previous Z 本身也不能区分同深度的错误表面、前帧透明度为零或已被 discard 的对应点；仍需前帧可见 ownership／element 身份与完整清单验证。完整 FG 门禁继续关闭。

ordinary staged 的 canonical ENTITY／ITEM／BLOCK／PARTICLE 增加一项必要拒绝条件：已接受前帧的 DynamicTransforms 缺失，或 ColorModulator.a 非正时，不消费其旧位置作为 exact correspondence，转用现有 current-only ownership。当前真实颜色覆盖／current Z 保留，previous Z=-1、XY=0、owned-invalid=64，V4 将该覆盖设为 reactive／disocclusion=255。失败源事务不推进该快照；成功的 current-only 源事务可供下一连续可见帧恢复。零 alpha 的 opaque／GLINT 仍可能写入 RGB，此处对它们保守拒绝，不声称所有零 alpha 均 discard。正 alpha 也不是完整 previous-visible 证书：旧纹理／顶点 alpha、同深度其他 element、背景与完整遮挡仍未准入，unknown／indirect／unowned 清单的 fail-closed 机制保持独立。

对照使用原版颜色 shader 绘制同 Z 蓝色背景及 alpha 0→1／1→1 红色前景；ENTITY／ITEM 的纹理 cutout 先于 modulator，故零颜色贡献场景明确采用实际 alpha blending，不能把 opaque 写入误认成 discard。测试从真实 pass 冻结同 owner、generation、geometry、material 的源 uniform，执行 packaged motion MRT 和 native V4，另显式模拟仅移除上述 guard 的错误准入。GPU 与 CPU 事务结果必须绑定各自新 SHA；测试存在不等于验收通过，准确结果见当前整合 PR 的逐项证据。

普通 staged PARTICLE 另冻结原版 `DefaultVertexFormat.PARTICLE` 的实际 packed `Color.a` 必要拒绝证据，与 Position 一起在 `freeVertexData` 释放真实切片前捕获；不保留源内存，也不从当前颜色补旧证据。旧 batch 含零 alpha 顶点或缺少这项捕获时，整个对应 draw 使用上述 current-only 路径。粒子 ID 跨 batch 合并／重排时，从复制旧几何的同一次匹配保留所有旧来源，并汇总其拒绝证据；不能只沿用一个代表 draw 的 alpha。证据随同一个源提交事务推进，失败／丢弃不推进；成功 current-only 源提交后的连续可见帧可恢复原有 exact 条件。

该项先在 `75abf4c7` 复现：[双 macOS 原始运行](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39564277833) 各 196 项中 7 项失败。真实 QUADS、固定 modulator=1／材质／位置／UV／索引与同 Z 蓝背景下，原版 particle shader 的旧红贡献为零，当前 768 像素却被标 exact 并被 V4 接受；分开的旧 A=0、B=255 合并成交换 ID 的当前 draw 时，A 的 384 像素同样误接收。修复控制保留真实分片捕获、覆盖源内存后释放、提交／丢弃／恢复、两种合并 ID 顺序与仅移除新 guard 的负例。新 SHA 的测试结果仍须独立核对。此 guard 对含零顶点的部分可见 draw 也保守拒绝；非零顶点不等于前帧可见，纹理内容／版本的现有跟踪保持独立，部分 alpha／cutout、同深度其他 element 和完整 previous-visible 仍未准入。其他 shader family 不据此扩展。

普通 canonical PARTICLE 的 partial-alpha 后续统一处理：在同一真实释放边界冻结 unsigned `Color.a` min/max，并将每个源 draw 的已捕获 modulator 单位值证明随同事务保存。ID 合并从复制旧几何的同一 `previousSources` 汇总全部 min/max 及 uniform 证明；任一未知或 partial 旧来源保守拒绝整笔当前 draw。仅保留旧顶点 min=max=255、全部旧 modulator.a=1 且当前 modulator.a=1 的既有必要条件；缺失、非有限、负值、超过范围或非单位值不 clamp 为通过。当前可见部分仍用既有 current-only：保留覆盖／current Z，previous Z=-1、XY=0、owned-invalid64，V4 reactive／disocclusion255。失败／丢弃不推进，成功接收 full-source-input 帧后可恢复原有 exact 条件。非零或这些必要条件均不证明完整前帧可见。

[未修改生产的 `272c0d05` 基线](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39578861989) 已在双 macOS 各复现 264 项中 48 失败：新 GPU 24 项中 20 失败、新 CPU 41 项中 28 失败，原有 199 项通过。测试直接使用 Minecraft 26.3 的 ordinary opaque／translucent pipeline，其深度均为 GREATER_THAN_OR_EQUAL、writeDepth=true、bias0；OIT 不在该矩阵内。原版 fragment 的 texture×插值 Color/lightmap×ColorModulator 与 alpha<0.1 discard 由原始 shader 执行，蓝背景独立绑定 white texture、modulator1，前景 atlas 与固定 alpha1 的 lightmap 分离。RGBA 对 background-only／foreground-over-background 作逐像素差分，确认 25/255 无贡献、26/255 有贡献，覆盖 255×0.1 的相邻 float、128/255×0.19、固定 atlas alpha38/255 下 vertex128→255、插值区间、当前 modulator 改变、两种顺序分合 batch 及提交事务。12 个 case 实测旧无颜色贡献区域却获得 exact/V4 接受；可见 partial 也会保守失去 exact，这是安全覆盖代价，不是完整 cutout／Temporal／FG 实现。min 偏低只能触发拒绝，不能据此声称整个旧 draw 必 discard；该结论还需可靠的旧采样 alpha 上界与整个 draw 的 max 总 alpha 上界<0.1。纹理版本机制、其他 family 和 native ABI 均未扩展，新 SHA 的 GPU／客户端结果仍须逐项验证。

partial-alpha 策略只审计 ordinary；`d08d4e38` 的 [Vanilla FX 客户端](https://github.com/21Z121Z1/MetalUniversal/actions/runs/39581769946/job/119161536725) 暴露 shader 路径判定也把新的 ordinary 输入条件施加到 OIT，该场景未满足原有 OIT exact-motion 清单断言。该断言读取 exact 编码计数，current-only 在管理器另行计入 disocclusion；因此这条首错本身不能证明当前覆盖未写入，旧运行的实际 OIT current-only 覆盖尚未独立读回。后续限定已知 canonical OIT pipeline 身份与 OIT defines 保留原有零 modulator／零或未知旧顶点拒绝规则，不新增 OIT partial 可见性准入；未知 pipeline 不能只靠 OIT flag 绕过 partial guard。新增真实 canonical OIT pipeline 的旧策略回归，原客户端断言保持，修复后 SHA 仍须完整验证。

阴影可见性审计发现，旧运动片元使用 flat 顶点 alpha、未手动 clamp UV，且遗漏 ColorModulator.a；与原版颜色着色器的 smooth alpha 和采样逻辑不同。回放改为同样的 UV clamp 和插值透明度乘当前源 modulator。新增六项 GPU 对照直接渲染原版 shadow shader 的 RGBA32F 贡献，检查普通阴影、越界 UV、渐变 alpha、modulator=0 和透明纹理。`6f42f0b` 的 macOS 15/26 均为 57/57 GPU，通过原始附件核对六项覆盖差异均为零。

`200c63a` 的五条工作流和 8 个客户端任务成功。已下载的 macOS 15 JUnit 确认 58/58，包含刻意覆写源 Projection/DynamicTransforms、改写并关闭 Globals 的增强用例；Sodium 两种 HUD 入口仍有 41,808 个有效手部像素，184,184 个合成像素全部有限。macOS 26 与 Vanilla 原始附件下载遇到制品存储连接失败，尚未完成附件级复核，不能把上一提交的附件替代为本次证据。

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

整合分支进一步在 buildMesh 写入前失效旧 CPU faces/backing/origin；捕获停用或同帧重复替换退役 generation，失败重建不能借用原地复写前的数据。云世界／HUD 由独立 ownership 通道判别，159／191 分别是 overlay／普通 reactive 标签，不能把 159 当成 HUD 身份。OIT 云不必写原版世界 D32，云准入按世界有效表面与 reactive 像素排除全部 HUD 覆盖，并独立读取原始通道核对；不透明世界／手部深度检查保留。主捕获和独立 terrain 控制都读取关闭后的快照及磁盘终态；终态头以流式 reader 跳过大 producer payload，保留完整失败计数诊断。仍需准确整合 head 的 GPU／客户端验收。
