# Metal 渲染器参考研究与改进

基线：MetalUniversal `9930a2e3ae9d54756f79693c1e8c60704975372c`，Minecraft 26.3。
以下比较以固定提交的源码为依据，不把各项目的 README 跑分当成同条件性能结论。

| 项目与参考提交 | 核查到的实现 | 本次采用与边界 |
| --- | --- | --- |
| [Ciderlight / 8f230335](https://github.com/riccqi/ciderlight/tree/8f2303357c87bd1e692e3451d33f35fb467f4a62) | RenderPearl 后端、FFM/Objective-C；提交完成索引、可配置背压、drawable 预取、输入采样时机 | 增加 1–3 次 GPU 提交预算，保留原有三槽资源寿命。异步 drawable 与输入预测涉及窗口、刷新率和 MetalFX 呈现，留待独立实机证据，未引入另一条呈现路径。 |
| [Vantage / 94891c66](https://github.com/imjameslin/vantage/tree/94891c66f6882d035f998713a366d890b2f76a97) | Java 堆外命令流、native 解码、延后节流、地形交接与几何租约；通过补丁替换原版客户端 | 沿用 MetalUniversal 已有状态包，增加一次调用完成状态应用与直接/索引绘制。地形代际保护在本项目已有实现，不替换为另一套调度器；tick 等待重排和 LOD 需要单独设计与验证。 |
| [metal189 / ba4a766d](https://github.com/bomberfish/metal189/tree/ba4a766d58f14d2ade587b83d8f72596e5d52468) | Forge 1.8.9 ASM/GL 捕获、逐帧命令流、基础/高级管线、同场景 GL/Metal 截图比较 | 采用原路径与候选路径共用独立像素预期的验证方法。新增直接/索引、偏移、负 baseVertex、baseInstance、多实例和重复 encoder 的加法混合读回案例。没有复制该仓库源码；研究快照未见明确 LICENSE。 |

Ciderlight 为 Apache-2.0，Vantage 自有代码为 MIT。本次实现基于 MetalUniversal 现有模块编写；上表记录设计参考，不引入外部源文件。

## 第二轮 GitHub 搜索与目标选择（2026-10-19）

本轮先完成搜索，再对照 MetalUniversal `3d2593bc6e4388591990940da5bb6e9d9ebbbf68` 选择目标。确认的主线仍为 `9930a2e3ae9d54756f79693c1e8c60704975372c`，前一轮改进保留在 PR #90 中。

检索覆盖 `minecraft metal renderer`、`minecraft metal macos`、`minecraft metal backend`、`minecraft metal native`、`minecraft metal rendering`、`minecraft apple silicon renderer`、`minecraft renderpearl`、`minecraft wgpu`、`minecraft MetalRender`、`minecraft "Metal API"` 等，共 21 组查询／页，去重命中 317 个仓库条目。命中包含矿物模组、分叉、独立方块游戏和光影包，并不代表 317 个独立 Minecraft Metal 后端。筛选核查 39 个候选，38 个可访问并固定了提交，下载 27 个源码快照；一个历史上游 `Xenonidium/Metal-Mod` 返回 404。宽泛查询用于发现候选，不声称穷尽 GitHub。

以下记录新增的主要参考。链接固定到此次实际读取的提交；README 的 FPS、硬件和兼容性声明不当作本项目的验收结果。

| 项目与源码快照 | 核查到的边界／实现 | 对 MetalUniversal 的价值 |
| --- | --- | --- |
| [mcopt / 7b1d0fb4](https://github.com/noahdunnagan/mcopt/tree/7b1d0fb49b4aa03f0a8ebd31a42f93c5fa1cbc6c) | Java 26.3 后端；`MetalRenderPass.multiDrawIndexed` 一次 FFM 调用，`mcmetal.m:mc_r_multi_draw_indexed` 在原生侧遍历交错参数；还包含地形 GPU 路径 | 采用批量跨语言调用的设计。其直接传递大 baseVertex 的实现不能替代本项目已验证的顶点绑定重定位。Apache-2.0。 |
| [MCMetal / d771db0a](https://github.com/Goodbuilder34/MCMetal/tree/d771db0ad80cffe40be2dc7b41236f5bcb1ab420) | `src/native/pass.mm` 实现原生 multi-draw、附件匹配和 pass 合并，`transfer.mm` 将清屏延后到 load action | 原生批提交作为本轮目标；pass／clear 合并必须继续服从本项目已有资源身份、代际及危险关系。MIT。 |
| [Graft / 4386b989](https://github.com/kkorenn/Graft/tree/4386b98945178da071ba0ffe0b7a1e7e57eddb97) | `core` 通过 FFM 调 Objective-C，render pass 可在 blit／compute 前暂停并恢复，提交完成后释放 scratch；多个 Minecraft 版本共享核心 | 对照编码器恢复和提交寿命；不引入另一套命令所有者。LGPL-3.0。 |
| [Minecraft Metal / c609745f](https://github.com/Kausik-Velaga/minecraft-metal-renderer/tree/c609745f67cbd271b2f60ccef67cd9de15d8d93c) | 26.3 JNI／Objective-C++ 后端，渲染器、shader loader、scene optimizer 分开；含管线数学与 pack 编译检查 | 对照后端／可选光影的边界和错误诊断，保留我们的原版、Sodium、Iris 分层。MIT。 |
| [metal-mc / 448b9c3d](https://github.com/virajpandey/metal-mc/tree/448b9c3d8f46c6773ae5ba65c79d87ecc4f2b542) | Swift 独立引擎及 26.3 客户端后端，GPU 剔除与远景 LOD，另有固定相机图像评分工具 | 独立图像评分可参考；LOD 和地形替换需要新的生产者与发布合同，另立目标。LGPL-3.0。 |
| [Tin / 28f74e72](https://github.com/Dovyrn/Tin/tree/28f74e728f6c149612314482212c48ea36cec371) | Blaze3D 后端，FFM／Objective-C、管线绑定和 uniform／texture 脏状态 | 资源绑定缓存和公共后端兼容性已有对应实现，优先避免重复建设。MIT。 |
| [MetalForge / 8a20c86c](https://github.com/LeadFreeCandy/metalforge/tree/8a20c86c44550d06c6ba17a3fcad9bf8dc23e984) | mcopt 的 NeoForge 26.1.2 移植，Sodium 适配、独立 present queue 和显示节拍策略 | 批提交与上传可对照；更换呈现队列会影响等待、输入和资源寿命，需要单独的时序证据。Apache-2.0。 |
| [MetalMC / 8304347e](https://github.com/XaviFortes/MetalMC/tree/8304347e5acf251238303dc8404e5f2c79384e98) | `IndirectCommandManager` 按在途帧分配 ICB／uniform，执行当前写入范围，明确最高绑定槽与驻留资源 | 核查现有 ICB 的范围和寿命；不把它直接套入通用 draw 路径。MIT。 |
| [ShaderMetal / bdda56bc](https://github.com/LingXiFox/ShaderMetal/tree/bdda56bcffea6125f6aaa1af782cd53b9f2e2211) | 1.21.4 Mixin／JNI／Objective-C++，Metal 光栅、加速结构与实验性硬件光追 | 属于更大的光照和几何更新目标；本轮不新增未经当前设备验证的画面功能。GPL-3.0-only。 |
| [wgpu-mc / 65a2fe33](https://github.com/wgpu-mc/wgpu-mc/tree/65a2fe332ace938067afd143b65f59995e7d01e5) | Electrum 将 Blaze3D 接到 Rust／wgpu，绑定组缓存及原生间接绘制；Metal 由 wgpu 后端承接 | 可比较前后端合同；不将 wgpu 或 MoltenVK 加入本项目的原生 Metal 执行路径。MPL-2.0。 |
| [metal-mc-terrain / a8dbbae4](https://github.com/Infatoshi/metal-mc-terrain/tree/a8dbbae45a113890a2d1e2c9c3af508e722bb8fb) | 面向 1.16.5 的地形替换与独立 Metal 带宽／绘制探针，覆盖面较窄 | 仅参考独立微基准方法，不把地形专用演示等同于完整 26.3 后端。MIT。 |
| [Metallum 上游 / 4da72f52](https://github.com/kokodio/metallum/tree/4da72f52a4d994efff39ce6f90b37f3a2ce66834) 与 [Sergey 分支 / 7e1da462](https://github.com/SergeyWithNormalName/metallum/tree/7e1da4622976e01a4886dcd85dcdbdc7fef91f56) | 前者为本项目同源后端；后者增加 HDR／光照／MetalFX 等实验 | 比较差异，不把同源分叉计为多个独立架构，也不整体合并旧分支。MIT。 |
| [minecraft-metal-shaders / a5b5a98e](https://github.com/GaelQuinta/minecraft-metal-shaders/tree/a5b5a98ef6b1ea47b0e713b8bd3ae687f8d473e7)、[fulgor / 399b00e2](https://github.com/Chere3/fulgor/tree/399b00e27556b0d60ad15a4e1e1d5fdedd788e33) | 分别沿 mcopt、Ciderlight 扩展光照／光影；均含原生后端源码 | 按派生关系归类，保留功能与纯编码成本的区别。Apache-2.0。 |
| [Anodic / 5475ffd4](https://github.com/owenvanvooren/anodic/tree/5475ffd477f411a298e7699d45fb656f75e6f6f3) | Metallum 的可选 AA 扩展；时间路径使用相机／深度且缺少逐物体运动信息 | 图像质量与运动伪影需独立验证，不作为本轮性能优化。MIT。 |
| [Delta Client / 70cd0ed4](https://github.com/stackotter/delta-client/tree/70cd0ed48c67534343db29eca3a013df89a90a96)、[Pebble / 2419b8d6](https://github.com/thebriangao/pebble/tree/2419b8d60f5bcdd99acb3b6b65e3a87d0b34e109) | 前者为 Swift 重写的 Java 协议客户端，后者为独立 Swift／Metal 方块游戏 | 可比较渲染算法，不能用它们的整机 FPS 代替 Minecraft 模组同场景结果。 |
| [MGL-minecraft / 5a7547be](https://github.com/53453450/MGL-minecraft/tree/5a7547be99908886df592c3f26f1b4960ead1607) | OpenGL 到 Metal 的兼容层，与直接实现 Minecraft 后端不同 | 作为接口兼容性对照；本项目继续直接执行 Metal。LGPL-3.0。 |
| [MetalRender / afc0c128](https://github.com/webblepebbles/MetalRender/tree/afc0c12821042a8fb9f35fd5b42f313ca9ae28b7)、[历史公开快照 / 324c1cb8](https://github.com/mateo-cogeanu/MetalRender/tree/324c1cb8c435967f88ad13b068ddd953f1ca49cb) | 当前默认分支仅有说明、许可证及不可直接复核的编码内容；历史公开快照包含 Java 后端／Sodium 适配 | 不以当前 README 的功能声明代替可检查实现。当前为自定义许可，历史快照的 LICENSE.md 为 Apache-2.0；本轮均不复制源码。 |
| [Metaler / b7adb95e](https://github.com/CopperleafMC/metaler-render/tree/b7adb95e591b55eb157481997672ea8b2be92251) | README 明确源码仍在拆分、准备公开，当前未发布完整后端 | 列入观察清单，不作为已复核实现或性能依据。 |

第一轮 Ciderlight、Vantage、metal189 的三个参考提交在本次复查时均未变化。以上项目的源码只用于设计比较，本轮代码在 MetalUniversal 的现有模块上独立实现；不引入第三方源文件、重写客户端或另一条 GPU 执行路径。

### 搜索完成后确定的目标

| 候选目标 | 本项目现状与验证代价 | 本轮取舍 |
| --- | --- | --- |
| 原生索引批提交 | 已有整批准入、大 baseVertex 重定位与批末恢复；仍由 Java 对每次绘制调用 native setter／draw。可沿用已有独立 GPU 像素、实例、回退和成本场景 | 首选：把已准入批次下沉为一次普通 FFM 调用，保留绘制数量／顺序及原回退 |
| pass／clear 合并 | 已有延迟清屏、encoder 保留及 Iris 资源计划；进一步合并涉及附件身份、load/store 与 RAW/WAR/WAW | 后续根据真实 pass 计数定位独立缺口，避免重复合并器 |
| GPU 地形剔除、ICB、LOD | 已有受代际与设备能力约束的候选；新增路径还要覆盖几何发布、驻留、遮挡保守性和硬件差异 | 保留为独立目标，不通过开启开关冒充执行验证 |
| RT、HDR、时间 AA、呈现线程 | 改变画面／显示节拍，微基准不能证明质量或输入时序 | 独立质量与时序验收 |

本轮成功条件是：新入口在原生侧先验证整批，再编码并在批末恢复原绑定；旧 dylib、不支持的输入、零／负偏移、观察顶点编号的着色器保留原路径。FFM 调用结果不明确时禁止重放。macOS Actions 必须证明 ABI、真实 Sodium 大偏移读回、实例与首实例、非零 slice／index offset、后续绘制绑定、资源寿命，以及原版／Sodium 生产默认均正确。性能使用独立 JVM 的 ABBA 顺序，只切换新入口，关闭计数并将 GPU 等待放在计时区间外；获得实际激活和稳定收益证据后才保留默认开启。

## 状态与绘制合并

`metallum.opt.fusedStateDraw` 默认开启，显式 `-Dmetallum.opt.fusedStateDraw=false` 回退。其他晋升项与未启用实验的具体边界见[优化默认值](optimization-defaults.zh-CN.md)。

- 协商 feature 3 的 interface v2：entry 0 保留状态包 v1，entry 1 增加 `metallum_render_state_packet_draw_v2`。
- 状态记录布局不变；绘制描述符为 64 字节定宽结构，格式在 `MetallumInterface.swift` 中定义。
- 仅合并达到既有原生状态包门槛的直接或索引绘制；单项变化继续使用原 critical setter。无状态、旧 dylib、多绘制、间接绘制、三角扇及特殊辅助操作保留原路径。
- 同步消费 CPU scratch，不延长 GPU 资源寿命，不重排绘制、屏障或资源操作；Metal 3/4 均调用已有执行函数。
- 只有返回 1 才表示已绘制。其他 native 状态保证没有绘制，状态前缀可幂等重放，然后执行一次普通绘制。FFM 异常结果不明确，禁止重放并使该状态包保持失败。
- `metallum.hotpath.telemetry=true` 可记录 `fusedDrawCalls`、`rejectedFusedDrawCalls`。客户端验证报告会输出请求开关与调用计数。符合条件的状态包加绘制从两次跨语言调用变为一次；这不是 FPS 提升的实测结论。

## GPU 提交预算

`-Dmetallum.maxSubmitsInFlight=1|2|3`，默认 3。非法文本使用默认值，数值限制在 1–3。

较小预算在提交前等待必要的完成凭据。原三槽 GPU 存储、四代销毁队列、回调和 MetalFX 生命周期不缩短，poll fence 不新增提交。该设置控制提交背压；实际输入延迟、GPU 利用率与显示节拍需要在固定场景中比较。

## 验证

Linux：

```bash
./gradlew --no-daemon compileJava test -x buildMacNative -x buildIOSNative -x buildIOSSpvc
bash scripts/agent/verify_unified_eval.sh
./gradlew --no-daemon vanillaRenderPearlBoundaryTest -Pmetallum.noOptionalMods=true \
  -x buildMacNative -x buildIOSNative -x buildIOSSpvc
```

GitHub Actions 的 `metal-capabilities` 在 macOS 上先运行 GPU 能力探针、编译 shipping dylib，再执行原路径和以下候选路径：

```bash
./gradlew --no-daemon -I .github/ci/HostedMetalGradle.init.gradle \
  hostedMetalFusedStateDrawTest \
  hostedMetalFusedStateDrawOneSubmitTest \
  hostedMetalFusedStateDrawTwoSubmitsTest
```

三个候选使用相同 MRT/资源寿命测试与独立像素预期，必须证明合并路径真正激活，不能用回退冒充通过。提交预算测试连续跨越三槽环，核查完成回调恰好一次和已观测积压上限。原生接口测试另外核查旧入口地址和错误描述符拒绝。

托管 GPU 读回证明特定提交的 ABI 与离屏执行；客户端 Actions 另外验证原版／Sodium 的生产默认、候选与原路径。已通过验证的默认项与独立回退见[优化默认值](optimization-defaults.zh-CN.md)。托管结果不证明 MetalFX 质量、Metal 4 硬件路径、稳定实机 FPS 或输入延迟。
