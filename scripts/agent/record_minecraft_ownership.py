#!/usr/bin/env python3
"""Preserve exact-version renderer, MetalFX producer and workload reference files."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

NAMES = {"Minecraft.java", "Window.java", "RenderSystem.java", "Timer.java", "DeltaTracker.java",
         "FramerateLimitTracker.java", "SdlWindow.java", "SDLWindow.java",
         "PipelineBuilder.java", "SpvModule.java", "BindGroupLayout.java", "UniformType.java",
         "BackendRenderPipeline.java", "FrontendRenderPipeline.java", "ShaderCompiler.java",
         "ShaderSource.java", "RenderPipeline.java", "GpuBackend.java", "GlslCompiler.java",
         "SpvUtil.java", "SpvReflection.java", "ShadercSpvModule.java",
         "Blocks.java", "Entity.java", "TagCommand.java", "EntitySelectorOptions.java",
         # MetalFX consumes these upstream producers, not a second renderer model.
         "GameRenderer.java", "LevelRenderer.java", "RenderTarget.java", "MainTarget.java",
         "TextureTarget.java", "ProjectionMatrixBuffer.java", "Camera.java", "CameraRenderState.java",
         "LevelTargetBundle.java", "ChunkSectionLayer.java", "ChunkSectionsToRender.java",
         "SectionRenderDispatcher.java", "StagedVertexBuffer.java", "PreparedRenderType.java",
         "RenderType.java", "RenderTypes.java", "RenderPipelines.java", "RenderPipelineBuilder.java",
         "RenderPass.java", "RenderPassDescriptor.java", "FrontendRenderPass.java",
         "FrontendCommandEncoder.java", "CommandEncoder.java", "GpuFormat.java",
         "FeatureRenderDispatcher.java", "RenderTypeFeatureRenderer.java", "ModelFeatureRenderer.java",
         "QuadParticleFeatureRenderer.java", "QuadParticleRenderState.java", "CloudRenderer.java",
         "WeatherEffectRenderer.java", "FirstPersonHandsAndItemsRenderer.java", "GuiRenderer.java",
         "PostChain.java", "PostPass.java", "SpriteContents.java", "LightmapRenderState.java"}

REQUIRED_METALFX = {
    "com/mojang/blaze3d/pipeline/MainTarget.java",
    "com/mojang/blaze3d/pipeline/RenderTarget.java",
    "com/mojang/renderpearl/api/commands/RenderPassDescriptor.java",
    "com/mojang/renderpearl/frontend/FrontendRenderPass.java",
    "net/minecraft/client/renderer/GameRenderer.java",
    "net/minecraft/client/renderer/LevelRenderer.java",
    "net/minecraft/client/renderer/StagedVertexBuffer.java",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    source = args.sources.resolve(strict=True)
    args.output.mkdir(parents=True, exist_ok=True)
    info = source.parent / "REFERENCE_INFO.txt"
    shutil.copyfile(info, args.output / info.name)
    manifest = {"scope": "exact upstream reference, not project-owned code or runtime proof", "files": []}
    for path in sorted(source.rglob("*")):
        shader = path.is_file() and "assets/minecraft/shaders/" in path.as_posix()
        if not path.is_file() or (path.name not in NAMES and not shader):
            continue
        relative = path.relative_to(source)
        target = args.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        manifest["files"].append({"path": relative.as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    if not any(row["path"].endswith("net/minecraft/client/Minecraft.java") for row in manifest["files"]):
        raise SystemExit("Exact Minecraft client reference is missing")
    captured = {row["path"] for row in manifest["files"]}
    missing = REQUIRED_METALFX - captured
    if missing:
        raise SystemExit(f"MetalFX producer reference is incomplete: {sorted(missing)}")
    (args.output / "reference-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

if __name__ == "__main__":
    main()
