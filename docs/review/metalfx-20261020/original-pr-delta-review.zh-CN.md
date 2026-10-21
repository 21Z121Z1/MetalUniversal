# 原PR90未匹配patch的语义取舍

原分支 `a43637d08164a2e95116edaa4e7dda1b4fd2a155`；候选 `36a9f3b1d7d82be73091ce091a037d7460a6181b`。这里的8个patch-id未匹配不代表8项功能缺失。全部原始提交由远端注释恢复标签保留。

| 原始完整SHA | 意图 | 本分支取舍／验证 |
| --- | --- | --- |
| `f78a9950ca168254133ac511af27b81f45c4ed48` | 附件为空时的depth状态与fixture调整 | 排除其nil depth state；d7bd70d8显式disabled state与六模式GPU回归保留。保留R32F和真正FragDepth admission，C7双mac160控制通过；36仍待对应GPU。 |
| `5d621a8602832c6432c44a9a9a1074e9915ea96d` | cached disabled depth state | 与本分支d7bd70d8独立修复语义等价，不重复覆盖。 |
| `95890b8bf3b44c67c3f2f95170b2301aa567525e` | previous block offsets及原始BLOCK alpha | 以b42cface选择性移植，后续lightmap顺序/阴影/previous raster测试增强；不恢复旧颜色错误。 |
| `02976fdd31dffcfa3c65ac8179af27299521c415` | lightning/dragon rays、frozen fog visibility | 已选择性纳入d8f1f1be；旧cross-pose oracle问题由逐primitive平面及严格half转换控制替代，不靠扩大容差。 |
| `ec63decf9336c63570da94608d2962103074d3fb` | BLOCK/ENTITY/ITEM lightmap及render owner关闭 | 材质部分已选择性纳入；render owner关闭不足以解决真实closed/OOM/预算失败，本分支在Runtime锁下先退役owner并保留首错，严格close回归和最终磁盘oracle保留。 |
| `14cc12a2c3cc76ca9777286608314d629f50c736` | 首错保留、关闭失败后退役recorder及通过后注入IO失败 | 逐项审查：本分支从completedCapture建立真通过gate，再覆盖manifest/results/两者文件故障，检查两个owner均关闭、primary/suppressed准确、buffer invalidation和重复close安全、gate保持failed；另有并发持锁故障和最终字节超限负例，更严格，不机械覆盖。 |
| `dcb7c0e3db9f75ed005e3d94c95c4770c44952ce` | 程序化云faces/history原型 | 选择身份/生命周期/原始绑定；不纳入CPU重算current光栅的错误路径（旧12fixture失败保留）。 |
| `a43637d08164a2e95116edaa4e7dda1b4fd2a155` | 保留GPU cloud表达式、冻结绑定及终态断言 | 680选择原GPU表达式与冻结CloudInfo/Faces；C7双mac18云fixture全零误差。36补公共buildMesh完成buffer快照覆盖Sodium批写，并以独立world/HUD通道证明透明云所有权；最终终态采用更严格独立解码oracle，保持门禁。 |

C7旧完整客户端失败被保留：Vanilla普通file budget、Vanilla OIT透明云判据、Sodium callback-only云缺失。36不是以原PR的成功任务替代整合验证，当前仅Vanilla candidate准确关闭后9捕获/画面/呈现/重载完成。其余准确36 CI仍需读回。

原写入者尚未明确确认永久停写或保存未提交/未推送数据。原分支未写入、未删；main未改。
