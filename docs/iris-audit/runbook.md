# 构建/运行 Runbook(rollout 挖掘 + 本会话核验)

## JDK(必须显式指定)

- `build.gradle` 要求 release 25;PATH java=Oracle 24 会报「不支持发行版本 25」。
- 历史会话用 `/tmp/metallum-jdk25/jdk-25.0.3+9/Contents/Home`(Temurin,**/tmp 易失**,重启后需按 rollout 中命令重下)。
- 本会话核验的稳定等价:`JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`(Homebrew 25.0.2;compileJava/test/MRT E2E 均通过)。
- gradle toolchain 自动探测发现不了以上两个 JDK,env 前缀必须每次带。
- 旧 JDK 的 javap 读不了 classfile 69,用 JDK25 的 javap。

## 常用命令(全部在仓库根,历史会话统一 `--no-daemon`)

```bash
# L1 构建
JAVA_HOME=$JDK25 ./gradlew compileJava compileTestJava test buildMacNative --no-daemon
# L2 GPU 测试
JAVA_HOME=$JDK25 ./gradlew metalMrtBackendIntegrationTest --no-daemon
JAVA_HOME=$JDK25 ./gradlew metalFxOffscreenValidation --no-daemon
JAVA_HOME=$JDK25 ./gradlew metalFrameGenerationLifecycleTest --no-daemon
# L3 Minecraft 自动化验证(有屏桌面即可,锁屏也能跑;热态 26-36s,客户端段约 12s)
JAVA_HOME=$JDK25 ./gradlew minecraftMetalFxClientValidation --no-daemon
# 全量门禁(历史成功案例)
JAVA_HOME=$JDK25 ./gradlew clean test buildMacNative metalMrtBackendIntegrationTest \
  metalFxOffscreenValidation metalFrameGenerationPresentationValidation build --no-daemon
```

- runClient 冒烟(手动矩阵):`./gradlew runClient --no-daemon --args='--quickPlaySingleplayer "New World"' -Dmetallum.metalfx.mode=TEMPORAL -Dmetallum.metalfx.scale=0.67 -Dmetallum.metalfx.debug=true`;残留进程 `ps -axo pid=,command= | awk '/metallum\.metalfx/{print $1}'` + `kill -TERM`。

## Metal 验证环境(本机 M1 Pro 特有陷阱)

- **全局 `MTL_SHADER_VALIDATION=1` 会让 Apple MetalFX 私有 temporal kernel 中止**(instrument 后 1024 线程组超本机上限)。因此:
  - `metalFxOffscreenValidation` 与所有客户端验证:`MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=0`;
  - 需要项目 pipeline 的 shader validation 时用白名单:`MTL_SHADER_VALIDATION_DEFAULT_STATE=none MTL_SHADER_VALIDATION_ENABLE_PIPELINES='Motion Reconstruction,Transparency Mask' MTL_SHADER_VALIDATION_REPORT_TO_STDERR=1`;
  - `test`/`metalMrtBackendIntegrationTest` 维持双 =1。
- env 必须在 Java 进程创建 Metal device 之前生效(gradle 任务里已配好)。

## L3 客户端验证机制

- 任务 `minecraftMetalFxClientValidation` 触发时对 `runClient` 注入:validation.enabled/output、mode=TEMPORAL、debug、frameGeneration=false、`--quickPlaySingleplayer "New World"`、MTL env。
- 世界 `run/saves/New World` 预先存在(**未由任何会话创建**),已被改成旁观者模式(level.dat/GameType=3,NBT 结构化编辑,客户端退出会重写 level.dat——跑矩阵前复核 GameType)。
- 场景配置是代码:`MetalValidationClient.java`(fabric client entrypoint,`metallum.validation.enabled` 门禁);8 场景定帧 capture(6/12/22/32/42/47/54/62),输出 `build/metal-validation/minecraft-client-current/`(capture-*.bin、frame-state.json、metrics.json、run-state.json);失败 fail-closed 抛异常令任务红。
- 无需 caffeinate/xvfb;窗口在真实桌面;LWJGL 窗口对 macOS accessibility 不可见(Computer Use 驱动不了,自动退出机制就是为 agent 设计的)。
- 离线开发账号会有 Yggdrasil/Realms 401 与 publickeys 超时噪声,与渲染无关。

## 交接要点(来自 docs/handoffs/metalfx-cutout-reactive-handoff-2026-07-26.md,状态=本会话已核验)

- 交接时警告「最后一次 MetalFxManager 验证 metrics 编辑未编译」——**本会话已核验:基线树 compileJava/test 通过**,该风险已解除;后续 CUTOUT 场景(帧 74/82,captures 8→10)仍未完成,属 MetalFX 线,阶段一不动。
- `build/libs/metallum-1.0.1.jar` 陈旧(早于最新 dylib),不得作为证据;打包后必须比对 jar 内 dylib SHA-256 与新构建一致。
- CUTOUT 修复的验收不变式与 mixin remap 检查项见原文;`OBJECT_MOTION_PRODUCER_CONNECTED=false` 必须维持。
- Launcher 体验档案强制注入 MetalFX 属性导致游戏内选项置灰的问题仍开放(阶段二收尾项)。

## 会话时间线(6 个 rollout)

01:25 主实现(MetalFX temporal/reactive/FG/pacing;Metal System Trace 在 /tmp)→ 02:35 存根 → 10:13 只读 forensics(docs/render-pipeline-forensics)→ 11:58 Computer Use:真实 Launcher 隔离实例 `~/Library/Application Support/minecraft/instances/MetalUniversal-26.2`(Sodium 0.9.0+metallum,Java25 runtime,TEMPORAL 67%)→ 12:56 动机=语义完整 motion+MRT+display timeline,被本地代理 503 连环打断(presenter 改造中断于 NSObject/delegate 适配)→ 15:26(**项目目录外**:`~/.codex/sessions/2026/07/26/rollout-2026-07-26T15-26-02-*.jsonl`)完成 MRT E2E/presentation/offscreen/客户端 harness(17:31 8/8 PASS),CUTOUT 修复做到一半按用户要求停手写交接。
- Iris 相关:全部 rollout 仅 1 处 "iris" 命中(某 fabric.mod.json 的 breaks `iris<=1.10.8`)——**无任何 Iris 实现尝试**。
- 用户全局 minecraft 目录有既有 OptiFine/BSL 资产,历史会话刻意用隔离实例避免触碰——沿用该纪律。
