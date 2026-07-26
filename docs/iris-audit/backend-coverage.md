# Blaze3D 26.2 抽象面 × metallum 实现覆盖(工作树核验)

来源:本会话后台 agent 对 `minecraft-merged-deobf-26.2.jar`(loom named jar,classfile v69)与工作树源码的逐条核验。行号以 `MetalUniversal-iris` 基线 `ea2dfd4` 为准。

## 1. Blaze3D 26.2 API 结构

前端具体类(`GpuDevice/CommandEncoder/RenderPass/GpuSurface`)做验证并委托给后端接口:`GpuDeviceBackend/CommandEncoderBackend/RenderPassBackend/GpuSurfaceBackend`;入口 `GpuBackend{getName, setWindowHints, handleWindowCreationErrors, createDevice}`。

### GpuDeviceBackend(全部抽象)
createSurface(long) / createCommandEncoder() / createSampler(AddressMode×2, FilterMode×2, int maxAniso, OptionalDouble maxLod) / createTexture(Supplier<String>|String, int usage, GpuFormat, w, h, depthOrLayers, mips) / createTextureView(GpuTexture[, baseMip, mips]) / createBuffer(Supplier<String>, usage, long|ByteBuffer) / getLastDebugMessages / isDebuggingEnabled / precompilePipeline(RenderPipeline, ShaderSource) / clearPipelineCache / close / createTimestampQueryPool(int) / getTimestampNow / getDeviceInfo

### CommandEncoderBackend(全部抽象)
submit / transientMemory / createRenderPass(RenderPassDescriptor) / submitRenderPass / clearColorTexture / clearColorAndDepthTextures(×2,含区域) / clearDepthTexture / writeToBuffer(GpuBufferSlice, ByteBuffer) / copyToBuffer(slice,slice) / writeToTexture(GpuTexture, ByteBuffer, mip, layer, x, y, w, h) / copyBufferToTexture(...) / copyTextureToBuffer(...×2, mip[,region], async callback) / copyTextureToTexture(src,dst,mip,dstXY,srcXY,wh) / createFence / writeTimestamp

### RenderPassBackend(全部抽象)
push/popDebugGroup / setPipeline(RenderPipeline) / bindTexture(String, GpuTextureView, GpuSampler) / setUniform(String, GpuBuffer|GpuBufferSlice) / enable/disableScissor / setVertexBuffer(slot, GpuBufferSlice) / setIndexBuffer(GpuBuffer, IndexType) / drawIndexed(indexCount, instanceCount, firstIndex, vertexOffset, firstInstance) / multiDrawIndexed(×2) / drawIndexedIndirect(GpuBufferSlice, drawCount) / drawMultipleIndexed(Collection<Draw>, ...) / draw(vertexCount, instanceCount, firstVertex, firstInstance) / multiDraw(×2) / drawIndirect(GpuBufferSlice, drawCount) / writeTimestamp

### 支撑类型要点
- `RenderPassDescriptor`:colorAttachments 列表(`withColorAttachment(view[,clear])`/`withUnusedColorAttachment()`)、depthAttachment(`withDepthAttachment`)、renderArea —— **MRT 一级公民**。
- `GpuTexture` usage:COPY_DST=1, COPY_SRC=2, TEXTURE_BINDING=4, RENDER_ATTACHMENT=8, CUBEMAP_COMPATIBLE=16 —— **无 storage/image 位**。
- `GpuBuffer` usage:MAP_READ/WRITE, HINT_CLIENT_STORAGE, COPY_DST/SRC, VERTEX, INDEX, UNIFORM, UNIFORM_TEXEL_BUFFER=256, INDIRECT_PARAMETERS=512 —— **无 STORAGE(SSBO)位**。
- `GpuSampler`:AddressMode={REPEAT, CLAMP_TO_EDGE},FilterMode={NEAREST, LINEAR} —— **无 compare、无 border、无独立 mip filter**。
- `RenderPipeline`:vertex+fragment 两 stage;`ColorTargetState[]`(MAX=8,record(blend?, format, writeMask));`DepthStencilState`(compare、writeDepth、depthBias —— **无 stencil op**);`getBindGroupLayouts`。
- `GpuFormat`:R/RG/RGB/RGBA × 8/16/32 全家族(unorm/snorm/uint/sint/float)+ RGB10A2_UNORM/UINT + RG11B10_FLOAT + D32_FLOAT/D32F_S8/D24_S8/D16/S8。
- `TransientMemory`:每帧 ring 分配(cpu/staging/gpu/mapped, upload*)。

### vanilla 26.2 能力裁定表
| 能力 | Blaze3D 26.2 |
|---|---|
| Compute pipeline / pass | **无**(全 jar 无 *Compute* 类) |
| SSBO | **无** |
| storage texture / image load-store | **无** |
| memory barrier | **无** |
| GPU mipmap 生成 | **无**(CPU `MipmapGenerator` 逐层 `writeToTexture`) |
| copies(tex↔tex/buf↔tex/tex→buf/buf↔buf) | 有,齐全 |
| depth attachment/clear/state | 有;**stencil op 状态无** |
| sampler compare | **无** |
| fence / timestamp query | 有(接口) |
| indirect / multiDraw | 有(Sodium 取向) |

**Iris 后果**:compute/SSBO/image/barrier/GPU mipmap/compare sampler 必须作为 **mod 私有扩展**加在 FFM 桥两侧——vanilla 抽象没有挂点。

## 2. metallum Java 实现覆盖

- `MetalDevice`(MetalDevice.java):**全实现**;弱项:`getLastDebugMessages`=空、`getTimestampNow`=System.nanoTime(CPU)。DeviceInfo :299-323:`DeviceLimits(maxAniso=1, uboAlign=256, maxTex=16384, maxAlloc=native, maxMultiDrawInterleaved=0, maxColorAttachments=8)`;`DeviceFeatures(shaderDrawParameters=false, multiDrawDirectInterleaved=false, multiDrawDirectSeparate=true, multiDrawIndirect=true, drawIndirect=true, nonZeroFirstInstance=false, persistentMapping=true)`。
- `MetalCommandEncoder`:全实现;要点:submit 三缓冲(`MAX_SUBMITS_IN_FLIGHT=3`);clear **延迟化**(pendingColorClears/pendingDepthClears → load-action 或独立 clear encoder,`flushPendingClear` :987-1014);layered attachment 拒绝(:275/:310 UnsupportedOperationException);`copyTextureToBuffer` blit slice 硬编码 0(:849,数组层 readback 不支持);fence=`MetalFence`(提交序号+信号量,非 MTLSharedEvent);writeTimestamp=CPU。
- **hazard 模型**:全部资源 `hazardTrackingMode=untracked`;正确性靠单一全局 `MTLFence`:每个新 render/blit encoder `waitForFence`,结束 `updateFence`(:79-99, :189-204)。这就是当前事实上的 barrier 机制,compute encoder 必须并入该 fence 链。
- `MetalRenderPass`:除 `multiDraw(IntBuffer,int,int,int)` :308-311 与 `multiDraw(IntBuffer,IntBuffer,int)` :313-316 抛 UnsupportedOperationException 外全实现(注意:宣告 multiDrawDirectSeparate=true 与 :313 抛异常存在旗标/实现不一致);triangle-fan 在 multiDrawIndexed(Pointer)/drawIndexedIndirect/drawIndirect 抛异常,其余场景经 transient index buffer 模拟;绑定模型 `bindDrawState` :505-565 仅 `UNIFORM_BUFFER|SAMPLED_IMAGE|TEXEL_BUFFER` 三种 ResourceKind(MetalCompiledRenderPipeline.java:28-32)——**无 SSBO/storage image 种类**。
- `MetalGpuTexture`:创建 → `metallum_create_texture_2d`(2D/2DArray/Cube/CubeArray,Private storage);**私有扩展位 `USAGE_SHADER_WRITE = 1<<5`** :18(仅 MetalFxManager.java:1430 使用);usage 翻译 :136-156(RENDER_ATTACHMENT 对 color 格式附带 ShaderWrite)。与 vanilla 最高位 16 相邻,**Mojang 增加第 6 位即冲突**——需要迁移到更高位并留注释。
- `MetalGpuBuffer`:池化;Shared storage 条件(MAP_*|HINT_CLIENT_STORAGE|dynamic);`map` 为持久指针视图;untracked。Metal buffer 无 usage 概念,**SSBO 原生可行**,缺的只是 Java usage 位 + 绑定路径。
- `MetalGpuSampler`:**无 compare function**(Swift descriptor 不设 compareFunction :3697-3705);mip filter 启发式(`maxLod>0.25→Linear`);AddressMode 仅 REPEAT/CLAMP(MTL 枚举已declare Mirror/ClampToZero/Border 备用)。
- `MetalCompiledRenderPipeline`:每 pipeline 预建 PSO × 固定 depth/stencil 格式表 :162-171(Invalid/D16/D32F/D24S8/D32FS8/S8);binding index 上限 64;注意 D24S8 在 Apple silicon 无原生支持(预编译静默产不出缓存)。
- `MetalCrossShaderCompiler`:SPIR-V→MSL(SPVC MSL 4.0,FLIP_VERTEX_Y);反射仅 `uniformBuffers()+samplers()`(sampler 限 Dim2D/DimCube;texel buffer DimBuffer);显式 `layout(location=N) out` 解析 + fragment 输出签名校验;push constants 重映射到尾部 buffer binding;**只支持 vertex+fragment,无 compute**。

## 3. Swift ABI(92 个 @_cdecl)

分类:device/layer(10)、queue/commandBuffer/semaphore(11)、MTLFence(5,intra-queue)、blit copy(6,**无 generateMipmaps**)、资源创建(buffer/texture2d[裸 MTLTextureUsage 位直通,**shaderWrite/shaderAtomic 已可传**]/textureView/bufferTextureView/sampler[**无 compare**]/depthStencilState)、render encoder v1+v2(v2=8 槽 indexed MRT,per-slot clear/load/store,depth+auto-stencil)、encoder 状态与 draw(15)、present/drawable(2)、PSO 构建(12)、MetalFX 内部(10,固定功能)。
**compute 现状**:MTLComputePipelineState/makeComputeCommandEncoder 仅在 MetalFX 内部 hardcoded kernels 使用;**C ABI 无通用 compute 导出**;无 MTLEvent、无 memoryBarrier、无 generateMipmaps。

## 4. FFM 桥(MetalNativeBridge.java)

静态绑定同上 92 符号;`downcall`=critical(false),阻塞调用用 downcallWithoutCritical(semaphore_wait、waitUntilCompleted、createShaderFunction、fan draw、present);`optionalDowncall`(可空)用于 MetalFX v2 与 **makeRenderCommandEncoder_v2** 等新 ABI;库加载:macOS 从 jar 抽 dylib → `SymbolLookup.libraryLookup`。**compute/mipmap/barrier 条目:无。**

## 5. 纹理格式与 usage

`MTLPixelFormat.from(GpuFormat)` :75-122:R/RG/RGBA × 8/16/32 全家(含全部 uint/sint)、RGB10A2_UNORM、RG11B10F、五种 depth/stencil 全映射;**未映射即 IllegalStateException**:全部 RGB 三通道格式与 RGB10A2_UINT。`MTLTextureUsage`:Unknown/ShaderRead/ShaderWrite/RenderTarget/PixelFormatView/ShaderAtomic 已声明;ShaderAtomic/PixelFormatView 目前无人使用。

## 6. 主 framebuffer / resize

- vanilla `RenderTarget/MainTarget/TextureTarget` 已后端无关(自持 GpuTexture,resize 直接走 `GpuDevice.createTexture`)——**后端看不到 RenderTarget,resize 由 vanilla 完成**;metallum 无 RenderTarget mixin。
- 呈现链:`MetalSurface.configure` → `metallum_configure_layer`(bgra8Unorm、drawableSize、vsync);`acquireNextTexture` 为 no-op(drawable 延迟到 present encode);`blitFromTexture` → `presentTextureToDrawable`(可路由 FG,否则全屏三角采样 present)。

## 7. 既有 E2E harness(扩展底座)

`MetalMrtBackendIntegrationTest`(§见主审计 4.2):无窗口引导(直接 `metallum_create_system_default_device` + 包内可见 `MetalDevice` 构造,layer/view 传 NULL;GLSL 由 lambda ShaderSource 提供,经 Mojang GlslCompiler);gradle 任务 `metalMrtBackendIntegrationTest` dependsOn buildMacNative,`--enable-native-access=ALL-UNNAMED`、`MTL_DEBUG_LAYER=1`、`MTL_SHADER_VALIDATION=1`,已接 `check`。readback 模式:`createBuffer(MAP_READ|COPY_DST)` → `copyTextureToBuffer` → `submit` → `waitForSubmittedGpuWork` → 读 `currentStorage()`。

## 8. mixin 面

配置 client-only + `MetallumMixinConfigPlugin` 门禁(mac 才应用;sodium.* 需 sodium 在场;**MetalFX mixin 仅当 options.txt `preferredGraphicsBackend` 为 default/缺省**;PreferredGraphicsApiMixin 恒应用)。后端选择:`PreferredGraphicsApiMixin.getBackendsToTry` HEAD 返回 `[Metal, Vulkan, GL]`。Sodium:`DrawBackendMixin`(Metal→`VK_INDIRECT`)、`DrawContextMixin`(换 `MetalDrawContext`)、cosmetic 改名、`ShaderChunkRendererMetalFxMixin`(begin/compileProgram/end)、`DefaultChunkRendererMetalFxMixin`(render)。其余 MetalFX mixin 目标:GameRenderer(init/resize/render/renderLevel/blur/setLevel/resetData/close)、Minecraft(renderFrame HEAD/RETURN)、LevelRenderer(addAlwaysOnTopPass)、GuiRenderer(draw)、GameRenderState(useShaderTransparency)、EntityRenderDispatcher/ModelFeature*/RenderTypeFeatureGroup/PreparedRenderType/StagedVertexBuffer(motion 捕获)。

## 9. Iris-ready 缺口总表(证据齐)

| Iris 需求 | 状态 | 依据 |
|---|---|---|
| MRT ≤8 | 已完成且 E2E 有测 | encoder :227-358;Swift :3779;测试套件 |
| ping-pong | 原语可行(copy/passes/fence 链),缺框架与测试 | encoder :862-892 |
| depth 纹理采样 | 可建可绑 | usage 翻译 :138-149 |
| shadow targets | 渲染侧可行;**compare 采样缺失** | §5/§6 |
| compute | **三层全缺**(API/Java/bridge/Swift ABI) | §1/§3/§4 |
| image load/store | API 无;Swift usage 位已直通;私有 USAGE_SHADER_WRITE 在;缺绑定/dispatch 路径 | §3/§5 |
| SSBO | API 无;绑定模型无 storage 种类;SPIRV-Cross 反射忽略 storage buffer | 编译器 :126-160 |
| memory barrier | API 无;现模型=untracked+全局 MTLFence 链 | encoder :79-99 |
| GPU mipmap 生成 | 无(vanilla=CPU);无 generateMipmaps 导出 | §1/§3 |
| 纹理 copies | 齐全 | §2/§3 |
| compare+mip sampler | mip 有(启发);compare 无 | §6 |
| 整数/浮点格式 | uint/sint 全映射;RGB 三通道与 RGB10A2_UINT 抛异常 | §5 |
