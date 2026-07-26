# Shader 与 Pipeline 编译链

> **2026-07-26 status:** 本文是实现前编译链快照。当前 indexed V2 ABI、1/2/3/8-slot pipeline 和 Java-to-GPU integration test 已完成；以最终验收报告中的 MRT receipt 为准。

## 当前实际编译链

```text
Minecraft/Sodium GLSL source
  -> Minecraft GlslCompiler / define injection
  -> shaderc GLSL -> SPIR-V
  -> IntermediaryShaderModule reflection
  -> MetalDevice shader cache
  -> MetalCrossShaderCompiler / SPIRV-Cross
  -> MSL 4.0 with binding remap
  -> regex entry-point extraction
  -> metallum_create_shader_function / MTLDevice.makeLibrary(source:)
  -> MetalCompiledRenderPipeline
  -> MTLRenderPipelineDescriptor
  -> MTLRenderPipelineState
```

### 1. GLSL 到 SPIR-V

Minecraft 的 shader compiler 位于 `/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/vulkan/glsl/GlslCompiler.java:22-63`。Metal backend 先在 `MetalDevice.getOrCompileShader` 以 `(Identifier, ShaderType, ShaderDefines)` 组成 cache key，并去注释、注入 defines，再调用 Minecraft compiler（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalDevice.java:261-287`）。因此 shader key 不只是文件名，还包含 stage 和 defines。

shaderc 参数和 auto binding/locations 由 Minecraft `GlslCompiler` 完成；当前工作树没有另一个 Metal 专用 GLSL parser。**置信度：confirmed by source path；限制：没有把每个运行时 define 集合从 capture 枚举出来。**

### 2. SPIR-V reflection/rebind

`IntermediaryShaderModule` 负责保存 SPIR-V 与 Vulkan 风格资源布局/reflection（`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/vulkan/IntermediaryShaderModule.java:26-118`）。`MetalCrossShaderCompiler.compile` 对 vertex/fragment 两个 module 做 reflection、bind group/resource mapping，再调用 SPIRV-Cross（`MetalCrossShaderCompiler.java:65-220`）。当前绑定信息包括 uniform buffer、sampled image、sampler、texel buffer、vertex input/output；具体每个 shader 的 binding 由 module reflection 产生，而不是硬编码一个全局表。

### Fragment output 的实际边界

映射源码的 `IntermediaryShaderModule.createFromSpirv` 会同时反射 vertex/fragment module 的 output variables，并把每个 output 的 SPIR-V `Location` 按列表顺序写成 `0..N-1`（`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule.java:26-28,79-113`）。因此“编译链绝对只能产生一个 fragment output”不是当前源码事实。

但 `MetalCrossShaderCompiler.compile` 只把 **vertex** outputs 提取出来用于 fragment input rebind；fragment outputs 没有被 Java 层删掉、重命名或映射到 `ColorTargetState`，而是直接随 SPIR-V 送入 SPIRV-Cross（`MetalCrossShaderCompiler.java:65-88`；`spirvToMsl` 的 MSL compile 在 `:305-406`）。若未来 GLSL/第三方 shader 本身声明多个 fragment outputs，SPIRV-Cross/Metal PSO 可能沿 output location 产生多个颜色结果，但当前代码没有对 fragment output count、location、format 和 `RenderPipeline.getColorTargetStates()` 做显式一致性验证。**confidence：reflection preserves/renumbers outputs=confirmed；multi-output MSL/PSO runtime acceptance=strong static inference；当前所有运行时 shader 的 output count=unknown。**

### 3. SPIR-V 到 MSL

SPIRV-Cross 选项在 `MetalCrossShaderCompiler.java:344-405`：MSL backend、macOS platform、MSL 4.0、decoration binding、native texture buffer、flip vertex Y；resource binding decoration 和 push-constant binding 会在 compile 前设置。MSL vertex/fragment entry 通过正则提取（`MetalCrossShaderCompiler.java:36-38`），不是 AST 级入口查询。

native bridge 将最终 MSL 字符串和 entry name 交给 `MTLDevice.makeLibrary(source:)` 并从 library `makeFunction(name:)` 取函数（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/native/MetallumNative.swift:3158-3180`）。因此当前链中没有落盘 `.metallib` 或独立 MSL cache 的证据；Java 侧 cache key 是完整 MSL+entry（`MetalDevice.java:283-293`）。

## Render pipeline descriptor

`MetalCompiledRenderPipeline` 从 Minecraft `RenderPipeline` 读取 vertex/fragment function、vertex descriptor、depth/stencil、blend 与 color target 状态，再创建 Java wrapper descriptor（`/Users/retriedstormtrooper/Documents/Projects/Active/MinecraftMetal/MetalUniversal-master/src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java:23,114-125,187-216`）。native descriptor bridge 设置 functions、vertex descriptor、indexed attachment format 和 blend state（`MetallumNative.swift:3182-3197,3214-3275,3305-3317`）。

当前实现的 attachment 事实必须分成三层：

- **Minecraft contract 支持多附件：** `RenderPipeline.Builder` 的 `colorTargetStates` 是长度 8 的数组，支持按 index 写入或保留 unused slot；`RenderPass.setPipeline` 要求 pipeline target 数量和 pass color attachment 数量相等（`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/pipeline/RenderPipeline.java:147-159,241-255,357-381`；`/tmp/minecraftmetal-mc26-sources/com/mojang/blaze3d/systems/RenderPass.java:82-98`）。
- **Java Metal backend 保留多附件：** `MetalCommandEncoder.renderCommandEncoder` 接收 `MetalGpuTextureView[]`，逐槽建立 native handle 数组并调用 `makeRenderCommandEncoderV2`；`createRenderPass` 遍历 `descriptor.colorAttachments()` 并保留 null slot（`MetalCommandEncoder.java:134-180,205-227`；`MetalRenderPass.java:33-80,382-409`）。
- **Java PSO 和 native bridge 也按 index 设置：** `MetalCompiledRenderPipeline` 读取 `getColorTargetStates()`，逐槽设置 pixel format、blend 和 write mask；native v2 render pass 和 descriptor setter 均允许最多 8 个 slot（`MetalCompiledRenderPipeline.java:114-125,187-216`；`MetallumNative.swift:2484-2580,3214-3275`）。
- **当前已枚举 pipeline 仍是单附件：** Minecraft 26.2 `RenderPipelines` 的现有声明均调用无 index 的 `withColorTargetState(...)`，未发现 `withColorTargetState(1..7, ...)`；Sodium 0.9 `ShaderChunkRenderer.createShader` 也只声明一个 target（`/tmp/minecraftmetal-mc26-sources/net/minecraft/client/renderer/RenderPipelines.java:88-746` 的 43 个调用位置；`/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer.java:51-66`）。这不是完整 runtime pipeline 日志枚举。

**结论：当前工作树已确认“backend 源码和当前 bundled dylib 都暴露 indexed MRT symbols”，但未确认“当前运行 pipeline 有 motion MRT”；更准确地说，当前内置 Minecraft/Sodium pipeline 的 motion MRT contract 缺失。** 不能再把通用 Metal backend 写成单 attachment。增加 motion MRT 仍需同时提供第二个 FrameGraph target、对应 `RenderPipeline` color target、fragment shader output/location、pass attachment 数量/格式和 Temporal 输入绑定；现有 indexed backend 只减少了 Java/native binding 的修改面，不能证明 shader 或运行时资源已经接通。Java bridge 对缺少 v2/indexed symbols 的旧 dylib 会对单附件走 legacy fallback、对多附件直接抛错（`MetalNativeBridge.java:1341-1371,1820-1842,1861-1890`）。**confidence：source/bundled symbol capacity=confirmed；current built-in motion MRT absence=confirmed for inspected source declarations；active loaded dylib symbol set=unknown；complete runtime key/output enumeration=unknown。**

## Shader reload/cache

`MetalDevice` 维护 `compiledPipelines` 和 `shaderCache`，`close` 时逐项关闭并清空（`MetalDevice.java:42-43,155-168`）。当前首轮没有证明 Minecraft resource reload 会调用同一 close/recompile 路径；因此 shader reload 时 old native library/function/pipeline 与 in-flight command buffer 的关系仍是 lifecycle 未知。不要把 `compiledPipelines.clear()` 当成 GPU-safe release 证明。

## Sodium shader入口

Sodium `DefaultChunkRenderer.render` 建立 generic `RenderPass`，`ShaderChunkRenderer` 创建 terrain shader pipeline；反编译证据位于 `/tmp/minecraftmetal-sodium-decomp/net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultChunkRenderer.java:48-131`、`ShaderChunkRenderer.java:25-79`。Metal backend 因此仍经过上面的 GLSL/SPIR-V/MSL/PSO 链，未发现 Sodium 独立 MSL/Metal shader compiler。

## 首轮 pipeline 分类数据库

下表是从当前 mapped/Sodium 源码可确认的类别，不声称是完整 runtime key 枚举。后续若需要完整数据库，应在 `MetalDevice.precompilePipeline`/`getOrCompilePipeline` 附近对 `RenderPipeline.getLocation()` 做日志枚举，或用 GPU capture 交叉确认。

| Pipeline 类别 | 调用位置 | 顶点格式 | 深度 | Blend/Discard | Instancing/Dynamic | 当前可输出 motion | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Sodium SOLID terrain | `DefaultTerrainRenderPasses.SOLID`、`DefaultMaterials.SOLID`；`/tmp/minecraftmetal-sodium-decomp/.../DefaultTerrainRenderPasses.java:5-9` | Sodium chunk vertex format，具体 attributes 在 terrain shader | depth enabled by terrain pass | no fragment discard; opaque | Sodium draw batches/indirect context | no MRT; no motion | 共享 main/terrain color |
| Sodium CUTOUT terrain | `DefaultTerrainRenderPasses.CUTOUT` | same terrain format | depth enabled | alpha cutoff 0.5 / discard | batch/indirect | no MRT; no object motion | alpha-cutout leaves/grass are here when material selects CUTOUT |
| Sodium TRANSLUCENT terrain | `DefaultTerrainRenderPasses.TRANSLUCENT` | same terrain format | depth enabled | translucent blend; alpha cutoff 0.01 | batch/indirect | no MRT; reactive target only when target bundle selected | target chosen by `TerrainRenderPass.getTarget()` |
| Minecraft entities/player/hand/item/block entity | Minecraft renderer feature pipelines, `GameRenderer.renderLevel`/feature dispatcher | runtime-specific vertex formats | normal scene depth | pipeline-specific | runtime-specific | no generic motion output observed | needs runtime enumeration |
| particles/weather/clouds | Minecraft renderer passes | runtime-specific | normal scene depth | often alpha/blend | runtime-specific | no generic motion output observed | some color goes to transparency targets |
| sky/world border/outline/glint/postprocess/GUI | Minecraft render graph/GUI/post chain | runtime-specific | per pass | per pipeline | runtime-specific | no generic motion output observed | no proof of a complete key list |

## shader modification risk

Adding a fragment motion output through regex MSL rewriting is not proven safe: SPIRV-Cross can change entry structs, resource bindings and output semantics; current entry extraction is regex-based. The current PSO does not configure attachment 1 for the built-in one-target pipelines, but the indexed setter path can configure it when the `RenderPipeline` target array contains that slot. A later implementation must choose a Java/Minecraft render-pipeline contract first, then carry it through reflection, MSL generation, PSO and encoder. This report makes no implementation change or recommendation beyond that boundary.
