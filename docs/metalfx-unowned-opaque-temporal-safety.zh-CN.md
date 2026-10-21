# ordinary opaque 未归属写入与 Temporal 源帧安全

本轮仅处理 `minecraft:pipeline/opaque_particle` 的未归属颜色执行。它与已有 owned-invalid 回退不同：后者已经写入当前覆盖、当前 Z 和拒绝旧历史的标记；缺少 staged sample 的执行不会自动获得这些像素标记。本轮不把 FG 的全 exact 门禁搬到 Temporal，也不接入像素 owner 原型资源。

## 既有客户端差额的证据边界

准确源 f9bc19840950a1f15ef7c7984cacf5f1a6318cb7 的 Vanilla FX job 119299968021（run 39622384695）累计 ordinary opaque 数值为 actual=483、hooked=483、owned=482、current-only=482、exact=0。最后封存源帧为 frameId=1566 / epoch=27，其 opaque 条目 actual=hooked=owned=current-only=1。这两个统计粒度不同。

旧收据没有记录每次 sample-null 的源帧、stage 或原因，因此不能从该文件确定第 483 次发生在哪帧，不能断言它真的写入可见像素，也不能区分 capture 缺失、生命周期结束或重复 color replay。最后一帧的总 unowned 数还包含其他程序，不能据此归因 opaque。旧运行实际选择 SPATIAL，temporalEncodes=0；它没有发生可声称的物理 Temporal 错误。

源码可确认 `takeExecute` 在没有 capture、非颜色 OIT stage 或重复颜色重放时均可返回 null，调用方随后直接返回。FG source manifest 的拒绝并未直接阻止 Temporal dispatch。该组合需要独立的真实可见负例，不能仅以计数差额定性。

## 冻结的独立负例

基线 cb3fc9aa13ddb9bc024bc775d3affa6aaf8a9926：64×48，原版 canonical opaque particle shader / QUADS / 白 atlas 与 lightmap / 全 alpha / 实际 D32。已接受蓝背景与当前红粒子同 Z=0.5，红粒子占 768 像素。当前真实 draw 不调用 capture attach/transfer，再进入生产 `drawStagedMotion`。读取原色 RGBA/D32、object tag、native V4 motion/reactive/disocclusion 和生产源帧准入函数。

基线只提取已有源准入谓词以便测试，未改变它的行为。run 39633160506 的 macOS 15 / 26（jobs 119337154210 / 119337154060）均实际复现：原色可见 unowned=768、V4 错误相机历史接受=768、生产源准入=true，motion 前后原色 RGBA/D32 不变。Java 25 预编译与邻近 JVM 控制也通过。此处没有调用物理 Temporal scaler；有硬件能力条件下的生产模式选择与帧准入、真实 native V4 结果分别留存，不能改写成支持硬件上的时域画质事故。

## 最小保护与代价

生产 backend 的真实 ordinary opaque 颜色执行计数，与消费 staged capture 时的 owned 结果按同一源帧/epoch 对照。当前帧只要存在没有 hook、sample 缺失、重复 color replay、未知间接对应或计数冲突，就不允许进入 Temporal。已有 owned-invalid / current-only 不要求 exact；没有 opaque 执行的帧不要求 FG manifest 完整。这不是像素可见性证书：一个实际执行但最终没有贡献颜色的 unowned draw 也会保守拒绝整帧。

判断发生在 V4 准备之后、真正 scaler dispatch 之前，不能缓存准备前的允许结果。拒绝时复制当前颜色、保留当前画面，禁止该源帧的 Temporal encode 与成功提交历史事务；丢弃 pending motion/receipt，不交换该源的深度历史，不推进 jitter phase。下一帧仍使用 reset，只有随后真正安全且提交成功的源帧才能恢复历史。旧异步回调的源 stamp 检查继续保留，resize/reload 的 reset 语义不改变。SPATIAL 诊断模式不被当成真实 Temporal 编码，其原有采集历史继续工作。

生产增量没有纹理分配、GPU pass、采样或 native ABI 变化。增加固定大小帧计数，以及仅诊断时最多 64 条 lifetime 缺口记录；超出后仍累计 omitted 数。每条记录保留源 frame/epoch、ordinary-color、missing-capture 或 duplicate-color、实际/已 hook/owned 计数和存活 capture/execute 数，缺失的旧事件无法追溯补造。一个缺口会使整个该 Temporal 帧失去时域处理，已有连续 opaque owned-invalid 覆盖不受此准入条件禁止；这项范围和画质代价明确保留。

本地参考源码下载脚本收到 HTTP 403 并退出，没有改用其他通道下载。真实原版 shader fixture 的 Java 25 编译和 GPU 执行由用户仓库 Actions 提供。

## 本轮边界

PR91、main 与旧 PR90 未推进。本轮不写仍待明确授权的 PR91 正文，不合并、不删除、不 force push、不发布，不改变安全或费用设置。完整 previous-visible、其他 family、透明/OIT、Iris 和物理 Temporal/FG 画质、节拍、延迟继续保留原有门禁。
