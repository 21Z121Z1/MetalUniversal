#!/usr/bin/env python3
from pathlib import Path

repls = {
    "import com.mojang.blaze3d.systems.*;": "import com.mojang.renderpearl.api.commands.GpuQueryPool;\nimport com.mojang.renderpearl.api.device.DeviceInfo;\nimport com.mojang.renderpearl.backend.api.GpuDeviceBackend;\nimport com.mojang.renderpearl.backend.api.GpuSurfaceBackend;",
    "import com.mojang.blaze3d.textures.*;": "import com.mojang.renderpearl.api.textures.AddressMode;\nimport com.mojang.renderpearl.api.textures.FilterMode;\nimport com.mojang.renderpearl.api.textures.GpuSampler;\nimport com.mojang.renderpearl.api.textures.GpuTexture;\nimport com.mojang.renderpearl.api.textures.GpuTextureView;",
}
path = Path("src/main/java/com/metallum/client/metal/render/MetalDevice.java")
text = path.read_text()
for old, new in repls.items():
    if text.count(old) != 1:
        raise SystemExit(f"{path}: expected one {old}")
    text = text.replace(old, new)
path.write_text(text)

path = Path("src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java")
text = path.read_text()
old = "import com.mojang.blaze3d.systems.*;"
new = "import com.mojang.renderpearl.api.buffers.TransientMemory;\nimport com.mojang.renderpearl.api.commands.GpuQueryPool;\nimport com.mojang.renderpearl.api.commands.RenderPass;\nimport com.mojang.renderpearl.api.commands.RenderPassDescriptor;\nimport com.mojang.renderpearl.backend.api.CommandEncoderBackend;\nimport com.mojang.renderpearl.backend.api.RenderPassBackend;"
if text.count(old) != 1:
    raise SystemExit(f"{path}: expected one systems wildcard")
path.write_text(text.replace(old, new))

for p in Path("src/main/java").rglob("*.java"):
    text = p.read_text()
    updated = text.replace("com.mojang.blaze3d.opengl.GlBackend", "com.mojang.renderpearl.backend.opengl.GlBackend")
    updated = updated.replace("com.mojang.blaze3d.vulkan.VulkanBackend", "com.mojang.renderpearl.backend.vulkan.VulkanBackend")
    updated = updated.replace("com.mojang.blaze3d.opengl.GlStateManager", "com.mojang.renderpearl.backend.opengl.GlStateManager")
    if updated != text:
        p.write_text(updated)

path = Path("src/main/java/com/metallum/client/metal/render/MetalGpuSampler.java")
text = path.read_text()
marker = "    @Override\n    public boolean isClosed() {"
if marker not in text:
    raise SystemExit("MetalGpuSampler isClosed marker missing")
getters = '''    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }

'''
text = text.replace(marker, getters + marker, 1)
path.write_text(text)
print("Applied 26.3 import/interface cleanup")
