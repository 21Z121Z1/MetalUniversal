# MetalFX 整合与恢复审查包

本目录由用户明确要求提交为仓库可访问的审查材料。它是带时间和 SHA 的证据快照，不是产品运行状态或准入授权；事实优先级仍为准确源码和准确 CI。未合并 main、未删除任何分支、未修改保护或发布。

## 阅读入口与范围

- 已验证修复基线：`36a9f3b1d7d82be73091ce091a037d7460a6181b`。9/9 实际客户端、双 macOS 各160/160、build/migration成功；本快照时 hosted-metal/ABBA 尚在独立继续。新 UI 写入审计代码不能沿用36的绿灯，必须在新准确 SHA 验证。
- 后续工作使用自有 `codex/metalfx-ui-review-20261020` 创建整合Draft PR，避免audit分支push与PR重复启动九客户端；不改工作流门禁，不取消consolidated@36现有任务。远端将新增一个临时review分支，原19个头保持不动，新分支同样追加恢复锚点。原19分支快照与所有旧提交完整列表见 [分支分析](integration-latest-branch-analysis.json)，逐项取舍见 [分支复核](integration-branch-review.md)。
- 原PR90八项patch-id未匹配已逐项解释：[原始完整SHA和排除/替代依据](original-pr-delta-review.zh-CN.md)。未匹配不是自动判定缺失；独有提交仍完整可恢复。
- [81捕获、全部10会话的准确收据](36-client-terminal-receipts.json)、[实际制品ID和摘要](36-client-artifact-metadata.json)、[manifest正常规模/压缩字节因果证据](manifest-storage-causal-proof-36a9f3b1.json)。逐job原始终态日志同目录 `36-*-terminal-receipt.log`。ZIP元数据不是人工截图检查。
- [API原始状态快照](ci-36a9f3b1-current.json)、[PR/default/ruleset记录](cleanup-api-refresh-36a9f3b1.json)、[工作流引用盘点](cleanup-workflow-reference-review.json)。其中旧queued状态只描述快照时刻，最新准确状态在Draft PR说明和Actions。

## 恢复原文

[完整19分支head→注释tag→peeled提交映射、bundle校验和和验证结果](recovery-36a9f3b1-snapshot.json)；[fresh clone、逐项rev-parse、fsck与bundle verify原始stdout/stderr/exit](fresh-restore-36a9f3b1.raw.log)。该bundle的二进制在本地生成；持久等效恢复备份是已推到用户GitHub的非发布注释标签，已从远端全新mirror验证，不依赖临时容器的dangling SHA。未发现LFS指针或gitlink，详见 [对象扫描](recovery-external-object-scan.json)。

在全新目录恢复，先从公开仓库读取，不依赖本环境路径：

```sh
git clone --mirror https://github.com/21Z121Z1/MetalUniversal.git recovery.git
git -C recovery.git fsck --full --no-reflogs
git -C recovery.git rev-parse 'refs/tags/branch-backup/20261020T210011Z/codex/consolidated-metal-20261020^{commit}'
```

最后一项必须等于36完整SHA。对JSON `anchors`逐项做同样peel校验，要求19/19完全相等。需要恢复某个已获准删除的分支时，从对应tag的peeled commit正常创建原ref；如果同名ref已存在，立即停止并比较，不能覆盖后来写入。

最新修复/审查提交本身的备份会在正常推送后追加不可变tag，并在Draft PR正文记录准确head/tag/bundle SHA256及fresh恢复原文；这是避免文档自引用SHA循环。原19头快照不会被假称为覆盖尚未提交的新head。

## 五个现有PR的逐项处理方案（尚未执行）

| PR | head | 正常合入main前/后处理 |
| --- | --- | --- |
| [#90](https://github.com/21Z121Z1/MetalUniversal/pull/90) | a43637d08164a2e95116edaa4e7dda1b4fd2a155 | 选择性整合有效producer/weather/material/cloud修复，保留更严格关闭/颜色/R32F/oracle；逐项8差异已有原文。先确认写入者停写和未提交/未推送工作保存，最新head再审查/备份。最终main可达后经审核关闭为被整合替代，不能凭interrupted删除。 |
| [#89](https://github.com/21Z121Z1/MetalUniversal/pull/89) | 7b83bc4df3d93bc979a8c34caa551ad818595ce9 | 不整支纳入：私有Sodium目标0.9.3-alpha与当前0.9.2不同，独立palette verifier失败。183独有提交完整映射/备份；先由父侧审核明确排除范围，再归档/关闭，不宣称这些优化已验收。 |
| [#87](https://github.com/21Z121Z1/MetalUniversal/pull/87) | cd5f8ddd939d38e05cd6ac56e466dcef69877d3c | 选择camera/raster/source receipts，排除旧CUTOUT失败对照与旧深度历史，以V4和严格负例替代。main包含有效delta且准确验收后关闭为整合替代。 |
| [#86](https://github.com/21Z121Z1/MetalUniversal/pull/86) | 1548c41d9e0ea65fad932740854d75804ff81d07 | 固定相机和源时间收据已选入；保留完整RGBA和独立8+1，排除忽略alpha的弱验收。物理性能/显示验收仍独立，不把归档PR当性能通过。 |
| [#85](https://github.com/21Z121Z1/MetalUniversal/pull/85) | 1888d3e28ba0fb0cb6ce043977db658d9c628c62 | 有效参数/源提交/相机契约已选择；旧不完整历史及未证明颜色/UI不带回。准确main验收及父侧审核后关闭为整合替代，仍明确Temporal/FG支持门禁。 |

操作顺序详见 [单分支审核方案](single-branch-cleanup-plan.zh-CN.md)：全部最终相关门禁、恢复原文审核、写入者确认后，正常将有效候选纳入main，随后再按最新expected-old-OID收敛其余分支。未获merge/delete放行；不force push、不改保护、不发布、不增加付费服务。

## 本轮有限软件适配

UI观察从目标clear之前开始；实际纹理失效/完整clear/普通与间接compute/storage image（含Iris解析后的fallback绑定）进入内容证据。只证明完整、有界premultiplied clear，任何后续未知写入失效；不把通用shader范围或完整写入覆盖设为true。独立CPU fixture和真实GPU upload/copy/compute负例保留原混合断言。HDR控制要求RGBA16F原始4.0/2.0辐射和alpha在SDR decoder拒绝后保持不变、且不分配SDR输出，不能把sRGB transfer冒充tone-map。

背景环境/完整custom与间接producer、通用UI、真实HDR/tone-map和混合world/HUD分层颜色仍按实际证据逐项收敛，不由native supportsDevice=false替代软件判断。支持硬件的Temporal/FG画质、节奏与延迟仍需单列验收。
