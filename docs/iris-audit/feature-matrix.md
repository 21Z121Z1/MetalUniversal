# Iris 1.11.2+26.2 → Metal 后端 功能矩阵

依据:`iris-1.11.2-mc26.2-surface.md`(真实 jar 审计)× `backend-coverage.md`(工作树核验)。
「状态」指当前 Metal 后端对该功能所需底层原语的支持度,不是集成完成度。
集成形态结论:**形态 B(GL 渲染器,需语义层)**;策略与阶段边界见 `iris_on_metal_implementation_plan.md` §2.2(修订版)。

| # | Iris 功能 | 实际调用(GL/抽象) | Metal 后端现状 | 需改 Java 层 | 需改 bridge 层 | 需改 Swift/Metal 层 | 验证方式 |
|---|---|---|---|---|---|---|---|
| 1 | `GlFramebuffer`(自建 FBO,addColorAttachment(index,glId)/addDepthAttachment/drawBuffers(int[])/readBuffer/bind*) | GlStateManager `glGenFramebuffers/_glFramebufferTexture2D` + DSA `glCreateFramebuffers/glNamedFramebuffer*` | 无 FBO 概念;等价物=RenderPassDescriptor 每 pass 组装(MRT 已通;null 槽=非连续 drawBuffers) | 新 `IrisMetalFramebuffer`:持久化 attachment 集合+drawBuffers 映射→按需产出 RenderPassDescriptor;状态校验(尺寸/格式一致) | 无(复用 v2 encoder ABI) | 无 | L2:非连续 drawBuffers(0,2,5)、depth+MRT、resize 重建内容级测试 |
| 2 | `RenderTargets`(colortex0..15 main/alt GL id + depth 副本 + framebuffer 工厂 + resizeIfNeeded) | 纹理经 `GpuDevice.createTexture` 分配后 `iris$getGlId` 取 id;副本经 DepthCopyStrategy | 纹理分配/复制原语齐;无 main/alt 管理 | 新 `IrisMetalRenderTargets`:main/alt MetalGpuTexture 对、格式表(InternalTextureFormat→GpuFormat 映射)、resize 重建、销毁 | 无 | 无 | L2:分配/重建/销毁;格式映射表单测 |
| 3 | `BufferFlipper`(flip/isFlipped/snapshot) | 纯 CPU 状态 | 无 | 新 `IrisMetalBufferFlipper`(语义等同)+ flip 快照→framebuffer 重建钩子 | 无 | 无 | 单测:flip/快照/复位;L2:三连 pass 内容级 ping-pong |
| 4 | `CompositeRenderer`(逐 pass:绑 FBO+Program,GlStateManager `_drawElements` 全屏 quad;pass 间 mipmap;ComputeOnlyPass) | GL 直绘 + `glGenerateMipmap`(DSA)+ compute | draw 原语齐(经 RenderPass);**generateMipmaps 缺**;compute 缺 | 新 `IrisMetalCompositeRenderer` 骨架:pass 列表→RenderPass 序列;mipmap 钩子;compute 钩子 | +generateMipmaps;+compute(见 #8) | +blit `generateMipmaps`;+compute encoder | L2:多 pass composite 序列内容级;mipmap 各层 readback |
| 5 | `FinalPassRenderer`(写主目标;`glCopyTexSubImage2D` SwapPass 维护历史) | GL copy | `copyTextureToTexture` 已有 | 复用 encoder copy;SwapPass 语义并入 targets 框架 | 无 | 无 | L2:final 后历史纹理内容断言 |
| 6 | `ShadowRenderTargets`/`ShadowRenderer`(shadowtex0/1、shadowcolor、重驱动地形/实体、逐 buffer MipmapPass) | 同 #2 + 场景重渲染 | depth 渲染原语齐;**compare sampler 缺**;mipmap 缺 | 新 `IrisMetalShadowTargets`;shadow pass 状态隔离(独立 RenderPass+viewport) | +compare sampler 创建 ABI | +`MTLSamplerDescriptor.compareFunction` | L2:正交阴影渲染+compare 采样判定;shadow resize |
| 7 | `ShadowCompositeRenderer`(shadowcomp,抽象 RenderPass+iris$setCustomPass) | 抽象层+重定向 | RenderPass 原语齐 | 并入 #4 骨架 | 无 | 无 | L2 同 #4 |
| 8 | `ComputeProgram`(dispatch(w,h)、indirect、`glMemoryBarrier`) | GL43/45 compute | **三层全缺** | 新 `mtl/MTLComputeCommandEncoder`、`mtl/MTLComputePipelineState`、`MetalComputePass`;SPIR-V compute→MSL 编译路径 | +makeComputeCommandEncoder/makeComputePipelineState/setBuffer/setTexture/dispatchThreadgroups(+indirect)/end | +对应 @_cdecl;并入全局 MTLFence 链 | L2:absolute/relative/indirect dispatch;compute↔render 顺序内容级 |
| 9 | `Program`+`ShaderCreator`(pack GLSL→jcpp→glsl-transformer→glCompileShader/glLinkProgram) | GlStateManager 编译链 | 无 GL 编译;已有 GLSL→SPIR-V(GlslCompiler)→MSL(Spvc)链 | 新 `IrisMetalProgram`:接管 transformer 输出的 GLSL,走 GlslCompiler→Spvc→PSO;uniform location 语义映射(glGetUniformLocation→UBO 成员/push constant 表) | 无(复用 PSO ABI) | 无 | L2:代表性 Iris 风格 GLSL(MRT 输出/uniform 集/shadow sampler)编译+GPU 执行断言;编译错误回传带 program 名 |
| 10 | `ProgramSamplers`(单元绑定、`glBindSamplers` 多绑定、12 静态 GlSampler 预设) | GL sampler 对象 | sampler 原语有;**compare 缺**;Mirror/Border 地址模式未暴露 | 扩展 `MetalGpuSampler`(compare、可选地址模式);Iris 采样单元→argument 槽映射表 | +sampler ABI 参数 | +descriptor 参数 | L2:compare 采样;预设矩阵单测 |
| 11 | `ProgramImages`/`GlImage`(glBindImageTexture、glClearTexImage) | GL42 image | usage 位 Swift 已直通;**绑定/清除路径缺** | storage texture 绑定种类(ResourceKind+bindDrawState);clear image 走已有 clear/blit | +setTexture(写访问)已有 setTexture 可复用;确认 usage 传递 | 校验 shaderWrite/atomic usage;必要时 PixelFormatView | L2:imageStore→sample、imageLoad 断言 |
| 12 | `ShaderStorageBufferHolder`(glBufferStorage、glBindBufferBase(index)、屏幕相对 resize、clear) | GL43/44 SSBO | Metal buffer 原生可作 SSBO;**Java usage 位/绑定种类/反射缺** | `MetalGpuBuffer` +STORAGE usage;ResourceKind.STORAGE_BUFFER;Spvc 反射 storageBuffers();binding index 稳定映射 | setBuffer 复用;确认 fragment/vertex/compute 可见性 | 无新增(setBuffer 通用) | L2:SSBO 写后读(compute 写→fragment 读;fragment 写→readback);resize/clear |
| 13 | `IrisRenderSystem`(~200 静态 GL 入口 + DSA 三策略 + 能力探测) | 裸 LWJGL GL | 不可直译 | **语义层核心**:逐入口映射到上述框架对象;能力探测返回 Metal 真值(DSA=true 等价、SSBO/image/compute=true 当实现后) | 按上述各行 | 按上述各行 | 逐类别 L2 测试(即 #1-#12 的并集) |
| 14 | `ExtendedShader extends GlProgram`(vanilla 管线替换载体;iris$setupState;before/after-translucent FBO) | GlDevice.getOrCompilePipeline mixin + GlProgram 子类 | GlDevice/GlProgram 在 Metal 上不存在/不加载 → **替换机制整体失效** | 等价机制:在 `MetalDevice.precompilePipeline/getOrCompilePipeline` 增加「管线覆盖钩子」(RenderPipeline→Iris program 查表,即 IrisPipelines 语义) | 无 | 无 | L2:覆盖钩子单测;L3:世界几何走覆盖 program |
| 15 | Sodium override(0.9.1:MixinShaderChunkRenderer/MixinDefaultChunkRenderer/XHFP 顶点格式/shadow 重驱动/MixinUniformData) | Sodium 类 mixin(经 Blaze3D 抽象) | Sodium 0.9.0 在场;**Iris 二进制要求 0.9.1**;metallum 5 个 sodium mixin 需随升复验(DefaultChunkRenderer.render 参数 GpuBuffer→GpuBufferSlice) | 升级依赖;复验 metallum sodium mixin;与 Iris 的 mixin 共存顺序(priority) | 无 | 无 | L1 编译;L2 回归;L3 地形走 Iris terrain program |
| 16 | shader pack reload(PipelineManager 重建全部资源) | 全链 | clearPipelineCache 等原语在 | 框架对象全部实现 destroy/rebuild;reload 入口驱动 | 无 | 无 | L3:reload 前后 capture 对比,无崩溃无旧资源复用 |
| 17 | render target resize(resizeIfNeeded;屏幕相对 SSBO) | 全链 | vanilla resize 原语在 | targets/flipper/framebuffer/SSBO 全部尺寸感知重建 | 无 | 无 | L2 resize 重建;L3 resize 场景 capture |
| 18 | reverse-Z(`ARBClipControl.glClipControl` + UndoReverseZ mixin ×5) | GL45 clip control | Metal NDC z∈[0,1] 天然;MC 26.2 reverse-Z 已由后端处理 | 语义层需保证 Iris 期望的深度约定一致(取 DeviceInfo.isZZeroToOne 真值) | 无 | 无 | L2:深度值方向断言(近/远平面写入值) |
| 19 | `IrisMixinPlugin` "vulkan" 门(无 Metal 感知→GL mixin 带病上线) | mixin 插件 | — | 接入初期:兼容垫片让 Iris 在 Metal 上按「不支持后端」安全停用(等价 vulkan 分支),随语义层推进逐步放行 | 无 | 无 | L3:Iris 共存启动不崩溃(过渡态);放行后逐项点亮 |
| 20 | `DepthCopyStrategy`(Gl20/Gl30Blit/Gl43CopyImage) | GL copy 三选一 | `copyTextureToTexture` 深度路径已有(MetalFX 每帧在用) | 映射到 encoder copy;确认 depth 格式 blit 合法性 | 无 | 无 | L2:depthtex0/1/2 复制语义内容级 |

## 集成缺口速览(按层)

- **Java 新增**:`com.metallum.client.iris.*`(Framebuffer/RenderTargets/BufferFlipper/CompositeRenderer 骨架/ShadowTargets/Program/ComputePass/SSBO holder)、`mtl` compute 二类、ResourceKind 扩展、sampler compare、管线覆盖钩子。
- **bridge 新增**:compute encoder/pipeline/dispatch(含 indirect)、generateMipmaps、sampler compare 参数。
- **Swift 新增**:compute @_cdecl 组(并入 MTLFence 链)、blit generateMipmaps、sampler descriptor compare。
- **依赖**:Sodium 0.9.0→0.9.1(+metallum mixin 回归)、`maven.modrinth:iris:1.11.2+26.2-fabric`。
- **不需要动**:MRT 主链、copies、clear 体系、PSO/MSL 编译链主体、present 链、MetalFX(阶段二)。
