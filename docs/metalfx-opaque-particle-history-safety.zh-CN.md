# Ordinary OPAQUE_PARTICLE 的保守历史准入

这是生产安全修复，尚未接入逐像素 owner 历史。已验收的诊断原型 `0fbe2641` 证明：同 Z 的旧 B 后被 A 完全覆盖，旧 B 原色贡献为0，但 geometry/depth/input witness 仍会让 motion MRT/native V4 假接受768像素。冻结失败基线为 `9066e43f`，不追改为通过。

生产目前没有来自 accepted 原色帧的同源 pixel owner/generation/depth pair，因此 `minecraft:pipeline/opaque_particle` 无条件禁止 previous-exact。planner 与 Manager 的统一 staged/direct 准入口共同使用这一限制。不会把旧几何、alpha 或深度改名为可见证明，也没有默认关闭的安全开关。

继续使用既有 current-only：保留当前覆盖及 current Z；owned-invalid=64、previous Z=-1、XY=0，native V4 reactive/disocclusion=255。其他 family、ordinary translucent、OIT、shader-source/producer 总门禁均保持原范围。

覆盖率代价是连续真正同 owner、全 alpha、可见 opaque 粒子也不再拥有 exact 历史。8项真实原版 shader→history→motion MRT→native V4 控制覆盖同 Z 遮挡、连续正例、两种 ID 顺序的分合 batch、discard/源提交未接受后的连续帧。生产应始终拒绝历史；仅在测试中去掉新的准入拒绝，应重新得到768 exact像素，核心遮挡场景重新出现768假接受。所有旧 source input/geometry/material 前提仍逐项断言，原色 RGBA/D32 在 replay 前后逐字节相等。

CPU 事务控制在失败帧改变实际 vertex alpha，再 discard，要求下一帧仍读到 accepted 旧证据；成功 current-only 提交继续推进几何/input 事务，但即使后续输入全可用，opaque exact 仍禁止。这里没有强制制造 native GPU 故障。

本修复新增历史纹理字节、GPU pass 和 sampling 均为0；使用原有 current-only 上传/编码路径。CPU 新增固定 pipeline 身份判断。额外 reactive/disocclusion 覆盖可能减少时域可复用区域，不能宣称净性能、FPS 或画质收益。实际 current-only/原色 draw 数与 GPU 像素计数保留在准确 SHA 的证据中。

恢复 previous-exact 的后续产品接入仍需单独审核：真实 draw 前稳定 element 输入、fast/indirect/未知后续 writer 的失效观察、GPU completed 后才接受同源 owner/depth pair、完整资源生命周期与全场景 producer 证明。40WH shadow 原型不随本修复进入产品。Temporal/FG、真实画质、节奏和延迟继续未准入；不授权合并、删分支或发布。

本轮验证入口为双 macOS 的 `hostedMetalMotionProducerTest`，以及原客户端 harness 的 Vanilla/Sodium default requested-temporal moving 两 lane。后者保留全部实际 GameTest、画面、呈现、重载和终态断言，仅把该 opaque 历史契约改为严格的 exact=0 且 encodedDisocclusion>0；OIT exact>0 的要求不变。完整结果及可恢复 refs 以不可变审查证据 tag 为准。
