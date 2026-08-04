# MobileGL source map

## Fixed source identity

- Repository: `MobileGL-Dev/MobileGL`
- Branch: `dev`
- Commit: `598c5497b06c57e1ab2586a560aaaf5cc957b772`
- Working tree at inspection: clean, all recursive submodules initialized
- License boundary: this document records behavior and architecture. No
  MobileGL implementation text is copied into MetalUniversal.

Relevant pinned shader/backend dependencies include:

- glslang: `900b29d449a67d2a18b569f64dd46333575b352f`
- SPIRV-Cross: `072444287f4e139c178d6d8fe32e04a0d2c34e8b`
- SPIRV-Reflect: `10b4f09a24d7ac1603071e767c089551dc6a3949`
- Vulkan-Headers: `ad9ce1235e88dc09287e19171dfac384db8ec32c`
- VulkanMemoryAllocator: `e722e57c891a8fbe3cc73ca56c19dd76be242759`

The Mac has no system Vulkan ICD configuration. The Minecraft LWJGL 3.4.1
artifact contains an arm64 `libMoltenVK.dylib` whose SHA-256 is
`33ffaf11e8d042fd078f1ca4daf44a1f75697f80c6f0ad35e3b10ac4994bee32`.
Its exported `vkGetVersionStringsMVK` reports MoltenVK 1.4.2 and Vulkan 1.4.334.
MobileGL must therefore direct-link this exact library for the V lane; it must
not silently use an unrecorded loader or ICD.

## API front end and validation

| Concern | MobileGL evidence | MetalUniversal adoption point |
| --- | --- | --- |
| API dispatch and context lookup | `MG_Impl/GLImpl/Exporting/Definitions.cpp`, `MG_Impl/GetProcAddress.cpp`, `MG_State/GLState/Core.{h,cpp}` | Blaze3D/Mixin entry points remain compatibility adapters; normalized render/compute/transfer operations must enter one Java semantic owner before FFM. |
| Buffer validation | `MG_Impl/GLImpl/Buffer/Validators.{h,cpp}`, `GL_Buffer.cpp` | Validate target, size, range, mapping and binding before touching Metal objects; packet validation is atomic. |
| Texture validation and proxy behavior | `MG_Impl/GLImpl/Texture/Validators.{h,cpp}`, `ProxyTexture.{h,cpp}`, `GL_Texture.cpp` | Preserve format, level, dimensions, pixel-store and copy legality. Unsupported translation fails with an observable reason. |
| FBO validation | `MG_Impl/GLImpl/Framebuffer/Validators.{h,cpp}`, `GL_Framebuffer.cpp` | Compile attachment identity and format compatibility into pass plans; do not infer attachment semantics in Swift. |
| VAO validation | `MG_Impl/GLImpl/VertexArray/Validators.{h,cpp}`, `GL_VertexArray.cpp` | Dense compiled vertex binding plan with index type/base vertex/restart represented per draw. |
| Sampler validation | `MG_Impl/GLImpl/Sampler/Validators.{h,cpp}`, `GL_Sampler.cpp` | Sampler state belongs to the API object and compiled resource layout, not texture-name heuristics. |
| Errors | `MG_State/GLState/ErrorState/*`, validators throughout `MG_Impl` | Compatibility error, unsupported semantic, packet ABI error and Metal execution failure remain distinct; no silent success or partial replay. |

MobileGL records API errors in its GL error state and keeps backend failures
separate. MetalUniversal does not expose a general interposed GL error queue,
because it implements the fixed Blaze3D/Iris/Sodium surface rather than a
drop-in libGL. The applicable rule is nevertheless retained: reject invalid
observable calls before mutation, and never turn a native failure into a
successful compatibility result.

## Canonical state and object model

| State/object | MobileGL evidence | Applicable design lesson |
| --- | --- | --- |
| Context aggregate/default state | `MG_State/GLState/Core.{h,cpp}` | One canonical context owner composes render, buffer, texture, sampler, program, VAO, FBO and renderbuffer state. Defaults are explicit. |
| Buffer names/backing/version | `BufferState/BufferState.*`, `BufferObject.*`, `PipeResource.h` | API object identity outlives replaceable backend backing. Backing replacement increments generation and old backing retires after GPU completion. |
| Textures and mip storage | `TextureState/TextureObject*`, `MipmapStorage.*`, `TextureUnit.*` | Texture unit bindings, object storage and per-mip contents are different state. Mip-chain growth submits pending consumers before backing replacement. |
| Samplers | `SamplerState/SamplerState.*`, `SamplerObject.*` | Sampler objects are independent from texture objects and can remain referenced after name deletion. |
| Programs/shaders | `ProgramState/ProgramState.*`, `ProgramObject.*`, `ShaderObject.*` | Attach/link/reflection state is API-visible; shader deletion is deferred while attached, and backend program versions are replaceable. |
| VAOs | `VertexArrayState/VertexArrayState.*`, `VertexArrayObject.*` | Element binding is VAO state; buffer references and vertex format changes invalidate compiled input state. |
| FBO/renderbuffer | `FramebufferState/*`, `RenderbufferState/*` | Attachments retain object references; deleting an attached texture detaches it as required by current MobileGL behavior and increments completeness/version state. |
| Render state | `RenderState/RenderState.*` | Blend, masks, depth/stencil, cull/front face, scissor/viewport and raster state are normalized independently from backend objects. |

The important lifecycle boundary is not the C++ container choice. It is the
distinction between API-visible object/name, immutable or versioned semantic
state, and replaceable backend allocation. MetalUniversal will express that
with generation-bearing Java objects and command-owned native handles; it will
not depend on Java GC or permanent native retention.

## Shader and program pipeline

| Stage | MobileGL evidence | MetalUniversal mapping |
| --- | --- | --- |
| GLSL front end | `MG_Util/ShaderTranspiler/ShaderCompiler.*`, `ShaderSourceProcessor.*`, `glslang/*` | Fixed Iris/Minecraft GLSL is validated and translated once per semantic program generation. |
| SPIR-V normalization | `SpirvPasses/*` | Adopt only transformations required by real fixed shader semantics; preserve explicit failure and add a conformance fixture for each transformation. |
| Reflection/resource layout | `SpvcSession.*`, `ProgramFactory.*`, `UniformManager.*` | Compile a typed resource layout and stage visibility into `MetalCompiledRenderPipeline`; runtime binding uses slot tokens, never per-draw reflection. |
| Pipeline identity | `PipelineFactory.*`, `VertexInputStateFactory.*`, `VertexInputStateBuilder.*` | Identity includes shaders, vertex layout, attachments, samples, blend/masks, depth/stencil, raster state, resource layout and Metal mode. |
| Compatibility transforms | `LowerDrawParametersPass`, `RebaseInstanceIndexPass`, `EmulateNoPerspectivePass`, `FlattenInterfaceStructPass` | Reimplement only where OpenGL/Iris observable semantics require it; no pack-name condition. |

## Backend execution, synchronization and frames

| Concern | MobileGL evidence | Native Metal interpretation |
| --- | --- | --- |
| Backend object isolation | `MG_Backend/DirectVulkan/BackendObject_DirectVulkan.*`, `DirectVulkan.*` | Swift receives validated typed commands/handles and must not rediscover GL state. |
| Ordered renderer | `Renderer/VulkanRenderer.*` | A single ordered representation covers draw, clear, transfer, barriers, queries and present. |
| Per-frame ownership | `Renderer/FrameContext.*`, `BufferArena.*`, `BufferSlice.h` | Three bounded in-flight slots, completion-driven reclamation, per-slot transient arenas, byte budgets. |
| Buffers | `VkBufferManager.*`, `VkBufferObject.*` | Backing replacement has generation, pending uses, and deferred destruction. |
| Textures | `VkTextureManager.*`, latest commit `598c5497` | Before mip backing recreation/preserve copy, submit pending frame writes. Metal replacement must preserve identical ordering. |
| Render passes/clear | `VkRenderPassManager.*`, `VkClearManager.*` | Pending clears are attachment/generation specific; clear helpers cannot leak encoder shadow state. |
| Programs/uniforms | `ProgramFactory.*`, `UniformManager.*` | Precompile layouts and batch updates; no name lookup or reflection on draw. |
| Samplers | `VkSamplerManager.*`, `VkTextureSamplerManager.cpp` | Cache immutable sampler state with bounded ownership and explicit resource pairing. |
| Query/timing | `VkTimerQueryManager.*` | Query availability and result synchronization are explicit; missing measurements are unavailable, never zero. |
| Presentation | `SwapchainObject.*` | Drawable/swapchain generation, defined contents and recreation are explicit; old images retire by completion serial. |
| Resource state | `DirectVulkanResourceState.h` | Track read/write intent and RAW/WAR/WAW across encoder/pass boundaries rather than relying on unified memory. |

Vulkan descriptor pools, VMA allocation calls, image layouts, swapchain
ownership transfers and MoltenVK workarounds are not copied. Their semantic
purposes map to Metal argument buffers/tables, storage modes/heaps, encoder and
fence/event ordering, drawable generations and explicit residency.

## Draw, transfer, query and synchronization entry points

- Draw and indexed draw behavior: `MG_Impl/GLImpl/Drawing/GL_Drawing.cpp`.
- Buffer upload/copy/map behavior: `MG_Impl/GLImpl/Buffer/GL_Buffer.cpp`.
- Texture upload/copy/mipmap behavior: `MG_Impl/GLImpl/Texture/GL_Texture.cpp`
  and `MG_Util/Texture/{PixelStoreProcessor,TextureFormatProcessor}.*`.
- Clear/blit/readback/FBO behavior:
  `MG_Impl/GLImpl/Framebuffer/GL_Framebuffer.cpp`.
- Query behavior: `MG_Impl/GLImpl/Query/GL_Query.cpp`.
- Fence/wait behavior: `MG_Impl/GLImpl/Sync/GL_Sync.cpp`.
- Presentation/context integration: `MG_Impl/{CGLImpl,NSOpenGLImpl,EGLImpl}`.

For every equivalent MetalUniversal command, the normalized operation records
inputs, outputs, dynamic state, pipeline/pass identity, resource generations,
ordering requirements and fail-closed preconditions. APIs absent from the
fixed Minecraft/Iris/Sodium call surface are documented as not observed, not
invented as unused abstractions.

## Compatibility/workaround classification

| Observed class | Classification | Adoption rule |
| --- | --- | --- |
| GL target/value/operation checks and deletion/reference rules | Real OpenGL semantics | Adopt for the fixed observable surface. |
| Minecraft/LWJGL function lookup, context and swap behavior | Application compatibility | Adopt only where the pinned client actually observes it; test via O/V/M differential runs. |
| Vulkan image layouts, descriptor pools, queue-family ownership | Vulkan-specific | Do not port; map the dependency/lifetime intent to Metal. |
| MoltenVK feature/format limitations | Vulkan-on-Metal limitation | Do not port to native Metal unless a separate Metal capability check proves the same restriction. |
| Latest pending-submit-before-mip-recreate fix | Cross-backend semantic hazard | Adopt the ordering invariant, not the Vulkan commands. |
| MobileGL stubs or unsupported paths | MobileGL implementation limit | Never treat as OpenGL permission to omit a fixed-client behavior. |
| Diligent/DirectGLES-only quirks | Different backend workaround | Do not port without an independently reproduced native Metal defect. |

## Tests and benchmarks used as design input

- Buffer semantics: `MG_Test/Buffer/BufferTest.cpp` and
  `MG_Benchmark/Buffer/BufferBench.cpp`.
- Texture and clear: `MG_Test/Texture/{TextureTest,VkClearManagerTest}.cpp`.
- Program/link/reflection: `MG_Test/Program/*` and
  `MG_Benchmark/Program/ProgramBench.cpp`.
- VAO and framebuffer: `MG_Test/VertexArray/*`, `MG_Test/Framebuffer/*`.
- Query/backend: `MG_Test/Query/*`, `MG_Test/Backend/DirectVulkan/*`.
- Shader lowering: `MG_Test/ShaderTranspiler/SpirvPassTest.cpp`.
- Pipeline compatibility: `MG_Test/Pipeline/PipelineQuirkTest.cpp`.

MetalUniversal ports the semantic cases into Java/native physical-GPU tests
where the fixed client uses them. Benchmark numbers are not compared across
test harnesses; only the same-Mac O/V/M Minecraft protocol is performance
evidence.
