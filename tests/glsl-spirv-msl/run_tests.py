#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MetalUniversal — 严格 GLSL→SPIR-V→MSL 转换测试套件
====================================================

本测试套件严格验证 MetalUniversal 适配 Iris 的核心转换链路
GLSL → SPIR-V → MSL，并通过 GitHub Actions (test.yml) 持续运行。

被测对象
--------
MetalUniversal (分支 metal_iris-beta) 的着色器转换入口：

    MetalIrisBridge.compileIrisShader(name, glslSource, stage)
      └─ MetalIrisBridge.ensureSpirvCompatible(glsl)        # GLSL→GLSL 预处理
      └─ MetalCrossShaderCompiler.compileGlslToMsl(...)
            ├─ com.mojang.blaze3d.vulkan.glsl.GlslCompiler  # GLSL→SPIR-V
            └─ LWJGL spvc (SPIRV-Cross, MSL 后端)           # SPIR-V→MSL

为什么用 CLI 工具而非直接调 JVM
--------------------------------
MetalCrossShaderCompiler 依赖 Minecraft classpath (Fabric Loom) + LWJGL spvc
原生库，在 Linux CI 上完整复刻该 JVM 路径成本极高。本套件改用与 MetalUniversal
**完全等价的工具链与选项** 在 CLI 层面验证转换语义：

    glslangValidator -V -S <stage>        # GLSL→SPIR-V（Mojang GlslCompiler 即 glslang 的封装，target Vulkan SPIR-V）
    spirv-cross --msl <MetalUniversal 选项>  # SPIR-V→MSL（与 LWJGL spvc 同源同选项）

MetalUniversal 在 spirvToMsl() 中设置的 MSL 选项（MetalCrossShaderCompiler.java:388-407）：
    SPVC_COMPILER_OPTION_MSL_PLATFORM               = SPVC_MSL_PLATFORM_MACOS
    SPVC_COMPILER_OPTION_MSL_VERSION                = 31000      # MSL 3.1（major*10000+minor*1000+patch）
    SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING = false   # ★ 由 true 改为 false（修复 binding 碰撞，见下）
    SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE     = true
    SPVC_COMPILER_OPTION_FLIP_VERTEX_Y                  = true

对应 CLI 标志（spirv-cross）：
    --msl-texture-buffer-native
    --flip-vert-y
    平台 macOS 为默认（--msl-ios 才切换到 iOS）
    --msl-version 31000
    ★ 不传 --msl-decoration-binding：spirv-cross main.cpp:658 中 msl_decoration_binding 默认即为
      false，且 CLI 无 --msl-disable-decoration-binding 变体（main.cpp:1783 仅有 enable 形式），
      故直接省略该标志即可对应 enable_decoration_binding=false。

为何关闭 decoration-binding（回归修复）：
    Iris 的 push constants (PC) 与全局 UBO (u_Globals) 在 GLSL 中均声明 layout(binding=0)。
    若 enableDecorationBinding=true，SPIRV-Cross 会把两个 UBO 都映射到 [[buffer(0)]]，
    触发 Metal 编译错误 "cannot reserve 'buffer' resource location at index 0"。
    关闭后 SPIRV-Cross 按声明顺序自动分配 [[buffer(0)]] / [[buffer(1)]]（见用例 12_binding_collision_vert）。

MSL 版本说明：MetalUniversal 源码常量 MSL_VERSION_3_1 = 31000（MSL 3.1），
采用 SPIRV-Cross 标准编码 major*10000+minor*1000+patch。MSL 3.1 对应 macOS 14+
（Metal 3.0+），原生支持图像原子操作（imageAtomicAdd/Min/Max/Exchange 等），通过
metal::atomic_fetch_add_explicit 等 API 实现。MSL 3.0 下 SPIRV-Cross 对图像原子
操作会生成回退代码或失败，故升级至 3.1。本套件 CLI 使用 --msl-version 31000 与源码
一致（已验证 spirv-cross CLI 接受 31000）。
（历史注记：源码曾误用 0x040000=262144 的非标准编码，后修复为 30000，再升级为 31000。）

预处理移植
----------
MetalIrisBridge.ensureSpirvCompatible (MetalIrisBridge.java:320-328) 的三步：
  1. bumpVersionTo450          — 将 #version 提升到 450
  2. wrapLooseUniformsInUbo    — 把散装非不透明 uniform 收集进 layout(std140) uniform iris_LooseUniforms { ... };
  3. addLayoutLocationsToInOut — 为缺少 layout(location=N) 的 in/out 补上顺序 location
本套件用 Python `re` 忠实复刻这三步（见下方 ensure_spirv_compatible），保证测试
输入与 MetalUniversal 实际喂给 GlslCompiler 的 GLSL 完全一致。

=====================================================================
Iris 26.2 GLSL→SPIR-V→MSL 关键路径清单（严格参照源代码）
=====================================================================
（SubTask 2.5 产出；基于对 /workspace/iris-26.2 commit ff6f5aa1 的实地搜索）

核心结论：Iris 26.2 **自身不实施** GLSL→SPIR-V→MSL 转换。Iris 是纯 OpenGL 路径
（GLSL → glsl-transformer AST patch → glCompileShader 交驱动编译）。SPIR-V/MSL
转换由 MetalUniversal 在 Iris 之上叠加。下表为 Iris 各子系统入口与 MetalUniversal
对应实现的对照：

| 子系统               | Iris 26.2 入口（绝对路径）                                                                | MetalUniversal 对应                                                                 |
|----------------------|------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| GLSL 宏注入          | iris-26.2/common/.../gl/shader/StandardMacros.java:46-352 (MC_*/IRIS_*/IS_IRIS)         | （测试用例 GLSL 内联 IRIS 风格 iris_ 内建）                                          |
| #include 内联        | iris-26.2/common/.../shaderpack/include/IncludeProcessor.java:33-58                     | （测试 GLSL 已内联，无 #include）                                                    |
| JCPP 预处理          | iris-26.2/common/.../shaderpack/preprocessor/JcppProcessor.java                         | （测试 GLSL 已预处理，无 #ifdef）                                                    |
| GLSL AST patch       | iris-26.2/common/.../pipeline/transform/TransformPatcher.java:140-198                   | MetalIrisRenderingPipeline.patchAndCompile 调用 TransformPatcher.patchVanilla 等    |
|                      |   (VANILLA/COMPOSITE/DH_TERRAIN/DH_GENERIC/SODIUM/COMPUTE)                              |                                                                                     |
| LayoutTransformer    | iris-26.2/common/.../pipeline/transform/transformer/LayoutTransformer.java              | MetalIrisBridge.addLayoutLocationsToInOut（复刻其意图，因 VK_CONFORMANCE=false 不生效）|
| VK_CONFORMANCE 标志  | iris-26.2/common/.../gl/IrisLimits.java:14 (硬编码 false → LayoutTransformer 永不执行)   | N/A（MetalUniversal 自己补 layout(location)）                                       |
| OpenGL shader 编译   | iris-26.2/common/.../gl/shader/GlShader.java:31-33 (glCreateShader/glCompileShader)     | MetalCrossShaderCompiler 用 Mojang GlslCompiler（glslang）替代                      |
| attribute location   | iris-26.2/common/.../gl/shader/ProgramCreator.java:19-27 (Position=0,UV0=1,mc_Entity=11,mc_midTexCoord=12,at_tangent=13,at_midBlock=14) | 测试用例 08 验证                                                                     |
| sampler binding      | iris-26.2/common/.../gl/sampler/SamplerBinding.java (OpenGL texture unit)               | MetalCrossShaderCompiler SPIRV-Cross decoration-binding 映射                         |
| UBO binding          | iris-26.2/common/.../gl/IrisRenderSystem.java:361-368 (glUniformBlockBinding)           | MetalIrisBridge.wrapLooseUniformsInUbo 注入 iris_LooseUniforms UBO                  |
| fog uniform 注入     | iris-26.2/common/.../pipeline/transform/transformer/CommonTransformer.java:329-340       | 测试用例 03 验证 iris_FogColor/iris_FogDensity 等                                    |
| gl_FragData 改名     | iris-26.2/common/.../pipeline/transform/transformer/CommonTransformer.java:252,268,274  | 测试用例 02 验证 iris_FragData0 输出                                                |
| 程序组               | iris-26.2/common/.../shaderpack/loading/ProgramGroup.java (Gbuffers/Shadow/Composite/...) | MetalIrisRenderingPipeline.planForProgramId 映射                                    |
| 内建 compute shader  | iris-26.2/common/src/main/resources/colorSpace.csh (#version 430 core)                  | （MetalUniversal beta 仅支持 VERTEX/FRAGMENT，compute 暂不测）                       |
| 测试 fixture（已禁用）| iris-26.2/common/src/disabledTest/resources/shaderpacks/*/shaders/gbuffers_basic.{vsh,fsh} | 测试用例 01 参考 gbuffers_basic.vsh 的 #version 120 空桩结构                         |
| macOS 支持策略       | iris-26.2/docs/usage/drivers.md:33,48 (依赖第三方 openglonmetal/MGL，非自建 MSL)         | MetalUniversal 自建 MSL 路径，绕过 MGL                                              |

差异要点：
  1. Iris 不产 SPIR-V/MSL；MetalUniversal 叠加 glslang + SPIRV-Cross。
  2. Iris VK_CONFORMANCE=false 导致 LayoutTransformer 死代码；MetalUniversal 自己补 layout(location)。
  3. flip-vert-y 是 Metal 专属（OpenGL clip space y 约定不同），Iris 无需此选项。
  4. MetalUniversal spirvToMsl 未显式处理 SSBO（无 SPVC_RESOURCE_TYPE_STORAGE_BUFFER 反射），
     故本套件不包含 SSBO 正向用例，仅在 negative 用例中确认 SSBO 当前不被特殊处理。
=====================================================================
"""

from __future__ import annotations

import os
import re
import sys
import shutil
import subprocess
import tempfile
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Tuple


# ---------------------------------------------------------------------------
# 1. ensureSpirvCompatible —— MetalIrisBridge.java:320-328 的忠实 Python 移植
# ---------------------------------------------------------------------------

# MetalIrisBridge.java:257-259
_VERSION_LINE = re.compile(r"#version\s+\d+(?:\s+\w+)?", re.MULTILINE)

# MetalIrisBridge.java:276-280：匹配散装非不透明 uniform（排除 sampler/image 等）
_LOOSE_UNIFORM = re.compile(
    r"^\s*uniform\s+(?!sampler|image|subpass|atomicCounter|isampler|usampler|iimage|uimage)"
    r"(\w+)\s+(\w+)\s*(\[[^\]]*\])?\s*(?:=[^;]+)?\s*;",
    re.MULTILINE,
)

# MetalIrisBridge.java:620-623：已有 layout(location=N) ... in/out
_EXISTING_LAYOUT_LOCATION = re.compile(
    r"layout\s*\(\s*location\s*=\s*(\d+)\s*\)[^;]*?\b(in|out)\b", re.MULTILINE
)

# MetalIrisBridge.java:294-297：缺少 layout 的 in/out（含可选插值限定符）
_UNLOCATED_IN_OUT = re.compile(
    r"^\s*((?:(?:flat|smooth|noperspective|centroid|invariant|precise)\s+)*)(in|out)\s+(\w+)\s+([^;]+);",
    re.MULTILINE,
)

_ARRAY_SIZE = re.compile(r"\[(\d+)\]")


def _bump_version_to_450(glsl: str) -> str:
    """MetalIrisBridge.bumpVersionTo450 (Java:334-340)。"""
    m = _VERSION_LINE.search(glsl)
    if m:
        return glsl[: m.start()] + "#version 450" + glsl[m.end():]
    return "#version 450\n" + glsl


def _wrap_loose_uniforms_in_ubo(glsl: str) -> str:
    """MetalIrisBridge.wrapLooseUniformsInUbo (Java:357-402)。

    收集所有散装非不透明 uniform，移除，并在首个匹配位置注入
    layout(std140) uniform iris_LooseUniforms { ... };
    """
    members: List[str] = []
    positions: List[Tuple[int, int]] = []
    for m in _LOOSE_UNIFORM.finditer(glsl):
        type_ = m.group(1)
        name = m.group(2)
        array_spec = m.group(3)
        members.append(f"{type_} {name}{array_spec or ''};")
        positions.append((m.start(), m.end()))

    if not members:
        return glsl

    ubo = "layout(std140) uniform iris_LooseUniforms {\n"
    for mem in members:
        ubo += "    " + mem + "\n"
    ubo += "};\n"

    out = []
    last = 0
    for i, (start, end) in enumerate(positions):
        out.append(glsl[last:start])
        if i == 0:
            out.append(ubo)
        last = end
    out.append(glsl[last:])
    return "".join(out)


def _array_size(name_spec: str) -> int:
    """MetalIrisBridge.arraySize (Java:629-639)。"""
    m = _ARRAY_SIZE.search(name_spec)
    if m:
        try:
            return int(m.group(1))
        except ValueError:
            return 1
    return 1


def _add_layout_locations_to_in_out(glsl: str) -> str:
    """MetalIrisBridge.addLayoutLocationsToInOut (Java:666-727)。

    为缺少 layout 的 in/out 补顺序 location；处理多变量声明拆分与数组多 location 占用。
    """
    max_in = -1
    max_out = -1
    for m in _EXISTING_LAYOUT_LOCATION.finditer(glsl):
        loc = int(m.group(1))
        storage = m.group(2)
        if storage == "in":
            max_in = max(max_in, loc)
        else:
            max_out = max(max_out, loc)
    in_loc = max_in + 1
    out_loc = max_out + 1

    if not _UNLOCATED_IN_OUT.search(glsl):
        return glsl

    def repl(m: re.Match) -> str:
        full = m.group(0)
        if "layout" in full:
            return full
        qualifiers = m.group(1)
        storage = m.group(2)
        type_ = m.group(3)
        names = m.group(4).strip()
        parts = [n.strip() for n in names.split(",")]
        nonlocal in_loc, out_loc
        pieces = []
        for name in parts:
            loc = in_loc if storage == "in" else out_loc
            size = _array_size(name)
            if storage == "in":
                in_loc += size
            else:
                out_loc += size
            pieces.append(
                f"layout(location = {loc}) {qualifiers}{storage} {type_} {name};"
            )
        return "\n".join(pieces)

    return _UNLOCATED_IN_OUT.sub(repl, glsl)


def ensure_spirv_compatible(glsl: str) -> str:
    """MetalIrisBridge.ensureSpirvCompatible (Java:320-328)。"""
    if not glsl or not glsl.strip():
        return glsl
    glsl = _bump_version_to_450(glsl)
    glsl = _wrap_loose_uniforms_in_ubo(glsl)
    glsl = _add_layout_locations_to_in_out(glsl)
    return glsl


# ---------------------------------------------------------------------------
# 2. 转换管线：glslangValidator (GLSL→SPIR-V) + spirv-cross (SPIR-V→MSL)
# ---------------------------------------------------------------------------

# 与 MetalUniversal MetalCrossShaderCompiler.spirvToMsl 的 MSL 选项完全对应。
# 见文件头注释中的选项映射说明。
_SPIRV_CROSS_MSL_OPTS = [
    "--msl",
    "--msl-version", "31000",        # MSL 3.1（原生图像原子操作支持，对应 MetalUniversal MSL_VERSION_3_1=31000）
    "--msl-texture-buffer-native",    # SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE=true
    "--flip-vert-y",                  # SPVC_COMPILER_OPTION_FLIP_VERTEX_Y=true；macOS 默认平台
    # 注意：刻意不传 --msl-decoration-binding。对应 SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING=false
    # （spirv-cross main.cpp:658 默认值即 false）。原因：Iris 的 PC 与 u_Globals 两个 UBO 在 GLSL 中均
    # 声明 layout(binding=0)，启用 decoration-binding 会让两者都映射到 [[buffer(0)]] 造成 Metal
    # "cannot reserve 'buffer' resource location at index 0" 编译错误；关闭后 SPIRV-Cross 按声明顺序
    # 自动分配 [[buffer(0)]] / [[buffer(1)]]。回归用例见 12_binding_collision_vert / 13_binding_collision_frag。
]


class PipelineError(Exception):
    pass


def _run(cmd: List[str], input_bytes: bytes = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def glsl_to_spirv(glsl: str, stage: str, workdir: str) -> str:
    """glslangValidator -V -S <stage>，产出 Vulkan SPIR-V。返回 .spv 路径。

    使用 ``--auto-map-bindings`` 自动为缺少 ``layout(binding=N)`` 的 uniform /
    sampler / UBO 分配 binding 槽。这与 MetalUniversal 实际使用的 Mojang
    ``GlslCompiler``（com.mojang.blaze3d.vulkan.glsl.GlslCompiler）行为一致：
    该封装在 Vulkan SPIR-V 模式下会为没有显式 binding 的资源自动分配顺序 binding，
    因此 MetalIrisBridge 的预处理不需要补 binding。CLI 直接调用 glslangValidator
    必须显式开启该行为，否则会因 ``binding required`` 错误失败。
    """
    ext = "vert" if stage == "vert" else "frag"
    src = os.path.join(workdir, f"input.{ext}")
    spv = os.path.join(workdir, f"output.spv")
    with open(src, "w", encoding="utf-8") as f:
        f.write(glsl)
    glslang = shutil.which("glslangValidator") or "glslangValidator"
    cp = _run([glslang, "-V", "-S", stage, "--auto-map-bindings", src, "-o", spv])
    if cp.returncode != 0:
        raise PipelineError(
            f"glslangValidator 失败 (rc={cp.returncode}):\n"
            f"--- stdout ---\n{cp.stdout.decode('utf-8', 'replace')}\n"
            f"--- stderr ---\n{cp.stderr.decode('utf-8', 'replace')}"
        )
    if not os.path.exists(spv):
        raise PipelineError("glslangValidator 未输出 SPIR-V 文件")
    return spv


def spirv_to_msl(spv_path: str, workdir: str) -> str:
    """spirv-cross --msl <MetalUniversal 选项>，产出 MSL 源码字符串。"""
    spirv_cross = shutil.which("spirv-cross") or "spirv-cross"
    cp = _run([spirv_cross] + _SPIRV_CROSS_MSL_OPTS + [spv_path])
    if cp.returncode != 0:
        raise PipelineError(
            f"spirv-cross 失败 (rc={cp.returncode}):\n"
            f"--- stderr ---\n{cp.stderr.decode('utf-8', 'replace')}"
        )
    msl = cp.stdout.decode("utf-8", "replace")
    if not msl.strip():
        raise PipelineError("spirv-cross 输出空 MSL")
    return msl


def compile_glsl_to_msl(glsl: str, stage: str) -> str:
    """完整 GLSL→SPIR-V→MSL，返回 MSL 源码。"""
    with tempfile.TemporaryDirectory(prefix="mu-spirv-") as d:
        spv = glsl_to_spirv(glsl, stage, d)
        return spirv_to_msl(spv, d)


# ---------------------------------------------------------------------------
# 3. 测试用例定义
# ---------------------------------------------------------------------------


@dataclass
class TestCase:
    name: str
    stage: str                       # "vert" | "frag"
    glsl: str                        # Iris TransformPatcher 输出风格的 GLSL（#version 330, iris_ 内建）
    must_contain: List[str] = field(default_factory=list)   # MSL 中必须出现的子串
    must_not_contain: List[str] = field(default_factory=list)
    iris_ref: str = ""               # 追溯的 Iris 26.2 源代码位置
    desc: str = ""
    # 自定义断言：输入生成的 MSL，返回失败消息列表（空列表 = 通过）。用于 must_contain/must_not_contain
    # 无法表达的断言（如统计某子串出现次数）。见 _binding_collision_check。
    custom_check: Optional[Callable[[str], List[str]]] = None


def _binding_collision_check(msl: str) -> List[str]:
    """回归断言：两个 layout(binding=0) UBO（Iris 的 PC + u_Globals）在 MSL 中必须被分配到
    不同的 buffer 槽，不得出现 [[buffer(0)]] 重复。

    回归背景：当 SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING=true（CLI 传
    --msl-decoration-binding）时，SPIRV-Cross 会把两个 binding=0 的 UBO 都映射到
    [[buffer(0)]]，导致 Metal "cannot reserve 'buffer' resource location at index 0"。
    关闭后（默认 false）SPIRV-Cross 按声明顺序自动分配 [[buffer(0)]] / [[buffer(1)]]。
    """
    failures: List[str] = []
    buffer0_count = len(re.findall(r"\[\[buffer\(0\)\]\]", msl))
    buffer1_count = len(re.findall(r"\[\[buffer\(1\)\]\]", msl))
    # 主回归断言：[[buffer(0)]] 至多出现一次（碰撞 bug 下会出现两次）
    if buffer0_count > 1:
        failures.append(
            f"Binding 碰撞：[[buffer(0)]] 出现 {buffer0_count} 次（应 ≤ 1）"
        )
    # 强化断言：恰好一次（其中一个 UBO 拿到 buffer(0)）
    if buffer0_count != 1:
        failures.append(
            f"期望 [[buffer(0)]] 恰好出现 1 次，实际 {buffer0_count} 次"
        )
    # 第二个 UBO 应分到不同槽 buffer(1)
    if buffer1_count < 1:
        failures.append(
            f"期望 [[buffer(1)]] 至少出现 1 次（第二个 UBO 应分到不同槽），实际 {buffer1_count} 次"
        )
    return failures


CASES: List[TestCase] = [

    TestCase(
        name="01_basic_vertex",
        stage="vert",
        # Iris TransformPatcher 输出风格：#version 330 core
        # （iris-26.2 TransformPatcher.java:175 versionStatement.profile = Profile.CORE）
        glsl="""#version 330 core
in vec3 iris_Position;
in vec2 iris_UV0;
out vec2 v_uv;
void main() {
    v_uv = iris_UV0;
    gl_Position = vec4(iris_Position, 1.0);
}
""",
        must_contain=["vertex ", "[[stage_in]]", "[[position]]", "iris_Position"],
        iris_ref=(
            "iris-26.2/common/.../gl/shader/ProgramCreator.java:24-27 "
            "(glBindAttribLocation Position=0, UV0=1); "
            "gbuffers_basic.vsh fixture (#version 120 空桩)"
        ),
        desc="基础 gbuffers 顶点着色器：iris_Position/iris_UV0 输入 → gl_Position 输出",
    ),

    TestCase(
        name="02_basic_fragment",
        stage="frag",
        glsl="""#version 330 core
in vec2 v_uv;
out vec4 iris_FragData0;
uniform sampler2D iris_gbuffer;
void main() {
    iris_FragData0 = texture(iris_gbuffer, v_uv);
}
""",
        must_contain=["fragment ", "[[color(0)]]", "iris_FragData0"],
        iris_ref=(
            "iris-26.2/common/.../pipeline/transform/transformer/CommonTransformer.java:252,268,274 "
            "(gl_FragData[i] → iris_FragDatai)"
        ),
        desc="基础 gbuffers 片元着色器：iris_FragData0 输出（Iris gl_FragData 改名）",
    ),

    TestCase(
        name="03_loose_uniforms_ubo",
        stage="vert",
        glsl="""#version 330 core
in vec3 iris_Position;
out vec4 v_pos;
uniform mat4 iris_ModelViewMat;
uniform mat4 iris_ProjMat;
uniform vec3 iris_FogColor;
uniform float iris_FogDensity;
void main() {
    v_pos = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0);
    gl_Position = v_pos;
}
""",
        must_contain=[
            "iris_LooseUniforms",
            "[[buffer(",  # UBO 被映射到 buffer 槽
            "iris_ModelViewMat",
            "iris_FogColor",
        ],
        iris_ref=(
            "MetalIrisBridge.wrapLooseUniformsInUbo (MetalIrisBridge.java:357-402) "
            "注入 layout(std140) uniform iris_LooseUniforms; "
            "Iris fog uniforms: iris-26.2/common/.../transformer/CommonTransformer.java:329-340 "
            "(iris_FogDensity/iris_FogStart/iris_FogEnd/iris_FogColor/iris_FogParameters)"
        ),
        desc="散装 uniform 收集进 iris_LooseUniforms UBO（Iris 依赖 desktop GL 允许散装 uniform，MetalUniversal 包成 UBO）",
    ),

    TestCase(
        name="04_sampler_2d_binding",
        stage="frag",
        glsl="""#version 330 core
in vec2 v_uv;
out vec4 iris_FragData0;
uniform sampler2D colortex0;
uniform sampler2D colortex1;
void main() {
    vec4 a = texture(colortex0, v_uv);
    vec4 b = texture(colortex1, v_uv);
    iris_FragData0 = a + b;
}
""",
        must_contain=["[[texture(", "[[sampler(", "colortex0", "colortex1"],
        iris_ref=(
            "iris-26.2/common/.../gl/sampler/SamplerBinding.java (OpenGL texture unit); "
            "MetalCrossShaderCompiler.java:146-164 (SpvSampler 处理, SpvDim2D 校验)"
        ),
        desc="sampler2D 绑定映射（Iris texture unit → Metal [[texture(N)]]/[[sampler(N)]]）",
    ),

    TestCase(
        name="05_sampler_cube",
        stage="frag",
        glsl="""#version 330 core
in vec3 v_dir;
out vec4 iris_FragData0;
uniform samplerCube skybox;
void main() {
    iris_FragData0 = texture(skybox, v_dir);
}
""",
        must_contain=["[[texture(", "skybox", "fragment "],
        iris_ref=(
            "MetalCrossShaderCompiler.java:159 (dimensions != SpvDim2D && SpvDimCube → 抛错; "
            "SpvDimCube 允许)"
        ),
        desc="samplerCube 绑定（MetalCrossShaderCompiler 仅允许 SpvDim2D / SpvDimCube）",
    ),

    TestCase(
        name="06_in_out_interface",
        stage="vert",
        glsl="""#version 330 core
in vec3 iris_Position;
out vec3 v_normal;
out vec2 v_uv;
out vec4 v_color;
void main() {
    v_normal = vec3(0.0, 1.0, 0.0);
    v_uv = vec2(0.0);
    v_color = vec4(1.0);
    gl_Position = vec4(iris_Position, 1.0);
}
""",
        must_contain=[
            "vertex ",
            "[[user(locn0)]]",  # 第一个 out → location 0
            "[[user(locn1)]]",  # 第二个 out → location 1
            "v_normal",
            "v_uv",
            "v_color",
        ],
        iris_ref=(
            "MetalIrisBridge.addLayoutLocationsToInOut (MetalIrisBridge.java:666-727); "
            "Iris LayoutTransformer (因 VK_CONFORMANCE=false 永不执行，MetalUniversal 自补)"
        ),
        desc="顶点多个 out 接口变量补 layout(location) → MSL [[user(locnN)]]",
    ),

    TestCase(
        name="07_multiple_color_targets",
        stage="frag",
        glsl="""#version 330 core
in vec2 v_uv;
out vec4 colortex0;
out vec4 colortex1;
out vec4 colortex2;
out vec4 colortex3;
uniform sampler2D gcolor;
void main() {
    vec4 c = texture(gcolor, v_uv);
    colortex0 = c;
    colortex1 = c;
    colortex2 = c;
    colortex3 = c;
}
""",
        must_contain=[
            "fragment ",
            "[[color(0)]]",
            "[[color(1)]]",
            "[[color(2)]]",
            "[[color(3)]]",
        ],
        iris_ref=(
            "MetalIrisRenderingPipeline.COMPOSITE_MRT_COUNT=4 (MetalIrisRenderingPipeline.java:164); "
            "Iris ProgramGroup.Composite/Deferred (iris-26.2/common/.../shaderpack/loading/ProgramGroup.java)"
        ),
        desc="composite/deferred MRT 多色彩目标输出（4 路 colortex，对应 COMPOSITE_MRT_COUNT=4）",
    ),

    TestCase(
        name="08_attribute_locations",
        stage="vert",
        glsl="""#version 330 core
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
out vec2 v_uv;
void main() {
    v_uv = UV0;
    gl_Position = vec4(Position, 1.0);
}
""",
        must_contain=["[[attribute(0)]]", "[[attribute(1)]]", "Position", "UV0"],
        iris_ref=(
            "iris-26.2/common/.../gl/shader/ProgramCreator.java:19-27 "
            "(glBindAttribLocation: Position=0, UV0=1, mc_midTexCoord=12, at_tangent=13, at_midBlock=14)"
        ),
        desc="Iris 标准 attribute location（Position=0, UV0=1）→ Metal [[attribute(N)]]",
    ),

    TestCase(
        name="09_array_varying",
        stage="vert",
        glsl="""#version 330 core
in vec3 iris_Position;
out vec4 v_data[4];
void main() {
    for (int i = 0; i < 4; i++) v_data[i] = vec4(float(i));
    gl_Position = vec4(iris_Position, 1.0);
}
""",
        must_contain=["v_data", "vertex "],
        iris_ref=(
            "MetalIrisBridge.addLayoutLocationsToInOut (Java:709-715) 数组占多 location；"
            "MetalIrisBridge.arraySize (Java:629-639)"
        ),
        desc="数组 out 变量占多个连续 location（MetalIrisBridge 数组 location 推进逻辑）",
    ),

    TestCase(
        name="10_multiname_inout",
        stage="vert",
        glsl="""#version 330 core
in vec3 iris_Position;
out vec2 v_a, v_b;
void main() {
    v_a = vec2(0.0);
    v_b = vec2(1.0);
    gl_Position = vec4(iris_Position, 1.0);
}
""",
        must_contain=["v_a", "v_b", "vertex "],
        iris_ref=(
            "MetalIrisBridge.addLayoutLocationsToInOut (Java:704-722) 多变量声明拆分为独立 location"
        ),
        desc="多变量 out 声明拆分为各自独立 location（MetalIrisBridge 多名拆分逻辑）",
    ),

    TestCase(
        name="11_flat_interpolation",
        stage="frag",
        glsl="""#version 330 core
flat in int v_id;
in vec2 v_uv;
out vec4 iris_FragData0;
void main() {
    iris_FragData0 = vec4(float(v_id), vec3(v_uv, 0.0));
}
""",
        # MSL 没有 [[flat]] 属性：整型变量在 Metal 中默认即为 flat（无插值）。
        # 验证 v_id 作为 int 类型保留（int 隐式 flat），且经 SPIRV-Cross 正确映射到 [[user(locnN)]]。
        must_contain=["fragment ", "int v_id", "[[user(locn0)]]"],
        iris_ref=(
            "MetalIrisBridge UNLOCATED_IN_OUT 正则 (Java:294-297) 含 flat/smooth/noperspective 等"
            "插值限定符; MetalIrisBridge.addLayoutLocationsToInOut 保留 qualifiers (Java:716); "
            "MSL 整型隐式 flat（无 [[flat]] 属性）"
        ),
        desc="flat 插值限定符保留（MSL 中整型变量隐式 flat，验证 int v_id 正确映射）",
    ),

    TestCase(
        name="12_binding_collision_vert",
        stage="vert",
        # 两个 UBO 同 binding=0 —— 模拟 Iris 的 push constants (PC) + 全局 UBO (u_Globals)。
        # enableDecorationBinding=true 会产生两个 [[buffer(0)]]（碰撞）；=false 时
        # SPIRV-Cross 按声明顺序自动分配 [[buffer(0)]] / [[buffer(1)]]。
        # 与 tests/glsl-spirv-msl/cases/binding_collision.vert 内容一致。
        glsl="""#version 460 core

// Two UBOs at the SAME binding=0 but DIFFERENT descriptor sets.
// With enableDecorationBinding=true both map to [[buffer(0)]] (Metal compile error).
// With enableDecorationBinding=false SPIRV-Cross auto-assigns [[buffer(0)]] and [[buffer(1)]].
layout(set=0, binding=0) uniform PC {
    float pc_value;
};

layout(set=1, binding=0) uniform u_Globals {
    float global_value;
};

void main() {
    gl_Position = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
""",
        must_contain=["vertex ", "pc_value", "global_value", "[[buffer("],
        custom_check=_binding_collision_check,
        iris_ref=(
            "回归：Iris push constants (PC) + 全局 UBO (u_Globals) 在 GLSL 中均声明 layout(binding=0)；"
            "MetalCrossShaderCompiler.spirvToMsl (MetalCrossShaderCompiler.java:388-407) "
            "SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING 由 true 改为 false；"
            "对应 CLI 移除 --msl-decoration-binding（spirv-cross main.cpp:658 默认 false，"
            "main.cpp:1783 仅有 enable 形式，无 disable 变体）"
        ),
        desc="两个 UBO 同 binding=0 碰撞（顶点阶段）：关闭 decoration-binding 后自动分配不同 [[buffer(N)]]",
    ),

    TestCase(
        name="13_binding_collision_frag",
        stage="frag",
        # 同 12_binding_collision_vert 的片元阶段版本。
        # 与 tests/glsl-spirv-msl/cases/binding_collision.frag 内容一致。
        glsl="""#version 460 core

layout(location=0) out vec4 fragColor;

layout(set=0, binding=0) uniform PC {
    float pc_value;
};

layout(set=1, binding=0) uniform u_Globals {
    float global_value;
};

void main() {
    fragColor = vec4(pc_value + global_value, 0.0, 0.0, 1.0);
}
""",
        must_contain=["fragment ", "fragColor", "pc_value", "global_value", "[[buffer("],
        custom_check=_binding_collision_check,
        iris_ref=(
            "回归（片元阶段）：同 12_binding_collision_vert，验证 frag 阶段 PC + u_Globals "
            "同 binding=0 也能被分配到不同 [[buffer(N)]]"
        ),
        desc="两个 UBO 同 binding=0 碰撞（片元阶段）：同样自动分配不同 [[buffer(N)]]",
    ),
]


# ---------------------------------------------------------------------------
# 4. 断言与运行
# ---------------------------------------------------------------------------


def check_case(case: TestCase) -> Tuple[bool, str]:
    """运行单个用例：预处理 → GLSL→SPIR-V→MSL → 断言。返回 (是否通过, 报告)。"""
    report_lines = []
    report_lines.append(f"\n=== {case.name} ===")
    report_lines.append(f"  描述: {case.desc}")
    report_lines.append(f"  Iris 参照: {case.iris_ref}")

    # 步骤 1：ensureSpirvCompatible 预处理（与 MetalIrisBridge 一致）
    try:
        preprocessed = ensure_spirv_compatible(case.glsl)
    except Exception as e:
        report_lines.append(f"  [FAIL] ensureSpirvCompatible 预处理异常: {e}")
        return False, "\n".join(report_lines)
    if "#version 450" not in preprocessed.split("\n")[0]:
        report_lines.append(
            f"  [FAIL] 预处理后首行非 #version 450: {preprocessed.splitlines()[0]!r}"
        )
        return False, "\n".join(report_lines)
    report_lines.append("  [OK]   ensureSpirvCompatible: #version→450, UBO 包装, layout(location) 补全")

    # 步骤 2+3：GLSL→SPIR-V→MSL
    try:
        msl = compile_glsl_to_msl(preprocessed, case.stage)
    except PipelineError as e:
        report_lines.append(f"  [FAIL] 转换管线错误:\n{e}")
        return False, "\n".join(report_lines)
    except Exception as e:
        report_lines.append(f"  [FAIL] 未预期异常: {type(e).__name__}: {e}")
        return False, "\n".join(report_lines)
    report_lines.append(f"  [OK]   GLSL→SPIR-V→MSL 成功 (MSL {len(msl)} 字符)")

    # 步骤 4：断言必须包含
    failed = []
    for needle in case.must_contain:
        if needle not in msl:
            failed.append(f"必须包含 {needle!r} 但未找到")
    for bad in case.must_not_contain:
        if bad in msl:
            failed.append(f"必须不包含 {bad!r} 但存在")
    if case.custom_check is not None:
        failed.extend(case.custom_check(msl))

    if failed:
        for f in failed:
            report_lines.append(f"  [FAIL] 断言失败: {f}")
        # 输出 MSL 片段辅助诊断
        report_lines.append("  --- MSL 输出 (前 1200 字符) ---")
        report_lines.append(msl[:1200])
        return False, "\n".join(report_lines)

    report_lines.append(f"  [OK]   断言通过 (must_contain={len(case.must_contain)}, must_not={len(case.must_not_contain)})")
    return True, "\n".join(report_lines)


def main() -> int:
    print("MetalUniversal GLSL→SPIR-V→MSL 严格测试套件")
    print(f"工具: glslangValidator={shutil.which('glslangValidator')}, "
          f"spirv-cross={shutil.which('spirv-cross')}")
    print(f"用例数: {len(CASES)}")
    print("=" * 70)

    # 工具存在性校验
    if not shutil.which("glslangValidator"):
        print("FATAL: glslangValidator 未安装")
        return 2
    if not shutil.which("spirv-cross"):
        print("FATAL: spirv-cross 未安装")
        return 2

    passed = 0
    failed = 0
    failures: List[str] = []
    for case in CASES:
        ok, report = check_case(case)
        print(report)
        if ok:
            passed += 1
        else:
            failed += 1
            failures.append(case.name)

    print("=" * 70)
    print(f"结果: {passed} 通过, {failed} 失败, 共 {len(CASES)} 用例")
    if failures:
        print("失败用例: " + ", ".join(failures))
        return 1
    print("全部通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
