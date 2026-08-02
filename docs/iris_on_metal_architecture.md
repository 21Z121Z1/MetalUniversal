# Iris-on-Metal 架构(as-built)

状态口径:本文只描述 **已实现并有测试证据** 的部分;规划中的内容见 `iris_on_metal_implementation_plan.md`,完成度判定见 `iris_metalfx_acceptance_report.md`。
分支:`iris-on-metal`(worktree `MetalUniversal-iris`)。

## 1. 分层总览

```
Iris 1.11.2+26.2(已安装,Metal 上休眠;语义层逐步替换其 GL 缝合面)   ← B2 进行中
────────────────────────────────────────────────────────
Iris 语义框架层(com.metallum.client.metal.render.IrisMetal*)        ← B1 已实现+测试
  IrisMetalPingPongTargets / IrisMetalRenderTargets / IrisMetalShadowTargets
────────────────────────────────────────────────────────
后端能力层(B0 已实现+测试)
  compute pipeline/pass、SSBO、storage image、GPU mipmap、compare sampler
  + 既有 MRT/copy/clear/PSO/MSL 链
────────────────────────────────────────────────────────
FFM 桥(MetalNativeBridge,optional downcall + 能力探测)
────────────────────────────────────────────────────────
Swift ABI(MetallumNative.swift,@_cdecl)→ Metal
```

## 2. B0 能力层

### 2.1 Compute

- **编译链**:GLSL compute(显式 `layout(binding=N)`)→ LWJGL shaderc(Vulkan 语义,与 Mojang GlslCompiler 同族)→ SPIRV-Cross MSL(`MSL_ENABLE_DECORATION_BINDING`,反射 `local_size`)→ 运行时 MSL 编译 → `MTLComputePipelineState`。实现:`MetalComputePipeline`。
- **绑定契约**(即 Iris `glBindBufferBase`/`glBindImageTexture` index 的映射面):SPIR-V binding N 原样保留——buffer 类资源(UBO+SSBO 共 namespace)→ MSL `[[buffer(N)]]`;image/texture → `[[texture(N)]]`;sampler → `[[sampler(N)]]`。调用方保证各 namespace 内 index 唯一。
- **Pass 模型**:`MetalCommandEncoder.createComputePass()` → `MetalComputePass`(bindBuffer/bindTexture/bindSampler、`dispatchGroups`(=glDispatchCompute 组数语义)、`dispatchThreadsCovering`(相对/向上取整)、`dispatchIndirect`(3×uint32 组数,布局同 GL indirect))。pass 拥有 encoder 直到 close;开 pass 前强制 flush 全部延迟 clear(见 §2.4)。
- **门禁**:`MetalNativeBridge.supportsComputeAbi()`;旧 dylib → 明确异常(fail-closed),不静默降级。

### 2.2 SSBO / storage image

- Metal buffer 无 usage 概念 → SSBO 原生可行,经 compute pass 显式 index 绑定(render 阶段的 SSBO 绑定属 B2,未实现)。
- storage texture:`MetalGpuTexture.USAGE_SHADER_WRITE`(mod 私有位 1<<5)→ MTL ShaderWrite;imageStore/imageLoad 均有 GPU 测试。

### 2.3 GPU mipmap 与 compare sampler

- `MetalCommandEncoder.generateMipmaps(texture)` → blit `generateMipmaps`(Iris `setupMipmapping`/DSA `glGenerateMipmap` 语义),mip 内容有下采样断言测试。
- `MetalGpuSampler` 新增 compare 构造(`metallum_create_sampler_v2`,`MTLCompareFunction`);MSL `sample_compare` 路径(`sampler2DShadow` 语义)有 GPU 测试(LessEqual:ref 0.25/0.75 vs depth 0.5 → 1/0)。

### 2.4 同步模型与 GL barrier 语义表

后端资源全部 `hazardTrackingMode=untracked`;正确性由**单一全局 MTLFence 链**保证:每个 render/blit/**compute** encoder 创建时 `waitForFence`,结束时 `updateFence`(`MetalCommandEncoder.endEncoder`)。因此「encoder 边界即 barrier」。

| OpenGL barrier bit(Iris 用法) | 本后端语义 |
|---|---|
| `GL_SHADER_STORAGE_BARRIER_BIT`(SSBO 写后读) | compute pass close → 下一 encoder waitForFence;同 pass 内多次 dispatch 之间 **无** 屏障(Metal 同 encoder dispatch 顺序执行且内存一致——Apple GPU compute pass 内 dispatch 串行语义;跨资源 hazard 由 untracked+fence 链覆盖跨 encoder 场景)。Iris 的 barrier 调用点均在 pass 间 → 映射为 pass 边界。 |
| `GL_SHADER_IMAGE_ACCESS_BARRIER_BIT`(image 写后采样) | 同上:image 写发生在 compute/render encoder 内,消费方必属后续 encoder → fence 链覆盖。已测:compute imageStore → blit readback;render attachment 写 → compute imageLoad。 |
| `GL_TEXTURE_FETCH_BARRIER_BIT` | 同上(encoder 边界)。 |
| `GL_FRAMEBUFFER_BARRIER_BIT`(attachment 写后读) | render pass 结束(endEncoder+updateFence)后消费。ping-pong 框架另有同 pass 读写守卫(§3.1)。 |
| `GL_BUFFER_UPDATE_BARRIER_BIT` | writeToBuffer 走 staging blit encoder → fence 链。 |
| `GL_COMMAND_BARRIER_BIT`(indirect args) | args 写入(blit)与 indirect dispatch(compute)分属 encoder → fence 链;已测。 |
| mipmap 生成前后 | generateMipmaps 独占 blit encoder → 两侧 fence。 |

限制(如实):同一 compute pass 内「dispatch A 写 → dispatch B 读」依赖 Metal 同-encoder 顺序保证,未单独测试跨-dispatch 原子性以外的极端情形;Iris 集成时若遇到 pass 内 barrier 调用,按语义拆分为两个 pass(有 `createComputePass` 低开销支持)。

### 2.5 MRT(既有 + 补全)

逐槽 format/blend/writeMask、null 槽、非连续逻辑 drawBuffers(0/2/5)、depth+MRT、resize 重建、clear/load/store 矩阵、三类 fail-closed——`metalMrtBackendIntegrationTest` 14/14。

### 2.6 健壮性修复(本分支)

- `writeToBuffer`/`writeToTexture` staging 路径拒绝 heap ByteBuffer(此前 SIGBUS 崩 JVM)。
- `MetalFxManager.reactiveTexture` 补 `USAGE_RENDER_ATTACHMENT`(clearColorTexture 的延迟 clear 需以其为 color attachment;缺失时 Metal 校验中止——CUTOUT 半成品遗留)。

## 3. B1 Iris 语义框架层

### 3.1 `IrisMetalPingPongTargets`

Iris `RenderTargets`+`BufferFlipper` 语义核心:每逻辑目标 main/alt 两纹理;未 flip 时读 main 写 alt,`flip(i)` 交换;`snapshot()/restore()`(framebuffer 缓存键/显式 flip 指令);`flippedAtLeastOnce`(单调,restore 不回退);同 pass 读写同目标 → `checkNoFeedbackLoop` 异常;`resize` 重建全部纹理并复位 flip 状态与历史。纹理 usage:RT|TB|COPY_SRC|COPY_DST(可附着/采样/复制/读回)。

### 3.2 `IrisMetalRenderTargets`

colortex 集 + **depthtex 三元组**:`mainDepth`(depthtex0)、`captureNoTranslucentsDepth()`(不透明后调用 → depthtex1)、`captureNoHandDepth()`(半透明后、手前调用 → depthtex2),GPU copy 走 fence 链。
**DRAWBUFFERS 映射**:`createWriteDescriptor(label, drawBuffers[], clears, withDepth, clearDepth, readTargets)` 产出**紧凑** RenderPassDescriptor——slot k = `writeTexture(drawBuffers[k])`,等价 GL `glDrawBuffers` 对 shader 顺序输出的路由(Iris patch 后输出即按序);读集合传入即做 feedback 校验。View 生命周期由返回的 `RenderPassDescriptorWithViews`(AutoCloseable)承载。

### 3.3 `IrisMetalShadowTargets`

shadowtex0/1(D32)+ flip-aware shadowcolor 集;方形分辨率由光影包 shadow 指令驱动(`resize(int)`),与屏幕无关;`captureNoTranslucentsDepth()`(shadowtex1 语义);与主目标完全隔离(独立纹理,fence 链保证 shadow 写 → 主 pass 采样有序)。

### 3.4 测试

`metalIrisTargetsIntegrationTest` 6/6:三 pass ping-pong 双侧内容断言、snapshot/restore、feedback 守卫、depth 三元组(0.75/0.25/0.5)、shadow(深度 0.1/0.3 + 颜色 + 主目标隔离 + resize 后重渲)、resize 复位。

## 4. B2 接入层(进行中)

- 依赖:Sodium `mc26.2-0.9.1-fabric`(Iris 二进制要求;metallum 既有 5 个 sodium mixin 编译+运行回归通过)、Iris `1.11.2+26.2-fabric`(dev classpath 作为 mod 加载)。
- **休眠垫片**(`com.metallum.mixin.iris.*`,门禁=Iris 在场 + default 后端;运行时再查 live backend=="Metal",Vulkan/GL 回退零影响):
  - `Iris.onRenderSystemInit` / `Iris.loadShaderpack` 取消 → currentPack 空 → PipelineManager 惰性构造并服务真实 `VanillaRenderingPipeline`;
  - `IrisRenderSystem.initRenderer`(GL capability 探测)、`GLDebug.reloadDebugState`(KHR debug)、`IrisSamplers.initRenderer`(glGenSamplers)取消;
  - `VanillaRenderingPipeline.beginLevelRendering`(其唯一 GL 面:clip-control/useProgram)取消——reverse-Z 由 Metal 后端自有约定承担。
- 逐步点亮路径(未实现,见 plan §2.2 B2):`IrisRenderSystem`/`GlStateManager` 缝合面 → B1 框架;`MetalDevice` 管线覆盖钩子等价 `GlDevice.getOrCompilePipeline` 机制;pack GLSL → GlslCompiler→Spvc 链。

## 5. 所有权/线程/生命周期约定

- 全部对象 render-thread only(随后端惯例)。
- `IrisMetal*Targets` 拥有其纹理;close/resize 即释放重建;descriptor 的 views 由调用方在提交后 close。
- `MetalComputePipeline` 持有 retained PSO,close 经 destruction queue 延迟释放(在飞 command buffer 安全)。
- compute pass 独占 encoder 至 close;期间禁止其它编码(违规 = IllegalStateException)。
- 能力探测(`supportsComputeAbi` 等)= 旧 dylib fail-closed 契约。
