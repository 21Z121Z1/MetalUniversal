# MetalUniversal

[English](README.md)

MetalUniversal 是一个 Fabric 渲染后端。它保留 Minecraft Java 的可观察渲染语义，并在 Apple Silicon 上通过原生 Metal 执行 GPU 工作。代码包名和构建产物中也使用 Metallum 这个名称。

## 当前支持范围

| 范围 | 当前状态 |
| --- | --- |
| Minecraft | 26.3 是当前源码和构建目标。当前 JAR 不声明兼容 26.2。 |
| 平台 | Apple Silicon Mac 是主要产品目标。 |
| 渲染器 | Vanilla 使用公共渲染器。Sodium 和 Iris 是可选适配器。 |
| Metal | Metal 3 是受维护的回退路径。Metal 4 是实验性的能力专属执行路径。 |
| MetalFX | 时域缩放和帧生成是可选实验功能。 |
| 移动平台 | macOS 构建会编译 iOS 原生代码；启动器签名、打包和设备运行需要单独验证。 |

仓库不会把 Vulkan 或 MoltenVK 用作 Metal 执行运行时。SPIR-V 可继续作为生成 Metal Shading Language 前的着色器中间表示。

## 构建

要求：

- Java 25
- 构建原生模块时需要带 macOS 26 SDK 的 Xcode
- 构建发布用原生模块时需要 Apple Silicon Mac 或兼容的 macOS runner

构建 Java 和原生 macOS 产物：

```bash
./gradlew --no-daemon buildMacNative build \
  -x metalFrameGenerationPresentationValidation \
  -x metalFxOffscreenValidation
```

在没有 Apple 原生环境时运行 Java 和契约测试：

```bash
./gradlew --no-daemon compileJava test \
  -x buildMacNative -x buildIOSNative -x buildIOSSpvc
```

为 `gradle.properties` 中的 Minecraft 版本生成本地且被 Git 忽略的参考源码：

```bash
bash scripts/minecraft-reference.sh --print-path
```

## 架构

渲染器只有一条语义路径：

```text
Minecraft 26.3 与 RenderPearl 语义
  -> vanilla、Sodium 或 Iris 适配器
  -> 稳定的资源与 generation 身份
  -> 最小执行计划
  -> Java Metal 后端
  -> Java/FFM 原生 ABI
  -> Swift Metal 执行
  -> Apple Silicon GPU
```

Metal 4 使用额外能力降低同一个执行计划。MetalFX 将渲染器输出作为可选呈现功能的输入。两者都不建立第二套渲染器架构。

Terrain 工作以可见正确性和延迟为主要标准，而不只看构建吞吐量。发布过程必须拒绝过期 generation。相机移动影响可见性和优先级，但不会单独使正确几何失效。

## 验证

GitHub 托管检查可以对指定 commit 证明编译、单元与契约行为、ABI 形状、导出符号和部分托管 Metal 路径。它不能证明真实 GPU 行为、WindowServer 呈现、Minecraft 视觉一致性、稳定性能、温度表现、可变刷新时序或 MetalFX 画质。

使用 `bash scripts/agent/verify_unified_eval.sh` 执行受维护的静态证据契约。真实 Apple Silicon 结果必须记录源码 commit、JAR 与原生库身份、环境、场景、激活状态、正确性结果和测量值。

## 参与开发

先阅读 `AGENTS.md`，再检查行为所有者和最近的测试。不要从旧分支名、日期化报告或迁移计划推断当前架构。

`main` 是规范开发线。历史分支仅作为溯源；`research/modernization-backlog` 保存已退休提交历史。移动平台生命周期契约与 macOS 渲染器保持隔离。

## 许可证

见 `LICENSE`。
