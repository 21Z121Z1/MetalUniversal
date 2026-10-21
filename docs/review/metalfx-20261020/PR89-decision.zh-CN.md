# PR89精确取舍：保留恢复，不晋升实验优化

审核对象 `7b83bc4df3d93bc979a8c34caa551ad818595ce9`。相对当前整合基线有183个独有提交，这是提交数，不是183项功能；相对main差异包含70个新增文件，已从git diff A状态定量复核。完整独有SHA列表见分支分析JSON，全部历史有持久恢复tag。

结论：**未晋升的默认关闭实验优化，部分已有旧版本运行证据**。整包保留恢复，不为本次MetalFX收敛重做整套实验性能优化。

## 依赖与证据分别描述

- 私有Sodium适配锁0.9.3-alpha.1，当前产品为0.9.2，需要按真实私有API/语义核验，不能直接套用。
- 启动/模型循环、chunk/POI/light/palette、QoS及buffer预分配并非全依赖Sodium。它们各有状态/顺序/生命周期/保存语义与性能验证要求，不能用“Sodium版本不同”一概否定，也不由模块存在或默认关闭就声称通过。
- 最终旧run [37266006468](https://github.com/21Z121Z1/MetalUniversal/actions/runs/37266006468) 中Vanilla job111622879421和sodium-fast job111622879216成功；sodium-verify job[111622879402](https://github.com/21Z121Z1/MetalUniversal/actions/runs/37266006468/job/111622879402)失败。三项终态已重新从API核对。
- 错误位于保存阶段`ChunkPaletteFastCodec:106`：候选编码与Vanilla编码的serializedTag字节等价校验不一致，抛`Palette encode verification diverged for block-states`。源码VERIFY分支先生成Vanilla reference并比较，不应把这份证据直接称作“存档损坏”。相关原日志节选见PR89-palette-failure.raw.log。
- sodium-fast的非零执行断言仅证明指定路径曾执行，不证明性能净收益；旧版本运行成功也不批准当前版本的准确源码/二进制/场景。

## cull recovery的有限核实

旧修复在`prepareRenderTrees`入口记录`pendingTask`，在`consumeCullTaskResults(Z)V`之前若task消失就恢复`needsGraphUpdate`。当前0.9.2是否有相同的取消控制流，必须查准确依赖；同名字段、方法、hook形状不足以证明取消语义。

本轮仅添加精确classpath字节码诊断收据，保存class SHA256、相关fields及prepare/consume方法的调用、字段、跳转与指令序列。收据默认`sameCancellationSemanticsProven=false`、`promotionAllowed=false`；它供定点审阅，不引入旧Mixin，不改变culling策略。若没有确证，不扩展实验优化范围。诊断代码自身也须在包含它的准确SHA上执行，不能沿用旧测试通过。

main/PR89均未修改或关闭；后续处理依正常合入和分支收敛审核，未获merge/delete放行。
