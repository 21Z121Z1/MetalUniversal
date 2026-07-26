# B2-1 设计与交接文档:gbuffers_terrain 点亮(Sodium 地形走 Iris shader)

状态:**活文档**。每个实施步骤带状态标记(`[ ]`未做 `[x]`完成 `[!]`受阻)。
接手人(包括小模型)只需:按 §4 步骤顺序执行,每步有精确文件/改动/验证命令;遇到偏差先查 §5 风险表。

日期:2026-07-27。分支 `iris-on-metal`,worktree `MetalUniversal-iris`。基线 commit `4b59c5c`。

---

## 1. 目标与验收判定

- **目标**:Sodium 0.9.1 的世界地形(solid/cutout,translucent 尽力)在 Metal 后端上通过 **光影包的 gbuffers_terrain 程序**渲染;Iris 装载线(loadShaderpack)在游戏内被唤醒,pack 真实解析;不再是休眠共存。
- **判定(B2-1 里程碑,非阶段一整体)**:
  1. 离线 GPU 测试:BSL+Potato 的 terrain 程序经 patchSodium→库存编译链→真机 PSO 创建成功(`isValid()`),绑定表含预期资源。
  2. 真实客户端:terrain 覆盖 PSO 被编译并用于绘制,画面可见 pack 地形着色(**gbuffer0 内容,非最终画面**——composite 链属 B2-3),无崩溃,90s 存活。
- **显示语义(B2-1 简化,必须写进 validation 文档)**:colortex0 别名主帧缓冲(DRAWBUFFERS[i]==0 的输出直接落屏),其余 DRAWBUFFERS 落真实 IrisMetalRenderTargets;composite/final 未运行,画面 = 原始 gbuffer0(BSL 下近似 albedo×lightmap)。B2-3 落位后恢复标准语义。

## 2. 已确证事实(全部字节码级验证,勿凭记忆推翻)

jar 路径:
- Iris: `~/.gradle/caches/modules-2/files-2.1/maven.modrinth/iris/1.11.2+26.2-fabric/f7d526b1062c4bfe2567113cf933d1de26eddd3f/iris-1.11.2+26.2-fabric.jar`
- Sodium: `.../sodium/mc26.2-0.9.1-fabric/14f3388694fa77f870d28262f74562de67eabcbe/sodium-mc26.2-0.9.1-fabric.jar`
- javap 必须用 `/opt/homebrew/opt/openjdk@25/.../bin/javap`(class file 69)。

1. **Iris GL 侧管线覆盖**:`MixinShaderManager_Overrides` 注入 `GlDevice.getOrCompilePipeline` HEAD;条件 `getPipelineNullable() instanceof IrisRenderingPipeline && shouldOverrideShaders() && !ImmediateState.bypass`;跳过 `CompositeRenderer.COMPOSITE_PIPELINE` 与 `ANIMATE_SPRITE_*`。
2. **sodium 管线识别**(`IrisPipelines.getPipeline`):`pipeline.getLocation().getNamespace().contains("sodium")` → translucent 若 `getColorTargetState().blendFunction().isPresent()`;cutout 若 `getShaderDefines().asSourceDirectives().contains("CUTOUT")`;否则 solid。shadow 变体看 `ShadowRenderingState.areShadowsCurrentlyBeingRendered()`(本阶段恒 false)。
3. **patchSodium 调用约定**(ShaderCreator.create 字节码):`TransformPatcher.patchSodium(name, vsh, gsh|null, tcs|null, tes|null, fsh, alphaTest, pipeline.getTextureMap(), false)`。
4. **顶点格式**:sodium 路 `vertexFormat == null` 时取 `WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat()`;`IrisRenderingPipeline` 构造器调 `FormatAnalyzer.createFormat(true,true,true,true)`(字节码 iconst_1×4,字面全 true)并 `setVertexFormat`;`MixinRenderSectionManager` 把 sodium 的 ChunkVertexType redirect 到该 setting(mesh 侧扩展属性写入由 Iris 自己的 MixinChunkVertex 等承担,CPU 侧);`MixinShaderChunkRenderer` 把 sodium RenderPipeline 对象的 VertexFormat 恒强制为 `ChunkMeshFormats.COMPACT.getVertexFormat()`(仅查表身份;**构建 PSO 时必须用 XHFP 格式而非管线对象声称的格式**)。
5. **ShaderKey 数据面**:`SODIUM_TERRAIN_{SOLID,CUTOUT,TRANSLUCENT}.getProgram()/getAlphaTest()/getFogMode()`;`ProgramId.getFallback()` 提供回退链(Terrain→...)。映射逻辑全部读 Iris 枚举,零硬编码。
6. **我们的钩子位**:`MetalDevice.precompilePipeline`(:156)与 `getOrCompilePipeline`(:260)都汇入 `compiledPipelines.computeIfAbsent(pipeline, p -> MetalCrossShaderCompiler.compile(this, p, source))`。
7. **库存编译链已解决 varying 配对**:`MetalCrossShaderCompiler.compile` 用 vanilla `com.mojang.blaze3d.vulkan.glsl.GlslCompiler` 出 SPIR-V,`IntermediaryShaderModule.rebind(providedNames, layoutEntries)` 按名分配 location/绑定;fragment 用 `rebind(vertexOutputs, ...)` 配对。`addToBindGroup` 校验 shader 声明的每个 UBO/sampler 必须出现在 RenderPipeline 的 BindGroupLayout(内建 Projection/Lighting/Fog/Globals 豁免)。
8. **MetalCompiledRenderPipeline**:PSO 按(管线声明的 colorFormats)×(6 组 depth/stencil)预建,draw 时按实际 pass 附件签名查表——**合成管线 colorTargets 必须与扩展后的地形 pass 附件严格一致**。顶点描述符来自 `info.getVertexFormatBindings()`;逐 target blend 来自 `info.getColorTargetStates()`。
9. **RenderPipeline.Builder** 支持:withLocation/withVertexShader/withFragmentShader(Identifier)/withBindGroupLayout/withColorTargetState(int,ColorTargetState)/withDepthStencilState/withVertexBinding(int,VertexFormat)/withCull/withPrimitiveTopology。
10. **sodium 地形 pass**:`DefaultChunkRenderer` 自建 render pass(jar 内含 `createRenderPass` 调用),draw 前按名设置 `u_Globals`、`u_SectionTimeInfo`、`u_LightTex`、`u_BlockTex`。pass 状态是按名键值表,预置多余条目无害 → **iris 资源(MetallumIrisUniforms/采样器)在 pass 创建后用公开 API 预置,无需改 MetalRenderPass**(缺资源会在 bindDrawState :616/:636 抛异常,预置即避免)。**修订(2026-07-27)**:pass 创建时 sodium 还没绑 `u_BlockTex`/`u_LightTex`,拿不到它们的 GpuTextureView 转手给 `gtexture`/`lightmap`,所以实际 seam 改到 `MetalRenderPass.pushDescriptor` 的缺名 fallback——详见 §4.3 S6a。
11. **StandardMacros 游戏内 GL 面**(真实类,非测试 shadow):`GlStateManager._getInteger`(已被 GlStateManagerCompatMixin 假接)、`GlStateManager._getString`、`IrisRenderSystem.getStringi`、`RenderSystem.getDevice().getDeviceInfo()`(抽象接口,Metal 安全)。
12. **唤醒面**:`Iris.loadShaderpack` 目前被 `IrisBootstrapCompatMixin` 取消(经 `MetalIrisCompat.holdIrisDormant()`);`Iris.createPipeline(NamespacedId)` 为 private static,可 mixin;`WorldRenderingPipeline` 接口 41 方法(VanillaRenderingPipeline 为默认值参照)。
13. **IrisRenderingPipeline 对 WorldRenderingSettings 的置位清单**(需在我们的管线里镜像):setVertexFormat/setBlockStateIds/setBlockTypeIds/setEntityIds/setItemIds/setAmbientOcclusionLevel/setDisableDirectionalShading/setUseSeparateAo/setSeparateEntityDraws/setVoxelizeLightBlocks/setBreaksAnisotropy,数据源 pack.getIdMap() 与 programSet.getPackDirectives()。

## 3. 架构(形态 B 的 B2-1 切片)

```
游戏内:
Iris.loadShaderpack(放行) ─→ ShaderPack(真实解析;StandardMacros 假接=离线矩阵同款 pinned 环境)
Iris.createPipeline ──mixin──→ MetalWorldRenderingPipeline(我们的,实现 WorldRenderingPipeline)
   ├─ WorldRenderingSettings 置位(§2.13;vertexFormat=FormatAnalyzer.createFormat(t,t,t,t))
   ├─ IrisMetalPipelineOverrides.activate(programSet, device)   ← 注册表激活
   └─ beginLevelRendering(): IrisMetalUniformValues.updateFrame()

绘制线:
sodium DefaultChunkRenderer.createRenderPass ──mixin redirect──→
   IrisMetalTerrainPass.begin(encoder, ...):附件0=主帧缓冲,附件1..k=IrisMetalRenderTargets
   (按当前 kind 的 DRAWBUFFERS),创建后立即 pass.setUniform("MetallumIrisUniforms",...)
   + 绑定 iris 采样器(lightmap/noisetex/...)
sodium draw → MetalRenderPass.bindDrawState:名字齐全,正常走

编译线:
MetalDevice.computeIfAbsent(sodiumPipeline) ─→ IrisMetalPipelineOverrides.tryCompile(device, p)
   命中(§2.2 判定)→ 合成 RenderPipeline(wrapped GLSL 经合成 ShaderSource)
   → MetalCrossShaderCompiler.compile(库存链:vanilla GlslCompiler→rebind→Spvc→PSO)
   未命中 → 原路径
```

关闭/回退:`MetalWorldRenderingPipeline.destroy()` → 注册表清空 + `MetalDevice.clearPipelineCache()`(下次编译回落原生)+ WorldRenderingSettings.setVertexFormat(ChunkMeshFormats.COMPACT)。
总开关:`-Dmetallum.iris.semantic`。**当前默认 `false`(关)**,`=true` 才开;S4+S6 落地并冒烟通过后把默认改成 `true`,届时 `=false` 即回到纯休眠(冒烟 C 行为)。理由见 §4.1 末尾。

## 4. 实施步骤 ledger

- [x] **S1 转译 lane**(`MetalIrisShaderCompiler`):`translateSodiumTerrain(ProgramSource, ShaderKey, textureMap)`;patchSodium(§2.3 约定)→ stripComments→renameHostileIdentifiers→wrapLooseUniforms;新增:wrap 返回 **std140 成员布局**(name/glslType/offset/size,按收集顺序);文本枚举 wrapped GLSL 的 sampler/UBO 声明;从 `ProgramSource.getDirectives()` 取 DRAWBUFFERS。产物 record `SodiumTerrainProgram`(vertexGlsl/fragmentGlsl/uniformLayout/samplers/ubos/drawBuffers/alpha)。**注意**:此 lane 停在 GLSL,不出 MSL(库存链负责)。
- [x] **S2 注册表+合成管线**(`IrisMetalPipelineOverrides` 新类):`activate(device, programSet, textureMap)`(翻译 3 kind,失败记日志并跳过该 kind)/`deactivate()`/`tryCompile(device, RenderPipeline)`(§2.2 判定;懒构建合成管线,XHFP VertexFormat 来自 WorldRenderingSettings;colorTargets 按 §1 显示语义;BindGroupLayout=枚举出的资源;合成 ShaderSource 闭包返回 GLSL)→ `MetalCrossShaderCompiler.compile`。**MetalDevice 两处 computeIfAbsent lambda 前置查询**。
- [x] **S3 离线 GPU 测试**(`MetalIrisSodiumTerrainTest` 新测试,归入 `metalIrisShaderTranslationTest` 同套件 task):真机 device;BSL+Potato;对 solid/cutout/translucent:S1 翻译→S2 合成→库存链编译→断言 isValid() + 资源表含 MetallumIrisUniforms/gtexture(名字以 dump 为准);失败 dump 到 build/reports/metallum/sodium-terrain-dumps/。**首跑即 ground truth 采集**(patched GLSL 的属性名/uniform 名/输出布局落盘)。
- [x] **S4 uniform 供给**(已落地,见 §4.1;实现与本条规格的差异在 §4.2 顶部说明)(`IrisMetalUniformValues` 新类):按 S1 布局填 std140 buffer(transient 环);首版实值:gbufferModelView(+Inverse/Prev)、gbufferProjection(+Inverse/Prev)、cameraPosition(+prev)、frameTimeCounter/worldTime/worldDay、viewWidth/viewHeight、near/far、fogColor/skyColor/fogDensity 近似、sunAngle/shadowAngle/sunPosition/moonPosition/shadowLightPosition/upPosition、eyeAltitude、isEyeInWater=0、rainStrength、screenBrightness、ambientLight 类缺省;**未覆盖名置零并每名一次日志**。矩阵源用 Iris `CapturedRenderingState`(其填充 mixin 在 Metal 上活跃)+ 天体公式按 CelestialUniforms 语义(sunPathRotation=programSet 值)。
- [x] **S5 唤醒 mixin 组**(已落地,见 §4.1 实际实现;默认关,`-Dmetallum.iris.semantic=true` 开):
  - `IrisBootstrapCompatMixin.loadShaderpack`:`holdIrisDormant()` → 改为 `holdIrisDormant() && !MetalIrisCompat.semanticLayerEnabled()` 时取消。
  - 新 `IrisPipelineFactoryMixin`(target `Iris.createPipeline` HEAD):semantic 启用且 currentPack 存在 → 返回 `new MetalWorldRenderingPipeline(...)`。
  - `GlStateManagerCompatMixin`:加 `_getString` 假接(VENDOR="Apple", RENDERER="Metallum Metal", VERSION="4.6.0 Metallum", GLSL="4.60");`_getInteger` 加 `GL_NUM_EXTENSIONS(33309)→0`。
  - `IrisRenderSystemCompatMixin`:加 `getStringi` 假接(返回 null——NUM_EXTENSIONS=0 时不会被调;防御性)。
  - 新 `MetalWorldRenderingPipeline`(§2.13 置位 + 41 方法默认值,参照 VanillaRenderingPipeline 返回;getTextureMap 返回 pack 的 customTextureDataMap 若可得否则空 map)。
- [~] **S6 地形 pass 附件扩展**(S6a 资源供给已落地;S6b 多附件扩展未做):新 sodium mixin(mixins.json 加包)redirect `DefaultChunkRenderer` 的 `createRenderPass` 调用 → `IrisMetalTerrainPass.begin(...)`:活跃且 kind 判定命中 → 扩展附件 + 预置资源;否则原样。IrisMetalRenderTargets 实例由注册表持有(主帧缓冲尺寸,resize 跟随)。
- [ ] **S7 客户端冒烟**(哨兵纪律:确认 options.txt `startedCleanly:true`+`preferredGraphicsBackend:"default"`,删 run/logs/latest.log):BSL 启用,进世界 90s;判定:日志出现覆盖编译标记、无崩溃、截图可见非 vanilla 地形着色。截图对照 vanilla。
- [ ] **S8 文档+提交**:validation(B2-1 章节:判定、显示语义边界、迭代记录)、acceptance(缺口 2 状态更新——**只有真实渲染验证通过才可标进展;阶段一仍不通过**)、plan、runbook(新开关/任务)、记忆、提交。

进度记录(接手必读;实施时逐条追加,保持与 ledger 一致):
- 2026-07-27: §2 事实收集与设计冻结完成;S1 起步。
- 2026-07-27: **S1/S2/S3 完成**。`metalIrisShaderTranslationTest --tests MetalIrisSodiumTerrainTest` 绿:BSL+Potato × solid/cutout/translucent 共 6 个组合全部创建出有效 PSO(`isValid()==true`),资源表含 `MetallumIrisUniforms`。回归:`test`、`metalMrtBackendIntegrationTest`、`metalComputeBackendIntegrationTest`、`metalIrisTargetsIntegrationTest` 全绿(共享编译链改动见 §6 迭代 1)。
  实测产物(供 S4/S6 参照):BSL SOLID drawBuffers=[0] / 48 个 uniform / 800B 块 / samplers=[u_SectionTimeInfo,gtexture,noisetex,shadowtex0,shadowtex1,shadowcolor0];BSL TRANSLUCENT drawBuffers=[0,1] / 55 uniform / 1024B / 另加 gaux1,gaux2,depthtex1;Potato 三种 kind 均 28 uniform / 656B / samplers=[u_SectionTimeInfo,noisetex,gtexture,lightmap],SOLID+CUTOUT drawBuffers=[0,2]、TRANSLUCENT drawBuffers=[3,4]。
- 2026-07-27: **S5 完成(代码落地,未冒烟)**。唤醒线见 §4.1 表。`compileTestJava` 通过;`metalIrisShaderTranslationTest --rerun-tasks` 全绿(B2-2 矩阵 + B2-1 terrain 6/6)。
  **语义层默认关**(`-Dmetallum.iris.semantic=true` 才开),因为 S4/S6 未做,开了会在首次地形绘制抛`Missing uniform MetallumIrisUniforms`。下一步严格按 §4.2(S4)→ §4.3 S6a → 冒烟(S7)→ 把默认改成 true。
  (该条已被下一条更新)**当时未验证项**:游戏内 pack 解析、`Iris.createPipeline` 重定向、`MetalWorldRenderingPipeline` 的 WorldRenderingSettings 置位、XHFP mesh 重建、任何真实渲染。
- 2026-07-27: **S4 + S6a 完成,语义层默认改为开**(`-Dmetallum.iris.semantic=false` 为 kill switch)。
  新增 `IrisMetalUniformValues`(按 std140 布局逐名填块,懒分配 GPU buffer,采样失败降级为中性帧)、
  `IrisMetalPlaceholderTextures`(1×1 彩色 + 1×1 深度/compare,后者供 `sampler2DShadow`)、
  `MetalRenderPass.pushDescriptor` 的缺名 fallback(仅当覆盖注册表活跃且该 PSO 是覆盖时生效,否则照旧抛)。
  **顺带修掉一个会静默渲染错误的 bug**:见 §6 迭代 3(sodium 的 per-draw push constants 被折进了包 uniform 块)。
  离线门新增 `verifyUniformSupply`:走一遍 PSO 的完整绑定表,断言每个非 sodium 提供的资源都能被 fallback 解析,
  并断言 `gbufferModelView` 的 16 个 float 真的被写进了块的正确偏移。
  验证:`metalIrisShaderTranslationTest` / `test` / `metalMrtBackendIntegrationTest` /
  `metalComputeBackendIntegrationTest` / `metalIrisTargetsIntegrationTest` 全绿。
  **仍未验证**:任何游戏内运行(S7 冒烟未做)。**已知边界**:DRAWBUFFERS 长度 >1 的 kind 仍走 sodium 原生
  (BSL solid/cutout 是 `[0]` 会生效;BSL translucent `[0,1]`、Potato 全部 `[0,2]`/`[3,4]` 不生效),需 S6b。

## 4.1 S5 的实际实现(已落地,与原计划的差异)

已提交的唤醒线(全部编译通过,**未做游戏内冒烟**):

| 文件 | 改动 |
|---|---|
| `MetalIrisCompat` | 新增 `semanticLayerEnabled()`:`SEMANTIC_LAYER && holdIrisDormant()`。`SEMANTIC_LAYER` 由 `-Dmetallum.iris.semantic` 控制,**当前默认 `false`(见下方“为什么默认关”)**。 |
| `IrisBootstrapCompatMixin` | `loadShaderpack` 的取消条件改为 `holdIrisDormant() && !semanticLayerEnabled()`。`onRenderSystemInit`/`duringRenderSystemInit` **保持无条件取消**(它们是真 GL)。 |
| `GlStateManagerCompatMixin` | `_getInteger` 加 `GL_NUM_EXTENSIONS(33309) → 0`;新增 `_getString` 注入:`GL_VENDOR(7936)="Metallum"`、`GL_RENDERER(7937)="Metallum Metal"`、`GL_VERSION(7938)`/`GL_SHADING_LANGUAGE_VERSION(35724)="4.6.0"`,其余 `""`。字节码确认 StandardMacros 只用这几个。`"4.6.0"` 经 Iris 的 `SEMVER_PATTERN`(`(?<major>\d+)\.(?<minor>\d+)\.*(?<bugfix>\d*)(.*)`)得 `MC_GL_VERSION=460`/`MC_GLSL_VERSION=460`,与离线 shadow 一致;vendor/renderer 都不匹配 Iris 的任何已知硬件子串 → 落 `MC_GL_VENDOR_OTHER`/`MC_GL_RENDERER_OTHER`(**故意的**:不让包在 Metal 上走厂商特化分支)。 |
| `IrisRenderSystemCompatMixin` | 新增 `getStringi` 注入返回 `""`(防御性;NUM_EXTENSIONS=0 时不会被调)。 |
| `MetalWorldRenderingPipeline`(新) | **`extends VanillaRenderingPipeline`** —— 比原计划的“实现 41 个方法”省掉全部样板,且默认值天然正确。构造器镜像 §2.13 置位;`beginLevelRendering()` 覆写(**不调 super**,super 是 glClipControl)里懒初始化 blockStateIds/blockTypeIds 并 `Minecraft.getInstance().levelExtractor.allChanged()`;覆写 `getTextureMap`/`getSunPathRotation`/`shouldDisableDirectionalShading`;`destroy()` 调 `IrisMetalPipelineOverrides.deactivate()`。 |
| `IrisPipelineFactoryMixin`(新) | `Iris.createPipeline` HEAD;semantic 开且 `Iris.getCurrentPack()` 非空 → 返回 `new MetalWorldRenderingPipeline(pack.getProgramSet(dimensionId))`;抛异常 → 记日志并返回 `new VanillaRenderingPipeline()`(**绝不放行让 IrisRenderingPipeline 的 GL 构造器跑**)。已加进 `metallum.mixins.json` 的 client 列表。 |
| `IrisMetalPipelineOverrides` | 新增静态开关 `extendedTerrainTargets`:DRAWBUFFERS 长度 >1 且未置位时 `compileOverride` 返回 null(每 kind 告警一次)。原因见 §2.8:PSO 按 pass 附件签名查表,pass 没有那些附件时编出来也绑不上。离线测试里置 `true` 以覆盖全部 kind。 |

**为什么默认关**:S4(uniform 供给)与 S6(pass 资源预置)尚未实现。合成管线的 BindGroupLayout 声明了 `MetallumIrisUniforms` 和包的采样器,而 `MetalRenderPass.pushDescriptor` 对缺失名字直接抛
`Missing uniform MetallumIrisUniforms` / `Missing sampler <name>`。所以现在打开 `-Dmetallum.iris.semantic=true` 并启用光影包,**第一次地形绘制就会崩**。S4+S6 落地并冒烟通过后,把 `MetalIrisCompat.SEMANTIC_LAYER` 的默认值改成 `"true"`(一行),并把该 javadoc 段落删掉。

---

## 4.2 S4 实现规格(接手直接照做)

新建 `src/main/java/com/metallum/client/metal/render/IrisMetalUniformValues.java`。

**数据来源(全部已验证存在)**:
- `MetalIrisShaderCompiler.GlslProgram.uniformLayout()` → `List<UniformMember>`,每项 `(String type, String name, int arrayCount, int offset, int byteSize)`;块总大小 `uniformBlockSize()`。offset 是 std140 字节偏移,已由 `MetalIrisSodiumTerrainTest.verifyStd140` 对着 SPIRV-Cross 反射逐个校验过,**可以直接信任**。
- 矩阵:`net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE` —— `getGbufferModelView()`(`Matrix4fc`)、`getGbufferProjection()`(`Matrix4fc`)、`getFogColor()`(`Vector3d`)、`getFogDensity()`、`getTickDelta()`。其填充 mixin 不属于被休眠的 GL 面,在 Metal 上活跃。
- 其余:`Minecraft.getInstance()` 的 level/player/window。

**写法**:
1. 一个 `GpuBuffer`,`RenderSystem.getDevice().createBuffer(() -> "metallum:iris_uniforms", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, size)`;size = `program.uniformBlockSize()`(每 kind 一个,或取三者最大值共用)。
2. 每帧一次 `updateFrame()`:往一个 `ByteBuffer`(`ByteOrder.nativeOrder()`,即 little-endian)按 layout 写值,再 `RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(), data)`。
3. 逐名填值,**用 switch 按 name 分发**;命中不到的名字:按 `byteSize` 清零,并用一个 `Set<String>` 去重、每名 `LOGGER.debug` 一次(**不要每帧刷屏**)。
4. 首版必须给实值的名字(BSL terrain 实测 48 个、Potato 28 个,取并集覆盖即可):
   `gbufferModelView` / `gbufferModelViewInverse` / `gbufferProjection` / `gbufferProjectionInverse`
   / `gbufferPreviousModelView` / `gbufferPreviousProjection`(首帧用当前值)
   / `cameraPosition` / `previousCameraPosition` / `frameTimeCounter` / `worldTime` / `worldDay`
   / `viewWidth` / `viewHeight` / `aspectRatio` / `near` / `far`
   / `fogColor` / `skyColor` / `fogDensity` / `fogStart` / `fogEnd`
   / `sunAngle` / `shadowAngle` / `sunPosition` / `moonPosition` / `shadowLightPosition` / `upPosition`
   / `eyeAltitude` / `eyeBrightness` / `eyeBrightnessSmooth` / `isEyeInWater`(=0)
   / `rainStrength` / `wetness` / `screenBrightness` / `nightVision`(=0) / `blindness`(=0)
   / `alphaTestRef`(从 `ShaderKey.getAlphaTest()`)。
   天体向量按 Iris `CelestialUniforms` 的语义算:`sunAngle` 来自 `level.getTimeOfDay(tickDelta)`,`sunPathRotation` 取 `programSet.getPackDirectives().getSunPathRotation()`。
5. **std140 写入规则**(与 `MetalIrisShaderCompiler.STD140_TYPES` 一致,别自己另立一套):`vec3` 占 16 字节但只写前 12;`mat4` 是 4 个 vec4 列,列主序,每列 16 字节;`mat3` 是 3 个 vec4 列,每列写前 12 字节。
6. 单元测试(放进 `metalIrisShaderTranslationTest` 套件):对 BSL SOLID 的 layout 跑一次 `updateFrame()`,断言 (a) 不抛异常,(b) `gbufferProjection` 处的 16 个 float 与 `CapturedRenderingState` 里的矩阵逐元素相等,(c) 未覆盖名区间全零。

## 4.3 S6 实现规格(接手直接照做)

分两半,**S6a 是必须的,S6b 可以先跳过**。

**S6a — 把资源喂给地形 pass(不做就崩)**。seam 有两个,选后者:

- ~~在 `DefaultChunkRendererMetalFxMixin` 的 `createRenderPass` redirect 里 `pass.setUniform(...)`/`pass.bindTexture(...)`~~ —— 可行但要在 mixin 里拿到 block atlas / lightmap 的 `GpuTextureView`,而那些是 sodium 在 pass 创建**之后**才绑的。
- **推荐:在 `MetalRenderPass.pushDescriptor` 补一个 fallback**(`MetalRenderPass.java:612` 起)。当前它对缺名直接抛:
  ```java
  TextureViewAndSampler textureBinding = samplers.get(binding.name());
  if (textureBinding == null) {
      throw new IllegalStateException("Missing sampler " + binding.name());
  }
  ```
  改成先问覆盖注册表,再抛:
  ```java
  TextureViewAndSampler textureBinding = samplers.get(binding.name());
  if (textureBinding == null) {
      textureBinding = IrisMetalPipelineOverrides.fallbackTexture(binding.name(), samplers);
  }
  if (textureBinding == null) {
      throw new IllegalStateException("Missing sampler " + binding.name());
  }
  ```
  uniform 分支同理走 `IrisMetalPipelineOverrides.fallbackUniform(binding.name())`。
  这个位置是 draw 时,sodium 的 `u_BlockTex`/`u_LightTex` 已经在 `samplers` 里了,可以直接转手。
  `fallbackTexture` 的映射(B2-1 首版,够 BSL/Potato terrain 用):
  `gtexture`/`texture`/`tex` → 复用已绑的 `u_BlockTex`;`lightmap` → `u_LightTex`;
  其余(`noisetex`、`shadowtex0`、`shadowtex1`、`shadowcolor0`、`depthtex1`、`gaux1`、`gaux2`…)→ 一张 1×1 占位纹理(白色)+ 默认 sampler,并每名告警一次。
  `fallbackUniform("MetallumIrisUniforms")` → S4 的 buffer slice。
  **注意**:只在 `IrisMetalPipelineOverrides.active() != null` 时才做 fallback,否则原样抛——别掩盖真实 bug。

**S6b — 扩展 pass 附件(多 DRAWBUFFERS)**:在 `DefaultChunkRendererMetalFxMixin` 已有的 `createRenderPass` redirect 里追加分支(**不要新开一个 redirect,同一 invoke 上两个 redirect 会冲突**):活跃且当前 kind 的 `drawBuffersFor(kind).length > 1` 时,用 `RenderPassDescriptor.create(label).withColorAttachment(colorTexture, clearColor).withColorAttachment(<IrisMetalRenderTargets 的第 i 个>)…` 建 pass;然后把 `IrisMetalPipelineOverrides.setExtendedTerrainTargets(true)` 置位。附件格式必须与 `IrisMetalPipelineOverrides.EXTENDED_TARGET_FORMAT`(RGBA8_UNORM)一致,否则 PSO 查表落空。

### 迭代 3 — sodium 的 per-draw push constants 被折进了包 uniform 块(S4 前发现)

- **现象**:无(离线门全绿,PSO 有效)。是在写 S4 逐名填值时,看着 dump 里的
  `u_CurrentTime` / `u_RegionID` / `u_RegionOffset` 出现在 `MetallumIrisUniforms` 里才发现的。
- **根因**:sodium 的 `block_layer_opaque.vsh` 里这三个是
  `#ifdef VULKAN → layout(push_constant) uniform PC {...}` / `#else → 松散 uniform`,
  Iris 的 `patchSodium` 产出走的是 `#else` 分支,于是我们的 `wrapLooseUniforms` 把它们
  当成包的 uniform 收进了统一块。而 `MetalDrawContext.updateData` 是**每个 render region**
  写 20 字节(`u_RegionOffset`@0 / `u_CurrentTime`@12 / `u_RegionID`@16)并
  `setUniform("push_constants", slice)` —— 它永远不会写到我们的块里。
- **后果(若不修)**:编译通过、PSO 有效、绘制不报错,但每个 region 读到的 `u_RegionOffset`
  恒为 0 → **所有区块塌到区域原点**,是那种“测试全绿但画面全错”的 bug。
- **修复**(`MetalIrisShaderCompiler`):`partitionSodiumPushConstants` 把这三个从松散 uniform
  里摘出去,再以 `layout(push_constant) uniform MetallumSodiumPushConstants {...}` 原样重新
  发射(只发射给原本声明它们的 stage)。库存链于是产出与原生 sodium 管线**完全相同**的
  `push_constants` 资源,per-draw ABI 不变。类型/顺序对不上就抛 `TranslationException`——
  sodium 改了这个块要炸在转译期,而不是变成几何错位。
- **验证**:6/6 的资源表都多出 `push_constants`,uniform 数各减 3(BSL SOLID 48→45)。

### 迭代 4 — 唤醒线的三个真实客户端阻塞(S7 首跑)

按顺序踩到,每个都靠日志栈直接定位:

1. **`loadShaderpack` 根本没被调到**。放行 `loadShaderpack` 的注入是空操作——
   字节码确认它在启动期**只有一个调用点**:`Iris.onRenderSystemInit` 的最后一句
   (offset 149),而我们对 `onRenderSystemInit` 是无条件取消的(它从第一句就是
   `GL.getCapabilities`)。**修**:`IrisBootstrapCompatMixin.metallum$skipGlRendererInit`
   在语义层开启时先自己 `Iris.loadShaderpack()`(包在 try/catch 里,装载失败只让
   `currentPack` 留空,不能把渲染器初始化拖下水)再 cancel。
   *`onRenderSystemInit` 被跳过的其余内容*:`PBRTextureManager.init`(GL)、
   4 个 `VertexSerializerRegistry.registerSerializer`(纯 CPU,目前不需要)。
2. **`ShaderPack.<init>` 撞 GL capability 探测**:
   `FeatureFlags.isUsable` → `IrisRenderSystem.supportsImageLoadStore` → `GL.getCapabilities()`
   → `IllegalStateException: No GLCapabilities instance set`。**修**:`IrisRenderSystemCompatMixin`
   把 `supportsImageLoadStore` / `supportsBufferBlending` / `supportsCompute` /
   `supportsTesselation` 一并假接为 false(`supportsSSBO` 早已假接)。全 false 是**故意**的:
   B2-1 只实现 gbuffer terrain,不能让包走 compute/image 分支;真要求这些特性的包会被 Iris
   按正常流程拒绝,这比渲染错误好。
3. **`MetalWorldRenderingPipeline` 构造顺序 NPE**:`VanillaRenderingPipeline` 的构造器会调用
   虚方法 `shouldDisableDirectionalShading()`,此时子类字段还没赋值 → `programSet` 为 null。
   **修**:该覆写加 null 检查(超类构造期返回 vanilla 默认值),构造器里改用
   `directives.isOldLighting()` 直接算并写进 WorldRenderingSettings。

**S7 首跑结果(2026-07-27,BSL 10.1.3,`enableShaders=true`)**:
```
[metallum] Iris-on-Metal semantic layer active: ...
[Iris] Profile: HIGH (+0 options changed by user)
[Iris] Using shaderpack: bsl-shaders.zip
[metallum-iris] translated sodium terrain SOLID from pack program gbuffers_terrain (drawBuffers=[0])
[metallum-iris] translated sodium terrain CUTOUT from pack program gbuffers_terrain (drawBuffers=[0])
[metallum-iris] translated sodium terrain TRANSLUCENT from pack program gbuffers_water (drawBuffers=[0, 1])
[metallum-iris] semantic pipeline generation 1 online for pack program set Profile: HIGH
```
到标题画面为止 0 崩溃、管线创建后无任何 ERROR。**注意 in-game 的 drawBuffers 与离线不同**:
离线跑的是默认 profile,in-game 是 BSL 的 HIGH profile,translucent 变成 `[0,1]`——
**S6b 的必要性由 profile 决定,不能只看离线结果**。

**S7 仍未完成的部分**:没有进世界,因此 `IrisMetalPipelineOverrides.tryCompile` 是否真的
在地形绘制时被命中、`MetallumIrisUniforms`/采样器 fallback 是否真的喂上、画面是否出现
pack 着色——**全部未验证**。接手第一件事就是进世界看 `compiling terrain override` 日志。

## 4.4 与集成分支对齐(高频集成协议)

集成分支 = `MetalUniversal-master` 的 `wip/uncommitted-snapshot-2026-07-27`(fbff4d7+)。
**本仓库无 remote,集成纯本地,不 push。** 遇到 `index.lock` 是别的会话在操作,等几秒重试,**不要 rm 锁**。

`iris-on-metal` 与集成分支的共同祖先是 `ea2dfd4`(两线都从这里分出)。截至 `9538341`,
**双方都改过的文件(= 真实冲突面,10 个)**:

| 文件 | 本线改了什么 | 冲突风险 |
|---|---|---|
| `MetalRenderPass.java` | `pushDescriptor` 缺名 fallback(S6a) | **高·语义级**——这就是协议警告里的「绑定 45 处」;Metal 4 迁移线也在改绑定路径。git 可能不报冲突但运行期绑定语义会坏 |
| `MetalCrossShaderCompiler.java` | varying 按槽宽重排 + 若干成员放宽到包级可见 | 中——改的是编译期 location 分配,与同步层无关 |
| `MetalDevice.java` | 两处 `computeIfAbsent` 前置查询覆盖注册表 | 低 |
| `metallum.mixins.json` | 新增 `iris.IrisPipelineFactoryMixin` | 低·纯追加 |
| `build.gradle` | 测试 task 加一条 `includeTestsMatching` | 低·纯追加 |
| `MetalFxManager.java` / `MetallumNative.swift` / `MetalNativeBridge.java` / `MetalCommandEncoder.java` / `MetalGpuTexture.java` | **本线未改**(记忆里的老风险,现已不成立) | 无 |

**本线迄今没有动过同步层**:没碰 fence 链、barrier、encoder 边界。唯一沾边的是
`MetalRenderPass.pushDescriptor` 的**资源解析**(不是同步),但它与 Metal 4 迁移线的绑定改造
落在同一函数区域,合并时必须逐行看,不能信 git 的「无冲突」。

**合并前必须知道**:本线的 fallback 只在 `IrisMetalPipelineOverrides.active() != null` 时生效,
非覆盖管线一律返回 null 走原逻辑 —— 所以对 Metal 4 线是**加法**,不改变既有绑定语义。
合并后请重跑 `metalIrisShaderTranslationTest` 与 `metalMrtBackendIntegrationTest` 验证。

### 4.4.1 合并已完成(2026-07-27)

**结果:5 个文件冲突全部解决,合并已提交,回归全绿。**下面保留逐文件解法作为记录;
`MetalCommandEncoder` 那条的答案由 Metal 4 线给出(见本节末尾)。

原始记录(首次尝试时曾 abort 过一次):

`git merge wip/uncommitted-snapshot-2026-07-27` 实跑过一次,**5 个文件冲突,已 `--abort`**。
下面是逐个的解法,照做即可;三个是机械的,一个必须由 Metal 4 线的人拍板。

| 文件 | 冲突内容 | 解法 |
|---|---|---|
| `MetalFxManager.java`(1 处) | 同一段 `createTexture("MetalFX Reactive R8", ...)`,只是注释措辞与换行不同,**代码等价** | **取 theirs**。MetalFX 是他们的线 |
| `MetalDevice.java`(2 处) | HEAD 把覆盖查询塞进 `computeIfAbsent`;theirs 重构成 async prewarm + `COMPILE_CHAIN_LOCK` + `PENDING_PRECOMPILE` | **取 theirs 的结构,把覆盖查询搬进去**:在 `synchronized (COMPILE_CHAIN_LOCK)` 里的 `computeIfAbsent` lambda 内,以及 `compileInBackground` 里,都改成先问 `IrisMetalPipelineOverrides.tryCompile(this, p, effectiveSource)`,非 null 就用它。**两处都要改**,漏掉后台那条会导致预热出来的是原生 PSO |
| `MetalCrossShaderCompiler.java`(1 处) | 纯相邻插入:HEAD 把 `vertexAttributeFormats` 放宽到包级;theirs 在它前面加了 `vertexFormatSignature`/`bindGroupSignature`(MSL 磁盘缓存的 key) | **两边都留**。注意最终 `vertexAttributeFormats` 要保持**包级可见**(`static`,不是 `private static`) |
| `MetallumNative.swift`(1 处) | HEAD 是 B0 的 compute/mipmap/compare-sampler ABI 段;theirs 在同一位置加 M4 相关导出 | **两边都留**,顺序无所谓,都是独立的 `@_cdecl` |
| `MetalCommandEncoder.java`(2 处) | **语义冲突,不要自己猜** | 见下 |

**`MetalCommandEncoder` 为什么不能机械合**:
- HEAD 侧是本线 B0 的**单 MTLFence 链**:`computeCommandEncoder()` 里 `encoder.waitForFence(fence)`,
  `endEncoder()` 里 render/blit/**compute** 三种编码器都 `updateFence(fence)`。
- theirs 侧是 S10 的**拆分 fence**:`SPLIT_FENCE` 时 `transferFence` 管上传→顶点抓取、
  `fence` 管前一趟 render 输出→fragment 消费(`waitRenderFences` 按 `MTLRenderStages` 收窄),
  且 blit 改成 `updateFence(SPLIT_FENCE ? transferFence : fence)`,**compute 分支在冲突块里消失了**。
- 需要回答的问题只有一个:**拆分 fence 模型下,compute 编码器 wait/update 哪一条 fence?**
  这是 Metal 4 线的设计决定,不是可以从代码推出来的。答错的后果正是协议警告的那种:
  编译通过、跑得动、同步语义已经坏了,而且很难在事后定位。
- 建议:让 Metal 4 线的会话给出这一条的答案(或直接由他们做这个文件的合并),其余四个文件本线可以自己合。

**本线在同步层的实际持仓**:`MetalCommandEncoder` 的 compute 编码器 fence 语义(B0 引入,
`metalComputeBackendIntegrationTest` 里 render→compute→render / compute→compute / indirect args
三条有序性用例在守它)。合完必须重跑这个 task,它是判定同步语义没坏的唯一自动化证据。

**`MetalCommandEncoder` 的答案(Metal 4 线给出,2026-07-27)**:

> compute 编码器归 **render fence**——即调用方作为参数传进来的那个 fence,不是 transfer fence。
> 依据是 `NativeState.transferFence` 的不变量注释:split-fence 模式下**唯一**在 transfer 链上的
> Swift 编码器是 frame-generation 的输入 copy blit,其余 native 编码器一律留在传入的 render fence 上。
> 旁证:7 处 `makeComputeCommandEncoder`(:2550/:2647/:2716/:2764/:2876/:3093/:3135)正好对应
> `NativeState` 里的 7 条 MetalFX compute 管线;规格 M6 依赖表「compute 写 → render 读」那一行,
> Metal 3 现状记的就是「全量 wait/update 传入 fence」。

据此的最终解法(**不是**我原先那个「保守地两条链都上」——那个会让 M7e 迁移时把这条链
误翻成两对屏障):`computeCommandEncoder()` 只 `waitForFence(fence)`,`endEncoder()` 里
compute 分支只 `updateFence(fence)`,两种模式下都一样,不碰 `transferFence`。
代码里的注释写明了这条依据,以及它对应 M6 表的 dispatch→fragment 生产者/消费者对。

**该线自陈的边界(照录)**:没有逐个复核那 7 个调用点是否有哪一处直接摸了
`NativeState.transferFence`——注释声明的是不变量,不等于逐点验证。要钉死需要 grep 这 7 处的
fence 参数来源。**本线没做这个复核**;它只影响 Swift 侧 MetalFX 通道,不影响 Java 侧
compute 编码器的 fence 归属(本次改动只动了 Java 侧)。

**合并后回归(全绿)**:`metalComputeBackendIntegrationTest`(守 compute fence 语义的三条
有序性用例)/ `metalMrtBackendIntegrationTest` / `metalIrisTargetsIntegrationTest` /
`metalIrisShaderTranslationTest` / `test`。

**合并带来的一处本线必须知道的结构变化**:`MetalDevice` 现在有 async prewarm 线程,
编译走三条路径(render 线程按需、prewarm 后台、precompile)。三条都已收敛到新的
`compileWithIrisOverride(pipeline, source)` 单一漏斗——**漏掉后台那条会让预热抢先把原生 PSO
写进缓存,覆盖静默失效**。以后再改编译路径,保持这个漏斗是唯一入口。

## 5. 风险与预案

| 风险 | 信号 | 预案 |
|---|---|---|
| vanilla GlslCompiler 拒绝 patched GLSL(版本指令/方言) | S3 编译异常 dump | wrap 阶段重写 `#version` 行为 MC 同款;若结构性拒绝,回退方案=用 B2-2 自有 shaderc lane 出 SPIR-V 再手动 rebind(等价库存链后半) |
| patched 顶点属性名与 XHFP VertexFormat 元素名不一致 | S3 rebind 后 tolerateUnprovidedInputs 吞掉属性(渲染错) | dump 对照;必要时在合成 VertexFormat 上做名字桥接(不改 shader) |
| BSL terrain 需要的 iris 采样器超出预置集 | S3 资源表/S7 bindDrawState "Missing sampler X" | 该名加入预置(占位 1×1 纹理或真实源),记录到 validation |
| ShaderPack 游戏内解析崩溃(StandardMacros 之外的 GL 触碰) | S7 启动即崩 | 栈定位→按既有模式加最小假接;**逐条记录进 runbook** |
| translucent blend 逐目标语义(GL 全局 blend vs MRT) | S7 水面异常 | B2-1 接受:target0 用 sodium 原 blend,其余无 blend;记录边界,B2-3 处理 bufferBlendOverrides |
| XHFP mesh 重建时机(setVertexFormat 后已建 section 仍旧格式) | S7 地形花屏/属性错位 | 参照 Iris:pack 加载在世界加载前完成即可;若中途 reload,调 sodium 全量重建(Minecraft.levelRenderer.allChanged()) |
| MetalDevice PSO 缓存含旧覆盖 | reload 后画面不变 | destroy() 已含 clearPipelineCache;确认 resize 语义 |

## 6. S3/S7 迭代记录(简)

（实施中逐条补记:现象 → 根因 → 修复;详细版进 validation 文档。）

### 迭代 1 — sodium 的 `u_SectionTimeInfo` 被当成普通采样器(S3)

- **现象**:`ShaderCompileException: Sampled texture (u_SectionTimeInfo) must have type of SpvDim2D or SpvDimCube`。
- **根因**:合成 RenderPipeline 时我重建了一份新的 BindGroupLayout,把 sodium 声明的全部名字都按普通 sampler 加入;但 `u_SectionTimeInfo` 在 sodium 的 `ShaderChunkRenderer.<clinit>` 里是 **texel buffer(`GpuFormat.R32_SINT`)**,库存 `addToBindGroup` 会按 `UniformDescription` 走 TEXEL_BUFFER 分支校验维度。
- **修复**(`IrisMetalPipelineOverrides.buildSynthetic`):**逐字复制源 sodium 管线自己的 `BindGroupLayout`**,只把包新增的名字(pack uniform block / pack sampler)追加到一个额外的 layout 里;并对包声明的 `samplerBuffer` 直接 fail-closed 抛异常(我们无法为它提供 UTB 格式)。

### 迭代 2 — 矩阵 varying 造成 `[[user(locnN)]]` 槽位重叠(S3)

- **现象**:Potato SOLID 的 PSO `isValid()==false`。MSL 编译器报
  `duplicated user-defined name 'locn2' for vertex output declaration`(vertex 与 fragment 都报),
  例如 `colorPalette_2 [[user(locn2)]]` 与 `coord_0 [[user(locn2)]]` 撞槽。BSL 不受影响。
- **根因**(字节码 + 运行时 dump 双向确证,**不是**当初猜的“只有 rebind 有问题”):
  1. `IntermediaryShaderModule.rebind(providedNames, layoutEntries)` 只改写 **inputs** 的 Location
     与 UBO/sampler 的 binding,**从不触碰 outputs**;它给 inputs 的编号是“一个名字一个 location”的稠密序。
  2. 更关键:库存链给 **vertex outputs** 分配的 location 同样是一个变量一个槽。实测 dump:
     `vsOut={colorPalette=0, iris_FogFragCoord=1, coord=2, tint=3, ...}`。
  3. 而 GLSL/SPIR-V 里矩阵 varying 按列各占一个 location(Potato 声明了
     `out mat2 coord` 占 2 槽、`flat out mat4x3 colorPalette` 占 4 槽),数组按元素同理。
     于是 `colorPalette` 实占 0..3,`iris_FogFragCoord`/`coord`/`tint` 落进它的区间 → 重叠。
     原版着色器只有标量/向量 varying,所以库存链一直没暴露这个问题。
- **修复**(`MetalCrossShaderCompiler`,共享编译链):新增按槽宽重排,两侧都做——
  - `varyingLocationSpans(spirv, resourceType)`:用 SPIRV-Cross 反射 stage 输入/输出的类型,
    算每个变量占用的 location 槽数(向量/标量 1;64 位且分量 >2 记 2;矩阵 ×列数;数组 ×元素数)。
  - `relocateVertexOutputs(vertex)`:按当前 location 升序遍历 vertex outputs,逐个分配起始
    location 并按槽宽推进游标,写回 SPIR-V;返回 `VaryingLayout`(名字→起始 location + 下一个空闲槽)。
  - `relocateFragmentInputs(pipeline, fragment, layout)`:**必须在 `fragment.rebind(...)` 之后**调用,
    把 fragment 输入按名字改写成 vertex 侧的同名起始 location;没有同名 vertex 输出的输入(未连接
    varying,读到未定义值)排到 vertex 输出区之后并按 pipeline+名字去重告警一次。
  - location 编号对外无契约,只要两 stage 一致即可,所以可以自由紧密重排。
- **验证**:BSL+Potato × solid/cutout/translucent 6/6 PSO 有效;
  回归 `test` / `metalMrtBackendIntegrationTest` / `metalComputeBackendIntegrationTest` /
  `metalIrisTargetsIntegrationTest` 全绿(原版路径 varying 全是标量/向量,重排结果与原编号等价)。
- **排查手法留档**:`METALLUM_MRT_ABI_DEBUG=1` 会把 fragment MSL 全文打到 stderr;
  当初正是靠它看到 `main0_in` 里重复的 `user(locnN)` 才定位到槽位重叠。
