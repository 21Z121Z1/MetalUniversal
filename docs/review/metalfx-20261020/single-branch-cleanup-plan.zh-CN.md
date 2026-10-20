# 单分支收敛审核方案

观察：2026-10-20T21:01:01.597812+00:00。候选 `36a9f3b1d7d82be73091ce091a037d7460a6181b`；19 个远程 heads。建议最终保留当前默认分支 `main`，正常合入有效整合内容。当前 deletionReady=false。

## 当前持久恢复证据

- 最新快照 20261020T210011Z；19 个 head 均有持久注释标签，准确 ref/SHA 清单见 recovery-36a9f3b1-snapshot.json。新产品候选 36a9f3b1 已覆盖；旧 C7 和固定 harness 65 的恢复锚点保留。
- 完整 bundle `MetalUniversal-precleanup-20261020T210011Z.bundle`，16317403 bytes，SHA-256 `8a5ba8a1d056c3cee14beda0685c92d5bfd14b2424b7ab50a134426ce9f7cc5d`；bundle verify 通过。
- 从 GitHub 远端全新 mirror clone，19/19 注释标签 peeled commit 一致，fsck --full --no-reflogs 通过；最新 heads 复读一致，非 shallow、非 partial。
- 29494 个可达对象、579 个小 blob 扫描：LFS 指针 0，19 head 的 gitlink 分支数 0；详见 recovery-external-object-scan.json。
- 2a 的原始异常保留负例现已由 36a9f3b1 的正常历史覆盖，不再是待推送本地修改。
- 标签为非 v*，不触发发布。writersStopped=false、deletionReady=false；未删除、合并、改默认/保护或发布。

## 分支逐项取舍

| 分支 | 当前 head | 处理依据 |
| --- | --- | --- |
| agent/metalfx-assembly-20260924-01 | `af30078ebfc6677f063361b88dbed06c5e08be56` | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-cloud-assembly-20260924 | `cd5f8ddd939d38e05cd6ac56e466dcef69877d3c` | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-cloud-workbench-20260924 | `c0dd7d9e35f970611d161bc950548d565e372ad6` | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-contract-completion-20260924 | `1888d3e28ba0fb0cb6ce043977db658d9c628c62` | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/metalfx-data-contracts-20260924 | `cd5f8ddd939d38e05cd6ac56e466dcef69877d3c` | 选择源相机／raster／收据；旧 CUTOUT 对照失败和旧深度历史不带回；以 V4 与新负例替代 |
| agent/upstream-26.3-prepared-20260923 | `f6f5d8391ddc4d34f66a53ee9086665caba79a25` | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| agent/upstream-26.3-renderpearl | `7a6f94eb4165eb7c763ed06a247a1e9dc6fbdb82` | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| agent/upstream-26.3-workbench-20260923 | `be6f578151ca7a765e67c0d7e7aa216c41774d02` | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| codex/consolidated-metal-20261020 | `36a9f3b1d7d82be73091ce091a037d7460a6181b` | 当前工程候选；必须准确 SHA 的整轮 CI／真实客户端通过并经父侧审核后，正常纳入 main |
| codex/mcopt-semantic-convergence-20261005 | `7b83bc4df3d93bc979a8c34caa551ad818595ce9` | 排除未验证的私有 Sodium 0.9.3-alpha 变更及失败 palette 验收；183 个独有提交持久可恢复，详见 branch-review.md |
| codex/metal-renderer-research | `a43637d08164a2e95116edaa4e7dda1b4fd2a155` | 已选择性纳入 weather、lightmap、云冻结源与关闭终态增量；本分支保留更严格 streaming／生命周期／颜色／R32F／粒子／oracle；需原写入者明确停写 |
| codex/metalfx-official-audit-20261020 | `fe547607b3bdfb2dda9d373fd3bbe8415f19e328` | 审计历史为整合分支祖先；保留恢复锚点 |
| codex/p1-framebuffer-determinism | `1548c41d9e0ea65fad932740854d75804ff81d07` | 选择相机／固定源时间收据；排除忽略 alpha 的弱验收；完整 RGBA 与独立 terrain 8+1 保留 |
| codex/sodium-client-e2e-20260923 | `9448f2d8ab7c0fe253ad2dd4158ec8bd3242f2fe` | 已为 main 祖先；保留恢复锚点 |
| codex/vanilla-fx-aff7f0f0-validation | `65c711e2b6c9e080c70bd3086cbb8ef2e49dceac` | 隔离的固定产品源码验证 harness；保留失败及身份自检，不能作为产品功能合入 |
| main | `9930a2e3ae9d54756f79693c1e8c60704975372c` | 最终保留；当前 canonical base，按正常 fast-forward／合入流程更新 |
| research/modernization-backlog | `7f7852843b228486e9d8e6cc3e98bfc3bf0ff16a` | 排除归档 26.2 runtime 的整支回滚；795 个独有提交已由恢复标签保存 |
| upstream/26.3-fabric-pr | `81b7e61821be46b6ad1d787bee6077a7208ebeaa` | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |
| upstream/renderpearl-26.3 | `4403a941091ecb7c25d41b8398d6ddf270b8c0cd` | 旧 26.3 移植／工作台已由 canonical RenderPearl/native ABI 取代；不整支覆盖当前实现，历史由恢复标签保存 |

## 仍阻止清理的条件

- 准确 36a9f3b1 的完整工程／9 客户端验证尚未完成。C7 的 Vanilla 常规三 lane 超 manifest 字节预算，FX 两后端云场景失败；固定 C7 独立 lane 完整复现云失败。新候选加入完整无损压缩、批量云采集与独立像素所有权证明，仍必须实际验证。
- 原写入者没有明确停止／迁移及保存未提交、未推送工作的确认。interrupted 或一段时间 head 不变不能替代确认。
- 默认仍 main；公开 ruleset 23875862 active，保护 main 删除／非 FF，无 bypass；经典保护 GET403 的必需检查细节未知，未重试／绕过／修改。
- 5 个开放 PR #90/#89/#87/#86/#85，均 base=main；未评论、关闭或合并。需要父侧审核如何处理及正常合入要求。
- 清理前重新枚举全部 refs、PR、保护和工作流引用，任何 head 漂移都重新审查／备份。恢复清单必须覆盖最终每个待删 SHA。

## 审核后执行顺序

1. 冻结准确候选，核对整轮测试、实际客户端、制品身份和仍需物理设备的边界。
2. 确认全部写入者已停止／迁移；刷新逐分支取舍和持久恢复锚点、bundle、远端恢复验证。
3. 在现有保护规则允许的正常流程中把候选纳入 main；无 force push、无重写 main。若 main 前进，在任务分支重新整合和验证。
4. 父侧审核通过且满足删除条件后，按最新 expected-old-OID 原子删除其余分支；任何漂移立即拒绝，不覆盖新写入者。
5. 再读远端确认仅剩 main；备份标签保留。恢复时从对应注释标签 peeled commit 正常创建原 ref，遇到同名新分支不得覆盖。

本会话尚未删除分支、修改 main/default/保护、发布、创建 PR 或评论。备份标签只承担恢复用途。
