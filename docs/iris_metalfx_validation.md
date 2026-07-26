# Iris + MetalFX 验证记录(iris-on-metal 分支)

约定:只记录真实执行过的命令与结果;每条含日期、命令、退出状态、证据路径。
环境:macOS 26.5(Apple M1 Pro),JAVA_HOME=Homebrew openjdk@25(25.0.2),Gradle 9.4.1 `--no-daemon`。

## L1 构建

| 日期 | 命令 | 结果 |
|---|---|---|
| 2026-07-26 | `compileJava compileTestJava test buildMacNative`(基线 ea2dfd4,master 树) | BUILD SUCCESSFUL |
| 2026-07-26 | `buildMacNative`(新增 compute/mipmap/sampler-v2 ABI 后) | BUILD SUCCESSFUL |
| 2026-07-26 | `compileJava` / `compileTestJava`(B0/B1 各步后) | BUILD SUCCESSFUL |
| 2026-07-26 | Sodium 0.9.0→0.9.1(gradle.properties)后 `compileJava compileTestJava test` | BUILD SUCCESSFUL(MetalDrawContext 与全部 sodium mixin 编译兼容) |

## L2 独立 GPU 测试(真实 Java→FFM→Swift 链路,GPU readback)

| 套件 | 结果 | 覆盖 |
|---|---|---|
| `metalMrtBackendIntegrationTest` | **14/14** (0 fail) | 1/2/3/4/8 attachment、混合格式、null 槽、非连续 0/2/5 映射、逐槽 clear/load/store/blend/writeMask、depth+MRT(深度内容 0.25 断言)、resize 重建、legacy ABI、3 类 fail-closed、提交回调 ×5 |
| `metalComputeBackendIntegrationTest`(新) | **10/10** (0 fail) | compute absolute/relative/indirect dispatch、SSBO 写读+compute→compute 链、imageStore/imageLoad、render→compute→render 顺序(fence 链 barrier 语义)、GPU mipmap 内容(mip2 下采样)、compare sampler shadow 语义(0.25/0.75 vs depth 0.5)、ABI 探测 |
| `metalIrisTargetsIntegrationTest`(新) | **6/6** (0 fail) | ping-pong 三连 pass 双侧内容、snapshot/restore、feedback 守卫、depthtex0/1/2 复制语义(0.75/0.25/0.5)、shadow targets 深度+颜色+主目标隔离+resize、resize 复位 flip/内容 |

环境:`MTL_DEBUG_LAYER=1`、`MTL_SHADER_VALIDATION=1`(项目自有 pipeline 全程 shader 校验)。
三套件均接入 `check`。

## L3 Minecraft 真实客户端

### 已知红:`minecraftMetalFxClientValidation`(MetalFX 线半成品,非本分支引入)

- 2026-07-26 23:26 首跑(Sodium 0.9.1):**SIGABRT** — Metal API validation 断言
  `Texture at colorAttachment[0] has usage (0x03) which doesn't specify MTLTextureUsageRenderTarget`。
  根因:基线中 15:26 会话未完成的 CUTOUT 工作对 `reactiveTexture` 新增了 `clearColorTexture`(MetalFxManager:1484),但该纹理创建时缺 `USAGE_RENDER_ATTACHMENT`(:1449);延迟 clear 经 V1 render-encoder 物化触发断言。**已修复**(本分支给 reactiveTexture 补 RENDER_ATTACHMENT usage)。
- 2026-07-26 23:30 复跑:客户端完整跑完 9 次 capture 后 fail-closed 退出:
  `Automated Minecraft GPU validation failed: completed=9/8, failures=3`。
  根因:交接文档(docs/handoffs/metalfx-cutout-reactive-handoff-2026-07-26.md)预告的**验证 harness 半成品状态** —— MetalFxManager 的 capture 已扩展(多出 frame-074,并收紧 moving-entity 场景的 motion 指标判定:motionDrawsEncoded=11 但 object validity=0 → mean NaN → fail),而 `MetalValidationClient` 仍按旧 8-capture 契约驱动场景。此为 **MetalFX 线在制品**(master 工作树的并行会话正在推进),按任务书纪律阶段一不修改其语义,不以调低标准换绿。
- 结论:该任务在本分支当前为**红**,原因与所有权如上;阶段一的 Iris 运行验证不以它为载体。

### 阶段一 L3 冒烟(MetalFX OFF,与 MetalFX 指标解耦)

- 设计:`runClient --quickPlaySingleplayer "New World"`(旁观者存档)+ `metallum.metalfx.mode=OFF`,真实窗口渲染 ≥60s,断言:Metal 后端激活、Sodium 0.9.1 mixin 全部应用、进入世界、无异常/无崩溃;随后 SIGTERM 结束。
- **冒烟 A(Sodium 0.9.1,无 Iris)**:通过。Metal 后端,进世界后渲染 ~4 分钟,0 崩溃标记(唯一异常=已知离线鉴权 401 噪声);SIGTERM 收尾(BUILD FAILED 是主动杀进程的预期产物)。
- **冒烟 B(Iris 首跑)**:失败并定位——垫片按设计触发("holding Iris dormant"),但 `Iris.duringRenderSystemInit → setDebug → IrisRenderSystem.<clinit> → SamplerLimits(GL glGetInteger)` 触发 LWJGL "No context is current" JVM abort。**教训:GL 类的 `<clinit>` 连锁无法被方法注入取消,必须掐调用源头。** 已补 `duringRenderSystemInit` 取消。
- **冒烟 B2(修复后误跑 GL 后端)**:发现 **MC 26.2 崩溃回退持久化**——B 的硬崩溃使 vanilla 把 `preferredGraphicsBackend` 写为 `"opengl"`;B2 实际跑在 Apple GL4.1 上(此时按门禁设计 metallum/垫片全部停用,真实 Iris 在 GL 上以 vanilla-fallback 正常运行 60s——反向验证了门禁正确性)。**测试纪律:每次崩溃后必须复核并恢复 options.txt 的 backend 值。**
- **冒烟 B3(Metal + Iris 休眠,二迭代)**:失败——同为 `IrisRenderSystem.<clinit>` 引爆,但触发点换成 Iris handler 对 `IrisRenderSystem.initRenderer()` 的 invokestatic 本身:**方法体取消挡不住类初始化**。`<clinit>` → `SamplerLimits.<init>` → `GlStateManager._getInteger`×3 + `IrisRenderSystem.supportsSSBO()`(直读 GL.getCapabilities)。修复:`GlStateManagerCompatMixin`(dormant 时 `_getInteger` 假接安全常量:34930→16、34852→8、默认 8)+ `supportsSSBO` 取消返回 false(方法注入在 clinit 中段依然生效)。
- **冒烟 B4(GL 误跑,机制定案)**:Metal 未被尝试。定位到 **vanilla 启动崩溃日志机制**:`options.txt` 的 `startedCleanly` 字段启动时置 false、启动完成置 true;上次为 false 时 `Minecraft.<init>` 打印 "Detected unexpected shutdown during last game startup: forcing preferred graphics API to OpenGL",把 DEFAULT 强制为 OPENGL 并保存(若上次是具体 API 则先重置为 Default——连环崩溃在 DEFAULT↔OPENGL 间摆动)。**测试纪律(最终版):每次客户端运行前确认 `startedCleanly:true` 且 `preferredGraphicsBackend:"default"`。** 该机制同时是任务书"fallback 到原始路径"生命周期项的 vanilla 原生实现:Metal 启动期崩溃会被自动打入 OpenGL,直到用户/工具改回。
- **冒烟 B5(clinit 垫片后)**:启动期跨过 RenderSystem init(dormant 标记打出),新缺口:Iris 对 `AbstractTexture` 的全量纹理钩子调用 mixin 注入 `GpuTexture.iris$getGlId()`,默认实现对非 GL 纹理抛异常(首个受害者=字体纹理,`FontManager.<init>`)。修复:`MetalGpuTexture` 按名覆写 `iris$getGlId()` 返回合成递增 id(运行时对 mixin 合成虚方法的覆写,无编译依赖)。
- **冒烟 B6(getGlId 覆写后)**:31s 进世界(Metal + dormant ✓),但入世 ~14s 后崩:Iris Hud mixin 调 `GLDebug.pushGroup`(其 debug 状态因 reloadDebugState 被取消而未初始化)。修复:`GLDebug.pushGroup/popGroup/nameObject` dormant 取消。
- **冒烟 B7(最终)**:**通过** —— 2026-07-27 00:00,Metal 后端 + Sodium 0.9.1 + Iris 1.11.2 共存,28s 进世界,**90 秒在世界内持续渲染存活**,0 崩溃标记,dormant 标记正常,SIGTERM 收尾;options.txt 哨兵(startedCleanly/preferredGraphicsBackend)运行后保持健康。
- 结论:**「Iris 安装共存、Metal 上受控休眠、游戏可玩」已达成并有运行证据**;Iris 渲染语义点亮(pack/composite/终局目标)仍属未完成(见 acceptance report)。

## 4. 门禁状态速览(阶段一)

- 工作树可构建:✅
- Java/Swift 可编译:✅
- MRT 全链路:✅(14/14)
- ping-pong 内容:✅(6/6 内含)
- depthtex 语义:✅
- shadow targets:✅
- compute/image/SSBO 后端 smoke:✅(10/10,超出 smoke 深度)
- Iris composite/final 可执行:❌ 未实现(集成层未起步)
- Sodium 几何走 Iris shader:❌ 未实现
- reload/resize 不崩溃:后端层 ✅(L2);Iris 层 N/A
- ≥1 光影包真实运行验证:❌ 未达成
