#!/usr/bin/env python3
"""Apply only evidence-backed Minecraft 26.3 RenderPearl package migrations.

This intentionally does not touch old Vulkan shader implementation classes such as
GlslCompiler, IntermediaryShaderModule, or VulkanBindGroupLayout. Those APIs changed
architecturally in 26.3 and must be migrated against BackendRenderPipeline/SpvModule,
not papered over with guessed imports.
"""
from __future__ import annotations

from pathlib import Path

ROOTS = (Path("src/main/java"), Path("src/test/java"))

REPLACEMENTS = {
    "com.mojang.blaze3d.GpuFormat": "com.mojang.renderpearl.api.GpuFormat",
    "com.mojang.blaze3d.IndexType": "com.mojang.renderpearl.api.pipeline.IndexType",
    "com.mojang.blaze3d.PrimitiveTopology": "com.mojang.renderpearl.api.pipeline.PrimitiveTopology",
    "com.mojang.blaze3d.buffers.GpuBufferSlice": "com.mojang.renderpearl.api.buffers.GpuBufferSlice",
    "com.mojang.blaze3d.buffers.GpuBuffer": "com.mojang.renderpearl.api.buffers.GpuBuffer",
    "com.mojang.blaze3d.buffers.GpuFence": "com.mojang.renderpearl.api.commands.GpuFence",
    "com.mojang.blaze3d.pipeline.BindGroupLayout": "com.mojang.renderpearl.api.pipeline.BindGroupLayout",
    "com.mojang.blaze3d.pipeline.BlendFunction": "com.mojang.renderpearl.api.pipeline.BlendFunction",
    "com.mojang.blaze3d.pipeline.ColorTargetState": "com.mojang.renderpearl.api.pipeline.ColorTargetState",
    "com.mojang.blaze3d.pipeline.CompiledRenderPipeline": "com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline",
    "com.mojang.blaze3d.pipeline.DepthStencilState": "com.mojang.renderpearl.api.pipeline.DepthStencilState",
    "com.mojang.blaze3d.pipeline.RenderPipeline": "com.mojang.renderpearl.api.pipeline.RenderPipeline",
    "com.mojang.blaze3d.platform.PolygonMode": "com.mojang.renderpearl.api.pipeline.PolygonMode",
    "com.mojang.blaze3d.vertex.VertexFormatElement": "com.mojang.renderpearl.api.vertex.VertexFormatElement",
    "com.mojang.blaze3d.vertex.VertexFormat": "com.mojang.renderpearl.api.vertex.VertexFormat",
    "com.mojang.blaze3d.textures.AddressMode": "com.mojang.renderpearl.api.textures.AddressMode",
    "com.mojang.blaze3d.textures.FilterMode": "com.mojang.renderpearl.api.textures.FilterMode",
    "com.mojang.blaze3d.textures.GpuSampler": "com.mojang.renderpearl.api.textures.GpuSampler",
    "com.mojang.blaze3d.textures.GpuTextureView": "com.mojang.renderpearl.api.textures.GpuTextureView",
    "com.mojang.blaze3d.textures.GpuTexture": "com.mojang.renderpearl.api.textures.GpuTexture",
    "com.mojang.blaze3d.systems.RenderPassDescriptor": "com.mojang.renderpearl.api.commands.RenderPassDescriptor",
    "com.mojang.blaze3d.systems.RenderPass": "com.mojang.renderpearl.api.commands.RenderPass",
    "com.mojang.blaze3d.systems.CommandEncoder": "com.mojang.renderpearl.api.commands.CommandEncoder",
    "com.mojang.blaze3d.systems.GpuQueryPool": "com.mojang.renderpearl.api.commands.GpuQueryPool",
    "com.mojang.blaze3d.systems.GpuDeviceBackend": "com.mojang.renderpearl.backend.api.GpuDeviceBackend",
    "com.mojang.blaze3d.systems.CommandEncoderBackend": "com.mojang.renderpearl.backend.api.CommandEncoderBackend",
    "com.mojang.blaze3d.systems.RenderPassBackend": "com.mojang.renderpearl.backend.api.RenderPassBackend",
    "com.mojang.blaze3d.systems.GpuSurfaceBackend": "com.mojang.renderpearl.backend.api.GpuSurfaceBackend",
    "com.mojang.blaze3d.systems.DeviceInfo": "com.mojang.renderpearl.api.device.DeviceInfo",
    "com.mojang.blaze3d.systems.BackendCreationException": "com.mojang.renderpearl.api.device.BackendCreationException",
    "com.mojang.blaze3d.systems.GpuBackend": "com.mojang.renderpearl.api.device.GpuBackend",
    "com.mojang.blaze3d.systems.GpuDevice": "com.mojang.renderpearl.api.device.GpuDevice",
    "com.mojang.blaze3d.shaders.GpuDebugOptions": "com.mojang.renderpearl.api.device.GpuDebugOptions",
    "com.mojang.blaze3d.shaders.ShaderSource": "com.mojang.renderpearl.api.pipeline.ShaderSource",
    "com.mojang.blaze3d.shaders.ShaderType": "com.mojang.renderpearl.api.pipeline.ShaderType",
}


def main() -> None:
    files_changed = 0
    replacements = 0
    per_file: list[tuple[str, int]] = []

    for root in ROOTS:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*.java")):
            original = path.read_text(encoding="utf-8")
            updated = original
            count = 0
            for old, new in REPLACEMENTS.items():
                occurrences = updated.count(old)
                if occurrences:
                    updated = updated.replace(old, new)
                    count += occurrences
            if updated != original:
                path.write_text(updated, encoding="utf-8")
                files_changed += 1
                replacements += count
                per_file.append((str(path), count))

    if replacements == 0:
        raise SystemExit("no RenderPearl package migrations were applied")

    print(f"RenderPearl package migration: {replacements} replacements across {files_changed} files")
    for path, count in per_file:
        print(f"  {count:3d}  {path}")

    # Guard the scope: these implementation-private 26.2 classes require a real
    # architectural migration and must remain visible as compile failures.
    forbidden_guesses = (
        "com.mojang.renderpearl.backend.vulkan.glsl.GlslCompiler",
        "com.mojang.renderpearl.backend.vulkan.glsl.IntermediaryShaderModule",
        "com.mojang.renderpearl.backend.vulkan.VulkanBindGroupLayout",
    )
    for root in ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for guessed in forbidden_guesses:
                if guessed in text:
                    raise SystemExit(f"unsafe guessed 26.3 implementation import in {path}: {guessed}")


if __name__ == "__main__":
    main()
