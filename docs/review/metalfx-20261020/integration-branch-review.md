# 当前整合分支取舍复核

观察 2026-10-20T21:06:09.261495+00:00；main `9930a2e3ae9d54756f79693c1e8c60704975372c`，候选 `36a9f3b1d7d82be73091ce091a037d7460a6181b`。19 heads 从最新已远端验证恢复快照读取。

| 分支 | 当前 head | candidate 祖先 | 未匹配 / patch等价 | 取舍 |
| --- | --- | --- | --- | --- |
| agent/metalfx-assembly-20260924-01 | `af30078ebfc6677f063361b88dbed06c5e08be56` | False | 12 / 0 | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-cloud-assembly-20260924 | `cd5f8ddd939d38e05cd6ac56e466dcef69877d3c` | False | 10 / 0 | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-cloud-workbench-20260924 | `c0dd7d9e35f970611d161bc950548d565e372ad6` | False | 10 / 0 | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-contract-completion-20260924 | `1888d3e28ba0fb0cb6ce043977db658d9c628c62` | False | 9 / 0 | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-data-contracts-20260924 | `cd5f8ddd939d38e05cd6ac56e466dcef69877d3c` | False | 10 / 0 | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/upstream-26.3-prepared-20260923 | `f6f5d8391ddc4d34f66a53ee9086665caba79a25` | False | 14 / 0 | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| agent/upstream-26.3-renderpearl | `7a6f94eb4165eb7c763ed06a247a1e9dc6fbdb82` | False | 17 / 0 | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| agent/upstream-26.3-workbench-20260923 | `be6f578151ca7a765e67c0d7e7aa216c41774d02` | False | 16 / 0 | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| codex/consolidated-metal-20261020 | `36a9f3b1d7d82be73091ce091a037d7460a6181b` | True | 0 / 0 | 当前工程候选；必须准确 SHA 的整轮 CI／真实客户端通过并经父侧审核后，正常纳入 main |
| codex/mcopt-semantic-convergence-20261005 | `7b83bc4df3d93bc979a8c34caa551ad818595ce9` | False | 183 / 0 | 未晋升的默认关闭实验优化，部分有旧版本运行证据；183为提交数、70新增文件，多模块并非全依赖Sodium；私有适配锁0.9.3-alpha.1，当前0.9.2。整包持久保留、不重做整套实验；palette为保存时编码等价校验失败。详见PR89-decision.zh-CN.md |
| codex/metal-renderer-research | `a43637d08164a2e95116edaa4e7dda1b4fd2a155` | False | 8 / 6 | 已选择性纳入 weather、lightmap、云冻结源与关闭终态增量；本分支保留更严格 streaming／生命周期／颜色／R32F／粒子／oracle；需原写入者明确停写 |
| codex/metalfx-official-audit-20261020 | `fe547607b3bdfb2dda9d373fd3bbe8415f19e328` | True | 0 / 0 | 审计历史为整合分支祖先；保留恢复锚点 |
| codex/p1-framebuffer-determinism | `1548c41d9e0ea65fad932740854d75804ff81d07` | False | 6 / 0 | 选择相机／固定源时间收据；排除忽略 alpha 的弱验收；完整 RGBA 与独立 terrain 8+1 保留 |
| codex/sodium-client-e2e-20260923 | `9448f2d8ab7c0fe253ad2dd4158ec8bd3242f2fe` | True | 0 / 0 | 已为 main 祖先；保留恢复锚点 |
| codex/vanilla-fx-aff7f0f0-validation | `65c711e2b6c9e080c70bd3086cbb8ef2e49dceac` | False | 4 / 0 | 隔离的固定产品源码验证 harness；保留失败及身份自检，不能作为产品功能合入 |
| main | `9930a2e3ae9d54756f79693c1e8c60704975372c` | True | 0 / 0 | 最终保留；当前 canonical base，按正常 fast-forward／合入流程更新 |
| research/modernization-backlog | `7f7852843b228486e9d8e6cc3e98bfc3bf0ff16a` | False | 769 / 0 | 排除归档 26.2 runtime 的整支回滚；795 个独有提交已由恢复标签保存 |
| upstream/26.3-fabric-pr | `81b7e61821be46b6ad1d787bee6077a7208ebeaa` | False | 27 / 0 | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| upstream/renderpearl-26.3 | `4403a941091ecb7c25d41b8398d6ddf270b8c0cd` | False | 16 / 0 | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |

精确提交列表见 integration-latest-branch-analysis.json；patch-id 不能替代语义对照。原PR选中的weather/lightmap/cloud/close增量已被更严格生命周期、颜色、R32F、历史和终态控制整合；原回退/测试失败证据保留。未匹配历史提交均由已验证的远端恢复标签保护，不自动整支合入。

本轮实际源码仅推进自己的整合分支。准确CI未完整结束、原写入者未确认停写，因此未合入main、未删分支。
