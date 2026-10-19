# Metal 渲染器参考研究与改进

基线：MetalUniversal `9930a2e3ae9d54756f79693c1e8c60704975372c`，Minecraft 26.3。
以下比较以固定提交的源码为依据，不把各项目的 README 跑分当成同条件性能结论。

| 项目与参考提交 | 核查到的实现 | 本次采用与边界 |
| --- | --- | --- |
| [Ciderlight / 8f230335](https://github.com/riccqi/ciderlight/tree/8f2303357c87bd1e692e3451d33f35fb467f4a62) | RenderPearl 后端、FFM/Objective-C；提交完成索引、可配置背压、drawable 预取、输入采样时机 | 增加 1–3 次 GPU 提交预算，保留原有三槽资源寿命。异步 drawable 与输入预测涉及窗口、刷新率和 MetalFX 呈现，留待独立实机证据，未引入另一条呈现路径。 |
| [Vantage / 94891c66](https://github.com/imjameslin/vantage/tree/94891c66f6882d035f998713a366d890b2f76a97) | Java 堆外命令流、native 解码、延后节流、地形交接与几何租约；通过补丁替换原版客户端 | 沿用 MetalUniversal 已有状态包，增加一次调用完成状态应用与直接/索引绘制。地形代际保护在本项目已有实现，不替换为另一套调度器；tick 等待重排和 LOD 需要单独设计与验证。 |
| [metal189 / ba4a766d](https://github.com/bomberfish/metal189/tree/ba4a766d58f14d2ade587b83d8f72596e5d52468) | Forge 1.8.9 ASM/GL 捕获、逐帧命令流、基础/高级管线、同场景 GL/Metal 截图比较 | 采用原路径与候选路径共用独立像素预期的验证方法。新增直接/索引、偏移、负 baseVertex、baseInstance、多实例和重复 encoder 的加法混合读回案例。没有复制该仓库源码；研究快照未见明确 LICENSE。 |

Ciderlight 为 Apache-2.0，Vantage 自有代码为 MIT。本次实现基于 MetalUniversal 现有模块编写；上表记录设计参考，不引入外部源文件。

## 状态与绘制合并

`-Dmetallum.opt.fusedStateDraw=true` 显式启用，默认关闭。

- 协商 feature 3 的 interface v2：entry 0 保留状态包 v1，entry 1 增加 `metallum_render_state_packet_draw_v2`。
- 状态记录布局不变；绘制描述符为 64 字节定宽结构，格式在 `MetallumInterface.swift` 中定义。
- 仅合并有待应用状态的直接或索引绘制；无状态、旧 dylib、多绘制、间接绘制、三角扇及特殊辅助操作保留原路径。
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

三个候选使用相同 MRT/资源寿命测试与独立像素预期，必须证明合并路径真正激活，不能用回退冒充通过。原生接口测试另外核查旧入口地址和错误描述符拒绝。

托管 GPU 读回证明特定提交的 ABI 与离屏执行，不证明 WindowServer 呈现、游戏视觉兼容、MetalFX 质量、Metal 4 硬件路径、稳定性能或输入延迟。优化保持默认关闭，直到相关证据完成。
