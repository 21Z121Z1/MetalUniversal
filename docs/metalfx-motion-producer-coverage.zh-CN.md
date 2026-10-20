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

客户端导出 `motion-source-draw-coverage.json`：按真实管线列出后端绘制调用与 staged hook 调用，并分别保留三阶段／所有者／排队／编码计数。未覆盖管线保留在清单中，不能记为 NOT_PRESENT。绘制调用数不是 fragment 数；indirect 的调用数也不证明其中每条命令非空。

## 模型、材质和实际回放

- 每个真实颜色 draw 在同一历史事务中保存实际 projection/model-view、去抖动投影及材质资源 allocation/generation。纹理更换、采样语义或管线改变时，不能仅凭顶点数量继续复用。OIT 的每帧系数目标不当作材质身份。
- 世界逐顶点回放使用前一 draw 的实际 MVP；手部继续使用独立 HUD 历史。所有 exact draw 都复制当前源投影，避免后续全局 UBO 覆写。
- 不可变顶点快照在附加矩阵、材质和提交时共享数据，保留对外防御性复制，避免每个 draw 重复复制整份姿态顶点。
- `CustomFeatureRenderer` 必须在取得 vertex builder 前激活所有者。`574a4a7` 的 Sodium 实机客户端清单显示 OIT entity 有 10 次 hook、0 次所有者，暴露了旧 callback 包围位置过晚；后续 CI 必须要求所有者、完整历史和实际运动编码，不能只验 hook 次数。
- 新审计模型路径为附魔台、讲台书本、饰纹陶罐、潮涌核心的 shell/cage/wind/eye、头颅，以及水晶及其 ENTITY beam、经验球、自身模型与透明覆盖的潜影贝导弹、两种风弹。每个对象仍需完整 manifest，空讲台不算几何，未知状态／管线继续拒绝。
- 增加实际 Minecraft `core/entity` 颜色着色器的独立光栅参考，检查世界 draw 的不同 model-view、源抖动及源投影覆写。客户端读回 `raw-source-motion/` 明确记录源提交、历史 epoch、有效像素、有限向量和当前 scaler 模式。

后续仍需逐片元对应／前帧 Z、特殊格式／材质、其余实体和方块实体、天气环境、粒子身份重排、Iris／自定义渲染契约及完整清单验收；上述模型扩展不代表这些项目已完成。

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
