# Iris 1.11.2+mc26.2(Fabric)GPU 调用面审计

来源:真实 jar 反编译审计(本会话后台 agent;jar sha512 前缀 `c1b46bcd…`,与 Modrinth 版本 `oaD6KQls` 一致)。
产物留存:`/private/tmp/claude-501/.../scratchpad/iris-audit/`(jar、解包树、`gpu_api_analysis.txt`、`blaze3d_analysis.txt`、`key_apis*.txt`、sodium 0.9.0/0.9.1 对照)。
注:MC 26.2 未混淆,jar 内全部是真实 Mojang 名称,无需 intermediary 翻译。

## 1. fabric.mod.json

- id `iris`,1.11.2+mc26.2,client,LGPL-3.0。
- depends:`fabricloader >= 0.12.3`,`sodium: ["0.9.x"]`(硬依赖;无 minecraft 版本约束)。
- 入口点仅 `modmenu` 与 `sodium:config_api_user`;**无 main/client 入口** —— 通过 `MixinRenderSystem.iris$onRendererInit(GpuDevice)` 在 Blaze3D RenderSystem 初始化时自举。
- mixin 配置:`mixins.iris.json`(143 client,插件 `IrisMixinPlugin`)、`mixins.iris.fabric.json`(4)、`mixins.iris.vertexformat.json`(7)、`mixins.iris.compat.sodium.json`(20)、`mixins.iris.compat.dh.json`(4)、maxfpscrash(1)。
- accessWidener 主要面向 **Blaze3D GL 后端内部**:`GlStateManager$*State`、`GlRenderPass.pipeline/samplers`、`GlProgram.<init>(int,String)`+`uniformsByName`、`GlDevice`、`GlCommandEncoder`、`GlBuffer.handle` 等。
- 注入接口(loom injected interfaces):`RenderTarget`、`GpuTexture`(`iris$getGlId`、`iris$markMipmapNonLinear`)、`RenderPass`+`RenderPassBackend`(`iris$setCustomPass`)、`RenderType`、`ItemInHandRenderer`。
- 内嵌:antlr4-runtime-4.13.1、**glsl-transformer 3.0.0-pre3**、jcpp-1.4.14(自带 GLSL AST 变换 + C 预处理器)。

## 2. 三层 GPU API 使用

963 个类;24 个类直接触 `org/lwjgl/opengl`,121 个类触 `com/mojang/blaze3d`。**混合体**:Blaze3D 抽象层用于资源分配与全屏 pass;Blaze3D GL 后端(GlStateManager)用于状态/program;裸 LWJGL GL 用于现代特性(DSA、compute、SSBO、image)。

### 2a. 裸 LWJGL GL(核心:`net.irisshaders.iris.gl.IrisRenderSystem`)

- sampler:`glGenSamplers/glSamplerParameteri/glBindSampler`、GL45 `glBindSamplers` 多绑定;
- image:GL42 `glBindImageTexture`(EXT fallback)、`ARBClearTexture.glClearTexImage`;
- SSBO:GL43 `glBindBufferBase`、GL45 `glBufferStorage`、`glClearBufferSubData`;
- compute:GL45 `glDispatchCompute`、GL43 `glDispatchComputeIndirect`、GL45 `glMemoryBarrier`;
- copy:GL46 `glCopyImageSubData`、`glCopyTexImage2D`、`glCopyTexSubImage2D`(FinalPassRenderer);
- uniform/introspection:`glUniform*`、`glGetActiveUniform`、`glGetUniformBlockIndex`、`glUniformBlockBinding`;
- 其他:`glEnablei/glDisablei`、ARB per-buffer blend(`glBlendFuncSeparateiARB`)、`glPolygonMode`、`glReadPixels`、`glCheckFramebufferStatus`;
- DSA 三策略(`$DSAARB/$DSACore/$DSAUnsupported`):`glCreateFramebuffers/Textures/Buffers`、`glNamedFramebufferTexture/DrawBuffers/ReadBuffer`、`glBlitNamedFramebuffer`、`glGenerateTextureMipmap`、`glCopyTextureSubImage2D` vs legacy 路径;
- 能力探测:DSA、SSBO、image load/store、buffer storage、multi-bind、draw-buffers-blend、tessellation、GL40/42/44/45。
- 其他裸 GL 类:`GLDebug`(debug 标签/组)、`GlImage`、`ShaderWorkarounds`(`nglShaderSource`)、`DepthCopyStrategy`(Gl20CopyTexture/Gl30BlitFb/Gl43CopyImage 三选一)、`IrisRenderingPipeline`/`VanillaRenderingPipeline`(`ARBClipControl.glClipControl`,reverse-Z)、DH compat。

### 2b. Blaze3D GL 后端(GlStateManager,≈裸 GL 经 MC 状态缓存)

`_bindTexture`(16 类)、`_glBindFramebuffer`(9 类:GlFramebuffer.bind、CompositeRenderer、FinalPassRenderer…)、`_glUseProgram`(8 类)、`glGenFramebuffers/_glDeleteFramebuffers`、`_glFramebufferTexture2D`、`_genTexture/_deleteTexture`、SSBO 辅助(`_glGenBuffers/_glBindBuffer/_glBufferSubData`)、`_viewport/_scissorBox/_colorMask/_depthMask/_depthFunc`、blend 开关+`_blendFuncSeparate`、`_clear`、`_drawElements`(CompositeRenderer 全屏quad)、**shader 编译**:`glCreateShader/glShaderSource/glCompileShader/glLinkProgram`(GlShader/ShaderCreator/ProgramCreator)。

### 2c. Blaze3D 抽象层

- `GpuDevice.createTexture`(**RenderTargets/ShadowRenderTargets 的纹理与 depth**、PBRAtlasTexture)、`createBuffer`(FullScreenQuadRenderer 等)、`createSampler`(IrisSamplers)、`createTextureView`、`createCommandEncoder`(9 类);
- `CommandEncoder.createRenderPass`+`RenderPass.*`(setPipeline/bindTexture/setUniform/drawIndexed):CenterDepthSampler、ColorSpaceFragmentConverter、FinalPassRenderer、HorizonRenderer、PBRAtlasTexture、ShadowCompositeRenderer —— 先 `iris$setCustomPass`,由 `MixinGlCommandEncoder` 把 pass 重定向到 Iris 的 GlFramebuffer+自有 program;
- `RenderPipeline.builder()`:`CompositeRenderer.COMPOSITE_PIPELINE`;
- `writeToTexture`(noise/自定义纹理)、`clearDepthTexture`、`getSequentialBuffer`、DeviceInfo(`isZZeroToOne`、driverInfo、extensions);
- **关键**:12 个类用注入的 `GpuTexture.iris$getGlId()` 把 GL id 从抽象纹理里挖出来挂到自建 FBO——**抽象层只用于分配,使用时绕开**。

## 3. 关键内部类(公开 API 摘要)

- `targets.RenderTargets`:`RenderTarget[]`(main+alt GL 纹理 id、InternalTextureFormat、`getMainTexture()/getAltTexture()`)、Blaze3D GpuTexture depth(+noTranslucents/noHand 副本,经 DepthCopyStrategy)、`List<GlFramebuffer>`;`createFramebufferWritingToMain/Alt(int[])`、`createGbufferFramebuffer`、`createColorFramebuffer(WithDepth)`、`resizeIfNeeded`。
- `targets.BufferFlipper`:`flip(int)`、`isFlipped(int)`、`snapshot()`。
- `gl.framebuffer.GlFramebuffer`:裸 FBO:`addColorAttachment(index, glTexId)`、`addDepthAttachment(GpuTexture)`、`addDepthAttachmentBypass(int)`、`drawBuffers(int[])`、`readBuffer`、`bind/bindAsReadBuffer/bindAsDrawBuffer`、`getStatus/getId`。
- `pipeline.WorldRenderingPipeline`(接口):`beginLevelRendering`、`renderShadows(LevelRendererAccessor, Camera, CameraRenderState)`、`beginHand`、`beginTranslucents`、`finalizeLevelRendering/GameRendering`、`setPhase(WorldRenderingPhase)`、`onSetAlbedoTex(GpuTextureView)`、`allowConcurrentCompute` 等;实现:`IrisRenderingPipeline`/`VanillaRenderingPipeline`(`PipelineManager` 按维度管理)。
- `pipeline.CompositeRenderer`:按 stage(Begin/Prepare/Deferred/Composite)的 `Pass{Program, GlFramebuffer, viewport, mipmap 标志}` + `ComputeOnlyPass`;`renderAll()` = GlStateManager 绑 FBO、`_drawElements` 全屏 quad、`ComputeProgram[]` + memory barrier、`setupMipmapping`(DSA glGenerateMipmap)。
- `pipeline.FinalPassRenderer`:`renderFinalPass()` 写主目标;`glCopyTexSubImage2D` SwapPass 维护 colortex 历史。
- `shadows.ShadowRenderTargets/ShadowRenderer/ShadowCompositeRenderer`:同构;shadow pass 重驱动 terrain/entity 渲染(ShadowMatrices、逐 buffer MipmapPass)。
- `gl.program.Program/ComputeProgram`(`use()`、`dispatch(w,h)`、indirect)、`ProgramBuilder`(begin/beginCompute/attribute/sampler/image DSL)、`ProgramUniforms/Samplers/Images`。
- `gl.buffer.ShaderStorageBuffer(Holder)`:`glBufferStorage` + `glBindBufferBase`,屏幕相对尺寸,支持 clear。
- `pipeline.programs.ExtendedShader extends com.mojang.blaze3d.opengl.GlProgram`:Iris gbuffers program 伪装成 vanilla GlProgram;持有 before/after-translucent 两个 GlFramebuffer、blend override、alpha test、自定义 uniform;`iris$setupState(...)` 由 `MixinGlCommandEncoder` 在 vanilla render pass 用到它时回调。`FallbackShader` 同理。
- `pipeline.programs.ShaderMap`/`ShaderKey`(含 `SODIUM_TERRAIN_SOLID/CUTOUT/TRANSLUCENT`、`SHADOW_SODIUM_TERRAIN_*`、`CLOUDS_SODIUM`、text/entity/particle 变体)/`IrisPipelines`(静态映射 **~107 个 vanilla RenderPipeline** → ShaderKey,另有 shadow map)。
- `shaderpack.*`:ShaderPack/ProgramSet/ProgramSource/ComputeSource、shaders.properties(`ShaderProperties`)、PackDirectives/PackRenderTargetDirectives/PackShadowDirectives、options/profiles、include、IdMap、自定义纹理、SSBO 声明(`getBufferObjects`)、维度覆盖。

## 4. 五个关键问题的直接答案

1. **自建 GL 资源还是走抽象?** 分裂式:FBO 100% 自建(GL id 来自 `iris$getGlId`);目标纹理经 `GpuDevice.createTexture` 分配;program 100% 自建 GL(GlStateManager 编译)再包成 `GlProgram` 子类;gbuffer 绘制走 vanilla RenderPass 但被 `MixinGlCommandEncoder` 在抽象背后重绑 Iris FBO/drawBuffers/blend;composite=GlStateManager 直绘;final/shadowcomp=抽象 RenderPass+`iris$setCustomPass` 重定向。
2. **shader 编译?** 自有 GL 路径:pack GLSL → jcpp → glsl-transformer(AST)→ `glShaderSource/glCompileShader/glLinkProgram`。**全 jar 零 SPIR-V/shaderc/glslang/blaze3d.vulkan 引用。** ShaderType 含 VERTEX/GEOMETRY/FRAGMENT/COMPUTE/TESS_CONTROL/TESS_EVAL。
3. **compute?** 是,仅裸 GL:`ComputeProgram` + `glDispatchCompute(Indirect)` + `glMemoryBarrier`;用于 pack `setup` 与各 stage compute 数组以及自身 `ColorSpaceComputeConverter`(#version 430、rgba8 image2D、8×8 local size;有 fragment fallback)。
4. **SSBO / image?** 是,均裸 GL(见 2a);pack 经 `bufferObject.N` 声明 SSBO(支持屏幕相对尺寸)。
5. **Sodium 挂接?** 20 个 mixin:`MixinShaderChunkRenderer` 包 `createShader(String, TerrainRenderPass)→RenderPipeline` 供 Iris 登记查表;`MixinDefaultChunkRenderer` 包 `begin(...)` 换 Iris program;`MixinRenderSectionManager` 换 `ChunkVertexType` 为 XHFP 扩展顶点格式(mid-texcoord/tangent/entity data,用 Blaze3D GpuFormat 属性构建);`MixinRenderSectionManagerShadow` 重驱动 shadow pass 的 section 渲染(独立 shadow UBO);`MixinUniformData` 挂 `UniformBufferManager`;chunk 构建 mixin 捕获 block/material 上下文。
6. **vanilla 管线替换?** `MixinShaderManager_Overrides` 注入 **`com.mojang.blaze3d.opengl.GlDevice.getOrCompilePipeline(RenderPipeline)` HEAD**,返回包着 `ExtendedShader` 的 GlRenderPipeline(经 IrisPipelines→ShaderKey→ShaderMap);`MixinLevelRenderer` 挂 `addMainPass` 及内部调用驱动 phase;`MixinGameRenderer` 包帧首尾;`MixinMinecraft_PipelineManagement` 维度切换时 `iris$resetPipeline`。
7. **后端 gating?** `IrisMixinPlugin.<clinit>` 读 options.txt `preferredGraphicsBackend`,含 "vulkan" 则停用全部非 VKOnly mixin 并显示"Iris cannot run when using Vulkan"切换提示。**无 Metal 感知**:字符串不含 "vulkan" 就全量应用 GL mixin → 在 Metal 后端上会因 `com.mojang.blaze3d.opengl.*` 缺失/转型失败而崩溃。另有 `iris.unsupported.pack.macos` 提示与 reverse-Z(`isZZeroToOne`/`glClipControl`)处理。

## 5. Sodium 版本结论

- 声明 `0.9.x`,但**二进制要求 0.9.1**:`MixinUniformData` shadow 的 `uniformData: GpuBufferSlice` / `uniformStorage: DynamicUniformStorage` 在 0.9.0 不存在(0.9.0 为 `MappableRingBuffer`,`getUniformBuffer()` 返回 `GpuBuffer`);`DefaultChunkRenderer.render(...)` 参数 `GpuBuffer`→`GpuBufferSlice`;`MultiDrawBatch.getIndexBufferSize→getMaxElementCount`。
- 0.9.0↔0.9.1 未变:`ShaderChunkRenderer.begin/end/createShader` 签名、`ChunkVertexType`、`RenderRegion.clearAllCachedBatches`、`RenderRegionManager.uploadResults`。
- **结论:上 Iris 1.11.2 必须把项目 Sodium 从 0.9.0 升到 0.9.1**,并回归 metallum 的 5 个 sodium mixin。

## 6. 程序集(Metal 后端最终要服务的面)

- `ProgramId`(39,带 fallback 链):shadow 系(shadow/solid/cutout/water/entities/lightning/block)、gbuffers 系(basic,line,textured,textured_lit,skybasic,skytextured,clouds,terrain,terrain_solid,terrain_cutout,damagedblock,block,block_translucent,beacon_beam,item,entities,entities_translucent,lightning,particles,particles_translucent,entities_glowing,armor_glint,spidereyes,hand,weather,water,hand_water)、DH 变体、final。
- `ProgramArrayId`:Setup、Begin、ShadowComposite(shadowcomp)、Prepare、Deferred、Composite(各带可选 per-pass compute 数组与 _a/_b 后缀)。
- Iris 内置辅助 shader:`centerDepth.vsh/fsh`(GLSL 150)、`colorSpace.vsh/csh`(compute+fragment fallback)。

## 7. 对 Metal 后端的实践判断

Iris 26.2 是"以 GL 为渲染器、以抽象层为分配器"的实现。Metal 化两条现实路线:
- **A. GL 子集垫片(shim)**:在 `IrisRenderSystem` + `GlStateManager` + `GlFramebuffer/GlShader` 缝合面实现 GL4.6 子集(本审计 §2 即精确契约),shader 编译链换成 GLSL→SPIR-V→MSL;
- **B. fork/重定向**:把 24 个裸 GL 类 + `opengl.*` mixin 组重定向到 Metal 等价物(对 Iris 打 mixin 或 fork)。
两条路线都必须处理 `IrisMixinPlugin` 的 "vulkan" 子串门(加 "metal" 感知,避免 GL mixin 带病上线)。
