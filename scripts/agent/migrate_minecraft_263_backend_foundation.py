#!/usr/bin/env python3
"""Migrate the Metal backend's resource/SPI foundation to Minecraft 26.3 RenderPearl.

Scope is deliberately narrow: package moves confirmed from exact 26.3 sources plus
conversion of RenderPearl resources that changed from abstract base classes to
interfaces. Shader compiler architecture and Sodium terrain ownership are left for
separate commits.
"""
from pathlib import Path

ROOTS = (Path("src/main/java"), Path("src/test/java"))

PACKAGE_REPLACEMENTS = {
    "com.mojang.renderpearl.api.commands.CommandEncoderBackend": "com.mojang.renderpearl.backend.api.CommandEncoderBackend",
    "com.mojang.renderpearl.api.commands.RenderPassBackend": "com.mojang.renderpearl.backend.api.RenderPassBackend",
    "com.mojang.renderpearl.api.commands.GpuSurfaceBackend": "com.mojang.renderpearl.backend.api.GpuSurfaceBackend",
    "com.mojang.blaze3d.systems.TransientMemory": "com.mojang.renderpearl.api.buffers.TransientMemory",
    "com.mojang.blaze3d.util.TransientBlockAllocator": "com.mojang.renderpearl.backend.util.TransientBlockAllocator",
    "com.mojang.blaze3d.platform.CompareOp": "com.mojang.renderpearl.api.pipeline.CompareOp",
    "com.mojang.blaze3d.platform.BlendFactor": "com.mojang.renderpearl.api.pipeline.BlendFactor",
    "com.mojang.blaze3d.platform.BlendOp": "com.mojang.renderpearl.api.pipeline.BlendOp",
    "com.mojang.blaze3d.shaders.UniformType": "com.mojang.renderpearl.api.pipeline.UniformType",
    "com.mojang.blaze3d.systems.GpuSurface": "com.mojang.renderpearl.api.device.GpuSurface",
    "com.mojang.blaze3d.systems.SurfaceException": "com.mojang.renderpearl.api.device.SurfaceException",
}


def replace_once(text: str, old: str, new: str, path: Path) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one occurrence of {old!r}, found {count}")
    return text.replace(old, new, 1)


def migrate_buffer(path: Path) -> None:
    text = path.read_text()
    text = replace_once(text, "public class MetalGpuBuffer extends GpuBuffer {", "public class MetalGpuBuffer implements GpuBuffer {", path)
    text = replace_once(
        text,
        "    private final MetalDevice device;\n",
        "    private final MetalDevice device;\n    private final int usage;\n    private final long size;\n",
        path,
    )
    text = text.replace("        super(usage, size);\n        this.device = device;", "        this.device = device;\n        this.usage = usage;\n        this.size = size;")
    if "super(usage, size);" in text:
        raise SystemExit(f"{path}: stale GpuBuffer superclass constructor remains")
    anchor = "    ByteBuffer sliceStorage(final long offset, final long length) {"
    insert = """    @Override
    public long size() {
        return this.size;
    }

    @Override
    public int usage() {
        return this.usage;
    }

"""
    text = replace_once(text, anchor, insert + anchor, path)
    path.write_text(text)


def migrate_texture(path: Path) -> None:
    text = path.read_text()
    text = replace_once(text, "final class MetalGpuTexture extends GpuTexture {", "final class MetalGpuTexture implements GpuTexture {", path)
    text = replace_once(
        text,
        "    private final MetalDevice device;\n",
        "    private final MetalDevice device;\n    private final int usage;\n    private final String label;\n    private final GpuFormat format;\n    private final int width;\n    private final int height;\n    private final int depthOrLayers;\n    private final int mipLevels;\n",
        path,
    )
    text = replace_once(
        text,
        "        super(usage, label, format, width, height, depthOrLayers, mipLevels);\n        this.device = device;",
        "        this.device = device;\n        this.usage = usage;\n        this.label = label;\n        this.format = format;\n        this.width = width;\n        this.height = height;\n        this.depthOrLayers = depthOrLayers;\n        this.mipLevels = mipLevels;",
        path,
    )
    anchor = "    int pixelSize() {"
    insert = """    @Override
    public int getWidth(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of range: " + mipLevel);
        }
        return Math.max(1, this.width >> mipLevel);
    }

    @Override
    public int getHeight(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of range: " + mipLevel);
        }
        return Math.max(1, this.height >> mipLevel);
    }

    @Override
    public int getDepthOrLayers() {
        return this.depthOrLayers;
    }

    @Override
    public int getMipLevels() {
        return this.mipLevels;
    }

    @Override
    public GpuFormat getFormat() {
        return this.format;
    }

    @Override
    public int usage() {
        return this.usage;
    }

    @Override
    public String getLabel() {
        return this.label;
    }

"""
    text = replace_once(text, anchor, insert + anchor, path)
    path.write_text(text)


def migrate_view(path: Path) -> None:
    text = path.read_text()
    text = replace_once(text, "final class MetalGpuTextureView extends GpuTextureView {", "final class MetalGpuTextureView implements GpuTextureView {", path)
    text = replace_once(
        text,
        "    private final boolean alphaOneSwizzle;\n",
        "    private final GpuTexture texture;\n    private final int baseMipLevel;\n    private final int mipLevels;\n    private final boolean alphaOneSwizzle;\n",
        path,
    )
    text = replace_once(
        text,
        "        super(texture, baseMipLevel, mipLevels);\n        this.alphaOneSwizzle = alphaOneSwizzle;",
        "        this.texture = texture;\n        this.baseMipLevel = baseMipLevel;\n        this.mipLevels = mipLevels;\n        this.alphaOneSwizzle = alphaOneSwizzle;",
        path,
    )
    anchor = "    MemorySegment nativeHandle() {"
    insert = """    @Override
    public GpuTexture texture() {
        return this.texture;
    }

    @Override
    public int baseMipLevel() {
        return this.baseMipLevel;
    }

    @Override
    public int mipLevels() {
        return this.mipLevels;
    }

    @Override
    public int getWidth(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of view range: " + mipLevel);
        }
        return this.texture.getWidth(this.baseMipLevel + mipLevel);
    }

    @Override
    public int getHeight(final int mipLevel) {
        if (mipLevel < 0 || mipLevel >= this.mipLevels) {
            throw new IllegalArgumentException("Mip level out of view range: " + mipLevel);
        }
        return this.texture.getHeight(this.baseMipLevel + mipLevel);
    }

"""
    text = replace_once(text, anchor, insert + anchor, path)
    path.write_text(text)


def migrate_sampler(path: Path) -> None:
    text = path.read_text()
    text = replace_once(text, "final class MetalGpuSampler extends GpuSampler {", "final class MetalGpuSampler implements GpuSampler {", path)
    text = replace_once(text, "    boolean isClosed() {", "    @Override\n    public boolean isClosed() {", path)
    path.write_text(text)


def main() -> None:
    changed = []
    for root in ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            text = path.read_text()
            updated = text
            for old, new in PACKAGE_REPLACEMENTS.items():
                updated = updated.replace(old, new)
            if updated != text:
                path.write_text(updated)
                changed.append(str(path))

    migrate_buffer(Path("src/main/java/com/metallum/client/metal/render/MetalGpuBuffer.java"))
    migrate_texture(Path("src/main/java/com/metallum/client/metal/render/MetalGpuTexture.java"))
    migrate_view(Path("src/main/java/com/metallum/client/metal/render/MetalGpuTextureView.java"))
    migrate_sampler(Path("src/main/java/com/metallum/client/metal/render/MetalGpuSampler.java"))

    print("Migrated backend foundation packages/resources")
    for path in sorted(set(changed)):
        print(path)


if __name__ == "__main__":
    main()
